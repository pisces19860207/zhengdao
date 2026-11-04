// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import com.example.zhengdao.schedule.ScheduleFreq
import com.example.zhengdao.schedule.ScheduleRules

/**
 * 事件触发的纯判据（第三百九十九批 §二③「去重与限频」＋ §三判据的数学面）。
 *
 * 零 Android 类型、零时钟（「现在」一律从参数进）⇒ JVM 上真跑；账怎么记归
 * [TriggerPass]，系统怎么叫醒归 `TriggerScheduler`，这里只回答「该不该、什么时候、算不算同一次」。
 *
 * 定时那半边的时刻数学**全部委托** `ScheduleRules`（nextRunAfter / slotOf）——
 * 167 与本包对「每天 8 点是哪一毫秒」必须永远同答案，委托是唯一不漂移的写法。
 */
object TriggerRules {

    /** 到点晚于窗口仍发（记补跑）的宽限；与 167 同值——「迟到多久还算数」两套系统一个答案。 */
    const val GRACE_MS = ScheduleRules.GRACE_MS

    /** 晚过这个数就不再补发（日程类的事过了点就没了意义），只标 MISSED 推锚点。 */
    const val MISSED_AFTER_MS = 24L * 60 * 60 * 1000

    /** 槽位重试上限（同 167 [ScheduleRules.MAX_ATTEMPTS]：单活跃门被占时回队再试的次数）。 */
    const val MAX_ATTEMPTS = ScheduleRules.MAX_ATTEMPTS

    /** 派工单 §二③「同一源 1 小时内最多 N 次」的 N。 */
    const val RATE_LIMIT_PER_HOUR = 6

    /** 限频窗口。 */
    const val RATE_WINDOW_MS = 60L * 60 * 1000

    /** CALENDAR 的 N（开始前多少分钟）界：1 分钟..12 小时。 */
    const val LEAD_MIN = 1
    const val LEAD_MAX = 12 * 60

    /** 日历查询向前看多远（找「下一次该叫醒的事件」用；due 判定不需要这么远）。 */
    const val CALENDAR_HORIZON_MS = 7L * 24 * 60 * 60 * 1000

    /** 没有待办的锚点哨兵（同 167 [ScheduleRules.NO_RUN]）。 */
    const val NO_WAKE = ScheduleRules.NO_RUN

    /** 日历去重键前缀（存进 [TriggerRule.firedKey]，与 SCHED 的槽位串不混）。 */
    private const val CAL_KEY_PREFIX = "cal:"

    /** 到没到点、该怎么处置。 */
    enum class Reason { DISABLED, NO_ANCHOR, NOT_DUE, ALREADY_FIRED, MISSED_EXPIRED, RETRY_LIMIT, FIRE, FIRE_LATE }

    /** SCHED 规则此刻的处置（判定序与 167 `reasonOf` 逐条同构）。 */
    fun reasonOf(nowMs: Long, rule: TriggerRule): Reason {
        if (!rule.enabled) return Reason.DISABLED
        if (rule.source != TriggerSource.SCHED) return Reason.NOT_DUE
        if (rule.nextWakeAtMs <= NO_WAKE) return Reason.NO_ANCHOR
        if (nowMs < rule.nextWakeAtMs) return Reason.NOT_DUE
        val due = rule.nextWakeAtMs
        if (rule.firedKey.isNotEmpty() && rule.firedKey == ScheduleRules.slotOf(due, rule.zone)) {
            return Reason.ALREADY_FIRED
        }
        if (nowMs - due > MISSED_AFTER_MS) return Reason.MISSED_EXPIRED
        if (rule.attempts >= MAX_ATTEMPTS) return Reason.RETRY_LIMIT
        return if (nowMs - due > GRACE_MS) Reason.FIRE_LATE else Reason.FIRE
    }

    /** SCHED 触发之后的下一次锚点（一次性任务推到 [NO_WAKE]）。 */
    fun nextSchedAnchor(rule: TriggerRule, afterMs: Long): Long {
        if (rule.freq == ScheduleFreq.ONCE) return NO_WAKE
        // 找「严格晚于 afterMs 的下一次」：nextRunAfter 只往后看一天/一周，
        // 长时间停用（锚点落后好几天）时逐天垫到追上为止——167 同款兜法。
        var anchor = afterMs
        repeat(8) {
            val next = ScheduleRules.nextRunAfter(rule.freq, rule.minuteOfDay, rule.dayOfWeek, rule.onceDate, rule.zone, anchor)
            if (next > afterMs) return next
            anchor += 24L * 60 * 60 * 1000
        }
        return NO_WAKE
    }

    /** CALENDAR：事件的叫醒时刻 = 开始 − N 分钟。 */
    fun calWakeAt(beginMs: Long, leadMinutes: Int): Long =
        beginMs - leadMinutes * 60_000L

    /** CALENDAR 的事件去重键（同一个日历事件同一个开始时刻，只叫一次）。 */
    fun calEventKey(ruleId: String, eventId: Long, beginMs: Long): String =
        "${CAL_KEY_PREFIX}$ruleId:$eventId@$beginMs"

    /**
     * CALENDAR 此刻该不该叫：窗口 = [开始−N, 开始)——会开了才到的那一趟不补（没意义）。
     * 宽限内算准点，再晚算补跑，开场后算错过。
     */
    fun calReasonOf(nowMs: Long, beginMs: Long, leadMinutes: Int): Reason {
        val wakeAt = calWakeAt(beginMs, leadMinutes)
        if (nowMs < wakeAt) return Reason.NOT_DUE
        if (nowMs >= beginMs) return Reason.MISSED_EXPIRED
        return if (nowMs - wakeAt > GRACE_MS) Reason.FIRE_LATE else Reason.FIRE
    }

    /** 派工单 §二③：这个源在窗口内的已发次数还在限内吗。 */
    fun rateOk(firesInWindow: Int): Boolean = firesInWindow < RATE_LIMIT_PER_HOUR

    /** SCHED 的调度对象名（闹钟 data 与 WorkManager 唯一名共用；前缀与 167 错开）。 */
    fun slotName(rule: TriggerRule, anchorMs: Long): String =
        ("zd-trig-" + rule.id + "-" + ScheduleRules.slotOf(anchorMs, rule.zone)).replace(':', '-')
}
