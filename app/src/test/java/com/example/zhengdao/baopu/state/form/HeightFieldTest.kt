// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 体表高度场核验：体脂升⇒隆起降且区间变宽・部件档・网格步长・等值线・666 三位置。
package com.example.zhengdao.baopu.state.form

import com.example.zhengdao.baopu.body.SilhouettePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeightFieldTest {

    private val props = FormFixtures.props

    private fun field(
        fat: Double = 18.0,
        parts: PartsSpec = PartsSpec(),
        genital: GenitalInput = GenitalInput(),
    ): BodyHeightField {
        val spec = FormFixtures.spec(170.0, 65.0, fat, genital, parts)
        val frame = BodyFrame(spec, props)
        val g = GenitalModel(genital, spec, frame, FactsFromMd, FormFixtures.zones, FormFixtures.anatomy)
        return BodyHeightField(frame, props, parts, g)
    }

    private fun sectionY(f: BodyHeightField, id: String) = f.frame.sections.first { it.id == id }.yCm

    @Test
    fun `判据_体脂升则腹与胸隆起中值降且区间变宽`() {
        val fats = listOf(15.0, 20.0, 25.0, 30.0, 35.0, 40.0)
        val ref = field()
        for (sec in listOf("waist", "chest_upper")) {
            val y = sectionY(ref, sec)
            val readings = fats.map { field(fat = it).reliefNear(y) }
            for (i in 1 until readings.size) {
                val a = readings[i - 1]
                val b = readings[i]
                assertTrue("$sec 体脂 ${fats[i]} 隆起中值须降：${a.mid()} → ${b.mid()}", b.mid() < a.mid())
                assertTrue("$sec 体脂 ${fats[i]} 区间须变宽：${a.width} → ${b.width}", b.width > a.width)
            }
        }
    }

    @Test
    fun `胸肌档升则胸隆起升_腹深刻度升则腹隆起升`() {
        val low = field(parts = PartsSpec(pecMass = 0.2))
        val high = field(parts = PartsSpec(pecMass = 0.9))
        val y = sectionY(low, "chest_upper")
        assertTrue(high.reliefNear(y).mid() > low.reliefNear(y).mid())
        val shallow = field(parts = PartsSpec(absDepth = 0.1))
        val deep = field(parts = PartsSpec(absDepth = 0.9))
        val wy = sectionY(shallow, "waist")
        assertTrue(deep.reliefNear(wy).mid() > shallow.reliefNear(wy).mid())
    }

    @Test
    fun `腹肌块数决定隆起个数_左右成对`() {
        assertEquals(2 + 4 + 2, field(parts = PartsSpec(absBlocks = 4)).bumps.size)
        assertEquals(2 + 8 + 2, field(parts = PartsSpec(absBlocks = 8)).bumps.size)
        val f = field()
        assertEquals(6, f.resolved.absBlocks)
        assertEquals(2, f.bumps.count { it.id.startsWith("pec.") })
        assertEquals(2, f.bumps.count { it.id.startsWith("glute.") })
        assertEquals(6, f.bumps.count { it.id.startsWith("abs.") })
    }

    @Test
    fun `部件档超域即拒_由输入域守卫拦下`() {
        for (p in listOf(PartsSpec(absBlocks = 10), PartsSpec(pecMass = 1.5), PartsSpec(gluteMass = -0.1))) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                FormFixtures.state(FormFixtures.spec(parts = p))
            }
        }
    }

    @Test
    fun `网格步长取口径表值_覆盖躯干全宽全高`() {
        val f = field()
        val step = props.constant("height_field_grid_cm").default
        assertEquals(step, f.xs[1] - f.xs[0], 1e-12)
        assertEquals(step, f.ys[1] - f.ys[0], 1e-12)
        assertEquals(f.frame.layout.hipY, f.ys.first(), 1e-12)
        assertTrue(f.ys.last() <= f.frame.layout.neckBaseY + 1e-9)
        val half = f.frame.sections.maxOf { it.widthCm.value } / 2.0
        assertEquals(-half, f.xs.first(), 1e-12)
        assertTrue(f.xs.last() <= half + 1e-9)
        assertEquals(f.ys.size * f.xs.size, f.samples.size)
        assertEquals(f.ys.size, f.grid.size)
    }

    @Test
    fun `等值线条数取表值_每条都有线段_阈值递增`() {
        val f = field()
        assertEquals(props.constant("contour_levels").default.toInt(), f.contours.size)
        for (c in f.contours) assertTrue("等值线 ${c.levelCm} 无线段", c.segments.isNotEmpty())
        for (i in 1 until f.contours.size) assertTrue(f.contours[i].levelCm > f.contours[i - 1].levelCm)
        for (c in f.contours) for (s in c.segments) assertTrue("线段两端须不同", s.a != s.b)
    }

    @Test
    fun `后侧隆起不进前网格`() {
        val f = field()
        assertEquals(2, f.bumps.count { it.side == "back" })
        val glute = f.bumps.first { it.side == "back" }
        assertEquals(0.0, f.reliefAt(glute.centerCm.x, glute.centerCm.y).mid(), 1e-12)
        val pec = f.bumps.first { it.id == "pec.left" }
        assertTrue("前侧隆起中心须有正隆起", f.reliefAt(pec.centerCm.x, pec.centerCm.y).mid() > 0.0)
    }

    @Test
    fun `接666三位置_裆缝就是生殖器凸起高`() {
        val spec = FormFixtures.spec(genital = GenitalInput(turgorGrade = 3))
        val frame = BodyFrame(spec, props)
        val g = GenitalModel(spec.genital, spec, frame, FactsFromMd, FormFixtures.zones, FormFixtures.anatomy)
        val f = BodyHeightField(frame, props, spec.parts, g)
        assertEquals(g.bulgeCm, f.bumpHeightCm(SilhouettePosition.CROTCH), 0.0)
        assertEquals(f.reliefNear(sectionY(f, "waist")).mid(), f.bumpHeightCm(SilhouettePosition.WAIST), 1e-12)
        val s = props.constant("limb_relief_frac")
        val r = frame.radii.getValue("thigh").value
        val thigh = f.bumpHeightCm(SilhouettePosition.THIGH)
        assertTrue("大腿根隆起须落表区间", thigh in s.low * r..s.high * r)
    }

    @Test
    fun `侧影自足底到颅顶_前后表面异号`() {
        val f = field()
        assertEquals(0.0, f.silhouette.first().yCm, 1e-12)
        assertTrue(f.silhouette.last().yCm <= f.frame.layout.topY + 1e-9)
        assertTrue(f.silhouette.size > 100)
        for (p in f.silhouette) {
            assertTrue("半宽须为正", p.halfWidthCm > 0.0)
            assertTrue("前表面须为正", p.frontZCm > 0.0)
            assertTrue("后表面须为负", p.backZCm < 0.0)
        }
        val waist = f.profileAt(sectionY(f, "waist"))
        assertEquals(f.frame.sections.first { it.id == "waist" }.widthCm.value / 2.0, waist.halfWidthCm, 1e-9)
    }

    @Test
    fun `截面覆盖直接改侧影_覆盖值标实测`() {
        val spec = FormFixtures.spec().copy(
            sections = mapOf("waist" to SectionOverride(widthCm = FormFixtures.override(40.0))),
        )
        val frame = BodyFrame(spec, props)
        val g = GenitalModel(spec.genital, spec, frame, FactsFromMd, FormFixtures.zones, FormFixtures.anatomy)
        val f = BodyHeightField(frame, props, spec.parts, g)
        assertEquals(20.0, f.profileAt(sectionY(f, "waist")).halfWidthCm, 1e-9)
        assertEquals(Origin.MEASURED, frame.sections.first { it.id == "waist" }.widthCm.origin)
    }

    @Test
    fun `同输入两次逐字节一致`() {
        val a = field(fat = 22.0, parts = PartsSpec(pecMass = 0.7, absBlocks = 8))
        val b = field(fat = 22.0, parts = PartsSpec(pecMass = 0.7, absBlocks = 8))
        assertEquals(a.bumps, b.bumps)
        assertEquals(a.grid, b.grid)
        assertEquals(a.samples, b.samples)
        assertEquals(a.silhouette, b.silhouette)
        assertEquals(a.contours, b.contours)
        assertEquals(a.resolved, b.resolved)
        assertEquals(a.xs, b.xs)
        assertEquals(a.ys, b.ys)
    }
}