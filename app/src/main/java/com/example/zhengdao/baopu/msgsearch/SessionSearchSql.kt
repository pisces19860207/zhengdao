// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：702 派工单（533 落地单 §①②）——会话搜索索引落在**私有 `search.db`**（App 内部存储）
// 里，两张新表 `messages` ＋ `tokens`（**普通表倒排**：真机实测系统 SQLite 没编 FTS5，设计
// §12.2，`SearchSql.kt` 头注同款）。派单红线：**正文只存哈希（text_sha），正文留在主库
// `message`×`part` 原处**；删会话 ⇒ 删其 token 行（**不重建全表**）。
//
// 为什么 SQL 全是纯文本、不落在 `SQLiteDatabase` 上：本仓没有 Robolectric，`android.jar` 是
// stub ⇒ JVM 里 SQLite 跑不起来（`SearchSql.kt` 头注同款）。表长什么样、每条语句绑哪些参数、
// 删会话删谁，由字符串测试钉死（房型照 `SearchSqlTest`），薄壳 [SessionSearchStore] 只管执行。
//
// 为什么不并进 `SearchSql`：那边五张表是手机文件索引的领地，`SearchSqlTest` 的表名全集／
// 列名逐字对账／写语句表名单三道门禁都按它钉的——往里加会话表等于改别人的门禁。本对象
// 自持 DDL（全 `CREATE TABLE IF NOT EXISTS`，缺表就建），共用 [SearchDb] 同一个连接壳，
// `search.db` 原五张表一个字不动（540 交付单 §五「search.db 四表与主库既有索引一字未改」的延续）。
package com.example.zhengdao.baopu.msgsearch

/**
 * `search.db` 会话索引（533/702）的建表语句与全部 SQL 常量（**只有字符串**，没有 Android 依赖，JVM 直测）。
 *
 * ## 两张表（702 派工单点名的形状；列名落成全仓 SQL 惯例的 snake_case，与派单的 camelCase
 *    一一对应：msgId→msg_id、sessionId→session_id、ts→ts、textSha→text_sha）
 * - `messages`：一条消息一行——`msg_id` 即主库 `message.id`（自增主键、永不复用 ⇒ 不需要
 *   AUTOINCREMENT，`SearchSql` 那条「rowid 复用」的顾虑在这里不存在：id 的真源在主库）；
 *   `text_sha` 是正文 SHA-256（**正文不进这张库**——派单「正文留原处」；隐私线同
 *   `SearchSqlTest` 的「没有任何列能存下查询词」，`SessionSearchSqlTest` 逐列对账钉死）。
 * - `tokens`：倒排 `token → msg_id → tf`；token 来自 `MemoryRules.tokenCountsOf`
 *   （与记忆／手机文件检索**同一个分词机**，702 派工单「分词复用 tokensOf／不新造第二套分词」）。
 *
 * ## 三处自作主张（交付报告逐条说明，都有理由）
 * 1. `messages` 加 `len`（正文 token 总次数）：BM25 的文档长度 dl 必需，没有它打分不自洽；
 *    派单 filter 的「长度上限」也落在这一列上（同源，不另造一列）。
 * 2. `messages` 加 `indexed_at`：状态读数 `lastIndexedAt` 的真源（`MAX(indexed_at)`）。
 * 3. 删会话只写**一条** `DELETE FROM messages WHERE session_id = ?`：token 行靠
 *    `ON DELETE CASCADE` 联动（外键由 `SearchDb.onConfigure` 的 PRAGMA 打开；房型照
 *    `SearchSql` doc/tok 先例「不写第二条 DELETE，避免『删了一半』」）。**不重建全表**是
 *    结构性保证：本对象没有任何无 `WHERE` 的 DELETE（`SessionSearchSqlTest` 钉死），
 *    也没有 rebuild 方法——库坏了的重建路径是「删掉 search.db 文件 ⇒ 表自愈 ⇒ catch-up 重铺」。
 */
internal object SessionSearchSql {

    const val TABLE_MESSAGES = "messages"
    const val TABLE_TOKENS = "tokens"

    /** 本包允许写的表名全集（隐私门禁：任何写语句只能碰这两张）。 */
    val TABLES: Set<String> = setOf(TABLE_MESSAGES, TABLE_TOKENS)

    /**
     * 会话索引 DDL（缺表就建——`search.db` 是可整包丢弃重建的缓存，`SearchDb` 头注同款）。
     * `tokens(token, msg_id)` 复合主键最左列就是 token ⇒ 查询走它，不再另建索引
     * （`SearchSql` tok 表「再建一棵是同一份数据的第二份拷贝」同款理由）。
     */
    val DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS messages(
          msg_id INTEGER PRIMARY KEY,
          session_id INTEGER NOT NULL,
          role TEXT NOT NULL,
          ts INTEGER NOT NULL,
          text_sha TEXT NOT NULL,
          len INTEGER NOT NULL DEFAULT 0,
          indexed_at INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        // 删会话（按 session_id 删行）与孤儿清点（DISTINCT session_id）都按会话翻 ⇒ 不加索引就是全表扫。
        "CREATE INDEX IF NOT EXISTS idx_messages_session ON messages (session_id)",
        """
        CREATE TABLE IF NOT EXISTS tokens(
          token TEXT NOT NULL,
          msg_id INTEGER NOT NULL REFERENCES messages(msg_id) ON DELETE CASCADE,
          tf INTEGER NOT NULL,
          PRIMARY KEY(token, msg_id)
        )
        """.trimIndent(),
    )

    // ── 写侧（messages / tokens 两张表之外一律不许出现） ─────────────────────

    /** 幂等入库（catch-up 重遇同一条＝刷新不是追加；`indexed_at` 每次都刷成当下）。 */
    const val UPSERT_MESSAGE =
        "INSERT INTO messages(msg_id, session_id, role, ts, text_sha, len, indexed_at) " +
            "VALUES(?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(msg_id) DO UPDATE SET session_id = excluded.session_id, role = excluded.role, " +
            "ts = excluded.ts, text_sha = excluded.text_sha, len = excluded.len, indexed_at = excluded.indexed_at"

    /** 倒排一行（`INSERT OR REPLACE`：同词同消息只有一行计数，重复入库是刷新——`UPSERT_TOK` 同款）。 */
    const val INSERT_TOKEN = "INSERT OR REPLACE INTO tokens(token, msg_id, tf) VALUES(?, ?, ?)"

    /** 重索引一条消息先清它的旧词（正文改了旧词不该还挂着；新词由 [INSERT_TOKEN] 补上）。 */
    const val DELETE_TOKENS_OF_MESSAGE = "DELETE FROM tokens WHERE msg_id = ?"

    /**
     * 删会话＝删它的 messages 行，token 靠外键级联一起走（派单「删会话 ⇒ 删其 token 行」）。
     * 本对象所有 DELETE 都带 `WHERE`——无 WHERE 的全表删除不存在（判据④「不重建全表」）。
     */
    const val DELETE_MESSAGES_OF_SESSION = "DELETE FROM messages WHERE session_id = ?"

    // ── 读侧（search.db） ─────────────────────────────────────────────────────

    /** 水位＝已索引的最大 msg_id（自增 id 永不复用 ⇒ 「id 比它大」就等于「还没索引过」）。 */
    const val SELECT_MAX_MSG_ID = "SELECT MAX(msg_id) FROM messages"

    /** 语料统计：N 与平均文档长（BM25 的 docCount／avgDl；空表给 0，不打分）。 */
    const val SELECT_STATS = "SELECT COUNT(*), COALESCE(AVG(len), 0) FROM messages"

    /** 状态读数（派单 §④ 要的 {messages, tokens, lastIndexedAt}）：三条子查询一行拿全。 */
    const val SELECT_STATUS =
        "SELECT (SELECT COUNT(*) FROM messages), (SELECT COUNT(*) FROM tokens), " +
            "(SELECT COALESCE(MAX(indexed_at), 0) FROM messages)"

    /** 孤儿清点的输入：索引里出现过哪些会话（与主库 `session` 对不上的就是被删掉的）。 */
    const val SELECT_INDEX_SESSIONS = "SELECT DISTINCT session_id FROM messages"

    /**
     * 候选倒排＋候选元数据一次拿全（JOIN 保证每个候选都带 meta，不用第二趟）。
     * **词永远走绑定参数**（`SearchSql.selectSearch` 同款隐私线：词本身一个字都不进 SQL 文本）。
     */
    fun selectPostings(tokenCount: Int): String {
        require(tokenCount in 1..MAX_QUERY_TOKENS) {
            "tokenCount 要在 1..$MAX_QUERY_TOKENS，实传 $tokenCount"
        }
        return "SELECT t.token, t.msg_id, t.tf, m.session_id, m.role, m.ts, m.len " +
            "FROM tokens t JOIN messages m ON m.msg_id = t.msg_id " +
            "WHERE t.token IN (" + placeholders(tokenCount) + ")"
    }

    /** 占位符串（纯函数，可单测）：只允许 `?`、逗号、空格——出现别的字符就是拼参数进 SQL。 */
    fun placeholders(count: Int, max: Int = MAX_QUERY_TOKENS): String {
        require(count in 1..max) { "count 要在 1..$max，实传 $count" }
        return List(count) { "?" }.joinToString(", ")
    }

    // ── 读侧（主库 baopu.db——只读，表名只许 message / part / session） ────────

    /**
     * 增量的候选行：id 比水位大（没见过的）**或**还没标 done 的（在飞那条每次都重看一遍，
     * 完成后自然带全文入索引；`RunEngine` 现状都是 done 直插，这条子句是防御）。
     * ORDER BY id 正序——水位语义要求从旧往新铺，首批慢点、后面的每轮只补增量。
     */
    const val SRC_AFTER =
        "SELECT id, session_id, role, created_at FROM message " +
            "WHERE (id > ? OR status != 'done') ORDER BY id LIMIT ?"

    /** 一批消息的正文（`kind='text'` 的 part；多段按 seq 拼——用户正文在 seq=0，`BaopuDb` 同款）。 */
    fun selectTexts(messageCount: Int): String {
        require(messageCount in 1..MAX_BATCH_MSGS) {
            "messageCount 要在 1..$MAX_BATCH_MSGS，实传 $messageCount"
        }
        return "SELECT message_id, payload FROM part " +
            "WHERE kind = 'text' AND message_id IN (" +
            placeholders(messageCount, MAX_BATCH_MSGS) + ") ORDER BY message_id, seq"
    }

    /** 回收站里的会话（`deleted_at IS NOT NULL`）——search 命中要把它滤掉（540「回收站会话不出」同款）。 */
    const val SRC_TRASHED_SESSIONS = "SELECT id FROM session WHERE deleted_at IS NOT NULL"

    /** 孤儿清点的对岸：主库还活着的会话 id（软删的也算活着——索引留着，恢复即回来）。 */
    const val SRC_ALL_SESSIONS = "SELECT id FROM session"

    /** 摘要提炼的原材料：某会话全部正文（多段按 seq；id 天然单调，同 id 内按 seq 定序）。 */
    const val SRC_SESSION_TEXTS =
        "SELECT m.id, m.role, p.payload FROM message m " +
            "JOIN part p ON p.message_id = m.id AND p.kind = 'text' " +
            "WHERE m.session_id = ? ORDER BY m.id, p.seq"

    // ── 常量 ────────────────────────────────────────────────────────────────

    /** 一次查询最多绑多少个词（`tokensOf` 切出的词按文本序取前 N——`PhoneRank.termsOf` 同款截断）。 */
    const val MAX_QUERY_TOKENS = 8

    /** 一次 catch-up 最多索引多少条消息（首轮回填分多轮慢慢铺，不一口气吃下十万条）。 */
    const val MAX_BATCH_MSGS = 500
}
