// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第六百三十三批 · 一次识别的编排（纯 JVM）；第六百八十批补「结束」这一半；
 * 第七百批把「服务压根没干活」从猜测变成读数。
 *
 * 入口 → 处理 → 回显整条链都在这里，且**没有发送口**：[VoiceSession] 只把文本交回输入框，
 * 发送永远是用户点「发送」按钮那一个动作。账只记「时长 / 来源 / 是否成功」（[VoiceLedger]）。
 *
 * 第六百八十批改了什么（真机证据见 680-语音结束-交付）：
 * 1. **相位唯一**：[phase] 是界面「正在听」的唯一来源，收尾也走它，失败一律回 [IDLE]；
 * 2. **两个看门狗**：启动后一直不 ready（服务压根没起来）与停止后一直不回结果，都到点收口；
 * 3. **停止后 3 秒兜底**：有 partial 就落地（不吞用户说过的话），没有就明说「没听清」；
 * 4. **失败四档**：每一条失败路径都带 [VoiceFailure]，界面据此说一句人话。
 *
 * 第七百批换掉了其中「等 ready」那一只：真机是**四个回调一个都不来**，
 * 只盯 ready 会把「来了 partial 却不发 ready」的正常引擎误杀。现在改成
 * **12 秒零回调硬超时**（[probeTimeoutMs]，可配）：窗口内收到过任何一个回调就不判，
 * 一个都没来才判「系统识别未响应」，并留一份 [VoiceProbe] 读数进账（来源/时长/回调计数/结论）。
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
    /** 12 秒窗口可配：装配层不传就是派工单口径的 [VoiceProbe.TIMEOUT_MS]。 */
    private val probeTimeoutMs: Long = VoiceProbe.TIMEOUT_MS,
    /** 探测读数外送口（界面据此选「12 秒内无回调」那句文案）。 */
    private val onProbe: (VoiceProbe) -> Unit = {},
) {

    private var startedAtMs = 0L
    private var closed = false
    private var lastPartial: String = ""

    /** 本轮收到的回调数：ready/partial/final/error 四种都算，1 就不是「零回调」。 */
    private var callbacks = 0
    private var probeTimer: Cancellable? = null
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
        // 个别实现在 start 里就同步把 error/final 打回来：此刻已经收口，不许再把相位点亮。
        if (closed) return false
        moveTo(VoicePhase.LISTENING)
        // 第七百批：硬超时只数「回调」，不数时间以外的东西；到了点还零回调才是未响应。
        // 表**不随第一个回调撤掉**——到点由 [onProbeTimeout] 自己复核回调计数，
        // 让「数回调」成为唯一判据（撤表 + 计数两层会互相掩护，负例就测不出来了）。
        probeTimer = scheduler.post(probeTimeoutMs) { onProbeTimeout() }
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
            noteCallback()
        }

        override fun onPartial(text: String) {
            if (closed) return
            noteCallback()
            lastPartial = text
            draft.onPartial(text)
            onText(draft.render())
        }

        override fun onFinal(text: String) {
            if (closed) return
            noteCallback()
            draft.onFinal(text)
            onText(draft.render())
            settle(failure = null, success = true, restore = false)
        }

        override fun onError(reason: VoiceFailure) {
            if (closed) return
            noteCallback()
            // 已经听到半句就按「超时口径」落地，别把用户说过的话一起扔掉。
            if (lastPartial.isEmpty()) {
                settle(reason, success = false, restore = true)
            } else {
                landPartial(VoiceFailure.TIMEOUT)
            }
        }
    }

    /**
     * 记一次回调：四种回调都算「服务在干活」，于是 12 秒窗口到点时不该判「未响应」。
     * 这里只记账、不撤表——判据只留一处，负例才抓得住。
     */
    private fun noteCallback() {
        callbacks++
    }

    private fun cancelProbeTimer() {
        probeTimer?.cancel()
        probeTimer = null
    }

    /**
     * 12 秒到了：窗口内一个回调都没来 ⇒ 判「系统识别未响应」，并留读数。
     * 来过回调就什么都不做（负例：正常引擎不许被误报）。
     */
    private fun onProbeTimeout() {
        if (closed || phase != VoicePhase.LISTENING) return
        val verdict = VoiceProbe.verdict(source, clock() - startedAtMs, callbacks) ?: return
        settle(VoiceFailure.NO_RESPONSE, success = false, restore = true, probe = verdict)
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

    /**
     * 收口：落一条账、释放内存音频缓冲；只跑一次，且必定把相位归零。
     *
     * [probe] 非空 = 这一行是探测记录：除了来源/时长/是否成功，再写回调计数与结论
     * （判据③要一行探测记录）。音频与识别文本仍然一个字节都不进账。
     */
    private fun settle(
        failure: VoiceFailure?,
        success: Boolean,
        restore: Boolean,
        probe: VoiceProbe? = null,
    ) {
        if (closed) return
        closed = true
        cancelProbeTimer()
        stopTimer?.cancel()
        stopTimer = null
        if (restore) onText(draft.cancel())
        val elapsed = (clock() - startedAtMs).coerceAtLeast(0L)
        val row = VoiceLedger.Row(elapsed, source, success, probe?.callbacks, probe?.conclusion)
        val ok = ledger?.append(row) ?: true
        if (!ok) com.example.zhengdao.core.RunLog.log("语音输入：账没落成（已如实记日志）")
        port.release()
        moveTo(VoicePhase.IDLE)
        if (probe != null) onProbe(probe)
        onSettled(failure)
    }

    companion object {
        /** 停止后等最终结果的上限：派工单判据写的就是 3 秒。 */
        const val STOP_TIMEOUT_MS = 3_000L
    }
}
