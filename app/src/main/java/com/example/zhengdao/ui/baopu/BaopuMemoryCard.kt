// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuMemoryCard：记忆写入要在会话里可见——设计 §9
// 施工细节第 8 条）+ 程枢版 §16.4（记忆带来源、可单条删改；提炼归会话结束一次性）。
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
