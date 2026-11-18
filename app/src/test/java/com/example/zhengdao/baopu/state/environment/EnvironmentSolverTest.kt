package com.example.zhengdao.baopu.state.environment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 657 统一入口：EnvironmentState 即 SceneState.environment 的槽值（SceneState 本体归 660）。
 * 钉住：一次求解五分项齐全、风级→代表风速接线、整体确定性（逐字节）、note/assumption、拒收传递。
 */
class EnvironmentSolverTest {

    private fun dump(e: EnvironmentState): ByteArray =
        (
            listOf(
                e.sun.azimuthDeg, e.sun.elevationDeg, e.sun.shadowRatio, e.sun.shadowDirDeg,
                e.sun.intensity, e.sun.polar, e.sun.assumption, e.sun.note,
                e.wind.beaufort, e.wind.dirDeg, e.wind.gustFactor, e.wind.feels,
                e.wind.assumption, e.wind.note,
                e.humidity.rhPct, e.humidity.dewPointC, e.humidity.wetBulbC,
                e.humidity.evaporation, e.humidity.assumption, e.humidity.note,
                e.sweat.grade, e.sweat.assumption, e.sweat.note,
                e.sand.grade, e.sand.label, e.sand.binding, e.sand.assumption, e.sand.note,
                e.assumption, e.note,
            ) + e.wind.effects.flatMap { listOf(it.kind, it.grade, it.label) } +
                e.sweat.effects.flatMap { listOf(it.kind, it.grade, it.label) }
            ).joinToString("|").toByteArray(Charsets.UTF_8)

    private fun sample() = EnvironmentSolver.solve(18.0, 172, 12.0, 3, 90.0, 30.0, 60.0, 1, 0)

    @Test
    fun oneCallProducesAllFiveParts() {
        val e = sample()
        assertTrue("光照分项应满足判据①", e.sun.elevationDeg > 75.0 && e.sun.shadowRatio < 0.4)
        assertEquals(3, e.wind.beaufort)
        assertEquals(6, e.wind.effects.size)
        assertTrue("湿球≤干球应传递到整体", e.humidity.wetBulbC <= 30.0 + 1e-12)
        assertEquals("风级 3 代表风速 4.4 ⇒ 产汗档 2", 2, e.sweat.grade)
        assertEquals(4, e.sweat.effects.size)
        assertEquals("干沙", e.sand.label)
        assertEquals(0.2, e.sand.binding, 1e-12)
    }

    @Test
    fun beaufortFeedsHumidityAndSweatViaRepresentativeSpeed() {
        val e = EnvironmentSolver.solve(18.0, 172, 12.0, 6, 90.0, 30.0, 60.0, 1, 0)
        assertEquals("风级 6 代表风速 12.3 ⇒ 蒸发高", "高", e.humidity.evaporation)
        assertEquals("风级 6 代表风速 12.3 ⇒ 产汗档 0（若误把风级当风速则为 1）", 0, e.sweat.grade)
    }

    @Test
    fun sameInputGivesByteIdenticalEnvironment() {
        val a = sample()
        val b = sample()
        assertEquals(a, b)
        assertArrayEquals(dump(a), dump(b))
    }

    @Test
    fun everyPartCarriesTheNotMeasurementNote() {
        val e = sample()
        for (note in listOf(e.note, e.sun.note, e.wind.note, e.humidity.note, e.sweat.note, e.sand.note)) {
            assertEquals("模拟结果，非测量", note)
        }
        for (a in listOf(e.assumption, e.sun.assumption, e.wind.assumption, e.humidity.assumption, e.sweat.assumption, e.sand.assumption)) {
            assertTrue(a.isNotBlank())
        }
    }

    @Test
    fun outOfDomainInputsPropagateRejection() {
        assertThrows(IllegalArgumentException::class.java) {
            EnvironmentSolver.solve(18.0, 172, 12.0, 13, 90.0, 30.0, 60.0, 1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvironmentSolver.solve(91.0, 172, 12.0, 3, 90.0, 30.0, 60.0, 1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnvironmentSolver.solve(18.0, 172, 12.0, 3, 90.0, 30.0, 60.0, 5, 0)
        }
    }
}