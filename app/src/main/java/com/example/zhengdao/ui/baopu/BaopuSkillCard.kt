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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaProposal
import com.example.zhengdao.baopu.SkillProposal
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import java.io.File

/**
 * 技能索引提案卡（第十九批 §2.2）：封卷提炼／启动补算产出的 PENDING 提案，
 * 用户「采纳」⇒ 写进人设对应层，「丢弃」⇒ 只移除。**采纳之前人设一个字都不动**（K4）。
 *
 * 卡上注明提案来路：用户明说的（「记住这个流程」）写「这是你说的」，其余写「会话封卷提炼」
 * ——确认制要求用户审的就是「为什么改」（[PersonaProposal.reason] 已带，这里再亮一遍层）。
 */
@Composable
fun BaopuSkillProposalCard(
    proposals: List<PersonaProposal>,
    onConfirm: (PersonaProposal) -> Unit,
    onDiscard: (PersonaProposal) -> Unit,
) {
    if (proposals.isEmpty()) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.skill_proposal_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            proposals.forEach { p ->
                val layerLabel = stringResource(
                    if (p.layer == PersonaLayer.B_GLOBAL) R.string.skill_layer_global
                    else R.string.skill_layer_project,
                )
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = layerLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = p.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = p.proposed.lines().lastOrNull { it.isNotBlank() }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
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
 */
@Composable
internal fun SkillEditorDialog(
    onDismiss: () -> Unit,
    onSaved: (SkillWriteResult) -> Unit,
    projectRoot: java.io.File,
    globalRoot: java.io.File,
    personaFiles: com.example.zhengdao.baopu.PersonaFiles,
    backupStamp: String,
) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var body by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var scope by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(SkillScope.PROJECT) }
    var draft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    var reviewing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val proposal = SkillProposal(scope = scope, name = name, body = body, reason = "用户手写", draft = draft)
    // 索引条数由调用方在 apply 时再核一次；这里给 0 只为了把「是否超上限」的判据留在 apply 里。
    val plan = SkillStore.plan(proposal, projectRoot, globalRoot, backupStamp, indexCountAfterAdd = 0)

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp)) {
                if (!reviewing) {
                    Text(stringResource(R.string.skill_new_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.skill_new_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope = SkillScope.PROJECT }) {
                            Text(
                                stringResource(R.string.skill_new_scope_project),
                                color = if (scope == SkillScope.PROJECT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { scope = SkillScope.GLOBAL }) {
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
                        androidx.compose.material3.Checkbox(checked = draft, onCheckedChange = { draft = it })
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
                                val indexText = personaFiles.read(
                                    if (scope == SkillScope.GLOBAL) PersonaLayer.B_GLOBAL else PersonaLayer.C_PROJECT,
                                ).orEmpty()
                                val result = SkillStore.apply(plan, personaFiles, indexText)
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