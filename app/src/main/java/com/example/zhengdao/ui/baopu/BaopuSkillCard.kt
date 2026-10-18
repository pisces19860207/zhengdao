// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十九批 §2.2（写索引三路全过确认）与 §2.5（审阅第一版只做看和删）。
// 房型照 BaopuPendingMemoryCard：候选未落盘，用户拍板才走 applyProposal。
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
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult

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