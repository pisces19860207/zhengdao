// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零一批 · 表单类平台场景的**渲染层**（只渲染，零判据）。
//
// 红线对照（派工单 §1/§红线）：
// - 卡上只有两个动作：**「复制全文」与「打开页面」**——没有「创建」「发布」「保存」
//   按钮，也没有替用户按快捷键的入口。这一条在渲染层是**结构性**的：本文件里
//   `Button`/`OutlinedButton` 的 onClick 只连 [onCopy] 与 [onOpen] 两个回调。
// - 走四档容器里的**行动卡**（它在等你动手：去页面上点创建）——不新造第五种卡。
// - 触控热区 ≥48dp（139 批口径）；圆角走令牌（rSheet/rChip），不裸写。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.baopu.PublishCard
import com.example.zhengdao.baopu.PublishRow
import com.example.zhengdao.ui.theme.rSheet
import com.example.zhengdao.ui.theme.zhengdaoErrorText

/** 浮层的可观察状态（控制器算好再给，渲染层不改它）。 */
internal data class FormFillUiState(
    val templateName: String,
    /** 当前第几张（1 起；全填完时等于 total）。 */
    val index: Int,
    val total: Int,
    /** 当前待发布卡（null ＝ 还没填或已全部填完）。 */
    val card: PublishCard?,
    val done: Boolean,
    /** 一句提示（阻塞原因/进度摘要）。 */
    val note: String? = null,
)

/**
 * 待发布卡：**最终值 ＋ 截图 ＋ 两个按钮**。
 *
 * @param card 出卡数据（[PublishCard] 里没有 submit/publish 字段——不是忘了加）
 * @param onCopy 「复制全文」：把 [PublishCard.copyText] 放进剪贴板（由调用方做，本文件不碰 IO）
 * @param onOpen 「打开页面」：跳到那个页面，用户自己点创建
 */
@Composable
internal fun FormFillPublishCard(
    card: PublishCard,
    onCopy: (String) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BaopuActionContainer(
        title = card.title,
        badge = stringResource(R.string.form_publish_badge),
        modifier = modifier,
    ) {
        card.rows.forEach { row -> PublishRowLine(row) }
        if (card.shotPath != null) {
            Text(
                text = stringResource(R.string.form_publish_shot, card.shotPath),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.form_publish_no_shot),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        card.notes.forEach { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = zhengdaoErrorText(),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = stringResource(R.string.form_publish_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { onCopy(card.copyText) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.form_publish_copy))
            }
            OutlinedButton(
                onClick = { onOpen(card.openUrl) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.form_publish_open))
            }
        }
    }
}

/** 一行「字段 = 值」；不过的标红并把原因写在后面。 */
@Composable
private fun PublishRowLine(row: PublishRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = if (row.ok) "✓" else "✗",
            style = MaterialTheme.typography.bodySmall,
            color = if (row.ok) MaterialTheme.colorScheme.primary else zhengdaoErrorText(),
        )
        Column(Modifier.padding(start = 6.dp)) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = row.value.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (!row.ok && row.reason != null) {
                Text(
                    text = row.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = zhengdaoErrorText(),
                )
            }
        }
    }
}

/**
 * 填表浮层：进度 ＋ 当前待发布卡 ＋ 两个流程按钮（填下一张 / 这张已发布）。
 *
 * 位置纪律：浮层（Dialog），不占对话流；关掉不丢进度（状态在控制器里）。
 */
@Composable
internal fun FormFillSheet(
    state: FormFillUiState,
    onNext: () -> Unit,
    onAdvance: () -> Unit,
    onCopy: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(rSheet),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.form_sheet_title, state.templateName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.form_sheet_progress, state.index, state.total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                state.note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                state.card?.let { card ->
                    FormFillPublishCard(
                        card = card,
                        onCopy = onCopy,
                        onOpen = onOpen,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (state.done) {
                    Text(
                        text = stringResource(R.string.form_sheet_done),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 两张流程按钮互斥：有待发布卡时只能「已发布，下一张」
                    Button(
                        onClick = onNext,
                        enabled = !state.done && state.card == null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.form_sheet_next))
                    }
                    OutlinedButton(
                        onClick = onAdvance,
                        enabled = state.card != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.form_sheet_advance))
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.form_sheet_close))
                    }
                }
            }
        }
    }
}
