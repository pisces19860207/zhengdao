// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十批 · 冷启动限频的判据测试（判据④「超限即停」在这里，端到端在 ProfileBlackboxTest）。
package com.example.zhengdao.baopu.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ColdStartThrottleTest {

    /** 当地 01:00（跨时区确定：加小时数永远落在同一或次日白天）。 */
    private val t0 = LocalDate.of(2026, 1, 5)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 3_600_000L

    private val day = TwoLayerProfile.DAY_MS

    // ── 判据④：超限即停 ────────────────────────────────────────────────────

    @Test
    fun `判据④_冷启动日额50_第51条即停`() {
        val t = ColdStartThrottle()
        repeat(50) { i ->
            val v = t.feed(t0 + i, screenOn = true, foreground = true)
            assertTrue("第${i + 1}条应收下", v is FeedVerdict.Accept)
        }
        val v = t.feed(t0 + 999, screenOn = true, foreground = true)
        assertEquals(FeedDeny.DAY_CAP, (v as FeedVerdict.Deny).reason)
        assertEquals(50, t.todayCount(t0 + 999)) // 拒的那条不记账
    }

    @Test
    fun `屏幕不亮或不在前台不算_也不占额度`() {
        val t = ColdStartThrottle()
        listOf(
            false to true, // 熄屏
            true to false, // 后台
            false to false,
        ).forEach { (on, fg) ->
            val v = t.feed(t0, screenOn = on, foreground = fg)
            assertEquals(FeedDeny.NOT_VISIBLE, (v as FeedVerdict.Deny).reason)
        }
        assertEquals(0, t.todayCount(t0))
        // 拦截不装机：第一条被收下的才算装机日
        assertTrue(t.feed(t0, true, true) is FeedVerdict.Accept)
    }

    @Test
    fun `次日额度重置_第14天起不再设日额`() {
        val t = ColdStartThrottle()
        repeat(50) { t.feed(t0 + it, true, true) }
        assertTrue(t.feed(t0 + 999, true, true) is FeedVerdict.Deny)
        // 次日（+25h，当地还是白天）：额度回满
        assertTrue(t.feed(t0 + 25 * 3_600_000L, true, true) is FeedVerdict.Accept)
        // 装机日 2026-01-05，第 14 天（01-19）起放开：60 条照单全收
        val day14 = LocalDate.of(2026, 1, 19).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli() + 3_600_000L
        repeat(60) { i ->
            assertTrue("第14天第${i + 1}条不该限", t.feed(day14 + i, true, true) is FeedVerdict.Accept)
        }
    }

    @Test
    fun `暂停即全拒_不占额度_恢复后照常`() {
        val t = ColdStartThrottle()
        t.setPaused(true)
        assertEquals(FeedDeny.PAUSED, (t.feed(t0, true, true) as FeedVerdict.Deny).reason)
        assertEquals(0, t.todayCount(t0))
        t.setPaused(false)
        assertTrue(t.feed(t0, true, true) is FeedVerdict.Accept)
    }

    // ── 重算只认充电 ────────────────────────────────────────────────────────

    @Test
    fun `重算只认充电_与限频无关`() {
        val t = ColdStartThrottle()
        assertFalse(t.allowRecompute(charging = false))
        assertTrue(t.allowRecompute(charging = true))
        // 没装机的状态也照判（重算闸独立于冷启动）
        assertFalse(ColdStartThrottle().allowRecompute(false))
    }

    // ── 状态持久化 ──────────────────────────────────────────────────────────

    @Test
    fun `状态往返_装机日_计数_暂停都在`() {
        val t = ColdStartThrottle()
        repeat(7) { t.feed(t0 + it, true, true) }
        t.setPaused(true)
        val restored = ColdStartThrottle()
        restored.loadFrom(t.toJson())
        assertTrue(restored.paused)
        assertEquals(7, restored.todayCount(t0))
        // 恢复后冷启动窗口还在（装机日没丢）：当日第 8 条收，第 51 条停
        restored.setPaused(false)
        val day2 = t0 + 25 * 3_600_000L
        repeat(50) { restored.feed(day2 + it, true, true) }
        assertEquals(FeedDeny.DAY_CAP, (restored.feed(day2 + 999, true, true) as FeedVerdict.Deny).reason)
    }

    @Test
    fun `装机日从当天起算_冷启动窗口14天`() {
        val t = ColdStartThrottle()
        assertTrue(t.feed(t0, true, true) is FeedVerdict.Accept)
        assertEquals("2026-01-05", t.toJson().optString("install"))
        assertTrue(t.coldStartActive(t0))
        val day15 = LocalDate.of(2026, 1, 20).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli() + 3_600_000L
        assertFalse(t.coldStartActive(day15))
    }

    @Test
    fun `计数表滚动_只留近30天`() {
        val t = ColdStartThrottle()
        repeat(ColdStartThrottle.DAY_KEEP + 5) { i ->
            t.feed(t0 + i * day, true, true)
        }
        assertEquals(ColdStartThrottle.DAY_KEEP, t.toJson().optJSONObject("days")!!.length())
    }
}
