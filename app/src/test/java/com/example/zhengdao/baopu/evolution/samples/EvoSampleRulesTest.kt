// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十九批 · 判据①②③ 的纯函数面（零 IO：不碰盘、不碰账，只喂事件列表）。
//
// 三条夹具就是派工单 ① 那三句，逐条对应：
//   · 25 次＋8 天 ⇒ 够转正；
//   · 10 次 ⇒ 只能灰度；
//   · 3 次 ⇒ 样本不足。
// 另加四条边界（19/20 条分界、7/8 条分界、7 天分界、25 次挤在同一天），
// 与两条负例（同会话重复只算一次、连续同类动作合并）——判据要真钉，边界不钉等于没钉。
package com.example.zhengdao.baopu.evolution.samples

import com.example.zhengdao.baopu.consequence.CanaryCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvoSampleRulesTest {

    private val day = EvoSampleRules.DAY_MS
    private val now = 1_760_000_000_000L
    private val domain = CanaryCapability.REFLECTION_AUTO_EDIT.wire

    /** 均匀撒 [n] 条事件、跨度正好 [days] 天（间隔远大于防刷线，条条都是独立事件）。 */
    private fun spread(n: Int, days: Int, kind: String = "act"): List<EvoSampleEvent> {
        if (n <= 0) return emptyList()
        val span = days.toLong() * day
        val first = now - span
        return (0 until n).map { i ->
            // 跨度用 i*span/(n-1) 现算，不用 step=i*(span/(n-1))：后者整数截断会把 7 天算成 6 天。
            val at = if (n == 1) first else first + i * span / (n - 1)
            EvoSampleEvent(
                domain = domain,
                sessionId = 7_000L + i,
                eventId = "e$i",
                kind = kind,
                atMs = at,
            )
        }
    }

    private fun reading(events: List<EvoSampleEvent>) = EvoSampleRules.reading(domain, events)

    // ── 判据①：三条夹具 ────────────────────────────────────────────────────

    @Test
    fun `夹具一_25次8天_够转正`() {
        val r = reading(spread(25, 8))
        assertEquals("25 条独立事件", 25, r.events)
        assertEquals("跨度 8 天", 8, r.days)
        assertEquals(EvoSampleVerdict.PROMOTE_READY, r.verdict)
        assertTrue("够转正要能一眼读出来：${r.describe()}", r.promotable())
    }

    @Test
    fun `夹具二_10次_只能灰度`() {
        val r = reading(spread(10, 8))
        assertEquals(10, r.events)
        assertEquals(EvoSampleVerdict.CANARY_ONLY, r.verdict)
        assertFalse("10 次不许被当成够转正", r.promotable())
    }

    @Test
    fun `夹具三_3次_样本不足`() {
        val r = reading(spread(3, 8))
        assertEquals(3, r.events)
        assertEquals(EvoSampleVerdict.INSUFFICIENT, r.verdict)
        assertFalse(r.promotable())
    }

    // ── 判据①的边界（差一条就得换档，否则夹具只是三条好走的路） ──────────

    @Test
    fun `边界_19条30天_次数差一条_只能灰度`() {
        assertEquals(EvoSampleVerdict.CANARY_ONLY, reading(spread(19, 30)).verdict)
    }

    @Test
    fun `边界_7条_不够灰度_样本不足`() {
        assertEquals(EvoSampleVerdict.INSUFFICIENT, reading(spread(7, 30)).verdict)
        assertEquals("8 条是灰度线", EvoSampleVerdict.CANARY_ONLY, reading(spread(8, 30)).verdict)
    }

    @Test
    fun `边界_25次全挤在一天_次数够窗口不够_只能灰度`() {
        // 25 条、每条间隔 1 分钟 ⇒ 跨度 0 天：次数再够也不构成「≥7 天窗口」。
        val events = spread(25, 0).mapIndexed { i, e -> e.copy(atMs = now - 30L * 60_000L + i * 60_000L) }
        val r = reading(events)
        assertEquals(0, r.days)
        assertEquals(25, r.events)
        assertEquals("同一天的 25 次不许当 7 天样本用", EvoSampleVerdict.CANARY_ONLY, r.verdict)
    }

    @Test
    fun `边界_20条7天_两条线同时压线_够转正`() {
        val r = reading(spread(20, 7))
        assertEquals(20, r.events)
        assertEquals(7, r.days)
        assertEquals(EvoSampleVerdict.PROMOTE_READY, r.verdict)
    }

    @Test
    fun `边界_20条6天_差一天窗口_只能灰度`() {
        assertEquals(EvoSampleVerdict.CANARY_ONLY, reading(spread(20, 6)).verdict)
    }

    // ── 判据②：同会话同一事件只算一次 ──────────────────────────────────────

    @Test
    fun `判据②_同会话同一事件重复_只计一次`() {
        val one = EvoSampleEvent(domain, sessionId = 42L, eventId = "run-1", kind = "act", atMs = now - day)
        val r = reading(listOf(one, one, one))
        assertEquals("同一行读三遍仍是一条独立事件", 1, r.events)
        assertEquals(2, r.droppedDuplicate)
        assertEquals(EvoSampleVerdict.INSUFFICIENT, r.verdict)
    }

    @Test
    fun `判据②_同事件号不同会话_算两条`() {
        val a = EvoSampleEvent(domain, sessionId = 1L, eventId = "run-1", kind = "act", atMs = now - day)
        val b = a.copy(sessionId = 2L)
        assertEquals("去重键是(会话,事件)两格，不是事件号一格", 2, reading(listOf(a, b)).events)
    }

    @Test
    fun `负例_同一会话连续同类动作_合并计数`() {
        // 一次连点：同会话、同类、1 分钟一条 —— 五下只算一次。
        val burst = (0 until 5).map {
            EvoSampleEvent(domain, sessionId = 9L, eventId = "click-$it", kind = "save", atMs = now - day + it * 60_000L)
        }
        val r = reading(burst)
        assertEquals(1, r.events)
        assertEquals(4, r.droppedBurst)
    }

    @Test
    fun `负例_同会话隔一天的同类动作_仍是两次独立事件`() {
        val a = EvoSampleEvent(domain, sessionId = 9L, eventId = "save-1", kind = "save", atMs = now - 2 * day)
        val b = EvoSampleEvent(domain, sessionId = 9L, eventId = "save-2", kind = "save", atMs = now - day)
        val r = reading(listOf(a, b))
        assertEquals("隔一天还合并＝把两次独立选择算成一次", 2, r.events)
        assertEquals(0, r.droppedBurst)
    }

    @Test
    fun `负例_连点但动作类不同_各算一次`() {
        val a = EvoSampleEvent(domain, sessionId = 9L, eventId = "k1", kind = "save", atMs = now - 60_000L)
        val b = EvoSampleEvent(domain, sessionId = 9L, eventId = "k2", kind = "delete", atMs = now - 30_000L)
        assertEquals(2, reading(listOf(a, b)).events)
    }

    // ── 数据形状的负例（建不出来才有意义） ────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `负例_没登记在册的域_建不出事件`() {
        EvoSampleEvent("no_such_domain", sessionId = 1L, eventId = "x", kind = "act", atMs = now)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负例_无会话的样本_建不出事件`() {
        EvoSampleEvent(domain, sessionId = -1L, eventId = "x", kind = "act", atMs = now)
    }

    @Test
    fun `闭集_三态线值稳定且认不出的返回null`() {
        EvoSampleVerdict.entries.forEach {
            assertEquals("线值是落账的稳定键", it, EvoSampleVerdict.ofWire(it.wire))
        }
        assertNull(EvoSampleVerdict.ofWire("maybe"))
    }

    @Test
    fun `读数行_带evo_samples键与四个数`() {
        val line = reading(spread(25, 8)).line()
        assertTrue("读数键必须是派工单点名的 evo_samples：$line", line.startsWith("evo_samples|$domain|25 次|8 天|"))
        assertTrue("结论要写在行上：$line", line.endsWith(EvoSampleVerdict.PROMOTE_READY.cn))
    }

    @Test
    fun `空池的读数_0次0天_样本不足`() {
        val r = EvoSampleRules.reading(domain, emptyList())
        assertEquals(0, r.events)
        assertEquals(0, r.days)
        assertEquals(EvoSampleVerdict.INSUFFICIENT, r.verdict)
    }
}
