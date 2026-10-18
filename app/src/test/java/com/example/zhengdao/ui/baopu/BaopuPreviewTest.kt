// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.preview.PreviewSlots
import com.example.zhengdao.ui.browser.BrowserHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 刀 4-B（第二十八批）· 预览接线的判据门禁：**R1–R8**。
 *
 * ## 两半分着钉（与 `BridgeBlade4GuardTest` 同一口径）
 * - **行为级**（R1/R2/R5/R7 ＋ R4 的执行面）：`replyToHtml`/`cardTitle`/`renderAndPlace`/
 *   `clearPreviewCache`/`openInXuanlan` 全是**无 Android 内核**的普通函数，JVM 直接喂值断言。
 *   服务起不来这件事用**注入桩**演（`renderAndPlace` 的 `ensureService` 形参），于是 R5
 *   「不必测端口占满」也能真测到失败分支——不是只盯着源码看它像不像。
 * - **源码级**（R3/R4/R6/R8）：入口时序、不许自己 new WebView、不许加 strings 键、抽屉零改动，
 *   写的就是源码的形状。每条 absence 断言都配一条**判别器自证**（把源码喂一段坏形状，
 *   判别器必须报警）⇒「不红＝没真测」；§4 两条负例是真改文件那一档。
 *
 * 注释与字符串在断言前剥掉（仓库纪律：凡断言某字样**不出现**，先剥注释再断言——本文件对应的
 * 主源 KDoc 里合法地写着 `LaunchedEffect`，不剥就会假红）。
 */
class BaopuPreviewTest {

    // ── 基础设施 ────────────────────────────────────────────────────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    /** 剥注释：块注释整段、单行注释只认整行（`http://` 里的 `//` 不能误伤）。 */
    private fun stripComments(s: String): String = s
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 剥字符串（先三引号再普通串）：CSS 里的 `{` 与模板的 `${}` 都不该参与花括号配平。 */
    private fun stripStrings(s: String): String = s
        .replace(Regex("(?s)\"\"\".*?\"\"\""), "\"\"\"\"\"\"")
        .replace(Regex("\"[^\"\\n]*\""), "\"\"")

    private val previewRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuPreview.kt"
    private val drawerRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt"
    private val screenRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt"

    /** 主源：注释与字符串都剥干净的那份。 */
    private val code: String by lazy { stripStrings(stripComments(raw(previewRel))) }

    /** 从签名起按花括号配平取函数体。 */
    private fun bodyOf(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("源码里找不到签名：$signature", start >= 0)
        val open = src.indexOf('{', start)
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
        error("$signature 的花括号没配平")
    }

    /**
     * 判别器：`callName` 的调用点是否落在 `LaunchedEffect`／`DisposableEffect`／`Effect`／`init`
     * 的块里（＝「自动渲 / 预渲」的形状）。做法是剥完注释与字符串后按花括号配平给每个块贴标签。
     */
    private fun effectEnclosedCall(src: String, callName: String): List<String> {
        val hits = mutableListOf<String>()
        // 每遇到一个 `{` 就压一帧「这个块是不是 effect 块」；`}` 弹掉。
        // 字符串与注释在调用前已经剥过 ⇒ 花括号配平不会被 CSS 里的 `}` 或 `${}` 带偏。
        val frames = ArrayList<Boolean>()
        var pendingEffect = false
        val openers = Regex("(LaunchedEffect|DisposableEffect|\\bEffect)\\s*\\(|\\binit\\s*\\{")
        var i = 0
        while (i < src.length) {
            val rest = src.substring(i)
            when {
                rest.startsWith("{") -> {
                    frames.add(pendingEffect)
                    pendingEffect = false
                }
                rest.startsWith("}") -> if (frames.isNotEmpty()) frames.removeAt(frames.size - 1)
                rest.startsWith(callName) -> {
                    val tainted = frames.any { it }
                    if (tainted) hits.add("第 ${src.substring(0, i).count { it == '\n' } + 1} 行：$callName 在 effect 块里")
                }
                openers.containsMatchIn(rest.take(60)) -> pendingEffect = true
            }
            i++
        }
        return hits
    }

    // ── R1：渲染是纯函数 ────────────────────────────────────────────────────

    @Test
    fun `R1_replyToHtml 是纯函数_同输入两次一字不差且不碰任何状态`() {
        val md = "第一行\n\n第二行\n\n```kotlin\nval a = 1\n```\n收尾一句"
        val once = replyToHtml(md, "卡片标题")
        val twice = replyToHtml(md, "卡片标题")
        assertEquals("纯函数：同输入必须同输出", once, twice)
        assertTrue("必须是完整页面", once.startsWith("<!doctype html>"))
        assertTrue("带 lang", once.contains("<html lang=\"zh-CN\">"))
        // 不碰状态：调两次之后槽里仍然是空的（渲染不许顺手 put）
        PreviewSlots.resetForTests()
        replyToHtml(md, "标题")
        assertEquals("replyToHtml 不许顺手放槽", 0, PreviewSlots.stats().used)
    }

    @Test
    fun `R1_cardTitle 取第一行去掉行首记号并截断`() {
        assertEquals("今天天气", cardTitle("## 今天天气\n正文"))
        assertEquals("列表也算第一行", cardTitle("- 列表也算第一行"))
        assertEquals("抱朴回复", cardTitle("   \n  "))
        val long = "一".repeat(60)
        val t = cardTitle(long)
        assertEquals("超长要截断到 42 再加省略号", 43, t.length)
        assertTrue(t.endsWith("…"))
    }

    // ── R2：必须转义（负例 1 的落点） ───────────────────────────────────────

    @Test
    fun `R2_回复里的 script 渲成卡片后不许是可执行内容`() {
        val md = "看这段：<script>alert(1)</script> 还有 a < b & c > d"
        val html = replyToHtml(md, "标题")
        assertFalse("整页里不许出现真的 <script 标签", html.contains("<script"))
        assertTrue("& 必须第一个转（否则出现双重转义）", html.contains("&amp;"))
        assertTrue("尖括号转成实体后照常显示", html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue("正文里的裸 &lt; 也在", html.contains("a &lt; b"))
        assertFalse("不许出现双重转义 &amp;lt;", html.contains("&amp;lt;"))
    }

    @Test
    fun `R2_标题同样转义_不给自己开注入口子`() {
        val html = replyToHtml("正文", "<img src=x onerror=alert(1)>")
        assertFalse("标题里不许出现可执行标签", html.contains("<img"))
        assertEquals(2, html.split("&lt;img").size - 1) // <title> 与 <h1> 各一次
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
    }

    @Test
    fun `R2_escapeHtml 的逐字符口径`() {
        // 逐字符按顺序转：输入 `<&>"'` ⇒ `&lt;` `&amp;` `&gt;` `&quot;` `&#39;`
        assertEquals("&lt;&amp;&gt;&quot;&#39;", escapeHtml("<&>\"'"))
        assertEquals("中文与数字不动", escapeHtml("证道 4B ok"), "证道 4B ok")
    }

    @Test
    fun `R2 判别器自证_不转义时 script 必须被抓到`() {
        // 这段是「去掉转义」的坏形状（等价于 §4 负例 1 的产物），判别口径就是 html 里出现 <script
        val naive = "<p>" + "<script>alert(1)</script>" + "</p>"
        assertTrue("判别口径本身要能咬住未转义", naive.contains("<script"))
        assertFalse("而真渲染产物咬不住 ⇒ 说明转义在场", replyToHtml("<script>", "t").contains("<script"))
    }

    // ── 卡片形状（§1.2：标题＋正文＋代码块 ＋ 深浅两套） ─────────────────────

    @Test
    fun `代码块按 pre 包起来_围栏没闭合也要吐出来`() {
        val html = replyToHtml("前一句\n```\nline1\nline2\n```\n后一句", "t")
        assertTrue("围栏要变成 pre+code", html.contains("<pre><code>line1\nline2</code></pre>"))
        val open = replyToHtml("前一句\n```\n没闭掉的半截", "t")
        assertTrue("没闭合也照样吐，不吞用户的话", open.contains("<pre><code>没闭掉的半截</code></pre>"))
        val inside = replyToHtml("```\nif (a < b) {}\n```", "t")
        assertTrue("代码里的尖括号同样转义", inside.contains("if (a &lt; b) {}"))
    }

    @Test
    fun `空行分段_连续行同一段用 br 连着`() {
        val html = replyToHtml("甲\n乙\n\n丙", "t")
        assertTrue("两行连成一段", html.contains("<p>甲<br>\n乙</p>"))
        assertEquals("空行切出第二段", 2, Regex("<p>").findAll(html).count())
        assertTrue(html.contains("<p>丙</p>"))
    }

    @Test
    fun `无正文也有页面且明说`() {
        val html = replyToHtml("", "空卡")
        assertTrue("要走 empty 分支", html.contains("<p class=\"empty\">（这条回复没有正文）</p>"))
        assertTrue("标题仍在", html.contains("<h1>空卡</h1>"))
    }

    @Test
    fun `标题同源的正文第一行不重复排_但记号仍按原样显示`() {
        val md = "## 甲\n乙\n\n丙"
        val html = replyToHtml(md, cardTitle(md))
        assertTrue("h1 里是派生出来的标题", html.contains("<h1>甲</h1>"))
        assertFalse("h1 之外不该再排一遍同一行", html.contains("## 甲"))
        assertTrue("第一段的其余行照常排", html.contains("<p>乙</p>"))
        assertTrue(html.contains("<p>丙</p>"))
        // 反向钉子：标题不是从第一行派生时，第一行必须原样出现（不许"顺手吃掉一行"）
        val keep = replyToHtml(md, "另一回事")
        assertTrue("markdown 记号按原样显示（§1.2 不引库）", keep.contains("## 甲"))
    }

    @Test
    fun `样式内联且深浅各一套_不引外部资源`() {
        val html = replyToHtml("正文", "t")
        assertTrue("浅色一套", html.contains("@media (prefers-color-scheme:light)"))
        assertTrue("深色一套", html.contains("@media (prefers-color-scheme:dark)"))
        assertFalse("不许外链样式表", html.contains("<link"))
        assertFalse("不许引外部脚本", html.contains("<script"))
        assertFalse("不许 @import", html.contains("@import"))
        assertFalse("页面里不许出现任何网络地址", html.contains("http://") || html.contains("https://"))
    }

    // ── R3：入口只在点击里（负例 2 的落点） ─────────────────────────────────

    @Test
    fun `R3_主源里不许有自动渲的形状_且 put 只出现在一处`() {
        assertFalse("不许有 LaunchedEffect", code.contains("LaunchedEffect"))
        assertFalse("不许有 DisposableEffect", code.contains("DisposableEffect"))
        assertFalse("不许有组合函数", code.contains("@Composable"))
        assertFalse("不许有 init 块", Regex("(?m)^\\s*init\\s*\\{").containsMatchIn(code))
        assertEquals("PreviewSlots.put 只许出现在 renderAndPlace 里（一处）",
            1, Regex("PreviewSlots\\.put\\(").findAll(code).count())
        val place = bodyOf(code, "internal fun renderAndPlace(")
        assertTrue("put 在 renderAndPlace 体内", place.contains("PreviewSlots.put(html)"))
    }

    /**
     * R3 的**接线面**：入口将来落在 `BaopuScreen.kt`（29 批的抽屉行调它），所以这一条扫的是
     * 「主源 ＋ 抱朴页」两份，任何 `previewLastReply(`／`PreviewSlots.put(` 出现在
     * `LaunchedEffect`／`DisposableEffect`／`init` 块里就是「自动渲 / 后台预渲」⇒ 判红。
     * （§4 负例 2 就是往 `BaopuScreen.kt` 里塞一个 `LaunchedEffect { previewLastReply(...) }`，
     * 只有这一条红，验完按字节还原。）
     */
    @Test
    fun `R3_接线面也不许自动渲_抱朴页里没有任何 effect 调预览`() {
        val wiring = stripStrings(stripComments(raw(screenRel)))
        val hitsReply = effectEnclosedCall(wiring, "previewLastReply(")
        val hitsPut = effectEnclosedCall(wiring, "PreviewSlots.put(")
        assertTrue("抱朴页里有自动渲的形状：$hitsReply", hitsReply.isEmpty())
        assertTrue("抱朴页里直接动了槽（绕过 renderAndPlace）：$hitsPut", hitsPut.isEmpty())
        val preview = stripStrings(stripComments(raw(previewRel)))
        assertTrue("主源自己也不许：${effectEnclosedCall(preview, "previewLastReply(")}",
            effectEnclosedCall(preview, "previewLastReply(").isEmpty())
    }

    @Test
    fun `R3 判别器自证_把调用塞进 LaunchedEffect 必须报警`() {
        val bad = """
            LaunchedEffect(Unit) {
                previewLastReply("自动渲就是错的")
            }
        """.trimIndent()
        val stripped = stripStrings(stripComments(bad))
        val hits = effectEnclosedCall(stripped, "previewLastReply(")
        assertTrue("判别器没咬住 ⇒ 这条门禁等于没测：$hits", hits.isNotEmpty())
        // 反向：同一个调用写在普通函数里不许报警（免得判别器见谁咬谁）
        val good = """
            fun onClick() {
                previewLastReply("点了才渲")
            }
        """.trimIndent()
        assertTrue("普通调用被误报：${effectEnclosedCall(stripStrings(stripComments(good)), "previewLastReply(")}",
            effectEnclosedCall(stripStrings(stripComments(good)), "previewLastReply(").isEmpty())
    }

    // ── R4：打开只走 B 路径 ─────────────────────────────────────────────────

    @Test
    fun `R4_打开走 pendingUrl 加 requestBrowserTab_顺序是先 url 后切页`() {
        val open = bodyOf(code, "internal fun openInXuanlan(url: String)")
        val atUrl = open.indexOf("BrowserHolder.pendingUrl = url")
        val atTab = open.indexOf("BrowserHolder.requestBrowserTab()")
        assertTrue("必须写 pendingUrl", atUrl >= 0)
        assertTrue("必须请求切 Tab", atTab >= 0)
        assertTrue("顺序反了会先切页再带 url", atUrl < atTab)
        assertFalse("不许自己 new WebView", code.contains("WebView("))
        assertFalse("不许新建 Activity", code.contains("Activity"))
        assertFalse("不许起子进程", code.contains("Runtime.getRuntime") || code.contains("ProcessBuilder"))
        // 失败分支不许动玄览：openInXuanlan 只在 Opened 分支被调
        val entry = bodyOf(code, "fun previewLastReply(assistantText: String)")
        assertTrue("只在 Opened 分支打开", entry.contains("if (placed is PreviewResult.Opened) openInXuanlan(placed.url)"))
        assertFalse("Failed 分支不许打开", entry.contains("openInXuanlan(placed.reason") ||
            Regex("Failed[^\\n]*openInXuanlan").containsMatchIn(entry))
    }

    @Test
    fun `R4 执行面_调用后 pendingUrl 是卡片地址且切页请求真挂上`() {
        BrowserHolder.pendingUrl = null
        val url = "http://127.0.0.1:7000/p/0f0f0f0f-0f0f-0f0f-0f0f-0f0f0f0f0f0f"
        openInXuanlan(url)
        assertEquals("pendingUrl 必须就是这个 url", url, BrowserHolder.pendingUrl)
        assertTrue("切页请求是一次性的：第一次消费为真", BrowserHolder.consumeTabRequest())
        assertFalse("消费一次就清零", BrowserHolder.consumeTabRequest())
        BrowserHolder.pendingUrl = null // 复位，免得串到别的用例
    }

    // ── R5：失败要说话（三条分支都走一遍） ──────────────────────────────────

    @Test
    fun `R5_服务起不来时回那句指定文案_且一张都没放`() {
        PreviewSlots.resetForTests()
        val stub = "预览服务起不来（端口被占）：模拟 10 个候选全被占"
        val r = renderAndPlace("有正文", ensureService = { stub })
        val f = r as PreviewResult.Failed // 不是 Failed 就直接红在这里
        assertEquals("原样带出那句（不吞、不改写）", stub, f.reason)
        assertTrue("前缀必须是派工单 §1.4 那句话", f.reason.startsWith(PreviewNotice.SERVICE_DOWN))
        assertEquals("失败时槽里必须还是空的", 0, PreviewSlots.stats().used)
    }

    @Test
    fun `R5_空回复不许静默`() {
        val f = renderAndPlace("   \n  ") as PreviewResult.Failed
        assertEquals("空白正文要 Failed", PreviewNotice.EMPTY_REPLY, f.reason)
    }

    @Test
    fun `R5_槽那边拒绝时同样是一句能显示的话`() {
        PreviewSlots.resetForTests() // 未 bind ⇒ put 自己会回「预览服务未启动…」
        val r = renderAndPlace("有正文", ensureService = { null })
        val f = r as PreviewResult.Failed
        assertTrue("要带上前缀，不能只回内部术语", f.reason.startsWith("预览没放进去："))
        assertTrue("槽的原话要在里面", f.reason.contains("预览服务未启动"))
        assertEquals(0, PreviewSlots.stats().used)
    }

    @Test
    fun `R5_真链路_放好了才回 url 且槽里取得到那张卡`() {
        PreviewSlots.resetForTests()
        PreviewSlots.bind("http://127.0.0.1:7000")
        val r = renderAndPlace("正文一句\n\n<script>不该执行</script>", ensureService = { null })
        assertTrue("应当成功：$r", r is PreviewResult.Opened)
        val opened = r as PreviewResult.Opened
        assertTrue("url 形如 base/p/uuid", opened.url.startsWith("http://127.0.0.1:7000/p/"))
        assertEquals("uuid 就是 url 的最后一段", opened.url.substringAfterLast('/'), opened.id)
        assertNull("槽没满 ⇒ 不该淘汰谁", opened.evicted)
        val slot = PreviewSlots.get(opened.id)
        assertNotNull("放回来的 id 必须真取得到", slot)
        assertTrue("卡片里那条回复是转义后的样子", slot!!.html.contains("&lt;script&gt;不该执行&lt;/script&gt;"))
        assertEquals("一次调用放一张", 1, PreviewSlots.stats().used)
    }

    // ── R7：清空要报张数（N=0 也要说） ──────────────────────────────────────

    @Test
    fun `R7_clearPreviewCache 回数等于真清掉的张数`() {
        PreviewSlots.resetForTests()
        PreviewSlots.bind("http://127.0.0.1:7000")
        assertEquals("空槽时回 0（不是 -1，也不是 null）", 0, clearPreviewCache())
        repeat(3) { PreviewSlots.put("<p>$it</p>") }
        assertEquals(3, PreviewSlots.stats().used)
        assertEquals("三张 ⇒ 回 3", 3, clearPreviewCache())
        assertEquals("清完真的空了", 0, PreviewSlots.stats().used)
        assertEquals("再点一次回 0，仍然要说话", 0, clearPreviewCache())
    }

    @Test
    fun `R7_提示文案在 0 张时也要有内容`() {
        assertEquals("已清空 3 张预览", PreviewNotice.cleared(3))
        assertEquals("已清空 0 张预览（槽里本来就没有）", PreviewNotice.cleared(0))
        assertTrue("两句都以「已清空」开头 ⇒ 同一句口径",
            PreviewNotice.cleared(0).startsWith("已清空") && PreviewNotice.cleared(1).startsWith("已清空"))
    }

    // ── R6／R8：范围钉子 ────────────────────────────────────────────────────

    @Test
    fun `R6_本批零新键_文案集中在 PreviewNotice 一处`() {
        // 断言「某字样不出现」必须先剥注释（仓库纪律）：本文件对应的 KDoc 里合法地写着
        // 「29 批接线时换成 stringResource」——拿原文断言会假红（第一版就是这么红的，用例留着这条口径）。
        assertFalse("不许用 stringResource（那要求 Context，与纯函数口径冲突）", code.contains("stringResource"))
        assertFalse("不许引 R.string", code.contains("R.string."))
        // 中英对称门禁（BaopuSessionLibraryTest K8）钉的是「两边键集合相等」：
        // 本批一个键都不加 ⇒ 那条门禁天然不破。这里把它读出来核对，免得哪天只加一边。
        val zh = keySetOf("src/main/res/values/strings.xml")
        val en = keySetOf("src/main/res/values-en/strings.xml")
        assertTrue("zh 的 baopu_* 键不该为空（扫描器自己失效了？）", zh.isNotEmpty())
        assertEquals("zh/en 的 baopu_* 键集合必须对称", zh, en)
    }

    @Test
    fun `R8_抽屉一个字都没动_预览入口归第二十九批`() {
        // R8 的原话是 `git diff --stat <基线> HEAD -- ui/baopu/BaopuDrawer.kt` 必须为空（读数在报告 §5）；
        // 这里钉的是同一件事的源码形状：抽屉里不许出现任何预览接线，也不许出现"顺手加一行"的引用。
        val drawer = stripComments(raw(drawerRel))
        assertFalse("BaopuDrawer.kt 不许出现 previewLastReply", drawer.contains("previewLastReply"))
        assertFalse("BaopuDrawer.kt 不许出现 clearPreviewCache", drawer.contains("clearPreviewCache"))
        assertFalse("BaopuDrawer.kt 不许出现预览字样", drawer.contains("预览"))
        assertFalse("BaopuDrawer.kt 不许 import 本批文件", drawer.contains("BaopuPreview"))
        // 抱朴页的「不许自动渲」归 R3 那条判别器（一条判据一个主人：§4 负例 2 只该让 R3 红，
        // 不该被两条门禁重复咬）；"本批没在抱朴页接线"这件事由报告 §5 的 git 读数钉。
    }

    // ── 样卡落盘（给报告与肉眼核对；不改变任何断言） ────────────────────────

    @Test
    fun `样卡落盘_一张带代码块与尖括样的卡写到 build 目录`() {
        val md = """
            ## 验收要点
            本批交的是零件：`previewLastReply()` 一个调用做完「渲 ⇒ 放槽 ⇒ 交给玄览」。

            ```kotlin
            val r = previewLastReply(lastAssistant)   // 点了才走这一行
            if (r is PreviewResult.Failed) show(r.reason)
            ```

            注意：<script> 这类文本必须显示成字，不许被执行。
        """.trimIndent()
        val html = replyToHtml(md, cardTitle(md))
        val out = File("build/preview-card-sample.html")
        out.parentFile?.mkdirs()
        out.writeText(html)
        assertTrue("样卡要真落得下来（落不下来这题就是假绿）", out.isFile && out.length() > 600)
        assertFalse("样卡里不许有可执行脚本", html.contains("<script"))
        assertTrue("样卡里代码块进了 pre", html.contains("<pre><code>"))
    }

    private fun keySetOf(rel: String): Set<String> = raw(rel)
        .split('\n')
        .mapNotNull { line -> Regex("name=\"(baopu_[a-z_0-9]+)\"").find(line)?.groupValues?.get(1) }
        .toSet()
}
