// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十六批 §2B（压缩增量：压过的不再重发）与 §4 判据 J8/J9/J10/J11。
// 房型照 [CompressionRunnerTest]：真 SQLite / 真网络注入（FakeStore + complete 闭包）
// ⇒ JVM 直测，不上真机。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.CompletionResult
import com.example.zhengdao.baopu.CompressionRecord
import com.example.zhengdao.baopu.Compressor
import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.TokenEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩增量单测（第十六批第二件）。
 *
 * 四条判据的靶心：
 * - J8：已压的原文**不许**再进请求体（「压过的不再重发」——否则长会话越压越慢，
 *   最后必然撞压缩模型窗口，压缩永久失败）；
 * - J9：单次上限 200 条，超了只压**最旧的**（不许整批拒绝，也不许取最新的）；
 * - J10：摘要增量（乙案）——旧摘要的实质内容不丢，超限丢最旧段并留日志；
 * - J11：不传 skipIds ⇒ 请求体与第十三批**逐字节一致**（老调用零扰动）。
 */
class CompressionIncrementTest {

    // ── 假件（照 CompressionRunnerTest 房型） ─────────────────────────────

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

    /** 每条正文都唯一（含序号），请求体里的「出现 0 次 / 恰 1 次」才有判头。 */
    private fun msg(i: Int) =
        HistoryMessage(id = "h$i", role = HistoryMessage.ROLE_USER, text = "第${i}号原文")

    private fun history(n: Int): List<HistoryMessage> = (1..n).map { msg(it) }

    private fun runner(
        result: CompletionResult,
        store: CompressionStore,
        freeAvailable: Boolean = true,
        seen: MutableList<List<LlmMessage>> = mutableListOf(),
        logs: MutableList<String> = mutableListOf(),
    ) = CompressionRunner(
        complete = { messages, _, _ -> seen += messages; result },
        store = store,
        log = { logs += it },
        freeAvailable = { freeAvailable },
        now = { 1_700_000_000_000L },
    )

    // ── J8 已压不重发 ─────────────────────────────────────────────────────

    /**
     * 124 条历史 ⇒ 基线选区 h1..h120（最近 KEEP_RECENT=4 条不压）；
     * 其中 h1..h60 已压过 ⇒ 请求体里这 60 条原文出现 0 次，新选区只剩 60 条。
     * （派工单 J8 写的 120/60/60 没扣 KEEP_RECENT=4，算术对不上，fixture 改 124/60/60，
     * 判据本意不变——偏差记交付报告。）
     */
    @Test
    fun `J8_已压不重发_skipIds扣除后选区只剩未压的60条`() {
        val seen = mutableListOf<List<LlmMessage>>()
        val store = FakeStore()
        val skipIds = (1..60).map { "h$it" }.toSet()

        val r = runner(CompletionResult.Ok("目标：x", "m"), store, seen = seen)
            .runOnce(1L, history(124), provider(), step = 1, skipIds = skipIds)

        assertTrue("应当 Ok：$r", r is CompressionRunner.Outcome.Ok)
        val record = (r as CompressionRunner.Outcome.Ok).record
        assertEquals("新选区 = 未压的 60 条（h61..h120）", (61..120).map { "h$it" }, record.compressedIds)
        val userBody = seen.single().last().content
        for (i in 1..60) {
            assertTrue("已压的 h$i 原文不许再进请求体", !userBody.contains("第${i}号原文"))
        }
        for (i in 61..120) {
            assertTrue("未压的 h$i 应在请求体里", userBody.contains("第${i}号原文"))
        }
    }

    /** 扣除后选区为空 ⇒ 不发请求、不落库，Skipped（不许拿空请求去烧通道）。 */
    @Test
    fun `全被压过_扣除后选区空_不发请求Skipped`() {
        val seen = mutableListOf<List<LlmMessage>>()
        val store = FakeStore()
        val skipIds = setOf("h1", "h2", "h3", "h4") // 8 条历史的基线选区正好是这 4 条

        val r = runner(CompletionResult.Ok("目标：x", "m"), store, seen = seen)
            .runOnce(1L, history(8), provider(), step = 1, skipIds = skipIds)

        assertTrue("应当 Skipped：$r", r is CompressionRunner.Outcome.Skipped)
        assertEquals("选区为空不许发请求", 0, seen.size)
        assertTrue("没压就不落表", store.disk[1L].isNullOrEmpty())
    }

    // ── J9 单次上限 ───────────────────────────────────────────────────────

    /** 300 条历史 ⇒ 未压 296 条 > 200 ⇒ 只压最旧的 200（h1..h200），并留日志。 */
    @Test
    fun `J9_单次上限生效_未压296条只压最旧的200条`() {
        val seen = mutableListOf<List<LlmMessage>>()
        val logs = mutableListOf<String>()
        val store = FakeStore()

        val r = runner(CompletionResult.Ok("目标：x", "m"), store, seen = seen, logs = logs)
            .runOnce(1L, history(300), provider(), step = 1)

        assertTrue("应当 Ok（不许整批拒绝）：$r", r is CompressionRunner.Outcome.Ok)
        val record = (r as CompressionRunner.Outcome.Ok).record
        assertEquals("只压最旧的 200 条（h1..h200，不是最新的）", (1..200).map { "h$it" }, record.compressedIds)
        val userBody = seen.single().last().content
        assertTrue("请求体写明条数 200", userBody.contains("待压缩的对话（200 条）"))
        assertTrue("第200号是最后一条原文", userBody.contains("第200号原文"))
        assertTrue("第201号及以后不进本次请求", !userBody.contains("第201号原文"))
        assertTrue("超限要留日志", logs.any { it.contains("超过单次上限") })
    }

    // ── J10 摘要增量（乙案） ──────────────────────────────────────────────

    /** 20 条历史、skip 掉 6 条 ⇒ 本轮压 10 条新段；旧摘要 S1 的标记串必须还在，旧在前新在后。 */
    @Test
    fun `J10_摘要不丢旧内容_旧摘要标记串保留_旧在前新在后`() {
        val store = FakeStore()
        val skipIds = (1..6).map { "h$it" }.toSet() // 20 条 ⇒ 选区 h1..h16，扣 6 ⇒ 新段 10 条
        val s1 = "目标：旧目标甲\n已定的决定：标记串S1旧内容"
        val logs = mutableListOf<String>()

        val r = runner(CompletionResult.Ok("目标：新目标乙\n未决：无", "m"), store, logs = logs)
            .runOnce(1L, history(20), provider(), step = 1, skipIds = skipIds, previousSummary = s1)

        assertTrue("应当 Ok：$r", r is CompressionRunner.Outcome.Ok)
        val summary = (r as CompressionRunner.Outcome.Ok).record.summary
        assertTrue("旧摘要的实质内容（标记串）不能丢", summary.contains("标记串S1旧内容"))
        assertTrue("新段内容要在", summary.contains("新目标乙"))
        assertTrue("顺序：旧在前、新在后", summary.indexOf("标记串S1旧内容") < summary.indexOf("新目标乙"))
        assertTrue("合并后仍受 1024 上限约束", TokenEstimator.estimate(summary) <= CompressionRunner.MAX_TOKENS)
        assertEquals("落库的就是合并后的摘要", summary, store.disk[1L]!!.single().summary)
    }

    /** 纯函数边界：拼接后超限 ⇒ 丢最旧的段并记日志「摘要超限，丢弃最旧段」，结果 ≤ 上限。 */
    @Test
    fun `J10b_摘要超限_丢弃最旧段_日志留痕`() {
        val logs = mutableListOf<String>()
        val bigOld = "旧内容".repeat(800) // 中文按字符计 token ⇒ 约 2400，远超 maxTokens=100
        val fresh = "目标：新段"

        val merged = Compressor.mergeSummaries(bigOld, fresh, maxTokens = 100, log = { logs += it })

        assertEquals("只剩新段（最旧的段被丢）", fresh, merged)
        assertTrue("要有一句「摘要超限，丢弃最旧段」", logs.any { it.contains("摘要超限，丢弃最旧段") })
        assertTrue("丢完 ≤ 上限", TokenEstimator.estimate(merged) <= 100)
    }

    /** 纯函数边界：没有旧摘要 ⇒ 原样返回新摘要（第一次压缩零扰动）。 */
    @Test
    fun `无旧摘要_合并即新摘要原样`() {
        assertEquals("新摘要", Compressor.mergeSummaries(null, "新摘要", 1024))
        assertEquals("新摘要", Compressor.mergeSummaries("", "新摘要", 1024))
    }

    // ── J11 老调用不变 ────────────────────────────────────────────────────

    /**
     * 不传 skipIds ⇒ 请求体与第十三批**逐字节一致**（固定 fixture 全等断言——
     * system 提示与 user 正文都是硬编码字面量，不引用生产代码拼串，改一个字就红）。
     */
    @Test
    fun `J11_老调用不变_请求体与改前逐字节一致`() {
        val seen = mutableListOf<List<LlmMessage>>()
        val store = FakeStore()

        val r = runner(CompletionResult.Ok("目标：x", "m"), store, seen = seen)
            .runOnce(1L, history(8), provider(), step = 2)

        assertTrue(r is CompressionRunner.Outcome.Ok)
        val record = (r as CompressionRunner.Outcome.Ok).record
        assertEquals("选区与改前一致：h1..h4", listOf("h1", "h2", "h3", "h4"), record.compressedIds)
        val expected = listOf(
            LlmMessage(
                LlmMessage.ROLE_SYSTEM,
                "你是对话压缩器。把下面这段旧对话压成结构化摘要，只输出这些字段：\n" +
                    "目标 / 已定的决定 / 已排除的路 / 未决 / 涉及的文件路径。\n" +
                    "规则：每行一个字段，格式「字段：内容」；没有内容的字段写「无」；\n" +
                    "不许编造对话里没有的信息；保留具体路径、模型名、已定的口径原样。",
            ),
            LlmMessage(
                LlmMessage.ROLE_USER,
                "待压缩的对话（4 条）：\n" +
                    "【用户】第1号原文\n" +
                    "【用户】第2号原文\n" +
                    "【用户】第3号原文\n" +
                    "【用户】第4号原文\n",
            ),
        )
        assertEquals("请求体逐字节一致（含 system 与 user 两条）", expected, seen.single())
    }
}
