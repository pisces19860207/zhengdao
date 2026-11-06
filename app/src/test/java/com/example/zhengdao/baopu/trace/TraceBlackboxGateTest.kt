// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零五批 · 474 轨迹黑匣子的闸（派工单 §一 与 §三 判据①②⑥）。
//
// 三条形状规矩钉在这里，一条都不许靠自觉：
// · **一步一行**（判据①）：喂入口每定论一次，匣子落一行，行数==调用数、序号连续、可回放；
// · **只发现不阻断**（判据②）：匣子没有一票否决的权力——落盘失败、异常达阈都不改这一笔的下场；
// · **不含正文**（判据⑥）：`BlackboxRow` 的键集是闭集，喂进来的形状里根本没有参数对象，
//   只有 digest；所以正文进不来不是"我们没写进去"，是"没有地方能写进去"。
//
// 「不许另造第三个观察器」（§一④）由源级用例钉：主源码里 `TraceBlackbox.observe(` 只许出现一处，
// 且必须落在 `agent/JinwuObserve.kt` 里——多一处＝第二个喂入口，少一处＝匣子被拆了。
package com.example.zhengdao.baopu.trace

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.agent.DeviationWiring
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.JinwuObserve
import com.example.zhengdao.baopu.agent.ReadFileTool
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.escalate.CapabilityKind
import com.example.zhengdao.baopu.escalate.Escalation
import com.example.zhengdao.baopu.escalate.EscalationTrigger
import com.example.zhengdao.baopu.escalate.StopMode
import com.example.zhengdao.baopu.inspect.JinwuPatrol
import com.example.zhengdao.baopu.inspect.StepResultCode
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.verify.GapFeedback
import com.example.zhengdao.law.LawIo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TraceBlackboxGateTest {

    private lateinit var workspace: File
    private lateinit var ledger: File
    private lateinit var observeDir: File
    private val lines = mutableListOf<String>()

    private val runId = "run-505-a"

    @Before
    fun setUp() {
        workspace = Files.createTempDirectory("zd505-ws").toFile()
        ledger = Files.createTempDirectory("zd505-ledger").toFile()
        observeDir = Files.createTempDirectory("zd505-observe").toFile()
        DecisionLedger.install(ledger)
        JinwuObserve.attachAt(observeDir)
        DeviationWiring.installRun(runId)
        TraceBlackbox.resetForTest()
        Escalation.resetForTest()
        JinwuPatrol.resetForTest()
        GapFeedback.resetForTest()
    }

    @After
    fun tearDown() {
        TraceBlackbox.resetForTest()
        Escalation.resetForTest()
        JinwuObserve.resetForTest()
        JinwuPatrol.resetForTest()
        GapFeedback.resetForTest()
        DeviationWiring.resetForTest()
        DecisionLedger.uninstallForTest()
        workspace.deleteRecursively()
        ledger.deleteRecursively()
        observeDir.deleteRecursively()
    }

    /** 直接喂一步（规则层与负例走这条路；判据①的端到端另有真管线那一组）。 */
    private fun feed(
        callId: String,
        tool: String = "read_file",
        result: StepResultCode = StepResultCode.DONE,
        capability: String = CapabilityKind.FILE_READ.wire,
        escape: Boolean = false,
        offPlan: Boolean = false,
        atMs: Long = System.currentTimeMillis(),
        digest: String = "0123456789abcdef",
    ): BlackboxRow? {
        TraceBlackbox.observe(
            ObservedStep(
                atMs = atMs,
                callId = callId,
                tool = tool,
                argsDigest = digest,
                result = result,
                capability = capability,
                escape = escape,
                offPlan = offPlan,
            ),
        )
        return TraceBlackbox.replay(runId).lastOrNull()
    }

    // ── 判据①：一步一行（真管线端到端） ─────────────────────────────────────

    private fun registry(): ToolRegistry = ToolRegistry().apply { register(ReadFileTool.spec()) }

    private fun pipeline(): GuardPipeline = GuardPipeline(
        registry(),
        { workspace },
        { null },
        ToolRunner(Executors.newSingleThreadExecutor()),
        { lines += it },
    )

    private fun readCall(id: String, path: String): ToolCall =
        ToolCall(id, "read_file", JSONObject().put("path", path))

    @Test
    fun `判据一_真管线跑五笔_轨迹五行一笔不落`() {
        File(workspace, "资料库").mkdirs()
        File(workspace, "资料库/设定.md").writeText("第一行\n第二行\n")
        val p = pipeline()
        val calls = listOf(
            readCall("c505-1", "资料库/设定.md"),
            readCall("c505-2", "资料库/没有这个文件.md"),
            readCall("c505-3", "资料库/设定.md"),
            readCall("c505-4", "   "),
            readCall("c505-5", "/storage/emulated/0/.ssh/id_rsa"),
        )
        val results = calls.map { p.execute(it) { _, _ -> null } }

        val rows = TraceBlackbox.replay(runId)
        assertEquals("轨迹行数 == 工具调用数（判据①的口径就是这个等式）", calls.size, rows.size)
        assertEquals(calls.size, TraceBlackbox.stepCount(runId))
        assertEquals(listOf(1, 2, 3, 4, 5), rows.map { it.index })
        assertEquals(
            "结果码由喂入口按同一个映射转手（成功／工具失败／②段空参数拒／凭据目录拒）",
            listOf(
                StepResultCode.DONE.wire,
                StepResultCode.FAILED.wire,
                StepResultCode.DONE.wire,
                StepResultCode.REJECTED.wire,
                StepResultCode.REJECTED.wire,
            ),
            rows.map { it.result },
        )
        assertEquals("五笔全走同一个工具（连败那把尺才连得上）", 1, rows.map { it.tool }.distinct().size)
        assertEquals(
            "匣子只记账不判生死：主流程该成的成、该失败的失败、该拒的拒，三种去向都在：$results",
            3,
            results.map { it::class.simpleName }.distinct().size,
        )
        assertTrue(
            "匣子不碰参数本体：摘要只有键名＋哈希前 16 位（值原文进哈希不进文案，照 239 那把尺）",
            rows.all { it.argsDigest.matches(Regex("""^path:[0-9a-f]{16}$""")) },
        )
        assertTrue("② 段越界那一笔如实带出越界列", rows[4].escape)
        assertFalse("② 段空参数那一笔不是越界（判据②）", rows[3].escape)
        val shard = File(ledger, "${TraceBlackbox.SHARD_PREFIX}0${TraceBlackbox.SHARD_SUFFIX}")
        assertTrue("落盘才是到达：分片文件要在 $shard", shard.isFile)
        assertEquals("盘上行数与内存一致", calls.size, shard.readLines(Charsets.UTF_8).count { it.isNotBlank() })
    }

    @Test
    fun `判据一_同一笔重复喂不重复落`() {
        feed("dup-1")
        feed("dup-1")
        feed("dup-2")
        assertEquals(2, TraceBlackbox.stepCount(runId))
        assertEquals(2, TraceBlackbox.replay(runId).size)
    }

    @Test
    fun `判据一_一行八项各有出处`() {
        val at = 1_700_000_000_000L
        feed("bx-1", atMs = at)
        val r = feed("bx-2", atMs = at + 250L)!!
        assertEquals("step＝本轮序号", 2, r.index)
        assertEquals("t＝这一步的时刻", at + 250L, r.atMs)
        assertEquals("tool＝工具名", "read_file", r.tool)
        assertEquals("args 摘要＝金吾卫同一个口径（16 位）", 16, r.argsDigest.length)
        assertNotNull("result＝结果码闭集里的一格", StepResultCode.entries.firstOrNull { it.wire == r.result })
        assertEquals(
            "risk＝风险底档名（本层不判档，只转手）",
            RiskMatrixBaseName.of("read_file"), r.riskTier,
        )
        assertFalse("escape＝是否越界（这一笔没拦）", r.escape)
        assertEquals("累计耗时＝自本轮第一行的真墙钟差", 250L, r.accumulatedMs)
        assertEquals(runId, r.runId)
    }

    @Test
    fun `没登记进风险表_如实写UNCLASSIFIED不编一档`() {
        val r = feed("bx-tier", tool = "no_such_tool_in_matrix")!!
        assertEquals(TraceBoxRules.UNCLASSIFIED_TIER, r.riskTier)
    }

    @Test
    fun `累计耗时_不知道起点写负数不写0`() {
        assertEquals(TraceBoxRules.NO_START, TraceBoxRules.accumulatedMs(null, 5_000L))
        assertEquals(0L, TraceBoxRules.accumulatedMs(5_000L, 4_000L))
        assertEquals(1_000L, TraceBoxRules.accumulatedMs(5_000L, 6_000L))
    }

    // ── 判据①②：四类异常 ─────────────────────────────────────────────────────

    @Test
    fun `判据一_同工具连败到线_标异常`() {
        (1 until TraceBoxRules.SAME_TOOL_FAIL_MIN).forEach { i ->
            val row = feed("f-$i", result = StepResultCode.FAILED, atMs = 1_000L + i)!!
            assertTrue("没到线就别说异常：${row.anomalies}", row.anomalies.isEmpty())
        }
        val row = feed("f-min", result = StepResultCode.FAILED, atMs = 2_000L)!!
        assertTrue("连败到线要标：${row.anomalies}", row.anomalies.contains(TraceAnomaly.SAME_TOOL_FAIL.wire))
        assertEquals(
            "线借内核那把「工具坏了」的尺（不另立第二个数）",
            BaopuKernel.Budget.MAX_TOOL_FAILURES, TraceBoxRules.SAME_TOOL_FAIL_MIN,
        )
    }

    @Test
    fun `成功一次打断连败`() {
        feed("b-1", result = StepResultCode.FAILED)
        feed("b-2", result = StepResultCode.FAILED)
        feed("b-3", result = StepResultCode.DONE)
        feed("b-4", result = StepResultCode.FAILED)
        val last = feed("b-5", result = StepResultCode.FAILED)!!
        assertTrue(
            "中间成功过 ⇒ 连败从那一笔重新数：${TraceBlackbox.replay(runId).map { it.anomalies }}",
            !last.anomalies.contains(TraceAnomaly.SAME_TOOL_FAIL.wire),
        )
    }

    @Test
    fun `判据二_计划外路径_标异常`() {
        val row = feed("off-1", offPlan = true)!!
        assertTrue("242 的契约偏离事实要落进异常列：${row.anomalies}", row.anomalies.contains(TraceAnomaly.OFF_PLAN.wire))
    }

    @Test
    fun `判据二_反复重试同参数_标异常`() {
        repeat(TraceBoxRules.REPEAT_ARGS_MIN - 1) { i -> feed("r-$i", digest = "aaaaaaaaaaaaaaaa") }
        assertTrue(TraceBlackbox.replay(runId).all { it.anomalies.isEmpty() })
        val row = feed("r-min", digest = "aaaaaaaaaaaaaaaa")!!
        assertTrue("同参数摘要第 N 次要标：${row.anomalies}", row.anomalies.contains(TraceAnomaly.REPEAT_ARGS.wire))
        assertEquals(
            "线借内核那把「同参再调」的尺",
            BaopuKernel.Budget.REPEAT_CALL_LIMIT, TraceBoxRules.REPEAT_ARGS_MIN,
        )
    }

    @Test
    fun `判据二_单轮步数超阈_25不标26标`() {
        (1..TraceBoxRules.ROUND_STEP_WARN).forEach { i -> feed("s-$i", atMs = 10_000L + i) }
        assertTrue(
            "没超提醒线不标",
            TraceBlackbox.replay(runId).none { it.anomalies.contains(TraceAnomaly.ROUND_STEP_OVER.wire) },
        )
        feed("s-over", atMs = 20_000L)
        assertEquals("第 26 步标一次", 1, stepOverRows())
        feed("s-over2", atMs = 21_000L)
        assertEquals("过了线不每步再标（不刷屏）", 1, stepOverRows())
        assertFalse(
            "这条是提醒线，与引擎 300 步硬刹不是一把尺",
            TraceBoxRules.ROUND_STEP_WARN == BaopuKernel.Budget.MAX_STEPS,
        )
    }

    @Test
    fun `异常列的键是闭集`() {
        assertEquals(
            "四类之外没有第五类（加一类要改派工单，不许代码里自己长）",
            setOf("same_tool_fail", "off_plan", "repeat_args", "round_step_over"),
            TraceAnomaly.entries.map { it.wire }.toSet(),
        )
        assertEquals(TraceAnomaly.OFF_PLAN, TraceAnomaly.ofWire("off_plan"))
        assertNull(TraceAnomaly.ofWire("body_in_trace"))
    }

    /** 本轮里被标了「单轮步数超阈」的行数（一条线只许标一次，多了＝刷屏）。 */
    private fun stepOverRows(): Int =
        TraceBlackbox.replay(runId).count { row -> TraceAnomaly.ROUND_STEP_OVER.wire in row.anomalies }

    /** 本轮里带异常的行数——出口那一行的 M 就是这个数（口径能被别人独立核）。 */
    private fun anomalyRows(): Int = TraceBlackbox.replay(runId).count { it.anomalies.isNotEmpty() }

    // ── 判据②：可回放 ＋ 不阻断 ───────────────────────────────────────────────

    @Test
    fun `判据二_轨迹可回放至少三节点_内存清了盘上还在`() {
        List(3) { i -> feed("r$i", atMs = 3_000L + i) }
        assertEquals(3, TraceBlackbox.replay(runId).size)
        TraceBlackbox.resetForTest()
        val replayed = TraceBlackbox.replay(runId)
        assertTrue("只追加的盘账在，重启后照样回放（判据②「可回放 ≥3 节点」）：${replayed.size}", replayed.size >= 3)
        assertEquals(listOf(1, 2, 3), replayed.take(3).map { it.index })
    }

    @Test
    fun `判据二_回放按序号升序且能截断`() {
        (1..5).forEach { i -> feed("p-$i", atMs = 100L * i) }
        val head = TraceBlackbox.replay(runId, limit = 2)
        assertEquals(2, head.size)
        assertEquals(listOf(1, 2), head.map { it.index })
        assertTrue("没有出处的回放不给历史", TraceBlackbox.replay("   ").isEmpty())
    }

    @Test
    fun `判据二_监控不阻断_账落不上这一步也照做`() {
        // 落点指向一棵"文件里的目录"：mkdirs 与 appendText 都办不成 ⇒ 匣子只能记账、不能改判。
        val blocker = File(ledger, "我不是目录")
        blocker.writeText("占位")
        DecisionLedger.install(File(blocker, "装不进去"))
        TraceBlackbox.resetForTest()
        File(workspace, "资料库").mkdirs()
        File(workspace, "资料库/甲.md").writeText("内容")
        val r = pipeline().execute(readCall("c505-block", "资料库/甲.md")) { _, _ -> null }
        assertTrue("匣子没有一票否决：这一笔照样跑成：$r", r is GuardPipeline.Result.Done)
        assertEquals("盘上没有、内存里有——匣子如实说没落上，不假装有账", 1, TraceBlackbox.replay(runId).size)
        assertTrue("分片一个都没建出来", TraceBlackbox.shardFiles().isEmpty())
    }

    @Test
    fun `判据二_喂进去抛不了_坏数据不挡主流程`() {
        TraceBlackbox.resetForTest()
        DecisionLedger.uninstallForTest()
        feed(callId = "", tool = "x".repeat(200), digest = "不是十六进制", capability = "不存在的类", atMs = -9L)
        val row = TraceBlackbox.replay(runId).single()
        assertEquals("工具名有上限，不把账撑爆", TraceBoxRules.TOOL_NAME_MAX, row.tool.length)
        assertEquals("累计耗时没起点就写哨兵负数，不写 0 假装知道", TraceBoxRules.NO_START, row.accumulatedMs)
        assertTrue(Escalation.open().isEmpty())
    }

    // ── 判据⑥：不含正文 ───────────────────────────────────────────────────────

    @Test
    fun `判据六_行的键集是闭集_没有能装正文的字段`() {
        feed("bx-key")
        val row = TraceBlackbox.replay(runId).single()
        assertEquals(
            "想多带一段原文就得改这两处（键集与字段），那条用例先红",
            setOf("v", "id", "at", "run", "ix", "tool", "ad", "res", "risk", "esc", "cum", "an"),
            JSONObject(row.toJson()).keys().asSequence().toSet(),
        )
        val closed = setOf(
            "id", "atMs", "runId", "index", "tool", "argsDigest",
            "result", "riskTier", "escape", "accumulatedMs", "anomalies",
        )
        assertEquals("字段表就是这十一格——多一格得先回答它装不装正文", closed, rowFieldNames())
        val fields = rowFieldNames()
        listOf("content", "text", "body", "args", "arguments", "prompt", "detail").forEach { bad ->
            assertFalse("$bad 不能成为黑匣子的一格：$fields", fields.any { it.equals(bad, ignoreCase = true) })
        }
        val fed = stepFieldNames()
        assertFalse(
            "喂进来的形状里根本没有参数对象（正文从形状上就进不来）：$fed",
            fed.any { it.equals("arguments", ignoreCase = true) || it.equals("args", ignoreCase = true) },
        )
    }

    /** 判据⑥的 grep 面：直接读出生那两格字段名（反射要引 kotlin-reflect，这里不为此加依赖）。 */
    private fun fieldNamesOf(anchor: String): Set<String> {
        val src = LawIo.stripComments(LawIo.read("src/main/java/com/example/zhengdao/baopu/trace/TraceBlackbox.kt"))
        val block = src.substringAfter(anchor).substringBefore("\n)")
        assertTrue("切片要真切到字段表（防空转）：$anchor", block.contains("val "))
        return Regex("""val\s+(\w+)\s*:""").findAll(block).map { it.groupValues[1] }.toSet()
    }

    private fun rowFieldNames(): Set<String> = fieldNamesOf("internal data class BlackboxRow(")

    private fun stepFieldNames(): Set<String> = fieldNamesOf("internal data class ObservedStep(")

    @Test
    fun `判据六_正文与路径都进不了账`() {
        val secret = "机密正文-绝密代号-42"
        val reg = registry()
        val call = ToolCall(
            "c505-secret",
            "read_file",
            JSONObject().put("path", "资料库/私人日记.md").put("note", secret),
        )
        JinwuObserve.observe(GuardPipeline.Result.Failed(call, "读不到"), call, reg)
        val text = TraceBlackbox.replay(runId).joinToString("\n") { it.toJson() } +
            TraceBlackbox.shardFiles().flatMap { f -> f.readLines(Charsets.UTF_8) }.joinToString("\n")
        assertFalse("正文没进账：$text", text.contains(secret))
        assertFalse("连路径本身都不进账（只留摘要）：$text", text.contains("私人日记"))
        val row = TraceBlackbox.replay(runId).single()
        assertEquals(JinwuPatrol.argsDigestOf(call.arguments), row.argsDigest)
    }

    // ── §一③：出口那一行 ＋ 达阈值交 475 ──────────────────────────────────────

    @Test
    fun `出口一行_本轮轨迹N步斜杠异常M前3条`() {
        feed("e-1", result = StepResultCode.FAILED, offPlan = true, atMs = 1_000L)
        feed("e-2", result = StepResultCode.FAILED, atMs = 2_000L)
        feed("e-3", result = StepResultCode.FAILED, atMs = 3_000L)
        val line = TraceBlackbox.sheetLines().single()
        // M 数的是"带异常的行"，不是异常的种类：三行里两行有异常 ⇒ 2
        assertTrue("格式照派工单：$line", line.startsWith("本轮轨迹 3 步 / 异常 2（前 3 条："))
        assertTrue("前几条要点名：$line", line.contains("同工具连败") && line.contains("计划外路径"))
        assertTrue("第三格也点名：$line", line.contains("反复重试同参数"))
        assertEquals("M 就是带异常的行数（口径可核，不靠文案）", 2, anomalyRows())
        // 「没喂过」那一格要明说，不许留一格让人猜（先摘掉轮次出处，再清内存）
        TraceBlackbox.resetForTest()
        DeviationWiring.resetForTest()
        assertEquals(listOf("轨迹：本轮没有落盘观测"), TraceBlackbox.sheetLines())
    }

    @Test
    fun `达阈值交475_一轮只递一次`() {
        val n = TraceBoxRules.ESCALATE_ANOMALY_MIN
        repeat(n) { i -> feed("h-$i", result = StepResultCode.FAILED, offPlan = true, atMs = 1_000L + i) }
        val open = Escalation.open()
        assertEquals("到线递一件：$open", 1, open.size)
        val req = open[0]
        assertEquals(EscalationTrigger.TRACE_OVER_LIMIT, req.trigger)
        assertEquals("轨迹达阈交的是本能力类", CapabilityKind.FILE_READ.wire, req.capability)
        assertEquals("停＝正常收尾（这一轮还在跑，不是中止）", StopMode.WRAP_UP, req.stop.mode)
        assertTrue("为什么那一格点名的是异常列：${req.say.why}", req.say.why.contains("计划外路径"))
        feed("h-more", result = StepResultCode.FAILED, offPlan = true, atMs = 9_000L)
        assertEquals("一轮只递一次（不每步刷一件）", 1, Escalation.open().size)
        assertEquals(n, TraceBoxRules.ESCALATE_ANOMALY_MIN)
    }

    @Test
    fun `没到阈值不递_异常一两行只是记下来`() {
        feed("q-1", result = StepResultCode.FAILED, offPlan = true)
        assertTrue("没到线不递：${Escalation.open()}", Escalation.open().isEmpty())
        assertEquals(1, TraceBlackbox.anomalyRowCount(runId))
    }

    @Test
    fun `判据三_越界硬事实递升级_没归类的也递`() {
        feed("x-1", tool = "schedule_task", capability = CapabilityKind.OTHER.wire, escape = true)
        val open = Escalation.open()
        assertEquals(1, open.size)
        val req = open[0]
        assertEquals(EscalationTrigger.OUT_OF_SCOPE, req.trigger)
        assertEquals("停＝安全中止（动作没发生）", StopMode.SAFE_ABORT, req.stop.mode)
        assertEquals(runId, req.stop.runId)
        assertTrue(req.say.what.contains("schedule_task"))
        assertTrue(req.say.suggestion.isNotBlank())
    }

    @Test
    fun `负例M2_不是越界的拦下不递升级`() {
        // 匣子把管线事实原样转手（escape=false），不许自己加戏把"含糊"判成"越界"。
        feed("n-1", escape = false, capability = CapabilityKind.OTHER.wire)
        feed("n-2", escape = false, result = StepResultCode.REJECTED)
        feed("n-3", escape = false, result = StepResultCode.REJECTED)
        feed("n-4", escape = false, result = StepResultCode.REJECTED)
        assertTrue("含糊与拦下都不配越界升级：${Escalation.open()}", Escalation.open().isEmpty())
        assertTrue(
            "连败到线递的是轨迹达阈，不是越界",
            Escalation.open().all { it.trigger == EscalationTrigger.TRACE_OVER_LIMIT },
        )
    }

    @Test
    fun `无出处不递_轮次为空的升级件构造不出来`() {
        DeviationWiring.resetForTest()
        feed("z-1", escape = true)
        assertTrue("没出处就不许有升级件：${Escalation.open()}", Escalation.open().isEmpty())
        assertTrue(
            "匣子照样记这一步（记与递是两件事）：${TraceBlackbox.sheetLines()}",
            TraceBlackbox.sheetLines().single().contains("本轮轨迹 1 步"),
        )
    }

    // ── 轮转有上限 ────────────────────────────────────────────────────────────

    @Test
    fun `只追加有上限轮转_满一片起新片`() {
        val total = TraceBlackbox.ROWS_PER_SHARD + 3
        repeat(total) { i -> feed("roll-$i", atMs = 1_000L + i) }
        val shards = TraceBlackbox.shardFiles()
        assertEquals("满了就该起第二片：${shards.map { it.name }}", 2, shards.size)
        assertEquals(
            "前一片写满，多出来的在新一片",
            TraceBlackbox.ROWS_PER_SHARD,
            shards[0].readLines(Charsets.UTF_8).count { it.isNotBlank() },
        )
        assertEquals(
            total - TraceBlackbox.ROWS_PER_SHARD,
            shards[1].readLines(Charsets.UTF_8).count { it.isNotBlank() },
        )
        assertEquals("回放跨片拼齐", total, TraceBlackbox.replay(runId).size)
        assertTrue("留片上限是个不小于 2 的数（轮转才既不断档也不无限）", TraceBlackbox.SHARD_KEEP >= 2)
    }

    // ── §一④：不许另造第三个观察器（源级） ────────────────────────────────────

    @Test
    fun `源级_匣子只挂在金吾卫那一个喂入口上`() {
        LawIo.assertNotBlind(LawIo.MAIN_ROOT, minFiles = 100, minLines = 10_000)
        val files = LawIo.sources("src/main/java/com/example/zhengdao/baopu")
            .map { (rel, text) -> rel to LawIo.stripComments(text) }
        val feeders = files.filter { it.second.contains("TraceBlackbox.observe(") }.map { it.first }.sorted()
        assertEquals(
            "落盘那一路只许挂在既有喂入口里（多一处＝第二个观察器）：$feeders",
            listOf("agent/JinwuObserve.kt"),
            feeders,
        )
        val pipelineCode = files.first { it.first.endsWith("agent/GuardPipeline.kt") }.second
        assertEquals(
            "喂入口收口处还是那五处，一处不许多（匣子接的是去向，不是新的口）",
            5,
            Regex("""JinwuObserve\s*\.\s*observe\s*\(""").findAll(pipelineCode).count(),
        )
        val traceFiles = File("src/main/java/com/example/zhengdao/baopu/trace")
            .listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }!!
            .map { it.name }.sorted()
        assertEquals("trace/ 包里除了这只匣子不许有第二个观察者", listOf("TraceBlackbox.kt"), traceFiles)
        val boxCode = files.first { it.first.endsWith("trace/TraceBlackbox.kt") }.second
        assertFalse("匣子代码面不碰 JSONObject 参数对象", boxCode.contains("java.util.JSONObject"))
        assertTrue("匣子摸不到 JSONObject 那种带正文的入参形状", !boxCode.contains("arguments"))
    }

    /** 风险底档名的转手口径（本层不判档，测试只核对"取的是同一个名"）。 */
    private object RiskMatrixBaseName {
        fun of(tool: String): String =
            com.example.zhengdao.baopu.RiskMatrix.baseOf(tool)?.name ?: TraceBoxRules.UNCLASSIFIED_TIER
    }
}
