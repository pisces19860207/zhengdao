// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.zhengdao.baopu.voice.VoiceDecision
import com.example.zhengdao.baopu.voice.VoiceDraft
import com.example.zhengdao.baopu.voice.VoiceFailure
import com.example.zhengdao.baopu.voice.VoiceGate
import com.example.zhengdao.baopu.voice.VoiceMode
import com.example.zhengdao.baopu.voice.VoicePhase
import com.example.zhengdao.baopu.voice.VoicePrefs
import com.example.zhengdao.baopu.voice.VoiceSession
import com.example.zhengdao.baopu.voice.VoiceSource
import kotlinx.coroutines.delay

/** 语音输入在 Composer 里的唯一接线面（状态 + 动作，不含发送）。 */
internal data class VoiceUi(
    val granted: Boolean,
    val deniedBefore: Boolean,
    /** 「正在听 / 正在收尾」只认这一个相位，不再另存一个布尔量。 */
    val phase: VoicePhase,
    val mode: VoiceMode,
    val source: VoiceSource,
    val decision: VoiceDecision,
    val systemInstalled: Boolean,
    /** 最近一次失败（四档闭集）；null = 这一轮没有失败。 */
    val failure: VoiceFailure?,
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onCancel: () -> Unit,
    val onRequestPermission: () -> Unit,
    val noteUserEdit: (String) -> Unit,
)

/**
 * 一轮会话的持有者；[VoiceSession] 只管文本、账与相位，不认识 Compose。
 *
 * 第六百八十批：相位与失败都由会话回调**推**过来，本类不自己维护「在听」的副本——
 * 副本一多，就会出现「会话已经死了、界面还在正在听」的 G-673-2 式错位。
 */
internal class VoiceController(
    private val engine: AndroidSpeechEngine,
    private val scheduler: MainHandlerScheduler,
    private val onText: (String) -> Unit,
    private val onPhase: (VoicePhase) -> Unit,
    private val onFailure: (VoiceFailure?) -> Unit,
) {
    private var session: VoiceSession? = null

    fun start(source: VoiceSource, baseline: String): Boolean {
        if (session?.active == true) return false
        val blocked = when {
            !engine.granted() -> VoiceFailure.PERMISSION
            !engine.available() -> VoiceFailure.NO_RESPONSE
            else -> null
        }
        val next = VoiceSession(
            draft = VoiceDraft(baseline),
            port = engine,
            source = source,
            ledger = engine.ledger(),
            clock = { System.currentTimeMillis() },
            scheduler = scheduler,
            onText = onText,
            onPhase = onPhase,
            onSettled = onFailure,
        )
        session = next
        return next.start(blocked)
    }

    fun finish() {
        session?.finish()
    }

    fun cancel() {
        session?.cancel()
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
    val scheduler = remember { MainHandlerScheduler() }
    val sink = rememberUpdatedState(onText)
    val baseline = rememberUpdatedState(currentText)

    var granted by remember { mutableStateOf(engine.granted()) }
    var deniedBefore by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(VoicePhase.IDLE) }
    var failure by remember { mutableStateOf<VoiceFailure?>(null) }
    var mode by remember { mutableStateOf(VoicePrefs.getMode(ctx)) }
    var source by remember { mutableStateOf(VoicePrefs.getSource(ctx)) }

    // 与相机权限同款房型：回执不信 result，重读系统状态（部分 ROM 会静默掉弹窗）。
    val requestMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = engine.granted()
        deniedBefore = !result && !granted
    }

    val controller = remember(engine, scheduler) {
        VoiceController(
            engine = engine,
            scheduler = scheduler,
            onText = { sink.value(it) },
            onPhase = { phase = it },
            onFailure = { failure = it },
        )
    }
    DisposableEffect(engine) {
        onDispose {
            controller.dispose()
            engine.release()
        }
    }

    // G-673-2：`pm revoke` 之后不杀进程，进程里的旧读数会让按钮「看着还能用」。
    // 每次回到前台重读一次真实权限；被撤掉就立刻灰掉并给引导。
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, engine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = engine.granted()
                if (now != granted) granted = now
                if (now) deniedBefore = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // 失败文案不常驻：说清楚就撤，免得盖住后面真正的引导行。
    LaunchedEffect(failure) {
        if (failure != null) {
            delay(FAILURE_NOTE_MS)
            failure = null
        }
    }

    val decision = VoiceGate.decide(source, granted, engine.available(), engine.installed())
    return VoiceUi(
        granted = granted,
        deniedBefore = deniedBefore,
        phase = phase,
        mode = mode,
        source = source,
        decision = decision,
        systemInstalled = engine.installed(),
        failure = failure,
        onStart = {
            // 每次启动都重读权限：不信进程内缓存，也不信上一次的 decision。
            granted = engine.granted()
            if (!granted) {
                deniedBefore = true
                failure = VoiceFailure.PERMISSION
            } else {
                failure = null
                controller.start(source, baseline.value())
            }
        },
        onStop = { controller.finish() },
        onCancel = { controller.cancel() },
        onRequestPermission = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
        noteUserEdit = { controller.noteUserEdit(it) },
    )
}

/** 失败文案停留时长：够读完一句，又不至于一直挂在那儿。 */
private const val FAILURE_NOTE_MS = 6_000L
