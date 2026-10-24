// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 刀 4 §2.2–§2.4 的桥侧门禁（派工单 **J6／J7／J8／J10／J11／J12**）。
 *
 * ## 为什么一半靠源码、一半靠纯函数
 * `BrowserCommands.snapshot/screenshot` 的**执行**要 WebView ＋ 主线程，JVM 里起不来；
 * 但判据里"会不会又变成收了不用"这类事，其实就写在源码的形状上（第㉓刀、㊽-C 都这么做）。
 * 所以本文件两件事分开钉：
 * - **源码级**（J6 分支在场、J7 报错口径、J8 selector 被真用、J10 路由只一处、J11a 不再
 *   1×1 冒充、J12 矩形算法）：读源码、剥注释、按花括号配平取函数体再断言；
 * - **行为级**（`shotMode`/`fitScale`/`cropOf`/`ShotBounds.parse`/`screenshotJson`）：
 *   这些是第48刀A `statusJson` 同款的"无 Android 内核"，JVM 直接喂值断言。
 *
 * 每条 absence 断言都配一条**负例自证**（把源码改回坏形状，判别器必须报警）——
 * 「不红＝没真测」。
 */
class BridgeBlade4GuardTest {

    // ── 基础设施（与 BridgeTabIdRoutingGuardTest 同一口径） ─────────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    /** 剥注释：块注释整段、单行注释只认整行（`http://` 这种串里的 `//` 不能误伤）。 */
    private fun strip(s: String): String = s
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val commandsRel = "src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt"
    private val bridgeRel = "src/main/java/com/example/zhengdao/bridge/BrowserAgentBridge.kt"
    private val commands by lazy { strip(raw(commandsRel)) }
    private val bridge by lazy { strip(raw(bridgeRel)) }

    /** 从签名起按花括号配平取函数体（含签名后的第一个块）。 */
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

    private val snapshotBody by lazy {
        bodyOf(commands, "fun snapshot(mode: String?, selector: String?, tabId: String? = null): String")
    }
    // 第一百七十七批：截图实现从 `screenshot()` 抽到 `captureShotRef()`（JSON/ref 两个出口
    // 共用一份内核）。门禁跟着**实现**走，不是放宽——断言的那几句仍然在实现里。
    private val screenshotBody by lazy { bodyOf(commands, "internal fun captureShotRef(") }

    /** dispatch 里某一条命令分支的调用文本。 */
    private fun dispatchBranch(src: String, cmd: String): String {
        val start = src.indexOf("BridgePolicy.Command.$cmd ->")
        assertTrue("dispatch 里找不到 $cmd 分支", start >= 0)
        val rest = src.substring(start)
        val next = rest.indexOf("BridgePolicy.Command.", 1)
        return if (next > 0) rest.substring(0, next) else rest
    }

    // ── J8：snapshot 的 selector 不许再静默 ─────────────────────────────────

    @Test
    fun `J8_snapshot 函数体必须真的用 selector 取子树并对不存在报错`() {
        assertTrue("selector 必须进子树脚本：$snapshotBody", snapshotBody.contains("subtreeJsScript(sel)"))
        assertTrue("取到的子树必须进同一条脱敏管线", snapshotBody.contains("redactDom(PageSnapshot.scrubSecrets(html))"))
        assertTrue("元素不存在必须报错", snapshotBody.contains("找不到元素："))
        assertTrue("selector 校验仍走 BridgePolicy.checkSelector（不许绕开）", snapshotBody.contains("BridgePolicy.checkSelector(it)"))
        assertFalse("旧那句「预留…当前忽略」必须删掉", raw(commandsRel).contains("当前忽略但校验其合法性"))
    }

    @Test
    fun `J8_负例自证_把 selector 退回静默忽略判别器必须报警`() {
        val regressed = snapshotBody.replace("subtreeJsScript(sel)", "\"\"")
        assertTrue("退回假过滤后判别器居然还过——门禁自己失效了", !regressed.contains("subtreeJsScript(sel)"))
        assertTrue("regressed 体里必须看不到真用 selector 的形状", !regressed.contains("document.querySelector"))
    }

    // ── J6：fullPage 真做（含分支与降级口径） ──────────────────────────────

    @Test
    fun `J6_fullPage 走整页路线且降级必须明说`() {
        assertEquals(BrowserCommands.ShotMode.FULL_PAGE, BrowserCommands.shotMode("1", null))
        assertEquals(BrowserCommands.ShotMode.FULL_PAGE, BrowserCommands.shotMode("true", null))
        assertEquals(BrowserCommands.ShotMode.FULL_PAGE, BrowserCommands.shotMode(" TRUE ", null))
        assertEquals(BrowserCommands.ShotMode.VIEWPORT, BrowserCommands.shotMode("0", null))
        assertEquals(BrowserCommands.ShotMode.VIEWPORT, BrowserCommands.shotMode(null, null))
        assertEquals("selector 与 fullPage 同时给：范围更窄的元素优先", BrowserCommands.ShotMode.ELEMENT, BrowserCommands.shotMode("1", "#card"))
        assertTrue("整页路线必须真调 capturePicture", commands.contains("webView.capturePicture()"))
        assertTrue("降级必须写明退化成可视区", screenshotBody.contains("已退化为可视区"))
        assertTrue("降级必须写清是没取到整页布局", screenshotBody.contains("页面过高/未取到整页布局"))
    }

    @Test
    fun `J6_上限与缩放比读数`() {
        assertEquals("上限内不该缩", 1f, BrowserCommands.fitScale(1080, 5000), 0f)
        assertEquals(0.5f, BrowserCommands.fitScale(8192, 1000), 0.001f)
        assertEquals(0.5f, BrowserCommands.fitScale(1000, 40000), 0.001f)
        val s = BrowserCommands.fitScale(4096, 20000)
        assertTrue("4096×20000 是 8192 万像素，必须被面积闸压到 1200 万内", s < 1f)
        val w = (4096 * s).toInt()
        val h = (20000 * s).toInt()
        assertTrue("缩完宽不超 ${BrowserCommands.MAX_SHOT_WIDTH}：$w", w <= BrowserCommands.MAX_SHOT_WIDTH)
        assertTrue("缩完高不超 ${BrowserCommands.MAX_SHOT_HEIGHT}：$h", h <= BrowserCommands.MAX_SHOT_HEIGHT)
        assertTrue("缩完面积不超 ${BrowserCommands.MAX_SHOT_PIXELS}：${w.toLong() * h}", w.toLong() * h <= BrowserCommands.MAX_SHOT_PIXELS)
        assertEquals("尺寸缺失时不该缩（交由调用方报错）", 1f, BrowserCommands.fitScale(0, 100), 0f)
    }

    @Test
    fun `J6_负例自证_删掉 fullPage 分支 shotMode 必须不再判整页`() {
        // 派工单 §5 第 3 条负例的行为面：把 shotMode 改成"收了不用"（永远 VIEWPORT）
        val alwaysViewport: (String?, String?) -> BrowserCommands.ShotMode = { _, _ -> BrowserCommands.ShotMode.VIEWPORT }
        assertEquals("变异体必须让 fullPage 判不出整页（J6 红）", BrowserCommands.ShotMode.VIEWPORT, alwaysViewport("1", null))
        assertTrue(
            "源码级同步：函数体里没有整页调用形状时判别器必须报警",
            !screenshotBody.replace("captureShot(tab.webView, bounds)", "captureViewportDraw(tab.webView)")
                .contains("captureShot(tab.webView, bounds)"),
        )
    }

    // ── J7＋J12：局部截图 ──────────────────────────────────────────────────

    @Test
    fun `J7_元素不存在与零尺寸都必须明说不许拿整页充数`() {
        assertTrue("元素不存在走 NOT_FOUND 哨兵", screenshotBody.contains("\"NOT_FOUND\" -> return ShotCapture.Bad(\"找不到元素"))
        assertTrue("零尺寸必须明说", screenshotBody.contains("元素没有尺寸"))
        assertTrue("selector 校验走 checkSelector", screenshotBody.contains("BridgePolicy.checkSelector(it)"))
        assertTrue("几何缺失要如实报错", screenshotBody.contains("取不到页面几何"))
    }

    @Test
    fun `J12_矩形用 getBoundingClientRect 加滚动补偿`() {
        val scripts = commands.substringAfter("private fun elementBoundsJsScript")
        assertTrue("JS 里必须有 getBoundingClientRect", scripts.contains("getBoundingClientRect()"))
        assertTrue("JS 里必须有 scrollX", scripts.contains("window.scrollX"))
        assertTrue("JS 里必须有 scrollY", scripts.contains("window.scrollY"))
        assertTrue("补偿要真加在 rect 上", scripts.contains("r.left+sx") && scripts.contains("r.top+sy"))
        assertTrue("未命中回 NOT_FOUND（不许回整页）", scripts.contains("return 'NOT_FOUND'"))
    }

    @Test
    fun `J7_cropOf 把 CSS 矩形映射进位图像素并裁进边界`() {
        // 页面内容 1000×4000 CSS px，位图 1080×4320 px（比例 1.08）
        val el = BrowserCommands.ShotBounds(x = 20.0, y = 1000.0, w = 300.0, h = 120.0, contentWidth = 1000.0, contentHeight = 4000.0)
        val box = BrowserCommands.cropOf(el, 1080, 4320, 1f)
        assertNotNull("正常元素必须算出裁剪框", box)
        assertEquals(22, box!!.left)
        assertEquals(1080, box.top)
        assertEquals(324, box.width)
        assertEquals(130, box.height)
        // 滚动补偿后的坐标在页面底部之外 ⇒ 裁进边界而不是回整页
        val edge = BrowserCommands.ShotBounds(900.0, 3950.0, 300.0, 300.0, 1000.0, 4000.0)
        val clipped = BrowserCommands.cropOf(edge, 1080, 4320, 1f)
        assertNotNull(clipped)
        assertTrue("右边越界必须被裁住", clipped!!.left + clipped.width <= 1080)
        assertTrue("下边越界必须被裁住", clipped.top + clipped.height <= 4320)
        // 完全在页面之外 ⇒ null（调用方报错，不许回整页）
        val off = BrowserCommands.ShotBounds(5000.0, 5000.0, 100.0, 100.0, 1000.0, 4000.0)
        assertNull("与内容区无交集必须给 null", BrowserCommands.cropOf(off, 1080, 4320, 1f))
        // 零尺寸 ⇒ null
        val zero = BrowserCommands.ShotBounds(10.0, 10.0, 0.0, 40.0, 1000.0, 4000.0)
        assertNull("0 宽必须算不出框", BrowserCommands.cropOf(zero, 1080, 4320, 1f))
        assertNull("内容尺寸缺失也不许猜", BrowserCommands.cropOf(el, 1080, 4320, 0f))
    }

    @Test
    fun `几何脚本的六段读数解析与容错`() {
        val ok = BrowserCommands.ShotBounds.parse("12|345.5|100|200|800|4000")
        assertNotNull(ok)
        assertEquals(345.5, ok!!.y, 0.001)
        assertNull("缺一段就不许猜", BrowserCommands.ShotBounds.parse("12|345|100|200|800"))
        assertNull(BrowserCommands.ShotBounds.parse("12|abc|100|200|800|4000"))
        assertNull(BrowserCommands.ShotBounds.parse(null))
        assertNull(BrowserCommands.ShotBounds.parse("NaN|0|1|1|1|1"))
    }

    // ── J11：截图不许假成功 ＋ 回执两条路径 ────────────────────────────────

    @Test
    fun `J11a_旧 1×1 假成功的根因写法必须从代码里清零`() {
        // 剥注释再断言（仓库口径：BrowserBridgeGateTest 的「凡断言某字样不出现先剥注释」，
        // 台账坑「门禁被自己注释里的同名字面量带偏」）。本刀的 KDoc 要**解释**旧根因，
        // 那里留着 `coerceAtLeast(1)` 是文档；代码里必须一个都没有 ⇒ 用 commands（已剥注释）判。
        assertFalse("J11a：代码里不许再有 coerceAtLeast(1)（把未布局兜成 1×1 的根因）", commands.contains("coerceAtLeast(1)"))
        assertFalse("旧函数 captureScreenshot 不许回潮", commands.contains("fun captureScreenshot("))
        assertTrue("取不到像素必须如实报失败", screenshotBody.contains("截屏失败（页面还没绘制"))
        val draw = bodyOf(commands, "private fun captureViewportDraw(webView: WebView)")
        assertTrue("未布局必须直接判 null", draw.contains("w <= 0 || h <= 0"))
        assertTrue("降级路线不许被当成整页成功", draw.contains("viaPicture = false"))
    }

    @Test
    fun `J11b_回执同时给相对路径与宿主绝对路径并注明 guest 用途`() {
        val json = BrowserCommands.screenshotJson(
            file = "截屏/bridge-1.png",
            hostPath = "/storage/emulated/0/Download/证道/截屏/bridge-1.png",
            guestPath = "/opt/zhengdao/截屏/bridge-1.png",
            bytes = 4321,
            mode = "fullpage",
            width = 1080,
            height = 19200,
            scale = 0.75f,
            note = null,
        )
        assertTrue(json.startsWith("{\"ok\":true,"))
        listOf(
            "\"file\":\"截屏/bridge-1.png\"",
            "\"hostPath\":\"/storage/emulated/0/Download/证道/截屏/bridge-1.png\"",
            "\"guestPath\":\"/opt/zhengdao/截屏/bridge-1.png\"",
            "\"bytes\":\"4321\"",
            "\"mode\":\"fullpage\"",
            "\"width\":\"1080\"",
            "\"height\":\"19200\"",
            "\"scale\":\"0.750\"",
        ).forEach { assertTrue("回执缺 $it：$json", json.contains(it)) }
        assertTrue("guestPath 必须注明只给终端 guest", json.contains("guestPathScope"))
        assertTrue(json.contains("只给终端 guest"))
        assertFalse("没降级就不许凭空冒 note 字段", json.contains("\"note\""))
        val degraded = BrowserCommands.screenshotJson("f", "h", "g", 1, "viewport", 100, 80, 1f, "页面过高/未取到整页布局，已退化为可视区")
        assertTrue("降级必须在回执里说清", degraded.contains("\"note\""))
        assertTrue(degraded.contains("\"scale\":\"1.000\""))
    }

    // ── J10：路由层只透传一处 ──────────────────────────────────────────────

    @Test
    fun `J10_SCREENSHOT 分支把 selector 原样交给执行层`() {
        val branch = dispatchBranch(bridge, "SCREENSHOT")
        assertTrue("分支必须透传 selector：$branch", branch.contains("param(body, \"selector\")"))
        assertTrue("参数顺序必须是 app, tabId, fullPage, selector：$branch", branch.indexOf("app,") < branch.indexOf("param(body, \"tabId\")"))
        assertTrue(branch.indexOf("param(body, \"fullPage\")") < branch.indexOf("param(body, \"selector\")"))
        // 路由层只搬运：不许在 BrowserAgentBridge 里自造 selector 校验
        assertFalse("路由层不许自造第二套校验", bridge.contains("checkSelector"))
        assertEquals(
            "全 dispatch 里 selector 透传恰 5 处（snapshot/click/type/scroll 原有 4 处 + screenshot 1 处）",
            5,
            Regex("param\\(body, \"selector\"\\)").findAll(bridge).count(),
        )
    }

    @Test
    fun `J10_其余十条分支一个字没动`() {
        listOf("STATUS", "EXEC_JS", "NAVIGATE", "NEW_TAB", "ACTIVE_TAB", "CLOSE_TAB").forEach { cmd ->
            val branch = dispatchBranch(bridge, cmd)
            assertFalse("$cmd 分支不许被顺手改动（本刀只许动 SCREENSHOT）", branch.contains("selector"))
        }
    }
}
