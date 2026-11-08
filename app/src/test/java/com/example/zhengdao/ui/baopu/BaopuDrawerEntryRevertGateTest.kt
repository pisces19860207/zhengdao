// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十六批：**回退 528**「抽屉里的决策中心主屏」——本门禁钉回退后的三个形状：
//   ① 抽屉「会话组」里没有「决策中心」那一行，且「新建会话」仍是**第一行**（下标断言）；
//   ② 判定页签仍是**五页签**且御案（ROYAL）排第一——528 一个字节没动它，回退也不许动；
//   ③ `DecisionZone*` 一个文件都不许留在树上（判定层／屏幕／中英文案／两份测试全撤）。
//
// 扫法照本仓既有源级门禁（229 `BaopuDrawerScheduleGateTest`＝行的渲染顺序就是书写顺序），
// 另加一条纪律：**正例与负例共用同一把尺**。负例喂的是「在新建会话之上塞一行」的假源码、
// 以及「真放一个 DecisionZoneScreen.kt 回去」的假目录——尺子不响，就是这条断言假绿。
package com.example.zhengdao.ui.baopu

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuDrawerEntryRevertGateTest {

    // ── 定位（Gradle 单测的工作目录是 app/，同时容忍从仓库根跑）────────────────

    private fun dirOf(candidates: List<String>, what: String): File =
        candidates.map(::File).firstOrNull { it.isDirectory }
            ?: throw AssertionError("找不到 $what（工作目录 ${File(".").absolutePath}）")

    private fun dir(rel: String, what: String): File = dirOf(
        listOf(rel, "../app/$rel", "app/$rel"),
        what,
    )

    private val mainUi by lazy { dir("src/main/java/com/example/zhengdao/ui/baopu", "ui/baopu（main）") }
    private val testUi by lazy { dir("src/test/java/com/example/zhengdao/ui/baopu", "ui/baopu（test）") }

    /**
     * 读源码并**归一化行尾**：库存 LF、工作树 CRLF，锚点用 `\n`
     * （不归一化，所有多行锚点在 Windows 上必假红）。
     */
    private fun source(rel: String): String {
        val f = File(mainUi, rel)
        if (!f.isFile) throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    // ── 尺子一：抽屉「会话组」之后的第一行标题 ────────────────────────────────

    private val rowTitle = Regex("""title\s*=\s*stringResource\((R\.string\.[A-Za-z0-9_]+)\)""")

    /** 同一把尺：`DrawerGroup(会话组)` 之后第一个 `title = stringResource(...)` 的字符串锚。 */
    private fun firstSessionRowTitle(src: String): String? {
        val atGroup = src.indexOf("R.string.baopu_drawer_group_session")
        if (atGroup < 0) return null
        return rowTitle.findAll(src)
            .firstOrNull { it.range.first > atGroup }
            ?.groupValues?.get(1)
    }

    // ── 尺子二：目录下名字以某前缀开头的文件 ────────────────────────────────

    private fun filesByPrefix(dir: File, prefix: String): List<String> =
        dir.walkTopDown()
            .filter { it.isFile && it.name.startsWith(prefix) }
            .map { it.name }
            .sorted()
            .toList()

    // ── 判据①：抽屉那一行撤了，「新建会话」回到第一行 ────────────────────────

    @Test
    fun `抽屉里没有决策中心那一行_新建会话仍是第一行`() {
        val drawer = source("BaopuDrawer.kt")
        assertFalse(
            "528 的「决策中心」行必须已撤（题面 §一①：它在「新建会话」之上）",
            drawer.contains("baopu_drawer_decision_center"),
        )
        assertFalse("528 的回调参数 onDecisionCenter 必须已撤", drawer.contains("onDecisionCenter"))
        assertEquals(
            "会话组第一行必须仍是「新建会话」（下标断言）",
            "R.string.baopu_new_session",
            firstSessionRowTitle(drawer),
        )
    }

    @Test
    fun `负例_在新建会话之上塞一行_同一把尺必须读出那一行`() {
        val drawer = source("BaopuDrawer.kt")
        val anchor = "        DrawerItem(\n            title = stringResource(R.string.baopu_new_session),"
        assertTrue("锚点丢了（抽屉排版变了要回来重钉，别把它改成恒真）", drawer.contains(anchor))
        val mutated = drawer.replace(
            anchor,
            "        DrawerItem(\n" +
                "            title = stringResource(R.string.baopu_drawer_decision_center),\n" +
                "            description = \"x\",\n" +
                "            onClick = {},\n" +
                "        )\n" + anchor,
        )
        assertEquals(
            "尺子必须分得清「谁在第一行」：插一行就应读出那一行",
            "R.string.baopu_drawer_decision_center",
            firstSessionRowTitle(mutated),
        )
    }

    // ── 判据①的另一半：抱朴页上不再挂那屏 ──────────────────────────────────

    @Test
    fun `抱朴页不再挂决策中心主屏`() {
        val page = source("BaopuScreen.kt")
        assertFalse("528 的浮层状态 showDecisionZone 必须已撤", page.contains("showDecisionZone"))
        assertFalse("528 的屏幕调用必须已撤", page.contains("DecisionZoneScreen("))
        // 反向自证：同一份文件里 104 批那套（顶栏盾牌进的）必须还在——回退只该动 528 那一处。
        assertTrue(
            "104 批的决策中心浮层不许被连带删掉（showDecisionCenter 仍应在）",
            page.contains("showDecisionCenter"),
        )
    }

    // ── 判据②：五页签、御案排第一（528 没动过它，回退也不许动）───────────────

    @Test
    fun `判定页签仍是五页签且御案排第一`() {
        assertEquals("页签数必须是 5（题面 §一③）", 5, DecisionTab.entries.size)
        assertEquals(
            "页签顺序必须原样：御案 → 待授权 → 建议 → 提案 → 记录",
            listOf(
                DecisionTab.ROYAL,
                DecisionTab.TO_AUTHORIZE,
                DecisionTab.SUGGESTIONS,
                DecisionTab.PROPOSALS,
                DecisionTab.HISTORY,
            ),
            DecisionTab.entries.toList(),
        )
        // 尺子自证：顺序反过来必须不等，否则上面那行断言分不出顺序（只会数个数）。
        assertNotEquals(
            "这条断言必须对顺序敏感",
            DecisionTab.entries.toList(),
            DecisionTab.entries.reversed().toList(),
        )
    }

    // ── 判据③：DecisionZone* 一个文件都不许留 ────────────────────────────────

    @Test
    fun `DecisionZone 文件与中英文案一个都不许留`() {
        assertEquals(
            "main 侧的判定层／屏幕必须全撤（git ls-files 里 DecisionZone* 为空）",
            emptyList<String>(),
            filesByPrefix(mainUi, "DecisionZone"),
        )
        assertEquals(
            "528 那两份测试也必须全撤",
            emptyList<String>(),
            filesByPrefix(testUi, "DecisionZone"),
        )
        listOf("values", "values-en").forEach { v ->
            val rel = "src/main/res/$v/strings_baopu_decision_zone.xml"
            val f = listOf(rel, "../app/$rel", "app/$rel").map(::File).firstOrNull { it.isFile }
            assertEquals("528 的文案 $rel 必须已撤", null, f)
        }
        // 自证：尺子在同一批目录里认得出真文件（否则「空」可能只是没扫到）。
        assertEquals(
            "尺子必须认得出同目录下的真文件（判空之前先证明扫得到）",
            listOf("DecisionCenterScreen.kt"),
            filesByPrefix(mainUi, "DecisionCenterScreen"),
        )
    }

    @Test
    fun `负例_真放一个 DecisionZoneScreen_kt 回去_同一把尺必须报出来`() {
        val tmp = Files.createTempDirectory("zd536").toFile()
        try {
            File(tmp, "DecisionZoneScreen.kt").writeText("// 假的\n", Charsets.UTF_8)
            File(tmp, "DecisionCenterScreen.kt").writeText("// 对照\n", Charsets.UTF_8)
            assertEquals(
                "尺子不响＝上面的判空是假绿",
                listOf("DecisionZoneScreen.kt"),
                filesByPrefix(tmp, "DecisionZone"),
            )
        } finally {
            tmp.deleteRecursively()
        }
    }
}
