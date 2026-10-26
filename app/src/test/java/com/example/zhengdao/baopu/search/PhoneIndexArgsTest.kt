// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §2「JVM 表驱动测……不许只写真机验」）：**入库薄壳的参数对账**。
//
// ## 为什么这个文件必须存在
// 本仓没有 Robolectric，`android.jar` 是 stub ⇒ JVM 里 SQLite 根本跑不起来。于是「列名与参数顺序对不对」
// 这件事**没有运行时兜底**：写错一位，SQLite 不报错，它只是把 `kind` 存进 `size` 那一列，
// 于是搜索结果「看着对其实错」，而且增量核对永远对不上（size 是个字符串，mtime 是个扩展名）。
// `PhoneIndex.kt` 文件头承诺过「顺序对不对由 PhoneIndexArgsTest 用『占位符个数 == 参数个数』＋
// 『逐位取出的字面量』钉」——本文件就是那句承诺的兑现处。
//
// ## 三层判据（从最便宜到最要紧）
// 1. [表驱动_占位符个数与建表列数对齐]：每条 UPSERT 的列名表 ⇔ 建表语句里的列序（去掉自增主键）；
//    占位符个数 ⇔ 列数。这一层不需要读源码，纯字符串对账。
// 2. [表驱动_绑定顺序逐位对账]：从 `PhoneIndex.kt` 的源码里**按括号配平**把 `arrayOf(…)` 的参数表取出来，
//    与本文件声明的「第 i 位绑哪个表达式」逐位比。声明表在测试里，不在生产码里——
//    生产码改顺序，测试必须跟着改并解释为什么，这就是这条门禁的用处。
// 3. [结构_壳里没有第二条删除语句／没有内联 SQL]：删行只写 `DELETE FROM doc`，`tok` 靠
//    `ON DELETE CASCADE`——写两条就可能出现「doc 删了 tok 没删」的半截账。
package com.example.zhengdao.baopu.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.zhengdao.core.RunLog

class PhoneIndexArgsTest {

    /** 一条写语句的对账项：语句 ⇔ 表 ⇔ 列名表 ⇔ 占位符个数。 */
    private data class Case(val sql: String, val table: String, val wantColumns: List<String>, val questionMarks: Int)

    // ── 1. 建表列序 ⇔ UPSERT 列名表 ⇔ 占位符个数 ────────────────────────────────

    @Test
    fun `表驱动_占位符个数与建表列数对齐`() {
        // 第四项「? 的个数」单独声明，不写成「等于列数」：`UPSERT_SCOPE` 的第四位是**字面 NULL**
        // （`revoked_at = NULL`，重新授权要把撤销标记清掉），所以它列数 4、占位符 3。
        // 把这条压成「列数 == ? 数」就等于把那句字面 NULL 判红——门禁不许逼人改写来凑数。
        val cases = listOf(
            Case(SearchSql.UPSERT_DOC, "doc", listOf("root_id", "rel_path", "kind", "size", "mtime", "title", "sha256_head", "indexed_at", "status"), 9),
            Case(SearchSql.UPSERT_TOK, "tok", listOf("token", "doc_id", "cnt"), 3),
            Case(SearchSql.UPSERT_WATERMARK, "watermark", listOf("root_id", "last_scan_at", "last_full_at", "files_seen", "files_skipped", "bytes_read"), 6),
            Case(SearchSql.UPSERT_SCOPE, "scope", listOf("abs_root", "kind", "granted_at", "revoked_at"), 3),
        )
        cases.forEach { (sql, table, wantColumns, questionMarks) ->
            val cols = insertColumns(sql)
            // ① 列名表与期望逐字同序（写进测试的这张表就是「真相的一次抄写」，改了要在这儿同步）
            assertEquals("$table 的 INSERT 列表漂了", wantColumns, cols)
            // ② 占位符个数与声明一致（少一个 ⇒ 有人把值写进语句文本了；多一个 ⇒ 绑不上）
            assertEquals("$table 的 ? 个数", questionMarks, sql.count { it == '?' })
            // ③ 列名必须真是建表语句里的那些，且**按建表声明的顺序**（自增主键不绑参，已排除）
            assertEquals("$table 的 INSERT 列表必须照建表列序", ddlColumns(table), cols)
        }
    }

    @Test
    fun `表驱动_查询语句的列序与游标读取对得上`() {
        // 游标是按**下标**取值的（`c.string(1)`／`c.long(0)`），所以 SELECT 的列序就是判据的一部分。
        // 这几条是 PhoneIndex 真正发的那几条；列序一改，壳里所有下标都要跟着改——本条逼着人来改对。
        assertEquals(listOf("doc_id", "rel_path", "size", "mtime", "status"), selectColumns(SearchSql.SELECT_DOCS_OF_ROOT))
        assertEquals(listOf("size", "mtime"), selectColumns(SearchSql.SELECT_DOC_STAT))
        assertEquals(listOf("root_id", "rel_path", "score"), selectColumns(SearchSql.selectSearch(2)))
        assertEquals(listOf("last_scan_at", "last_full_at", "files_seen", "files_skipped", "bytes_read"), selectColumns(SearchSql.SELECT_WATERMARK))
        assertEquals(listOf("root_id", "abs_root", "kind"), selectColumns(SearchSql.SELECT_ACTIVE_ROOTS))
        assertEquals(listOf("doc_id"), selectColumns(SearchSql.SELECT_DOC_ID))
    }

    @Test
    fun `表驱动_游标下标与列名逐位对上`() {
        // 上面那条钉「语句长什么样」，这条钉「壳里按下标取到的到底是哪一列」。
        // 下标写错 SQLite 一个字都不报：它只是把 `rel_path` 交到一个 `Long` 的位置上，
        // 于是 size 变成 0、增量核对永远「对不上」⇒ 每次全重扫。这一层是 JVM 里唯一能查的地方。
        val src = sourceOf("PhoneIndex.kt")
        val cases = listOf(
            Triple("DocLedgerRow", SearchSql.SELECT_DOCS_OF_ROOT, 4),
            Triple("MarkRow", SearchSql.SELECT_WATERMARK, 5),
            Triple("TermHit", SearchSql.selectSearch(2), 3),
        )
        cases.forEach { (ctor, sql, wantReads) ->
            val reads = cursorReads(src, ctor)
            val cols = selectColumns(sql)
            assertEquals("$ctor 从游标取了几列", wantReads, reads.size)
            reads.forEach { (field, idx) ->
                assertTrue("$ctor 的下标 $idx 越界（语句只有 ${cols.size} 列）", idx in cols.indices)
                assertEquals("$ctor 用下标 $idx 取的那一列", cols[idx], snake(field))
            }
        }
        // 位置参数（没有 `field =` 可锚）的两条读数：把整段按原样钉住，改列序就必须在这儿同步。
        val stat = code(src)
        assertTrue("SizeMtime 只能按 (size, mtime) 这个下标序取", stat.contains("SizeMtime(c.long(0), c.long(1))"))
        assertTrue(
            "ActiveRoot 只能按 (root_id, abs_root, kind) 这个下标序取",
            Regex("ScopeKind\\.fromTag\\(c\\.string\\(2\\)\\)[\\s\\S]{0,80}ActiveRoot\\(c\\.long\\(0\\), c\\.string\\(1\\), kind\\)").containsMatchIn(stat),
        )
    }

    // ── 2. 绑定顺序逐位对账（本文件的正题） ─────────────────────────────────────

    @Test
    fun `表驱动_绑定顺序逐位对账`() {
        // 跑在**剥掉注释**的原文上：本文件的说明里也写了 `[SearchSql.SELECT_DOC_ID]` 这种引用，
        // 不剥就会锚到注释那一处，取到**下一条**语句的参数表（实测：拿回 markOf 的一个参数，
        // 于是这条对账比的是别人的顺序，还正好「配得上」——静默失效的样板）。
        val src = code(sourceOf("PhoneIndex.kt"))
        val truth = listOf(
            "UPSERT_DOC" to listOf("rootId", "doc.relPath", "doc.kind", "doc.size", "doc.mtime", "doc.title", "doc.sha256Head", "now", "doc.status.tag"),
            "SELECT_DOC_ID" to listOf("rootId.toString()", "doc.relPath"),
            "UPSERT_TOK" to listOf("token", "docId", "cnt"),
            "DELETE_DOC_BY_ID" to listOf("id"),
            "UPSERT_WATERMARK" to listOf("w.rootId", "w.lastScanAt", "w.lastFullAt", "w.filesSeen", "w.filesSkipped", "w.bytesRead"),
            "SELECT_WATERMARK" to listOf("rootId.toString()"),
            "SELECT_DOC_STAT" to listOf("rootId.toString()", "rel"),
            "SELECT_DOCS_OF_ROOT" to listOf("rootId.toString()"),
        )
        truth.forEach { (stmt, want) ->
            val args = argsAfter(src, stmt)
            assertEquals("PhoneIndex 里 `$stmt` 的绑定表与期望不同序（或个数不同）", want, args)
            assertEquals(
                "$stmt 的绑定个数必须等于语句里的 ? 个数",
                SearchSql.statementOf(stmt).count { it == '?' }, args.size,
            )
        }
    }

    @Test
    fun `表驱动_词表按整词绑三列`() {
        // `tok(token, doc_id, cnt)`：一次一篇的每个词一行；主键 (token, doc_id) ⇒ 重复入库是刷新不是追加。
        val sql = SearchSql.UPSERT_TOK
        assertTrue("倒排行必须用 INSERT OR REPLACE（同词同篇只留最新计数）", sql.startsWith("INSERT OR REPLACE INTO tok"))
        assertEquals("tok 的 INSERT 列表", listOf("token", "doc_id", "cnt"), insertColumns(sql))
        // cnt 的来源在算式层（PhoneRank.tokenCounts），本批不在 SQL 里加权 ⇒ 三条语句都不许出现乘法
        listOf(SearchSql.UPSERT_TOK, SearchSql.UPSERT_DOC, SearchSql.selectSearch(3)).forEach {
            assertFalse("SQL 文本里出现乘法：权重应该折进 cnt", it.contains("*"))
        }
    }

    // ── 3. 壳的形状（删一半／自己拼 SQL／把异常抄进日志） ─────────────────────────

    @Test
    fun `结构_删行只写一条语句`() {
        val src = code(sourceOf("PhoneIndex.kt"))
        assertTrue("删 doc 行必须走 SearchSql 的那条常量", src.contains("SearchSql.DELETE_DOC_BY_ID"))
        assertFalse("不许另写 DELETE FROM tok：那是「doc 删了 tok 没删」的半截账（级联才是唯一真源）", src.contains("DELETE FROM tok"))
        assertFalse("不许另写 DELETE FROM doc", src.contains("DELETE FROM doc"))
        // 级联是建表时声明的，本批一条都没删掉它
        assertTrue(SearchSql.DDL.any { it.contains("REFERENCES doc(doc_id) ON DELETE CASCADE") })
        assertTrue(SearchSql.DDL.any { it.contains("REFERENCES scope(root_id) ON DELETE CASCADE") })
    }

    @Test
    fun `结构_壳里没有内联SQL`() {
        // 全部语句必须来自 SearchSql／PhoneJump 的常量：语句文本散进壳里，SearchSqlTest 那条
        // 「列名逐字对账」就管不到执行现场了（`PhoneIndex.kt` 文件头第一条就是这个理由）。
        val allowedConsts = Regex("SearchSql\\.[A-Za-z_]+|PhoneJump\\.SELECT_ARTIFACT_SESSION|SearchSql\\.selectSearch")
        listOf("PhoneIndex.kt", "PhoneSearch.kt", "SearchDb.kt").forEach { name ->
            val src = code(sourceOf(name))
            Regex("\"(SELECT |INSERT |UPDATE |DELETE |CREATE )").findAll(src).forEach { m ->
                throw AssertionError("$name 里出现了内联 SQL 字面量（从 ${m.value} 开始）：语句只许来自 SearchSql 的常量")
            }
            // 反证本用例不是空跑：这三个文件确实都在用 SearchSql 的常量（不然是「没语句可拼」才没红）
            assertTrue("$name 没引用任何 SearchSql 常量？", allowedConsts.findAll(src).count() > 0)
        }
    }

    @Test
    fun `结构_一片一个事务`() {
        // 派工单 §1①「边扫边入库」的落点：刷盘单位是片，不是整根、也不是单文件。
        val src = code(sourceOf("PhoneIndex.kt"))
        assertTrue("putShard 必须走 transact（一片一个事务）", src.contains("fun putShard(rootId: Long, docs: List<IndexedDoc>, now: Long): Boolean"))
        assertTrue("事务由写的那一侧包（仓内形状：谁写谁开事务，OpenHelper 本体只给句柄）", src.contains("private fun transact(block: (SQLiteDatabase) -> Unit): Boolean"))
        assertTrue(
            "三件套必须挂在 db.write 的 lambda 里（UseKtx 只在「带 SQLiteDatabase 形参的私有方法」形状下点名 beginTransaction，lambda 形状全绿是仓内九处现数）",
            Regex("db\\.write \\{ d ->\\s*d\\.beginTransaction\\(\\)").containsMatchIn(src),
        )
        val body = src.substringAfter("fun putShard(").substringBefore("fun deleteDocs")
        assertTrue("片内必须是一个 transact", body.contains("transact { d ->"))
        assertTrue("空片直接算成（不占事务）", body.contains("if (docs.isEmpty()) return true"))
        assertTrue("doc_id 查不回来 ⇒ 一篇词都不写", body.contains("if (docId > 0L)"))
        assertFalse("壳里不许出现事务嵌套之外的 commit 手工调用", body.contains("setTransactionSuccessful"))
        // 三件套全文件只许一份：多一处就是多一个「改了分支忘了 endTransaction」的地方
        assertEquals("全文件只许一处 beginTransaction()", 1, Regex("beginTransaction\\(\\)").findAll(src).count())
    }

    @Test
    fun `结构_读不出就当没有`() {
        // fail closed：根清单读失败 ⇒ 按「没有授予的根」处理（这一次一行都不扫）；
        // 单篇读数读失败 ⇒ 按「没对上报」处理（多读一遍）。两个方向都不许反过来。
        val src = code(sourceOf("PhoneIndex.kt"))
        assertTrue("activeRoots 失败必须给空表", src.contains("RunLog.log(\"读侧根清单没读出来：本次扫描按「没有授予的根」处理（一行都不扫）\")"))
        assertTrue("docStat 失败必须给 null（不许给 SizeMtime(0,0) 那种假对上）", src.contains("fun docStat(rootId: Long, rel: String): SizeMtime?"))
        assertFalse("docStat 不许返回一个非 null 的默认读数", Regex("fun docStat[\\s\\S]{0,400}?getOrDefault\\(SizeMtime").containsMatchIn(src))
        assertTrue("空词表不发语句（IN () 是语法错，而「只打了标点」不是错误）", src.contains("if (tokens.isEmpty()) return emptyList()"))
    }

    // ── helper ────────────────────────────────────────────────────────────────

    /** `INSERT INTO t(a, b, c)` 的列名表（按源码文本，不碰 SQLite）。 */
    private fun insertColumns(sql: String): List<String> =
        sql.substringAfter("INTO ").substringAfter("(").substringBefore(")").split(",")
            .map { it.trim() }.filter { it.isNotEmpty() }

    /** `SELECT x, y FROM …` 的列名表（`AS` 取别名；`d.rel_path` 取点号后面那一截）。 */
    private fun selectColumns(sql: String): List<String> =
        sql.substringAfter("SELECT ").substringBefore(" FROM").split(",")
            .map { it.trim().substringAfterLast(" AS ").trim() }
            .map { if (it.contains(".")) it.substringAfterLast(".").trim() else it }
            .map { it.replace(Regex("\\s+"), " ") }

    /** 建表语句里的列名（按声明顺序）；约束行不算列，`AUTOINCREMENT` 主键不绑参 ⇒ 排除。 */
    private fun ddlColumns(table: String): List<String> {
        val ddl = SearchSql.DDL.first { it.contains("CREATE TABLE IF NOT EXISTS $table(") }
        // 列名必须容得下数字：`sha256_head` 用 `[a-z_]+` 匹配不到（"sha" 后面是 '2' 不是空白），
        // 于是 doc 的列表静默少一列 —— 少的那一列恰好是最容易绑错的一位。实测踩到过。
        val col = Regex("(?m)^\\s+([a-z_][a-z0-9_]*)\\s+[A-Z]")
        return ddl.lines().filter { col.containsMatchIn(it) && !it.contains("AUTOINCREMENT") }.map { col.find(it)!!.groupValues[1] }
    }

    /** 驼峰字段名 → 库里的列名（`relPath`→`rel_path`）：游标下标与列序对账用的桥。 */
    private fun snake(field: String): String = field.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }

    /**
     * 抠出壳里 `Ctor(field = c.long(i), …)` 的 (字段, 下标) 表。
     *
     * 同名构造器可能出现多次（一次是注释或类型引用）⇒ 不取第一个，取**参数里带 `c.long(`/`c.string(`
     * 的那一次**：锚在真正的游标读取上，才不会因为一句注释里提到类名就把整条对账带偏。
     */
    private fun cursorReads(src: String, ctor: String): List<Pair<String, Int>> {
        var from = 0
        while (true) {
            val at = src.indexOf("$ctor(", from)
            assertTrue("$ctor(...) 在 PhoneIndex.kt 里找不到（壳改名了？这条对账得回来重写）", at >= 0)
            val inner = balancedInner(src, src.indexOf('(', at))
            if (inner.contains("c.long(") || inner.contains("c.string(")) {
                return splitTopLevel(inner).mapNotNull { part ->
                    val m = Regex("([A-Za-z]+)\\s*=\\s*c\\.(?:long|string)\\((\\d+)\\)").find(part)
                    m?.let { it.groupValues[1] to it.groupValues[2].toInt() }
                }
            }
            from = at + ctor.length
        }
    }

    /** 从**左括号本身**开始配平，返回括号内的原文（不含两端括号）。 */
    private fun balancedInner(src: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(open + 1, i)
                }
            }
            i++
        }
        throw AssertionError("从 $open 开始括号没配平（原文尾部：${src.takeLast(120)}）")
    }

    /**
     * 取出源码里 `SearchSql.<stmt>` 之后**第一个** `arrayOf(…)` 的参数表（括号配平，按逗号切）。
     *
     * 不用正则一次匹配整段：参数里有 `rootId.toString()` 这种带括号的表达式，
     * `[^)]*` 会在第一个右括号处截断——那正是这条门禁要防的「静默少比对一项」。
     */
    private fun argsAfter(src: String, stmt: String): List<String> {
        val at = src.indexOf("SearchSql.$stmt")
        assertTrue("PhoneIndex.kt 里没有 SearchSql.$stmt（壳改了？那这条对账得回来重写）", at >= 0)
        val arrStart = src.indexOf("arrayOf", at)
        assertTrue("$stmt 之后找不到 arrayOf", arrStart >= 0)
        return splitTopLevel(balancedInner(src, src.indexOf('(', arrStart))).map { it.trim() }
    }

    /** 只在深度 0 处切逗号（括号里的逗号是嵌套调用的参数分隔）。 */
    private fun splitTopLevel(text: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var depth = 0
        text.forEach { ch ->
            when {
                ch == '(' -> { depth++; buf.append(ch) }
                ch == ')' -> { depth--; buf.append(ch) }
                ch == ',' && depth == 0 -> { out += buf.toString(); buf.setLength(0) }
                else -> buf.append(ch)
            }
        }
        if (buf.isNotBlank()) out += buf.toString()
        return out.map { it.replace(Regex("\\s+"), " ") }
    }

    /** 语句常量名 → SQL 文本（本测试只需要这几条；写全等于把 SearchSql 抄第二遍）。 */
    private fun SearchSql.statementOf(name: String): String = when (name) {
        "UPSERT_DOC" -> UPSERT_DOC
        "UPSERT_TOK" -> UPSERT_TOK
        "UPSERT_WATERMARK" -> UPSERT_WATERMARK
        "SELECT_DOC_ID" -> SELECT_DOC_ID
        "SELECT_DOC_STAT" -> SELECT_DOC_STAT
        "SELECT_DOCS_OF_ROOT" -> SELECT_DOCS_OF_ROOT
        "SELECT_WATERMARK" -> SELECT_WATERMARK
        "DELETE_DOC_BY_ID" -> DELETE_DOC_BY_ID
        else -> throw AssertionError("表驱动_绑定顺序逐位对账 里出现了没登记的语句：$name")
    }

    private fun sourceOf(name: String): String = repoFile("src/main/java/com/example/zhengdao/baopu/search/$name").readText()

    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun repoFile(rel: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf(File(dir, "app/$rel"), File(dir, rel)).firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
    }
}
