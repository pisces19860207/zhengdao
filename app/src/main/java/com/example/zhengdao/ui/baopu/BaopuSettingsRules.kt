// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
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
// baopuFoldSummaryText @Composable          972-988      17  stringResource+6 个 R 键    六键被 61 批 G6 钉在本页 ⇒ 不动（它吃的名单判据 = ①）
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
// SettingsGroupFoldTest G1-G7、PersonaBackupTest、SkillStoreTest S14、BaopuSkillCategoryTest
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

// ── ① 折叠态「关闭项摘要」的生成（第六十一批 §1，整段照搬）────────────────────

/** 折叠态摘要的那四种局面（第六十一批 §1；只描述「关掉了几个」，不参与任何开关判定）。 */
internal enum class BaopuFoldKind { EmptyGroup, AllOn, SomeOff, AllOff }

/**
 * 一个组在折叠态要显示的那一行摘要的数据（[offNames] 保持分组表的表序）。
 *
 * [total] 是该组成员数：整组关光时「关闭项」就是全部成员，摘要只有名字没有总数
 * 会让人误估规模，所以这一行按 [total] 说话。
 */
internal data class BaopuFoldSummary(
    val foldKind: BaopuFoldKind,
    val offNames: List<String>,
    val total: Int,
)

/**
 * 折叠态「关闭项摘要」的生成（第六十一批 §1 的纯函数，零 Android）。
 *
 * 为什么需要它：组头一收起，组里那些**被用户关掉的**工具就跟着不见了——那等于把
 * 「这个工具你关过」这件事藏起来，比不折叠更糟（§1 原话「不许把关闭项藏到看不见」）。
 *
 * 三条口径：
 * 1. 纯展示：输入只有「成员名单 / 组开关状态 / 单个开关状态」，**不写任何东西**，
 *    也不改任何默认值——开关语义仍是 `prefs.isDisabled(n)` 那一处；
 * 2. 名单由调用方从那张唯一的分组表（`ToolGroupTable.namesOf`）传入，这里不许出现
 *    第二份工具名单；
 * 3. [groupDisabled] 优先：组开关关掉 ⇒ 组内一律不可见（引擎侧 `isVisibleTool` 同一口径），
 *    所以关闭项 = 全部成员，而不是"逐个关掉的那几个"。
 */
internal object BaopuToolFoldSummary {

    fun of(
        members: List<String>,
        groupDisabled: Boolean,
        isDisabled: (String) -> Boolean,
    ): BaopuFoldSummary = when {
        members.isEmpty() -> BaopuFoldSummary(BaopuFoldKind.EmptyGroup, emptyList(), 0)
        groupDisabled -> BaopuFoldSummary(BaopuFoldKind.AllOff, members, members.size)
        else -> {
            val off = members.filter { isDisabled(it) }
            val foldKind = when {
                off.isEmpty() -> BaopuFoldKind.AllOn
                off.size == members.size -> BaopuFoldKind.AllOff
                else -> BaopuFoldKind.SomeOff
            }
            BaopuFoldSummary(foldKind, off, members.size)
        }
    }
}

// ── ② 设置页要渲染哪几个组、按什么序（照搬页面 195 行那一句 filter）────────────

/**
 * 设置页取组的顺序（第八十一批 ②）。
 *
 * COMPAT（read / bash）由通道决定、压根没有开关 ⇒ 不进取组列表（第十八批 §2.1-4，
 * 卡片尾部那两行只读说明是它们的唯一展示位）；其余组保持 `values()` 的枚举声明序，
 * 页面按这个序画组头、`gi > 0` 才补间距——所以这个函数改动顺序就是界面上组的顺序。
 *
 * 输入是调用方给的整张枚举表（不自己 `values()`），为的是能在 JVM 上喂各种形态；
 * 这里**不列成员**：组里有哪些工具仍由页面从那张唯一的分组表读（`ToolGroupTable.namesOf`）。
 */
internal fun baopuSwitchableGroups(all: Array<ToolGroup>): List<ToolGroup> =
    all.filter { it != ToolGroup.COMPAT }

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
    ToolGroup.CARD -> R.string.baopu_tools_group_card
    ToolGroup.SKILL -> R.string.baopu_tools_group_skill
    ToolGroup.PREVIEW -> R.string.baopu_tools_group_preview
    // COMPAT 由通道决定、没有开关，卡片尾部以只读文字展示（第十八批 §2.1-4）⇒ 不进分组列表
    ToolGroup.COMPAT -> throw IllegalArgumentException("COMPAT 组没有开关，不该走到分组标题")
}

// ── ④ 组头第二行「默认是否可见」取哪句（照搬页面 220-225 行的 if/else）───────────

/**
 * 组头第二行的那句「这一组默认开启 / 默认不开启」（第八十一批 ④）。
 *
 * 输入是**调用方算好的布尔**：`group in ToolGroup.DEFAULT_VISIBLE` 那一次读取留在页面里
 * ——六十一批 G5 钉它必须恰好一处，第五十九批 §1.6 与 ProgressiveToolsTest G3 也钉着
 * 「设置页只许**读** DEFAULT_VISIBLE 来显示这一句，不许拿它去算可见集合」（那是第二条链）。
 */
internal fun baopuGroupDefaultCaption(isDefaultVisible: Boolean): Int =
    if (isDefaultVisible) R.string.baopu_tools_group_default_on
    else R.string.baopu_tools_group_default_off

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
