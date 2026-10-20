// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十一批 §4：预算表 / 可用上下文 / 触发判定 / 降级顺序，各≥3 例。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    private fun provider(
        id: String = "deepseek",
        model: String = "deepseek-flash",
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

    // ── 预算表 ────────────────────────────────────────────────────────────

    @Test
    fun `预算表_DeepSeek取元数据行`() {
        val row = BudgetTable.forProvider(Providers.DEEPSEEK)
        assertEquals(1_048_576, row.contextWindow)
        assertEquals(393_216, row.maxOutput)
        assertEquals(12_000, row.systemPromptReserve)
        assertEquals(24_000, row.toolSchemaReserve)
        assertTrue("出处要写明元数据：${row.source}", row.source.contains("元数据"))
    }

    @Test
    fun `预算表_model前缀优先_更具体的行在前`() {
        val mini = BudgetTable.forProvider(provider(id = "openai-compat", model = "gpt-4o-mini"))
        val plain = BudgetTable.forProvider(provider(id = "openai-compat", model = "gpt-4o"))
        val custom = BudgetTable.forProvider(provider(id = "openai-compat", model = "my-model"))
        assertEquals("mini 专属行的预留更小", 6_000, mini.systemPromptReserve)
        assertEquals("gpt-4o 走自己那行", 8_000, plain.systemPromptReserve)
        assertEquals("表里没有的模型走通道兜底行", 8_000, custom.systemPromptReserve)
        assertEquals(128_000, mini.contextWindow)
    }

    @Test
    fun `预算表_未知通道回退现有回退值`() {
        val row = BudgetTable.forProvider(provider(id = "my-channel", model = "x"))
        assertEquals(ContextAssembler.DEFAULT_WINDOW_TOKENS, row.contextWindow)
        assertEquals(ContextAssembler.ContextBudgets.DEFAULT_OUTPUT_TOKENS, row.maxOutput)
        assertTrue(row.source.contains("兜底"))
    }

    @Test
    fun `预算表_Provider元数据优先于表`() {
        val row = BudgetTable.forProvider(
            provider(id = "openai-compat", model = "gpt-4o", window = 64_000, max = 8_000),
        )
        assertEquals("元数据是 provider 自己的事实", 64_000, row.contextWindow)
        assertEquals(8_000, row.maxOutput)
    }

    // ── 可用上下文（输出预留 / 系统提示 / 工具 schema） ──────────────────

    @Test
    fun `可用上下文_扣掉输出与两笔预留`() {
        assertEquals(65_000, TokenBudget.availableContext(budget()))
    }

    @Test
    fun `可用上下文_实测提示更大时按实测扣`() {
        val b = budget()
        assertEquals(
            "实测系统 8k、schema 12k ⇒ 100k-20k-8k-12k",
            60_000,
            TokenBudget.availableContext(b, systemTokens = 8_000, toolTokens = 12_000),
        )
        assertEquals(
            "实测比预留小 ⇒ 仍按预留扣（至少留这么多）",
            65_000,
            TokenBudget.availableContext(b, systemTokens = 100, toolTokens = 100),
        )
    }

    @Test
    fun `可用上下文_输出预留过大也不为负`() {
        assertEquals(0, TokenBudget.availableContext(budget(window = 10_000, maxOutput = 20_000)))
    }

    // ── 触发判定 ─────────────────────────────────────────────────────────

    @Test
    fun `触发判定_严格大于_等于不压`() {
        val b = budget(window = 10_000, maxOutput = 1_000)
        assertFalse(TokenBudget.needsCompression(7_000, b))
        assertTrue(TokenBudget.needsCompression(7_001, b))
    }

    @Test
    fun `触发线复用现有口径_70pct且900k封顶`() {
        assertEquals(7_000.0, TokenBudget.thresholdOf(budget(window = 10_000, maxOutput = 1_000)), 0.0)
        val big = budget(window = 1_048_576, maxOutput = 1_000)
        assertEquals(734_003.2, TokenBudget.thresholdOf(big), 0.01)
    }

    @Test
    fun `读数_可用与触发线各自有余量`() {
        val u = TokenBudget.usage(inputTokens = 60_000, budget = budget())
        assertEquals(65_000, u.availableTokens)
        assertEquals(5_000, u.availableHeadroom)
        assertEquals(10_000, u.triggerHeadroom)
        assertFalse(u.overAvailable)
        assertFalse(u.overThreshold)
    }

    // ── 降级顺序（压缩 → 截断 → 拒绝） ──────────────────────────────────

    @Test
    fun `降级阶梯_顺序钉死`() {
        assertEquals(
            listOf(BudgetStep.COMPRESS, BudgetStep.TRUNCATE, BudgetStep.REJECT),
            DegradationPolicy.LADDER,
        )
    }

    @Test
    fun `降级计划_溢出时先压缩再截断`() {
        val u = TokenBudget.usage(inputTokens = 70_000, budget = budget())
        val plan = DegradationPolicy.plan(u)
        assertEquals(listOf(BudgetStep.COMPRESS, BudgetStep.TRUNCATE), plan.steps)
        assertTrue(plan.reason.contains("先压缩"))
    }

    @Test
    fun `降级计划_压缩不可用时才直接截断`() {
        val u = TokenBudget.usage(inputTokens = 70_000, budget = budget())
        val plan = DegradationPolicy.plan(u, compressionAvailable = false)
        assertEquals(listOf(BudgetStep.TRUNCATE), plan.steps)
        assertTrue(plan.reason.contains("压缩通道不可用"))
    }

    @Test
    fun `降级计划_可用空间低于下限时拒绝`() {
        val u = TokenBudget.usage(
            inputTokens = 10,
            budget = budget(window = 1_000, maxOutput = 900, system = 100, tools = 100),
        )
        assertEquals(0, u.availableTokens)
        // 阶梯仍按「压缩 → 拒绝」排列；截断这一级不可达（不够 MIN_KEEP_TOKENS）
        assertEquals(listOf(BudgetStep.COMPRESS, BudgetStep.REJECT), DegradationPolicy.plan(u).steps)
        assertTrue(DegradationPolicy.plan(u).reason.contains("拒绝"))
    }

    @Test
    fun `降级计划_装得下时为空`() {
        val u = TokenBudget.usage(inputTokens = 60_000, budget = budget())
        assertTrue(DegradationPolicy.plan(u).steps.isEmpty())
    }

    // ── 默认路径关 ───────────────────────────────────────────────────────

    @Test
    fun `默认关闭_执行返回空表`() {
        val u = TokenBudget.usage(inputTokens = 70_000, budget = budget())
        val plan = DegradationPolicy.plan(u)
        assertFalse(DegradationPolicy.ENGAGED_BY_DEFAULT)
        assertTrue("默认不执行任何降级动作", DegradationPolicy.execute(plan).isEmpty())
        assertEquals(
            "显式打开才拿到步骤",
            listOf(BudgetStep.COMPRESS, BudgetStep.TRUNCATE),
            DegradationPolicy.execute(plan, engaged = true),
        )
    }
}
