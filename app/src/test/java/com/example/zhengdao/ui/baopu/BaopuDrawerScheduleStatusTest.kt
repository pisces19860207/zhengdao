// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.schedule.ScheduleFreq
import com.example.zhengdao.schedule.ScheduleOutcome
import com.example.zhengdao.schedule.ScheduleRules
import com.example.zhengdao.schedule.ScheduleTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉「定时任务」行状态的纯函数判据（第二百一十批）。
 *
 * 行怎么画归真机，这里钉「读到的任务表 → 显示什么」：未启用 / 已启用 N 条 / 下次 HH:mm
 * 三态与派工单 §1 一致；「下次」按**任务自己的时区**算（跨时区不漂移），
 * 且锚点停在过去时不让位给一个假的「下次」。
 */
class BaopuDrawerScheduleStatusTest {

    private val zone = "Asia/Shanghai"

    private fun task(
        id: String,
        enabled: Boolean = true,
        minuteOfDay: Int = 8 * 60,
        nextRunAtMs: Long,
        zone: String = this.zone,
        freq: ScheduleFreq = ScheduleFreq.DAILY,
    ) = ScheduleTask(
        id = id,
        title = "任务$id",
        prompt = "跑一下",
        freq = freq,
        minuteOfDay = minuteOfDay,
        dayOfWeek = 0,
        onceDate = "",
        zone = zone,
        enabled = enabled,
        nextRunAtMs = nextRunAtMs,
        firedSlot = "",
        lastRunAtMs = 0L,
        lastOutcome = ScheduleOutcome.NONE,
        lastDetail = "",
        sessionId = -1L,
        runCount = 0,
        attempts = 0,
        createdAtMs = 0L,
    )

    @Test
    fun `一条都没有_未启用且没有下次`() {
        val s = scheduleDrawerStatus(emptyList(), nowMs = 1_000L)
        assertEquals(0, s.enabledCount)
        assertNull(s.nextClock)
    }

    @Test
    fun `全关掉也算未启用`() {
        val s = scheduleDrawerStatus(
            listOf(task("a", enabled = false, nextRunAtMs = 9_000L)),
            nowMs = 1_000L,
        )
        assertEquals(0, s.enabledCount)
        assertNull(s.nextClock)
    }

    @Test
    fun `启用两条_数得对_下次取最早的那个`() {
        // 两条的本地时刻分别是 08:00 与 09:30（同一天）⇒ 下次 = 08:00。
        val morning = task("a", minuteOfDay = 8 * 60, nextRunAtMs = anchor(8, 0))
        val later = task("b", minuteOfDay = 9 * 60 + 30, nextRunAtMs = anchor(9, 30))
        val s = scheduleDrawerStatus(listOf(later, morning), nowMs = anchor(7, 0))
        assertEquals(2, s.enabledCount)
        assertEquals("08:00", s.nextClock)
    }

    @Test
    fun `下次按任务自己的时区算_不跟手机时区漂移`() {
        // 同一时刻锚点：Asia/Tokyo（UTC+9）比 Asia/Shanghai（UTC+8）**早**一小时。
        // Shanghai 09:00 那一刻，东京已是 10:00 —— 抽屉要显示任务自己时区里的 10:00。
        val ms = anchor(9, 0) // Shanghai 09:00
        val tokyo = task("t", zone = "Asia/Tokyo", nextRunAtMs = ms)
        val shanghai = task("s", zone = zone, nextRunAtMs = ms)
        assertEquals("10:00", scheduleDrawerStatus(listOf(tokyo), nowMs = 0L).nextClock)
        assertEquals("09:00", scheduleDrawerStatus(listOf(shanghai), nowMs = 0L).nextClock)
    }

    @Test
    fun `锚点停在过去_不显示假的下次`() {
        // 设备关掉几天：锚点 08:00 已过（now=12:00）⇒ 只有「已启用 N 条」，没有「下次」。
        val s = scheduleDrawerStatus(
            listOf(task("a", nextRunAtMs = anchor(8, 0))),
            nowMs = anchor(12, 0),
        )
        assertEquals(1, s.enabledCount)
        assertNull(s.nextClock)
    }

    @Test
    fun `没有待办锚点_NO_RUN不参与下次`() {
        val done = task("a", freq = ScheduleFreq.ONCE, nextRunAtMs = ScheduleRules.NO_RUN)
        val s = scheduleDrawerStatus(listOf(done), nowMs = anchor(7, 0))
        assertEquals(1, s.enabledCount)
        assertNull(s.nextClock)
    }

    @Test
    fun `启用的与关掉的混在一起_只数启用的`() {
        val s = scheduleDrawerStatus(
            listOf(
                task("a", enabled = true, nextRunAtMs = anchor(8, 0)),
                task("b", enabled = false, nextRunAtMs = anchor(9, 0)),
                task("c", enabled = false, nextRunAtMs = ScheduleRules.NO_RUN),
            ),
            nowMs = anchor(7, 0),
        )
        assertEquals(1, s.enabledCount)
        assertEquals("08:00", s.nextClock)
    }

    /** 当天 HH:mm 的 epoch ms（按 [zone] 本地时刻算）。 */
    private fun anchor(hour: Int, minute: Int): Long {
        val local = java.time.LocalDate.of(2026, 10, 25).atTime(hour, minute)
        return local.atZone(java.time.ZoneId.of(zone)).toInstant().toEpochMilli()
    }

    @Test
    fun `抽屉读定时任务表不走主线程`() {
        // 派工单负例 4（抽屉里定时任务状态读主线程文件）：schedule_tasks.json 是**文件**，
        // 抽屉内容常驻组合，一次拉开读一次——读必须包在 withContext(Dispatchers.IO) 里。
        // 行为归真机，源码形态在这里钉（与 HealthFixCoroutineGateTest 同款思路）。
        val rel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt"
        val f = java.io.File(rel).let { if (it.isFile) it else java.io.File("app/$rel") }
        assertTrue("找不到 $rel", f.isFile)
        val src = f.readText()
        val idx = src.indexOf("ScheduleStore.of(ctx).load()")
        assertTrue("抽屉里看不到 ScheduleStore 读取（入口没接上？）", idx > 0)
        val before = src.substring(0, idx)
        assertTrue("读表必须包在 withContext(Dispatchers.IO) 里（主线程不许读文件）", before.contains("withContext(Dispatchers.IO)"))
        // 每次拉开抽屉重读一次（E-163：常驻组合不重读就永远是旧值）。
        assertTrue("必须有 LaunchedEffect(drawerOpen) 触发重读", src.contains("LaunchedEffect(drawerOpen) { if (drawerOpen) schedTick++ }"))
    }

    @Test
    fun `时钟格式总是两位小时两位分钟`() {
        val s = scheduleDrawerStatus(
            listOf(task("a", minuteOfDay = 7 * 60 + 5, nextRunAtMs = anchor(7, 5))),
            nowMs = anchor(6, 0),
        )
        assertTrue("时钟形如 HH:mm（实得 ${s.nextClock}）", Regex("^\\d{2}:\\d{2}$").matches(s.nextClock ?: ""))
    }
}
