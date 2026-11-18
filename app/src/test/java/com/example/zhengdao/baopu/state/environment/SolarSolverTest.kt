package com.example.zhengdao.baopu.state.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 657 判据①–④（光照）：
 * ① 北纬 18° 夏至正午 ⇒ 高度角 >75°、影长比 <0.4；
 * ② 日落前 1 小时 ⇒ 影长比 >2.5，影子朝太阳反侧；
 * ③ 南半球同日期影子方向相反（春分取 δ≈0，夏至 ±18° 同侧不构成反例）；
 * ④ 极昼/极夜不崩，返回「太阳不升起/不落下」。
 * 另：确定性（同输入逐字节一致）、assumption＋note、真太阳时换算、参数拒收。
 */
class SolarSolverTest {

    private fun dump(s: SolarSolver.SunState): ByteArray =
        listOf(
            s.azimuthDeg, s.elevationDeg, s.shadowRatio, s.shadowDirDeg,
            s.intensity, s.polar, s.assumption, s.note,
        ).joinToString("|").toByteArray(Charsets.UTF_8)

    @Test
    fun noonAt18NorthSummerSolsticeIsHighSunShortShadow() {
        val s = SolarSolver.solve(18.0, 172, 12.0)
        assertTrue("判据①：高度角应 >75°，实测 ${s.elevationDeg}", s.elevationDeg > 75.0)
        assertTrue("判据①：影长比应 <0.4，实测 ${s.shadowRatio}", s.shadowRatio < 0.4)
        assertEquals("正午高度角锚定预核算值 84.5502°", 84.5502, s.elevationDeg, 0.01)
        assertEquals("正午影长比锚定预核算值 0.0954", 0.0954, s.shadowRatio, 0.001)
        assertEquals("夏至正午太阳在正北（δ=23.45°>φ=18°）", 0.0, s.azimuthDeg, 0.01)
        assertEquals("影子朝南", 180.0, s.shadowDirDeg, 0.01)
        assertEquals("强", s.intensity)
        assertEquals("none", s.polar)
    }

    @Test
    fun oneHourBeforeSunsetShadowIsLongAndOppositeTheSun() {
        val sunset = SolarSolver.sunsetOf(18.0, 172)
        assertEquals("日落时刻锚定预核算值 18.5402h", 18.5402, sunset.trueSolarTimeH!!, 0.01)
        val s = SolarSolver.solve(18.0, 172, sunset.trueSolarTimeH - 1.0)
        assertTrue("判据②：影长比应 >2.5，实测 ${s.shadowRatio}", s.shadowRatio > 2.5)
        assertEquals("判据②：影长比锚定预核算值 4.2752", 4.2752, s.shadowRatio, 0.01)
        val opposite = (s.azimuthDeg + 180.0) % 360.0
        assertEquals("判据②：影子方向=太阳方位+180°", opposite, s.shadowDirDeg, 1e-9)
    }

    @Test
    fun equinoxNoonShadowsOppositeAcrossHemispheres() {
        val north = SolarSolver.solve(18.0, 81, 12.0)
        val south = SolarSolver.solve(-18.0, 81, 12.0)
        assertEquals("春分北半球正午太阳在正南", 180.0, north.azimuthDeg, 0.01)
        assertEquals("春分北半球影子朝北", 0.0, north.shadowDirDeg, 0.01)
        assertEquals("春分南半球正午太阳在正北", 0.0, south.azimuthDeg, 0.01)
        assertEquals("判据③：南半球影子朝南", 180.0, south.shadowDirDeg, 0.01)
        val diff = Math.abs(north.shadowDirDeg - south.shadowDirDeg)
        assertEquals("判据③：两半球影子方向相差 180°", 180.0, diff, 0.01)
    }

    @Test
    fun midnightSunAt78NorthDoesNotCrash() {
        val s = SolarSolver.solve(78.0, 172, 12.0)
        assertEquals("判据④：极昼标记", "midnight_sun", s.polar)
        assertTrue("极昼正午太阳仍在天上", s.elevationDeg > 0.0)
        assertNull("极昼无日落时刻", SolarSolver.sunsetOf(78.0, 172).trueSolarTimeH)
        val edge = SolarSolver.solve(67.0, 172, 12.0)
        assertEquals("极昼边界内侧（67°N）仍判极昼", "midnight_sun", edge.polar)
        val outside = SolarSolver.solve(66.0, 172, 12.0)
        assertEquals("极昼边界外侧（66°N）有日落", "none", outside.polar)
        assertTrue(SolarSolver.sunsetOf(66.0, 172).trueSolarTimeH!! > 12.0)
    }

    @Test
    fun polarNightAt78NorthDoesNotCrash() {
        val s = SolarSolver.solve(78.0, 355, 12.0)
        assertEquals("判据④：极夜标记", "polar_night", s.polar)
        assertTrue("极夜正午太阳仍在地平线下", s.elevationDeg <= 0.0)
        assertTrue("极夜影长比=Infinity", s.shadowRatio.isInfinite())
        assertEquals("无", s.intensity)
        assertNull("极夜无日落时刻", SolarSolver.sunsetOf(78.0, 355).trueSolarTimeH)
        assertEquals("polar_night", SolarSolver.sunsetOf(78.0, 355).polar)
    }

    @Test
    fun midnightAt18NorthHasNoDirectLight() {
        val s = SolarSolver.solve(18.0, 172, 0.0)
        assertTrue(s.elevationDeg < 0.0)
        assertTrue(s.shadowRatio.isInfinite())
        assertEquals("无", s.intensity)
    }

    @Test
    fun trueSolarTimeAppliesLongitudeAndEquationOfTime() {
        val atMeridian = SolarSolver.toTrueSolarTime(12.0, 120.0, 8.0, 172)
        assertEquals("经度恰在时区中央子午线：只剩时差 −1.5 分钟", 11.975, atMeridian.hours, 1e-9)
        val beijing = SolarSolver.toTrueSolarTime(12.0, 116.4, 8.0, 172)
        assertEquals("116.4°E：−14.4−1.5=−15.9 分钟", 11.735, beijing.hours, 1e-9)
        assertTrue(beijing.assumption.isNotBlank())
        assertEquals("模拟结果，非测量", beijing.note)
    }

    @Test
    fun sameInputGivesByteIdenticalOutput() {
        val a = SolarSolver.solve(18.0, 172, 12.0)
        val b = SolarSolver.solve(18.0, 172, 12.0)
        assertEquals(a, b)
        org.junit.Assert.assertArrayEquals(dump(a), dump(b))
    }

    @Test
    fun resultCarriesAssumptionAndNotMeasurementNote() {
        val s = SolarSolver.solve(18.0, 172, 12.0)
        assertEquals("模拟结果，非测量", s.note)
        assertTrue(s.assumption.isNotBlank())
        assertTrue("assumption 应写明公式版本", s.assumption.contains("SPA"))
        assertTrue("assumption 应写明忽略项", s.assumption.contains("折射"))
        val sunset = SolarSolver.sunsetOf(18.0, 172)
        assertEquals("模拟结果，非测量", sunset.note)
        assertTrue(sunset.assumption.isNotBlank())
    }

    @Test
    fun outOfDomainInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SolarSolver.declinationDeg(0) }
        assertThrows(IllegalArgumentException::class.java) { SolarSolver.declinationDeg(367) }
        assertThrows(IllegalArgumentException::class.java) { SolarSolver.solve(91.0, 172, 12.0) }
        assertThrows(IllegalArgumentException::class.java) { SolarSolver.solve(18.0, 172, 25.0) }
    }
}