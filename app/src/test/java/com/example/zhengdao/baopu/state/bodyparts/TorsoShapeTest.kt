// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百四十八批 · 形状单自身的测试：五项齐、档序不漂、出口逐字、禁数值、体积复用 805。
package com.example.zhengdao.baopu.state.bodyparts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TorsoShapeTest {

    private val f = TorsoFixtures

    @Test
    fun `判据一_八维齐且五项各有归属`() {
        assertEquals(8, TorsoDimension.entries.size)
        assertEquals(8, TorsoCatalog.GRADES.size)
        assertEquals(TorsoItem.COUNT, TorsoItem.entries.size)
        // 五项分组：①三维 ②两维 ③一维 ④一维 ⑤一维 —— 与派工单一致
        assertEquals(listOf(3, 2, 1, 1, 1), TorsoItem.entries.map { it.dimensions.size })
        val covered = TorsoItem.entries.flatMap { it.dimensions }
        assertEquals("每维只能挂在一个项下", TorsoDimension.entries.size, covered.toSet().size)
        assertEquals("五项必须覆盖八维", TorsoDimension.entries.toSet(), covered.toSet())
    }

    @Test
    fun `判据一_八维全部有档且档数可数`() {
        // 5+4+3+4+3+3+3+3 = 28
        assertEquals(28, TorsoCatalog.totalGrades)
        TorsoDimension.entries.forEach { dim ->
            assertTrue(dim.label + " 没有档", TorsoCatalog.grades(dim).isNotEmpty())
        }
    }

    @Test
    fun `档序必须从零连续_否则按档序取档会取错`() {
        TorsoDimension.entries.forEach { dim ->
            assertEquals(dim.label + " 档序不连续", TorsoCatalog.grades(dim).indices.toList(),
                TorsoCatalog.grades(dim).map { it.level })
        }
        assertEquals(RectusBlocks.EIGHT, RectusBlocks.of(4))
        assertEquals(LineaAlba.COVERED, LineaAlba.of(0))
        assertEquals(Serratus.OBVIOUS, Serratus.of(2))
    }

    @Test
    fun `每档自报的维与目录表一致`() {
        TorsoDimension.entries.forEach { dim ->
            TorsoCatalog.grades(dim).forEach { grade ->
                assertEquals(dim.label + " 的档自报了别的维", dim, grade.dim)
            }
        }
    }

    @Test
    fun `块数是计数不是量度`() {
        assertEquals(2, RectusBlocks.TWO.blocks)
        assertEquals(4, RectusBlocks.FOUR.blocks)
        assertEquals(6, RectusBlocks.SIX.blocks)
        assertEquals(8, RectusBlocks.EIGHT.blocks)
        assertEquals("块档就是 2/4/6/8 四个数", listOf(2, 4, 6, 8),
            RectusBlocks.entries.filter { !it.zero }.map { it.blocks })
        assertEquals("界不清不猜块数", 0, RectusBlocks.INDISTINCT.blocks)
    }

    @Test
    fun `零档是事实不是空值`() {
        assertTrue(RectusBlocks.INDISTINCT.zero)
        assertTrue(LineaAlba.COVERED.zero)
        assertTrue(Serratus.NONE.zero)
        assertTrue(BlockAlignment.EVEN.zero)
        assertFalse(RectusBlocks.TWO.zero)
        assertFalse(LineaAlba.DEEP.zero)
        // 被脂覆盖不是「浅」：两者档序不同，且都只有一档是零档
        assertFalse(LineaAlba.SHALLOW.zero)
        assertEquals(1, RectusBlocks.entries.count { it.zero })
        assertEquals(1, LineaAlba.entries.count { it.zero })
    }

    @Test
    fun `形状出口逐字可回放`() {
        assertEquals(
            "腹直肌块数=两块，白线深浅=浅，腹直肌左右列=等高，胸肌下缘走向=圆钝，" +
                "胸沟宽窄=中，斜方肌坡度=平缓，背阔外扩=直下，前锯肌=无",
            f.sedentaryShape().line(),
        )
    }

    @Test
    fun `档位向量与出口确定性双跑一致`() {
        val shape = f.athleticShape()
        assertEquals(shape.line(), shape.line())
        assertEquals(shape.levels(), shape.levels())
        assertEquals(listOf(3, 3, 0, 1, 1, 2, 0, 1), shape.levels())
    }

    @Test
    fun `出口不出长度不出诊断词`() {
        val text = f.athleticShape().line()
        assertNull("出口不得带长度／百分比", BodyPartGuard.measureHit(text))
        assertTrue("出口不得带诊断词", BodyPartGuard.diagnosisHits(text).isEmpty())
    }

    @Test
    fun `档名里不许出现数字_判据六的机器落点`() {
        TorsoDimension.entries.forEach { dim ->
            TorsoCatalog.grades(dim).forEach { grade ->
                assertFalse(dim.label + "=" + grade.label + " 档名带数字",
                    grade.label.any { it in '0'..'9' })
            }
        }
    }

    @Test
    fun `体积签复用805既有档不新造`() {
        val thick = TorsoVolume(PecVolume.THICK, AbsBlock.DISTINCT, Girth.THICK)
        assertEquals(PecVolume.THICK, thick.pec)
        assertEquals("体积签的腹肌维仍是 805 的分块可见度", AbsBlock.DISTINCT, thick.abs)
        assertTrue(thick.differs(f.volume()))
        assertFalse(f.volume().differs(f.volume()))
    }

    @Test
    fun `体积差逐维点名_只列不同的项`() {
        val a = f.volume(pec = PecVolume.SLIGHT)
        val b = f.volume(pec = PecVolume.THICK)
        val words = a.diffWords(b)
        assertTrue(words.contains("胸肌 微隆→厚实"))
        assertFalse("没差的维不该出现在差异里", words.contains("腹肌"))
        assertEquals("", a.diffWords(a))
    }

    @Test
    fun `形状一律记在放松态_复用805姿态词`() {
        assertEquals(Posture.RELAXED, TORSO_SHAPE_POSTURE)
        assertEquals("放松", TORSO_SHAPE_POSTURE.label)
    }

    @Test
    fun `角色缺标识构造即拒`() {
        val e = assertThrows(IllegalArgumentException::class.java) { f.person(roleId = "  ") }
        assertNotNull(e.message)
        assertTrue(e.message!!.contains("缺标识"))
    }

    @Test
    fun `角色出口含档案体积与可见性`() {
        val text = f.person("甲").line()
        assertTrue(text.startsWith("甲（久坐，躯干可见）"))
        assertTrue(text.contains("体积｜胸肌=微隆"))
        assertTrue(f.person("乙", visible = false).line().contains("躯干不可见"))
    }

    @Test
    fun `档案名逐字对齐815十三型`() {
        assertEquals("健身型", TorsoProfile.ATHLETIC.archive815)
        assertEquals("体力劳动", TorsoProfile.LABOR.archive815)
        assertEquals("脑力劳动久坐", TorsoProfile.SEDENTARY.archive815)
        assertEquals("大腹便便", TorsoProfile.BELLY.archive815)
        assertEquals("老年松垮", TorsoProfile.ELDER.archive815)
        assertEquals(TorsoProfile.COUNT, TorsoProfile.entries.size)
    }
}
