// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百三十一批 · UI-D4 门禁（源码结构级）。
//
// 为什么只能钉结构：真机 J1（点 ☰ 开 → 再点 ☰ 关）在蓝总管手里复验，JVM 侧没有
// Compose 测试运行时（本仓无 Robolectric，见 MEMORY 的既有结论），所以这一层只钉
// **让 J1 成立的三条结构事实**——结构一变，J1 的前提就没了，这里先红，别等真机。
//
// 三条事实（对应派工单 §A「测试侧钉『顶栏在抽屉之后渲染／遮罩从顶栏下缘开始』」）：
//   F1 全文件只有一颗 ☰（禁止「往抽屉里再塞一个关闭键」，那会出现两个 ☰）；
//   F2 抽屉声明排在顶栏 Surface 之后，且只占 `weight(1f)`（＝顶栏以下那一块）——
//      遮罩的边界因此从顶栏下缘开始，碰不到 ☰；
//   F3 抽屉内容只有一份（`drawerSheet` 声明一次、挂载一次），不存在「第二套抽屉」。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.text.Charsets

class UiD4DrawerAboveTopBarTest {

    private val screen: String by lazy {
        // Gradle 单测的工作目录是 `app/`（与 util/BareThreadGuardTest 同一套取法）。
        File("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")
            .readText(Charsets.UTF_8)
    }

    /** F1：☰ 只许出现一次（不许复制进抽屉当第二个关闭键）。 */
    @Test
    fun `F1_全文件只有一颗三横线按钮`() {
        // 用 char 拼引号，避开转义（编译器对 \" 这段曾报语法错，没必要在这上面纠缠）。
        val needle = "Text(text = " + '"' + "☰" + '"'
        val hits = screen.split(needle).size - 1
        assertEquals("☰ 必须恰好一处（抽屉里不许再塞一颗）", 1, hits)
    }

    /** F2a：抽屉声明排在顶栏 Surface 之后（顶栏在上，抽屉在下）。 */
    @Test
    fun `F2a_抽屉声明在顶栏之后`() {
        val topBar = screen.indexOf("Surface(color = MaterialTheme.colorScheme.surface)")
        val drawer = screen.indexOf("ModalNavigationDrawer(")
        assertTrue("顶栏 Surface 必须在文件里出现", topBar >= 0)
        assertTrue("ModalNavigationDrawer 必须在文件里出现", drawer >= 0)
        assertTrue(
            "抽屉($drawer) 必须排在顶栏($topBar) 之后，否则遮罩又会盖住 ☰",
            drawer > topBar,
        )
    }

    /** F2b：抽屉只吃 `weight(1f)` 那一块（＝顶栏下缘以下），不是整屏。 */
    @Test
    fun `F2b_抽屉只占顶栏以下那一块`() {
        val start = screen.indexOf("ModalNavigationDrawer(")
        val block = screen.substring(start, minOf(screen.length, start + 400))
        assertTrue("抽屉必须带 modifier = Modifier.weight(1f)，否则它就是整屏高、遮罩会盖住顶栏", block.contains("Modifier.weight(1f)"))
    }

    /** F3：抽屉内容只有一份——`drawerSheet` 声明一次、`drawerContent` 引用一次。 */
    @Test
    fun `F3_抽屉内容只有一份`() {
        val declared = screen.split("val drawerSheet: @Composable").size - 1
        val mounted = screen.split("drawerContent = drawerSheet").size - 1
        assertEquals("drawerSheet 只许声明一次", 1, declared)
        assertEquals("drawerSheet 只许挂载一次（第二份＝两套抽屉）", 1, mounted)
    }

    /** 负例口径：抽屉里不许再出现第二个「关闭」按钮文案（关闭只靠遮罩/返回/☰）。 */
    @Test
    fun `F4_抽屉里没有第二个关闭键`() {
        val drawerStart = screen.indexOf("val drawerSheet: @Composable")
        val drawerEnd = screen.indexOf("Surface(color = MaterialTheme.colorScheme.background")
        assertTrue("抽屉 lambda 的边界必须可定位", drawerStart in 0 until drawerEnd)
        val drawerBody = screen.substring(drawerStart, drawerEnd)
        assertTrue(
            "抽屉内容里不许出现第二个 ☰ 或「关闭抽屉」按钮",
            !drawerBody.contains("☰") && !drawerBody.contains("baopu_drawer_close"),
        )
    }
}
