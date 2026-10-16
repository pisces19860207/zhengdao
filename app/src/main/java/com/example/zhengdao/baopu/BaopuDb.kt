// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：android.database.sqlite（SQLiteOpenHelper / SQLiteDatabase）官方 API；
// SQLite 官方文档（外键约束默认关闭、PRAGMA foreign_keys、AUTOINCREMENT 语义）。
package com.example.zhengdao.baopu

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * 抱朴数据库的**纯 SQL 层**（表结构 / 语句拼装，无 Android 依赖，可 JVM 单测）。
 *
 * 为什么单独抽：SQLiteOpenHelper 在无 Robolectric 的 JVM 环境里实例化不了
 * （android.jar 是 stub），但"表长什么样、每条语句绑定哪些参数、删会话时
 * 先删谁后删谁"这些判据必须能在单测里钉死。薄壳（[BaopuDb]）只做执行。
 */
internal object BaopuSql {

    /** 每个连接都要开（不持久化！）——级联删除的前提。SQLite 外键默认关闭是经典坑。 */
    const val PRAGMA_FK_ON = "PRAGMA foreign_keys = ON"

    // ── 建表 DDL（字段与派工单规格逐字对齐；NOT NULL / CHECK 是约束不是字段）──

    /** v1 基线五张表（刀 0 交付，本刀不许动）。 */
    val DDL_V1: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS session (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          title TEXT NOT NULL DEFAULT '',
          model TEXT NOT NULL DEFAULT '',
          provider TEXT NOT NULL DEFAULT '',
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL,
          archived INTEGER NOT NULL DEFAULT 0,
          msg_count INTEGER NOT NULL DEFAULT 0,
          preview TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_session_archived_updated ON session (archived, updated_at DESC)",
        """
        CREATE TABLE IF NOT EXISTS message (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE,
          seq INTEGER NOT NULL,
          role TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          status TEXT NOT NULL DEFAULT 'streaming'
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_message_session_seq ON message (session_id, seq)",
        """
        CREATE TABLE IF NOT EXISTS part (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          message_id INTEGER NOT NULL REFERENCES message(id) ON DELETE CASCADE,
          seq INTEGER NOT NULL,
          kind TEXT NOT NULL,
          payload TEXT NOT NULL DEFAULT '',
          created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_part_message_seq ON part (message_id, seq)",
        """
        CREATE TABLE IF NOT EXISTS run_log (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          session_id INTEGER NOT NULL REFERENCES session(id),
          step INTEGER NOT NULL DEFAULT 0,
          state TEXT NOT NULL DEFAULT '',
          model TEXT NOT NULL DEFAULT '',
          tokens_in INTEGER NOT NULL DEFAULT 0,
          tokens_out INTEGER NOT NULL DEFAULT 0,
          tokens_in_est INTEGER NOT NULL DEFAULT 0,
          tokens_out_est INTEGER NOT NULL DEFAULT 0,
          tool TEXT NOT NULL DEFAULT '',
          ok INTEGER NOT NULL DEFAULT 1,
          latency_ms INTEGER NOT NULL DEFAULT 0,
          err TEXT NOT NULL DEFAULT '',
          created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        // 规格没要求 run_log 索引，但"按会话翻账"是它的全部用途，不加等于全表扫。
        "CREATE INDEX IF NOT EXISTS idx_run_log_session ON run_log (session_id)",
        """
        CREATE TABLE IF NOT EXISTS plan_batch (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          session_id INTEGER NOT NULL REFERENCES session(id),
          kind TEXT NOT NULL CHECK (kind IN ('proposal', 'ops')),
          proposal_id INTEGER REFERENCES plan_batch(id),
          status TEXT NOT NULL DEFAULT 'pending',
          ops_json TEXT NOT NULL DEFAULT '',
          expires_at INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL,
          decided_at INTEGER,
          CHECK ((kind = 'ops') = (proposal_id IS NOT NULL))
        )
        """.trimIndent(),
        // 同上：plan_batch 的查询面是"这个会话有哪些批、什么状态"。
        "CREATE INDEX IF NOT EXISTS idx_plan_batch_session ON plan_batch (session_id)",
    )

    /**
     * v2 新增（刀 0b）：memory 表 —— 记忆的**索引**（真相在 `抱朴记忆/` 的 .md 文件里）。
     *
     * 字段按派工单第八批草案；两处自作主张（报告里说明）：
     * 1. `id TEXT PRIMARY KEY`（草案只写 "id PK"）——记忆 id 是 `<时间戳>-<slug>`
     *    字符串（[MemoryRules.newId]），从文件名可重建，reindex 后不变；
     * 2. `status` 默认 `candidate`（未确认不落盘的起点形态）。
     */
    val MEMORY_DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS memory (
          id TEXT PRIMARY KEY,
          scope TEXT NOT NULL,
          kind TEXT NOT NULL,
          title TEXT NOT NULL,
          body TEXT NOT NULL,
          tags TEXT NOT NULL DEFAULT '',
          source_session TEXT NOT NULL DEFAULT '',
          source_file TEXT NOT NULL DEFAULT '',
          status TEXT NOT NULL DEFAULT 'candidate',
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL,
          use_count INTEGER NOT NULL DEFAULT 0,
          last_used_at INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_memory_scope_kind ON memory (scope, kind)",
    )

    /** 全新安装（onCreate）跑全量：v1 五表 ＋ v2 memory。 */
    val DDL: List<String> = DDL_V1 + MEMORY_DDL

    /** session 表 v2 新增列（追加二第 3 条：中断于第 N 步——断点续跑的判据）。 */
    const val COL_SESSION_INTERRUPTED_STEP = "interrupted_step"

    /**
     * `session.interrupted_step` 的加列语句（**纯函数**：缺列才给 ALTER，有列就给空）。
     *
     * 为什么不是直接 `ALTER TABLE … ADD COLUMN`：SQLite 没有
     * `ADD COLUMN IF NOT EXISTS` ⇒ 直接执行会在重复迁移时炸。幂等性靠
     * 「先看现有列再决定」——新安装与 v1→v2 升级走**同一条**迁移路径，
     * 重跑安全（v1 库为空也不丢表：只加不删）。
     *
     * @param existingColumns `PRAGMA table_info(session)` 取到的列名清单（壳层喂进来）
     */
    fun sessionInterruptedStepStmt(existingColumns: List<String>): List<Stmt> {
        val has = existingColumns.any { it.equals(COL_SESSION_INTERRUPTED_STEP, ignoreCase = true) }
        if (has) return emptyList()
        return listOf(Stmt("ALTER TABLE session ADD COLUMN interrupted_step INTEGER", emptyArray()))
    }

    // ── 语句拼装（返回 sql + 绑定参数，顺序即调用方实参顺序）──

    fun insertSession(title: String, model: String, provider: String, now: Long): Stmt =
        Stmt(
            "INSERT INTO session (title, model, provider, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?)",
            arrayOf<String?>(title, model, provider, now.toString(), now.toString()),
        )

    fun touchSession(id: Long, updatedAt: Long, preview: String): Stmt =
        Stmt(
            "UPDATE session SET updated_at = ?, preview = ? WHERE id = ?",
            arrayOf<String?>(updatedAt.toString(), preview, id.toString()),
        )

    fun setMsgCount(id: Long, count: Int): Stmt =
        Stmt(
            "UPDATE session SET msg_count = ? WHERE id = ?",
            arrayOf<String?>(count.toString(), id.toString()),
        )

    /** 归档 / 取消归档（updated_at 一并动，保证列表排序跟着变）。 */
    fun setArchived(id: Long, archived: Boolean, updatedAt: Long): Stmt =
        Stmt(
            "UPDATE session SET archived = ?, updated_at = ? WHERE id = ?",
            arrayOf<String?>(if (archived) "1" else "0", updatedAt.toString(), id.toString()),
        )

    fun listSessions(includeArchived: Boolean): Stmt = Stmt(
        if (includeArchived) {
            "SELECT id, title, model, provider, created_at, updated_at, archived, msg_count, preview " +
                "FROM session ORDER BY updated_at DESC"
        } else {
            "SELECT id, title, model, provider, created_at, updated_at, archived, msg_count, preview " +
                "FROM session WHERE archived = 0 ORDER BY updated_at DESC"
        },
        emptyArray(),
    )

    fun insertMessage(sessionId: Long, seq: Int, role: String, createdAt: Long, status: String): Stmt =
        Stmt(
            "INSERT INTO message (session_id, seq, role, created_at, status) VALUES (?, ?, ?, ?, ?)",
            arrayOf<String?>(sessionId.toString(), seq.toString(), role, createdAt.toString(), status),
        )

    fun insertPart(messageId: Long, seq: Int, kind: String, payload: String, createdAt: Long): Stmt =
        Stmt(
            "INSERT INTO part (message_id, seq, kind, payload, created_at) VALUES (?, ?, ?, ?, ?)",
            arrayOf<String?>(messageId.toString(), seq.toString(), kind, payload, createdAt.toString()),
        )

    /**
     * run_log 插入（v2 增加两个缓存口径列，追加三）。
     *
     * [cacheHitTokens] / [cacheMissTokens] **可空 ⇒ 取不到就绑 SQL NULL（留空），
     * 不许瞎填 0**（0 = 「确定没命中」，与「没测到」是两件事，混为一谈后面全乱）。
     * 各家 usage 字段名到这两个口径的映射（③未实测，按各家公开文档）：
     * - OpenAI 系：`prompt_tokens_details.cached_tokens` 命中部分 ⇒ hit；
     * - DeepSeek 系：`prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` ⇒ hit / miss；
     * - Anthropic 系：`cache_read_input_tokens` ⇒ hit、`cache_creation_input_tokens` ⇒ miss。
     * 命中率算法见 [RunLogMetrics.cacheHitRate]。
     */
    @Suppress("LongParameterList")
    fun insertRunLog(
        sessionId: Long,
        step: Int,
        state: String,
        model: String,
        tokensIn: Int,
        tokensOut: Int,
        tokensInEst: Int,
        tokensOutEst: Int,
        tool: String,
        ok: Boolean,
        latencyMs: Long,
        err: String,
        createdAt: Long,
        cacheHitTokens: Int? = null,
        cacheMissTokens: Int? = null,
    ): Stmt = Stmt(
        "INSERT INTO run_log (session_id, step, state, model, tokens_in, tokens_out, " +
            "tokens_in_est, tokens_out_est, tool, ok, latency_ms, err, created_at, " +
            "cache_hit_tokens, cache_miss_tokens) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf<String?>(
            sessionId.toString(), step.toString(), state, model,
            tokensIn.toString(), tokensOut.toString(), tokensInEst.toString(), tokensOutEst.toString(),
            tool, if (ok) "1" else "0", latencyMs.toString(), err, createdAt.toString(),
            cacheHitTokens?.toString(), cacheMissTokens?.toString(),
        ),
    )

    /** 提案批：proposalId 必须为 null（CHECK 约束会拦 ops 形态）。 */
    fun insertProposalBatch(sessionId: Long, opsJson: String, expiresAt: Long, createdAt: Long): Stmt =
        Stmt(
            "INSERT INTO plan_batch (session_id, kind, proposal_id, ops_json, expires_at, created_at) " +
                "VALUES (?, 'proposal', NULL, ?, ?, ?)",
            arrayOf<String?>(sessionId.toString(), opsJson, expiresAt.toString(), createdAt.toString()),
        )

    /** 执行批：必须挂在已 approved 的提案批下（"approved"判据归计划层，SQL 只保证存在）。 */
    fun insertOpsBatch(
        sessionId: Long,
        proposalId: Long,
        opsJson: String,
        expiresAt: Long,
        createdAt: Long,
    ): Stmt = Stmt(
        "INSERT INTO plan_batch (session_id, kind, proposal_id, ops_json, expires_at, created_at) " +
            "VALUES (?, 'ops', ?, ?, ?, ?)",
        arrayOf<String?>(sessionId.toString(), proposalId.toString(), opsJson, expiresAt.toString(), createdAt.toString()),
    )

    fun decidePlanBatch(id: Long, status: String, decidedAt: Long): Stmt =
        Stmt(
            "UPDATE plan_batch SET status = ?, decided_at = ? WHERE id = ?",
            arrayOf<String?>(status, decidedAt.toString(), id.toString()),
        )

    /**
     * 删一个会话的**全部**语句，**顺序即执行顺序**（外键已开，顺序错就报 constraint）：
     * 1. 先删 ops 子批（plan_batch 自引用，子先父后）；
     * 2. 再删提案批；
     * 3. run_log（规格里它没有 ON DELETE CASCADE，必须显式删）；
     * 4. message（part 靠 DDL 的 ON DELETE CASCADE 级联，不重复删）；
     * 5. 最后才是 session 本体。
     */
    fun deleteSessionStatements(sessionId: Long): List<Stmt> = listOf(
        Stmt(
            "DELETE FROM plan_batch WHERE session_id = ? AND proposal_id IS NOT NULL",
            arrayOf<String?>(sessionId.toString()),
        ),
        Stmt("DELETE FROM plan_batch WHERE session_id = ?", arrayOf<String?>(sessionId.toString())),
        Stmt("DELETE FROM run_log WHERE session_id = ?", arrayOf<String?>(sessionId.toString())),
        Stmt("DELETE FROM message WHERE session_id = ?", arrayOf<String?>(sessionId.toString())),
        Stmt("DELETE FROM session WHERE id = ?", arrayOf<String?>(sessionId.toString())),
    )

    /** 一条待执行语句（sql + 绑定参数）。参数一律 String[]，与 execSQL(sql, args) 对齐。 */
    data class Stmt(val sql: String, val args: Array<String?>) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Stmt && sql == other.sql && args.contentEquals(other.args))

        override fun hashCode(): Int = 31 * sql.hashCode() + args.contentHashCode()
    }

    // ── memory 表语句（v2 · 刀 0b） ────────────────────────────────────────

    /**
     * 写入/更新一条记忆索引行（reindex 重建也走它：INSERT OR REPLACE 幂等）。
     * tags 以逗号拼接存储（读回来由 [EntryCodec] 同规则切）。
     */
    fun upsertMemory(e: MemoryEntry): Stmt = Stmt(
        "INSERT OR REPLACE INTO memory " +
            "(id, scope, kind, title, body, tags, source_session, source_file, status, " +
            "created_at, updated_at, use_count, last_used_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf<String?>(
            e.id,
            e.scope.name.lowercase(),
            e.kind.name.lowercase(),
            e.title,
            e.body,
            e.tags.joinToString(","),
            e.sourceSession,
            e.sourceFile,
            e.status.name.lowercase(),
            e.createdAt.toString(),
            e.updatedAt.toString(),
            e.useCount.toString(),
            e.lastUsedAt.toString(),
        ),
    )

    /** 删一条记忆索引行（文件由 [MemoryStore.delete] 删；这里是索引同步）。 */
    fun deleteMemory(id: String): Stmt =
        Stmt("DELETE FROM memory WHERE id = ?", arrayOf<String?>(id))

    /** 清空记忆索引（reindex 第一步：先清后建，索引永远跟着文件走）。 */
    fun clearMemories(): Stmt = Stmt("DELETE FROM memory", emptyArray())

    /** run_log v2 新增的两个缓存口径列（追加三：可空，取不到留空不许填 0）。 */
    val RUN_LOG_CACHE_COLUMNS = listOf("cache_hit_tokens", "cache_miss_tokens")

    /**
     * run_log 缓存列的加列语句（**纯函数**：缺哪列给哪条 ALTER，幂等）。
     * 与 [sessionInterruptedStepStmt] 同一房型——SQLite 没有 ADD COLUMN IF NOT EXISTS。
     */
    fun runLogCacheColumnsStmt(existingColumns: List<String>): List<Stmt> =
        RUN_LOG_CACHE_COLUMNS
            .filterNot { col -> existingColumns.any { it.equals(col, ignoreCase = true) } }
            .map { col -> Stmt("ALTER TABLE run_log ADD COLUMN " + col + " INTEGER", emptyArray()) }

    /** 读全部索引行（按创建时间序——与 [MemoryStore.loadAll] 同序，便于对拍）。 */
    fun allMemories(): Stmt = Stmt(
        "SELECT id, scope, kind, title, body, tags, source_session, source_file, status, " +
            "created_at, updated_at, use_count, last_used_at FROM memory ORDER BY created_at",
        emptyArray(),
    )

    /** 用一次记忆（use_count + 1，last_used_at 刷新——「哪条还真被用上」的账）。 */
    fun touchMemoryUse(id: String, now: Long): Stmt = Stmt(
        "UPDATE memory SET use_count = use_count + 1, last_used_at = ? WHERE id = ?",
        arrayOf<String?>(now.toString(), id),
    )
}

/**
 * 抱朴数据库（刀 0 骨架 · 刀 0b v2 迁移）。
 *
 * ## 三个不可动摇的点
 * 1. **单写者线程**（[writer]，`zd-baopu-db`，daemon）：一切写走 [write] 的 Future，
 *    读走调用方线程（WAL 下读不挡写、写不挡读）。谁在主线程直接摸 writableDatabase
 *    就是在拆这道闸——要写就从 [write] 过。
 * 2. **外键每个连接都开**（[BaopuSql.PRAGMA_FK_ON] 放 onConfigure）：PRAGMA 不持久化，
 *    放 onCreate 只对建库那一个连接生效，之后的连接全是关的——级联删除会静默失效。
 *    这是 SQLite 最经典的坑之一。
 * 3. **迁移必须真迁移**：v1→v2 建 memory 表 ＋ 给 session 加 `interrupted_step` 列，
 *    全部幂等（`IF NOT EXISTS` / 先看列再加）；v1 库为空也不丢表。**没有路径的
 *    迁移必须炸**——静默按新 schema 跑在旧库上比崩掉糟糕得多。
 *
 * ## 文件落点（刀 0b 按派工单 §4.3 对齐设计）
 * 落 `filesDir/baopu/baopu.db`（设计 §9 原文）：显式建父目录后传**绝对路径**。
 * 路径计算抽成纯函数 [dbPathUnder]（可 JVM 单测）。**真机行为标③留给刀 1 复验** ——
 * SQLiteOpenHelper 对绝对路径 name 的解析依赖 `Context.getDatabasePath` 的实现
 * （本机无 Android 运行时，无法实测）；失败形态是首开库时抛异常（响、可定位），
 * 不是静默落到别处（那才是不可接受的双真相）。
 */
class BaopuDb(context: Context) : SQLiteOpenHelper(
    context,
    dbPathUnder(context.filesDir).absolutePath,
    null,
    VERSION,
) {

    /** 单写者（daemon）。 */
    val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zd-baopu-db").apply { isDaemon = true }
    }

    init {
        // WAL：读不挡写、写不挡读（UI 读账本时不会被写卡住）。
        setWriteAheadLoggingEnabled(true)
        // 绝对路径名不走 getDatabaseDir 的自动父目录创建 ⇒ 显式建（派工单 §4.3）。
        runCatching { dbPathUnder(context.filesDir).parentFile?.mkdirs() }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL(BaopuSql.PRAGMA_FK_ON)
    }

    override fun onCreate(db: SQLiteDatabase) {
        BaopuSql.DDL_V1.forEach { db.execSQL(it) }
        applyV2Migration(db)
    }

    /**
     * v1 → v2 真迁移（刀 0b）。**新安装与升级走同一条路径**（onCreate 里也调它）——
     * 两处入口同一份迁移语句，不会出现「新装的有列、升级的没列」这种分叉。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (newVersion > VERSION) {
            throw IllegalStateException(
                "BaopuDb：没有 v$newVersion 的迁移路径。" +
                    "VERSION 被提到 ${VERSION + 1} 以上却没写迁移——先写 onUpgrade 再升版本号。"
            )
        }
        if (oldVersion < 1) {
            throw IllegalStateException(
                "BaopuDb：onUpgrade 收到 oldVersion=$oldVersion（< 1）——SQLiteOpenHelper " +
                    "不会为不存在的库调升级，走到这说明库文件被人为改坏。"
            )
        }
        if (oldVersion < 2) applyV2Migration(db)
    }

    /** v2 迁移体：memory 表 ＋ 索引（IF NOT EXISTS）＋ session 加列（看列再动）。 */
    private fun applyV2Migration(db: SQLiteDatabase) {
        BaopuSql.MEMORY_DDL.forEach { db.execSQL(it) }
        val sessionCols = tableColumns(db, "session")
        BaopuSql.sessionInterruptedStepStmt(sessionCols).forEach { stmt ->
            if (stmt.args.isEmpty()) db.execSQL(stmt.sql) else db.execSQL(stmt.sql, stmt.args)
        }
        val runLogCols = tableColumns(db, "run_log")
        BaopuSql.runLogCacheColumnsStmt(runLogCols).forEach { stmt ->
            if (stmt.args.isEmpty()) db.execSQL(stmt.sql) else db.execSQL(stmt.sql, stmt.args)
        }
    }

    /** `PRAGMA table_info(<table>)` 的列名（薄壳：SQLiteDatabase 进不了 JVM 单测）。 */
    private fun tableColumns(db: SQLiteDatabase, table: String): List<String> = runCatching {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val idx = c.getColumnIndex("name")
            if (idx < 0) emptyList() else (0 until c.count).map { c.moveToPosition(it); c.getString(idx) }
        }
    }.getOrDefault(emptyList())

    /** 写：提交到单写者线程，返回 Future（调用方 `.get()` 等结果，异常经 Future 抛出）。 */
    fun <T> write(block: (SQLiteDatabase) -> T): Future<T> =
        writer.submit<T> { block(writableDatabase) }

    /** 读：调用方线程直接读（WAL 下与写并发安全）。 */
    fun <T> read(block: (SQLiteDatabase) -> T): T = block(readableDatabase)

    /** 进程退出时调用（停写线程；库本身交给系统）。 */
    fun shutdown() {
        writer.shutdown()
    }

    companion object {
        const val DB_NAME = "baopu.db"

        /** v2（刀 0b）：加 memory 表 ＋ session.interrupted_step。v1 = 刀 0 五表。 */
        const val VERSION = 2

        /** 库目录名（App 私有；设计 §9 定版落点）。 */
        const val DIR_BAOPU = "baopu"

        /**
         * 库文件路径（**纯函数**：只拼目录，可 JVM 单测）。
         * 真机解析行为（SQLiteOpenHelper 吃绝对路径 name）标③，见类 KDoc。
         */
        fun dbPathUnder(filesDir: File): File = File(File(filesDir, DIR_BAOPU), DB_NAME)
    }
}

/**
 * run_log 缓存口径的纯函数（追加三 · 2026-10-16）。
 *
 * 「怎么从 run_log 看出本次会话的缓存命中率」的算法落在这里而不是只写在报告里 ——
 * 报告会过期，函数不会。
 */
internal object RunLogMetrics {

    /**
     * 缓存命中率 = 命中 ÷（命中 ＋ 未命中）。
     *
     * - 两边都拿不到（null）⇒ **返回 null**（没测到就不编数，与「0% 命中」区分开）；
     * - 一边有一边没有 ⇒ 按有的那边算（另一个当 0 是合理的：没读数就是没读到量）；
     * - 都有但都为 0 ⇒ 0.0（真的零命中，不是没测到）。
     */
    fun cacheHitRate(hitTokens: Int?, missTokens: Int?): Double? {
        if (hitTokens == null && missTokens == null) return null
        val hit = hitTokens ?: 0
        val miss = missTokens ?: 0
        val total = hit + miss
        return if (total <= 0) null else hit.toDouble() / total
    }
}
