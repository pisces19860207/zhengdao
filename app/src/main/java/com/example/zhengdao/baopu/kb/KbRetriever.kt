// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单-2026-10-21-第一百二十八批-知识库v2 §2（检索：关键词，返回 TopK＋来源；
// 无结果明确说"没找到"，不编造来源；按上下文预算注入，不超限）。
// 切词/打分/预算裁剪的房型与 `MemoryRules`（记忆召回）逐条对齐——两处召回共用
// 同一把尺子（`tokensOf` 切词、`RECALL_TOKEN_BUDGET` 预算），不另起第二套。
package com.example.zhengdao.baopu.kb

import com.example.zhengdao.baopu.MemoryRules
import com.example.zhengdao.baopu.TokenEstimator

/**
 * 检索与引用（**纯函数**，零 IO 零 Android，JVM 直测）。
 *
 * ## 为什么是关键词不是向量
 * 台账 §C 明载「FIX-G 向量检索/RAG——不做，别复活（R16 裁决）」；派工单亦允许
 * 「先关键词也可」。本批只做本地关键词：切词（CJK bigram + ASCII 词）→ 打分
 * （标题 ×3／正文 ×1）→ 显式稳定排序 → TopK → 预算裁剪。
 */
internal object KbRetriever {

    /** 默认返回段数。 */
    const val DEFAULT_TOP_K = 5

    /** TopK 上限（再多就是"把资料库背下来"，不是检索）。 */
    const val MAX_TOP_K = 10

    /**
     * 自动注入的 token 预算（与记忆召回**同一份** [MemoryRules.RECALL_TOKEN_BUDGET]
     * ＝ 2k——两处召回各 2k，加起来也不到窗口的零头；各自另起一份必漂）。
     */
    val INJECT_TOKEN_BUDGET: Int = MemoryRules.RECALL_TOKEN_BUDGET

    /** 一次命中。 */
    data class Hit(val chunk: KbChunk, val score: Int)

    /** 检索结果（[hits] 已按相关度排序；[truncated]/[note] 是预算侧的口径，search 本身不裁）。 */
    data class Result(val hits: List<Hit>, val truncated: Boolean = false, val note: String = "")

    /**
     * 关键词检索。
     *
     * 三条硬口径：
     * 1. **无来源的块一律不进结果**（[KbChunk.relPath] 空即弃）——负例 N2 的靶子：
     *    宁可少返回一段，不许返回一段没有出处的"知识"；
     * 2. 只收分数 > 0 的块（一个查询词都没命中 ⇒ 没找到，不硬凑）；
     * 3. 排序显式稳定（分数 → relPath → lineStart），全平手可判，不依赖集合顺序。
     */
    fun search(index: KbIndex, query: String, topK: Int = DEFAULT_TOP_K): Result {
        val terms = MemoryRules.tokensOf(query)
        if (terms.isEmpty() || index.docs.isEmpty()) return Result(emptyList())
        val k = topK.coerceIn(1, MAX_TOP_K)
        val hits = index.docs.asSequence()
            .flatMap { it.chunks.asSequence() }
            .filter { it.relPath.isNotBlank() && it.text.isNotBlank() }
            .map { c -> c to score(c, terms) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<KbChunk, Int>> { it.second }
                    .thenBy { it.first.relPath }
                    .thenBy { it.first.lineStart },
            )
            .take(k)
            .map { Hit(it.first, it.second) }
            .toList()
        return Result(hits)
    }

    /** 打分（[MemoryRules.score] 同房型：标题命中 ×3、正文命中 ×1，每个查询词分开计）。 */
    fun score(chunk: KbChunk, terms: Set<String>): Int {
        if (terms.isEmpty()) return 0
        val heading = chunk.heading.lowercase()
        val text = chunk.text.lowercase()
        var s = 0
        for (t in terms) {
            if (t.isNotEmpty() && t in heading) s += 3
            if (t in text) s += 1
        }
        return s
    }

    /**
     * 按 token 预算裁剪（**不重排**——[search] 给的顺序就是注入顺序）。
     *
     * 与 [MemoryRules.trimToBudget] 同一房型：单条就超预算也装下（第一条永远给），
     * 但 [truncated] = true 且调用方必须明说——静默截断会让模型以为"就这些了"。
     *
     * @return (裁好的命中, 是否截断)
     */
    fun trimToBudget(
        hits: List<Hit>,
        budgetTokens: Int,
        tokensOf: (String) -> Int = { TokenEstimator.estimate(it) },
    ): Pair<List<Hit>, Boolean> {
        if (hits.isEmpty()) return emptyList<Hit>() to false
        val picked = mutableListOf<Hit>()
        var used = 0
        var truncated = false
        for (h in hits) {
            val cost = tokensOf(renderFragment(h.chunk))
            if (picked.isNotEmpty() && used + cost > budgetTokens) {
                truncated = true
                break
            }
            picked += h
            used += cost
        }
        if (used > budgetTokens) truncated = true
        return picked to truncated
    }

    /**
     * 引用行（每个片段的"出处"）——**永不为空**：[relPath] 空的黑活儿在 [search] 就被滤掉，
     * 走到这里 relPath 必然非空；提取文本（html/docx）标明"提取文本"，不冒充原文件行号。
     */
    fun renderCitation(chunk: KbChunk): String = buildString {
        append("【来源：").append(chunk.relPath)
        if (chunk.source == KbChunk.SOURCE_EXTRACTED) append("（提取文本）")
        append(" 第 ").append(chunk.lineStart).append('-').append(chunk.lineEnd).append(" 行")
        if (chunk.heading.isNotBlank()) append("（").append(chunk.heading).append("）")
        append("】")
    }

    /** 一个片段进上下文/回灌时的完整形态（引用行 ＋ 正文）。 */
    fun renderFragment(chunk: KbChunk): String =
        renderCitation(chunk) + "\n" + chunk.text
}
