// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百七十四批 §判据（W1 三条映射／W2 三条预算／W3 回放）＋ §负例五条。
// 每条负例都有对应用例，名字里带"不许/不得/不改"，红了就知道踩的是哪一条。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskCode
import com.example.zhengdao.baopu.RiskDecision
import com.example.zhengdao.baopu.RiskReason
import com.example.zhengdao.baopu.RiskVerdict
import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpinionWiringTest {

    /** 账本落点（每个用例一个临时目录：208 与本批的账都落在它下面）。 */
    private lateinit var root: File

    /** 当前的"轮"（测试注入；生产默认不装＝进程级）。 */
    private var round = 1

    /** 固定时刻（2023-11-14 前后；算"日"用本地时区，所以只用 24h 的整数倍跨日）。 */
    private val now = 1_700_000_000_000L

    private val dayMs = 24L * 3600 * 1000

    @Before
    fun setUp() {
        root = Files.createTempDirectory("opinion-wiring").toFile()
        OpinionWiring.resetForTest()
        OpinionWiring.installRoundSource { round.toString() }
        DecisionLedger.install(root)
        round = 1
    }

    @After
    fun tearDown() {
        DecisionLedger.uninstallForTest()
        OpinionWiring.resetForTest()
    }

    // ── 造料 ─────────────────────────────────────────────────────────────

    private fun call(name: String = "write_file") = ToolCall("c-$name", name, JSONObject())

    /** 未分类：矩阵里没有这一行（fail-closed ⇒ R2 必问）。 */
    private fun unclassified() = RiskVerdict(
        risk = RiskClass.R2,
        decision = RiskDecision.MUST_ASK,
        reasons = listOf(RiskReason(RiskCode.UNCLASSIFIED_TOOL, "矩阵里没有这个工具")),
        unclassified = true,
        matrixVersion = 3,
    )

    /** 硬拒：原件区只读。 */
    private fun hardDeny() = RiskVerdict(
        risk = RiskClass.R2,
        decision = RiskDecision.REJECT,
        reasons = listOf(RiskReason(RiskCode.PATH_ORIGINAL_RAW, "原件区只读")),
        unclassified = false,
        matrixVersion = 3,
    )

    /** 越界：写到工作区外。 */
    private fun outside() = RiskVerdict(
        risk = RiskClass.R2,
        decision = RiskDecision.MUST_ASK,
        reasons = listOf(RiskReason(RiskCode.PATH_OUTSIDE_WORKSPACE, "工作区外")),
        unclassified = false,
        matrixVersion = 3,
    )

    private fun proceed() = RiskVerdict(
        risk = RiskClass.R0,
        decision = RiskDecision.PROCEED,
        reasons = emptyList(),
        unclassified = false,
        matrixVersion = 3,
    )

    private fun opinion(
        source: Source = Source.CLASSIFIER,
        severity: Severity = Severity.MAJOR,
        law: LawRef = LawRef.L7,
        gist: String = "未分类",
        action: ActionCode = ActionCode.REVIEW,
        evidence: List<String> = listOf("risk:unclassified_tool"),
    ) = Opinion(source, severity, gist, action, law, evidence)

    /** 把今日御案打满（三张，三轮各一张）。 */
    private fun fillDay() {
        (1..3).forEach { n ->
            round = n
            assertEquals(
                OpinionDecision.POP,
                OpinionWiring.onVerdict(call("t$n"), unclassified(), now)!!.decision,
            )
        }
    }

    // ── W1 三条映射 ──────────────────────────────────────────────────────

    @Test
    fun `未分类包成L7且分类器判定一个字段都不改`() {
        val verdict = unclassified()
        val snapshot = verdict.copy(reasons = verdict.reasons.toList())

        val op = OpinionWiring.opinionOf(call("probe"), verdict)!!

        assertEquals(Source.CLASSIFIER, op.source)
        assertEquals(LawRef.L7, op.lawRef)
        assertEquals(Severity.MAJOR, op.severity)
        assertEquals(ActionCode.REVIEW, op.action)
        assertTrue("证据要带工具名", op.evidence.contains("tool:probe"))
        assertTrue("证据要带矩阵版本（回放要知道当时是哪版表）", op.evidence.contains("matrix:v3"))
        assertEquals("负例：改写分类器结论", snapshot, verdict)
    }

    @Test
    fun `硬拒包成L8且按致命处理`() {
        val op = OpinionWiring.opinionOf(call("probe"), hardDeny())!!

        assertEquals(LawRef.L8, op.lawRef)
        assertEquals(Severity.CRITICAL, op.severity)
        assertEquals(ActionCode.SUGGEST_PAUSE, op.action)
        assertEquals("硬拒", op.gist)
        assertTrue(op.evidence.contains("risk:raw_zone"))
    }

    @Test
    fun `越界包成L9`() {
        val op = OpinionWiring.opinionOf(call("probe"), outside())!!

        assertEquals(LawRef.L9, op.lawRef)
        assertEquals(Severity.MAJOR, op.severity)
        assertEquals("越界访问", op.gist)
        assertTrue(op.evidence.contains("risk:outside_workspace"))
    }

    @Test
    fun `其余判定不产意见`() {
        assertNull(OpinionWiring.opinionOf(call(), proceed()))
        val batch = RiskVerdict(
            RiskClass.R2, RiskDecision.MUST_ASK,
            listOf(RiskReason(RiskCode.BATCH_OVER_20, "目标超过 20 个")), false, 3,
        )
        assertNull("批量必问走 173 的卡，不是意见", OpinionWiring.opinionOf(call(), batch))
    }

    // ── W2 预算：每轮 ≤1 ────────────────────────────────────────────────

    @Test
    fun `同轮第二张延后并进队列`() {
        val first = OpinionWiring.onVerdict(call("a"), unclassified(), now)!!
        val second = OpinionWiring.onVerdict(call("b"), outside(), now)!!

        assertEquals(OpinionDecision.POP, first.decision)
        assertEquals(OpinionDecision.DEFER, second.decision)
        assertEquals(OpinionWhy.ROUND_TAKEN, second.why)
        assertTrue("负例：同轮弹 2 张", OpinionWiring.replay().count { it.decision == OpinionDecision.POP } == 1)
        assertTrue("负例：压下的意见没进队列", OpinionWiring.pending().any { it.id == second.id })
    }

    @Test
    fun `同轮同事件只判一次`() {
        // 两个**不同**的调用撞出同一条意见（同轮同事件）：只判一次、只记一笔
        val a = OpinionWiring.onVerdict(ToolCall("c1", "probe", JSONObject()), unclassified(), now)!!
        val b = OpinionWiring.onVerdict(ToolCall("c2", "probe", JSONObject()), unclassified(), now)!!

        assertEquals(a.id, b.id)
        assertEquals(1, OpinionWiring.replay().count { it.opinion.gist == "未分类" })
    }

    // ── 轮来源：照实记 process，不装成轮号 ──────────────────────────────

    @Test
    fun `账上round列照实写process不装成轮号`() {
        OpinionWiring.resetForTest() // 卸掉换源＝生产默认（进程级）

        val ruling = OpinionWiring.onVerdict(call("probe"), unclassified(), now)!!

        assertEquals(OpinionWiring.PROCESS_ROUND, ruling.round)
        val back = OpinionWiring.replay().first { it.id == ruling.id }
        assertEquals("process", back.round)
        assertNull("不许装成轮号（数字）", back.round.toIntOrNull())
    }

    @Test
    fun `装了真轮来源就记round号`() {
        OpinionWiring.installRoundSource { "7" }

        val ruling = OpinionWiring.onVerdict(call("probe"), unclassified(), now)!!

        assertEquals("round#7", ruling.round)
        assertEquals("round#7", OpinionWiring.replay().first { it.id == ruling.id }.round)
    }

    @Test
    fun `进程级来源打一行可见日志且只打一行`() {
        OpinionWiring.resetForTest()
        assertTrue(OpinionWiring.processNote().contains("轮来源=process"))
        assertTrue(OpinionWiring.processNote().contains("不装成轮号"))

        val first = OpinionWiring.onVerdict(call("a"), unclassified(), now)!!
        OpinionWiring.onVerdict(call("b"), outside(), now)
        round = 2
        OpinionWiring.onVerdict(call("c"), unclassified(), now)

        assertTrue(OpinionWiring.logLine(first).contains("来源=process"))
        assertEquals("进程级只打一行（不刷屏）", 1, OpinionWiring.processNoteCount())
    }

    // ── 幂等：越界两处都挂，只入账一条 ──────────────────────────────────

    @Test
    fun `越界两处都挂只入账一条`() {
        val same = ToolCall("c-out", "write_file", JSONObject().put("path", "../x"))

        val fromGate = OpinionWiring.onOutsidePath(same, now)
        val fromMatrix = OpinionWiring.onVerdict(same, outside(), now)!!

        assertEquals("同一次调用只产一条意见", fromGate.id, fromMatrix.id)
        assertEquals("只入账一条（L24 累进罚则不许被打歪）", 1, OpinionWiring.replay().count { it.tool == "write_file" })
        assertEquals("也只占一张预算", 1, OpinionWiring.replay().count { it.decision == OpinionDecision.POP })
        // 先合并、后入账：两处证据并进内存那一条
        assertTrue(fromMatrix.opinion.evidence.contains("gate:path_guard"))
        assertTrue(fromMatrix.opinion.evidence.contains("risk:outside_workspace"))
        assertTrue(OpinionWiring.pending().isEmpty())
    }

    // ── W2 预算：每日御案 ≤3 ────────────────────────────────────────────

    @Test
    fun `每日御案三张第四张延后`() {
        fillDay()
        round = 4

        val fourth = OpinionWiring.onVerdict(call("t4"), outside(), now)!!

        assertEquals(OpinionDecision.DEFER, fourth.decision)
        assertEquals(OpinionWhy.DAY_QUOTA, fourth.why)
        assertEquals("判定那一刻当日已弹 3 张", 3, fourth.royalToday)
        assertTrue(OpinionWiring.pending().any { it.id == fourth.id })
    }

    @Test
    fun `第二天重新起算`() {
        fillDay()
        round = 4

        val nextDay = OpinionWiring.onVerdict(call("t4"), outside(), now + dayMs)!!

        assertEquals(OpinionDecision.POP, nextDay.decision)
        assertEquals(OpinionWhy.BUDGET_OK, nextDay.why)
        assertEquals(1, nextDay.royalToday)
    }

    // ── W2 豁免：critical＋L21 必须带理由 ───────────────────────────────

    @Test
    fun `急案带理由可越预算且理由进账`() {
        fillDay()
        round = 4
        val critical = opinion(
            severity = Severity.CRITICAL, law = LawRef.L21, gist = "即时危险",
            action = ActionCode.SUGGEST_PAUSE, evidence = listOf("event:1"),
        )

        val ruling = OpinionWiring.onOpinion(critical, "probe", now, exemptionReason = "现有条文未覆盖该即时危险")

        assertEquals(OpinionDecision.POP, ruling.decision)
        assertEquals(OpinionWhy.EXEMPT_CRITICAL, ruling.why)
        assertEquals("现有条文未覆盖该即时危险", ruling.exemptReason)
        assertEquals("理由必须原样进账（回放查得到）", ruling.exemptReason, OpinionWiring.replay().first { it.id == ruling.id }.exemptReason)
    }

    @Test
    fun `急案没理由不得越预算`() {
        fillDay()
        round = 4
        val critical = opinion(
            severity = Severity.CRITICAL, law = LawRef.L21, gist = "即时危险",
            action = ActionCode.SUGGEST_PAUSE, evidence = listOf("event:1"),
        )

        val ruling = OpinionWiring.onOpinion(critical, "probe", now, exemptionReason = "   ")

        assertEquals("负例：critical 越预算无理由", OpinionDecision.DEFER, ruling.decision)
        assertEquals(OpinionWhy.EXEMPT_NO_REASON, ruling.why)
        assertNull(ruling.exemptReason)
        assertTrue(OpinionWiring.pending().any { it.id == ruling.id })
    }

    @Test
    fun `非L21的critical也不得越预算`() {
        fillDay()
        round = 4
        val critical = opinion(
            severity = Severity.CRITICAL, law = LawRef.L13, gist = "账实不符",
            action = ActionCode.SUGGEST_PAUSE, evidence = listOf("audit:1"),
        )

        val ruling = OpinionWiring.onOpinion(critical, "probe", now, exemptionReason = "就是想弹")

        assertEquals(OpinionDecision.DEFER, ruling.decision)
        assertEquals(OpinionWhy.DAY_QUOTA, ruling.why)
        assertNull("非 L21 的豁免理由不许被采信", ruling.exemptReason)
    }

    // ── 不弹的两种（都还要留账） ─────────────────────────────────────────

    @Test
    fun `硬拒不弹但仍留账`() {
        val ruling = OpinionWiring.onVerdict(call("probe"), hardDeny(), now)!!

        assertEquals(OpinionDecision.SKIP, ruling.decision)
        assertEquals(OpinionWhy.ALREADY_REFUSED, ruling.why)
        assertTrue(OpinionWiring.pending().isEmpty())
        assertTrue(OpinionWiring.replay().any { it.id == ruling.id && it.why == OpinionWhy.ALREADY_REFUSED })
    }

    @Test
    fun `无法可依且不致命不弹但留账`() {
        val ruling = OpinionWiring.onOpinion(
            opinion(law = LawRef.NONE, severity = Severity.MINOR, gist = "缺证据", evidence = listOf("patrol:1")),
            "probe", now,
        )

        assertEquals(OpinionDecision.SKIP, ruling.decision)
        assertEquals(OpinionWhy.NO_LAW, ruling.why)
        assertTrue(OpinionWiring.replay().any { it.id == ruling.id })
    }

    @Test
    fun `影子小意见不许被吞`() {
        val shadow = opinion(
            source = Source.SHADOW, severity = Severity.MINOR, law = LawRef.L13,
            gist = "影子不符", action = ActionCode.REVIEW, evidence = listOf("shadow:1"),
        )

        val ruling = OpinionWiring.onOpinion(shadow, "probe", now)

        assertNotEquals("负例：为省事吞掉 shadow-minor", OpinionDecision.SKIP, ruling.decision)
        assertEquals(1, OpinionWiring.replay().count { it.id == ruling.id })
        if (ruling.decision == OpinionDecision.DEFER) {
            assertTrue(OpinionWiring.pending().any { it.id == ruling.id })
        }
    }

    // ── W3 落账可回放 ────────────────────────────────────────────────────

    @Test
    fun `落账三十天后仍可回放`() {
        val old = now - 45 * dayMs

        val ruling = OpinionWiring.onVerdict(call("probe"), unclassified(), old)!!
        val back = OpinionWiring.replay().first { it.id == ruling.id }

        assertEquals(ruling.decision, back.decision)
        assertEquals(ruling.why, back.why)
        assertEquals(ruling.detail, back.detail)
        assertEquals(ruling.opinion, back.opinion)
        assertEquals(OpinionWiring.dayOf(old).toString(), back.day)
    }

    @Test
    fun `回放不按三十天窗过滤`() {
        val recent = OpinionWiring.onVerdict(call("r"), unclassified(), now)!!
        val old = OpinionWiring.onVerdict(call("o"), unclassified(), now - 45 * dayMs)!!

        val ids = OpinionWiring.replay().map { it.id }

        assertTrue(ids.contains(recent.id))
        assertTrue(ids.contains(old.id))
    }

    @Test
    fun `重启后当日御案数从账本种子化`() {
        round = 1
        OpinionWiring.onVerdict(call("a"), unclassified(), now)
        round = 2
        OpinionWiring.onVerdict(call("b"), unclassified(), now)

        // 模拟重启：内存状态全清，盘上那本账还在
        OpinionWiring.resetForTest()
        OpinionWiring.installRoundSource { round.toString() }

        round = 3
        val third = OpinionWiring.onVerdict(call("c"), unclassified(), now)!!
        assertEquals(OpinionDecision.POP, third.decision)
        assertEquals(3, third.royalToday)

        round = 4
        val fourth = OpinionWiring.onVerdict(call("d"), outside(), now)!!
        assertEquals("重启不许白送御案额度", OpinionDecision.DEFER, fourth.decision)
        assertEquals(OpinionWhy.DAY_QUOTA, fourth.why)
    }

    // ── 真链路：GuardPipeline 两个调用点 ─────────────────────────────────

    @Test
    fun `管线未分类工具照样必问且意见落账`() {
        val ws = Files.createTempDirectory("opinion-ws").toFile()
        val registry = ToolRegistry().apply {
            register(
                ToolSpec(
                    name = "opaque_probe",
                    description = "矩阵里没有这一行的探针",
                    inputSchema = jsonObjectOf("type" to "object", "properties" to JSONObject()),
                    permission = ToolPermission.CONFIRM,
                ) { ToolOutcome.Ok("ok") },
            )
        }
        val pipeline = GuardPipeline(
            registry, { ws }, { null }, ToolRunner(Executors.newSingleThreadExecutor()),
        )

        val prepared = pipeline.prepare(ToolCall("c1", "opaque_probe", JSONObject()))

        assertTrue(prepared is GuardPipeline.Prepared.Settled)
        assertTrue(
            "判定不许被接线改掉：未分类仍然必问",
            (prepared as GuardPipeline.Prepared.Settled).result is GuardPipeline.Result.NeedsConfirm,
        )
        val ruling = OpinionWiring.replay().firstOrNull { it.tool == "opaque_probe" }
        assertNotNull("分类器结论要真的进意见账", ruling)
        assertEquals(Source.CLASSIFIER, ruling!!.opinion.source)
        assertEquals(LawRef.L7, ruling.opinion.lawRef)
    }

    @Test
    fun `管线越界弹卡照旧且落一条L9`() {
        val parent = Files.createTempDirectory("opinion-outside").toFile()
        val ws = File(parent, "ws").apply { mkdirs() }
        File(parent, "out.txt").writeText("x")
        val registry = ToolRegistry().apply { register(ReadFileTool.spec()) }
        val pipeline = GuardPipeline(
            registry, { ws }, { null }, ToolRunner(Executors.newSingleThreadExecutor()),
            log = {}, allowOutside = { true },
        )

        val prepared = pipeline.prepare(ToolCall("c1", "read_file", JSONObject().put("path", "../out.txt")))

        assertTrue(prepared is GuardPipeline.Prepared.Settled)
        assertTrue(
            "判定不许被接线改掉：越界仍然问一次",
            (prepared as GuardPipeline.Prepared.Settled).result is GuardPipeline.Result.NeedsConfirm,
        )
        val ruling = OpinionWiring.replay().firstOrNull { it.tool == "read_file" }
        assertNotNull(ruling)
        assertEquals(LawRef.L9, ruling!!.opinion.lawRef)
    }
}
