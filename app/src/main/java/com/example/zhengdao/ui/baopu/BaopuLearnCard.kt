// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19 用户口径：抱朴「这次学到」要在任务收尾时出一张卡，**卡上三件套**
// （内容／来源／建议草稿还是正式），只出卡不落盘，用户点采纳才写，拒绝就一字不动。
//
// 位置纪律（与 ask_user 那张卡同区）：**输入栏上方**——不遮对话、不跳新页。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.SkillRefine

/**
 * 「这次学到」提案卡（第三十七批 B 段）。
 *
 * @param proposals 引擎收尾时生成的提案（≤3 条；**没信号时引擎根本不会出这张卡**）
 * @param onAdopt 采纳某一条（把 id 交回控制器；**只有这一条路会落盘**）
 * @param onDismiss 「不记」⇒ 只收起卡片，一个字节都不写
 */
@Composable
internal fun BaopuLearnCard(
    proposals: List<SkillRefine.LearnProposal>,
    onAdopt: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.learn_card_title),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            proposals.forEach { p ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    // 三件套：内容 · 来源 · 建议（草稿还是转正式）
                    Text(
                        text = p.content,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "${stringResource(R.string.learn_source_prefix)}：${p.source}" +
                            "（${stringResource(R.string.learn_suggest_prefix)}：${
                                stringResource(
                                    if (p.suggest == SkillRefine.Suggest.FORMAL) {
                                        R.string.learn_suggest_formal
                                    } else {
                                        R.string.learn_suggest_draft
                                    },
                                )
                            }）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        // 采纳＝实心（这是会落盘的那个动作），「不记」用描边
                        Button(onClick = { onAdopt(p.id) }) {
                            Text(stringResource(R.string.learn_adopt))
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(R.string.learn_dismiss))
                        }
                    }
                }
            }
        }
    }
}
