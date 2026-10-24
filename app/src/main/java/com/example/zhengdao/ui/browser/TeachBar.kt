// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 · 玄览「教它一次」的 UI 半边：录制指示条＋确认卡。
// 判据与状态全在 baopu 侧（TeachSessionUiModel/TeachLearner），本文件只渲染与转发。
package com.example.zhengdao.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.TeachSkillDraft

/**
 * 示教指示条（底栏上方一行小胶囊）：告诉用户「正在记」，别让无感的录制变成隐私事故。
 * 只渲染；步数由 BrowserScreen 从控制器读。
 */
@Composable
fun TeachRecordingBadge(stepCount: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = pluralStringResource(R.plurals.teach_recording_badge, stepCount, stepCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * 确认卡（§1「确认才落盘」的 UI 半边）：技能名可改，步骤摘要可读，
 * 「保存技能」走 TeachReplayStore（184 写口），「放弃」一个字节不写。
 */
@Composable
fun TeachConfirmSheet(
    draft: TeachSkillDraft,
    onSave: (name: String) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(draft) { mutableStateOf(draft.skill.name) }
    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text(stringResource(R.string.teach_confirm_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.teach_confirm_name_label)) },
                )
                Spacer(Modifier.padding(top = 8.dp))
                Text(
                    text = draft.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }) {
                Text(stringResource(R.string.teach_confirm_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.teach_confirm_discard))
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}
