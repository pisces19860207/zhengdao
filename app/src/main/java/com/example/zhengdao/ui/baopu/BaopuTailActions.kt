// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19 用户口径（第四十批）：
//   「有些公司会偶尔抽风截断模型的回复，可以有重试吗？就是一个刷新的按钮」
//   「如果用户打字错了，可以修改当前对话里的最近一条已经发出去的信息内容吗？然后让模型重新输出」
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R

/** 输入栏上方那一行的动作（三个都只在「该出现」的时候出现）。 */
internal enum class TailAction {
    /** 上一轮被输出上限截断 ⇒ 接着说完（不新增用户消息）。 */
    CONTINUE,

    /** 上一轮跑挂了 / 被截断后停下 ⇒ 重发这一轮。 */
    RETRY,

    /** 改「最近一条用户消息」再重跑。 */
    EDIT,
}

/**
 * 该显示哪几个动作（纯函数，JVM 直测）。
 *
 * 纪律：
 * 1. 跑着的时候一个都不显示（不许在跑的时候改历史/重发，会把自己绕进去）；
 * 2. 没有会话就没有「编辑上一条」；
 * 3. 截断与失败可以同时成立（先截断收尾、后面又失败过）——两个都给，用户自己选。
 */
internal fun tailActions(
    busy: Boolean,
    hasSession: Boolean,
    truncated: Boolean,
    aborted: Boolean,
): Set<TailAction> {
    if (busy) return emptySet()
    val out = mutableSetOf<TailAction>()
    if (truncated) out += TailAction.CONTINUE
    if (aborted) out += TailAction.RETRY
    if (hasSession) out += TailAction.EDIT
    return out
}

/**
 * 输入栏上方的动作行（第四十批）。
 *
 * 位置纪律与「这次学到」「ask_user」一致：**输入栏上方**，不遮对话、不跳新页。
 */
@Composable
internal fun BaopuTailActions(
    actions: Set<TailAction>,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
) {
    if (actions.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        if (TailAction.EDIT in actions) {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.baopu_edit_last)) }
        }
        if (TailAction.RETRY in actions) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.baopu_tail_retry)) }
        }
        if (TailAction.CONTINUE in actions) {
            TextButton(onClick = onContinue) { Text(stringResource(R.string.baopu_tail_continue)) }
        }
    }
}

/**
 * 「编辑上一条」的编辑框（第四十批）。
 *
 * **保存前把后果写清楚**：这句之后的回复（含工具卡、计划卡）会被删掉，然后按改后的内容重跑。
 * 不给这段说明的话，用户会以为改字只是改字。
 */
@Composable
internal fun BaopuEditMessageDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.baopu_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.baopu_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.baopu_edit_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
