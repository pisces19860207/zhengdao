// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十单 · MCP 工具上下文膨胀（全局预算＋按需装配）——引擎与注册表之间的接线。
//
// 每步一次（RunEngine 步循环、组装 tools 数组之前）：
// 1. 从注册表取「未过闸」的 MCP 可见清单（候选池）与内置工具的 schema 估算；
// 2. 用 [BudgetTable] / [TokenBudget]（全仓唯一预算尺）算全局工具预算，
//    按 [McpToolBudget.DEFAULT_RATIO]（可配）折出 MCP 额度；
// 3. [McpToolAssembly.plan] 装配（pin 必装 → 意图预筛 → LRU → 连接序，整装整卸）；
// 4. 装载名单写回注册表（[ToolRegistry.setMcpLoadedNames]）——请求侧三出口
//    （toolsJsonOpenAi/Anthropic/Gemini）自动只看得见名单内的 MCP 工具。
//
// 装不下的（挤出）以一句话进本轮上下文（[McpTurnAssemblyResult.notice]），
// 条数进 [ToolDeclSnapshot] 的 budget 列（188 批立好的「问题④」落点）。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.BudgetTable
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.TokenBudget
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolRegistry

/**
 * 引擎每步调一次的装配口（生产在 BaopuRuntime 接线；未接 = 不装配，行为零回归）。
 */
internal interface McpTurnAssembler {

    /**
     * 算一步的装配并把名单写进注册表。
     *
     * @param sessionId 本轮会话
     * @param intentText 本轮用户原话（null/空 = 不做意图预筛）
     * @return 读数（notice 给模型、evictedCount 给 [ToolDeclSnapshot] 的 budget 列）
     */
    fun assembleForStep(sessionId: Long, intentText: String?): McpTurnAssemblyResult
}

/** 一步装配的读数（RunEngine 消费；纯数据）。 */
internal data class McpTurnAssemblyResult(
    /** 给模型的一句话（零挤出 = null，上下文一个字不多）。 */
    val notice: String?,
    /** 被挤出的外部工具数（快照日志 budget 列）。 */
    val evictedCount: Int,
    val loadedCount: Int,
    val totalCandidates: Int,
    /** 本轮给 MCP 的额度（token）。 */
    val budgetTokens: Int,
    /** 已装载 MCP 的 token 合计。 */
    val usedTokens: Int,
    /** 意图预筛是否生效（生效 ⇒ notice 是 X/Y 措辞）。 */
    val prefiltered: Boolean,
) {
    companion object {
        /** 没有候选（没连任何 server / 全被用户关掉）＝ 什么都不做。 */
        fun none() = McpTurnAssemblyResult(
            notice = null,
            evictedCount = 0,
            loadedCount = 0,
            totalCandidates = 0,
            budgetTokens = 0,
            usedTokens = 0,
            prefiltered = false,
        )
    }
}

/** 生产接线：账本 + 候选池 + 注册表闸 + 预算尺，拼成引擎要的那一个口。 */
internal class McpBudgetWiring(
    /**
     * 注册表用「每次取最新的」getter：换通道会重建引擎与表（BaopuRuntime.switchChannel），
     * 固定引用会变成旧表——名单写进旧表 = 新表看不见，等于装配悄悄失效。
     */
    private val registry: () -> ToolRegistry,
    private val budget: McpBudgetStore,
    /** 候选池（McpToolSource 是懒单例，调用时才解析）。 */
    private val source: () -> McpToolSource,
    /** 当前通道（预算尺要按 provider 取表行；熔断降级只影响预留读数，可容忍）。 */
    private val provider: () -> Provider,
    private val log: (String) -> Unit = {},
) : McpTurnAssembler {

    /** 上一次留痕的装载名单（去重刷屏；装配每步都跑，名单不变就不写日志）。 */
    @Volatile
    private var lastLoggedKey: String? = null

    override fun assembleForStep(sessionId: Long, intentText: String?): McpTurnAssemblyResult {
        val reg = registry()
        val visible = reg.mcpVisibleSpecs(sessionId)
        val visibleNames = visible.map { it.name }.toSet()
        val candidates = source().assemblyCandidates().filter { it.wireName in visibleNames }
        if (candidates.isEmpty()) {
            reg.setMcpLoadedNames(null)
            return McpTurnAssemblyResult.none()
        }
        val allowance = allowanceTokens(reg, sessionId)
        val plan = McpToolAssembly.plan(
            candidates = candidates,
            mcpAllowanceTokens = allowance,
            pins = budget.pins(),
            lru = budget.lastUsedAtMs(),
            intentText = intentText,
        )
        reg.setMcpLoadedNames(if (plan.loadsAll) null else plan.loadedNames.toSet())
        // 名单变了才留一行（步循环里装配每步都跑，不变不刷屏）。
        val key = plan.loadedNames.joinToString(",")
        if (key != lastLoggedKey) {
            lastLoggedKey = key
            log(assemblyLine(plan))
        }
        return McpTurnAssemblyResult(
            notice = plan.notice,
            evictedCount = plan.evictedCount,
            loadedCount = plan.loadedCount,
            totalCandidates = plan.totalCandidates,
            budgetTokens = plan.budgetTokens,
            usedTokens = plan.usedTokens,
            prefiltered = plan.prefiltered,
        )
    }

    /**
     * MCP 额度 ＝ 全局预算 − 内置已占。
     *
     * 全局预算 = 可用上下文 × 比例（默认 8%，账本可拨）；可用上下文用
     * [TokenBudget.availableContext]（窗口 − 输出上限 − 系统预留 − 工具预留），
     * systemTokens 传 0（取预留兜底：真实 system 段此刻还没拼出来，预留是
     * 文档化的保守下限）；toolTokens 只算内置——MCP 自己的额度循环依赖要避开。
     */
    private fun allowanceTokens(reg: ToolRegistry, sessionId: Long): Int {
        val row = BudgetTable.forProvider(provider())
        val builtIn = reg.visibleSpecs(sessionId)
            .filter { it.group != ToolGroup.MCP }
            .sumOf { McpToolBudget.schemaTokens(it.inputSchema) }
        val available = TokenBudget.availableContext(row, systemTokens = 0, toolTokens = builtIn)
        val global = McpToolBudget.budgetTokens(available, budget.ratio() ?: McpToolBudget.DEFAULT_RATIO)
        return McpToolBudget.mcpAllowanceTokens(global, builtIn)
    }

    /** 一行装配读数（运行日志；只记数与 token，不记名单全文——名单在注册表快照里有）。 */
    private fun assemblyLine(plan: McpAssemblyPlan): String {
        val head = "[mcp-budget] 装载 ${plan.loadedCount}/${plan.totalCandidates}" +
            "（额度 ${plan.budgetTokens}，已用 ${plan.usedTokens}）"
        val tail = when {
            plan.evictedCount == 0 -> ""
            plan.prefiltered -> "；预筛生效（本轮意图优先）"
            else -> "；按 pin→LRU→连接序挤出"
        }
        return head + tail
    }
}
