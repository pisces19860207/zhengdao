// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.browser

/**
 * `xuanlan_page` 表的 DDL 与语句（651 会话级页面记忆）。
 *
 * 为什么**另开一张表、另开一个库**（`filesDir/baopu/xuanlan_page.db`），不塞进 `browser_tab`
 * 也不升 `BaopuDb.VERSION`：
 * - `browser_tab` 是**标签快照**（id 就是标签 id、带滚动位、写入即剪枝），语义是"这台设备上
 *   开着哪些标签"；页面记忆是"这个会话里看过哪些页"，**跨标签、跨重启、按会话分桶**。
 *   塞进同一张表要么抢 id，要么给每行加一列再判两种语义（=两个真相）。
 * - 升 `BaopuDb.VERSION` 会连带 6 份别的批次的迁移计数门禁（`DiaryDbGateTest` /
 *   `MemoryQualityGateTest` / `DecisionWiringGateTest` …），白改别人的账。
 *   本仓已有**先例**：`search.db`（197 批）就是"另开库、全 DDL `IF NOT EXISTS`、没有版本阶梯"。
 *
 * 与 `search.db` 的唯一差别：页面记忆**不是可重建的缓存**（重建就是把用户看过的页丢了），
 * 所以 [XuanlanPageDb] 在结构升级时只清这一张表并如实记一行日志，不整包删库。
 *
 * **顺序由 `id` 承担**（AUTOINCREMENT 单调、删行不回收）：
 * 同一毫秒连开两页也不会乱序——这是"有序"这条判据唯一稳的落法（时间戳会撞）。
 *
 * 列就是 [PageMemoryPolicy.COLUMNS]，一列不多（判据：只记 URL 与标题）。
 */
object PageMemorySql {

    /** 建表 ＋ 按会话取最近若干页的索引（`IF NOT EXISTS` ⇒ 新装与升级同一条路径）。 */
    val DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS xuanlan_page (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL,
            url        TEXT    NOT NULL,
            title      TEXT    NOT NULL DEFAULT '',
            visited_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_xuanlan_page_session ON xuanlan_page(session_id, id DESC)",
    )

    /**
     * 取某会话最近 N 条。
     *
     * 内层按 `id DESC` 取"最近 N"，外层再按 `id ASC` 翻回来 ⇒ 返回 **oldest → newest**，
     * 与 [PageMemoryPolicy] 的顺序语义一致（界面要倒序由 `recentFirst` 负责，不在 SQL 里翻）。
     */
    const val SELECT_SESSION =
        "SELECT url, title FROM (" +
            "SELECT url, title, id FROM xuanlan_page WHERE session_id=? ORDER BY id DESC LIMIT ?" +
            ") ORDER BY id ASC"

    /** 重复访问：先摘旧行（新的一次会以新的 id 落到末尾）。 */
    const val DELETE_SAME_URL = "DELETE FROM xuanlan_page WHERE session_id=? AND url=?"

    /** 写一条。 */
    const val INSERT = "INSERT INTO xuanlan_page(session_id,url,title,visited_at) VALUES(?,?,?,?)"

    /** 超量剪枝：这个会话只留最近 N 条（按 id）。 */
    const val PRUNE_OVERFLOW =
        "DELETE FROM xuanlan_page WHERE session_id=? AND id NOT IN (" +
            "SELECT id FROM xuanlan_page WHERE session_id=? ORDER BY id DESC LIMIT ?)"

    /** 单会话清空。 */
    const val DELETE_SESSION = "DELETE FROM xuanlan_page WHERE session_id=?"

    /** 全清。 */
    const val DELETE_ALL = "DELETE FROM xuanlan_page"

    /** 有账的会话数（「全部清空」要能说清清掉几个会话的账）。 */
    const val COUNT_SESSIONS = "SELECT COUNT(DISTINCT session_id) FROM xuanlan_page"

    /** 结构升级时的唯一动作：清这张表（记忆不是可重建的缓存，但结构不认识时只能重来）。 */
    const val DROP_TABLE = "DROP TABLE IF EXISTS xuanlan_page"
}
