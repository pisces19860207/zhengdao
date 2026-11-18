// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：702 派工单 §①③④——私有 `search.db` 里的会话索引薄壳：增量入库、检索、摘要、状态读数。
// 房型照 `SearchDb`／`PhoneIndex`：SQL 全在 [SessionSearchSql]（纯文本、JVM 直测），
// 本类只做「拿连接、绑参数、执行」；算法全在 [SessionSearchEngine]（纯函数）。
//
// 三条纪律（与 `PhoneIndex` 同一条隐私线／同一套失败形态）：
// 1. **查询词与正文不进日志**：SQLite 异常的 message 会抄绑定参数，失败只记步骤名与异常类名；
// 2. **fail-open**：搜索/摘要失败按「没结果」、写失败按「没写成」——540 的 Port 就是这个口径；
// 3. **增量只进新账**：catch-up 只碰「id 比水位大（或还没 done）」的行，删会话删的是
//    会话作用域的行——全对象没有无 `WHERE` 的 DELETE，判据④「不重建全表」是结构性的。
package com.example.zhengdao.baopu.msgsearch

import android.content.Context
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.MemoryRules
import com.example.zhengdao.baopu.search.SearchDb
import com.example.zhengdao.core.RunLog
import java.security.MessageDigest

/** 索引状态读数（702 §④ 给 540 的第三件：{messages, tokens, lastIndexedAt}）。 */
internal data class SessionIndexStatus(
    val messages: Long,
    val tokens: Long,
    val lastIndexedAt: Long,
)

/**
 * 会话搜索索引（533/702）的连接壳与全部写读动作。
 *
 * 给 540 的接口（派单 §④）：[search]／[digest]／[status]；[catchUp]／[removeSession]／
 * [sweepOrphans] 是增量与删除的维护面。540 合入后由它的 `RecallSearchPort` 适配到这三件
 * （540 交付单 §五「533 合入后由它实现该接口换底」），本类不改。
 *
 * `search.db` 原有五张表（手机文件索引）一个字没动：本类只在自己的 DDL 上 `IF NOT EXISTS`
 * 自愈建表，共用 [SearchDb] 的同一个连接壳（每次使用点新建，`PhoneSearch` 同款）。
 */
internal class SessionSearchStore(private val baopu: BaopuDb, context: Context) {

    private val db = SearchDb(context)

    init {
        // 缺表就建（幂等；search.db 整包丢弃重建后，下一次进来自愈）——SearchDb 一行未改。
        runCatching { db.write { d -> SessionSearchSql.DDL.forEach { d.execSQL(it) } } }
            .onFailure { logStep("ensureTables", it) }
    }

    /** 状态读数：messages＝消息行数；tokens＝倒排行数（postings）；lastIndexedAt＝最后一次入库时刻。 */
    fun status(): SessionIndexStatus = runCatching {
        db.readable().rawQuery(SessionSearchSql.SELECT_STATUS, null).use { c ->
            if (c.moveToFirst()) {
                SessionIndexStatus(messages = c.getLong(0), tokens = c.getLong(1), lastIndexedAt = c.getLong(2))
            } else {
                SessionIndexStatus(0, 0, 0)
            }
        }
    }.getOrDefault(SessionIndexStatus(0, 0, 0))

    /**
     * 关键词检索（702 §②）：先增量补索引，再倒排查候选、BM25 打分、取前 [limit] 条。
     * 回收站会话的命中直接滤掉（540「回收站会话不出」同款）；正文被压缩／删掉导致
     * 取不到原文的命中如实丢弃（540「跳转如实停在会话里」的检索侧同款）。
     */
    fun search(query: String, filters: SearchFilters = SearchFilters(), limit: Int = SessionSearchEngine.DEFAULT_LIMIT): List<SearchHit> =
        runCatching { searchInner(query, filters, limit) }
            .getOrElse { logStep("search", it); emptyList() }

    /**
     * 摘要提炼（702 §③）：某会话 ≤[max] 条要点，每条 ≤60 字、必带 msgId。
     * 纯抽取式（挑原句压缩），**不调模型**；读的是主库正文，不依赖索引——没有索引也能提。
     */
    fun digest(sessionId: Long, max: Int = SessionSearchEngine.DIGEST_MAX): List<DigestPoint> =
        runCatching {
            val msgs = readSessionTexts(sessionId)
            SessionSearchEngine.digest(msgs, max)
        }.getOrElse { logStep("digest", it); emptyList() }

    /**
     * 增量入库（702 §①）：把「id 比水位大 ＋ 还没 done 的」消息补进索引；返回本次入库条数。
     * 先清孤儿（主库里已经没有的会话，索引行跟着删——判据⑤的兜底），再按批补新消息。
     */
    fun catchUp(maxMessages: Int = SessionSearchSql.MAX_BATCH_MSGS): Int =
        runCatching {
            sweepOrphans()
            val watermark = readMaxMsgId()
            val rows = readSourceAfter(watermark, maxMessages)
            if (rows.isEmpty()) 0 else indexRows(rows)
        }.getOrElse { logStep("catchUp", it); 0 }

    /** 删一个会话的索引行（messages 删行，token 靠外键级联一起走——派单「删会话 ⇒ 删其 token 行」）。 */
    fun removeSession(sessionId: Long): Boolean = db.exec(
        SessionSearchSql.DELETE_MESSAGES_OF_SESSION,
        arrayOf<Any?>(sessionId),
    )

    /** 孤儿清点：索引里还有、主库已经没有的会话（硬删残留）⇒ 删其索引行。返回清掉的会话数。 */
    fun sweepOrphans(): Int = runCatching {
        val indexed = readIndexedSessions()
        if (indexed.isEmpty()) return@runCatching 0
        val live = readAllSessionIds()
        val gone = indexed - live
        gone.forEach { removeSession(it) }
        gone.size
    }.getOrElse { logStep("sweepOrphans", it); 0 }

    // ── 检索内件（失败已在外层吞，这里只按正常路径写） ──────────────────────────

    private fun searchInner(query: String, filters: SearchFilters, limit: Int): List<SearchHit> {
        catchUp()
        val terms = SessionSearchEngine.queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        val (postings, docs) = readPostings(terms)
        if (postings.isEmpty()) return emptyList()
        val stats = readStats()
        val scored = SessionSearchEngine.search(
            postings, docs, stats.first, stats.second, query,
            filters, readTrashedSessions(), limit,
        )
        return renderHits(scored, terms.toSet())
    }

    /** 倒排＋候选 meta 一趟拿全（`selectPostings` 的 JOIN 保证候选必带 meta）。 */
    private fun readPostings(terms: List<String>): Pair<Map<String, List<Posting>>, Map<Long, DocMeta>> {
        val postings = HashMap<String, MutableList<Posting>>()
        val docs = HashMap<Long, DocMeta>()
        db.readable().rawQuery(SessionSearchSql.selectPostings(terms.size), terms.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val token = c.getString(0)
                postings.getOrPut(token) { mutableListOf() }.add(Posting(msgId = c.getLong(1), tf = c.getInt(2)))
                docs[c.getLong(1)] = DocMeta(
                    msgId = c.getLong(1),
                    sessionId = c.getLong(3),
                    role = c.getString(4),
                    ts = c.getLong(5),
                    len = c.getInt(6),
                )
            }
        }
        return postings to docs
    }

    private fun readStats(): Pair<Int, Double> = db.readable().rawQuery(SessionSearchSql.SELECT_STATS, null).use { c ->
        if (c.moveToFirst()) c.getInt(0) to c.getDouble(1) else 0 to 0.0
    }

    private fun readTrashedSessions(): Set<Long> = db.readable().rawQuery(SessionSearchSql.SRC_TRASHED_SESSIONS, null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getLong(0)) }
    }

    private fun readIndexedSessions(): Set<Long> = db.readable().rawQuery(SessionSearchSql.SELECT_INDEX_SESSIONS, null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getLong(0)) }
    }

    private fun readAllSessionIds(): Set<Long> = baopu.read { d ->
        d.rawQuery(SessionSearchSql.SRC_ALL_SESSIONS, null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    private fun readSessionTexts(sessionId: Long): List<DigestMsg> = baopu.read { d ->
        d.rawQuery(SessionSearchSql.SRC_SESSION_TEXTS, arrayOf(sessionId.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(DigestMsg(msgId = c.getLong(0), role = c.getString(1), text = c.getString(2)))
            }
        }
    }

    /** 命中取原文（≤limit 条，走主库 `part` 现读）＋起窗——取不到正文的命中如实丢。 */
    private fun renderHits(scored: List<Scored>, terms: Set<String>): List<SearchHit> {
        if (scored.isEmpty()) return emptyList()
        val texts = readTexts(scored.map { it.doc.msgId })
        return scored.mapNotNull { s ->
            val text = texts[s.doc.msgId] ?: return@mapNotNull null
            SearchHit(
                msgId = s.doc.msgId,
                sessionId = s.doc.sessionId,
                score = s.score,
                snippet = SessionSearchEngine.snippet(text, terms),
            )
        }
    }

    // ── 索引内件 ──────────────────────────────────────────────────────────────

    private fun readMaxMsgId(): Long = db.readable().rawQuery(SessionSearchSql.SELECT_MAX_MSG_ID, null).use { c ->
        if (c.moveToFirst()) c.getLong(0) else 0L
    }

    private fun readSourceAfter(watermark: Long, maxMessages: Int): List<SourceMsg> = baopu.read { d ->
        d.rawQuery(SessionSearchSql.SRC_AFTER, arrayOf(watermark.toString(), maxMessages.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(SourceMsg(msgId = c.getLong(0), sessionId = c.getLong(1), role = c.getString(2), ts = c.getLong(3)))
                }
            }
        }
    }

    /** 一批消息的正文（多段按 seq 用换行拼——正文留在主库，这里只是读出来切词，不落任何表）。 */
    private fun readTexts(msgIds: List<Long>): Map<Long, String> {
        if (msgIds.isEmpty() || msgIds.size > SessionSearchSql.MAX_BATCH_MSGS) return emptyMap()
        return baopu.read { d ->
            d.rawQuery(SessionSearchSql.selectTexts(msgIds.size), msgIds.map { it.toString() }.toTypedArray()).use { c ->
                val out = HashMap<Long, StringBuilder>()
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val sb = out.getOrPut(id) { StringBuilder() }
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(c.getString(1))
                }
                out.entries.associate { it.key to it.value.toString() }
            }
        }
    }

    /** 入库一批（一个事务；正文哈希＋切词都在事务外算，事务里只写 SQL——连接占用越短越好）。 */
    private fun indexRows(rows: List<SourceMsg>): Int {
        val texts = readTexts(rows.map { it.msgId })
        val now = System.currentTimeMillis()
        var n = 0
        db.write { d ->
            d.beginTransaction()
            try {
                rows.forEach { row ->
                    val text = texts[row.msgId] ?: return@forEach
                    val trimmed = text.trim()
                    val counts = if (trimmed.isEmpty()) emptyMap() else MemoryRules.tokenCountsOf(trimmed)
                    if (counts.isEmpty()) return@forEach
                    d.execSQL(
                        SessionSearchSql.UPSERT_MESSAGE,
                        arrayOf<Any?>(
                            row.msgId, row.sessionId, row.role, row.ts,
                            sha256Hex(trimmed), counts.values.sum(), now,
                        ),
                    )
                    d.execSQL(SessionSearchSql.DELETE_TOKENS_OF_MESSAGE, arrayOf<Any?>(row.msgId))
                    counts.forEach { (token, tf) ->
                        d.execSQL(SessionSearchSql.INSERT_TOKEN, arrayOf<Any?>(token, row.msgId, tf))
                    }
                    n++
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        return n
    }

    /** 正文 SHA-256（手拼十六进制：不经 `String.format`，不吃 locale 的亏）。 */
    private fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 失败只记步骤名与异常类名：SQLite 异常的 message 会把**绑定参数**抄进文本（查询词、
     * 正文碎片都不许进日志——`PhoneIndex` 同一条隐私线），处置按 fail-open。
     */
    private fun logStep(step: String, err: Throwable) {
        RunLog.log("会话索引 $step 失败（${err::class.java.simpleName}）——按没结果/没写成处理")
    }

    /** 增量候选行（主库 message 表的投影；正文不在这一步）。 */
    private data class SourceMsg(val msgId: Long, val sessionId: Long, val role: String, val ts: Long)

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
