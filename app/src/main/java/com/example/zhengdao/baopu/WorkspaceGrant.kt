// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 157 补充（用户 2026-10-23 定稿）：工作区外的授权粒度 = **一次会话问一次**。
//
// 156 批交出的是「一次运行问一次」：账本放进程内存、引擎每轮入口清空 ⇒ 同一个会话里
// 第二条消息再碰工作区外，用户又要被问一遍；App 重启更是从零开始。用户裁定改成会话级，
// 四条原话逐条落地：
// 1. 工作区外授权不是「一次运行问一次」，是**一次会话问一次**；
// 2. 同意后按 sessionId 持久化（重启 App 不再问），只有新开会话才重新问；
// 3. 设置页给「已放行工作区外的会话」清单，可一键撤销；撤销后下次越界重新问；
// 4. 每次越界仍逐条记审计（时间/路径/会话）。
//
// 分工（与 156 的三层判据对齐，别混）：
// - [`workspaceAccessVerdict`]（WorkspaceAccess.kt）：**四象限纯函数**——能不能越界；
// - 本文件：**账本**——谁（哪个会话）已经被放行、放行到哪个根，以及越界审计行；
// - `rootfs/PathGuard`：**禁区硬拒**（备份区/回收站/原件区），授权翻不了它。
//
// 落库 vs 内存：生产装配层装 [SessionWorkspaceGrantStore]（SQLite，跨进程存活）；
// 没接线（纯 JVM 直测、装配层之外的直调）退回 [InMemoryWorkspaceGrantStore]——
// 那时「重启不再问」这条**不成立**，但判据本身照样可测，也不会静默放行。
package com.example.zhengdao.baopu

import java.io.File
import java.util.Collections

/**
 * 一次越界的处置（写进 `workspace_audit.decision`，短标签可 grep）。
 *
 * 为什么四个值而不是「允许/拒绝」两个：用户要的是「每次越界逐条记审计」，
 * 「这次是第一次问、还是老早批过」正是事后复盘要看的那一层，两个值分不出来。
 */
internal enum class OutsideDecision(val tag: String, val text: String) {

    /** 工作区外、开关开、本会话还没批过 ⇒ 弹确认卡问用户。 */
    ASKED("ask", "询问用户"),

    /** 用户刚在确认卡上点了批准 ⇒ 记账（本会话自此放行整个根）。 */
    APPROVED("ok", "用户新批准"),

    /** 本会话早先批过（可能是上个进程批的，从库里读出来的）⇒ 直接执行。 */
    ALREADY("already", "本会话已放行"),

    /** 设置里关掉了「允许访问工作区外」⇒ 原文拒绝（授权翻不了这一条）。 */
    DENIED("denied", "开关关，拒绝"),
}

/** 一条越界审计（时间/路径/会话是用户点名的三列，其余两列是可复盘的补充）。 */
internal data class WorkspaceGrantAudit(
    val at: Long,
    val sessionId: Long,
    val rootPath: String,
    val path: String,
    val decision: OutsideDecision,
    val tool: String,
)

/** 设置页「已放行工作区外的会话」清单的一行。`sessionTitle` 缺失（会话已删/老库）时为空串。 */
internal data class WorkspaceGrantRow(
    val sessionId: Long,
    val rootPath: String,
    val grantedAt: Long,
    val sessionTitle: String,
)

/**
 * 放行账本与审计的存取口（一个接口两件事，因为它们是**同一次越界**的两面：
 * 「批了就记一行放行、越界就记一行审计」，拆两个接口只会让装配层多一个可能漏装的点）。
 */
internal interface WorkspaceGrantStore {

    /** 这个会话是否已放行这个根（生产走库，重启后仍为真）。 */
    fun isGranted(sessionId: Long, rootPath: String): Boolean

    /** 记一条放行（幂等：同一会话同一个根重复记只更新时间戳）。 */
    fun grant(sessionId: Long, rootPath: String, at: Long)

    /** 撤销某会话的全部放行，返回撤掉的行数。 */
    fun revoke(sessionId: Long): Int

    /** 设置页清单（按批准时间倒序）。 */
    fun list(): List<WorkspaceGrantRow>

    /** 记一条越界审计。 */
    fun audit(a: WorkspaceGrantAudit)
}

/** 未接线环境（纯 JVM 直测）用的内存账本——语义与库版一致，只是活不过进程。 */
internal class InMemoryWorkspaceGrantStore : WorkspaceGrantStore {

    private data class Key(val sessionId: Long, val rootPath: String)

    private val grants: MutableMap<Key, Long> = Collections.synchronizedMap(mutableMapOf())
    private val audits: MutableList<WorkspaceGrantAudit> = Collections.synchronizedList(mutableListOf())

    override fun isGranted(sessionId: Long, rootPath: String): Boolean =
        grants.containsKey(Key(sessionId, rootPath))

    override fun grant(sessionId: Long, rootPath: String, at: Long) {
        grants[Key(sessionId, rootPath)] = at
    }

    override fun revoke(sessionId: Long): Int = synchronized(grants) {
        val victims = grants.keys.filter { it.sessionId == sessionId }
        victims.forEach { grants.remove(it) }
        victims.size
    }

    override fun list(): List<WorkspaceGrantRow> = synchronized(grants) {
        grants.entries
            .sortedByDescending { it.value }
            .map { WorkspaceGrantRow(it.key.sessionId, it.key.rootPath, it.value, "") }
    }

    override fun audit(a: WorkspaceGrantAudit) {
        audits.add(a)
    }

    /** 测试读数口：这一轮到底记了几条、记的是什么（生产不用）。 */
    fun audits(): List<WorkspaceGrantAudit> = synchronized(audits) { audits.toList() }

    /** 测试读数口：手工塞一条放行（模拟「上个进程批过」）。 */
    fun seed(sessionId: Long, rootPath: String, at: Long) = grant(sessionId, rootPath, at)
}

/**
 * SQL 常量（**放本文件而不是 `BaopuDb.BaopuSql`**）。
 *
 * 为什么：裸线程对账单把 `zd-baopu-db` 创建点钉死在 `BaopuDb.kt:1370`，
 * `BaopuSql` 在它之上（:24）——往里加行会把全仓 33 条对账单一起平移。
 * 迁移体与 DDL 落在创建点之下是 87 批定的房型（v17 也这么干），本文件与 BaopuDb 无关，
 * 天然零位移。
 */
internal object WorkspaceGrantSql {

    const val TABLE_GRANT = "workspace_grant"
    const val TABLE_AUDIT = "workspace_audit"

    /** 审计表只留最近 [AUDIT_CAP] 条（越界是低频事件，但手机库不该无限长）。 */
    const val AUDIT_CAP = 500

    /**
     * v18 DDL（幂等：全是 `IF NOT EXISTS`，新装与升级走同一条路径）。
     *
     * 主键 `(session_id, root_path)`：授权是「会话 × 工作区根」的二维事实。
     * 为什么带 root_path：工作区可以在设置里改，换了根还是同一句话——
     * 「这个会话放行这个根」，旧根上的放行不该自动延伸到新根。
     */
    val DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS workspace_grant(
          session_id INTEGER NOT NULL,
          root_path TEXT NOT NULL,
          granted_at INTEGER NOT NULL,
          PRIMARY KEY(session_id, root_path)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS workspace_audit(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          at INTEGER NOT NULL,
          session_id INTEGER NOT NULL,
          root_path TEXT NOT NULL,
          path TEXT NOT NULL,
          decision TEXT NOT NULL,
          tool TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_workspace_grant_session ON workspace_grant (session_id)",
        "CREATE INDEX IF NOT EXISTS idx_workspace_audit_at ON workspace_audit (at DESC)",
    )

    const val UPSERT_GRANT =
        "INSERT OR REPLACE INTO workspace_grant(session_id, root_path, granted_at) VALUES(?, ?, ?)"

    const val SELECT_IS_GRANTED =
        "SELECT 1 FROM workspace_grant WHERE session_id = ? AND root_path = ? LIMIT 1"

    const val SELECT_GRANTS =
        "SELECT g.session_id, g.root_path, g.granted_at, IFNULL(s.title, '') " +
            "FROM workspace_grant g LEFT JOIN session s ON s.id = g.session_id " +
            "ORDER BY g.granted_at DESC"

    const val INSERT_AUDIT =
        "INSERT INTO workspace_audit(at, session_id, root_path, path, decision, tool) VALUES(?, ?, ?, ?, ?, ?)"

    const val PRUNE_AUDIT =
        "DELETE FROM workspace_audit WHERE id NOT IN " +
            "(SELECT id FROM workspace_audit ORDER BY id DESC LIMIT $AUDIT_CAP)"
}

/**
 * 生产账本：SQLite 落库（`baopu.db`，v18 两张表）。
 *
 * 为什么库而不是 SharedPreferences：清单要带**会话标题**（LEFT JOIN `session`），
 * 撤销要按会话批量删，审计要按时间倒序——这三件事都是 SQL 的活。
 * 异常一律吞成「没批过/没撤掉」：账本读不到时退化成 156 批的老行为（再问一次），
 * 不会静默放行（fail closed）。
 */
internal class SessionWorkspaceGrantStore(private val db: BaopuDb) : WorkspaceGrantStore {

    override fun isGranted(sessionId: Long, rootPath: String): Boolean = runCatching {
        db.read { d ->
            d.rawQuery(WorkspaceGrantSql.SELECT_IS_GRANTED, arrayOf(sessionId.toString(), rootPath))
                .use { c -> c.moveToFirst() }
        }
    }.getOrDefault(false)

    override fun grant(sessionId: Long, rootPath: String, at: Long) {
        runCatching {
            db.write { d ->
                d.execSQL(WorkspaceGrantSql.UPSERT_GRANT, arrayOf<Any?>(sessionId, rootPath, at))
            }.get()
        }
    }

    override fun revoke(sessionId: Long): Int = runCatching {
        db.write { d ->
            d.delete(WorkspaceGrantSql.TABLE_GRANT, "session_id = ?", arrayOf(sessionId.toString()))
        }.get()
    }.getOrDefault(0)

    override fun list(): List<WorkspaceGrantRow> = runCatching {
        db.read { d ->
            d.rawQuery(WorkspaceGrantSql.SELECT_GRANTS, null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            WorkspaceGrantRow(
                                sessionId = c.getLong(0),
                                rootPath = c.getString(1) ?: "",
                                grantedAt = c.getLong(2),
                                sessionTitle = c.getString(3) ?: "",
                            ),
                        )
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    override fun audit(a: WorkspaceGrantAudit) {
        runCatching {
            db.write { d ->
                d.execSQL(
                    WorkspaceGrantSql.INSERT_AUDIT,
                    arrayOf<Any?>(a.at, a.sessionId, a.rootPath, a.path, a.decision.tag, a.tool),
                )
                d.execSQL(WorkspaceGrantSql.PRUNE_AUDIT)
            }.get()
        }
    }
}

/** 账本纯规则（不碰 Android/库，JVM 直测）：根归一、审计行文本、清单标题兜底。 */
internal object WorkspaceGrantRules {

    /** 会话未知时的哨兵（引擎没喂过 sessionId 的环境：直测、绕过引擎的直调）。 */
    const val NO_SESSION = -1L

    /**
     * 根的账本键：`canonicalPath` 归一（`…/ws/.` 与 `…/ws` 算同一个根——
     * 与 156 批 `WorkspaceGrants` 的归一口径逐字一致；规范化失败退回绝对路径，不抛）。
     */
    fun rootKey(root: File): String =
        runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)

    /** 审计行（RunLog 用；库里的三列是权威，这行是给人实时看的）。 */
    fun auditLine(a: WorkspaceGrantAudit): String =
        "[越界] 会话 ${sessionLabel(a.sessionId)} ${a.decision.text}：" +
            "${a.path}（工作区 ${a.rootPath}，工具 ${a.tool.ifBlank { "?" }}）"

    /** 会话在界面/日志里的写法：`#12`；未知会话写`未知`（不编 id）。 */
    fun sessionLabel(sessionId: Long): String = if (sessionId <= 0) "未知" else "#$sessionId"

    /** 设置页一行的主标题：会话标题优先，空标题退回 `会话 #id`（不编标题）。 */
    fun rowTitle(row: WorkspaceGrantRow): String =
        row.sessionTitle.trim().ifBlank { "会话 ${sessionLabel(row.sessionId)}" }
}

/**
 * 进程级授权账本（**一次会话问一次**）。
 *
 * 与 156 批的差别只有一条：`beginRun()` 清账 → `enterSession(id)` 切身份、账不清。
 * 同会话跨运行仍放行（库里有行）；新会话没有行 ⇒ 重新问；撤销删行 ⇒ 下次越界重新问。
 *
 * 为什么当前会话是进程级字段：管线是进程级单例、工具层的自检在**执行线程**上现读，
 * 「这一轮是哪个会话」只有引擎知道（与 `registry.currentSessionId`、`planSessionId`
 * 同一个房型——每轮入口喂一次）。全仓同时只有一个活跃会话（`ActiveSessionGate`）。
 */
internal object WorkspaceGrants {

    /** 落库实现（生产由 `BaopuRuntime` 装 [SessionWorkspaceGrantStore]）。 */
    @Volatile
    private var store: WorkspaceGrantStore = InMemoryWorkspaceGrantStore()

    /** 当前会话（引擎每轮入口喂；未喂过 = [WorkspaceGrantRules.NO_SESSION]）。 */
    @Volatile
    private var sessionId: Long = WorkspaceGrantRules.NO_SESSION

    /** 已确认放行的账本键 `"<sessionId>|<rootKey>"`（省掉每次判据一次库里读）。 */
    private val cache: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** 装账本（生产装配层调用）。顺手清缓存：换了库，旧缓存不再可信。 */
    fun install(s: WorkspaceGrantStore) {
        store = s
        cache.clear()
    }

    /** 切会话身份（引擎每轮入口/取门时调）。**不清放行**——同会话跨运行仍算批过。 */
    fun enterSession(id: Long) {
        sessionId = id
    }

    /** 当前会话 id（审计/日志用；未知会话为 [WorkspaceGrantRules.NO_SESSION]）。 */
    fun session(): Long = sessionId

    /** 本会话是否已放行这个根（先查缓存，未命中查库并回填）。 */
    fun granted(root: File): Boolean {
        val sid = sessionId
        if (sid <= 0) return false
        val rootKey = WorkspaceGrantRules.rootKey(root)
        val key = cacheKey(sid, rootKey)
        if (cache.contains(key)) return true
        val hit = runCatching { store.isGranted(sid, rootKey) }.getOrDefault(false)
        if (hit) cache.add(key)
        return hit
    }

    /** 用户在确认卡上点了批准 ⇒ 记账（整根放行，按会话持久化）。 */
    fun grant(root: File) {
        val sid = sessionId
        if (sid <= 0) return
        val rootKey = WorkspaceGrantRules.rootKey(root)
        runCatching { store.grant(sid, rootKey, System.currentTimeMillis()) }
            .onSuccess { cache.add(cacheKey(sid, rootKey)) }
    }

    /** 撤销某会话的全部放行（设置页一键撤销）；返回撤掉的行数。 */
    fun revoke(id: Long): Int {
        val n = runCatching { store.revoke(id) }.getOrDefault(0)
        // 缓存整清而不是按前缀删：撤销是低频动作，重建一次比算前缀安全（键里带会话 id，串不了）。
        cache.clear()
        return n
    }

    /** 设置页清单。 */
    fun list(): List<WorkspaceGrantRow> = runCatching { store.list() }.getOrDefault(emptyList())

    /**
     * 记一条越界审计（时间/路径/会话三列由本方法现取，调用方只给「哪个根、哪个路径、
     * 什么处置、哪个工具」）。返回这一条记录，便于调用方把同一内容写进 RunLog。
     */
    fun audit(root: File, path: String, decision: OutsideDecision, tool: String): WorkspaceGrantAudit {
        val a = WorkspaceGrantAudit(
            at = System.currentTimeMillis(),
            sessionId = sessionId,
            rootPath = WorkspaceGrantRules.rootKey(root),
            path = path,
            decision = decision,
            tool = tool,
        )
        runCatching { store.audit(a) }
        return a
    }

    /** 直测收尾：换回内存账本＋清身份＋清缓存（生产不调）。 */
    fun resetForTest() {
        install(InMemoryWorkspaceGrantStore())
        sessionId = WorkspaceGrantRules.NO_SESSION
    }

    private fun cacheKey(sid: Long, rootKey: String): String = "$sid|$rootKey"
}
