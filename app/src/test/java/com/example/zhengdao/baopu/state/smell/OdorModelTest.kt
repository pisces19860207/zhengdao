package com.example.zhengdao.baopu.state.smell

import com.example.zhengdao.baopu.state.environment.EnvironmentSolver
import com.example.zhengdao.baopu.state.fluid.DrynessGrade
import com.example.zhengdao.baopu.state.fluid.FluidKind
import com.example.zhengdao.baopu.state.fluid.VolumeGrade
import com.example.zhengdao.baopu.state.surfaceflow.FlowVolume
import com.example.zhengdao.baopu.state.surfaceflow.SweatZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OdorModelTest {

    private fun hotEnv() = EnvironmentSolver.solve(31.0, 200, 12.0, 0, 0.0, 32.0, 85.0, 3, 0)

    @Test
    fun `存在档_四档逐字对齐`() {
        assertEquals(listOf(0, 1, 2, 3), OdorPresence.entries.map { it.level })
        assertEquals(listOf("无", "隐约", "明显", "很重"), OdorPresence.entries.map { it.label })
    }

    @Test
    fun `来源_十类覆盖体表口腔脚衣物环境`() {
        assertEquals(10, OdorSource.entries.size)
        listOf("体表汗味", "头皮油脂", "口腔", "脚", "衣物残留·油烟", "环境·香水").forEach {
            assertTrue(it, OdorSource.entries.any { source -> source.label == it })
        }
    }

    @Test
    fun `中性词表_九词与枚举同一真相`() {
        assertEquals(
            listOf("汗", "酸", "油", "烟", "消毒水", "皂香", "皮革", "金属", "潮闷"),
            OdorWord.entries.map { it.label },
        )
        assertEquals(OdorWord.entries.map { it.label }, OdorWords.NEUTRAL)
    }

    @Test
    fun `分区_下体四区加肛周_会阴尚未接入`() {
        assertEquals(
            listOf("冠状沟", "包皮内板", "阴囊", "根部", "肛周"),
            OdorRegion.entries.filter { it.lowerBody }.map { it.label },
        )
        assertFalse(OdorRegion.entries.any { it.label == "会阴" })
    }

    @Test
    fun `时间档_七档每档都有现时解释`() {
        assertEquals(7, OdorTemporal.entries.size)
        OdorTemporal.entries.forEach {
            assertTrue(it.label, it.why.isNotBlank())
            assertFalse(it.label, it.why.contains("有病"))
        }
    }

    @Test
    fun `季节_恰好四档`() {
        assertEquals(
            listOf("夏·闷热", "梅雨·潮", "秋冬·干冷", "室内暖气·空调"),
            OdorSeason.entries.map { it.label },
        )
    }

    @Test
    fun `730引用_只收档序且带来源`() {
        val ref = ThermoTierRef(2, 3, 2)
        assertEquals("730", ref.source)
        assertThrows(IllegalArgumentException::class.java) { ThermoTierRef(4, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { ThermoTierRef(0, 4, 0) }
        assertThrows(IllegalArgumentException::class.java) { ThermoTierRef(0, 0, 4) }
    }

    @Test
    fun `气候依据_657或730至少一个`() {
        assertThrows(IllegalArgumentException::class.java) { ClimateEvidence() }
        val evidence = ClimateEvidence(env657 = hotEnv())
        assertTrue(evidence.env657 != null)
        assertTrue(evidence.thermo730 == null)
    }

    @Test
    fun `883强度_只允许零到三档且来源固定`() {
        val ref = SmellIntensityRef(2)
        assertEquals(2, ref.smellIntensity)
        assertEquals("883", ref.source)
        assertThrows(IllegalArgumentException::class.java) { SmellIntensityRef(4) }
    }

    @Test
    fun `下体来源_各自钉住上游与合法域`() {
        assertEquals("883", SmegmaSource(1).source)
        assertThrows(IllegalArgumentException::class.java) { SmegmaSource(4) }
        assertEquals(
            "780",
            FluidResidueSource(FluidKind.PRE_SEMINAL, VolumeGrade.TRACE, DrynessGrade.WET).source,
        )
        assertThrows(IllegalArgumentException::class.java) {
            FluidResidueSource(FluidKind.SALIVA, VolumeGrade.TRACE, DrynessGrade.WET)
        }
        assertEquals("806/730", SweatSource(SweatZone.GROIN, FlowVolume.TRACE).source)
        assertThrows(IllegalArgumentException::class.java) { SweatSource(SweatZone.SCALP, FlowVolume.TRACE) }
    }

    @Test
    fun `码册_无重复`() {
        val fields = OdorCodes::class.java.declaredFields.filter { it.type == String::class.java }
        fields.forEach { it.isAccessible = true }
        val codes = fields.map { it.get(null) as String }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.size >= 20)
    }
}
