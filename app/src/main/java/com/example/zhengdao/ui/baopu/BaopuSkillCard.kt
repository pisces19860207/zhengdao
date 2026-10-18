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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaProposal

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
