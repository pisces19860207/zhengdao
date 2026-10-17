// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：android.database.sqlite（SQLiteDatabase.execSQL / rawQuery）官方 API；
// SQLite 官方文档（ON CONFLICT / DO NOTHING 需要 3.24+，本仓 minSdk=36 远高于此）。
package com.example.zhengdao.baopu.library

import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuSql
import java.util.concurrent.Future

/** 来源：从 `投递/` 收料进来，还是用户在抱朴页「导入」选文件进来。 */
object LibrarySource {
    const val DELIVER = "deliver"
    const val IMPORT = "import"

    /** 索引 JSON 里给人/agent 看的字面值（派工单 §1 B 段原文：`投递` 或 `导入`）。 */
    fun label(code: String): String = if (code == IMPORT) "导入" else "投递"
}

/** 认领分类（与 [LibraryPaths.FileKind] 对应的存储字面值）。 */
object LibraryKind {
    const val TEXT = "text"
    const val UNCLAIMED = "unclaimed"
}

/**
 * 一条认脸索引行（**库内镜像**；真相是共享区 `整理/_认脸索引.json`，
 * 库只是让「有没有隔离/导没导出」这类账能在 UI 上秒查）。
 *
 * 字段与索引 JSON 逐条对齐（派工单 §1 B 段：`sha256`／`name`／`size`／`mtime`／
 * `source`／`exported_at`／`isolated`／`kind`）——两份必须能互相对拍，
 * 多了少了都是双真相。
 */
data class LibraryItem(
    val sha256: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val source: String = LibrarySource.DELIVER,
    val exportedAt: Long = 0,
    /** false = 这批是「复制」进来的（共享区留底 ⇒ 隔离作废，UI 必须明示）。 */
    val isolated: Boolean = true,
    val kind: String = LibraryKind.TEXT,
    val privatePath: String = "",
    val createdAt: Long = 0,
)

/**
 * 资料库两表的**纯 SQL 层**（与 `BaopuSql` 同房型：表在 `BaopuDb` v3 里建，
 * 语句只在本文件拼——派工单 §4 A 段原话「SQL 只在这里拼」）。
 *
 * 可 JVM 单测的部分全在这里（SQL 文本契约）；Cursor 映射在壳里（android.jar 是 stub）。
 */
internal object LibrarySql {

    /** 一次性提示的 op 前缀（行在 = 提示过；「首次搬原件提示一次」靠它）。 */
    const val NOTICE_PREFIX = "notice:"

    /**
     * 插入一条索引行，**同 sha256 已存在就什么都不做**（认脸幂等：先见先立，
     * 后到的不许改写 `isolated`/`exported_at`——那是用户的选择与导出账）。
     */
    fun insertItemIfAbsent(
        sha256: String,
        name: String,
        size: Long,
        mtime: Long,
        source: String,
        isolated: Boolean,
        kind: String,
        privatePath: String,
        createdAt: Long,
    ): BaopuSql.Stmt = BaopuSql.Stmt(
        "INSERT INTO library_item " +
            "(sha256, name, size, mtime, source, exported_at, isolated, kind, private_path, created_at) " +
            "VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?) " +
            "ON CONFLICT(sha256) DO NOTHING",
        arrayOf(
            sha256, name, size.toString(), mtime.toString(), source,
            if (isolated) "1" else "0", kind, privatePath, createdAt.toString(),
        ),
    )

    /**
     * 刷新一条索引行的**描述性字段**（流水线重跑时件可能被改过名/大小）。
     *
     * ⚠️ **故意不碰 `isolated` 与 `exported_at`**：前者是搬家时用户选「搬」还是
     * 「复制」的定稿（流水线无权翻案），后者是导出账（只有导出动作能写）。
     * 谁把这两列加进 SET，`LibraryStoreTest` 的契约断言必须红。
     */
    fun updateItemMeta(
        sha256: String,
        name: String,
        size: Long,
        mtime: Long,
        source: String,
        kind: String,
        privatePath: String,
    ): BaopuSql.Stmt = BaopuSql.Stmt(
        "UPDATE library_item SET name = ?, size = ?, mtime = ?, source = ?, kind = ?, private_path = ? " +
            "WHERE sha256 = ?",
        arrayOf(name, size.toString(), mtime.toString(), source, kind, privatePath, sha256),
    )

    /** 记一次导出（`exported_at` = 导出时间戳；0 = 从没导出过）。 */
    fun markExported(sha256: String, exportedAt: Long): BaopuSql.Stmt = BaopuSql.Stmt(
        "UPDATE library_item SET exported_at = ? WHERE sha256 = ?",
        arrayOf(exportedAt.toString(), sha256),
    )

    /**
     * 隔离作废（用户选「复制」⇒ 共享区留底 ⇒ `isolated = 0`）。
     *
     * **只许单向**（1→0）：隔离一旦作废就回不来（共享区那份副本用户随时能再复制），
     * 所以这条 SQL 的 WHERE 带着 `isolated = 1`——重复执行幂等，也绝不会把
     * 已经作废的批又"修"回隔离。与 [updateItemMeta] 的分工：那个管描述性字段，
     * 这个只管隔离位；两个都不碰 `exported_at`。
     */
    fun markNotIsolated(sha256: String): BaopuSql.Stmt = BaopuSql.Stmt(
        "UPDATE library_item SET isolated = 0 WHERE sha256 = ? AND isolated = 1",
        arrayOf(sha256),
    )

    /** 总件数（顶条「超期提醒」的判据要区分「空库」与「有件没导出」）。 */
    fun totalCount(): BaopuSql.Stmt = BaopuSql.Stmt(
        "SELECT COUNT(*) FROM library_item",
        emptyArray(),
    )

    /**
     * 读全部索引行——**排序显式稳定**（`(name, sha256)`），与 `整理/_认脸索引.json`
     * 的排序口径一致（索引是缓存前缀的一部分，两次生成必须字节相同）。
     */
    fun allItems(): BaopuSql.Stmt = BaopuSql.Stmt(
        "SELECT sha256, name, size, mtime, source, exported_at, isolated, kind, private_path, created_at " +
            "FROM library_item ORDER BY name, sha256",
        emptyArray(),
    )

    fun findItem(sha256: String): BaopuSql.Stmt = BaopuSql.Stmt(
        "SELECT sha256, name, size, mtime, source, exported_at, isolated, kind, private_path, created_at " +
            "FROM library_item WHERE sha256 = ?",
        arrayOf(sha256),
    )

    /** 流水账（搬家/复制/导出/拒绝/提示）。只增不减。 */
    fun insertMoveLog(op: String, path: String, sha256: String, status: String, createdAt: Long): BaopuSql.Stmt =
        BaopuSql.Stmt(
            "INSERT INTO library_move_log (op, path, sha256, status, created_at) VALUES (?, ?, ?, ?, ?)",
            arrayOf(op, path, sha256, status, createdAt.toString()),
        )

    /** 一次性提示Flag：`notice:<key>` 行在即提示过。 */
    fun hasNotice(key: String): BaopuSql.Stmt = BaopuSql.Stmt(
        "SELECT 1 FROM library_move_log WHERE op = ? LIMIT 1",
        arrayOf(NOTICE_PREFIX + key),
    )

    /** 全部件里最近一次导出时间（0 = 从没导出过 ⇒ 顶条提醒的判据）。 */
    fun lastExportAt(): BaopuSql.Stmt = BaopuSql.Stmt(
        "SELECT COALESCE(MAX(exported_at), 0) FROM library_item",
        emptyArray(),
    )

    /** 还没导出过的件数（顶条「未导出件数」）。 */
    fun unexportedCount(): BaopuSql.Stmt = BaopuSql.Stmt(
        "SELECT COUNT(*) FROM library_item WHERE exported_at = 0",
        emptyArray(),
    )
}

/**
 * 资料库两表的读写壳（刀 2 A 段；薄壳：SQL 在 [LibrarySql]，执行走 [BaopuDb] 的单写者）。
 *
 * 读走调用方线程（WAL 下不挡写）；写走 [BaopuDb.write] 的 Future——谁想绕开单写者
 * 直接摸 writableDatabase，等于拆 `zd-baopu-db` 那道闸。
 */
internal class LibraryStore(private val db: BaopuDb) {

    /** 插入（同 sha 已在 ⇒ 保留原行，含它的 isolated/exported_at）。 */
    fun insertItemIfAbsent(item: LibraryItem): Future<Unit> = db.write { d ->
        val s = LibrarySql.insertItemIfAbsent(
            item.sha256, item.name, item.size, item.mtime, item.source,
            item.isolated, item.kind, item.privatePath, item.createdAt,
        )
        d.execSQL(s.sql, s.args)
    }

    /** 刷新描述性字段（不碰 isolated/exported_at，见 [LibrarySql.updateItemMeta]）。 */
    fun updateItemMeta(item: LibraryItem): Future<Unit> = db.write { d ->
        val s = LibrarySql.updateItemMeta(
            item.sha256, item.name, item.size, item.mtime, item.source, item.kind, item.privatePath,
        )
        d.execSQL(s.sql, s.args)
    }

    fun markExported(sha256: String, exportedAt: Long): Future<Unit> = db.write { d ->
        val s = LibrarySql.markExported(sha256, exportedAt)
        d.execSQL(s.sql, s.args)
    }

    /** 隔离作废（「复制」档；幂等，且永不复位——见 [LibrarySql.markNotIsolated]）。 */
    fun markNotIsolated(sha256: String): Future<Unit> = db.write { d ->
        val s = LibrarySql.markNotIsolated(sha256)
        d.execSQL(s.sql, s.args)
    }

    /** 总件数。 */
    fun totalCount(): Int = db.read { d ->
        d.rawQuery(LibrarySql.totalCount().sql, emptyArray()).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun all(): List<LibraryItem> = db.read { d ->
        d.rawQuery(LibrarySql.allItems().sql, emptyArray()).use { c -> itemsFrom(c) }
    }

    fun find(sha256: String): LibraryItem? = db.read { d ->
        d.rawQuery(LibrarySql.findItem(sha256).sql, arrayOf(sha256)).use { c ->
            if (c.moveToFirst()) itemFrom(c) else null
        }
    }

    fun logMove(op: String, path: String = "", sha256: String = "", status: String = "", now: Long = System.currentTimeMillis()): Future<Unit> =
        db.write { d ->
            val s = LibrarySql.insertMoveLog(op, path, sha256, status, now)
            d.execSQL(s.sql, s.args)
        }

    /** 一次性提示Flag（[key] 例：`first-move-notice`）。 */
    fun hasNotice(key: String): Boolean = db.read { d ->
        d.rawQuery(LibrarySql.hasNotice(key).sql, arrayOf(LibrarySql.NOTICE_PREFIX + key)).use { c ->
            c.moveToFirst()
        }
    }

    /** 打一次性提示Flag（幂等：已有行就不再插）。 */
    fun markNoticed(key: String): Future<Unit> = db.write { d ->
        if (!hasNotice(key)) {
            val s = LibrarySql.insertMoveLog(
                op = LibrarySql.NOTICE_PREFIX + key,
                path = "",
                sha256 = "",
                status = "shown",
                createdAt = System.currentTimeMillis(),
            )
            d.execSQL(s.sql, s.args)
        }
    }

    fun lastExportAt(): Long = db.read { d ->
        d.rawQuery(LibrarySql.lastExportAt().sql, emptyArray()).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    fun unexportedCount(): Int = db.read { d ->
        d.rawQuery(LibrarySql.unexportedCount().sql, emptyArray()).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ── Cursor → LibraryItem（壳层：android.database.Cursor 进不了 JVM 单测） ──

    private fun itemsFrom(c: android.database.Cursor): List<LibraryItem> {
        val out = ArrayList<LibraryItem>(c.count)
        while (c.moveToNext()) out += itemFrom(c)
        return out
    }

    /** 列序与 [LibrarySql.allItems] 的 SELECT 一字对齐（改 SELECT 必须同步改这里）。 */
    private fun itemFrom(c: android.database.Cursor): LibraryItem = LibraryItem(
        sha256 = c.getString(0),
        name = c.getString(1),
        size = c.getLong(2),
        mtime = c.getLong(3),
        source = c.getString(4),
        exportedAt = c.getLong(5),
        isolated = c.getInt(6) != 0,
        kind = c.getString(7),
        privatePath = c.getString(8),
        createdAt = c.getLong(9),
    )
}
