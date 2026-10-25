// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十批 · 接线件判据：事后账（ImpactLedger）／信箱（ImpactMailbox）／
// 开关（ImpactPrefs）／耗时读数（ImpactStats）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImpactWiringTest {

    // ── 开关：默认开、关掉就关掉 ─────────────────────────────────────────

    @Test
    fun `判据_开关默认开`() {
        assertTrue("没存过 ⇒ 默认开（派工单 §2.5）", ImpactPrefs.resolve(null))
        assertTrue(ImpactPrefs.resolve(true))
        assertFalse(ImpactPrefs.resolve(false))
    }

    // ── 事后账：只存摘要＋哈希，环形有界，新的在前 ───────────────────────

    @Test
    fun `判据_台账新的在前且有条数上限`() {
        ImpactLedger.clear()
        repeat(ImpactLedger.CAPACITY + 7) { i ->
            ImpactLedger.record("write_file", "第 $i 行", shown = true, atMs = i.toLong())
        }
        val snap = ImpactLedger.snapshot()
        assertEquals("环子满了就丢最老的", ImpactLedger.CAPACITY, snap.size)
        assertEquals("新的在前", ImpactLedger.CAPACITY + 6L, snap.first().atMs)
        ImpactLedger.clear()
        assertTrue(ImpactLedger.snapshot().isEmpty())
    }

    @Test
    fun `判据_台账只存摘要与12位哈希_不存原始名单`() {
        ImpactLedger.clear()
        val r = ImpactLedger.record("delete_path", "删 3 个文件（1.2MB）", shown = false)
        assertEquals(12, r.hash.length)
        assertTrue("哈希只含十六进制", r.hash.all { it in "0123456789abcdef" })
        assertFalse("没显示过就要如实记 false（无卡场景）", r.shown)
        // 同摘要 ⇒ 同哈希（这是它能当去重键的前提）
        assertEquals(r.hash, ImpactLedger.record("delete_path", "删 3 个文件（1.2MB）", shown = true).hash)
        ImpactLedger.clear()
    }

    // ── 信箱：一次性、只留最新 ───────────────────────────────────────────

    @Test
    fun `判据_信箱取走即清`() {
        ImpactMailbox.clear()
        ImpactMailbox.offer("A")
        assertEquals(listOf("A"), ImpactMailbox.drain())
        assertTrue("第二次必须空（不许重放）", ImpactMailbox.drain().isEmpty())
    }

    @Test
    fun `判据_信箱只留最新一条`() {
        ImpactMailbox.clear()
        ImpactMailbox.offer("A")
        ImpactMailbox.offer("B")
        assertEquals(listOf("B"), ImpactMailbox.drain())
    }

    @Test
    fun `判据_信箱空串当没有`() {
        ImpactMailbox.clear()
        ImpactMailbox.offer("   ")
        assertTrue(ImpactMailbox.drain().isEmpty())
    }

    // ── 耗时读数：p50/p95 与降级次数 ────────────────────────────────────

    @Test
    fun `判据_耗时读数_p50与p95按样本算`() {
        ImpactStats.clear()
        (1..100).forEach { ImpactStats.record(it.toLong()) }
        assertEquals(50L, ImpactStats.p50())
        assertEquals(95L, ImpactStats.p95())
        assertEquals(100L, ImpactStats.count())
        assertEquals(">300ms 的有 0 个（最大才 100）", 0, ImpactStats.overBudget())
        ImpactStats.record(400)
        assertEquals(1, ImpactStats.overBudget())
        ImpactStats.clear()
    }

    @Test
    fun `判据_耗时读数_没有样本时不装数字`() {
        ImpactStats.clear()
        assertEquals(-1L, ImpactStats.p50())
        assertEquals(-1L, ImpactStats.p95())
    }

    @Test
    fun `判据_耗时读数有界_不会无限涨`() {
        ImpactStats.clear()
        // 灌 3 倍窗口的全超预算样本 ⇒ 窗口里只该留下 CAPACITY 个（再多就是内存账本泄漏）
        repeat(ImpactStats.CAPACITY * 3) { ImpactStats.record(500) }
        assertEquals(ImpactStats.CAPACITY, ImpactStats.overBudget())
        ImpactStats.clear()
    }
}
