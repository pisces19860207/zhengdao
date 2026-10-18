// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.browser

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 地址胶囊「编辑态出口」守卫（2026-10-18 收尾 · 落地 §B 顺序 75）。
 *
 * 背景：底栏改版定稿 §6 写了「点胶囊进编辑态、键盘 Go 提交」，**没写怎么取消**。
 * 真机实测（蓝总管 2026-10-18）：点开胶囊之后只能靠「提交」或「切标签」退出，
 * 点页面区、按系统返回都退不出去（`BackHandler` 当时只在 `canGoBack()` 时挂）。
 * 旧设计地址栏永远是输入框 ⇒ 不算回归，但两态设计里「点开却退不出去」是硬伤。
 *
 * 判据四组（全部源码文本级，与 [BrowserBottomBarGuardTest] 同一手法，**先剥注释**再断言）：
 * ① 编辑态有返回键出口，且**不抢** WebView 历史（两条 BackHandler 的 enabled 互斥）；
 * ② 点页面区也能退出编辑，而且**旁听不消费**（`PointerEventPass.Initial`）—— 页面照样收得到这一下；
 * ③ 退出编辑只有一个实现（[exitAddressEditing]），点页面那条**必须复用它**，不许抄第二份；
 * ④ 退出编辑不导航：出口体内不许出现 `tryNavigate` / `loadUrl`。
 */
class BrowserAddressEditExitGuardTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 剥注释后的源码（块注释 ＋ 整行单行注释都剥）。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun screen() = code("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt")

    @Test
    fun `编辑态有返回键出口_且不抢WebView历史`() {
        val src = screen()
        assertTrue(
            "编辑态必须挂一条 BackHandler（点开胶囊却退不出去＝§B 75 那个洞）",
            src.contains("BackHandler(enabled = addressEditing)"),
        )
        assertTrue(
            "原来那条历史栈 BackHandler 必须加 !addressEditing 守卫，否则编辑态按返回会直接导航掉",
            src.contains("BackHandler(enabled = !addressEditing && engine.canGoBack())"),
        )
    }

    @Test
    fun `点页面区能退出编辑_且旁听不消费事件`() {
        val src = screen()
        assertTrue(
            "页面区要在 Initial pass 旁听一次（不消费，页面照收）",
            src.contains("awaitPointerEvent(PointerEventPass.Initial)"),
        )
        assertTrue(
            "非编辑态不许挂监听（编辑态才有意义，也免得多一次 pointerInput 键变化）",
            src.contains("if (!addressEditing) return@pointerInput"),
        )
    }

    @Test
    fun `退出编辑只有一个实现_点页面复用它`() {
        val src = screen()
        val decl = src.indexOf("val exitAddressEditing: () -> Unit = {")
        assertTrue("退出编辑要抽成一个 lambda（唯一实现）", decl >= 0)
        val body = src.substring(decl, minOf(src.length, decl + 400))
        assertTrue("出口必须收键盘（focusManager.clearFocus(force = true)）",
            body.contains("focusManager.clearFocus(force = true)"))
        assertTrue("出口必须把编辑态置回 false", body.contains("addressEditing = false"))
        // 点页面那条要复用出口，不许自己再写一遍 clearFocus
        val pointer = src.indexOf("awaitPointerEvent(PointerEventPass.Initial)")
        val tail = src.substring(pointer, minOf(src.length, pointer + 200))
        assertTrue("点页面区必须复用 exitAddressEditing()，不许抄第二份退出逻辑",
            tail.contains("exitAddressEditing()"))
    }

    @Test
    fun `退出编辑不导航`() {
        val src = screen()
        val decl = src.indexOf("val exitAddressEditing: () -> Unit = {")
        val end = src.indexOf("}", decl)
        val body = src.substring(decl, end)
        assertTrue("退出编辑的 lambda 里不许写导航（它是取消，不是提交）", !body.contains("tryNavigate"))
        assertTrue("退出编辑的 lambda 里不许 loadUrl", !body.contains("loadUrl"))
    }
}
