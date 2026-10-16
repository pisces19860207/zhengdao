// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-补-20（2026-10-16）：触摸目标守卫 —— 把「M3/Google 无障碍最小触摸目标 48dp」钉成可执行判据。
 *
 * 台账原条目（`docs/FEATURE-LEDGER.md` §B-补 ④）：
 * `- **B-补-20**（R5 / P3-6）触摸目标偏小：模型胶囊约 40dp（BaopuComponents.kt:227-250）、
 *  抽屉插件行与结束会话行约 36dp（:1676-1682）、权限 Checkbox 文字 24dp 不可点（:1194）、
 *  终端红点 30dp、res/layout/term_keys.xml 第二行 12 键在 360dp 屏每键约 28dp`
 *
 * 2026-10-16 逐条按当前代码复核（文件已随 P4-1 拆分，行号全部失效）：
 * - 模型胶囊、`DrawerActionRow`、`SheetActionRow`、`BaopuPartCards` 的两处 —— **都已达标**（各自带 48dp 注释；
 *   `BaopuPartCards` 2026-10-16 清场已删，刀 1 重做时按同样判据补）；
 * - **终端三个流量灯 30dp** ⇒ 本刀修（热区 48dp，inset 同步改 18.5dp 保持 11dp 视觉）；
 * - **抽屉「新会话」入口 14dp 竖内边距 ≈ 44dp** ⇒ 本刀修（16dp ≈ 48dp）；
 * - **权限批准抽屉的「记住选择」：裸 Checkbox ＋ 兄弟 Text，文字不可点** ⇒ 本刀修（整行 toggleable）；
 * - **term_keys 第二行按键宽约 27dp**（360dp 屏 12 键 weight 均分；**高度已是 48dp**，见
 *  `res/values/themes.xml` 的 `TermKeyFlex`）⇒ 属既有设计取舍（2026-10-08 刻意取消横向滚动、
 *  12 键等宽均分一屏可见），改宽度要重排键盘布局、会动终端可视高度 ⇒ **留痕不修**，
 *  记在台账处置里，等真机手感实测后再决定要不要动。
 *
 * 判据都走「源码级取值 ＋ 断言」，与 [A11yBannerGuardTest] / [RouteRestoreGuardTest] 同惯例：
 * 本模块没有 UI 测试基建，这些是布局常量，源码就是唯一真源。
 */
class TouchTargetGuardTest {

    /** 单测的工作目录是模块目录（`app/`），也可能是仓库根。 */
    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    /** 抽一个 `@+id/<id>` View 的 XML 片段（到 `/>` 或该元素结束）。 */
    private fun viewBlock(xmlRel: String, id: String): String {
        val xml = repoFile(xmlRel).readText()
        val start = xml.indexOf("@+id/$id")
        assertTrue("$xmlRel 里找不到 @+id/$id", start >= 0)
        val end = xml.indexOf("/>", start).let { if (it < 0) xml.length else it + 2 }
        return xml.substring(start, end)
    }

    private fun dpAttr(block: String, attr: String): Int {
        val m = Regex("""android:$attr="(\d+)dp"""").find(block)
            ?: throw AssertionError("片段里找不到 android:$attr=\"<n>dp\"：\n$block")
        return m.groupValues[1].toInt()
    }

    @Test
    fun `门禁_B补20_终端三个流量灯热区不小于48dp`() {
        val xml = "app/src/main/res/layout/activity_main.xml"
        for ((id, what) in listOf(
            "btn_close" to "关闭终端（整页唯一的出口）",
            "btn_yellow" to "收起分屏",
            "btn_split" to "上下分屏",
        )) {
            val block = viewBlock(xml, id)
            val w = dpAttr(block, "layout_width")
            val h = dpAttr(block, "layout_height")
            assertTrue("B-补-20：$id（$what）热区 ${w}×${h}dp 小于 48dp", w >= 48 && h >= 48)
        }
    }

    @Test
    fun `门禁_B补20_工具条本身不小于48dp否则热区会被裁掉`() {
        val xml = repoFile("app/src/main/res/layout/activity_main.xml").readText()
        // 三个圆点所在的横向工具条：从 btn_close 往上找最近的 <LinearLayout 开标签。
        val closeAt = xml.indexOf("@+id/btn_close")
        assertTrue("找不到 btn_close", closeAt >= 0)
        val rowStart = xml.lastIndexOf("<LinearLayout", closeAt)
        assertTrue("btn_close 不在 LinearLayout 里（布局变了？）", rowStart >= 0)
        val block = xml.substring(rowStart, closeAt)
        val h = dpAttr(block, "layout_height")
        assertTrue("B-补-20：工具条只有 ${h}dp 高，里面 48dp 的热区会被容器裁掉", h >= 48)
    }

    @Test
    fun `门禁_B补20_圆点inset与热区配平视觉仍是11dp`() {
        // 热区 48dp、inset 每边 18.5dp ⇒ 视觉圆点 48 − 18.5×2 = 11dp。
        // 改热区却忘了改 inset，圆点就会跟着变大（=偷偷改了界面视觉）。
        for (name in listOf("red", "yellow", "green")) {
            val xml = repoFile("app/src/main/res/drawable/traffic_dot_$name.xml").readText()
            for (side in listOf("Left", "Right", "Top", "Bottom")) {
                val m = Regex("""android:inset$side="([\d.]+)dp"""").find(xml)
                assertTrue("traffic_dot_$name.xml 找不到 inset$side", m != null)
                assertTrue(
                    "B-补-20：traffic_dot_$name.xml 的 inset$side=${m!!.groupValues[1]}dp，圆点视觉尺寸会偏离 11dp",
                    m.groupValues[1].toDouble() == 18.5,
                )
            }
        }
    }

    // 2026-10-16 清场：原「门禁_B补20_抽屉新会话入口竖内边距16dp」与
    // 「门禁_B补20_权限记住选择整行可点而不是只有方框」随 ui/baopu/**（BaopuHistoryDrawer /
    // BaopuSheets）删除而移除。抱朴线刀 1 重做抽屉与权限 sheet 时按同样判据补回。

    @Test
    fun `门禁_B补20_快捷键条按键高度仍是48dp`() {
        // 宽度（360dp 屏 12 键 ≈ 27dp）属既有取舍，已在台账留痕；**高度**这条不能再退。
        val styles = repoFile("app/src/main/res/values/themes.xml").readText()
        val start = styles.indexOf("<style name=\"TermKeyFlex\">")
        assertTrue("themes.xml 里找不到 TermKeyFlex", start >= 0)
        val block = styles.substring(start, (start + 600).coerceAtMost(styles.length))
        assertTrue(
            "B-补-20：TermKeyFlex 的 android:layout_height 掉了 48dp（键高是终端手感的命脉）",
            block.contains("<item name=\"android:layout_height\">48dp</item>"),
        )
    }
}
