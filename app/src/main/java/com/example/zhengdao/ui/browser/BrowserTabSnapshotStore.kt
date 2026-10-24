// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.ui.browser

import android.content.Context
import android.database.Cursor
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.browser.BrowserSnapshotPolicy
import com.example.zhengdao.browser.BrowserTabSql
import com.example.zhengdao.rootfs.RunLog

/**
 * 玄览页签快照的**落库出口**（第一百七十二批 L4）。
 *
 * 三条纪律：
 * 1. **写一律走 `BaopuDb.write {}`**（单写者线程 `zd-baopu-db`），绝不在主线程碰 SQLite；
 * 2. 失败只 `RunLog`，**不抛、不弹**——快照是增强，不是主链路（崩在这儿等于把玄览搞坏）；
 * 3. URL 已经过 [BrowserSnapshotPolicy.sanitizeUrlForStore] 洗过才允许进来（本类**不做**第二道脱敏：
 *    脱敏判据只有一处，在 policy 与 `SensitiveRedactor`）。
 *
 * 与 `browser_tab` 表一一对应；表由 `BaopuDb` v19 迁移创建（`BrowserTabSql.DDL`）。
 */
internal object BrowserTabSnapshotStore {

    /** 标题脱敏用（与桥同一套判据；一个实例够用，避免每次落库都 new）。 */
    private val redactor = com.example.zhengdao.browser.SensitiveRedactor()

    /** 读恢复行（按创建序）。IO 线程调用，主线程不许直接进来。 */
    fun load(context: Context, limit: Int = BrowserSnapshotPolicy.MAX_ROWS): List<BrowserSnapshotPolicy.Row> =
        runCatching {
            BaopuRuntime.db(context).read { db ->
                db.rawQuery(BrowserTabSql.SELECT_RESTORE, arrayOf(limit.toString())).use { c ->
                    buildList {
                        while (c.moveToNext()) add(c.toRow())
                    }
                }
            }
        }.onFailure { RunLog.log("玄览 快照读取失败：${it.message}") }
            .getOrDefault(emptyList())

    /**
     * 写/更新一条（upsert）。
     *
     * @param touch true = 同时刷新 `last_used_at`（切到该标签、用户点开恢复行时）
     * @param scrollX/scrollY 已经 clamp 过的 CSS px
     */
    fun upsert(
        context: Context,
        tabId: Int,
        url: String,
        title: String,
        scrollX: Float,
        scrollY: Float,
        now: Long,
        touch: Boolean,
    ) {
        val safeUrl = BrowserSnapshotPolicy.sanitizeUrlForStore(url) ?: return
        runCatching {
            BaopuRuntime.db(context).write { db ->
                db.execSQL(
                    "INSERT INTO browser_tab(id,url,title,scroll_x,scroll_y,updated_at,last_used_at) " +
                        "VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                        "url=excluded.url, title=excluded.title, scroll_x=excluded.scroll_x, " +
                        "scroll_y=excluded.scroll_y, updated_at=excluded.updated_at, " +
                        "last_used_at=CASE WHEN ? THEN excluded.last_used_at ELSE last_used_at END",
                    arrayOf(
                        tabId,
                        safeUrl,
                        redactor.redactTabTitle(title),
                        scrollX,
                        scrollY,
                        now,
                        now,
                        if (touch) 1 else 0,
                    ),
                )
                prune(db, now)
            }.get()
        }.onFailure { RunLog.log("玄览 快照写入失败（tab#$tabId）：${it.message}") }
    }

    /** 只更新滚动（滚动节流用；不动 url/title/updated_at，避免把"内容变更"记成滚动时间）。 */
    fun updateScroll(context: Context, tabId: Int, scrollX: Float, scrollY: Float, now: Long) {
        runCatching {
            BaopuRuntime.db(context).write { db ->
                db.execSQL(
                    "UPDATE browser_tab SET scroll_x=?, scroll_y=? WHERE id=?",
                    arrayOf(scrollX, scrollY, tabId),
                )
            }.get()
        }.onFailure { RunLog.log("玄览 滚动写入失败（tab#$tabId）：${it.message}") }
    }

    /** 只刷新 LRU 时间戳（切标签）。 */
    fun touch(context: Context, tabId: Int, now: Long) {
        runCatching {
            BaopuRuntime.db(context).write { db ->
                db.execSQL("UPDATE browser_tab SET last_used_at=? WHERE id=?", arrayOf(now, tabId))
            }.get()
        }.onFailure { RunLog.log("玄览 LRU 刷新失败（tab#$tabId）：${it.message}") }
    }

    /** 关标签：删行。 */
    fun remove(context: Context, tabId: Int) {
        runCatching {
            BaopuRuntime.db(context).write { db ->
                db.execSQL("DELETE FROM browser_tab WHERE id=?", arrayOf(tabId))
            }.get()
        }.onFailure { RunLog.log("玄览 快照删除失败（tab#$tabId）：${it.message}") }
    }

    /** 剪枝（TTL ＋ 超量），在同一个写事务里跟写入一起跑。 */
    private fun prune(db: android.database.sqlite.SQLiteDatabase, now: Long) {
        db.execSQL(BrowserTabSql.PRUNE_TTL, arrayOf(now - BrowserSnapshotPolicy.TTL_MS))
        db.execSQL(BrowserTabSql.PRUNE_OVERFLOW, arrayOf(BrowserSnapshotPolicy.MAX_ROWS))
    }

    private fun Cursor.toRow() = BrowserSnapshotPolicy.Row(
        id = getInt(0),
        url = getString(1).orEmpty(),
        title = getString(2).orEmpty(),
        scrollX = getFloat(3),
        scrollY = getFloat(4),
        updatedAt = getLong(5),
        lastUsedAt = getLong(6),
    )
}
