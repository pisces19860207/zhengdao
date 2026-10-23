// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据：派工单第一百四十一批（抱朴设置重构 §8「抱朴工具区 · 卡片化」）。
//
// 现场（141 之前）：`BaopuSettingsScreen.kt` 的「抱朴工具」是一张长列表——三段落说明、
// 每组一行开关、行内还有「展开看具体工具」，一屏放不下（用户 09:15「太长了」）。
//
// §8 的新形态：**一张摘要卡**。收起只有一行三个数字（工具 N · M 组开 · K 组待同意），
// 点开只列**要你处理**的组（待同意 / 你关掉的 / 组里被单独关掉几个的）；
// **成员名单不进 UI**（用户原话「谁关心你具体是哪些工具啊」）⇒ 单工具开关一并退役。
//
// 判据分两层：
// - S1–S10：摘要卡判据本体（零 Android、直接打）——三个数字怎么算、哪些组露面、顺序、
//   数字与行列是否同源、空边界；
// - G1–G12：源码门禁——卡片档位、三个占位符、成员名单不许回潮、行内展开按钮不许回潮、
//   组开关写入点各一处、热区、200% 字号不裁字、琥珀只给「待同意」、
//   五个新文案键中英都在且都被引用、prefs 读写口白名单、旧三段长文案不许回来、数字必须订阅 toolTick。
//
// 会话安全（141）：文件按相对路径找仓库根，与 61/81/97 批同款。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupTable
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAreaCardTest {

    // ── 0. 读源码的小工具（与 SettingsGroupFoldTest / SliceGateTest 同一套）────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 剥注释（保留字符串）后的源码——门禁只认代码，注释里的关键词不算命中。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun page(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
    private fun rules(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsRules.kt")
    private fun zh(): String = repoFile("src/main/res/values/strings.xml").readText()
    private fun en(): String = repoFile("src/main/res/values-en/strings.xml").readText()

    // ── 1. 判据本体的夹具 ───────────────────────────────────────────────────────

    private val groups = baopuSwitchableGroups(ToolGroup.values())

    private fun stateOf(disabled: Set<ToolGroup> = emptySet(), approved: Set<ToolGroup> = emptySet()) =
        { g: ToolGroup ->
            baopuGroupState(
                defaultVisible = g in ToolGroup.DEFAULT_VISIBLE,
                approved = g in approved,
                disabled = g in disabled,
            )
        }

    private val membersOf: (ToolGroup) -> List<String> = { ToolGroupTable.namesOf(it) }

    private fun summarize(
        disabledGroups: Set<ToolGroup> = emptySet(),
        approved: Set<ToolGroup> = emptySet(),
        off: Set<String> = emptySet(),
    ) = BaopuToolArea.summarize(
        groups,
        stateOf(disabledGroups, approved),
        membersOf,
    ) { it in off }

    private fun rows(
        disabledGroups: Set<ToolGroup> = emptySet(),
        approved: Set<ToolGroup> = emptySet(),
        off: Set<String> = emptySet(),
    ) = BaopuToolArea.rowsOf(
        groups,
        stateOf(disabledGroups, approved),
        membersOf,
    ) { it in off }

    /** 默认可见组的成员总数（现算，不写死 12——表加工具时不该假红）。 */
    private fun defaultVisibleTools(): Int =
        groups.filter { it in ToolGroup.DEFAULT_VISIBLE }.sumOf { ToolGroupTable.namesOf(it).size }

    // ── 2. S1–S10：摘要卡的判据本体 ─────────────────────────────────────────────

    @Test
    fun `S1_默认态_只有核心与编辑两组开_其余全在等点头`() {
        val s = summarize()
        assertEquals("默认可见的工具数", defaultVisibleTools(), s.toolCount)
        assertEquals("默认开着的组数", 2, s.groupOnCount)
        assertEquals("等点头的组数＝上页组数减默认可见两组", groups.size - 2, s.pendingGroupCount)
        // 三数互不串位（谁被写成谁都会在这里露）
        assertTrue("默认态下工具数必须大于 0", s.toolCount > 0)
        assertTrue("待同意组必须大于 0（否则这条用例失去了意义）", s.pendingGroupCount > 0)
    }

    @Test
    fun `S2_长期同意一组_待同意减一_组开加一_工具数按成员数涨`() {
        val before = summarize()
        val target = groups.first { it !in ToolGroup.DEFAULT_VISIBLE }
        val after = summarize(approved = setOf(target))
        assertEquals(before.pendingGroupCount - 1, after.pendingGroupCount)
        assertEquals(before.groupOnCount + 1, after.groupOnCount)
        assertEquals(before.toolCount + ToolGroupTable.namesOf(target).size, after.toolCount)
    }

    @Test
    fun `S3_关掉一组_组开减一_工具数掉该组全部成员_待同意不动`() {
        val target = ToolGroup.EDIT
        val before = summarize()
        val after = summarize(disabledGroups = setOf(target))
        assertEquals(before.groupOnCount - 1, after.groupOnCount)
        assertEquals(before.toolCount - ToolGroupTable.namesOf(target).size, after.toolCount)
        assertEquals("关一组不该改变「等点头」的组数", before.pendingGroupCount, after.pendingGroupCount)
    }

    @Test
    fun `S4_组内单独关一个工具_工具数减一_组开关与待同意都不受影响`() {
        val one = ToolGroupTable.namesOf(ToolGroup.CORE).first()
        val before = summarize()
        val after = summarize(off = setOf(one))
        assertEquals(before.toolCount - 1, after.toolCount)
        assertEquals("单工具开关不改变组口径", before.groupOnCount, after.groupOnCount)
        assertEquals(before.pendingGroupCount, after.pendingGroupCount)
    }

    @Test
    fun `S5_默认开启的组不露面_露面的只有等点头的`() {
        val r = rows()
        assertTrue("默认态必须列出等点头的组", r.isNotEmpty())
        assertTrue(
            "默认开启（且一个没关）的组不许露面：${r.map { it.group }}",
            r.none { it.group in ToolGroup.DEFAULT_VISIBLE },
        )
        assertTrue("露面的每一行都应当是 Locked", r.all { it.state == BaopuGroupState.Locked })
        assertEquals("露面行数＝待同意组数", summarize().pendingGroupCount, r.size)
    }

    @Test
    fun `S6_关掉的组要露面_标已关闭_否则用户找不回来`() {
        val r = rows(disabledGroups = setOf(ToolGroup.SKILL))
        val row = r.firstOrNull { it.group == ToolGroup.SKILL }
        assertTrue("关掉的组必须露面（61 批：不许把关闭项藏到看不见）", row != null)
        assertEquals(BaopuGroupState.Disabled, row!!.state)
        assertEquals(BaopuToolRowTone.Closed, baopuToolRowTone(row.state))
    }

    @Test
    fun `S7_组开着但里面单独关了东西_也要露面并报个数`() {
        val offOne = ToolGroupTable.namesOf(ToolGroup.CORE).first()
        val r = rows(off = setOf(offOne))
        val row = r.firstOrNull { it.group == ToolGroup.CORE }
        assertTrue("核心组被单独关了一个工具，这一行必须露面", row != null)
        assertEquals(1, row!!.offCount)
        assertEquals(BaopuToolRowTone.Partial, baopuToolRowTone(row.state))
    }

    @Test
    fun `S8_露面顺序跟传进来的组序_不重排`() {
        val r = rows(disabledGroups = setOf(ToolGroup.LIBRARY, ToolGroup.CARD))
        val orderIn = groups.filter { g -> r.any { it.group == g } }
        assertEquals("露面行的顺序必须与传进来的组序一致", orderIn, r.map { it.group })
    }

    @Test
    fun `S9_数字与行列同源_工具数等于开着的组里没被关掉的成员数`() {
        // 数字说 12，行列却说 13 ——「一边说一边列」这类不一致只能靠同源保证。
        // 这里按定义重算一遍，两个入口必须给出同一个答案。
        val disabledGroups = setOf(ToolGroup.BROWSER)
        val off = setOf(ToolGroupTable.namesOf(ToolGroup.EDIT).first())
        val s = summarize(disabledGroups, off = off)
        val manual = groups
            .filter { it !in disabledGroups }
            .filter { g ->
                val st = stateOf(disabledGroups)(g)
                baopuGroupSwitchOn(st)
            }
            .sumOf { g -> ToolGroupTable.namesOf(g).count { it !in off } }
        assertEquals("摘要数字与开关状态必须同源", manual, s.toolCount)
    }

    @Test
    fun `S10_空表与空组_三个数为零且不崩`() {
        val s = BaopuToolArea.summarize(emptyList(), stateOf(), { emptyList() }) { false }
        assertEquals(0, s.toolCount)
        assertEquals(0, s.groupOnCount)
        assertEquals(0, s.pendingGroupCount)
        assertTrue("空表不许凭空造行", BaopuToolArea.rowsOf(emptyList(), stateOf(), { emptyList() }) { false }.isEmpty())
    }

    // ── 3. G1–G12：源码门禁 ─────────────────────────────────────────────────────

    @Test
    fun `G1_工具区是信息卡档位_不是白底描边卡`() {
        val src = page()
        assertFalse(
            "工具区不许再套 SectionCard（白底 0.5dp 发丝边）—— 与信息卡的左竖条叠起来就是两种边界位置",
            src.contains("SectionCard(stringResource(R.string.baopu_tools_card))"),
        )
        assertTrue("工具区必须用信息卡容器（纸深底 ＋ 左 4dp 黛青竖条）", src.contains("BaopuInfoContainer {"))
        // 卡外组标签仍在（与 SectionCard 的标题同款），标题键不许因为换容器而丢
        assertTrue("卡外组标签必须留着", src.contains("text = stringResource(R.string.baopu_tools_card)"))
    }

    @Test
    fun `G2_摘要行三个数字都传_少一个就渲染成原文`() {
        val src = page()
        // 134 批：baopu_tools_summary 改成 <plurals>（lint PluralsCandidate），引用形态随之是 R.plurals。
        val i = src.indexOf("R.plurals.baopu_tools_summary,")
        assertTrue("摘要行必须用 baopu_tools_summary 且带三个参数", i >= 0)
        val block = src.substring(i, minOf(src.length, i + 420))
        listOf("toolSummary.toolCount", "toolSummary.groupOnCount", "toolSummary.pendingGroupCount").forEach {
            assertTrue("摘要行漏了 $it（占位符渲染成 0 或直接崩）", block.contains(it))
        }
    }

    @Test
    fun `G3_成员名单不进UI_单工具开关不许回潮`() {
        val src = page()
        // 两个映射（baopuToolLabel / baopuToolDesc）作为 catalog 覆盖断言网留着，
        // 但**页面里不许再调用它们**——调用一次就等于把成员名单又画回了界面。
        assertEquals(
            "页面不许再调 baopuToolLabel(（成员名单不进 UI）",
            0,
            Regex("(?<!fun )\\bbaopuToolLabel\\(").findAll(src).count(),
        )
        assertEquals(
            "页面不许再调 baopuToolDesc(",
            0,
            Regex("(?<!fun )\\bbaopuToolDesc\\(").findAll(src).count(),
        )
        assertFalse("旧的成员行写入点不许回潮", src.contains("prefs.setDisabled(name, !want)"))
        assertFalse("旧的成员行遍历不许回潮", src.contains("members.forEach"))
    }

    @Test
    fun `G4_行内展开按钮已砍_组里只有组名与开关`() {
        val src = page()
        assertFalse(
            "「展开看具体工具」的按钮文案与用法都该消失",
            src.contains("baopu_tools_expand_members") || src.contains("R.string.baopu_tools_expand"),
        )
        // 组行里只允许出现：组标题、状态、Switch 三样
        val rowStart = src.indexOf("toolRows.forEach { row ->")
        assertTrue("必须逐行渲染露面组", rowStart >= 0)
        val block = src.substring(rowStart, minOf(src.length, rowStart + 2600))
        assertTrue("组行必须有组标题", block.contains("baopuGroupTitle(row.group)"))
        assertTrue("组行必须有组开关", block.contains("Switch("))
        assertFalse("组行里不许再出现成员开关", block.contains("prefs.setDisabled(name"))
    }

    @Test
    fun `G5_组开关三档写入点各一处`() {
        val src = page()
        assertEquals(
            "长期同意档写入点必须只有一处",
            1,
            Regex("prefs\\.setGroupApproved\\(row\\.group, want && row\\.group !in ToolGroup\\.DEFAULT_VISIBLE\\)")
                .findAll(src).count(),
        )
        assertEquals("禁用档写入点必须只有一处", 1, Regex("prefs\\.setGroupDisabled\\(row\\.group, !want\\)").findAll(src).count())
        assertEquals("关组撤会话解锁只许一个点", 1, Regex("relockAll\\(listOf\\(row\\.group\\)\\)").findAll(src).count())
        assertEquals(
            "打开组时清单项关闭只许一个点（成员名单不进 UI ⇒ 这是「再也开不回来」的唯一出路）",
            1,
            Regex("prefs\\.setDisabled\\(it, false\\)").findAll(src).count(),
        )
    }

    @Test
    fun `G6_热区_摘要行48组行64`() {
        val src = page()
        val i = src.indexOf("R.plurals.baopu_tools_summary,")
        val head = src.substring(maxOf(0, i - 700), i)
        assertTrue("摘要行热区必须 ≥48dp", head.contains("heightIn(min = 48.dp)"))
        val rowStart = src.indexOf("toolRows.forEach { row ->")
        val block = src.substring(rowStart, minOf(src.length, rowStart + 1200))
        assertTrue("组行热区必须 ≥48dp（141 设计约束第 3 条）", block.contains("heightIn(min = 64.dp)"))
    }

    @Test
    fun `G7_摘要行不设maxLines_200%字号下折行不裁字`() {
        val src = page()
        val i = src.indexOf("R.plurals.baopu_tools_summary,")
        val window = src.substring(maxOf(0, i - 400), minOf(src.length, i + 400))
        assertFalse(
            "摘要行不许 maxLines —— 系统字号 200% 时 14 字要能折两行，不许裁字（派工单验收第 4 条）",
            window.contains("maxLines"),
        )
        assertFalse("也不许 overflow = TextOverflow.Ellipsis（同样会裁字）", window.contains("TextOverflow"))
    }

    @Test
    fun `G8_琥珀只给待同意_其余次墨_不加图标`() {
        val src = page()
        val i = src.indexOf("val tone = baopuToolRowTone(row.state)")
        assertTrue("组行必须依据 tone 取色", i >= 0)
        // 窗口取到整段组行末尾（从 tone 到 Switch 之间约 1.9k 字符，缩进深）
        val block = src.substring(i, minOf(src.length, i + 3000))
        assertTrue("「待同意」用 tertiary（琥珀 #856018，纸深底 4.66:1）", block.contains("MaterialTheme.colorScheme.tertiary"))
        assertTrue("已关闭/部分关闭用次墨", block.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertFalse("派工单明令「不加图标」", block.contains("Icon("))
        // 纯函数的色相归一：Pending 只从 Locked 来，Closed 只从 Disabled 来
        assertEquals(BaopuToolRowTone.Pending, baopuToolRowTone(BaopuGroupState.Locked))
        assertEquals(BaopuToolRowTone.Closed, baopuToolRowTone(BaopuGroupState.Disabled))
        assertEquals(BaopuToolRowTone.Partial, baopuToolRowTone(BaopuGroupState.DefaultVisible))
        assertEquals(BaopuToolRowTone.Partial, baopuToolRowTone(BaopuGroupState.Approved))
    }

    @Test
    fun `G9_五个新文案键中英都在且都被页面引用`() {
        val src = page()
        listOf(
            "baopu_tools_summary",
            "baopu_tools_rows_empty",
            "baopu_tools_row_pending",
            "baopu_tools_row_closed",
            "baopu_tools_row_partial",
        ).forEach { key ->
            assertTrue("$key 缺中文", zh().contains("name=\"$key\""))
            assertTrue("$key 缺英文", en().contains("name=\"$key\""))
            assertTrue(
                "$key 没被页面引用（零调用的文案键是下一次「改哪份」的坑）",
                src.contains("R.string.$key") || src.contains("R.plurals.$key"),
            )
        }
        // 带占位符的两条：占位符写全，漏一个就渲染成原文（134 批起是 <plurals>，取整块）
        val summaryBlock = Regex("name=\"baopu_tools_summary\">([\\s\\S]*?)</plurals>").find(zh())!!.groupValues[1]
        assertTrue(
            "摘要行的三个数字占位符都要有",
            summaryBlock.contains("%1\$d") && summaryBlock.contains("%2\$d") && summaryBlock.contains("%3\$d"),
        )
        val partialBlock = Regex("name=\"baopu_tools_row_partial\">([\\s\\S]*?)</plurals>").find(zh())!!.groupValues[1]
        assertTrue("「N 个已关」要有个数占位符", partialBlock.contains("%1\$d"))
    }

    @Test
    fun `G10_页面能碰的prefs方法只有这几个`() {
        val src = page()
        val calls = Regex("prefs\\.(\\w+)\\(").findAll(src).map { it.groupValues[1] }.toSet()
        assertEquals(
            "设置页多出来的 prefs 口子都是新造的读写口子：$calls",
            setOf(
                "approvedGroups", "disabledGroups", "disabledNames",
                "setGroupApproved", "setGroupDisabled", "setDisabled",
            ),
            calls,
        )
        // 四态归一仍只有一处（不许在别处再算一遍组状态）
        assertEquals("组状态只许在一处归一（四态）", 1, Regex("baopuGroupState\\(").findAll(src).count())
        // 默认可见的读取点仍恰好一处
        assertEquals(1, Regex("group in ToolGroup\\.DEFAULT_VISIBLE").findAll(src).count())
    }

    @Test
    fun `G11_三段长说明与六句折叠文案不许回来`() {
        val src = page()
        listOf("baopu_tools_desc", "baopu_tools_progressive_note", "baopu_tools_fold_note").forEach { key ->
            assertFalse("$key 已随长列表退役，页面不许再引用", src.contains("R.string.$key"))
            assertFalse("$key 的字符串也该删掉（留着就是零引用资源）", zh().contains("name=\"$key\""))
        }
        // 折叠摘要那个私有函数与它吃的六句文案一并退役
        assertFalse("baopuFoldSummaryText 已删", src.contains("baopuFoldSummaryText"))
        assertEquals("折叠摘要判据本体已退役", 0, rules().split("object BaopuToolFoldSummary").size - 1)
    }

    @Test
    fun `G12_三个数字必须订阅toolTick_否则开关点了不刷新`() {
        val src = page()
        listOf("approvedGroups", "disabledGroupSet", "disabledNameSet").forEach { name ->
            assertTrue(
                "val $name 必须 remember(toolTick)（97 批真机：写盘成功但同屏不刷新）",
                src.contains("val $name = remember(toolTick)"),
            )
        }
        assertFalse(
            "不许退回无订阅的 remember（改了开关数字不动）",
            Regex("val (approvedGroups|disabledGroupSet|disabledNameSet) = remember \\{").containsMatchIn(src),
        )
    }
}
