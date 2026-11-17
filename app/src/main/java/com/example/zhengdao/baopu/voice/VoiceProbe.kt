// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第七百批 · 「系统识别一个字都不回」的探测读数（纯 JVM）。
 *
 * 真机读数（Honor MagicVoice，见 680 与 700 交付）：点下去之后 12 秒里
 * `onReadyForSpeech / onPartialResults / onResults / onError` **一个都不来**，
 * `audio_flinger` 当天 0 条录音会话——服务侧连开始都没接单。也就是说，
 * 「等结果」和「服务没干活」在界面上长得一模一样，用户只会觉得「死键」。
 *
 * 本类的唯一职责：把「这个 12 秒窗口里到底有没有过回调」变成一个可判、可记账、
 * 可复现的读数，而不是靠猜。
 *
 * 隐私：这里只有来源/时长/回调计数三个元数据，没有音频、没有识别文本。
 */
internal data class VoiceProbe(
    val source: VoiceSource,
    val durationMs: Long,
    val callbacks: Int,
) {
    init {
        require(durationMs >= 0L) { "时长不许是负数" }
        require(callbacks >= 0) { "回调计数不许是负数" }
    }

    /** 一个回调都没来 ⇒ 这就是「系统识别未响应」的那一档。 */
    val silent: Boolean get() = callbacks == 0

    /**
     * 账里那一列「结论」：闭集词，不是自由文本。
     * 日后换通道 / 换引擎时，这两张词表仍能对上同一件事。
     */
    val conclusion: String get() = if (silent) SILENT else ANSWERED

    companion object {
        /**
         * 硬超时默认值：派工单写的就是 12 秒。
         * 装配层可以传更短的值（[VoiceSession] 的同名构造参数），但默认值只有这一个来源。
         */
        const val TIMEOUT_MS = 12_000L

        /** 结论词：12 秒窗口内零回调。 */
        const val SILENT = "no_callbacks"

        /** 结论词：窗口内有过回调（不该判「未响应」）。 */
        const val ANSWERED = "callbacks_seen"

        /**
         * 判据：零回调 ⇒ 出一份「未响应」读数；只要来过任何一个回调 ⇒ null（不误报）。
         *
         * 负例就在这一行：`callbacks = 1` 必须拿到 null，否则「正常回调也报未响应」。
         */
        fun verdict(source: VoiceSource, durationMs: Long, callbacks: Int): VoiceProbe? =
            if (callbacks == 0) VoiceProbe(source, durationMs, callbacks) else null
    }
}
