// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：228 批 A 片（抱朴↔玄览手眼断层）。ref 句柄的判据全是纯函数
// （RefHandle.decide / refEntriesOf / parseEyeProbe / stableNow / snapshotHeadJson），
// 按本项目的房型（审计类缺陷修成「纯函数 + JVM 表驱动单测」）直接钉在 JVM 上；
// 带 WebView 的那半（resolveRef 的页面探针、click 的真实执行）归真机链路证据。
// 四条负例突变的判红用例都在这里：epoch 翻转、去 redactUrl、stable 阈值归零、
// checkSelector 短路（引号 selector 在没有页面的 JVM 上也必须拒）。
//
// 228 批 B 片续用同一房型：几何探针的纯函数面（parseGeometry / rectInViewport /
// applyGeometry）也钉在 JVM 上；B 片负例的**源码门禁**（①脱敏不前移——几何探针
// 不碰文本、快照管线两道在场；③头部 url 现读 PageFingerprint 不吃陈旧缓存；
// ④tabs 两道脱敏＋关不存在的标签必拒）也归本文件——注释里写「我脱敏了」不算数。
package com.example.zhengdao.bridge

import com.example.zhengdao.browser.PageSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRefHandleTest {

    // ── 定位子生成（PageSnapshot.pathOf，228 批 A 的落点） ───────────────────

    @Test
    fun 路径_同型兄弟_序号各自正确() {
        val s = PageSnapshot.summarize(
            "<html><body><div><a href=\"#a\">a1</a><a href=\"#b\">a2</a></div></body></html>",
        )
        assertEquals(2, s.nodes.size)
        assertEquals(
            "html > body:nth-of-type(1) > div:nth-of-type(1) > a:nth-of-type(1)",
            s.nodes[0].path,
        )
        assertEquals(
            "html > body:nth-of-type(1) > div:nth-of-type(1) > a:nth-of-type(2)",
            s.nodes[1].path,
        )
    }

    @Test
    fun 路径_病态标签_放弃生成() {
        // SAFE_TAG 只收 [a-z][a-z0-9-]*：带点的病态标签拿不到定位子，快照对它不发 ref
        val s = PageSnapshot.summarize(
            "<html><body><foo.bar role=\"link\" onclick=\"x\">t</foo.bar></body></html>",
        )
        assertEquals(1, s.nodes.size)
        assertEquals("", s.nodes[0].path)
    }

    @Test
    fun 路径_超深树_放弃生成() {
        val sb = StringBuilder()
        repeat(40) { sb.append("<div>") }
        sb.append("<a href=\"#\">x</a>")
        repeat(40) { sb.append("</div>") }
        val s = PageSnapshot.summarize(sb.toString())
        assertEquals("", s.nodes[0].path)
    }

    @Test
    fun tagOfPath_取末段标签() {
        assertEquals("a", BrowserCommands.tagOfPath("html > body > div:nth-of-type(2) > a:nth-of-type(3)"))
        assertEquals("html", BrowserCommands.tagOfPath("html"))
    }

    @Test
    fun relativeLocator_只剥首段_单段为null() {
        assertEquals(
            "body > div:nth-of-type(1) > a:nth-of-type(2)",
            BrowserCommands.relativeLocator("html > body > div:nth-of-type(1) > a:nth-of-type(2)"),
        )
        assertNull(BrowserCommands.relativeLocator("html"))
    }

    // ── refEntriesOf ────────────────────────────────────────────────────────

    @Test
    fun refEntries_整页_编号即数组序_空路径跳过() {
        val nodes = listOf(
            PageSnapshot.Node("link", "a1", "", "", "html > body > a:nth-of-type(1)"),
            PageSnapshot.Node("textbox", "q", "", "", ""),
            PageSnapshot.Node("button", "b", "", "", "html > body > button:nth-of-type(1)"),
        )
        val m = BrowserCommands.refEntriesOf(nodes, null)
        assertEquals(2, m.size)
        assertEquals("html > body > a:nth-of-type(1)", m["e1"]!!.locator)
        assertNull(m["e1"]!!.subtreeSelector)
        assertEquals("a", m["e1"]!!.tag)
        assertEquals("html > body > button:nth-of-type(1)", m["e3"]!!.locator)
        assertNull(m["e2"])
    }

    @Test
    fun refEntries_子树_剥首段留子树根之外的路() {
        val nodes = listOf(
            PageSnapshot.Node("link", "a1", "", "", "html > body > a:nth-of-type(1)"),
        )
        val m = BrowserCommands.refEntriesOf(nodes, "#box")
        assertEquals("body > a:nth-of-type(1)", m["e1"]!!.locator)
        assertEquals("#box", m["e1"]!!.subtreeSelector)
    }

    @Test
    fun refEntries_子树根自己_不发ref() {
        val nodes = listOf(PageSnapshot.Node("link", "only", "", "", "div"))
        assertTrue(BrowserCommands.refEntriesOf(nodes, "#box").isEmpty())
    }

    // ── RefHandle.decide（四道闸的纯判据） ───────────────────────────────────

    private fun book(): BrowserCommands.RefBook = BrowserCommands.RefBook(
        tabId = 3,
        nonce = "abc",
        domEpoch = 7L,
        entries = mapOf(
            "e1" to BrowserCommands.RefEntry("html > body > a:nth-of-type(1)", null, "a"),
            "e2" to BrowserCommands.RefEntry("div > a:nth-of-type(1)", "#box", "a"),
        ),
    )

    private fun decide(
        raw: String?,
        b: BrowserCommands.RefBook?,
        tabId: Int = 3,
        nonce: String? = "abc",
        epoch: Long? = 7L,
    ): BrowserCommands.RefDecision = BrowserCommands.RefHandle.decide(raw, b, tabId, nonce, epoch)

    @Test
    fun decide_非句柄_NotRef() {
        assertEquals(BrowserCommands.RefDecision.NotRef, decide(null, book()))
        assertEquals(BrowserCommands.RefDecision.NotRef, decide("#btn", book()))
        assertEquals(BrowserCommands.RefDecision.NotRef, decide("e3", book()))
    }

    @Test
    fun decide_形状错_明说只认eN() {
        val d = decide("@foo", book())
        assertTrue(d is BrowserCommands.RefDecision.Stale)
        assertTrue((d as BrowserCommands.RefDecision.Stale).reason.contains("@e"))
        assertTrue(decide("@e3x", book()) is BrowserCommands.RefDecision.Stale)
        assertTrue(decide("@", book()) is BrowserCommands.RefDecision.Stale)
    }

    @Test
    fun decide_没有本_回旧句柄已失效() {
        val d = decide("@e1", null)
        assertTrue(d is BrowserCommands.RefDecision.Stale)
        val reason = (d as BrowserCommands.RefDecision.Stale).reason
        assertTrue(reason.contains("旧句柄已失效"))
        assertTrue(reason.contains("请重新 browser_snapshot"))
    }

    @Test
    fun decide_别标签的旧本_失效() {
        assertTrue(decide("@e1", book(), tabId = 4) is BrowserCommands.RefDecision.Stale)
    }

    @Test
    fun decide_无此句_失效() {
        assertTrue(decide("@e9", book()) is BrowserCommands.RefDecision.Stale)
    }

    @Test
    fun decide_文档换了_nonce变_失效() {
        // 负例 M1 的判红用例之一（另一条是 epoch 翻转）
        assertTrue(decide("@e1", book(), nonce = "xyz") is BrowserCommands.RefDecision.Stale)
    }

    @Test
    fun decide_内容变了_epoch变_失效() {
        val d = decide("@e1", book(), epoch = 8L)
        assertTrue(d is BrowserCommands.RefDecision.Stale)
        assertTrue((d as BrowserCommands.RefDecision.Stale).reason.contains("请重新 browser_snapshot"))
    }

    @Test
    fun decide_眼没了_失效() {
        assertTrue(decide("@e1", book(), nonce = null) is BrowserCommands.RefDecision.Stale)
        assertTrue(decide("@e1", book(), epoch = null) is BrowserCommands.RefDecision.Stale)
    }

    @Test
    fun decide_通过_给出定位子与标签核对值() {
        val d = decide("@e1", book())
        assertTrue(d is BrowserCommands.RefDecision.Ok)
        val ok = d as BrowserCommands.RefDecision.Ok
        assertEquals("html > body > a:nth-of-type(1)", ok.entry.locator)
        assertEquals("a", ok.entry.tag)
    }

    // ── 眼读数解析与 stable 判据（A-3） ──────────────────────────────────────

    @Test
    fun probe_四段全读出() {
        assertEquals(
            BrowserCommands.EyeProbe("abc", 7L, "complete", 250L),
            BrowserCommands.parseEyeProbe("abc|7|complete|250"),
        )
    }

    @Test
    fun probe_缺段_非数字_null() {
        assertNull(BrowserCommands.parseEyeProbe("abc|7|complete"))
        assertNull(BrowserCommands.parseEyeProbe("abc|x|complete|250"))
        assertNull(BrowserCommands.parseEyeProbe("NO_EYE"))
        assertNull(BrowserCommands.parseEyeProbe(null))
    }

    @Test
    fun stable_静默未满不算稳() {
        // 负例 M3 的判红用例：阈值被归零时这条会翻绿
        val p = BrowserCommands.parseEyeProbe("abc|7|complete|150")!!
        assertFalse(BrowserCommands.stableNow(p))
    }

    @Test
    fun stable_满静默且加载完_算稳_含边界() {
        assertTrue(BrowserCommands.stableNow(BrowserCommands.parseEyeProbe("abc|7|complete|200")!!))
        assertTrue(BrowserCommands.stableNow(BrowserCommands.parseEyeProbe("abc|7|complete|250")!!))
    }

    @Test
    fun stable_没加载完_再安静也不算稳() {
        assertFalse(BrowserCommands.stableNow(BrowserCommands.parseEyeProbe("abc|7|loading|2500")!!))
    }

    @Test
    fun fetch_正常_三段分离_无几何() {
        val ef = BrowserCommands.parseEyeFetch("abc|7|complete|250\u0001<h1>hi</h1>")!!
        assertEquals("abc", ef.probe!!.nonce)
        assertEquals(7L, ef.probe.epoch)
        assertEquals("<h1>hi</h1>", ef.html)
        assertNull("没有几何段 ⇒ 几何如实缺席（不假装有坐标）", ef.geo)
    }

    @Test
    fun fetch_带几何_正文里的控制符不串段() {
        val geo = "390|800" + '\u0003' + "html|0|0|390|800|1"
        val ef = BrowserCommands.parseEyeFetch(
            "abc|7|complete|250\u0001<h1>a\u0001b</h1>\u0002$geo",
        )!!
        assertEquals("正文段以 \\u0002 截断；正文里出现 \\u0001 不影响分段", "<h1>a\u0001b</h1>", ef.html)
        assertEquals(390, ef.geo!!.viewW)
    }

    @Test
    fun fetch_眼没装上_正文与几何照带() {
        val ef = BrowserCommands.parseEyeFetch("NO_EYE\u0001<html></html>\u0002390|800")!!
        assertNull(ef.probe)
        assertEquals("<html></html>", ef.html)
        assertEquals(800, ef.geo!!.viewH)
    }

    @Test
    fun fetch_无分隔_null() {
        assertNull(BrowserCommands.parseEyeFetch("abc|7|complete|250html"))
        assertNull(BrowserCommands.parseEyeFetch(null))
    }

    // ── snapshotHeadJson（A-2 头部 url 行） ──────────────────────────────────

    @Test
    fun head_url在首位_敏感参数打码() {
        // 负例 M2 的判红用例：redactUrl 被摘掉时 SECRET 会漏出去
        val h = BrowserCommands.snapshotHeadJson(
            url = "https://x.com/p?sessionid=SECRET&page=2",
            title = "标题",
            text = "正文",
            nodesJson = "[]",
        )
        // 头片段拼进 okRaw（外层已有 {）⇒ 片段自身不带花括号，只断首字段是 url
        assertTrue("url 应是头部第一个字段", h.startsWith("\"url\":"))
        assertTrue(h.contains("sessionid=***"))
        assertFalse(h.contains("SECRET"))
        assertTrue("非敏感参数保留", h.contains("page=2"))
    }

    @Test
    fun head_普通url原样() {
        val h = BrowserCommands.snapshotHeadJson("https://x.com/p?q=1", "标题", "t", "[]")
        assertTrue(h.contains("\"https://x.com/p?q=1\""))
    }

    @Test
    fun head_title裸键值_打码() {
        val h = BrowserCommands.snapshotHeadJson("https://x.com/", "token=abc", "t", "[]")
        assertTrue(h.contains("token=***"))
        assertFalse(h.contains("abc"))
    }

    // ── 负例 M4：checkSelector 不许被句柄分流绕过 ─────────────────────────────

    @Test
    fun click_引号selector_桥这层就拒_不需要页面() {
        // 校验在路由之前：JVM（无 WebView/无活跃标签）上也必须拒——
        // 若谁把 checkSelector 短路，这里会漏成「没有活跃标签」，用例翻红
        val out = BrowserCommands.click("#a'b")
        assertTrue(out.contains("引号"))
        assertFalse(out.contains("没有活跃标签"))
    }

    @Test
    fun type_引号selector_桥这层就拒_不需要页面() {
        val out = BrowserCommands.type("#a'b", "x")
        assertTrue(out.contains("引号"))
        assertFalse(out.contains("没有活跃标签"))
    }

    // ── 228 批 B-1：几何探针的纯函数面 ───────────────────────────────────────

    private fun geoLine(loc: String, x: Int, y: Int, w: Int, h: Int, vis: Boolean): String =
        "$loc|$x|$y|$w|$h|${if (vis) "1" else "0"}"

    private fun summaryOf(vararg nodes: PageSnapshot.Node): PageSnapshot.Summary =
        PageSnapshot.Summary("标题", nodes.toList(), nodes.size, "正文", false)

    private val GEO_HEAD = "390|800"
    private val BTN_PATH = "html > body > button:nth-of-type(1)"

    @Test
    fun 几何_正常解析_头行加节点行() {
        val g = BrowserCommands.parseGeometry(
            GEO_HEAD +
                '\u0003' + geoLine(BTN_PATH, 10, 20, 100, 30, true) +
                '\u0003' + geoLine("html > body > a:nth-of-type(1)", 0, 50, 200, 24, false),
        )!!
        assertEquals(390, g.viewW)
        assertEquals(800, g.viewH)
        assertEquals(2, g.byLocator.size)
        val b = g.byLocator[BTN_PATH]!!
        assertEquals(10, b.x)
        assertEquals(20, b.y)
        assertEquals(100, b.w)
        assertEquals(30, b.h)
        assertTrue(b.visible)
        assertFalse("vis=0 如实存进表（剔除是 applyGeometry 的事，解析器不替它做主）", g.byLocator["html > body > a:nth-of-type(1)"]!!.visible)
    }

    @Test
    fun 几何_头行坏_整体作废() {
        // 没有视口尺寸 inViewport 就算不出来 ⇒ 头行坏一票否决，不猜 390×800
        assertNull("头行非数字", BrowserCommands.parseGeometry("x|800" + '\u0003' + geoLine(BTN_PATH, 1, 2, 3, 4, true)))
        assertNull("头行多段", BrowserCommands.parseGeometry("390|800|9" + '\u0003' + geoLine(BTN_PATH, 1, 2, 3, 4, true)))
        assertNull("头行只有一段", BrowserCommands.parseGeometry("390"))
        assertNull("空串", BrowserCommands.parseGeometry(""))
        assertNull("null", BrowserCommands.parseGeometry(null))
    }

    @Test
    fun 几何_畸形行跳过_好行保留() {
        val g = BrowserCommands.parseGeometry(
            GEO_HEAD +
                '\u0003' + "button|10|20|100" +
                '\u0003' + "button|x|20|100|30|1" +
                '\u0003' + "button|10|20|100|30|2" +
                '\u0003' + geoLine("html > body > a:nth-of-type(1)", 1, 2, 3, 4, true),
        )!!
        assertEquals(1, g.byLocator.size)
        assertEquals("html > body > a:nth-of-type(1)", g.byLocator.keys.single())
    }

    @Test
    fun 视口内_含部分相交_算在视口里() {
        assertTrue(BrowserCommands.rectInViewport(BrowserCommands.GeoEntry(10, 20, 100, 30, true), 390, 800))
        // 右下角只探进一只角（380,790,100,40）：部分相交也算在视口里
        assertTrue(BrowserCommands.rectInViewport(BrowserCommands.GeoEntry(380, 790, 100, 40, true), 390, 800))
        // 左上角探出屏幕（负坐标）：本体还压着视口 ⇒ 算在
        assertTrue(BrowserCommands.rectInViewport(BrowserCommands.GeoEntry(-50, -50, 100, 100, true), 390, 800))
    }

    @Test
    fun 视口外或零尺寸_必不在视口里() {
        // 负例②的算术面：整体在视口下方/右方/上方，inViewport 必须 false
        assertFalse(BrowserCommands.rectInViewport(BrowserCommands.GeoEntry(0, 900, 100, 30, true), 390, 800))
        assertFalse(BrowserCommands.rectInViewport(BrowserCommands.GeoEntry(500, 0, 100, 30, true), 390, 800))
        assertFalse(BrowserCommands.rectInViewport(BrowserCommands.GeoEntry(-200, -200, 100, 100, true), 390, 800))
        assertFalse("零尺寸矩形不算", BrowserCommands.rectInViewport(BrowserCommands.GeoEntry(0, 0, 0, 0, true), 390, 800))
        assertFalse("零视口一律不算", BrowserCommands.rectInViewport(BrowserCommands.GeoEntry(10, 20, 100, 30, true), 0, 800))
    }

    @Test
    fun 套几何_class隐藏的按钮_整行剔除且nodesTotal跟着减() {
        // JS 探针明说 vis=0（class 里的 display:none / opacity:0 / 零尺寸 / visibility:hidden 都归这里）
        val geo = BrowserCommands.parseGeometry(GEO_HEAD + '\u0003' + geoLine(BTN_PATH, 10, 20, 0, 0, false))!!
        val s = BrowserCommands.applyGeometry(
            summaryOf(PageSnapshot.Node("button", "隐藏按钮", "", "", BTN_PATH)),
            geo,
        )
        assertTrue("vis=0 的节点必须整行剔除（判据：class 隐藏的按钮不再出现在快照里）", s.nodes.isEmpty())
        assertEquals("nodesTotal 同步减，否则隐藏节点会被误报成 nodesTruncated", 0, s.nodesTotal)
        assertFalse(s.nodesTruncated)
    }

    @Test
    fun 套几何_命中可见_附坐标与inViewport() {
        val geo = BrowserCommands.parseGeometry(GEO_HEAD + '\u0003' + geoLine(BTN_PATH, 10, 20, 100, 30, true))!!
        val s = BrowserCommands.applyGeometry(summaryOf(PageSnapshot.Node("button", "按钮", "", "", BTN_PATH)), geo)
        assertEquals(1, s.nodes.size)
        assertEquals(10, s.nodes[0].x)
        assertEquals(20, s.nodes[0].y)
        assertTrue(s.nodes[0].inViewport)
        assertEquals(1, s.nodesTotal)
    }

    @Test
    fun 套几何_视口外坐标_inViewport必须false_节点不丢() {
        // 负例②的判红用例：inViewport 不许信页面自报（vis=1），必须 Kotlin 按矩形现算；
        // 谁把它改成 true / 改成 g.visible，这条就翻红
        val geo = BrowserCommands.parseGeometry(GEO_HEAD + '\u0003' + geoLine(BTN_PATH, 10, 900, 100, 30, true))!!
        val s = BrowserCommands.applyGeometry(summaryOf(PageSnapshot.Node("button", "按钮", "", "", BTN_PATH)), geo)
        assertEquals("视口外的节点照样列出（只是标 out）", 1, s.nodes.size)
        assertEquals(10, s.nodes[0].x)
        assertEquals(900, s.nodes[0].y)
        assertFalse("inViewport 必须 false（rectInViewport 现算，不信 vis）", s.nodes[0].inViewport)
    }

    @Test
    fun 套几何_定位子失配_原样保留不带坐标() {
        // 解析器与实况 DOM 在病态 HTML 上漂移 / 超探针上限 ⇒ 如实缺席，不伪造坐标也不误剔
        val geo = BrowserCommands.parseGeometry(
            GEO_HEAD + '\u0003' + geoLine("html > body > div:nth-of-type(1)", 1, 2, 3, 4, true),
        )!!
        val s = BrowserCommands.applyGeometry(summaryOf(PageSnapshot.Node("button", "按钮", "", "", BTN_PATH)), geo)
        assertEquals(1, s.nodes.size)
        assertNull("失配＝如实缺席：不伪造坐标", s.nodes[0].x)
        assertNull(s.nodes[0].y)
        assertFalse(s.nodes[0].inViewport)
        assertEquals("失配不许减 nodesTotal（只有明说不可见才减）", 1, s.nodesTotal)
    }

    // ── 228 批 B 负例①③④：源码门禁（形制照 BridgeTabIdRoutingGuardTest） ────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    /** 剥注释：先多行再整行单行 —— 只剥注释，不碰字符串里的 `//`。 */
    private fun code(rel: String): String {
        val f = repoFile(rel)
        assertTrue("找不到 $rel（cwd=${File("").absolutePath}）", f.isFile)
        return f.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")
    }

    /** 抽出 BrowserCommands 里某个函数的正文（按花括号配平，从签名起）。 */
    private fun commandBody(signature: String): String {
        val src = code("src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt")
        val start = src.indexOf(signature)
        if (start < 0) return ""
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
        return src.substring(open)
    }

    @Test
    fun 门禁_几何探针不碰文本_快照管线两道脱敏在场() {
        val geo = commandBody("private fun geometryJsScript(")
        assertTrue("几何探针脚本没抓到（扫描器空转的保险丝）", geo.contains("getBoundingClientRect"))
        assertFalse(
            "几何探针里出现了脱敏调用 ⇒ 脱敏被前移进 JS（红线：探针只产数值与布尔，" +
                "负例①「JS 侧算完就省」的源码门）",
            geo.contains("redact") || geo.contains("scrub") || geo.contains("MASK"),
        )
        val snap = commandBody("fun snapshot(")
        assertTrue(
            "快照管线里 scrubSecrets 不见了 ⇒ 「JS 算完就省」成了真（两道脱敏的第一道）",
            snap.contains("PageSnapshot.scrubSecrets(html)"),
        )
        assertTrue(
            "快照管线里 redactDom 不见了 ⇒ 两道脱敏只剩一道",
            snap.contains("redactDom("),
        )
    }

    @Test
    fun 门禁_快照头url现读指纹_不吃陈旧缓存() {
        val snap = commandBody("fun snapshot(")
        assertTrue(
            "快照头没问 PageFingerprint（fp）——负例③「导航后 URL 不更新」的源码门",
            snap.contains("val fp = fingerprintOf(tab)"),
        )
        assertTrue(
            "头部 url 没取 fp?.url（改回 state.url 之类的陈旧缓存时这里必须红）",
            snap.contains("url = fp?.url ?: \"\""),
        )
    }

    @Test
    fun 门禁_tabs列表两道脱敏在场_关不存在的标签必拒() {
        val tabs = commandBody("internal fun tabsList(")
        assertTrue("tabs 列表的 url 没过 redactUrl", tabs.contains("redactUrl(st.url)"))
        assertTrue("tabs 列表的 title 没过 redactTabTitle", tabs.contains("redactTabTitle(st.title)"))
        val close = commandBody("fun closeTab(")
        assertTrue(
            "closeTab 丢了「没有这个标签」判据 ⇒ 关掉的 tab 仍可被指到（负例④）",
            close.contains("没有这个标签"),
        )
    }
}
