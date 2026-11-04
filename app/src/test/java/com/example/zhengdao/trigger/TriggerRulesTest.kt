// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import com.example.zhengdao.schedule.ScheduleFreq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件触发纯判据（第三百九十九批 §三判据的数学面）：到没到点、迟到怎么算、
 * 同一事件算不算同一次、限频边界。零 Android 类型，JVM 真跑。
 */
class TriggerRulesTest {

    private fun ms(iso: String): Long = java.time.Instant.parse(iso).toEpochMilli()

    private fun schedRule(
        next: Long,
        zone: String = "Asia/Shanghai",
        freq: ScheduleFreq = ScheduleFreq.ONCE,
        onceDate: String = "2026-11-05",
        firedKey: String = "",
        enabled: Boolean = true,
        attempts: Int = 0,
    ): TriggerRule = TriggerRule(
        id = "r1", source = TriggerSource.SCHED, title = "早报", prompt = "早报", enabled = enabled,
        freq = freq, minuteOfDay = 480, dayOfWeek = 0, onceDate = onceDate, zone = zone,
        leadMinutes = 0, nextWakeAtMs = next, firedKey = firedKey, lastRunAtMs = 0L,
        lastOutcome = com.example.zhengdao.schedule.ScheduleOutcome.NONE, lastDetail = "",
        sessionId = -1L, runCount = 0, attempts = attempts, createdAtMs = 0L,
    )

    // ── SCHED：到点/宽限/补跑/错过/去重 ──────────────────────────────────

    @Test
    fun `定时_没到点不动_到点FIRE_宽限内FIRE_超宽限FIRE_LATE_超一天MISSED`() {
        val due = ms("2026-11-05T00:00:00Z")
        assertEquals(TriggerRules.Reason.NOT_DUE, TriggerRules.reasonOf(due - 1, schedRule(due)))
        assertEquals(TriggerRules.Reason.FIRE, TriggerRules.reasonOf(due, schedRule(due)))
        assertEquals(TriggerRules.Reason.FIRE, TriggerRules.reasonOf(due + TriggerRules.GRACE_MS, schedRule(due)))
        assertEquals(TriggerRules.Reason.FIRE_LATE, TriggerRules.reasonOf(due + TriggerRules.GRACE_MS + 1, schedRule(due)))
        assertEquals(
            TriggerRules.Reason.MISSED_EXPIRED,
            TriggerRules.reasonOf(due + TriggerRules.MISSED_AFTER_MS + 1, schedRule(due)),
        )
    }

    @Test
    fun `定时_关闭与无锚点与重试封顶各有各的格子`() {
        val due = ms("2026-11-05T00:00:00Z")
        assertEquals(TriggerRules.Reason.DISABLED, TriggerRules.reasonOf(due, schedRule(due, enabled = false)))
        assertEquals(TriggerRules.Reason.NO_ANCHOR, TriggerRules.reasonOf(due, schedRule(0L)))
        assertEquals(
            TriggerRules.Reason.RETRY_LIMIT,
            TriggerRules.reasonOf(due, schedRule(due, attempts = TriggerRules.MAX_ATTEMPTS)),
        )
    }

    @Test
    fun `定时_已发过的槽位不再发_去重键与167同款本地读法`() {
        val due = ms("2026-11-05T00:00:00Z")
        val slot = com.example.zhengdao.schedule.ScheduleRules.slotOf(due, "Asia/Shanghai")
        assertEquals(
            TriggerRules.Reason.ALREADY_FIRED,
            TriggerRules.reasonOf(due + 1, schedRule(due, firedKey = slot)),
        )
        assertTrue("槽位串要带本地日期（可读、可对表）", slot.contains("2026-11-05") || slot.contains("2026-11-04"))
    }

    @Test
    fun `定时_下一次锚点_每天找严格晚于now的下一次_一次性推到无待办`() {
        val now = ms("2026-11-05T00:00:00Z")
        val daily = schedRule(0L, freq = ScheduleFreq.DAILY, onceDate = "")
        val next = TriggerRules.nextSchedAnchor(daily, now)
        assertTrue("下一次必须严格晚于 now", next > now)
        assertEquals(
            "再下一次正好差一天（DAILY）",
            next + 24L * 60 * 60 * 1000,
            TriggerRules.nextSchedAnchor(daily.copy(nextWakeAtMs = next), next),
        )
        val once = schedRule(0L, freq = ScheduleFreq.ONCE)
        assertEquals("一次性发完 = 没有待办", TriggerRules.NO_WAKE, TriggerRules.nextSchedAnchor(once, now))
    }

    // ── CALENDAR：开始前 N 分钟的窗口 ────────────────────────────────────

    @Test
    fun `日程_叫醒点等于开始减N分钟_窗口到开场为止`() {
        val begin = ms("2026-11-05T02:00:00Z")
        val wakeAt = TriggerRules.calWakeAt(begin, 5)
        assertEquals(begin - 5 * 60_000L, wakeAt)
        assertEquals(TriggerRules.Reason.NOT_DUE, TriggerRules.calReasonOf(wakeAt - 1, begin, 5))
        assertEquals(TriggerRules.Reason.FIRE, TriggerRules.calReasonOf(wakeAt, begin, 5))
        assertEquals("会开了才到 ⇒ 错过（不补，没意义）", TriggerRules.Reason.MISSED_EXPIRED, TriggerRules.calReasonOf(begin, begin, 5))
        // 补跑分支只在 lead > 宽限时可达（lead=5 时，晚过宽限必已开场）：lead=30 才有「迟到但没开场」
        val wake30 = TriggerRules.calWakeAt(begin, 30)
        assertEquals(
            TriggerRules.Reason.FIRE_LATE,
            TriggerRules.calReasonOf(wake30 + TriggerRules.GRACE_MS + 1, begin, 30),
        )
    }

    @Test
    fun `日程_同一事件同键_不同事件不同键`() {
        val k1 = TriggerRules.calEventKey("r1", 42L, 1_000L)
        assertEquals(k1, TriggerRules.calEventKey("r1", 42L, 1_000L))
        assertFalse(k1 == TriggerRules.calEventKey("r1", 42L, 2_000L))
        assertFalse(k1 == TriggerRules.calEventKey("r2", 42L, 1_000L))
        assertTrue("键带 cal: 前缀，与定时槽位串永不混", k1.startsWith("cal:"))
    }

    // ── 限频 ─────────────────────────────────────────────────────────────

    @Test
    fun `限频_窗口内次数到N就不许再发`() {
        assertTrue(TriggerRules.rateOk(0))
        assertTrue(TriggerRules.rateOk(TriggerRules.RATE_LIMIT_PER_HOUR - 1))
        assertFalse(TriggerRules.rateOk(TriggerRules.RATE_LIMIT_PER_HOUR))
    }
}
