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

import androidx.compose.foundation.clickable
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
import com.example.zhengdao.baopu.PersonaBackup
import com.example.zhengdao.baopu.PersonaBackupResult
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaRules
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupTable
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
                // 开关存储（热读热写：改完下一轮请求的 tools 数组就变）。
                // 第五十九批 §1.1/§1.6 两处改动：
                // 1. **这里不再造注册表**。以前写的是 `BaopuRuntime.registry(ctx, provider)`，
                //    而注册表装配时会把全局 `CapabilitiesTool.sink` 指到自己身上——设置页
                //    这一张是"只为读开关"的临时注册表，`currentSessionId` 还是 NO_SESSION，
                //    于是用户开过一次设置页之后，模型 `capabilities(enable=…)` 就把解锁写进
                //    -1 那一格（§4 打回第三条"跨会话串状态"的另一种犯法）。开关的语义
                //    `isEnabled(n) == !prefs.isDisabled(n)` 完全等价 ⇒ 直接读 prefs，
                //    一张表、一个存储，注册表留给引擎自己装配。
                // 2. **分组标题与成员都从那张唯一的分组表读**（[ToolGroupTable]），
                //    原来这里的 `BaopuToolGroups.GROUPS` 是第二张组表（纯展示也算第二张，
                //    §1.1 不认），已删。
                // 状态仍在 SP 里（进程级），Compose 看不见 ⇒ tick 只负责切完那一击触发重组。
                val prefs = remember { BaopuRuntime.toolPrefs(ctx) }
                // ── 渐进披露的一句解释（§1.6）──
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.baopu_tools_progressive_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                // COMPAT（read / bash）不在这里列：它由通道决定、没有开关，
                // 卡片尾部那两行只读说明（第十八批 §2.1-4）仍是它们的唯一展示位。
                ToolGroup.values().filter { it != ToolGroup.COMPAT }.forEachIndexed { gi, group ->
                    if (gi > 0) Spacer(Modifier.height(10.dp))
                    val groupOn = group !in prefs.disabledGroups()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(baopuGroupTitle(group)),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(
                                    if (group in ToolGroup.DEFAULT_VISIBLE) {
                                        R.string.baopu_tools_group_default_on
                                    } else {
                                        R.string.baopu_tools_group_default_off
                                    },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 组开关（§1.6「与单工具开关并存」）：关掉 ⇒ 组内一律不可见，
                        // 且 capabilities 也解不开（判据只有 isVisibleTool 那一处）。
                        Switch(
                            checked = groupOn,
                            onCheckedChange = { want ->
                                prefs.setGroupDisabled(group, !want)
                                toolTick++
                            },
                            colors = iosSwitchColors(),
                        )
                    }
                    ToolGroupTable.namesOf(group).forEach { name ->
                        val on = toolTick.let { !prefs.isDisabled(name) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(baopuToolLabel(name)),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(baopuToolDesc(name)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = on,
                                onCheckedChange = { want ->
                                    prefs.setDisabled(name, !want)
                                    toolTick++
                                },
                                colors = iosSwitchColors(),
                            )
                        }
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

            // ── 第三十七批 D 段 · 人设回滚（B 全局 / C 本项目各一行）──
            // 写入前的备份已经在唯一写盘口做完（PersonaFiles.applyProposal），
            // 这里只负责「滚回去」：点了先备份当前版，再覆盖 —— 可反悔。
            // 没有备份时**按钮灰掉但这一行不消失**（判据 L6 最后一句：灰掉≠假装没这功能）。
            SectionCard(stringResource(R.string.persona_rollback_card)) {
                var rollbackTick by remember { mutableStateOf(0) }
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
                                    is PersonaBackupResult.Done -> ctx.getString(
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

            // ── 技能与索引审阅（第十九批 §2.5：第一版只做看和删）──
            // 删索引＝用户本人操作即确认（确认制最高一级），但**仍走唯一合法写盘口**
            // PersonaFiles.applyProposal（PENDING 提案），不另开写文件的路。
            // 只删索引行，不删技能文件（§2.5：文件由用户在文件管理器自己删）。
            SectionCard(stringResource(R.string.skill_review_card)) {
                var skillReload by remember { mutableStateOf(0) }
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
                // ── 第三十六批 B 段 · 技能分类（旁挂 `_分类.json`，SKILL.md 一个字不动）──
                // 分类**只影响显示**：它不改索引行文本、不改正文、不改人设（判据 T5）。
                // 两层各一份表（全局那份在 App 私有、项目那份在工作区），互不串。
                // 坏 JSON 由纯函数当空表（corrupt=true），这里只补一句红字，页面不许崩。
                var catTick by remember { mutableStateOf(0) }
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

                /** 作用域 → 人设层（A/B 段共用，与上面 `skillFileOf` 同一口径）。 */
                fun layerOf(scope: SkillScope): PersonaLayer = when (scope) {
                    SkillScope.GLOBAL -> PersonaLayer.B_GLOBAL
                    SkillScope.PROJECT -> PersonaLayer.C_PROJECT
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
                            RestoredIndex.Added -> ctx.getString(R.string.skill_archived_restored, o.name)
                            RestoredIndex.OverCap -> ctx.getString(R.string.skill_archived_index_full, SkillRules.INDEX_CAP)
                            RestoredIndex.BadName -> ctx.getString(R.string.skill_archived_index_skipped, o.name)
                        }
                        is ArchivedOutcome.Occupied -> ctx.getString(R.string.skill_archived_occupied, o.target)
                        is ArchivedOutcome.IoFailed -> ctx.getString(R.string.skill_archived_io_failed, o.target, o.why)
                        is ArchivedOutcome.NeedsConfirm -> ctx.getString(R.string.skill_archived_confirm, o.target)
                        is ArchivedOutcome.Deleted -> ctx.getString(R.string.skill_archived_deleted, o.target)
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
                            // 第五十七批 §1.2：待重验徽标（只提示不处置；数据来自 skill_event 留痕）
                            globalRecheck[line.name]?.let { why ->
                                Text(
                                    text = stringResource(R.string.skill_recheck_badge, why),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = zhengdaoErrorText(),
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
                            // 第五十七批 §1.2：待重验徽标（只提示不处置；数据来自 skill_event 留痕）
                            projectRecheck[line.name]?.let { why ->
                                Text(
                                    text = stringResource(R.string.skill_recheck_badge, why),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = zhengdaoErrorText(),
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
                                    ctx.getString(R.string.skill_cat_saved, o.name, ctx.getString(o.category.labelRes))
                                is CategoryOutcome.Rejected -> ctx.getString(R.string.skill_cat_rejected, o.name)
                                is CategoryOutcome.IoFailed ->
                                    ctx.getString(R.string.skill_cat_io_failed, o.name, o.why)
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
                // （两层根目录 globalRoot／projectRoot 已在卡片开头声明，34 批上移——详情与归档共用。）
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
                            restoreLast(layerOf(d.scope), d.name)
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
                                    ctx.getString(R.string.skill_edit_saved)
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
 * 设置页分组标题（第五十九批 §1.1）。
 *
 * 只有「组 → 标题字符串」这一小段映射留在 UI 层，**成员表不在这**——
 * 组里有哪些工具由 [ToolGroupTable.namesOf] 出（那张唯一的分组表）。
 * 原来这里是一份 `BaopuToolGroups.GROUPS`（组名＋成员各写一遍），它就是派工单
 * 禁止的第二张组表：改了工具忘了改它，界面会显示一个不存在的分组。
 *
 * 新增组时会直接抛 IllegalArgumentException（fail-fast），与 baopuToolLabel 同一口径。
 */
private fun baopuGroupTitle(group: ToolGroup): Int = when (group) {
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
    // 第十七批 A+B 段加的 update_plan：当时没同步这张表 ⇒ 2026-10-18 真机开本页直接崩
    // （IllegalArgumentException: 未知抱朴工具「update_plan」）。回归网见 BaopuSettingsGuardTest 的
    // 「catalog 每个工具都有标签/副标题」那条。
    "update_plan" -> R.string.baopu_tool_update_plan
    "write_skill" -> R.string.baopu_tool_write_skill
        "append_persona" -> R.string.baopu_tool_append_persona
    "library_list" -> R.string.baopu_tool_library_list
    "library_organize" -> R.string.baopu_tool_library_organize
    "delete_path" -> R.string.baopu_tool_delete_path
    "image_ops" -> R.string.baopu_tool_image_ops
    "ask_user" -> R.string.baopu_tool_ask_user
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
    "update_plan" -> R.string.baopu_tool_update_plan_desc
    "write_skill" -> R.string.baopu_tool_write_skill_desc
        "append_persona" -> R.string.baopu_tool_append_persona_desc
    "library_list" -> R.string.baopu_tool_library_list_desc
    "library_organize" -> R.string.baopu_tool_library_organize_desc
    "delete_path" -> R.string.baopu_tool_delete_path_desc
    "image_ops" -> R.string.baopu_tool_image_ops_desc
    "ask_user" -> R.string.baopu_tool_ask_user_desc
    else -> throw IllegalArgumentException("未知抱朴工具「$name」：开关副标题映射漏了，请补 baopuToolDesc")
}
