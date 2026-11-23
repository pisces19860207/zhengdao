// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 一维确定性仿真测试（ERRATA 2026-11-24）：固定 dt / 步上限 /
// 初值来源 658·657 / ≥3 步推进 / 同输入逐位一致 / 对外仍档位。
package com.example.zhengdao.baopu.state.thermo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermoSimulationTest {

    private fun input(
        thermal: ThermalBand = ThermalBand.MILD,
        humidity: HumidityBand = HumidityBand.NORMAL,
        wind: WindBand = WindBand.STILL,
        trigger: CremasterTrigger = CremasterTrigger.NONE,
        sweat: Int = 1,
        steps: Int = ThermoClock.MAX_STEPS,
    ) = ThermoInput(thermal, humidity, wind, Medium.NONE, Posture.STAND, trigger, Direction.FLAT, sweat, steps)

    @Test
    fun `ERRATA_dt固定为1_步上限为6`() {
        assertEquals(1, ThermoClock.DT)
        assertEquals(6, ThermoClock.MAX_STEPS)
    }

    @Test
    fun `ERRATA_初值来源658出汗档_不造温度数值`() {
        assertEquals(0, ThermoInit.filmFromSweat(0))
        assertEquals(ThermoClock.PER_GRADE, ThermoInit.filmFromSweat(1))
        assertEquals(3 * ThermoClock.PER_GRADE, ThermoInit.filmFromSweat(3))
        // 初值封顶：越界钳在 0-3 档，不产生超出档域的值
        assertEquals(0, ThermoInit.filmFromSweat(-5))
        assertEquals(3 * ThermoClock.PER_GRADE, ThermoInit.filmFromSweat(99))
    }

    @Test
    fun `ERRATA_温度偏移初值来自657温度档`() {
        assertEquals(-ThermoClock.PER_GRADE, ThermoInit.tempFromBand(ThermalBand.COOL))
        assertEquals(0, ThermoInit.tempFromBand(ThermalBand.MILD))
        assertTrue(ThermoInit.tempFromBand(ThermalBand.HOT) <= ThermoClock.TEMP_MAX)
    }

    @Test
    fun `ERRATA_至少有3步推进路径且状态真变化`() {
        val start = ThermoInit.of(1, ThermalBand.HOT)
        val end = ThermoSolver.advance(start, input(thermal = ThermalBand.HOT, humidity = HumidityBand.HUMID), 3)
        assertEquals(3, end.beat)
        assertNotEquals(start.film, end.film)
        // 每步推进 1 拍，beat 递增严格
        val s1 = ThermoSolver.step(start, input())
        val s2 = ThermoSolver.step(s1, input())
        assertEquals(1, s1.beat)
        assertEquals(2, s2.beat)
    }

    @Test
    fun `ERRATA_步数上限被钳住不超过6`() {
        val start = ThermoInit.of(2, ThermalBand.MILD)
        val end = ThermoSolver.advance(start, input(), 999)
        assertEquals(ThermoClock.MAX_STEPS, end.beat)
    }

    @Test
    fun `ERRATA_同输入两次逐位一致`() {
        val i = input(thermal = ThermalBand.HOT, humidity = HumidityBand.SATURATED, trigger = CremasterTrigger.TOUCH, sweat = 2)
        val a = ThermoEval.eval(i)
        val b = ThermoEval.eval(i)
        assertEquals(a, b)
        assertEquals(Thermo.render(a), Thermo.render(b))
        val ja = Thermo.judge(i)
        val jb = Thermo.judge(i)
        assertEquals(ja, jb)
    }

    @Test
    fun `ERRATA_对外只给档位无毫档外泄`() {
        val r = ThermoEval.eval(input(sweat = 3, thermal = ThermalBand.HOT))
        assertTrue(r.volume in 0..3)
        assertTrue(r.hang in 0..3)
        assertTrue(r.rugosity in 0..3)
        assertTrue(r.surface.wetFilm in 0..3)
        assertTrue(r.surface.colorShift in 0..3)
        assertTrue(r.surface.vesselVisible in 0..2)
        // 渲染文本不含毫档大数（推进刻度量绝不出下游）
        val text = Thermo.render(r)
        assertTrue(ContinuousGuard.hits(text).isEmpty())
    }

    @Test
    fun `提睾必须可回落_时间夹具`() {
        assertTrue(ThermoChecks.recoilWorks(CremasterTrigger.COLD, 1).isEmpty())
        val top = 3 * ThermoClock.PER_GRADE
        assertEquals(top, ThermoSolver.liftAt(CremasterTrigger.COLD, 1, 1))
        assertEquals(0, ThermoSolver.liftAt(CremasterTrigger.COLD, 4, 1))
        assertEquals(0, ThermoSolver.liftAt(CremasterTrigger.NONE, 1, 1))
    }

    @Test
    fun `提睾只上不落不成立_随拍数严格下降`() {
        val beats = (1..4).map { ThermoSolver.liftAt(CremasterTrigger.COLD, it, 1) }
        for (i in 1 until beats.size) assertTrue(beats[i] < beats[i - 1])
        assertEquals(0, beats.last())
    }

    @Test
    fun `方向断言三条加风_全过`() {
        assertTrue(ThermoChecks.directionsHold().isEmpty())
    }
}
