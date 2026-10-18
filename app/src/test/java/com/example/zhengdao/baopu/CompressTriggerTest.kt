// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第十五批压缩触发口径 ＋ 2026-10-19 用户真机口径反转
// （逐字：「上下文压缩的太早了点，100万上下文，怎么也得当前会话的百分之 70 左右才自动压缩吧」）。
//
// 口径演进：
// - 第十三批：触发线 = 窗口 × 0.85（1M 窗口 ⇒ 891k，手机永远够不着）；
// - 第十五批：`min(窗口 × 0.85, 120k)` ＋ 「可压旧消息 ≥ 60 条」两条任一满足就压
//   ——1M 窗口上 120k 封顶相当于 **11% 就压**，60 条消息在 1k token 时也能触发；
// - **本版（2026-10-19）**：只看 `min(窗口 × 0.70, 900k)`，1M 窗口 ⇒ **734,003 token（约 70%）**；
//   条数线撤掉（它把 70% 的口径架空了），条数只剩「能不能压」的读数意义。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.RunRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** J1–J6：压缩触发口径（窗口 70%，绝对护栏 900k，条数不再触发）。 */
class CompressTriggerTest {

    private fun provider(contextWindow: Int? = null, maxOutput: Int? = null) = Provider(
        id = "t", label = "t", protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://x/v1", model = "m",
        contextWindow = contextWindow, maxOutput = maxOutput,
    )

    private fun msg(id: String, text: String = "消息$id") =
        HistoryMessage(id = id, role = HistoryMessage.ROLE_USER, text = text)

    private fun historyOf(n: Int) = (1..n).map { msg("h$it") }

    /** 1M 窗口的触发线（70%）——多处复用，避免散写数字。 */
    private val oneMThreshold: Double = 1_048_576 * 0.70

    // ── J1：1M 窗口按 70%，不再是 120k ─────────────────────────────────────

    @Test
    fun `J1_1M窗口触发线是734k_不再是120k`() {
        assertEquals(
            "纯函数口径：min(1_048_576 × 0.70, 900_000) = 734,003.2",
            oneMThreshold,
            ContextAssembler.compressionThreshold(1_048_576),
            1e-9,
        )
        assertEquals(
            "Provider 字段口径（元数据 ctx=1M）同值",
            oneMThreshold,
            ContextAssembler.ContextBudgets.thresholdOf(provider(contextWindow = 1_048_576)),
            1e-9,
        )
        assertEquals(
            "常量落在 RunRules（纯判据内核）",
            900_000, RunRules.COMPRESS_ABS_MAX_TOKENS,
        )
        assertEquals("阈值比例 0.70", 0.70, ContextAssembler.COMPRESS_THRESHOLD, 1e-9)
    }

    // ── J2：小窗口仍按比例，护栏不碰它 ─────────────────────────────────────

    @Test
    fun `J2_8k窗口按比例5734`() {
        assertEquals(
            8_192 * ContextAssembler.COMPRESS_THRESHOLD,
            ContextAssembler.compressionThreshold(8_192), 1e-9,
        )
        assertEquals(5_734.4, ContextAssembler.compressionThreshold(8_192), 1e-9)
        assertEquals(
            "Provider 字段口径同上",
            8_192 * ContextAssembler.COMPRESS_THRESHOLD,
            ContextAssembler.ContextBudgets.thresholdOf(provider(contextWindow = 8_192)),
            1e-9,
        )
    }

    // ── J3：未声明窗口回落 1M ──────────────────────────────────────────────

    @Test
    fun `J3_未声明窗口回落1M_触发线同口径734k`() {
        assertEquals(
            oneMThreshold,
            ContextAssembler.ContextBudgets.thresholdOf(provider(contextWindow = null)),
            1e-9,
        )
        assertEquals(
            "非正数窗口当没给",
            oneMThreshold,
            ContextAssembler.ContextBudgets.thresholdOf(provider(contextWindow = 0)),
            1e-9,
        )
    }

    // ── J4：条数不再触发（这是本次反转的核心） ─────────────────────────────

    @Test
    fun `J4_可压旧消息再多_只要token没到70%就不压`() {
        val w = 1_048_576
        assertFalse("59 条不触发", ContextAssembler.needsCompressing(1_000, w, 59))
        assertFalse(
            "60 条也不再触发（旧口径在这里压的，用户说太早）",
            ContextAssembler.needsCompressing(1_000, w, 60),
        )
        assertFalse(
            "200 条也不触发——条数只决定「能不能压」，不决定「该不该压」",
            ContextAssembler.needsCompressing(1_000, w, 200),
        )
        assertTrue(
            "token 过 70% 才压（与条数无关）",
            ContextAssembler.needsCompressing(oneMThreshold.toInt() + 1, w, 0),
        )
    }

    /** 条数读数本身仍然正确（喂 UI/日志用）：复用选区函数、扣已压、不动最近 KEEP_RECENT 条。 */
    @Test
    fun `J4选区_条数复用选区函数_扣除已压且不动最近KEEP_RECENT条`() {
        assertEquals(60, ContextAssembler.pendingOldMessageCount(historyOf(64)))
        assertEquals(
            50,
            ContextAssembler.pendingOldMessageCount(
                historyOf(64),
                compressedIds = (1..10).map { "h$it" }.toSet(),
            ),
        )
        assertEquals(0, ContextAssembler.pendingOldMessageCount(historyOf(Compressor.KEEP_RECENT)))
        assertEquals(0, ContextAssembler.pendingOldMessageCount(emptyList()))
    }

    /** 端到端：assemble 传给 needsCompressing 的条数不会再触发压缩。 */
    @Test
    fun `J4端到端_64条历史但token很低_不再触发`() {
        val p = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "/w", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = historyOf(64),
            current = "",
            windowTokens = 1_048_576,
            oldMessageCount = ContextAssembler.pendingOldMessageCount(historyOf(64)),
            tokensOf = { it.length },
        )
        assertFalse("64 条历史＋低 token ⇒ 不压（旧口径在这压）", p.needsCompressing)
    }

    // ── J5：严格大于（恰好压线不触发） ─────────────────────────────────────

    @Test
    fun `J5_恰好等于触发线不压_多一才压`() {
        val w = 1_048_576
        val line = ContextAssembler.compressionThreshold(w).toInt()
        assertFalse("恰好压线不触发", ContextAssembler.needsCompressing(line, w))
        assertTrue("多一 token 才触发", ContextAssembler.needsCompressing(line + 1, w))
    }

    // ── J6：默认参数不破坏老调用 ───────────────────────────────────────────

    @Test
    fun `J6_不传条数时行为只取决于token`() {
        val w = 8_192
        val t = ContextAssembler.compressionThreshold(w) // 5734.4
        assertFalse("估算 = floor(线) 不触发", ContextAssembler.needsCompressing(t.toInt(), w))
        assertTrue("估算 = floor(线)+1 触发", ContextAssembler.needsCompressing(t.toInt() + 1, w))
        assertFalse(
            "1k token / 1M 窗口 ⇒ 不压（哪怕历史很长）",
            ContextAssembler.needsCompressing(1_000, 1_048_576),
        )
    }
}
