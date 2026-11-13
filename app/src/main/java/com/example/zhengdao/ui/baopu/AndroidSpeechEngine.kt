// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.zhengdao.baopu.voice.AudioRingBuffer
import com.example.zhengdao.baopu.voice.LocalModelAvailability
import com.example.zhengdao.baopu.voice.MicPermissionState
import com.example.zhengdao.baopu.voice.SpeechCallback
import com.example.zhengdao.baopu.voice.SpeechSessionPort
import com.example.zhengdao.baopu.voice.SystemSpeechAvailability
import com.example.zhengdao.baopu.voice.VoiceLedger
import java.io.File

/**
 * 第六百三十三批 · 系统自带 ASR 的唯一生产实现（装配层）。
 *
 * 口径：
 * - 启动前先 `SpeechRecognizer.isRecognitionAvailable`（本机实测有 MagicVoice 服务）；
 * - `EXTRA_PREFER_OFFLINE = true` ＋ App 侧零网络调用 ⇒ **音频永不离开设备**；
 * - 系统回调 [RecognitionListener.onBufferReceived] 的 PCM 只进 [AudioRingBuffer] 内存，
 *   [release] 时覆写清零；没有 File / 缓存目录 / 上传 / 设备租约；
 * - 本地模型未装（默认）⇒ [installed] 返回 false，设置项灰掉，不假装可用。
 *
 * 只持有 applicationContext，不进任何静态字段（lint StaticFieldLeak）。
 */
internal class AndroidSpeechEngine(private val ctx: Context) :
    SpeechSessionPort,
    SystemSpeechAvailability,
    LocalModelAvailability,
    MicPermissionState {

    private val audio = AudioRingBuffer(CAPACITY_BYTES)
    private var recognizer: SpeechRecognizer? = null
    private var callback: SpeechCallback? = null

    /** 派工单①：启动前的硬检查。 */
    override fun available(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)

    /** 派工单②：没装就是没装，绝不造一个假目录。 */
    override fun installed(): Boolean = localModelFile().isFile

    override fun granted(): Boolean = micGranted(ctx)

    override fun start(callback: SpeechCallback): Boolean {
        if (!available()) return false
        if (!granted()) return false
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull() ?: return false
        this.callback = callback
        recognizer = created
        created.setRecognitionListener(listener)
        created.startListening(offlineIntent())
        return true
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    override fun release() {
        audio.clear()
        recognizer?.destroy()
        recognizer = null
        callback = null
    }

    /** 账落 App 私有 filesDir（不是产物目录、不是工作区、不是日志）。 */
    fun ledger(): VoiceLedger = VoiceLedger(
        File(File(ctx.filesDir, VoiceLedger.DIR_NAME), VoiceLedger.FILE_NAME),
    )

    private fun localModelFile(): File =
        File(File(ctx.filesDir, VoiceLedger.DIR_NAME), LOCAL_MODEL_FILE)

    /** 只要离线识别；不声明任何语言偏好以外的联网参数。 */
    private fun offlineIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit

        /** 音频只在这里过一趟内存缓冲，绝不落盘 / 不进账 / 不进日志。 */
        override fun onBufferReceived(buffer: ByteArray?) {
            if (buffer != null) audio.write(buffer)
        }

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstText(partialResults)
            if (text.isNotEmpty()) callback?.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = firstText(results)
            if (text.isEmpty()) {
                callback?.onError()
            } else {
                callback?.onFinal(text)
            }
        }

        override fun onError(error: Int) {
            callback?.onError()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstText(bundle: Bundle?): String = runCatching {
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    }.getOrDefault("")

    companion object {
        /** 32 KiB 定长内存环：够接住系统回调的 PCM，且不会无限增长。 */
        const val CAPACITY_BYTES = 32 * 1024
        const val LOCAL_MODEL_FILE = "model.bin"

        /** 只判状态，不发起申请（申请点在输入框的引导行）。 */
        fun micGranted(ctx: Context): Boolean = runCatching {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
        }.getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED
    }
}
