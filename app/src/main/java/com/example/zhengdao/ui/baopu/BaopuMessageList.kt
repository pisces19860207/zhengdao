// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuMessageList：role 分组、文本 part、工具调用 part
// 可见（入参出参摘要）、压缩点可见可展开）+ 设计 §9 施工细节第 8 条。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.ui.theme.zhengdaoErrorText

/**
 * 消息列表。role 分组：user 右对齐浅色、assistant 左对齐 surface；
 * tool part 显示「工具调用 · 名字」＋ 摘要（点开看全文——围栏包裹的原文）。
 */
@Composable
internal fun BaopuMessageList(
    messages: List<BaopuUiMessage>,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) {
        Column(modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.baopu_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(messages, key = { it.id }) { msg ->
            BaopuMessageRow(msg)
        }
    }
}

@Composable
private fun BaopuMessageRow(msg: BaopuUiMessage) {
    val isUser = msg.role == "user"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isUser) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                tonalElevation = if (isUser) 0.dp else 1.dp,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(if (isUser) 0.85f else 1f),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(
                            if (isUser) R.string.baopu_role_user else R.string.baopu_role_assistant,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    msg.parts.forEachIndexed { i, part ->
                        when (part.kind) {
                            "tool" -> BaopuToolPart(part, key = "${msg.id}-$i")
                            else -> BaopuTextPart(part, key = "${msg.id}-$i")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BaopuTextPart(part: BaopuUiPart, key: String) {
    if (part.text.isBlank()) return
    Text(
        text = part.text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 工具 part：默认只显示调用名 ＋ 摘要；点开看全文（围栏原文，含 offset/limit 提示）。 */
@Composable
private fun BaopuToolPart(part: BaopuUiPart, key: String) {
    var expanded by remember(key) { mutableStateOf(false) }
    val failed = part.text.startsWith("失败") || part.text.startsWith("被拒")
    Column(Modifier.padding(top = 6.dp).clickable { expanded = !expanded }) {
        Text(
            text = stringResource(R.string.baopu_tool_label) + " · " + part.tool,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (failed) {
                zhengdaoErrorText() // P2-11：error 色 3.55:1 不达正文 AA 4.5:1
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Text(
            text = if (expanded) part.text else part.text.take(160) + if (part.text.length > 160) "…" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
