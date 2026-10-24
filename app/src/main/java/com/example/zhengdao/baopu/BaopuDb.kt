// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据的公开接口：android.database.sqlite（SQLiteOpenHelper / SQLiteDatabase）官方 API；
// SQLite 官方文档（外键约束默认关闭、PRAGMA foreign_keys、AUTOINCREMENT 语义）。
package com.example.zhengdao.baopu

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.zhengdao.baopu.agent.CompressionRunner
import com.example.zhengdao.baopu.agent.CompressionStore
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

    /**
     * v14 新增（第八十九批 · 记忆质量环）：memory 表的质量四列。
     *
     * 为什么是**加列**而不是改 `MEMORY_DDL`：那张表已经跟着 v2 发出去了，改它的字面量
     * 等于伪造历史（`BaopuDbTest` 的 v2 判据就是历史）。新装与升级都走
     * `applyV14Migration` 的同一条加列路径 ⇒ 不会出现「新装的有列、升级的没列」。
     *
     * 为什么 `score` 默认 100：派工单 §1「老数据有默认分数」——升级那天老记忆一条都不该
     * 因为「没算过分」被判成低质量而沉底。**默认满分 = 暂不扣分**，不是「已经算过」。
     * 列名不叫 `quality_score` 而叫 `score`：与 `use_count`/`last_used_at` 一样是短账列，
     * 且 `memory` 表里只有这一个分数，不会歧义（.md 侧仍写 `quality_score`，
     * 人读的文件名要自解释，索引列短就行——两份口径由 [BaopuSql.upsertMemory] 一处对齐）。
     */
    val MEMORY_QUALITY_COLUMNS: List<Pair<String, String>> = listOf(
        "score" to "INTEGER NOT NULL DEFAULT " + MEMORY_QUALITY_DEFAULT_SCORE,
        "quality_flags" to "TEXT NOT NULL DEFAULT ''",
        "merged_from" to "TEXT NOT NULL DEFAULT ''",
        "scored_at" to "INTEGER NOT NULL DEFAULT 0",
    )

    /** 老库默认分（与 [MemoryQuality.DEFAULT_SCORE] 同值，由 v14 迁移测试钉住不漂移）。 */
    const val MEMORY_QUALITY_DEFAULT_SCORE = 100

    /**
     * v3 新增（刀 2）：资料库两张表 —— `library_item`（认脸索引的**库内镜像**，
     * 真相是共享区 `整理/_认脸索引.json`）与 `library_move_log`（搬家/导出/一次性提示的流水账）。
     *
     * 两处按派工单 §4 A 段口径定的形：
     * 1. `library_item.sha256 TEXT NOT NULL UNIQUE` —— 认脸幂等靠它：同内容同 sha
     *    只一行（`ON CONFLICT(sha256)`）；`isolated` 记「这批有没有真隔离」（选复制 = 0）；
     * 2. `library_move_log` **无外键** —— 它是流水账（op/path/sha256/status/created_at），
     *    行数只增不减，不随任何表级联删；一次性提示（「首次搬原件提示一次」）也寄存在
     *    这里（op = `notice:<key>`，行在即提示过）。
     */
    val LIBRARY_DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS library_item (
          sha256 TEXT NOT NULL UNIQUE,
          name TEXT NOT NULL,
          size INTEGER NOT NULL,
          mtime INTEGER NOT NULL,
          source TEXT NOT NULL DEFAULT 'deliver',
          exported_at INTEGER NOT NULL DEFAULT 0,
          isolated INTEGER NOT NULL DEFAULT 1,
          kind TEXT NOT NULL DEFAULT 'text',
          private_path TEXT NOT NULL DEFAULT '',
          created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_library_item_isolated ON library_item (isolated)",
        """
        CREATE TABLE IF NOT EXISTS library_move_log (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          op TEXT NOT NULL,
          path TEXT NOT NULL DEFAULT '',
          sha256 TEXT NOT NULL DEFAULT '',
          status TEXT NOT NULL DEFAULT '',
          created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_library_move_log_sha ON library_move_log (sha256)",
    )

    /**
     * v3 新增（刀 2 追加包）：`chapter_index` —— 章节级抽取流水线的落库表。
     *
     * 幂等判据是**三元组** `UNIQUE(book, chapter_no, sha256)`：同一本书的同一章、
     * 章文本没变（sha 同）⇒ `INSERT OR IGNORE` 不产生重复行（追加包判据②
     * 「ChapterDigest 同章重复跑不产生重复行」）；章文本变了 ⇒ 新行，
     * 取「最新」时按 `MAX(id)`（见 BaopuSql.latestChapters）——改过的章不会被
     * 旧摘要挡住。
     */
    val CHAPTER_INDEX_DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS chapter_index (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          book TEXT NOT NULL,
          chapter_no INTEGER NOT NULL,
          title TEXT NOT NULL,
          sha256 TEXT NOT NULL,
          digest TEXT NOT NULL,
          chars INTEGER NOT NULL,
          created_at INTEGER NOT NULL,
          UNIQUE(book, chapter_no, sha256)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_chapter_index_book ON chapter_index (book, chapter_no)",
    )

    /** 全新安装（onCreate）跑全量：v1 五表 ＋ v2 memory ＋ v3 资料库两表 + 章节索引表。 */
    val DDL: List<String> = DDL_V1 + MEMORY_DDL + LIBRARY_DDL + CHAPTER_INDEX_DDL

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

    // ── session pending 三列（v5 · 刀 3 A 段：确认制跨进程必须能续）─────────

    /**
     * session 表 v5 新增三列（派工单 §3.3）：`pending_tool` / `pending_args` / `pending_at`
     * ——「哪张确认卡还在等」的**跨进程真相**（内存态会被杀丢，T3 判据靠它）。
     *
     * 为什么 nullable 而不是 NOT NULL DEFAULT ''：没 pending 是**正常大多数时刻**，
     * 空串要跟「有 pending 但工具名为空」区分开（后者不可能出现，但 NULL 一眼可判）。
     */
    val SESSION_PENDING_COLUMNS: List<Pair<String, String>> = listOf(
        "pending_tool" to "TEXT",
        "pending_args" to "TEXT",
        "pending_at" to "INTEGER",
    )

    /**
     * session pending 三列的加列语句（**纯函数**：缺哪列给哪条 ALTER，幂等）。
     * 与 [sessionInterruptedStepStmt] 同一房型——SQLite 没有 ADD COLUMN IF NOT EXISTS。
     */
    fun sessionPendingColumnsStmt(existingColumns: List<String>): List<Stmt> =
        SESSION_PENDING_COLUMNS
            .filterNot { (col, _) -> existingColumns.any { it.equals(col, ignoreCase = true) } }
            .map { (col, type) -> Stmt("ALTER TABLE session ADD COLUMN $col $type", emptyArray()) }

    /** 进 AWAIT_CONFIRM 时写 pending 三列（与 run_log 的 AwaitConfirm 行同事务——引擎侧拼）。 */
    fun savePending(sessionId: Long, tool: String, argsJson: String, at: Long): Stmt = Stmt(
        "UPDATE session SET pending_tool = ?, pending_args = ?, pending_at = ? WHERE id = ?",
        arrayOf<String?>(tool, argsJson, at.toString(), sessionId.toString()),
    )

    /** 批准/拒绝/超时后清空（绑 SQL NULL，不是空串——「没有」与「空工具名」两回事）。 */
    fun clearPending(sessionId: Long): Stmt = Stmt(
        "UPDATE session SET pending_tool = NULL, pending_args = NULL, pending_at = NULL WHERE id = ?",
        arrayOf<String?>(sessionId.toString()),
    )

    /** 读 pending（控制器重建确认卡 / 引擎恢复执行；工具名为空或 NULL ⇒ 没有 pending）。 */
    fun loadPending(sessionId: Long): Stmt = Stmt(
        "SELECT pending_tool, pending_args, pending_at FROM session WHERE id = ?",
        arrayOf<String?>(sessionId.toString()),
    )

    // ── 会话封卷两列（第十九批 §2.4：closed_at / refined_at）──────────────
    //
    // 现状缺口（调研稿 Q5-B）：代码里没有「会话级收敛信号」，技能提炼只能靠
    // 「每轮结束」猜——太吵。定稿口径：**点「新会话」＝旧会话封卷**，先写
    // closed_at，提炼成功才写 refined_at；进程被杀没跑成 ⇒ 下次启动按
    // 「closed_at 有、refined_at 没有」自检补算，同一会话永不重复弹卡。

    /** session 表 v7 新增两列（封卷信号；NULL = 未封卷 / 未提炼，一眼可判）。 */
    val SESSION_CLOSE_COLUMNS: List<Pair<String, String>> = listOf(
        "closed_at" to "INTEGER",
        "refined_at" to "INTEGER",
    )

    /** 封卷两列的加列语句（**纯函数**：缺哪列给哪条 ALTER，幂等；同 [sessionPendingColumnsStmt] 房型）。 */
    fun sessionCloseColumnsStmt(existingColumns: List<String>): List<Stmt> =
        SESSION_CLOSE_COLUMNS
            .filterNot { (col, _) -> existingColumns.any { it.equals(col, ignoreCase = true) } }
            .map { (col, type) -> Stmt("ALTER TABLE session ADD COLUMN $col $type", emptyArray()) }

    /** 封卷：写 closed_at（幂等——已封过的不改时间；WHERE closed_at IS NULL）。 */
    fun markSessionClosed(sessionId: Long, at: Long): Stmt = Stmt(
        "UPDATE session SET closed_at = ? WHERE id = ? AND closed_at IS NULL",
        arrayOf<String?>(at.toString(), sessionId.toString()),
    )

    /** 提炼成功：写 refined_at（补算幂等的锚——写过的不再出现在自检清单里）。 */
    fun markSessionRefined(sessionId: Long, at: Long): Stmt = Stmt(
        "UPDATE session SET refined_at = ? WHERE id = ? AND refined_at IS NULL",
        arrayOf<String?>(at.toString(), sessionId.toString()),
    )

    /** 启动自检：封了卷但没提炼成功的会话（同一会话只补一次的关键筛选）。 */
    fun listUnclosedRefinements(): Stmt = Stmt(
        "SELECT id FROM session WHERE closed_at IS NOT NULL AND refined_at IS NULL ORDER BY id ASC",
        emptyArray(),
    )

    // ── 会话记住自己的工作区（第二十批 §2.3：v8 加一列）──────────────────
    //
    // 「旧会话有明确的地方」的落库形态：新会话创建时把当时解析出的工作区绝对路径
    // 写进 `session.workspace`。**老会话这一列为 NULL**——NULL 是「当时没记」这个
    // 历史事实，不许用当前工作区回填（那是编造历史）；列表里呈现为「（工作区未记录）」，
    // 分组排在最后。

    /** session 表 v8 新增一列（会话记工作区；NULL = 老会话未记录，一眼可判）。 */
    val SESSION_WORKSPACE_COLUMNS: List<Pair<String, String>> = listOf(
        "workspace" to "TEXT",
    )

    /** workspace 列的加列语句（**纯函数**：缺列才给 ALTER，幂等；同 [sessionCloseColumnsStmt] 房型）。 */
    fun sessionWorkspaceColumnStmt(existingColumns: List<String>): List<Stmt> =
        SESSION_WORKSPACE_COLUMNS
            .filterNot { (col, _) -> existingColumns.any { it.equals(col, ignoreCase = true) } }
            .map { (col, type) -> Stmt("ALTER TABLE session ADD COLUMN $col $type", emptyArray()) }

    /** 新会话写入工作区绝对路径（[com.example.zhengdao.terminal.Workspace.hostDir] 的解析结果）。 */
    fun setSessionWorkspace(sessionId: Long, path: String): Stmt = Stmt(
        "UPDATE session SET workspace = ? WHERE id = ?",
        arrayOf<String?>(path, sessionId.toString()),
    )

    /**
     * 会话库列表（第二十批 §2.1：列表六要素 + 排序；第二十五批 A 段：轮数要真）。
     *
     * 轮数 = 该会话 `message.role='user'` 的条数（「一轮」＝用户一次发话），**实时数出来**，
     * 不读 `msg_count` 列——那列没被维护，真机全线显示 0（第二十五批 §1-A）。
     * 计数方式选 **LEFT JOIN 子查询一条 SQL**（另一选项是列表逐条 COUNT 的 N+1）：
     * 一条语句一次游标拿全六要素，没有 N+1 次查询往返；LEFT JOIN 数不到自动 NULL，
     * `COALESCE` 落 0——「0 轮真为 0」不需要代码分支兜底。不改表结构（msg_count 列留着）。
     * 只列未归档（archived = 0），按 `updated_at DESC`。
     */
    fun sessionLibraryEntries(): Stmt = Stmt(
        "SELECT s.id, s.title, COALESCE(u.n, 0) AS rounds, s.updated_at, s.closed_at, s.workspace " +
            "FROM session s " +
            "LEFT JOIN (SELECT session_id, COUNT(*) AS n FROM message " +
            "WHERE role = 'user' GROUP BY session_id) u ON u.session_id = s.id " +
            "WHERE s.archived = 0 AND s.deleted_at IS NULL ORDER BY s.updated_at DESC",
        emptyArray(),
    )

    // ── compression 表（第十三批 v6：压过哪几条要留痕，摘要要能重启再拿到）──

    /**
     * 压缩记录表（派工单 §4.1 的建表语句，**全部 IF NOT EXISTS ⇒ 幂等**，照 v3 房型）。
     *
     * ⚠️ 与派工单的一处不同：`session_id` 用 **INTEGER**（派工单写的是 TEXT）。
     * `session.id` 是 `INTEGER PRIMARY KEY AUTOINCREMENT`，全仓 `run_log` / `part`
     * 等外键也都是 INTEGER；写成 TEXT 会让「按 session 查」走不上索引、且与
     * 既有外键类型不一致（SQLite 不强制，但会埋类型隐式转换的坑）。已记入偏差清单。
     */
    val COMPRESSION_DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS compression (
          id TEXT PRIMARY KEY,
          session_id INTEGER NOT NULL REFERENCES session(id),
          summary TEXT NOT NULL,
          compressed_ids TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          model TEXT NOT NULL,
          source_tokens INTEGER NOT NULL DEFAULT 0,
          summary_tokens INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_compression_session ON compression(session_id, created_at)",
    )

    /** 压缩记录的稳定 id（同一会话同一时刻只可能一条；恢复与去重都靠它）。 */
    fun compressionId(sessionId: Long, createdAt: Long): String = "c-$sessionId-$createdAt"

    fun insertCompression(
        sessionId: Long,
        record: CompressionRecord,
        sourceTokens: Int,
        summaryTokens: Int,
    ): Stmt = Stmt(
        "INSERT INTO compression (id, session_id, summary, compressed_ids, created_at, " +
            "model, source_tokens, summary_tokens) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf<String?>(
            compressionId(sessionId, record.createdAt),
            sessionId.toString(),
            record.summary,
            idsToJson(record.compressedIds),
            record.createdAt.toString(),
            record.model,
            sourceTokens.toString(),
            summaryTokens.toString(),
        ),
    )

    /** 本会话最近一条压缩记录（没有 ⇒ 空结果）。 */
    fun latestCompression(sessionId: Long): Stmt = Stmt(
        "SELECT id, session_id, summary, compressed_ids, created_at, model, " +
            "source_tokens, summary_tokens FROM compression " +
            "WHERE session_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
        arrayOf<String?>(sessionId.toString()),
    )

    /**
     * 最近一次**压缩失败**发生在第几步（冷却判据的锚）。
     * 走 `tool` 列的原因码（引擎留痕时写 `compression_failed`），不新加状态。
     */
    fun lastCompressionFailedStep(sessionId: Long): Stmt = Stmt(
        "SELECT COALESCE(MAX(step), -1) FROM run_log WHERE session_id = ? AND tool = ?",
        arrayOf<String?>(sessionId.toString(), CompressionRunner.FAIL_REASON),
    )

    /**
     * 十六批第一件（步骤卡收纳）：全会话 run_log **活动行**的只读 SELECT 常量。
     *
     * 「活动行」= tool 列非空（Thinking/Done 这些状态行不进 UI）；compression 留痕行
     * （`compression_failed` 等）不在这里滤——那是 UI 纯函数的判据（JVM 可测）。
     * 派工单白名单限定 BaopuDb 只许加这一条 SELECT 常量：轮窗口切片在 UI 层做，
     * 这里一次把全会话行读齐，不逐轮查询。
     */
    const val RUN_LOG_STEPS_SELECT: String =
        "SELECT tool, ok, latency_ms, err, created_at FROM run_log " +
            "WHERE session_id = ? AND tool != '' ORDER BY id ASC"

    /** 被压消息 id 列表 → JSON 数组（**纯函数**：空表也给 `[]`，不给空串）。 */
    fun idsToJson(ids: List<String>): String = org.json.JSONArray().also { arr ->
        ids.forEach { arr.put(it) }
    }.toString()

    /** JSON 数组 → id 列表（**纯函数**：坏 JSON 返回空表，不抛——坏一行不该让恢复炸）。 */
    fun idsFromJson(json: String?): List<String> = runCatching {
        val arr = org.json.JSONArray(json ?: return emptyList())
        buildList {
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
    }.getOrDefault(emptyList())

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

    // ── 会话回收站（第四十七批）────────────────────────────────────────────

    /** v10：session 加 `deleted_at`（可空；NULL＝未删）。列已在⇒空语句（幂等）。 */
    fun sessionDeletedAtStmt(cols: List<String>): List<Stmt> =
        if ("deleted_at" in cols) emptyList()
        else listOf(Stmt("ALTER TABLE session ADD COLUMN deleted_at INTEGER", emptyArray()))

    /** 软删：只动 session 这一行，消息/产物/计划都还在（恢复即回来）。 */
    fun softDeleteSession(sid: Long, nowMs: Long): Stmt = Stmt(
        "UPDATE session SET deleted_at = ?, updated_at = ? WHERE id = ?",
        arrayOf<String?>(nowMs.toString(), nowMs.toString(), sid.toString()),
    )

    /** 恢复：把 deleted_at 清回 NULL。 */
    fun restoreSession(sid: Long, nowMs: Long): Stmt = Stmt(
        "UPDATE session SET deleted_at = NULL, updated_at = ? WHERE id = ?",
        arrayOf<String?>(nowMs.toString(), sid.toString()),
    )

    fun listDeletedSessions(): Stmt = Stmt(
        "SELECT id, title, updated_at, msg_count FROM session " +
            "WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC",
        emptyArray(),
    )

    fun countDeletedSessions(): Stmt = Stmt(
        "SELECT COUNT(*) FROM session WHERE deleted_at IS NOT NULL",
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
     * 改「最近一条用户消息」的正文（第四十批「编辑上一条」）。
     *
     * 只改 `kind='text'` 的 part（用户消息的正文在 seq=0 的 text part 里）；图片 part 不动
     * ——改字不该顺手把她发过的图删了。
     */
    fun updateUserText(messageId: Long, text: String): Stmt = Stmt(
        "UPDATE part SET payload = ? WHERE message_id = ? AND kind = 'text'",
        arrayOf<String?>(text, messageId.toString()),
    )

    /**
     * 删掉某会话里 **id 大于 [afterId]** 的全部消息（第四十批「编辑上一条」/「重试」）。
     *
     * part 与 message 之间是 `ON DELETE CASCADE`（DB 打开时已开外键）⇒ 删 message 连带删
     * parts，不用两条语句。只按 `session_id + id` 定位：id 是自增主键、同会话内天然单调；
     * seq 会因压缩重排而不可靠，不能拿它当边界。
     */
    fun deleteMessagesAfter(sessionId: Long, afterId: Long): Stmt = Stmt(
        "DELETE FROM message WHERE session_id = ? AND id > ?",
        arrayOf<String?>(sessionId.toString(), afterId.toString()),
    )

    /** 清掉某会话的计划快照（编辑上一条后重跑：旧计划不该跟着新内容走）。 */
    fun deletePlanBatches(sessionId: Long): Stmt = Stmt(
        "DELETE FROM plan_batch WHERE session_id = ?",
        arrayOf<String?>(sessionId.toString()),
    )

    /**
     * 计划快照的消息行（第十七批 B 段）：assistant 消息＋一条 `kind='plan'` part。
     *
     * seq 用 `MAX(seq)+1` 子查询**在一条 INSERT 里算**——与引擎 `nextSeq` 同口径
     * （新会话从 0 起）。拆成先查后插会露出「两条消息同 seq」的窗口；单写者线程
     * 内虽无并发，SQL 自己算更稳。
     *
     * 约束绑定顺序：[sessionId, sessionId, createdAt]（WHERE 子查询里还有一个
     * sessionId，别数错）。
     */
    fun insertPlanMessage(sessionId: Long, createdAt: Long): Stmt = Stmt(
        "INSERT INTO message (session_id, seq, role, created_at, status) " +
            "VALUES (?, (SELECT COALESCE(MAX(seq), -1) + 1 FROM message WHERE session_id = ?), " +
            "'assistant', ?, 'done')",
        arrayOf<String?>(sessionId.toString(), sessionId.toString(), createdAt.toString()),
    )

    /**
     * 某会话全部 `kind='plan'` part 的 payload（按消息序正序；**最后一条＝当前计划**）。
     *
     * 第十七批（工作计划卡 B 段）：`part.kind` 是自由文本列，加一种 kind
     * **不动表结构**（派工单 §1.2 首选方案；没有迁移、没有幂等负担）。
     * 写入沿用 [insertPart]（kind="plan"），这里只补读。
     *
     * 「同一轮多次 update_plan」的处理口径：**全部保留**，取用方取最后一条
     * （与 tool part 一条调用一条的追加形态一致；删旧行反而丢了「计划怎么变的」）。
     * 按 sessionId 读 ⇒ 天然与「会话的某一轮」对上：轮是 UI 侧按 user 消息切的，
     * 计划 part 的消息序落在哪两条 user 消息之间，它就属于哪一轮。
     */
    fun planPayloadsBySession(sessionId: Long): Stmt = Stmt(
        "SELECT part.payload FROM part " +
            "JOIN message ON part.message_id = message.id " +
            "WHERE message.session_id = ? AND part.kind = 'plan' " +
            "ORDER BY message.seq ASC, part.seq ASC",
        arrayOf<String?>(sessionId.toString()),
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

    /**
     * 把一轮的用量读数更新进 run_log（刀 3 B 段；[usage] 为 null 或五列全空
     * ⇒ 返回 null，调用方跳过——「没测到不编数」，不写半行空话）。
     *
     * 目标行 = 该 (session, step) 的**最新一行**（`MAX(id)`）：引擎的写入顺序是
     * Thinking 行先落（模型轮次前）、Acting 行后落（工具执行前）⇒ 本语句必须在
     * 同 step 的 Acting 行插入**之前**调，否则更到的是 Acting 行。这条调用时序
     * SQL 自己看不出来，记在 KDoc 里（引擎侧接线归第十二批 A 段：第十四批
     * 禁改 agent 包——工具与护栏本单零改动）。
     *
     * 「只 SET 有读数的列」是有意的：`tokens_in`/`tokens_out` 是 NOT NULL 列
     * （insert 时写 0），绑 NULL 会炸约束；取不到就不动那一列。cache 两列与
     * reasoning 列可空（绑 SQL NULL 也合法）——两种列型两种处理，不混。
     */
    fun updateRunLogUsage(sessionId: Long, step: Int, usage: LlmUsage?): Stmt? {
        if (usage == null) return null
        val sets = ArrayList<String>(5)
        val args = ArrayList<String?>(7)
        usage.promptTokens?.let { sets += "tokens_in = ?"; args += it.toString() }
        usage.completionTokens?.let { sets += "tokens_out = ?"; args += it.toString() }
        usage.reasoningTokens?.let { sets += "reasoning_tokens = ?"; args += it.toString() }
        usage.cacheHitTokens?.let { sets += "cache_hit_tokens = ?"; args += it.toString() }
        usage.cacheMissTokens?.let { sets += "cache_miss_tokens = ?"; args += it.toString() }
        if (sets.isEmpty()) return null
        args += sessionId.toString()
        args += step.toString()
        return Stmt(
            "UPDATE run_log SET " + sets.joinToString(", ") +
                " WHERE id = (SELECT MAX(id) FROM run_log WHERE session_id = ? AND step = ?)",
            args.toTypedArray(),
        )
    }

    /**
     * 删一个会话的**全部**语句，**顺序即执行顺序**（外键已开，顺序错就报 constraint）：
     * 1. 先删 ops 子批（plan_batch 自引用，子先父后）；
     * 2. 再删提案批；
     * 3. run_log（规格里它没有 ON DELETE CASCADE，必须显式删）；
     * 4. **compression**（压缩摘要替身。同样没有级联 —— E-158：漏了这条，
     *    凡是被压缩过一次的会话就**永远删不掉**，真机上表现为 Toast「删除失败」）；
     * 5. message（part 靠 DDL 的 ON DELETE CASCADE 级联，不重复删）；
     * 6. 最后才是 session 本体。
     *
     * ⚠️ 以后给任何表加 `REFERENCES session(id)`：**没写级联的**必须在这里补一条
     * （`BaopuDbTest` 有一条从 DDL 反推的门禁，漏了会红）；**写了 `ON DELETE CASCADE`
     * 的**由 SQLite 联动清行，不在此列——`part`（v1）与 `artifact`（v9，38 批 §9.1）
     * 都是这一类，别为它们加重复语句。
     */
    fun deleteSessionStatements(sessionId: Long): List<Stmt> = listOf(
        Stmt(
            "DELETE FROM plan_batch WHERE session_id = ? AND proposal_id IS NOT NULL",
            arrayOf<String?>(sessionId.toString()),
        ),
        Stmt("DELETE FROM plan_batch WHERE session_id = ?", arrayOf<String?>(sessionId.toString())),
        Stmt("DELETE FROM run_log WHERE session_id = ?", arrayOf<String?>(sessionId.toString())),
        Stmt("DELETE FROM compression WHERE session_id = ?", arrayOf<String?>(sessionId.toString())),
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
            "created_at, updated_at, use_count, last_used_at, " +
            "score, quality_flags, merged_from, scored_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
            e.qualityScore.toString(),
            e.qualityFlags.joinToString(","),
            e.mergedFrom.joinToString(","),
            e.scoredAt.toString(),
        ),
    )

    /**
     * v14 质量四列的加列语句（**纯函数**：缺哪列给哪条 ALTER，幂等）。
     * 与 [runLogReasoningColumnsStmt]／`skillEffectColumnStmt` 同一房型——
     * SQLite 没有 `ADD COLUMN IF NOT EXISTS`，重跑迁移必须看列况再动，否则老库升级直接炸。
     *
     * 老行填默认值靠 `NOT NULL DEFAULT`（ALTER 时 SQLite 就给既有行补上），
     * 不写 UPDATE：**不写就等于不碰用户数据**（红线：不许动已有记忆正文）。
     */
    fun memoryQualityColumnsStmt(existingColumns: List<String>): List<Stmt> =
        MEMORY_QUALITY_COLUMNS
            .filterNot { (col, _) -> existingColumns.any { it.equals(col, ignoreCase = true) } }
            .map { (col, type) -> Stmt("ALTER TABLE memory ADD COLUMN $col $type", emptyArray()) }

    /** 删一条记忆索引行（文件由 [MemoryStore.delete] 删；这里是索引同步）。 */
    fun deleteMemory(id: String): Stmt =
        Stmt("DELETE FROM memory WHERE id = ?", arrayOf<String?>(id))

    /** run_log v2 新增的两个缓存口径列（追加三：可空，取不到留空不许填 0）。 */
    val RUN_LOG_CACHE_COLUMNS = listOf("cache_hit_tokens", "cache_miss_tokens")

    /**
     * run_log v4 新增的思考用量列（刀 3 B 段 · 第十四批 B 段第 5 条）：
     * `reasoning_tokens`——模型侧思考烧掉的 token（① DeepSeek 实测
     * `completion_tokens_details.reasoning_tokens`）。可空：没读到就不写，
     * 与 cache 两列同口径（0 = 确定没思考，null = 没测到，两回事）。
     */
    val RUN_LOG_REASONING_COLUMNS = listOf("reasoning_tokens")

    /**
     * run_log 缓存列的加列语句（**纯函数**：缺哪列给哪条 ALTER，幂等）。
     * 与 [sessionInterruptedStepStmt] 同一房型——SQLite 没有 ADD COLUMN IF NOT EXISTS。
     */
    fun runLogCacheColumnsStmt(existingColumns: List<String>): List<Stmt> =
        RUN_LOG_CACHE_COLUMNS
            .filterNot { col -> existingColumns.any { it.equals(col, ignoreCase = true) } }
            .map { col -> Stmt("ALTER TABLE run_log ADD COLUMN " + col + " INTEGER", emptyArray()) }

    /**
     * run_log 思考列的加列语句（v4 · 纯函数：缺列才给 ALTER，幂等）。
     * 与 [runLogCacheColumnsStmt] 同一房型（先看列况再动，重跑迁移不炸）。
     */
    fun runLogReasoningColumnsStmt(existingColumns: List<String>): List<Stmt> =
        RUN_LOG_REASONING_COLUMNS
            .filterNot { col -> existingColumns.any { it.equals(col, ignoreCase = true) } }
            .map { col -> Stmt("ALTER TABLE run_log ADD COLUMN " + col + " INTEGER", emptyArray()) }

    /**
     * 按质量序读索引行（第八十九批 §1「检索排序」在 DB 侧的那一份）。
     *
     * 为什么单开一条而不是复用旧读法：历史那套「与文件重建同序」的对拍口径
     * （v2 就定死了，`BaopuDbTest` 钉着）改了就是把历史判据偷偷换掉。
     * 排序键与 [MemoryQuality.rankComparator] 逐字对应：**分数降 → 创建升 → id 升**。
     */
    fun allMemoriesRanked(): Stmt = Stmt(
        "SELECT id, scope, kind, title, body, tags, source_session, source_file, status, " +
            "created_at, updated_at, use_count, last_used_at, " +
            "score, quality_flags, merged_from, scored_at FROM memory " +
            "ORDER BY score DESC, created_at ASC, id ASC",
        emptyArray(),
    )

    // ── artifact 表（v9 · 第三十八批 §9.1：抽屉「产物区」要能回看与删除）─────────
    //
    // 为什么要落库、不再每次扫盘猜：产物区要显示**来自哪个会话**和**时间**，删过的还不许
    // 再冒出来——这三件事扫盘扫不出来（派工单 §9.1 原话）。
    // 表里只有**相对路径**与字节数：不存绝对路径、更不存文件内容（§9.4 红线）；
    // 内容永远现读盘，与 B 段那个查看器同一个口径（查看器也不看这张表）。
    //
    // ⚠️ 版本号纠偏：派工单写「v7 迁移」，但仓里 v7（第十九批 session 封卷两列）与
    // v8（第二十批 session 工作区列）**已经占用** ⇒ 本表落在 **v9**。写法照 v6
    // `compression`：新常量 + `applyV9Migration`，onCreate / onUpgrade 走同一份迁移体。

    /**
     * 产物台账表（全部 `IF NOT EXISTS` ⇒ 幂等，重复跑不炸；「一份文件只出现一次」
     * 靠那条 UNIQUE 索引，判据 C1）。
     *
     * `session_id … ON DELETE CASCADE`：删会话时台账跟着走（与 `part` 同处理，
     * 所以 `deleteSessionStatements` 里**不**为它另加一条——BaopuDbTest 那条从 DDL
     * 反推的门禁认级联）。
     */
    val ARTIFACT_DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS artifact (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE,
          workspace TEXT NOT NULL,
          rel_path TEXT NOT NULL,
          bytes INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_artifact_ws_path ON artifact (workspace, rel_path)",
    )

    /** 抽屉产物列表最多列几行（派工单 §9.2「最多 200 行」）。 */
    const val ARTIFACT_LIST_MAX = 200

    /**
     * 记一条产物：同一 `(workspace, rel_path)` 再写 ⇒ **UPDATE**（只刷 `bytes`／
     * `updated_at`，`created_at` 与 `session_id` 保持第一次那份）——判据 C1。
     *
     * `ON CONFLICT … DO UPDATE` 需要 SQLite 3.24+：本仓 minSdk=36，且
     * `LibraryStore` 里 `ON CONFLICT(sha256) DO NOTHING` 早有先例。
     * 只在**真写成功**时调（引擎 Done 分支；判据 C2 从源码层钉）。
     */
    fun upsertArtifact(sessionId: Long, workspace: String, relPath: String, bytes: Long, nowMs: Long): Stmt =
        Stmt(
            "INSERT INTO artifact (session_id, workspace, rel_path, bytes, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (workspace, rel_path) DO UPDATE SET " +
                "bytes = excluded.bytes, updated_at = excluded.updated_at",
            arrayOf<String?>(
                sessionId.toString(),
                workspace,
                relPath,
                bytes.toString(),
                nowMs.toString(),
                nowMs.toString(),
            ),
        )

    /** 当前工作区有几个产物（抽屉入口那个 N；判据 C3 按工作区隔离）。 */
    fun countArtifacts(workspace: String): Stmt = Stmt(
        "SELECT COUNT(*) FROM artifact WHERE workspace = ?",
        arrayOf<String?>(workspace),
    )

    /**
     * 产物列表（抽屉面板那一份读）：`updated_at` 倒序、最多 [ARTIFACT_LIST_MAX] 行，
     * 每行带「来自哪个会话」的标题。
     *
     * `LEFT JOIN session` 而不是 INNER：会话记录被清了也不该把产物整行弄丢
     * （文件还在盘上，用户还得能删它）；没标题 ⇒ `COALESCE` 落空串，界面显示「（未命名）」。
     *
     * `LIMIT` 拼的是 internal 常量字面量、不是外部输入 ⇒ 不违反「参数一律 `?`」那条
     * （SQLite 的 LIMIT 要整数，绑 String 靠隐式转换，宁可钉在 SQL 文本里更可测）。
     */
    fun listArtifacts(workspace: String): Stmt = Stmt(
        "SELECT a.rel_path, a.bytes, a.created_at, a.updated_at, COALESCE(s.title, '') " +
            "FROM artifact a LEFT JOIN session s ON s.id = a.session_id " +
            "WHERE a.workspace = ? ORDER BY a.updated_at DESC LIMIT $ARTIFACT_LIST_MAX",
        arrayOf<String?>(workspace),
    )

    /** 从台账里清掉一条（抽屉删除：文件**移进回收站之后**才调；判据 C5/C6）。 */
    fun deleteArtifact(workspace: String, relPath: String): Stmt = Stmt(
        "DELETE FROM artifact WHERE workspace = ? AND rel_path = ?",
        arrayOf<String?>(workspace, relPath),
    )

    // ── v11（第五十七批）：技能事件表 skill_event ────────────────────────────
    //
    // 一张表两种事件（kind 区分），派工单 §1.1/§1.3「同 1.1 的表或一张 skill_decision 表，
    // 二选一说明」⇒ **选一张表**：两件事都是「技能进化链上的被动留痕」，字段几乎重合
    // （名字/时间/成败），分开两张要两份迁移、两份读写壳，合一张省一半且审计一条 SELECT 全出。
    //
    // - kind='use'：技能被用过一次（write_skill 的 use 动作收口时记）；ok=该次调用最终成败。
    // - kind='decision'：提案被采纳/丢弃；ok=1 采纳 / 0 丢弃；hash=「名称＋正文摘要」去重键
    //   （丢弃后下轮提炼按它跳过）；detail=丢弃理由（可空）。
    //
    // 红线：这表是**只增不改不删**的账本——没有 UPDATE/DELETE 语句（判据在测试里钉）。
    // session_id 不挂 CASCADE：会话删了，技能用过没用的账还要留（过期判据靠它）。

    /**
     * 技能事件表（全部 `IF NOT EXISTS` ⇒ 幂等，照 v6/v9 房型）。
     * `target`：'skill' | 'memory'（use 恒为 skill；decision 记提案是哪一类）。
     */
    val SKILL_EVENT_DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS skill_event (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          kind TEXT NOT NULL,
          target TEXT NOT NULL,
          name TEXT NOT NULL,
          session_id INTEGER,
          step INTEGER,
          ok INTEGER NOT NULL DEFAULT 1,
          hash TEXT,
          detail TEXT,
          at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_skill_event_name ON skill_event (name, kind, id)",
    )

    /** 记一条「技能被用过」（use 收口时调；写失败由调用方只 log 不阻塞）。 */
    fun insertSkillUse(name: String, sessionId: Long?, step: Int?, ok: Boolean, nowMs: Long): Stmt =
        Stmt(
            "INSERT INTO skill_event (kind, target, name, session_id, step, ok, at) " +
                "VALUES ('use', 'skill', ?, ?, ?, ?, ?)",
            arrayOf<String?>(
                name,
                sessionId?.toString(),
                step?.toString(),
                if (ok) "1" else "0",
                nowMs.toString(),
            ),
        )

    /**
     * 某技能最近 [limit] 次**使用成败**（旧→新；§1.2 过期判据「最近 M 次失败率」的数据源）。
     * 记录不足 [limit] 条也照回——不足时判据侧「证据不够不判」。
     */
    fun recentUseOks(name: String, limit: Int): Stmt = Stmt(
        "SELECT ok FROM skill_event WHERE kind = 'use' AND name = ? ORDER BY id DESC LIMIT $limit",
        arrayOf<String?>(name),
    )

    /** 某技能最后一次被用的时间（null = 记录上线后还没用过；§1.2 判据「久未用」的数据源）。 */
    fun lastUseAt(name: String): Stmt = Stmt(
        "SELECT MAX(at) FROM skill_event WHERE kind = 'use' AND name = ?",
        arrayOf<String?>(name),
    )

    /**
     * 某技能最后一次**失败**使用的时间（null = 没有失败记录）。
     *
     * 第一百八十批（技能索引失败态 UI）的数据源：设置页每个失败技能要显示「最近一次失败时刻」，
     * 只读、零写入，与 [recentUseOks] / [lastUseAt] 同族同口径（不进任何判据，判据一个字不改）。
     * 放在数据层而不是设置页里，是因为分类门禁整文件禁出现 "kind" 子串（SKILL.md 头字段纪律），
     * SQL 只能住在这一层。
     */
    fun lastFailedUseAt(name: String): Stmt = Stmt(
        "SELECT MAX(at) FROM skill_event WHERE kind = 'use' AND name = ? AND ok = 0",
        arrayOf<String?>(name),
    )

    /** 记一条「提案采纳/丢弃」（§1.3 提案卡按钮收口时调；detail=丢弃理由，可空）。 */
    fun insertSkillDecision(
        target: String,
        name: String,
        adopted: Boolean,
        hash: String?,
        detail: String?,
        nowMs: Long,
    ): Stmt = Stmt(
        "INSERT INTO skill_event (kind, target, name, ok, hash, detail, at) " +
            "VALUES ('decision', ?, ?, ?, ?, ?, ?)",
        arrayOf<String?>(
            target,
            name,
            if (adopted) "1" else "0",
            hash,
            detail,
            nowMs.toString(),
        ),
    )

    /** 被**丢弃**过的提案去重键全集（§1.3 提炼时跳过同一候选，下轮不再重复提）。 */
    fun rejectedDecisionHashes(): Stmt = Stmt(
        "SELECT DISTINCT hash FROM skill_event WHERE kind = 'decision' AND ok = 0 AND hash IS NOT NULL",
        emptyArray(),
    )

    // ── v12（第八十五批）：技能使用**效果**信号（同表新 kind，加四列）───────────────
    //
    // 派工单 §2「复用现有 SkillAudit／skill_event 表；没有就加表/列」＋ §3「不新增第二套审计表」
    // ⇒ **同表加列**：`kind='effect'` 的行用新四列，57 批的 `kind='use'`／`kind='decision'`
    // 一行语义没改（过期判据只读 kind='use'，所以新行不会搅动晋升／退役）。
    //
    // 四列全部**可空**：老库升上来后既有的 use／decision 行留 NULL——NULL 是「当时没这个口径」
    // 这个历史事实，不许回填（回填＝编造历史，与 v8 的 session.workspace 同口径）。
    // 效果行的值域只有枚举名／技能名／12 位正文摘要／时刻；闸门在 `SkillEffects.toStmt`。

    /** skill_event 表 v12 新增四列（效果信号；NULL = 老记录没有，一眼可判）。 */
    val SKILL_EFFECT_COLUMNS: List<Pair<String, String>> = listOf(
        "outcome" to "TEXT",
        "source" to "TEXT",
        "skill_version" to "TEXT",
        "reason_code" to "TEXT",
    )

    /**
     * 效果信号四列的加列语句（**纯函数**：缺哪列给哪条 ALTER，幂等；
     * 照 [sessionCloseColumnsStmt]／[sessionDeletedAtStmt] 房型——加列必须看列况，
     * 裸 ALTER 写进 DDL 常量会让第二次迁移炸掉）。
     */
    fun skillEffectColumnStmt(existingColumns: List<String>): List<Stmt> =
        SKILL_EFFECT_COLUMNS
            .filterNot { (col, _) -> existingColumns.any { it.equals(col, ignoreCase = true) } }
            .map { (col, type) -> Stmt("ALTER TABLE skill_event ADD COLUMN $col $type", emptyArray()) }

    /**
     * 记一条「这次使用效果如何」（第八十五批 §1；写失败由调用方只 log 不阻塞）。
     *
     * - `kind`／`target` 与 use 行同为 `'effect'`／`'skill'`（写死在 SQL 文本里，不绑参）；
     * - `ok` = **这次调用本身**成没成（事实）；`outcome` = 这条信号说明什么（解释）。
     *   两列各管一头，谁也不覆盖谁——RETRIED 行可以 ok=1（重来了、而且成了），
     *   这正是 §B 92 要的「用了之后有没有用」；
     * - `detail`／`hash` **不写**：那两列留给 57 批的 decision 行，效果行不往里塞任何文本。
     */
    fun insertSkillEffect(
        name: String,
        sessionId: Long?,
        step: Int?,
        outcome: String,
        source: String,
        skillVersion: String?,
        reasonCode: String?,
        ok: Boolean,
        nowMs: Long,
    ): Stmt = Stmt(
        "INSERT INTO skill_event (kind, target, name, session_id, step, ok, outcome, source, skill_version, reason_code, at) " +
            "VALUES ('effect', 'skill', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf<String?>(
            name,
            sessionId?.toString(),
            step?.toString(),
            if (ok) "1" else "0",
            outcome,
            source,
            skillVersion,
            reasonCode,
            nowMs.toString(),
        ),
    )

    /**
     * 某技能在**这一会话**里最后一条效果信号（outcome ＋ 时刻；没有 ⇒ 空结果集）。
     *
     * 判 RETRIED 的输入。走 `idx_skill_event_name (name, kind, id)` 定位再筛 session_id；
     * 按 id 倒序取第一条——`at` 会因时钟调整不单调，id 才是写入顺序。
     */
    fun lastSkillEffect(name: String, sessionId: Long): Stmt = Stmt(
        "SELECT outcome, at FROM skill_event " +
            "WHERE kind = 'effect' AND name = ? AND session_id = ? ORDER BY id DESC LIMIT 1",
        arrayOf<String?>(name, sessionId.toString()),
    )

    /**
     * 按 outcome 聚合某技能的效果信号计数（派工单 §2 的读接口所依赖的那条 SELECT）。
     *
     * **只数数**：不许在这里带出门槛／评分／退役判据。outcome 为 NULL 的行不计数
     * （效果行恒带 outcome，NULL 只可能是有人往这张表塞了别的东西——不认）。
     */
    fun skillOutcomeCounts(name: String): Stmt = Stmt(
        "SELECT outcome, COUNT(*) FROM skill_event " +
            "WHERE kind = 'effect' AND name = ? AND outcome IS NOT NULL GROUP BY outcome",
        arrayOf<String?>(name),
    )

    /**
     * 某会话 `created_at` 晚于 [sinceMs] 的**最后一条**用户消息正文（判「用户纠正」用）。
     *
     * 只取 seq=0 的 text part（用户消息正文就在那儿，图片 part 不参与认脸）；
     * **这条 SELECT 读出来的文本只进内存、过 [SkillEffects.correctionCode] 就丢**，
     * 一个字都不落表——红线「不存用户原话」。按 m.id 倒序：id 是自增主键、天然单调。
     */
    fun userTextAfter(sessionId: Long, sinceMs: Long): Stmt = Stmt(
        "SELECT p.payload FROM message m JOIN part p ON p.message_id = m.id " +
            "WHERE m.session_id = ? AND m.role = 'user' AND p.kind = 'text' AND p.seq = 0 AND m.created_at > ? " +
            "ORDER BY m.id DESC LIMIT 1",
        arrayOf<String?>(sessionId.toString(), sinceMs.toString()),
    )

    /** 本会话最新步号（效果行的 step 与 57 批 use 行同一个来源；57 批那份是内联写的，本批不回填改造）。 */
    fun lastRunLogStep(sessionId: Long): Stmt = Stmt(
        "SELECT MAX(step) FROM run_log WHERE session_id = ?",
        arrayOf<String?>(sessionId.toString()),
    )

    // ── v13（第八十七批）：运行轨迹表 run_trace ──────────────────────────────
    //
    // 为什么新开一张表而不是复用 run_log：两者**寿命与粒度都不同**。
    // run_log 是「状态迁移流水」（一步可能好几行：Thinking/Acting/Done），它的用途是
    // **断点续跑判据**（最后一行是什么 ⇒ 有没有中断），字段被确认制/用量/缓存三批
    // 反复加列，语义已经很挤。轨迹是「一次运行的结构」，按 runId 聚合、一行一个边界
    // （步/工具/压缩），要能被 traceOf(runId) 一次读全。
    //
    // 三处刻意的取舍：
    // 1. **不存正文**：工具参数只留 16 位 sha256 摘要（args_hash），提示词/回答/文件内容
    //    一个字节都不进库——这张表要能随便给人看（判据 T4 从 DDL 与模型类两处钉）；
    // 2. `session_id … ON DELETE CASCADE`：删会话时轨迹跟着走（与 `artifact` 同处理，
    //    所以 `deleteSessionStatements` 里**不**为它另加一条——那条从 DDL 反推的门禁认级联）；
    // 3. `duration_ms` 可负：-1 = **没测到**（审批路径耗在等人点按钮上，工具本身没耗时），
    //    不写 0 假装知道（与 `BaopuUsageStep.elapsedMs` 同口径）。
    //
    // 只增不改：这张表没有 UPDATE/DELETE 语句——轨迹是「当时发生了什么」，不许事后改写。
    //
    // ⚠️ 版本号：派工单出单时 v12 还空着，开工时被第八十五批（skill_event 效果信号四列）
    // 先占了 ⇒ 本表落在 **v13**（同批 rebase 实测）。

    /** 运行轨迹表（全部 `IF NOT EXISTS` ⇒ 幂等，照 v6/v9/v11/v12 房型）。 */
    val RUN_TRACE_DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS run_trace (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          run_id TEXT NOT NULL,
          session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE,
          step INTEGER NOT NULL DEFAULT 0,
          seq INTEGER NOT NULL DEFAULT 0,
          phase TEXT NOT NULL DEFAULT '',
          tool TEXT NOT NULL DEFAULT '',
          args_hash TEXT NOT NULL DEFAULT '',
          status TEXT NOT NULL DEFAULT '',
          duration_ms INTEGER NOT NULL DEFAULT 0,
          tokens_in INTEGER NOT NULL DEFAULT 0,
          tokens_out INTEGER NOT NULL DEFAULT 0,
          failure_kind TEXT NOT NULL DEFAULT '',
          decision TEXT NOT NULL DEFAULT '',
          started_at INTEGER NOT NULL,
          ended_at INTEGER NOT NULL
        )
        """.trimIndent(),
        // 回放读法就一种：按 runId 取全量、按 seq 排 ⇒ 复合索引。
        "CREATE INDEX IF NOT EXISTS idx_run_trace_run ON run_trace (run_id, seq)",
        // 「这个会话上次跑成什么样」按会话倒查 ⇒ 单列索引。
        "CREATE INDEX IF NOT EXISTS idx_run_trace_session ON run_trace (session_id, id)",
    )

    /**
     * 轨迹表的列序（**唯一一份**：SELECT 与游标映射都读它。
     * `agent/RunTrace.kt` 的 `RunTraceRules.COLUMNS` 直接转手这里，不另抄一遍）。
     */
    val RUN_TRACE_COLUMNS: List<String> = listOf(
        "run_id", "session_id", "step", "seq", "phase", "tool", "args_hash", "status",
        "duration_ms", "tokens_in", "tokens_out", "failure_kind", "decision", "started_at", "ended_at",
    )

    // ── v15（第一百一十七批）：会话内任务队列 run_queue ─────────────────────
    //
    // 为什么单独一张表：队列行要在**开跑那一刻**与用户消息同事务删/插，
    // 靠 run_log 的 step 或 message 表都表达不了「这条还没开跑」。
    // `ON DELETE CASCADE`：删会话时排队条跟着走（与 artifact/run_trace 同处理，
    // 所以 deleteSessionStatements **不**为它另加一条——DDL 反推门禁认级联）。
    // 只有 text / 相对图片路径 / 时间：不存附件字节、不存绝对路径。
    val RUN_QUEUE_DDL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS run_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE, text TEXT NOT NULL, image_rel_paths TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS idx_run_queue_session ON run_queue (session_id, id)",
    )

    /**
     * 记一行轨迹。
     *
     * 参数全是**枚举名/计数/时刻/摘要**（[com.example.zhengdao.baopu.agent.TraceRow.toStmt]
     * 是唯一调用方）——本函数刻意收成扁平参数，不让 `BaopuSql` 反向依赖 agent 包的类型。
     */
    @Suppress("LongParameterList")
    fun insertRunTrace(
        runId: String,
        sessionId: Long,
        step: Int,
        seq: Int,
        phase: String,
        tool: String,
        argsHash: String,
        status: String,
        durationMs: Long,
        tokensIn: Int,
        tokensOut: Int,
        failureKind: String,
        decision: String,
        startedAt: Long,
        endedAt: Long,
    ): Stmt = Stmt(
        "INSERT INTO run_trace (run_id, session_id, step, seq, phase, tool, args_hash, status, " +
            "duration_ms, tokens_in, tokens_out, failure_kind, decision, started_at, ended_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf<String?>(
            runId, sessionId.toString(), step.toString(), seq.toString(), phase, tool, argsHash, status,
            durationMs.toString(), tokensIn.toString(), tokensOut.toString(), failureKind, decision,
            startedAt.toString(), endedAt.toString(),
        ),
    )

    /** 读一次运行的全部轨迹（顺序＝`seq`，就是发生顺序；空表 ⇒ 零行，不报错＝老库可读）。 */
    fun selectRunTrace(runId: String): Stmt = Stmt(
        "SELECT " + RUN_TRACE_COLUMNS.joinToString(", ") +
            " FROM run_trace WHERE run_id = ? ORDER BY seq ASC",
        arrayOf<String?>(runId),
    )

    /** 该会话最近一次有轨迹的 runId（没有 ⇒ 零行）。 */
    fun selectLatestRunTrace(sessionId: Long): Stmt = Stmt(
        "SELECT run_id FROM run_trace WHERE session_id = ? ORDER BY id DESC LIMIT 1",
        arrayOf<String?>(sessionId.toString()),
    )

    // ── v15（第一百一十七批）：run_queue 语句 ─────────────────────────────

    /** 入队一行；开跑事务里按 id 删它（删行 + 插用户消息同事务）。 */
    fun insertRunQueue(
        sessionId: Long,
        text: String,
        imageRelPaths: List<String>,
        createdAt: Long,
    ): Stmt = Stmt(
        "INSERT INTO run_queue (session_id, text, image_rel_paths, created_at) VALUES (?, ?, ?, ?)",
        arrayOf<String?>(
            sessionId.toString(), text, idsToJson(imageRelPaths), createdAt.toString(),
        ),
    )

    fun deleteRunQueue(rowId: Long): Stmt =
        Stmt("DELETE FROM run_queue WHERE id = ?", arrayOf<String?>(rowId.toString()))

    fun deleteRunQueueForSession(sessionId: Long): Stmt =
        Stmt("DELETE FROM run_queue WHERE session_id = ?", arrayOf<String?>(sessionId.toString()))

    fun selectRunQueue(sessionId: Long): Stmt = Stmt(
        "SELECT id, session_id, text, image_rel_paths FROM run_queue " +
            "WHERE session_id = ? ORDER BY id ASC",
        arrayOf<String?>(sessionId.toString()),
    )

    fun selectAllRunQueue(): Stmt = Stmt(
        "SELECT id, session_id, text, image_rel_paths FROM run_queue ORDER BY id ASC",
        emptyArray(),
    )

    // ── v16（第一百二十七批）：多 Agent 协作 sub_task 审计表 ───────────────
    //
    // 为什么单独一张表：父子关系（parent_id 一串同分解的子任务）、逐条状态机
    // （PENDING→RUNNING→终态）、尝试次数与步数累计，都表达不了在 message/run_log 里。
    // `ON DELETE CASCADE`：删会话时子任务账跟着走（与 run_queue/run_trace 同处理，
    // deleteSessionStatements 不为它另加一条——DDL 反推门禁认级联）。
    // `task_id` 是 [com.example.zhengdao.baopu.agent.multiagent.SubTask.id] 的原文
    // （`sg-<会话>-<时刻>-<第几笔>-<序号>`）——更新/查询都按它，不拼字符串。
    // 只存枚举名/计数/时刻/摘要：result_text 截断 600 字（与 run_trace「不存正文」同口径），
    // goal/input/acceptance 也已在入口截断（SubAgentRules 的 MAX_*_CHARS）。
    val SUB_TASK_DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS sub_task (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          task_id TEXT NOT NULL UNIQUE,
          parent_id TEXT NOT NULL,
          session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE,
          seq INTEGER NOT NULL,
          goal TEXT NOT NULL,
          input TEXT NOT NULL DEFAULT '',
          acceptance TEXT NOT NULL DEFAULT '',
          tools TEXT NOT NULL DEFAULT '',
          status TEXT NOT NULL,
          attempts INTEGER NOT NULL DEFAULT 1,
          steps INTEGER NOT NULL DEFAULT 0,
          started_at INTEGER NOT NULL DEFAULT 0,
          ended_at INTEGER NOT NULL DEFAULT 0,
          failure_reason TEXT NOT NULL DEFAULT '',
          result_text TEXT NOT NULL DEFAULT '',
          created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        // 「这笔分解有哪些子任务」按 parent 取、按 seq 排 ⇒ 复合索引。
        "CREATE INDEX IF NOT EXISTS idx_sub_task_parent ON sub_task (parent_id, seq)",
        // 「这个会话历史上拆过哪些任务」按会话倒查 ⇒ 复合索引。
        "CREATE INDEX IF NOT EXISTS idx_sub_task_session ON sub_task (session_id, id)",
    )

    /** sub_task 的列序（唯一一份：SELECT 与游标映射都读它）。 */
    val SUB_TASK_COLUMNS: List<String> = listOf(
        "task_id", "parent_id", "session_id", "seq", "goal", "input", "acceptance", "tools",
        "status", "attempts", "steps", "started_at", "ended_at", "failure_reason",
        "result_text",
    )

    /** 分解落账：整批 PENDING（一行一条）。 */
    fun insertSubTask(
        taskId: String,
        parentId: String,
        sessionId: Long,
        seq: Int,
        goal: String,
        input: String,
        acceptance: String,
        tools: String,
        status: String,
        attempts: Int,
        steps: Int,
        startedAt: Long,
        endedAt: Long,
        failureReason: String,
        resultText: String,
        createdAt: Long,
    ): Stmt = Stmt(
        "INSERT INTO sub_task (task_id, parent_id, session_id, seq, goal, input, acceptance, " +
            "tools, status, attempts, steps, started_at, ended_at, failure_reason, result_text, " +
            "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf<String?>(
            taskId, parentId, sessionId.toString(), seq.toString(), goal, input, acceptance, tools,
            status, attempts.toString(), steps.toString(), startedAt.toString(), endedAt.toString(),
            failureReason, resultText, createdAt.toString(),
        ),
    )

    /** 一条开跑（PENDING ⇒ RUNNING；attempts/steps 不动）。 */
    fun updateSubTaskRunning(taskId: String, at: Long): Stmt = Stmt(
        "UPDATE sub_task SET status = 'RUNNING', started_at = ? WHERE task_id = ?",
        arrayOf<String?>(at.toString(), taskId),
    )

    /** 一条进终态（连同结果/原因/步数/尝试次数）。 */
    fun updateSubTaskFinished(
        taskId: String,
        status: String,
        attempts: Int,
        steps: Int,
        startedAt: Long,
        endedAt: Long,
        failureReason: String,
        resultText: String,
    ): Stmt = Stmt(
        "UPDATE sub_task SET status = ?, attempts = ?, steps = ?, started_at = ?, ended_at = ?, " +
            "failure_reason = ?, result_text = ? WHERE task_id = ?",
        arrayOf<String?>(
            status, attempts.toString(), steps.toString(), startedAt.toString(), endedAt.toString(),
            failureReason, resultText, taskId,
        ),
    )

    /** 审计查询：一笔分解的全部子任务（按 seq）。 */
    fun selectSubTasksOfParent(parentId: String): Stmt = Stmt(
        "SELECT " + SUB_TASK_COLUMNS.joinToString(", ") + " FROM sub_task " +
            "WHERE parent_id = ? ORDER BY seq ASC",
        arrayOf<String?>(parentId),
    )

    /** 审计查询：一个会话的全部分解开销（按 id 正序＝开拆顺序）。 */
    fun selectSubTasksOfSession(sessionId: Long): Stmt = Stmt(
        "SELECT " + SUB_TASK_COLUMNS.joinToString(", ") + " FROM sub_task " +
            "WHERE session_id = ? ORDER BY id ASC",
        arrayOf<String?>(sessionId.toString()),
    )
}

/**
 * 抱朴数据库（刀 0 骨架 · 刀 0b v2 迁移 · 刀 2 v3 迁移 · 刀 3 B 段 v4 迁移 ·
 * 刀 3 A 段 v5 迁移）。
 *
 * ## 三个不可动摇的点
 * 1. **单写者线程**（[writer]，`zd-baopu-db`，daemon）：一切写走 [write] 的 Future，
 *    读走调用方线程（WAL 下读不挡写、写不挡读）。谁在主线程直接摸 writableDatabase
 *    就是在拆这道闸——要写就从 [write] 过。
 * 2. **外键每个连接都开**（[BaopuSql.PRAGMA_FK_ON] 放 onConfigure）：PRAGMA 不持久化，
 *    放 onCreate 只对建库那一个连接生效，之后的连接全是关的——级联删除会静默失效。
 *    这是 SQLite 最经典的坑之一。
 * 3. **迁移必须真迁移**：v1→v2 建 memory 表 ＋ 给 session 加 `interrupted_step` 列，
 *    v2→v3 建资料库两表（`library_item`/`library_move_log`），v3→v4 给 run_log 加
 *    `reasoning_tokens` 列，v4→v5 给 session 加 pending 三列（确认制跨进程续），
 *    全部幂等（`IF NOT EXISTS` / 先看列再加）；v1 库为空也不丢表。
 *    **没有路径的迁移必须炸**——静默按新 schema 跑在旧库上比崩掉糟糕得多。
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
        applyV3Migration(db)
        applyV4Migration(db)
        applyV5Migration(db)
        applyV6Migration(db)
        applyV7Migration(db)
        applyV8Migration(db)
        applyV9Migration(db)
        applyV10Migration(db)
        applyV11Migration(db)
        applyV12Migration(db)
        applyV13Migration(db)
        applyV14Migration(db)
        applyV15Migration(db)
        applyV16Migration(db)
        applyV17Migration(db)
        applyV18Migration(db)
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
        if (oldVersion < 3) applyV3Migration(db)
        if (oldVersion < 4) applyV4Migration(db)
        if (oldVersion < 5) applyV5Migration(db)
        if (oldVersion < 6) applyV6Migration(db)
        if (oldVersion < 7) applyV7Migration(db)
        if (oldVersion < 8) applyV8Migration(db)
        if (oldVersion < 9) applyV9Migration(db)
        if (oldVersion < 10) applyV10Migration(db)
        if (oldVersion < 11) applyV11Migration(db)
        if (oldVersion < 12) applyV12Migration(db)
        if (oldVersion < 13) applyV13Migration(db)
        if (oldVersion < 14) applyV14Migration(db)
        if (oldVersion < 15) applyV15Migration(db)
        if (oldVersion < 16) applyV16Migration(db)
        if (oldVersion < 17) applyV17Migration(db)
        if (oldVersion < 18) applyV18Migration(db)
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

    /**
     * v2 → v3 迁移体（刀 2）：资料库两表 + 章节索引表 ＋ 索引，全部 `IF NOT EXISTS`
     * ⇒ 幂等，重复跑不炸（派工单 §2.4 原话：「加列/加表都要幂等，重复跑不炸」）。
     * v2 库升级与新安装走同一条路径（onCreate 也调它）。
     */
    private fun applyV3Migration(db: SQLiteDatabase) {
        BaopuSql.LIBRARY_DDL.forEach { db.execSQL(it) }
        BaopuSql.CHAPTER_INDEX_DDL.forEach { db.execSQL(it) }
    }

    /**
     * v3 → v4 迁移体（刀 3 B 段）：run_log 加 `reasoning_tokens` 列（思考用量）。
     * 加列走「先看列况再动」的纯函数 ⇒ 幂等，重复跑不炸；v3 库升级与新安装
     * 走同一条路径（onCreate 也调它）。
     */
    private fun applyV4Migration(db: SQLiteDatabase) {
        val runLogCols = tableColumns(db, "run_log")
        BaopuSql.runLogReasoningColumnsStmt(runLogCols).forEach { stmt ->
            if (stmt.args.isEmpty()) db.execSQL(stmt.sql) else db.execSQL(stmt.sql, stmt.args)
        }
    }

    /**
     * v4 → v5 迁移体（刀 3 A 段）：session 加 pending 三列（确认制跨进程续——
     * 进程被杀后卡还在、批准仍有效，全靠这三列；只存内存态 T3 必红）。
     * 加列走「先看列况再动」的纯函数 ⇒ 幂等，重复跑不炸；v4 库升级与新安装
     * 走同一条路径（onCreate 也调它）。
     */
    private fun applyV5Migration(db: SQLiteDatabase) {
        val sessionCols = tableColumns(db, "session")
        BaopuSql.sessionPendingColumnsStmt(sessionCols).forEach { stmt ->
            if (stmt.args.isEmpty()) db.execSQL(stmt.sql) else db.execSQL(stmt.sql, stmt.args)
        }
    }

    /**
     * v5 → v6 迁移体（第十三批）：`compression` 表 ＋ 索引，**全是 IF NOT EXISTS**
     * ⇒ 幂等（派工单 §4.1「照 BaopuDb 既有风格」；J9 钉的就是连开两次不炸）。
     */
    private fun applyV6Migration(db: SQLiteDatabase) {
        BaopuSql.COMPRESSION_DDL.forEach { db.execSQL(it) }
    }

    /**
     * v6 → v7 迁移体（第十九批）：session 加封卷两列 `closed_at` / `refined_at`
     * （会话级收敛信号——点「新会话」封卷提炼，进程被杀没跑成 ⇒ 下次启动自检补算）。
     * 加列走「先看列况再动」的纯函数 ⇒ 幂等，重复跑不炸；v6 库升级与新安装
     * 走同一条路径（onCreate 也调它）。
     */
    private fun applyV7Migration(db: SQLiteDatabase) {
        val sessionCols = tableColumns(db, "session")
        BaopuSql.sessionCloseColumnsStmt(sessionCols).forEach { stmt ->
            if (stmt.args.isEmpty()) db.execSQL(stmt.sql) else db.execSQL(stmt.sql, stmt.args)
        }
    }

    /**
     * v7 → v8 迁移体（第二十批）：session 加 `workspace` 列（会话记住自己在哪个
     * 工作区里跑的）。加列走「先看列况再动」的纯函数 ⇒ 幂等，重复跑不炸；v7 库
     * 升级与新安装走同一条路径（onCreate 也调它）。
     */
    private fun applyV8Migration(db: SQLiteDatabase) {
        val sessionCols = tableColumns(db, "session")
        BaopuSql.sessionWorkspaceColumnStmt(sessionCols).forEach { stmt ->
            if (stmt.args.isEmpty()) db.execSQL(stmt.sql) else db.execSQL(stmt.sql, stmt.args)
        }
    }

    /**
     * v8 → v9 迁移体（第三十八批 §9.1）：`artifact` 表 + 唯一索引，**全是 IF NOT EXISTS**
     * ⇒ 幂等（照 v6 `compression` 房型：加表就是 DDL 一遍，不需要看列况）。
     * 新安装（onCreate 也调它）与 v8 库升级走**同一条**路径。
     */
    /** v10 迁移体：session 加 deleted_at（看列再动）——会话回收站（第四十七批）。 */
    private fun applyV10Migration(db: SQLiteDatabase) {
        val cols = tableColumns(db, "session")
        BaopuSql.sessionDeletedAtStmt(cols).forEach { stmt ->
            if (stmt.args.isEmpty()) db.execSQL(stmt.sql) else db.execSQL(stmt.sql, stmt.args)
        }
    }

    private fun applyV9Migration(db: SQLiteDatabase) {
        BaopuSql.ARTIFACT_DDL.forEach { db.execSQL(it) }
    }

    /**
     * v10 → v11 迁移体（第五十七批）：`skill_event` 技能事件表 ＋ 索引，**全是 IF NOT
     * EXISTS** ⇒ 幂等（照 v6 `compression`／v9 `artifact` 房型：加表就是 DDL 一遍）。
     * 新安装（onCreate 也调它）与 v10 库升级走**同一条**路径。
     */
    private fun applyV11Migration(db: SQLiteDatabase) {
        BaopuSql.SKILL_EVENT_DDL.forEach { db.execSQL(it) }
    }

    /**
     * v11 → v12 迁移体（第八十五批）：`skill_event` 加效果信号四列（可空）。
     *
     * **看列再动** ⇒ 幂等（照 v7 封卷两列／v10 deleted_at 房型：加列不能写成裸 ALTER 常量，
     * 否则第二次跑就炸）。新安装（onCreate 也调它）与 v11 库升级走**同一条**路径。
     */
    private fun applyV12Migration(db: SQLiteDatabase) {
        val cols = tableColumns(db, "skill_event")
        BaopuSql.skillEffectColumnStmt(cols).forEach { stmt ->
            if (stmt.args.isEmpty()) db.execSQL(stmt.sql) else db.execSQL(stmt.sql, stmt.args)
        }
    }

    /**
     * v12 → v13 迁移体（第八十七批）：`run_trace` 运行轨迹表 ＋ 两条索引，**全是
     * `IF NOT EXISTS`** ⇒ 幂等（照 v6/v9/v11/v12 房型）。新安装（onCreate 也调它）
     * 与 v12 库升级走**同一条**路径。
     *
     * 老库可读性：v12 及更早的库升上来只会**多一张空表**，`run_log`/`message`/`part`/
     * `skill_event` 一列不动 ⇒ 老会话照常打开；老 run 没有轨迹（`traceOf` 返回空表），不报错。
     */
    private fun applyV13Migration(db: SQLiteDatabase) {
        BaopuSql.RUN_TRACE_DDL.forEach { db.execSQL(it) }
    }

    /**
     * v13 → v14 迁移体（第八十九批 · 记忆质量环）：`memory` 加质量四列（**看列再动** ⇒ 幂等）。
     *
     * 号为什么是 v14：出单时 main 是 v11，派工单写「DB 版本 +1」（＝v12）。但排队的这段时间里
     * **85 批把 v12 用掉了**（`62744e1d` skill_event 效果四列）、**87 批把 v13 用掉了**
     * （`70118526` run_trace 表），两支都已在 origin/main 落地 ⇒ 89 顺位接 **v14**，
     * 迁移链 `12 → 13 → 14` 一路接上、不跳号也不抢号（号归口见 `docs/在飞.md`）。
     * 新装（onCreate 也调它）与 v13 及更早的库升级走**同一条**路径。
     *
     * 老库可读性：升上来只多四个有默认值的账列，`memory` 的 id／标题／**正文一列都不动**
     * （派工单红线「不自动删除用户记忆」的最硬形态——连写都不写）；老行默认满分＝暂不扣分。
     */
    private fun applyV14Migration(db: SQLiteDatabase) {
        val cols = tableColumns(db, "memory")
        BaopuSql.memoryQualityColumnsStmt(cols).forEach { stmt ->
            if (stmt.args.isEmpty()) db.execSQL(stmt.sql) else db.execSQL(stmt.sql, stmt.args)
        }
    }

    /**
     * v14 → v15 迁移体（第一百一十七批）：`run_queue` 表 ＋ 索引，全是 `IF NOT EXISTS`
     * ⇒ 幂等（照 v13 run_trace 房型）。新安装（onCreate 也调它）与 v14 及更早的库升级
     * 走**同一条**路径。
     *
     * 老库可读性：升上来只多一张空表；`message`/`session`/`run_log` 一列不动 ⇒ 老会话照开。
     */
    private fun applyV15Migration(db: SQLiteDatabase) {
        BaopuSql.RUN_QUEUE_DDL.forEach { db.execSQL(it) }
    }

    /**
     * v15 → v16 迁移体（第一百二十七批）：`sub_task` 表 ＋ 两个索引，全是 `IF NOT EXISTS`
     * ⇒ 幂等（照 v15 run_queue 房型）。新安装（onCreate 也调它）与 v15 及更早的库升级
     * 走**同一条**路径。
     *
     * 老库可读性：升上来只多一张空表；`message`/`session`/`run_log` 一列不动 ⇒ 老会话照开。
     */
    private fun applyV16Migration(db: SQLiteDatabase) {
        BaopuSql.SUB_TASK_DDL.forEach { db.execSQL(it) }
    }

    /**
     * v17 迁移体（147 批）：`diary_index` 情景记忆索引（**只是加速——文件是真相**，
     * 索引删掉后从「记忆/日记」目录下的日文件全量重建，见 [DiaryIndexStore.rebuild]）。
     *
     * ⚠️ DDL 放在本迁移体旁而不是文件头 `BaopuSql`（:24）：裸线程对账单把
     * `zd-baopu-db` 创建点钉在 **:1370**，`BaopuSql` 在它之上，往里加行会平移冻结点；
     * 迁移区在冻结点之下，加行零位移（87 批「迁移体落在创建点之后」同款手法）。
     */
    private fun applyV17Migration(db: SQLiteDatabase) {
        DIARY_DDL.forEach { db.execSQL(it) }
    }

    /** `diary_index` DDL（v17）。不存 summary/正文与产物清单——那些以日记文件为真相。 */
    private val DIARY_DDL = listOf(
        """CREATE TABLE IF NOT EXISTS diary_index(
            id TEXT PRIMARY KEY,
            date TEXT NOT NULL,
            session_id INTEGER NOT NULL,
            time_from INTEGER NOT NULL,
            time_to INTEGER NOT NULL,
            title TEXT NOT NULL,
            evidence TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )""",
    )

    /**
     * v18 迁移体（157 补充，用户 2026-10-23）：工作区外访问的**会话级授权**与**逐条审计**。
     *
     * 两张表（`workspace_grant` / `workspace_audit`）＋两条索引，DDL 本体在
     * [WorkspaceGrantSql.DDL]（同包另一个文件）。为什么 DDL 不放本文件：`zd-baopu-db`
     * 创建点冻在 **:1370**，`BaopuSql` 在它之上，往里加行会平移对账单——本迁移体与
     * v17 同一手法，落在创建点**之下**，加行零位移。
     *
     * 老库可读性：升上来只多两张空表；`session`/`message`/`run_log` 一列不动 ⇒ 老会话照开，
     * 老用户第一次越界照旧被问一次（没有历史放行行 ＝ 没批过）。
     */
    private fun applyV18Migration(db: SQLiteDatabase) {
        WorkspaceGrantSql.DDL.forEach { db.execSQL(it) }
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

        /** v5（刀 3 A 段）：session 加 pending 三列（确认制跨进程续）。v4 = 刀 3 B 段（run_log 加 reasoning_tokens）。v3 = 刀 2（资料库两表）。v2 = 刀 0b memory。
         *  v7 = 第十九批（session 封卷两列）。v8 = 第二十批（session 工作区列）。
         *  v9 = 第三十八批 §9（`artifact` 产物台账表 ＋ (workspace, rel_path) 唯一索引）。
         *  v11 = 第五十七批（`skill_event` 技能事件表：use 留痕 ＋ 采纳/丢弃留痕）。
         *  v12 = 第八十五批（同一张表加效果信号四列 outcome／source／skill_version／reason_code，
         *  全部可空＝老行不回填；kind='effect' 的行只存枚举名／技能名／正文摘要／时刻）。
         *  v13 = 第八十七批（`run_trace` 运行轨迹表：一次运行一行一个边界，只存枚举/计数/hash，
         *  **不存正文**；版本号让号说明见 `RUN_TRACE_DDL` 上方注释）。
         *  v14 = 第八十九批（`memory` 质量四列 `score`／`quality_flags`／`merged_from`／`scored_at`，
         *  全部 `NOT NULL` 带默认值 ⇒ 老行不回填、正文一列不动；默认满分＝暂不扣分）。
         *  v15 = 第一百一十七批（`run_queue` 会话内任务队列：只存文本/相对图片路径/时刻，
         *  开跑事务删行；带 `ON DELETE CASCADE`）。
         *  v16 = 第一百二十七批（`sub_task` 多 Agent 协作审计表：父子 id/状态/尝试/
         *  步数/起止/原因/结果摘要，带 `ON DELETE CASCADE` 与 task_id UNIQUE）。
         *  v17 = 第一百四十七批（`diary_index` 情景记忆索引：文件是真相，索引只是加速）。
         *  v18 = 157 补充（`workspace_grant` 会话级放行 ＋ `workspace_audit` 越界审计：
         *  工作区外授权从「一次运行问一次」改成「一次会话问一次」，按 sessionId 落库）。 */
        const val VERSION = 18

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

/**
 * 确认制 pending 的存取（刀 3 A 段）。
 *
 * 为什么抽接口：引擎的 `ConfirmFlow` 要 JVM 直测（判据 J1–J5 全压它），而
 * SQLiteOpenHelper 进不了 JVM ⇒ 接口留在这里（baopu 包），Android 侧实现
 * [SessionPendingStore]，单测用内存假件。
 *
 * **没有 save**：进 AWAIT_CONFIRM 的写必须与 run_log 的 AwaitConfirm 行**同事务**
 * （派工单 §3.3「同事务写」）——那是引擎的 [com.example.zhengdao.baopu.agent.RunEngine]
 * 事务，不是这里能代劳的单列 UPDATE。本接口只管事务外的两件事：清与读。
 */
internal interface PendingStore {
    /** 批准/拒绝/超时后清空（脱离引擎事务的单列更新；执行后立刻清，窗口毫秒级）。 */
    fun clear(sessionId: Long)

    /** 读 pending（没有 ⇒ null）。 */
    fun load(sessionId: Long): PendingRow?
}

/** pending 一行（[PendingRow.tool] 空 = 没有 pending）。 */
internal data class PendingRow(val tool: String, val argsJson: String, val at: Long)

/**
 * [CompressionStore] 的 BaopuDb 实现（第十三批 · 薄壳，照 [SessionPendingStore] 房型）。
 *
 * 写走 [BaopuDb.write] 的 Future 并 `.get()` 等真落库（与 pending 同纪律：
 * Future 不 get 等于没写）；读走调用方线程（WAL 下不挡写）。
 *
 * 留痕两行都进 `run_log` 的 **tool 列**（`compression_failed` /
 * `compression_skipped_degraded`）：不新增 state 枚举值，蓝总管按 tool 列就能筛出来。
 */
internal class SessionCompressionStore(private val db: BaopuDb) : CompressionStore {

    override fun insert(
        sessionId: Long,
        record: CompressionRecord,
        sourceTokens: Int,
        summaryTokens: Int,
    ) {
        val stmt = BaopuSql.insertCompression(sessionId, record, sourceTokens, summaryTokens)
        runCatching { db.write { d -> d.execSQL(stmt.sql, stmt.args) }.get() }
            .onFailure {
                com.example.zhengdao.rootfs.RunLog.log(
                    "抱朴压缩：落库失败 ${it.javaClass.simpleName}: ${it.message}",
                )
            }
    }

    override fun latest(sessionId: Long): CompressionRecord? = db.read { d ->
        val stmt = BaopuSql.latestCompression(sessionId)
        d.rawQuery(stmt.sql, stmt.args).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                CompressionRecord(
                    summary = c.getString(2),
                    compressedIds = BaopuSql.idsFromJson(c.getString(3)),
                    createdAt = c.getLong(4),
                    model = c.getString(5),
                )
            }
        }
    }

    override fun lastFailedStep(sessionId: Long): Int? = db.read { d ->
        val stmt = BaopuSql.lastCompressionFailedStep(sessionId)
        d.rawQuery(stmt.sql, stmt.args).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
    }.takeIf { it != null && it >= 0 }

    override fun markFailed(sessionId: Long, step: Int, code: Int, message: String) {
        appendCompressionLog(
            sessionId, step, tool = CompressionRunner.FAIL_REASON,
            ok = false, err = "code=$code $message",
        )
    }

    override fun markSkipped(sessionId: Long, step: Int, reason: String) {
        appendCompressionLog(
            sessionId, step, tool = reason,
            ok = true, err = "",
        )
    }

    private fun appendCompressionLog(
        sessionId: Long,
        step: Int,
        tool: String,
        ok: Boolean,
        err: String,
    ) {
        val stmt = BaopuSql.insertRunLog(
            sessionId = sessionId,
            step = step,
            state = "Compressing",
            model = "",
            tokensIn = 0,
            tokensOut = 0,
            tokensInEst = 0,
            tokensOutEst = 0,
            tool = tool,
            ok = ok,
            latencyMs = 0,
            err = err,
            createdAt = System.currentTimeMillis(),
        )
        runCatching { db.write { d -> d.execSQL(stmt.sql, stmt.args) }.get() }
            .onFailure {
                com.example.zhengdao.rootfs.RunLog.log(
                    "抱朴压缩：留痕失败 ${it.javaClass.simpleName}: ${it.message}",
                )
            }
    }
}

/**
 * [PendingStore] 的 BaopuDb 实现（薄壳：清走 [BaopuDb.write] 的 Future 并 .get()
 * 等真落库；读走调用方线程，WAL 下不挡写）。
 */
internal class SessionPendingStore(private val db: BaopuDb) : PendingStore {

    override fun clear(sessionId: Long) {
        val stmt = BaopuSql.clearPending(sessionId)
        runCatching { db.write { d -> d.execSQL(stmt.sql, stmt.args) }.get() }
            .onFailure {
                com.example.zhengdao.rootfs.RunLog.log(
                    "抱朴 pending：清空失败 ${it.javaClass.simpleName}: ${it.message}",
                )
            }
    }

    override fun load(sessionId: Long): PendingRow? = db.read { d ->
        val stmt = BaopuSql.loadPending(sessionId)
        d.rawQuery(stmt.sql, stmt.args).use { c ->
            if (c.moveToFirst() && !c.isNull(0) && c.getString(0).isNotBlank()) {
                PendingRow(
                    tool = c.getString(0),
                    argsJson = if (c.isNull(1)) "" else c.getString(1),
                    at = if (c.isNull(2)) 0L else c.getLong(2),
                )
            } else {
                null
            }
        }
    }
}

/**
 * 情景记忆**索引**的 BaopuDb 实现（147 阶段①，v17）——薄壳同 [SessionPendingStore]：
 * 写走 [BaopuDb.write] 的 Future 并 `.get()`，读走调用方线程（WAL 下不挡写）。
 *
 * **索引只是加速，文件是真相**（`<工作区根>/记忆/日记/` 下的日文件，真相在 [DiaryStore]）：
 * 索引可随时 [rebuild]——把表清空后按 [DiaryStore.loadAll] 的条目全量重灌，
 * 判据 19 要求「删索引 → 重建 → 条数与内容逐条一致」。
 *
 * SQL 内联在本类而不进文件头 `BaopuSql`（:24）：裸线程对账单把 `zd-baopu-db`
 * 创建点钉在 :1370，`BaopuSql` 在它之上，加行会平移冻结点（applyV17Migration 旁有同款注释）。
 */
internal class DiaryIndexStore(private val db: BaopuDb) {

    data class Row(
        val id: String,
        val date: String,
        val sessionId: Long,
        val timeFromMs: Long,
        val timeToMs: Long,
        val title: String,
        val evidence: String,
        val createdAt: Long,
    )

    private fun replaceSql(e: DiaryEntry) = Pair(
        "INSERT OR REPLACE INTO diary_index" +
            "(id, date, session_id, time_from, time_to, title, evidence, created_at) " +
            "VALUES (?,?,?,?,?,?,?,?)",
        arrayOf<Any>(
            e.id, e.date, e.sessionId, e.timeFromMs, e.timeToMs, e.title,
            e.evidence.name.lowercase(), e.createdAt,
        ),
    )

    /** upsert 一条索引行（与 [DiaryStore.upsert] 同一事务口径：文件写成功后调它）。 */
    fun replace(e: DiaryEntry) {
        val (sql, args) = replaceSql(e)
        runCatching { db.write { d -> d.execSQL(sql, args) }.get() }
            .onFailure {
                com.example.zhengdao.rootfs.RunLog.log(
                    "抱朴日记：索引写入失败 ${it.javaClass.simpleName}: ${it.message}",
                )
            }
    }

    /** 删一条索引行（真删的索引半边；文件半边在 [DiaryStore.deleteById]）。 */
    /** @return 删掉的行数（0 = 本来就没有）；失败不抛（记运行日志），调用方按 0 处理。 */
    fun deleteById(id: String): Int =
        runCatching {
            db.write { d -> d.delete("diary_index", "id = ?", arrayOf(id)) }.get()
        }.onFailure {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴日记：索引删除失败 ${it.javaClass.simpleName}: ${it.message}",
            )
        }.getOrDefault(0)

    fun count(): Int = db.read { d ->
        d.rawQuery("SELECT COUNT(*) FROM diary_index", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun readAll(): List<Row> = db.read { d ->
        val out = mutableListOf<Row>()
        d.rawQuery(
            "SELECT id, date, session_id, time_from, time_to, title, evidence, created_at " +
                "FROM diary_index ORDER BY created_at, id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Row(
                    id = c.getString(0),
                    date = c.getString(1),
                    sessionId = c.getLong(2),
                    timeFromMs = c.getLong(3),
                    timeToMs = c.getLong(4),
                    title = c.getString(5),
                    evidence = c.getString(6),
                    createdAt = c.getLong(7),
                )
            }
        }
        out
    }

    /**
     * 全量重建（判据 19）：清空索引 → 按给定条目（来自 [DiaryStore.loadAll]，文件是真相）
     * 逐条重灌。单事务；文件读取在调用方（本类不碰盘上日记文件）。
     */
    fun rebuild(entries: List<DiaryEntry>) {
        db.write { d ->
            d.execSQL("DELETE FROM diary_index")
            entries.forEach { e ->
                val (sql, args) = replaceSql(e)
                d.execSQL(sql, args)
            }
        }.get()
    }
}
