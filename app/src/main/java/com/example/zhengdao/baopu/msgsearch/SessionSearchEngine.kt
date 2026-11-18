// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：702 派工单 §②③——检索打分用**中文 BM25**（k1=1.2, b=0.75 起步，取值依据见交付报告
// §「BM25 取值」：Okapi BM25 文献推荐区间 1.2–2.0 的下限，与 Lucene `BM25Similarity` 默认一致，
// 短文本语料从保守档起步）；摘要提炼**只用抽取式**（挑原句压缩），**不调模型**。
//
// 为什么这一层是纯函数、零 Android 依赖：本仓没有 Robolectric ⇒ JVM 单测跑不了真库，
// 「纯 Kotlin 判据、能进全量单测」（702 派工单方案栏原话）就落在这里——夹具、filters、
// BM25 排序、要点长度全部直测；SQLite 只是往里搬数据的运输队（`SessionSearchStore`，真机标③）。
//
// 分词：只认 `MemoryRules.tokensOf`（CJK 二字元 bigram ＋ ASCII 词）——**不新造第二套分词**。
package com.example.zhengdao.baopu.msgsearch

import com.example.zhengdao.baopu.MemoryRules
import kotlin.math.ln
import kotlin.math.sqrt

/** 倒排一行：某消息里某词出现 [tf] 次（`tokens` 表 PK(token, msg_id) 保证不重复）。 */
internal data class Posting(val msgId: Long, val tf: Int)

/**
 * 候选消息的元数据。[len] = 正文 token 总次数（BM25 的文档长度 dl，也是 filter「长度上限」的那一列）。
 */
internal data class DocMeta(val msgId: Long, val sessionId: Long, val role: String, val ts: Long, val len: Int)

/** 结构化过滤（702 §②）：时间区间／会话 id／角色／长度上限；null ＝ 不过滤。 */
internal data class SearchFilters(
    val fromTs: Long? = null,
    val toTs: Long? = null,
    val sessionId: Long? = null,
    val role: String? = null,
    val maxLen: Int? = null,
)

/** 打完分的候选（分数降序、同分按 msgId 升——同输入必同序，单测要能断言第 1 名是谁）。 */
internal data class Scored(val doc: DocMeta, val score: Double)

/** 给 540 的命中（派单 §②④：msgId 必带——「跳原文」靠它；sessionId 是跳转的另一半）。 */
internal data class SearchHit(val msgId: Long, val sessionId: Long, val score: Double, val snippet: String)

/** 摘要提炼的原料（正文从主库读出来现切；msgId 是主库 id，缺不了——结构上丢不掉）。 */
internal data class DigestMsg(val msgId: Long, val role: String, val text: String)

/** 摘要要点（派单 §③：≤60 字、必带 msgId）。 */
internal data class DigestPoint(val text: String, val msgId: Long)

/**
 * 会话搜索的纯算法层：BM25 打分 ＋ 结构化过滤 ＋ 摘要窗口。
 * 全部输入显式进参、输出确定序；不读时钟、不落盘、不调模型（判据⑦的源就是这一层）。
 */
internal object SessionSearchEngine {

    /** BM25 的 k1（词频饱和）：Okapi BM25 推荐 1.2–2.0 的下限起步；Lucene 默认同值（报告 §「BM25 取值」）。 */
    const val BM25_K1 = 1.2

    /** BM25 的 b（文档长度归一）：经典值 0.75（同上）。 */
    const val BM25_B = 0.75

    /** 一次检索默认返回条数（派单 §②「≤20 条按分数降序」）。 */
    const val DEFAULT_LIMIT = 20

    const val MAX_LIMIT = 50

    /** 命中摘要的字符上限（含省略号）。 */
    const val SNIPPET_CHARS = 80

    /** 摘要要点条数上限（派单 §③「≤5」）。 */
    const val DIGEST_MAX = 5

    /** 摘要要点单条字符上限（派单 §③「≤60 字」）。 */
    const val DIGEST_POINT_CHARS = 60

    /** 短于此的句子不进摘要候选（「好」「知道了」不是要点）。 */
    const val DIGEST_MIN_SENTENCE_CHARS = 6

    /** 命中词往回留的窗（540「超长从命中词往回留 12 字起窗」同口径）。 */
    const val WINDOW_LOOKBACK_CHARS = 12

    /** 摘要去重阈值（词集合 Jaccard；两句几乎一样只留分高的那句）。 */
    const val DEDUP_SIMILARITY = 0.8

    /**
     * 关键词检索（702 §②）：对候选倒排做 BM25 打分、按 filters 过滤、取前 [limit] 条。
     *
     * @param postings   每个查询词的倒排行（df ＝ 列表长度，PK(token, msg_id) 保证不重复）
     * @param docs       候选消息的元数据（只需含候选；JOIN 保证候选必有 meta）
     * @param docCount   语料里**全部**消息数（N——不是候选数，IDF 按全库算）
     * @param avgDl      全库平均文档长（空库给 0 ＝ 不打分返回空）
     */
    fun search(
        postings: Map<String, List<Posting>>,
        docs: Map<Long, DocMeta>,
        docCount: Int,
        avgDl: Double,
        query: String,
        filters: SearchFilters = SearchFilters(),
        excludedSessions: Set<Long> = emptySet(),
        limit: Int = DEFAULT_LIMIT,
    ): List<Scored> {
        require(limit in 1..MAX_LIMIT) { "limit 要在 1..$MAX_LIMIT，实传 $limit" }
        val terms = queryTerms(query)
        if (terms.isEmpty() || docCount <= 0 || avgDl <= 0.0) return emptyList()
        val scores = HashMap<Long, Double>()
        for (term in terms) {
            val plist = postings[term] ?: continue
            val df = plist.size
            for (p in plist) {
                val doc = docs[p.msgId] ?: continue
                if (!passes(doc, filters, excludedSessions)) continue
                val gain = bm25TermScore(p.tf, df, doc.len, avgDl, docCount)
                if (gain > 0.0) scores.merge(p.msgId, gain, Double::plus)
            }
        }
        return scores.entries
            .map { Scored(docs.getValue(it.key), it.value) }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.doc.msgId })
            .take(limit)
    }

    /**
     * BM25 单词项得分（多词＝求和，调用方累加）。
     * IDF 用 Lucene 式 `ln(1 + (N−df+0.5)/(df+0.5))`：恒为正（df＝N 也不翻负），
     * 人群里的高频词贡献小、稀有词贡献大，方向与「值得召回」一致。
     */
    fun bm25TermScore(tf: Int, df: Int, docLen: Int, avgDl: Double, docCount: Int): Double {
        if (tf <= 0 || df <= 0 || docCount <= 0 || avgDl <= 0.0) return 0.0
        val idf = ln(1.0 + (docCount - df + 0.5) / (df + 0.5))
        val dl = if (docLen > 0) docLen.toDouble() else avgDl
        val norm = 1.0 - BM25_B + BM25_B * dl / avgDl
        return idf * (tf * (BM25_K1 + 1.0)) / (tf + BM25_K1 * norm)
    }

    /** 查询词（`tokensOf` 切、文本序取前 [SessionSearchSql.MAX_QUERY_TOKENS] 个——绑参数有上限）。 */
    fun queryTerms(query: String): List<String> =
        MemoryRules.tokensOf(query).toList().take(SessionSearchSql.MAX_QUERY_TOKENS)

    /**
     * 命中摘要（702 §② hits 带snippet）：找最早的词命中位置，从命中词往回留
     * [WINDOW_LOOKBACK_CHARS] 字起窗、总长不超 [maxChars]（含省略号）；一个词都没命中等价于 anchor=0。
     */
    fun snippet(text: String, terms: Set<String>, maxChars: Int = SNIPPET_CHARS): String {
        require(maxChars >= 8) { "maxChars 至少 8，实传 $maxChars" }
        val flat = flatten(text)
        val anchor = terms.asSequence()
            .map { flat.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: 0
        return windowAround(flat, anchor, maxChars)
    }

    /**
     * 抽取式摘要（702 §③）：挑原句、不调模型。
     * 打分＝句内词的「会话内句频」累加 ÷ √词数（质心法的退化版：会话主题词多的短句靠前），
     * 近重复句（Jaccard ≥ [DEDUP_SIMILARITY]）只留分高的那句，同分按原文顺序。
     */
    fun digest(messages: List<DigestMsg>, max: Int = DIGEST_MAX): List<DigestPoint> {
        require(max in 1..DIGEST_MAX) { "max 要在 1..$DIGEST_MAX，实传 $max" }
        val cands = sentenceCandidates(messages)
        if (cands.isEmpty()) return emptyList()
        val df = HashMap<String, Int>()
        cands.forEach { c -> c.tokens.forEach { t -> df.merge(t, 1) { a, b -> a + b } } }
        val ranked = cands.sortedWith(compareByDescending<Cand> { scoreOf(it, df) }.thenBy { it.order })
        val picked = mutableListOf<Cand>()
        for (c in ranked) {
            if (picked.size >= max) break
            if (picked.none { similarity(it.tokens, c.tokens) >= DEDUP_SIMILARITY }) picked.add(c)
        }
        return picked.map { DigestPoint(pointTextOf(it, df), it.msgId) }
    }

    /** 摘要句候选：按句切、剥空白、丢太短的；order 是原文出现序（确定性排序的 tiebreaker）。 */
    private fun sentenceCandidates(messages: List<DigestMsg>): List<Cand> {
        val out = mutableListOf<Cand>()
        messages.forEach { m ->
            SENTENCE_SPLIT.split(m.text).forEach { raw ->
                val s = raw.trim()
                if (s.length >= DIGEST_MIN_SENTENCE_CHARS) {
                    out.add(Cand(out.size, m.msgId, s, MemoryRules.tokensOf(s)))
                }
            }
        }
        return out
    }

    private data class Cand(val order: Int, val msgId: Long, val text: String, val tokens: Set<String>)

    /** 句分＝Σ（词的会话内句频）÷ √词数；没词的候选得 0（不会被选中，除非全场都是 0）。 */
    private fun scoreOf(c: Cand, df: Map<String, Int>): Double {
        if (c.tokens.isEmpty()) return 0.0
        val sum = c.tokens.sumOf { (df[it] ?: 0).toDouble() }
        return sum / sqrt(c.tokens.size.toDouble())
    }

    /** 词集合 Jaccard（与 `MemoryRules.bodySimilarity` 同式，但输入已是切好的集合）。 */
    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }

    /** 要点文本：短句原样；长句从它词频最高的词起窗（同 [WINDOW_LOOKBACK_CHARS] 口径），≤60 字。 */
    private fun pointTextOf(c: Cand, df: Map<String, Int>): String {
        if (c.text.length <= DIGEST_POINT_CHARS) return c.text
        val top = c.tokens.maxWithOrNull(compareBy({ df[it] ?: 0 }, { it.length }))
        val anchor = top?.let { c.text.indexOf(it) } ?: 0
        return windowAround(c.text, anchor, DIGEST_POINT_CHARS)
    }

    /**
     * 从 [anchor] 往回留 [WINDOW_LOOKBACK_CHARS] 字的窗，总长（含省略号）不超 [maxChars]。
     * 窗起点在句首就不补头省略号；窗终点没到句尾就补尾省略号——省略号算进预算，绝不超。
     */
    fun windowAround(text: String, anchor: Int, maxChars: Int): String {
        require(maxChars >= 8) { "maxChars 至少 8，实传 $maxChars" }
        val start = maxOf(0, anchor.coerceIn(0, text.length) - WINDOW_LOOKBACK_CHARS)
        val prefix = if (start > 0) "…" else ""
        var end = minOf(text.length, start + maxChars - prefix.length)
        val suffix = if (end < text.length) "…" else ""
        end = minOf(end, start + maxChars - prefix.length - suffix.length)
        if (end <= start) return text.take(maxChars.coerceAtMost(text.length))
        return prefix + text.substring(start, end) + suffix
    }

    /** 折行折叠（snippet 要一行；摘要句已被句切分开，不走这里）。 */
    private fun flatten(text: String): String = text.replace(WHITESPACE_RUN, " ").trim()

    /**
     * 结构化过滤（判据②的语义）：时间区间闭区间、会话 id 精确、角色精确、长度上限；
     * `excludedSessions`（回收站）一律拦在门外。
     */
    private fun passes(doc: DocMeta, f: SearchFilters, excludedSessions: Set<Long>): Boolean =
        doc.sessionId !in excludedSessions &&
            (f.sessionId == null || doc.sessionId == f.sessionId) &&
            (f.role == null || doc.role == f.role) &&
            (f.fromTs == null || doc.ts >= f.fromTs) &&
            (f.toTs == null || doc.ts <= f.toTs) &&
            (f.maxLen == null || doc.len <= f.maxLen)

    /** 句子切分（**不是分词**：分词只有 `tokensOf` 一套；这里只是把一段话断成候选句）。 */
    private val SENTENCE_SPLIT = Regex("[，。！？；…\\n\\r!?;]+")

    private val WHITESPACE_RUN = Regex("\\s+")
}
