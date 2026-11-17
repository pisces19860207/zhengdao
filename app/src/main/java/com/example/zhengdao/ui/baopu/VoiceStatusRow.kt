// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.compose.foundation.layout.Column
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
import androidx.core.net.toUri
import com.example.zhengdao.R
import com.example.zhengdao.baopu.voice.SwitchBlock
import com.example.zhengdao.baopu.voice.VoiceFailure
import com.example.zhengdao.baopu.voice.VoiceGuide
import com.example.zhengdao.baopu.voice.VoicePhase
import com.example.zhengdao.baopu.voice.VoiceProbe
import com.example.zhengdao.baopu.voice.VoiceRetryPolicy
import com.example.zhengdao.ui.theme.zhengdaoErrorText
import com.example.zhengdao.ui.theme.zhengdaoLinkText

/**
 * 第六百三十三批 · 状态行；第六百八十批补「显式停止」与四档失败文案；
 * 第七百批补「12 秒零回调」这句话与「切换识别通道」入口。
 *
 * 判据⑥：权限拒绝 ⇒ 麦克风按钮灰掉（VoiceMicButton 按 decision.enabled）＋ 这里给一条
 * 可点的引导，绝不静默失败。首次拒绝就地发起运行时请求；已拒绝过则指去系统权限页。
 *
 * 第六百八十批：
 * - 正在听时给两颗按钮——**「停止」**（收尾，最多等 3 秒）与「取消」（整段丢弃）。
 *   只有松手/再点一下时，HOLD 被外层抢手势就等于取消，用户没有第二条路；
 * - 收尾途中显示「正在收尾…」，让人知道还在等识别端；
 * - 失败按四档各说一句人话（[VoiceFailure]），不再「回滚了但一声不响」。
 *
 * 第七百批：
 * - 未响应那句要看**读数**说：零回调（[VoiceProbe.silent]）才说「12 秒内无回调」，
 *   启动就失败那种只说「识别服务没响应」——不许把没等过的 12 秒说成等过了；
 * - 未响应旁边给「切换识别通道」：693 未合入 ⇒ **灰着并注明**（[SwitchBlock]）；
 * - 连续两次没成 ⇒ 常驻一句「不再自动重试，请手动再试」。
 */
@Composable
internal fun VoiceStatusRow(ui: VoiceUi) {
    if (ui.phase != VoicePhase.IDLE) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (ui.phase == VoicePhase.LISTENING) R.string.voice_listening else R.string.voice_stopping,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (ui.phase == VoicePhase.LISTENING) {
                TextButton(onClick = ui.onStop) {
                    Text(stringResource(R.string.voice_stop), color = zhengdaoLinkText())
                }
            }
            TextButton(onClick = ui.onCancel) {
                Text(stringResource(R.string.voice_cancel), color = zhengdaoLinkText())
            }
        }
        return
    }
    val res = LocalResources.current
    if (ui.failure != null) {
        FailureBlock(ui, res)
        return
    }
    if (ui.decision.guide != VoiceGuide.NONE) {
        GuideBlock(ui, res)
        return
    }
    // 手动入口之外不再有任何自动动作：这句话就是在说「自动重试已经停了」。
    if (VoiceRetryPolicy.gaveUp(ui.streak)) {
        Text(
            text = res.getString(R.string.voice_streak_stop_retry, VoiceRetryPolicy.LIMIT),
            style = MaterialTheme.typography.labelSmall,
            color = zhengdaoErrorText(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

/** 失败块：一句人话 ＋（未响应时）切换通道入口；入口灰着就写明为什么。 */
@Composable
private fun FailureBlock(ui: VoiceUi, res: Resources) {
    val noResponse = ui.failure == VoiceFailure.NO_RESPONSE
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = failureText(ui.failure, ui.probe, res),
                style = MaterialTheme.typography.labelSmall,
                color = zhengdaoErrorText(),
                modifier = Modifier.weight(1f),
            )
            if (noResponse) {
                TextButton(onClick = ui.onSwitchChannel, enabled = ui.channelSwitch.enabled) {
                    Text(
                        text = stringResource(R.string.voice_channel_switch),
                        color = if (ui.channelSwitch.enabled) {
                            zhengdaoLinkText()
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        if (noResponse) SwitchNote(ui.channelSwitch.block, res)
    }
}

/** 灰掉的入口必须有一句原因，不能只变个颜色。 */
@Composable
private fun SwitchNote(block: SwitchBlock, res: Resources) {
    val note = when (block) {
        SwitchBlock.NONE -> return
        SwitchBlock.LOCAL_NOT_WIRED -> res.getString(R.string.voice_channel_local_unwired)
        SwitchBlock.LOCAL_NOT_INSTALLED -> res.getString(R.string.voice_channel_local_missing)
    }
    Text(
        text = note,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 不可用原因的引导块（权限 / 系统不可用 / 本地未装）。 */
@Composable
private fun GuideBlock(ui: VoiceUi, res: Resources) {
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

/**
 * 四档失败各一句：每一档都必须说清「为什么没成、下一步怎么办」。
 * 「未响应」这一档要分两种说法——等满 12 秒零回调才敢说 12 秒。
 */
private fun failureText(failure: VoiceFailure?, probe: VoiceProbe?, res: Resources): String = when (failure) {
    VoiceFailure.NOT_HEARD -> res.getString(R.string.voice_fail_not_heard)
    VoiceFailure.NO_RESPONSE -> if (probe?.silent == true) {
        res.getString(R.string.voice_probe_silent)
    } else {
        res.getString(R.string.voice_fail_no_response)
    }
    VoiceFailure.TIMEOUT -> res.getString(R.string.voice_fail_timeout)
    VoiceFailure.PERMISSION -> res.getString(R.string.voice_fail_permission)
    null -> ""
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
