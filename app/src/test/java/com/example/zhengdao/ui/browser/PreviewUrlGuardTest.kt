// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百四十九批 组3：`isPreviewUrl` 接 BrowserTabManager（去掉内联 startsWith）。
//
// 依据：第五十九批派工单 §5（本地预览页签「只显示、不运行」）＋ §4.6（外部打开入口）
// 口径演进：判据原来写的是 `url.startsWith("$PREVIEW_SCHEME://")`——与本文件其余判据
// （都走 [LocalPreviewPolicy.schemeOf]）不是同一把尺子，大小写变体与少斜杠变体判不出来。
package com.example.zhengdao.ui.browser

import com.example.zhengdao.browser.LocalPreviewPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览假地址的判据 ＋ 它在外部打开入口上的接线。
 *
 * 「预览 URL 绕过」是这个洞的形状：一条 `local-preview://产物/x.html` 只要被认成普通网址
 * 送进 [com.example.zhengdao.ui.browser.BrowserTabManager.openExternal]，就会以**可导航网址**
 * 的身份拿到上网页签的默认档配置（JS 开、网通）——预览页签那三条硬语义一条都不带。
 * 判据侧少认一个变体 ＝ 放行侧多一个洞，所以这里把变体逐条钉住。
 */
class PreviewUrlGuardTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** 去注释（先块后行；行注释只按**行首**切，否则 `"local-preview://…"` 这种代码里的串会被误伤）。 */
    private fun stripComments(src: String): String {
        val noBlock = src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return noBlock.split("\n").filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    private val managerFile = "src/main/java/com/example/zhengdao/ui/browser/BrowserTabManager.kt"
    private val policyFile = "src/main/java/com/example/zhengdao/browser/LocalPreviewPolicy.kt"

    // ── 判据本体：正反一致 ＋ 变体 ───────────────────────────────────────

    @Test
    fun `previewUrl造的地址反过来一定认得出`() {
        for (rel in listOf("产物/首页.html", "a/b/c.txt", "", "带 空格 的文件.html")) {
            val url = LocalPreviewPolicy.previewUrl(rel)
            assertTrue("自己造的地址认不出：$url", LocalPreviewPolicy.isPreviewUrl(url))
        }
    }

    @Test
    fun `大小写与少斜杠变体照样算预览地址_旧内联写法在这里漏`() {
        // 旧写法 = startsWith("local-preview://")，下面四条里有三条会判成"不是预览"，
        // 而 Uri/引擎那一侧认得它们 ⇒ 假地址被当普通网址放过去。
        for (url in listOf(
                "local-preview://x",
                "LOCAL-PREVIEW://x",
                "Local-Preview://x",
                "local-preview:x",           // 少两个斜杠
                "local-preview:/x",
                "LOCAL-PREVIEW:x",
            )
        ) {
            assertTrue("判据漏了变体：$url", LocalPreviewPolicy.isPreviewUrl(url))
        }
    }

    @Test
    fun `不是预览地址的一律不认_判据不许放宽成看到个preview就拦`() {
        for (url in listOf(
                "https://local-preview/x",     // 真网址，路径里带这个词
                "http://x/local-preview://y",
                "notlocal-preview://x",        // 前缀像但不是同一个 scheme
                "xlocal-preview://y",
                "file:///local-preview/x",
                "//local-preview/x",           // 没有 scheme
                "",                            // 空串
                "localpreview://x",            // 少了连字符
            )
        ) {
            assertFalse("误伤：$url", LocalPreviewPolicy.isPreviewUrl(url))
        }
    }

    @Test
    fun `scheme判据与其余三条同尺子`() {
        // isPreviewUrl 改用 schemeOf 之后，"预览地址"与"能导航的网址"必须互斥。
        val url = LocalPreviewPolicy.previewUrl("产物/首页.html")
        assertEquals("local-preview", LocalPreviewPolicy.schemeOf(url))
        assertFalse("预览地址不该被算成走网络的地址", LocalPreviewPolicy.isNetwork(url))
        assertFalse("预览地址永远不可导航（连上网档也不放行）",
            LocalPreviewPolicy.allowsNavigation(url, blockAllNetwork = false))
        assertTrue("外部打开入口拿到的普通网址仍走原通道",
            LocalPreviewPolicy.allowsNavigation("https://x", blockAllNetwork = false))
    }

    // ── 接线门禁：接上前这条红 ───────────────────────────────────────────

    @Test
    fun `外部打开入口在建新标签之前先拒预览地址`() {
        val src = stripComments(read(managerFile))
        val guard = src.indexOf("LocalPreviewPolicy.isPreviewUrl(url)")
        val create = src.indexOf("newTab(context, url)")
        assertTrue("openExternal 没接 isPreviewUrl ⇒ 预览假地址能从外部入口混进上网页签", guard > 0)
        assertTrue("守卫必须排在开标签之前（开了就来不及）", guard < create)
        assertTrue("守卫必须带 return（只记日志不拦＝静默）",
            src.substring(guard).take(260).contains("return"))
    }

    @Test
    fun `scheme字面量只在判据本体出现一次`() {
        // 接线处不许再手写 "local-preview"（两处各判一遍＝漂移入口）。
        val manager = stripComments(read(managerFile))
        assertFalse("BrowserTabManager 里手写了 scheme 字面量：${manager.lines().filter { it.contains("local-preview") }}",
            manager.contains("local-preview"))
        assertFalse("BrowserTabManager 里引了 PREVIEW_SCHEME 常量自己拼（判据只该在 LocalPreviewPolicy）",
            manager.contains("PREVIEW_SCHEME"))
        val policy = stripComments(read(policyFile))
        assertEquals("PREVIEW_SCHEME 的字面量定义必须只有一处", 1, Regex("\"local-preview\"").findAll(policy).count())
        assertFalse("isPreviewUrl 又退回到内联 startsWith",
            policy.contains("""startsWith("${'$'}PREVIEW_SCHEME"""))
    }
}
