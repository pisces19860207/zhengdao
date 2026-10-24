// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 157 补充（用户 2026-10-23）判据测试：工作区外授权的**会话粒度**。
//
// 用户四条原话 → 这里的四条判据：
// 1. 一次会话问一次 ⇒ 同会话放行、换会话重问（`账本_*`）；
// 2. 按 sessionId 持久化（重启不再问）⇒ 落库语句 + 缓存丢了从账本读回；
// 3. 设置页清单可一键撤销 ⇒ [WorkspaceGrants.list] / [WorkspaceGrants.revoke] 契约；
// 4. 每次越界逐条审计（时间/路径/会话）⇒ 审计记录三列齐备（管线层那半在
//    `WorkspaceOpenPipelineTest`）。
//
// 库版账本走不了 JVM 单测（本仓无 Robolectric，SQLite 进不来）⇒ 按仓库惯例钉**源码文本**：
// DDL 两张表、幂等、VERSION=18、迁移链两处、DDL 文件落在裸线程冻结点之外（零位移）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspaceGrantTest {

    // ── 纯规则 ────────────────────────────────────────────────────────────

    @Test
    fun `规则_根键按canonicalPath归一`() {
        val root = File("build/tmp/ws-grant-rule").apply { mkdirs() }
        assertEquals("同一目录两种写法必须算同一个键", WorkspaceGrantRules.rootKey(root), WorkspaceGrantRules.rootKey(File(root, ".")))
        val sibling = File(root.parentFile, "ws-grant-rule-2")
        assertFalse("同前缀兄弟目录不许撞键", WorkspaceGrantRules.rootKey(root) == WorkspaceGrantRules.rootKey(sibling))
    }

    @Test
    fun `规则_会话标签与清单标题都不编数`() {
        assertEquals("未知会话不编 id", "未知", WorkspaceGrantRules.sessionLabel(WorkspaceGrantRules.NO_SESSION))
        assertEquals("#12", WorkspaceGrantRules.sessionLabel(12L))
        assertEquals(
            "没标题就退回「会话 #id」，不编标题",
            "会话 #12",
            WorkspaceGrantRules.rowTitle(WorkspaceGrantRow(12L, "/w", 1L, "  ")),
        )
        assertEquals(
            "有标题就用标题",
            "写小说",
            WorkspaceGrantRules.rowTitle(WorkspaceGrantRow(12L, "/w", 1L, "写小说")),
        )
    }

    @Test
    fun `规则_审计行带齐时间会话路径与处置`() {
        val a = WorkspaceGrantAudit(
            at = 1_700_000_000_000L,
            sessionId = 7L,
            rootPath = "/data/ws",
            path = "/data/elsewhere/note.md",
            decision = OutsideDecision.ASKED,
            tool = "read_file",
        )
        val line = WorkspaceGrantRules.auditLine(a)
        assertTrue("可 grep 的锚：$line", line.startsWith("[越界]"))
        assertTrue("会话：$line", line.contains("会话 #7"))
        assertTrue("路径：$line", line.contains("/data/elsewhere/note.md"))
        assertTrue("处置：$line", line.contains(OutsideDecision.ASKED.text))
        assertTrue("工具：$line", line.contains("read_file"))
    }

    // ── 账本契约（内存版＝库版的语义对照；库版只钉 SQL 文本）──────────────

    @Test
    fun `账本契约_放行幂等_撤销只删那一个会话`() {
        val store = InMemoryWorkspaceGrantStore()
        store.grant(1L, "/w", 100L)
        store.grant(1L, "/w", 200L) // 重复批 ⇒ 只更新时间，不新增行
        store.grant(2L, "/w", 150L)
        assertEquals(2, store.list().size)
        assertTrue(store.isGranted(1L, "/w"))
        assertFalse("换根不算批过", store.isGranted(1L, "/other"))
        assertEquals(1, store.revoke(1L))
        assertFalse(store.isGranted(1L, "/w"))
        assertTrue("撤销不影响别的会话", store.isGranted(2L, "/w"))
        assertEquals(0, store.revoke(999L))
    }

    @Test
    fun `账本契约_清单按批准时间倒序`() {
        val store = InMemoryWorkspaceGrantStore()
        store.grant(1L, "/w", 100L)
        store.grant(2L, "/w", 300L)
        store.grant(3L, "/w", 200L)
        assertEquals(listOf(2L, 3L, 1L), store.list().map { it.sessionId })
    }

    @Test
    fun `账本契约_审计逐条保留不合并`() {
        val store = InMemoryWorkspaceGrantStore()
        repeat(3) { i ->
            store.audit(WorkspaceGrantAudit(i.toLong(), 5L, "/w", "/out/$i", OutsideDecision.ALREADY, "read_file"))
        }
        assertEquals("三条越界就是三条审计", 3, store.audits().size)
    }

    // ── 源码级门禁：v18 迁移与 DDL（无 Robolectric 的惯例做法）────────────

    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"), File("app/$rel"))
        return cands.firstOrNull { it.isFile } ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    private val dbSrc: String by lazy { repoFile("src/main/java/com/example/zhengdao/baopu/BaopuDb.kt").readText() }
    private val grantSrc: String by lazy { repoFile("src/main/java/com/example/zhengdao/baopu/WorkspaceGrant.kt").readText() }

    /** 剥整行 `//` 注释（防注释里的反例词误伤，房规）。 */
    private val dbCode: String by lazy { dbSrc.replace(Regex("(?m)^[ \\t]*//.*$"), "") }

    @Test
    fun `门禁_V18两张表与幂等DDL`() {
        val ddl = WorkspaceGrantSql.DDL.joinToString("\n")
        assertTrue("必须建 workspace_grant", ddl.contains("CREATE TABLE IF NOT EXISTS workspace_grant"))
        assertTrue("必须建 workspace_audit", ddl.contains("CREATE TABLE IF NOT EXISTS workspace_audit"))
        assertTrue("全部 IF NOT EXISTS（幂等：新装与升级同一条路）", WorkspaceGrantSql.DDL.all { it.contains("IF NOT EXISTS") })
        listOf("session_id", "root_path", "granted_at").forEach {
            assertTrue("放行表缺列 $it", ddl.contains(it))
        }
        listOf("at", "session_id", "path", "decision", "tool").forEach {
            assertTrue("审计表缺列 $it（时间/路径/会话是用户点名的三列）", ddl.contains(it))
        }
        assertTrue(
            "审计表要写上限（手机库不该无限长）",
            WorkspaceGrantSql.PRUNE_AUDIT.contains(WorkspaceGrantSql.AUDIT_CAP.toString()),
        )
    }

    @Test
    fun `门禁_VERSION18且迁移链两处`() {
        assertTrue("BaopuDb 的 VERSION 应为 19（v19 = 172 L4 玄览页签快照）", dbCode.contains("const val VERSION = 19"))
        val chain = Regex("applyV18Migration\\(db\\)").findAll(dbCode).count()
        assertTrue("迁移链应有两处调用（onCreate 全量链 ＋ oldVersion 升级链），实测 $chain", chain >= 2)
        assertTrue("应有迁移体定义", dbCode.contains("private fun applyV18Migration"))
        assertTrue("迁移体要调 DDL", dbCode.contains("WorkspaceGrantSql.DDL.forEach"))
    }

    @Test
    fun `门禁_DDL与迁移体都在裸线程冻结点之下_零位移`() {
        val freeze = dbCode.lines().indexOfFirst { it.contains("zd-baopu-db") } + 1
        val migration = dbCode.lines().indexOfFirst { it.contains("private fun applyV18Migration") } + 1
        assertTrue("冻结点应存在（对账单锚）", freeze > 0)
        assertTrue("v18 迁移体（:$migration）必须在 zd-baopu-db（:$freeze）之下", migration > freeze)
        assertFalse(
            "DDL 不许写回 BaopuSql（文件头在冻结点之上，加行会平移 33 条对账单）",
            dbCode.contains("CREATE TABLE IF NOT EXISTS workspace_grant"),
        )
        assertTrue("DDL 应在 WorkspaceGrant.kt 里", grantSrc.contains("CREATE TABLE IF NOT EXISTS workspace_grant"))
    }

    // ── 双语：清单与撤销的文案两边都有（缺一个键就是英文用户看中文/空）────────

    @Test
    fun `门禁_清单与撤销文案双语齐备`() {
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        listOf(
            "baopu_ws_grant_card",
            "baopu_ws_grant_empty",
            "baopu_ws_grant_loading",
            "baopu_ws_grant_revoke",
            "baopu_ws_grant_root",
            "baopu_ws_grant_revoked",
        ).forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
        assertTrue(
            "开关说明要改成「每会话一次」（156 写的是每运行）",
            zh.contains("每个会话只问一次") && en.contains("once per session"),
        )
    }

    // ── 负例：账本读不到时 fail closed，不许静默放行 ─────────────────────

    @Test
    fun `负例_没有会话身份时清单与放行都不成立`() {
        WorkspaceGrants.resetForTest()
        try {
            assertEquals(WorkspaceGrantRules.NO_SESSION, WorkspaceGrants.session())
            assertFalse("无会话 ⇒ 永远要问", WorkspaceGrants.granted(File("build/tmp/ws-grant-neg")))
            WorkspaceGrants.grant(File("build/tmp/ws-grant-neg"))
            assertTrue("记不上就要么空清单，不许编一行出来", WorkspaceGrants.list().isEmpty())
        } finally {
            WorkspaceGrants.resetForTest()
        }
    }
}
