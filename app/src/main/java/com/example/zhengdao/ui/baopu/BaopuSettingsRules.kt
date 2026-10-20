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