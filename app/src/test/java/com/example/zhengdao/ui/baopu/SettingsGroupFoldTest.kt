// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第六十一批 §1（设置页工具开关折叠）。
//
// 现场（基线 origin/main）：`BaopuSettingsScreen.kt` 把 8 个组、29 个工具开关全平铺，
// 一页拉到底。§1 要「组头可点展开/收起、默认折叠」，同时明令两条：
// 1. **纯展示**——开关的读写、默认值、`DEFAULT_ENABLED`、权限与工具行为一个都不许动；
// 2. **不许把关闭项藏到看不见**——折叠态必须在组头下常驻一行「关闭项摘要」。
//
// 判据分两层：
// - P1–P6：折叠态摘要的生成（空组 / 全开 / 部分关 / 逐个关光 / 整组关 / 不串组），零 Android，直接打；
// - G1–G7：源码门禁（折叠状态必须 `rememberSaveable`、组头必须可点、摘要必须常驻且从
//   同一张分组表读、`prefs` 那几个读写点必须一个没变、新文案键中英都在）。
//
// 字符串保留、注释剥掉：赋值点钉的是文案内容（「记下来中…」这类），用 `CodeText.codeOnly`
// 会把字面量清空，反而钉不住。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsGroupFoldTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 剥注释（保留字符串）后的源码——所有源码门禁都读这一份，注释里的关键词不算命中。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun screen(): String =
        code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")

    private fun zhStrings(): String = repoFile("src/main/res/values/strings.xml").readText()
    private fun enStrings(): String = repoFile("src/main/res/values-en/strings.xml").readText()

    // ── P1–P6：折叠态摘要的生成（纯函数） ─────────────────────────────────

    private val members = listOf("read_file", "list_dir", "search_in_files")

    private fun off(members: List<String>, groupDisabled: Boolean, disabled: Set<String>) =
        BaopuToolFoldSummary.of(members, groupDisabled) { it in disabled }

    @Test
    fun `P1_空组说这一组没有工具`() {
        val s = off(emptyList(), false, emptySet())
        assertEquals(BaopuFoldKind.EmptyGroup, s.foldKind)
        assertTrue("空组不该凭空报出关闭项", s.offNames.isEmpty())
        assertEquals(0, s.total)
        // 组开关关掉一个空组：仍然是「没有工具」，不许写成「0 项已全部关闭」
        assertEquals(BaopuFoldKind.EmptyGroup, off(emptyList(), true, emptySet()).foldKind)
    }

    @Test
    fun `P2_全开时报全部开启`() {
        val s = off(members, false, emptySet())
        assertEquals(BaopuFoldKind.AllOn, s.foldKind)
        assertTrue("一个都没关 ⇒ 摘要不该列出任何名字", s.offNames.isEmpty())
        assertEquals(3, s.total)
    }

    @Test
    fun `P3_部分关时按表序列出关闭项`() {
        val s = off(members, false, setOf("list_dir"))
        assertEquals(BaopuFoldKind.SomeOff, s.foldKind)
        assertEquals("关闭项必须就是那个被关的工具", listOf("list_dir"), s.offNames)
        assertEquals(1, s.offNames.size)
        assertEquals(3, s.total)
    }

    @Test
    fun `P4_逐个关光算全关`() {
        val s = off(members, false, members.toSet())
        assertEquals(BaopuFoldKind.AllOff, s.foldKind)
        assertEquals("全关 ⇒ 关闭项就是全部成员（一个都不许藏）", members, s.offNames)
        assertEquals(members.size, s.total)
    }

    @Test
    fun `P5_整组开关关掉时关闭项是全部成员`() {
        // 组开关优先于单工具开关（引擎侧 isVisibleTool 同一口径）：这时组里**每一个**
        // 工具都不可见，所以摘要必须报全部成员——哪怕用户一个单工具开关都没碰过。
        val s = off(members, true, emptySet())
        assertEquals(BaopuFoldKind.AllOff, s.foldKind)
        assertEquals(members, s.offNames)
    }

    @Test
    fun `P6_关闭项只许是本组成员`() {
        // 名单由调用方给（分组表），别处传进来的名字不许混进这一组的摘要——
        // 那等于在 UI 上凭空多出一个"你关掉了它"的指控。
        val s = off(members, false, setOf("delete_path", "list_dir"))
        assertEquals(listOf("list_dir"), s.offNames)
        // 表序保持：摘要与展开后的开关行念的是同一个顺序，不重新排序
        val s2 = off(members, false, setOf("search_in_files", "read_file"))
        assertEquals(listOf("read_file", "search_in_files"), s2.offNames)
    }

    // ── G1–G7：§1 源码门禁 ───────────────────────────────────────────────

    @Test
    fun `G1_折叠状态用rememberSaveable且默认折叠`() {
        val src = screen()
        assertEquals(
            "折叠状态必须 rememberSaveable(键) { mutableStateOf(false) }，且只此一处",
            1,
            Regex("rememberSaveable\\(group\\.key\\) \\{ mutableStateOf\\(false\\) \\}").findAll(src).count(),
        )
        // 默认折叠：这一处初值是 false，不许有人改成 true（§1「默认折叠」）
        assertEquals(
            "折叠态那一格必须是 rememberSaveable 的（页面上别的 remember{mutableStateOf(false)} 是别的状态）",
            1,
            Regex("val expanded = rememberSaveable\\(group\\.key\\) \\{ mutableStateOf\\(false\\) \\}")
                .findAll(src).count(),
        )
        assertEquals(
            "不许用 remember 存折叠态（旋转/回前台会丢，收起一半又自己弹开）",
            0,
            Regex("val expanded\\s*=\\s*remember\\b").findAll(src).count(),
        )
        assertEquals(
            "折叠态初值只许 false（默认折叠，不许有人改成默认展开）",
            0,
            Regex("expanded = rememberSaveable\\(group\\.key\\) \\{ mutableStateOf\\(true\\) \\}")
                .findAll(src).count(),
        )
        // 也不许把折叠状态写进 SP：那是把 UI 姿势当配置存，跨会话串状态那一类雷
        assertFalse(
            "折叠状态不许落 SP",
            Regex("putBoolean\\([^)]*fold|getStringSet\\([^)]*fold").containsMatchIn(src),
        )
    }

    @Test
    fun `G2_组头必须可点`() {
        val src = screen()
        assertEquals(
            "组头整行可点＝展开/收起，且只切那一个状态",
            1,
            Regex("clickable \\{ expanded\\.value = !expanded\\.value \\}").findAll(src).count(),
        )
        // 箭头复用既成两把键（§1「能复用就复用」）
        assertTrue("展开态用 common_collapse_arrow", src.contains("R.string.common_collapse_arrow"))
        assertTrue("折叠态用 common_expand_arrow", src.contains("R.string.common_expand_arrow"))
        assertFalse(
            "不许为箭头再造一份字符串键（那是第二套话术）",
            src.contains("baopu_tools_fold_arrow") || zhStrings().contains("baopu_tools_fold_arrow"),
        )
    }

    @Test
    fun `G3_折叠态常驻一行关闭项摘要`() {
        val src = screen()
        val i = src.indexOf("if (!expanded.value) {")
        assertTrue("折叠态必须有那行摘要（§1：不许把关闭项藏到看不见）", i >= 0)
        val block = src.substring(i, minOf(src.length, i + 400))
        assertTrue("摘要行必须调 baopuFoldSummaryText(", block.contains("baopuFoldSummaryText("))
        // 顺序：摘要行在成员循环**之前**——展开时成员就在下面，收起时靠这一行兜底
        val j = src.indexOf("if (expanded.value) {")
        assertTrue("摘要行必须排在展开块之前", j > i)
    }

    @Test
    fun `G4_摘要成员只从那张唯一的分组表读`() {
        val src = screen()
        assertTrue(
            "成员名单只许来自 ToolGroupTable.namesOf(group)",
            src.contains("val members = ToolGroupTable.namesOf(group)"),
        )
        // 摘要与展开的开关共用同一个 members（同源，不可能一个报三个、另一个列五个）
        assertTrue(
            "摘要吃的必须是那一份 members",
            Regex("BaopuToolFoldSummary\\.of\\(members,").containsMatchIn(src),
        )
        assertTrue(
            "展开态列的也必须是那一份 members",
            Regex("if \\(expanded\\.value\\) \\{\\s*members\\.forEach").containsMatchIn(src),
        )
        // 页面里不许再出现第二份工具名单（§1「不新造第二张表」）
        assertFalse(
            "不许硬写工具名单",
            Regex("listOf\\(\\s*\"(read_file|list_dir|write_file|delete_path)\"").containsMatchIn(src),
        )
        // 表序 = 摘要与开关行的顺序都跟着 ToolGroupTable；这里顺手量一次真实表的组数，
        // 免得哪天有人把组数写死（页面上「8 组 / 29 个开关」这种数字）。
        val groups = ToolGroup.values().filter { it != ToolGroup.COMPAT }
        assertEquals("分组表读出来的组数", groups.size, ToolGroup.values().size - 1)
        assertTrue(groups.all { ToolGroupTable.namesOf(it).isNotEmpty() })
    }

    @Test
    fun `G5_开关的读写点一个都没动`() {
        val src = screen()
        // 两个写入点：原样、各一处（§1「不改任何开关的读写」；多一处就是偷偷写了状态）
        assertEquals(
            "组开关写入点必须原样且只有一处",
            1,
            Regex("prefs\\.setGroupDisabled\\(group, !want\\)").findAll(src).count(),
        )
        assertEquals(
            "单工具开关写入点必须原样且只有一处",
            1,
            Regex("prefs\\.setDisabled\\(name, !want\\)").findAll(src).count(),
        )
        // 读取点也各自只有一处：折叠不许变成第三种"算可见性"的路子
        assertEquals(1, Regex("group !in prefs\\.disabledGroups\\(\\)").findAll(src).count())
        assertEquals(1, Regex("!prefs\\.isDisabled\\(name\\)").findAll(src).count())
        assertEquals(
            "折叠态摘要读开关只能走 isDisabled 那一个入口",
            1,
            Regex("prefs\\.isDisabled\\(it\\)").findAll(src).count(),
        )
        // 页面能碰 prefs 的方法只有这四个（多出来的都是新造的读写口子）
        val calls = Regex("prefs\\.(\\w+)\\(").findAll(src).map { it.groupValues[1] }.toSet()
        assertEquals(
            "设置页只许读写这四个开关方法：$calls",
            setOf("setGroupDisabled", "setDisabled", "disabledGroups", "isDisabled"),
            calls,
        )
        // 默认可见那一句解释仍按 DEFAULT_VISIBLE 说，不因为折叠换个说法
        assertEquals(1, Regex("group in ToolGroup\\.DEFAULT_VISIBLE").findAll(src).count())
    }

    @Test
    fun `G6_新增折叠文案中英都在`() {
        val keys = listOf(
            "baopu_tools_fold_note",
            "baopu_tools_fold_empty",
            "baopu_tools_fold_all_on",
            "baopu_tools_fold_all_off",
            "baopu_tools_fold_some_off",
            "baopu_tools_fold_list_sep",
        )
        val src = screen()
        keys.forEach { k ->
            assertTrue("$k 中英两份都得有（values）", zhStrings().contains("name=\"$k\""))
            assertTrue("$k 中英两份都得有（values-en）", enStrings().contains("name=\"$k\""))
            // 加了键就必须真被用：零调用的文案键是下一次「改哪份」的坑
            assertTrue("$k 在设置页里没被引用", src.contains("R.string.$k"))
        }
        // 带位置参数的两条：占位符必须写全（漏一个 %n\$d 就渲染成原文）
        val zh = zhStrings()
        Regex("name=\"baopu_tools_fold_some_off\"[^<]*").find(zh)?.value?.let {
            assertTrue("「已关闭 N 项：名单」两个占位符都要有", it.contains("%1\$d") && it.contains("%2\$s"))
        }
        Regex("name=\"baopu_tools_fold_all_off\"[^<]*").find(zh)?.value?.let {
            assertTrue("「这一组 N 项已全部关闭」要有个数占位符", it.contains("%1\$d"))
        }
    }

    @Test
    fun `G7_展开态仍然逐个列开关`() {
        val src = screen()
        val i = src.indexOf("if (expanded.value) {")
        assertTrue("展开块还在", i >= 0)
        // 取到「read / bash 只读说明」之前为止（那一段紧跟在分组循环后面）
        val end = src.indexOf("R.string.baopu_tools_alias_read", i)
        assertTrue("循环后面那段只读说明还在", end > i)
        val block = src.substring(i, end)
        assertTrue("展开态必须有单工具开关的写入点", block.contains("prefs.setDisabled(name, !want)"))
        assertTrue("展开态必须有工具名与说明", block.contains("baopuToolLabel(name)") && block.contains("baopuToolDesc(name)"))
        assertTrue("展开态必须有开关本体", Regex("Switch\\(").containsMatchIn(block))
        // 折叠只是不渲染成员，成员名单本身一个没减：摘要与开关行读的是同一份 members（G4 钉）
        assertEquals("展开块里只能有一处成员遍历（不许按折叠态再筛一遍名单）", 1, Regex("members\\.forEach").findAll(block).count())
        // 「读/写工具说明」那两行只读展示（18 批 §2.1-4）在循环外 ⇒ 不随组收起
        val alias = src.indexOf("R.string.baopu_tools_alias_read")
        assertTrue("read/bash 只读说明必须在循环外（常驻，不随组收起）", alias > src.lastIndexOf("if (expanded.value) {"))
    }
}
