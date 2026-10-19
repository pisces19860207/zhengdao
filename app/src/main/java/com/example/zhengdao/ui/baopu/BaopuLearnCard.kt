// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19 用户口径：抱朴「这次学到」要在任务收尾时出一张卡，**卡上三件套**
// （内容／来源／建议草稿还是正式），只出卡不落盘，用户点采纳才写，拒绝就一字不动。
//
// 第三十七批 C 段追加：采纳前过三道门禁（危险指令／平台事实没标未实测／同名冲突），
// **不过就标红并把采纳按钮禁掉，且说清哪条不过**。门禁是纯函数，卡上这是第一次查，
// 控制器落盘前还会再查一次（卡可能已经过期）。
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
import com.example.zhengdao.ui.theme.zhengdaoErrorText

/**
 * 「这次学到」提案卡（第三十七批 B 段 + C 段门禁）。
 *
 * @param proposals 引擎收尾时生成的提案（≤3 条；**没信号时引擎根本不会出这张卡**）
 * @param existingNames 出卡时的已有技能名（判同名冲突；空集＝没冲突）
 * @param onAdopt 采纳某一条（把 id 交回控制器；**只有这一条路会落盘**）
 * @param onDismiss 「不记」⇒ 只收起卡片，一个字节都不写
 */
@Composable
internal fun BaopuLearnCard(
    proposals: List<SkillRefine.LearnProposal>,
    existingNames: Set<String>,
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
                val review = SkillRefine.reviewLearn(p, existingNames)
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
                    // 门禁不过 ⇒ 红字说清哪条不过（这里不落盘，也没有"强行写"的入口）
                    if (!review.ok) {
                        Text(
                            text = stringResource(R.string.learn_gate_blocked, review.summary),
                            style = MaterialTheme.typography.labelSmall,
                            // P2-11：colorScheme.error 做正文只有 3.55:1，走 zhengdaoErrorText()（门禁钉着）
                            color = zhengdaoErrorText(),
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        // 采纳＝实心（这是会落盘的那个动作），「不记」用描边
                        Button(
                            onClick = { onAdopt(p.id) },
                            enabled = review.ok,
                        ) {
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
