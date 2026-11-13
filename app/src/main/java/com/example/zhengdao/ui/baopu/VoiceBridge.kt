// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.zhengdao.baopu.voice.VoiceDecision
import com.example.zhengdao.baopu.voice.VoiceDraft
import com.example.zhengdao.baopu.voice.VoiceGate
import com.example.zhengdao.baopu.voice.VoiceMode
import com.example.zhengdao.baopu.voice.VoicePrefs
import com.example.zhengdao.baopu.voice.VoiceSession
import com.example.zhengdao.baopu.voice.VoiceSource

/** 语音输入在 Composer 里的唯一接线面（状态 + 动作，不含发送）。 */
internal data class VoiceUi(
    val granted: Boolean,
    val deniedBefore: Boolean,
    val listening: Boolean,
    val mode: VoiceMode,
    val source: VoiceSource,
    val decision: VoiceDecision,
    val systemInstalled: Boolean,
    val onStart: () -> Unit,
    val onFinish: () -> Unit,
    val onCancel: () -> Unit,
    val onRequestPermission: () -> Unit,
    val noteUserEdit: (String) -> Unit,
)

/** 一轮会话的持有者；[VoiceSession] 只管文本与账，不认识 Compose。 */
internal class VoiceController(
    private val engine: AndroidSpeechEngine,
    private val onText: (String) -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private var session: VoiceSession? = null

    fun start(source: VoiceSource, baseline: String): Boolean {
        if (session?.active == true) return false
        val next = VoiceSession(
            draft = VoiceDraft(baseline),
            port = engine,
            source = source,
            ledger = engine.ledger(),
            clock = { System.currentTimeMillis() },
            onText = onText,
            onSettled = { onState(false) },
        )
        if (!next.start()) return false
        session = next
        onState(true)
        return true
    }

    fun finish() {
        session?.finish()
    }

    fun cancel() {
        session?.cancel()
        onState(false)
    }

    fun noteUserEdit(newText: String) {
        session?.noteUserEdit(newText)
    }

    /** 页面销毁时兜底：正在听就整段丢弃，别把半截文字留在输入框。 */
    fun dispose() {
        if (session?.active == true) session?.cancel()
    }
}

@Composable
internal fun rememberVoiceBridge(
    onText: (String) -> Unit,
    currentText: () -> String,
): VoiceUi {
    val ctx = LocalContext.current
    val engine = remember { AndroidSpeechEngine(ctx.applicationContext) }
    val sink = rememberUpdatedState(onText)
    val baseline = rememberUpdatedState(currentText)

    var granted by remember { mutableStateOf(engine.granted()) }
    var deniedBefore by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(VoicePrefs.getMode(ctx)) }
    var source by remember { mutableStateOf(VoicePrefs.getSource(ctx)) }

    // 与相机权限同款房型：回执不信 result，重读系统状态（部分 ROM 会静默掉弹窗）。
    val requestMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = engine.granted()
        deniedBefore = !result && !granted
    }

    val controller = remember(engine) {
        VoiceController(engine, { sink.value(it) }) { listening = it }
    }
    DisposableEffect(engine) {
        onDispose {
            controller.dispose()
            engine.release()
        }
    }

    val decision = VoiceGate.decide(source, granted, engine.available(), engine.installed())
    return VoiceUi(
        granted = granted,
        deniedBefore = deniedBefore,
        listening = listening,
        mode = mode,
        source = source,
        decision = decision,
        systemInstalled = engine.installed(),
        onStart = { controller.start(source, baseline.value()) },
        onFinish = { controller.finish() },
        onCancel = { controller.cancel() },
        onRequestPermission = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
        noteUserEdit = { controller.noteUserEdit(it) },
    )
}
