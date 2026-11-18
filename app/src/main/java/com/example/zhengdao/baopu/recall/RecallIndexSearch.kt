// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **索引主路径**（`RecallSearchPort` 的新生产实现，533 换底点）。
//
// ## 三条来路怎么用（702 三件各归其位）
// 1. `search`——每组线索一次 BM25 检索（自带增量入库）。命中按分数降序，第一名的
//    会话就是「这句话最可能指的那段旧话」。
// 2. `digest`——**光杆指代**专用：整句剥完没有实词（「那个怎么弄来着」）时，用户指的
//    是一整段旧话，不是某个词 ⇒ 拿第一名命中的会话做 ≤5 条抽取式摘要（每条第 msgId），
//    正好是卡面要的「要点」。有实词时仍用命中原文（更准，不会把整段会话里无关的
//    高分句端上来）。
// 3. `status`——只在调用方出读数/判断空索引时用，本类不拿它做判定。
//
// ## 为什么还要补时刻
// 533 的 `SearchHit`/`DigestPoint` 只带 msgId，不带消息时刻；540 的组内排序（新话在前）
// 要 `atMs`。补时刻走 `RecallQuery.timestampsStmt`（主键点查，≤64 个 id）。
// **补不到就丢**：`message` 里已经没有这条（被压缩/删掉）⇒ 与 533「取不到正文即丢」
// 同一条口径，不许把一条跳不过去的要点端给用户。
//
// ## 为什么索引空/没命中要直读兜底
// 533 的增量是「从水位往后补，≤500 条/轮」。升级后的头几次唤回，索引只铺到最旧的那
// 500 条，而「上次说的」通常指最近的旧话 ⇒ 只查索引会把「索引还没铺到」说成
// 「没找到」，那是假话。所以调用方（`RecallFallback`）在索引零命中时用**同一批线索**
// 直读一次；两条路都空才叫没找到。兜底不新增线索组 ⇒ 仍守「多线索检索 ≤3 次」。
package com.example.zhengdao.baopu.recall

internal class RecallIndexSearch(
    private val index: RecallIndexPort,
    private val timesOf: (List<Long>) -> Map<Long, Long>,
    /** 这句话自己有没有实词（false ⇒ 光杆指代 ⇒ 要点走 [RecallIndexPort.digest]）。 */
    private val preferDigest: Boolean,
) : RecallSearchPort {

    override fun search(keywords: List<String>): List<RecallHit> {
        val query = RecallIndexPlan.queryOf(keywords) ?: return emptyList()
        val hits = index.search(query, RecallIndexPlan.INDEX_LIMIT)
        if (hits.isEmpty()) return emptyList()
        val rows = digestRows(hits) ?: hits.map { Row(it.msgId, it.sessionId, it.snippet) }
        return hydrate(rows)
    }

    /** 光杆指代：摘要第一名命中所在会话；摘要空（老会话没有可用句）⇒ null ⇒ 退回命中原文。 */
    private fun digestRows(hits: List<IndexHit>): List<Row>? {
        if (!preferDigest) return null
        val top = hits.first()
        return index.digest(top.sessionId, RecallEngine.MAX_POINTS)
            .takeIf { it.isNotEmpty() }
            ?.map { Row(it.msgId, top.sessionId, it.text) }
    }

    /** 补时刻并丢掉补不到的（主库已无此消息）；同时完成 540 的 `RecallHit` 形状。 */
    private fun hydrate(rows: List<Row>): List<RecallHit> {
        if (rows.isEmpty()) return emptyList()
        val times = timesOf(rows.map { it.msgId })
        return rows.mapNotNull { r ->
            val at = times[r.msgId] ?: return@mapNotNull null
            RecallHit(sessionId = r.sessionId, messageId = r.msgId, atMs = at, text = r.text)
        }
    }

    /** 适配层内部行（摘要/命中两条来路折叠成同一形）。 */
    private data class Row(val msgId: Long, val sessionId: Long, val text: String)
}

/** 索引查询串的拼法（纯函数，JVM 直测；与 533 的 `queryTerms` 同口径——空格分隔，它自己切）。 */
internal object RecallIndexPlan {

    /** 一次索引检索取几条（与 702 `DEFAULT_LIMIT` 同值 20；540 最终只取前 5 条要点）。 */
    const val INDEX_LIMIT = 20

    /** 线索词 → 查询串（去空、去重、截到 [VagueTrigger.MAX_KEYWORDS]；空 ⇒ null 不发查询）。 */
    fun queryOf(keywords: List<String>): String? {
        val q = keywords.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(VagueTrigger.MAX_KEYWORDS)
            .joinToString(" ")
        return q.ifBlank { null }
    }
}
