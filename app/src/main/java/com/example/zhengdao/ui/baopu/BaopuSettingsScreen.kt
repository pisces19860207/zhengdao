// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第十八批 §2.1（A 段：抱朴设置归位）。
// 三块（工具开关 / 模型参数 / 思考深度）从 ui/SettingsDataActivity.kt **原样搬入**
// （含状态读取、保存、提示文案——派工单明令「不要只搬 UI」），另加两处本批新增：
// 1. 工具卡尾部两行**只读**说明（read / bash 免费通道兼容别名，§2.1-4 选「只读展示
//    『由通道决定』」——理由：别名归引擎管（BaopuRuntime，本批禁改），read 跟随
//    read_file 的开关（独立开关＝给「绕开开关」开洞，引擎注释明令禁止）、bash 恒拒
//    （本 App 不提供 shell），给它们做开关都是假开关；
// 2. 思考深度卡加一句提示：只有 DeepSeek 通道会带上这个参数（§2.1-5，此前「选了不
//    生效」这件事模型知道、用户不知道）。
//
// 承载方式：BaopuScreen 抽屉「抱朴设置」**直达本页**——页内全屏浮层，与章节抽取 /
// 备份原件 / 换通道同一模式（BaopuScreen 的 showExtract/showBackup/showChannel）。
// 不新建 Activity：AndroidManifest.xml 本批禁改，新增 Activity 必须动清单。
//
// 第八十一批（§B 80 延伸）：五条纯判据（折叠摘要 ①、取组序 ②、组标题 ③、默认可见提示 ④、
// 作用域→人设层 ⑤）等值搬进 ui/baopu/BaopuSettingsRules.kt，本页只留调用；布局、文案、
// prefs 读写点、写盘口一个没动（清单与方法盘点写在那个文件的文件头）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.ModelParams
import com.example.zhengdao.baopu.PersonaBackup
import com.example.zhengdao.baopu.PersonaBackupResult
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaRules
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupAudit
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.eval.ProdEvalSettings
import com.example.zhengdao.baopu.kernel.KernelAudit
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.FilterChip2
import com.example.zhengdao.ui.InfoRow
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.SettingsSubPage
import com.example.zhengdao.ui.WorkspaceFolderPicker
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.PersonaPackError
import com.example.zhengdao.baopu.PersonaPackPayload
import com.example.zhengdao.baopu.PersonaPackPrefs
import com.example.zhengdao.baopu.PersonaPackResult
import com.example.zhengdao.baopu.PersonaPackRules
import com.example.zhengdao.baopu.PersonaPackStore
import com.example.zhengdao.baopu.PersonaPackSummary
import com.example.zhengdao.ui.theme.zhengdaoSwitchColors
import com.example.zhengdao.ui.theme.zhengdaoErrorText

/** 抱朴设置页（第十八批 A 段）：三块归位 ＋ read/bash 只读说明 ＋ 思考深度提示。 */
@Composable
internal fun BaopuSettingsScreen(onDismiss: () -> Unit, onOpenSessionLibrary: () -> Unit = {}) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    // 承载方式（2026-10-18 真机修正）：必须用 **Dialog 全屏**，不能直接摆在 BaopuScreen 的
    // Column 里——实测那样只在对话与输入框之间挤出一条，聊天还露在外面上下两端。
    // 与 BaopuExtractSheet 同一条路子（Dialog 是真窗口），宽度默认受限故关掉 usePlatformDefaultWidth。
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SettingsSubPage(title = stringResource(R.string.baopu_drawer_settings), onBack = onDismiss) {

            // ── 抱朴工具（第一百四十一批 §8：长列表 → 一张摘要卡）──
            // 定位：抱朴页里那个 agent 能用哪些工具，由用户在这里决定。
            // 三条口径（派工单原话级）：
            // 1. 关掉的工具**不进发给模型的 tools 数组**——模型根本看不见；
            // 2. 模型若仍幻觉调用被关的工具，回一条工具结果「该工具已被用户关闭」，
            //    **不许自动打开**；
            // 3. 默认五个全启用（read_file / list_dir / search_in_files / file_info / write_file）。
            //
            // 141 批把「每组一行开关 ＋ 行内展开成员 ＋ 三段落说明」整段换成 §8 的摘要卡：
            // 收起只有一行三个数字，点开只列**要你处理**的组。**成员名单不进 UI**
            // （用户原话「谁关心你具体是哪些工具啊」）⇒ 单工具开关一并退役，组级开关是唯一入口。
            var toolTick by remember { mutableIntStateOf(0) }
            var toolRowsOpen by rememberSaveable { mutableStateOf(false) }
            // 开关存储（热读热写：改完下一轮请求的 tools 数组就变）。第五十九批两条仍生效：
            // 1. **这里不造注册表**（`BaopuRuntime.registry` 会把全局 CapabilitiesTool.sink
            //    指到自己身上，设置页那张临时注册表 `currentSessionId` 是 NO_SESSION ⇒
            //    用户开过一次设置页后，模型 `capabilities(enable=…)` 就写进 -1 那一格）。
            //    开关语义 `isEnabled(n) == !prefs.isDisabled(n)` 完全等价 ⇒ 直接读 prefs。
            // 2. **分组只从那张唯一的分组表读**（[ToolGroupTable]），不新造第二张组表。
            //
            // 九个组 × 每个成员一次 isDisabled 全在一次快照里读齐（`toolTick` 订阅），
            // 不给每个组各留一个 remember —— 那是九个刷新源，迟早有一个不跟手。
            val prefs = remember { BaopuRuntime.toolPrefs(ctx) }
            val toolGroups = remember { baopuSwitchableGroups(ToolGroup.values()) }
            val approvedGroups = remember(toolTick) { prefs.approvedGroups() }
            val disabledGroupSet = remember(toolTick) { prefs.disabledGroups() }
            val disabledNameSet = remember(toolTick) { prefs.disabledNames() }
            val toolStateOf: (ToolGroup) -> BaopuGroupState = { group ->
                baopuGroupState(
                    defaultVisible = group in ToolGroup.DEFAULT_VISIBLE,
                    approved = group in approvedGroups,
                    disabled = group in disabledGroupSet,
                )
            }
            val toolMembersOf: (ToolGroup) -> List<String> = { ToolGroupTable.namesOf(it) }
            val toolIsDisabled: (String) -> Boolean = { it in disabledNameSet }
            val toolSummary = BaopuToolArea.summarize(toolGroups, toolStateOf, toolMembersOf, toolIsDisabled)
            val toolRows = BaopuToolArea.rowsOf(toolGroups, toolStateOf, toolMembersOf, toolIsDisabled)

            Column(modifier = Modifier.fillMaxWidth()) {
                // 卡外组标签：与 [SectionCard] 的标题同款（本区换的是卡片本体，标签位置不换）
                Text(
                    text = stringResource(R.string.baopu_tools_card),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                // 档位＝信息卡（《纸墨黛青》§6.1）：纸深底 ＋ 只左边 4dp 黛青竖条，**不描四边**
                // （描四边＝行动卡语义，会与「等你动手」混淆）。这里刻意用 [BaopuInfoContainer]
                // 而不是 [SectionCard]：后者是白底 0.5dp 发丝边，两层叠起来就是两种边界位置。
                BaopuInfoContainer {
                    // 摘要行：整行可点＝展开／收起（热区 ≥48dp）。**不设 maxLines**——
                    // 200% 字号下宁可折两行也不许裁字（派工单设计约束第 4 条）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { toolRowsOpen = !toolRowsOpen },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.baopu_tools_summary,
                                toolSummary.groupOnCount,
                                toolSummary.toolCount,
                                toolSummary.groupOnCount,
                                toolSummary.pendingGroupCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(
                                if (toolRowsOpen) R.string.common_collapse_arrow else R.string.common_expand_arrow,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (toolRowsOpen) {
                        if (toolRows.isEmpty()) {
                            Text(
                                text = stringResource(R.string.baopu_tools_rows_empty),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        toolRows.forEach { row ->
                            val tone = baopuToolRowTone(row.state)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 组行命中 ≥48dp（派工单设计约束第 3 条）
                                    .heightIn(min = 64.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(baopuGroupTitle(row.group)),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = when (tone) {
                                            BaopuToolRowTone.Pending ->
                                                stringResource(R.string.baopu_tools_row_pending)
                                            BaopuToolRowTone.Closed ->
                                                stringResource(R.string.baopu_tools_row_closed)
                                            BaopuToolRowTone.Partial ->
                                                pluralStringResource(R.plurals.baopu_tools_row_partial, row.offCount, row.offCount)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = when (tone) {
                                            // 琥珀 #856018（纸深底 4.66:1，§2.1 第 2 条）只给「等你点头」
                                            BaopuToolRowTone.Pending -> MaterialTheme.colorScheme.tertiary
                                            BaopuToolRowTone.Closed, BaopuToolRowTone.Partial ->
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Switch(
                                    checked = baopuGroupSwitchOn(row.state),
                                    onCheckedChange = { want ->
                                        prefs.setGroupApproved(row.group, want && row.group !in ToolGroup.DEFAULT_VISIBLE)
                                        prefs.setGroupDisabled(row.group, !want)
                                        if (want) {
                                            // 141：单工具开关随成员名单一起退役（§8）⇒ 组开关打开时把
                                            // 组内「被单独关掉」的成员一并清掉。否则用户以前关过的那几个
                                            // 工具**再也开不回来**（界面里已经没有它们的开关了）。
                                            toolMembersOf(row.group).forEach { prefs.setDisabled(it, false) }
                                        }
                                        if (!want) BaopuRuntime.toolUnlocks(ctx).relockAll(listOf(row.group))
                                        // 审计自带落盘（ToolGroupAudit.manual 的 `.also { emit }` 走
                                        // sink=RunLog）；这里再 RunLog.log 一次就是双写
                                        // （2026-10-21 真机：一次点击两条一模一样的 [group] 行）。
                                        ToolGroupAudit.manual(row.group, want)
                                        toolTick++
                                    },
                                    colors = zhengdaoSwitchColors(),
                                )
                            }
                        }
                    }
                }
                // ── read / bash（第十八批 §2.1-4：只读展示「由通道决定」）──
                // 这两个名字不在 [BaopuToolCatalog.ENTRIES] 里（引擎侧的免费通道兼容别名：
                // read 跟随 read_file 的开关，bash 恒拒），所以没有开关可给——在此明说，
                // 不再「模型看得见、用户管不着」。它们**在摘要卡外**：不是组，塞进卡里会让
                // 「卡里全是组」这条读数失真。
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.baopu_tools_alias_read),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.baopu_tools_alias_read_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.baopu_tools_alias_bash),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.baopu_tools_alias_bash_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 工作区（第一百五十六批：放开工作区边界，用户批准即可）──
            // 开关只管「越界还要不要问」：开 ⇒ 每个会话第一次越界弹一张确认卡，同意才做
            // （157 补充：授权按会话落库，重启不再问；清单与撤销在下面一张卡里）；
            // 关 ⇒ 越界一律拒（与 156 批之前逐字一致）。存储在 BaopuRuntime.WorkspacePrefs。
            SectionCard(stringResource(R.string.baopu_workspace_card)) {
                var allowOutside by rememberSaveable { mutableStateOf(BaopuRuntime.WorkspacePrefs.enabled(ctx)) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 命中 ≥48dp（派工单设计约束第 3 条）
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.baopu_ws_outside_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.baopu_ws_outside_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = allowOutside,
                        onCheckedChange = { want ->
                            BaopuRuntime.WorkspacePrefs.setEnabled(ctx, want)
                            allowOutside = want
                            RunLog.log("抱朴设置：允许访问工作区外 = $want（开也每会话问一次）")
                        },
                        colors = zhengdaoSwitchColors(),
                    )
                }
            }

            // ── 已放行工作区外的会话（157 补充：一次会话问一次，可一键撤销）──
            // 清单本身读库（workspace_grant）；撤销只影响那一个会话，撤完下次越界重新问。
            Spacer(Modifier.height(12.dp))
            WorkspaceGrantCard()

            // ── 模型参数（刀 3 B 段 · 第十四批 J12：只读展示，不许编数）──（原样搬入）
            // 定位：当前通道向服务端声明的两个硬参数。没声明 ⇒ 「未声明」——
            // **不许显示 0 或 128000**：0 是「确实是 0」、128000 是已作废的旧回退值，
            // 两种都是编数（派工单 J12 原话级）。
            SectionCard(stringResource(R.string.baopu_params_card)) {
                val summary = remember {
                    ModelParams.summaryOf(
                        BaopuRuntime.provider(ctx),
                    )
                }
                InfoRow(
                    stringResource(R.string.baopu_params_window),
                    summary.window ?: stringResource(R.string.baopu_params_undeclared),
                )
                InfoRow(
                    stringResource(R.string.baopu_params_max_output),
                    summary.maxOutput ?: stringResource(R.string.baopu_params_undeclared),
                )
            }

            // ── 思考深度（刀 3 B 段 · 第十四批 C 段：关/低/高/最高 四档）──（原样搬入 ＋ 提示）
            // 两条口径（派工单原话级）：
            // 1. 改完**下一轮**生效——LlmClient 每轮现读档位，不用重建任何东西；
            // 2. 只对「声明了 effort」的通道有实效（DeepSeek 预设声明了；其余通道
            //    不瞎发这个字段，选了也不发——见 ModelParams KDoc 的分界）。
            // 第十八批 §2.1-5：第 2 条此前只写在代码注释里，界面上补一句（J7）。
            SectionCard(stringResource(R.string.baopu_effort_card)) {
                val provider = BaopuRuntime.provider(ctx)
                var effortId by remember {
                    mutableStateOf(
                        ModelParams.uiEffort(
                            ModelParams.storedEffort(ctx),
                            provider.reasoningEffort,
                        ),
                    )
                }
                Text(
                    text = stringResource(R.string.baopu_effort_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.baopu_effort_deepseek_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ModelParams.EFFORT_LEVELS.forEach { level ->
                        FilterChip2(
                            label = stringResource(effortLabel(level)),
                            selected = level == effortId,
                        ) {
                            effortId = level
                            ModelParams.saveEffort(ctx, level)
                        }
                    }
                }
            }

            // ── 生产评测（第一百六十八批：eval_prod_on 写入口；派工单 §2）──
            // 定位：周期性用当前通道跑一轮迷你评测，给进化建议供「个人生产证据」。
            // 默认关；关掉时零读用例、零请求、零写文件（ProdEvalGate＋execute 的计数器假件钉着）。
            // 写口只有这一个（[ProdEvalSettings.set]）——没有 devMode/adb/环境变量旁路；
            // 跑到只记录：不阻塞聊天、不回滚、不改人设/技能。
            Spacer(Modifier.height(12.dp))
            SectionCard(stringResource(R.string.baopu_eval_prod_card)) {
                var prodEvalOn by rememberSaveable { mutableStateOf(ProdEvalSettings.get(ctx)) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 命中 ≥48dp（派工单设计约束第 3 条）
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.baopu_eval_prod_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.baopu_eval_prod_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = prodEvalOn,
                        onCheckedChange = { want ->
                            ProdEvalSettings.set(ctx, want)
                            prodEvalOn = want
                            RunLog.log("抱朴设置：周期性生产评测 = $want（会话收尾触发，24h 至多一次，烧额度）")
                        },
                        colors = zhengdaoSwitchColors(),
                    )
                }
            }

            // ── 第一百四十七批 · 日记（情景记忆）卡 ──
            // 定位：看近期流水、按条真删。删除语义全在 DiaryDeletionRules（纯函数）＋
            // DiaryStore.realDelete（执行）；本卡只展示与确认——确认卡把
            // 「删日记，不删文件」说到字面上，并把产物路径逐条列出来给人核对（§5 边界②⑤）。
            // 这是本页除技能归档删除外第二处「用户本人按确认才写盘」的口径。
            SectionCard(stringResource(R.string.baopu_diary_card)) {
                var diaryReload by remember { mutableIntStateOf(0) }
                val (diaryEntries, diaryOnDelete) = rememberDiarySection(ctx, diaryReload)
                Text(
                    text = stringResource(R.string.baopu_diary_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                BaopuDiaryCard(
                    entries = diaryEntries,
                    onDelete = { plan ->
                        diaryOnDelete(plan)
                        diaryReload++
                    },
                )
            }

            // ── 第三十七批 D 段 · 人设回滚（B 全局 / C 本项目各一行）──
            // 写入前的备份已经在唯一写盘口做完（PersonaFiles.applyProposal），
            // 这里只负责「滚回去」：点了先备份当前版，再覆盖 —— 可反悔。
            // 没有备份时**按钮灰掉但这一行不消失**（判据 L6 最后一句：灰掉≠假装没这功能）。
            SectionCard(stringResource(R.string.persona_rollback_card)) {
                var rollbackTick by remember { mutableIntStateOf(0) }
                val pfr = remember { BaopuRuntime.personaFiles(ctx) }
                Text(
                    text = stringResource(R.string.persona_rollback_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                listOf(
                    PersonaLayer.C_PROJECT to R.string.persona_rollback_project,
                    PersonaLayer.B_GLOBAL to R.string.persona_rollback_global,
                ).forEach { (layer, labelRes) ->
                    val pfile = pfr.fileOf(layer)
                    val latest = remember(rollbackTick) { PersonaBackup.latestOf(pfile) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val stamp = latest?.let { PersonaBackup.stampOf(it.name, pfile.name) }
                            Text(
                                text = if (stamp != null) {
                                    stringResource(
                                        R.string.persona_rollback_last,
                                        PersonaBackup.displayStamp(stamp),
                                    )
                                } else {
                                    stringResource(R.string.persona_rollback_none)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            enabled = latest != null,
                            onClick = {
                                val msg = when (
                                    val r = PersonaBackup.restore(pfile, PersonaBackup.nowStamp())
                                ) {
                                    is PersonaBackupResult.Done -> res.getString(
                                        R.string.persona_rollback_done,
                                        r.backup.name,
                                    )
                                    is PersonaBackupResult.Rejected -> r.reason
                                }
                                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                rollbackTick++
                            },
                        ) {
                            Text(stringResource(R.string.persona_rollback_action))
                        }
                    }
                }
            }

            // ── 第一百六十五批 · 人设包（Persona Pack v1）──
            // 定位：用户选「谁在帮忙」的模板——只改语气/风格/工作偏好。包在
            // <工作区>/抱朴记忆/人设/<名字>/persona.md（文件是真相，卸载不丢）；
            // 启用项按工作区记在设置里。红线：包不能改 A 内核、确认制、工具权限。
            SectionCard(stringResource(R.string.persona_pack_card)) {
                var packTick by remember { mutableIntStateOf(0) }
                val packWorkspace = remember(packTick) { BaopuRuntime.workspaceRoot(ctx) }
                val packStore = remember(packWorkspace) { PersonaPackStore(packWorkspace) }
                val packs = remember(packTick, packStore) { packStore.list() }
                val activeDir = remember(packTick, packStore) {
                    PersonaPackPrefs.activeDir(ctx, packWorkspace)
                }
                val canRollback = remember(packTick, packStore) {
                    PersonaPackPrefs.hasPrevious(ctx, packWorkspace)
                }
                var previewing by remember { mutableStateOf<PersonaPackPayload?>(null) }
                var editorOpen by remember { mutableStateOf(false) }
                var editingPack by remember { mutableStateOf<PersonaPackSummary?>(null) }
                var editingText by remember { mutableStateOf("") }
                var deletingPack by remember { mutableStateOf<PersonaPackSummary?>(null) }

                fun say(msg: String) {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }

                fun activate(dir: String?, name: String) {
                    PersonaPackPrefs.setActive(ctx, packWorkspace, dir)
                    packTick++
                    say(
                        if (dir == null) res.getString(R.string.persona_pack_default_switched)
                        else res.getString(R.string.persona_pack_switched, name),
                    )
                }

                fun openEditor(pack: PersonaPackSummary?) {
                    if (pack == null) {
                        editingPack = null
                        editingText = res.getString(R.string.persona_pack_new_template)
                        editorOpen = true
                        return
                    }
                    when (val r = packStore.load(pack.dirName)) {
                        is PersonaPackResult.Ok -> {
                            editingPack = pack
                            editingText = r.pack.text
                            editorOpen = true
                        }
                        is PersonaPackResult.Rejected -> say(personaPackErrorText(res, r))
                    }
                }

                val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        val fallback = res.getString(R.string.persona_pack_import_fallback)
                        val display = queryOpenDisplayName(ctx, uri) ?: fallback
                        val result = runCatching {
                            ctx.contentResolver.openInputStream(uri)?.use {
                                packStore.importStream(display, fallback, it)
                            }
                        }.getOrNull()
                        when (result) {
                            null -> say(res.getString(R.string.persona_pack_err_io, "open"))
                            is PersonaPackResult.Ok -> {
                                say(res.getString(R.string.persona_pack_imported, result.pack.name))
                                packTick++
                            }
                            is PersonaPackResult.Rejected -> say(personaPackErrorText(res, result))
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.persona_pack_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))

                // 默认行：不套包＝按内核里的抱朴本人工作（与今天的行为逐字节相同）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.persona_pack_default_name),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.persona_pack_default_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (activeDir == null) {
                        Text(
                            text = stringResource(R.string.persona_pack_active),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        TextButton(onClick = { activate(null, "") }) {
                            Text(stringResource(R.string.persona_pack_use))
                        }
                    }
                }

                packs.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = p.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (p.manifestProblem != null) {
                                    res.getString(R.string.persona_pack_manifest_bad)
                                } else if (p.description.isNotBlank()) {
                                    p.description
                                } else {
                                    res.getString(
                                        R.string.persona_pack_size,
                                        (p.bytes / 1024).coerceAtLeast(1).toInt(),
                                    )
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (activeDir == p.dirName) {
                            Text(
                                text = stringResource(R.string.persona_pack_active),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            TextButton(onClick = { activate(p.dirName, p.name) }) {
                                Text(stringResource(R.string.persona_pack_use))
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = {
                            when (val r = packStore.load(p.dirName)) {
                                is PersonaPackResult.Ok -> previewing = r.pack
                                is PersonaPackResult.Rejected -> say(personaPackErrorText(res, r))
                            }
                        }) {
                            Text(stringResource(R.string.persona_pack_preview))
                        }
                        TextButton(onClick = { openEditor(p) }) {
                            Text(stringResource(R.string.persona_pack_edit))
                        }
                        TextButton(onClick = { deletingPack = p }) {
                            Text(stringResource(R.string.persona_pack_delete))
                        }
                    }
                }

                if (packs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.persona_pack_none),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { openEditor(null) }) {
                        Text(stringResource(R.string.persona_pack_new))
                    }
                    TextButton(onClick = { importLauncher.launch(arrayOf(PersonaPackRules.MIME_ANY)) }) {
                        Text(stringResource(R.string.persona_pack_import))
                    }
                    TextButton(
                        enabled = canRollback,
                        onClick = {
                            val rolled = PersonaPackPrefs.rollback(ctx, packWorkspace)
                            packTick++
                            if (rolled == null) {
                                say(res.getString(R.string.persona_pack_rollback_default))
                            } else {
                                val name = packs.firstOrNull { it.dirName == rolled }?.name ?: rolled
                                say(res.getString(R.string.persona_pack_rollback_done, name))
                            }
                        },
                    ) {
                        Text(stringResource(R.string.persona_pack_rollback))
                    }
                }
                Text(
                    text = stringResource(R.string.persona_pack_file_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                previewing?.let { p ->
                    PersonaPackPreviewDialog(p) { previewing = null }
                }
                deletingPack?.let { p ->
                    PersonaPackDeleteDialog(
                        pack = p,
                        onDismiss = { deletingPack = null },
                        onConfirm = {
                            val removed = packStore.delete(p.dirName)
                            if (activeDir == p.dirName) {
                                PersonaPackPrefs.clear(ctx, packWorkspace)
                            }
                            deletingPack = null
                            packTick++
                            say(
                                if (removed) res.getString(R.string.persona_pack_delete_done, p.name)
                                else res.getString(R.string.persona_pack_err_io, "delete"),
                            )
                        },
                    )
                }
                if (editorOpen) {
                    val locked = editingPack
                    PersonaPackEditorDialog(
                        initialName = locked?.name ?: "",
                        initialText = editingText,
                        nameLocked = locked != null,
                        onDismiss = { editorOpen = false },
                        onSave = { newName, body ->
                            if (locked == null) packStore.create(newName, body)
                            else packStore.update(locked.dirName, body)
                        },
                        onSaved = { saved ->
                            editorOpen = false
                            packTick++
                            say(res.getString(R.string.persona_pack_saved, saved.name))
                        },
                    )
                }
            }

            // ── 技能与索引审阅（第十九批 §2.5：第一版只做看和删）──
            // 删索引＝用户本人操作即确认（确认制最高一级），但**仍走唯一合法写盘口**
            // PersonaFiles.applyProposal（PENDING 提案），不另开写文件的路。
            // 只删索引行，不删技能文件（§2.5：文件由用户在文件管理器自己删）。
            SectionCard(stringResource(R.string.skill_review_card)) {
                var skillReload by remember { mutableIntStateOf(0) }
                val pf = remember { BaopuRuntime.personaFiles(ctx) }
                // 第三十四批：两层根目录上移到卡片开头。A 段的详情、B 段的归档列表都要按这两个根
                // **现读盘**，而入口在下面的索引行上（原来这两行到草稿段才声明，够不着）。
                // 落点口径一个字没改：全局＝App 私有的「抱朴记忆」，项目＝BaopuRuntime.workspaceRoot。
                val globalRoot = File(ctx.filesDir, "抱朴记忆")
                val projectRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(ctx)
                var detail by remember { mutableStateOf<SkillDetailRow?>(null) }
                var editing by remember { mutableStateOf<SkillDetailRow?>(null) }
                var archivedOpen by remember { mutableStateOf(false) }
                // B 段：点过一次「彻底删除」的那一条（第二击才带 confirmed=true；闸在 deleteArchived 里）
                var confirmingDelete by remember { mutableStateOf<File?>(null) }
                val bText = remember(skillReload) { pf.read(PersonaLayer.B_GLOBAL) }
                val cText = remember(skillReload) { pf.read(PersonaLayer.C_PROJECT) }
                val globalLines = remember(skillReload) {
                    SkillRules.parseIndexLines(bText, MemoryScope.GLOBAL)
                }
                val projectLines = remember(skillReload) {
                    SkillRules.parseIndexLines(cText, MemoryScope.PROJECT)
                }
                // ── 第五十七批 §1.2 · 待重验（旁挂 `_重验.json`，SKILL.md 一个字节不动）──
                // 判据是纯函数（SkillRefine.expiryVerdict），证据来自 skill_event 表；
                // 算表＋幂等写旁挂 JSON 全在数据层函数 refreshRecheck（T5：设置页零写盘）。
                // 记录上线前没有 use 数据 ⇒ 判不了 ⇒ 不标（宁可漏标不冤枉）。只提示不处置。
                val recheckTables = remember(skillReload) {
                    val d = com.example.zhengdao.baopu.BaopuRuntime.db(ctx)
                    val now = System.currentTimeMillis()
                    fun oksOf(name: String): List<Boolean> = d.read { x ->
                        val s2 = com.example.zhengdao.baopu.BaopuSql.recentUseOks(
                            name, com.example.zhengdao.baopu.SkillRefine.SkillExpiry.FAIL_WINDOW,
                        )
                        x.rawQuery(s2.sql, s2.args).use { c ->
                            (0 until c.count).map { c.moveToPosition(it); c.getInt(0) == 1 }
                        }
                    }
                    fun lastOf(name: String): Long? = d.read { x ->
                        val s2 = com.example.zhengdao.baopu.BaopuSql.lastUseAt(name)
                        x.rawQuery(s2.sql, s2.args).use { c ->
                            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                        }
                    }
                    mapOf(
                        "global" to refreshRecheck(
                            SkillRules.globalSkillDir(globalRoot), globalLines, ::oksOf, ::lastOf, now,
                        ),
                        "project" to refreshRecheck(
                            SkillRules.projectSkillDir(projectRoot), projectLines, ::oksOf, ::lastOf, now,
                        ),
                    )
                }
                val globalRecheck: Map<String, String> = recheckTables["global"].orEmpty()
                val projectRecheck: Map<String, String> = recheckTables["project"].orEmpty()
                // ── 第一百八十批 · 失败态摘要优先 ─────────────────────────────────
                // 失败（待重验）技能聚成「同类失败 ×N」，判语原句只进详情——原来每个技能行下
                // 铺一句红判语，失败一多就是一长串扫不动。聚合判据在 BaopuSkillFailure（纯函数，
                // JVM 可测）；最近失败时刻来自 skill_event **只读**查询，判据/存储一个字不改。
                // 草稿数（待转正）也在这里先算好：顶部读数行要用，下面草稿段共用同一份。
                val draftRows = remember(skillReload) { draftSkillRows(projectRoot, globalRoot) }
                val failureRows = remember(skillReload) {
                    val d = com.example.zhengdao.baopu.BaopuRuntime.db(ctx)
                    fun lastFailedAtOf(name: String): Long? = d.read { x ->
                        // 只读查询（BaopuDb 同族 helper，180 批新增）；查不到/判不了 ⇒ null 不拦截
                        val s2 = com.example.zhengdao.baopu.BaopuSql.lastFailedUseAt(name)
                        x.rawQuery(s2.sql, s2.args).use { c ->
                            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                        }
                    }
                    buildSkillFailureRows(globalRecheck, projectRecheck, ::lastFailedAtOf)
                }
                val failureGroups = remember(skillReload) { aggregateSkillFailures(failureRows) }
                // 行内短状态查这个表（键带层：两层同名各算各的，与列表同一口径）
                val failureByKey = remember(skillReload) {
                    failureRows.associateBy { it.global to it.name }
                }
                val draftGlobalNames = remember(skillReload) {
                    draftRows.filter { it.scope == SkillScope.GLOBAL }.map { it.name }.toSet()
                }
                val draftProjectNames = remember(skillReload) {
                    draftRows.filter { it.scope == SkillScope.PROJECT }.map { it.name }.toSet()
                }
                var failureDetail by remember { mutableStateOf<SkillFailureGroup?>(null) }
                // ── 第三十六批 B 段 · 技能分类（旁挂 `_分类.json`，SKILL.md 一个字不动）──
                // 分类**只影响显示**：它不改索引行文本、不改正文、不改人设（判据 T5）。
                // 两层各一份表（全局那份在 App 私有、项目那份在工作区），互不串。
                // 坏 JSON 由纯函数当空表（corrupt=true），这里只补一句红字，页面不许崩。
                var catTick by remember { mutableIntStateOf(0) }
                var picking by remember { mutableStateOf<Pair<SkillScope, String>?>(null) }
                val globalCats = remember(catTick) { readCategories(SkillRules.globalSkillDir(globalRoot)) }
                val projectCats = remember(catTick) { readCategories(SkillRules.projectSkillDir(projectRoot)) }
                Text(
                    text = stringResource(
                        R.string.skill_review_desc,
                        globalLines.size + projectLines.size,
                        SkillRules.INDEX_CAP,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                // 第一百八十批：顶部读数一行（技能 N · 失败 M · 待转正 K）——摘要优先。
                // 全成功态也只是这行中性读数，不出红字；红字只出现在失败组行与行内短状态上。
                Text(
                    text = stringResource(
                        R.string.skill_fail_summary,
                        globalLines.size + projectLines.size,
                        failureRows.size,
                        draftRows.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 同因聚合组行：每组一条「同类失败 ×N ＋ 判语摘要」，点开详情才看完整信息
                // （判语原文＋逐条证据，可复制）。不新造容器：组行是决策中心同款信息卡。
                failureGroups.forEach { g ->
                    SkillFailureGroupRow(group = g, onClick = { failureDetail = g })
                }
                // 第二十三批 A 段：技能现在能自己写了（写前备份、必过确认卡、草稿 3 次转正）
                var newSkillOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { newSkillOpen = true }) {
                    Text(stringResource(R.string.skill_new_button))
                }
                if (newSkillOpen) {
                    SkillEditorDialog(
                        onDismiss = { newSkillOpen = false },
                        onSaved = { result ->
                            val msg = when (result) {
                                is com.example.zhengdao.baopu.SkillWriteResult.Written ->
                                    res.getString(R.string.skill_new_saved, result.file.parentFile?.name ?: "")
                                is com.example.zhengdao.baopu.SkillWriteResult.Rejected -> result.reason
                            }
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            skillReload++
                        },
                        projectRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(ctx),
                        globalRoot = File(ctx.filesDir, "抱朴记忆"),
                        personaFiles = pf,
                        backupStamp = com.example.zhengdao.util.TimeFormats.fileStamp(),
                    )
                }
                if (globalLines.isEmpty() && projectLines.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skill_none),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                /** 索引行 → 技能正文文件（回滚按钮要按文件找 `.bak-*`）。落点与 SkillRules 同一口径。 */
                fun skillFileOf(layer: PersonaLayer, name: String): File {
                    val root = when (layer) {
                        PersonaLayer.B_GLOBAL -> File(ctx.filesDir, "抱朴记忆")
                        else -> com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(ctx)
                    }
                    val dir = when (layer) {
                        PersonaLayer.B_GLOBAL -> SkillRules.globalSkillDir(root)
                        else -> SkillRules.projectSkillDir(root)
                    }
                    return File(File(dir, name), SkillRules.SKILL_FILE)
                }

                /** 恢复到上一版（第二十三批 A 段）。没有备份就不出这个按钮，点了也没有意义。 */
                fun restoreLast(layer: PersonaLayer, name: String) {
                    val file = skillFileOf(layer, name)
                    val msg = when (val r = SkillStore.restore(file, com.example.zhengdao.util.TimeFormats.fileStamp())) {
                        is com.example.zhengdao.baopu.SkillWriteResult.Written ->
                            res.getString(R.string.skill_restore_done)
                        is com.example.zhengdao.baopu.SkillWriteResult.Rejected -> r.reason
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    skillReload++
                }

                fun removeIndex(layer: PersonaLayer, name: String) {
                    // 第二十三批 A 段追加：删索引行时把技能正文也**归档**（改名 .deleted-*，不硬删）
                    val archived = SkillStore.archive(skillFileOf(layer, name), com.example.zhengdao.util.TimeFormats.fileStamp())
                    if (archived is com.example.zhengdao.baopu.SkillWriteResult.Rejected) {
                        Toast.makeText(ctx, archived.reason, Toast.LENGTH_SHORT).show()
                        return
                    }
                    val current = pf.read(layer).orEmpty()
                    val newText = (SkillRules.removeIndexLine(current, name) as? SkillRules.EditResult.Ok)
                        ?.newText ?: return
                    val p = PersonaRules.propose(
                        layer, current, newText,
                        res.getString(R.string.skill_reason_manual),
                    ) ?: return
                    pf.applyProposal(p)
                    skillReload++
                }

                /**
                 * B 段：归档的每一种结果都要**说话**（判据 U3＋U5 的第二、三处）。
                 *
                 * `ArchivedOutcome` 只带盘上路径，句子全在这里查 strings.xml 的键——
                 * 撞名（Occupied）要指名占位者、没二次确认（NeedsConfirm）不许装作删了、
                 * 盘上没做成（IoFailed）要带上是哪个文件与原因，一条都不许静默。
                 */
                fun sayArchived(o: ArchivedOutcome) {
                    val msg = when (o) {
                        is ArchivedOutcome.Restored -> when (o.index) {
                            RestoredIndex.Added -> res.getString(R.string.skill_archived_restored, o.name)
                            RestoredIndex.OverCap -> res.getString(R.string.skill_archived_index_full, SkillRules.INDEX_CAP)
                            RestoredIndex.BadName -> res.getString(R.string.skill_archived_index_skipped, o.name)
                        }
                        is ArchivedOutcome.Occupied -> res.getString(R.string.skill_archived_occupied, o.target)
                        is ArchivedOutcome.IoFailed -> res.getString(R.string.skill_archived_io_failed, o.target, o.why)
                        is ArchivedOutcome.NeedsConfirm -> res.getString(R.string.skill_archived_confirm, o.target)
                        is ArchivedOutcome.Deleted -> res.getString(R.string.skill_archived_deleted, o.target)
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }

                Text(
                    text = stringResource(R.string.skill_group_global),
                    style = MaterialTheme.typography.labelLarge,
                )
                // 三十六批 B 段：这一段**按分类分组**（组标题只在换档时补一行，行本身一个字不改）。
                // `sortByCategory` 是稳定排序 ⇒ 同一档里仍是人设里那个顺序。
                var globalLastCat: SkillCategory? = null
                sortByCategory(globalLines, { it.name }, globalCats).forEach { line ->
                    val lineCat = globalCats.of(line.name)
                    if (lineCat != globalLastCat) {
                        Text(
                            text = stringResource(lineCat.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        globalLastCat = lineCat
                    }
                    Row(
                        // A 段（判据 U1/U5）：**整行可点** ⇒ 详情对话框，正文按盘上原文逐字显示。
                        // 行上的「恢复到上一版／删索引」两个按钮照旧（点击不冲突：子按钮先吃掉这一击）。
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                detail = skillDetailRow(line.name, SkillScope.GLOBAL, projectRoot, globalRoot)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                            withoutBoldMarkers(line.name),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                            Text(
                                line.scenario,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 第一百八十批：行内只留一枚**短状态**（失败＝红字一行带最近失败时刻，
                            // 无失败记录的「久未用」类只说失败两字；判语原句聚到上面的同类失败组，
                            // 不再每行铺一遍）。草稿行给一枚灰「草稿」（顶部待转正 K 就来自这些行）。
                            when {
                                failureByKey.containsKey(true to line.name) -> {
                                    val fr = failureByKey.getValue(true to line.name)
                                    Text(
                                        text = if (fr.lastFailedAt != null) {
                                            stringResource(
                                                R.string.skill_row_failed_at,
                                                skillFailureTimeText(fr.lastFailedAt),
                                            )
                                        } else {
                                            stringResource(R.string.skill_row_failed)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = zhengdaoErrorText(),
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                line.name in draftGlobalNames -> Text(
                                    text = stringResource(R.string.skill_detail_status_draft),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        // 右侧当前分类（点一下弹六档小选单改）。不用 TextButton：那玩意儿最小宽度
                        // 会把这一行挤到 360dp 窄屏上换行。
                        Text(
                            text = stringResource(lineCat.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { picking = SkillScope.GLOBAL to line.name }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        if (SkillStore.backupsOf(skillFileOf(PersonaLayer.B_GLOBAL, line.name)).isNotEmpty()) {
                            TextButton(onClick = { restoreLast(PersonaLayer.B_GLOBAL, line.name) }) {
                                Text(stringResource(R.string.skill_restore_last))
                            }
                        }
                        TextButton(onClick = { removeIndex(PersonaLayer.B_GLOBAL, line.name) }) {
                            Text(stringResource(R.string.skill_delete))
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.skill_group_project),
                    style = MaterialTheme.typography.labelLarge,
                )
                var projectLastCat: SkillCategory? = null
                sortByCategory(projectLines, { it.name }, projectCats).forEach { line ->
                    val lineCat = projectCats.of(line.name)
                    if (lineCat != projectLastCat) {
                        Text(
                            text = stringResource(lineCat.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        projectLastCat = lineCat
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                detail = skillDetailRow(line.name, SkillScope.PROJECT, projectRoot, globalRoot)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(line.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                line.scenario,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 第一百八十批：同上——行内短状态，判语原句进同类失败组（不铺长串）
                            when {
                                failureByKey.containsKey(false to line.name) -> {
                                    val fr = failureByKey.getValue(false to line.name)
                                    Text(
                                        text = if (fr.lastFailedAt != null) {
                                            stringResource(
                                                R.string.skill_row_failed_at,
                                                skillFailureTimeText(fr.lastFailedAt),
                                            )
                                        } else {
                                            stringResource(R.string.skill_row_failed)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = zhengdaoErrorText(),
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                line.name in draftProjectNames -> Text(
                                    text = stringResource(R.string.skill_detail_status_draft),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        Text(
                            text = stringResource(lineCat.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { picking = SkillScope.PROJECT to line.name }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                        if (SkillStore.backupsOf(skillFileOf(PersonaLayer.C_PROJECT, line.name)).isNotEmpty()) {
                            TextButton(onClick = { restoreLast(PersonaLayer.C_PROJECT, line.name) }) {
                                Text(stringResource(R.string.skill_restore_last))
                            }
                        }
                        TextButton(onClick = { removeIndex(PersonaLayer.C_PROJECT, line.name) }) {
                            Text(stringResource(R.string.skill_delete))
                        }
                    }
                }
                // 三十六批 B 段：分类表读不通 ⇒ 当「没有分类」用（判据 T4：不许抛、不许崩页面），
                // 但得**说一句**——悄悄当空表是第二种骗人（红字，沿用 34 批那套 zhengdaoErrorText）。
                if (globalCats.corrupt || projectCats.corrupt) {
                    Text(
                        text = stringResource(R.string.skill_cat_corrupt),
                        style = MaterialTheme.typography.labelSmall,
                        color = zhengdaoErrorText(),
                    )
                }
                // 六档小选单（点行右侧那枚分类标签进来）。写盘只有 [setCategory] 一条口，
                // 它只写 `_分类.json`——正文、索引行、人设一概不碰（判据 T5）。
                picking?.let { (pickScope, pickName) ->
                    val pickDir = skillDirOf(globalRoot, projectRoot, pickScope == SkillScope.GLOBAL)
                    val pickTable = if (pickScope == SkillScope.GLOBAL) globalCats else projectCats
                    SkillCategoryDialog(
                        name = pickName,
                        current = pickTable.of(pickName),
                        onDismiss = { picking = null },
                        onPick = { cat ->
                            val msg = when (val o = setCategory(pickDir, pickName, cat)) {
                                is CategoryOutcome.Saved ->
                                    res.getString(R.string.skill_cat_saved, o.name, res.getString(o.category.labelRes))
                                is CategoryOutcome.Rejected -> res.getString(R.string.skill_cat_rejected, o.name)
                                is CategoryOutcome.IoFailed ->
                                    res.getString(R.string.skill_cat_io_failed, o.name, o.why)
                            }
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            picking = null
                            catTick++
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                // ── 第三十二批 A 段 · 草稿（试用中）──────────────────────────────
                // 23A 把 status／uses／canPromote 全算完了，界面上一个都没露（派工单 §0）。
                // 这一段只**读**数据层算好的数；写盘只有 commitSkillDraft → SkillStore.apply 一条路（T4）。
                // （两层根目录 globalRoot／projectRoot 已在卡片开头声明，34 批上移——详情与归档共用。
                //   draftRows 本批上移到失败态块：顶部读数行的「待转正 K」要用同一份，不扫两遍盘。）

                /**
                 * 「用一次」：uses+1 写回，仍是草稿（能不能转正继续由数据层判）。
                 *
                 * 第九十九批 §1.5.3：写成功之后**顺手记两行账**——`use` 行＋
                 * `source=MANUAL` 的效果信号（85 §2 点名的手动使用入口）。这是「用了之后
                 * 有没有用」这条效果链上唯一的人肉样本来源：不接它，新技能永远零样本，
                 * 进化判定只会永远 DEFER。记账失败只 log，不拦这一次试用（65/89 口径）。
                 */
                fun useOnce(row: SkillDraftRow) {
                    val msg = when (
                        val r = commitSkillDraft(
                            row = row,
                            newBody = useOnceBody(row.body),
                            stillDraft = true,
                            projectRoot = projectRoot,
                            globalRoot = globalRoot,
                            personaFiles = pf,
                            backupStamp = com.example.zhengdao.util.TimeFormats.fileStamp(),
                        )
                    ) {
                        is com.example.zhengdao.baopu.SkillWriteResult.Written -> {
                            com.example.zhengdao.baopu.BaopuRuntime.recordSkillUseManual(ctx, row.name, row.file)
                            res.getString(R.string.skill_draft_used, row.name)
                        }
                        is com.example.zhengdao.baopu.SkillWriteResult.Rejected -> r.reason
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    skillReload++
                }

                /** 「转正」：正文头改 status=active ＋ 索引多一行（走 addIndexLine／applyProposal）。 */
                fun promote(row: SkillDraftRow) {
                    val msg = when (
                        val r = commitSkillDraft(
                            row = row,
                            newBody = promoteBody(row.body),
                            stillDraft = false,
                            projectRoot = projectRoot,
                            globalRoot = globalRoot,
                            personaFiles = pf,
                            backupStamp = com.example.zhengdao.util.TimeFormats.fileStamp(),
                        )
                    ) {
                        // 索引没加上（上限挡着）时必须明说——正文已转正但模型还看不见它。
                        is com.example.zhengdao.baopu.SkillWriteResult.Written ->
                            if (r.indexUpdated) res.getString(R.string.skill_draft_promoted, row.name)
                            else res.getString(R.string.skill_draft_index_full, SkillRules.INDEX_CAP)
                        is com.example.zhengdao.baopu.SkillWriteResult.Rejected -> r.reason
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    skillReload++
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.skill_draft_group),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (draftRows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skill_draft_none),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                draftRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                // T2：N 就是 SkillStore.usesOf 的读数，分母是 PROMOTE_USES——这里不自己数
                                text = stringResource(R.string.skill_draft_uses, row.uses, SkillStore.PROMOTE_USES),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { useOnce(row) }) {
                            Text(stringResource(R.string.skill_draft_use_once))
                        }
                        // T3：「转正」只在 canPromote 为真时出现（够不够次数不由 UI 判）
                        if (row.canPromote) {
                            TextButton(onClick = { promote(row) }) {
                                Text(stringResource(R.string.skill_draft_promote))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // ── 第三十四批 B 段 · 已归档入口 ────────────────────────────────
                // 删过的技能都躺在 `<名>/SKILL.md.deleted-<时间戳>`（23A 的归档口径：改名不硬删），
                // 但界面上**从来没有入口** ⇒ 用户以为删了就真没了，抱朴自己也找不回来。
                // 派工单原话：这一行「N=0 时灰掉但不许消失」——所以它是常驻的 TextButton，只把 enabled 关掉。
                val archivedRows = remember(skillReload) { archivedSkillRows(projectRoot, globalRoot) }
                TextButton(enabled = archivedRows.isNotEmpty(), onClick = { archivedOpen = true }) {
                    Text(stringResource(R.string.skill_archived_group, archivedRows.size))
                }
                if (archivedOpen) {
                    SkillArchivedDialog(
                        rows = archivedRows,
                        confirming = confirmingDelete,
                        onDismiss = {
                            archivedOpen = false
                            confirmingDelete = null // 关掉面板就把「等第二击」的状态清掉，下次进来重新确认
                        },
                        onRestore = { row ->
                            sayArchived(restoreArchived(row, projectRoot, globalRoot, pf))
                            skillReload++
                        },
                        onDelete = { row ->
                            val o = deleteArchived(row, confirmingDelete?.absolutePath == row.file.absolutePath)
                            when (o) {
                                is ArchivedOutcome.NeedsConfirm -> confirmingDelete = row.file
                                is ArchivedOutcome.Deleted -> confirmingDelete = null
                                else -> Unit
                            }
                            sayArchived(o)
                            skillReload++
                        },
                    )
                }

                // ── 第三十四批 A 段 · 详情对话框（整行可点的落点）────────────────
                detail?.let { d ->
                    SkillDetailDialog(
                        detail = d,
                        onDismiss = { detail = null },
                        onRestoreBackup = {
                            // 写盘仍然只有 SkillStore.restore 那一条（它自己先备份当前正文再覆盖）
                            restoreLast(baopuPersonaLayerOf(d.scope), d.name)
                            detail = skillDetailRow(d.name, d.scope, projectRoot, globalRoot)
                        },
                        onEdit = {
                            // 进编辑前**现读一次**：详情那屏可能是几分钟前打开的，中间「用一次」动过正文
                            editing = skillDetailRow(d.name, d.scope, projectRoot, globalRoot)
                            detail = null
                        },
                    )
                }

                // ── 第三十四批 C 段 · 编辑（复用 SkillEditorDialog，名字锁定）──────
                editing?.let { d ->
                    SkillEditorDialog(
                        onDismiss = { editing = null },
                        onSaved = { result ->
                            val msg = when (result) {
                                is com.example.zhengdao.baopu.SkillWriteResult.Written ->
                                    res.getString(R.string.skill_edit_saved)
                                is com.example.zhengdao.baopu.SkillWriteResult.Rejected -> result.reason
                            }
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            skillReload++
                        },
                        projectRoot = projectRoot,
                        globalRoot = globalRoot,
                        personaFiles = pf,
                        backupStamp = com.example.zhengdao.util.TimeFormats.fileStamp(),
                        lockedName = d.name,
                        initialBody = stripStatusHeader(d.body),
                        initialScope = d.scope,
                        initialDraft = d.isDraft,
                        originalBody = d.body,
                    )
                }

                // ── 第一百八十批 · 失败详情（同类失败组行的落点；判据与文案在 BaopuSkillFailure）──
                failureDetail?.let { g ->
                    SkillFailureDetailDialog(group = g, onDismiss = { failureDetail = null })
                }

                Spacer(Modifier.height(6.dp))
                // B/C 人设只读展示（§2.5：能看见；编辑留给后续批次）
                Text(
                    text = stringResource(R.string.skill_persona_b),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!bText.isNullOrBlank()) {
                    Text(
                        text = bText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.skill_persona_c),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!cText.isNullOrBlank()) {
                    Text(
                        text = cText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }
        }
    }
}

/**
 * 抱朴工具的开关标签（自 SettingsDataActivity 原样搬入——本页是这些卡唯一的家）。
 *
 * 为什么用 when 而不是从 catalog 里带名字：agent 包零 Android 依赖，R 引用
 * 不能进那里；标签是纯展示层的事，走 strings.xml 双语。
 * catalog 加新工具时这里同步加一行——漏了会直接抛 IllegalArgumentException（fail-fast），
 * 回归网见 BaopuSettingsGuardTest J8。
 */
private fun baopuToolLabel(name: String): Int = when (name) {
    "card_inspect" -> R.string.baopu_tool_card_inspect
    "card_export" -> R.string.baopu_tool_card_export
    "card_build" -> R.string.baopu_tool_card_build
    "capabilities" -> R.string.baopu_tool_capabilities
    "preview_local_file" -> R.string.baopu_tool_preview_local_file
    "read_file" -> R.string.baopu_tool_read_file
    "list_dir" -> R.string.baopu_tool_list_dir
    "search_in_files" -> R.string.baopu_tool_search_in_files
    "find_files" -> R.string.baopu_tool_find_files
    "book_outline" -> R.string.baopu_tool_book_outline
    "file_info" -> R.string.baopu_tool_file_info
    "write_file" -> R.string.baopu_tool_write_file
    "edit_file" -> R.string.baopu_tool_edit_file
    "move_path" -> R.string.baopu_tool_move_path
    "convert_encoding" -> R.string.baopu_tool_convert_encoding
    "browser_open" -> R.string.baopu_tool_browser_open
    "browser_snapshot" -> R.string.baopu_tool_browser_snapshot
    "browser_click" -> R.string.baopu_tool_browser_click
    "browser_type" -> R.string.baopu_tool_browser_type
    "browser_scroll" -> R.string.baopu_tool_browser_scroll
    "browser_screenshot" -> R.string.baopu_tool_browser_screenshot
    // 第一百七十七批：坐标四件＋等待（同批新增工具必须同步两张映射表，
    // 否则真机进本页会 IllegalArgumentException —— 见下面 update_plan 那条教训）
    "browser_tap" -> R.string.baopu_tool_browser_tap
    "browser_swipe" -> R.string.baopu_tool_browser_swipe
    "browser_long_press" -> R.string.baopu_tool_browser_long_press
    "browser_key" -> R.string.baopu_tool_browser_key
    "browser_wait" -> R.string.baopu_tool_browser_wait
    // 第十七批 A+B 段加的 update_plan：当时没同步这张表 ⇒ 2026-10-18 真机开本页直接崩
    // （IllegalArgumentException: 未知抱朴工具「update_plan」）。回归网见 BaopuSettingsGuardTest 的
    // 「catalog 每个工具都有标签/副标题」那条。
    "update_plan" -> R.string.baopu_tool_update_plan
    "write_skill" -> R.string.baopu_tool_write_skill
        "append_persona" -> R.string.baopu_tool_append_persona
    "library_list" -> R.string.baopu_tool_library_list
    "library_organize" -> R.string.baopu_tool_library_organize
    "kb_index" -> R.string.baopu_tool_kb_index
    "kb_search" -> R.string.baopu_tool_kb_search
    "delete_path" -> R.string.baopu_tool_delete_path
    "image_ops" -> R.string.baopu_tool_image_ops
    "generate_image" -> R.string.baopu_tool_generate_image
    // 小说工程三件（第一百五十五批）：漏这两组分支 ⇒ 设置页开工具卡直接抛（同 update_plan 教训）
    "novel_create" -> R.string.baopu_tool_novel_create
    "novel_project" -> R.string.baopu_tool_novel_project
    "novel_continue" -> R.string.baopu_tool_novel_continue
    "ask_user" -> R.string.baopu_tool_ask_user
    // 第一百二十七批：多 Agent 协作（run_subtasks）。不补这两行 ⇒ 设置页开工具卡直接抛。
    "run_subtasks" -> R.string.baopu_tool_run_subtasks
    else -> throw IllegalArgumentException("未知抱朴工具「$name」：开关标签映射漏了，请补 baopuToolLabel")
}

/**
 * 思考深度四档的标签（自 SettingsDataActivity 原样搬入；与 ModelParams.EFFORT_LEVELS 一一对应）。
 * 同 baopuToolLabel 的理由：ModelParams 在 baopu 包（零 Android 依赖），R 引用不能进那里。
 */
private fun effortLabel(level: String): Int = when (level) {
    ModelParams.EFFORT_NONE -> R.string.baopu_effort_none
    ModelParams.EFFORT_LOW -> R.string.baopu_effort_low
    ModelParams.EFFORT_HIGH -> R.string.baopu_effort_high
    ModelParams.EFFORT_MAX -> R.string.baopu_effort_max
    else -> throw IllegalArgumentException("未知思考档位「$level」：四档映射漏了，请补 effortLabel")
}

/** 抱朴工具的开关副标题（同上，双语走 strings.xml）。 */
private fun baopuToolDesc(name: String): Int = when (name) {
    "card_inspect" -> R.string.baopu_tool_card_inspect_desc
    "card_export" -> R.string.baopu_tool_card_export_desc
    "card_build" -> R.string.baopu_tool_card_build_desc
    "capabilities" -> R.string.baopu_tool_capabilities_desc
    "preview_local_file" -> R.string.baopu_tool_preview_local_file_desc
    "read_file" -> R.string.baopu_tool_read_file_desc
    "list_dir" -> R.string.baopu_tool_list_dir_desc
    "search_in_files" -> R.string.baopu_tool_search_in_files_desc
    "find_files" -> R.string.baopu_tool_find_files_desc
    "book_outline" -> R.string.baopu_tool_book_outline_desc
    "file_info" -> R.string.baopu_tool_file_info_desc
    "write_file" -> R.string.baopu_tool_write_file_desc
    "edit_file" -> R.string.baopu_tool_edit_file_desc
    "move_path" -> R.string.baopu_tool_move_path_desc
    "convert_encoding" -> R.string.baopu_tool_convert_encoding_desc
    "browser_open" -> R.string.baopu_tool_browser_open_desc
    "browser_snapshot" -> R.string.baopu_tool_browser_snapshot_desc
    "browser_click" -> R.string.baopu_tool_browser_click_desc
    "browser_type" -> R.string.baopu_tool_browser_type_desc
    "browser_scroll" -> R.string.baopu_tool_browser_scroll_desc
    "browser_screenshot" -> R.string.baopu_tool_browser_screenshot_desc
    "browser_tap" -> R.string.baopu_tool_browser_tap_desc
    "browser_swipe" -> R.string.baopu_tool_browser_swipe_desc
    "browser_long_press" -> R.string.baopu_tool_browser_long_press_desc
    "browser_key" -> R.string.baopu_tool_browser_key_desc
    "browser_wait" -> R.string.baopu_tool_browser_wait_desc
    "update_plan" -> R.string.baopu_tool_update_plan_desc
    "write_skill" -> R.string.baopu_tool_write_skill_desc
        "append_persona" -> R.string.baopu_tool_append_persona_desc
    "library_list" -> R.string.baopu_tool_library_list_desc
    "library_organize" -> R.string.baopu_tool_library_organize_desc
    "kb_index" -> R.string.baopu_tool_kb_index_desc
    "kb_search" -> R.string.baopu_tool_kb_search_desc
    "delete_path" -> R.string.baopu_tool_delete_path_desc
    "image_ops" -> R.string.baopu_tool_image_ops_desc
    "generate_image" -> R.string.baopu_tool_generate_image_desc
    // 小说工程三件（第一百五十五批）
    "novel_create" -> R.string.baopu_tool_novel_create_desc
    "novel_project" -> R.string.baopu_tool_novel_project_desc
    "novel_continue" -> R.string.baopu_tool_novel_continue_desc
    "ask_user" -> R.string.baopu_tool_ask_user_desc
    "run_subtasks" -> R.string.baopu_tool_run_subtasks_desc
    else -> throw IllegalArgumentException("未知抱朴工具「$name」：开关副标题映射漏了，请补 baopuToolDesc")
}

/** 第一百六十五批：人设包预览——正文逐字显示，外加一句「会改变什么」。 */
@Composable
private fun PersonaPackPreviewDialog(pack: PersonaPackPayload, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.persona_pack_preview_title, pack.name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.persona_pack_preview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = pack.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.persona_pack_close))
                    }
                }
            }
        }
    }
}

/** 第一百六十五批：新建/编辑人设包（名字在编辑时锁死——改名＝另建一份）。 */
@Composable
private fun PersonaPackEditorDialog(
    initialName: String,
    initialText: String,
    nameLocked: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> PersonaPackResult,
    onSaved: (PersonaPackPayload) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var body by remember { mutableStateOf(initialText) }
    var error by remember { mutableStateOf<String?>(null) }
    val res = LocalResources.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = if (nameLocked) {
                        stringResource(R.string.persona_pack_editor_edit, initialName)
                    } else {
                        stringResource(R.string.persona_pack_editor_new)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.persona_pack_name)) },
                    singleLine = true,
                    enabled = !nameLocked,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(stringResource(R.string.persona_pack_text)) },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = zhengdaoErrorText(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.persona_pack_cancel))
                    }
                    TextButton(onClick = {
                        when (val r = onSave(name, body)) {
                            is PersonaPackResult.Ok -> onSaved(r.pack)
                            is PersonaPackResult.Rejected -> error = personaPackErrorText(res, r)
                        }
                    }) {
                        Text(stringResource(R.string.persona_pack_save))
                    }
                }
            }
        }
    }
}

/** 第一百六十五批：删除确认——只删这套包的两个自有文件，日记/产物/记忆不动。 */
@Composable
private fun PersonaPackDeleteDialog(
    pack: PersonaPackSummary,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.persona_pack_delete_title, pack.name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.persona_pack_delete_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.persona_pack_cancel))
                    }
                    TextButton(onClick = onConfirm) {
                        Text(stringResource(R.string.persona_pack_delete))
                    }
                }
            }
        }
    }
}

/** 可读错误 → 一句人话（数据层只给分类，文案全在这边）。 */
private fun personaPackErrorText(
    res: android.content.res.Resources,
    r: PersonaPackResult.Rejected,
): String = when (r.error) {
    PersonaPackError.NAME_INVALID -> res.getString(R.string.persona_pack_err_name)
    PersonaPackError.EMPTY -> res.getString(R.string.persona_pack_err_empty)
    PersonaPackError.TOO_LARGE -> res.getString(R.string.persona_pack_err_too_large)
    PersonaPackError.BAD_UTF8 -> res.getString(R.string.persona_pack_err_utf8)
    PersonaPackError.NOT_FOUND -> res.getString(R.string.persona_pack_err_not_found)
    PersonaPackError.IO -> res.getString(R.string.persona_pack_err_io, r.detail.ifBlank { "io" })
}

/** SAF 打开的文件名（导入时只借它起目录名；读不到就用界面给的后备名）。 */
private fun queryOpenDisplayName(ctx: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        ctx.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
