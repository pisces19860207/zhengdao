// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零四批 · 限频预算的 JVM 钉子（派工单 §3：同类 24h 最多 1 次；每月 ≤20）。
package com.example.zhengdao.baopu.evolution.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvalTriggerBudgetTest {

    private val day = EvalTriggerBudget.SAME_TYPE_INTERVAL_MS

    private fun triggered(type: TriggerType, at: Long) = TriggerRow(
        atMs = at, type = type, triggered = true, ref = "x", intent = "",
    )

    private fun suppressed(type: TriggerType, at: Long) = TriggerRow(
        atMs = at, type = type, triggered = false, ref = "x", intent = "", suppressReason = "同日",
    )

    @Test
    fun `从没触发过 ⇒ 放行`() {
        assertNull(EvalTriggerBudget.suppressReason(TriggerType.RUN_FAILED, emptyList(), nowMs = 1_000L))
    }

    @Test
    fun `同类 24h 内 ⇒ 抑制（验收②：同日再 FAILED 不触发）`() {
        val rows = listOf(triggered(TriggerType.RUN_FAILED, at = 1_000L))
        val reason = EvalTriggerBudget.suppressReason(
            TriggerType.RUN_FAILED, rows, nowMs = 1_000L + day - 1,
        )
        assertEquals("同类「任务失败」24h 内已评过（上次 1000）", reason)
    }

    @Test
    fun `恰好满 24h ⇒ 放行（边界含等号）`() {
        val rows = listOf(triggered(TriggerType.RUN_FAILED, at = 1_000L))
        assertNull(
            EvalTriggerBudget.suppressReason(TriggerType.RUN_FAILED, rows, nowMs = 1_000L + day),
        )
    }

    @Test
    fun `别的类型不受同类限频影响`() {
        val rows = listOf(triggered(TriggerType.RUN_FAILED, at = 1_000L))
        assertNull(
            EvalTriggerBudget.suppressReason(TriggerType.USER_CORRECTION, rows, nowMs = 1_000L + 60),
        )
    }

    @Test
    fun `被抑制的尝试不消耗预算`() {
        val rows = listOf(suppressed(TriggerType.RUN_FAILED, at = 1_000L))
        assertNull(
            EvalTriggerBudget.suppressReason(TriggerType.RUN_FAILED, rows, nowMs = 1_000L + 60),
        )
    }

    @Test
    fun `本月 19 次 ⇒ 放行；第 20 次之后 ⇒ 抑制（月上限）`() {
        // 月份函数注入成常量（与"什么时候"无关，只数条数）；账行间隔 25h，
        // 免得同类 24h 判定先于月判定触发（那是另一条测试的事）。
        val fixedMonth: (Long) -> Int = { 202_610 }
        val h = EvalTriggerBudget.SAME_TYPE_INTERVAL_MS + 3_600_000L // 25h
        val rows19 = (1..19).map { triggered(TriggerType.MANUAL, at = it * h) }
        assertNull(
            EvalTriggerBudget.suppressReason(TriggerType.MANUAL, rows19, 20 * h + 1, monthOf = fixedMonth),
        )
        val rows20 = rows19 + triggered(TriggerType.MANUAL, at = 20 * h)
        val reason = EvalTriggerBudget.suppressReason(TriggerType.MANUAL, rows20, 21 * h + 1, monthOf = fixedMonth)
        assertEquals("本月已触发 20 次（上限 20）", reason)
    }

    @Test
    fun `跨月重置（上月 20 次不堵本月）`() {
        val h = EvalTriggerBudget.SAME_TYPE_INTERVAL_MS + 3_600_000L // 25h
        val rows = (1..25).map { triggered(TriggerType.MANUAL, at = it * h) }
        // 账行全在上月（< 100h），"现在"在本月（200h）⇒ 月计数从零起算，24h 也早过了。
        assertNull(
            EvalTriggerBudget.suppressReason(
                TriggerType.MANUAL, rows, nowMs = 200 * h,
                monthOf = { if (it < 100 * h) 202_609 else 202_610 },
            ),
        )
        // 反向钉：同一批行若都算本月 ⇒ 抑制（证明上面放行真是"跨月"的功劳）。
        val stillThisMonth = EvalTriggerBudget.suppressReason(
            TriggerType.MANUAL, rows, nowMs = 200 * h, monthOf = { 202_610 },
        )
        assertEquals("本月已触发 25 次（上限 20）", stillThisMonth)
    }

    @Test
    fun `月上限是四类合计（月键不分类型）`() {
        val fixedMonth: (Long) -> Int = { 202_610 }
        val h = EvalTriggerBudget.SAME_TYPE_INTERVAL_MS + 3_600_000L // 25h
        val rows = (0 until 20).map { i ->
            triggered(TriggerType.entries[i % TriggerType.entries.size], at = (i + 1) * h)
        }
        // now 取得足够晚：同类 24h 不挡，让月判定说话。
        val reason = EvalTriggerBudget.suppressReason(TriggerType.RUN_FAILED, rows, 100 * h, monthOf = fixedMonth)
        assertEquals("本月已触发 20 次（上限 20）", reason)
    }

    @Test
    fun `自然月键与本机日历一致`() {
        // 2026-11-01 00:30 本机时区 ⇒ 202611（Calendar 同源，避免 UTC 差一天）。
        val c = java.util.Calendar.getInstance()
        c.set(2026, 10, 1, 0, 30, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        assertEquals(202611, EvalTriggerBudget.calendarMonthOf(c.timeInMillis))
    }
}
