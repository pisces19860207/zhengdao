// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百四十四批 · 纯函数边界测试第二批（第 1 组）。
//
// `ContextBudget` 的既有用例（ContextBudgetTest）钉的是「表怎么取、降级怎么排」；
// 本组只补**空输入 / 恰好到线 / 保留输出**三类边界，不改实现：
// 空段估算为 0、零或负的元数据当没给、可用空间下限两侧、恰好等于各条线不判超。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetEdgeTest {

    private fun provider(
        id: String = "openai-compat",
        model: String = "gpt-4o",
        window: Int? = null,
        max: Int? = null,
    ) = Provider(
        id = id,
        label = "测试通道",
        protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://example.com/v1",
        model = model,
        contextWindow = window,
        maxOutput = max,
    )

    private fun budget(
        window: Int = 100_000,
        maxOutput: Int = 20_000,
        system: Int = 5_000,
        tools: Int = 10_000,
    ) = ModelBudget(
        providerId = "test",
        modelPrefix = "",
        contextWindow = window,
        maxOutput = maxOutput,
        systemPromptReserve = system,
        toolSchemaReserve = tools,
        source = "测试",
    )

    // ── 空输入 ────────────────────────────────────────────────────────────

    @Test
    fun `空段估算为零_不污染合计`() {
        assertEquals(0, TokenBudget.estimateInput(emptyList()))
        assertEquals("空串段就是 0", 0, TokenBudget.estimateInput(listOf("", "")))
        assertEquals(
            "混合时只有非空段计入",
            TokenEstimator.estimate("abcd"),
            TokenBudget.estimateInput(listOf("", "abcd")),
        )
    }

    // ── 元数据的边界值 ────────────────────────────────────────────────────

    @Test
    fun `预算行_零或负元数据当没给_回落到表值`() {
        val zero = BudgetTable.forProvider(provider(window = 0, max = 0))
        assertEquals(128_000, zero.contextWindow)
        assertEquals(16_384, zero.maxOutput)
        val negative = BudgetTable.forProvider(provider(window = -1, max = -100))
        assertEquals(128_000, negative.contextWindow)
        assertEquals(16_384, negative.maxOutput)
    }

    @Test
    fun `预算行_非法数值构造即抛_不许静默兜底`() {
        val ok = budget()
        assertThrows(IllegalArgumentException::class.java) { ok.copy(contextWindow = 0) }
        assertThrows(IllegalArgumentException::class.java) { ok.copy(maxOutput = 0) }
        assertThrows(IllegalArgumentException::class.java) { ok.copy(systemPromptReserve = -1) }
        assertThrows(IllegalArgumentException::class.java) { ok.copy(toolSchemaReserve = -1) }
    }

    // ── 保留输出 / 两笔预留的边界 ─────────────────────────────────────────

    @Test
    fun `可用上下文_负的实测预留按零处理_仍扣配置预留`() {
        assertEquals(
            "负实测不能变成「加回来」",
            65_000,
            TokenBudget.availableContext(budget(), systemTokens = -1, toolTokens = -50),
        )
    }

    @Test
    fun `MIN_KEEP边界_恰好512还能截断_511只能拒绝`() {
        val at512 = budget(window = 10_000, maxOutput = 1_000, system = 8_488, tools = 0)
        assertEquals(512, TokenBudget.availableContext(at512))
        assertEquals(
            "可用恰好等于下限 ⇒ 截断这一级仍可达",
            listOf(BudgetStep.COMPRESS, BudgetStep.TRUNCATE),
            DegradationPolicy.plan(TokenBudget.usage(inputTokens = 513, budget = at512)).steps,
        )

        val at511 = budget(window = 10_000, maxOutput = 1_000, system = 8_489, tools = 0)
        assertEquals(511, TokenBudget.availableContext(at511))
        val u511 = TokenBudget.usage(inputTokens = 512, budget = at511)
        assertEquals(
            "低于下限 ⇒ 只到拒绝级",
            listOf(BudgetStep.COMPRESS, BudgetStep.REJECT),
            DegradationPolicy.plan(u511).steps,
        )
        assertTrue(
            "压缩不可用时更不能假装能截断",
            DegradationPolicy.plan(u511, compressionAvailable = false).steps == listOf(BudgetStep.REJECT),
        )
    }

    // ── 恰好到线 ──────────────────────────────────────────────────────────

    @Test
    fun `恰好等于可用空间不判溢出_多一才溢出`() {
        val atLine = TokenBudget.usage(inputTokens = 65_000, budget = budget())
        assertEquals(65_000, atLine.availableTokens)
        assertEquals(0, atLine.availableHeadroom)
        assertFalse("等于不算超", atLine.overAvailable)
        assertTrue("装得下 ⇒ 无降级步骤", DegradationPolicy.plan(atLine).steps.isEmpty())

        val over = TokenBudget.usage(inputTokens = 65_001, budget = budget())
        assertEquals(-1, over.availableHeadroom)
        assertTrue(over.overAvailable)
    }

    @Test
    fun `恰好等于触发线不判超_多一才超`() {
        val atLine = TokenBudget.usage(inputTokens = 70_000, budget = budget())
        assertEquals(70_000.0, atLine.thresholdTokens, 0.0)
        assertFalse("等于触发线不压", atLine.overThreshold)
        assertEquals(0, atLine.triggerHeadroom)

        val over = TokenBudget.usage(inputTokens = 70_001, budget = budget())
        assertTrue(over.overThreshold)
        assertEquals(-1, over.triggerHeadroom)
    }

    @Test
    fun `显式打开降级_空步骤仍是空表`() {
        val plan = DegradationPolicy.plan(TokenBudget.usage(inputTokens = 1, budget = budget()))
        assertTrue(plan.steps.isEmpty())
        assertTrue(DegradationPolicy.execute(plan, engaged = true).isEmpty())
    }
}
