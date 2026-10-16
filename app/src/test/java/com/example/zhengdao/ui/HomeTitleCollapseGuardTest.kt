// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-补-17（2026-10-16）：首页大标题随滚动收缩 —— 源码级门禁。
 *
 * 台账条目（`docs/FEATURE-LEDGER.md` §B-补 ④）：
 * `- **B-补-17**（美化第 11 条）首页标题常驻 22sp、不随滚动收缩：ui/HomeScreen.kt:259
 *  style = MaterialTheme.typography.titleLarge`（另见同节复核 blockquote：「该文件**无**
 *  TopAppBar / scrollBehavior / collapsed 命中 ⇒ 标题常驻、不随滚动收缩」）。
 *
 * 本刀口径（iOS large title 的简化版，**不引入 TopAppBar/scrollBehavior** —— 首页顶栏是自绘 Row，
 * 换 Container 会把状态卡与设置入口一起挪位）：
 * - 展开档 = `typography.titleLarge`（列表在顶部时与改前**逐像素一致**，零视觉变化）；
 * - 收起档 = `typography.titleMedium`；
 * - 两档之间用 `animateFloatAsState` ＋ `lerp` 按滚动量插值；
 * - 触发判据只看**列表滚动量**（`firstVisibleItemIndex` / `firstVisibleItemScrollOffset`），
 *   不看时间、不看是否正在拖动 —— 滚回顶部标题自己长回来。
 *
 * 为什么两档都从主题槽位取：B-补-18 要动 `ui/theme/Type.kt` 的字号阶梯，
 * 若这里写死 16.sp/22.sp，阶梯一改就与标题脱节（这正是台账点名的那类「一处改了、另一处写死」）。
 */
class HomeTitleCollapseGuardTest {

    private val homeRel = "app/src/main/java/com/example/zhengdao/ui/HomeScreen.kt"

    /** 单测的工作目录是模块目录（`app/`），也可能是仓库根。 */
    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    /**
     * 剥掉**注释**后的源码（先剥多行注释，再剥整行单行注释）；判据一律读它。
     *
     * ⚠️ 顺带把 CRLF 归一成 LF：仓库 `.kt` 全是 CRLF，而本类的 `region()` 用 `")\n"` 这类
     * **带换行的锚点**截段 —— 不归一的话首次运行就会报「找不到结束锚点」。
     */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val code by lazy { code(homeRel) }

    /** 取 `start` 之后、`end` 之前的那段源码（两端都必须是源码里真实存在的锚点）。 */
    private fun region(src: String, start: String, end: String): String {
        val i = src.indexOf(start)
        assertTrue("源码里找不到锚点：$start", i >= 0)
        val j = src.indexOf(end, i)
        assertTrue("锚点 $start 之后找不到结束锚点：$end", j > i)
        return src.substring(i, j)
    }

    @Test
    fun `门禁_B补17_首页标题不再裸用titleLarge`() {
        assertFalse(
            "首页标题又退回常驻的裸 `style = MaterialTheme.typography.titleLarge`（B-补-17 的缺陷本体）",
            code.contains("style = MaterialTheme.typography.titleLarge"),
        )
        assertTrue(
            "首页标题应当用随滚动插值出来的 `titleStyle`",
            code.contains("style = titleStyle,"),
        )
    }

    @Test
    fun `门禁_B补17_折叠判据读列表滚动量`() {
        assertTrue("判据必须来自列表滚动（derivedStateOf 包住 listState 的读数）", code.contains("derivedStateOf"))
        assertTrue(
            "判据要看「同一项内已滚的像素」：listState.firstVisibleItemScrollOffset",
            code.contains("listState.firstVisibleItemScrollOffset"),
        )
        assertTrue(
            "判据要看「整项已滚出」：listState.firstVisibleItemIndex",
            code.contains("listState.firstVisibleItemIndex"),
        )
    }

    @Test
    fun `门禁_B补17_收起阈值是正数dp常量且换算成px`() {
        val m = Regex("private val HOME_TITLE_COLLAPSE_DISTANCE = ([0-9]+(?:\\.[0-9]+)?)\\.dp")
            .find(code)
        assertTrue("找不到收起阈值常量 `HOME_TITLE_COLLAPSE_DISTANCE = <n>.dp`", m != null)
        val v = m!!.groupValues[1].toDouble()
        assertTrue("收起阈值必须是正数（现在是 $v dp）——0 会让标题一路抖动", v > 0.0)
        assertTrue("收起阈值不该大到「滚一整屏都不收」（现在是 $v dp）", v <= 48.0)
        assertTrue(
            "阈值必须换算成 px 再与滚动量比（dp 与 px 直接比会在高密度屏上形同虚设）",
            code.contains("HOME_TITLE_COLLAPSE_DISTANCE.toPx()"),
        )
    }

    @Test
    fun `门禁_B补17_两档字号都取自主题槽位不写死sp`() {
        assertTrue(
            "展开档必须取主题槽位 titleLarge",
            code.contains("val expandedTitle = MaterialTheme.typography.titleLarge"),
        )
        assertTrue(
            "收起档必须取主题槽位 titleMedium（不能是 expandedTitle 的别名 —— 那样两档同字号，等于没收缩）",
            code.contains("val collapsedTitle = MaterialTheme.typography.titleMedium"),
        )
        val block = region(code, "val titleStyle = expandedTitle.copy(", ")\n")
        assertFalse(
            "插值块里不许再出现写死的 sp（字号要跟着 Type.kt 的阶梯走）：$block",
            Regex("[0-9]+(?:\\.[0-9]+)?\\.sp").containsMatchIn(block),
        )
    }

    @Test
    fun `门禁_B补17_收缩是插值动画不是硬跳`() {
        assertTrue(
            "收缩必须用 animateFloatAsState 过渡，不能 if/else 硬切",
            code.contains("animateFloatAsState("),
        )
        assertTrue(
            "动画目标值要跟随滚动判据 titleScrolled",
            Regex("targetValue = if \\(titleScrolled\\) 1f else 0f").containsMatchIn(code),
        )
        val block = region(code, "val titleStyle = expandedTitle.copy(", ")\n")
        assertTrue("字号要插值：lerp(…fontSize…)", block.contains("fontSize = lerp("))
        assertTrue("行高要一起插值：lerp(…lineHeight…)，否则收起时行距还是 28sp", block.contains("lineHeight = lerp("))
    }

    @Test
    fun `门禁_B补17_注释留痕且顶栏旧注释已同步`() {
        val raw = repoFile(homeRel).readText()
        assertTrue("源码里要留下本刀的口径注释（B-补-17（2026-10-16））", raw.contains("B-补-17（2026-10-16）"))
        assertFalse(
            "顶栏旧注释「标题常驻、内容在下方自滚」已与本刀相反，必须改掉（否则注释骗后来人）",
            raw.contains("标题常驻、内容在下方自滚"),
        )
        assertTrue(
            "顶栏注释要写明「不随内容滚出屏幕，但会收缩」这层新行为",
            raw.contains("不随内容滚出屏幕，但会收缩"),
        )
    }
}
