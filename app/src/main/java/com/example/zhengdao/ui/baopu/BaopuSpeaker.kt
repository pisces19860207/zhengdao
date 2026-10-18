// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：`docs/接线审计-2026-10-18.md` §5 P4 —— 「朗读（系统 TTS）」在账上写着、
// 代码里 `TextToSpeech`/`tts`/`朗读` 全仓 0 命中。本文件把它补上（2026-10-18 蓝总管）。
package com.example.zhengdao.ui.baopu

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.zhengdao.R
import com.example.zhengdao.rootfs.RunLog
import java.util.Locale

/**
 * 系统 TTS 朗读（手机自带的朗读引擎，不联网、不引第三方库）。
 *
 * 三条纪律：
 * 1. **引擎是异步就绪的**：`TextToSpeech` 构造后要等 `onInit(SUCCESS)` 才能说话 ——
 *    这之前进来的点击**排队一次**（[pending]），就绪后自动补说；不许「点了没反应」。
 * 2. **失败必须说出来**：引擎缺语言包 / 初始化失败 ⇒ 弹一条 Toast 并记 `RunLog`，
 *    不许静默（项目铁律：不能静默）。
 * 3. **跟着页面走**：页面离开时 [release]；同一段再点一次＝停（toggle）。
 *
 * 线程：TTS 回调在主线程，[tts] 的读写都在主线程（Compose 点击也主线程），不加锁。
 */
internal class BaopuSpeaker(private val ctx: Context) {

    /** 0＝空闲 1＝正在读（用于按钮文案与再次点击即停） */
    var speaking by mutableStateOf(false)
        private set

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var current: String? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        speaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        speaking = false
                        current = null
                    }

                    override fun onError(utteranceId: String?) {
                        speaking = false
                        current = null
                    }
                })
                pending?.let { text ->
                    pending = null
                    speak(text)
                }
            } else {
                failed(ctx.getString(R.string.baopu_speak_failed))
            }
        }
    }

    /** 读一段文字；同一段正在读 ⇒ 停。 */
    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (current == clean && speaking) {
            stop()
            return
        }
        val engine = tts
        if (engine == null || !ready) {
            // 引擎还没就绪（首次点击常见）⇒ 排队一次，就绪后补说
            pending = clean
            return
        }
        stop()
        val locale = pickLocale(clean)
        val available = engine.isLanguageAvailable(locale)
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
            failed(ctx.getString(R.string.baopu_speak_no_language))
            return
        }
        engine.language = locale
        current = clean
        speaking = true
        engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UTTER_ID)
    }

    fun stop() {
        speaking = false
        current = null
        tts?.stop()
    }

    fun release() {
        pending = null
        speaking = false
        current = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun failed(msg: String) {
        RunLog.log("[baopu-tts] $msg")
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val UTTER_ID = "baopu-read-aloud"

        /** 有汉字就中文，否则英文 —— 只按内容挑，不跟系统语言走（她的手机是中文系统但会读英文原文）。 */
        fun pickLocale(text: String): Locale =
            if (text.any { it.code in 0x4E00..0x9FFF }) Locale.SIMPLIFIED_CHINESE else Locale.US
    }
}
