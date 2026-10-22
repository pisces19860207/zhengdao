// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九十一批 §1（R3 上下文预算：按 provider/model 的预算表、纯估算与
// 触发判定、压缩→截断→拒绝的降级顺序）。本文件只提供**只读度量与配置**：
// 不参与实际装配、不改 [ContextAssembler.ContextBudgets] 的既有口径、
// 不改变任何默认路径——真正的执行由后续批次显式打开。
package com.example.zhengdao.baopu

/**
 * 一条模型预算（派工单 §1 的「表」）。
 *
 * 为什么要有这张表：现有预算只从 [Provider] 的两个可空字段推，拿不到元数据时
 * 全系统回退 1M/32k；而「系统提示」与「工具 schema」同样占窗口，之前没有一处
 * 把它们显式扣掉。本表把两笔预留变成**有出处的数据行**，不是一个函数里的魔法数。
 *
 * @param providerId 对应 [Provider.id]；`*` 表示兜底行
 * @param modelPrefix 模型名前缀（空串 = 该通道任意模型）；更具体的行排在前面
 * @param contextWindow 上下文窗口（token）
 * @param maxOutput 单次输出上限（token；从可用输入里先扣掉）
 * @param systemPromptReserve 系统提示预留（token）
 * @param toolSchemaReserve 工具 schema 预留（token）
 * @param source 读数出处（「元数据」「公开文档•本仓未实测」等，报告直接引用）
 */
internal data class ModelBudget(
    val providerId: String,
    val modelPrefix: String,
    val contextWindow: Int,
    val maxOutput: Int,
    val systemPromptReserve: Int,
    val toolSchemaReserve: Int,
    val source: String,
) {
    init {
        require(contextWindow > 0) { "contextWindow 必须为正：$contextWindow" }
        require(maxOutput > 0) { "maxOutput 必须为正：$maxOutput" }
        require(systemPromptReserve >= 0) { "systemPromptReserve 不能为负" }
        require(toolSchemaReserve >= 0) { "toolSchemaReserve 不能为负" }
    }
}

/**
 * 预算表（数据，不写死在判定函数里）。
 *
 * 取值口径：
 * - DeepSeek：窗口/输出直接来自 [Providers.DEEPSEEK] 的元数据字段（本仓已有）；
 * - OpenAI 兼容 / Anthropic / 内置免费：按各自公开文档的常见档位填**预设**，
 *   本仓没有对它们实测过 ⇒ [ModelBudget.source] 明说「未实测」，报告不许当读数；
 * - `*` 兜底：沿用现有回退值（窗口 1M、输出 32k），保证拿不到任何信息时口径不变。
 *
 * 预留值（系统提示 8k–12k、工具 schema 16k–24k）是**配置**，不是本次实测：
 * 它们只进 [TokenBudget.availableContext] 的减法，不参与压缩触发线。
 */
internal object BudgetTable {

    /** 系统提示预留的兜底值（表里每行可覆盖）。 */
    const val DEFAULT_SYSTEM_RESERVE = 12_000

    /** 工具 schema 预留的兜底值（表里每行可覆盖）。 */
    const val DEFAULT_TOOL_RESERVE = 24_000

    /** 表行。顺序即优先级：同通道内先匹配更具体的 modelPrefix，最后才是 `*`。 */
    val rows: List<ModelBudget> = listOf(
        ModelBudget(
            providerId = "deepseek",
            modelPrefix = "deepseek",
            contextWindow = 1_048_576,
            maxOutput = 393_216,
            systemPromptReserve = 12_000,
            toolSchemaReserve = 24_000,
            source = "Provider 元数据（contextWindow/maxOutput）",
        ),
        ModelBudget(
            providerId = "openai-compat",
            modelPrefix = "gpt-4o-mini",
            contextWindow = 128_000,
            maxOutput = 16_384,
            systemPromptReserve = 6_000,
            toolSchemaReserve = 12_000,
            source = "公开文档预设（本仓未实测；mini 档预留更小）",
        ),
        ModelBudget(
            providerId = "openai-compat",
            modelPrefix = "gpt-4o",
            contextWindow = 128_000,
            maxOutput = 16_384,
            systemPromptReserve = 8_000,
            toolSchemaReserve = 16_000,
            source = "公开文档预设（本仓未实测）",
        ),
        ModelBudget(
            providerId = "openai-compat",
            modelPrefix = "",
            contextWindow = 128_000,
            maxOutput = 16_384,
            systemPromptReserve = 8_000,
            toolSchemaReserve = 16_000,
            source = "公开文档预设（本仓未实测）；自定义模型按此兜底",
        ),
        ModelBudget(
            providerId = "anthropic",
            modelPrefix = "",
            contextWindow = 200_000,
            maxOutput = 64_000,
            systemPromptReserve = 12_000,
            toolSchemaReserve = 24_000,
            source = "公开文档预设（本仓未实测）",
        ),
        ModelBudget(
            providerId = "openrouter-free",
            modelPrefix = "",
            contextWindow = 128_000,
            maxOutput = 16_384,
            systemPromptReserve = 8_000,
            toolSchemaReserve = 16_000,
            source = "预设占位（免费车道能从清单里换模型 ⇒ 取保守值；" +
                "Providers.OPENROUTER_FREE 已声明的 ① 实测窗口优先于本行）",
        ),
        ModelBudget(
            providerId = "*",
            modelPrefix = "",
            contextWindow = ContextAssembler.DEFAULT_WINDOW_TOKENS,
            maxOutput = ContextAssembler.ContextBudgets.DEFAULT_OUTPUT_TOKENS,
            systemPromptReserve = DEFAULT_SYSTEM_RESERVE,
            toolSchemaReserve = DEFAULT_TOOL_RESERVE,
            source = "兜底：沿用现有回退值",
        ),
    )

    /** 兜底行（表被改坏时仍能取到一条合法预算）。 */
    val fallback: ModelBudget = rows.last()

    /**
     * 取某通道的预算行。
     *
     * [Provider.contextWindow] / [Provider.maxOutput] 非空时**优先于表**——元数据是
     * provider 自己的事实，表只是拿不到元数据时的预设。非正数按「没给」处理
     * （与 [ContextAssembler.ContextBudgets] 同口径）。
     */
    fun forProvider(provider: Provider): ModelBudget {
        val row = rows.firstOrNull { it.providerId == provider.id && it.matches(provider.model) }
            ?: rows.firstOrNull { it.providerId == "*" }
            ?: fallback
        return row.copy(
            contextWindow = provider.contextWindow?.takeIf { it > 0 } ?: row.contextWindow,
            maxOutput = provider.maxOutput?.takeIf { it > 0 } ?: row.maxOutput,
        )
    }

    private fun ModelBudget.matches(model: String): Boolean =
        modelPrefix.isEmpty() || model.startsWith(modelPrefix, ignoreCase = true)
}

/**
 * 纯 token 预算判定（无 Android、无 I/O，可 JVM 直测）。
 *
 * **只算不改**：本对象的任何函数都不碰消息、不裁剪历史、不发请求。
 * 「装得下/该压缩/该降级」是给调用方看的读数，执行入口在
 * [DegradationPolicy.execute]（默认关闭）。
 */
internal object TokenBudget {

    /** 一次预算读数（输入侧 token 与各条线的对照）。 */
    data class Usage(
        /** 本轮输入估算（消息 ＋ 本轮输入；不含输出）。 */
        val inputTokens: Int,
        /** 扣掉输出上限与两笔预留后的可用输入。 */
        val availableTokens: Int,
        /** 现有压缩触发线（[ContextAssembler.compressionThreshold]；只读复用，不另立口径）。 */
        val thresholdTokens: Double,
        /** 距触发线还有多少（负数 = 已超）。 */
        val triggerHeadroom: Int,
        /** 距可用上下文还有多少（负数 = 已溢出）。 */
        val availableHeadroom: Int,
    ) {
        /** 是否到压缩线（严格大于，与 [ContextAssembler.needsCompressing] 同口径）。 */
        val overThreshold: Boolean get() = inputTokens > thresholdTokens

        /** 是否已超出可用上下文（压缩也装不下时才是降级场景）。 */
        val overAvailable: Boolean get() = inputTokens > availableTokens
    }

    /** 输入估算：逐段调用全系统唯一尺子 [TokenEstimator]，不私算。 */
    fun estimateInput(texts: Iterable<String>): Int = texts.sumOf { TokenEstimator.estimate(it) }

    /**
     * 可用输入 = 窗口 − 输出上限 − max(系统提示预留, 实测系统提示)
     * − max(工具 schema 预留, 实测 schema)，下限 0。
     *
     * 为什么取 max：预留是「至少留这么多」；实测更大时按实测扣，绝不能因为
     * 配置写小了就把系统提示挤进用户上下文。
     */
    fun availableContext(
        budget: ModelBudget,
        systemTokens: Int = 0,
        toolTokens: Int = 0,
    ): Int {
        val system = maxOf(budget.systemPromptReserve, systemTokens.coerceAtLeast(0))
        val tools = maxOf(budget.toolSchemaReserve, toolTokens.coerceAtLeast(0))
        return (budget.contextWindow - budget.maxOutput - system - tools).coerceAtLeast(0)
    }

    /** 现有压缩触发线（复用 [ContextAssembler.compressionThreshold]，不复制 0.70/900k）。 */
    fun thresholdOf(budget: ModelBudget): Double =
        ContextAssembler.compressionThreshold(budget.contextWindow)

    /** 一次算全所有读数。 */
    fun usage(
        inputTokens: Int,
        budget: ModelBudget,
        systemTokens: Int = 0,
        toolTokens: Int = 0,
    ): Usage {
        val available = availableContext(budget, systemTokens, toolTokens)
        val threshold = thresholdOf(budget)
        return Usage(
            inputTokens = inputTokens,
            availableTokens = available,
            thresholdTokens = threshold,
            triggerHeadroom = Math.floor(threshold - inputTokens).toInt(),
            availableHeadroom = available - inputTokens,
        )
    }

    /** 是否该压缩（严格大于；等于触发线不压，与引擎同口径）。 */
    fun needsCompression(inputTokens: Int, budget: ModelBudget): Boolean =
        inputTokens > thresholdOf(budget)

    /** 一行读数（日志/报告共用，避免两处各写一遍单位与措辞）。 */
    fun note(usage: Usage): String =
        "输入 ${usage.inputTokens} tokens；可用 ${usage.availableTokens}；" +
            "触发线 ${Math.round(usage.thresholdTokens)}；" +
            "余量 ${usage.availableHeadroom}（触发线余量 ${usage.triggerHeadroom}）"
}

/** 降级阶梯的一级（顺序由 [DegradationPolicy.LADDER] 钉死）。 */
internal enum class BudgetStep {
    /** 先把旧消息压成摘要（默认路径已在做的那一步）。 */
    COMPRESS,

    /** 压完仍装不下才截断（本批只给判据，不执行）。 */
    TRUNCATE,

    /** 截断也救不了（可用空间低于下限）才拒绝本轮。 */
    REJECT,
}

/** 降级计划（纯数据）。 */
internal data class DegradationPlan(
    val usage: TokenBudget.Usage,
    /** 按阶梯顺序、本次真正可能用到的级（可能为空 = 装得下）。 */
    val steps: List<BudgetStep>,
    /** 给人看的一句话。 */
    val reason: String,
)

/**
 * 降级顺序（派工单 §1 第三条）：**压缩 → 截断 → 拒绝**，纯函数。
 *
 * 🛑 默认关闭（[ENGAGED_BY_DEFAULT] = false）：`execute` 在默认配置下恒返回空表，
 * 所以本对象存在与否不改变任何线上行为。要打开必须由调用方显式传 `engaged = true`，
 * 且本批没有这样的调用方（源码门禁 `ContextBudgetWiringGateTest` 钉住）。
 */
internal object DegradationPolicy {

    /** 阶梯定版顺序（测试直接断言这个列表，改序即红）。 */
    val LADDER: List<BudgetStep> = listOf(
        BudgetStep.COMPRESS,
        BudgetStep.TRUNCATE,
        BudgetStep.REJECT,
    )

    /** 截断后至少要留这么多 token 才有意义；低于它直接进拒绝级。 */
    const val MIN_KEEP_TOKENS = 512

    /**
     * 默认是否启用执行。**本批固定 false**：R3 只度量，不自动降级。
     * 负例门禁会把这个常量与 `execute` 的默认值一起钉住。
     */
    const val ENGAGED_BY_DEFAULT = false

    /**
     * 规划降级步骤（纯判定，不执行）。
     *
     * @param compressionAvailable 压缩通道当前是否可用（降级通道不可信 ⇒ 传 false）
     */
    fun plan(
        usage: TokenBudget.Usage,
        compressionAvailable: Boolean = true,
    ): DegradationPlan {
        if (!usage.overAvailable) {
            return DegradationPlan(
                usage = usage,
                steps = emptyList(),
                reason = "装得下：可用 ${usage.availableTokens}，输入 ${usage.inputTokens}",
            )
        }
        val steps = buildList {
            if (compressionAvailable) add(BudgetStep.COMPRESS)
            if (usage.availableTokens >= MIN_KEEP_TOKENS) {
                add(BudgetStep.TRUNCATE)
            } else {
                add(BudgetStep.REJECT)
            }
        }
        val reason = buildString {
            append("输入 ${usage.inputTokens} 超过可用 ${usage.availableTokens}：")
            append(
                if (compressionAvailable) {
                    "先压缩；压完仍超则截断"
                } else {
                    "压缩通道不可用；只能截断"
                },
            )
            if (usage.availableTokens < MIN_KEEP_TOKENS) {
                append("；但可用空间低于 $MIN_KEEP_TOKENS，截断也装不下 ⇒ 拒绝")
            }
        }
        return DegradationPlan(usage = usage, steps = steps, reason = reason)
    }

    /**
     * 取出本次要执行的步骤。默认 [engaged] = [ENGAGED_BY_DEFAULT]（false）⇒ 空表。
     *
     * 这就是「默认路径仍关」的唯一执行口：没有调用方传 true，行为就不变。
     */
    fun execute(plan: DegradationPlan, engaged: Boolean = ENGAGED_BY_DEFAULT): List<BudgetStep> =
        if (engaged) plan.steps else emptyList()
}
