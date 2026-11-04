// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import com.example.zhengdao.schedule.ScheduleFreq
import com.example.zhengdao.schedule.ScheduleOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 一趟 pass 的账（第三百九十九批 §三判据主体，全部真跑）：
 * 造事件⇒触发一次并落账；关掉⇒不触发；默认全关；同一事件连发 5 次⇒只处理 1 次；
 * 限频；落账可回放；单活跃让路；重试封顶；一次性发完即关。
 *
 * 套路照 SchedulePassTest：真 [TriggerStore]/[TriggerLedger]（临时目录真文件）＋假发送出口，
 * 引擎隔在外面。`switchOn` 是注入的源级开关假件——「默认全关」的字面证据在
 * TriggerWiringGateTest（生产读法 `getBoolean(key, false)`）。
 */
class TriggerPassTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private val now = 1_770_000_000_000L

    private fun store(): TriggerStore = TriggerStore(File(tmp.root, "t-${System.nanoTime()}/trigger_rules.json"))

    private fun ledger(): TriggerLedger = TriggerLedger(File(tmp.root, "t-${System.nanoTime()}/trigger/事件触发.jsonl"))

    private fun schedRule(
        id: String = "s1",
        next: Long = now,
        freq: ScheduleFreq = ScheduleFreq.ONCE,
        onceDate: String = "2026-02-02",
        enabled: Boolean = true,
    ): TriggerRule = TriggerRule(
        id = id, source = TriggerSource.SCHED, title = "早报", prompt = "早报", enabled = enabled,
        freq = freq, minuteOfDay = 480, dayOfWeek = 0, onceDate = onceDate, zone = "Asia/Shanghai",
        leadMinutes = 0, nextWakeAtMs = next, firedKey = "", lastRunAtMs = 0L,
        lastOutcome = ScheduleOutcome.NONE, lastDetail = "", sessionId = -1L,
        runCount = 0, attempts = 0, createdAtMs = 0L,
    )

    private fun calRule(id: String = "c1", lead: Int = 5, enabled: Boolean = true): TriggerRule = TriggerRule(
        id = id, source = TriggerSource.CALENDAR, title = "开会准备", prompt = "汇总材料", enabled = enabled,
        freq = ScheduleFreq.ONCE, minuteOfDay = 0, dayOfWeek = 0, onceDate = "", zone = "Asia/Shanghai",
        leadMinutes = lead, nextWakeAtMs = 0L, firedKey = "", lastRunAtMs = 0L,
        lastOutcome = ScheduleOutcome.NONE, lastDetail = "", sessionId = -1L,
        runCount = 0, attempts = 0, createdAtMs = 0L,
    )

    private fun event(id: Long = 42L, begin: Long = now + 5 * 60_000L, title: String = "周会"): CalEventRow =
        CalEventRow(eventId = id, title = title, beginMs = begin)

    /** 假发送出口：记下每次调用；可选指定返回。 */
    private class FakeSender : WakeSender {
        val sent = mutableListOf<Pair<String, String>>() // ruleId to prompt
        var outcome: ScheduleOutcome = ScheduleOutcome.FIRED
        var sessionId = 77L
        override fun send(rule: TriggerRule, eventTitle: String, nowMs: Long): WakeResult {
            sent += rule.id to rule.prompt
            return WakeResult(outcome, sessionId, "started")
        }
    }

    private fun pass(store: TriggerStore, ledger: TriggerLedger, sender: WakeSender, vararg on: TriggerSource): TriggerPass =
        TriggerPass(store, ledger, sender) { it in on }

    // ── 判据①：造一条事件 ⇒ 触发一次并落账 ─────────────────────────────

    @Test
    fun `定时_到点触发一次_账一行fired_锚点推进`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        s.save(listOf(schedRule(next = now)))
        val r = pass(s, l, sender, TriggerSource.SCHED).run(now, emptyList())
        assertTrue(r.sent)
        assertEquals(1, sender.sent.size)
        assertEquals("早报", sender.sent[0].second)
        val rows = l.rows()
        assertEquals(1, rows.size)
        assertEquals("fired", rows[0].action)
        assertEquals("sched", rows[0].source)
        assertEquals(77L, rows[0].sessionId)
        val rule = s.load().single()
        assertEquals(1, rule.runCount)
        assertEquals(ScheduleOutcome.FIRED, rule.lastOutcome)
        assertTrue("一次性发完即关（167 同款）", !rule.enabled)
        assertEquals(TriggerRules.NO_WAKE, rule.nextWakeAtMs)
    }

    @Test
    fun `日程_开始前N分钟触发一次_账里带事件键_会话发出prompt`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        s.save(listOf(calRule(lead = 5)))
        val r = pass(s, l, sender, TriggerSource.CALENDAR).run(now, listOf(event(begin = now + 5 * 60_000L)))
        assertTrue(r.sent)
        assertEquals(1, sender.sent.size)
        assertEquals("汇总材料", sender.sent[0].second)
        val rows = l.rows()
        assertEquals(1, rows.size)
        assertEquals("fired", rows[0].action)
        assertEquals("calendar", rows[0].source)
        assertEquals(TriggerRules.calEventKey("c1", 42L, now + 5 * 60_000L), rows[0].eventId)
        assertEquals(TriggerRules.calEventKey("c1", 42L, now + 5 * 60_000L), s.load().single().firedKey)
    }

    // ── 判据③：重复触发要有去重（同一事件不跑两遍）────────────────────

    @Test
    fun `定时_同一槽位第二趟不再发_只推锚点不刷账`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        s.save(listOf(schedRule(next = now, freq = ScheduleFreq.DAILY, onceDate = "")))
        val p = pass(s, l, sender, TriggerSource.SCHED)
        assertTrue(p.run(now, emptyList()).sent)
        val second = p.run(now + 60_000L, emptyList())
        assertFalse(second.sent)
        assertEquals("第二趟 0 次发送", 1, sender.sent.size)
        assertEquals("账还是一行（去重重访不记账）", 1, l.rows().size)
        assertTrue("锚点已推进到下一次", s.load().single().nextWakeAtMs > now + 60_000L)
    }

    @Test
    fun `日程_同一事件连发5次_只处理1次`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        s.save(listOf(calRule(lead = 5)))
        val ev = event(begin = now + 5 * 60_000L)
        val p = pass(s, l, sender, TriggerSource.CALENDAR)
        repeat(5) { p.run(now, listOf(ev)) }
        assertEquals("同一事件不跑两遍", 1, sender.sent.size)
        assertEquals(1, l.rows().size)
    }

    @Test
    fun `日程_两个不同事件都到期_各发一次_跨趟串行`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        s.save(listOf(calRule(lead = 5)))
        val e1 = event(id = 1L, begin = now + 5 * 60_000L)   // 叫醒点 = now
        val e2 = event(id = 2L, begin = now + 6 * 60_000L)   // 叫醒点 = now + 1min
        val p = pass(s, l, sender, TriggerSource.CALENDAR)
        val first = p.run(now, listOf(e1, e2))
        assertTrue(first.sent)
        assertEquals("一趟只发一条（全局单活跃）", 1, sender.sent.size)
        // 第二趟在 e2 的叫醒点之后：e1 已发过（键相同）跳过，e2 补上
        val second = p.run(now + 90_000L, listOf(e1, e2))
        assertTrue(second.sent)
        assertEquals(2, sender.sent.size)
        assertEquals("两个事件两个键", 2, l.rows().size)
    }

    // ── 判据②：关掉 ⇒ 不触发；默认全关 ────────────────────────────────

    @Test
    fun `源关掉_到点也不触发_连事件都不看_一行账都不记`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        s.save(listOf(schedRule(next = now), calRule()))
        val r = pass(s, l, sender).run(now, listOf(event())) // switchOn 全 false = 新装默认态
        assertFalse(r.sent)
        assertEquals(0, sender.sent.size)
        assertEquals(0, l.rows().size)
        assertEquals("锚点也不动（开了之后按原锚点处理）", now, s.load().first { it.id == "s1" }.nextWakeAtMs)
    }

    @Test
    fun `只开一个源_另一个源的事件装聋`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        s.save(listOf(schedRule(next = now), calRule()))
        pass(s, l, sender, TriggerSource.CALENDAR).run(now, listOf(event()))
        assertEquals("定时没开，日历开了 ⇒ 只发日历那条", listOf("c1"), sender.sent.map { it.first })
    }

    // ── 限频（派工单 §二③）────────────────────────────────────────────

    @Test
    fun `限频_一小时内到N次_第N加1次丢弃并落rateLimited账`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        s.save(listOf(schedRule(next = now, freq = ScheduleFreq.DAILY, onceDate = "")))
        // 预置：同源一小时内已经发满 6 条
        repeat(TriggerRules.RATE_LIMIT_PER_HOUR) { l.append(TriggerLedger.Row(now - 1_000L, "sched", "old", "", "fired", 7L, "d")) }
        val r = pass(s, l, sender, TriggerSource.SCHED).run(now, emptyList())
        assertFalse(r.sent)
        assertEquals(0, sender.sent.size)
        val rows = l.rows()
        assertEquals("预置 6 条 + 1 条 rateLimited", TriggerRules.RATE_LIMIT_PER_HOUR + 1, rows.size)
        assertEquals("rateLimited", rows.last().action)
    }

    @Test
    fun `限频_窗口滑走后恢复触发`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        s.save(listOf(schedRule(next = now, freq = ScheduleFreq.DAILY, onceDate = "")))
        l.append(TriggerLedger.Row(now - TriggerRules.RATE_WINDOW_MS - 1, "sched", "old", "", "fired", 7L, "d"))
        assertTrue("窗口外的 fired 不占额度", pass(s, l, sender, TriggerSource.SCHED).run(now, emptyList()).sent)
    }

    // ── 单活跃与重试封顶 ────────────────────────────────────────────────

    @Test
    fun `被别的会话挡住_回队重试到顶翻篇落refusedBusy账`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        sender.outcome = ScheduleOutcome.REFUSED_BUSY
        s.save(listOf(schedRule(next = now, freq = ScheduleFreq.DAILY, onceDate = "")))
        val p = pass(s, l, sender, TriggerSource.SCHED)
        repeat(TriggerRules.MAX_ATTEMPTS - 1) {
            val r = p.run(now, emptyList())
            assertTrue(r.moreRemain)
            assertFalse(l.rows().any { it.action == "refusedBusy" })
        }
        val last = p.run(now, emptyList())
        assertTrue(last.sent.not())
        val rows = l.rows()
        assertEquals("翻篇那一条要落账", 1, rows.size)
        assertEquals("refusedBusy", rows[0].action)
        assertTrue("锚点推进（这一次放弃了，不无限重试）", s.load().single().nextWakeAtMs > now)
    }

    @Test
    fun `错过窗口_标MISSED不发但落账`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        s.save(listOf(schedRule(next = now - TriggerRules.MISSED_AFTER_MS - 1, freq = ScheduleFreq.DAILY, onceDate = "")))
        val r = pass(s, l, sender, TriggerSource.SCHED).run(now, emptyList())
        assertFalse(r.sent)
        assertEquals("missed", l.rows().single().action)
        assertEquals(ScheduleOutcome.MISSED, s.load().single().lastOutcome)
    }

    // ── 落账可回放（判据⑤）────────────────────────────────────────────

    @Test
    fun `两个源各触发一次_跨趟串行_时间窗回放一条不落`() {
        val s = store(); val l = ledger(); val sender = FakeSender()
        s.save(listOf(schedRule("s1", next = now - 30_000L, freq = ScheduleFreq.DAILY, onceDate = ""), calRule()))
        val p = pass(s, l, sender, TriggerSource.SCHED, TriggerSource.CALENDAR)
        val first = p.run(now, listOf(event(begin = now + 5 * 60_000L)))
        val second = p.run(now + 1_000L, listOf(event(begin = now + 5 * 60_000L)))
        assertTrue("两趟各发一条（一趟一条：全局单活跃）", first.sent && second.sent)
        assertEquals(2, sender.sent.size)
        val window = l.rowsInWindow(now - 60_000L, now + 60_000L)
        assertEquals(2, window.size)
        assertEquals(setOf("sched", "calendar"), window.map { it.source }.toSet())
    }
}
