package com.example.zhengdao.baopu.state.environment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 657 判据⑤（风）：风级 0/3/6/9 四档 ⇒ 影响清单单调增强。
 * 每类影响同时钉住四档的精确档位序列（防「单调但整体平移」类变异），
 * 另：风速↔风级换算往返、阵风系数、风向归一、确定性、assumption＋note、参数拒收。
 */
class WindTableTest {

    private val probe = listOf(0, 3, 6, 9)

    private fun grades(kind: String): List<Int> = probe.map { b ->
        WindTable.of(b, 90.0).effects.first { it.kind == kind }.grade
    }

    private fun dump(w: WindTable.WindState): ByteArray =
        (
            listOf(w.beaufort, w.dirDeg, w.gustFactor, w.feels, w.assumption, w.note) +
                w.effects.flatMap { listOf(it.kind, it.grade, it.label) }
            ).joinToString("|").toByteArray(Charsets.UTF_8)

    @Test
    fun effectsAreMonotoneAcrossBeaufort0369() {
        for (kind in WindTable.KINDS) {
            val g = grades(kind)
            for (i in 1 until g.size) {
                assertTrue("判据⑤：$kind 档位应单调不降，实测 $g", g[i] >= g[i - 1])
            }
        }
        val feels = probe.map { WindTable.of(it, 90.0).feels }
        assertEquals(listOf("无", "微风", "有风", "大风"), feels)
        assertEquals("恶劣", WindTable.of(12, 90.0).feels)
    }

    @Test
    fun effectGradeTablesArePinnedAtProbePoints() {
        assertEquals("起沙档位表", listOf(0, 0, 1, 2), grades("sand_lift"))
        assertEquals("吹平档位表", listOf(0, 0, 1, 2), grades("sand_flatten"))
        assertEquals("衣摆档位表", listOf(0, 1, 2, 3), grades("cloth_hem"))
        assertEquals("发向档位表", listOf(0, 1, 1, 1), grades("hair_dir"))
        assertEquals("风声档位表", listOf(0, 1, 2, 3), grades("wind_sound"))
        assertEquals("体感档位表", listOf(0, 1, 1, 2), grades("touch"))
    }

    @Test
    fun effectKindsFormTheClosedSetInOrder() {
        val w = WindTable.of(6, 90.0)
        assertEquals(WindTable.KINDS, w.effects.map { it.kind })
        assertEquals(6, w.effects.size)
        for (e in w.effects) assertTrue(e.label.isNotBlank())
    }

    @Test
    fun windSpeedRoundTripsThroughBeaufort() {
        for (b in 0..12) {
            assertEquals("代表风速往返回收风级 $b", b, WindTable.beaufortOf(WindTable.representativeMs(b)))
        }
        assertEquals(0, WindTable.beaufortOf(0.0))
        assertEquals(12, WindTable.beaufortOf(40.0))
    }

    @Test
    fun gustFactorAndDirectionAreDeterministic() {
        assertEquals(1.3, WindTable.of(6, 90.0).gustFactor, 1e-12)
        assertEquals(1.0, WindTable.of(0, 90.0).gustFactor, 1e-12)
        assertEquals("负风向归一到 0..360", 270.0, WindTable.of(3, -90.0).dirDeg, 1e-12)
        assertEquals(90.0, WindTable.of(3, 450.0).dirDeg, 1e-12)
    }

    @Test
    fun sameInputGivesByteIdenticalOutput() {
        val a = WindTable.of(6, 90.0)
        val b = WindTable.of(6, 90.0)
        assertEquals(a, b)
        assertArrayEquals(dump(a), dump(b))
    }

    @Test
    fun resultCarriesAssumptionAndNotMeasurementNote() {
        val w = WindTable.of(6, 90.0)
        assertEquals("模拟结果，非测量", w.note)
        assertTrue(w.assumption.isNotBlank())
        assertTrue("assumption 应写明档位表非流场", w.assumption.contains("档位"))
    }

    @Test
    fun outOfDomainInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WindTable.of(13, 90.0) }
        assertThrows(IllegalArgumentException::class.java) { WindTable.of(-1, 90.0) }
        assertThrows(IllegalArgumentException::class.java) { WindTable.beaufortOf(-0.1) }
        assertThrows(IllegalArgumentException::class.java) { WindTable.representativeMs(13) }
    }
}