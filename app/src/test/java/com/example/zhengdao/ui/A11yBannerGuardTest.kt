// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-补-13 ＋ B-补-21（2026-10-16）：终端状态横幅的「可点」线索，与无障碍四项里的 ①②③。
 *
 * 台账原文（`docs/FEATURE-LEDGER.md` §B-补 ④）：
 * - **B-补-13**（P1-6）：终端状态横幅是「无形交互」——`setOnClickListener { visibility = GONE }`
 *   却没有任何「可点」的提示；
 * - **B-补-21**（R6 / P3-1）：无障碍——`clickable` 无 `role`、符号按钮 ●○✓▴▾⌫☯ 读屏念符号、
 *   横幅无 `liveRegion`、装饰性「·」被念。
 *
 * 本批落 **B-补-13 ＋ ①②③**；④（装饰性中点）见台账留痕：多数中点长在本地化字符串里
 * （如 `思考过程 · %1$d 步`），逐条改会连带动文案与翻译，单独一刀处理。
 *
 * 判据一律是**源码形态**，与 [PullToRefreshGuardTest] / [LocalLifecycleOwnerGuardTest] 同惯例：
 * 不跑 Compose 测试（本模块无 UI 测试基建），而是钉住「改动不能被悄悄改回去」。
 */
class A11yBannerGuardTest {

    /** 单测的工作目录是模块目录（`app/`），也可能是仓库根；两种都试。 */
    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    private fun bannerBlock(): String {
        val xml = repoFile("app/src/main/res/layout/activity_main.xml").readText()
        val start = xml.indexOf("@+id/status_banner")
        assertTrue("布局里必须有 status_banner", start >= 0)
        val end = xml.indexOf("/>", start)
        assertTrue("status_banner 应是自闭合 TextView", end > start)
        return xml.substring(start, end)
    }

    @Test
    fun `门禁_B补13_横幅带可见的收起线索`() {
        val block = bannerBlock()
        assertTrue(
            "B-补-13：横幅上要有 ✕ 图标当「可关闭」的视觉线索（drawableEnd）",
            block.contains("android:drawableEnd=\"@drawable/ic_banner_dismiss\""),
        )
        assertTrue("图标与文字之间要留呼吸（drawablePadding）", block.contains("android:drawablePadding="))
        assertTrue("B-补-13：可点就必须声明可点（clickable）", block.contains("android:clickable=\"true\""))
        assertTrue("键盘/无障碍焦点也要能落到横幅上（focusable）", block.contains("android:focusable=\"true\""))
    }

    @Test
    fun `门禁_B补13_收起图标是自绘矢量且只有两条笔画`() {
        val xml = repoFile("app/src/main/res/drawable/ic_banner_dismiss.xml").readText()
        assertTrue("必须是 vector（12dp ✕，随文字缩放）", xml.contains("<vector"))
        assertTrue("颜色要跟横幅正文一致（#FF5C4300）", xml.contains("android:strokeColor=\"#FF5C4300\""))
        assertTrue("圆头笔画才不显得生硬", xml.contains("android:strokeLineCap=\"round\""))
        val paths = Regex("<path").findAll(xml).count()
        assertTrue("自绘 ✕ = 两条交叉笔画（实测 $paths 条）", paths == 2)
    }

    @Test
    fun `门禁_B补13_横幅正文不动只补读屏提示`() {
        val src = repoFile("app/src/main/java/com/example/zhengdao/TerminalActivity.kt").readText()
        assertTrue("读屏提示的构造点必须存在（bannerSpoken）", src.contains("private fun bannerSpoken(text: String)"))
        assertTrue("提示语要说清动作", src.contains("（点一下收起）"))
        assertTrue(
            "两条显示路径（重进页面恢复 / showBanner）都要补 contentDescription",
            Regex("contentDescription = bannerSpoken\\(").findAll(src).count() >= 2,
        )
        assertTrue(
            "dismissNetworkBanner 的「按文案精确比对」契约不能被破坏" +
                "（它靠这句话决定能不能收起「网络不通」那条）",
            src.contains("b.text?.toString() == getString(R.string.term_no_network)"),
        )
    }

    @Test
    fun `门禁_B补21_横幅挂 liveRegion`() {
        val src = repoFile("app/src/main/java/com/example/zhengdao/TerminalActivity.kt").readText()
        assertTrue(
            "安装进度/失败是异步冒出来的状态 ⇒ 必须挂 POLITE（此前全仓 liveRegion 命中 0）",
            src.contains("accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE"),
        )
    }

    @Test
    fun `门禁_B补21_pressClickable默认念按钮`() {
        val src = repoFile("app/src/main/java/com/example/zhengdao/ui/util/Press.kt").readText()
        assertTrue("要 import Role", src.contains("import androidx.compose.ui.semantics.Role"))
        assertTrue(
            "默认 role 必须是 Role.Button（整个 helper 的调用点一起受益）",
            src.contains("role: Role? = Role.Button"),
        )
        assertTrue("参数要真的传给 clickable，不能只声明", src.contains("role = role,"))
    }

    @Test
    fun `门禁_B补21_装饰符号与对勾不朗读`() {
        val cards = repoFile("app/src/main/java/com/example/zhengdao/ui/taiji/TaijiPartCards.kt").readText()
        assertTrue(
            "工具卡状态徽标的 ●/✓/✗ 是装饰（旁边就有「运行中/已完成/失败」文字）",
            cards.contains("clearAndSetSemantics"),
        )
        val screen = repoFile("app/src/main/java/com/example/zhengdao/ui/taiji/TaijiScreen.kt").readText()
        assertTrue("空会话提示里的 ☯ 是纯装饰", screen.contains("clearAndSetSemantics"))

        val sheet = repoFile("app/src/main/java/com/example/zhengdao/ui/taiji/ModelSheet.kt").readText()
        assertTrue("模型行的 ✓ 不朗读（视觉重复）", sheet.contains("clearAndSetSemantics"))
        assertTrue(
            "但选中态必须改由语义播报：多选一行 = 单选按钮",
            sheet.contains("pressClickable(role = Role.RadioButton)"),
        )
        assertTrue("选中态要显式声明，否则读屏不知道哪一行被选中", sheet.contains("this.selected = selected"))
    }
}
