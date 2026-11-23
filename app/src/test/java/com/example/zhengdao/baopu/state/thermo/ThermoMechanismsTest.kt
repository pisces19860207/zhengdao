// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 七条方向表逐条断言：每条给"输入档→输出档方向"，缺一条即红。
package com.example.zhengdao.baopu.state.thermo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermoMechanismsTest {

    private fun input(
        thermal: ThermalBand = ThermalBand.MILD,
        humidity: HumidityBand = HumidityBand.NORMAL,
        wind: WindBand = WindBand.STILL,
        medium: Medium = Medium.NONE,
        trigger: CremasterTrigger = CremasterTrigger.NONE,
        turgor: Direction = Direction.FLAT,
        sweat: Int = 1,
    ) = ThermoInput(thermal, humidity, wind, medium, Posture.STAND, trigger, turgor, sweat)

    private fun eff(id: String, input: ThermoInput) = MechanismTable.byId(id).direction(input)

    @Test
    fun `判据一_七条机制齐全无缺`() {
        assertEquals(MechanismIds.ALL, MechanismTable.ALL.map { it.id })
        assertTrue(ThermoChecks.mechanismsComplete().isEmpty())
    }

    @Test
    fun `M1_提睾反射_冷触碰惊吓都上提加皱缩`() {
        listOf(CremasterTrigger.COLD, CremasterTrigger.TOUCH, CremasterTrigger.STARTLE).forEach { t ->
            val e = eff(MechanismIds.M1_REFLEX, input(trigger = t))
            assertTrue(e.any { it.dim == OutDim.CREMASTERIC && it.dir == Direction.UP })
            assertTrue(e.any { it.dim == OutDim.RUGOSITY && it.dir == Direction.UP })
        }
        assertTrue(eff(MechanismIds.M1_REFLEX, input()).isEmpty())
    }

    @Test
    fun `M2_冷_体积降垂度降皱缩升`() {
        val e = eff(MechanismIds.M2_COLD, input(thermal = ThermalBand.COOL))
        assertTrue(e.any { it.dim == OutDim.VOLUME && it.dir == Direction.DOWN })
        assertTrue(e.any { it.dim == OutDim.HANG && it.dir == Direction.DOWN })
        assertTrue(e.any { it.dim == OutDim.RUGOSITY && it.dir == Direction.UP })
    }

    @Test
    fun `M3_热_体积升垂度升皱缩降湿膜升`() {
        val e = eff(MechanismIds.M3_HEAT, input(thermal = ThermalBand.HOT))
        assertTrue(e.any { it.dim == OutDim.VOLUME && it.dir == Direction.UP })
        assertTrue(e.any { it.dim == OutDim.HANG && it.dir == Direction.UP })
        assertTrue(e.any { it.dim == OutDim.RUGOSITY && it.dir == Direction.DOWN })
        assertTrue(e.any { it.dim == OutDim.WET_FILM && it.dir == Direction.UP })
    }

    @Test
    fun `M4_湿_湿膜升贴合升`() {
        val e = eff(MechanismIds.M4_HUMID, input(humidity = HumidityBand.HUMID))
        assertTrue(e.any { it.dim == OutDim.WET_FILM && it.dir == Direction.UP })
        assertTrue(e.any { it.dim == OutDim.CLING && it.dir == Direction.UP })
        assertTrue(eff(MechanismIds.M4_HUMID, input(humidity = HumidityBand.DRY)).isEmpty())
    }

    @Test
    fun `M5_干风_湿膜降皱缩升`() {
        listOf(
            input(humidity = HumidityBand.DRY),
            input(wind = WindBand.GUSTY),
        ).forEach { i ->
            val e = eff(MechanismIds.M5_DRY_WIND, i)
            assertTrue(e.any { it.dim == OutDim.WET_FILM && it.dir == Direction.DOWN })
            assertTrue(e.any { it.dim == OutDim.RUGOSITY && it.dir == Direction.UP })
        }
    }

    @Test
    fun `M6_介质_水沙湿衣各有方向`() {
        val water = eff(MechanismIds.M6_MEDIUM, input(medium = Medium.WATER))
        assertTrue(water.any { it.dim == OutDim.HANG && it.dir == Direction.DOWN })
        val sand = eff(MechanismIds.M6_MEDIUM, input(medium = Medium.SAND))
        assertTrue(sand.any { it.dim == OutDim.RUGOSITY && it.dir == Direction.UP })
        val cloth = eff(MechanismIds.M6_MEDIUM, input(medium = Medium.WET_CLOTH))
        assertTrue(cloth.any { it.dim == OutDim.CLING && it.dir == Direction.UP })
    }

    @Test
    fun `M7_turgor_方向只引用不自定`() {
        val up = eff(MechanismIds.M7_TURGOR, input(turgor = Direction.UP))
        assertTrue(up.all { it.dir == Direction.UP })
        val down = eff(MechanismIds.M7_TURGOR, input(turgor = Direction.DOWN))
        assertTrue(down.all { it.dir == Direction.DOWN })
        assertTrue(eff(MechanismIds.M7_TURGOR, input(turgor = Direction.FLAT)).isEmpty())
    }

    @Test
    fun `分段单调_湿度越高贴合不降`() {
        val grades = HumidityBand.entries.map { h -> ThermoEval.eval(input(humidity = h)).cling }
        for (i in 1 until grades.size) assertTrue(grades[i] >= grades[i - 1])
    }
}
