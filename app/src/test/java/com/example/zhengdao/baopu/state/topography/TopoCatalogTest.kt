// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百六十六批 · 目录表测试：三族各五维、档序连续、左右维数、与 848／805 不撞名。
package com.example.zhengdao.baopu.state.topography

import com.example.zhengdao.baopu.state.bodyparts.BodyPart
import com.example.zhengdao.baopu.state.bodyparts.BodyPartCatalog
import com.example.zhengdao.baopu.state.bodyparts.Dim
import com.example.zhengdao.baopu.state.bodyparts.FatBand
import com.example.zhengdao.baopu.state.bodyparts.TorsoCatalog
import com.example.zhengdao.baopu.state.bodyparts.TorsoDimension
import com.example.zhengdao.baopu.state.bodyparts.TorsoItem
import com.example.zhengdao.baopu.state.bodyparts.TorsoProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopoCatalogTest {

    @Test
    fun `三族各五维_十五维`() {
        assertEquals(3, TopoFamily.COUNT)
        assertEquals(5, TopoCatalog.RELIEF_DIMS.size)
        assertEquals(5, TopoCatalog.HOLLOW_DIMS.size)
        assertEquals(5, TopoCatalog.DEPTH_DIMS.size)
        assertEquals(15, TopoCatalog.allDims().size)
        assertEquals(15, TopoCatalog.allDims().map { it.label }.toSet().size)
    }

    @Test
    fun `档数四四三_合计五十五`() {
        assertEquals(4, TopoCatalog.grades(TopoFamily.RELIEF).size)
        assertEquals(4, TopoCatalog.grades(TopoFamily.HOLLOW).size)
        assertEquals(3, TopoCatalog.grades(TopoFamily.DEPTH).size)
        assertEquals(3, TopoCatalog.orderMax(TopoFamily.RELIEF))
        assertEquals(3, TopoCatalog.orderMax(TopoFamily.HOLLOW))
        assertEquals(2, TopoCatalog.orderMax(TopoFamily.DEPTH))
        assertEquals(55, TopoCatalog.totalChoices)
    }

    @Test
    fun `每族档序零到n连续_档名不重`() {
        TopoFamily.entries.forEach { family ->
            val grades = TopoCatalog.grades(family)
            assertEquals(family.label, grades.indices.toList(), grades.map { it.level })
            assertEquals(family.label, grades.size, grades.map { it.label }.toSet().size)
        }
    }

    @Test
    fun `分左右十一维_中轴四维点名`() {
        assertEquals(TopoCatalog.LATERAL_COUNT, TopoCatalog.lateralDims().size)
        assertEquals(TopoCatalog.MIDLINE_COUNT, TopoCatalog.allDims().size - TopoCatalog.lateralDims().size)
        assertEquals(
            listOf("腹直肌沟", "胸厚", "腹厚", "臀厚"),
            TopoCatalog.allDims().filterNot { it.lateral }.map { it.label },
        )
    }

    @Test
    fun `骨性隆起三点_沟与坡不在内`() {
        assertEquals(
            listOf(ReliefDim.SCAPULA, ReliefDim.RIBS, ReliefDim.ILIAC_CREST),
            TopoCatalog.bonyDims(),
        )
        assertTrue("腹直肌沟不是隆起", !ReliefDim.RECTUS_GROOVE.bony)
        assertTrue("斜方肌坡不是点状隆起", !ReliefDim.TRAP_SLOPE.bony)
    }

    @Test
    fun `维名与848形状维项档案零重名`() {
        val mine = TopoCatalog.allDims().map { it.label }.toSet()
        val torso = TorsoDimension.entries.map { it.label }.toSet()
        val items = TorsoItem.entries.map { it.label }.toSet()
        val profiles = TorsoProfile.entries.map { it.label }.toSet()
        assertEquals(emptySet<String>(), mine.intersect(torso))
        assertEquals(emptySet<String>(), mine.intersect(items))
        assertEquals(emptySet<String>(), mine.intersect(profiles))
    }

    @Test
    fun `与848档名也不撞_厚度档独立`() {
        val torsoWords = TorsoDimension.entries.flatMap { TorsoCatalog.words(it) }.toSet()
        assertTrue("薄／厚 不该与形状档重名", torsoWords.intersect(setOf("薄", "厚")).isEmpty())
        assertEquals(listOf("薄", "中", "厚"), TopoCatalog.words(TopoFamily.DEPTH))
    }

    @Test
    fun `805计数一条没变`() {
        // 866 是纯增量：805 的八部位／二十三维／七十二读数、四档体脂原样
        assertEquals(8, BodyPart.entries.size)
        assertEquals(23, Dim.entries.size)
        assertEquals(72, BodyPartCatalog.totalReadings)
        assertEquals(4, FatBand.COUNT)
    }

    @Test
    fun `族归属对_凸凹厚各归其族`() {
        TopoCatalog.RELIEF_DIMS.forEach { assertEquals(TopoFamily.RELIEF, it.family) }
        TopoCatalog.HOLLOW_DIMS.forEach { assertEquals(TopoFamily.HOLLOW, it.family) }
        TopoCatalog.DEPTH_DIMS.forEach { assertEquals(TopoFamily.DEPTH, it.family) }
    }
}
