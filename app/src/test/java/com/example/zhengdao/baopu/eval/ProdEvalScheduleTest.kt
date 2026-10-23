// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十八批：ProdEval 调用点的节流与单飞（派工单 §2 §3 判据的 JVM 钉子）。
//
// 判据原文：「打开后到点跑一次，产物落盘；再跑受节流限制」——这里把「到点」
// 拆成可证的矩阵：开关、网络、距上次多久、首次。本仓无 Robolectric，
// prefs 那一层（lastRanAt/markRan）不在此测，只测纯判定与单飞门。
package com.example.zhengdao.baopu.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProdEvalScheduleTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `间隔常量就是24小时`() {
        assertEquals(day, ProdEvalSchedule.INTERVAL_MS)
    }

    @Test
    fun `关着或不联网_一律不跑`() {
        assertFalse(
            "开关关着，网络再好也不跑",
            ProdEvalSchedule.shouldRun(enabled = false, networkAvailable = true, lastRanAt = null, now = 0L),
        )
        assertFalse(
            "网络不通，开关开着也不跑（不赌）",
            ProdEvalSchedule.shouldRun(enabled = true, networkAvailable = false, lastRanAt = null, now = 0L),
        )
        // 负例：两个判断若被写成「或」，这一行就红
        assertFalse(
            ProdEvalSchedule.shouldRun(enabled = false, networkAvailable = false, lastRanAt = null, now = 0L),
        )
    }

    @Test
    fun `从来没跑过_放行第一次`() {
        assertTrue(
            ProdEvalSchedule.shouldRun(enabled = true, networkAvailable = true, lastRanAt = null, now = 123_456L),
        )
    }

    @Test
    fun `24小时内不重复跑_满了才放行`() {
        val last = 1_000_000L
        assertFalse(
            "差一小时也不跑",
            ProdEvalSchedule.shouldRun(true, true, last, last + day - 3_600_000L),
        )
        assertFalse(
            "差一毫秒也不跑",
            ProdEvalSchedule.shouldRun(true, true, last, last + day - 1L),
        )
        assertTrue(
            "刚满 24h 放行（边界含等于）",
            ProdEvalSchedule.shouldRun(true, true, last, last + day),
        )
        assertTrue(
            "超过 24h 放行",
            ProdEvalSchedule.shouldRun(true, true, last, last + day + 1L),
        )
    }

    @Test
    fun `间隔可注入_不写死24h`() {
        assertTrue(ProdEvalSchedule.shouldRun(true, true, 0L, 60_000L, intervalMs = 60_000L))
        assertFalse(ProdEvalSchedule.shouldRun(true, true, 0L, 59_999L, intervalMs = 60_000L))
    }

    @Test
    fun `单飞门_抢到才放_放掉才能再抢`() {
        // 门是进程级共享的：先放掉再测，免得被别的用例残留状态干扰
        ProdEvalSchedule.release()
        assertTrue("空门第一个抢到", ProdEvalSchedule.tryAcquire())
        assertFalse("门里有在跑的，第二个抢不到", ProdEvalSchedule.tryAcquire())
        ProdEvalSchedule.release()
        assertTrue("放了才能再抢", ProdEvalSchedule.tryAcquire())
        ProdEvalSchedule.release()
    }
}
