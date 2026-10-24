// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据：派工单第八十一批 §1（BaopuSettingsScreen 纯逻辑抽取，§B 80 延伸）。
//
// ── §1 方法清单（先出清单，再动手；行号与行数 = 抽取前的 BaopuSettingsScreen.kt，1,101 行）──
//
// 方法/块（>80 行的一律列出）              行号        行数  依赖                     可抽性
// ----------------------------------------------------------------------------------
// BaopuSettingsScreen 页面本体             124-963      840  Compose+prefs+ctx+DB+写盘  §1 明令「不拆 Composable」⇒ 不动
//  └ 「抱朴工具」SectionCard               145-313      169  Compose+prefs+分组表       内嵌 4 条纯判据 ⇒ 本文件 ①②③④
//  └ 「模型参数」SectionCard               319-333       15  Compose+ModelParams(纯)    不足 80 行；判据本体在 baopu/ModelParams.kt（不是本文件 ⇒ 红线外）
//  └ 「思考深度」SectionCard               341-376       36  Compose+ctx(saveEffort)    不足 80 行；档位映射 effortLabel 被 18 批 J1 钉成本页 private fun ⇒ 不动
//  └ 「人设回滚」SectionCard               382-437       56  Compose+ctx+PersonaBackup  不足 80 行；enabled/displayStamp/restore 三点被 37 批门禁钉在本页 ⇒ 不动
//  └ 「技能与索引审阅」SectionCard          443-959      517  Compose+ctx+DB+pf 写盘      §1 禁拆；页内 12 处调用形状被 32/34/36/57/68 批门禁钉死 ⇒ 只抽得出 ⑤
//     └ recheckTables = remember{...}      468-493       26  BaopuRuntime.db（读盘）     非纯 ⇒ 不动
//     └ fun skillFileOf                    546-556       11  ctx.filesDir/工作区根       Context 不许进 helper ⇒ 不动
//     └ fun restoreLast                    559-568       10  ctx+Toast+SkillStore（写盘） 非纯 ⇒ 不动
//     └ fun removeIndex                    570-586       17  ctx+pf.applyProposal（写盘） 非纯 ⇒ 不动
//     └ fun layerOf                        589-592        4  纯（枚举 → 枚举）           ⇒ 本文件 ⑤
//     └ fun sayArchived                    601-614       14  ctx.getString+7 个 R 键     七键被 34 批 U5 钉在本页 ⇒ 不动
// baopuToolAreaSummaryText @Composable      ——           ——   stringResource+3 个 R 键    141 批新增；判据在 ①（摘要卡）
// baopuGroupTitle private fun              1000-1011     12  纯（只有 R 引用）           ⇒ 本文件 ③
// baopuToolLabel private fun               1021-1055     35  纯（只有 R 引用）           J1 钉 private fun ＋ J8 数本页「"名" ->」两次 ⇒ 不动
// effortLabel private fun                  1061-1067      7  纯（只有 R 引用）           J1 钉 ⇒ 不动
// baopuToolDesc private fun                1070-1101     32  纯（只有 R 引用）           J1 ＋ J8 钉 ⇒ 不动
//
// 另有两处内嵌纯判据（不成方法，但同样只算值）：
//   组渲染序 ToolGroup.values().filter { it != ToolGroup.COMPAT }  195     ⇒ 本文件 ②
//   默认可见提示 if (group in ToolGroup.DEFAULT_VISIBLE) …        220-225 ⇒ 本文件 ④（那一次读取仍留页内：61 批 G5 钉它必须恰好一处）
//
// 盘点依据：15 个测试套按文本读 BaopuSettingsScreen.kt（BaopuSettingsGuardTest J1/J8、
// ToolAreaCardTest G1-G12（141 批接手原 SettingsGroupFoldTest G1-G7 的位置）、
// PersonaBackupTest、SkillStoreTest S14、BaopuSkillCategoryTest
// T5、BaopuSkillInspectUiTest U3/U5/红线、BaopuSkillTrialUiTest T3/T4/红线、
// ToolRegistrySwitchTest、ProgressiveToolsTest G3、KernelGuardTest、BaopuSessionTreeTest 口径 4、
// DeletePathToolTest G3、ImageOpsTest G4），它们把标签表/副标题表/档位表/写口形状钉在本页，
// 且 G5 把 prefs 的读写点限定为四个方法各恰好一处 ⇒ 凡「搬走就少一条字面量」的都不在本批动，
// 只搬「搬走之后页面里剩下的正好是一次调用」的那五条。
//
// 这个文件只回答「该显示什么 / 该排谁」，不回答「长什么样」：Text 的字号、颜色、间距、
// 开关本体、prefs 的两个写入点全部留在页面里。入参与出参都是普通值（枚举 / List / R 键整型），
// 不碰 Compose state、不碰 SharedPreferences、不碰 Context、不读写盘 ⇒ 能在 JVM 上直测
// （app/src/test/.../ui/baopu/BaopuSettingsRulesTest.kt）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.agent.ToolGroup

// ── ① 工具区摘要卡（第一百四十一批 §8；原「折叠态关闭项摘要」随长列表一起退役）────
//
// §8 把「抱朴工具」从一张长列表（每组一行开关 ＋ 行内「展开看具体工具」＋ 三段落说明）
// 改成**一张摘要卡**：收起时只有三个数字，点开只列「要你处理」的组。
// 判据全在这里（零 Android、JVM 直测），页面只负责画。
//
// 为什么原 ① 段可以退役：它服务的是「组头收起后仍要看得到关了什么」，而新形态
// 根本不再把成员铺开——成员的可见性由摘要数字与「N 个已关」承担（见 [BaopuToolArea.rowsOf]）。

/** 摘要卡上的三个数字（§8 那句「工具 14 · 11 组开 · 2 组待同意」）。 */
internal data class BaopuToolAreaSummary(
    /** 此刻**真的发给模型**的工具数：默认可见／已同意的组里没被单独关掉的成员。 */
    val toolCount: Int,
    /** 此刻开着的组数（默认可见 ＋ 你已长期同意）。 */
    val groupOnCount: Int,
    /** 等着你点头的组数（已锁：不发，抱朴要用时会弹卡申请）。 */
    val pendingGroupCount: Int,
)

/** 摘要卡展开后**露面**的一行。 */
internal data class BaopuToolAreaRow(
    val group: ToolGroup,
    val state: BaopuGroupState,
    /** 组内被单独关掉的成员数（0 ＝ 一个没关）。 */
    val offCount: Int,
)

/** 一行在界面上算哪种口径（琥珀＝等你点头；次墨＝你自己关的）。 */
internal enum class BaopuToolRowTone { Pending, Closed, Partial }

/**
 * 工具区摘要卡的判据（§8）。
 *
 * 三条口径：
 * 1. **数字与开关同源**：只吃调用方从 `ToolPrefs` 读出来的那份快照，自己不存任何状态
 *    ⇒ 开关一改（`toolTick` 重组）三个数字跟着变，不可能一边说 12 个一边列 13 行；
 * 2. **露面只列要你处理的组**：已锁（等你点头）与已关闭（你关的）必露面；默认开启／
 *    已长期同意的组**不露面**——但组里有成员被你单独关掉时仍要露面（第六十一批
 *    「不许把关闭项藏到看不见」不因换形态而作废），标「N 个已关」；
 * 3. 成员名单只从调用方传进来的 `membersOf` 取（页面给的是 `ToolGroupTable.namesOf`），
 *    这里不许出现第二份名单。
 *
 * 为什么 `toolCount` 只算「开着且成员没被关」的数量：这是「模型此刻看得见几个工具」。
 * 已锁的组模型看不见（`isVisibleTool` 同一口径），所以不计入——否则摘要会替一个
 * 模型根本拿不到的工具数作证。
 */
internal object BaopuToolArea {

    fun summarize(
        groups: List<ToolGroup>,
        stateOf: (ToolGroup) -> BaopuGroupState,
        membersOf: (ToolGroup) -> List<String>,
        isDisabled: (String) -> Boolean,
    ): BaopuToolAreaSummary {
        var tools = 0
        var on = 0
        var pending = 0
        groups.forEach { group ->
            val state = stateOf(group)
            if (baopuGroupSwitchOn(state)) {
                on++
                tools += membersOf(group).count { !isDisabled(it) }
            }
            if (state == BaopuGroupState.Locked) pending++
        }
        return BaopuToolAreaSummary(tools, on, pending)
    }

    /**
     * 展开后要画哪几行（顺序＝传进来的组序，页面给的是 `baopuSwitchableGroups`）。
     *
     * 三种露面理由，其余一律不露面（§8「默认开启的组不露面」）：
     * - [BaopuGroupState.Locked]：等用户点头；
     * - [BaopuGroupState.Disabled]：用户自己关的，得能找回来；
     * - 组开着但有成员被单独关掉：组里有成员被单独关掉，标「N 个已关」——
     *   去掉这一条就等于把第六十一批那条红线作废（用户关过的东西不许查不到）。
     */
    fun rowsOf(
        groups: List<ToolGroup>,
        stateOf: (ToolGroup) -> BaopuGroupState,
        membersOf: (ToolGroup) -> List<String>,
        isDisabled: (String) -> Boolean,
    ): List<BaopuToolAreaRow> = groups.mapNotNull { group ->
        val state = stateOf(group)
        val off = membersOf(group).count { isDisabled(it) }
        when {
            state == BaopuGroupState.Locked -> BaopuToolAreaRow(group, state, off)
            state == BaopuGroupState.Disabled -> BaopuToolAreaRow(group, state, off)
            off > 0 -> BaopuToolAreaRow(group, state, off)
            else -> null
        }
    }
}

/**
 * 一行用什么色相（纯函数；琥珀**只**给「等你点头」，其余次墨，不加图标）。
 *
 * [BaopuToolRowTone.Partial] 只给「组开着、但里面有成员被单独关掉」的行；这种行由
 * [BaopuToolArea.rowsOf] 的 `offCount > 0` 筛出来，一个没关的默认组根本不成行。
 */
internal fun baopuToolRowTone(state: BaopuGroupState): BaopuToolRowTone = when (state) {
    BaopuGroupState.Locked -> BaopuToolRowTone.Pending
    BaopuGroupState.Disabled -> BaopuToolRowTone.Closed
    BaopuGroupState.DefaultVisible, BaopuGroupState.Approved -> BaopuToolRowTone.Partial
}

// ── ② 设置页要渲染哪几个组、按什么序（照搬页面 195 行那一句 filter）────────────

/**
 * 设置页取组的顺序（第八十一批 ②）。
 *
 * COMPAT（read / bash）由通道决定、压根没有开关 ⇒ 不进取组列表（第十八批 §2.1-4，
 * 卡片尾部那两行只读说明是它们的唯一展示位）；动态组（MCP）v1 没有「添加 server」
 * 入口、成员为空 ⇒ 也不上页（画出来就是一个永远空着的组；第一百二十三批）。其余组
 * 保持 `values()` 的枚举声明序，页面按这个序画组头、`gi > 0` 才补间距——所以这个
 * 函数改动顺序就是界面上组的顺序。
 *
 * 输入是调用方给的整张枚举表（不自己 `values()`），为的是能在 JVM 上喂各种形态；
 * 这里**不列成员**：组里有哪些工具仍由页面从那张唯一的分组表读（`ToolGroupTable.namesOf`）。
 */
internal fun baopuSwitchableGroups(all: Array<ToolGroup>): List<ToolGroup> =
    all.filter { it != ToolGroup.COMPAT && !it.dynamic }

// ── ③ 组标题（第十八批 A 段搬入、第五十九批 §1.1 定形的 private fun，整段照搬）────

/**
 * 设置页分组标题（第五十九批 §1.1）。
 *
 * 只有「组 → 标题字符串」这一小段映射留在 UI 层，**成员表不在这**——
 * 组里有哪些工具由 `ToolGroupTable.namesOf` 出（那张唯一的分组表）。
 * 原来这里是一份 `BaopuToolGroups.GROUPS`（组名＋成员各写一遍），它就是派工单
 * 禁止的第二张组表：改了工具忘了改它，界面会显示一个不存在的分组。
 *
 * 新增组时会直接抛 IllegalArgumentException（fail-fast），与 baopuToolLabel 同一口径。
 */
internal fun baopuGroupTitle(group: ToolGroup): Int = when (group) {
    ToolGroup.CORE -> R.string.baopu_tools_group_core
    ToolGroup.EDIT -> R.string.baopu_tools_group_edit
    ToolGroup.BROWSER -> R.string.baopu_tools_group_browser
    ToolGroup.FILES -> R.string.baopu_tools_group_files
    ToolGroup.LIBRARY -> R.string.baopu_tools_group_library
    // 第一百五十八批：文生图（默认不可见，capabilities 解锁）
    ToolGroup.IMAGE -> R.string.baopu_tools_group_image
    // 第一百八十六批：画板/图表/表格（默认不可见，capabilities 解锁）
    ToolGroup.DRAW -> R.string.baopu_tools_group_draw
    ToolGroup.CARD -> R.string.baopu_tools_group_card
    ToolGroup.SKILL -> R.string.baopu_tools_group_skill
    ToolGroup.PREVIEW -> R.string.baopu_tools_group_preview
    // 第一百二十三批：外部 MCP 工具组（成员由连接成功后动态注册，默认不可见）
    ToolGroup.MCP -> R.string.baopu_tools_group_mcp
    ToolGroup.AGENT -> R.string.baopu_tools_group_agent
    // COMPAT 由通道决定、没有开关，卡片尾部以只读文字展示（第十八批 §2.1-4）⇒ 不进分组列表
    ToolGroup.COMPAT -> compatNoSwitch()
}

// ── ③b 组用途一句话（第一百零四批：授权卡副标题；与 ToolGroup.oneLine 对位）──

/**
 * COMPAT（read / bash）没有开关：标题与用途两张映射都对它抛——**抛的理由只写一遍**。
 *
 * 81 批「每条判据只算一次」：同一个 fail-fast 不许在两个映射里各抄一份
 * （抄两份就会有「改了标题忘了用途」的那天）。两个映射表共用这一处。
 */
private fun compatNoSwitch(): Nothing =
    throw IllegalArgumentException("COMPAT 组没有开关，不该走到这里")

/**
 * 组用途一句话（第一百零四批）。
 *
 * 授权卡副标题要「一句话用途」（需求 §P0）。引擎侧 [com.example.zhengdao.baopu.agent.ToolGroup.oneLine]
 * 是中文直写（引擎层不引 Android，不本地化）⇒ UI 层按同一口径给一份本地化映射，
 * **只作展示**：组成员仍只有 `ToolGroupTable` 一张表，这里不列工具。
 */
internal fun baopuGroupLine(group: ToolGroup): Int = when (group) {
    ToolGroup.CORE -> R.string.baopu_group_line_core
    ToolGroup.EDIT -> R.string.baopu_group_line_edit
    ToolGroup.BROWSER -> R.string.baopu_group_line_browser
    ToolGroup.FILES -> R.string.baopu_group_line_files
    ToolGroup.LIBRARY -> R.string.baopu_group_line_library
    ToolGroup.IMAGE -> R.string.baopu_group_line_image
    ToolGroup.DRAW -> R.string.baopu_group_line_draw
    ToolGroup.CARD -> R.string.baopu_group_line_card
    ToolGroup.SKILL -> R.string.baopu_group_line_skill
    ToolGroup.PREVIEW -> R.string.baopu_group_line_preview
    ToolGroup.MCP -> R.string.baopu_group_line_mcp
    ToolGroup.AGENT -> R.string.baopu_group_line_agent
    ToolGroup.COMPAT -> compatNoSwitch()
}

// ── ④ 组头第二行：四态说明（第九十七批把「默认开/不开」扩成「默认可见/已锁/已同意/已关闭」）──

/**
 * 一个组在设置页上的四态（第九十七批 §1/§3.5）。
 *
 * 为什么从两态扩成四态：59 批只有「默认可见 / 默认不发」两种说法，用户看到的就是
 * 「工具全都打开的」——因为「已锁」与「你已开」在界面上长得一样。现在分开：
 * - [DefaultVisible]：CORE/EDIT，一直发给模型；
 * - [Locked]：高组默认态——不发给模型，抱朴要用时会弹卡申请；
 * - [Approved]：你（或卡片上的「一直」）已经同意长期开启；
 * - [Disabled]：你手动关掉了，抱朴自己也解不开（连卡都不弹）。
 *
 * 优先级固定：disabled 压过 default-visible（用户关的就是关了），approved 只在
 * 非默认组上有意义。**只描述显示，不算可见性**——可见性仍只有 `isVisibleTool` 一处。
 */
internal enum class BaopuGroupState { DefaultVisible, Locked, Approved, Disabled }

/** 四态归一（纯函数；输入就是页面读到的三个布尔）。 */
internal fun baopuGroupState(defaultVisible: Boolean, approved: Boolean, disabled: Boolean): BaopuGroupState =
    when {
        disabled -> BaopuGroupState.Disabled
        defaultVisible -> BaopuGroupState.DefaultVisible
        approved -> BaopuGroupState.Approved
        else -> BaopuGroupState.Locked
    }

/** 四态 → 组头第二行那句说明。 */
internal fun baopuGroupCaptionKey(state: BaopuGroupState): Int = when (state) {
    BaopuGroupState.DefaultVisible -> R.string.baopu_tools_group_default_on
    BaopuGroupState.Locked -> R.string.baopu_tools_group_default_off
    BaopuGroupState.Approved -> R.string.baopu_tools_group_approved
    BaopuGroupState.Disabled -> R.string.baopu_tools_group_disabled
}

/** 组开关此刻该不该是「开」——只有「默认可见」与「已长期同意」两个状态算开。 */
internal fun baopuGroupSwitchOn(state: BaopuGroupState): Boolean =
    state == BaopuGroupState.DefaultVisible || state == BaopuGroupState.Approved

// ── ⑤ 技能作用域 → 人设层（照搬页面 589-592 行那个局部 fun layerOf）─────────────

/**
 * 作用域 → 人设层（A/B 段共用，与页面里 `skillFileOf` 同一口径）。
 *
 * 全局技能住 B 层、项目技能住 C 层：删索引、恢复到上一版、详情里的回滚按钮都按这一次
 * 映射去找文件，UI 不许在这里另立一套对应关系。
 */
internal fun baopuPersonaLayerOf(scope: SkillScope): PersonaLayer = when (scope) {
    SkillScope.GLOBAL -> PersonaLayer.B_GLOBAL
    SkillScope.PROJECT -> PersonaLayer.C_PROJECT
}
