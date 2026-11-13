// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.voice.VoiceMode
import com.example.zhengdao.baopu.voice.VoicePrefs
import com.example.zhengdao.baopu.voice.VoiceSource
import com.example.zhengdao.ui.SectionCard
import com.example.zhengdao.ui.theme.rDialog
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.ui.theme.zhengdaoReady

/**
 * 第六百三十三批 · 语音输入设置（抱朴设置浮层里的一张卡）。
 *
 * 两件事：交互模式（按住说／点按开始结束）、识别来源（系统默认／本地可选）。
 * 本地模型未装 ⇒ 那一行真的灰掉且写「未安装」，点不动，也不偷偷回退到系统档。
 */
@Composable
internal fun VoiceSettingsCard() {
    val ctx = LocalContext.current
    val res = LocalResources.current
    val engine = remember { AndroidSpeechEngine(ctx.applicationContext) }
    val localInstalled = remember { engine.installed() }
    var mode by rememberSaveable { mutableStateOf(VoicePrefs.getMode(ctx)) }
    var source by rememberSaveable { mutableStateOf(VoicePrefs.getSource(ctx)) }
    var modeDialog by remember { mutableStateOf(false) }

    SectionCard(stringResource(R.string.voice_settings_card)) {
        VoiceOptionRow(
            label = stringResource(R.string.voice_mode_title),
            value = modeLabel(mode),
            enabled = true,
            note = "",
            onClick = { modeDialog = true },
        )
        VoiceOptionRow(
            label = stringResource(R.string.voice_source_system),
            value = if (source == VoiceSource.SYSTEM) res.getString(R.string.voice_selected_mark) else "",
            enabled = true,
            note = "",
            onClick = {
                VoicePrefs.setSource(ctx, VoiceSource.SYSTEM)
                source = VoiceSource.SYSTEM
            },
        )
        VoiceOptionRow(
            label = stringResource(R.string.voice_source_local),
            value = if (!localInstalled) {
                res.getString(R.string.voice_local_not_installed)
            } else if (source == VoiceSource.LOCAL) {
                res.getString(R.string.voice_selected_mark)
            } else {
                ""
            },
            enabled = localInstalled,
            note = if (localInstalled) "" else res.getString(R.string.voice_local_hint),
            onClick = {
                VoicePrefs.setSource(ctx, VoiceSource.LOCAL)
                source = VoiceSource.LOCAL
            },
        )
        Text(
            text = stringResource(R.string.voice_settings_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    if (modeDialog) {
        AlertDialog(
            onDismissRequest = { modeDialog = false },
            shape = RoundedCornerShape(rDialog),
            title = { Text(stringResource(R.string.voice_mode_title)) },
            text = {
                Column {
                    listOf(VoiceMode.HOLD, VoiceMode.TAP).forEach { candidate ->
                        TextButton(onClick = {
                            VoicePrefs.setMode(ctx, candidate)
                            mode = candidate
                            modeDialog = false
                        }) { Text(modeLabel(candidate)) }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun modeLabel(mode: VoiceMode): String = stringResource(
    when (mode) {
        VoiceMode.HOLD -> R.string.voice_mode_hold
        VoiceMode.TAP -> R.string.voice_mode_tap
    },
)

@Composable
private fun VoiceOptionRow(
    label: String,
    value: String,
    enabled: Boolean,
    note: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (note.isNotEmpty()) {
                Text(text = note, style = MaterialTheme.typography.labelSmall, color = zhengdaoErrorText())
            }
        }
        if (value.isNotEmpty()) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) zhengdaoReady() else zhengdaoErrorText(),
            )
        }
    }
}
