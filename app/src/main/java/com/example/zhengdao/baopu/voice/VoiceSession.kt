// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第六百三十三批 · 一次识别的编排（纯 JVM）；第六百八十批补「结束」这一半。
 *
 * 入口 → 处理 → 回显整条链都在这里，且**没有发送口**：[VoiceSession] 只把文本交回输入框，
 * 发送永远是用户点「发送」按钮那一个动作。账只记「时长 / 来源 / 是否成功」（[VoiceLedger]）。
 *
 * 第六百八十批改了什么（真机证据见 680-语音结束-交付）：
 * 1. **相位唯一**：[phase] 是界面「正在听」的唯一来源，收尾也走它，失败一律回 [IDLE]；
 * 2. **两个看门狗**：启动后一直不 ready（服务压根没起来）与停止后一直不回结果，都到点收口；
 * 3. **停止后 3 秒兜底**：有 partial 就落地（不吞用户说过的话），没有就明说「没听清」；
 * 4. **失败四档**：每一条失败路径都带 [VoiceFailure]，界面据此说一句人话。
 */
internal class VoiceSession(
    private val draft: VoiceDraft,
    private val port: SpeechSessionPort,
    private val source: VoiceSource,
    private val ledger: VoiceLedger?,
    private val clock: () -> Long,
    private val scheduler: VoiceScheduler,
    private val onText: (String) -> Unit,
    private val onPhase: (VoicePhase) -> Unit,
    private val onSettled: (VoiceFailure?) -> Unit = {},
) {

    private var startedAtMs = 0L
    private var closed = false
    private var lastPartial: String = ""
    private var readySeen = false
    private var readyTimer: Cancellable? = null
    private var stopTimer: Cancellable? = null

    var phase: VoicePhase = VoicePhase.IDLE
        private set

    val active: Boolean get() = !closed

    /**
     * 起一轮。[blockedBy] 是装配层在启动前查出来的前置失败（权限 / 服务不可用），
     * 传进来就**不起识别**，直接按那一档收口——不假装录上了。
     */
    fun start(blockedBy: VoiceFailure? = null): Boolean {
        if (closed || phase != VoicePhase.IDLE) return false
        startedAtMs = clock()
        if (blockedBy != null) {
            settle(blockedBy, success = false, restore = true)
            return false
        }
        if (!port.start(callback)) {
            settle(VoiceFailure.NO_RESPONSE, success = false, restore = true)
            return false
        }
        moveTo(VoicePhase.LISTENING)
        readyTimer = scheduler.post(READY_TIMEOUT_MS) { onReadyTimeout() }
        // 识别端可能同步就 ready 了（假件如此，个别系统实现也如此）⇒ 表要当场撤掉。
        if (readySeen) {
            readyTimer?.cancel()
            readyTimer = null
        }
        return true
    }

    /** 用户在识别途中改了输入框：交给草稿冻结，后续 partial 不覆盖。 */
    fun noteUserEdit(newText: String) {
        if (closed) return
        draft.onUserEdit(newText)
    }

    /** 松手 / 点停止 / 再点一下：请识别端收尾，最终文本由 onFinal 交回。 */
    fun finish() {
        if (closed || phase != VoicePhase.LISTENING) return
        moveTo(VoicePhase.STOPPING)
        // 先挂表再请识别端收尾：识别端同步回结果时，settle 会把这张表一起撤掉。
        stopTimer = scheduler.post(STOP_TIMEOUT_MS) { onStopTimeout() }
        port.stop()
    }

    /** 取消：整段丢弃，输入框回到开始前内容。取消不是失败，但也不记成功。 */
    fun cancel() {
        if (closed) return
        port.cancel()
        settle(failure = null, success = false, restore = true)
    }

    private val callback = object : SpeechCallback {
        override fun onReady() {
            if (closed) return
            readySeen = true
            readyTimer?.cancel()
            readyTimer = null
        }

        override fun onPartial(text: String) {
            if (closed) return
            lastPartial = text
            draft.onPartial(text)
            onText(draft.render())
        }

        override fun onFinal(text: String) {
            if (closed) return
            draft.onFinal(text)
            onText(draft.render())
            settle(failure = null, success = true, restore = false)
        }

        override fun onError(reason: VoiceFailure) {
            if (closed) return
            // 已经听到半句就按「超时口径」落地，别把用户说过的话一起扔掉。
            if (lastPartial.isEmpty()) {
                settle(reason, success = false, restore = true)
            } else {
                landPartial(VoiceFailure.TIMEOUT)
            }
        }
    }

    /** 启动后一直不 ready：服务接住了 bind 却不出声，不能一直挂着「正在听…」。 */
    private fun onReadyTimeout() {
        if (closed || phase != VoicePhase.LISTENING || readySeen) return
        settle(VoiceFailure.NO_RESPONSE, success = false, restore = true)
    }

    /** 停止后 3 秒还没等到最终结果：有半句就落地，没有就明说没听清。 */
    private fun onStopTimeout() {
        if (closed || phase != VoicePhase.STOPPING) return
        if (lastPartial.isEmpty()) {
            settle(VoiceFailure.NOT_HEARD, success = false, restore = true)
        } else {
            landPartial(VoiceFailure.TIMEOUT)
        }
    }

    /** 把最后一段 partial 定稿进输入框，并按「没等到最终结果」那一档说清楚。 */
    private fun landPartial(failure: VoiceFailure) {
        draft.onFinal(lastPartial)
        onText(draft.render())
        settle(failure, success = false, restore = false)
    }

    private fun moveTo(next: VoicePhase) {
        phase = next
        onPhase(next)
    }

    /** 收口：落一条三字段账、释放内存音频缓冲；只跑一次，且必定把相位归零。 */
    private fun settle(failure: VoiceFailure?, success: Boolean, restore: Boolean) {
        if (closed) return
        closed = true
        readyTimer?.cancel()
        stopTimer?.cancel()
        readyTimer = null
        stopTimer = null
        if (restore) onText(draft.cancel())
        val elapsed = (clock() - startedAtMs).coerceAtLeast(0L)
        val ok = ledger?.append(VoiceLedger.Row(elapsed, source, success)) ?: true
        if (!ok) com.example.zhengdao.core.RunLog.log("语音输入：账没落成（已如实记日志）")
        port.release()
        moveTo(VoicePhase.IDLE)
        onSettled(failure)
    }

    companion object {
        /** 停止后等最终结果的上限：派工单判据写的就是 3 秒。 */
        const val STOP_TIMEOUT_MS = 3_000L

        /** 启动后等 ready 的上限：再慢的冷启动也该出声了，超过就是「服务没起来」。 */
        const val READY_TIMEOUT_MS = 5_000L
    }
}
