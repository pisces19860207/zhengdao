// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §B 顺序 32「切 Tab 回玄览要恢复上一页」的验收（2026-10-14 真机验收立账）——
 * 第⑨刀 B 多标签化后平迁：原钉 [BrowserHolder] 单例状态，现钉每标签一份的 [BrowserTabState]。
 *
 * ## 缺陷面（原案）
 * 切走再切回玄览 ⇒ 空态重现、地址栏被清空，看着像"页面没了"。其实 WebView 由
 * BrowserHolder 保活（页面内容还在），丢的是 **Compose 侧**状态。
 *
 * ## 多标签后的结构（第⑨刀 B）
 * 状态搬进 [BrowserTabManager.Tab] 持有的 [BrowserTabState]（每标签一份，切 Tab 各归各），
 * BrowserScreen 入场从活跃标签的 state 回填。
 *
 * ## 两半验收（缺一不成立）
 * 1. **状态侧**（行为断言，本文件前 4 例）：[BrowserTabState] 存得下、读得回、
 *    只涨不落、空 URL 不覆盖；
 * 2. **接线侧**（文本门禁，末 2 例）：BrowserScreen 真的去读了活跃标签 state，
 *    而不是又写死空串；且 rememberPage 的调用点真的在（页面事件 → state）。
 *
 * 真机读数（切走再切回地址栏是否还原、切 Tab 不丢页面）归蓝总管采。
 */
class BrowserTabStateTest {

    // ── 第一半：状态侧 ────────────────────────────────────────────────────

    @Test
    fun `初始为空`() {
        val s = BrowserTabState()
        assertEquals("没打开过页面时应当是空串", "", s.url)
        assertFalse("没打开过页面前应显示空态引导", s.everLoaded)
        assertFalse("初始不在加载", s.loading)
        assertNull("初始无提示条", s.blockedMsg)
    }

    @Test
    fun `记下页面后能读回_等价于切走再切回`() {
        val s = BrowserTabState()
        s.onPageStarted("https://example.com/a")

        // 切走：不调用任何东西——模拟"Compose 的 remember 被丢掉"这件事本身。
        // 切回：场回填读的就是下面这些值（state 活在 Tab 上，不随组合消失）。

        assertEquals("https://example.com/a", s.url)
        assertTrue("加载过就该记住（否则切回来又见到空态引导）", s.everLoaded)
    }

    @Test
    fun `空 URL 不覆盖上次记录`() {
        val s = BrowserTabState()
        s.rememberPage("https://example.com/a", true)
        s.rememberPage("", false)
        assertEquals("空串不该把地址栏擦掉", "https://example.com/a", s.url)
    }

    @Test
    fun `everLoaded 只涨不落_后一次加载覆盖 URL`() {
        val s = BrowserTabState()
        s.rememberPage("https://a.com", true)
        s.rememberPage("https://b.com", false)
        assertTrue("加载过一次就回不去空态", s.everLoaded)
        assertEquals("后一次加载应覆盖 URL", "https://b.com", s.url)
    }

    @Test
    fun `onBlocked 停转圈并落提示_onPageStarted 清提示`() {
        val s = BrowserTabState()
        s.onPageStarted("https://example.com")
        s.onBlocked("被拦了")
        assertFalse("被拦后不该还在转圈", s.loading)
        assertEquals("被拦了", s.blockedMsg)
        s.onPageStarted("https://example.com/2")
        assertNull("重新加载应清掉旧提示", s.blockedMsg)
        assertTrue(s.loading)
    }

    @Test
    fun `空标题不擦掉已有标题`() {
        val s = BrowserTabState()
        s.onReceivedTitle("示例页面")
        s.onReceivedTitle("")
        assertEquals("示例页面", s.title)
    }

    // ── 第二半：接线侧（文本门禁） ─────────────────────────────────────────

    /**
     * Gradle 单测的工作目录是模块目录 `app/`（与 [TerminalLinkScannerTest] 同一约定）。
     */
    private fun browserScreenSource(): String {
        val rel = "src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt"
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 BrowserScreen.kt：${f.path}", f.isFile)
        return f.readText()
    }

    @Test
    fun `BrowserScreen 入场从活跃标签 state 回填而不是写死空串`() {
        val src = browserScreenSource()
        assertTrue(
            "urlInput 必须从活跃标签 state.url 回填（§B 顺序 32），否则切回来地址栏仍是空的",
            src.contains("mutableStateOf(active?.state?.url ?: \"\")")
        )
        assertTrue(
            "everLoaded 必须从活跃标签 state.everLoaded 回填，否则切回来又显示空态引导",
            src.contains("active.state.everLoaded")
        )
        assertTrue(
            "页面事件必须写进活跃标签 state（否则回填永远是空）——经 BrowserTabManager 的每标签委托",
            src.contains("BrowserTabManager")
        )
    }

    @Test
    fun `管理器委托把页面事件写进对应标签的 state`() {
        val rel = "src/main/java/com/example/zhengdao/ui/browser/BrowserTabManager.kt"
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 BrowserTabManager.kt：${f.path}", f.isFile)
        val src = f.readText()
        assertTrue(
            "onPageStarted 必须写进 tab.state（后台标签的加载态照常演进）",
            src.contains("tab.state.onPageStarted(url)")
        )
        assertTrue(
            "onPageFinished 必须写进 tab.state",
            src.contains("tab.state.onPageFinished(url)")
        )
        assertTrue(
            "scheme 拦截要落本标签提示条",
            src.contains("tab.state.onBlocked(")
        )
    }
}
