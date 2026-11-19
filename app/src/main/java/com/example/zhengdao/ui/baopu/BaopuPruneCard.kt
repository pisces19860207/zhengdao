// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十二批 · 记忆修剪读数卡（判据④「一键回滚」的界面落点 ＋ 判据⑥读数送达）。
//
// 只在「最近一轮修剪真跑过且还没撤销」时显示；卡上两钮：撤销本次修剪（一键回滚）/
// 知道了（本会话收起——收起不动任何数据，下次有新修剪轮会再出现）。
// 读数行直接来自状态文件（合并 N / 退役 M / 跳过 K），一个字不加工。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.memoryprune.PruneReadout

/**
 * 修剪读数卡：本轮总账（合并 N / 退役 M / 跳过 K ＋ 明细）＋「撤销本次修剪」。
 * [readout] 为 null ＝ 没有可展示的修剪轮（卡不渲染——读数卡不占地方）。
 */
@Composable
internal fun BaopuPruneCard(
    readout: PruneReadout?,
    note: String?,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (readout == null) return
    BaopuInfoContainer(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        if (!note.isNullOrBlank()) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.baopu_prune_card_title),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = readout.note,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (readout.skipDetail.isNotBlank()) {
            Text(
                text = readout.skipDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = stringResource(R.string.baopu_prune_card_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onUndo) {
                Text(stringResource(R.string.baopu_prune_undo))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.baopu_prune_dismiss))
            }
        }
    }
}
