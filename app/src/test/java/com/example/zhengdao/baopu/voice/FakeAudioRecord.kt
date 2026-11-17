// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第六百三十三批 · 模拟 AudioRecord 的内存夹具（判据①）；第六百八十批补「不说话的服务」。
 *
 * 每喂一块 PCM 就写进 [AudioRingBuffer]（纯内存）并吐一次更长的 partial；[release] 时
 * 缓冲清零，对应生产 AndroidSpeechEngine 的 onBufferReceived / release 两端。
 * 夹具里没有任何 File / OutputStream，所以它本身也证明不了「音频落盘」，只证明链路。
 *
 * 三个开关专门用来复现真机上的坏情形：
 * - [readyOnStart] = false ⇒ 接住 bind 却一次回调都不给（Honor MagicVoice 实测就是这个）；
 * - [respondToStop] = false ⇒ stopListening 之后也不回 onResults（真机同样实测到）；
 * - [startOk] = false ⇒ 识别器压根建不起来。
 */
internal class FakeAudioRecord(
    private val buffer: AudioRingBuffer,
    private val words: List<String>,
) : SpeechSessionPort {

    private var callback: SpeechCallback? = null
    private var fed = 0
    var released = false
        private set

    /** 生产侧 onReadyForSpeech 会不会到。 */
    var readyOnStart = true

    /** stop() 之后会不会回最终结果。 */
    var respondToStop = true

    /** start() 本身成不成。 */
    var startOk = true

    override fun start(callback: SpeechCallback): Boolean {
        if (!startOk) return false
        this.callback = callback
        if (readyOnStart) callback.onReady()
        return true
    }

    /** 一块 PCM 到达 ⇒ 一次 partial。返回 false 表示没有更多块。 */
    fun feedPcm(): Boolean {
        if (fed >= words.size) return false
        buffer.write(ByteArray(PCM_CHUNK_BYTES) { (fed + 1).toByte() })
        fed++
        callback?.onPartial(words.take(fed).joinToString(""))
        return true
    }

    /** 收尾：把已听到的词定稿（生产侧是 onResults）。 */
    override fun stop() {
        if (!respondToStop) return
        callback?.onFinal(words.take(fed).joinToString(""))
    }

    /** 取消由 VoiceSession 自己丢弃整段；端口这边不回吐半截文本。 */
    override fun cancel() = Unit

    override fun release() {
        buffer.clear()
        released = true
    }

    /** 模拟识别端报错（四档之一）。 */
    fun failWith(reason: VoiceFailure) {
        callback?.onError(reason)
    }

    fun fedCount(): Int = fed

    companion object {
        const val PCM_CHUNK_BYTES = 320
    }
}
