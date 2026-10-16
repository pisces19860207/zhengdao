// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第51刀（§B 82 的 N7）门禁 —— 地址栏/起始页输入策略的两半：
 * ① **行为测试**：直接调纯函数 [BrowserUrlPolicy.shouldReject] / [BrowserUrlPolicy.normalize]，
 *    喂真实输入、断言真实结果（第㊾刀真机那几条原样进来）；
 * ② **接线门禁**：`BrowserScreen.tryNavigate` 必须在归一化**之前**调 [BrowserUrlPolicy.shouldReject]
 *    —— N7 的本质是"顺序错 ⇒ 分支不可达"，只测纯函数测不出来。
 *
 * 与既有门禁的分工：`BrowserEmptyStateSubmitGuardTest` 管"唯一入口 + 0 标签兜底"（㊽-D），
 * 本文件管"顺序 + scheme 判据"（N7），两者都不许被后人改回去。
 */
class BrowserUrlPolicyTest {

    // ── ① 行为：必须拦下的显式 scheme ──

    @Test
    fun `N7_显式非 http 协议一律拦下`() {
        listOf(
            "intent://x", // 真机抓到的那条（第㊾刀 C-3）
            "intent://x.com",
            "javascript:alert(1)",
            "file:///sdcard/a.txt",
            "content://com.example/x",
            "data:text/html,<b>x</b>",
            "mailto:someone@example.com",
            "tel:10086",
            "ftp://example.com/x",
            "blob:https://example.com/abc",
        ).forEach {
            assertTrue("必须拦下：$it", BrowserUrlPolicy.shouldReject(it))
        }
    }

    @Test
    fun `N7_大小写与前后空白不影响判定`() {
        assertTrue(BrowserUrlPolicy.shouldReject("  INTENT://x  "))
        assertFalse(BrowserUrlPolicy.shouldReject("  HTTPS://example.com/x  "))
    }

    // ── ① 行为：不许误伤的普通输入 ──

    @Test
    fun `N7_裸域名与带端口的裸域名不误拦`() {
        listOf(
            "example.com",
            "example.com:8080",
            "example.com:8080/x?y=1",
            "127.0.0.1:14080",
            "localhost:3000/path",
            "www.baidu.com",
            "https://example.com/x?intent=1", // 查询里出现 intent 不算协议
            "http://example.com/x",
        ).forEach {
            assertFalse("不许误拦：$it", BrowserUrlPolicy.shouldReject(it))
        }
    }

    @Test
    fun `N7_中文与搜索词不误拦`() {
        listOf(
            "证道 是什么",
            "如何 使用 intent",
            "搜索: 意图", // 冒号前是中文，不构成 scheme 前缀
            "",
            "   ",
        ).forEach {
            assertFalse("不许误拦：$it", BrowserUrlPolicy.shouldReject(it))
        }
    }

    // ── ① 行为：归一化口径（第㊽-D 起逐字不变，现在由纯函数承载） ──

    @Test
    fun `N7_归一化_空串与搜索词与补协议`() {
        val identity: (String) -> String = { it }
        assertEquals("", BrowserUrlPolicy.normalize("   ", identity))
        assertEquals(
            "https://www.bing.com/search?q=证道 是什么",
            BrowserUrlPolicy.normalize("证道 是什么", identity),
        )
        assertEquals("https://example.com/x", BrowserUrlPolicy.normalize("example.com/x", identity))
        assertEquals("https://example.com/x", BrowserUrlPolicy.normalize("http://example.com/x", identity))
        assertEquals("https://example.com/x", BrowserUrlPolicy.normalize("https://example.com/x", identity))
    }

    @Test
    fun `N7_归一化_搜索词过编码器`() {
        assertEquals(
            "https://www.bing.com/search?q=证道%20是什么",
            BrowserUrlPolicy.normalize("证道 是什么") { it.replace(" ", "%20") },
        )
    }

    @Test
    fun `N7_旧顺序为何不可达_归一化会把显式协议洗成搜索词`() {
        // 这就是 N7 的机理：`intent://x` 不像 URL ⇒ 归一化先把它包成 bing 查询，
        // 之后的 scheme 预检看到的永远是 https ⇒ 提示分支不可达。
        val washed = BrowserUrlPolicy.normalize("intent://x") { it }
        assertTrue("归一化对显式非 http 协议是「洗白」：$washed", washed.startsWith("https://www.bing.com/search?q="))
        assertTrue("所以必须在归一化之前拦下", BrowserUrlPolicy.shouldReject("intent://x"))
    }

    // ── ② 接线门禁（源码级：顺序 + 唯一入口 + 实现已搬走） ──

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        val hit = candidates.firstOrNull { it.isFile }
            ?: error("找不到源文件：$relative（工作目录 ${File(".").absolutePath}）")
        return hit.readText()
            .replace("\r\n", "\n")
            .let { text -> text.replace(Regex("(?s)/\\*.*?\\*/"), "").replace(Regex("(?m)//.*$"), "") }
    }

    @Test
    fun `接线_N7_预检必须早于归一化_否则分支不可达`() {
        val body = source("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt")
            .substringAfter("private fun tryNavigate(context: Context, input: String): Boolean {")
            .substringBefore("\n}")
        val rejectAt = body.indexOf("BrowserUrlPolicy.shouldReject(input)")
        val normalizeAt = body.indexOf("normalizeInput(input)")
        assertTrue("tryNavigate 必须显式调 BrowserUrlPolicy.shouldReject(input)：$body", rejectAt >= 0)
        assertTrue("tryNavigate 必须仍走 normalizeInput(input)：$body", normalizeAt >= 0)
        assertTrue("顺序反了 —— N7 就是这么来的（预检在归一化之后 ⇒ 永远看到 https）", rejectAt < normalizeAt)
    }

    @Test
    fun `接线_㊽-D 的兜底与唯一入口不许被本刀改回去`() {
        val screen = source("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt")
        val body = screen
            .substringAfter("private fun tryNavigate(context: Context, input: String): Boolean {")
            .substringBefore("\n}")
        assertTrue(
            "0 标签兜底（㊽-D）必须留着：$body",
            body.contains("BrowserTabManager.activeEngine() ?: BrowserTabManager.obtain(context)"),
        )
        assertTrue("加载动作必须留着：$body", body.contains("loadUrl("))
        assertEquals(
            "归一化调用点必须唯一（判据只放一处，才不会有第二条路径漏改）",
            1,
            Regex("normalizeInput\\(input\\)").findAll(screen).count(),
        )
    }

    @Test
    fun `接线_归一化实现已搬进策略_UI 里不许再内联搜索词拼装`() {
        val screen = source("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt")
        assertFalse(
            "BrowserScreen 里不该再出现 bing 拼装（已搬进 BrowserUrlPolicy.normalize）",
            screen.contains("bing.com/search?q="),
        )
        assertTrue(
            "BrowserScreen 必须把归一化委托给策略",
            screen.contains("BrowserUrlPolicy.normalize(input)"),
        )
    }
}
