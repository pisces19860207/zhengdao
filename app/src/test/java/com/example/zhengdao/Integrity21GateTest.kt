// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二十一批「真闸门与空壳」的**源码级门禁**（J3/J4/J5/J8/J9）。
 *
 * ## 为什么这些判据只能钉在源码上
 *
 * 它们是「有没有那条路」的问题，不是「算出什么值」的问题：
 *
 * - **J3**：确认卡正文必须是**完整 URL**。改成 host 也能编译、界面也照常弹，
 *   但「显示 A 跳 B」的钓鱼防护就没了——这是这条判据存在的唯一理由。
 *   真机截图能看出「显示了什么」，但看不出「未来源码会不会被改成 host」。
 * - **J4**：点「打开」必须走与 `OPEN_IN_APP` **完全相同**的路径。多出第二条
 *   打开路径（另写一个 startActivity）不会报错，只会让「确认过一次」变成
 *   「以后都绕过确认」。
 * - **J5**：监听地址行不能是空 lambda。这是「看着能点、点下去零反应」的
 *   最典型形态，编译期完全看不出来。
 * - **J8**：`resetFailures()` 要有真调用点（旧版全仓只有定义）。
 * - **J9**：中英新键对称。
 *
 * 这些判据的失效都是静默的（编译过、界面正常、只在被利用时才有代价），
 * 所以钉住源码比钉住某个函数返回值更贴近真实风险。
 */
class Integrity21GateTest {

    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = appFile(rel).readText()

    private val terminalActivity: String
        get() = read("src/main/java/com/example/zhengdao/TerminalActivity.kt")

    private val bridgeSettings: String
        get() = read("src/main/java/com/example/zhengdao/ui/SettingsBridgeActivity.kt")

    private val zh: String get() = read("src/main/res/values/strings.xml")
    private val en: String get() = read("src/main/res/values-en/strings.xml")

    // ── J3：确认卡正文是完整 URL ─────────────────────────────────────────────

    @Test
    fun `J3_确认卡正文必须是完整URL`() {
        val idx = terminalActivity.indexOf("private fun showLinkConfirmDialog")
        assertTrue("确认卡函数必须存在（第二十一批 A 段）", idx >= 0)
        val body = terminalActivity.substring(idx, minOf(idx + 1400, terminalActivity.length))

        assertTrue(
            "弹卡正文必须是完整 uri —— 防「显示 A 跳 B」全靠这一条；" +
                "改成 host 就等于把防护删了（负例 2 的靶心）",
            body.contains(".setMessage(uri)"),
        )
        // 反向断言：不许把正文换成 host（哪怕同时保留了 uri 也不行——会让人以为
        // 「反正显示的是 host，那 host 就够了」）
        assertFalse(
            "确认卡正文不许出现 setMessage(host)",
            Regex("""setMessage\(\s*host""").containsMatchIn(body),
        )
    }

    @Test
    fun `J3_确认卡两个按钮都给_打开与取消`() {
        val idx = terminalActivity.indexOf("private fun showLinkConfirmDialog")
        val body = terminalActivity.substring(idx, minOf(idx + 1400, terminalActivity.length))
        assertTrue(body.contains("R.string.term_link_confirm_title"))
        assertTrue(body.contains("R.string.term_link_confirm_open"))
        assertTrue("取消按钮复用 common_cancel（不新建文案）", body.contains("R.string.common_cancel"))
    }

    // ── J4：只有一条打开路径 ─────────────────────────────────────────────────

    @Test
    fun `J4_onLinkOpen里不许自己拼打开动作`() {
        // 说明：全文件 requestBrowserTab 有两处是**既有设计**——OSC8 链接链（openInXuanlan）
        // 与终端里点裸 URL 那条链（B 路径的另一入口）。本判据只钉 onLinkOpen 这条链：
        // 它必须把打开动作交给 openInXuanlan 一处，不许自己再写一遍（那样确认与不确认
        // 就会走出两套行为，正是"假安全"的土壤）。
        val start = terminalActivity.indexOf("override fun onLinkOpen")
        val end = terminalActivity.indexOf("private fun openInXuanlan")
        assertTrue("onLinkOpen 必须存在", start >= 0)
        assertTrue("openInXuanlan 必须存在且在其后", end > start)
        val body = terminalActivity.substring(start, end)
        assertEquals(
            "onLinkOpen 里不许直接碰 BrowserHolder.requestBrowserTab——必须走 openInXuanlan",
            0,
            Regex("""BrowserHolder\.requestBrowserTab\(\)""").findAll(body).count(),
        )
        assertTrue("OPEN_IN_APP 分支必须调 openInXuanlan", body.contains("openInXuanlan(uri)"))
    }

    @Test
    fun `J4_全文件打开出口数是2_不许悄悄加第三条`() {
        // 两个既有入口：OSC8 链接（onLinkOpen 链）＋ 点裸 URL。数字变了就要重审。
        val hits = Regex("""BrowserHolder\.requestBrowserTab\(\)""").findAll(terminalActivity).count()
        assertEquals("终端里的 App 内打开出口应恰好两处（OSC8 链、裸 URL 链）", 2, hits)
    }

    @Test
    fun `J4_确认后走的是同一函数`() {
        val idx = terminalActivity.indexOf("private fun openInXuanlan")
        assertTrue("openInXuanlan 必须存在（唯一打开路径）", idx >= 0)
        val body = terminalActivity.substring(idx, minOf(idx + 500, terminalActivity.length))
        assertTrue(body.contains("BrowserHolder.pendingUrl = uri"))
        assertTrue(body.contains("BrowserHolder.requestBrowserTab()"))

        // 确认分支里必须调它，而不是自己再拼一遍打开动作
        val confirmIdx = terminalActivity.indexOf("Verdict.CONFIRM ->")
        assertTrue(confirmIdx >= 0)
        val confirmBody = terminalActivity.substring(confirmIdx, minOf(confirmIdx + 600, terminalActivity.length))
        assertTrue("CONFIRM 分支必须弹卡", confirmBody.contains("showLinkConfirmDialog(uri)"))
    }

    @Test
    fun `J4_确认卡里不许另开Activity`() {
        val idx = terminalActivity.indexOf("private fun showLinkConfirmDialog")
        val body = terminalActivity.substring(idx, minOf(idx + 1400, terminalActivity.length))
        assertFalse(
            "确认卡里不许自己 startActivity —— 打开动作只能在 openInXuanlan 里",
            body.contains("startActivity("),
        )
    }

    @Test
    fun `J4_OPEN_SYSTEM那条路没被顺手改`() {
        // 派工单 §2.1-3：不许动外跳浏览器那条。它的特征还在：ACTION_VIEW + 失败 toast
        val hits = Regex("""Intent\(Intent\.ACTION_VIEW""").findAll(terminalActivity).count()
        assertEquals("外跳浏览器仍应是唯一一处 ACTION_VIEW", 1, hits)
        assertTrue(
            "外跳失败仍要报 term_browser_failed",
            terminalActivity.contains("R.string.term_browser_failed"),
        )
    }

    // ── J5：「监听地址」行不再是空壳 ─────────────────────────────────────────

    @Test
    fun `J5_监听地址行不再是空lambda`() {
        val idx = bridgeSettings.indexOf("bridge_port_label")
        assertTrue("监听地址行必须还在（不许整个删掉当修好）", idx >= 0)
        val tail = bridgeSettings.substring(idx, minOf(idx + 700, bridgeSettings.length))

        assertFalse(
            "SettingRow(...) { } 这种空 lambda 形态必须消失（派工单 J5）",
            Regex("""\)\s*\{\s*\}""").containsMatchIn(tail),
        )
        assertTrue(
            "点这一行必须真的做点什么（打开只读说明卡）",
            tail.contains("showListenInfo = true"),
        )
    }

    @Test
    fun `J5_说明卡数据从BridgePolicy现取_不新造字段`() {
        val idx = bridgeSettings.indexOf("R.string.bridge_listen_detail")
        assertTrue("说明卡必须存在", idx >= 0)
        val tail = bridgeSettings.substring(idx, minOf(idx + 400, bridgeSettings.length))
        assertTrue(
            "绑定地址必须从 BridgePolicy 取（不许在 UI 里写死 127.0.0.1）",
            tail.contains("BridgePolicy.BIND_HOST"),
        )
        assertTrue(
            "端口必须从 BridgePolicy 取（不许在 UI 里写死 14080）",
            tail.contains("BridgePolicy.PORT"),
        )
    }

    // ── J8：resetFailures 有真调用点 ─────────────────────────────────────────

    @Test
    fun `J8_resetFailures有真调用点`() {
        assertTrue(
            "终端页重新进入时必须清失败计数（旧版全仓只有定义，是死函数）",
            Regex("""netWatch\.resetFailures\(\)""").containsMatchIn(terminalActivity),
        )
    }

    @Test
    fun `J8_清零前必须先取旧账`() {
        // 先 reset 再读 failureCount ⇒ 日志永远是 0（清零后读＝读了个空）。
        val idx = terminalActivity.indexOf("netWatch.failureCount")
        assertTrue("必须先取一次旧账", idx >= 0)
        val stale = terminalActivity.indexOf("val staleFailures = netWatch.failureCount")
        val reset = terminalActivity.indexOf("netWatch.resetFailures()")
        assertTrue("取旧账的语句必须存在", stale >= 0)
        assertTrue("取旧账必须在清零之前", stale < reset)
    }

    // ── J9：中英新键对称 ─────────────────────────────────────────────────────

    @Test
    fun `J9_本批新增键中英对称`() {
        val added = listOf(
            "term_link_confirm_title",
            "term_link_confirm_open",
            "bridge_listen_title",
            "bridge_listen_detail",
        )
        added.forEach { key ->
            assertTrue("中文缺键：$key", zh.contains("""name="$key""""))
            assertTrue("英文缺键：$key", en.contains("""name="$key""""))
        }
    }

    @Test
    fun `J9_说明卡里没有写死的回环地址`() {
        // 文案用 %1$s 占位；写死 127.0.0.1 会让「改了 BridgePolicy 文案却在骗人」复活
        val idx = zh.indexOf("bridge_listen_detail")
        val line = zh.substring(idx, minOf(idx + 120, zh.length))
        assertTrue("中文用 %1\$s 占位绑定地址", line.contains("%1\$s"))
        assertFalse("中文文案里不许写死 127.0.0.1", line.contains("127.0.0.1"))
    }
}
