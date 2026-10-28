// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：262 批第二步「征询式切屏」的宿主侧状态机（BrowserHolder 的 requestTabTo /
// openForShot / restoreAfterShot / appTab）。BrowserHolder 无 Android 实例依赖
// （BaopuPreviewTest 已在 JVM 直测过 pendingUrl/consumeTabRequest）⇒ 行为直测。
package com.example.zhengdao.ui.browser

import com.example.zhengdao.TabIndex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrowserHolderShotSwitchTest {

    @Before
    fun reset() {
        BrowserHolder.appTab = null
        BrowserHolder.consumeTabRequest()
        BrowserHolder.onBrowserHidden()
        BrowserHolder.browserShownListener = null
    }

    @After
    fun cleanup() = reset()

    // ── S1 切进：记原页签、请求目标恒是玄览、请求一次性 ──────────────────────

    @Test
    fun `S1_openForShot_记原页签并请求切玄览`() {
        BrowserHolder.appTab = TabIndex.BAOPU

        val origin = BrowserHolder.openForShot()

        assertEquals("原页签要如实记下（截完恢复用它）", TabIndex.BAOPU, origin)
        assertTrue("必须发出切屏请求", BrowserHolder.consumeTabRequest())
        assertEquals(
            "切进的目标恒是玄览",
            TabIndex.BROWSER,
            BrowserHolder.requestedTabTarget(),
        )
        assertFalse("请求是一次性的", BrowserHolder.consumeTabRequest())
    }

    // ── S2 恢复：用户仍在玄览 ⇒ 切回原页签 ─────────────────────────────────

    @Test
    fun `S2_恢复_用户仍在玄览_切回原页签`() {
        BrowserHolder.appTab = TabIndex.BAOPU
        val origin = BrowserHolder.openForShot()!!
        BrowserHolder.appTab = TabIndex.BROWSER
        val before = BrowserHolder.tabRequestVersion

        assertTrue("用户没动 ⇒ 程序化切回", BrowserHolder.restoreAfterShot(origin))

        assertEquals("恢复的目标是原页签", origin, BrowserHolder.requestedTabTarget())
        assertEquals("恢复要发新的切屏请求", before + 1, BrowserHolder.tabRequestVersion)
        assertTrue(BrowserHolder.consumeTabRequest())
    }

    // ── S3 恢复：用户已手动切走 ⇒ 不抢（工单原话） ──────────────────────────

    @Test
    fun `S3_恢复_用户已手动切走_不动`() {
        BrowserHolder.appTab = TabIndex.BAOPU
        val origin = BrowserHolder.openForShot()!!
        assertTrue("App 会消费切进玄览的请求（真实时序：消费后用户才可能手动切走）", BrowserHolder.consumeTabRequest())
        BrowserHolder.appTab = TabIndex.SETTINGS
        val before = BrowserHolder.tabRequestVersion

        assertFalse("他切去哪儿是他此刻的选择，程序化恢复是抢屏", BrowserHolder.restoreAfterShot(origin))
        assertEquals("不该发新的切屏请求", before, BrowserHolder.tabRequestVersion)
        assertFalse(BrowserHolder.consumeTabRequest())
    }

    // ── S4 恢复的三个不动口径 ──────────────────────────────────────────────

    @Test
    fun `S4a_恢复_当前页签未知_不动`() {
        BrowserHolder.appTab = null
        assertFalse(BrowserHolder.restoreAfterShot(TabIndex.BAOPU))
    }

    @Test
    fun `S4b_恢复_原页签未知_不动`() {
        BrowserHolder.appTab = TabIndex.BROWSER
        assertFalse(BrowserHolder.restoreAfterShot(null))
    }

    @Test
    fun `S4c_恢复_原页签就是玄览_不动`() {
        BrowserHolder.appTab = TabIndex.BROWSER
        assertFalse(BrowserHolder.restoreAfterShot(TabIndex.BROWSER))
    }

    // ── S5 老入口的目标恒是玄览（旁路可能被恢复写成别的页签） ────────────────

    @Test
    fun `S5_requestBrowserTab_把目标重置回玄览`() {
        BrowserHolder.appTab = TabIndex.BAOPU
        val origin = BrowserHolder.openForShot()!!
        BrowserHolder.appTab = TabIndex.BROWSER
        BrowserHolder.restoreAfterShot(origin)
        BrowserHolder.consumeTabRequest()

        BrowserHolder.requestBrowserTab()
        BrowserHolder.consumeTabRequest()

        assertEquals(
            "终端点链接走的是老入口：目标必须是玄览，不能是上一次恢复留下的页签",
            TabIndex.BROWSER,
            BrowserHolder.requestedTabTarget(),
        )
    }

    // ── S6 在屏位与上屏钩子（预约式 drain 的触发口） ────────────────────────

    @Test
    fun `S6_上屏钩子在_onBrowserShown_触发`() {
        var fired = 0
        BrowserHolder.browserShownListener = { fired++ }

        BrowserHolder.onBrowserShown()
        assertEquals("进组合那一刻触发一次", 1, fired)
        assertTrue(BrowserHolder.browserOnScreenNow())

        BrowserHolder.onBrowserHidden()
        assertFalse(BrowserHolder.browserOnScreenNow())
    }
}
