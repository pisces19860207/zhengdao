// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BaopuDb 纯 SQL 层单测（刀 0 · 2026-10-16）。
 *
 * ## 为什么只测 SQL 层（重要，先说清楚）
 * 本仓库单测环境**没有 Robolectric**，android.jar 是 stub ⇒ SQLiteOpenHelper
 * 在 JVM 里实例化不了（证据②：全仓既有测试零处直接摸 SQLiteDatabase）。
 * 所以本类钉的是**能在 JVM 钉的全部判据**：表结构、字段、外键级联、CHECK、
 * 索引、每条语句的参数绑定顺序、删会话的语句顺序。
 *
 * **未覆盖（证据等级③，如实记账）**：真实 SQLite 执行行为——级联删除真的联动、
 * PRAGMA foreign_keys 真的生效、WAL 真的并发——这些要等刀 1 有界面后
 * 在真机/集成层验证。判据本身（SQL 文本）在这里是全的。
 */
class BaopuDbTest {

    private fun table(name: String): String =
        BaopuSql.DDL.first { it.contains("CREATE TABLE IF NOT EXISTS $name (") }

    @Test
    fun `五张表齐全`() {
        listOf("session", "message", "part", "run_log", "plan_batch").forEach { name ->
            assertTrue("缺表 $name", BaopuSql.DDL.any { it.contains("CREATE TABLE IF NOT EXISTS $name (") })
        }
    }

    @Test
    fun `session表字段与索引`() {
        val ddl = table("session")
        listOf(
            "id INTEGER PRIMARY KEY AUTOINCREMENT",
            "title TEXT NOT NULL DEFAULT ''",
            "model TEXT NOT NULL DEFAULT ''",
            "provider TEXT NOT NULL DEFAULT ''",
            "created_at INTEGER NOT NULL",
            "updated_at INTEGER NOT NULL",
            "archived INTEGER NOT NULL DEFAULT 0",
            "msg_count INTEGER NOT NULL DEFAULT 0",
            "preview TEXT NOT NULL DEFAULT ''",
        ).forEach { col -> assertTrue("session 缺列：$col", ddl.contains(col)) }
        assertTrue(
            BaopuSql.DDL.any {
                it.contains("idx_session_archived_updated ON session (archived, updated_at DESC)")
            },
        )
    }

    @Test
    fun `message与part的级联外键`() {
        val msg = table("message")
        assertTrue(msg.contains("session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE"))
        assertTrue(msg.contains("seq INTEGER NOT NULL"))
        assertTrue(msg.contains("status TEXT NOT NULL DEFAULT 'streaming'"))
        val part = table("part")
        assertTrue(part.contains("message_id INTEGER NOT NULL REFERENCES message(id) ON DELETE CASCADE"))
        assertTrue(part.contains("kind TEXT NOT NULL"))
        assertTrue(part.contains("payload TEXT NOT NULL DEFAULT ''"))
        assertTrue(BaopuSql.DDL.any { it.contains("idx_message_session_seq ON message (session_id, seq)") })
        assertTrue(BaopuSql.DDL.any { it.contains("idx_part_message_seq ON part (message_id, seq)") })
    }

    @Test
    fun `run_log字段含估算两列`() {
        val ddl = table("run_log")
        listOf(
            "session_id INTEGER NOT NULL REFERENCES session(id)",
            "step INTEGER NOT NULL DEFAULT 0",
            "state TEXT NOT NULL DEFAULT ''",
            "model TEXT NOT NULL DEFAULT ''",
            "tokens_in INTEGER NOT NULL DEFAULT 0",
            "tokens_out INTEGER NOT NULL DEFAULT 0",
            "tokens_in_est INTEGER NOT NULL DEFAULT 0",
            "tokens_out_est INTEGER NOT NULL DEFAULT 0",
            "tool TEXT NOT NULL DEFAULT ''",
            "ok INTEGER NOT NULL DEFAULT 1",
            "latency_ms INTEGER NOT NULL DEFAULT 0",
            "err TEXT NOT NULL DEFAULT ''",
            "created_at INTEGER NOT NULL",
        ).forEach { col -> assertTrue("run_log 缺列：$col", ddl.contains(col)) }
        assertTrue(BaopuSql.DDL.any { it.contains("idx_run_log_session ON run_log (session_id)") })
    }

    @Test
    fun `plan_batch的kind与ops必须有提案两条CHECK`() {
        val ddl = table("plan_batch")
        assertTrue(ddl.contains("kind TEXT NOT NULL CHECK (kind IN ('proposal', 'ops'))"))
        // ops 必须带 proposal_id、proposal 必须不带（同一条布尔等式同时钉两头）
        assertTrue(ddl.contains("CHECK ((kind = 'ops') = (proposal_id IS NOT NULL))"))
        assertTrue(ddl.contains("status TEXT NOT NULL DEFAULT 'pending'"))
        assertTrue(ddl.contains("proposal_id INTEGER REFERENCES plan_batch(id)"))
        assertTrue(ddl.contains("expires_at INTEGER NOT NULL DEFAULT 0"))
        assertTrue(BaopuSql.DDL.any { it.contains("idx_plan_batch_session ON plan_batch (session_id)") })
    }

    @Test
    fun `外键PRAGMA是每连接常量`() {
        assertEquals("PRAGMA foreign_keys = ON", BaopuSql.PRAGMA_FK_ON)
    }

    @Test
    fun `版本与库名`() {
        assertEquals(1, BaopuDb.VERSION)
        assertEquals("baopu.db", BaopuDb.DB_NAME)
    }

    // ── 语句绑定 ──────────────────────────────────────────────────────────

    @Test
    fun `insertSession绑定五参_created与updated同值`() {
        val s = BaopuSql.insertSession("标题", "gpt-4o-mini", "openai-compat", 1_700_000_000_000L)
        assertTrue(s.sql.startsWith("INSERT INTO session (title, model, provider, created_at, updated_at)"))
        assertEquals(listOf("标题", "gpt-4o-mini", "openai-compat", "1700000000000", "1700000000000"), s.args.toList())
    }

    @Test
    fun `归档语句置archived并动updated_at`() {
        val on = BaopuSql.setArchived(7L, true, 123L)
        assertTrue(on.sql.contains("SET archived = ?, updated_at = ?"))
        assertEquals(listOf("1", "123", "7"), on.args.toList())
        val off = BaopuSql.setArchived(7L, false, 123L)
        assertEquals("0", off.args[0])
    }

    @Test
    fun `listSessions按归档过滤`() {
        assertTrue(BaopuSql.listSessions(includeArchived = true).sql.contains("ORDER BY updated_at DESC"))
        assertFalse(BaopuSql.listSessions(includeArchived = true).sql.contains("WHERE"))
        assertTrue(BaopuSql.listSessions(includeArchived = false).sql.contains("WHERE archived = 0"))
    }

    @Test
    fun `insertRunLog十三个参数全绑定`() {
        val s = BaopuSql.insertRunLog(
            sessionId = 1L, step = 2, state = "thinking", model = "m",
            tokensIn = 100, tokensOut = 50, tokensInEst = 98, tokensOutEst = 49,
            tool = "read_file", ok = false, latencyMs = 1234L, err = "boom", createdAt = 999L,
        )
        assertEquals(13, s.args.size)
        assertEquals("1", s.args[0])
        assertEquals("2", s.args[1])
        assertEquals("read_file", s.args[8])
        assertEquals("0", s.args[9]) // ok = false ⇒ "0"
        assertEquals("1234", s.args[10])
        assertEquals("boom", s.args[11])
        assertEquals("999", s.args[12])
    }

    @Test
    fun `提案批与执行批的kind和proposal_id绑定`() {
        val proposal = BaopuSql.insertProposalBatch(1L, "[]", 100L, 50L)
        assertTrue(proposal.sql.contains("'proposal'"))
        assertTrue(proposal.sql.contains("NULL"))
        assertEquals(listOf("1", "[]", "100", "50"), proposal.args.toList())

        val ops = BaopuSql.insertOpsBatch(1L, proposalId = 9L, opsJson = "[{}]", expiresAt = 100L, createdAt = 50L)
        assertTrue(ops.sql.contains("'ops'"))
        assertEquals(listOf("1", "9", "[{}]", "100", "50"), ops.args.toList())
    }

    @Test
    fun `decidePlanBatch更新状态与决定时间`() {
        val s = BaopuSql.decidePlanBatch(3L, "approved", 777L)
        assertTrue(s.sql.contains("SET status = ?, decided_at = ?"))
        assertEquals(listOf("approved", "777", "3"), s.args.toList())
    }

    @Test
    fun `删会话五步_顺序即执行顺序`() {
        val stmts = BaopuSql.deleteSessionStatements(42L)
        assertEquals(5, stmts.size)
        // 1. ops 子批先删（plan_batch 自引用：子先父后）
        assertTrue(stmts[0].sql.contains("DELETE FROM plan_batch WHERE session_id = ? AND proposal_id IS NOT NULL"))
        // 2. 再删提案批
        assertEquals("DELETE FROM plan_batch WHERE session_id = ?", stmts[1].sql)
        // 3. run_log 无级联，必须显式删
        assertEquals("DELETE FROM run_log WHERE session_id = ?", stmts[2].sql)
        // 4. message（part 靠 DDL 级联，不重复删）
        assertEquals("DELETE FROM message WHERE session_id = ?", stmts[3].sql)
        // 5. 会话本体最后
        assertEquals("DELETE FROM session WHERE id = ?", stmts[4].sql)
        assertTrue(stmts.all { it.args.contentEquals(arrayOf("42")) })
    }

    @Test
    fun `message与part的插入绑定`() {
        val m = BaopuSql.insertMessage(1L, 3, "user", 100L, "done")
        assertEquals(listOf("1", "3", "user", "100", "done"), m.args.toList())
        val p = BaopuSql.insertPart(2L, 0, "text", "{}", 101L)
        assertEquals(listOf("2", "0", "text", "{}", "101"), p.args.toList())
    }

    @Test
    fun `touchSession与setMsgCount`() {
        val t = BaopuSql.touchSession(5L, 200L, "预览…")
        assertTrue(t.sql.contains("SET updated_at = ?, preview = ?"))
        assertEquals(listOf("200", "预览…", "5"), t.args.toList())
        val c = BaopuSql.setMsgCount(5L, 9)
        assertEquals(listOf("9", "5"), c.args.toList())
    }
}
