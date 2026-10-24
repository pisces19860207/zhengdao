// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百六十九批 §2：终端页切换基准的完成信号必须是**真实锚点**。
 *
 * ## 旧写法的毛病
 *
 * 旧基调用 `Until.gone(By.text("太极"))` 当「已进入终端页」的完成信号。可「太极」是
 * 主页底栏的 Tab——终端页是独立 `Activity`，一压上去底栏本来就没了，**gone 立即为 true**，
 * 量到的只是「点了之后马上返回」，根本没等终端拉起。读数好看，测的不是切换。
 *
 * ## 今天钉住的四件事
 *
 * 1. 全文件不许再出现 `太极`——底栏早改名为「抱朴/逍遥/玄览/怡然」，旧字串一个都不许留；
 * 2. 正向基准必须**先断言起点**（终端锚点不可见）→ 点「逍遥」→ 等锚点**出现**，
 *    且三步顺序就是这个顺序（先等后点＝没测到点击）；
 * 3. 锚点常量是终端页恒在的 `ESC`（`res/layout/term_keys.xml` 的 `term_key_esc`），
 *    两个 Tab 字串必须与 `strings.xml` 的 `nav_terminal`/`nav_taiji` 逐字相等；
 * 4. 负例必须存在：不点任何东西、页面保持原样，等待锚点仍须**失败**（`check(!appeared)`），
 *    且负例只判行为（不走 measureRepeated/FrameTimingMetric——页面不动就没有新帧，
 *    指标会以「no renderthread slices」先失败，那读不出「锚点有没有区分力」）。
 *
 * 门禁按**源码文本**判定：设备上的行为由 macrobenchmark 自己跑，这里防的是
 * 「有人把完成信号又改回一个主页上也存在的元素」——那种改动单测跑不出来，但一定是假读数。
 */
class BenchmarkAnchorGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }
            .let { if (it.isFile) it else File("../$rel") }

    private fun raw(rel: String): String =
        repoFile(rel).readText().replace("\r\n", "\n")

    private fun code(rel: String): String = raw(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val benchSrc =
        "macrobenchmark/src/main/java/com/example/zhengdao/macrobenchmark/ZhengdaoBenchmark.kt"

    /** 读 `strings.xml` 里某个字串的真值；找不到 ⇒ 红（改名了就得来看一眼）。 */
    private fun stringRes(name: String): String {
        val m = Regex("<string name=\"$name\">([^<]*)</string>")
            .find(raw("app/src/main/res/values/strings.xml"))
        assertTrue("strings.xml 里找不到 `$name`", m != null)
        return m!!.groupValues[1]
    }

    private fun const(name: String): String {
        val m = Regex("private const val $name = \"([^\"]*)\"").find(code(benchSrc))
        assertTrue("基准源码里找不到常量 `$name`", m != null)
        return m!!.groupValues[1]
    }

    /** 取两个标记之间的源码；标记缺失 ⇒ 直接红（改名/删函数都要有人来看一眼）。 */
    private fun slice(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("基准源码里找不到「$from」——函数改名/删除必须同步本门禁", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("基准源码里找不到「$to」（在「$from」之后）", end > start)
        return src.substring(start, end)
    }

    private fun terminalSwitch(): String =
        slice(code(benchSrc), "fun terminalSwitch()", "fun terminalSwitchNegative")

    private fun negative(): String {
        val all = code(benchSrc)
        val start = all.indexOf("fun terminalSwitchNegativeUnchangedPageMustFail")
        assertTrue("负例函数必须存在", start >= 0)
        val end = all.indexOf("@Test", start)
        assertTrue("负例后面必须还有别的场景（找不到下一个 @Test ⇒ 切不干净）", end > start)
        return all.substring(start, end)
    }

    @Test
    fun `旧太极锚点与旧字串必须清零`() {
        val all = code(benchSrc)
        assertFalse(
            "旧完成信号 `Until.gone(By.text(\"太极\"))` 在主页上立即成立，量不到切换，必须删干净",
            all.contains("Until.gone(By.text(\"太极\"))"),
        )
        assertFalse("「太极」底栏早已改名「抱朴」，全文件不许再出现", raw(benchSrc).contains("太极"))
        assertFalse("「终端」Tab 早已改名「逍遥」，旧点击字串不许再出现", all.contains("\"终端\""))
        assertFalse("「丹房」Tab 早已改名「怡然」，旧点击字串不许再出现", all.contains("\"丹房\""))
    }

    @Test
    fun `基准里的Tab字串必须与strings_xml逐字相等`() {
        assertEquals("底部终端 Tab 必须用 strings.xml 的 nav_terminal 真值", stringRes("nav_terminal"), const("TERMINAL_TAB"))
        assertEquals("底部主页 Tab 必须用 strings.xml 的 nav_taiji 真值", stringRes("nav_taiji"), const("HOME_TAB"))
        assertEquals("主页标题必须用 app_name 真值", stringRes("app_name"), const("HOME_TITLE"))
    }

    @Test
    fun `正向基准先断言起点再点再等真实锚点出现`() {
        val body = terminalSwitch()
        val startGuard = body.indexOf("device.findObject(By.text(TERMINAL_ANCHOR)) == null")
        val tap = body.indexOf("device.tapText(TERMINAL_TAB)")
        val waitAnchor = body.indexOf("Until.hasObject(By.text(TERMINAL_ANCHOR))")

        assertTrue("必须显式断言起点：终端锚点在主页不可见（否则这一轮测不到切换）", startGuard >= 0)
        assertTrue("必须点底部「逍遥」Tab", tap >= 0)
        assertTrue("必须等终端锚点**出现**（hasObject），不是等底栏消失", waitAnchor >= 0)
        assertTrue(
            "顺序必须「断言起点 → 点 → 等锚点」：现在 start=$startGuard tap=$tap wait=$waitAnchor",
            startGuard < tap && tap < waitAnchor,
        )
        assertFalse("只有一次点击，不许把等待写进循环里蒙混过关", body.split("device.tapText(TERMINAL_TAB)").size > 2)
    }

    @Test
    fun `锚点常量是终端页恒在的ESC`() {
        val all = code(benchSrc)
        assertTrue(
            "终端锚点必须是 `private const val TERMINAL_ANCHOR = \"ESC\"`（term_keys.xml 的 term_key_esc）",
            Regex("private const val TERMINAL_ANCHOR = \"ESC\"").containsMatchIn(all),
        )
        assertTrue(
            "完成信号必须引用 TERMINAL_ANCHOR 常量，不许另写一个字符串",
            all.contains("Until.hasObject(By.text(TERMINAL_ANCHOR))"),
        )
    }

    @Test
    fun `负例不点页面且必须期望锚点等待失败`() {
        val body = negative()
        assertFalse("负例不许点任何东西（点了就不叫「页面没变化」）", body.contains("device.tapText("))
        assertTrue(
            "负例必须复用同一个锚点等待",
            body.contains("Until.hasObject(By.text(TERMINAL_ANCHOR))"),
        )
        assertTrue(
            "负例必须断言等待**失败**（check(!appeared) / assertFalse）——否则锚点没区分力也能过",
            body.contains("check(!appeared)") || body.contains("assertFalse(appeared)"),
        )
        assertTrue(
            "负例必须把 App 拉回主页再判（起点得是「可切换但没切」的页面）",
            body.contains("am start -n \$PKG/.MainActivity"),
        )
        assertFalse(
            "负例不走 measureRepeated/FrameTimingMetric：页面不动没有新帧，指标会先红，读不出锚点有没有区分力",
            body.contains("measureRepeated") || body.contains("FrameTimingMetric"),
        )
    }}
