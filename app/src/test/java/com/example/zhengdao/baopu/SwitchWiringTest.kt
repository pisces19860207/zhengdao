// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：262 批第二步的接线门禁。照 `ScreenshotWiringTest` 的法子做**源码级钉子**：
// 闸写好了没接上、恢复被吞进异步、跳过被记成失败——这些失败形状发生在两处调用
// 之间/跨文件装配，行为测试看不见，只有源码钉子拦得住。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SwitchWiringTest {

    private fun src(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()

    /** 去注释后的源码（KDoc/行注释里提到某个名字不算引用）。 */
    private fun codeOnly(text: String): String =
        text.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val engine: String
        get() = codeOnly(src("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"))

    /** 串行调用那一段（闸的**调用点**与执行都在里面，位置判据在同一段内比）。 */
    private val serialCall: String
        get() {
            val start = engine.indexOf("private fun handleSerialCall(")
            assertTrue("找不到 handleSerialCall（接线断了）", start >= 0)
            return engine.substring(start, engine.indexOf("private fun ", start + 10).coerceAtLeast(start))
        }

    private val switchFn: String
        get() = fn("private fun switchGate(")

    private val skipFn: String
        get() = fn("private fun handleSkippedScreenshot(")

    private val wrapFn: String
        get() = fn("private fun <T> executeWithSwitch(")

    private fun fn(header: String): String {
        val start = engine.indexOf(header)
        assertTrue("找不到 $header", start >= 0)
        return engine.substring(start, engine.indexOf("private fun ", start + 10).coerceAtLeast(start))
    }

    private val wiring: String
        get() = codeOnly(src("src/main/java/com/example/zhengdao/baopu/BaopuBrowserWiring.kt"))

    // ── W1 闸的顺序：隐私闸 → 切屏闸 → 真执行 ─────────────────────────────

    @Test
    fun `W1_切屏闸排在隐私闸之后_真执行之前`() {
        val privacyAt = serialCall.indexOf("screenshotGate(call, sessionId)")
        val switchAt = serialCall.indexOf("switchGate(call, sessionId)")
        val execAt = serialCall.indexOf("pipeline.execute(call)")
        assertTrue("必须调用 switchGate", switchAt >= 0)
        assertTrue("切屏闸必须在隐私闸之后（拒截就无所谓切不切屏）", privacyAt in 0 until switchAt)
        assertTrue("切屏闸必须在真执行之前（切完才发现不该切＝白打扰）", switchAt < execAt)
    }

    @Test
    fun `W1b_跳过路径在执行前分流`() {
        val switchAt = serialCall.indexOf("switchGate(call, sessionId)")
        val skipAt = serialCall.indexOf("handleSkippedScreenshot(")
        val execAt = serialCall.indexOf("pipeline.execute(call)")
        assertTrue("跳过要在执行前分流（跳过还执行＝卡片白问）", switchAt in 0 until skipAt && skipAt < execAt)
    }

    // ── W2 切屏闸的三个不打扰口径 ＋ §6 三级分流 ───────────────────────────

    @Test
    fun `W2_闸的三个早退`() {
        assertTrue(
            "只对截图生效",
            switchFn.contains("if (call.name != BrowserTools.NAME_SCREENSHOT) return null"),
        )
        assertTrue(
            "玄览没开就不问（为一个必然失败的调用打扰用户，比不问更糟）",
            switchFn.contains("if (BaopuBrowser.require() == null) return null"),
        )
        assertTrue(
            "没装配切屏台按已在屏上放行（只少打扰不少功能）",
            switchFn.contains("ScreenSwitchDesk.impl ?: return null"),
        )
    }

    @Test
    fun `W2b_三级判定先行_只有后台未渲染档出卡`() {
        assertTrue(
            "§6：先按（渲染过没有 × 前台与否）分流，判定来自纯函数 ShotTierRule",
            switchFn.contains("ShotTierRule.decide(renderable, foreground)"),
        )
        assertTrue(
            "已渲染档直接放行（免征询、免切屏）",
            switchFn.contains("ShotTier.PAINT_OFFSCREEN ->"),
        )
        assertTrue(
            "前台未渲染档走无感载入包裹，不出卡",
            switchFn.contains("ShotSwitch.LoadFirst(auditUrl = url, auditTitle = title)"),
        )
        assertTrue(
            "§6 红线：页面没渲染过时「在屏」不许当免问判据（直拍＝白图）——" +
                "「有像素」这件事已由 renderable 判据覆盖",
            !switchFn.contains("if (sw.browserOnScreen()) return null"),
        )
    }

    @Test
    fun `W2c_载入包裹_载入在执行前_摘载在finally`() {
        assertTrue(
            "LoadFirst/SwitchFirst 都要先前台载入（没渲染过就拍＝白图）",
            wrapFn.contains("sw.loadForShot()"),
        )
        val loadAt = wrapFn.indexOf("sw.loadForShot()")
        val finallyAt = wrapFn.indexOf("finally")
        val detachAt = wrapFn.indexOf("sw.detachForShot()")
        assertTrue("载入要在执行块之前", loadAt in 0 until finallyAt)
        assertTrue(
            "摘载必须放 finally（执行抛异常也要摘下 1dp 挂载）",
            finallyAt in 0 until detachAt,
        )
    }

    // ── W3 跳过＝SKIPPED（不算失败），账法照 Rejected 分支 ───────────────────

    @Test
    fun `W3_跳过记SKIPPED_不算失败`() {
        assertTrue("跳过路径必须存在", engine.contains("private fun handleSkippedScreenshot("))
        assertTrue("status 用 SKIPPED（与 REJECTED 区分：回放的人要分得清）", skipFn.contains("TraceStatus.SKIPPED"))
        assertTrue("单发路径要回灌历史", skipFn.contains("persistAssistantMessage("))
        assertTrue("编排路径要进批账", skipFn.contains("recordSkipped("))
        assertTrue(
            "跳过文案来自 ScreenshotSwitchConsent（别处自造定性一律不认）",
            engine.contains("ScreenshotSwitchConsent.skippedText()"),
        )
    }

    // ── W4 切屏包裹：finally 恢复＋有界等待 ─────────────────────────────────

    @Test
    fun `W4_执行包在切进切回之间_恢复在finally`() {
        val originAt = wrapFn.indexOf("sw.openForShot()")
        val finallyAt = wrapFn.indexOf("finally")
        val restoreAt = wrapFn.indexOf("sw.restoreAfterShot(origin)")
        assertTrue("必须先切进（记原页签）", originAt >= 0)
        assertTrue("恢复必须放 finally（执行抛异常也要把界面还回去）", finallyAt >= 0)
        assertTrue("恢复必须在 finally 之后", originAt < finallyAt && finallyAt < restoreAt)
        assertTrue("切进后要有界等待上屏", wrapFn.contains("waitBrowserOnScreen(sw)"))
        assertTrue("等待必须有上限（不能无限等人切屏）", engine.contains("switchOnScreenTimeoutMs"))
    }

    // ── W5 装配：切屏台真身＋预约式触发口，恢复的布尔要如实 ───────────────────

    @Test
    fun `W5_接线装在BaopuBrowserWiring_恢复返回值如实`() {
        assertTrue(
            "切屏台真身必须装配（引擎只认接口）",
            wiring.contains("ScreenSwitchDesk.impl = object : ScreenSwitch"),
        )
        assertTrue(wiring.contains("BrowserHolder.browserOnScreenNow()"))
        assertTrue(wiring.contains("BrowserHolder.openForShot()"))
        assertTrue(
            "恢复要直调（返回值如实进审计行）",
            wiring.contains("BrowserHolder.restoreAfterShot(origin)"),
        )
        assertTrue(
            "恢复不许 post（post 只能乐观回 true，把「用户手动切走就不抢」吞进异步）",
            !wiring.contains("main.post { BrowserHolder.restoreAfterShot"),
        )
        assertTrue(
            "预约式的触发口（玄览上屏钩子）必须接上",
            wiring.contains("BrowserHolder.browserShownListener"),
        )
        assertTrue(
            "钩子里必须投后台线程（主线程直调桥命令＝onMain 自等死锁）",
            wiring.contains("VisualEvidenceQueue.drain("),
        )
    }

    // ── W6 MainActivity：消费点读旁路＋页签上报 ────────────────────────────

    @Test
    fun `W6_消费点读目标旁路_页签变化上报`() {
        val main = codeOnly(src("src/main/java/com/example/zhengdao/MainActivity.kt"))
        assertEquals(
            "两处消费点（组合期＋ON_RESUME）都要读目标旁路",
            2,
            main.split("BrowserHolder.requestedTabTarget()").size - 1,
        )
        assertTrue(
            "页签变化要上报（「用户手动切走就不抢」的判定面）",
            main.contains("BrowserHolder.appTab = tab"),
        )
    }

    // ── W7 §6 的 1dp 挂窗纪律（BrowserHolder 源码级钉子） ───────────────────

    private val holder: String
        get() = codeOnly(src("src/main/java/com/example/zhengdao/ui/browser/BrowserHolder.kt"))

    private val attachFn: String
        get() {
            val start = holder.indexOf("private fun attachOffscreenForShot(")
            assertTrue("找不到 attachOffscreenForShot（1dp 载入台断线）", start >= 0)
            return holder.substring(start, holder.indexOf("private fun ", start + 10).coerceAtLeast(start))
        }

    @Test
    fun `W7_1dp挂窗_不抢焦点不盖屏_视口是真的_摘载认父`() {
        assertTrue(
            "挂点必须是现有 Activity 的 decorView（不新建窗口——新窗口才有焦点问题）",
            attachFn.contains("decorView as? ViewGroup"),
        )
        val paramsAt = attachFn.indexOf("FrameLayout.LayoutParams")
        assertTrue("WebView 要给真实屏幕尺寸的布局参数（1dp 布局＝1px 视口＝废图）", paramsAt >= 0)
        assertTrue(
            "WebView 的布局参数不许是 WRAP_CONTENT/MATCH_PARENT 之外的 1dp 小尺寸写法",
            attachFn.contains("dm.widthPixels") && attachFn.contains("dm.heightPixels"),
        )
        assertTrue(
            "容器必须 1dp（工单原话「1dp 挂窗」；clipChildren 默认裁剪 ⇒ 对用户不可见）",
            attachFn.contains("maxOf(1, dm.density.toInt())"),
        )
        val detach = holder.substring(
            holder.indexOf("fun detachForShot("),
            holder.indexOf("private fun attachOffscreenForShot(").coerceAtLeast(0),
        )
        assertTrue(
            "摘载只摘自己容器持有的（用户切进玄览后组合已把它挂走，再摘＝当面拆页面）",
            detach.contains("child.parent === container"),
        )
        assertTrue(
            "载入就绪判据必须走纯函数（readyState=complete 且提交了目标地址）",
            holder.contains("ShotTierRule.isReadyState(rs)") &&
                holder.contains("committed != \"about:blank\""),
        )
    }
}
