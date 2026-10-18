// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.CompletionResult
import com.example.zhengdao.baopu.CompressionRecord
import com.example.zhengdao.baopu.Compressor
import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩内核单测（第十三批 A 段 · 派工单 §6.1 的 J3/J6/J7/J8 + 两条边界）。
 *
 * 为什么能 JVM 直测：`CompressionRunner` 把「真 SQLite」与「真网络」都**注入**
 * 了（[CompressionStore] 与 `complete` 闭包）——本仓无 Robolectric，
 * SQLiteOpenHelper 进不了 JVM，不注入就只能上真机。
 *
 * [FakeStore.disk] 是「磁盘」：两个 store 实例共享它 ⇒ 能验「关库重开」
 * 这种本来只有真机才验得了的事（J8）。
 */
class CompressionRunnerTest {

    // ── 假件 ──────────────────────────────────────────────────────────────

    private class FakeStore(
        val disk: MutableMap<Long, MutableList<CompressionRecord>> = mutableMapOf(),
        val failedAt: MutableMap<Long, Int> = mutableMapOf(),
        val skipped: MutableList<String> = mutableListOf(),
        val calls: MutableList<String> = mutableListOf(),
    ) : CompressionStore {
        override fun insert(
            sessionId: Long,
            record: CompressionRecord,
            sourceTokens: Int,
            summaryTokens: Int,
        ) {
            disk.getOrPut(sessionId) { mutableListOf() }.add(record)
        }

        override fun latest(sessionId: Long): CompressionRecord? =
            disk[sessionId]?.maxByOrNull { it.createdAt }

        override fun lastFailedStep(sessionId: Long): Int? = failedAt[sessionId]

        override fun markFailed(sessionId: Long, step: Int, code: Int, message: String) {
            failedAt[sessionId] = step
            calls += "failed:$sessionId:$step:$code"
        }

        override fun markSkipped(sessionId: Long, step: Int, reason: String) {
            skipped += reason
            calls += "skipped:$sessionId:$step"
        }
    }

    private fun provider() = Provider(
        id = "t",
        label = "测试通道",
        protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://x/v1",
        model = "test-model",
    )

    private fun msg(id: String, text: String = "消息$id 的正文") =
        HistoryMessage(id = id, role = HistoryMessage.ROLE_USER, text = text)

    /** 8 条历史：最近 [Compressor.KEEP_RECENT] 条（h5–h8）不压，h1–h4 进选区。 */
    private fun history(): List<HistoryMessage> =
        (1..8).map { msg("h$it", "第 $it 条的正文内容") }

    private fun runner(
        result: CompletionResult,
        store: CompressionStore,
        freeAvailable: Boolean = true,
        seen: MutableList<List<LlmMessage>> = mutableListOf(),
    ) = CompressionRunner(
        complete = { messages, _, _ -> seen += messages; result },
        store = store,
        log = {},
        freeAvailable = { freeAvailable },
        now = { 1_700_000_000_000L },
    )

    // ── J3 成功路径落库 ───────────────────────────────────────────────────

    @Test
    fun `J3_成功路径落库_摘要原样_compressedIds与选区一致`() {
        val store = FakeStore()
        val summary = "目标：整理资料\n已定的决定：三格式\n已排除的路：无\n未决：无\n涉及的文件路径：资料库/整理/x.txt"
        val r = runner(CompletionResult.Ok(summary, "test-model"), store).runOnce(
            sessionId = 1L,
            history = history(),
            provider = provider(),
            step = 3,
        )

        assertTrue("应当是 Ok：$r", r is CompressionRunner.Outcome.Ok)
        val record = (r as CompressionRunner.Outcome.Ok).record
        val rows = store.disk[1L] ?: error("compression 表没落行")
        assertEquals("落且只落一行", 1, rows.size)
        assertEquals("摘要原样（解析后的结构化文本）", record.summary, rows[0].summary)
        // 选区 = 除最近 4 条之外的全部 ⇒ h1..h4
        assertEquals(listOf("h1", "h2", "h3", "h4"), rows[0].compressedIds)
        assertEquals("模型回传（追溯谁压的）", "test-model", rows[0].model)
        assertEquals("摘要替身返回给引擎", record.summary, r.record.summary)
    }

    // ── J6 失败不炸 ───────────────────────────────────────────────────────

    @Test
    fun `J6_失败不炸_转Failed_表不新增_记了冷却锚`() {
        val store = FakeStore()
        val r = runner(CompletionResult.Failed(500, "HTTP 500 Server Error"), store).runOnce(
            sessionId = 1L,
            history = history(),
            provider = provider(),
            step = 7,
        )

        assertTrue("失败必须转成 Failed 而不是抛异常：$r", r is CompressionRunner.Outcome.Failed)
        assertEquals(500, (r as CompressionRunner.Outcome.Failed).code)
        assertTrue("失败不许落 compression 表", store.disk[1L].isNullOrEmpty())
        assertEquals("失败要留冷却锚（第 7 步）", 7, store.failedAt[1L])
    }

    // ── J7 冷却 ───────────────────────────────────────────────────────────

    @Test
    fun `J7_冷却_失败后第1步不再发请求_第11步才允许`() {
        val seen = mutableListOf<List<LlmMessage>>()
        val store = FakeStore()
        // 先在第 3 步失败一次
        runner(CompletionResult.Failed(503, "HTTP 503"), store, seen = seen)
            .runOnce(1L, history(), provider(), step = 3)
        val afterFail = seen.size

        // 第 4 步（失败后第 1 步）：冷却中 ⇒ 连请求都不发
        val cooling = runner(CompletionResult.Ok("目标：x", "m"), store, seen = seen)
            .runOnce(1L, history(), provider(), step = 4)
        assertTrue("冷却期内必须是 Cooling：$cooling", cooling is CompressionRunner.Outcome.Cooling)
        assertEquals(3 + CompressionRunner.COOLDOWN_STEPS, (cooling as CompressionRunner.Outcome.Cooling).untilStep)
        assertEquals("冷却期内不许发请求", afterFail, seen.size)

        // 第 12 步（失败后第 9 步，还在冷却）
        assertTrue(
            "第 12 步仍在冷却",
            runner(CompletionResult.Ok("目标：x", "m"), store, seen = seen)
                .runOnce(1L, history(), provider(), step = 12) is CompressionRunner.Outcome.Cooling,
        )
        assertEquals("仍不许发请求", afterFail, seen.size)

        // 第 13 步（失败后第 10 步）⇒ 允许发
        val ok = runner(CompletionResult.Ok("目标：x", "m"), store, seen = seen)
            .runOnce(1L, history(), provider(), step = 13)
        assertTrue("满 10 步后应放行：$ok", ok is CompressionRunner.Outcome.Ok)
        assertEquals("这一次真的发了请求", afterFail + 1, seen.size)
    }

    // ── J8 重启可恢复 ─────────────────────────────────────────────────────

    @Test
    fun `J8_关库重开_拿回同一条摘要与同一批ids`() {
        val disk = mutableMapOf<Long, MutableList<CompressionRecord>>()
        val first = FakeStore(disk = disk)
        val summary = "目标：整理资料\n已定的决定：三格式"
        runner(CompletionResult.Ok(summary, "test-model"), first)
            .runOnce(1L, history(), provider(), step = 2)
        val before = first.latest(1L) ?: error("先落一条")

        // 关库重开：换一个 store 实例，共享同一份「磁盘」
        val reopened = FakeStore(disk = disk)
        val after = reopened.latest(1L)
        assertEquals("摘要逐字相同", before.summary, after?.summary)
        assertEquals("被压 ids 一个不少", before.compressedIds, after?.compressedIds)
        assertEquals(listOf("h1", "h2", "h3", "h4"), after?.compressedIds)
    }

    // ── 两条边界：空选区 / 降级通道 ────────────────────────────────────────

    @Test
    fun `空选区_不发请求_直接Skipped`() {
        val seen = mutableListOf<List<LlmMessage>>()
        val store = FakeStore()
        val r = runner(CompletionResult.Ok("目标：x", "m"), store, seen = seen)
            .runOnce(1L, history().take(Compressor.KEEP_RECENT), provider(), step = 1)
        assertTrue("最近 N 条之外没有可压的 ⇒ Skipped：$r", r is CompressionRunner.Outcome.Skipped)
        assertEquals("空选区不许发请求", 0, seen.size)
        assertTrue("没压就不落表", store.disk[1L].isNullOrEmpty())
    }

    @Test
    fun `通道降级_不压_留痕skipped_degraded`() {
        val seen = mutableListOf<List<LlmMessage>>()
        val store = FakeStore()
        val r = runner(
            CompletionResult.Ok("目标：x", "m"),
            store,
            freeAvailable = false, // 免费通道不可用 ⇒ chooseChannel 降级
            seen = seen,
        ).runOnce(1L, history(), provider(), step = 5)

        assertTrue("降级通道上不许压：$r", r is CompressionRunner.Outcome.Skipped)
        assertEquals("降级不许发请求", 0, seen.size)
        assertTrue(
            "要留痕 compression_skipped_degraded（蓝总管按它认）",
            store.skipped.contains(CompressionRunner.SKIP_REASON_DEGRADED),
        )
        assertNull("降级是跳过不是失败 ⇒ 不设冷却锚", store.failedAt[1L])
    }
}
