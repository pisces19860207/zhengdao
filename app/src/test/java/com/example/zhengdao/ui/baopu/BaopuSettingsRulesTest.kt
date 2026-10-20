// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据：派工单第八十一批 §2「每个抽出的纯函数：JVM 单测 ≥3 例（正常/边界/空值/冲突优先级）」。
// 这 21 例打的全是 `BaopuSettingsRules.kt` 里搬过去的判据本体（零 Android ⇒ 不需要 Robolectric）；
// 页面侧「只剩调用、不留第二份实现」由 BaopuSettingsSliceGateTest 用源码门禁钉。
//
// 与 61 批 SettingsGroupFoldTest P1–P6 的分工：那六例钉的是「折叠摘要在产品口径上对不对」
// （空组/全开/部分关/逐个关光/整组关/不串组），本文件 F1–F5 钉的是**搬走之后仍然成立的性质**：
// 表序不被重排、重复名单不走去重、组开关与空组的优先序、取组序不碰输入、
// 标题映射两两不同且 COMPAT 依然 fail-fast。两条网叠着才拦得住「抽取时顺手改了点东西」。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.agent.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuSettingsRulesTest {

    // ── ① 折叠态「关闭项摘要」：搬走的是同一段 when，先把优先序钉死 ──────────────

    private val core = listOf("read_file", "list_dir", "search_in_files", "file_info", "ask_user")

    private fun off(members: List<String>, groupDisabled: Boolean, disabled: Set<String>) =
        BaopuToolFoldSummary.of(members, groupDisabled) { it in disabled }

    @Test
    fun `F1_部分关闭_名单按传入的表序_计数与总数各说各的`() {
        // 正常：5 个成员关掉第 2 与第 4 个
        val s = off(core, false, setOf("list_dir", "file_info"))
        assertEquals(BaopuFoldKind.SomeOff, s.foldKind)
        assertEquals("名单必须就是传进来的那个顺序（不排序、不反着来）",
            listOf("list_dir", "file_info"), s.offNames)
        assertEquals(2, s.offNames.size)
        assertEquals("total 是成员数，不是关闭数", 5, s.total)
    }

    @Test
    fun `F2_单成员组_关掉就是全关`() {
        // 边界：一个成员的组，关掉它 ⇒ SomeOff 与 AllOff 只差在说法，这里必须是 AllOff（61 批 P4 的同一口径）
        val s = off(listOf("image_ops"), false, setOf("image_ops"))
        assertEquals(BaopuFoldKind.AllOff, s.foldKind)
        assertEquals(listOf("image_ops"), s.offNames)
        assertEquals(1, s.total)
        // 同一个成员没被关 ⇒ 全开，offNames 空
        val on = off(listOf("image_ops"), false, emptySet())
        assertEquals(BaopuFoldKind.AllOn, on.foldKind)
        assertTrue(on.offNames.isEmpty())
    }

    @Test
    fun `F3_空组优先于组开关_不凭空报关闭项`() {
        // 空值：空组即便组开关是关的，也说「这一组没有工具」，不许写成「0 项已全部关闭」
        val s = off(emptyList(), true, emptySet())
        assertEquals(BaopuFoldKind.EmptyGroup, s.foldKind)
        assertEquals(0, s.total)
        assertTrue(s.offNames.isEmpty())
        // 空名单 + 关闭集合非空：还是空组（关闭集合里那些名字与本组无关）
        assertEquals(BaopuFoldKind.EmptyGroup, off(emptyList(), false, setOf("read_file")).foldKind)
    }

    @Test
    fun `F4_组开关赢_整组关光时报全部成员`() {
        // 冲突优先级：组开关优先于单工具开关（引擎侧 isVisibleTool 同一口径）。
        // 用户一个单工具都没关，组开关一关 ⇒ 关闭项 = 全部成员，而不是「全部开启」。
        val s = off(core, true, emptySet())
        assertEquals(BaopuFoldKind.AllOff, s.foldKind)
        assertEquals(core, s.offNames)
        assertEquals(core.size, s.total)
        // 组开关开着、逐个关到零：同样 AllOff，但名单是逐个关的那几个（这里恰好也等于全部）
        val byItem = off(core, false, core.toSet())
        assertEquals(BaopuFoldKind.AllOff, byItem.foldKind)
        assertEquals(core, byItem.offNames)
    }

    @Test
    fun `F5_名单里出现重复名字时不走去重_照搬原filter的脾气`() {
        // 这是一条** quirks 照搬 **用例：判据本体用的是 `members.filter { }`，它不去重、不排序。
        // 分组表本来不会给重名（ToolGroupTable 每个名字只落一个组），所以这条不改变任何现实行为，
        // 它钉的是「抽取时没有人顺手加了 distinct()」——真要改这个脾气，属于另立一单。
        val dup = listOf("read_file", "list_dir", "read_file")
        val s = off(dup, false, setOf("read_file"))
        assertEquals(BaopuFoldKind.SomeOff, s.foldKind)
        assertEquals(listOf("read_file", "read_file"), s.offNames)
        assertEquals(3, s.total)
    }

    // ── ② 取组序：哪些组上设置页、按什么序 ────────────────────────────────────

    @Test
    fun `G1_取组序只摘掉COMPAT_其余保持枚举声明序`() {
        // 正常：喂真实的整张枚举表
        val got = baopuSwitchableGroups(ToolGroup.values())
        assertFalse("COMPAT 没有开关，不许出现在取组列表里", got.contains(ToolGroup.COMPAT))
        assertEquals("除了 COMPAT 全都得上页", ToolGroup.values().size - 1, got.size)
        assertEquals("顺序 = 枚举声明序（页面上的组上下次序就是这个序）",
            ToolGroup.values().filter { it != ToolGroup.COMPAT }, got)
    }

    @Test
    fun `G2_COMPAT在中间也照样摘掉_前后顺序不动`() {
        // 边界：手搓一张把 COMPAT 夹在中间的表，验证「只摘它、不重排」
        val hand = arrayOf(ToolGroup.CORE, ToolGroup.COMPAT, ToolGroup.EDIT, ToolGroup.CARD)
        assertEquals(listOf(ToolGroup.CORE, ToolGroup.EDIT, ToolGroup.CARD), baopuSwitchableGroups(hand))
        // 空值：空表进空表出
        assertTrue(baopuSwitchableGroups(emptyArray()).isEmpty())
        // 全 COMPAT
        assertEquals(emptyList<ToolGroup>(), baopuSwitchableGroups(arrayOf(ToolGroup.COMPAT)))
    }

    @Test
    fun `G3_取组序不改输入_重复调用同一结果`() {
        // 纯函数性质：不碰入参那个数组（页面每帧都会现取一次，动了输入就是副作用）
        val hand = arrayOf(ToolGroup.CORE, ToolGroup.COMPAT, ToolGroup.EDIT)
        val snapshot = hand.toList()
        val first = baopuSwitchableGroups(hand)
        val second = baopuSwitchableGroups(hand)
        assertEquals(snapshot, hand.toList())
        assertEquals(first, second)
    }

    // ── ③ 组标题：每个能上页的组都得有自己的标题 ──────────────────────────────

    @Test
    fun `T1_每个可开关的组都有标题_且两两不同`() {
        // 正常＋边界：两个组共用一句标题＝界面上会出现两个同名分组（第五十九批删掉第二张组表之后，
        // 这张映射是标题的唯一来源，所以这里钉「互不相同」而不是只钉「非零」）。
        val groups = baopuSwitchableGroups(ToolGroup.values())
        val titles = groups.map { baopuGroupTitle(it) }
        groups.forEachIndexed { i, g ->
            assertNotEquals(0, titles[i])
            assertTrue("$g 的标题不许与别的组重复", titles.filter { it == titles[i] }.size == 1)
        }
        assertEquals(groups.size, titles.size)
    }

    @Test
    fun `T2_COMPAT照旧fail-fast`() {
        // 空值/冲突：COMPAT 永远不该走到分组标题（它不在取组列表里，②已经摘掉了）。
        // 一旦有人把它放回列表，这里直接抛，而不是界面上冒出一句「未知分组」。
        val e = runCatching { baopuGroupTitle(ToolGroup.COMPAT) }.exceptionOrNull()
        assertTrue("必须是 IllegalArgumentException：$e", e is IllegalArgumentException)
        assertTrue("话术要指名 COMPAT：${e?.message}", e!!.message!!.contains("COMPAT"))
    }

    @Test
    fun `T3_标题映射是纯的_两次调用同值`() {
        ToolGroup.values().filter { it != ToolGroup.COMPAT }.forEach { g ->
            assertEquals(baopuGroupTitle(g), baopuGroupTitle(g))
        }
    }

    // ── ④ 组头第二句：四态（第九十七批把「默认开/不开」扩成「默认可见/已锁/已同意/已关闭」）──

    @Test
    fun `C1_四态各有一句_四句互不相同`() {
        val keys = BaopuGroupState.values().map { baopuGroupCaptionKey(it) }
        assertEquals("四态四句（两句一样就等于两态没拆开）", keys.size, keys.toSet().size)
        assertEquals(R.string.baopu_tools_group_default_on, baopuGroupCaptionKey(BaopuGroupState.DefaultVisible))
        assertEquals(R.string.baopu_tools_group_default_off, baopuGroupCaptionKey(BaopuGroupState.Locked))
        assertEquals(R.string.baopu_tools_group_approved, baopuGroupCaptionKey(BaopuGroupState.Approved))
        assertEquals(R.string.baopu_tools_group_disabled, baopuGroupCaptionKey(BaopuGroupState.Disabled))
    }

    @Test
    fun `C2_每组那句必须与DEFAULT_VISIBLE现算的一致`() {
        // 冲突优先级：默认态只跟着那张唯一的默认可见名单说（59 批 §1.6）。
        baopuSwitchableGroups(ToolGroup.values()).forEach { g ->
            val isDefault = g in ToolGroup.DEFAULT_VISIBLE
            val state = baopuGroupState(defaultVisible = isDefault, approved = false, disabled = false)
            val expected = if (isDefault) {
                R.string.baopu_tools_group_default_on
            } else {
                R.string.baopu_tools_group_default_off
            }
            assertEquals("$g 的默认提示", expected, baopuGroupCaptionKey(state))
        }
    }

    @Test
    fun `C3_默认可见名单现实读数_只有core与edit上页就亮`() {
        val onPage = baopuSwitchableGroups(ToolGroup.values())
            .filter { baopuGroupSwitchOn(baopuGroupState(it in ToolGroup.DEFAULT_VISIBLE, false, false)) }
        assertEquals(listOf(ToolGroup.CORE, ToolGroup.EDIT), onPage)
    }

    @Test
    fun `C4_四态优先级_关掉压过默认可见_同意只在非默认组上说得通`() {
        assertEquals(BaopuGroupState.Disabled, baopuGroupState(defaultVisible = true, approved = true, disabled = true))
        assertEquals(BaopuGroupState.DefaultVisible, baopuGroupState(true, true, false))
        assertEquals(BaopuGroupState.Approved, baopuGroupState(false, true, false))
        assertEquals(BaopuGroupState.Locked, baopuGroupState(false, false, false))
        // 开关：只有「默认可见」与「已长期同意」是开；已锁/已关闭都是关
        assertTrue(baopuGroupSwitchOn(BaopuGroupState.DefaultVisible))
        assertTrue(baopuGroupSwitchOn(BaopuGroupState.Approved))
        assertFalse(baopuGroupSwitchOn(BaopuGroupState.Locked))
        assertFalse(baopuGroupSwitchOn(BaopuGroupState.Disabled))
    }

    // ── ⑤ 作用域 → 人设层 ────────────────────────────────────────────────────

    @Test
    fun `L1_全局进B层项目进C层`() {
        // 正常
        assertEquals(PersonaLayer.B_GLOBAL, baopuPersonaLayerOf(SkillScope.GLOBAL))
        assertEquals(PersonaLayer.C_PROJECT, baopuPersonaLayerOf(SkillScope.PROJECT))
    }

    @Test
    fun `L2_两档互不相同且都碰不到A内核`() {
        // 边界＋冲突优先级：A 层（内核）是只读的（37 批判据：A 永远写不进去也永远没有备份），
        // 技能的恢复/删索引一律不许落到它上面。
        assertNotEquals(baopuPersonaLayerOf(SkillScope.GLOBAL), baopuPersonaLayerOf(SkillScope.PROJECT))
        SkillScope.values().forEach { scope ->
            assertNotEquals("$scope 不许映射到内核层", PersonaLayer.A_KERNEL, baopuPersonaLayerOf(scope))
        }
    }

    @Test
    fun `L3_枚举全覆盖_新增作用域时when会编译不过`() {
        // 空值/穷尽：这里把 `values()` 全打一遍，映射必须是满的（少一档时上面的 when 直接编译报错，
        // 这条用例负责的是「每一档都有落点，且落点稳定」）。
        val got = SkillScope.values().associateWith { baopuPersonaLayerOf(it) }
        assertEquals(SkillScope.values().size, got.size)
        assertEquals(setOf(PersonaLayer.B_GLOBAL, PersonaLayer.C_PROJECT), got.values.toSet())
    }

    // ── 附：搬过来的五段判据在 JVM 上跑，说明它们确实不碰 Android ──────────────

    @Test
    fun `Z1_判据本体不调Compose也不读prefs`() {
        // 判别器：折叠摘要吃的是调用方给的 lambda——本文件全程没建过 Context、没开过 SharedPreferences，
        // 页面里那句 `prefs.isDisabled(it)` 仍在页内（源码门禁钉）。这里补一条行为证据：
        // 同一个 members 换一种「谁被关掉」的读法，摘要就跟着变 ⇒ 判据没有自己藏一份开关状态。
        val a = BaopuToolFoldSummary.of(core, false) { it == "read_file" }
        val b = BaopuToolFoldSummary.of(core, false) { it == "list_dir" }
        assertEquals(listOf("read_file"), a.offNames)
        assertEquals(listOf("list_dir"), b.offNames)
        // 全 false（等于一个都没关）与全 true（等于逐个关光）是两头的边界
        assertEquals(BaopuFoldKind.AllOn, BaopuToolFoldSummary.of(core, false) { false }.foldKind)
        assertEquals(BaopuFoldKind.AllOff, BaopuToolFoldSummary.of(core, false) { true }.foldKind)
    }
}
