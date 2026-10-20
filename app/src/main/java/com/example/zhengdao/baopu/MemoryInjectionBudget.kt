// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九十一批 §3（R3 记忆注入预算：读时按分数/新鲜度/相关性排序，
// 限制注入条数与字符预算，**不删除任何记忆**）。与第八十九批（记忆质量：
// 合并/降权）的分工：那边改候选的分数与合并，这边只在**读时**决定这一轮
// 装哪些进上下文；两边都不删文件、不改存储。
package com.example.zhengdao.baopu

/**
 * 记忆注入预算（纯函数）：排序 → 按条数/字符两个上限挑 → 其余**只是不注入**。
 *
 * 三条硬口径：
 * 1. **不删除**：本对象没有任何写接口；[Plan.omitted] 只是「这轮没装下」，
 *    磁盘上的记忆一条不少，下一轮仍会参与排序；
 * 2. **稳定排序**：分数 → 更新时间 → 创建时间 → id，一路排到没有平手，
 *    不依赖集合迭代顺序（同一批输入必须得到同一批注入）；
 * 3. **只读度量**：本批不把它接进 [com.example.zhengdao.baopu.agent.RunEngine]
 *    的实际注入路径；控制器只拿它做预览，默认行为不变。
 *
 * 与 [MemoryRules.trimToBudget] 的关系：那边按 token 上界裁剪（既有口径），
 * 这边按「条数 ＋ 字符」双上限做补充度量；两者都不删除、都不重排输入顺序。
 */
internal object MemoryInjectionBudget {

    /** 默认最多注入多少条（配置默认值；本批未接入实际注入）。 */
    const val DEFAULT_MAX_ITEMS = 20

    /**
     * 默认字符预算。与 [MemoryRules.RECALL_TOKEN_BUDGET] 的 2k token 对齐：
     * CJK 口径下 1 token ≈ 1 字符，取同一数量级，避免两套预算互相打架。
     */
    const val DEFAULT_MAX_CHARS = 2_000

    /** 新鲜度半衰期：7 天（越久没更新的记忆权重越低，但**不归零**）。 */
    const val FRESHNESS_HALF_LIFE_MS = 7L * 24L * 60L * 60L * 1000L

    /** 相关性权重（查询词命中，见 [MemoryRules.score]）。 */
    const val RELEVANCE_WEIGHT = 3.0

    /** 新鲜度权重。 */
    const val FRESHNESS_WEIGHT = 2.0

    /** 使用次数权重（用得多的记忆更可能仍有效）。 */
    const val USAGE_WEIGHT = 0.5

    /** 计满「使用分」所需的次数。 */
    const val USAGE_FULL_COUNT = 10

    /** 预算配置（默认值 = 本批的口径；未接入实际注入）。 */
    data class Config(
        val maxItems: Int = DEFAULT_MAX_ITEMS,
        val maxChars: Int = DEFAULT_MAX_CHARS,
        val freshHalfLifeMs: Long = FRESHNESS_HALF_LIFE_MS,
        val relevanceWeight: Double = RELEVANCE_WEIGHT,
        val freshnessWeight: Double = FRESHNESS_WEIGHT,
        val usageWeight: Double = USAGE_WEIGHT,
    ) {
        init {
            require(maxItems > 0) { "maxItems 必须为正：$maxItems" }
            require(maxChars > 0) { "maxChars 必须为正：$maxChars" }
            require(freshHalfLifeMs > 0) { "freshHalfLifeMs 必须为正：$freshHalfLifeMs" }
        }
    }

    /** 一条记忆的排序拆解（报告要能解释「为什么它在前」）。 */
    data class Scored(
        val entry: MemoryEntry,
        val score: Double,
        val relevance: Double,
        val freshness: Double,
        val usage: Double,
    )

    /** 注入计划（只读；[omitted] 是没装下的，不是被删的）。 */
    data class Plan(
        val injected: List<MemoryEntry>,
        val omitted: List<MemoryEntry>,
        val usedChars: Int,
        val totalChars: Int,
        val note: String,
    ) {
        /** 是否发生了截断（装不下）。 */
        val truncated: Boolean get() = omitted.isNotEmpty()

        companion object {
            /** 空计划（控制器初始值；不承载任何行为）。 */
            val EMPTY = Plan(emptyList(), emptyList(), 0, 0, "")
        }
    }

    /**
     * 打分：相关性 × 权重 ＋ 新鲜度 × 权重 ＋ 使用分 × 权重。
     *
     * - 相关性 = [MemoryRules.score]（标签命中 3、标题 2、正文 1，按查询词累加）；
     * - 新鲜度 = 0.5 ^ (距上次更新/半衰期)，时间戳缺失记 0（不假装新鲜）；
     * - 使用分 = min(1, useCount / [USAGE_FULL_COUNT])。
     */
    fun score(
        entry: MemoryEntry,
        now: Long,
        queryTerms: Set<String>,
        config: Config = Config(),
    ): Scored {
        val relevance = MemoryRules.score(entry, queryTerms).toDouble()
        val touched = maxOf(entry.updatedAt, entry.createdAt)
        val age = if (touched <= 0L) Long.MAX_VALUE else (now - touched).coerceAtLeast(0L)
        val freshness = if (touched <= 0L) {
            0.0
        } else {
            Math.pow(0.5, age.toDouble() / config.freshHalfLifeMs.toDouble())
        }
        val usage = (entry.useCount.toDouble() / USAGE_FULL_COUNT.toDouble()).coerceIn(0.0, 1.0)
        val total = relevance * config.relevanceWeight +
            freshness * config.freshnessWeight +
            usage * config.usageWeight
        return Scored(entry, total, relevance, freshness, usage)
    }

    /**
     * 稳定排序：分数 → 更新时间 → 创建时间 → id。
     *
     * id 是最后一道平手判据，保证任意两条记忆都有确定先后（不靠输入顺序兜底）。
     * 输入为空查询时，相关性全 0，排序自然退化为「新鲜度/使用分」。
     */
    fun rank(
        entries: List<MemoryEntry>,
        now: Long,
        query: String = "",
        config: Config = Config(),
    ): List<Scored> {
        val terms = if (query.isBlank()) emptySet() else MemoryRules.tokensOfQuery(query)
        return entries
            .map { score(it, now, terms, config) }
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenByDescending { it.entry.updatedAt }
                    .thenByDescending { it.entry.createdAt }
                    .thenBy { it.entry.id },
            )
    }

    /**
     * 生成注入计划。
     *
     * 挑法：按排序逐条尝试，**跳过装不下的那条、继续看后面的**（后面可能有更短的），
     * 注入顺序始终是排序顺序。条数到上限后不再注入，但**不提前 break**——
     * 还要把剩余条目原样放进 [Plan.omitted]，让调用方知道「有多少没装下」。
     *
     * @throws IllegalArgumentException config 非法（构造时已挡；这里不吞）
     */
    fun plan(
        entries: List<MemoryEntry>,
        now: Long,
        query: String = "",
        config: Config = Config(),
    ): Plan {
        val ranked = rank(entries, now, query, config)
        val injected = ArrayList<MemoryEntry>(minOf(ranked.size, config.maxItems))
        var used = 0
        for (s in ranked) {
            if (injected.size >= config.maxItems) continue
            val cost = MemoryRules.renderForContext(s.entry).length
            if (used + cost > config.maxChars) continue
            injected += s.entry
            used += cost
        }
        val keptIds = injected.mapTo(HashSet()) { it.id }
        val omitted = ranked.map { it.entry }.filter { it.id !in keptIds }
        val totalChars = ranked.sumOf { MemoryRules.renderForContext(it.entry).length }
        val note = if (omitted.isEmpty()) {
            ""
        } else {
            "记忆注入预算：候选 ${ranked.size} 条，注入 ${injected.size} 条" +
                "（字符 $used/${config.maxChars}，条数上限 ${config.maxItems}），" +
                "其余 ${omitted.size} 条本轮未注入——**没有删除**"
        }
        return Plan(
            injected = injected,
            omitted = omitted,
            usedChars = used,
            totalChars = totalChars,
            note = note,
        )
    }
}
