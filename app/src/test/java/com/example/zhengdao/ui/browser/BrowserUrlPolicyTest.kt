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
 * 底栏改版（2026-10-18 定稿 §6）加第三半：③ **展示判据** [BrowserUrlPolicy.displayHost] /
 * [displayPath] / [isSecure] —— 一条 56dp 底栏里地址胶囊常态只有 **195dp**、有前进历史时
 * **143dp**（374.9dp 直板实算），放不下整条 URL，只能展示 host ＋ 次级色 path；这条展示线
 * 一旦写进 Compose 就进不了 JVM 单测，所以和归一化一样住在本类。
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

    // ── ③ 展示判据：地址胶囊常态（底栏改版 2026-10-18 定稿 §6） ──

    @Test
    fun `展示_常态只给域名_剥_scheme_与_www_与小写`() {
        assertEquals("example.com", BrowserUrlPolicy.displayHost("https://www.Example.COM/path"))
        assertEquals("example.com", BrowserUrlPolicy.displayHost("https://example.com/"))
        assertEquals("example.com", BrowserUrlPolicy.displayHost("http://example.com"))
        assertEquals("/path", BrowserUrlPolicy.displayPath("https://www.example.com/path"))
        // 展示串里永远不许再出现 scheme（143dp 那一档一个字都要省）
        listOf(
            "https://www.example.com/a",
            "http://example.com:8080/a",
            "about:blank",
        ).forEach { assertFalse("$it 的展示串不该带 ://", BrowserUrlPolicy.displayHost(it).contains("://")) }
    }

    @Test
    fun `展示_端口_默认剥_非默认留`() {
        assertEquals("example.com:8080", BrowserUrlPolicy.displayHost("http://example.com:8080/x"))
        assertEquals("example.com:14080", BrowserUrlPolicy.displayHost("https://example.com:14080"))
        assertEquals("example.com", BrowserUrlPolicy.displayHost("https://example.com:443/"))
        assertEquals("example.com", BrowserUrlPolicy.displayHost("http://example.com:80"))
    }

    @Test
    fun `展示_userinfo_一律不显示`() {
        // 凭据不能铺在界面上（这是安全口径，不是美化）
        assertEquals(
            "secret.example.com",
            BrowserUrlPolicy.displayHost("https://user:pass@secret.example.com/x"),
        )
        assertFalse(
            "展示串里不许残留凭据",
            BrowserUrlPolicy.displayHost("https://user:pass@secret.example.com/x").contains("pass"),
        )
    }

    @Test
    fun `展示_超长_host_中段省略_保住尾域`() {
        val got = BrowserUrlPolicy.displayHost("https://abcdefghij-klmnopqrstuv.example.com/x")
        assertEquals("abcdefghij-k…example.com", got)
        assertTrue("省略后仍要 26 字符以内：$got", got.length <= 26)
        assertTrue("尾域（注册域）必须看得见", got.endsWith("example.com"))
    }

    @Test
    fun `展示_无_authority_原样给_不拆路径`() {
        // about:blank / data:… 这类没有 `//`，硬拆会把 "blank" 当域名
        assertEquals("about:blank", BrowserUrlPolicy.displayHost("about:blank"))
        assertEquals("", BrowserUrlPolicy.displayPath("about:blank"))
        assertEquals("", BrowserUrlPolicy.displayHost(""))
        assertEquals("", BrowserUrlPolicy.displayPath(""))
        assertEquals("", BrowserUrlPolicy.displayHost("   "))
    }

    @Test
    fun `展示_IDN_与_punycode_原样显示_不做反解`() {
        // 仓库没有 punycode 依赖，不为一个显示细节引库 ⇒ xn-- 原样给（读得出"是个域名"即可）
        assertEquals("例え.テスト", BrowserUrlPolicy.displayHost("https://例え.テスト/x"))
        assertEquals("xn--r8jz45g.xn--zckzah", BrowserUrlPolicy.displayHost("https://xn--r8jz45g.xn--zckzah/x"))
        assertFalse(BrowserUrlPolicy.displayHost("https://xn--r8jz45g.xn--zckzah/x").contains("www."))
    }

    @Test
    fun `展示_IPV6_字面量的冒号不当端口`() {
        assertEquals("[::1]:3000", BrowserUrlPolicy.displayHost("http://[::1]:3000/x"))
        assertEquals("[::1]", BrowserUrlPolicy.displayHost("http://[::1]:80/x"))
        assertEquals("127.0.0.1:14080", BrowserUrlPolicy.displayHost("http://127.0.0.1:14080/bridge/v1/status"))
    }

    @Test
    fun `展示_path_根路径空_查询与片段全剥_超长尾省略`() {
        assertEquals("", BrowserUrlPolicy.displayPath("https://example.com/"))
        assertEquals("", BrowserUrlPolicy.displayPath("https://example.com"))
        assertEquals("", BrowserUrlPolicy.displayPath("https://example.com?x=1"))
        assertEquals("/rank/today", BrowserUrlPolicy.displayPath("https://example.com/rank/today?q=1#frag"))
        val long = BrowserUrlPolicy.displayPath("https://example.com/" + "a".repeat(30))
        assertEquals("path 必须截到 24 字符（含省略号）：$long", 24, long.length)
        assertTrue("截断必须带省略号收尾", long.endsWith("…"))
        assertTrue("根斜杠要留着（读得出是路径）", long.startsWith("/"))
    }

    @Test
    fun `展示_isSecure_只看_scheme_大小写无关`() {
        assertTrue(BrowserUrlPolicy.isSecure("https://example.com"))
        assertFalse(BrowserUrlPolicy.isSecure("http://example.com"))
        assertTrue(BrowserUrlPolicy.isSecure("HTTPS://Example.COM")) // 引擎回写的 URL 可能带大写
        assertFalse(BrowserUrlPolicy.isSecure(""))
        assertFalse(BrowserUrlPolicy.isSecure("about:blank"))
    }

    @Test
    fun `展示_归一化之后一定能被展示判据读回`() {
        // 两条线是接力的：normalize 补 scheme → 展示判据剥 scheme。顺序反了或口径不一致就红。
        val identity: (String) -> String = { it }
        assertEquals(
            "example.com",
            BrowserUrlPolicy.displayHost(BrowserUrlPolicy.normalize("example.com/x", identity)),
        )
        // 搜索词走 bing：胶囊要读得出"我在必应搜索"
        val searched = BrowserUrlPolicy.normalize("证道 是什么", identity)
        assertEquals("bing.com", BrowserUrlPolicy.displayHost(searched))
        assertEquals("/search", BrowserUrlPolicy.displayPath(searched))
        assertTrue("搜索态也算 https（升级过）", BrowserUrlPolicy.isSecure(searched))
    }
}
