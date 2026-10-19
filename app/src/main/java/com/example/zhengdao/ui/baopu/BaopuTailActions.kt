// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19 用户口径（第四十批）：
//   「有些公司会偶尔抽风截断模型的回复，可以有重试吗？就是一个刷新的按钮」
//   「编辑上一条可以在对应那个用户发送的信息长按出现吗？就不这么显化了，有点挡手机的屏幕」
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

/** 输入栏上方那一行的动作（只在出事时出现——平时**不占屏幕**）。 */
internal enum class TailAction {
    /** 上一轮被输出上限截断 ⇒ 接着说完（不新增用户消息）。 */
    CONTINUE,

    /** 上一轮跑挂了 / 被截断后停下 ⇒ 重发这一轮。 */
    RETRY,
}

/**
 * 该显示哪几个动作（纯函数，JVM 直测）。
 *
 * 纪律：
 * 1. **跑着的时候一个都不显示**（不许在跑的时候重发，会把自己绕进去）；
 * 2. 平常什么都不显示——「编辑上一条」改成**长按那条消息**出菜单（用户：挡屏幕）；
 * 3. 截断与失败可以同时成立，两个都给，用户自己选。
 */
internal fun tailActions(
    busy: Boolean,
    truncated: Boolean,
    aborted: Boolean,
): Set<TailAction> {
    if (busy) return emptySet()
    val out = mutableSetOf<TailAction>()
    if (truncated) out += TailAction.CONTINUE
    if (aborted) out += TailAction.RETRY
    return out
}

/** 输入栏上方的动作行：只在被截断 / 跑挂时出现，平时整行不存在。 */
@Composable
internal fun BaopuTailActions(
    actions: Set<TailAction>,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
) {
    if (actions.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        if (TailAction.RETRY in actions) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.baopu_tail_retry)) }
        }
        if (TailAction.CONTINUE in actions) {
            TextButton(onClick = onContinue) { Text(stringResource(R.string.baopu_tail_continue)) }
        }
    }
}

/**
 * 长按用户消息弹出的小菜单（第四十批，用户指定位置）。
 *
 * 为什么是菜单而不是直接进编辑框：那个长按手势以前是「选中复制」，直接改成编辑会把复制弄丢；
 * 菜单里两项各归各位，也不占正文区。
 */
@Composable
internal fun BaopuUserMessageMenu(
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.baopu_user_msg_menu_title)) },
        text = {
            Column {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.baopu_edit_this)) }
                TextButton(onClick = onCopy) { Text(stringResource(R.string.copy_text)) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * 「编辑这条」的编辑框（第四十批）。
 *
 * **保存前把后果写清楚**：这条之后的回复（含工具卡、计划卡）会被删掉，然后按改后的内容重跑。
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
