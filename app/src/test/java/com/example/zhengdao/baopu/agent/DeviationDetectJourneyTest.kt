// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十六批 · 偏差五类的**行为判据**（派工单 §二判据①：造一次真实偏差 ⇒ 账 +1 行，
// 不是测试里直接调账本）。
//
// 每一例都走**真的**判定路径：
//   · 超时（串行）：真跑 `ToolRunner`，工具执行器真睡过时限 ⇒ 引擎超时分支自己落账；
//   · 超时（并行）：真跑 `ToolBatchRunner`，到点未回 ⇒ 调度层超时收口自己落账；
//   · 挂起超时：真跑 `ConfirmFlow`（超时钟 16 分钟，J3 同款）⇒ 挂起等到线自己落账；
//   · 超步数：引擎步循环要真 SQLite + 网络（`RunEngineTest` 头注释：本仓无 Robolectric ⇒
//     归真机），JVM 层退一档——真调引擎接线的那一行生产口（`DeviationWiring.recordOverSteps`，
//     与步数刹车块里逐字同一口），账行仍由生产代码组装，测试不碰 `DeviationLedger.write`。
//     整环（真跑到 300 步刹车）挂真机，见交付报告。
// 账本的读侧（[DeviationLedger.read]）是 363 的生产读数口——读来断言不是"直接调账本"。
//
// 静默负例（判据②"接线前后执行路径不变"的另一半）在 `DeviationWiringSilentTest`。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PendingRow
import com.example.zhengdao.baopu.PendingStore
import com.example.zhengdao.baopu.inspect.DeviationEntry
import com.example.zhengdao.baopu.inspect.DeviationKind
import com.example.zhengdao.baopu.inspect.DeviationLedger
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviationDetectJourneyTest {

    private lateinit var ledgerRoot: File

    private val runId = "r-journey@0"

    @Before
    fun setup() {
        ledgerRoot = Files.createTempDirectory("baopu-386-ledger").toFile()
        DeviationLedger.install(ledgerRoot)
        DeviationWiring.resetForTest()
        DeviationWiring.installRun(runId)
    }

    @After
    fun teardown() {
        // 进程级全局不清就是随执行顺序变红（TaskGrantBoard.resetForTest 同款教训）。
        DeviationWiring.resetForTest()
        // 账本挂载让给一个一次性空目录（363 无卸载口，这是不指向别人数据区的让法）。
        DeviationLedger.install(Files.createTempDirectory("baopu-386-detach").toFile())
        listOf(ledgerRoot).forEach { runCatching { it.deleteRecursively() } }
    }

    private fun entries(): List<DeviationEntry> = DeviationLedger.read(ledgerRoot)

    // ── 超时（串行路）：真跑 ToolRunner，慢工具真睡过时限 ─────────────────────

    @Test
    fun `串行真跑_慢工具超时_账加一行且字段是真量出来的`() {
        val runner = ToolRunner(Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } })
        val spec = ToolSpec(
            name = "slow_probe",
            description = "测试用慢工具（执行器真睡过时限）",
            inputSchema = jsonObjectOf("type" to "object", "properties" to jsonObjectOf()),
            permission = ToolPermission.FREE,
            timeoutMs = 120L,
        ) {
            Thread.sleep(600L)
            ToolOutcome.Ok("迟到的结果")
        }

        val outcome = runner.run(spec, JSONObject())

        // 主流程结局不变：照旧拿到超时原文（判据②）——记账没有改判定。
        assertTrue("结局是超时失败", outcome is ToolOutcome.Failed)
        assertTrue("原文是超时那句", (outcome as ToolOutcome.Failed).error.contains("执行超时"))
        // 账 +1 行：由引擎超时分支落的，不是测试写的。
        val got = entries()
        assertEquals("账上恰好一行", 1, got.size)
        val e = got.single()
        assertEquals("类别是超时", DeviationKind.TIMEOUT, e.kind)
        assertEquals("出处是本轮", runId, e.runId)
        assertEquals("卡在工具＝真工具名", "slow_probe", e.facts[DeviationEntry.F_TOOL])
        assertEquals("上限＝真时限", "120", e.facts[DeviationEntry.F_LIMIT_MS])
        assertTrue(
            "实到毫秒是量出来的（≥时限，不到离谱）",
            (e.facts[DeviationEntry.F_ELAPSED_MS]?.toLongOrNull() ?: -1L) in 120L..60_000L,
        )
    }

    // ── 超时（并行路）：真跑 ToolBatchRunner，到点未回 ────────────────────────

    @Test
    fun `并行真跑_到点未回_账加一行且limit是真时限`() {
        val pool = Executors.newFixedThreadPool(2) { r -> Thread(r).apply { isDaemon = true } }
        val runner = ToolBatchRunner(pool = pool, pollMs = 10L)
        val call = ToolCall("c-1", "slow_read", JSONObject().put("path", "资料库/慢.txt"))
        val stage = ToolStage.Parallel(listOf(call))

        val items = runner.runParallel(
            stage,
            timeoutOf = { 150L },
            execute = { _, _ ->
                Thread.sleep(800L)
                throw IllegalStateException("测试用慢工具（到点前不会回来）")
            },
            shouldStop = { false },
        )
        pool.shutdownNow()

        // 主流程结局不变：照旧按超时收口（判据②）。
        val item = items.single()
        assertTrue("结局是超时失败", item.result is GuardPipeline.Result.Failed)
        assertTrue("原文是超时那句", (item.result as GuardPipeline.Result.Failed).error.contains("执行超时"))
        // 账 +1 行：调度层超时收口落的。
        val got = entries()
        assertEquals("账上恰好一行", 1, got.size)
        val e = got.single()
        assertEquals("类别是超时", DeviationKind.TIMEOUT, e.kind)
        assertEquals("卡在工具＝真工具名", "slow_read", e.facts[DeviationEntry.F_TOOL])
        assertEquals("上限＝真时限", "150", e.facts[DeviationEntry.F_LIMIT_MS])
        assertTrue(
            "实到毫秒是量出来的（≥时限）",
            (e.facts[DeviationEntry.F_ELAPSED_MS]?.toLongOrNull() ?: -1L) >= 150L,
        )
    }

    // ── 挂起超时：真跑 ConfirmFlow，超时钟到线 ────────────────────────────────

    /** 内存假件（同 ConfirmFlowTest：只记清库次数与落库行）。 */
    private class FakeStore : PendingStore {
        var cleared = 0
        var row: PendingRow? = null
        override fun clear(sessionId: Long) {
            cleared++
            row = null
        }

        override fun load(sessionId: Long): PendingRow? = row
    }

    @Test
    fun `确认卡真跑到线_挂起超时_账加一行且挂起时刻与落库行对得上`() = runBlocking {
        val ws = Files.createTempDirectory("baopu-386-ws").toFile()
        val store = FakeStore()
        val persistedAt = mutableListOf<Long>()
        val flow = ConfirmFlow(
            pipeline = GuardPipeline(
                ToolRegistry().apply { register(WriteFileTool.spec { ws }) },
                { ws },
                { null },
                ToolRunner(Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }),
            ),
            store = store,
            // 超时钟（J3 同款）：前台已累计 16 分钟 > 15 分钟线。
            foregroundElapsedMs = { 16 * 60 * 1000L },
            onPersistPending = { _, _, _, _, at -> persistedAt += at },
            onRequestCard = { },
        )
        val call = ToolCall("call-t", "write_file", JSONObject().put("path", "t.txt").put("content", "内容"))

        var outcome: ConfirmFlow.Outcome? = null
        val job: Job = launch { outcome = flow.enter(1L, 1, call) { _, _ -> null } }
        repeat(400) {
            if (!job.isActive) return@repeat
            delay(5)
        }
        job.join()

        // 主流程结局不变：照旧 TimedOut＋清 pending（判据②）。
        assertTrue("结局是超时", outcome is ConfirmFlow.Outcome.TimedOut)
        assertEquals("超时清 pending", 1, store.cleared)
        assertTrue("卡确实落过库（挂起时刻的真值来源）", persistedAt.isNotEmpty())
        // 账 +1 行：ConfirmFlow 挂起等到线落的。
        val got = entries()
        assertEquals("账上恰好一行", 1, got.size)
        val e = got.single()
        assertEquals("类别是挂起超时", DeviationKind.SUSPEND_TIMEOUT, e.kind)
        assertEquals("出处是本轮", runId, e.runId)
        assertEquals(
            "挂起时刻＝落 pending 那一刻（真实状态对账）",
            persistedAt.single(),
            e.facts[DeviationEntry.F_SUSPENDED_AT]?.toLongOrNull(),
        )
        assertTrue(
            "问题摘要＝卡面摘要（真 summary，卡上那行）",
            (e.facts[DeviationEntry.F_QUESTION] ?: "").contains("t.txt"),
        )
        assertEquals("已清库 ⇒ 不再挂起", "否", e.facts[DeviationEntry.F_PENDING])
        // 块尾必须收在 Unit（runBlocking 返回值会被 JUnit4 当方法签名看）。
        ws.deleteRecursively()
        Unit
    }

    // ── 超步数：引擎步循环归真机，这里打生产落账口（与刹车块逐字同一口） ──────

    @Test
    fun `超步数_生产落账口_账加一行且字段齐`() {
        // 步数刹车的真实形状：跑到 RunRules.MAX_STEPS 命中那一步。
        DeviationWiring.recordOverSteps(RunRules.MAX_STEPS, RunRules.MAX_STEPS, RunRules.MAX_STEPS)

        val got = entries()
        assertEquals("账上恰好一行", 1, got.size)
        val e = got.single()
        assertEquals("类别是超步数", DeviationKind.OVER_STEPS, e.kind)
        assertEquals("出处是本轮", runId, e.runId)
        assertEquals("实到步数＝真步数", "300", e.facts[DeviationEntry.F_STEPS])
        assertEquals("上限＝真上限", "300", e.facts[DeviationEntry.F_LIMIT])
        assertEquals("越过的那一步＝真步数", "300", e.facts[DeviationEntry.F_AT_STEP])
    }
}
