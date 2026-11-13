// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第六百三十三批 · 一次识别的编排（纯 JVM）。
 *
 * 入口 → 处理 → 回显整条链都在这里，且**没有发送口**：[finish] 只把文本交回输入框，
 * 发送永远是用户点「发送」按钮那一个动作。账只记「时长 / 来源 / 是否成功」（[VoiceLedger]）。
 */
internal class VoiceSession(
    private val draft: VoiceDraft,
    private val port: SpeechSessionPort,
    private val source: VoiceSource,
    private val ledger: VoiceLedger?,
    private val clock: () -> Long,
    private val onText: (String) -> Unit,
    private val onSettled: () -> Unit = {},
) {

    private var startedAtMs = 0L
    private var ended = false

    val active: Boolean get() = !ended

    fun start(): Boolean {
        if (ended) return false
        if (!port.start(callback)) return false
        startedAtMs = clock()
        return true
    }

    /** 用户在识别途中改了输入框：交给草稿冻结，后续 partial 不覆盖。 */
    fun noteUserEdit(newText: String) {
        if (ended) return
        draft.onUserEdit(newText)
    }

    /** 松手 / 再点一下：请识别端收尾，最终文本由 onFinal 交回。 */
    fun finish() {
        if (ended) return
        port.stop()
    }

    /** 取消：整段丢弃，输入框回到开始前内容。 */
    fun cancel(): String {
        if (!ended) port.cancel()
        val restored = draft.cancel()
        onText(restored)
        close(success = false)
        return restored
    }

    private val callback = object : SpeechCallback {
        override fun onPartial(text: String) {
            if (ended) return
            draft.onPartial(text)
            onText(draft.render())
        }

        override fun onFinal(text: String) {
            if (ended) return
            draft.onFinal(text)
            onText(draft.render())
            close(success = true)
        }

        override fun onError() {
            if (ended) return
            onText(draft.cancel())
            close(success = false)
        }
    }

    /** 收口：落一条三字段账、释放内存音频缓冲；只跑一次。 */
    private fun close(success: Boolean) {
        if (ended) return
        ended = true
        val elapsed = (clock() - startedAtMs).coerceAtLeast(0L)
        val ok = ledger?.append(VoiceLedger.Row(elapsed, source, success)) ?: true
        if (!ok) com.example.zhengdao.core.RunLog.log("语音输入：账没落成（已如实记日志）")
        port.release()
        onSettled()
    }
}
