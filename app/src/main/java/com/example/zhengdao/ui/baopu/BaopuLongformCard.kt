// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.zhengdao.R
import com.mikepenz.markdown.m3.Markdown

/**
 * 第一百七十九批 · 长文/微头条**输出卡**。
 *
 * 红线对照（派工单 §1）：
 * - 容器用既有 [BaopuActionContainer]（行动卡四档），**不新增第五种卡**；
 * - 正文渲染用现成 mikepenz 纯 Compose 渲染器（`com.mikepenz.markdown.m3.Markdown`，
 *   依赖 0.38.1 已在 v1.1 调研报告锁版），**不自写解析器**；
 * - 「右上角小复制保留但不再是唯一入口」⇒ 本卡自带**大按钮复制全文**；
 * - 卡内可编辑再复制/保存；**编辑不改模型原文**——原文由调用方持有，草稿只活在本卡状态里；
 * - 保存走 [BaopuLongformStore]（产物/ 平铺，同名不覆盖）。
 */
@Composable
internal fun BaopuLongformCard(markdown: String, modifier: Modifier = Modifier) {
    // 三种视图：渲染（mikepenz）/ Markdown 源码 / 纯文本
    var mode by remember { mutableIntStateOf(0) }
    // 编辑草稿：初始等于模型原文；编辑后 复制/保存 用草稿，原文不动
    var draft by remember(markdown) { mutableStateOf(markdown) }
    var editing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 复制键文案在组合期取好（lint：回调里用 context.getString 不感知配置变更）
    val copyLabel = stringResource(R.string.baopu_longform_copy_all)
    val copyDoneText = stringResource(R.string.baopu_longform_copy_done)

    fun copyAll() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(copyLabel, draft))
        status = copyDoneText
    }

    fun save(ext: String) {
        // 落库出口自己给可读原因（成功带路径；台账冲突也会说明，不静默）
        status = BaopuLongformStore.save(context, draft, ext).message
    }

    @Composable
    fun body(modifier: Modifier = Modifier) {
        when {
            editing -> OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = modifier.fillMaxWidth().heightIn(min = 240.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            mode == 1 -> Text(
                text = draft,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = modifier.fillMaxWidth(),
            )
            mode == 2 -> Text(
                text = BaopuLongformPolicy.toPlainText(draft),
                style = MaterialTheme.typography.bodyMedium,
                modifier = modifier.fillMaxWidth(),
            )
            else -> Markdown(content = draft, modifier = modifier.fillMaxWidth())
        }
    }

    @Composable
    fun toggleRow() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { editing = false; mode = 0 }) {
                Text(stringResource(R.string.baopu_longform_tab_render))
            }
            TextButton(onClick = { editing = false; mode = 1 }) {
                Text(stringResource(R.string.baopu_longform_tab_src))
            }
            TextButton(onClick = { editing = false; mode = 2 }) {
                Text(stringResource(R.string.baopu_longform_tab_plain))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (editing) {
                    editing = false
                } else {
                    editing = true
                }
            }) {
                Text(stringResource(if (editing) R.string.baopu_longform_done else R.string.baopu_longform_edit))
            }
        }
    }

    @Composable
    fun actionRow() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 大按钮：手机上难点的小复制键（右上角）在这一条上不再是唯一入口
            Button(
                onClick = { copyAll() },
                modifier = Modifier.weight(1.4f).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.baopu_longform_copy_all))
            }
            OutlinedButton(onClick = { save("md") }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.baopu_longform_save_md))
            }
            OutlinedButton(onClick = { save("txt") }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.baopu_longform_save_txt))
            }
            TextButton(onClick = { fullscreen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.baopu_longform_fullscreen))
            }
        }
    }

    BaopuActionContainer(
        // 卡头：标题（首行/模型标题）＋ 字数徽标
        title = BaopuLongformPolicy.cardTitle(markdown),
        badge = stringResource(
            R.string.baopu_longform_words,
            BaopuLongformPolicy.wordCount(if (editing) draft else markdown).toString(),
        ),
        modifier = modifier,
    ) {
        toggleRow()
        Spacer(Modifier.width(0.dp))
        body(Modifier.padding(vertical = 6.dp))
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        actionRow()
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                toggleRow()
                body(Modifier.weight(1f, fill = false).padding(vertical = 6.dp))
                actionRow()
            }
        }
    }
}
