// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百六十六批 · 地形单结构测试：「四件齐」是结构性的，缺一件造不出来。
package com.example.zhengdao.baopu.state.topography

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TopoReadingTest {

    private val f = TopoFixtures

    // ── ① 四件齐：15 维＝凸 5＋凹 5＋厚 5，左右 11 对 ─────────────────

    @Test
    fun `四件齐_十五维十一对四中轴`() {
        val t = f.topo()
        assertEquals(15, t.size)
        assertEquals(11, t.lateral().size)
        assertEquals(4, t.midline().size)
        assertEquals(Topography.EXPECTED_SIZE, t.size)
    }

    @Test
    fun `缺一维造不出来_点名臀厚`() {
        val short = f.readings().filterNot { it.dim == DepthDim.GLUTEAL }
        val e = assertThrows(IllegalArgumentException::class.java) { Topography(short) }
        assertTrue(e.message!!.startsWith(Topography.MISSING_PREFIX))
        assertTrue("要点名缺哪一维", e.message!!.contains("臀厚"))
    }

    @Test
    fun `重复维造不出来_两个真相`() {
        val dup = f.readings() + f.relief(ReliefDim.SCAPULA, ReliefGrade.NONE)
        val e = assertThrows(IllegalArgumentException::class.java) { Topography(dup) }
        assertTrue(e.message!!.startsWith(Topography.DUPLICATE_REJECT))
        assertTrue(e.message!!.contains("肩胛骨"))
    }

    @Test
    fun `空单造不出来`() {
        val e = assertThrows(IllegalArgumentException::class.java) { Topography(emptyList()) }
        assertEquals(Topography.EMPTY_REJECT, e.message)
    }

    @Test
    fun `中轴两侧不同即拒`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ReliefReading(ReliefDim.RECTUS_GROOVE, ReliefGrade.CLEAR, ReliefGrade.FAINT)
        }
        assertTrue(e.message!!.contains("中轴维只记一笔"))
        assertTrue(e.message!!.contains("清楚"))
    }

    @Test
    fun `中轴给不对称原因即拒`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            DepthReading(DepthDim.CHEST, DepthGrade.THICK, DepthGrade.THICK, AsymCause.HANDEDNESS)
        }
        assertTrue(e.message!!.contains("中轴维没有左右"))
    }

    @Test
    fun `分左右维允许给原因`() {
        val r = f.hollow(HollowDim.AXILLA, HollowGrade.SHALLOW, HollowGrade.DEEP, AsymCause.OLD_INJURY)
        assertEquals(2, r.gap)
        assertEquals(AsymCause.OLD_INJURY, r.cause)
    }

    // ── 判据④前置：厚度备注不许说形状 ───────────────────────────────

    @Test
    fun `厚度备注借用形状词即拒`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            f.depth(DepthDim.CHEST, note = "厚得像倒三角")
        }
        assertTrue(e.message!!.startsWith(TopoGuard.SHAPE_LEAK_REJECT))
        assertTrue("要点名命中的词", e.message!!.contains("倒三角"))
    }

    @Test
    fun `厚度备注带长度单位即拒`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            f.depth(DepthDim.CHEST, note = "约 7" + "c" + "m")
        }
        assertTrue(e.message!!.startsWith(BodyPartGuard.MEASURE_REJECT))
    }

    @Test
    fun `厚度备注只说起伏不看形状就放行`() {
        val r = f.depth(DepthDim.CHEST, note = "连到腋下才收")
        assertEquals("连到腋下才收", r.note)
    }

    // ── 快照出口 ────────────────────────────────────────────────────

    @Test
    fun `左右差按档算`() {
        assertEquals(1, f.relief(ReliefDim.SCAPULA, ReliefGrade.FAINT, ReliefGrade.CLEAR).gap)
        assertEquals(0, f.relief(ReliefDim.SCAPULA, ReliefGrade.CLEAR, ReliefGrade.CLEAR).gap)
        assertEquals(2, f.relief(ReliefDim.RIBS, ReliefGrade.NONE, ReliefGrade.CLEAR).gap)
    }

    @Test
    fun `出口三行_凸凹厚各一行`() {
        val text = f.topo().line()
        val lines = text.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("凸起｜"))
        assertTrue(lines[1].startsWith("凹陷｜"))
        assertTrue(lines[2].startsWith("厚度｜"))
        assertTrue(lines[0].contains("肩胛骨 左=隐约 右=清楚"))
        assertTrue("中轴只写一笔", lines[0].contains("腹直肌沟=清楚"))
        assertTrue("中轴不写左右", !lines[0].contains("腹直肌沟 左"))
    }

    @Test
    fun `出口点名十五维且无禁词`() {
        val t = f.topo()
        val text = t.line()
        TopoCatalog.allDims().forEach { dim ->
            assertTrue("出口漏了 " + dim.label, text.contains(dim.label))
        }
        assertTrue(BodyPartGuard.measureHit(text) == null)
        assertTrue(BodyPartGuard.diagnosisHits(text).isEmpty())
        assertTrue(TopoGuard.shapeHits(text).isEmpty())
    }

    @Test
    fun `读数顺序按目录表_凸凹厚`() {
        val t = f.readings().reversed().let { Topography(it) }
        assertEquals(TopoCatalog.RELIEF_DIMS, t.all().filterIsInstance<ReliefReading>().map { it.dim })
        assertEquals(TopoCatalog.HOLLOW_DIMS, t.all().filterIsInstance<HollowReading>().map { it.dim })
        assertEquals(TopoCatalog.DEPTH_DIMS, t.all().filterIsInstance<DepthReading>().map { it.dim })
    }
}
