// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.recall

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 540 的源码门禁（扫 `baopu/recall` 全包源码文本，`PhoneSearchPrivacyGateTest` 同款手法）。
 *
 * 钉四件事（派工单【判据】里可静态断言的那几条）：
 * 1. **不新建索引、不改索引结构**：全包零 DDL 字面量（CREATE/ALTER/DROP）——
 *    检索只许 SELECT 既有表，等 533 的倒排，不另造一套；
 * 2. **只读**：适配层零写面（execSQL/insert/beginTransaction/delete 都不许出现）；
 * 3. **查询词不进日志**：本包每一处 `RunLog.log(` 的参数表里，插值只许是「数量读数」
 *    （`*.size` 一类），用户话里切出来的词一个字都不许进去；
 * 4. **不调模型**：提炼是确定性抽取（要点只能来自命中原文），模型一来「找不到」
 *    就有了编造的口子。
 */
class RecallSourceGateTest {

    private val sources: Map<String, String> by lazy {
        val dir = repoFile("src/main/java/com/example/zhengdao/baopu/recall")
        dir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().associate { it.name to it.readText() }
    }

    private fun allSources(): String = sources.entries.sortedBy { it.key }.joinToString("\n") { it.value }

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

    // ── 1. 不新建索引、不改索引结构 ───────────────────────────────────────

    @Test
    fun `负例_全包零DDL_检索不建索引结构`() {
        val code = code(allSources())
        listOf("CREATE TABLE", "CREATE INDEX", "CREATE UNIQUE", "ALTER TABLE", "DROP TABLE", "DROP INDEX").forEach {
            assertFalse("recall 包出现「$it」：540 不建索引，等 533 的倒排", code.contains(it))
        }
    }

    @Test
    fun `正面_语句只住查询层_执行只住适配层`() {
        val query = code(sources.getValue("RecallQuery.kt"))
        assertTrue("语句文本在这一个文件里", query.contains("SELECT m.id"))
        // 其余文件不许再出现任何 SQL 关键字（判据层不藏第二条查消息的路）
        val others = sources
            .filterKeys { it != "RecallQuery.kt" && it != "RecallDbSearch.kt" }
            .values.joinToString("\n") { code(it) }
        listOf("SELECT ", "INSERT ", "UPDATE ", "DELETE FROM", "rawQuery", "BaopuSql").forEach {
            assertFalse("「${it.trim()}」出现在查询层/执行层之外", others.contains(it))
        }
        val adapter = code(sources.getValue("RecallDbSearch.kt"))
        assertEquals(
            "rawQuery 只许在适配层出现一次（唯一执行点）",
            1,
            Regex("rawQuery").findAll(adapter).count(),
        )
        assertFalse("适配层不写语句文本（语句只在查询层）", adapter.contains("SELECT "))
    }

    // ── 2. 适配层只读 ─────────────────────────────────────────────────────

    @Test
    fun `负例_适配层零写面`() {
        val adapter = code(sources.getValue("RecallDbSearch.kt"))
        listOf("execSQL", "insert(", "beginTransaction", "delete(", "setTransactionSuccessful").forEach {
            assertFalse("只读适配层出现「$it」", adapter.contains(it))
        }
        assertTrue("适配层必须挂在这条缝上（533 合入后换底的就是它）", adapter.contains("RecallSearchPort"))
    }

    @Test
    fun `负例_判据层不认数据库句柄_引擎只吃端口`() {
        val engine = code(sources.getValue("RecallEngine.kt"))
        val trigger = code(sources.getValue("VagueTrigger.kt"))
        listOf("BaopuDb", "SQLiteDatabase", "rawQuery").forEach {
            assertFalse("纯判据层出现「$it」", engine.contains(it) || trigger.contains(it))
        }
        assertTrue("引擎的唯一对外动作是这条检索缝", engine.contains("RecallSearchPort"))
    }

    // ── 3. 查询词不进日志 ─────────────────────────────────────────────────

    @Test
    fun `负例_本包日志的插值只许数量读数`() {
        val interpolations =
            Regex("\\$\\{([^}]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)")
        val logs = Regex("RunLog\\.log\\(").findAll(code(allSources())).count()
        assertTrue("造场景：recall 包至少有一行运行日志（没有的话这条是空跑）", logs >= 1)
        sources.values.forEach { src ->
            src.lines().filter { it.contains("RunLog.log(") }.forEach { line ->
                interpolations.findAll(line).forEach { m ->
                    val expr = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
                    assertTrue(
                        "日志行插了非计数读数「$expr」：「${line.trim()}」",
                        expr.endsWith(".size"),
                    )
                }
            }
        }
    }

    // ── 4. 不调模型 ───────────────────────────────────────────────────────

    @Test
    fun `负例_提炼不调模型_要点只能来自命中原文`() {
        val code = code(allSources())
        listOf("LlmClient", "ModelRouter", "chat(", "complete(", "prompt").forEach {
            assertFalse("recall 包出现「$it」：确定性提炼，模型一进来「没找到」就有得编", code.contains(it))
        }
    }

    // ── 5. 判据常量钉死（派工单数字不许漂） ─────────────────────────────────

    @Test
    fun `常量_检索上限3_要点5_每条60字`() {
        assertEquals(3, RecallEngine.MAX_SEARCH_CALLS)
        assertEquals(5, RecallEngine.MAX_POINTS)
        assertEquals(60, RecallEngine.MAX_POINT_CHARS)
        val engine = sources.getValue("RecallEngine.kt")
        assertTrue("上限常量带「硬顶」注解文字，防止哪天被当成软预算", engine.contains("硬顶"))
    }
}
