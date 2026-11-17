// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第六百三十三批 · 平台能力端口（纯 JVM）；第六百八十批补 ready 与失败档。
 *
 * 房型照 ContentUri.kt 的 ContentOpener：装配层注入真实现（AndroidSpeechEngine），
 * JVM 单测注入内存假件（FakeAudioRecord）。工具层不认识 Context / SpeechRecognizer，
 * 所以本包里没有任何 Android 类型，也不会把 Context 塞进静态字段。
 *
 * 第六百八十批为什么加 [SpeechCallback.onReady]：真机实测 MagicVoice 会「接住 bind、
 * 开一条会话，然后一次回调都不给」——界面只能靠「服务有没有真的 ready」判断它起没起来，
 * 否则永远停在「正在听…」（G-673-2 同源）。
 */
internal interface SpeechCallback {
    /** 识别端真的起来了（生产 = RecognitionListener.onReadyForSpeech）。 */
    fun onReady()
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(reason: VoiceFailure)
}

/** 一次识别会话。音频缓冲由实现方持有在内存里，[release] 时清零。 */
internal interface SpeechSessionPort {
    fun start(callback: SpeechCallback): Boolean
    fun stop()
    fun cancel()
    fun release()
}

/**
 * 定时器端口：会话只声明「多久之后算超时」，不认 Handler / Looper / Thread。
 *
 * 生产实现挂主线程 Handler（`MainHandlerScheduler`），JVM 单测注入手摇时钟
 * （`ManualScheduler`），于是 3 秒兜底可以被确定性地复现，而不是靠 sleep 赌。
 */
internal fun interface VoiceScheduler {
    fun post(delayMs: Long, task: () -> Unit): Cancellable
}

/** 取消一个还没到点的定时任务。 */
internal fun interface Cancellable {
    fun cancel()
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
