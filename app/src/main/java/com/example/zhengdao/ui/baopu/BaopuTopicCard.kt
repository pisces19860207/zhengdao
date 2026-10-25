// 独立开发声明：本文件为本项目原创。
//
// 第一百九十批 · 题材与写法卡的**建议卡／确认卡／进度卡**（只渲染，零判据）。
//
// 红线对照（派工单 §红线 / §0.1 / §0.3）：
// - 不新增第五种卡：三张卡全部长在既有 [BaopuActionContainer]（行动卡四档容器）里，
//   与提问卡/确认卡同一家族外形；
// - 并 164：卡在**输入栏上方**内联展开（位置由 BaopuScreen 的 Column 布局保证，
//   本文件不弹 Dialog、不盖输入框），用户不采纳也能直接打字发送；
// - 确认卡大按钮「就按这些写」≥48dp 触控；点之前**零落盘**（落盘只发生在控制器的
//   批量产出段，走 179 的 BaopuLongformStore，本文件不碰任何 IO）。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.TopicCandidate
import com.example.zhengdao.baopu.TopicStyle
import com.example.zhengdao.baopu.TopicWritingState

/**
 * 建议卡（题材候选，可换可多选）。
 *
 * @param candidates 本批候选（顺序即池子顺序，不重排）
 * @param selected 选中的种子 id（默认全选由状态机保证；点行/勾选框切换）
 * @param clashCount 本批查重撞掉的条数（「报告要写撞了哪条」的卡面摘要；逐条证据在交付报告）
 * @param exhausted 池子凑不满一批 ⇒ 说明行
 */
@Composable
internal fun BaopuTopicSuggestCard(
    candidates: List<TopicCandidate>,
    selected: Set<String>,
    clashCount: Int,
    exhausted: Boolean,
    onSelect: (String) -> Unit,
    onChangeBatch: () -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BaopuActionContainer(
        title = stringResource(R.string.baopu_topic_title_suggest),
        badge = stringResource(R.string.baopu_topic_badge),
        modifier = modifier,
    ) {
        candidates.forEach { c ->
            val checked = c.seed.id in selected
            // 读屏播报的就是这条题目（不是按钮文案——勾选框与按钮各说各的）
            val rowDesc = c.seed.title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onSelect(c.seed.id) },
                    modifier = Modifier.semantics { contentDescription = rowDesc },
                )
                Column(Modifier.padding(top = 4.dp)) {
                    Text(
                        text = c.seed.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.baopu_topic_why, c.reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        text = stringResource(R.string.baopu_topic_track, c.seed.track),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        if (exhausted) {
            Text(
                text = stringResource(R.string.baopu_topic_exhausted),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (clashCount > 0) {
            Text(
                text = stringResource(R.string.baopu_topic_clash_note, clashCount.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onChangeBatch,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.baopu_topic_change_batch))
            }
            Button(
                onClick = onAccept,
                enabled = selected.isNotEmpty(),
                modifier = Modifier.weight(1.4f).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.baopu_topic_accept_selected))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.baopu_topic_later))
            }
        }
    }
}

/**
 * 确认卡（开写闸）：汇总题材／写法／落点／预计字数，大按钮「就按这些写」。
 *
 * 打字绕不过它（状态机里只有 ConfirmWrite 能开写闸），按钮按下之前零落盘零台账。
 *
 * @param tasteNote 第二百零二批：本轮会参考/避开哪几篇（`TasteProfile.cardNote` 的产物；
 *        null＝没档案也没范例，卡面不显示这一行）。文案由控制器现算，卡只负责显示。
 */
@Composable
internal fun BaopuTopicConfirmCard(
    topics: List<TopicCandidate>,
    style: TopicStyle,
    dirRel: String,
    estimate: String,
    onConfirm: () -> Unit,
    onEditTopics: () -> Unit,
    onEditStyle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tasteNote: String? = null,
) {
    val confirmDesc = stringResource(R.string.baopu_topic_confirm_write)
    BaopuActionContainer(
        title = stringResource(R.string.baopu_topic_title_confirm),
        badge = stringResource(R.string.baopu_topic_badge),
        modifier = modifier,
    ) {
        topics.forEach { c ->
            Text(
                text = com.example.zhengdao.baopu.TopicSuggest.confirmLine(c, style),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = stringResource(R.string.baopu_topic_confirm_dir, dirRel),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = stringResource(R.string.baopu_topic_confirm_est, estimate),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 第二百零二批：本轮参考/避开的范例（可解释性——用户能看出「这次像哪几篇」）
        tasteNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            text = stringResource(R.string.baopu_topic_confirm_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .heightIn(min = 56.dp)
                .semantics { contentDescription = confirmDesc },
        ) {
            Text(stringResource(R.string.baopu_topic_confirm_write))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onEditTopics, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.baopu_topic_edit_topics))
            }
            OutlinedButton(onClick = onEditStyle, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.baopu_topic_edit_style))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.baopu_topic_later))
            }
        }
    }
}

/**
 * 进度／结果卡：WRITING 相位显示正在写第几篇＋逐篇结果，DONE 相位显示整批明细。
 * 落盘动作不在这里——控制器逐篇驱动引擎＋BaopuLongformStore，这里只报账。
 */
@Composable
internal fun BaopuTopicProgressCard(
    writing: TopicWritingState,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = writing.done >= writing.queue.size
    BaopuActionContainer(
        title = stringResource(
            if (done) R.string.baopu_topic_title_done else R.string.baopu_topic_title_writing,
        ),
        badge = stringResource(
            if (done) R.string.baopu_topic_badge else R.string.baopu_topic_badge_auto,
        ),
        modifier = modifier,
    ) {
        if (!done) {
            val current = writing.queue.getOrNull(writing.done)
            if (current != null) {
                Text(
                    text = stringResource(
                        R.string.baopu_topic_writing_progress,
                        writing.done + 1,
                        writing.queue.size,
                        current.seed.title,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        writing.results.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                Text(
                    text = stringResource(
                        if (r.ok) R.string.baopu_topic_result_ok else R.string.baopu_topic_result_fail,
                        if (r.ok) (r.path ?: r.message) else "${r.title}——${r.message}",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (r.ok) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
        if (!done) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onStop) {
                    Text(stringResource(R.string.baopu_topic_stop_write))
                }
            }
        } else {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.baopu_topic_dismiss))
            }
        }
    }
}
