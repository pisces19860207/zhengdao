// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuComposer：输入框 ＋ 发送/停止按钮）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R

/**
 * 输入区：发送（busy 时变停止）。busy 由调用方给（引擎状态），
 * 本组件不自持运行状态——它只管文本框。
 */
@Composable
internal fun BaopuComposer(
    busy: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.baopu_input_hint)) },
            maxLines = 4,
            enabled = !busy,
        )
        if (busy) {
            // 停止：立刻可点（点停止任何时候都该有用）
            Button(
                onClick = {
                    onStop()
                },
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text(stringResource(R.string.baopu_stop))
            }
        } else {
            Button(
                onClick = {
                    val t = text
                    if (t.isNotBlank()) {
                        text = ""
                        onSend(t)
                    }
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text(stringResource(R.string.baopu_send))
            }
        }
    }
}
