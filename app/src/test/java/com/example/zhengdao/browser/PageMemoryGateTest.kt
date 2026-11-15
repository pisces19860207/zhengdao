// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话级页面记忆的**接线与落盘**门禁（651）。
 *
 * 为什么纯判据之外还要这一份：判据对、没人调用，是本项目审计里最常见的假完工形状
 * （同 `BrowserSnapshotGateTest` 的理由）。这里钉五件事：
 * ① **只记 URL 与标题**：建表语句的列与 [PageMemoryPolicy.COLUMNS] 逐列一致（多一列就红），
 *    且列里不许出现任何"正文"类名字；
 * ② 记的时机是 `onPageFinished`（这一页真的到齐了），**不是** `onPageStarted`
 *    （失败/被拦/半途取消的加载不该出现在"最近 20 页"里）；
 * ③ 写入的入口只有两个字符串参数（url / title）——想夹带正文得先改签名；
 * ④ **重启保留**：落 SQLite 文件（`baopu/xuanlan_page.db`），不是内存表；
 * ⑤ 两种清空都在同一层（单会话 / 全清），界面不会自己拼 DELETE。
 */
class PageMemoryGateTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }

    private fun stripComments(s: String): String = s
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 取 `marker` 之后第一个花括号块的内容（与 BrowserSnapshotGateTest 同款）。 */
    private fun blockAfter(s: String, marker: String): String {
        val at = s.indexOf(marker)
        assertTrue("找不到标记 `$marker`", at >= 0)
        val open = s.indexOf('{', at)
        assertTrue("`$marker` 之后没有函数体 `{`", open > at)
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(open + 1, i)
                }
            }
            i++
        }
        throw AssertionError("`$marker` 的花括号没配平")
    }

    private fun sqlSource() = stripComments(src("src/main/java/com/example/zhengdao/browser/PageMemorySql.kt"))

    private fun storeSource() = stripComments(src("src/main/java/com/example/zhengdao/ui/browser/PageMemoryStore.kt"))

    private fun managerSource() = stripComments(src("src/main/java/com/example/zhengdao/ui/browser/BrowserTabManager.kt"))

    /** 建表语句里的列名（按出现顺序）。 */
    private fun ddlColumns(): List<String> {
        val s = sqlSource()
        val start = s.indexOf("CREATE TABLE IF NOT EXISTS xuanlan_page (")
        assertTrue("建表语句不见了", start >= 0)
        val body = s.substring(start).substringAfter('(').substringBefore(')')
        return body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore(' ').trim() }
            .filter { it.isNotEmpty() }
    }

    // ── ① 只记 URL 与标题 ────────────────────────────────────────────────────

    @Test
    fun `判据_建表的列与字段表逐列一致_多一列就红`() {
        assertEquals(
            "建表列必须与 PageMemoryPolicy.COLUMNS 完全一致（顺序也一致）：想记正文先改这两处",
            PageMemoryPolicy.COLUMNS,
            ddlColumns(),
        )
    }

    @Test
    fun `判据_列里不许出现任何正文类名字`() {
        val cols = ddlColumns().map { it.lowercase() }
        for (banned in listOf("body", "content", "text", "html", "snippet", "excerpt", "dom", "markdown")) {
            assertFalse("列名里出现了 $banned —— 只记 URL 与标题", cols.any { it.contains(banned) })
        }
    }

    @Test
    fun `判据_INSERT 只写四个值_没有夹带位`() {
        val s = sqlSource()
        val insert = s.substringAfter("const val INSERT =").substringBefore("\n\n")
        assertTrue(
            "INSERT 的列清单必须正好是 session_id,url,title,visited_at：$insert",
            insert.contains("INSERT INTO xuanlan_page(session_id,url,title,visited_at) VALUES(?,?,?,?)"),
        )
        assertEquals("占位符必须正好 4 个", 4, Regex("\\?").findAll(insert).count())
    }

    // ── ② 记的时机 ──────────────────────────────────────────────────────────

    @Test
    fun `判据_写在 onPageFinished_不写在 onPageStarted`() {
        val m = managerSource()
        val finished = blockAfter(m, "override fun onPageFinished(url: String)")
        assertTrue("页面加载完成时必须记一条", finished.contains("PageMemoryStore.record("))
        val started = blockAfter(m, "override fun onPageStarted(url: String)")
        assertFalse("开始加载那一刻不许记（失败/被拦的加载会污染列表）", started.contains("PageMemoryStore.record("))
    }

    @Test
    fun `判据_记的那一条带会话号且预览页签不记`() {
        val m = managerSource()
        val finished = blockAfter(m, "override fun onPageFinished(url: String)")
        assertTrue("必须带会话号（按会话分桶）", finished.contains("pageMemorySessionOf()"))
        assertTrue("必须把 isPreview 传下去（预览页签不是网页）", finished.contains("isPreview = tab.state.isPreview()"))
        assertTrue("会话号必须是可注入的闭包（唯一真相在 controller.sessionId）", m.contains("var pageMemorySessionOf"))
    }

    // ── ③ 写入入口只有 url / title ──────────────────────────────────────────

    @Test
    fun `判据_写入入口只有 url 与 title 两个字符串`() {
        // 签名在 `{` **之前**（参数表不在函数体里）⇒ 取 marker 到第一个 `{` 之间那一段
        val src = storeSource()
        val at = src.indexOf("fun record(")
        assertTrue("找不到 record 的签名", at >= 0)
        val sig = src.substring(at, src.indexOf('{', at))
        assertTrue("要有 url: String", sig.contains("url: String"))
        assertTrue("要有 title: String", sig.contains("title: String"))
        // 带前置空格再比：`context: Context` 里含 "text:"，不加空格会自己拦自己
        for (banned in listOf(" body", " content:", " html:", " text:", " snippet", " dom:")) {
            assertFalse("写入入口不许有 $banned 参数（正文无从进来）", sig.contains(banned))
        }
    }

    @Test
    fun `判据_两条既有脱敏判据都过一遍`() {
        val s = storeSource()
        assertTrue("URL 必须过 sanitizeUrlForStore（判据只有一处）", s.contains("sanitizeUrlForStore"))
        assertTrue("标题必须过 redactTabTitle（与桥同一套）", s.contains("redactTabTitle"))
    }

    // ── ④ 重启保留：落文件，不落内存 ────────────────────────────────────────

    @Test
    fun `判据_落 SQLite 文件而不是内存表`() {
        val db = src("src/main/java/com/example/zhengdao/browser/XuanlanPageDb.kt")
        assertTrue("必须是 SQLiteOpenHelper 壳", db.contains("SQLiteOpenHelper"))
        assertTrue("库名必须是 xuanlan_page.db", db.contains("const val DB_NAME = \"xuanlan_page.db\""))
        assertTrue("落点必须在 filesDir 下的 baopu/", db.contains("File(File(filesDir, DIR_BAOPU), DB_NAME)"))
        assertTrue("要有壳版本（结构变了能重建）", db.contains("const val VERSION = 1"))
    }

    @Test
    fun `判据_读回按会话取最近 N 条_顺序由 id 承担`() {
        val s = sqlSource()
        assertTrue("必须有按会话取最近 N 条的语句", s.contains("const val SELECT_SESSION"))
        assertTrue("顺序必须由 id 承担（时间戳会撞）", s.contains("ORDER BY id DESC LIMIT ?"))
        assertTrue("外层要翻回 oldest → newest", s.contains(") ORDER BY id ASC"))
        assertTrue("会话索引必须建", s.contains("CREATE INDEX IF NOT EXISTS idx_xuanlan_page_session"))
    }

    // ── ⑤ 两种清空 ──────────────────────────────────────────────────────────

    @Test
    fun `判据_单会话清空与全清各一条_都在同一层`() {
        val s = storeSource()
        assertTrue("要有单会话清空", s.contains("suspend fun clearSession("))
        assertTrue("单会话清空走 DELETE_SESSION", s.contains("PageMemorySql.DELETE_SESSION"))
        assertTrue("要有全清", s.contains("suspend fun clearAll("))
        assertTrue("全清走 DELETE_ALL", s.contains("PageMemorySql.DELETE_ALL"))
        assertTrue("全清要能说清清了几个会话", s.contains("COUNT_SESSIONS"))
        // 界面不许自己拼 DELETE（判据只在一层）
        val sheet = stripComments(src("src/main/java/com/example/zhengdao/ui/browser/XuanlanMemorySheet.kt"))
        assertFalse("半屏不许直接碰 SQL", sheet.contains("DELETE FROM"))
    }

    // ── ⑥ 中英键齐全 ────────────────────────────────────────────────────────

    @Test
    fun `判据_记忆半屏的中英键齐全`() {
        val zh = src("src/main/res/values/strings.xml")
        val en = src("src/main/res/values-en/strings.xml")
        for (k in listOf(
            "xuanlan_memory_menu",
            "xuanlan_memory_title",
            "xuanlan_memory_subtitle",
            "xuanlan_memory_empty",
            "xuanlan_memory_clear_session",
            "xuanlan_memory_clear_all",
            "xuanlan_memory_confirm_all",
        )) {
            assertTrue("中文缺键 $k", zh.contains("name=\"$k\""))
            assertTrue("英文缺键 $k", en.contains("name=\"$k\""))
        }
        // 两侧键集对称（本批新增的 xuanlan_* 一条不许只在一侧）
        val keyRegex = Regex("<string name=\"(xuanlan_[a-z_]+)\"")
        val zhKeys = keyRegex.findAll(zh).map { it.groupValues[1] }.toSet()
        val enKeys = keyRegex.findAll(en).map { it.groupValues[1] }.toSet()
        assertEquals("zh/en 的 xuanlan_* 键集合必须一致", zhKeys, enKeys)
    }
}
