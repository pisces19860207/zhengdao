// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：2026-10-18 真机 T4 实测（刀 3 A 段验收）——「确认卡出来后一直放前台不动，
// 15 分钟过去 pending 还在库里」，根因是引擎只读**已结账**的前台毫秒（切后台才结账），
// 当前段落没有现算 ⇒ 一直前台的等待永远不到线。判据是纯函数，JVM 直测；
// 引擎接线（`RunEngine` 的 `foregroundElapsedMs` 闭包）由真机 T4 复验。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRulesConfirmElapsedTest {

    private val minute = 60_000L

    @Test
    fun `前台连续不动_当前段落必须现算_15分钟到线`() {
        // 进确认后在库里的账是 0，回前台时刻 = t0，此刻 = t0 + 15min
        val elapsed = RunRules.confirmElapsedMs(committed = 0L, since = 1_000L, now = 1_000L + 15 * minute)
        assertEquals(15 * minute, elapsed)
        assertTrue(RunRules.confirmExpired(elapsed))
    }

    @Test
    fun `只结账不现算_会永远不到线_这就是真机那条bug`() {
        // 旧接线的等价物：只读 committed（0）⇒ 15 分钟过去仍是 false
        assertFalse(RunRules.confirmExpired(0L))
    }

    @Test
    fun `切后台后时间冻结_回前台继续计`() {
        // 已结账 2 分钟（后台中：since = null）⇒ 再等多久都还是 2 分钟
        assertEquals(2 * minute, RunRules.confirmElapsedMs(2 * minute, since = null, now = 9 * minute))
        // 回前台（since = t）⇒ 从 t 起继续累加
        assertEquals(2 * minute + 5 * minute, RunRules.confirmElapsedMs(2 * minute, since = 10L, now = 10L + 5 * minute))
    }

    @Test
    fun `时钟回拨不吃负数`() {
        assertEquals(0L, RunRules.confirmElapsedMs(0L, since = 5_000L, now = 4_000L))
        assertEquals(3 * minute, RunRules.confirmElapsedMs(3 * minute, since = 5_000L, now = 4_000L))
    }

    @Test
    fun `后台再久也不到线_前台凑满才到线`() {
        // 结账 14 分钟、后台不动（since null）⇒ 不到线
        assertFalse(RunRules.confirmExpired(RunRules.confirmElapsedMs(14 * minute, since = null, now = 100 * minute)))
        // 回前台再计 1 分钟 ⇒ 到线
        assertTrue(RunRules.confirmExpired(RunRules.confirmElapsedMs(14 * minute, since = 50L, now = 50L + minute)))
    }
}
