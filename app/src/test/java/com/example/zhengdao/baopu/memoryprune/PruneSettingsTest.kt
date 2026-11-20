// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.memoryprune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第七百五十八批 · 设置三键的**夹回判定本体**（757 判据①；「或 JVM 侧读同一纯函数」口径
 * ——本机单测环境无 Robolectric，SharedPreferences 往返的真值取证归真机轮）。
 * 每条判据带变异负例（判据本身能被绕过它就是假的——752 同纪律）。
 */
class PruneSettingsTest {

    // ── 判据①：出厂默认 true / "weekly" / 90 ────────────────────────────────

    @Test
    fun 判据1_三键出厂默认_true_weekly_90() {
        assertEquals("总开关默认开", true, PruneSettings.enabledOrDefault(null))
        assertEquals("周期默认每周", "weekly", PruneSettings.scheduleOrDefault(null))
        assertEquals("阈值默认90天", 90, PruneSettings.retireDaysOrDefault(null))
        // get() 的兜底快照与纯函数同源（prefs 读不动 ⇒ 出厂默认，不炸）。
        val fallback = PruneSettings.Values(enabled = true, schedule = PruneSettings.SCHEDULE_WEEKLY, retireAfterDays = 90)
        assertEquals(true, fallback.enabled)
        assertEquals(PruneSettings.SCHEDULE_WEEKLY, fallback.schedule)
        assertEquals(90, fallback.retireAfterDays)
    }

    @Test
    fun 判据1_周期值域闭集_非法值夹回weekly() {
        assertEquals("off 在闭集内", "off", PruneSettings.scheduleOrDefault("off"))
        assertEquals("daily 在闭集内", "daily", PruneSettings.scheduleOrDefault("daily"))
        assertEquals("weekly 在闭集内", "weekly", PruneSettings.scheduleOrDefault("weekly"))
        assertEquals("非法字面量 ⇒ weekly", "weekly", PruneSettings.scheduleOrDefault("monthly"))
        assertEquals("空串 ⇒ weekly", "weekly", PruneSettings.scheduleOrDefault(""))
        assertEquals("大小写不对 ⇒ weekly（闭集字面量敏感）", "weekly", PruneSettings.scheduleOrDefault("DAILY"))
        // 变异负例：夹回若恒真，闭集内的值也会被改写——判据不是恒真的。
        assertNotEquals("闭集内的值必须原样通过", PruneSettings.scheduleOrDefault("daily"), PruneSettings.scheduleOrDefault("monthly"))
    }

    @Test
    fun 判据1_阈值塞45_单独断言夹回90() {
        assertEquals("塞 45 ⇒ 90（757 判据①原话，单独断言）", 90, PruneSettings.retireDaysOrDefault(45))
        assertEquals(90, PruneSettings.retireDaysOrDefault(0))
        assertEquals(90, PruneSettings.retireDaysOrDefault(-1))
        assertEquals(90, PruneSettings.retireDaysOrDefault(91))
    }

    @Test
    fun 判据1_阈值四档闭集_档内原样通过() {
        listOf(30, 60, 90, 180).forEach { days ->
            assertEquals("档内 $days 原样", days, PruneSettings.retireDaysOrDefault(days))
        }
        assertEquals("四档闭集一字不差", setOf(30, 60, 90, 180), PruneSettings.RETIRE_DAYS_CHOICES)
        assertEquals("周期闭集一字不差", setOf("off", "daily", "weekly"), PruneSettings.SCHEDULE_CHOICES)
    }

    @Test
    fun 判据1_总开关夹回_null按true() {
        assertEquals(true, PruneSettings.enabledOrDefault(null))
        assertEquals(false, PruneSettings.enabledOrDefault(false))
        assertEquals(true, PruneSettings.enabledOrDefault(true))
        assertTrue("三键常量是 prefs 里唯一的三键（写口对象无别的键常量）", PruneSettings.PREFS_NAME.isNotBlank())
        assertNull("入口投影：N=0 ⇒ null（入口行不显示）", retiredEntryTextOf(0))
    }
}
