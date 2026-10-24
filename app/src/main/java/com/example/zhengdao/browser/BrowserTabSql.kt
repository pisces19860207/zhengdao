// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.browser

/**
 * `browser_tab` 表的 DDL（第一百七十二批 L4）。
 *
 * 设计依据：`_zd-private/docs/设计-2026-10-24-第一百七十二批-L4页签快照.md` §2 / §2.1。
 *
 * 为什么单独一个对象（而不是把 SQL 写在 `BaopuDb.applyV19Migration` 里）：
 * 本项目历次加表都是「一份 DDL 常量 + 迁移体只负责执行」（如 `WorkspaceGrantSql.DDL`），
 * 迁移与建表共用同一份字面量，不会出现"新装的表少一列、升级的少一张表"的分叉。
 *
 * 六列全部来自用户 2026-10-24 的拍板（url/title/scroll_x/scroll_y/updated_at/LRU），
 * 刻意**不加第七列**：顺序由自增 `id` 承担、活跃标签由 `last_used_at` 最大者决定。
 */
object BrowserTabSql {

    /** 建表 + LRU 索引（全部 `IF NOT EXISTS` ⇒ 迁移可重复执行）。 */
    val DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS browser_tab (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            url          TEXT    NOT NULL,
            title        TEXT    NOT NULL DEFAULT '',
            scroll_x     REAL    NOT NULL DEFAULT 0,
            scroll_y     REAL    NOT NULL DEFAULT 0,
            updated_at   INTEGER NOT NULL,
            last_used_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_browser_tab_lru ON browser_tab(last_used_at DESC)",
    )

    /** 恢复读：按创建序（`id ASC`）取前 N 条。 */
    const val SELECT_RESTORE =
        "SELECT id, url, title, scroll_x, scroll_y, updated_at, last_used_at " +
            "FROM browser_tab ORDER BY id ASC LIMIT ?"

    /** 清空（用户手动"不恢复上次页面"时用；本轮只提供语句，不接入 UI）。 */
    const val DELETE_ALL = "DELETE FROM browser_tab"

    /** TTL 剪枝：早于阈值的全删。 */
    const val PRUNE_TTL = "DELETE FROM browser_tab WHERE last_used_at < ?"

    /** 超量剪枝：按 LRU 只留最近 N 条。 */
    const val PRUNE_OVERFLOW =
        "DELETE FROM browser_tab WHERE id NOT IN (" +
            "SELECT id FROM browser_tab ORDER BY last_used_at DESC LIMIT ?)"
}
