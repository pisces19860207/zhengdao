// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十批 · 两层画像引擎的判据测试。判据①「8 天前样本被清」在 TTL=7（可配下限）
// 下验证——TTL 配在 7–15 之间，默认 14 时 8 天的样本还在（14 天界另有边界用例）。
package com.example.zhengdao.baopu.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TwoLayerProfileTest {

    private val day = TwoLayerProfile.DAY_MS
    private val t0 = 1_790_000_000_000L

    // ── 判据①：短期层硬清 ──────────────────────────────────────────────────

    @Test
    fun `判据①_TTL配7时8天前样本被清`() {
        val p = TwoLayerProfile(shortTermTtlDays = 7)
        p.feed("ask_style", 5, fromUser = true, atMs = t0)
        assertEquals(1, p.purge(t0 + 8 * day))
        assertEquals(0, p.shortTerm.size)
    }

    @Test
    fun `TTL默认14_越界夹回7到15`() {
        assertEquals(14, TwoLayerProfile().ttlDays)
        assertEquals(7, TwoLayerProfile(shortTermTtlDays = 3).ttlDays)
        assertEquals(15, TwoLayerProfile(shortTermTtlDays = 99).ttlDays)
        assertEquals(7, TwoLayerProfile().retuneTtl(1))
        assertEquals(15, TwoLayerProfile().retuneTtl(30))
    }

    @Test
    fun `默认14的边界_14天内还在_过线即清`() {
        val p = TwoLayerProfile()
        p.feed("ask_style", 5, fromUser = true, atMs = t0)
        assertEquals(0, p.purge(t0 + 14 * day - 1))
        assertEquals(1, p.shortTerm.size)
        assertEquals(1, p.purge(t0 + 14 * day + 1))
        assertEquals(0, p.shortTerm.size)
    }

    @Test
    fun `删明细保聚合_短期层清了稳定层权重还在`() {
        val p = TwoLayerProfile(shortTermTtlDays = 7)
        repeat(25) { p.feed("k1", 5, true, t0) }
        repeat(25) { p.feed("k2", 5, true, t0 + 10 * day) }
        val kinds = listOf("k1", "k2", "k3")
        // k1 的明细被清，但聚合没清——分布里它仍占份（只剩衰减后的量）
        assertEquals(25, p.purge(t0 + 10 * day + 1))
        assertTrue(p.shortTerm.none { it.kind == "k1" })
        val d = p.distribution(t0 + 10 * day + 1, kinds)
        assertTrue(d["k1"]!! > d["k3"]!!)
        assertTrue(d["k2"]!! > d["k1"]!!)
    }

    // ── 稳定层：指数衰减 ────────────────────────────────────────────────────

    @Test
    fun `半衰期30天_30天后权重减半`() {
        val p = TwoLayerProfile()
        // 80 条 ×5＝基数 400：30 天后条数 40≥20（不落弱先验）、权重 200；60 天后条数 20、权重 100
        repeat(80) { p.feed("k1", 5, true, t0) }
        val kinds = listOf("k1", "x", "y")
        val d30 = p.distribution(t0 + 30 * day, kinds)
        assertEquals((1.0 + 200.0) / (3.0 + 200.0), d30["k1"]!!, 1e-9)
        assertEquals(1.0 / 203.0, d30["x"]!!, 1e-9)
        val d60 = p.distribution(t0 + 60 * day, kinds)
        assertEquals((1.0 + 100.0) / (3.0 + 100.0), d60["k1"]!!, 1e-9)
    }

    @Test
    fun `模型自评权重只有行为的五分之一`() {
        val a = TwoLayerProfile()
        val b = TwoLayerProfile()
        repeat(30) { a.feed("k1", 5, fromUser = true, atMs = t0 + it) }
        repeat(30) { b.feed("k1", 5, fromUser = false, atMs = t0 + it) }
        val kinds = listOf("k1", "x", "y")
        // 条数都是 30（不落弱先验）；权重上行为 150、自评 30
        assertEquals((1.0 + 150.0) / (3.0 + 150.0), a.distribution(t0 + 1, kinds)["k1"]!!, 1e-9)
        assertEquals((1.0 + 30.0) / (3.0 + 30.0), b.distribution(t0 + 1, kinds)["k1"]!!, 1e-9)
    }

    @Test
    fun `重算_归一衰减_地板下的类清出_二次重算无变化`() {
        val p = TwoLayerProfile()
        // 1000 条 big（基数 10 万）＋1 条 tiny（基数 1）：130 天后条数≈50≥20、tiny 权重 0.0496＜地板
        repeat(1000) { p.feed("big", 100, true, t0 + it) }
        p.feed("tiny", 1, true, t0)
        val now = t0 + 130 * day
        assertTrue(p.recompute(now)) // tiny 清出＋451 条过期明细硬清
        // 清空短期层 ⇒ 弱先验生效（M7 判据的自然推论：清后第一周 1:1:1）
        assertEquals(0.5, p.distribution(now, listOf("tiny", "big"))["big"]!!, 1e-9)
        // 过了弱先验窗口：tiny 已被地板清出，big 独占
        val d = p.distribution(now + 8 * day, listOf("tiny", "big"))
        assertTrue(d["big"]!! > 0.99)
        assertFalse(p.recompute(now)) // 归一后再算一遍，不该再有变化
    }

    // ── 弱先验（M7 判据：清短期层后第一周回 1:1:1） ─────────────────────────

    @Test
    fun `判据_清短期层后第一周回弱先验1比1比1`() {
        val p = TwoLayerProfile()
        repeat(50) { p.feed("k1", 5, true, t0 + it) }
        repeat(10) { p.feed("k2", 5, true, t0 + 100 + it) }
        val kinds = listOf("k1", "k2", "k3")
        val skewed = p.distribution(t0 + 200, kinds)
        assertTrue(skewed["k1"]!! > 0.5)
        // 清短期层 ⇒ 立刻等分
        p.clearShortTerm(t0 + 200)
        val weak = p.distribution(t0 + 201, kinds)
        kinds.forEach { assertEquals(1.0 / 3.0, weak[it]!!, 1e-9) }
        // 第 8 天：弱先验窗口过了，偏斜回来
        val after = p.distribution(t0 + 200 + 8 * day, kinds)
        assertTrue(after["k1"]!! > 1.0 / 3.0)
        assertTrue(after["k3"]!! < 1.0 / 3.0)
    }

    @Test
    fun `样本不足20条也是弱先验_按条数不按权重`() {
        val p = TwoLayerProfile()
        repeat(10) { p.feed("k1", 5, true, t0 + it) } // 权重和 50 ≥20，但条数只有 10
        val d = p.distribution(t0 + 20, listOf("k1", "k2", "k3"))
        listOf("k1", "k2", "k3").forEach { assertEquals(1.0 / 3.0, d[it]!!, 1e-9) }
    }

    @Test
    fun `TTL自然清空到零也触发弱先验_与样本量无关`() {
        val p = TwoLayerProfile(shortTermTtlDays = 15)
        repeat(40) { p.feed("k1", 5, true, t0) }
        repeat(40) { p.feed("k2", 5, true, t0) }
        // 清空前：样本 80×0.5^(15/30)≈57 ≥20，本来不该弱先验
        p.purge(t0 + 15 * day + 1) // 一次性全部过期 → 层被清空 → 弱先验起算
        val d = p.distribution(t0 + 15 * day + 2, listOf("k1", "k2", "k3"))
        listOf("k1", "k2", "k3").forEach { assertEquals(1.0 / 3.0, d[it]!!, 1e-9) }
    }

    // ── 稳定偏好（份额 ≥60% 且连续 2 周） ──────────────────────────────────

    @Test
    fun `稳定偏好_份额达标还要连续两周_跌破即撤销`() {
        val p = TwoLayerProfile()
        repeat(50) { p.feed("k1", 5, true, t0 + it) }
        repeat(5) { p.feed("k2", 1, true, t0 + it) }
        val arm = t0 + 100
        assertFalse(p.isStableKind("k1", arm)) // 刚起算，还没满两周
        assertTrue(p.isStableKind("k1", arm + 14 * day + 1))
        // k2 猛喂把份额压下去 ⇒ 撤销
        repeat(60) { p.feed("k2", 5, true, arm + 14 * day + 2 + it) }
        assertFalse(p.isStableKind("k1", arm + 15 * day))
    }

    @Test
    fun `清短期层连稳定偏好的起算也重置`() {
        val p = TwoLayerProfile()
        repeat(50) { p.feed("k1", 5, true, t0 + it) }
        val arm = t0 + 100
        assertFalse(p.isStableKind("k1", arm)) // 先起算（份额达标那一刻记起点）
        assertTrue(p.isStableKind("k1", arm + 14 * day + 1))
        p.clearShortTerm(arm + 15 * day)
        assertFalse(p.isStableKind("k1", arm + 15 * day + 1)) // 起算被清，重新攒两周
    }

    // ── 滚动窗口与形状闸 ────────────────────────────────────────────────────

    @Test
    fun `滚动窗口最多200条`() {
        val p = TwoLayerProfile()
        repeat(250) { p.feed("k$it", 1, true, t0 + it) }
        assertEquals(TwoLayerProfile.ROLLING_CAP, p.shortTerm.size)
        assertEquals("k50", p.shortTerm.first().kind) // 最老的 50 条被挤掉
    }

    @Test
    fun `形状闸_散文与越界值进不来`() {
        val p = TwoLayerProfile()
        expectFailure { p.feed("用户喜欢长答案", 1, true, t0) } // 中文
        expectFailure { p.feed("k1 k2", 1, true, t0) } // 空格
        expectFailure { p.feed("K1", 1, true, t0) } // 大写
        expectFailure { p.feed("k1", 0, true, t0) } // 权重下界
        expectFailure { p.feed("k1", 101, true, t0) } // 权重上界
        expectFailure { p.feed("k1", 1, true, t0, textSha = "原文不散列") } // 哈希槽只收十六进制
        expectFailure { p.feed("k1", 1, true, 0L) } // 时刻
        // 合法样本照收
        assertTrue(p.feed("k1", 1, true, t0, textSha = "a1b2c3d4e5f6a7b8", textLen = 42))
    }

    // ── 序列化（文件里没有原文） ────────────────────────────────────────────

    @Test
    fun `序列化往返_内容纯ASCII_分布一致`() {
        val p = TwoLayerProfile(shortTermTtlDays = 9)
        p.feed("k1", 5, true, t0, textSha = "a1b2c3d4e5", textLen = 42)
        p.feed("k2", 3, false, t0 + 1)
        val json = p.toJson().toString()
        assertTrue("落盘内容必须纯 ASCII（没有原文的容身之处）", json.none { it.code > 127 })
        val q = TwoLayerProfile()
        assertTrue(q.loadFrom(JSONObject(json)))
        assertEquals(9, q.ttlDays)
        assertEquals(2, q.shortTerm.size)
        val kinds = listOf("k1", "k2")
        assertEquals(
            p.distribution(t0 + 2, kinds)["k1"]!!,
            q.distribution(t0 + 2, kinds)["k1"]!!,
            1e-12,
        )
    }

    @Test
    fun `loadFrom缺键文档_按默认起不抛`() {
        val p = TwoLayerProfile(shortTermTtlDays = 9)
        assertTrue(p.loadFrom(JSONObject())) // 空文档 ⇒ 全默认
        assertEquals(14, p.ttlDays)
        assertEquals(0, p.shortTerm.size)
    }

    @Test
    fun `勘验结论只出计数不出明细`() {
        val p = TwoLayerProfile()
        p.feed("secret_kind", 5, true, t0, textSha = "a1b2c3d4e5f6a7b8", textLen = 99)
        val line = p.conclusion(t0 + 1)
        assertFalse(line.contains("secret_kind"))
        assertFalse(line.contains("a1b2c3d4e5f6a7b8"))
        assertTrue(line.contains("短期层在档 1 条"))
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        fail("应当被形状闸拒绝")
    }
}
