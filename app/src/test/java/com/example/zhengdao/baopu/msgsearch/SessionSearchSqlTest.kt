// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 702 派工单判据的 SQL 侧钉法（房型照 `SearchSqlTest`——本仓没有 Robolectric，JVM 里 SQLite
// 跑不起来，「表长什么样」唯一的真源就是 SQL 常量的文本）：
// - 判据④「不重建全表」＝ 结构性保证：全对象**没有任何无 WHERE 的 DELETE**，也没有 rebuild 语句；
// - 判据⑤「删会话 ⇒ token 清零、其它会话不受影响」＝ 一条带 WHERE 的会话级删除 ＋ tokens
//   对 messages 的 `ON DELETE CASCADE`（外键由 `SearchDb.onConfigure` 打开）；
// - 派单红线「正文只存哈希」＝ 列名逐字对账：messages 里只有 `text_sha`，没有任何能装正文的列。
package com.example.zhengdao.baopu.msgsearch

import com.example.zhengdao.baopu.search.SearchSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionSearchSqlTest {

    private val ddl: String = SessionSearchSql.DDL.joinToString("\n")

    /** 一张表的 DDL 块（`CREATE TABLE ... ( ... )`，按括号配对取；找不到就红，不许假绿）。 */
    private fun tableBlock(table: String): String {
        val head = "CREATE TABLE IF NOT EXISTS $table("
        val i = ddl.indexOf(head)
        assertTrue("DDL 里找不到表 $table", i >= 0)
        var depth = 0
        var end = -1
        for (j in (i + head.length - 1) until ddl.length) {
            when (ddl[j]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        end = j
                        break
                    }
                }
            }
        }
        assertTrue("表 $table 的括号没配平", end > 0)
        return ddl.substring(i, end + 1)
    }

    /** 列名（跳过约束行与收尾的右括号；PRIMARY/UNIQUE/CHECK/FOREIGN/CONSTRAINT 都不是列）。 */
    private fun columnsOf(table: String): List<String> =
        tableBlock(table).lines().drop(1).map { it.trim().trimEnd(',') }.filter { it.isNotEmpty() }
            .filterNot {
                it.startsWith("PRIMARY") || it.startsWith("UNIQUE") || it.startsWith("CHECK") ||
                    it.startsWith("FOREIGN") || it.startsWith("CONSTRAINT") || it.startsWith(")")
            }
            .map { it.substringBefore(' ').substringBefore('(') }

    /** 反射收 [SessionSearchSql] 全部 `const val` 字符串（新语句自动进账，不靠手抄名单）。 */
    private fun stringConstants(): Map<String, String> {
        val found = SessionSearchSql::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .associate { it.name to (it.get(null) as String) }
        assertTrue("反射没收到任何 SQL 常量＝这个门禁失效了", found.size >= 10)
        return found
    }

    private fun writeStatements(): Map<String, String> =
        stringConstants().filterValues { v ->
            val verb = v.substringBefore(" ON CONFLICT")
            verb.startsWith("INSERT") || verb.startsWith("UPDATE") || verb.startsWith("DELETE")
        }

    private fun writtenTable(sql: String): String? = Regex(
        "(?:INSERT INTO|INSERT OR REPLACE INTO|UPDATE|DELETE FROM)\\s+([A-Za-z_][A-Za-z0-9_]*)",
    ).find(sql.substringBefore(" ON CONFLICT"))?.groupValues?.get(1)

    // ── 表形状 ────────────────────────────────────────────────────────────────

    @Test
    fun `两张表_表名与派单对上_与手机索引的五张表零交集`() {
        val found = Regex("CREATE TABLE IF NOT EXISTS (\\w+)").findAll(ddl).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("messages", "tokens"), found)
        assertEquals("常量表名与 DDL 必须一致", found, SessionSearchSql.TABLES)
        assertEquals(
            "search.db 原五张表（手机文件索引的领地）一个字不许动：会话索引的表不许撞名",
            emptySet<String>(),
            SessionSearchSql.TABLES intersect SearchSql.TABLES,
        )
    }

    @Test
    fun `全是普通表_没有虚拟表没有fts没有match`() {
        listOf("VIRTUAL TABLE", "fts5", "fts4", "fts3", " USING ", "MATCH ", "tokenize").forEach { banned ->
            assertFalse(
                "真机实测系统 SQLite 没编 FTS5 ⇒ 倒排只能是普通表（702 派工单方案栏原话）——DDL 里出现 $banned 就是红",
                ddl.contains(banned, ignoreCase = true),
            )
        }
    }

    @Test
    fun `列名逐字对账_两张表一共这些列`() {
        val expected = mapOf(
            // 派单 camelCase → 仓库 snake_case 一一对应：msgId→msg_id、sessionId→session_id、
            // ts→ts、textSha→text_sha；len/indexed_at 是自作主张（BM25 的 dl 与 lastIndexedAt 真源）
            "messages" to listOf("msg_id", "session_id", "role", "ts", "text_sha", "len", "indexed_at"),
            "tokens" to listOf("token", "msg_id", "tf"),
        )
        expected.forEach { (t, cols) ->
            assertEquals("$t 的列对不上（加列必须连这条一起改）", cols, columnsOf(t))
        }
    }

    @Test
    fun `正文不入库_没有任何能装下正文或查询词的列`() {
        val banned = setOf(
            "query", "q", "keyword", "keywords", "text", "content", "body", "payload", "args",
            "input", "prompt", "snippet", "summary", "phrase", "pattern", "regex", "msg", "message",
        )
        SessionSearchSql.TABLES.map { columnsOf(it) }.flatten().forEach { col ->
            assertFalse("列 $col 能装下正文/查询词 ⇒ 违反「正文只存哈希」红线", banned.contains(col.lowercase()))
        }
        // 正面钉一次：哈希列必须存在、名字必须自我声明是哈希
        assertTrue(columnsOf("messages").contains("text_sha"))
    }

    @Test
    fun `DDL全是IF_NOT_EXISTS_缺表就建不靠版本阶梯`() {
        SessionSearchSql.DDL.forEach { stmt ->
            assertTrue(
                "search.db 是可整包丢弃重建的缓存，DDL 必须幂等：$stmt",
                stmt.startsWith("CREATE TABLE IF NOT EXISTS") || stmt.startsWith("CREATE INDEX IF NOT EXISTS"),
            )
        }
    }

    @Test
    fun `tokens_级联挂在messages上_PK最左列是token_不另建第二棵索引`() {
        val block = tableBlock("tokens")
        assertTrue("删会话 ⇒ token 清零的机制本体（外键级联）", block.contains("REFERENCES messages(msg_id) ON DELETE CASCADE"))
        assertTrue("一个词在同一条消息里只有一行计数", block.contains("PRIMARY KEY(token, msg_id)"))
        val extra = Regex("CREATE INDEX IF NOT EXISTS (\\w+) ON tokens").findAll(ddl).map { it.groupValues[1] }.toList()
        assertEquals(
            "PK(token, msg_id) 最左前缀就是 token 索引，再建一棵是同一份数据的第二份拷贝",
            emptyList<String>(),
            extra,
        )
    }

    @Test
    fun `messages_按会话翻账的那一棵索引恰好一棵`() {
        val extra = Regex("CREATE INDEX IF NOT EXISTS (\\w+) ON messages").findAll(ddl).map { it.groupValues[1] }.toList()
        assertEquals(listOf("idx_messages_session"), extra)
    }

    // ── 判据④：不重建全表（结构性） ──────────────────────────────────────────

    @Test
    fun `判据4_没有任何无WHERE的DELETE_也没有整包重建的语句`() {
        val deletes = stringConstants().filterValues { it.startsWith("DELETE") }
        assertTrue("反射口坏了不许假绿", deletes.isNotEmpty())
        deletes.forEach { (name, sql) ->
            assertTrue("$name 是无 WHERE 的全表删除＝重建全表，判据④直接红：$sql", sql.contains("WHERE"))
        }
        listOf("DROP ", "TRUNCATE", "DELETE FROM tokens WHERE msg_id NOT", "DELETE FROM messages\n").forEach { banned ->
            stringConstants().values.forEach { sql ->
                assertFalse("任何 SQL 常量都不许出现 $banned：$sql", sql.contains(banned))
            }
        }
    }

    // ── 判据⑤：删会话是一条带 WHERE 的会话级删除，token 靠级联走 ───────────────

    @Test
    fun `判据5_删会话只删自己会话的行_级联带token_不碰别的会话`() {
        assertEquals(
            "删会话的语句必须精确到 session_id（其它会话不受影响的机制本体）",
            "DELETE FROM messages WHERE session_id = ?",
            SessionSearchSql.DELETE_MESSAGES_OF_SESSION,
        )
        assertEquals(
            "重索引单条消息只清它自己的旧词",
            "DELETE FROM tokens WHERE msg_id = ?",
            SessionSearchSql.DELETE_TOKENS_OF_MESSAGE,
        )
    }

    // ── 写语句只碰这两张表 ────────────────────────────────────────────────────

    @Test
    fun `写语句只碰这两张表_且不碰日志表`() {
        val writes = writeStatements()
        assertTrue("没扫到任何写语句＝反射口坏了，不许假绿", writes.isNotEmpty())
        writes.forEach { (name, sql) ->
            val t = writtenTable(sql)
            assertNotNull("$name 认不出目标表：$sql", t)
            assertTrue(
                "$name 写的是 `$t`，不在这两张表里——search.db 其它五张与主库一律不许碰：$sql",
                SessionSearchSql.TABLES.contains(t),
            )
        }
        stringConstants().values.forEach { sql ->
            assertFalse("任何 SQL 常量都不许出现 run_log：$sql", sql.contains("run_log"))
        }
    }

    @Test
    fun `消息行是幂等UPSERT_不用OR_REPLACE`() {
        assertTrue(SessionSearchSql.UPSERT_MESSAGE.contains("ON CONFLICT(msg_id) DO UPDATE"))
        assertFalse(
            "INSERT OR REPLACE 在主键冲突时删旧行插新行——状态读数会把「刷新」算成新账",
            SessionSearchSql.UPSERT_MESSAGE.contains("INSERT OR REPLACE"),
        )
    }

    // ── 检索语句：只读、词走绑定参数 ──────────────────────────────────────────

    @Test
    fun `倒排检索只读_词一律走绑定参数`() {
        val sql = SessionSearchSql.selectPostings(3)
        listOf("INSERT", "UPDATE", "DELETE", "REPLACE", "ATTACH", "PRAGMA", "DROP").forEach { banned ->
            assertFalse("检索语句必须只读，出现 $banned 就是拿查询当写入：$sql", sql.contains(banned, ignoreCase = true))
        }
        assertTrue(sql.contains("IN (?, ?, ?)"))
        assertTrue("候选 meta 由 JOIN 一次带全，不用第二趟", sql.contains("JOIN messages m ON m.msg_id = t.msg_id"))
    }

    @Test
    fun `占位符串只允许问号逗号空格_词数与批数越界必须断`() {
        (1..SessionSearchSql.MAX_QUERY_TOKENS).forEach { n ->
            val p = SessionSearchSql.placeholders(n)
            assertTrue("出现别的字符就是拼参数进 SQL 文本（注入＋隐私双防）：$p", Regex("[?, ]+").matches(p))
            assertEquals(n, p.count { it == '?' })
        }
        listOf(0, -1, SessionSearchSql.MAX_QUERY_TOKENS + 1, 9999).forEach { n ->
            try {
                SessionSearchSql.selectPostings(n)
                fail("selectPostings($n) 应当直接断")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("tokenCount"))
            }
        }
        listOf(0, SessionSearchSql.MAX_BATCH_MSGS + 1).forEach { n ->
            try {
                SessionSearchSql.selectTexts(n)
                fail("selectTexts($n) 应当直接断")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("messageCount"))
            }
        }
    }

    // ── 读主库的语句：只读、只碰 message / part / session ─────────────────────

    @Test
    fun `主库读语句只读_只碰message_part_session三张表`() {
        val sources = stringConstants().filterKeys { it.startsWith("SRC_") }
        assertTrue("反射口坏了不许假绿", sources.isNotEmpty())
        sources.forEach { (name, sql) ->
            assertTrue("$name 必须是只读的 SELECT：$sql", sql.trimStart().startsWith("SELECT"))
            val tables = Regex("(?:FROM|JOIN)\\s+([a-z_]+)").findAll(sql).map { it.groupValues[1] }.toSet()
            assertTrue(
                "$name 读到了 $tables——主库只许读 message/part/session（正文留在原处，读不算碰）",
                tables.subtract(setOf("message", "part", "session")).isEmpty(),
            )
        }
        assertTrue("增量必须带 LIMIT（首轮回填分多轮铺，不一口气吃下十万条）", sources.getValue("SRC_AFTER").contains("LIMIT ?"))
        assertTrue("会话正文要按消息序出", sources.getValue("SRC_SESSION_TEXTS").contains("ORDER BY m.id, p.seq"))
        assertTrue("回收站的判定就是 deleted_at", sources.getValue("SRC_TRASHED_SESSIONS").contains("deleted_at IS NOT NULL"))
    }

    private fun assertNotNull(msg: String, t: String?) {
        org.junit.Assert.assertNotNull(msg, t)
    }
}
