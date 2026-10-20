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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.zhengdao.baopu.agent.ToolGroupAudit
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
                //
                // ── 第六十一批 §1：组头可点展开/收起，默认折叠 ────────────────────────
                // 三条口径（都是"纯展示"，越线就算打回）：
                // 1. 折叠**不参与任何开关判定**：下面 `prefs.setGroupDisabled` /
                //    `prefs.setDisabled` 两个写入点、以及 `isDisabled` / `disabledGroups`
                //    两个读取点全部一行没动；默认值与 `DEFAULT_ENABLED` 也不在这里。
                //    收起只是把成员藏起来，关掉的工具照样不进 tools 数组、开着的照样进。
                // 2. 折叠态**必须**留一行「关闭项摘要」（[BaopuToolFoldSummary.of]）——
                //    用户关掉的工具不许因为收起而查不到；没有关闭项就写「全部开启」。
                // 3. 折叠状态用 `rememberSaveable(group.key)`：本次页面存活期记住、
                //    冷启动回默认折叠。用 `remember` 会连旋转/回前台都丢（收起一半又弹开），
                //    存 SP 又把「UI 姿势」当配置（跨会话串状态那一类雷，59 批刚踩过）。
                val foldNote = stringResource(R.string.baopu_tools_fold_note)
                Text(
                    text = foldNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                baopuSwitchableGroups(ToolGroup.values()).forEachIndexed { gi, group ->
                    if (gi > 0) Spacer(Modifier.height(10.dp))
                    val expanded = rememberSaveable(group.key) { mutableStateOf(false) }
                    // 第九十七批：组状态四态（默认可见/已锁/已长期同意/已关闭）。
                    // 上一版「已锁」在界面上显示成「开」，用户看到的就是「工具全都打开的」。
                    val state = baopuGroupState(
                        defaultVisible = group in ToolGroup.DEFAULT_VISIBLE,
                        approved = group in prefs.approvedGroups(),
                        disabled = group in prefs.disabledGroups(),
                    )
                    val groupOn = baopuGroupSwitchOn(state)
                    // 成员名单只从那张唯一的分组表读（§1「不新造第二张表」）；
                    // 摘要与展开后的开关共用这一个 members，两行说的一定是同一批工具。
                    val members = ToolGroupTable.namesOf(group)
                    // 「关闭项摘要」只描述**用户逐个关掉的工具**：已锁（没同意）不代表工具被关，
                    // 所以只有 Disabled 才按整组关光算（否则会把「已锁」误报成「全部关闭」）。
                    val summary = BaopuToolFoldSummary.of(members, groupDisabled = state == BaopuGroupState.Disabled) {
                        prefs.isDisabled(it)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 组头整行可点＝展开/收起。右边的组开关自己吃点击事件、
                            // 不会顺带折叠（嵌套 clickable：子级消费掉，父级收不到）。
                            .clickable { expanded.value = !expanded.value },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(baopuGroupTitle(group)),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(baopuGroupCaptionKey(state)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 箭头用既成键（§1「能复用就复用」）：common_expand_arrow / common_collapse_arrow
                        Text(
                            text = stringResource(
                                if (expanded.value) R.string.common_collapse_arrow else R.string.common_expand_arrow,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 组开关＝唯一主开关（§3.5）：开 ⇒ 长期同意（默认组就是解除禁用）；
                        // 关 ⇒ 立即禁用整组＋会话内已解锁的一并失效。卡片上的「一直」写的是
                        // 同一个 approved 档，所以两处状态永远一致（审计来源不同：卡片/设置页）。
                        Switch(
                            checked = groupOn,
                            onCheckedChange = { want ->
                                prefs.setGroupApproved(group, want && group !in ToolGroup.DEFAULT_VISIBLE)
                                prefs.setGroupDisabled(group, !want)
                                if (!want) BaopuRuntime.toolUnlocks(ctx).relockAll(listOf(group))
                                RunLog.log(ToolGroupAudit.manual(group, want))
                                toolTick++
                            },
                            colors = iosSwitchColors(),
                        )
                    }
                    // 折叠态常驻的「关闭项摘要」——展开时不显示（成员就在下面，看得见）
                    if (!expanded.value) {
                        Text(
                            text = baopuFoldSummaryText(summary),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (expanded.value) {
                        members.forEach { name ->
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
                            ctx.getString(R.string.skill_draft_used, row.name)
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
 * 折叠态那一行「关闭项摘要」的文案（第六十一批 §1）。
 *
 * 名单怎么算在 [BaopuToolFoldSummary.of]（零 Android、直接打）；这里只把名字换成
 * 本地化的工具标签——**标签取的还是 baopuToolLabel 那一份映射**，一个工具在展开的
 * 开关行与收起的摘要行里必须是同一个名字，否则「摘要」就成了另一套话术。
 */
@Composable
private fun baopuFoldSummaryText(summary: BaopuFoldSummary): String {
    val sep = stringResource(R.string.baopu_tools_fold_list_sep)
    return when (summary.foldKind) {
        BaopuFoldKind.EmptyGroup -> stringResource(R.string.baopu_tools_fold_empty)
        BaopuFoldKind.AllOn -> stringResource(R.string.baopu_tools_fold_all_on)
        // 整组关光（组开关关掉的、或逐个关到零的）：名单全列出来比只报个数有用吗？
        // 这里报个数——一组的成员名整排会撑成好几行，而"这一组一个都别想用"这件事
        // 组开关那个位置已经写明白了。
        BaopuFoldKind.AllOff -> stringResource(R.string.baopu_tools_fold_all_off, summary.total)
        BaopuFoldKind.SomeOff -> stringResource(
            R.string.baopu_tools_fold_some_off,
            summary.offNames.size,
            summary.offNames.map { stringResource(baopuToolLabel(it)) }.joinToString(sep),
        )
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
