// 独立开发声明：本文件为本项目原创。
//
// 第一百九十批 · 题材与写法卡的**推荐卡**（写法档位，只渲染，零判据）。
//
// 纪律（派工单 §0.2）：
// - 每批 2–3 套写法：口吻／长度区间／结构（数据在 `TopicSuggest.STYLES`，纯判据）；
// - 默认档＝上次成功发出去的那套（控制器从本地偏好取，不联网），卡面标「上次成功用的就是这套」；
// - 「按推荐的来」直接进确认卡；「改一档」就地切下一套后进确认卡（切换逻辑在状态机）；
// - 并 164：内联在输入栏上方，不弹 Dialog、不抢焦点——不采纳也能直接打字发送。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.TopicStyle

/**
 * 推荐卡（写法档位）。
 *
 * @param styles 全部档位（2–3 套；顺序固定）
 * @param chosenId 当前选中（默认＝上次成功那套）
 * @param recommendedId 推荐档（「按推荐的来」用它；通常与初始 chosen 相同）
 * @param lastUsedId 非空 ⇒ 该档标「上次成功用的就是这套」（本地偏好，不联网）
 */
@Composable
internal fun BaopuStyleCard(
    styles: List<TopicStyle>,
    chosenId: String,
    recommendedId: String,
    lastUsedId: String?,
    onPick: (String) -> Unit,
    onUseRecommended: () -> Unit,
    onShift: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BaopuActionContainer(
        title = stringResource(R.string.baopu_topic_title_style),
        badge = stringResource(R.string.baopu_topic_badge),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.baopu_topic_style_question),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        styles.forEach { s ->
            val selected = s.id == chosenId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(s.id) }
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = { onPick(s.id) })
                Column {
                    Text(
                        text = stringResource(
                            R.string.baopu_topic_style_summary,
                            s.name, s.voice, s.lenMin.toString(), s.lenMax.toString(), s.structure,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (s.id == lastUsedId) {
                        Text(
                            text = stringResource(R.string.baopu_topic_style_last_used),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onUseRecommended,
                modifier = Modifier.weight(1.4f).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.baopu_topic_use_recommended))
            }
            OutlinedButton(
                onClick = onShift,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.baopu_topic_shift_style))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.baopu_topic_later))
            }
        }
    }
}
