// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十七批（2026-10-30）：**全盘操作入账，30 天可回放**。
//
// 为什么要有这本账：287 把工作区从权限边界降级成"默认落点"，能碰的地方一下变成全盘。
// 安全不再靠开关，靠三条默认行为；其中"可审计"这一条必须是**事实**而不是口号——
// 每一笔文件操作（读/写/删/移/列/搜）落一行：时间、会话、工具、路径、用途。
// 30 天内的账可回放（设置页「文件操作记录」卡），30 天外自动清（[FileOpSql.PRUNE]）。
//
// 与 v18 的 `workspace_audit` 的关系：那张表记的是"越界被问/被拒"（156/157 的授权模型），
// 287 起越界不再需要授权，那张表**不再产生新行**（老行留着，不迁移、不改写）。
// 本文件是它的接任者：口径从"越界事件"变成"全部文件操作"。
package com.example.zhengdao.baopu

import org.json.JSONObject
import java.util.Collections

/** 一笔文件操作的种类（短标签可 grep，`label` 是给人看的一个字）。 */
internal enum class FileOpKind(val tag: String, val label: String) {
    READ("read", "读"),
    LIST("list", "列"),
    SEARCH("search", "搜"),
    WRITE("write", "写"),
    DELETE("delete", "删"),
    MOVE("move", "移"),

    /** 288：复制（源保留，与写新文件、搬动都不同——回放时看得出是留了底的）。 */
    COPY("copy", "拷"),
}

/** 账上一行（时间/会话/工具/种类/路径/用途——派工单 287 点名的"路径＋用途"）。 */
internal data class FileOpEntry(
    val at: Long,
    val sessionId: Long,
    val tool: String,
    val kind: FileOpKind,
    val path: String,
    val purpose: String,
)

/** 存取口（内存版与 SQLite 版同一语义；拆接口只为 JVM 直测不碰库）。 */
internal interface FileOpStore {

    /** 记一笔（顺手按 30 天清一次；清不掉不影响这笔入账）。 */
    fun record(e: FileOpEntry)

    /** 最近 [limit] 笔（时间倒序）。 */
    fun recent(limit: Int): List<FileOpEntry>

    /** 账上现有多少行（设置页读数/测试读数）。 */
    fun count(): Int
}

/** SQL 常量（与 `WorkspaceGrantSql` 同一手法：DDL 住本文件，迁移体只调一次）。 */
internal object FileOpSql {

    const val TABLE = "file_op"

    /** 回放窗口：30 天（派工单 287 判据⑤「每笔读写入账，30 天可回放」）。 */
    const val RETENTION_DAYS = 30

    val DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS file_op(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          at INTEGER NOT NULL,
          session_id INTEGER NOT NULL,
          tool TEXT NOT NULL,
          kind TEXT NOT NULL,
          path TEXT NOT NULL,
          purpose TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_file_op_at ON file_op (at DESC)",
    )

    const val INSERT =
        "INSERT INTO file_op(at, session_id, tool, kind, path, purpose) VALUES(?, ?, ?, ?, ?, ?)"

    /** 只留 [RETENTION_DAYS] 天内（`?` = 截止时刻）。 */
    const val PRUNE = "DELETE FROM file_op WHERE at < ?"

    const val SELECT_RECENT =
        "SELECT at, session_id, tool, kind, path, purpose FROM file_op ORDER BY at DESC LIMIT ?"

    const val COUNT = "SELECT COUNT(*) FROM file_op"
}

/** 未接线环境（纯 JVM 直测）用的内存账本：语义与库版一致，只是活不过进程。 */
internal class InMemoryFileOpStore : FileOpStore {

    private val rows: MutableList<FileOpEntry> = Collections.synchronizedList(mutableListOf())

    override fun record(e: FileOpEntry) {
        rows.add(e)
    }

    override fun recent(limit: Int): List<FileOpEntry> =
        synchronized(rows) { rows.sortedByDescending { it.at }.take(limit.coerceAtLeast(1)) }

    override fun count(): Int = rows.size
}

/**
 * 生产账本：SQLite（`baopu.db` v20 的 `file_op` 表）。
 *
 * 异常一律吞成「没记上」：账是留痕不是闸门——记不上不许把动作拦下来，
 * 也不许假装记上了（设置页读不到就是空列表）。
 */
internal class SessionFileOpStore(private val db: BaopuDb) : FileOpStore {

    override fun record(e: FileOpEntry) {
        runCatching {
            db.write { d ->
                d.execSQL(
                    FileOpSql.INSERT,
                    arrayOf<Any?>(e.at, e.sessionId, e.tool, e.kind.tag, e.path, e.purpose),
                )
                d.execSQL(FileOpSql.PRUNE, arrayOf<Any?>(cutoff(e.at)))
            }.get()
        }
    }

    override fun recent(limit: Int): List<FileOpEntry> = runCatching {
        db.read { d ->
            d.rawQuery(FileOpSql.SELECT_RECENT, arrayOf(limit.coerceAtLeast(1).toString())).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            FileOpEntry(
                                at = c.getLong(0),
                                sessionId = c.getLong(1),
                                tool = c.getString(2) ?: "",
                                kind = FileOpRules.kindOfTag(c.getString(3)),
                                path = c.getString(4) ?: "",
                                purpose = c.getString(5) ?: "",
                            ),
                        )
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    override fun count(): Int = runCatching {
        db.read { d -> d.rawQuery(FileOpSql.COUNT, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } }
    }.getOrDefault(0)

    private fun cutoff(now: Long): Long = now - FileOpSql.RETENTION_DAYS * 24L * 3600_000L
}

/** 纯规则（不碰 Android/库，JVM 直测）：工具 → 种类、参数 → 用途、账行 → 人话。 */
internal object FileOpRules {

    /** 工具名 → 操作种类；`null` = 这个工具不碰文件（不记账）。 */
    fun kindOf(tool: String): FileOpKind? = when (tool) {
        "read_file", "file_info", "book_outline", "sheet_read", "convert_encoding" -> FileOpKind.READ
        "list_dir" -> FileOpKind.LIST
        "find_files", "search_in_files" -> FileOpKind.SEARCH
        "write_file", "edit_file", "archive_pack", "archive_extract", "image_ops" -> FileOpKind.WRITE
        "delete_path" -> FileOpKind.DELETE
        "move_path" -> FileOpKind.MOVE
        // 288：复制/批量/整理（预览是只读，按 READ 入账——「每笔读写入账」没有黑洞）
        "copy_path", "bulk_copy" -> FileOpKind.COPY
        "bulk_move", "organize" -> FileOpKind.MOVE
        "organize_preview" -> FileOpKind.READ
        else -> null
    }

    /** 存进库的 tag → 枚举（认不出的按读——保守，不改账行数量）。 */
    fun kindOfTag(tag: String?): FileOpKind =
        FileOpKind.entries.firstOrNull { it.tag == tag } ?: FileOpKind.READ

    /**
     * 用途（"为什么碰这个文件"）——**一句人话**，不是参数 dump。
     *
     * 为什么从参数里摘：引擎没有"用户原话"这一列可落（那是会话历史的事），
     * 而「按名找『斗罗』」「删文件」「写 42 行」已经足够回放时看懂这一笔在干什么。
     */
    fun purposeOf(tool: String, args: JSONObject): String = when (tool) {
        "read_file" -> "读文件"
        "file_info" -> "看文件信息"
        "book_outline" -> "看目录结构"
        "sheet_read" -> "读表格"
        "convert_encoding" -> "转编码（先备份）"
        "list_dir" -> "列目录"
        "find_files" -> "按名找「${brief(args.optString("pattern"))}」"
        "search_in_files" -> "搜「${brief(args.optString("query"))}」"
        "write_file" -> if (args.optBoolean("overwrite", false)) "覆盖写" else "新建写"
        "edit_file" -> "按行改（${args.optString("op").ifBlank { "?" }}）"
        "delete_path" ->
            if (args.optBoolean("empty_trash", false)) "清空回收站"
            else if (args.optBoolean("permanent", false)) "彻底删（不可恢复）"
            else "删进回收站（可恢复）"
        "move_path" -> "移动/改名"
        "copy_path" -> "复制（源保留）"
        "bulk_move" -> "批量搬动（${args.optString("dir").ifBlank { "?" }}）"
        "bulk_copy" -> "批量复制（${args.optString("dir").ifBlank { "?" }}）"
        "organize" ->
            if (args.optString("undo_receipt").isNotBlank()) "按回执退回"
            else "整理归档（${args.optString("dir").ifBlank { "?" }}）"
        "organize_preview" -> "整理预览（未动文件）"
        "archive_pack" -> "打包"
        "archive_extract" -> "解包"
        "image_ops" -> "图像处理"
        else -> tool
    }

    /** 一行人话（RunLog 与设置页共用）。 */
    fun lineOf(e: FileOpEntry): String {
        val who = if (e.sessionId > 0) "#${e.sessionId}" else "未知"
        return "[文件] 会话 $who ${e.kind.label}：${e.path}（${e.purpose}，${e.tool}）"
    }

    private fun brief(raw: String, max: Int = 20): String {
        val one = raw.replace('\n', ' ').trim()
        return if (one.length <= max) one else one.take(max) + "…"
    }
}

/**
 * 进程级账本（与 `WorkspaceGrants` 同款房型：进程级单例、按 sessionId 分账）。
 *
 * 为什么进程级：工具层的记账发生在**执行线程**上，而"这一轮是哪个会话"只有引擎知道
 * （引擎每轮入口 `enterSession` 喂一次，与 `WorkspaceGrants` 的历史做法一致）。
 */
internal object FileOpLog {

    @Volatile
    private var store: FileOpStore = InMemoryFileOpStore()

    @Volatile
    private var sessionId: Long = -1L

    /** 装账本（生产装配层调用；顺手清空内存里攒的旧账——换了库，旧账不再可信）。 */
    fun install(s: FileOpStore) {
        store = s
    }

    /** 切会话身份（引擎每轮入口调）。 */
    fun enterSession(id: Long) {
        sessionId = id
    }

    /** 当前会话 id（未知 = -1）。 */
    fun session(): Long = sessionId

    /** 记一笔并回一行人话（调用方写 RunLog；库写失败也返回同一行——账是留痕不是闸门）。 */
    fun record(tool: String, kind: FileOpKind, path: String, purpose: String, now: Long = System.currentTimeMillis()): FileOpEntry {
        val e = FileOpEntry(now, sessionId, tool, kind, path, purpose)
        runCatching { store.record(e) }
        return e
    }

    /** 最近 [limit] 笔（设置页/测试读数）。 */
    fun recent(limit: Int = 50): List<FileOpEntry> = runCatching { store.recent(limit) }.getOrDefault(emptyList())

    /** 账上现有多少行。 */
    fun count(): Int = runCatching { store.count() }.getOrDefault(0)

    /** 直测收尾（生产不调）。 */
    fun resetForTest() {
        install(InMemoryFileOpStore())
        sessionId = -1L
    }
}
