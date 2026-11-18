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
 * 钉五件事（派工单【判据】里可静态断言的那几条）：
 * 1. **不新建索引、不改索引结构**：全包零 DDL 字面量（CREATE/ALTER/DROP）；
 *    **533 的索引只许从 [RecallIndexPort] 这一个缝进**，且只有 `SessionIndexPort.kt`
 *    能 import `baopu.msgsearch`（「复用 533 的索引，不另造」的正面锚）；
 * 2. **只读**：执行层零写面（execSQL/insert/beginTransaction/delete 都不许出现）；
 * 3. **查询词不进日志**：本包 `RunLog.log(` 的参数表里插值只许计数（`*.size`）；
 *    控制器那行必须走 [RecallLogLine]（它只接计数，用户原话进不去）；
 * 4. **不调模型**：提炼是确定性抽取（要点只能来自命中原文／533 的抽取式摘要），
 *    模型一来「找不到」就有了编造的口子；
 * 5. **SQL 文本只住 [RecallQuery]**：执行层（两个适配器＋时刻壳）只许调语句常量。
 */
class RecallSourceGateTest {

    /** 允许执行语句的壳（read-only thin shells）；除此之外任何文件都不许碰 SQL。 */
    private val executors = setOf("RecallDbSearch.kt", "RecallTimes.kt")

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

    // ── 1. 不新建索引、不改索引结构；533 索引唯一入口 ─────────────────────

    @Test
    fun `负例_全包零DDL_检索不建索引结构`() {
        val code = code(allSources())
        listOf("CREATE TABLE", "CREATE INDEX", "CREATE UNIQUE", "ALTER TABLE", "DROP TABLE", "DROP INDEX").forEach {
            assertFalse("recall 包出现「$it」：540 不建索引，复用 533 的倒排", code.contains(it))
        }
    }

    @Test
    fun `正面_533索引只有一个入口_只有SessionIndexPort认得msgsearch`() {
        val importers = sources.filterValues { code(it).contains("msgsearch") || code(it).contains("SessionSearchStore") }
        assertEquals("只许 SessionIndexPort.kt 搬 533 的类型（换底只改那一个文件）", setOf("SessionIndexPort.kt"), importers.keys)
        assertTrue(
            "索引主路径必须挂在 RecallIndexPort 上（不是自己另开一条查消息的路）",
            code(sources.getValue("RecallIndexSearch.kt")).contains("RecallIndexPort"),
        )
    }

    @Test
    fun `正面_语句只住查询层_执行只住壳`() {
        val query = code(sources.getValue("RecallQuery.kt"))
        assertTrue("语句文本在这一个文件里", query.contains("SELECT m.id"))
        // 除查询层与执行壳以外，任何文件不许出现 SQL 关键字（判据层不藏第二条查消息的路）
        val others = sources
            .filterKeys { it != "RecallQuery.kt" && it !in executors }
            .values.joinToString("\n") { code(it) }
        listOf("SELECT ", "INSERT ", "UPDATE ", "DELETE FROM", "rawQuery", "BaopuSql").forEach {
            assertFalse("「${it.trim()}」出现在查询层/执行壳之外", others.contains(it))
        }
        executors.forEach { name ->
            val shell = code(sources.getValue(name))
            assertEquals("$name 里 rawQuery 只许一处（唯一执行点）", 1, Regex("rawQuery").findAll(shell).count())
            assertFalse("$name 不写语句文本（语句只在查询层）", shell.contains("SELECT "))
        }
    }

    // ── 2. 执行层只读 ─────────────────────────────────────────────────────

    @Test
    fun `负例_执行层零写面`() {
        val shells = executors.joinToString("\n") { code(sources.getValue(it)) }
        listOf("execSQL", "insert(", "beginTransaction", "delete(", "setTransactionSuccessful").forEach {
            assertFalse("只读执行层出现「$it」", shells.contains(it))
        }
        assertTrue("检索适配器必须挂在这条缝上", code(sources.getValue("RecallDbSearch.kt")).contains("RecallSearchPort"))
        assertTrue("索引适配器必须挂在这条缝上", code(sources.getValue("RecallIndexSearch.kt")).contains("RecallSearchPort"))
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

    @Test
    fun `负例_读数行只接计数_要点原文进不去`() {
        val outcome = RecallEngine.Outcome(
            found = true,
            points = listOf(RecallEngine.Point("用户原话在这", 7L, 42L)),
            searchCalls = 2,
            clueGroupCount = 3,
        )
        val line = RecallLogLine.of(outcome, indexMessages = 188L, usedFallback = true)
        assertFalse("要点正文不许进日志", line.contains("用户原话在这"))
        assertTrue("计数必须在", line.contains("188") && line.contains("是"))
    }

    @Test
    fun `负例_控制器唤回那行只许RecallLogLine_不许自己插值`() {
        val ctl = code(repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText())
        val block = ctl.substringAfter("private fun runVagueRecall(")
            .substringBefore("private fun recentUserTexts(")
        assertEquals("唤回块里只许一行运行日志", 1, Regex("RunLog\\.log\\(").findAll(block).count())
        assertTrue("那行必须走 RecallLogLine（只接计数）", block.contains("RunLog.log(RecallLogLine.of("))
    }

    // ── 4. 不调模型 ───────────────────────────────────────────────────────

    @Test
    fun `负例_提炼不调模型_要点只能来自原文`() {
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
