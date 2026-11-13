// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第六百三十三批 · 音频只在内存里过一趟（隐私硬线）。
 *
 * 系统 ASR 的 RecognitionListener.onBufferReceived 会把 PCM 回调给 App；这里只做定长
 * 内存环形缓冲，**没有 File / OutputStream / 缓存目录 / 日志**。会话一结束（转完、取消、
 * 出错、页面销毁）就 [clear] 覆写清零，音频不在设备上留下任何副本。
 */
internal class AudioRingBuffer(private val capacityBytes: Int) {

    private val bytes = ByteArray(capacityBytes)
    private var held = 0

    /** 超容量的块只保留最后 [capacityBytes] 字节，不扩容、不落盘。 */
    fun write(chunk: ByteArray) {
        if (chunk.isEmpty() || capacityBytes <= 0) return
        val take = minOf(chunk.size, capacityBytes)
        val from = chunk.size - take
        if (take == capacityBytes) {
            System.arraycopy(chunk, from, bytes, 0, take)
            held = take
            return
        }
        shiftLeft(take)
        System.arraycopy(chunk, from, bytes, capacityBytes - take, take)
        held = minOf(capacityBytes, held + take)
    }

    /** 转写结束即丢：覆写成 0 再置空读数。 */
    fun clear() {
        bytes.fill(0)
        held = 0
    }

    fun heldBytes(): Int = held

    /** 已在内存里读到的那一段（只给测试夹具核对，生产不导出）。 */
    fun snapshot(): ByteArray = bytes.copyOf(held)

    private fun shiftLeft(take: Int) {
        val keep = minOf(held, capacityBytes - take)
        val from = held - keep
        if (keep > 0) System.arraycopy(bytes, from, bytes, 0, keep)
        bytes.fill(0, keep, capacityBytes)
        held = keep
    }
}
