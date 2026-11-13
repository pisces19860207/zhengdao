// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第六百三十三批 · 模拟 AudioRecord 的内存夹具（判据①）。
 *
 * 每喂一块 PCM 就写进 [AudioRingBuffer]（纯内存）并吐一次更长的 partial；[release] 时
 * 缓冲清零，对应生产 AndroidSpeechEngine 的 onBufferReceived / release 两端。
 * 夹具里没有任何 File / OutputStream，所以它本身也证明不了「音频落盘」，只证明链路。
 */
internal class FakeAudioRecord(
    private val buffer: AudioRingBuffer,
    private val words: List<String>,
) : SpeechSessionPort {

    private var callback: SpeechCallback? = null
    private var fed = 0
    var released = false
        private set

    override fun start(callback: SpeechCallback): Boolean {
        this.callback = callback
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
        callback?.onFinal(words.take(fed).joinToString(""))
    }

    /** 取消由 VoiceSession 自己丢弃整段；端口这边不回吐半截文本。 */
    override fun cancel() = Unit

    override fun release() {
        buffer.clear()
        released = true
    }

    fun fedCount(): Int = fed

    companion object {
        const val PCM_CHUNK_BYTES = 320
    }
}
