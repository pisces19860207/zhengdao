// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十一批 · 回放可见的 UI 半边：顶部指示条 ＋ 失败卡。
//
// 纪律与 196 的 TeachBar 同一条：**判据与状态全在 baopu 侧**（[ReplayState] 是唯一真相源），
// 本文件只渲染与转发，不存任何回放态、不判「该不该停」。三处可见物（指示条／胶囊进度／
// 失败卡）读的是同一份 [ReplayState]（判据⑤）。
//
// 为什么指示条放顶部：回放是**机器在替你动手**，跑起来第一件事是让人知道「现在它在动」；
// 放在标签条下面第一眼就能看到，且不挤压页面区（一行 28dp，页面区让一点高度）。
package com.example.zhengdao.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.ReplayFailure
import com.example.zhengdao.baopu.ReplayState
import com.example.zhengdao.baopu.ReplayText
import com.example.zhengdao.ui.theme.rButton

/**
 * 顶部指示条：`回放中 2/5 · 正在点「提交」` ＋ 停止钮（判据①）。
 *
 * 只在 [ReplayState.playing] 为真时由调用方组合（判据③的另一半：playing 一落，这一行
 * 就不在组合树里 —— 不是 `visibility = GONE`）。文案走 [ReplayText.fitBar]：整行硬性 ≤40 字。
 */
@Composable
internal fun ReplayIndicatorBar(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 一句话读三个字段：步号头（资源）＋ 动作短语（状态源里那句）拼成整行
    val head = stringResource(R.string.replay_bar_head, ReplayState.stepIndex, ReplayState.stepTotal)
    val line = ReplayText.fitBar(head, ReplayState.currentAction)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        shape = RoundedCornerShape(rButton),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 跑着的记号：一颗小圆点（呼吸留给胶囊，这里不抢动画）
            Spacer(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onStop) {
                Text(
                    text = stringResource(R.string.replay_stop),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * 失败卡（判据④）：第几步 / 目标 / 命中几个 / 原因 ＋ 三个出口。
 *
 * 三钮的顺序即推荐顺序：① 让抱朴改这条技能（带失败报告——它拿到的就是 [ReplayFailure.reportText]）；
 * ② 重新教（人自己录一遍）；③ 先不管（收卡，什么都不写）。
 * 卡上不出现「自动重试」：失败已经取证，重试是人的决定。
 */
@Composable
internal fun ReplayFailureCard(
    failure: ReplayFailure,
    onAskBaopu: () -> Unit,
    onReteach: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onLater,
        modifier = modifier,
        title = { Text(stringResource(R.string.replay_failure_title, failure.skillName)) },
        text = {
            Column {
                val stepLine = failure.step?.let {
                    stringResource(R.string.replay_failure_step, it, failure.total)
                } ?: stringResource(R.string.replay_failure_step_unknown)
                Text(stepLine, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.replay_failure_target, failure.target.ifBlank { "—" }),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = failure.hits?.let { stringResource(R.string.replay_failure_hits, it) }
                        ?: stringResource(R.string.replay_failure_hits_unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.replay_failure_reason, failure.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = com.example.zhengdao.ui.theme.zhengdaoErrorText(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAskBaopu) {
                Text(stringResource(R.string.replay_ask_baopu))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onReteach) {
                    Text(stringResource(R.string.replay_reteach))
                }
                TextButton(onClick = onLater) {
                    Text(stringResource(R.string.replay_later))
                }
            }
        },
    )
}
