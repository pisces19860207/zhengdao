// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.voice.VoiceGuide
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.ui.theme.zhengdaoLinkText

/**
 * 第六百三十三批 · 状态行：录音中给「取消」，不可用时把原因说清楚。
 *
 * 判据⑥：权限拒绝 ⇒ 麦克风按钮灰掉（VoiceMicButton 按 decision.enabled）＋ 这里给一条
 * 可点的引导，绝不静默失败。首次拒绝就地发起运行时请求；已拒绝过则指去系统权限页。
 */
@Composable
internal fun VoiceStatusRow(ui: VoiceUi) {
    if (ui.listening) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.voice_listening),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = ui.onCancel) {
                Text(stringResource(R.string.voice_cancel), color = zhengdaoLinkText())
            }
        }
        return
    }
    if (ui.decision.guide == VoiceGuide.NONE) return
    val res = LocalResources.current
    val ctx = LocalContext.current
    val message = guideMessage(ui.decision.guide, ui.deniedBefore, res)
    val openSettings: () -> Unit = { openAppSettings(ctx) }
    val action = guideAction(ui.decision.guide, ui.deniedBefore, ui.onRequestPermission, openSettings)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = zhengdaoErrorText(),
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(onClick = action) {
                Text(stringResource(R.string.voice_guide_action), color = zhengdaoLinkText())
            }
        }
    }
}

private fun guideMessage(guide: VoiceGuide, deniedBefore: Boolean, res: Resources): String = when (guide) {
    VoiceGuide.PERMISSION_DENIED -> if (deniedBefore) {
        res.getString(R.string.voice_denied_guide)
    } else {
        res.getString(R.string.voice_permission_guide)
    }
    VoiceGuide.SYSTEM_UNAVAILABLE -> res.getString(R.string.voice_system_unavailable)
    VoiceGuide.LOCAL_NOT_INSTALLED -> res.getString(R.string.voice_local_missing)
    VoiceGuide.NONE -> ""
}

/** 只有权限类给可点动作；能力类只说原因，不假装能修。 */
private fun guideAction(
    guide: VoiceGuide,
    deniedBefore: Boolean,
    request: () -> Unit,
    openSettings: () -> Unit,
): (() -> Unit)? = when (guide) {
    VoiceGuide.PERMISSION_DENIED -> if (deniedBefore) openSettings else request
    else -> null
}

/** 系统「应用详情 → 权限」页；被永久拒绝时唯一还能走的路。 */
private fun openAppSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(("package:" + ctx.packageName).toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
