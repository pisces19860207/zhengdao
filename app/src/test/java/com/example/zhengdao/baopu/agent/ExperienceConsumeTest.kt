// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十八批 · **判决时真查表**（进化环第三圈）——判据①②③④＋负例「学了不生效」的行为层。
//
// 五条判据里落在这里的四条：
//   ① 同类卡 30 天被关 ≥3 次 ⇒ 下次**不弹**，且账上有「命中经验表」字样（`意见判定.jsonl`
//      的 `why`/`draft`/`drule` 三列 ＋ `追认队列.jsonl` 的自决单理由）；
//   ② **删掉表 ⇒ 行为退回原样**——删的是文件（不是测试里清个变量），删完判定必须与
//      直接调判据层 [OpinionJudge.judge] 的结果**逐字相同**（这才能证明"没查表时表不参与"）；
//   ③ 撞法条 ⇒ 草稿作废、**完整流程优先**，账上记一行 `draft_veto`（供修正阈值）；
//   ④ 每轮记 命中／未命中／被否 三个计数 ＋ 查表代价与表的厚度无关（换轮清零、全程累计不清零）。
//
// 判据⑤（全程无模型）是源码级门禁，在 `policy/DraftSourceGateTest`；
// 快照的不可变／原子换指针／半截表在 `policy/VerdictSnapshotTest`。
//
// 生产接线（2026-10-31 裁定①）：`御案决定按钮 → LearnWiring.onUserSignal` 那一行由本文件的
// `生产接线_…` 钉住——手工灌账能让上面几条全绿，但生产上永远没数据（291 记的「做了没接线」）。
// 判据①要的是"人真的在手机上驳三次，第三次以后不再弹"，所以入口那一行也算交付的一部分。
//
// 反空转：本文件的用例全部走**生产入口** `OpinionWiring.onOpinion`（不是直接调草稿层），
// 所以「把查表那一句删掉」在这里必红——那正是负例①「学了不生效」。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import com.example.zhengdao.baopu.policy.DraftOutcome
import com.example.zhengdao.baopu.policy.VerdictExperienceRuntime
import com.example.zhengdao.baopu.policy.VerdictExperienceSnapshot
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExperienceConsumeTest {

    private lateinit var root: File
    private var round = 1
    private val now = 1_700_000_000_000L
    private val dayMs = 24L * 3600 * 1000

    private val clsPath = "越界访问" // L9＋MAJOR：L33 辖地，学得到"别问"
    private val clsUnc = "未分类" // L7：法典点名必问，学习的禁区

    @Before
    fun setUp() {
        root = Files.createTempDirectory("consume-298").toFile()
        DecisionLedger.install(root)
        LearnWiring.resetForTest() // 连带清 OpinionWiring／表快照／三计数／经验账
        OpinionWiring.installRoundSource { round.toString() }
        round = 1
    }

    @After
    fun tearDown() {
        DecisionLedger.uninstallForTest()
        LearnWiring.resetForTest()
        runCatching { root.deleteRecursively() }
    }

    /** 生产装配的那一步（`BaopuRuntime` 里同一句）：换落盘账本 ⇒ 当场折一张表。 */
    private fun installLedger(at: Long = now) = LearnWiring.installStore(JudgmentExperienceLedger, at)

    // ── 判据①：学到 ⇒ 下次不弹 ⇒ 账上有「命中经验表」──────────────────────

    @Test
    fun `判据1_同类三十天关满三次_下次不弹_账上有命中经验表`() {
        installLedger()
        // 铺垫：三次"关"，每次一条理由进账（三次都还在 30 天窗内，各占一天避开御案额度）
        for (i in 1..3) {
            val at = now + i * dayMs
            round = i
            val r = OpinionWiring.onOpinion(pathOp(i), tool = "shell", now = at)
            assertNotNull("阈值没到之前照原路弹（学到之前不许自己先哑）", r)
            assertEquals("第 $i 次：还没学到 ⇒ 该弹", OpinionDecision.POP, r!!.decision)
            LearnWiring.onUserSignal(UserSignal.DISMISS, clsPath, r.id, at, "第${i}次关：这类我自己会看")
        }

        round = 4
        val at = now + 5 * dayMs
        val hit = OpinionWiring.onOpinion(pathOp(4), tool = "shell", now = at)!!

        assertEquals("同类关满 3 次 ⇒ 不弹", OpinionDecision.SKIP, hit.decision)
        assertEquals(OpinionWhy.EXPERIENCE_HIT, hit.why)
        assertTrue("判词里要能看见是哪一版表：${hit.detail}", hit.detail.contains("命中经验表"))
        assertEquals(DraftOutcome.ADOPTED.wire, hit.draft)
        assertTrue("规则键要落账（回放按它分组）：${hit.draftRule}", hit.draftRule.startsWith("suppress:v"))
        // 不弹**不占御案额度**：这一轮/这一天的弹数一次也没加
        assertEquals(0, OpinionWiring.draftCounts().misses)
        assertEquals(1, OpinionWiring.draftCounts().hits)

        // ── 账面证据（判据①要的是"账上"，不是返回值）──
        val opinionRows = lines(OpinionWiring.fileIn(root))
        val row = opinionRows.last().let { org.json.JSONObject(it) }
        assertEquals(hit.id, row.getString("id"))
        assertEquals(OpinionWhy.EXPERIENCE_HIT.wire, row.getString("why"))
        assertEquals(DraftOutcome.ADOPTED.wire, row.getString("draft"))
        assertEquals(hit.draftRule, row.getString("drule"))
        val self = lines(SelfRulingLedger.fileIn(root))
        assertTrue(
            "自决单要带『命中经验表』的理由（L33：不打扰要留得下理由）",
            self.any { it.contains("命中经验表") && it.contains(clsPath) },
        )
        // 落盘的表本身：判决经验账里那三条关，就是这次命中的来源
        assertEquals(3, JudgmentExperienceLedger.readAll().count { it.key == "signal:dismiss" })
    }

    // ── 判据②：删掉表 ⇒ 行为退回原样（证明真在消费）───────────────────────

    @Test
    fun `判据2_删掉经验表_行为逐字退回原样`() {
        installLedger()
        for (i in 1..3) {
            val at = now + i * dayMs
            round = i
            val r = OpinionWiring.onOpinion(pathOp(i), tool = "shell", now = at)!!
            LearnWiring.onUserSignal(UserSignal.DISMISS, clsPath, r.id, at, "第${i}次关")
        }
        // 先自证"表在生效"：同样的输入此刻是不弹
        round = 4
        val hit = OpinionWiring.onOpinion(pathOp(4), tool = "shell", now = now + 4 * dayMs)!!
        assertEquals(OpinionDecision.SKIP, hit.decision)

        // 删表＝删**文件**（判据②的原话），再按启动那一步重折：账空 ⇒ 表空
        val ledgerFile = JudgmentExperienceLedger.fileIn(root)
        assertTrue("前提：经验账真的落过盘", ledgerFile.isFile && ledgerFile.length() > 0L)
        ledgerFile.delete()
        assertFalse("前提：文件真没了", ledgerFile.isFile)
        JudgmentExperienceLedger.resetForTest() // 同一次删除的另一侧：本进程的内存副本
        installLedger(now + 5 * dayMs)
        assertEquals("账本空 ⇒ 表空（清的是账，不是留一张旧表）", 0, VerdictExperienceRuntime.current().classes.size)

        val op = pathOp(41)
        round = 5
        val after = OpinionWiring.onOpinion(op, tool = "shell", now = now + 5 * dayMs)!!
        assertEquals("表没了 ⇒ 照原路弹", OpinionDecision.POP, after.decision)
        assertEquals(DraftOutcome.MISS.wire, after.draft)
        assertEquals("", after.draftRule)
        // 最强的一条：与"根本没接表"（直接调判据层）逐字相同
        val plain = OpinionJudge.judge(
            opinion = op,
            royalToday = 0,
            roundUsed = false,
            alreadyRefused = false,
            exemptionReason = null,
        )
        assertEquals("未命中 ⇒ 判定不得被表挪过一个字（decision）", plain.decision, after.decision)
        assertEquals("未命中 ⇒ 判定不得被表挪过一个字（why）", plain.why, after.why)
        assertEquals("未命中 ⇒ 判定不得被表挪过一个字（detail）", plain.detail, after.detail)
    }

    // ── 判据③：撞法条 ⇒ 草稿被否＋记账，完整流程优先 ─────────────────────

    @Test
    fun `判据3_撞法条_草稿作废并记账_完整流程优先`() {
        installLedger()
        for (i in 1..3) {
            val at = now + i * dayMs
            round = i
            val r = OpinionWiring.onOpinion(uncOp(i), tool = "shell", now = at)!!
            LearnWiring.onUserSignal(UserSignal.DISMISS, clsUnc, r.id, at, "第${i}次关：未分类我自己也看不懂")
        }
        round = 4
        val at = now + 5 * dayMs
        val vetoed = OpinionWiring.onOpinion(uncOp(4), tool = "shell", now = at)!!

        assertEquals("L7 未分类点名必问 ⇒ 不许按草稿不弹", OpinionDecision.POP, vetoed.decision)
        assertEquals(DraftOutcome.VETOED.wire, vetoed.draft)
        assertEquals(OpinionWhy.BUDGET_OK, vetoed.why) // 走的就是原判据

        val vetoes = JudgmentExperienceLedger.readAll().filter { it.kind == ExperienceKind.DRAFT_VETO }
        assertEquals(1, vetoes.size)
        val v = vetoes.first()
        assertEquals("draft_veto:law_first", v.key)
        assertEquals(clsUnc, v.cardClass)
        assertTrue("理由要点名是哪条法：${v.reason}", v.reason.contains("L7"))
        assertTrue("理由要写清谁优先：${v.reason}", v.reason.contains("完整流程优先"))
        assertTrue("证据要带上命中的是哪一版表：${v.evidence}", v.evidence.any { it.startsWith("suppress:v") })
        // 208 那本也留一行（供修正阈值），但**不新增 kind**——只在 reason 前缀上挂牌
        val ledger208 = lines(File(root, DecisionLedger.FILE_NAME))
        assertTrue("208 里要有一行 learn:draft_veto", ledger208.any { it.contains("learn:draft_veto") })
        // 被否的那一次仍然算"查过表"：计数里被否＋1、命中＋0
        assertEquals(1, OpinionWiring.draftCounts().vetoes)
        assertEquals(0, OpinionWiring.draftCounts().hits)
    }

    // ── 判据④：每轮三个计数（换轮清零、全程不清零）＋ 结论只出计数 ────────

    @Test
    fun `判据4_每轮记命中未命中被否_换轮清零`() {
        installLedger()
        // 先让 clsPath 学到（三条关），clsUnc 同样学到（撞法条用）
        for (i in 1..3) {
            val at = now + i * dayMs
            round = i
            val a = OpinionWiring.onOpinion(pathOp(i), tool = "shell", now = at)!!
            LearnWiring.onUserSignal(UserSignal.DISMISS, clsPath, a.id, at, "关 $i")
            val b = OpinionWiring.onOpinion(uncOp(i), tool = "shell", now = at)!!
            LearnWiring.onUserSignal(UserSignal.DISMISS, clsUnc, b.id, at, "关 $i")
        }
        // 本轮三条意见：一条命中、一条未命中、一条被否
        round = 4
        val t = now + 4 * dayMs
        val hit = OpinionWiring.onOpinion(pathOp(40), tool = "shell", now = t)
        val miss = OpinionWiring.onOpinion(op(Source.AUDIT, "重复失败", LawRef.L13, t, "trace:fail:run9"), tool = "inspector", now = t)
        val veto = OpinionWiring.onOpinion(uncOp(40), tool = "shell", now = t)
        assertEquals(OpinionDecision.SKIP, hit!!.decision)
        assertEquals(OpinionDecision.POP, miss!!.decision)
        assertEquals(OpinionDecision.DEFER, veto!!.decision) // 今日御案已满（前面弹过两张）

        val counts = OpinionWiring.draftCounts()
        assertEquals("命中", 1, counts.hits)
        assertEquals("未命中", 1, counts.misses)
        assertEquals("被否", 1, counts.vetoes)
        val totals = OpinionWiring.draftTotals()
        assertTrue("全程累计只增不减：${totals.text()}", totals.hits >= 1 && totals.vetoes >= 1 && totals.misses >= 1)
        assertTrue("三计数都在一行结论里：${counts.text()}", counts.text().contains("命中=1"))

        // 换轮 ⇒ 本轮清零（全程不清零）
        round = 5
        val next = OpinionWiring.onOpinion(pathOp(50), tool = "shell", now = now + 5 * dayMs)!!
        assertEquals(OpinionDecision.SKIP, next.decision)
        assertEquals(1, OpinionWiring.draftCounts().hits)
        assertEquals(0, OpinionWiring.draftCounts().misses)
        assertEquals(0, OpinionWiring.draftCounts().vetoes)
        assertTrue(OpinionWiring.draftTotals().hits >= 2)

        // 结论面只出计数，不出类名/时刻/原文（体检与审计读它）
        val line = OpinionWiring.tableConclusion()
        assertFalse("结论不许外泄类名明细：$line", line.contains(clsPath))
        assertTrue("结论要有表版本与计数：$line", line.contains("经验表 v"))
        // 查表确实发生了那么多次（不是自说自话）
        val (consults, nanos) = OpinionWiring.draftConsultStats()
        assertTrue("查表次数=$consults 应覆盖本轮判定", consults >= 6)
        assertTrue("累计耗时（纳秒）=$nanos", nanos >= 0L)
    }

    // ── 判据④的另一半：查表代价与表的厚度无关（结构上限＋实测）──────────

    @Test
    fun `判据4b_快照查表代价与表厚无关_O1`() {
        val thin = VerdictExperienceSnapshot.build(dismissRows("薄表类", 4, now - 4), now, seq = 1L)
        val thick = VerdictExperienceSnapshot.build(bigRows(10_000), now, seq = 1L)
        assertEquals(1, thin.classes.size)
        assertEquals(10_000, thick.classes.size)
        // 结构上限：一个类的"关"明细最多 64 条 ⇒ 一次查表最多比 64 个数，与账有多厚无关
        val fatter = VerdictExperienceSnapshot.build(dismissRows("胖类", 500, now - 500), now, seq = 1L)
        assertEquals(
            "同类关 500 次也只剩 $MAX 条明细（查表代价有界）",
            MAX,
            fatter.classes.getValue("胖类").closeTimes.size,
        )
        assertEquals(500, LearnPolicy.stats(dismissRows("胖类", 500, now - 500), "胖类", now).closes)

        val lines = mutableListOf<String>()
        lines += "【298 判据④】快照查表：1 类表 vs 10,000 类表（同一进程、同一轮测量）"
        val sample = 20_000
        val tThin = medianNanos { suppress(thin, "薄表类", now) }
        val tThick = medianNanos { suppress(thick, "类7", now) }
        val tAbsent = medianNanos { suppress(thick, "不在表里的类", now) }
        lines += "中位数：薄表（1 类）=${tThin} ns／厚表（10,000 类）=${tThick} ns／厚表未命中=${tAbsent} ns（各 $sample 次）"
        // 宽松上限：只断言"没有随表厚爆长"（绝对值随机器浮动，比值才是判据）
        assertTrue("厚表查表中位数 $tThick ns 远超薄表 $tThin ns×20＋2μs ⇒ 查表在随表厚长大", tThick <= tThin * 20 + 2_000)
        // 判决路径每判一条只读表一次（这条计数把"中途再读第二次"钉死）
        installLedger()
        val before = OpinionWiring.draftConsultStats().first
        round = 9
        OpinionWiring.onOpinion(pathOp(90), tool = "shell", now = now + 9 * dayMs)
        assertEquals("一条意见 = 一次查表", before + 1, OpinionWiring.draftConsultStats().first)
        writeTranscript(lines)
        lines.forEach { println(it) }
    }

    private fun suppress(snap: VerdictExperienceSnapshot, cls: String, at: Long): Boolean = snap.suppresses(cls, at)

    /** 中位数（预热 2,000 次再测；JIT 与线程调度会让前几次贵一个数量级）。 */
    private fun medianNanos(sample: Int = 20_000, block: () -> Any?): Long {
        repeat(2_000) { block() }
        val xs = LongArray(sample)
        repeat(sample) { i ->
            val t = System.nanoTime()
            block()
            xs[i] = System.nanoTime() - t
        }
        xs.sort()
        return xs[xs.size / 2]
    }

    // ── 并发读：判决路径不加锁，读到的永远是完整一版 ───────────────────────

    @Test
    fun `判决路径不加锁_读侧一次拿到完整表_版本号不会回退`() {
        installLedger()
        val writing = java.util.concurrent.atomic.AtomicBoolean(true)
        val traces = mutableListOf<List<Long>>()
        val pool = Executors.newFixedThreadPool(4)
        // 4 个"判决线程"读到写侧结束为止；主线程同时每次信号折一张新表
        val readers = (1..4).map {
            pool.submit<List<Long>> {
                val seen = ArrayList<Long>()
                while (writing.get()) {
                    val snap = VerdictExperienceRuntime.current()
                    seen += snap.seq
                    snap.suppresses(clsPath, now + seen.size)
                }
                seen
            }
        }
        repeat(50) { i -> LearnWiring.onUserSignal(UserSignal.DISMISS, clsPath, "ref:$i", now + i, "并发写 $i") }
        writing.set(false)
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS))
        readers.forEach { f -> traces += f.get() }
        traces.forEach { seen ->
            assertEquals("读侧看到的版本号必须单调不减（回退＝读到了换表的中间态）", seen.sorted(), seen)
            assertTrue("前提：这一路确实跨过了换表（第一版到最后一版）", seen.first() < seen.last())
        }
        val s = VerdictExperienceRuntime.current()
        assertEquals("并发写完 50 次 ⇒ 表是 50 条关的那一版", 50, s.classes.getValue(clsPath).closeTimes.size)
        assertEquals("每一版自己都对得上（不是半截）", s.classes.values.sumOf { it.closeTimes.size }, s.closeTotal)
        assertEquals(50, s.closeTotal)
        assertTrue(VerdictExperienceRuntime.state().seq >= 1L)
    }

    // ── 账的往返：老行没有 draft 列 ⇒ 认作"没查表"，不拿空串冒充未命中 ────

    @Test
    fun `老账行没有draft列_读回空_意思是没查表`() {
        val legacy = """{"v":1,"id":"op:1","at":1,"day":"2023-11-14","round":"process","src":"CLASSIFIER",""" +
            """"sev":"MAJOR","law":"L9","act":"REVIEW","gist":"$clsPath","tool":"shell",""" +
            """"decision":"pop","why":"budget_ok","detail":"老行（298 之前落的）","royal":1,"exempt":"",""" +
            """"ev":["risk:path_outside:legacy"]}"""
        OpinionWiring.fileIn(root).appendText(legacy + "\n", Charsets.UTF_8)
        val r = OpinionWiring.replay().first { it.id == "op:1" }
        assertEquals("没查表就是空串，不许伪造成 draft_miss", "", r.draft)
        assertEquals("", r.draftRule)
        // 新行写出去再读回来，两列都在（回放能自证"当时查过表"）
        installLedger()
        for (i in 1..3) {
            val at = now + i * dayMs
            round = i
            val x = OpinionWiring.onOpinion(pathOp(i), tool = "shell", now = at)!!
            LearnWiring.onUserSignal(UserSignal.DISMISS, clsPath, x.id, at, "关 $i")
        }
        round = 4
        val hit = OpinionWiring.onOpinion(pathOp(60), tool = "shell", now = now + 4 * dayMs)!!
        val back = OpinionWiring.replay().first { it.id == hit.id }
        assertEquals(hit.draft, back.draft)
        assertEquals(hit.draftRule, back.draftRule)
    }

    // ── 源级钉：判决路径必须查表（删掉那一行就红＝负例①「学了不生效」）────

    @Test
    fun `源级钉_调解器里必须有一次查表与一次计数`() {
        val text = mainFile("agent/OpinionWiring.kt").readText(Charsets.UTF_8)
        assertEquals("调解器必须且只许查一次草稿", 1, occurrences(text, "VerdictDraft.decide("))
        assertEquals("三计数必须落账", 1, occurrences(text, "DraftTally.note("))
        assertEquals("被否必须记账", 1, occurrences(text, "LearnWiring.recordDraftVeto("))
        // 未命中分支必须原样调用完整判据（不许自己另编一套判定）
        assertTrue(text.contains("OpinionJudge.judge("))
        // 只有"只进队列"那一支可以不查表（它本来就不弹，查了只会给计数掺水）
        val queueOnly = text.substringAfter("if (queueOnly) {").substringBefore("}")
        assertTrue("queueOnly 直接返回、不查表", queueOnly.contains("QUEUE_ONLY"))
    }

    // ── 接线（2026-10-31 裁定①）：御案决定按钮 ⇒ 标签 ⇒ 表换版 ⇒ 下次不弹 ──
    //
    // 手工灌账能过测试，生产上却永远没数据（291 记的「做了没接线」就是这个坑）。
    // 所以这一条把两端钉在一起：**UI 那一行调用还在**（删了就红）＋**三裁的语义各归其位**
    // （批＝正票、驳＝关、搁置＝"以后"不计关）——阈值只由人的表态挪，不由测试挪。

    @Test
    fun `生产接线_御案三裁走学习器_驳满三次才不弹_搁置不算关`() {
        // ① 源级钉：学习入口在生产 UI 里，且只有一处（四器结论不许往这儿递）
        val ui = uiFile("ui/baopu/DecisionCenterScreen.kt").readText(Charsets.UTF_8)
        assertEquals("御案页签只许一处学习入口", 1, occurrences(ui, "LearnWiring.onUserSignal("))
        assertTrue(
            "递的两个落点必须是行上的 gist（类）与意见判定 id（缺一个学习器就拒收）",
            ui.contains("LearnWiring.onUserSignal(UserSignal.ofRoyal(it), row.gist, row.id)"),
        )

        installLedger()
        // ② 搁置三次 ⇒ 一条也没计进"关"：阈值没动，下一次照弹
        for (i in 1..3) {
            val at = now + i * dayMs
            round = i
            val r = OpinionWiring.onOpinion(pathOp(i), tool = "shell", now = at)!!
            assertEquals("第 $i 次：搁置是「以后再说」，之前照弹", OpinionDecision.POP, r.decision)
            LearnWiring.onUserSignal(UserSignal.ofRoyal(RoyalVerdict.HOLD), clsPath, r.id, at)
        }
        // 信号行本身照记（三裁都进账），只是搁置不算关——账与阈值是两件事
        val defers = JudgmentExperienceLedger.readAll().count { it.key == "signal:defer" }
        assertEquals("三次搁置都落进了判决经验账", 3, defers)
        round = 4
        val afterHolds = OpinionWiring.onOpinion(pathOp(4), tool = "shell", now = now + 4 * dayMs)!!
        assertEquals("搁置不计关 ⇒ 不许学到「不弹」", OpinionDecision.POP, afterHolds.decision)

        // ③ 驳回三次 ⇒ 三次"关" ⇒ 这一类下次不弹（判据①的生产闭环）
        for (i in 5..7) {
            val at = now + i * dayMs
            round = i
            val r = OpinionWiring.onOpinion(pathOp(i), tool = "shell", now = at)!!
            assertEquals("第 $i 次：关没满三次之前照弹", OpinionDecision.POP, r.decision)
            LearnWiring.onUserSignal(UserSignal.ofRoyal(RoyalVerdict.REJECT), clsPath, r.id, at)
        }
        round = 8
        val afterRejects = OpinionWiring.onOpinion(pathOp(8), tool = "shell", now = now + 8 * dayMs)!!
        assertEquals("同类 30 天内被驳三次＝三次关 ⇒ 不弹", OpinionDecision.SKIP, afterRejects.decision)
        assertEquals(OpinionWhy.EXPERIENCE_HIT, afterRejects.why)

        // ④ 批是正票，不挪"关"的读数（否则批三次也会学出"别问了"）
        LearnWiring.onUserSignal(UserSignal.ofRoyal(RoyalVerdict.APPROVE), clsPath, afterHolds.id, now + 9 * dayMs)
        val exp = VerdictExperienceRuntime.current().classes.getValue(clsPath)
        assertEquals("三驳＝三关", 3, exp.closesAt(now + 9 * dayMs))
        assertEquals("一批＝一正票", 1, exp.approves)
    }

    // ── 帮手 ────────────────────────────────────────────────────────────

    private fun mainFile(rel: String): File {
        val direct = File("src/main/java/com/example/zhengdao/baopu/$rel")
        return if (direct.isFile) direct else File("app/src/main/java/com/example/zhengdao/baopu/$rel")
    }

    /** UI 那一份不在 `baopu/` 底下（`ui/baopu/`），单独取。 */
    private fun uiFile(rel: String): File {
        val direct = File("src/main/java/com/example/zhengdao/$rel")
        return if (direct.isFile) direct else File("app/src/main/java/com/example/zhengdao/$rel")
    }

    private fun occurrences(text: String, token: String): Int = text.split(token).size - 1

    private fun lines(f: File): List<String> =
        if (!f.isFile) emptyList() else f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }

    private fun pathOp(i: Int) = op(Source.CLASSIFIER, clsPath, LawRef.L9, i.toLong(), "risk:path_outside:")

    private fun uncOp(i: Int) = op(Source.CLASSIFIER, clsUnc, LawRef.L7, i.toLong(), "risk:unclassified:")

    private fun op(source: Source, gist: String, law: LawRef, i: Long, evPrefix: String) = Opinion(
        source = source,
        severity = Severity.MAJOR,
        gist = gist,
        action = ActionCode.REVIEW,
        lawRef = law,
        evidence = listOf("$evPrefix$i"),
    )

    private fun dismissRows(cardClass: String, n: Int, start: Long): List<ExperienceRow> =
        (1..n).map { i ->
            ExperienceRow(
                id = "$cardClass#$i",
                atMs = start + i,
                kind = ExperienceKind.SIGNAL,
                cardClass = cardClass,
                key = "signal:dismiss",
                reason = "测试用的关",
                evidence = listOf("ref:$cardClass:$i"),
            )
        }

    private fun bigRows(classes: Int): List<ExperienceRow> =
        (1..classes).flatMap { dismissRows("类$it", 4, now - 4) }

    /** 真跑转写落盘（交付报告直接引用；println 同步进测试报告的 system-out）。 */
    private fun writeTranscript(lines: List<String>) {
        runCatching {
            val f = File("build/reports/consume298-realrun.md")
            f.parentFile?.mkdirs()
            f.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        }
    }

    private companion object {
        const val MAX = VerdictExperienceSnapshot.MAX_CLOSE_TIMES
    }
}
