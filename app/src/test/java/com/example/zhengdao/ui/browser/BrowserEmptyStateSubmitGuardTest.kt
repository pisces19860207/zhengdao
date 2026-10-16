// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玄览 0 标签空态「提交必落地」门禁（㊽-D ＝ §B 79，2026-10-16）。
 *
 * 背景（workbuddy 第㉟刀真机验收报告 §7 N5，P3，报告标"待复核"）：玄览关掉全部标签后停在
 * 起始页，此时地址栏「前往」、地址栏 IME Go、起始页搜索框**三条路都不建标签**，输入 URL 后
 * 界面毫无反应（真机实证）。代码级根因已由蓝总管核过：三条路径都把 URL 交给 `remember` 下来的
 * engine（属于已销毁标签），且「被拦」提示写进 `active?.state`——而 `active` 恰为 null。
 *
 * 修复口径：三条路径统一走同一个入口 [BrowserScreen 的 tryNavigate]，由它**保证有承载引擎**
 * （0 标签时先建，走与标签条「＋」同一入口 `BrowserTabManager.obtain`），提示也不再写向 null。
 *
 * 源码级门禁（Compose + WebView，JVM 里起不来）：剥注释后断言，禁止"注释里声明已修"。
 */
class BrowserEmptyStateSubmitGuardTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun code(rel: String): String {
        val f = repoFile(rel)
        assertTrue("找不到 $rel（cwd=${File("").absolutePath}）", f.isFile)
        return f.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")
    }

    private fun original(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    private val screenRel = "src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt"
    private val startPageRel = "src/main/java/com/example/zhengdao/ui/browser/BrowserStartPage.kt"

    /** 抽出某个函数的正文（按花括号配平，从 `fun <名字>(` 起）。 */
    private fun body(funSignature: String): String {
        val src = code(screenRel)
        val start = src.indexOf(funSignature)
        assertTrue("源码里找不到 $funSignature", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("$funSignature 没有函数体", open > 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return src.substring(open)
    }

    // ① 入口签名：收 context（不再收 engine）—— 收 engine 就拿不到"建标签"的能力
    @Test
    fun `门禁_B补79_提交入口改收 context 不再收 engine`() {
        val src = code(screenRel)
        assertTrue(
            "提交入口签名应是 tryNavigate(context: Context, input: String)",
            src.contains("private fun tryNavigate(context: Context, input: String): Boolean {"),
        )
        assertFalse(
            "旧的 tryNavigate(engine, …) 形态回来了 —— 它就是 0 标签静默的根因",
            src.contains("tryNavigate(engine"),
        )
    }

    // ② 0 标签兜底：没有活跃引擎就先建（与标签条「＋」同一入口）
    @Test
    fun `门禁_B补79_0标签先建标签再加载`() {
        val fn = body("private fun tryNavigate(context: Context, input: String): Boolean {")
        assertTrue(
            "tryNavigate 缺 0 标签兜底（BrowserTabManager.activeEngine() ?: BrowserTabManager.obtain(context)）",
            fn.contains("BrowserTabManager.activeEngine() ?: BrowserTabManager.obtain(context)"),
        )
        assertTrue("兜底之后必须真的加载（loadUrl）", fn.contains("loadUrl("))
    }

    // ③ 三条提交路径都走同一个入口
    @Test
    fun `门禁_B补79_三条提交路径都走同一入口`() {
        val src = code(screenRel)
        assertTrue("地址栏「前往」没走 tryNavigate(context, urlInput)", src.contains("tryNavigate(context, urlInput)"))
        assertTrue("起始页搜索框没走 tryNavigate(context, input)", src.contains("tryNavigate(context, input)"))
        assertEquals(
            "带 context 的提交调用应恰 2 处（地址栏 + 起始页）",
            2,
            Regex("tryNavigate\\(context,").findAll(src).count(),
        )
    }

    // ④ 提示不再写向被捕获的 active（0 标签时 active 为 null ⇒ 提示丢失）
    @Test
    fun `门禁_B补79_被拦提示不再写向捕获的 active`() {
        val src = code(screenRel)
        assertFalse(
            "`active?.state?.onBlocked` 回来了 —— 0 标签时 active 为 null，提示会凭空消失",
            src.contains("active?.state?.onBlocked"),
        )
        assertTrue("缺统一的 reportBlocked 出口", src.contains("val reportBlocked: () -> Unit = {"))
        val blocked = src.substringAfter("val reportBlocked: () -> Unit = {").substringBefore("\n    }")
        assertTrue(
            "reportBlocked 必须重新取活跃标签（BrowserTabManager.activeTab()）",
            blocked.contains("BrowserTabManager.activeTab()"),
        )
        assertTrue(
            "reportBlocked 必须有无标签兜底（uiMsg = msg），否则空态仍然静默",
            blocked.contains("uiMsg = msg"),
        )
    }

    // ⑤ 解析路径仍只有一条（禁第二套归一化）
    @Test
    fun `门禁_B补79_归一化仍只有一条路径`() {
        val src = code(screenRel)
        assertEquals(
            "normalizeInput(input) 应恰 1 处调用（tryNavigate 内）",
            1,
            Regex("normalizeInput\\(input\\)").findAll(src).count(),
        )
        val startPage = code(startPageRel)
        listOf("normalizeInput", "tryNavigate", "Uri.parse", "loadUrl(").forEach {
            assertFalse("起始页不该自己解析/导航：$it", startPage.contains(it))
        }
    }

    // ⑥ 立账可追溯 ＋ 起始页提交仍挂在同一入口（原有判据 C 的加强）
    @Test
    fun `门禁_B补79_立账可追溯且起始页仍挂同一入口`() {
        val raw = original(screenRel)
        assertTrue("缺 ㊽-D 立账注释", raw.contains("㊽-D（§B 79"))
        assertTrue("缺 N5 取证来源", raw.contains("真机实证 N5"))
        val src = code(screenRel)
        val onSearch = src.substringAfter("onSearch = {", "")
        assertTrue("起始页 onSearch 没接到 tryNavigate", onSearch.take(300).contains("tryNavigate("))
    }
}
