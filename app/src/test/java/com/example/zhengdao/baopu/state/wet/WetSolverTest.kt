// 第八百九十七批 · 时间夹具：三拍必降档、拍预算 1/3/6、整数推进可复现。
package com.example.zhengdao.baopu.state.wet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WetSolverTest {

    private val conditions = listOf(
        DryAir.STILL to null,
        DryAir.STILL to WaistTier.SECURE,
        DryAir.BREEZE to WaistTier.LOOSE,
        DryAir.WINDY to WaistTier.MIDDLE,
        DryAir.WINDY to WaistTier.SECURE,
    )

    @Test
    fun `判据二_三拍后湿身至少降一档_全起点全环境`() {
        SkinWet.entries.filter { it.level > 0 }.forEach { skin ->
            conditions.forEach { (air, waist) ->
                val start = WetSolver.start(skin, WarmLevel.CLEAR)
                val end = WetSolver.advance(start, air, waist, 2, WetClock.DRY_CHECK_BEATS)
                val actual = WetSolver.skinGrade(end.skinMilli).level
                assertTrue(
                    "$skin / $air / $waist 三拍后仍为 $actual 档",
                    actual <= skin.level - 1,
                )
            }
        }
    }

    @Test
    fun `边界_半干是终点不会变负_起点半干不再降`() {
        val start = WetSolver.start(SkinWet.HALF_DRY, WarmLevel.SLIGHT)
        val end = WetSolver.advance(start, DryAir.WINDY, null, 0, WetClock.MAX_STEPS)
        assertEquals(0, end.skinMilli)
        assertEquals(SkinWet.HALF_DRY, WetSolver.skinGrade(end.skinMilli))
    }

    @Test
    fun `拍口径_恢复预算来自1比3比6计划口径`() {
        assertEquals(1, WetClock.recoveryBeats(0))
        assertEquals(3, WetClock.recoveryBeats(1))
        assertEquals(6, WetClock.recoveryBeats(2))
        assertEquals(1, WetClock.recoveryBeats(-9))
        assertEquals(6, WetClock.recoveryBeats(9))
    }

    @Test
    fun `偏温_三档预算逐拍单调回落并走完归零`() {
        listOf(0, 1, 2).forEach { level ->
            val budget = WetClock.recoveryBeats(level)
            val series = (1..budget + 1).map { WetSolver.warmAt(WarmLevel.CLEAR.level, it, level) }
            assertEquals(WarmLevel.CLEAR.level * WetClock.PER_GRADE, series.first())
            assertEquals(0, series.last())
            assertTrue("预算 $budget 的回落序列非单调：$series", series == series.sortedDescending())
        }
    }

    @Test
    fun `偏温_一档起点也回落且不越界`() {
        val top = WarmLevel.SLIGHT.level * WetClock.PER_GRADE
        assertEquals(top, WetSolver.warmAt(WarmLevel.SLIGHT.level, 1, 2))
        assertEquals(0, WetSolver.warmAt(WarmLevel.SLIGHT.level, WetClock.MAX_STEPS + 1, 2))
        assertEquals(0, WetSolver.warmAt(WarmLevel.SETTLED.level, 3, 1))
        assertEquals(0, WetSolver.warmAt(9, WetClock.MAX_STEPS + 1, 1))
    }

    @Test
    fun `确定性_同输入两次逐位一致且推进有上限`() {
        val first = WetSolver.advance(
            WetSolver.start(SkinWet.DRIPPING, WarmLevel.CLEAR), DryAir.BREEZE, null, 1, 3
        )
        val second = WetSolver.advance(
            WetSolver.start(SkinWet.DRIPPING, WarmLevel.CLEAR), DryAir.BREEZE, null, 1, 3
        )
        assertEquals(first, second)
        assertEquals(3, first.beat)
        val capped = WetSolver.advance(
            WetSolver.start(SkinWet.DRIPPING, WarmLevel.CLEAR), DryAir.BREEZE, null, 1, 99
        )
        assertEquals(WetClock.MAX_STEPS, capped.beat)
        assertEquals(
            WetSolver.advance(
                WetSolver.start(SkinWet.DRIPPING, WarmLevel.CLEAR),
                DryAir.BREEZE, null, 1, WetClock.MAX_STEPS,
            ),
            capped,
        )
    }

    @Test
    fun `单拍_拍钟固定为一且毫档有界`() {
        assertEquals(1, WetClock.DT)
        assertEquals(6, WetClock.MAX_STEPS)
        assertTrue(WetClock.MIN_DECAY * WetClock.DRY_CHECK_BEATS >= WetClock.PER_GRADE)
        val one = WetSolver.step(
            WetSolver.start(SkinWet.DRIPPING, WarmLevel.CLEAR), DryAir.STILL, null, 1
        )
        assertEquals(1, one.beat)
        assertTrue(one.skinMilli in 0..WetClock.WET_MAX)
        assertTrue(one.warmMilli in 0..WetClock.WARM_MAX)
    }
}
