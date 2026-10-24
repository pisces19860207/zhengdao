// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第一百六十七批 · 定时任务 v1》§3「判据正确：时区 / 夏令时 / 跨天 / 错过窗口」
// 与 §5 ①③（到点走哪条路、错过补跑怎么去重）。
//
// 这一套全是**纯函数**的账：只吃毫秒与字符串，不需要 Context，所以在 JVM 上就能算准、
// 也必须算准——真机上「关机三天再开机」这种行为没法每次验收都跑一遍。
package com.example.zhengdao.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 第一百六十七批判据层单测。
 *
 * 时区口径用 **UTC / Asia/Shanghai / America/New_York** 三个显式值：
 * 本机的默认时区不参与任何一条断言（掺进去就成了"在这台机器上过、到别人机器上红"）。
 */
class ScheduleRulesTest {

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun task(
        freq: ScheduleFreq = ScheduleFreq.DAILY,
        minute: Int = 8 * 60,
        dayOfWeek: Int = 0,
        onceDate: String = "",
        zone: String = "UTC",
        enabled: Boolean = true,
        next: Long = 0L,
        firedSlot: String = "",
        attempts: Int = 0,
        id: String = "t1",
    ): ScheduleTask = ScheduleTask(
        id = id,
        title = "早间一句",
        prompt = "提醒我喝水",
        freq = freq,
        minuteOfDay = minute,
        dayOfWeek = dayOfWeek,
        onceDate = onceDate,
        zone = zone,
        enabled = enabled,
        nextRunAtMs = next,
        firedSlot = firedSlot,
        lastRunAtMs = 0L,
        lastOutcome = ScheduleOutcome.NONE,
        lastDetail = "",
        sessionId = -1L,
        runCount = 0,
        attempts = attempts,
        createdAtMs = 0L,
    )

    // ── 一、下一次时刻（§3：严格晚于现在；跨天/跨时区按任务自己的时区） ──────────────

    @Test
    fun `每天_取今天还没到的那一次`() {
        assertEquals(
            "2026-10-24T08:00:00Z",
            iso(ScheduleRules.nextRunAfter(ScheduleFreq.DAILY, 480, 0, "", "UTC", ms("2026-10-24T00:05:00Z"))),
        )
    }

    @Test
    fun `每天_那个点已过就取明天`() {
        assertEquals(
            "2026-10-25T08:00:00Z",
            iso(ScheduleRules.nextRunAfter(ScheduleFreq.DAILY, 480, 0, "", "UTC", ms("2026-10-24T09:00:00Z"))),
        )
    }

    @Test
    fun `每天_正好等于现在也不许取_必须严格晚于`() {
        val at = ms("2026-10-24T08:00:00Z")
        val next = ScheduleRules.nextRunAfter(ScheduleFreq.DAILY, 480, 0, "", "UTC", at)
        assertEquals("到点这一刻再排一次会排到明天，绝不能等于现在（等于＝这一趟立刻又发一遍）", "2026-10-25T08:00:00Z", iso(next))
    }

    @Test
    fun `每周_找对星期几`() {
        // 2026-10-24 是周六，要周一（ISO 1）⇒ 10-26
        assertEquals(
            "2026-10-26T08:00:00Z",
            iso(ScheduleRules.nextRunAfter(ScheduleFreq.WEEKLY, 480, 1, "", "UTC", ms("2026-10-24T00:00:00Z"))),
        )
    }

    @Test
    fun `每周_同一天但那个时刻未到_仍取这一天`() {
        assertEquals(
            "2026-10-26T08:00:00Z",
            iso(ScheduleRules.nextRunAfter(ScheduleFreq.WEEKLY, 480, 1, "", "UTC", ms("2026-10-26T07:00:00Z"))),
        )
    }

    @Test
    fun `一次_过去就没有下次`() {
        assertEquals(
            ScheduleRules.NO_RUN,
            ScheduleRules.nextRunAfter(ScheduleFreq.ONCE, 480, 0, "2026-10-01", "UTC", ms("2026-10-24T00:00:00Z")),
        )
    }

    @Test
    fun `一次_未来按那个时刻`() {
        assertEquals(
            "2026-10-25T08:00:00Z",
            iso(ScheduleRules.nextRunAfter(ScheduleFreq.ONCE, 480, 0, "2026-10-25", "UTC", ms("2026-10-24T00:00:00Z"))),
        )
    }

    @Test
    fun `跨时区_按任务自己的时区算_不跟本机漂`() {
        // 任务：上海时间每天 01:00（= UTC 前一天 17:00）。从 UTC 10-23T12:00 起算，
        // 下一次是上海 10-24T01:00，即 UTC 10-23T17:00 —— 如果代码错用了"本机 UTC 日期"，
        // 这里会给出 UTC 10-24T17:00，差一整天。
        assertEquals(
            "2026-10-23T17:00:00Z",
            iso(ScheduleRules.nextRunAfter(ScheduleFreq.DAILY, 60, 0, "", "Asia/Shanghai", ms("2026-10-23T12:00:00Z"))),
        )
    }

    @Test
    fun `夏令时_春季跳表那天顺延不丢`() {
        // 纽约 2026-03-08 02:00 直接跳到 03:00：本地 02:30 那天根本不存在。
        // java.time 的口径是"往前挪一个缺口长度"⇒ 03:30-04:00（UTC 07:30）。
        // 钉住它是因为这是"宁可晚也不丢这一天"的取舍：改成"跳过这天"用户会莫名其妙少一次。
        assertEquals(
            "2026-03-08T07:30:00Z",
            iso(ScheduleRules.nextRunAfter(ScheduleFreq.DAILY, 150, 0, "", "America/New_York", ms("2026-03-08T05:00:00Z"))),
        )
    }

    @Test
    fun `夏令时_秋季回拨取较早的那个偏移`() {
        // 纽约 2026-11-01 02:00 回拨到 01:00：本地 01:30 出现两次（EDT 与 EST 各一次）。
        // 取较早的（EDT，UTC 05:30）——与 java.time 一致，也是"回拨那天只响一次"的保证。
        assertEquals(
            "2026-11-01T05:30:00Z",
            iso(ScheduleRules.nextRunAfter(ScheduleFreq.DAILY, 90, 0, "", "America/New_York", ms("2026-11-01T04:00:00Z"))),
        )
    }

    @Test
    fun `坏输入_不抛异常只给没有下次`() {
        assertEquals(ScheduleRules.NO_RUN, ScheduleRules.nextRunAfter(ScheduleFreq.DAILY, 1440, 0, "", "UTC", ms("2026-10-24T00:00:00Z")))
        assertEquals(ScheduleRules.NO_RUN, ScheduleRules.nextRunAfter(ScheduleFreq.DAILY, 480, 0, "", "Not/AZone", ms("2026-10-24T00:00:00Z")))
        assertEquals(ScheduleRules.NO_RUN, ScheduleRules.nextRunAfter(ScheduleFreq.ONCE, 480, 0, "不是日期", "UTC", ms("2026-10-24T00:00:00Z")))
    }

    // ── 二、槽位与调度对象名（§5 ③：去重键只有一个字符串） ─────────────────────────

    @Test
    fun `槽位标签是锚点的本地读法`() {
        assertEquals("2026-10-24T08:00", ScheduleRules.slotOf(ms("2026-10-24T08:00:00Z"), "UTC"))
        // 同一个绝对时刻在另一个时区是另一个槽位：这正是"按任务自己的时区去重"。
        assertEquals("2026-10-24T16:00", ScheduleRules.slotOf(ms("2026-10-24T08:00:00Z"), "Asia/Shanghai"))
        assertEquals("", ScheduleRules.slotOf(ScheduleRules.NO_RUN, "UTC"))
        assertEquals("", ScheduleRules.slotOf(ms("2026-10-24T08:00:00Z"), "Not/AZone"))
    }

    @Test
    fun `槽位名不带冒号_因为要进闹钟的_URI`() {
        val name = ScheduleRules.slotName("t1", ms("2026-10-24T08:00:00Z"), "UTC")
        assertEquals("zd-sched-t1-2026-10-24T08-00", name)
        assertFalse("URI 的 authority 段里冒号是端口分隔符，槽位名不许带", name.contains(":"))
        assertTrue(name.startsWith(ScheduleRules.WORK_NAME_PREFIX))
    }

    @Test
    fun `槽位名_改一次时间就换一个名_不自我取消`() {
        val a = ScheduleRules.slotName("t1", ms("2026-10-24T08:00:00Z"), "UTC")
        val b = ScheduleRules.slotName("t1", ms("2026-10-25T08:00:00Z"), "UTC")
        assertTrue("同一任务的不同槽位必须不同名（同名 REPLACE 会把正在跑的那一趟取消掉）：$a / $b", a != b)
    }

    // ── 三、到点处置矩阵（§3 判据：准点 / 宽限期 / 补跑窗 / 去重 / 封顶） ───────────

    @Test
    fun `没到点_不看`() {
        val t = task(next = ms("2026-10-24T08:00:00Z"))
        assertEquals(ScheduleRules.Reason.NOT_DUE, ScheduleRules.reasonOf(ms("2026-10-24T07:59:59Z"), t))
    }

    @Test
    fun `关着的与没有锚点的都不动`() {
        assertEquals(
            ScheduleRules.Reason.DISABLED,
            ScheduleRules.reasonOf(ms("2026-10-24T09:00:00Z"), task(enabled = false, next = ms("2026-10-24T08:00:00Z"))),
        )
        assertEquals(
            ScheduleRules.Reason.NO_ANCHOR,
            ScheduleRules.reasonOf(ms("2026-10-24T09:00:00Z"), task(next = ScheduleRules.NO_RUN)),
        )
    }

    @Test
    fun `到点_宽限期内算准点`() {
        val due = ms("2026-10-24T08:00:00Z")
        assertEquals(ScheduleRules.Reason.FIRE, ScheduleRules.reasonOf(due, task(next = due)))
        assertEquals(
            "恰好 15 分钟仍算准点（判据是「大于」，不是「不小于」）",
            ScheduleRules.Reason.FIRE,
            ScheduleRules.reasonOf(due + ScheduleRules.GRACE_MS, task(next = due)),
        )
    }

    @Test
    fun `过点超过宽限期_仍是发但记成补跑`() {
        val due = ms("2026-10-24T08:00:00Z")
        assertEquals(
            ScheduleRules.Reason.FIRE_LATE,
            ScheduleRules.reasonOf(due + ScheduleRules.GRACE_MS + 1L, task(next = due)),
        )
    }

    @Test
    fun `同一个槽位只发一次_后到的那条读到已写过的槽位`() {
        val due = ms("2026-10-24T08:00:00Z")
        val t = task(next = due, firedSlot = ScheduleRules.slotOf(due, "UTC"))
        assertEquals(ScheduleRules.Reason.ALREADY_FIRED, ScheduleRules.reasonOf(due + 60_000L, t))
        // 别的槽位不算已发过：昨天那个点跑过了，今天这个点照样要跑。
        val stale = task(next = due, firedSlot = "2026-10-23T08:00")
        assertEquals(ScheduleRules.Reason.FIRE, ScheduleRules.reasonOf(due + 60_000L, stale))
    }

    @Test
    fun `超过补跑窗_只标错过不再发`() {
        val due = ms("2026-10-24T08:00:00Z")
        assertEquals(
            ScheduleRules.Reason.MISSED_EXPIRED,
            ScheduleRules.reasonOf(due + ScheduleRules.BACKFILL_WINDOW_MS + 1L, task(next = due)),
        )
        assertEquals(
            "窗口边界内仍愿意补",
            ScheduleRules.Reason.FIRE_LATE,
            ScheduleRules.reasonOf(due + ScheduleRules.BACKFILL_WINDOW_MS - 60_000L, task(next = due)),
        )
    }

    @Test
    fun `同一槽位重试到上限就翻篇`() {
        val due = ms("2026-10-24T08:00:00Z")
        assertEquals(
            ScheduleRules.Reason.RETRY_LIMIT,
            ScheduleRules.reasonOf(due + 60_000L, task(next = due, attempts = ScheduleRules.MAX_ATTEMPTS)),
        )
        assertEquals(
            "还差一次就继续试",
            ScheduleRules.Reason.FIRE,
            ScheduleRules.reasonOf(due + 60_000L, task(next = due, attempts = ScheduleRules.MAX_ATTEMPTS - 1)),
        )
    }

    // ── 四、状态迁移（发完之后、关掉又打开、改一次文案） ────────────────────────────

    @Test
    fun `发出去之后_写槽位推锚点_每天的任务继续活着`() {
        val due = ms("2026-10-24T08:00:00Z")
        val now = due + 300_000L
        val next = ScheduleRules.afterHandled(task(next = due), now, ScheduleOutcome.FIRED, "started", 7L, ran = true)
        assertEquals(ScheduleRules.slotOf(due, "UTC"), next.firedSlot)
        assertEquals(7L, next.sessionId)
        assertEquals(ScheduleOutcome.FIRED, next.lastOutcome)
        assertEquals(1, next.runCount)
        assertEquals(0, next.attempts)
        assertTrue("锚点必须推进到未来，否则下一趟会把同一个过去时刻再发一遍", next.nextRunAtMs > now)
        assertEquals("2026-10-25T08:00:00Z", iso(next.nextRunAtMs))
        assertTrue("每天的任务发完还是开着的", next.enabled)
    }

    @Test
    fun `一次性任务发完就关掉`() {
        val due = ms("2026-10-25T08:00:00Z")
        val t = task(freq = ScheduleFreq.ONCE, onceDate = "2026-10-25", next = due)
        val next = ScheduleRules.afterHandled(t, due, ScheduleOutcome.FIRED, "started", 3L, ran = true)
        assertFalse("一次性没有「下一次」：不关掉的话列表里它还会到点冒出来", next.enabled)
        assertEquals(ScheduleRules.NO_RUN, next.nextRunAtMs)
        assertFalse(ScheduleRules.shouldReschedule(t))
        assertTrue(ScheduleRules.shouldReschedule(task()))
    }

    @Test
    fun `没发出去只加一次尝试次数_锚点与槽位都不动`() {
        val due = ms("2026-10-24T08:00:00Z")
        val retry = ScheduleRules.afterRetry(task(next = due), "other_session_busy")
        assertEquals(1, retry.attempts)
        assertEquals(due, retry.nextRunAtMs)
        assertEquals("", retry.firedSlot)
        assertEquals("other_session_busy", retry.lastDetail)
    }

    @Test
    fun `关掉又打开_重算锚点_不照着旧时刻立刻补发`() {
        val old = ms("2026-10-20T08:00:00Z")
        val paused = task(enabled = false, next = old)
        val now = ms("2026-10-24T09:00:00Z")
        val reopened = ScheduleRules.afterEnable(paused, now)
        assertTrue("开了一个关了很多天的任务，绝不能立刻补发（那是用户没预期的消息）", reopened.nextRunAtMs > now)
        assertEquals("2026-10-25T08:00:00Z", iso(reopened.nextRunAtMs))
        assertTrue(reopened.enabled)
        assertEquals(0, reopened.attempts)
    }

    @Test
    fun `暂停保留锚点只清重试`() {
        val due = ms("2026-10-25T08:00:00Z")
        val paused = ScheduleRules.afterDisable(task(next = due, attempts = 3))
        assertFalse(paused.enabled)
        assertEquals(0, paused.attempts)
        assertEquals("锚点不能清：清了就等于把用户填的时间意图扔掉，重开时没得算", due, paused.nextRunAtMs)
    }

    @Test
    fun `改一次文案要作废去重键`() {
        val due = ms("2026-10-24T08:00:00Z")
        val fired = task(next = due, firedSlot = ScheduleRules.slotOf(due, "UTC"), attempts = 2)
        val edited = ScheduleTask.withEdit(
            old = fired,
            title = fired.title,
            prompt = "换一句话",
            freq = ScheduleFreq.DAILY,
            minuteOfDay = 480,
            dayOfWeek = 0,
            onceDate = "",
            nextRunAtMs = due,
        )
        assertEquals("时间没变的编辑也要作废槽位：否则「今天 08:00 已经跑过」会把新的 08:00 判成已触发", "", edited.firedSlot)
        assertEquals(0, edited.attempts)
        assertEquals("换一句话", edited.prompt)
        assertEquals(fired.lastOutcome, edited.lastOutcome)
        assertEquals(fired.sessionId, edited.sessionId)
    }

    // ── 五、路由与界面读数用的判据（§5 ①②） ───────────────────────────────────────

    @Test
    fun `权限决定走哪条路`() {
        assertEquals(ScheduleRules.Route.EXACT_ALARM_PLUS_WORK, ScheduleRules.routeOf(true))
        assertEquals("默认（Android 14+ 新装机）走非精确", ScheduleRules.Route.WORK_ONLY, ScheduleRules.routeOf(false))
    }

    @Test
    fun `锚点落在今天还是明天`() {
        val now = ms("2026-10-24T10:00:00Z")
        assertEquals(ScheduleRules.DayOffset.TODAY, ScheduleRules.dayOffsetOf(ms("2026-10-24T20:00:00Z"), "UTC", now))
        assertEquals(ScheduleRules.DayOffset.TOMORROW, ScheduleRules.dayOffsetOf(ms("2026-10-25T06:00:00Z"), "UTC", now))
        assertEquals(ScheduleRules.DayOffset.OTHER, ScheduleRules.dayOffsetOf(ms("2026-11-02T06:00:00Z"), "UTC", now))
        assertEquals(ScheduleRules.DayOffset.NONE, ScheduleRules.dayOffsetOf(ScheduleRules.NO_RUN, "UTC", now))
        // 跨时区时「今天」按任务自己的时区判：上海 10-25 01:00 对 UTC 来说还是 10-24 晚上。
        assertEquals(
            ScheduleRules.DayOffset.TOMORROW,
            ScheduleRules.dayOffsetOf(ms("2026-10-24T17:00:00Z"), "Asia/Shanghai", ms("2026-10-24T12:00:00Z")),
        )
    }

    @Test
    fun `表单校验只报第一个不对的字段`() {
        assertNull(ScheduleTask.validate(ScheduleFreq.DAILY, 480, 0, "", "UTC"))
        assertEquals("minute", ScheduleTask.validate(ScheduleFreq.DAILY, 1440, 0, "", "UTC"))
        assertEquals("dayOfWeek", ScheduleTask.validate(ScheduleFreq.WEEKLY, 480, 8, "", "UTC"))
        assertNull(ScheduleTask.validate(ScheduleFreq.ONCE, 480, 0, "2026-10-25", "UTC"))
        assertEquals("onceDate", ScheduleTask.validate(ScheduleFreq.ONCE, 480, 0, "2026-10-32", "UTC"))
        assertEquals("zone", ScheduleTask.validate(ScheduleFreq.DAILY, 480, 0, "", "Mars/Olympus"))
    }

    /** 把 epoch ms 按 UTC 写成 ISO 串，断言里读得懂（测试自己不用被测代码的格式化器）。 */
    private fun iso(ms: Long): String =
        if (ms == ScheduleRules.NO_RUN) "NO_RUN" else Instant.ofEpochMilli(ms).toString()
}

// 负例口径（交付报告 §3 逐条实跑过）：
// ① 把 reasonOf 的 ALREADY_FIRED 判定删掉 ⇒ `同一个槽位只发一次` 红；
// ② 把 afterHandled 里的 nextAnchor 换成"保留旧锚点" ⇒ `发出去之后_写槽位推锚点…` 红；
// ③ 把 nextRunAfter 的日期基准从任务时区换成 Instant.atZone(systemDefault) ⇒
//    `跨时区_按任务自己的时区算_不跟本机漂` 红（本机是 UTC 时才侥幸绿， hence 显式三个时区）。
