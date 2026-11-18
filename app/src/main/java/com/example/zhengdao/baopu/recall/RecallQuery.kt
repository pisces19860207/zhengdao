// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **只读检索语句的纯层**（无 Android 依赖，语句形状 JVM 直测）。
//
// ## 为什么 SQL 住在这儿
// 全仓房型：语句文本与绑定参数在纯层逐字对账（`SearchSqlTest`／`BaopuDbTest` 同款），
// 壳层只拿句柄执行。本对象**只许 SELECT**——「不新建索引、不改索引结构」的另一半：
// 这里连 DDL 的字面量都不该出现（`RecallSourceGateTest` 扫本包源码钉死）。
//
// ## 查的是什么
// 既有主库的 `message` × `part(kind='text')` × `session`：正文在 text part 的
// `payload` 里；回收站里的会话（`deleted_at` 非 NULL）不出——「上次说的」不该把
// 用户亲手删掉的会话翻回来。命中按新话在前（读完一次窗口就能点最近那条）。
// 词只走 `LIKE ?` 绑定参数，语句文本里只有 `?`（与 `PhoneIndex.search` 同一条隐私线）。
package com.example.zhengdao.baopu.recall

import com.example.zhengdao.baopu.BaopuSql

internal object RecallQuery {

    /** 一次检索最多翻几行原文（要点最多 5 条，200 行的候选池绰绰有余；硬顶防长尾）。 */
    const val ROW_CAP = 200

    /** 一组线索最多带几个词进语句（与 [VagueTrigger.MAX_KEYWORDS] 同数量级，防炸 IN）。 */
    const val MAX_KEYWORDS = 8

    /** 一次补时刻的最多 id 数（索引命中 ≤20 ＋ 摘要 ≤5；留一倍余量，防炸 IN）。 */
    const val MAX_TIME_IDS = 64

    private const val HEAD =
        "SELECT m.id, m.session_id, m.created_at, p.payload " +
        "FROM message m " +
        "JOIN part p ON p.message_id = m.id " +
        "JOIN session s ON s.id = m.session_id " +
        "WHERE s.deleted_at IS NULL AND p.kind = 'text' AND ("

    /** 一条线索检索语句；空词表 ⇒ null（不发语句，一次没内容的查询不算检索次数）。 */
    fun searchStmt(keywords: List<String>): BaopuSql.Stmt? {
        val kws = keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_KEYWORDS)
        if (kws.isEmpty()) return null
        val clause = kws.joinToString(" OR ") { "p.payload LIKE ? ESCAPE '\\'" }
        val sql = HEAD + clause + ") ORDER BY m.created_at DESC, m.id DESC LIMIT $ROW_CAP"
        return BaopuSql.Stmt(sql, Array<String?>(kws.size) { likeArg(kws[it]) })
    }

    /**
     * LIKE 绑定值：整词子串匹配；`\` `%` `_` 三个通配转义（用户原话里带这两个符号
     * 时，「不精确」好过「语句被通配撑爆」）。转义符用语句里写死的 `\`。
     */
    fun likeArg(keyword: String): String =
        "%" + keyword
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"

    /** 游标一行 → 命中（列序与 [HEAD] 一致；壳层取错列是测试红，不是运行时炸）。 */
    fun hitOf(id: Long, sessionId: Long, createdAt: Long, payload: String): RecallHit =
        RecallHit(sessionId = sessionId, messageId = id, atMs = createdAt, text = payload)

    /**
     * 索引命中补「原文时刻」（533 的 `SearchHit` 不带时刻，540 的组内排序要用）。
     *
     * 只按主键 `message.id` 打点：一条 `IN (?,…)`，与正文无关、与查询词无关
     * （参数只有消息 id）。查不到的行**不补**——`message` 里已经没有它（压缩/删掉），
     * 调用方据此丢这条命中（533 侧「取不到正文即丢」的同一条口径）。
     */
    fun timestampsStmt(msgIds: List<Long>): BaopuSql.Stmt? {
        val ids = msgIds.filter { it > 0L }.distinct().take(MAX_TIME_IDS)
        if (ids.isEmpty()) return null
        val sql = "SELECT id, created_at FROM message WHERE id IN (" +
            ids.joinToString(",") { "?" } + ")"
        return BaopuSql.Stmt(sql, Array<String?>(ids.size) { ids[it].toString() })
    }
}
