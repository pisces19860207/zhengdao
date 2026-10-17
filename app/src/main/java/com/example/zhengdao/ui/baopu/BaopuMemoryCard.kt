// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuMemoryCard：记忆写入要在会话里可见——设计 §9
// 施工细节第 8 条）+ 程枢版 §16.4（记忆带来源、可单条删改；提炼归会话结束一次性）
// ＋ 派工单第十批 §1 C 段（提炼候选一律 PENDING 未落盘，等用户过目；顺带一条自省）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.MemoryEntry

/**
 * 记忆候选卡：一条候选 = 标题 ＋ 正文摘要 ＋「记住 / 不记」。
 * 只要有候选就显示（候选来自 MemoryStore 的 CANDIDATE 条目）；
 * 真正的会话结束提炼归刀 2，本刀先把卡与确认链路通起来（机制可见）。
 */
@Composable
internal fun BaopuMemoryCard(
    candidates: List<MemoryEntry>,
    onConfirm: (String) -> Unit,
    onForget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        candidates.take(3).forEach { e -> // 最多同屏 3 张，避免把对话挤没
            Surface(
                shape = MaterialTheme.shapes.small,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.baopu_memory_card_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = e.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (e.body.isNotBlank()) {
                        Text(
                            text = e.body.take(120) + if (e.body.length > 120) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onConfirm(e.id) }) {
                            Text(stringResource(R.string.baopu_memory_confirm))
                        }
                        TextButton(onClick = { onForget(e.id) }) {
                            Text(stringResource(R.string.baopu_memory_forget))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 会话结束提炼出的**待过目**候选卡（刀 2 C 段）。
 *
 * 与 [BaopuMemoryCard] 的两点不同（都是派工单点名的）：
 * 1. **没落盘**——条目是 [com.example.zhengdao.baopu.MemoryStatus.PENDING]，
 *    只活在进程里；用户点「记住」才转 CONFIRMED 写盘，点「不记」直接丢弃。
 *    标题明说「还没落盘」，用户知道点什么都不会立刻改文件。
 * 2. 按钮文案用同一对（记住/不记）——动作语义一致，不发明第三套词。
 *
 * 房型（同屏 3 张、Surface + 两按钮）与老卡一致：同一个位置换数据源，
 * 不让用户学两套界面。
 */
@Composable
internal fun BaopuPendingMemoryCard(
    pending: List<MemoryEntry>,
    onConfirm: (String) -> Unit,
    onForget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pending.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        pending.take(3).forEach { e ->
            Surface(
                shape = MaterialTheme.shapes.small,
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.baopu_pending_card_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = e.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (e.body.isNotBlank()) {
                        Text(
                            text = e.body.take(120) + if (e.body.length > 120) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onConfirm(e.id) }) {
                            Text(stringResource(R.string.baopu_memory_confirm))
                        }
                        TextButton(onClick = { onForget(e.id) }) {
                            Text(stringResource(R.string.baopu_memory_forget))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 自省便签（刀 2 C 段）——**恰好一条**，短句，不占地方。
 *
 * 派工单原话：「顺带产出一条『这次学到什么』……一条，短句」。所以这里不做成
 * 卡片也不给按钮（它没有可点的动作——自省不是记忆，不能确认也不能删），
 * 就是一行带底色的便签，压在待过目卡上面。
 */
@Composable
internal fun BaopuIntrospectionNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.baopu_introspection_label),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
