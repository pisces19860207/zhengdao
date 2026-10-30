// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十一批（第二刀）· 月位判据的门禁。
//
// 判据住在 `PatrolScheduler`（夜巡那一层）——用户 2026-10-30 的原话是「挂到现成的
// PatrolScheduler 一个月任务位」。所以这里测的**不是**体检自己的算法，而是挂在夜巡那层的
// `monthlyGate`：体检侧只剩装配，判错就红在这里。
//
// 值得单独钉的理由：体检是**后台**跑的，跑错了没人当场看得见——
// "同月跑了两趟""没充电也跑""关了还跑"都只能靠这里红。
package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSchedulerTest {

    /** 2026-10-30 00:00 +08:00（与首次体检单同一个日期）。 */
    private val now = 1_793_289_600_000L

    private val day = 86_400_000L

    @Test
    fun `月键按自然月`() {
        assertEquals("2026-10", PatrolScheduler.monthlyKey(now))
        // 同一个月里换一天，键不变
        assertEquals(PatrolScheduler.monthlyKey(now), PatrolScheduler.monthlyKey(now + day))
    }

    @Test
    fun `从没跑过就算到期`() {
        assertTrue(PatrolScheduler.monthlyDue(null, now))
    }

    @Test
    fun `同一趟不重复跑`() {
        assertFalse(PatrolScheduler.monthlyDue(now, now))
    }

    @Test
    fun `跨月才到期`() {
        // 40 天前一定不在本月（任何时区都成立）
        assertTrue(PatrolScheduler.monthlyDue(now - 40 * day, now))
    }

    @Test
    fun `到月且充电才跑`() {
        val g = PatrolScheduler.monthlyGate(lastAtMs = now - 40 * day, nowMs = now, charging = true)
        assertTrue(g.run)
        assertEquals(PatrolScheduler.WHY_MONTHLY_RUN, g.why)
    }

    @Test
    fun `没充电不跑`() {
        val g = PatrolScheduler.monthlyGate(lastAtMs = now - 40 * day, nowMs = now, charging = false)
        assertFalse("同夜巡：只在充电时跑", g.run)
        assertEquals(PatrolScheduler.WHY_MONTHLY_NOT_CHARGING, g.why)
    }

    @Test
    fun `同月不再跑`() {
        val g = PatrolScheduler.monthlyGate(lastAtMs = now, nowMs = now, charging = true)
        assertFalse(g.run)
        assertEquals(PatrolScheduler.WHY_MONTHLY_DONE, g.why)
    }

    @Test
    fun `一键停优先于其它前提`() {
        val g = PatrolScheduler.monthlyGate(
            lastAtMs = now - 40 * day,
            nowMs = now,
            charging = true,
            enabled = false,
        )
        assertFalse("关了就是关了，别拿充电当借口", g.run)
        assertEquals(PatrolScheduler.WHY_MONTHLY_STOPPED, g.why)
    }

    @Test
    fun `月位是一月一次而不是三十天一次`() {
        // 9/30 跑过，10/1 就该跑（自然月换了），不是"再等 29 天"
        val sep30 = now - 30 * day
        val oct1 = now - 29 * day
        if (PatrolScheduler.monthlyKey(sep30) != PatrolScheduler.monthlyKey(oct1)) {
            assertTrue(PatrolScheduler.monthlyDue(sep30, oct1))
        }
    }

    @Test
    fun `装配的周期与月位判据同一个数`() {
        // 两处各写一个 30 迟早会有一个被改：这里钉死"只有一份"
        assertEquals(PatrolScheduler.MONTHLY_PERIOD_DAYS, HealthScheduler.PERIOD_DAYS)
    }
}
