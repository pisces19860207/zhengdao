// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.zhengdao.R
import com.example.zhengdao.baopu.voice.VoiceMode
import com.example.zhengdao.baopu.voice.VoicePhase
import com.example.zhengdao.ui.theme.zhengdaoReady

/**
 * 第六百三十三批 · 输入框旁的麦克风按钮；第六百八十批改「结束」这一半。
 *
 * 两模式（设置里选）：
 * - HOLD 按住说：按下开听，松手收尾。**手势被外层抢走也照样收尾**——`tryAwaitRelease`
 *   返回 false 只说明父级（滚动/翻页）把指针拿走了，不等于用户想取消；旧代码在这里
 *   直接 `onCancel()`，结果「一按就取消」。真正的取消只认状态行上那颗「取消」。
 * - TAP 点按：按 [VoicePhase] 判定，第一下开始，第二下收尾；收尾途中再点不重复下发。
 * 不可用（权限被拒 / 系统 ASR 不可用 / 本地模型未装）⇒ 按钮灰掉，引导由状态行给出，
 * 绝不在静默里假装录上了。
 */
@Composable
internal fun VoiceMicButton(ui: VoiceUi, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(ui)
    val desc = stringResource(R.string.voice_mic_desc)
    val gesture = Modifier.pointerInput(ui.mode) {
        detectTapGestures(
            onPress = {
                val s = current
                if (s.mode == VoiceMode.HOLD && s.decision.enabled) {
                    s.onStart()
                    // 返回值忽略：抢走与松开都算「说完了」，取消只认显式按钮。
                    tryAwaitRelease()
                    s.onStop()
                }
            },
            onTap = {
                val s = current
                if (s.mode == VoiceMode.TAP && s.decision.enabled) {
                    when (s.phase) {
                        VoicePhase.IDLE -> s.onStart()
                        VoicePhase.LISTENING -> s.onStop()
                        // 已在收尾：等 3 秒兜底，不再叠一次 stop
                        VoicePhase.STOPPING -> Unit
                    }
                }
            },
        )
    }
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(gesture)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        MicGlyph(
            tint = if (ui.decision.enabled) zhengdaoReady() else MaterialTheme.colorScheme.onSurfaceVariant,
            side = 20.dp,
        )
    }
}
