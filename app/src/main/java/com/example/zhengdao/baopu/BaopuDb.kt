// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：android.database.sqlite（SQLiteOpenHelper / SQLiteDatabase）官方 API；
// SQLite 官方文档（外键约束默认关闭、PRAGMA foreign_keys、AUTOINCREMENT 语义）。
package com.example.zhengdao.baopu

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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

    val DDL: List<String> = listOf(
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

    // ── 语句拼装（返回 sql + 绑定参数，顺序即调用方实参顺序）──

    fun insertSession(title: String, model: String, provider: String, now: Long): Stmt =
        Stmt(
            "INSERT INTO session (title, model, provider, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?)",
            arrayOf(title, model, provider, now.toString(), now.toString()),
        )

    fun touchSession(id: Long, updatedAt: Long, preview: String): Stmt =
        Stmt(
            "UPDATE session SET updated_at = ?, preview = ? WHERE id = ?",
            arrayOf(updatedAt.toString(), preview, id.toString()),
        )

    fun setMsgCount(id: Long, count: Int): Stmt =
        Stmt(
            "UPDATE session SET msg_count = ? WHERE id = ?",
            arrayOf(count.toString(), id.toString()),
        )

    /** 归档 / 取消归档（updated_at 一并动，保证列表排序跟着变）。 */
    fun setArchived(id: Long, archived: Boolean, updatedAt: Long): Stmt =
        Stmt(
            "UPDATE session SET archived = ?, updated_at = ? WHERE id = ?",
            arrayOf(if (archived) "1" else "0", updatedAt.toString(), id.toString()),
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
            arrayOf(sessionId.toString(), seq.toString(), role, createdAt.toString(), status),
        )

    fun insertPart(messageId: Long, seq: Int, kind: String, payload: String, createdAt: Long): Stmt =
        Stmt(
            "INSERT INTO part (message_id, seq, kind, payload, created_at) VALUES (?, ?, ?, ?, ?)",
            arrayOf(messageId.toString(), seq.toString(), kind, payload, createdAt.toString()),
        )

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
    ): Stmt = Stmt(
        "INSERT INTO run_log (session_id, step, state, model, tokens_in, tokens_out, " +
            "tokens_in_est, tokens_out_est, tool, ok, latency_ms, err, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf(
            sessionId.toString(), step.toString(), state, model,
            tokensIn.toString(), tokensOut.toString(), tokensInEst.toString(), tokensOutEst.toString(),
            tool, if (ok) "1" else "0", latencyMs.toString(), err, createdAt.toString(),
        ),
    )

    /** 提案批：proposalId 必须为 null（CHECK 约束会拦 ops 形态）。 */
    fun insertProposalBatch(sessionId: Long, opsJson: String, expiresAt: Long, createdAt: Long): Stmt =
        Stmt(
            "INSERT INTO plan_batch (session_id, kind, proposal_id, ops_json, expires_at, created_at) " +
                "VALUES (?, 'proposal', NULL, ?, ?, ?)",
            arrayOf(sessionId.toString(), opsJson, expiresAt.toString(), createdAt.toString()),
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
        arrayOf(sessionId.toString(), proposalId.toString(), opsJson, expiresAt.toString(), createdAt.toString()),
    )

    fun decidePlanBatch(id: Long, status: String, decidedAt: Long): Stmt =
        Stmt(
            "UPDATE plan_batch SET status = ?, decided_at = ? WHERE id = ?",
            arrayOf(status, decidedAt.toString(), id.toString()),
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
            arrayOf(sessionId.toString()),
        ),
        Stmt("DELETE FROM plan_batch WHERE session_id = ?", arrayOf(sessionId.toString())),
        Stmt("DELETE FROM run_log WHERE session_id = ?", arrayOf(sessionId.toString())),
        Stmt("DELETE FROM message WHERE session_id = ?", arrayOf(sessionId.toString())),
        Stmt("DELETE FROM session WHERE id = ?", arrayOf(sessionId.toString())),
    )

    /** 一条待执行语句（sql + 绑定参数）。参数一律 String[]，与 execSQL(sql, args) 对齐。 */
    data class Stmt(val sql: String, val args: Array<String>) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Stmt && sql == other.sql && args.contentEquals(other.args))

        override fun hashCode(): Int = 31 * sql.hashCode() + args.contentHashCode()
    }
}

/**
 * 抱朴数据库（刀 0 · 2026-10-16）：App 第一个 SQLite 库。
 *
 * ## 三个不可动摇的点
 * 1. **单写者线程**（[writer]，`zd-baopu-db`，daemon）：一切写走 [write] 的 Future，
 *    读走调用方线程（WAL 下读不挡写、写不挡读）。谁在主线程直接摸 writableDatabase
 *    就是在拆这道闸——要写就从 [write] 过。
 * 2. **外键每个连接都开**（[BaopuSql.PRAGMA_FK_ON] 放 onConfigure）：PRAGMA 不持久化，
 *    放 onCreate 只对建库那一个连接生效，之后的连接全是关的——级联删除会静默失效。
 *    这是 SQLite 最经典的坑之一。
 * 3. **onUpgrade 显式实现并抛错**：v1 是首个版本，永远走不到升级；真走到了说明
 *    有人改了 VERSION 却没写迁移——那时候必须炸，不能静默按新 schema 跑在旧库上。
 *
 * ## 文件落点（与派工单的一处偏差，已报备）
 * 派工单写 `filesDir/baopu/baopu.db`；实际落系统标准 `databases/baopu.db`。
 * 理由：SQLiteOpenHelper 对 name 里带子路径时，父目录创建行为在各 Android 版本上
 * 不一致（未实测，证据等级③），不把地基押在未验证行为上。两处都是 App 私有目录、
 * 都随卸载删除，"不落工作区"的要求不变。
 */
class BaopuDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, VERSION) {

    /** 单写者（daemon）。 */
    val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zd-baopu-db").apply { isDaemon = true }
    }

    init {
        // WAL：读不挡写、写不挡读（UI 读账本时不会被写卡住）。
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL(BaopuSql.PRAGMA_FK_ON)
    }

    override fun onCreate(db: SQLiteDatabase) {
        BaopuSql.DDL.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException(
            "BaopuDb：从 v$oldVersion 升到 v$newVersion 没有迁移路径。" +
                "v1 是首个版本（本刀之前无任何已发布 schema）——走到这里说明 VERSION 被改了却没写迁移。" +
                "要升 version，先写 onUpgrade 里的数据搬运，再让这里放行。"
        )
    }

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
        const val VERSION = 1
    }
}
