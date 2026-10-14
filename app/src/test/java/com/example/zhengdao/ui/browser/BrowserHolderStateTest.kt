// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * §B 顺序 32「切 Tab 回玄览要恢复上一页」的验收（2026-10-14 真机验收立账）。
 *
 * ## 缺陷面
 * 切走再切回玄览 ⇒ 空态重现、地址栏被清空，看着像"页面没了"。其实 WebView 由
 * [BrowserHolder] 保活（页面内容还在），丢的是 **Compose 侧**状态：BrowserScreen 里
 * 那几个 `remember { mutableStateOf(…) }` 随离开组合一起消失。
 *
 * ## 修法与两半验收
 * 把「上次 URL + 是否成功加载过」搬进 holder（与 `pendingUrl` 同处：跨 Tab、跨 Activity
 * 都活得下来），BrowserScreen 入场回填。
 * 1. **状态侧**（行为断言）：[BrowserHolder.rememberPage] / [BrowserHolder.lastUrl] /
 *    [BrowserHolder.everLoaded] 存得下、读得回、只涨不落、空 URL 不覆盖；
 * 2. **接线侧**（文本门禁）：BrowserScreen 真的去读了 holder，而不是又写死空串。
 *
 * 两半缺一不成立：存得住但界面不读 = 白存；界面读但没人写 = 永远空。
 * 真机读数（切走再切回地址栏是否还原）归蓝总管采。
 */
class BrowserHolderStateTest {

    @Before
    fun reset() {
        BrowserHolder.resetUiStateForTest()
    }

    // ── 第一半：状态侧 ────────────────────────────────────────────────────

    @Test
    fun `初始为空`() {
        assertEquals("没打开过页面时应当是空串", "", BrowserHolder.lastUrl())
        assertFalse("没打开过页面前应显示空态引导", BrowserHolder.everLoaded())
    }

    @Test
    fun `记下页面后能读回_等价于切走再切回`() {
        BrowserHolder.rememberPage("https://example.com/a", true)

        // 切走：不调用任何东西——模拟"Compose 的 remember 被丢掉"这件事本身。
        // 切回：场回填读的就是下面这两个值。

        assertEquals("https://example.com/a", BrowserHolder.lastUrl())
        assertTrue("加载过就该记住（否则切回来又见到空态引导）", BrowserHolder.everLoaded())
    }

    @Test
    fun `空 URL 不覆盖上次记录`() {
        BrowserHolder.rememberPage("https://example.com/a", true)
        BrowserHolder.rememberPage("", false)
        assertEquals("空串不该把地址栏擦掉", "https://example.com/a", BrowserHolder.lastUrl())
    }

    @Test
    fun `everLoaded 只涨不落_后一次加载覆盖 URL`() {
        BrowserHolder.rememberPage("https://a.com", true)
        BrowserHolder.rememberPage("https://b.com", false)
        assertTrue("加载过一次就回不去空态", BrowserHolder.everLoaded())
        assertEquals("后一次加载应覆盖 URL", "https://b.com", BrowserHolder.lastUrl())
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
    fun `BrowserScreen 入场从 holder 回填而不是写死空串`() {
        val src = browserScreenSource()
        assertTrue(
            "urlInput 必须从 BrowserHolder.lastUrl() 回填（§B 顺序 32），否则切回来地址栏仍是空的",
            src.contains("mutableStateOf(BrowserHolder.lastUrl())")
        )
        assertTrue(
            "everLoaded 必须从 BrowserHolder.everLoaded() 回填，否则切回来又显示空态引导",
            src.contains("mutableStateOf(BrowserHolder.everLoaded())")
        )
        assertTrue(
            "加载完成/开始时要把页面记进 holder（否则回填永远是空）",
            src.contains("BrowserHolder.rememberPage(")
        )
    }
}
