// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零四批 · 执行链的 JVM 钉子：触发→评测→证据落账→路由（不弹卡）。
//
// 判据③的"生产调用点"与④"不许模型自评"由源级门禁钉（EvalTriggerSourceGateTest）；
// 这里钉行为：账行里有没有读数、限频有没有生效、路由有没有走、评测跑没跑（计数器）。
package com.example.zhengdao.baopu.evolution.trigger

import com.example.zhengdao.baopu.scout.UnfinishedItem
import com.example.zhengdao.baopu.scout.UnfinishedLedger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalTriggerEngineTest {

    private val temp: File = File.createTempFile("zd304", "").let { it.delete(); it.mkdirs(); it }
    private val ledger = TriggerLedgerStore(File(temp, "evolution/trigger-ledger.jsonl"))
    private val unfinished = UnfinishedLedger(listOf(File(temp, "未办成单")))
    private var evalRuns = 0
    private var proposalCalls = 0

    private fun engine(now: Long, evalError: Boolean = false) = EvalTriggerEngine(
        readRows = { ledger.read() },
        appendRow = { ledger.append(it) },
        runEval = {
            evalRuns += 1
            if (evalError) throw IllegalStateException("评测崩了")
            TriggerEvalReading(
                ran = 13, passed = 12, total = 13, passRate = 12.0 / 13,
                failedCases = listOf("evo-token-budget"), elapsedMs = 5,
            )
        },
        recordUnfinished = { signal, reading, at ->
            // 与生产同构的最小写口：真账本、真 item（Context 相关的装配由源级门禁盯）。
            val item = UnfinishedItem(
                intent = signal.intent.ifBlank { "会话任务（${signal.ref}）" },
                outcome = com.example.zhengdao.baopu.scout.UnfinishedOutcome.FAILED,
                reason = com.example.zhengdao.baopu.scout.UnfinishedReason.OTHER,
                reasonDetail = "已自动评测：机械场景 ${reading.passed}/${reading.total} 过",
                reasonRef = signal.ref,
                fixable = false,
                atMs = at,
            )
            if (unfinished.record(item)) "unfinished:${com.example.zhengdao.baopu.scout.UnfinishedRules.keyOf(item)}" else ""
        },
        recordProposal = { signal, _, _ ->
            proposalCalls += 1
            "proposal:${signal.target}"
        },
        now = { now },
        log = {},
    )

    private fun failedInputs(sessionId: Long = 1L, intent: String = "整理笔记") = EvalTriggerInputs(
        nowMs = 0L,
        sessionId = sessionId,
        runs = listOf(TriggerRunRow("r-1", sessionId, 0, 100, listOf("OK", "FAILED"))),
        rollbackAtMs = emptyList(),
        sessionSkillRows = emptyList(),
        windowSkillRows = emptyList(),
        intent = intent,
    )

    // ── 验收①：造一次 FAILED ⇒ 触发一次且证据落账 ────────────────────────

    @Test
    fun `① 造一次 FAILED ⇒ 触发一次＋账行带读数＋未办成单落行`() {
        val out = engine(now = 1_000L).onRoundSettled(failedInputs())
        assertEquals(1, out.size)
        val triggered = out.single() as EvalTriggerOutcome.Triggered
        assertEquals(TriggerType.RUN_FAILED, triggered.row.type)
        assertEquals("task:r-1", triggered.row.ref)
        // 证据落账：读数在账行里（不许"评了但没数"）。
        assertEquals(13, triggered.row.evalTotal)
        assertEquals(12, triggered.row.evalPassed)
        assertEquals(12.0 / 13, triggered.row.evalPassRate!!, 1e-9)
        assertEquals(listOf("evo-token-budget"), triggered.row.evalFailedCases)
        assertEquals(1, evalRuns)
        // 路由：未办成单里真有一行，原因可点回 task:r-1；提案口一次都不该被碰。
        assertEquals(0, proposalCalls)
        val items = unfinished.load()
        assertEquals(1, items.size)
        assertEquals("整理笔记", items.single().intent)
        assertEquals("task:r-1", items.single().reasonRef)
        assertEquals("unfinished:整理笔记", triggered.row.route)
    }

    // ── 验收②：同日再 FAILED ⇒ 不触发（限频） ───────────────────────────

    @Test
    fun `② 同日再 FAILED ⇒ 抑制＋评测没跑＋未办成单没有第二行`() {
        val e = engine(now = 1_000L)
        e.onRoundSettled(failedInputs())
        assertEquals(1, evalRuns)
        val second = engine(now = 1_000L + 60_000).onRoundSettled(failedInputs())
        val suppressed = second.single() as EvalTriggerOutcome.Suppressed
        assertEquals(TriggerType.RUN_FAILED, suppressed.type)
        assertTrue(suppressed.reason.contains("24h"))
        assertEquals("评测没再跑", 1, evalRuns)
        // 账上留了两行：一触发一抑制（对账"为什么没评"）。
        val rows = ledger.read()
        assertEquals(2, rows.size)
        assertTrue(rows[0].triggered)
        assertTrue(!rows[1].triggered)
        assertTrue(rows[1].suppressReason.isNotBlank())
        assertEquals(1, unfinished.load().size)
    }

    // ── ②③ 的路由：纠错/成功率进提案 ────────────────────────────────────

    @Test
    fun `③ 成功率跌破 ⇒ 提案路由（PENDING 候选的素材在信号里）`() {
        val window = (1..4).map { TriggerSkillRow(it.toLong(), "查文献", "SUCCESS", it.toLong()) } +
            (5..10).map { TriggerSkillRow(it.toLong(), "查文献", "FAILURE", it.toLong()) }
        val inputs = EvalTriggerInputs(
            nowMs = 0L, sessionId = 1L, runs = emptyList(), rollbackAtMs = emptyList(),
            sessionSkillRows = emptyList(), windowSkillRows = window, intent = "",
        )
        val out = engine(now = 1_000L).onRoundSettled(inputs)
        val triggered = out.single() as EvalTriggerOutcome.Triggered
        assertEquals(TriggerType.SKILL_RATE, triggered.row.type)
        assertEquals("proposal:查文献", triggered.row.route)
        assertEquals(1, proposalCalls)
    }

    @Test
    fun `② 连续纠错 ⇒ 提案路由`() {
        val session = listOf(
            TriggerSkillRow(1, "查文献", "USER_CORRECTED", 1),
            TriggerSkillRow(2, "查文献", "USER_CORRECTED", 2),
        )
        val inputs = EvalTriggerInputs(
            nowMs = 0L, sessionId = 1L, runs = emptyList(), rollbackAtMs = emptyList(),
            sessionSkillRows = session, windowSkillRows = emptyList(), intent = "",
        )
        val out = engine(now = 1_000L).onRoundSettled(inputs)
        val triggered = out.single() as EvalTriggerOutcome.Triggered
        assertEquals(TriggerType.USER_CORRECTION, triggered.row.type)
        assertEquals("proposal:查文献", triggered.row.route)
    }

    // ── ④ 预留：手动（同样限频；不路由） ─────────────────────────────────

    @Test
    fun `④ 手动触发走预算：第二次同日被抑制`() {
        val e = engine(now = 2_000L)
        val first = e.manual("用户点了一次")
        assertTrue(first is EvalTriggerOutcome.Triggered)
        assertEquals("", (first as EvalTriggerOutcome.Triggered).row.route) // 预留：不进两本账
        val second = engine(now = 2_000L + 60_000).manual("又点了一次")
        assertTrue(second is EvalTriggerOutcome.Suppressed)
        assertEquals(1, evalRuns) // 第二次被限频 ⇒ 评测没再跑
    }

    // ── 月上限 ───────────────────────────────────────────────────────────

    @Test
    fun `本月触发满 20 次 ⇒ 再 FAILED 也抑制`() {
        // 预填 20 条本月已触发的账行（间隔 25h，让月判定而不是 24h 判定说话；
        // now 取在最后一行 +24h 之后、且与账行同月——月判定按真实日历算）。
        val h = EvalTriggerBudget.SAME_TYPE_INTERVAL_MS + 3_600_000L
        repeat(20) { i ->
            ledger.append(
                TriggerRow(
                    atMs = (i + 1) * h, type = TriggerType.MANUAL, triggered = true,
                    ref = "manual", intent = "",
                ),
            )
        }
        val now = 20 * h + EvalTriggerBudget.SAME_TYPE_INTERVAL_MS + 1
        val out = engine(now = now).onRoundSettled(failedInputs())
        val suppressed = out.single() as EvalTriggerOutcome.Suppressed
        assertEquals(TriggerType.RUN_FAILED, suppressed.type)
        assertTrue(suppressed.reason.contains("20"))
        assertEquals(0, evalRuns)
    }

    // ── 诚实性：评测崩了不许静默、四条件全不满足零动作、记账失败不冒成功 ──

    @Test
    fun `评测崩了 ⇒ 账行带错误原文，不许拿 0冒充读数`() {
        val out = engine(now = 1_000L, evalError = true).onRoundSettled(failedInputs())
        val row = (out.single() as EvalTriggerOutcome.Triggered).row
        assertEquals(0, row.evalRan)
        assertEquals(null, row.evalPassRate)
        assertTrue(row.evalError.contains("评测崩了"))
    }

    @Test
    fun `四条件全不满足 ⇒ NoSignal 零动作（没跑评测没记账）`() {
        val inputs = EvalTriggerInputs(
            nowMs = 0L, sessionId = 1L,
            runs = listOf(TriggerRunRow("r-1", 1L, 0, 100, listOf("OK"))),
            rollbackAtMs = emptyList(),
            sessionSkillRows = listOf(TriggerSkillRow(1, "查文献", "SUCCESS", 1)),
            windowSkillRows = listOf(TriggerSkillRow(1, "查文献", "SUCCESS", 1)),
            intent = "整理笔记",
        )
        val out = engine(now = 1_000L).onRoundSettled(inputs)
        assertEquals(EvalTriggerOutcome.NoSignal, out.single())
        assertEquals(0, evalRuns)
        assertTrue(ledger.read().isEmpty())
    }

    @Test
    fun `账行编码解码往返一致（坏行解析返回 null，不让整本账读崩）`() {
        val row = TriggerRow(
            atMs = 42L, type = TriggerType.USER_CORRECTION, triggered = true,
            ref = "skill_event:9", intent = "技能「查文献」被连续纠正两次",
            evalRan = 13, evalPassed = 13, evalTotal = 13, evalPassRate = 1.0,
            evalFailedCases = listOf("a", "b"), route = "proposal:abc",
        )
        val parsed = TriggerRowCodec.parse(TriggerRowCodec.encode(row).toString())!!
        assertEquals(row, parsed)
        assertEquals(null, TriggerRowCodec.parse("这不是 JSON"))
        assertEquals(null, TriggerRowCodec.parse("{\"type\":\"nope\"}"))
    }
}
