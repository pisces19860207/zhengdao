// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 388 判据：每小时汇总；B 级汇总 2h 没出 ⇒ 降级停手。本套钉：节拍到点、降级尺到点、
// 两把尺共用一个锚（上次汇总，没有 ⇒ 开值守）。
package com.example.zhengdao.unattended

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnattendedCadenceTest {

    private val t0 = 1_700_000_000_000L
    private val hour = UnattendedCadence.SUMMARY_PERIOD_MS
    private val twoHours = UnattendedCadence.DEGRADE_AFTER_MS

    // ── 每小时汇总 ──────────────────────────────────────────────────────

    @Test
    fun `没出过汇总_开值守满1小时_到点`() {
        assertTrue(UnattendedCadence.summaryDue(t0, null, t0 + hour))
    }

    @Test
    fun `不满1小时_不到点`() {
        assertFalse(UnattendedCadence.summaryDue(t0, null, t0 + hour - 1))
    }

    @Test
    fun `恰好1小时_到点_含边界`() {
        assertTrue(UnattendedCadence.summaryDue(t0, null, t0 + hour))
    }

    @Test
    fun `出过汇总_锚点换到上次汇总_再满1小时才到点`() {
        val last = t0 + hour
        assertFalse("汇总后半小时不该再到点", UnattendedCadence.summaryDue(t0, last, last + hour / 2))
        assertTrue("汇总后再满 1 小时该到点", UnattendedCadence.summaryDue(t0, last, last + hour))
    }

    // ── 2h 没出 ⇒ 降级停手 ──────────────────────────────────────────────

    @Test
    fun `开值守2小时一条汇总都没出_降级`() {
        assertTrue(UnattendedCadence.degradeDue(t0, null, t0 + twoHours))
    }

    @Test
    fun `不满2小时_不降级`() {
        assertFalse(UnattendedCadence.degradeDue(t0, null, t0 + twoHours - 1))
    }

    @Test
    fun `汇总照常出_就不降级_汇总1小时前刚出过`() {
        val last = t0 + hour
        assertFalse(
            "2h 降级尺从上次汇总起算：1h 前刚出过 ⇒ 还有 1h 余量",
            UnattendedCadence.degradeDue(t0, last, t0 + twoHours),
        )
    }

    @Test
    fun `服务死了汇总停了_看门狗2小时后降级`() {
        // "服务死了但看门狗还活着"的那段：最后一次汇总停在 t0+hour，之后没人出账。
        val last = t0 + hour
        assertTrue(UnattendedCadence.degradeDue(t0, last, last + twoHours))
    }

    @Test
    fun `两把尺共用一个锚_降级尺永远比汇总尺晚一小时`() {
        assertEquals(UnattendedCadence.SUMMARY_PERIOD_MS * 2, UnattendedCadence.DEGRADE_AFTER_MS)
    }

    @Test
    fun `下一次汇总时刻_锚点加一小时`() {
        assertEquals(t0 + hour, UnattendedCadence.nextSummaryAtMs(t0, null))
        val last = t0 + hour * 3
        assertEquals(last + hour, UnattendedCadence.nextSummaryAtMs(t0, last))
    }

    @Test
    fun `锚点不会早于开值守_汇总时刻在开值守之前是坏账不许认`() {
        // 防御格：lastSummary 比开值守还早（坏账/换日）⇒ 锚点回到开值守，不许拿旧账顶新班。
        val stale = t0 - hour
        assertEquals(t0 + hour, UnattendedCadence.nextSummaryAtMs(t0, stale))
    }
}
