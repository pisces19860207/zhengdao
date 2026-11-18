package com.example.zhengdao.baopu.state.environment

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 657 判据（湿度与汗液，追加段）：
 * ① 湿度升高 ⇒ 蒸发档下降、体感温度档上升（单调）；
 * ② 风速升高 ⇒ 蒸发档上升（单调）；
 * ③ 湿球温度必须 ≤ 干球温度（Stull 原式在 30°C/100% 处会略超，钳位是牙）；
 * ④ 活动强度升高 ⇒ 产汗档单调不降（端点钉死，防整体压平）；
 * ⑤ 沙含水档升高 ⇒ 束缚系数升高；
 * ⑥ 全部输出带 assumption＋「模拟结果，非测量」。
 */
class HumiditySolverTest {

    private fun dump(h: HumiditySolver.HumidityState): ByteArray =
        listOf(h.rhPct, h.dewPointC, h.wetBulbC, h.evaporation, h.assumption, h.note)
            .joinToString("|").toByteArray(Charsets.UTF_8)

    private fun feltGrade(rh: Double): Int =
        HumiditySolver.sweatOf(0, 25.0, rh, 0.0, 0).effects.first { it.kind == "felt_temp" }.grade

    @Test
    fun wetBulbNeverExceedsDryBulb() {
        for (t in listOf(-20.0, 0.0, 10.0, 20.0, 30.0, 40.0, 50.0)) {
            for (rh in listOf(5.0, 20.0, 50.0, 80.0, 99.0, 100.0)) {
                val tw = HumiditySolver.wetBulbC(t, rh)
                assertTrue("判据③：湿球 $tw 不得超过干球 $t（RH=$rh）", tw <= t + 1e-12)
            }
        }
    }

    @Test
    fun stullClampBitesAtHotSaturatedCorner() {
        assertEquals("30°C/100%：Stull 原式≈30.08，钳位后恰为干球", 30.0, HumiditySolver.wetBulbC(30.0, 100.0), 1e-12)
        assertEquals("Stull 公式锚定预核算值 22.2968", 22.2968, HumiditySolver.wetBulbC(30.0, 50.0), 0.01)
    }

    @Test
    fun dewPointFollowsMagnus() {
        assertEquals("露点锚定预核算值 18.44", 18.44, HumiditySolver.dewPointC(30.0, 50.0), 0.05)
        assertEquals("饱和时露点=干球", 30.0, HumiditySolver.dewPointC(30.0, 100.0), 1e-9)
        assertTrue(HumiditySolver.dewPointC(30.0, 30.0) < HumiditySolver.dewPointC(30.0, 80.0))
    }

    @Test
    fun higherHumidityLowersEvaporationGrade() {
        val grades = listOf(30.0, 60.0, 90.0).map { HumiditySolver.humidityOf(30.0, it, 1.0).evaporation }
        val rank = mapOf("低" to 0, "中" to 1, "高" to 2)
        for (i in 1 until grades.size) {
            assertTrue("判据追加①：蒸发档应随湿度单调不升，实测 $grades", rank.getValue(grades[i]) <= rank.getValue(grades[i - 1]))
        }
        assertEquals("端点钉死：RH30 ⇒ 中", "中", grades.first())
        assertEquals("端点钉死：RH90 ⇒ 低", "低", grades.last())
    }

    @Test
    fun higherWindRaisesEvaporationGrade() {
        val grades = listOf(0.0, 2.0, 8.0).map { HumiditySolver.humidityOf(30.0, 60.0, it).evaporation }
        assertEquals("判据追加②：低→中→高", listOf("低", "中", "高"), grades)
    }

    @Test
    fun higherActivityNeverLowersSweatGrade() {
        val grades = (0..4).map { HumiditySolver.sweatOf(it, 30.0, 60.0, 1.0, 0).grade }
        for (i in 1 until grades.size) {
            assertTrue("判据追加④：产汗档应单调不降，实测 $grades", grades[i] >= grades[i - 1])
        }
        assertEquals("端点钉死：静息 30°C 也有微汗", 1, grades.first())
        assertEquals("端点钉死：强度 3 ⇒ 大汗", 4, grades[3])
        assertEquals(listOf(1, 2, 3, 4, 4), grades)
    }

    @Test
    fun windAndTemperatureMoveSweatInTheRightDirection() {
        val byWind = listOf(0.0, 5.0, 10.0).map { HumiditySolver.sweatOf(2, 30.0, 60.0, it, 0).grade }
        assertEquals("风大 ⇒ 汗少", listOf(3, 2, 2), byWind)
        val byTemp = listOf(20.0, 30.0, 40.0).map { HumiditySolver.sweatOf(1, it, 60.0, 1.0, 0).grade }
        assertEquals("温高 ⇒ 汗多", listOf(1, 2, 4), byTemp)
    }

    @Test
    fun higherHumidityRaisesFeltTemperatureGrade() {
        val dry = feltGrade(40.0)
        val humid = feltGrade(90.0)
        assertTrue("判据追加①：体感温度档应随湿度上升（$dry → $humid）", humid > dry)
        assertEquals(1, dry)
        assertEquals(2, humid)
    }

    @Test
    fun soakedSweatEffectsArePinned() {
        val s = HumiditySolver.sweatOf(4, 35.0, 80.0, 0.5, 2)
        assertEquals(4, s.grade)
        val byKind = s.effects.associateBy { it.kind }
        assertEquals(listOf("skin_sheen", "cloth_cling", "sand_stick", "felt_temp"), s.effects.map { it.kind })
        assertEquals(2, byKind.getValue("skin_sheen").grade)
        assertEquals("油亮", byKind.getValue("skin_sheen").label)
        assertEquals(2, byKind.getValue("cloth_cling").grade)
        assertEquals("湿透贴身", byKind.getValue("cloth_cling").label)
        assertEquals(2, byKind.getValue("sand_stick").grade)
        assertEquals("粘成块", byKind.getValue("sand_stick").label)
        assertEquals(3, byKind.getValue("felt_temp").grade)
        assertEquals("烫", byKind.getValue("felt_temp").label)
    }

    @Test
    fun wetterSandBindsStronger() {
        val sands = (0..2).map { HumiditySolver.sandMoistureOf(it) }
        assertEquals(listOf("干沙", "潮沙", "湿沙"), sands.map { it.label })
        assertEquals(listOf(0.2, 0.5, 0.9), sands.map { it.binding })
        for (i in 1 until sands.size) {
            assertTrue("判据追加⑤：束缚系数应随含水档升高", sands[i].binding > sands[i - 1].binding)
        }
    }

    @Test
    fun sameInputGivesByteIdenticalOutput() {
        val a = HumiditySolver.humidityOf(30.0, 60.0, 2.0)
        val b = HumiditySolver.humidityOf(30.0, 60.0, 2.0)
        assertEquals(a, b)
        assertArrayEquals(dump(a), dump(b))
        assertEquals(HumiditySolver.sweatOf(2, 30.0, 60.0, 1.0, 1), HumiditySolver.sweatOf(2, 30.0, 60.0, 1.0, 1))
    }

    @Test
    fun allResultsCarryAssumptionAndNotMeasurementNote() {
        val h = HumiditySolver.humidityOf(30.0, 60.0, 2.0)
        val s = HumiditySolver.sweatOf(2, 30.0, 60.0, 1.0, 1)
        val sand = HumiditySolver.sandMoistureOf(1)
        for (note in listOf(h.note, s.note, sand.note)) assertEquals("模拟结果，非测量", note)
        for (a in listOf(h.assumption, s.assumption, sand.assumption)) assertTrue(a.isNotBlank())
        assertTrue("assumption 应写明档位近似非生理测量", h.assumption.contains("非生理测量"))
        assertTrue(s.assumption.contains("非生理测量"))
    }

    @Test
    fun outOfDomainInputsAreRejectedOrClamped() {
        assertThrows(IllegalArgumentException::class.java) { HumiditySolver.sweatOf(5, 30.0, 60.0, 1.0, 0) }
        assertThrows(IllegalArgumentException::class.java) { HumiditySolver.sweatOf(0, 30.0, 60.0, 1.0, 3) }
        assertThrows(IllegalArgumentException::class.java) { HumiditySolver.humidityOf(30.0, 60.0, -1.0) }
        assertThrows(IllegalArgumentException::class.java) { HumiditySolver.wetBulbC(61.0, 50.0) }
        assertThrows(IllegalArgumentException::class.java) { HumiditySolver.sandMoistureOf(3) }
        assertEquals("RH0 钳位到 1（0% 无露点定义）", 1.0, HumiditySolver.humidityOf(30.0, 0.0, 1.0).rhPct, 1e-12)
    }
}