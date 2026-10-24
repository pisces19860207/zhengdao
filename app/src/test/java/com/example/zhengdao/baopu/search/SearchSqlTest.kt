// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十七批（派工单 §1②）：`search.db` 四张表的**形状门禁**。
//
// 为什么钉字符串：本仓没有 Robolectric，`android.jar` 是 stub ⇒ JVM 里 SQLite 跑不起来，
// 「表长什么样」这件事在单测里唯一的真源就是 [SearchSql] 的文本（房型照 `BaopuDbTest`／
// `DiaryDbGateTest`）。文本钉住了，薄壳 [com.example.zhengdao.baopu.search.SearchDb] 只做执行，
// 真机首开库那条行为标③留给验收。
//
// 三条本批点名的红线在这里各有一条测试：
// - **普通表，不用虚拟表**（派工单 §1②「FTS5 实测不存在」）；
// - **没有能存查询词的列**（派工单 §2「查询词不出现在任何 DB 列」）；
// - **写语句只碰这四张表**（越界写＝多拿能力，四张表之外一律不许出现在 SQL 常量里）。
package com.example.zhengdao.baopu.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SearchSqlTest {

    private val ddl: String = SearchSql.DDL.joinToString("\n")

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

    /**
     * 反射收 [SearchSql] 里全部 `const val` 字符串（新增一条语句自动进账，
     * 不靠我手抄名单——抄的那份一定会过期）。没有静态 String 常量就红，不许假绿。
     */
    private fun stringConstants(): Map<String, String> {
        val found = SearchSql::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .associate { it.name to (it.get(null) as String) }
        assertTrue("反射没收到任何 SQL 常量＝这个门禁失效了", found.size >= 10)
        return found
    }

    /** 写语句（INSERT/UPDATE/DELETE）：`ON CONFLICT` 之后那段是 UPSERT 的更新子句，不算动词。 */
    private fun writeStatements(): Map<String, String> =
        stringConstants().filterValues { v ->
            val verb = v.substringBefore(" ON CONFLICT")
            verb.startsWith("INSERT") || verb.startsWith("UPDATE") || verb.startsWith("DELETE")
        }

    /** 一条语句里被写的那张表（把 UPSERT 的更新子句剪掉再找动词，避免 `DO UPDATE SET` 冒充表名）。 */
    private fun writtenTable(sql: String): String? = Regex(
        "(?:INSERT INTO|INSERT OR REPLACE INTO|UPDATE|DELETE FROM)\\s+([A-Za-z_][A-Za-z0-9_]*)",
    ).find(sql.substringBefore(" ON CONFLICT"))?.groupValues?.get(1)

    @Test
    fun `四张表_表名与设计62逐字对上`() {
        val found = Regex("CREATE TABLE IF NOT EXISTS (\\w+)").findAll(ddl).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("scope", "doc", "tok", "watermark"), found)
        assertEquals("常量表名与 DDL 必须一致", found, SearchSql.TABLES)
    }

    // ── 红线①：普通表 ────────────────────────────────────────────────────────

    @Test
    fun `全是普通表_没有虚拟表没有fts没有match`() {
        listOf("VIRTUAL TABLE", "fts5", "fts4", "fts3", " USING ", "MATCH ", "prAGMA ").forEach { banned ->
            assertFalse(
                "派工单 §1② 要普通表（真机实测系统 SQLite 没编 FTS5，设计 §12.2）——DDL 里出现 $banned 就是拿虚拟表当索引",
                ddl.contains(banned, ignoreCase = true),
            )
        }
        // 倒排靠 tok 这张普通表 ＋ 应用侧分词，不靠任何 tokenizer
        assertFalse(ddl.contains("tokenize", ignoreCase = true))
    }

    @Test
    fun `tok_是普通表且索引恰好一棵_最左列是token`() {
        val block = tableBlock("tok")
        assertTrue("一个词在同一篇里只能有一行计数", block.contains("PRIMARY KEY(token, doc_id)"))
        val extra = Regex("CREATE INDEX IF NOT EXISTS (\\w+) ON tok").findAll(ddl).map { it.groupValues[1] }.toList()
        assertEquals(
            "PK(token, doc_id) 自带的最左前缀就是 token 索引，再建一棵是同一份数据的第二份拷贝（自作主张 #2，报告 §3）",
            emptyList<String>(),
            extra,
        )
    }

    // ── 键与级联 ────────────────────────────────────────────────────────────

    @Test
    fun `doc_的键形状_自增代理键加根内路径唯一`() {
        val block = tableBlock("doc")
        assertTrue("自作主张 #1：tok 要引单列 doc_id ⇒ 它是 rowid 别名", block.contains("doc_id INTEGER PRIMARY KEY AUTOINCREMENT"))
        assertTrue("同一个根里 rel_path 唯一（增量重扫是刷新不是追加）", block.contains("UNIQUE(root_id, rel_path)"))
        assertTrue("撤销一个根要能把它的索引带走", block.contains("REFERENCES scope(root_id) ON DELETE CASCADE"))
    }

    @Test
    fun `三张明细表都挂着级联_撤销不会留半截索引`() {
        assertTrue(tableBlock("tok").contains("REFERENCES doc(doc_id) ON DELETE CASCADE"))
        assertTrue(tableBlock("watermark").contains("REFERENCES scope(root_id) ON DELETE CASCADE"))
    }

    @Test
    fun `scope_abs_root唯一且kind三档照设计`() {
        val block = tableBlock("scope")
        assertTrue("同一个物理根只许一条授予", block.contains("abs_root TEXT NOT NULL UNIQUE"))
        assertEquals(
            "kind 的字面量照设计 §6.2（MEDIA 是形状照单，本批代码不产出它）",
            setOf("'HOME'", "'PHONE'", "'MEDIA'"),
            Regex("'([A-Z]+)'").findAll(block.substringAfter("CHECK (kind IN")).map { "'${it.groupValues[1]}'" }.toSet(),
        )
    }

    @Test
    fun `status_字面量照设计62加63补的UNCLAIMED`() {
        val block = tableBlock("doc")
        assertEquals(
            setOf("OK", "TOO_BIG", "ENCRYPTED", "JUNK", "PRUNED", "UNCLAIMED"),
            Regex("'([A-Z_]+)'").findAll(block.substringAfter("CHECK (status IN")).map { it.groupValues[1] }.toSet(),
        )
        // 每个字面量都得能从 enum 读回来（读不懂的行会被 all() 丢掉 ⇒ 少一行就是静默丢数据）
        listOf("OK", "TOO_BIG", "ENCRYPTED", "JUNK", "PRUNED", "UNCLAIMED").forEach { s ->
            assertNotNull("DocStatus 读不回 $s", DocStatus.fromTag(s))
        }
    }

    // ── 红线②：没有能存查询词的列 ────────────────────────────────────────────

    @Test
    fun `列名逐字对账_四张表一共这些列`() {
        val expected = mapOf(
            "scope" to listOf("root_id", "abs_root", "kind", "granted_at", "revoked_at"),
            "doc" to listOf("doc_id", "root_id", "rel_path", "kind", "size", "mtime", "title", "sha256_head", "indexed_at", "status"),
            "tok" to listOf("token", "doc_id", "cnt"),
            "watermark" to listOf("root_id", "last_scan_at", "last_full_at", "files_seen", "files_skipped", "bytes_read"),
        )
        expected.forEach { (t, cols) -> assertEquals("$t 的列对不上（加列必须连这条一起改，因为它决定能不能存查询词）", cols, columnsOf(t)) }
    }

    @Test
    fun `没有任何列能存下查询词`() {
        // 派工单 §2：查询词不出现在任何 DB 列。`token` 存的是**文档切出来的词**（S3 写入），
        // 不是用户输入的那一句；用户输入唯一的去处是绑定参数（见 selectSearch 那条测试）。
        val banned = setOf("query", "q", "keyword", "keywords", "text", "content", "body", "args", "input", "prompt", "err", "phrase", "pattern", "regex")
        SearchSql.TABLES.map { columnsOf(it) }.flatten().forEach { col ->
            assertFalse("列 $col 能存下用户输入 ⇒ 违反「查询词不持久化」", banned.contains(col.lowercase()))
        }
    }

    // ── 红线③：语句面只碰这四张表、检索只读 ──────────────────────────────────

    @Test
    fun `写语句只碰四张表`() {
        val writes = writeStatements()
        assertTrue("没扫到任何写语句＝反射口坏了，不许假绿", writes.isNotEmpty())
        writes.forEach { (name, sql) ->
            val t = writtenTable(sql)
            assertNotNull("$name 认不出目标表：$sql", t)
            assertTrue(
                "$name 写的是 `$t`，不在这四张表里——search.db 之外一律不许碰（尤其不许碰 run_log：那里是日志，写进去就留了痕）：$sql",
                SearchSql.TABLES.contains(t),
            )
        }
        // 单独点一次名：日志表是查询词最容易顺手落进去的地方（设计 §6.6）
        stringConstants().values.forEach { sql ->
            assertFalse("任何 SQL 常量都不许出现 run_log：$sql", sql.contains("run_log"))
        }
    }

    @Test
    fun `检索语句只读_且词一律走绑定参数`() {
        val sql = SearchSql.selectSearch(3)
        listOf("INSERT", "UPDATE", "DELETE", "REPLACE", "ATTACH", "PRAGMA", "DROP").forEach { banned ->
            assertFalse("检索语句必须只读，出现 $banned 就是拿查询当写入：$sql", sql.contains(banned, ignoreCase = true))
        }
        assertTrue("三个词要有三个占位符：$sql", sql.contains("IN (?, ?, ?)"))
        assertTrue("状态不是 OK 的不许进结果（TOO_BIG/UNCLAIMED 没正文可搜）", sql.contains("d.status = 'OK'"))
        assertTrue("同分必按路径定序（单测要能断言第 1 名是谁）", sql.contains("ORDER BY score DESC, d.rel_path"))
    }

    @Test
    fun `占位符串只允许问号逗号空格`() {
        (1..SearchSql.MAX_TOKENS).forEach { n ->
            val p = SearchSql.placeholders(n)
            assertTrue("出现别的字符就是拼参数进 SQL 文本（注入＋隐私双防）：$p", Regex("[?, ]+").matches(p))
            assertEquals("占位符个数要等于词数", n, p.count { it == '?' })
        }
    }

    @Test
    fun `词数与条数越界必须断`() {
        listOf(0, -1, SearchSql.MAX_TOKENS + 1, 9999).forEach { n ->
            try {
                SearchSql.selectSearch(n)
                fail("selectSearch($n) 应当直接断，不许悄悄给一条能跑的 SQL")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("tokenCount"))
            }
        }
        listOf(0, -3, SearchSql.MAX_LIMIT + 1).forEach { limit ->
            try {
                SearchSql.selectSearch(1, limit)
                fail("limit=$limit 应当直接断")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("limit"))
            }
        }
    }

    // ── 撤销这条路径（隐私：撤了就别再能搜） ──────────────────────────────────

    @Test
    fun `记账用DO_UPDATE不用OR_REPLACE_重新授权不该清索引`() {
        assertTrue(SearchSql.UPSERT_SCOPE.contains("ON CONFLICT(abs_root) DO UPDATE"))
        assertFalse(
            "INSERT OR REPLACE 在 UNIQUE 上冲突＝删旧行＋插新行，删旧行会顺级联把这一根的 doc/tok 全清掉",
            SearchSql.UPSERT_SCOPE.contains("INSERT OR REPLACE"),
        )
        assertTrue("复授权必须把 revoked_at 放回 NULL（撤过的根重新批要能活过来）", SearchSql.UPSERT_SCOPE.contains("revoked_at = NULL"))
    }

    @Test
    fun `撤销不覆盖历史_残留探针按abs_root报账`() {
        assertTrue("已经撤过的行不该被第二次撤销刷时间戳（历史要能复盘）", SearchSql.MARK_SCOPE_REVOKED.contains("revoked_at IS NULL"))
        assertTrue("探针第一列给人看路径，不给 id", SearchSql.SELECT_ORPHAN_DOCS.startsWith("SELECT s.abs_root"))
        assertTrue("探针只查已撤销的根", SearchSql.SELECT_ORPHAN_DOCS.contains("WHERE s.revoked_at IS NOT NULL"))
        assertTrue("撤一个根先删它的 doc（tok 顺级联走，不写第二条 DELETE）", SearchSql.DELETE_DOCS_OF_ROOT.contains("DELETE FROM doc WHERE root_id = ?"))
    }

    @Test
    fun `建表语句全是IF_NOT EXISTS_缺表就建不靠版本阶梯`() {
        SearchSql.DDL.forEach { stmt ->
            assertTrue(
                "search.db 是可整包丢弃重建的缓存，DDL 必须幂等（设计 §6.4）：$stmt",
                stmt.startsWith("CREATE TABLE IF NOT EXISTS") || stmt.startsWith("CREATE INDEX IF NOT EXISTS"),
            )
        }
    }
}
