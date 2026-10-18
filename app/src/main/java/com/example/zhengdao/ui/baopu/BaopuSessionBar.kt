// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（BaopuSessionBar：当前会话头 ＋ 状态 ＋ 中断提示 ＋ 一键续跑）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.RunState
import com.example.zhengdao.ui.theme.zhengdaoErrorText

/** 状态 → 人话文案（全部走 stringResource；错误号口径在引擎层已人话化）。 */
internal fun stateLabel(state: RunState): Int = when (state) {
    RunState.IDLE -> R.string.baopu_state_idle
    RunState.THINKING -> R.string.baopu_state_thinking
    RunState.ACTING -> R.string.baopu_state_acting
    RunState.AWAIT_CONFIRM -> R.string.baopu_state_await_confirm
    RunState.COMPRESSING -> R.string.baopu_state_compressing
    RunState.ABORTED -> R.string.baopu_state_aborted
    RunState.DONE -> R.string.baopu_state_done
}

/**
 * 会话头：状态条 ＋ 中断提示 ＋ 续跑/新会话。
 * 中断判据：interruptedStep >= 0 且引擎没在跑（跑起来就不再是"中断"而是"续跑中"）。
 */
@Composable
internal fun BaopuSessionBar(
    state: RunState,
    interruptedStep: Int,
    notice: String?,
    hasSession: Boolean,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 1.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // 中断提示（可续跑）
            if (interruptedStep >= 0 && !busyState(state) && hasSession) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.baopu_interrupted_at, interruptedStep),
                        style = MaterialTheme.typography.bodySmall,
                        color = zhengdaoErrorText(), // P2-11：error 色 3.55:1 不达正文 AA 4.5:1
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onResume) {
                        Text(stringResource(R.string.baopu_resume))
                    }
                }
            }
            // 引擎给的人话（AskUser / 错误号口径；不是英文 err 直出）
            if (!notice.isNullOrBlank()) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun busyState(state: RunState): Boolean = state in setOf(
    RunState.THINKING, RunState.ACTING, RunState.COMPRESSING, RunState.AWAIT_CONFIRM,
)
