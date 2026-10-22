// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据：派工单第八十一批 §2「每个抽出的纯函数：JVM 单测 ≥3 例（正常/边界/空值/冲突优先级）」。
// 这 21 例打的全是 `BaopuSettingsRules.kt` 里搬过去的判据本体（零 Android ⇒ 不需要 Robolectric）；
// 页面侧「只剩调用、不留第二份实现」由 BaopuSettingsSliceGateTest 用源码门禁钉。
//
// 141 批：原 ① 段（折叠态「关闭项摘要」F1–F5）随长列表一起退役——设置页不再铺开成员，
// 那六个用例钉的函数已删；工具区的新判据（摘要卡）在 ToolAreaCardTest，本文件只留
// 取组序（G）／组标题（T）／四态（C）／人设层（L）四组，以及一条「判据不碰 Android」的行为证据。
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

    // ── ② 取组序：哪些组上设置页、按什么序 ────────────────────────────────────

    @Test
    fun `G1_取组序摘掉COMPAT与动态组_其余保持枚举声明序`() {
        // 正常：喂真实的整张枚举表
        val got = baopuSwitchableGroups(ToolGroup.values())
        assertFalse("COMPAT 没有开关，不许出现在取组列表里", got.contains(ToolGroup.COMPAT))
        assertFalse("MCP 是动态组，v1 没有管理入口，不许画成空组", got.contains(ToolGroup.MCP))
        val expected = ToolGroup.values().filter { it != ToolGroup.COMPAT && !it.dynamic }
        assertEquals("除了 COMPAT 与动态组全都得上页", expected.size, got.size)
        assertEquals("顺序 = 枚举声明序（页面上的组上下次序就是这个序）", expected, got)
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
        // 判别器：摘要卡的判据吃的是调用方给的两个 lambda（状态查询 / 开关查询）——
        // 本文件全程没建过 Context、没开过 SharedPreferences。这里补一条行为证据：
        // 同一批组、同一份成员表，换个「谁被关掉」的读法，三个数字就跟着变
        // ⇒ 判据没有自己藏一份开关状态（141 批把 ① 段换成 [BaopuToolArea] 后同一条仍然成立）。
        val core = listOf("read_file", "list_dir", "search_in_files")
        val groups = listOf(ToolGroup.CORE, ToolGroup.EDIT)
        val members = { g: ToolGroup -> if (g == ToolGroup.CORE) core else listOf("write_file") }
        val stateOf = { g: ToolGroup ->
            baopuGroupState(g in ToolGroup.DEFAULT_VISIBLE, approved = false, disabled = false)
        }
        val allOn = BaopuToolArea.summarize(groups, stateOf, members) { false }
        val oneOff = BaopuToolArea.summarize(groups, stateOf, members) { it == "read_file" }
        assertEquals(core.size + 1, allOn.toolCount)
        assertEquals(allOn.toolCount - 1, oneOff.toolCount)
        // 组数与待同意数不受单工具开关影响（两档互不覆盖）
        assertEquals(allOn.groupOnCount, oneOff.groupOnCount)
        assertEquals(allOn.pendingGroupCount, oneOff.pendingGroupCount)
    }
}
