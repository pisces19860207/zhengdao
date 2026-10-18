// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
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
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.ModelParams
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaRules
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.kernel.KernelAudit
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.FilterChip2
import com.example.zhengdao.ui.InfoRow
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.SettingsSubPage
import com.example.zhengdao.ui.WorkspaceFolderPicker
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.ui.theme.iosSwitchColors
import com.example.zhengdao.ui.theme.zhengdaoErrorText

/** 抱朴设置页（第十八批 A 段）：三块归位 ＋ read/bash 只读说明 ＋ 思考深度提示。 */
@Composable
internal fun BaopuSettingsScreen(onDismiss: () -> Unit, onOpenSessionLibrary: () -> Unit = {}) {
    val ctx = LocalContext.current
    // 承载方式（2026-10-18 真机修正）：必须用 **Dialog 全屏**，不能直接摆在 BaopuScreen 的
    // Column 里——实测那样只在对话与输入框之间挤出一条，聊天还露在外面上下两端。
    // 与 BaopuExtractSheet 同一条路子（Dialog 是真窗口），宽度默认受限故关掉 usePlatformDefaultWidth。
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SettingsSubPage(title = stringResource(R.string.baopu_drawer_settings), onBack = onDismiss) {

            // ── 抱朴工具（刀 2 追加包：工具集「表＋开关」的开关侧）──（自 SettingsDataActivity 原样搬入）
            // 定位：抱朴页里那个 agent 能用哪些工具，由用户在这里逐项决定。
            // 三条口径（派工单原话级）：
            // 1. 关掉的工具**不进发给模型的 tools 数组**——模型根本看不见；
            // 2. 模型若仍幻觉调用被关的工具，回一条工具结果「该工具已被用户关闭」，
            //    **不许自动打开**；
            // 3. 默认五个全启用（read_file / list_dir / search_in_files / file_info / write_file）。
            var toolTick by remember { mutableStateOf(0) }
            SectionCard(stringResource(R.string.baopu_tools_card)) {
                Text(
                    text = stringResource(R.string.baopu_tools_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                // 注册表持 ToolPrefs 单例（开关是热的：改完下一轮请求的 tools 数组就变）。
                // isEnabled/setDisabled 都现读 SP ⇒ 这里不必 remember(toolTick)，
                // tick 只负责在切完那一击触发重组（进程级单例的状态 Compose 本来就看不见）。
                val toolRegistry = remember {
                    BaopuRuntime.registry(
                        ctx,
                        BaopuRuntime.provider(ctx),
                    )
                }
                BaopuToolCatalog.ENTRIES.forEach { entry ->
                    val on = toolTick.let { toolRegistry.isEnabled(entry.name) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(baopuToolLabel(entry.name)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(baopuToolDesc(entry.name)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = on,
                            onCheckedChange = { want ->
                                toolRegistry.setDisabled(entry.name, !want)
                                toolTick++
                            },
                            colors = iosSwitchColors(),
                        )
                    }
                }
                // ── read / bash（第十八批 §2.1-4：只读展示「由通道决定」）──
                // 这两个名字不在 [BaopuToolCatalog.ENTRIES] 里（那是引擎侧的免费通道兼容
                // 别名：read 跟随 read_file 的开关，bash 恒拒），所以没有开关可给——
                // 在此明说，不再「模型看得见、用户管不着」。
                Spacer(Modifier.height(6.dp))
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

            // ── 技能与索引审阅（第十九批 §2.5：第一版只做看和删）──
            // 删索引＝用户本人操作即确认（确认制最高一级），但**仍走唯一合法写盘口**
            // PersonaFiles.applyProposal（PENDING 提案），不另开写文件的路。
            // 只删索引行，不删技能文件（§2.5：文件由用户在文件管理器自己删）。
            SectionCard(stringResource(R.string.skill_review_card)) {
                var skillReload by remember { mutableStateOf(0) }
                val pf = remember { BaopuRuntime.personaFiles(ctx) }
                val bText = remember(skillReload) { pf.read(PersonaLayer.B_GLOBAL) }
                val cText = remember(skillReload) { pf.read(PersonaLayer.C_PROJECT) }
                val globalLines = remember(skillReload) {
                    SkillRules.parseIndexLines(bText, MemoryScope.GLOBAL)
                }
                val projectLines = remember(skillReload) {
                    SkillRules.parseIndexLines(cText, MemoryScope.PROJECT)
                }
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
                                    ctx.getString(R.string.skill_new_saved, result.file.parentFile?.name ?: "")
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
                            ctx.getString(R.string.skill_restore_done)
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
                        ctx.getString(R.string.skill_reason_manual),
                    ) ?: return
                    pf.applyProposal(p)
                    skillReload++
                }

                Text(
                    text = stringResource(R.string.skill_group_global),
                    style = MaterialTheme.typography.labelLarge,
                )
                globalLines.forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(line.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                line.scenario,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                projectLines.forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(line.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                line.scenario,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                Spacer(Modifier.height(6.dp))
                // ── 第三十二批 A 段 · 草稿（试用中）──────────────────────────────
                // 23A 把 status／uses／canPromote 全算完了，界面上一个都没露（派工单 §0）。
                // 这一段只**读**数据层算好的数；写盘只有 commitSkillDraft → SkillStore.apply 一条路（T4）。
                val globalRoot = File(ctx.filesDir, "抱朴记忆")
                val projectRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(ctx)
                val draftRows = remember(skillReload) { draftSkillRows(projectRoot, globalRoot) }

                /** 「用一次」：uses+1 写回，仍是草稿（能不能转正继续由数据层判）。 */
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
                        is com.example.zhengdao.baopu.SkillWriteResult.Written ->
                            ctx.getString(R.string.skill_draft_used, row.name)
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
                            if (r.indexUpdated) ctx.getString(R.string.skill_draft_promoted, row.name)
                            else ctx.getString(R.string.skill_draft_index_full, SkillRules.INDEX_CAP)
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
 * catalog 加新工具时这里同步加一行——漏了会直接抛 IllegalArgumentException（fail-fast）。
 */
private fun baopuToolLabel(name: String): Int = when (name) {
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
    // 第十七批 A+B 段加的 update_plan：当时没同步这张表 ⇒ 2026-10-18 真机开本页直接崩
    // （IllegalArgumentException: 未知抱朴工具「update_plan」）。回归网见 BaopuSettingsGuardTest 的
    // 「catalog 每个工具都有标签/副标题」那条。
    "update_plan" -> R.string.baopu_tool_update_plan
    "write_skill" -> R.string.baopu_tool_write_skill
    "library_list" -> R.string.baopu_tool_library_list
    "library_organize" -> R.string.baopu_tool_library_organize
    "delete_path" -> R.string.baopu_tool_delete_path
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
    "update_plan" -> R.string.baopu_tool_update_plan_desc
    "write_skill" -> R.string.baopu_tool_write_skill_desc
    "library_list" -> R.string.baopu_tool_library_list_desc
    "library_organize" -> R.string.baopu_tool_library_organize_desc
    "delete_path" -> R.string.baopu_tool_delete_path_desc
    else -> throw IllegalArgumentException("未知抱朴工具「$name」：开关副标题映射漏了，请补 baopuToolDesc")
}
