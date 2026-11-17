// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **卡面**（纯渲染：形状判据全在 `baopu/recall` 纯层，这里只接线——
// 要点行、「跳原文」交给屏幕层的既有 544 跳转机制、收起钮）。容器走信息卡档
// （`BaopuInfoContainer`，137 批口径）：唤回是知会不是动作主角。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.recall.RecallCardRules
import com.example.zhengdao.baopu.recall.RecallCardView

@Composable
internal fun BaopuRecallCard(
    view: RecallCardView,
    onJump: (sessionId: Long, messageId: Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BaopuInfoContainer(modifier = modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.recall_card_title),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.recall_dismiss),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (view.notFound) {
            // 派工单③「找不到就直说，不许编」：没找到就一行口径，零要点、零动作。
            Text(
                text = stringResource(R.string.recall_not_found),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            view.rows.forEach { row ->
                Column(Modifier.padding(top = 2.dp)) {
                    Text(
                        text = row.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // 来源 id 摆在明处：派工单③「要点必带消息 id（可跳原文）」要看得见
                            text = "#" + row.sourceId.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        RecallCardRules.jumpOf(row)?.let { (sid, mid) ->
                            TextButton(onClick = { onJump(sid, mid) }) {
                                Text(
                                    text = stringResource(R.string.recall_jump),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
