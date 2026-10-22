// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百四十九批 组2：`thresholdOf` 接压缩触发读数；`budgetOf` **刻意不接**（数值不同 ⇒ 报告）。
//
// 口径演进见 CompressTriggerTest 的头（第十三批 0.85 → 第十五批 min(0.85,120k) →
// 2026-10-19 min(窗口 × 0.70, 900k)）。本文件不重复钉数值口径，只钉**接线**：
// 判定用的那条线和日志显示的那个数，必须是同一个出处算出来的。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.RunRules
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩触发的「单一取线口」门禁 ＋ `budgetOf` 未接的读数存档。
 *
 * 为什么门禁用源码形状、判定用行为：要钉的是「调用点不许再算一遍乘积」这种结构性事实
 * ——RunEngine 的日志行在 Compose/协程/SQLite 里，JVM 打不到；而「显示的那个数就是
 * 判定用的那个数」可以、也必须用行为用例钉（下面 ③）。同款门禁范式见
 * ChannelSwitchTruthGateTest／CompressionSummaryShadowGateTest。
 */
class CompressTriggerWiringGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val engineFile = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    private fun provider(contextWindow: Int? = null, maxOutput: Int? = null) = Provider(
        id = "t", label = "t", protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://x/v1", model = "m",
        contextWindow = contextWindow, maxOutput = maxOutput,
    )

    // ── ① 接线：引擎的触发日志从取线口读数 ──────────────────────────────

    @Test
    fun `引擎压缩日志的触发线取自ContextBudgets_thresholdOf`() {
        val code = codeOnly(read(engineFile))
        assertTrue(
            "RunEngine 的「压缩触发」读数没接取线口 ⇒ 日志里的线与判定用的线是两笔账",
            code.contains("ContextAssembler.ContextBudgets.thresholdOf(provider)"),
        )
        assertFalse(
            "取线口又退化回按 windowTokens 手写乘积（两处各算一遍＝漂移入口）",
            Regex("windowTokens\\s*\\*\\s*0?\\.?[0-9]").containsMatchIn(code) ||
                code.contains("COMPRESS_THRESHOLD"),
        )
    }

    @Test
    fun `全仓只有ContextAssembler里写一次比例`() {
        // 0.70 / 900k 这两个数字只该出现在判据内核（BaopuKernel.Budget）与
        // compressionThreshold 的实现处；出现在别处就是第二份事实。
        val engine = codeOnly(read(engineFile))
        assertFalse("引擎里出现了裸的 0.7 比例", Regex("""\b0\.7\b""").containsMatchIn(engine))
        assertFalse("引擎里出现了裸的 900k 封顶", engine.contains("900_000"))
    }

    // ── ② 取线口 ≡ 判定（数值同源）──────────────────────────────────────

    @Test
    fun `thresholdOf就是判定内部那条compressionThreshold`() {
        for (window in listOf(1_048_576, 128_000, 32_000, 2_000_000, 1_000)) {
            assertEquals(
                "窗口 $window：取线口与判定不同源",
                ContextAssembler.compressionThreshold(window),
                ContextAssembler.ContextBudgets.thresholdOf(provider(contextWindow = window)),
                1e-9,
            )
        }
        // 元数据缺失 ⇒ 回退窗口也是同一个出处
        assertEquals(
            "ctx 缺失时取线口按回退窗口（1M）算，不许另立",
            ContextAssembler.compressionThreshold(ContextAssembler.DEFAULT_WINDOW_TOKENS),
            ContextAssembler.ContextBudgets.thresholdOf(provider()),
            1e-9,
        )
    }

    @Test
    fun `封顶与比例都按公式_1M是734k_2M撞900k护栏`() {
        assertEquals(1_048_576 * 0.70, ContextAssembler.ContextBudgets.thresholdOf(provider(contextWindow = 1_048_576)), 1e-9)
        assertEquals(
            RunRules.COMPRESS_ABS_MAX_TOKENS.toDouble(),
            ContextAssembler.ContextBudgets.thresholdOf(provider(contextWindow = 2_000_000)),
            1e-9,
        )
    }

    // ── ③ 显示的那个数＝判定用的那个数（行为钉，防阈值漂移）──────────────

    @Test
    fun `日志取整后的触发线正好卡在判定边界`() {
        // 引擎打印的是 thresholdOf(...).toInt()。若日志与判定不同源，这条就红：
        // 显示的数会把「该压/不该压」的边界挪到别处去。
        val p = provider(contextWindow = 1_048_576)
        val shown = ContextAssembler.ContextBudgets.thresholdOf(p).toInt()
        assertTrue(
            "显示 $shown 之后判定却说还没到线（日志与判定漂移）",
            !ContextAssembler.needsCompressing(shown, ContextAssembler.ContextBudgets.windowOf(p)),
        )
        assertTrue(
            "显示 $shown 之后判定却说已经超线（日志与判定漂移）",
            ContextAssembler.needsCompressing(shown + 1, ContextAssembler.ContextBudgets.windowOf(p)),
        )
    }

    // ── ④ budgetOf：本批刻意不接，读数存档在这里 ────────────────────────

    @Test
    fun `budgetOf与线上输入线数值不同_所以本批不拿它替换任何一条线`() {
        val deepSeek = BudgetTable.rows.first { it.providerId == "deepseek" && it.modelPrefix == "deepseek" }
        val p = provider(contextWindow = deepSeek.contextWindow, maxOutput = deepSeek.maxOutput)
        val budgetOf = ContextAssembler.ContextBudgets.budgetOf(p)
        val online = TokenBudget.availableContext(deepSeek)
        // 两个数都是量出来的，写进断言消息里，报告直接引用（不是从 KDoc 抄的）。
        assertTrue(
            "budgetOf=$budgetOf vs availableContext=$online ⇒ 口径不同：budgetOf=min(窗口×0.6, 输出×0.9)，" +
                "线上输入线=窗口−输出−两笔预留。派工单口径「数值不同就保持现状」",
            budgetOf != online,
        )
        assertEquals("budgetOf 的口径本身（min(629145, 353894)）", 353_894, budgetOf)
        assertEquals("线上输入预算（1_048_576−393_216−12_000−24_000）", 619_360, online)
    }

    @Test
    fun `引擎没有把budgetOf接成任何一条现有线`() {
        val code = codeOnly(read(engineFile))
        assertFalse(
            "budgetOf 被接到了引擎里——它与 availableContext 数值不同，接线＝改合法行为",
            code.contains("ContextBudgets.budgetOf("),
        )
    }
}
