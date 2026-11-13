// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第六百三十三批 · 平台能力端口（纯 JVM）。
 *
 * 房型照 ContentUri.kt 的 ContentOpener：装配层注入真实现（AndroidSpeechEngine），
 * JVM 单测注入内存假件（FakeAudioRecord）。工具层不认识 Context / SpeechRecognizer，
 * 所以本包里没有任何 Android 类型，也不会把 Context 塞进静态字段。
 */
internal interface SpeechCallback {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError()
}

/** 一次识别会话。音频缓冲由实现方持有在内存里，[release] 时清零。 */
internal interface SpeechSessionPort {
    fun start(callback: SpeechCallback): Boolean
    fun stop()
    fun cancel()
    fun release()
}

/** 系统 ASR 是否可用（生产 = SpeechRecognizer.isRecognitionAvailable）。 */
internal fun interface SystemSpeechAvailability {
    fun available(): Boolean
}

/** 本地模型是否已装；未装 ⇒ 设置项灰掉。 */
internal fun interface LocalModelAvailability {
    fun installed(): Boolean
}

/** 麦克风权限是否已给（只判状态，不发起申请）。 */
internal fun interface MicPermissionState {
    fun granted(): Boolean
}
