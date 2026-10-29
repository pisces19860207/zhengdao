// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百八十三批（学习器↔反思器接进调解器）——判据①～④＋负例四条＋一段真跑
// （3 条意见 → 1 弹 2 不弹＋各自理由；转写落 `build/reports/learn283-realrun.md`，交付报告引用）。
//
// 类的选择说明：历史三轮弹的都是 L9 越界卡与 L7 未分类卡——调解器本来就会弹这两类
// （274 的映射），用户才关得着；学习调"何时问"的合法辖地正是这类 L33 自决权下的卡。
// L7 是"必问"标本（fail-closed），专供判据②验"学习撞法条"。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.ReflectionArchive
import com.example.zhengdao.baopu.ReflectionEvidence
import com.example.zhengdao.baopu.ReflectionEvidenceKind
import com.example.zhengdao.baopu.ReflectionRunner
import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
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

class LearnWiringTest {

    private lateinit var root: File

    /** 固定时刻（算"日"只用 24h 整数倍跨日，避免时区边界）。 */
    private val now = 1_700_000_000_000L

    private val dayMs = 24L * 3600 * 1000

    /** 轮来源（生产形态：引擎接 beginRound 后账上写 round#N；测试里按幕次推进）。 */
    private var round = 0

    @Before
    fun setUp() {
        root = Files.createTempDirectory("learn-283").toFile()
        LearnWiring.resetForTest()
        DecisionLedger.install(root)
        round = 0
        OpinionWiring.installRoundSource { round.toString() }
        LearnWiring.installRoundSource { round.toString() }
    }

    @After
    fun tearDown() {
        DecisionLedger.uninstallForTest()
        LearnWiring.resetForTest()
    }

    // ── 判据①：同类卡关 3 次后判"不弹"，账上有 3 条理由 ─────────────────────

    @Test
    fun `判据①_同类卡关3次后判不弹_账上有3条理由`() {
        val cls = "越界访问"
        for (i in 1..3) {
            round = i
            val at = now + i * dayMs
            val r = LearnWiring.onOpinion(pathOp("risk:path_outside:$i"), tool = "shell", now = at)
            assertEquals("前 3 张在预算内应弹", OpinionDecision.POP, r!!.decision)
            LearnWiring.onUserSignal(UserSignal.DISMISS, cls, r.id, at, "第${i}次关：越界我自己会看")
        }
        round = 4
        val r4 = LearnWiring.onOpinion(pathOp("risk:path_outside:4"), tool = "shell", now = now + 4 * dayMs)
        assertNull("关满 3 次后同类不弹（判据①）", r4)
        val signals = LearnWiring.signalsOf(cls)
        assertEquals("账上有 3 条理由（判据①）", 3, signals.size)
        assertTrue("每条理由都不许空白", signals.all { it.reason.isNotBlank() })
        assertTrue(
            "自决单里有学习压的那一条（L33）",
            SelfRulingLedger.replay().any { it.why == LearnWiring.SUPPRESS_WHY && it.reason.isNotBlank() },
        )
        assertEquals("压下的没进预算账", 3, OpinionWiring.replay().count { it.decision == OpinionDecision.POP })
    }

    // ── 判据②：法条冲突时学习结果作废并记违规 ────────────────────────────────

    @Test
    fun `判据②_法条冲突时学习作废并记违规`() {
        val cls = "未分类"
        for (i in 1..3) {
            round = i
            val at = now + i * dayMs
            val r = LearnWiring.onOpinion(unclassifiedOp("risk:unclassified:$i"), tool = "shell", now = at)
            assertEquals(OpinionDecision.POP, r!!.decision)
            LearnWiring.onUserSignal(UserSignal.DISMISS, cls, r.id, at, "第${i}次关：未分类别再问我")
        }
        round = 9
        val r = LearnWiring.onOpinion(unclassifiedOp("risk:unclassified:4"), tool = "shell", now = now + 5 * dayMs)
        assertNotNull("L7 必问的，学习压不掉（判据②）", r)
        assertEquals("照送预算：本轮第一张 ⇒ 该弹", OpinionDecision.POP, r!!.decision)
        assertEquals(1, LearnWiring.violations().size)
        val v = LearnWiring.violations().first()
        assertTrue("违规要点名法条", v.reason.contains("L7"))
        assertTrue("违规要写明作废", v.reason.contains("作废"))
        assertTrue("没落 learn_suppress 自决单", SelfRulingLedger.replay().none { it.why == LearnWiring.SUPPRESS_WHY })

        // critical 同在学习禁区（L33 四类禁区的一支）：撞上照样作废＋记违规
        val cls2 = "急案"
        for (i in 1..3) {
            LearnWiring.onUserSignal(UserSignal.DISMISS, cls2, "op:crit:$i", now + i * dayMs, "第${i}次关：急案也别烦我")
        }
        val crit = op(Source.PATROL, cls2, LawRef.NONE, "patrol:urgent", severity = Severity.CRITICAL)
        val r2 = LearnWiring.onOpinion(crit, tool = "patrol", now = now + 5 * dayMs)
        assertNotNull(r2)
        assertEquals("本轮已弹过 ⇒ 预算延后（不是学习压的）", OpinionDecision.DEFER, r2!!.decision)
        assertEquals("critical 撞学习再记一次违规", 2, LearnWiring.violations().size)
        assertTrue("这条到了调解器账上", OpinionWiring.replay().any { it.id == r2.id })
    }

    // ── 判据③：归因带证据行；L17 无证据不落盘 ────────────────────────────────

    @Test
    fun `判据③_归因带证据行_无证据不落盘`() {
        val ws = Files.createTempDirectory("learn-283-ws").toFile()
        val archive = ReflectionRunner.archiveFile(ws)
        archive.parentFile!!.mkdirs()
        // 265 的档案形状：出卡一行「待定」，用户否决再追加一行「否决」（append-only）
        archive.writeText(
            ReflectionArchive.header() + "\n" +
                "| 2026-09-20 | 新技能 | 日报整理 | 第一次提 | 记忆/日记/2026-09-18.md:L12；技能/_变更.jsonl:L3 | 待定 |\n" +
                "| 2026-09-25 | 新技能 | 日报整理 | 第一次提 | 同上 | 否决 |\n" +
                "| 2026-10-10 | 新技能 | 日报整理 | 又提一次 | 记忆/日记/2026-10-08.md:L4 | 待定 |\n" +
                "| 2026-10-14 | 新技能 | 日报整理 | 又提一次 | 同上 | 否决 |\n",
            Charsets.UTF_8,
        )
        val store = InMemoryJudgmentExperienceStore()
        val result = ReflectionAttributor(ws, store, clock = { now }, log = {}).attribute()
        assertEquals("两对提出→否决，各归因一条", 2, result.persisted.size)
        val repropose = result.persisted.first { it.why == MisjudgeWhy.REPROPOSE_IN_REJECT_WINDOW }
        assertTrue(
            "归因带证据行且条条可点开（判据③）",
            repropose.evidence.size >= 2 && repropose.evidence.all { it.openable },
        )
        assertTrue("归因进了经验账", store.readAll().any { it.kind == ExperienceKind.ATTRIBUTION })

        // L17 闸（271 同一条法）：单证据／点不开／没理由都不落
        val e = { open: Boolean -> ReflectionEvidence("证据", "p.md", "L1", ReflectionEvidenceKind.DIARY, open) }
        assertFalse("单证据说不出反复", AttributionGate.passes(listOf(e(true)), "r"))
        assertFalse("点不开的证据不算", AttributionGate.passes(listOf(e(true), e(false)), "r"))
        assertFalse("没理由的归因是空话", AttributionGate.passes(listOf(e(true), e(true)), ""))
        assertTrue(AttributionGate.passes(listOf(e(true), e(true)), "r"))
    }

    // ── 判据④：自决单可回放；账不设窗、阈值设窗 ─────────────────────────────

    @Test
    fun `判据④_自决单与经验账可回放_账不设窗而阈值设窗`() {
        val cls = "越界访问"
        for (i in 1..3) {
            round = i
            val at = now + i * dayMs
            val r = LearnWiring.onOpinion(pathOp("risk:path_outside:$i"), tool = "shell", now = at)
            LearnWiring.onUserSignal(UserSignal.DISMISS, cls, r!!.id, at, "第${i}次关")
        }
        round = 4
        assertNull(LearnWiring.onOpinion(pathOp("risk:path_outside:4"), tool = "shell", now = now + 4 * dayMs))
        val far = now + 40 * dayMs
        // 账不设窗：40 天后三本账都还在
        assertTrue(SelfRulingLedger.replay().isNotEmpty())
        assertTrue(LearnWiring.replay().isNotEmpty())
        assertTrue(OpinionWiring.replay().isNotEmpty())
        assertEquals("40 天后信号行还在（判据④）", 3, LearnWiring.signalsOf(cls).size)
        // 阈值设窗：出窗的信号不算数——但账没删
        assertFalse("出窗后不再判压", LearnPolicy.shouldSuppress(LearnWiring.replay(), cls, far).suppressed)
        assertTrue("窗内照判", LearnPolicy.shouldSuppress(LearnWiring.replay(), cls, now + 4 * dayMs).suppressed)
    }

    // ── 负例：四器结论与自身判断当不了标签 ────────────────────────────────────

    @Test
    fun `负例_四器结论与自身判断当不了标签`() {
        // ① 四器结论走意见路：分类器已硬拒 ⇒ SKIP——机器结论只产生观察行，产生不了 SIGNAL
        round = 1
        val r = LearnWiring.onOpinion(
            op(Source.CLASSIFIER, "硬拒", LawRef.L8, "risk:hard_deny"),
            tool = "shell",
            now = now,
            alreadyRefused = true,
        )
        assertEquals(OpinionDecision.SKIP, r!!.decision)
        assertEquals(0, LearnWiring.signalsOf("硬拒").size)
        assertTrue(LearnWiring.replay().none { it.kind == ExperienceKind.SIGNAL })

        // ② 学习自己的"压"也不是标签：压完信号数不变
        val cls = "越界访问"
        for (i in 1..3) {
            LearnWiring.onUserSignal(UserSignal.DISMISS, cls, "op:h$i", now + i * dayMs, "第${i}次关")
        }
        round = 4
        assertNull(LearnWiring.onOpinion(pathOp("risk:path_outside:n"), tool = "shell", now = now + 4 * dayMs))
        assertEquals("压下的决定不算用户信号", 3, LearnWiring.signalsOf(cls).size)

        // ③ 没落点的"信号"拒收——没有凭据的标签是编的
        assertNull(LearnWiring.onUserSignal(UserSignal.APPROVE, cls, " ", now))
        assertNull(LearnWiring.onUserSignal(UserSignal.APPROVE, "", "op:1", now))
    }

    // ── 事后重复问：压错了要能撤销 ────────────────────────────────────────────

    @Test
    fun `事后重复问_撤销压制`() {
        val cls = "越界访问"
        for (i in 1..3) {
            LearnWiring.onUserSignal(UserSignal.DISMISS, cls, "op:h$i", now + i * dayMs, "第${i}次关")
        }
        round = 1
        assertNull(LearnWiring.onOpinion(pathOp("risk:path_outside:a"), tool = "shell", now = now + 4 * dayMs))
        assertFalse(LearnWiring.shouldAsk(cls, now + 4 * dayMs))
        assertNotNull(LearnWiring.onUserSignal(UserSignal.REASK, cls, "user:query:1", now + 5 * dayMs))
        assertTrue("重问后压制撤销", LearnWiring.shouldAsk(cls, now + 5 * dayMs))
        round = 2
        val r = LearnWiring.onOpinion(pathOp("risk:path_outside:b"), tool = "shell", now = now + 5 * dayMs)
        assertEquals("重问后同类恢复弹", OpinionDecision.POP, r!!.decision)
    }

    // ── 次序（学习的另一半）：批过的类排前面 ─────────────────────────────────

    @Test
    fun `次序_批过的类排前面`() {
        fun ruling(gist: String, at: Long) = Ruling(
            id = "op:$gist",
            opinion = op(Source.SHADOW, gist, LawRef.L9, "e:$gist"),
            decision = OpinionDecision.DEFER,
            why = OpinionWhy.ROUND_TAKEN,
            detail = "d",
            round = "round#1",
            royalToday = 1,
            atMs = at,
            tool = "t",
        )
        val approved = ruling("越界访问", now)
        val plain = ruling("重复失败", now - 1)
        val out = LearnPolicy.orderRulings(listOf(plain, approved), { cls ->
            if (cls == "越界访问") LearnPolicy.ClassStats(0, 2, 0, null) else LearnPolicy.ClassStats(0, 0, 0, null)
        })
        assertEquals("批过的类先弹", "越界访问", out.first().opinion.gist)
    }

    // ── 真跑：3 条意见 → 1 弹 2 不弹＋各自理由（交付报告引用这段转写） ────────

    @Test
    fun `真跑_三条意见_一弹两不弹_各自理由`() {
        val lines = mutableListOf<String>()
        val clsPath = "越界访问"
        val clsUnc = "未分类"

        lines += "【283 真跑】3 条意见 → 1 弹 2 不弹（学习只调「何时问」；法条与预算一个字没改）"
        lines += "铺垫（学习窗 30 天内、非当日）：类「$clsPath」被关 3 次；类「$clsUnc」被关 3 次（每次一条理由进账）"
        for (i in 1..3) {
            val at = now + i * dayMs
            round = i
            val p = LearnWiring.onOpinion(pathOp("risk:path_outside:$i"), tool = "shell", now = at)!!
            LearnWiring.onUserSignal(UserSignal.DISMISS, clsPath, p.id, at, "第${i}次关：越界我自己会看")
            val u = LearnWiring.onOpinion(unclassifiedOp("risk:unclassified:$i"), tool = "shell", now = at)!!
            LearnWiring.onUserSignal(UserSignal.DISMISS, clsUnc, u.id, at, "第${i}次关：未分类别再问我")
        }

        lines += "── 真跑第 4 轮（同一进程、同一刻提交 3 条意见）──"
        val t = now + 5 * dayMs
        round = 4
        val o1 = LearnWiring.onOpinion(
            op(Source.AUDIT, "重复失败", LawRef.L13, "trace:fail:run9", "ledger:208:L42"),
            tool = "inspector",
            now = t,
        )!!
        lines += "意见① 御史·「重复失败」（勘验发现，类无前科）→ ${o1.decision.cn}（${o1.why.wire}）：${o1.detail}"
        assertEquals(OpinionDecision.POP, o1.decision)

        val o2 = LearnWiring.onOpinion(pathOp("risk:path_outside:4"), tool = "shell", now = t)
        val suppressRow = LearnWiring.replay().first { it.kind == ExperienceKind.SUPPRESS }
        lines += "意见② 御前侍卫·「$clsPath」（同类已关 3 次，学习辖地）→ 不弹（${suppressRow.key}）：${suppressRow.reason}"
        assertNull("同类关满 3 次：不弹", o2)

        val o3 = LearnWiring.onOpinion(unclassifiedOp("risk:unclassified:4"), tool = "shell", now = t)!!
        val v = LearnWiring.violations().first()
        lines += "意见③ 御前侍卫·「$clsUnc」（法条 ${o3.opinion.lawRef.name} 必问）→ 学习想压 ⇒ 作废并记违规（${v.key}）；调解器判 ${o3.decision.cn}（${o3.why.wire}）：${o3.detail}"
        assertEquals(OpinionDecision.DEFER, o3.decision)

        lines += "── 账面回放 ──"
        LearnWiring.replay().forEach { lines += "经验账｜${it.kind.wire}｜${it.cardClass}｜${it.key}｜${it.reason}" }
        SelfRulingLedger.replay().filter { it.why == LearnWiring.SUPPRESS_WHY }.forEach {
            lines += "自决单｜${it.why}｜${it.gist}｜${it.reason}"
        }
        lines += "违规 ${LearnWiring.violations().size} 条；「$clsPath」理由 ${LearnWiring.signalsOf(clsPath).size} 条；「$clsUnc」理由 ${LearnWiring.signalsOf(clsUnc).size} 条"

        assertEquals("1 弹 2 不弹：只 1 条违规（意见③）", 1, LearnWiring.violations().size)
        assertEquals(3, LearnWiring.signalsOf(clsPath).size)
        assertEquals(3, LearnWiring.signalsOf(clsUnc).size)
        assertEquals("判据①的自决单＋理由在账", 1, SelfRulingLedger.replay().count { it.why == LearnWiring.SUPPRESS_WHY })
        writeTranscript(lines)
        lines.forEach { println(it) }
    }

    // ── 帮手 ────────────────────────────────────────────────────────────────

    /** 越界访问（274 的分类器映射：MAJOR＋L9；调解器本来就会弹的那类——L33 自决权辖地）。 */
    private fun pathOp(vararg evidence: String) =
        op(Source.CLASSIFIER, "越界访问", LawRef.L9, *evidence)

    /** 未分类（L7 fail-closed 必问——学习的禁区，判据②的标本）。 */
    private fun unclassifiedOp(vararg evidence: String) =
        op(Source.CLASSIFIER, "未分类", LawRef.L7, *evidence)

    private fun op(source: Source, gist: String, law: LawRef, vararg evidence: String, severity: Severity = Severity.MAJOR) =
        Opinion(source, severity, gist, ActionCode.REVIEW, law, evidence.toList())

    /** 真跑转写落盘（交付报告引用；println 同步进测试报告的 system-out）。 */
    private fun writeTranscript(lines: List<String>) {
        runCatching {
            val f = File("build/reports/learn283-realrun.md")
            f.parentFile?.mkdirs()
            f.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        }
    }
}
