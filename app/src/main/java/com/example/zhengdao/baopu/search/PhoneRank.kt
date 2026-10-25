// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1③）：**分词与打分的算式层**。派工单原话「分词复用
// `MemoryRules.tokensOf`、打分复用 `KbRetriever` 口径」——这一句在本文件里落成了两件事，
// 而且第二件是**折进索引里**的，理由写在下面「权重为什么不在查询时算」。
//
// ## 一、分词：一个字都不重算
// 切词只有 [PhoneRank.split] 一个口，里面直接调 `MemoryRules.tokensOf`（Memory.kt:164，
// CJK 二字元 bigram ＋ ASCII 连续段小写）。查询侧走 [termsOf]，它先过同一个 `tokensOf`
// （经 `MemoryRules.tokensOfQuery`，:313 就是 `tokensOf` 的别名）再截到 [MAX_TERMS]。
// 「建索引用的切词」与「查询用的切词」必须是同一把尺子，否则词表对不上、搜不到还查不出原因——
// 这条同源性由 `PhoneSearchWiringTest.同源_切词与打分不在壳里重算` 用源码文本钉住。
//
// ## 二、打分：标题 ×3／正文 ×1 折进 `tok.cnt`
// `KbRetriever.score`（KbRetriever.kt:70-80）的口径是「每个查询词分开计：标题命中 +3、正文命中 +1」。
// 倒排里 `tok` 的主键是 `(token, doc_id)`，一个词在一篇里只有一行，所以把这个口径**在入库时**
// 折进 `cnt`：
//     cnt(token) = 3·[token 在标题的 token 集里] ＋ 1·[token 在正文的 token 集里]
// 于是检索侧 `SUM(t.cnt) … GROUP BY root_id, rel_path`（[SearchSql.selectSearch]，本批**没改**它一个字符）
// 算出来的就是同一个分数——不用给 SQL 加权重参数（那条语句的 S2 门禁 `检索语句只读` 钉着它的形状），
// 也不用把正文存进库里再二次打分。
//
// **一处必须说明的差别**（不许当成「完全等价」）：`KbRetriever` 用的是「子串在不在」，
// 倒排用的是「整个 token 在不在」。对**由同一把尺子切出来的词**，CJK 二字元与 ASCII 段两种
// 判据给同一个答案；差别只在「半个词」的查询上——`ab` 想搜 `abc`，`KbRetriever` 给 3 分、
// 倒排给 0 行。方向是**少召回，不多召回**，与本批所有失效口径一致（报告 §3 逐字写明，
// `PhoneRankTest.行为等价_词是整词时两边同分` 用 `KbRetriever.score` 现跑比对）。
//
// ## 三、两个上限：都是「索引体积换召回」，都不静默
// - [BODY_INDEX_MAX_CHARS]：正文只切前 12 万字符（一篇 2 MiB 的 txt 全文切词，
//   `tokensOf` 会在瞬时给出一张几万项的哈希表——峰值内存就是它）。
// - [MAX_DOC_TOKENS]：一篇最多 4096 个词进倒排（取文本顺序的前 4096 个，也就是**文档靠前的内容**）。
//   两者命中时 [tokenCounts] 返回 `truncated = true`，扫描器把它计进 `ScanReport.cappedDocs`，
//   设置页显示「长文截断 N 篇」——派工单 §2 的「静默截断」负例红在这里：截断可以有，
//   不许没人知道。
package com.example.zhengdao.baopu.search

import com.example.zhengdao.baopu.MemoryRules

/** 一条检索命中（`SUM(cnt)` 的分数由库里算出来，本文件只负责排序口径）。 */
internal data class TermHit(
    val rootId: Long,
    val relPath: String,
    val score: Int,
)

/**
 * 打分与切词（**纯函数，无 Android、无 SQL**）。
 *
 * 生产上只有 [termsOf] 一处接用户的原文，其余函数吃的都是已经切好的词表或本篇的文本。
 */
internal object PhoneRank {

    /** 标题命中权重（与 `KbRetriever.score` 里那句 `s += 3` 同一个数，同源门禁钉它）。 */
    const val TITLE_WEIGHT = 3

    /** 正文命中权重（同上，`s += 1`）。 */
    const val BODY_WEIGHT = 1

    /** 绑参用的词数上限（与 [SearchSql.MAX_TOKENS] 同一个数，也是 `selectSearch` 的 `require` 上界）。 */
    const val MAX_TERMS = SearchSql.MAX_TOKENS

    /** 正文参与切词的最大字符数（峰值内存的闸门，见文件头第三条）。 */
    const val BODY_INDEX_MAX_CHARS = 120_000

    /** 一篇最多进倒排多少个词（索引体积的闸门，同上）。 */
    const val MAX_DOC_TOKENS = 4096

    /** 切词唯一入口（**不在这里重算**任何规则，见文件头第一条）。 */
    fun split(text: String): Set<String> = MemoryRules.tokensOf(text)

    /**
     * 一次检索用的结果集（[truncated] 为真 = 原词比 [MAX_TERMS] 多，尾部被截；界面必须明说）。
     *
     * 顺序＝[MemoryRules.tokensOf] 的文本顺序（`LinkedHashSet` 保证），所以截断留下的是
     * **靠前**的词——用户打的那几个字通常就在前面。
     */
    class Terms(val tokens: List<String>, val truncated: Boolean) {
        val isEmpty: Boolean get() = tokens.isEmpty()
    }

    /**
     * 本包里**唯一**接用户原文的口（隐私线：原文只走到这里，之后全是切好的 token）。
     *
     * 空/全标点 ⇒ 空表（不查库，也不记日志）。返回的 [Terms.tokens] 只进绑定参数
     * （`SearchSql.selectSearch` 的 `IN (?, ?, …)`），一个字都不进 SQL 文本、不进日志、不进任何列。
     */
    fun termsOf(text: String): Terms {
        val all = MemoryRules.tokensOfQuery(text).toList()
        return if (all.size <= MAX_TERMS) Terms(all, false) else Terms(all.take(MAX_TERMS), true)
    }

    /** 一篇的倒排行数（token → cnt）；见文件头第二条的算式。 */
    fun tokenCounts(title: String, body: String): Counts {
        val titleTokens = split(title)
        val clipped = if (body.length <= BODY_INDEX_MAX_CHARS) body else body.take(BODY_INDEX_MAX_CHARS)
        val bodyTokens = split(clipped)
        val out = LinkedHashMap<String, Int>()
        titleTokens.forEach { t -> if (t.isNotEmpty()) out[t] = (out[t] ?: 0) + TITLE_WEIGHT }
        bodyTokens.forEach { t -> if (t.isNotEmpty()) out[t] = (out[t] ?: 0) + BODY_WEIGHT }
        val over = out.size > MAX_DOC_TOKENS
        val rows = if (over) out.entries.take(MAX_DOC_TOKENS).associate { it.key to it.value } else out
        return Counts(rows, over || body.length > clipped.length)
    }

    /** 一篇的切词结果（[truncated] = 正文按字符数截过 或 词数按 [MAX_DOC_TOKENS] 截过）。 */
    class Counts(val counts: Map<String, Int>, val truncated: Boolean)

    /**
     * 现算分数（**与库里的 `SUM(cnt)` 同式**，用来做行为等价比对与结果自检）。
     *
     * 逐个查询词累加：标题里 +3、正文里 +1，一个词最多计两次——这就是 `KbRetriever.score`
     * 的口径（它的 `s += 3`／`s += 1` 两条，本函数一字不差地照做，只是匹配判据从子串换成整 token）。
     */
    fun scoreOf(title: String, body: String, terms: Set<String>): Int {
        if (terms.isEmpty()) return 0
        val t = split(title)
        val b = split(body)
        var s = 0
        for (term in terms) {
            if (term.isNotEmpty() && term in t) s += TITLE_WEIGHT
            if (term in b) s += BODY_WEIGHT
        }
        return s
    }

    /**
     * 排序口径（与 [SearchSql.selectSearch] 的 `ORDER BY score DESC, d.rel_path` **逐字同一个**：
     * 分数降序、同分按路径升序）。库里已经排过一遍，这里只给「多根合并」这类内存侧聚合用——
     * 两处必须同序，否则同一查询换个入口就换个第 1 名（门禁 `PhoneRankTest.结构_排序口径两处同序`）。
     */
    fun rank(hits: List<TermHit>, limit: Int = SearchSql.SEARCH_LIMIT): List<TermHit> {
        require(limit >= 1) { "limit 至少 1，实传 $limit" }
        return hits.sortedWith(
            compareByDescending<TermHit> { it.score }.thenBy { it.relPath },
        ).take(limit)
    }
}
