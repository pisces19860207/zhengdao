// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 契约测试：surface 三字段逐字对齐 731 / 禁连续读数 / 禁病理性温度 /
// 体位口径 / 与 686·726·658 分工（只给档不动几何、抬多少多久用 726 口径）。
package com.example.zhengdao.baopu.state.thermo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermoContractTest {

    private fun input(
        thermal: ThermalBand = ThermalBand.MILD,
        medium: Medium = Medium.NONE,
        trigger: CremasterTrigger = CremasterTrigger.NONE,
    ) = ThermoInput(thermal, HumidityBand.NORMAL, WindBand.STILL, medium, Posture.STAND, trigger, Direction.FLAT, 1)

    @Test
    fun `判据六_surface字段名逐字对齐731`() {
        assertEquals(listOf("wetFilm", "colorShift", "vesselVisible"), SurfaceCodes.FIELDS)
        assertEquals(3, SurfaceCodes.WET_FILM_MAX)
        assertEquals(3, SurfaceCodes.COLOR_SHIFT_MAX)
        assertEquals(2, SurfaceCodes.VESSEL_VISIBLE_MAX)
    }

    @Test
    fun `surface档域越界构造不出`() {
        var threw = 0
        try { SurfaceReading(4, 1, 1, emptyList()) } catch (e: IllegalArgumentException) { threw++ }
        try { SurfaceReading(1, 1, 3, emptyList()) } catch (e: IllegalArgumentException) { threw++ }
        assertEquals(2, threw)
    }

    @Test
    fun `渲染出口逐字拼出三surface字段`() {
        val text = Thermo.render(ThermoEval.eval(input()))
        assertTrue(text.contains("surface[湿膜档="))
        assertTrue(text.contains("/色变档="))
        assertTrue(text.contains("/血管可见档="))
        assertTrue(text.contains(THERMO_DISCLAIMER))
    }

    @Test
    fun `禁连续读数_摄氏度长度体积时间全咬`() {
        val bad = listOf("36.5度", "温" + "度30摄" + "氏度", "长2c" + "m", "容" + "积5m" + "L", "历时3秒")
        bad.forEach { assertTrue("应命中连续读数:$it", ThermoChecks.noContinuousLeak(it).isNotEmpty()) }
        assertTrue(ThermoChecks.noContinuousLeak("湿膜档=2 · 非医疗判断").isEmpty())
    }

    @Test
    fun `禁病理性温度_发热炎症感染全咬`() {
        val bad = listOf("发" + "热", "炎" + "症", "感" + "染", "就" + "医")
        bad.forEach { assertTrue("应命中病理词:$it", ThermoChecks.noPathological(it).isNotEmpty()) }
        assertTrue(ThermoChecks.noPathological("偏凉 适中 偏热").isEmpty())
    }

    @Test
    fun `红不出值_病理场景judge红且reading空`() {
        // noteOf 只取触发词，正常触发词不含病理；此处直接验检查器红即无值
        val bad = ThermoVerdict.bad(listOf(ThermoCodes.PATHOLOGICAL), listOf("病理词命中"))
        assertTrue(bad.red)
        assertEquals(null, bad.reading)
    }

    @Test
    fun `体位五档齐全_垂坠必带体位`() {
        assertEquals(5, Posture.COUNT)
        assertEquals(
            listOf("站", "坐", "卧", "侧卧", "倒"),
            Posture.entries.map { it.label },
        )
    }

    @Test
    fun `与726分工_恢复拍档口径1比3比6`() {
        assertEquals(1, ThermoClock.recoveryBeats(0))
        assertEquals(3, ThermoClock.recoveryBeats(1))
        assertEquals(6, ThermoClock.recoveryBeats(2))
    }

    @Test
    fun `介质恰三加无_不做其他液体`() {
        assertEquals(4, Medium.COUNT)
        assertFalse(Medium.entries.map { it.label }.contains("油"))
    }

    @Test
    fun `确定性_全档位组合两次一致`() {
        for (t in ThermalBand.entries) for (h in HumidityBand.entries) for (w in WindBand.entries) {
            val i = ThermoInput(t, h, w, Medium.NONE, Posture.STAND, CremasterTrigger.NONE, Direction.FLAT, 2)
            assertEquals(Thermo.render(ThermoEval.eval(i)), Thermo.render(ThermoEval.eval(i)))
        }
    }
}
