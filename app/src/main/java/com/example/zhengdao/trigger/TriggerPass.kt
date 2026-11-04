// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import com.example.zhengdao.schedule.ScheduleFreq
import com.example.zhengdao.schedule.ScheduleOutcome
import com.example.zhengdao.schedule.ScheduleRules

/** 一次叫醒的出口（把引擎隔在外面，这套账才能在 JVM 上真跑——167 的 TaskSender 同款）。 */
fun interface WakeSender {
    fun send(rule: TriggerRule, eventTitle: String, nowMs: Long): WakeResult
}

/** 一次叫醒的结果。[outcome] ∈ {FIRED, FIRED_LATE, QUEUED} 算发出去了。 */
data class WakeResult(val outcome: ScheduleOutcome, val sessionId: Long, val detail: String)

/** 一条系统日历事件（探针读出来的原始行；判不判、怎么判归 [TriggerRules]）。 */
data class CalEventRow(val eventId: Long, val title: String, val beginMs: Long)

/** 一趟 pass 的结果。还有该发没发出去的 ⇒ 调用方交给 WorkManager 退避重试。 */
data class TriggerPassResult(val sent: Boolean, val moreRemain: Boolean, val handled: Int) {
    val needsRetry: Boolean get() = moreRemain
}

/**
 * 一趟「到点处理」的账（第三百九十九批；不含任何 Android 类型）。
 *
 * 派工单 §二四条硬规矩在这层落成代码：
 * ① **源级开关**：`switchOn` 返回 false 的源一个事件都不看、一行账都不记——关掉就是静默；
 * ② **只做触发＋唤起**：出口只有 [WakeSender]（生产实现走 `engine.send`，与用户敲字同一入口），
 *    不碰任何工具执行器、不碰审批——不可逆动作的圈判定归 388 与既有闸，这里没有第二条路；
 * ③ **去重＋限频**：SCHED 认 `firedKey == 槽位`（167 同款），CALENDAR 认
 *    `firedKey == cal:<eventId>@<beginMs>`；同源 1 小时超过 [TriggerRules.RATE_LIMIT_PER_HOUR]
 *    次 ⇒ 丢弃本次并落 `rateLimited` 账；
 * ④ **落账**：终局（发出/入队/被拒翻篇/失败翻篇/错过/限频）逐条进 [TriggerLedger]，可回放；
 *    去重跳过不记账（它是「已办过」的沉默重访，键已在规则表里），被拒的中间重试也不记
 *    （只记翻篇那一条——重试流水会把账本刷花，翻篇才是这件事的结果）。
 *
 * 一趟 pass **只发一条**（167 同款）：抱朴是全局单活跃会话，灌多条必被拒；剩下的交给
 * WorkManager 退避重试。整趟结束时写一次规则表。
 *
 * 与 167 的差异（有意为之，不是漏）：不做任务级连败自动暂停——本批没有规则管理页，
 * 暂停态没人看得见；失败重试用 [TriggerRule.attempts] 封顶（翻篇），终局都进账。
 * 自动暂停等有管理面时另单。
 */
class TriggerPass(
    private val store: TriggerStore,
    private val ledger: TriggerLedger,
    private val sender: WakeSender,
    private val switchOn: (TriggerSource) -> Boolean,
) {

    fun run(nowMs: Long, calendarEvents: List<CalEventRow>): TriggerPassResult {
        val rows = store.load().toMutableList()
        if (rows.isEmpty()) return TriggerPassResult(false, false, 0)
        val tally = Tally()
        for (rule in rows.toList().filter { it.enabled }.sortedBy { it.nextWakeAtMs }) {
            when (rule.source) {
                TriggerSource.SCHED -> schedStep(rule, rows, nowMs, tally)
                TriggerSource.CALENDAR -> calendarStep(rule, rows, nowMs, calendarEvents, tally)
            }
        }
        if (tally.changed && !store.save(rows)) {
            // 账没落成 ⇒ 下一趟可能把同一槽位再发一次。说出来，不当没事发生（167 同款）。
            tally.moreRemain = true
        }
        return TriggerPassResult(tally.sent, tally.moreRemain, tally.handled)
    }

    // ── 两个源的取件 ──────────────────────────────────────────────────────

    private fun schedStep(rule: TriggerRule, rows: MutableList<TriggerRule>, nowMs: Long, tally: Tally) {
        if (!switchOn(rule.source)) return // ① 源级开关：关 ⇒ 这个源整个不存在
        when (val reason = TriggerRules.reasonOf(nowMs, rule)) {
            TriggerRules.Reason.FIRE, TriggerRules.Reason.FIRE_LATE ->
                fireOne(rule, rows, nowMs, eventTitle = "", eventKey = slotKey(rule), late = reason == TriggerRules.Reason.FIRE_LATE, tally)
            TriggerRules.Reason.ALREADY_FIRED -> advanceOnly(rule, rows, nowMs, tally)
            TriggerRules.Reason.MISSED_EXPIRED -> giveUp(rule, rows, nowMs, ScheduleOutcome.MISSED, "missed_expired", tally)
            TriggerRules.Reason.RETRY_LIMIT -> giveUp(rule, rows, nowMs, ScheduleOutcome.REFUSED_BUSY, "retry_limit", tally)
            else -> Unit // DISABLED / NO_ANCHOR / NOT_DUE：这趟不动它
        }
    }

    private fun calendarStep(
        rule: TriggerRule,
        rows: MutableList<TriggerRule>,
        nowMs: Long,
        events: List<CalEventRow>,
        tally: Tally,
    ) {
        if (!switchOn(rule.source)) return // ① 源级开关
        for (event in events.sortedBy { it.beginMs }) {
            val reason = TriggerRules.calReasonOf(nowMs, event.beginMs, rule.leadMinutes)
            if (reason != TriggerRules.Reason.FIRE && reason != TriggerRules.Reason.FIRE_LATE) continue
            val key = TriggerRules.calEventKey(rule.id, event.eventId, event.beginMs)
            if (rule.firedKey == key) continue // ③ 去重：同一事件不跑两遍
            fireOne(rule, rows, nowMs, eventTitle = event.title, eventKey = key, late = reason == TriggerRules.Reason.FIRE_LATE, tally)
        }
    }

    // ── 真叫醒（限频 → 发送 → 分流落账）─────────────────────────────────

    private fun fireOne(
        rule: TriggerRule,
        rows: MutableList<TriggerRule>,
        nowMs: Long,
        eventTitle: String,
        eventKey: String,
        late: Boolean,
        tally: Tally,
    ) {
        tally.handled++
        if (tally.sent) { // 一趟一条；这条留给下一趟
            tally.moreRemain = true
            return
        }
        val fires = ledger.fireCountSince(rule.source, nowMs - TriggerRules.RATE_WINDOW_MS)
        if (!TriggerRules.rateOk(fires)) {
            settle(rule, rows, nowMs, eventKey, ScheduleOutcome.MISSED, "rate_limited($fires)", rule.sessionId, ran = false, action = "rateLimited", tally)
            return
        }
        val result = runCatching { sender.send(rule, eventTitle, nowMs) }
            .getOrElse { WakeResult(ScheduleOutcome.FAILED, -1L, it.javaClass.simpleName) }
        when (result.outcome) {
            ScheduleOutcome.FIRED, ScheduleOutcome.FIRED_LATE, ScheduleOutcome.QUEUED -> {
                tally.sent = true
                val stored = if (late && result.outcome == ScheduleOutcome.FIRED) ScheduleOutcome.FIRED_LATE else result.outcome
                settle(rule, rows, nowMs, eventKey, stored, result.detail, result.sessionId, ran = true, action = stored.wire, tally)
            }
            ScheduleOutcome.REFUSED_BUSY -> retryOrGiveUp(rule, rows, nowMs, eventKey, result, tally, ScheduleOutcome.REFUSED_BUSY)
            else -> retryOrGiveUp(rule, rows, nowMs, eventKey, result, tally, ScheduleOutcome.FAILED)
        }
    }

    // ── 被拒 / 失败：回队重试，到顶翻篇落账（167 同款）──────────────────

    /** 被拒是环境占用、失败是真没发成——处置相同（重试到顶翻篇），只差终态记哪个。 */
    private fun retryOrGiveUp(
        rule: TriggerRule,
        rows: MutableList<TriggerRule>,
        nowMs: Long,
        eventKey: String,
        result: WakeResult,
        tally: Tally,
        outcome: ScheduleOutcome,
    ) {
        tally.changed = true
        if (rule.attempts + 1 >= TriggerRules.MAX_ATTEMPTS) {
            settle(rule, rows, nowMs, eventKey, outcome, result.detail, result.sessionId, ran = false, action = outcome.wire, tally)
        } else {
            replace(rows, rule.copy(attempts = rule.attempts + 1, lastOutcome = outcome, lastDetail = result.detail))
            tally.moreRemain = true
        }
    }

    // ── 只推锚点不发送的终局 ─────────────────────────────────────────────

    /** 两条路径撞同一槽位：后到的只推锚点，绝不再发一遍（167 的 ALREADY_FIRED 同款，不记账）。 */
    private fun advanceOnly(rule: TriggerRule, rows: MutableList<TriggerRule>, nowMs: Long, tally: Tally) {
        tally.handled++
        tally.changed = true
        replace(rows, rule.copy(nextWakeAtMs = TriggerRules.nextSchedAnchor(rule, nowMs), lastRunAtMs = nowMs, attempts = 0))
    }

    /** 错过/重试到顶：推锚点写终态，落账，不静默。 */
    private fun giveUp(
        rule: TriggerRule,
        rows: MutableList<TriggerRule>,
        nowMs: Long,
        outcome: ScheduleOutcome,
        detail: String,
        tally: Tally,
    ) {
        settle(rule, rows, nowMs, slotKey(rule), outcome, detail, rule.sessionId, ran = false, action = outcome.wire, tally)
    }

    // ── 小件 ─────────────────────────────────────────────────────────────

    /**
     * 终局落表＋落账的唯一出口（一次性任务发完即关——167 的「跑完推到 NO_RUN 置 disabled」同款；
     * CALENDAR 锚点置 [TriggerRules.NO_WAKE]，下一次待办由 refreshAnchors 重算）。
     */
    private fun settle(
        rule: TriggerRule,
        rows: MutableList<TriggerRule>,
        nowMs: Long,
        eventKey: String,
        outcome: ScheduleOutcome,
        detail: String,
        sessionId: Long,
        ran: Boolean,
        action: String,
        tally: Tally,
    ) {
        tally.handled++
        val next = rule.copy(
            nextWakeAtMs = if (rule.source == TriggerSource.SCHED) TriggerRules.nextSchedAnchor(rule, nowMs) else TriggerRules.NO_WAKE,
            firedKey = eventKey,
            lastRunAtMs = nowMs,
            lastOutcome = outcome,
            lastDetail = detail.take(160),
            sessionId = sessionId,
            runCount = if (ran) rule.runCount + 1 else rule.runCount,
            attempts = 0,
            enabled = rule.enabled && !(rule.source == TriggerSource.SCHED && rule.freq == ScheduleFreq.ONCE),
        )
        replace(rows, next)
        tally.changed = true
        ledger.append(
            TriggerLedger.Row(
                tsMs = nowMs,
                source = rule.source.wire,
                ruleId = rule.id,
                eventId = if (rule.source == TriggerSource.CALENDAR) eventKey else "",
                action = action,
                sessionId = sessionId,
                detail = detail.take(160),
            ),
        )
    }

    /** SCHED 的去重键 = 167 同款槽位串（当次锚点的本地读法）。 */
    private fun slotKey(rule: TriggerRule): String = ScheduleRules.slotOf(rule.nextWakeAtMs, rule.zone)

    private fun replace(rows: MutableList<TriggerRule>, next: TriggerRule) {
        val idx = rows.indexOfFirst { it.id == next.id }
        if (idx >= 0) rows[idx] = next else rows += next
    }

    /** 一趟 pass 的累计现场（167 的 PassTally 同款）。 */
    private class Tally {
        var sent = false
        var moreRemain = false
        var handled = 0
        var changed = false
    }
}
