// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `v1 五张表齐全`() {
        listOf("session", "message", "part", "run_log", "plan_batch").forEach { name ->
            assertTrue("缺表 $name", BaopuSql.DDL.any { it.contains("CREATE TABLE IF NOT EXISTS $name (") })
        }
    }

    // ── v2（刀 0b）：memory 表 ＋ interrupted_step 迁移 ────────────────────

    @Test
    fun `v2 memory表字段与索引齐备`() {
        val ddl = BaopuSql.MEMORY_DDL.first { it.contains("CREATE TABLE IF NOT EXISTS memory (") }
        listOf(
            "id TEXT PRIMARY KEY",
            "scope TEXT NOT NULL",
            "kind TEXT NOT NULL",
            "title TEXT NOT NULL",
            "body TEXT NOT NULL",
            "tags TEXT NOT NULL DEFAULT ''",
            "source_session TEXT NOT NULL DEFAULT ''",
            "source_file TEXT NOT NULL DEFAULT ''",
            "status TEXT NOT NULL DEFAULT 'candidate'",
            "created_at INTEGER NOT NULL",
            "updated_at INTEGER NOT NULL",
            "use_count INTEGER NOT NULL DEFAULT 0",
            "last_used_at INTEGER NOT NULL DEFAULT 0",
        ).forEach { col -> assertTrue("memory 缺列：$col", ddl.contains(col)) }
        assertTrue(
            BaopuSql.MEMORY_DDL.any { it.contains("idx_memory_scope_kind ON memory (scope, kind)") },
        )
    }

    @Test
    fun `v2 迁移_缺列才给加列语句_有列不给`() {
        // v1 旧库：session 没有 interrupted_step ⇒ 必须给一条 ALTER
        val stmts = BaopuSql.sessionInterruptedStepStmt(listOf("id", "title", "model"))
        assertEquals(1, stmts.size)
        assertEquals("ALTER TABLE session ADD COLUMN interrupted_step INTEGER", stmts[0].sql)
        // 已有列（新库 / 重跑迁移）⇒ 一条都不给（幂等）
        assertTrue(BaopuSql.sessionInterruptedStepStmt(listOf("id", "interrupted_step")).isEmpty())
        assertTrue(
            BaopuSql.sessionInterruptedStepStmt(listOf("id", "INTERRUPTED_STEP")).isEmpty(),
        ) // 大小写不敏感
    }

    @Test
    fun `v3 迁移语句全部幂等形态`() {
        // 建表走 IF NOT EXISTS；加列不走 DDL 常量（壳层按 table_info 列况执行 sessionInterruptedStepStmt）
        // ⇒ 同一份迁移跑两遍不炸，v1 库为空也不丢表（只加不删）
        BaopuSql.MEMORY_DDL.forEach {
            assertTrue("迁移 DDL 必须 IF NOT EXISTS：$it", it.contains("IF NOT EXISTS"))
        }
        assertEquals(2, BaopuSql.MEMORY_DDL.size)
        assertTrue("DDL 常量里不许有裸 ALTER（加列必须看列况）", BaopuSql.DDL.none { it.contains("ALTER TABLE") })
        // 刀 2 v3：资料库两表 + 章节索引表也全部 IF NOT EXISTS（幂等真迁移）
        BaopuSql.LIBRARY_DDL.forEach {
            assertTrue("v3 迁移 DDL 必须 IF NOT EXISTS：$it", it.contains("IF NOT EXISTS"))
        }
        BaopuSql.CHAPTER_INDEX_DDL.forEach {
            assertTrue("v3 迁移 DDL 必须 IF NOT EXISTS：$it", it.contains("IF NOT EXISTS"))
        }
    }

    @Test
    fun `v4 版本号与库路径纯函数`() {
        assertEquals(4, BaopuDb.VERSION) // v4 = 刀 3 B 段（run_log 加 reasoning_tokens）
        val filesDir = java.io.File("/data/user/0/com.example.zhengdao/files")
        val db = BaopuDb.dbPathUnder(filesDir)
        assertEquals("baopu", db.parentFile?.name)
        assertEquals("baopu.db", db.name)
        assertEquals("/data/user/0/com.example.zhengdao/files/baopu/baopu.db", db.path.replace('\\', '/'))
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
        assertEquals(4, BaopuDb.VERSION) // v4 = 刀 3 B 段（run_log 加 reasoning_tokens）
        assertEquals("baopu.db", BaopuDb.DB_NAME)
        assertEquals("baopu", BaopuDb.DIR_BAOPU)
    }

    // ── memory 表语句（v2 · 刀 0b） ───────────────────────────────────────

    @Test
    fun `upsertMemory十三个参数全绑定`() {
        val e = MemoryEntry(
            id = "1729000000000-称呼偏好", scope = MemoryScope.PROJECT, kind = MemoryKind.PREFERENCE,
            title = "称呼偏好", body = "用户希望被称呼为老师", tags = listOf("称呼", "用户"),
            sourceSession = "s7", sourceFile = "资料库/原始/设定.md", status = MemoryStatus.CONFIRMED,
            createdAt = 100L, updatedAt = 200L, useCount = 3, lastUsedAt = 300L,
        )
        val s = BaopuSql.upsertMemory(e)
        assertTrue(s.sql.startsWith("INSERT OR REPLACE INTO memory"))
        assertEquals(13, s.args.size)
        assertEquals("1729000000000-称呼偏好", s.args[0])
        assertEquals("project", s.args[1])
        assertEquals("preference", s.args[2])
        assertEquals("称呼,用户", s.args[5]) // tags 逗号拼接
        assertEquals("s7", s.args[6])
        assertEquals("confirmed", s.args[8])
        assertEquals("100", s.args[9])
        assertEquals("3", s.args[11])
        assertEquals("300", s.args[12])
    }

    @Test
    fun `memory删清查与touch语句`() {
        assertEquals("DELETE FROM memory WHERE id = ?", BaopuSql.deleteMemory("x").sql)
        assertEquals(listOf("x"), BaopuSql.deleteMemory("x").args.toList())
        assertEquals("DELETE FROM memory", BaopuSql.clearMemories().sql)
        assertTrue(BaopuSql.allMemories().sql.contains("ORDER BY created_at"))
        val t = BaopuSql.touchMemoryUse("x", 999L)
        assertTrue(t.sql.contains("use_count = use_count + 1"))
        assertEquals(listOf("999", "x"), t.args.toList())
    }

    // ── v2 追加三：run_log 缓存口径两列 ───────────────────────────────────

    @Test
    fun `run_log缓存列_缺列才给ALTER_幂等`() {
        val both = BaopuSql.runLogCacheColumnsStmt(emptyList())
        assertEquals(2, both.size)
        assertTrue(both[0].sql.contains("ALTER TABLE run_log ADD COLUMN cache_hit_tokens INTEGER"))
        assertTrue(both[1].sql.contains("ALTER TABLE run_log ADD COLUMN cache_miss_tokens INTEGER"))
        // 已有其一 ⇒ 只补缺的那条
        val one = BaopuSql.runLogCacheColumnsStmt(listOf("cache_hit_tokens"))
        assertEquals(1, one.size)
        assertTrue(one[0].sql.contains("cache_miss_tokens"))
        // 两列都有 ⇒ 一条不给（重跑迁移安全）
        assertTrue(BaopuSql.runLogCacheColumnsStmt(listOf("cache_hit_tokens", "cache_miss_tokens")).isEmpty())
    }

    @Test
    fun `insertRunLog_缓存两列可取到才绑_取不到留空不填0`() {
        val withCache = BaopuSql.insertRunLog(
            sessionId = 1L, step = 1, state = "thinking", model = "m",
            tokensIn = 100, tokensOut = 50, tokensInEst = 98, tokensOutEst = 49,
            tool = "", ok = true, latencyMs = 10L, err = "", createdAt = 5L,
            cacheHitTokens = 640, cacheMissTokens = 360,
        )
        assertEquals(15, withCache.args.size)
        assertEquals("640", withCache.args[13])
        assertEquals("360", withCache.args[14])
        assertTrue(withCache.sql.contains("cache_hit_tokens, cache_miss_tokens"))

        val noCache = BaopuSql.insertRunLog(
            sessionId = 1L, step = 1, state = "thinking", model = "m",
            tokensIn = 100, tokensOut = 50, tokensInEst = 98, tokensOutEst = 49,
            tool = "", ok = true, latencyMs = 10L, err = "", createdAt = 5L,
        )
        // 取不到 ⇒ SQL NULL（留空）——不是 "0"（0 = 确定没命中，与没测到是两件事）
        assertEquals(15, noCache.args.size)
        assertNull(noCache.args[13])
        assertNull(noCache.args[14])
    }

    @Test
    fun `缓存命中率_命中除以总和_都没测到返回null`() {
        assertEquals(0.64, RunLogMetrics.cacheHitRate(640, 360)!!, 1e-9)
        assertNull(RunLogMetrics.cacheHitRate(null, null)) // 没测到不编数
        assertEquals(1.0, RunLogMetrics.cacheHitRate(500, null)!!, 1e-9) // 一边有一边没有按有的算
        assertEquals(0.0, RunLogMetrics.cacheHitRate(null, 500)!!, 1e-9)
        assertNull(RunLogMetrics.cacheHitRate(0, 0)) // 都读到 0：总数 0，无从谈比率
    }

    // ── v4（刀 3 B 段）：run_log 思考列 ＋ 用量回写语句 ─────────────────────

    @Test
    fun `v4 迁移_run_log加reasoning_tokens列_缺列才给ALTER_幂等`() {
        val none = BaopuSql.runLogReasoningColumnsStmt(emptyList())
        assertEquals(1, none.size)
        assertEquals("ALTER TABLE run_log ADD COLUMN reasoning_tokens INTEGER", none[0].sql)
        // 已有列（新库 / 重跑迁移）⇒ 一条不给（幂等）
        assertTrue(BaopuSql.runLogReasoningColumnsStmt(listOf("reasoning_tokens")).isEmpty())
        assertTrue(BaopuSql.runLogReasoningColumnsStmt(listOf("REASONING_TOKENS")).isEmpty()) // 大小写不敏感
    }

    @Test
    fun `updateRunLogUsage_只SET有读数的列_目标行是该step最新一行`() {
        val s = BaopuSql.updateRunLogUsage(
            sessionId = 7L, step = 2,
            usage = LlmUsage(
                promptTokens = 36, completionTokens = 64, reasoningTokens = 61,
                cacheHitTokens = 0, cacheMissTokens = 36,
            ),
        )!!
        assertTrue(s.sql.startsWith("UPDATE run_log SET tokens_in = ?, tokens_out = ?, reasoning_tokens = ?"))
        assertTrue(s.sql.contains("cache_hit_tokens = ?, cache_miss_tokens = ?"))
        assertTrue(s.sql.contains("WHERE id = (SELECT MAX(id) FROM run_log WHERE session_id = ? AND step = ?)"))
        // cacheHitTokens = 0 是真读数（确实没命中）⇒ 绑 "0"，不是 null
        assertEquals(listOf("36", "64", "61", "0", "36", "7", "2"), s.args.toList())

        // 只有部分读数 ⇒ 只 SET 那几列（tokens_in/out 是 NOT NULL 列，绑 NULL 会炸约束）
        val partial = BaopuSql.updateRunLogUsage(1L, 1, LlmUsage(reasoningTokens = 5))!!
        assertEquals("UPDATE run_log SET reasoning_tokens = ? WHERE id = (SELECT MAX(id) FROM run_log WHERE session_id = ? AND step = ?)", partial.sql)
        assertEquals(listOf("5", "1", "1"), partial.args.toList())

        // 没读数 / 五列全空 ⇒ null（调用方跳过，不写半行空话）
        assertNull(BaopuSql.updateRunLogUsage(1L, 1, null))
        assertNull(BaopuSql.updateRunLogUsage(1L, 1, LlmUsage()))
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
    fun `insertRunLog十五个参数全绑定`() {
        val s = BaopuSql.insertRunLog(
            sessionId = 1L, step = 2, state = "thinking", model = "m",
            tokensIn = 100, tokensOut = 50, tokensInEst = 98, tokensOutEst = 49,
            tool = "read_file", ok = false, latencyMs = 1234L, err = "boom", createdAt = 999L,
        )
        assertEquals(15, s.args.size) // v2 增加 cache_hit / cache_miss 两个可空列
        assertEquals("1", s.args[0])
        assertEquals("2", s.args[1])
        assertEquals("read_file", s.args[8])
        assertEquals("0", s.args[9]) // ok = false ⇒ "0"
        assertEquals("1234", s.args[10])
        assertEquals("boom", s.args[11])
        assertEquals("999", s.args[12])
        // 缓存两列默认不给 ⇒ null（SQL 留空，不瞎填 0）
        assertNull(s.args[13])
        assertNull(s.args[14])
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
