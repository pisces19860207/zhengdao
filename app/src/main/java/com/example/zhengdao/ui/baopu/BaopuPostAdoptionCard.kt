// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批（2026-10-21）：**采纳后效果的建议卡**。
//
// 这张卡只做三件事：报数（采纳前 vs 采纳后）、给一句结论、给三个按钮。
// 它**没有**任何自动动作：点「回滚」才走 `controller.rollbackPostAdoption()`，
// 那条路才允许换文件。样式全部沿用现有卡（`BaopuLearnCard`）：同样的 Surface 高度、
// 同样的 12/10 内距、同样的 bodyMedium/bodySmall/labelSmall 三级字号、同样的
// 「会动的用实心、不动的用描边」口径——**本批不新增配色/字号/圆角/动效**。
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
import com.example.zhengdao.baopu.PostAdoptionNotice
import com.example.zhengdao.baopu.PostAdoptionVerdict

@Composable
internal fun BaopuPostAdoptionCard(
    notice: PostAdoptionNotice,
    onRollback: () -> Unit,
    onKeep: () -> Unit,
    onLater: () -> Unit,
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
                text = stringResource(R.string.post_adopt_title, notice.skillName),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            // 一行纯文本说清「采纳前 / 采纳后」——不做表格、不加新布局组件
            Text(
                text = stringResource(
                    R.string.post_adopt_counts,
                    notice.before.successes,
                    notice.before.failures,
                    notice.before.corrections,
                    notice.delta.successes,
                    notice.delta.failures,
                    notice.delta.corrections,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    if (notice.verdict == PostAdoptionVerdict.RETIRE) {
                        R.string.post_adopt_verdict_retire
                    } else {
                        R.string.post_adopt_verdict_rollback
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth()) {
                // 会动文件的那个用实心（与学习卡「采纳＝实心」同口径）
                Button(onClick = onRollback, enabled = notice.verdict == PostAdoptionVerdict.ROLLBACK) {
                    Text(stringResource(R.string.post_adopt_rollback))
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onKeep) {
                    Text(stringResource(R.string.post_adopt_keep))
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onLater) {
                    Text(stringResource(R.string.post_adopt_later))
                }
            }
        }
    }
}
