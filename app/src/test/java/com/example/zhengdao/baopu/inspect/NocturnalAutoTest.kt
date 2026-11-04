// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百三十二批 · 暗卫自动档的判据靶：周位闸（开关 > 值守 > 周位 > WiFi）、
// 「样本不足（n<5）不许报零假 PASS」（拍板补充②）、灾异轮不当周位的账。
// 判据住在 `PatrolScheduler`（月位同款纪律），这里测的就是那层；装配侧只做读数与排程。
package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NocturnalAutoTest {

    /** 2026-10-30 00:00 +08:00（周五；任何时区下都是 ISO 周 2026-W44，跟月位用同一个锚点）。 */
    private val now = 1_793_289_600_000L

    private val day = 86_400_000L

    // ── 周位闸（开关 > 值守 > 周位 > WiFi）────────────────────────────────

    @Test
    fun `判据1_开关关_不跑_别拿条件当借口`() {
        val g = PatrolScheduler.autoWeeklyGate(
            lastRoundAtMs = null,
            nowMs = now,
            wifi = true,
            onDuty = false,
            enabled = false,
        )
        assertFalse("关了就是关了，别拿条件当借口", g.run)
        assertEquals(PatrolScheduler.WHY_WEEKLY_OFF, g.why)
    }

    @Test
    fun `判据2_L2值守期间不跑`() {
        val g = PatrolScheduler.autoWeeklyGate(
            lastRoundAtMs = null,
            nowMs = now,
            wifi = true,
            onDuty = true,
        )
        assertFalse(g.run)
        assertEquals(PatrolScheduler.WHY_WEEKLY_ON_DUTY, g.why)
    }

    @Test
    fun `判据1_没在WiFi不跑`() {
        val g = PatrolScheduler.autoWeeklyGate(
            lastRoundAtMs = null,
            nowMs = now,
            wifi = false,
            onDuty = false,
        )
        assertFalse(g.run)
        assertEquals(PatrolScheduler.WHY_WEEKLY_WIFI, g.why)
    }

    @Test
    fun `判据3_本周没抽且条件齐_该跑`() {
        val g = PatrolScheduler.autoWeeklyGate(
            lastRoundAtMs = now - 40 * day,
            nowMs = now,
            wifi = true,
            onDuty = false,
        )
        assertTrue("条件齐、周位到：${g.why}", g.run)
        assertEquals(PatrolScheduler.WHY_WEEKLY_RUN, g.why)
    }

    @Test
    fun `判据3_本周已抽_不跑_每周一次不许更密`() {
        val g = PatrolScheduler.autoWeeklyGate(
            lastRoundAtMs = now,
            nowMs = now,
            wifi = true,
            onDuty = false,
        )
        assertFalse(g.run)
        assertEquals(PatrolScheduler.WHY_WEEKLY_DONE, g.why)
    }

    @Test
    fun `首跑_从没抽过_周位不拦_条件齐才放行`() {
        // 拍板补充①：开启后不立即跑——判据不认"刚开启"，只认条件；周期作业的首跑
        // 在排上之后一个周期（≥7 天）且要等约束放行，等的就是第一个满足条件的窗口。
        val g = PatrolScheduler.autoWeeklyGate(
            lastRoundAtMs = null,
            nowMs = now,
            wifi = true,
            onDuty = false,
        )
        assertTrue(g.run)
        // 条件缺一条照样拒——首跑也不打折
        val noWifi = PatrolScheduler.autoWeeklyGate(null, now, wifi = false, onDuty = false)
        assertFalse(noWifi.run)
        val onDuty = PatrolScheduler.autoWeeklyGate(null, now, wifi = true, onDuty = true)
        assertFalse(onDuty.run)
    }

    @Test
    fun `周键按自然周_周日抽过下一周就该跑`() {
        val sat = now + 2 * day // 2026-11-01（周日，2026-W44）
        val wed = now + 5 * day // 2026-11-04（周三，2026-W45）
        assertEquals("2026-W44", PatrolScheduler.weeklyKey(now))
        assertEquals("同一周内键不变", PatrolScheduler.weeklyKey(now), PatrolScheduler.weeklyKey(sat))
        assertEquals("2026-W45", PatrolScheduler.weeklyKey(wed))
        assertTrue("周日抽过、进了下周就该跑（不是'再等 7 天'）", PatrolScheduler.weeklyDue(sat, wed))
        assertFalse("同一周不重复", PatrolScheduler.weeklyDue(now, sat))
    }

    @Test
    fun `装配的周期与周位判据同一个数`() {
        // 两处各写一个 7 迟早会有一个被改：这里钉死"只有一份"（月位同款）
        assertEquals(PatrolScheduler.WEEKLY_PERIOD_DAYS, NocturnalAuto.PERIOD_DAYS)
    }

    // ── 拍板补充②：样本不足（n<5）不许报"零假 PASS" ─────────────────────────

    @Test
    fun `判据4_样本不足n小于5_报样本不足_不许报零假PASS`() {
        val line = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 0, lastSampled = 3),
        )
        assertTrue("要点名样本不足：$line", line.contains("样本不足"))
        assertTrue("要写明抽到几条：$line", line.contains("只抽到 3 条"))
        assertTrue(line.contains("n<5"))
        assertFalse("样本不足不许显『正常』：$line", line.contains("正常"))
        assertFalse("样本不足不许报假 PASS 0：$line", line.contains("假 PASS 0"))
        assertFalse("不许报零产出：$line", line.contains("零产出"))
    }

    @Test
    fun `判据4_样本不足但真抽到假PASS_数字照实报`() {
        val line = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 1, lastSampled = 2),
        )
        assertTrue("报问题不受样本数拦：$line", line.contains("假 PASS 1"))
        assertTrue("但样本不足照样点名：$line", line.contains("样本不足"))
        assertFalse(line.contains("正常"))
    }

    @Test
    fun `判据4_样本够5条零假PASS_仍显正常并带抽了几条`() {
        val line = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 0, lastSampled = 5),
        )
        assertTrue("409 那句话术原样在：$line", line.contains("已抽检 1 轮（假 PASS 0，正常）"))
        assertTrue("拍板补充③：要写明抽了 N 条：$line", line.contains("抽了 5 条"))
    }

    @Test
    fun `判据4_边界_4条不足_5条才够`() {
        val four = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 0, lastSampled = 4),
        )
        assertTrue("n<5 是严格小于：$four", four.contains("样本不足"))
        val five = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 0, lastSampled = 5),
        )
        assertFalse("恰好 5 条就够：$five", five.contains("样本不足"))
    }

    @Test
    fun `判据4_老口径行没有lastSampled_行文与409一字不差`() {
        val line = HealthCheck.samplingLine(
            "金吾卫",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 2, lastRunMs = STAMP, problems = 3),
        )
        assertTrue(line.contains("已抽检 2 轮（假 PASS 3）"))
        assertFalse("没有样本读数就不显那一格（金吾卫报告没记抽了几条）：$line", line.contains("抽了"))
    }

    // ── 周位的账：灾异轮不算 ────────────────────────────────────────────────

    @Test
    fun `灾异轮不当周位的账`() {
        val auto = NightRound(id = "night:1", atMs = 10L, bucket = 1L, inspected = 5, hits = 0, noData = 0, emergency = false)
        val emergency = NightRound(id = "night:2", atMs = 20L, bucket = 1L, inspected = 5, hits = 0, noData = 0, emergency = true)
        assertEquals(
            "最新的是灾异轮也不算：周位看的是自动档自己",
            10L,
            PatrolScheduler.lastAutoRoundAtMs(listOf(auto, emergency)),
        )
        assertNull("全是灾异 ⇒ 周位照等到期", PatrolScheduler.lastAutoRoundAtMs(listOf(emergency)))
        assertNull(PatrolScheduler.lastAutoRoundAtMs(emptyList()))
    }

    private companion object {
        const val STAMP = 1_790_000_000_000L
    }
}
