// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

/** 发一条预设 prompt 的出口（把引擎隔在外面，这套账才能在 JVM 上真跑）。 */
fun interface TaskSender {
    fun send(task: ScheduleTask, nowMs: Long): SendResult
}

/** 一次发送的结果。[outcome] 是 FIRED / FIRED_LATE / QUEUED 三者之一就算发出去了。 */
data class SendResult(val outcome: ScheduleOutcome, val sessionId: Long, val detail: String)

/** 发出去/没发出去要说一声——通知怎么走由装配层实现，pass 自己不认识 Context。 */
interface PassNotifier {
    fun fired(task: ScheduleTask, sessionId: Long, late: Boolean)
    fun failed(task: ScheduleTask, outcome: ScheduleOutcome, detail: String)

    companion object {
        /** 单测用的哑出口：只记不弹。 */
        val NOOP = object : PassNotifier {
            override fun fired(task: ScheduleTask, sessionId: Long, late: Boolean) = Unit
            override fun failed(task: ScheduleTask, outcome: ScheduleOutcome, detail: String) = Unit
        }
    }
}

/** 一趟 pass 的结果。 */
data class PassResult(val sent: Boolean, val moreRemain: Boolean, val handled: Int) {
    /** 还有该发没发出去的 ⇒ Worker 交给 WorkManager 退避重试（不自己起线程）。 */
    val needsRetry: Boolean get() = moreRemain
}

/**
 * 一趟「到点处理」的账（第一百六十七批 §1、§3；不含任何 Android 类型）。
 *
 * 一次 pass **只发一条**：抱朴是全局单活跃会话（`baopu.agent.ActiveSessionGate`），
 * 一趟灌进不同会话必然被拒；剩下的交给 WorkManager 的退避重试，而不是在这里硬塞——
 * 这也顺带满足派工单 §1「不刷屏」。
 *
 * 去重与补跑都只认 [ScheduleRules.slotOf] 那一个字符串：精确闹钟与 WorkManager 兜底
 * 谁先到谁写槽位，后到的读到同一个槽位 ⇒ [ScheduleRules.Reason.ALREADY_FIRED]，
 * 什么都不发、只推进锚点。设备关掉几天再开：窗口内补一次，窗口外只标「已错过」。
 *
 * 整趟只在结束时写一次表：中途每条都落盘会把「发出去了但账没写完」的窗口拉到最长。
 */
class SchedulePass(
    private val store: ScheduleStore,
    private val sender: TaskSender,
    private val notifier: PassNotifier = PassNotifier.NOOP,
) {

    fun run(nowMs: Long): PassResult {
        val rows = store.load().tasks.toMutableList()
        if (rows.isEmpty()) return PassResult(false, false, 0)
        var sent = false
        var moreRemain = false
        var handled = 0
        var changed = false

        for (task in rows.toList().filter { it.enabled }.sortedBy { it.nextRunAtMs }) {
            when (val reason = ScheduleRules.reasonOf(nowMs, task)) {
                ScheduleRules.Reason.FIRE, ScheduleRules.Reason.FIRE_LATE -> {
                    if (sent) {
                        moreRemain = true
                        continue
                    }
                    val late = reason == ScheduleRules.Reason.FIRE_LATE
                    handled++
                    val result = runCatching { sender.send(task, nowMs) }
                        .getOrElse { SendResult(ScheduleOutcome.FAILED, -1L, it.javaClass.simpleName) }
                    when (result.outcome) {
                        ScheduleOutcome.FIRED, ScheduleOutcome.FIRED_LATE, ScheduleOutcome.QUEUED -> {
                            sent = true
                            // 「补跑」是这一趟判出来的（发送出口只管发没发出去），别两处各判一次。
                            val stored = if (late && result.outcome == ScheduleOutcome.FIRED) {
                                ScheduleOutcome.FIRED_LATE
                            } else {
                                result.outcome
                            }
                            val next = ScheduleRules.afterHandled(
                                task = task,
                                nowMs = nowMs,
                                outcome = stored,
                                detail = result.detail,
                                sessionId = result.sessionId,
                                ran = true,
                            )
                            replace(rows, next)
                            changed = true
                            notifier.fired(next, result.sessionId, late)
                        }

                        ScheduleOutcome.REFUSED_BUSY, ScheduleOutcome.FAILED, ScheduleOutcome.MISSED,
                        ScheduleOutcome.NONE,
                        -> {
                            changed = true
                            if (task.attempts + 1 >= ScheduleRules.MAX_ATTEMPTS) {
                                // 到上限：这一趟翻篇（写槽位＋推锚点），并说一声，不静默。
                                replace(
                                    rows,
                                    ScheduleRules.afterHandled(
                                        task, nowMs, result.outcome, result.detail, result.sessionId, ran = false,
                                    ),
                                )
                                notifier.failed(task, result.outcome, result.detail)
                            } else {
                                replace(rows, ScheduleRules.afterRetry(task, result.detail))
                                moreRemain = true
                            }
                        }
                    }
                }

                ScheduleRules.Reason.ALREADY_FIRED -> {
                    // 两条路径撞同一个槽位：后到的这条只推进锚点，绝不再发一遍。
                    handled++
                    changed = true
                    replace(
                        rows,
                        ScheduleRules.afterHandled(
                            task, nowMs, task.lastOutcome, task.lastDetail, task.sessionId, ran = false,
                        ),
                    )
                }

                ScheduleRules.Reason.MISSED_EXPIRED -> {
                    handled++
                    changed = true
                    replace(
                        rows,
                        ScheduleRules.afterHandled(
                            task, nowMs, ScheduleOutcome.MISSED,
                            ScheduleRules.slotOf(task.nextRunAtMs, task.zone),
                            task.sessionId, ran = false,
                        ),
                    )
                }

                ScheduleRules.Reason.RETRY_LIMIT -> {
                    handled++
                    changed = true
                    replace(
                        rows,
                        ScheduleRules.afterHandled(
                            task, nowMs, ScheduleOutcome.REFUSED_BUSY, "retry_limit",
                            task.sessionId, ran = false,
                        ),
                    )
                    notifier.failed(task, ScheduleOutcome.REFUSED_BUSY, "retry_limit")
                }

                else -> Unit // NOT_DUE / DISABLED / NO_ANCHOR：这一趟不动它
            }
        }

        if (changed && !store.save(rows)) {
            // 账没落成 ⇒ 下一趟可能把同一个槽位再发一次。说出来，不当没事发生。
            moreRemain = true
        }
        return PassResult(sent, moreRemain, handled)
    }

    private fun replace(rows: MutableList<ScheduleTask>, next: ScheduleTask) {
        val idx = rows.indexOfFirst { it.id == next.id }
        if (idx >= 0) rows[idx] = next else rows += next
    }
}
