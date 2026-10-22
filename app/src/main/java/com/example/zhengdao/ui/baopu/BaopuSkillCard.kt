// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十九批 §2.2（写索引三路全过确认）与 §2.5（审阅第一版只做看和删）。
// 房型照 BaopuPendingMemoryCard：候选未落盘，用户拍板才走 applyProposal。
//
// 第三十二批（2026-10-18 派工单）在本文件里加了**试用期界面的消费点**：草稿读盘
// `draftSkillRows`、「用一次／转正」的唯一写口 `commitSkillDraft`、新建框的
// 「用平台机制模板」`platformTemplateBody`。数据层（SkillStore／SkillRules）本批一个字没动，
// 这里只做「已经能算出来的事，界面上终于露出来」——派工单 §0 原话。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuGuardCore
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaProposal
import com.example.zhengdao.baopu.PersonaRules
import com.example.zhengdao.baopu.SkillProposal
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import java.io.File

/**
 * 技能提案卡的判据（UI 第二批 §2 点名：这张卡「只显示正文最后一行非空」＝静默截断，
 * 而且 `proposals.forEach` 没有上限，条目一多把对话挤没）。
 *
 * 三件事各归一位：**标题**＝技能名，由数据层 [SkillRules.parseIndexLines] 解析出来——
 * UI 不自己切字符串（切法一多，同一个技能在两张卡上会显示成两个名字）；
 * **摘要**＝这句索引的适用场景，解析不出索引行时退回提案理由「为什么改」，不留空；
 * **还有没有正文可看**＝除那一行之外还有别的非空行——没有就不许摆个假入口。
 */

/** 提案层 ⇒ 技能索引的作用域（解析器要它；A 内核不收索引行，兜底按全局）。 */
internal fun personaScopeOf(layer: PersonaLayer): MemoryScope = when (layer) {
    PersonaLayer.C_PROJECT -> MemoryScope.PROJECT
    else -> MemoryScope.GLOBAL
}

/** 提案正文里的索引行（多条时取第一条：一张卡说的是一个技能）。解析不出 ⇒ null。 */
private fun skillProposalIndexLine(p: PersonaProposal): SkillRules.IndexLine? =
    SkillRules.parseIndexLines(p.proposed, personaScopeOf(p.layer)).firstOrNull()

/**
 * 标题：解析得出用技能名，解析不出用那一行原文——宁可土，不猜。
 * 两种都过 [withoutBoldMarkers]：显示层不露 `**`（4a8ca5b2 那条口径），**存盘文本一个字不动**。
 */
internal fun skillProposalTitle(p: PersonaProposal): String {
    val line = skillProposalIndexLine(p)
    if (line != null) return withoutBoldMarkers(line.name)
    return withoutBoldMarkers(p.proposed.lines().lastOrNull { it.isNotBlank() }.orEmpty())
}

/** 可读摘要：索引行的适用场景那句；不是索引行的提案 ⇒ 提案理由（用户审的就是它）。同样只去显示层的 `**`。 */
internal fun skillProposalSummary(p: PersonaProposal): String {
    val line = skillProposalIndexLine(p)
    if (line != null) return withoutBoldMarkers(line.scenario)
    return withoutBoldMarkers(p.reason)
}

/** 正文里除了标题那一行还有别的内容 ⇒ 才有「展开」可点。 */
internal fun skillProposalHasMore(p: PersonaProposal): Boolean =
    p.proposed.lines().count { it.isNotBlank() } > 1

/**
 * 技能索引提案卡（第十九批 §2.2）：封卷提炼／启动补算产出的 PENDING 提案，
 * 用户「采纳」⇒ 写进人设对应层，「丢弃」⇒ 只移除。**采纳之前人设一个字都不动**（K4）。
 *
 * 卡上注明提案来路：用户明说的（「记住这个流程」）写「这是你说的」，其余写「会话封卷提炼」
 * ——确认制要求用户审的就是「为什么改」（[PersonaProposal.reason] 已带，这里再亮一遍层）。
 *
 * UI 第二批 §2：每条给「技能名＋可读摘要」（不再只甩最后一行），长正文按条展开，
 * 超过 [CARD_VISIBLE_LIMIT] 条折叠成「还有 N 条」。
 */
@Composable
fun BaopuSkillProposalCard(
    proposals: List<PersonaProposal>,
    onConfirm: (PersonaProposal) -> Unit,
    onDiscard: (PersonaProposal) -> Unit,
) {
    if (proposals.isEmpty()) return
    // 两个开关：① 列表折叠（「还有 N 条」）；② 每条自己的正文展开。
    // ②按提案对象记——PersonaProposal 是 data class，内容完全相同的两条会一起展开，
    // 它们在卡上本来就长得一模一样，不做多余区分。
    var listExpanded by remember { mutableStateOf(false) }
    var openedProposals by remember { mutableStateOf(setOf<PersonaProposal>()) }
    // 第一百三十七批：行动卡档（四边 2dp 黛青＋浅青标题条）——「等你同意」是行动卡判据
    BaopuActionContainer(
        title = stringResource(R.string.baopu_tier_action_skill),
        badge = stringResource(R.string.baopu_tier_action_badge),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.skill_proposal_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            val visible = visibleItems(proposals, expanded = listExpanded, limit = CARD_VISIBLE_LIMIT)
            val hidden = moreCount(total = proposals.size, shown = visible.size)
            visible.forEach { p ->
                val layerLabel = stringResource(
                    if (p.layer == PersonaLayer.B_GLOBAL) R.string.skill_layer_global
                    else R.string.skill_layer_project,
                )
                val opened = p in openedProposals
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = layerLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = skillProposalTitle(p),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        text = skillProposalSummary(p),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // 展开态才给正文全貌（收起态只上面那两行，卡不撑爆对话）。
                    if (opened) {
                        Text(
                            text = withoutBoldMarkers(p.proposed),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (skillProposalHasMore(p)) {
                        Text(
                            text = stringResource(
                                if (opened) R.string.common_collapse_arrow else R.string.common_expand_arrow,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable {
                                    openedProposals =
                                        if (opened) openedProposals - p else openedProposals + p
                                },
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        TextButton(onClick = { onConfirm(p) }) {
                            Text(stringResource(R.string.skill_proposal_accept))
                        }
                        OutlinedButton(onClick = { onDiscard(p) }) {
                            Text(stringResource(R.string.skill_proposal_discard))
                        }
                    }
                }
            }
            if (hidden > 0) {
                MoreItemsRow(
                    hidden = hidden,
                    expanded = listExpanded,
                    onToggle = { listExpanded = !listExpanded },
                )
            }
        }
    }
}

/**
 * 「新建技能」两屏对话框（第二十三批 A 段）。
 *
 * ## 为什么是两屏而不是一个表单直接写
 *
 * 铁律要求「**必过确认卡**」：卡上得看到 名字／落点／正文前 200 字／字数／备份文件名。
 * 所以第一屏是编辑器（改），第二屏是确认（审），**审的那一屏不显示全正文**——
 * 全正文会把卡撑爆，也不是这个卡要解决的问题（它要的是「你要写的确实是这个」）。
 *
 * 保存仍走 [SkillStore.apply]：备份 → 写正文 → （正式技能才）写索引。
 * 草稿默认勾上：新技能先进试用期，**不进人设索引**，模型默认看不到它。
 *
 * ## 第三十四批 C 段：同一个框也用来**改**（不另开一个编辑框）
 *
 * `lockedName != null` ⇒ 这是「编辑既有技能」：技能名与作用域**锁定不可改**（改名＝另建一份，
 * 原件还在原地被用，那是最坏的结果），草稿勾选也锁定成盘上那个状态（改了状态等于偷偷转正/降级）。
 * 保存仍然**只有写盘那一条路**，编辑走 32 批那条 [commitSkillDraft]（它内部就是 `SkillStore.apply`，
 * 所以本文件的写口数一个字没增），而且 [editedBody] 会**保住第一行的状态头**——
 * 改一次正文不该把试用次数清成 0。
 */
@Composable
internal fun SkillEditorDialog(
    onDismiss: () -> Unit,
    onSaved: (SkillWriteResult) -> Unit,
    projectRoot: java.io.File,
    globalRoot: java.io.File,
    personaFiles: com.example.zhengdao.baopu.PersonaFiles,
    backupStamp: String,
    lockedName: String? = null,
    initialBody: String = "",
    initialScope: SkillScope = SkillScope.PROJECT,
    initialDraft: Boolean = true,
    originalBody: String = "",
) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(lockedName.orEmpty()) }
    var body by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initialBody) }
    var scope by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initialScope) }
    var draft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initialDraft) }
    var reviewing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val proposal = SkillProposal(scope = scope, name = name, body = body, reason = "用户手写", draft = draft)
    // 索引条数由调用方在 apply 时再核一次；这里给 0 只为了把「是否超上限」的判据留在 apply 里。
    val plan = SkillStore.plan(proposal, projectRoot, globalRoot, backupStamp, indexCountAfterAdd = 0)

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp)) {
                if (!reviewing) {
                    Text(
                        stringResource(
                            // C 段：同一个框，标题按「新建／编辑」分；编辑时技能名是锁死的
                            if (lockedName == null) R.string.skill_new_title else R.string.skill_edit_title,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { if (lockedName == null) name = it },
                        label = { Text(stringResource(R.string.skill_new_name)) },
                        singleLine = true,
                        enabled = lockedName == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (lockedName != null) {
                        Text(
                            text = stringResource(R.string.skill_name_locked_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { scope = SkillScope.PROJECT },
                            // 编辑时落点也锁死：换作用域＝在另一层另建一份，原件还留在原处
                            enabled = lockedName == null,
                        ) {
                            Text(
                                stringResource(R.string.skill_new_scope_project),
                                color = if (scope == SkillScope.PROJECT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { scope = SkillScope.GLOBAL },
                            enabled = lockedName == null,
                        ) {
                            Text(
                                stringResource(R.string.skill_new_scope_global),
                                color = if (scope == SkillScope.GLOBAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text(stringResource(R.string.skill_new_body)) },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // 第三十二批 B 段：平台机制模板**预填**。点了只往正文框里灌文本，
                    // 技能名留空由用户填；写盘仍然只有「下一步：确认 → 保存」那一条路（判据 T5）。
                    OutlinedButton(onClick = { body = platformTemplateBody() }) {
                        Text(stringResource(R.string.skill_use_platform_template))
                    }
                    Text(
                        text = stringResource(R.string.skill_use_platform_template_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = draft,
                            onCheckedChange = { draft = it },
                            // 编辑时草稿与否＝盘上那个状态，点不动（要转正走草稿区的「转正」按钮）
                            enabled = lockedName == null,
                        )
                        Text(stringResource(R.string.skill_new_draft), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(stringResource(R.string.skill_confirm_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.skill_confirm_name, plan.proposal.name))
                    Text(
                        stringResource(
                            R.string.skill_confirm_scope,
                            stringResource(
                                if (scope == SkillScope.PROJECT) R.string.skill_new_scope_project else R.string.skill_new_scope_global,
                            ),
                        ),
                    )
                    Text(stringResource(R.string.skill_confirm_chars, plan.charCount))
                    Text(stringResource(R.string.skill_confirm_backup, plan.backupName ?: "—"))
                    Spacer(Modifier.height(8.dp))
                    Text(plan.preview, style = MaterialTheme.typography.bodySmall)
                    plan.blocked?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = com.example.zhengdao.ui.theme.zhengdaoErrorText())
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.skill_proposal_discard)) }
                    Spacer(Modifier.width(8.dp))
                    if (!reviewing) {
                        TextButton(onClick = { reviewing = true }) { Text(stringResource(R.string.skill_new_review)) }
                    } else {
                        TextButton(
                            enabled = plan.blocked == null,
                            onClick = {
                                val result = if (lockedName != null) {
                                    // C 段：编辑**不另开写口**——回到 32 批那条 commitSkillDraft（它内部就是
                                    // SkillStore.apply，写前照样备份）。row.file 给的是盘上那个真实目录，
                                    // 于是「安全化之后落点变了」那道闸照样生效：脏名字会被拒绝，不会另建一份。
                                    commitSkillDraft(
                                        row = SkillDraftRow(
                                            name = lockedName,
                                            scope = scope,
                                            file = skillBodyFile(lockedName, scope, projectRoot, globalRoot),
                                            body = originalBody,
                                            uses = SkillStore.usesOf(originalBody),
                                            canPromote = false,
                                        ),
                                        newBody = editedBody(originalBody, body),
                                        stillDraft = draft,
                                        projectRoot = projectRoot,
                                        globalRoot = globalRoot,
                                        personaFiles = personaFiles,
                                        backupStamp = backupStamp,
                                    )
                                } else {
                                    val indexText = personaFiles.read(
                                        if (scope == SkillScope.GLOBAL) PersonaLayer.B_GLOBAL else PersonaLayer.C_PROJECT,
                                    ).orEmpty()
                                    SkillStore.apply(plan, personaFiles, indexText)
                                }
                                onSaved(result)
                                onDismiss()
                            },
                        ) { Text(stringResource(R.string.skill_proposal_accept)) }
                    }
                }
            }
        }
    }
}

// ── 第三十二批 A/B 段 · 试用期的**界面消费点**（纯函数＋一个写盘口）────────────
//
// 为什么这些函数放这儿而不是 BaopuSettingsScreen.kt：那个文件是「一页的排版」，
// 本批的判据 T1/T2/T3/T5 全都能在不启动 Compose 的情况下断言，就得让算式待在一个
// 能被 JVM 直接 import 的文件里。写盘口也只有 [commitSkillDraft] 一个，好取证。

/**
 * 一条**草稿（试用中）**技能在界面上的读数：名字、落点、文件、正文、已用次数、能不能转正。
 *
 * 三个数全来自数据层（判据 T2）：[uses] ＝ `SkillStore.usesOf(body)`、
 * [canPromote] ＝ `SkillStore.canPromote(body)`——UI **不许自己数**用的次数，
 * 也不许自己定「几次算够」（那个常量是 `SkillStore.PROMOTE_USES`）。
 */
internal data class SkillDraftRow(
    val name: String,
    val scope: SkillScope,
    val file: File,
    val body: String,
    val uses: Int,
    val canPromote: Boolean,
)

/**
 * 扫两层技能目录，**只收 status=draft 的**（判据 T1：正式技能不许混进草稿区）。
 *
 * 目录仍然取自 `SkillRules.projectSkillDir` / `SkillRules.globalSkillDir` 这两个唯一真源，
 * 本批不在 UI 里另起一套落点；两边同名各算各的行（两层本来就有同名两回事的先例，
 * 查找优先级是数据层的事，这里只做列表）。目录读不到／正文读不出 ⇒ 当没有，不抛。
 */
internal fun draftSkillRows(projectRoot: File, globalRoot: File): List<SkillDraftRow> {
    val out = mutableListOf<SkillDraftRow>()
    listOf(
        SkillScope.PROJECT to SkillRules.projectSkillDir(projectRoot),
        SkillScope.GLOBAL to SkillRules.globalSkillDir(globalRoot),
    ).forEach { (scope, dir) ->
        val subDirs = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        subDirs.forEach { sub ->
            val file = File(sub, SkillRules.SKILL_FILE)
            if (!file.isFile) return@forEach
            val body = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
            if (SkillStore.statusOf(body) != "draft") return@forEach // T1：正式技能不进这一段
            out += SkillDraftRow(
                name = sub.name,
                scope = scope,
                file = file,
                body = body,
                uses = SkillStore.usesOf(body),
                canPromote = SkillStore.canPromote(body),
            )
        }
    }
    return out
}

/**
 * 剥掉**第一行**的状态头（`<!-- status=… uses=… -->`），其余一字不动。
 *
 * 只认第一行、只认带 `status=` 的注释行：正文中段自己写了个 HTML 注释不该被吃掉。
 */
internal fun stripStatusHeader(body: String): String {
    val lines = body.lines()
    val head = lines.firstOrNull()?.trim().orEmpty()
    return if (head.startsWith("<!--") && head.contains("status=")) lines.drop(1).joinToString("\n") else body
}

/** 「用一次」要写回的新正文：uses+1、status 仍是 draft（算式在数据层，这里只转手）。 */
internal fun useOnceBody(body: String): String = SkillStore.bumpUses(body)

/** 「转正」要写回的新正文：头改成 status=active、uses 原样保留（判据 T3 的正文那一半）。 */
internal fun promoteBody(body: String): String =
    SkillStore.draftHeader(status = "active", uses = SkillStore.usesOf(body)) + "\n" + stripStatusHeader(body)

/**
 * 「用一次」／「转正」的**唯一写盘口**（判据 T4：正文只能经 `SkillStore.apply` 落地）。
 *
 * ## 为什么绕一圈 `plan(...)` 再 `copy(proposal = …)`
 *
 * `SkillStore.apply` 是全局唯一能写 SKILL.md 的地方（它还管写前备份），但它写正文时走
 * `withDraftHeader(提案)`：提案 `draft=true` 会把头**重置**成 `uses=0`——「用一次」的计数白加；
 * `draft=false` 才是「正文原样写」。而索引行那句「（草稿·未验证，用前先 read_file 核对）」
 * 又只有 `draft=true` 的 plan 才算得出来。
 *
 * 所以：索引行**问 draft=true 的 plan 要**（还是数据层算的，UI 一个字不拼，C 段口径），
 * 只把「要写出去的正文」换成已经算好新头的 [newBody]。两头都不作弊，也不重写它的判据。
 *
 * @param stillDraft true ⇒「用一次」（还是草稿，索引行继续带未验证标）；false ⇒「转正」
 */
internal fun commitSkillDraft(
    row: SkillDraftRow,
    newBody: String,
    stillDraft: Boolean,
    projectRoot: File,
    globalRoot: File,
    personaFiles: com.example.zhengdao.baopu.PersonaFiles,
    backupStamp: String,
): SkillWriteResult {
    val proposal = SkillProposal(
        scope = row.scope,
        name = row.name,
        body = newBody,
        reason = if (stillDraft) "草稿试用：记一次" else "草稿转正：正式进索引",
        draft = false, // ⇒ withDraftHeader 原样写 newBody（头已由 SkillStore 的纯函数算好）
    )
    val planned = SkillStore.plan(
        if (stillDraft) proposal.copy(draft = true) else proposal,
        projectRoot,
        globalRoot,
        backupStamp,
        indexCountAfterAdd = 0, // 同 23A 的口径：超上限的判据留在 addIndexLine 里
    )
    // 名字安全化之后落点变了 ⇒ 盘上那个目录名不是数据层认可的写法。
    // 硬写会在旁边**另建一份**、原件还在原地用，那是最坏的结果；宁可拒绝并说清楚。
    if (planned.file.absolutePath != row.file.absolutePath) {
        return SkillWriteResult.Rejected(
            "技能目录名「${row.name}」不是可用的技能名，先手动改名再点这个按钮",
        )
    }
    val layer = when (row.scope) {
        SkillScope.GLOBAL -> PersonaLayer.B_GLOBAL
        SkillScope.PROJECT -> PersonaLayer.C_PROJECT
    }
    return SkillStore.apply(
        planned.copy(proposal = proposal),
        personaFiles,
        personaFiles.read(layer).orEmpty(),
    )
}

/**
 * 「用平台机制模板」按钮要灌进正文框的文本（判据 T5：**只是文本，不碰盘**）。
 *
 * 单独抽一个函数不是为了套壳，是为了让「点之前点之后盘上什么都没有」这条
 * 能在 JVM 里用临时目录直接断言，而不是靠读注释相信它。
 */
internal fun platformTemplateBody(): String = SkillStore.PLATFORM_SKILL_TEMPLATE

// ── 第三十四批 A/B/C 段 · 技能**能看见、能改、能找回**（只动 UI）─────────────────
//
// 派工单 §0：23A 让技能能写了、32 批让草稿能试用了，但**写完就看不见里面**——
// 索引行只有名字＋一句话，正文在盘上；删掉的技能进了 `.deleted-*` 就**再没有入口**；
// 写错了正文只能删了重写。本批三件事：详情（A）·归档找回（B）·编辑（C）。
//
// 与 32 批同一分工：**算**的事全做成不启动 Compose 也能断言的纯函数，落点／状态／次数／
// 索引句子一律问数据层要（`SkillStore.statusOf/usesOf/backupsOf/plan`、`SkillRules.*`），
// UI 一个字不重算、一句人不拼（判据 U6：界面文案全在 strings.xml）。
// 唯一例外是**归档的恢复与彻底删除**：数据层没有这两个 API（`SkillStore` 故意不做删除），
// 而红线不许动 `baopu/**` ⇒ 只能在白名单的 UI 文件里用 `renameTo`／`delete` 做。
// 两处都只做「搬文件」，不写正文，所以 U4（正文只能经 `SkillStore.apply` 落地）仍然成立。

/** 归档件文件名里那段标记：`SKILL.md.deleted-<时间戳>`（[SkillStore.archive] 的产物）。 */
internal const val ARCHIVED_MARK: String = ".deleted-"

/** 归档件的完整前缀——只认这个形状，别把 `.bak-` 的备份也当归档扫进来。 */
internal const val ARCHIVED_PREFIX: String = SkillRules.SKILL_FILE + ARCHIVED_MARK

/**
 * 技能正文在盘上的**真实**位置（按目录名原样拼，**不经过**名字安全化）。
 *
 * 为什么要绕开安全化：32 批那道闸比较的就是「盘上那个目录」与「数据层认可的目录」，
 * 只有拿原始名字拼出来的路径才比得出差异，脏名字才会被拒绝而不是在旁边另建一份。
 */
internal fun skillBodyFile(name: String, scope: SkillScope, projectRoot: File, globalRoot: File): File {
    val dir = when (scope) {
        SkillScope.PROJECT -> SkillRules.projectSkillDir(projectRoot)
        SkillScope.GLOBAL -> SkillRules.globalSkillDir(globalRoot)
    }
    return File(File(dir, name), SkillRules.SKILL_FILE)
}

/** 给人看的相对落点：`技能/<名>/SKILL.md`（两段名字都取自 [SkillRules] 的常量，不自己拼目录名）。 */
internal fun skillRelPath(name: String): String =
    "${SkillRules.SKILL_DIR}/$name/${SkillRules.SKILL_FILE}"

/**
 * 备份文件名里的**真实**时间戳：`x.SKILL.md.bak-20261018-214500` ⇒ `20261018-214500`。
 *
 * 判据 U2 要求详情卡上「恢复到上一版」显示的是**这份备份自己**的时间。现取 now 是错的：
 * 那会让人以为「上一版就是刚才存的」，而它可能是三天前的。切不出东西 ⇒ null（不许兜底成今天）。
 */
internal fun backupStampOf(backupName: String?): String? {
    val n = backupName ?: return null
    if (!n.contains(SkillStore.BACKUP_MARK)) return null
    return n.substringAfter(SkillStore.BACKUP_MARK).ifBlank { null }
}

/** 归档件文件名里的真实归档时间（`SKILL.md.deleted-20261018-230000` ⇒ 那串时间戳；切不出来就退回整名）。 */
internal fun archivedStampOf(fileName: String): String =
    fileName.substringAfter(ARCHIVED_MARK).ifBlank { fileName }

/**
 * 详情对话框那一屏的读数（A 段）。
 *
 * 四个顶部数字／状态**没有一个是界面自己数的**：`status`／`uses`／`promoteAt`／`canRestore`
 * 全部由 [SkillStore] 现读（判据 U1 的「原文」另说——正文一个字不动，见 [skillDetailDisplayBody]）。
 *
 * @param readable 盘上读得出正文？false ⇒ `body` 是空串，界面必须**说人话**（判据 U5），不许空白
 * @param relPath 给人看的那句「技能/xxx/SKILL.md」，缺文件时靠它指名是哪个
 * @param backupName 最近一份备份的文件名（没有 ⇒ null）
 * @param backupAt 那份备份文件名里的真实时间戳（[backupStampOf] 切的）
 * @param canRestore ＝ 有没有备份；「恢复到上一版」按钮的出现条件（判据 U2）
 */
internal data class SkillDetailRow(
    val name: String,
    val scope: SkillScope,
    val file: File,
    val relPath: String,
    val readable: Boolean,
    val body: String,
    val status: String,
    val isDraft: Boolean,
    val uses: Int,
    val promoteAt: Int,
    val backupName: String?,
    val backupAt: String?,
    val canRestore: Boolean,
)

/**
 * 索引行／草稿行 → 现读盘上那份详情。落点仍走 [SkillRules] 那两个唯一真源（不另起目录）。
 *
 * 读不到不抛：`isFile` 假、或读时炸（权限／被别的进程删了）⇒ `readable=false`＋空正文，
 * 由界面把「文件不在了：技能/xxx/SKILL.md」讲出来（判据 U5 的第一处）。
 */
internal fun skillDetailRow(
    name: String,
    scope: SkillScope,
    projectRoot: File,
    globalRoot: File,
): SkillDetailRow {
    val file = skillBodyFile(name, scope, projectRoot, globalRoot)
    val raw = if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
    val latest = SkillStore.backupsOf(file).firstOrNull()?.name
    return SkillDetailRow(
        name = name,
        scope = scope,
        file = file,
        relPath = skillRelPath(name),
        readable = raw != null,
        body = raw.orEmpty(),
        status = if (raw == null) "" else SkillStore.statusOf(raw),
        isDraft = raw != null && SkillStore.statusOf(raw) == "draft",
        uses = if (raw == null) 0 else SkillStore.usesOf(raw),
        promoteAt = SkillStore.PROMOTE_USES,
        backupName = latest,
        backupAt = backupStampOf(latest),
        canRestore = latest != null,
    )
}

/**
 * 详情正文要显示的文本：**盘上原文，一字不剥**（判据 U1）。
 *
 * 这条函数看着像废话（`row.body` 本来就是原文），但它存在的意义就是让 U1 能在 JVM 里逐字断言：
 * 状态头 `<!-- status=… uses=… -->` 是 23A 写在正文里的**事实**，用户在详情里就该看见它——
 * 替他剥掉＝把「这份是草稿、用过几次」的真相藏起来，也正是派工单点名的不许做的那件事。
 */
internal fun skillDetailDisplayBody(row: SkillDetailRow): String = row.body

/** 编辑既有技能时要写出去的正文：**保住第一行的状态头**（status／uses 原样），其余换成改后的文本。 */
internal fun editedBody(originalBody: String, newBody: String): String {
    val head = originalBody.lines().firstOrNull()?.trim().orEmpty()
    return if (head.startsWith("<!--") && head.contains("status=")) "$head\n$newBody" else newBody
}

/** 一条归档件：`.deleted-*` 那个文件本身＋它要搬回去的位置（B 段列表的一行）。 */
internal data class ArchivedSkillRow(
    val name: String,
    val scope: SkillScope,
    val file: File,
    val target: File,
    val relPath: String,
    val targetRelPath: String,
    val archivedAt: String,
)

/**
 * 扫两层 `技能/` 里的归档件（判据 U3 的前半：只列 `.deleted-*`）。
 *
 * 三条口径照派工单钉死：
 * 1. **只扫 `技能/` 本身**——一层子目录里的文件，不递归（递归进任意深度＝可能被软链带出工作区）；
 * 2. **跳过软链目录**（跟 `ListDirTool`／`SearchInFilesTool`／`Guard` 同一纪律：软链不跟随）；
 * 3. 只认 [ARCHIVED_PREFIX] 这个形状 ⇒ `.SKILL.md.bak-*` 那种备份不会被当成归档。
 *
 * 同一个名字可能有多份归档（删了又建、建了又删）⇒ 每份各占一行，新→旧；两层同名也各算各的。
 */
internal fun archivedSkillRows(projectRoot: File, globalRoot: File): List<ArchivedSkillRow> {
    val out = mutableListOf<ArchivedSkillRow>()
    listOf(
        SkillScope.PROJECT to SkillRules.projectSkillDir(projectRoot),
        SkillScope.GLOBAL to SkillRules.globalSkillDir(globalRoot),
    ).forEach { (scope, dir) ->
        val skillDirs = dir.listFiles { f -> f.isDirectory && !BaopuGuardCore.isSymlink(f) }
            .orEmpty()
            .sortedBy { it.name }
        skillDirs.forEach { sub ->
            val gone = sub.listFiles { f -> f.isFile && f.name.startsWith(ARCHIVED_PREFIX) }
                .orEmpty()
                .sortedByDescending { it.name }
            gone.forEach { f ->
                out += ArchivedSkillRow(
                    name = sub.name,
                    scope = scope,
                    file = f,
                    target = File(sub, SkillRules.SKILL_FILE),
                    relPath = "${SkillRules.SKILL_DIR}/${sub.name}/${f.name}",
                    targetRelPath = skillRelPath(sub.name),
                    archivedAt = archivedStampOf(f.name),
                )
            }
        }
    }
    return out
}

/** 恢复归档件之后索引那一半的三种下场（不许把「没加上」糊成「已恢复」）。 */
enum class RestoredIndex { Added, OverCap, BadName }

/**
 * 归档恢复／彻底删除的结果（B 段）。
 *
 * ## 为什么这里只带路径，不带句子
 *
 * 判据 U6 要「界面不硬编码中文」：每一句人话都在 strings.xml 里，`ArchivedOutcome` 只负责说清
 * 「哪件事没做成、涉及盘上哪个文件」，**怎么说**由界面查键。数据层 `SkillStore` 的拒绝理由是它
 * 自己的中文常量（23A 的口径），本批不动它、也不在这里抄它。
 */
internal sealed interface ArchivedOutcome {
    /** 正文搬回原位了；索引那一半看 [index]（`Added` 才是完整找回）。 */
    data class Restored(val name: String, val index: RestoredIndex) : ArchivedOutcome

    /** 目标位已经被占（判据 U3：**拒绝并指名占位者**，一个字节都不覆盖）。 */
    data class Occupied(val target: String) : ArchivedOutcome

    /** 盘上没做成：文件不见了／改名被拒。带盘上路径与原因（异常类名或 `rename`／`missing`）。 */
    data class IoFailed(val target: String, val why: String) : ArchivedOutcome

    /** 还没二次确认 ⇒ **什么都没动**（判据 U3 的第二半）。 */
    data class NeedsConfirm(val target: String) : ArchivedOutcome

    /** 真删了（只剩这一条路会走到，且必须先经 [NeedsConfirm]）。 */
    data class Deleted(val target: String) : ArchivedOutcome
}

/**
 * 把归档件搬回正文位（判据 U3 的「恢复」）。
 *
 * **只搬文件，绝不覆盖**：`技能/<名>/SKILL.md` 已经在 ⇒ [ArchivedOutcome.Occupied] 指名那个占位者。
 * 静默覆盖是这一条最不能出的事故——占位的那份可能是另一个人正在用的技能。
 *
 * 放回之后**顺手补一行索引**：删技能时 `removeIndex` 是把正文归档**＋**索引行一起去掉的，
 * 只把正文搬回来 ⇒ 模型还是看不见它 ⇒ 「找回」等于没做成。补这一行仍然：
 * 句子由数据层算（[SkillStore.plan] 出 `indexEntry`，UI 一个字不拼），
 * 写盘走人设层唯一合法口 [PersonaFiles.applyProposal]（确认制提案）。
 * 草稿件恢复后继续带「未验证」提醒（`draft` 照盘上状态问 plan 要）。
 */
internal fun restoreArchived(
    row: ArchivedSkillRow,
    projectRoot: File,
    globalRoot: File,
    personaFiles: PersonaFiles,
): ArchivedOutcome {
    if (!row.file.isFile) return ArchivedOutcome.IoFailed(row.relPath, "missing")
    if (row.target.isFile) return ArchivedOutcome.Occupied(row.targetRelPath)
    val moved = runCatching { row.file.renameTo(row.target) }
    if (moved.isFailure || !moved.getOrDefault(false) || !row.target.isFile) {
        return ArchivedOutcome.IoFailed(row.relPath, moved.exceptionOrNull()?.javaClass?.simpleName ?: "rename")
    }
    val body = runCatching { row.target.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
    val planned = SkillStore.plan(
        SkillProposal(
            scope = row.scope,
            name = row.name,
            body = stripStatusHeader(body),
            reason = "归档恢复",
            draft = SkillStore.statusOf(body) == "draft",
        ),
        projectRoot,
        globalRoot,
        backupStamp = "", // 这里只要 indexEntry，不落盘 ⇒ 备份戳用不上（真写盘的一律由调用方给戳）
    )
    val layer = when (row.scope) {
        SkillScope.GLOBAL -> PersonaLayer.B_GLOBAL
        SkillScope.PROJECT -> PersonaLayer.C_PROJECT
    }
    // 目录名不是数据层认可的写法 ⇒ 索引行会指向一个不存在的目录。宁可明说，不写假索引。
    if (planned.proposal.name != row.name) return ArchivedOutcome.Restored(row.name, RestoredIndex.BadName)
    val current = personaFiles.read(layer).orEmpty()
    val ok = when (val edit = SkillRules.addIndexLine(current, planned.indexEntry)) {
        is SkillRules.EditResult.Ok -> {
            val p = PersonaRules.propose(layer, current, edit.newText, "恢复技能索引：${row.name}")
            p != null && personaFiles.applyProposal(p)
        }
        else -> false
    }
    return ArchivedOutcome.Restored(
        row.name,
        if (ok) RestoredIndex.Added else RestoredIndex.OverCap,
    )
}

/**
 * 彻底删除归档件（判据 U3 的「二次确认」那一半）。
 *
 * `confirmed=false` ⇒ 返回 [ArchivedOutcome.NeedsConfirm] 并且**一个字节都不动**；界面第一击就走这条，
 * 第二击才带 true。把闸做在纯函数里（而不是只做在按钮的选中态上），是为了让「没确认就不许删」
 * 这条能在 JVM 里用真临时目录断言。
 *
 * 这里删的是**已经在 `.deleted-*` 状态**的那份副本：原件早就被 [SkillStore.archive] 改名归档，
 * 本函数只负责把「回收站里的那一份」清掉——所以它必须问用户要第二遍。
 */
internal fun deleteArchived(row: ArchivedSkillRow, confirmed: Boolean): ArchivedOutcome {
    if (!confirmed) return ArchivedOutcome.NeedsConfirm(row.relPath)
    if (!row.file.isFile) return ArchivedOutcome.IoFailed(row.relPath, "missing")
    val gone = runCatching { row.file.delete() }
    return if (gone.getOrDefault(false) && !row.file.exists()) ArchivedOutcome.Deleted(row.relPath)
    else ArchivedOutcome.IoFailed(row.relPath, gone.exceptionOrNull()?.javaClass?.simpleName ?: "denied")
}

/**
 * 技能详情对话框（A 段）。
 *
 * 顶部四行＝名字／作用域／状态／已用 N/3；中间＝**盘上原文**（等宽、可滚、可复制，
 * 状态头一字不剥，判据 U1）；底排＝复制全文／恢复到上一版（[SkillDetailRow.canRestore] 为真才出现，
 * 且把备份文件名里那个**真实时间**写在按钮上，判据 U2）／编辑／关闭。
 * 读不到正文时不许空白：整屏换成「文件不在了：技能/xxx/SKILL.md」（判据 U5 第一处）。
 *
 * 本对话框**不写盘**：恢复备份与编辑都只把意图交回调用方（写盘集中在设置页那一处）。
 */
@Composable
internal fun SkillDetailDialog(
    detail: SkillDetailRow,
    onDismiss: () -> Unit,
    onRestoreBackup: () -> Unit,
    onEdit: () -> Unit,
) {
    val ctx = LocalContext.current
    val res = LocalResources.current
    var copied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(detail.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.skill_detail_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        R.string.skill_detail_scope,
                        stringResource(
                            if (detail.scope == SkillScope.GLOBAL) R.string.skill_new_scope_global
                            else R.string.skill_new_scope_project,
                        ),
                    ),
                )
                Text(
                    stringResource(
                        R.string.skill_detail_status,
                        stringResource(
                            if (detail.isDraft) R.string.skill_detail_status_draft
                            else R.string.skill_detail_status_active,
                        ),
                    ),
                )
                Text(stringResource(R.string.skill_detail_uses, detail.uses, detail.promoteAt))
                Text(
                    stringResource(R.string.skill_detail_file, detail.relPath),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (detail.readable) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .padding(horizontal = 2.dp),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                // U1：这里给的就是 row.body，一字未动
                                text = skillDetailDisplayBody(detail),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    copied?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    // U5：文件不在 ⇒ 指名是哪个文件，不许空白屏、不许静默关掉
                    Text(
                        text = stringResource(R.string.skill_missing_file, detail.relPath),
                        style = MaterialTheme.typography.bodyMedium,
                        color = zhengdaoErrorText(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (detail.readable) {
                        TextButton(onClick = {
                            val text = skillDetailDisplayBody(detail)
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("zhengdao-skill", text))
                            copied = res.getString(R.string.skill_copied_chars, text.length)
                        }) { Text(stringResource(R.string.skill_copy_all)) }
                    }
                    val backup = detail.backupName
                    if (detail.canRestore) {
                        TextButton(onClick = onRestoreBackup) {
                            // 显示的是这份备份文件名里那串真实时间（U2），不是「刚刚」
                            Text(stringResource(R.string.skill_restore_backup_at, detail.backupAt ?: backup.orEmpty()))
                        }
                    }
                    if (detail.readable) {
                        TextButton(onClick = onEdit) { Text(stringResource(R.string.skill_detail_edit)) }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.skill_close)) }
                }
            }
        }
    }
}

/**
 * 已归档列表对话框（B 段）。
 *
 * 每行：原名＋归档时间（取自文件名，U3）＋落点＋「恢复」／「彻底删除」。
 * `confirming` 是**已经点过一次删除**的那一条：它下面多显示一句「再点一次就真删了，删掉找不回来」，
 * 第二击才带 `confirmed=true` 出去（真正的闸在 [deleteArchived] 里，界面只是把它露出来）。
 * 列表为空（打开之后别的进程把文件动了）也照样说人话，不许空白（U5 第三处）。
 */
@Composable
internal fun SkillArchivedDialog(
    rows: List<ArchivedSkillRow>,
    confirming: File?,
    onDismiss: () -> Unit,
    onRestore: (ArchivedSkillRow) -> Unit,
    onDelete: (ArchivedSkillRow) -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(stringResource(R.string.skill_archived_group, rows.size), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (rows.isEmpty()) {
                    Text(
                        stringResource(R.string.skill_archived_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        rows.forEach { row ->
                            val isConfirming = confirming?.absolutePath == row.file.absolutePath
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(row.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(R.string.skill_archived_at, row.archivedAt) + "・" +
                                        stringResource(
                                            if (row.scope == SkillScope.GLOBAL) R.string.skill_new_scope_global
                                            else R.string.skill_new_scope_project,
                                        ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    row.relPath,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { onRestore(row) }) {
                                        Text(stringResource(R.string.skill_archived_restore))
                                    }
                                    TextButton(onClick = { onDelete(row) }) {
                                        Text(stringResource(R.string.skill_archived_delete))
                                    }
                                }
                                if (isConfirming) {
                                    // U3：这一行就是「二次确认」的那一遍提醒，说清恢复不了
                                    Text(
                                        stringResource(R.string.skill_archived_confirm, row.relPath),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = zhengdaoErrorText(),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.skill_close)) }
                }
            }
        }
    }
}


/**
 * 显示用：把 `**加粗**` 标记去掉（2026-10-19 用户：「可以不用这个（**改稿**）这种吗？」）。
 *
 * 只改**显示**：提案/索引的原文一个字不动（那是要写入人设的字节）；这里只是别让星号
 * 露在卡片上。粗体由 Compositor 的字重给（整行 Medium），不再依赖 markdown。
 */
internal fun withoutBoldMarkers(text: String): String {
    if (!text.contains("**")) return text
    // 不用正则：UI 层「不解析文本」是本仓的门禁（BaopuSkillTrialUiTest T2），
    // 这里只是去掉成对的 ** 显示标记，逐字符扫一遍足够。
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
            i += 2
        } else {
            sb.append(text[i])
            i++
        }
    }
    return sb.toString()
}
