// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：docs/派工单-2026-10-18-第十五批-压缩触发口径.md §4（J1–J6 逐条一例）。
// 背景：第十三批真机 T1 实证——触发线＝窗口×0.85，1M 窗口 ⇒ 891,289 token，
// 手机会话永远够不着 ⇒ 第十五批改成两条触发任一满足就压：
// ① 估算 token ≥ min(窗口×0.85, 120_000)；② 可压旧消息 ≥ 60 条。
// J7（前缀不动）是第十三批既有回归例，在 ContextAssemblerTest，本文件不重复。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.RunRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第十五批 J1–J6：压缩触发口径（绝对上限 120k ＋ 旧消息条数 60）。 */
class CompressTriggerTest {

    private fun provider(contextWindow: Int? = null, maxOutput: Int? = null) = Provider(
        id = "t", label = "t", protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://x/v1", model = "m",
        contextWindow = contextWindow, maxOutput = maxOutput,
    )

    private fun msg(id: String, text: String = "消息$id") =
        HistoryMessage(id = id, role = HistoryMessage.ROLE_USER, text = text)

    private fun historyOf(n: Int) = (1..n).map { msg("h$it") }

    // ── J1：1M 窗口用绝对上限（不再 891k） ─────────────────────────────────

    @Test
    fun `J1_1M窗口触发线用绝对上限120k_不再891k`() {
        assertEquals(
            "纯函数口径：min(1_048_576 × 0.85, 120_000) = 120_000",
            120_000.0, ContextAssembler.compressionThreshold(1_048_576), 1e-9,
        )
        assertEquals(
            "Provider 字段口径（元数据 ctx=1M）同样封顶",
            120_000.0,
            ContextAssembler.ContextBudgets.thresholdOf(provider(contextWindow = 1_048_576)),
            1e-9,
        )
        assertEquals(
            "常量落在 RunRules（派工单 §2.1 指定位置）",
            120_000, RunRules.COMPRESS_ABS_MAX_TOKENS,
        )
    }

    // ── J2：小窗口仍按比例，封顶不碰它 ─────────────────────────────────────

    @Test
    fun `J2_8k窗口仍按比例6963_封顶不影响小窗口`() {
        assertEquals(
            8_192 * ContextAssembler.COMPRESS_THRESHOLD,
            ContextAssembler.compressionThreshold(8_192), 1e-9,
        )
        assertEquals(6_963.2, ContextAssembler.compressionThreshold(8_192), 1e-9)
        assertEquals(
            "Provider 字段口径同上",
            8_192 * ContextAssembler.COMPRESS_THRESHOLD,
            ContextAssembler.ContextBudgets.thresholdOf(provider(contextWindow = 8_192)),
            1e-9,
        )
    }

    // ── J3：未声明窗口回落 1M，同口径封顶 ──────────────────────────────────

    @Test
    fun `J3_未声明窗口回落1M_触发线同口径120k`() {
        assertEquals(
            120_000.0,
            ContextAssembler.ContextBudgets.thresholdOf(provider(contextWindow = null)),
            1e-9,
        )
        // 非正数窗口当没给（既有口径），同样封顶
        assertEquals(
            120_000.0,
            ContextAssembler.ContextBudgets.thresholdOf(provider(contextWindow = 0)),
            1e-9,
        )
    }

    // ── J4：可压旧消息 60 条触发（token 远低于线） ──────────────────────────

    @Test
    fun `J4_可压旧消息60条触发_即便token远低于触发线`() {
        val w = 1_048_576
        assertFalse("59 条不触发（对照）", ContextAssembler.needsCompressing(1_000, w, 59))
        assertTrue(
            "60 条到线 ⇒ 触发（token 才 1k，远低于 120k）",
            ContextAssembler.needsCompressing(1_000, w, 60),
        )
        assertTrue("远超线（200 条）也触发", ContextAssembler.needsCompressing(1_000, w, 200))
    }

    /** 条数口径本身：复用选区函数（最近 KEEP_RECENT 条不算）＋ 扣已压。负例 3 钉的就是这条。 */
    @Test
    fun `J4选区_条数复用选区函数_扣除已压且不动最近KEEP_RECENT条`() {
        // 64 条历史：60 条可压（64 − KEEP_RECENT(4)）——B 段口径的直接读数
        assertEquals(60, ContextAssembler.pendingOldMessageCount(historyOf(64)))
        // 已压进摘要的消息不算「可压」（否则压完仍满足条数线 ⇒ 每轮重压，C 段禁止）
        assertEquals(
            50,
            ContextAssembler.pendingOldMessageCount(
                historyOf(64),
                compressedIds = (1..10).map { "h$it" }.toSet(),
            ),
        )
        // 历史不足 KEEP_RECENT ⇒ 选区空 ⇒ 0 条
        assertEquals(0, ContextAssembler.pendingOldMessageCount(historyOf(Compressor.KEEP_RECENT)))
        assertEquals(0, ContextAssembler.pendingOldMessageCount(emptyList()))
    }

    /** assemble 端到端：条数传入即参与判据（引擎探针的接线口径）。 */
    @Test
    fun `J4端到端_assemble条数参与判据_token远低于线仍触发`() {
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
        assertTrue("64 条历史（60 条可压）即便估算远低于 120k 也触发", p.needsCompressing)
        assertTrue("条数读数确实远低于 token 线（排除 token 触发的混淆）", p.estimatedTokens < 120_000)
    }

    // ── J5：59 条不触发 ────────────────────────────────────────────────────

    @Test
    fun `J5_59条不触发_差一条也不行`() {
        assertFalse(ContextAssembler.needsCompressing(1_000, 1_048_576, 59))
        // 59 条且 token 也低于线 ⇒ 两条件都不满足 ⇒ 一定不压（C 段）
        val p = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "/w", model = "m"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = historyOf(63),
            current = "",
            windowTokens = 1_048_576,
            oldMessageCount = ContextAssembler.pendingOldMessageCount(historyOf(63)),
            tokensOf = { it.length },
        )
        assertFalse("63 条历史（59 条可压）＋ token 低于线 ⇒ 不压", p.needsCompressing)
    }

    // ── J6：默认参数不破坏老调用 ───────────────────────────────────────────

    @Test
    fun `J6_不传新参数时行为与改前一致_严格大于且条数线不参与`() {
        val w = 8_192
        val t = ContextAssembler.compressionThreshold(w) // 6963.2
        // 两参调用＝纯比例口径：恰好压线（toInt 后 ≤ 线）不触发，超一线触发
        assertFalse("估算 = floor(线) 不触发（严格大于）", ContextAssembler.needsCompressing(t.toInt(), w))
        assertTrue("估算 = floor(线)+1 触发", ContextAssembler.needsCompressing(t.toInt() + 1, w))
        // 新参数默认 0：条数线（60）绝不因缺参而误触发
        assertFalse(
            "默认 oldMessageCount=0 不触发",
            ContextAssembler.needsCompressing(1_000, 1_048_576),
        )
        // 0 条显式传入同样不触发
        assertFalse(ContextAssembler.needsCompressing(1_000, 1_048_576, 0))
    }
}
