// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百七十二批 L4 · **接线与迁移**门禁（J-L4-4/5/6/7）。
 *
 * 为什么单独一份：快照的纯判据在 `BrowserSnapshotPolicyTest`（JVM 表驱动），
 * 但"判据对、没人调用"是本项目审计里最常见的假完工形状（见 `BrowserRendererGoneGateTest` 的同款理由）。
 * 这里钉四件事：
 * ① 迁移升到 v19 且 `onCreate`／`onUpgrade` **两个入口都挂**；
 * ② **恢复链路绝不自动联网**（`loadUrl` 只许出现在"用户点了打开"那一处）；
 * ③ 退后台那一支必须有快照调用（进程被杀前最后的机会）；
 * ④ 中英键齐全（少一个就是英文包里空白）。
 */
class BrowserSnapshotGateTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

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

    private fun dbSource() = src("src/main/java/com/example/zhengdao/baopu/BaopuDb.kt")
    private fun managerSource() = src("src/main/java/com/example/zhengdao/ui/browser/BrowserTabManager.kt")
    private fun holderSource() = src("src/main/java/com/example/zhengdao/ui/browser/BrowserHolder.kt")

    // ── J-L4-4 迁移 ───────────────────────────────────────────────────────

    @Test
    fun `J-L4-4 迁移升到 v19 且两个入口都挂`() {
        val s = dbSource()
        assertTrue("BaopuDb.VERSION 应为 19", s.contains("const val VERSION = 19"))
        assertTrue("必须有 applyV19Migration 迁移体", s.contains("private fun applyV19Migration("))
        val calls = Regex("applyV19Migration\\(db\\)").findAll(s).count()
        assertTrue(
            "onCreate 与 onUpgrade 两处都必须调 applyV19Migration（只挂一处 ⇒ 新装或老库必有一边缺表），实际 $calls 处",
            calls >= 2,
        )
        assertTrue("onUpgrade 里要有 oldVersion < 19 的门槛", s.contains("if (oldVersion < 19) applyV19Migration(db)"))

        val sql = src("src/main/java/com/example/zhengdao/browser/BrowserTabSql.kt")
        assertTrue("DDL 建表必须 IF NOT EXISTS（迁移可重复执行）", sql.contains("CREATE TABLE IF NOT EXISTS browser_tab"))
        assertTrue("LRU 索引也要 IF NOT EXISTS", sql.contains("CREATE INDEX IF NOT EXISTS idx_browser_tab_lru"))
        for (col in listOf("url", "title", "scroll_x", "scroll_y", "updated_at", "last_used_at")) {
            assertTrue("browser_tab 必须有列 $col", sql.contains(col))
        }
    }

    // ── J-L4-5 恢复链路绝不自动联网 ────────────────────────────────────────

    @Test
    fun `J-L4-5 恢复链路不许出现 loadUrl`() {
        val m = managerSource()
        for (fn in listOf("fun restoreFromSnapshot(", "private fun materializeRestored(")) {
            val body = blockAfter(m, fn)
            assertFalse(
                "$fn 里不许 loadUrl/reload —— 恢复只是把地址放进标签，点了才算联网",
                body.contains("loadUrl(") || body.contains("reload("),
            )
        }
        val load = blockAfter(m, "fun loadRestoredTab(")
        assertTrue("用户点了「打开」这一处才允许 loadUrl（正向控制）", load.contains("loadUrl("))
        assertTrue(
            "界面上的恢复卡片必须走 loadRestoredTab（不是自己调引擎）",
            src("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt")
                .contains("BrowserTabManager.loadRestoredTab("),
        )
    }

    // ── J-L4-6 退后台必须快照 ─────────────────────────────────────────────

    @Test
    fun `J-L4-6 退后台那一支必须有快照调用`() {
        val body = blockAfter(holderSource(), "fun setAppForeground(")
        assertTrue(
            "setAppForeground(false) 分支里必须调 snapshotAll —— 那是进程被杀前最后一次写盘机会",
            body.contains("snapshotAll("),
        )
        assertTrue(
            "管理器必须真的实现 snapshotAll",
            managerSource().contains("fun snapshotAll("),
        )
    }

    // ── J-L4-7 中英键齐全 ─────────────────────────────────────────────────

    @Test
    fun `J-L4-7 恢复卡片与恢复片的中英键齐全`() {
        val zh = src("src/main/res/values/strings.xml")
        val en = src("src/main/res/values-en/strings.xml")
        for (k in listOf(
            "browser_restored_title",
            "browser_restored_body",
            "browser_restored_open",
            "browser_restored_chip_prefix",
        )) {
            assertTrue("中文缺键 $k", zh.contains("name=\"$k\""))
            assertTrue("英文缺键 $k", en.contains("name=\"$k\""))
        }
    }
}
