// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十四批 · 534 灵感捕获与关联唤醒 ② associate —— 给一条线索，找出
// 「曾经捕获过」的相关念头。**关联只读既有索引**（540 包对 533 的窄缝
// `RecallIndexPort.search/digest/status`），不自建向量、不引模型。
//
// 依据：待发提示词 ②「关联只许用既有索引（search/digest/status），不许自己造向量/嵌入」。
//
// 三条硬纪律：
//   1. **只读三方法**：本文件只碰 `index.search/digest/status`；种子词走
//      `MemoryRules.tokensOf`（与 677 同一把词面尺），不另造相似度公式；
//   2. **零向量**：本包源码门禁扫 embedding/vector/cosine/余弦/向量/相似度矩阵
//      等 token，命中即红（判据②）；
//   3. **结果必带锚点**：索引命中回连到本包已捕获念头的 [InsightAnchor.ref]，
//      对不上的命中不进关联结果（不许拿一条索引片段冒充「念头」）。
package com.example.zhengdao.baopu.insight

import com.example.zhengdao.baopu.MemoryRules
import com.example.zhengdao.baopu.recall.IndexHit
import com.example.zhengdao.baopu.recall.RecallIndexPort

/** 一条关联结果：念头 + 命中的索引行 + 为什么关联上（可追）。 */
internal data class InsightAssociation(
    val thought: CapturedThought,
    val hit: IndexHit,
    /** 命中理由（人话，进账/进 note）。 */
    val why: String,
)

/**
 * 线索 → 用既有索引找相关念头（只读；纯函数除了委托索引，零状态）。
 *
 * 关联口径（与 677 三重命中的词面那一重同源，不另立第二把尺）：
 *   · 线索词切 [MemoryRules.tokensOf]，拼查询串走 `index.search`；
 *   · 命中片段的词面与念头原文重合 ≥ [MIN_TOKEN_HITS] 个 ⇒ 关联成立；
 *   · 命中带的 messageId 与念头锚点一致 ⇒ 直接成立（同一句话，最强的关联）。
 */
internal object InsightAssociate {

    /** 词面重合下限（与 677 的二元组阈值同量级；重合 1 个词太容易误关联）。 */
    const val MIN_TOKEN_HITS = 2

    /** 一次关联最多取几条索引命中（与 540 的 INDEX_LIMIT=20 同预算，不另立）。 */
    const val HIT_LIMIT = 20

    /** 线索词 → 查询串（空格分隔，去空；空 ⇒ null 不发查询）。 */
    fun queryOf(clue: String): String? = MemoryRules.tokensOf(clue)
        .sorted()
        .take(HIT_LIMIT)
        .joinToString(" ")
        .ifBlank { null }

    /**
     * 关联（唯一入口）。[index] 由装配层注入（生产＝540 的 `SessionIndexPort`），
     * 本层不知道它的实现，更不认识 533 的类型——换索引实现只改装配那一处。
     */
    fun associate(
        clue: String,
        thoughts: List<CapturedThought>,
        index: RecallIndexPort,
    ): List<InsightAssociation> {
        val query = queryOf(clue) ?: return emptyList()
        if (thoughts.isEmpty()) return emptyList()
        val hits = index.search(query, HIT_LIMIT)
        return hits.mapNotNull { hit -> match(hit, thoughts) }
    }

    /** 一条索引命中 → 关联到哪条念头（同一句命中优先；否则词面重合达标）。 */
    private fun match(hit: IndexHit, thoughts: List<CapturedThought>): InsightAssociation? {
        val sameSentence = thoughts.firstOrNull { t ->
            (t.anchor as? InsightAnchor.Message)?.messageId == hit.msgId && hit.msgId > 0L
        }
        if (sameSentence != null) return InsightAssociation(sameSentence, hit, "命中同一句话（msg:${hit.msgId}）")
        val clueTokens = MemoryRules.tokensOf(hit.snippet)
        val best = thoughts.maxByOrNull { (clueTokens intersect MemoryRules.tokensOf(it.body)).size }
            ?: return null
        val overlap = (clueTokens intersect MemoryRules.tokensOf(best.body)).size
        if (overlap < MIN_TOKEN_HITS) return null
        return InsightAssociation(best, hit, "词面重合 $overlap 个词（线索：${hit.snippet.take(24)}）")
    }

    /**
     * 光杆指代（线索里没有实词）时用 digest 取会话要点（540 同款来路）。
     * 同样只调既有索引的 digest，不自建摘要器。
     */
    fun digestClue(sessionId: Long, max: Int, index: RecallIndexPort): List<String> =
        index.digest(sessionId, max).map { it.text }

    /** 索引读数（status 只用来如实说「索引几条」，不拿它做判定）。 */
    fun statusLine(index: RecallIndexPort): String {
        val s = index.status()
        return "索引 ${s.messages} 条消息 / ${s.tokens} 条倒排"
    }
}
