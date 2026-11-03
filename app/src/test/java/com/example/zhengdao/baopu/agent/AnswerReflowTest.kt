// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百九十六批（补问答案回灌通道）· 内核单测（纯 JVM，零 Android、零时钟——时刻全由用例递进来）。
//
// 判据对应（派工单 2026-11-03 §3）：
//   ① AUTH ⇒ 原动作可执行且落处置账 ／ INFO ⇒ 只补信息（不落账、不动可执行性）／
//      REJECT ⇒ 取消并落账（三档各有测试，外加一条不对称：只有 AUTH 与 REJECT 写得进处置账）；
//   ② 挂错轮次 ⇒ 拒（答复带的 turnId 与登记那一刻不符）；
//   ③ AUTH 无范围 ⇒ 拒（认不出的范围键／过去的到期时刻／没有任务号，四种「等于无限」都拒）；
//   ④ 回灌后历史**只追加**（补丁有寿命：到达轮＋下一轮；同轮重复取用逐字相同；处置账只增不改）。
//
// 引擎侧接线（轮号来源、检查点自发回灌、不写历史）不在这里测——RunEngine 在 JVM 里起不来
// （Android SQLite），照本仓门禁范式由 ReflowWiringGateTest 用源码形状钉。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.AssembledPrompt
import com.example.zhengdao.baopu.CardDisposition
import com.example.zhengdao.baopu.ContextAssembler
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.HardFacts
import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.SegmentKind
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnswerReflowTest {

    private val t0 = 1_700_000_000_000L
    private val task = "session:7"

    /** 落点自己装一棵临时目录：处置账要能验到盘上那一行，不能只验内存。 */
    private lateinit var ledgerRoot: File

    @Before
    fun setup() {
        AnswerReflow.resetForTest()
        CardDisposition.resetForTest()
        ledgerRoot = Files.createTempDirectory("baopu-396-ledger").toFile()
        DecisionLedger.install(ledgerRoot)
    }

    @After
    fun teardown() {
        AnswerReflow.resetForTest()
        CardDisposition.resetForTest()
        DecisionLedger.uninstallForTest()
        runCatching { ledgerRoot.deleteRecursively() }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 一张被免打扰压下的卡（379 的 DeferredAsk 原样；headline 从 request 现算）。 */
    private fun item(id: String, title: String) = DeferredAsk(
        id = id,
        atMs = t0,
        dueMs = t0 + QuietLimits.MAX_MS,
        why = "免打扰授权中",
        checkpoint = false,
        request = AskUserRequest(
            question = title,
            options = listOf(AskUserOption("a", "就这么办"), AskUserOption("b", "先别")),
        ),
    )

    /** 登记（＝第 [round] 轮压下的）并回那条待答记录。 */
    private fun defer(id: String, title: String = "删哪些旧稿？", round: Int = 3) =
        AnswerReflow.register(item(id, title), round)

    private fun answer(
        askId: String,
        kind: ReflowKind,
        turnId: Int = 3,
        scope: String = "",
        untilMs: Long = 0L,
        text: String = "都留着",
    ) = ReflowAnswer(askId = askId, turnId = turnId, kind = kind, scopeWire = scope, untilMs = untilMs, text = text)

    private fun submit(
        answer: ReflowAnswer,
        round: Int = 4,
        taskId: String = task,
        nowMs: Long = t0 + QuietLimits.MINUTE_MS,
    ): ReflowOutcome = AnswerReflow.submit(answer, round, taskId, nowMs)

    private fun accepted(outcome: ReflowOutcome): ReflowOutcome.Accepted {
        assertTrue("该收下，却拒了：${(outcome as? ReflowOutcome.Rejected)?.reason}", outcome is ReflowOutcome.Accepted)
        return outcome as ReflowOutcome.Accepted
    }

    private fun rejected(outcome: ReflowOutcome): ReflowOutcome.Rejected {
        assertTrue("该拒收，却收下了：$outcome", outcome is ReflowOutcome.Rejected)
        return outcome as ReflowOutcome.Rejected
    }

    /** 处置账里指向这张卡的那些行（按落点对号，不数全表——别的批也往这文件写）。 */
    private fun rowsFor(askId: String) = CardDisposition.rowsFor(askId)

    // ── 判据①：三档各判各的，各有测试 ─────────────────────────────────────

    @Test
    fun `判据①_AUTH_原动作可执行且落处置账`() {
        val ask = defer("quiet:1000#1")
        val got = accepted(submit(answer(ask.askId, ReflowKind.AUTH, scope = ReflowScope.ONCE.wire)))
        assertEquals("授权落成「批」", CardDisposition.Choice.APPROVE, got.dispositionRow?.choice)
        assertEquals("落点＝这张卡的主键（哪张卡批了要查得到）", ask.askId, got.dispositionRow?.cardId)
        assertEquals("补问卡这一格是 396 加的", CardDisposition.Face.REFLOW, got.dispositionRow?.face)
        assertEquals("谁表态：用户", CardDisposition.Actor.USER, got.dispositionRow?.actor)
        assertEquals("盘上真有一行", 1, rowsFor(ask.askId).size)
        assertTrue("可执行性被 AUTH 改动（判据①本体）", AnswerReflow.executable(ask.askId, task, t0))
        assertNotNull("授权对象在册", AnswerReflow.authorization(ask.askId))
        assertTrue("账里要写清范围（「她批了」不带范围＝她批了所有）",
            rowsFor(ask.askId).single().why.contains("范围=一次"))
    }

    @Test
    fun `判据①_INFO_只补信息不落账也不动可执行性`() {
        val ask = defer("quiet:1001#1")
        val got = accepted(submit(answer(ask.askId, ReflowKind.INFO, text = "只删 3 月以前的")))
        assertNull("她没对动作表态 ⇒ 处置账没有这一行（记成批或驳都是造假）", got.dispositionRow)
        assertEquals("盘上一行都不许多", 0, rowsFor(ask.askId).size)
        assertFalse("INFO 不改可执行性（判据①的不对称）", AnswerReflow.executable(ask.askId, task, t0))
        assertNull("INFO 造不出授权", AnswerReflow.authorization(ask.askId))
        assertTrue("补丁仍要带她的原话：${got.note}", got.note.contains("只删 3 月以前的"))
        assertTrue("补丁要如实说动作没变：${got.note}", got.note.contains("可执行性没变"))
    }

    @Test
    fun `判据①_REJECT_取消并落账`() {
        val ask = defer("quiet:1002#1")
        val got = accepted(submit(answer(ask.askId, ReflowKind.REJECT, text = "不用了")))
        assertEquals("取消落成「驳」", CardDisposition.Choice.REJECT, got.dispositionRow?.choice)
        assertEquals("盘上真有一行", 1, rowsFor(ask.askId).size)
        assertFalse("动作作废 ⇒ 不可执行", AnswerReflow.executable(ask.askId, task, t0))
        assertNull("取消不造授权", AnswerReflow.authorization(ask.askId))
        assertTrue("补丁要说别再推进：${got.note}", got.note.contains("别再推进"))
    }

    @Test
    fun `判据①_AUTH 的补丁不许读成自动重试`() {
        // 派工单 §4：不做自动重试原动作（重试是另一次决策）。补丁是唯一能影响模型的口子，
        // 所以它必须自己把这句话说明白——这条断言钉的是文案，不是行为。
        val ask = defer("quiet:1003#1")
        val got = accepted(submit(answer(ask.askId, ReflowKind.AUTH, scope = ReflowScope.TASK.wire)))
        assertTrue("补丁要点名「不是让你自动重试」：${got.note}", got.note.contains("不是让你自动重试"))
        assertTrue("补丁要带是第几轮收的、哪一轮问的：${got.note}",
            got.note.contains("第 4 轮收") && got.note.contains("第 3 轮那问"))
    }

    // ── 判据②：挂错轮次 ⇒ 拒 ──────────────────────────────────────────────

    @Test
    fun `判据②_答复挂错轮次_拒`() {
        val ask = defer("quiet:1004#1", round = 3)
        val bad = rejected(submit(answer(ask.askId, ReflowKind.AUTH, turnId = 9, scope = ReflowScope.ONCE.wire)))
        assertTrue("拒因要点名轮次不符：${bad.reason}", bad.reason.contains("挂错轮次"))
        assertTrue("两个轮号都要写出来（第几轮问的 vs 按第几轮回灌）：${bad.reason}",
            bad.reason.contains("第 3 轮") && bad.reason.contains("第 9 轮"))
        assertFalse("拒了就不许留下授权", AnswerReflow.executable(ask.askId, task, t0))
        assertEquals("拒了就不许落账", 0, rowsFor(ask.askId).size)
        assertEquals("待答清单不动（还能再答一次，答对了才灌）", 1, AnswerReflow.pendingSnapshot().size)
    }

    @Test
    fun `判据②_没带轮次_或者问根本没登记过_都拒`() {
        val ask = defer("quiet:1005#1")
        assertTrue("轮号 0 ＝ 不在任何轮里", rejected(submit(answer(ask.askId, ReflowKind.INFO, turnId = 0))).reason
            .contains("没带轮次"))
        assertTrue("没登记过的问题不许凭空回灌",
            rejected(submit(answer("quiet:9999#9", ReflowKind.INFO))).reason.contains("不在待答清单里"))
        assertTrue("连是哪一问都没写", rejected(submit(answer("", ReflowKind.INFO))).reason.contains("没带是哪一问"))
    }

    @Test
    fun `判据②_同一个问只吃一次答复_第二张不许追补`() {
        val ask = defer("quiet:1006#1")
        val first = accepted(submit(answer(ask.askId, ReflowKind.INFO, text = "第一答")))
        val second = rejected(submit(answer(ask.askId, ReflowKind.INFO, text = "第二答")))
        assertTrue("拒因要说「同一问不重复灌」：${second.reason}", second.reason.contains("同一问不重复灌"))
        val still = AnswerReflow.notes(4).single()
        assertEquals("已灌的那条一个字没被改写（改写＝改历史）", first.note, still)
        assertFalse("第二答没进上下文", still.contains("第二答"))
        assertEquals("待答清单摘牌", 0, AnswerReflow.pendingSnapshot().size)
    }

    // ── 判据③：AUTH 无范围 ⇒ 拒（四种「等于无限」都拒） ─────────────────────

    @Test
    fun `判据③_AUTH 缺范围_拒_不兜底成一次`() {
        val ask = defer("quiet:1007#1")
        val bad = rejected(submit(answer(ask.askId, ReflowKind.AUTH, scope = "")))
        assertTrue("拒因要点名缺范围：${bad.reason}", bad.reason.contains("AUTH 缺授权范围"))
        assertFalse("缺范围的授权不许悄悄生效（兜底成「一次」＝替她签收）",
            AnswerReflow.executable(ask.askId, task, t0))
        assertEquals("拒了不落账", 0, rowsFor(ask.askId).size)
    }

    @Test
    fun `判据③_范围认不出_同样拒（不静默猜一档）`() {
        val ask = defer("quiet:1008#1")
        assertTrue("自造的范围键不进账", rejected(submit(answer(ask.askId, ReflowKind.AUTH, scope = "always"))).reason
            .contains("AUTH 缺授权范围"))
        assertNull("三档闭集之外没有入口", ReflowScope.ofWire("always"))
        assertEquals("一次／本任务／到某时刻，三档", 3, ReflowScope.entries.size)
        assertEquals("AUTH／INFO／REJECT，三档", 3, ReflowKind.entries.size)
    }

    @Test
    fun `判据③_到某时刻没给将来那一刻_等于无限_拒`() {
        val ask = defer("quiet:1009#1")
        val now = t0 + QuietLimits.MINUTE_MS
        val bad = rejected(submit(answer(ask.askId, ReflowKind.AUTH, scope = ReflowScope.UNTIL.wire, untilMs = now - 1), nowMs = now))
        assertTrue("拒因要说等于无限：${bad.reason}", bad.reason.contains("等于无限"))
        // 给了将来那一刻就收，并且到点自己失效（[ReflowScope] 的失效边界）
        val ask2 = defer("quiet:1010#1")
        val until = now + 60 * QuietLimits.MINUTE_MS
        accepted(submit(answer(ask2.askId, ReflowKind.AUTH, scope = ReflowScope.UNTIL.wire, untilMs = until), nowMs = now))
        assertTrue("没到点可执行", AnswerReflow.executable(ask2.askId, task, until - 1))
        assertFalse("到点即失效", AnswerReflow.executable(ask2.askId, task, until))
    }

    @Test
    fun `判据③_本任务却没任务号_等于无限_拒`() {
        val ask = defer("quiet:1011#1")
        assertTrue("任务号空白＝不知道范围多大",
            rejected(submit(answer(ask.askId, ReflowKind.AUTH, scope = ReflowScope.TASK.wire), taskId = "")).reason
                .contains("等于无限"))
        // 有任务号才收，且换任务即失效
        val ask2 = defer("quiet:1012#1")
        accepted(submit(answer(ask2.askId, ReflowKind.AUTH, scope = ReflowScope.TASK.wire)))
        assertTrue("同任务内可执行", AnswerReflow.executable(ask2.askId, task, t0))
        assertFalse("任务换了就不可执行（本任务 ≠ 所有任务）", AnswerReflow.executable(ask2.askId, "session:8", t0))
    }

    @Test
    fun `判据③_非授权不许夹带范围（反方向也钉住）`() {
        val ask = defer("quiet:1013#1")
        val bad = rejected(submit(answer(ask.askId, ReflowKind.INFO, scope = ReflowScope.ONCE.wire)))
        assertTrue("补信息夹带范围＝形状错了：${bad.reason}", bad.reason.contains("不许带授权范围"))
        val ask2 = defer("quiet:1014#1", title = "要合并吗？")
        assertTrue("取消夹带范围同样拒",
            rejected(submit(answer(ask2.askId, ReflowKind.REJECT, scope = ReflowScope.TASK.wire))).reason
                .contains("不许带授权范围"))
    }

    @Test
    fun `一次授权取用即废_其余两档只验不扣`() {
        val once = defer("quiet:1015#1", title = "第一件")
        accepted(submit(answer(once.askId, ReflowKind.AUTH, scope = ReflowScope.ONCE.wire)))
        assertTrue("用之前可执行", AnswerReflow.executable(once.askId, task, t0))
        assertTrue("验一次不扣（executable 是无副作用的读）", AnswerReflow.executable(once.askId, task, t0))
        assertTrue("取用成功", AnswerReflow.useAuthorization(once.askId, task, t0))
        assertFalse("一次＝用过即废", AnswerReflow.executable(once.askId, task, t0))
        assertFalse("再取就取不到", AnswerReflow.useAuthorization(once.askId, task, t0))
        val until = defer("quiet:1016#1", title = "第二件")
        val deadline = t0 + QuietLimits.MAX_MS
        accepted(submit(answer(until.askId, ReflowKind.AUTH, scope = ReflowScope.UNTIL.wire, untilMs = deadline), nowMs = t0))
        assertTrue("到某时刻可以反复验", AnswerReflow.useAuthorization(until.askId, task, t0 + 1))
        assertTrue("取用过仍可执行（扣的是次数，未到点的档不扣）",
            AnswerReflow.executable(until.askId, task, t0 + 1))
    }

    // ── 判据④：回灌只追加、有寿命、不动历史 ───────────────────────────────

    @Test
    fun `判据④_补丁只活「到达轮＋下一轮」_到点自己退场`() {
        val ask = defer("quiet:1017#1")
        accepted(submit(answer(ask.askId, ReflowKind.INFO), round = 4))
        assertEquals("到达轮带 1 条", 1, AnswerReflow.notes(4).size)
        assertEquals("下一轮还带（她刚答的，模型正该看见）", 1, AnswerReflow.notes(5).size)
        assertEquals("再下一轮就退场——不往历史里反复塞同一句话", 0, AnswerReflow.notes(6).size)
        assertEquals("寿命写死成一个数，别处不许各写一把", 2, AnswerReflow.DELIVERY_ROUNDS)
    }

    @Test
    fun `判据④_同轮重复取用逐字相同_不消费不乱序`() {
        // 拼提示词每一步都会重算 extraNotes；中途换批等于打破前缀（与 22 批影响提示同一口径）。
        defer("quiet:1018#1", title = "第一件")
        defer("quiet:1019#1", title = "第二件")
        submit(answer("quiet:1018#1", ReflowKind.INFO), round = 4)
        submit(answer("quiet:1019#1", ReflowKind.INFO), round = 4)
        val first = AnswerReflow.notes(4)
        assertEquals(2, first.size)
        assertTrue("先收的那条在前：" + first[0], first[0].contains("第一件"))
        assertTrue("后收的跟在后面：" + first[1], first[1].contains("第二件"))
        assertEquals("重复调用逐字相同（前缀判据）", first, AnswerReflow.notes(4))
        assertEquals("顺序＝收下的先后，不随取用打乱", first, AnswerReflow.notes(5))
        assertEquals("两条一起退场（寿命从**到达轮**起算，不从问的那一轮起算）", 0, AnswerReflow.notes(6).size)
    }

    @Test
    fun `判据④_答得越晚补丁的出身仍写清楚_收答轮与问轮分开记`() {
        val ask = defer("quiet:1020#1", title = "删稿还是合并？", round = 3)
        val got = accepted(submit(answer(ask.askId, ReflowKind.INFO), round = 11))
        assertTrue("补丁要点名「第 11 轮收」：${got.note}", got.note.contains("第 11 轮收"))
        assertTrue("出身要点名「第 3 轮那问」：${got.note}", got.note.contains("第 3 轮那问"))
        assertTrue("卡面那一问也要在：${got.note}", got.note.contains("删稿还是合并？"))
    }

    @Test
    fun `判据④_处置账只增不改_后一条不许动前一条的字节`() {
        val a = defer("quiet:1021#1", title = "第一件")
        val b = defer("quiet:1022#1", title = "第二件")
        submit(answer(a.askId, ReflowKind.AUTH, scope = ReflowScope.ONCE.wire))
        val file = CardDisposition.fileIn(ledgerRoot)
        val before = file.readBytes()
        submit(answer(b.askId, ReflowKind.REJECT))
        val after = file.readBytes()
        assertTrue("只许变长", after.size > before.size)
        assertTrue("前面那行字节逐字不动（改行＝改历史）",
            after.copyOfRange(0, before.size).contentEquals(before))
        assertEquals(2, CardDisposition.replay().size)
    }

    @Test
    fun `判据④_通道不写会话历史_只写内存补丁与处置账`() {
        // 这条测的是「回灌走了 B 形态」的机械后果：收下一条答复之后，
        // 消息账没多一行、内存队列只多一条补丁——历史是模型那一轮的账，本通道不许碰。
        val ask = defer("quiet:1023#1")
        val file = CardDisposition.fileIn(ledgerRoot)
        val ledgerBefore = if (file.isFile) file.readLines().size else 0
        accepted(submit(answer(ask.askId, ReflowKind.AUTH, scope = ReflowScope.ONCE.wire)))
        assertEquals("处置账只多这一行", ledgerBefore + 1, file.readLines().size)
        assertEquals("补丁队列 1 条", 1, AnswerReflow.notes(4).size)
        assertEquals("已收 1 条（其中授权 1）", 1, AnswerReflow.receivedSnapshot().size)
        assertEquals(ReflowKind.AUTH, AnswerReflow.receivedSnapshot().single().kind)
        assertEquals("登记的轮号是问的那一刻（不是答的那一刻）", 3, AnswerReflow.turnOf(ask.askId))
    }

    @Test
    fun `判据④_补丁只追加在本轮输入末尾_历史段逐字节不动`() {
        // 「历史只追加（前缀判据＋行数不减）」在组装层的直测：B 形态不开新段、不碰 HISTORY 段。
        val history = listOf(
            HistoryMessage(id = "h1", role = HistoryMessage.ROLE_USER, text = "第一条历史"),
            HistoryMessage(id = "h2", role = HistoryMessage.ROLE_ASSISTANT, text = "第二条历史"),
        )
        val bare = prompt(emptyList(), history)
        val ask = defer("quiet:1028#1")
        accepted(submit(answer(ask.askId, ReflowKind.INFO, text = "只删 3 月以前的"), round = 4))
        val withNote = prompt(AnswerReflow.notes(4), history)
        assertEquals("历史段一个字没动（前缀判据）", seg(bare, SegmentKind.HISTORY), seg(withNote, SegmentKind.HISTORY))
        assertTrue("两条历史都还在段里，谁也没被摘要替掉：${seg(withNote, SegmentKind.HISTORY)}",
            seg(withNote, SegmentKind.HISTORY).contains("第一条历史") &&
                seg(withNote, SegmentKind.HISTORY).contains("第二条历史"))
        assertEquals("段数与段序不变（开新段等于给前缀判据多一个位置）",
            bare.segments.map { it.kind }, withNote.segments.map { it.kind })
        assertTrue("补丁追加在本轮输入末尾，不插在中间：${seg(withNote, SegmentKind.CURRENT)}",
            seg(withNote, SegmentKind.CURRENT).startsWith(seg(bare, SegmentKind.CURRENT)))
        assertTrue("补丁确实到了提示词里：${seg(withNote, SegmentKind.CURRENT)}",
            seg(withNote, SegmentKind.CURRENT).contains("只删 3 月以前的"))
    }

    /** 最小可用的组装调用：本用例只问「历史段动不动、补丁进哪一段」，别的全给空。 */
    private fun prompt(extraNotes: List<String>, history: List<HistoryMessage>) =
        ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "/sdcard/Download/证道", model = "test-model"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = history,
            current = "把这周的草稿清一下",
            extraNotes = extraNotes,
        )

    private fun seg(prompt: AssembledPrompt, kind: SegmentKind): String =
        prompt.segments.firstOrNull { it.kind == kind }?.text.orEmpty()

    // ── 只读面（报告与巡按的读数口；不许改账） ─────────────────────────────

    @Test
    fun `一行读数恒在_没东西也照印`() {
        val line = AnswerReflow.stateLine(1)
        assertTrue("要说待答几条：$line", line.contains("待答 0 问"))
        assertTrue("要说本轮带几条补丁：$line", line.contains("本轮带 0 条"))
        assertTrue("要说上限：$line", line.contains("上限"))
        defer("quiet:1024#1")
        assertTrue(AnswerReflow.stateLine(1).contains("待答 1 问"))
        assertFalse("读数口不改账", AnswerReflow.pendingSnapshot().isEmpty())
    }

    @Test
    fun `重复登记不改写第一次的轮号_改写就是改历史`() {
        val ask = defer("quiet:1025#1", round = 3)
        val again = AnswerReflow.register(item("quiet:1025#1", "换个说法问同一件事"), 7)
        assertEquals("轮号仍是第一次那一刻", 3, again.turnId)
        assertEquals("headline 也保持第一次（队列里那是同一张卡）", ask.headline, again.headline)
        assertEquals("不新增一格", 1, AnswerReflow.pendingSnapshot().size)
    }

    @Test
    fun `待答清单到线淘汰最老一条_过期那条不能再被灌`() {
        // 她一直不答 ⇒ 待答只涨不掉。到线（100）淘汰最老的：宁可重新问，不许悄悄生效。
        val ids = (1..101).map { "quiet:2000#$it" }
        ids.forEach { defer(it) }
        assertEquals("上限 100，第 101 条把最老的挤掉", 100, AnswerReflow.pendingSnapshot().size)
        assertTrue("最老那条已不在清单里", AnswerReflow.pendingSnapshot().none { it.askId == ids.first() })
        assertTrue("拿它来答 ⇒ 拒（不在待答清单里）",
            rejected(submit(answer(ids.first(), ReflowKind.INFO))).reason.contains("不在待答清单里"))
        assertNotNull("最新那条还在等", AnswerReflow.turnOf(ids.last()))
    }
}
