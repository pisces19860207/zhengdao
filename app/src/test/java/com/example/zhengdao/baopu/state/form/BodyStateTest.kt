// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · BodyState 与定标入口核验：五件齐全・3–5 个实测・其余标推算・换底演练。
package com.example.zhengdao.baopu.state.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyStateTest {

    private fun rejects(block: () -> Unit) =
        assertThrows(IllegalArgumentException::class.java) { block() }

    private fun readings(vararg keys: String) = keys.associateWith { FormFixtures.override(170.0) }

    @Test
    fun `五件齐全_输入骨架生殖器高度场代理体`() {
        val s = FormFixtures.state()
        assertEquals(170.0, s.spec.heightCm, 1e-12)
        assertEquals(10, s.frame.segments.size)
        assertEquals(15, s.genital.zones.size)
        assertTrue(s.heightField.grid.isNotEmpty())
        assertEquals(32, s.proxies.all.size)
        assertEquals(3, s.flex.grade)
        assertEquals(Origin.DERIVED, s.flex.origin)
        assertEquals(17, s.jointLimits.size)
        assertTrue(s.ok)
        assertTrue(s.ratiosInPopulationRange)
        assertEquals("模拟非测量", s.note)
        assertTrue(s.assumption.isNotBlank())
    }

    @Test
    fun `定标须填三到五个_两个或六个即拒`() {
        val base = FormFixtures.spec()
        rejects { Calibration.apply(readings("height_cm", "weight_kg"), base, FormFixtures.props) }
        rejects {
            Calibration.apply(
                readings("height_cm", "weight_kg", "body_fat_pct", "thigh", "waist.width", "head_body_ratio"),
                base, FormFixtures.props,
            )
        }
        for (n in 3..5) {
            val keys = listOf("height_cm", "weight_kg", "body_fat_pct", "thigh", "waist.width").take(n)
            val out = Calibration.apply(keys.associateWith { FormFixtures.override(170.0) }, base, FormFixtures.props)
            assertEquals(170.0, out.heightCm, 1e-12)
        }
    }

    @Test
    fun `定标键悬空即拒`() {
        val base = FormFixtures.spec()
        for (k in listOf("nowhere", "waist", "chest_upper.breadth", "girth_mm")) {
            rejects { Calibration.apply(readings("height_cm", "weight_kg", k), base, FormFixtures.props) }
        }
    }

    @Test
    fun `定标三个实测_其余按比例推并标推算`() {
        val base = FormFixtures.spec()
        val spec = Calibration.apply(
            mapOf(
                "height_cm" to FormFixtures.override(175.0),
                "thigh" to FormFixtures.override(43.0),
                "waist.width" to FormFixtures.override(26.0),
            ),
            base, FormFixtures.props,
        )
        val s = FormFixtures.state(spec)
        assertEquals(175.0, s.frame.layout.topY, 1e-9)
        assertEquals(Origin.MEASURED, s.frame.lengths.getValue("thigh").origin)
        assertEquals(Origin.MEASURED, s.frame.sections.first { it.id == "waist" }.widthCm.origin)
        assertEquals(Origin.DERIVED, s.frame.lengths.getValue("calf").origin)
        assertEquals(Origin.DERIVED, s.frame.ratios.shoulder.origin)
        assertTrue("实测项须入账", s.calibration.measured.containsAll(listOf("thigh", "waist.width")))
        assertTrue("推算项须入账", s.calibration.derived.contains("calf"))
        assertTrue(s.calibration.derivedCount > s.calibration.measuredCount)
        assertEquals("整体三项是输入不是带出身的读数，故入账两条", 2, s.calibration.measuredCount)
    }

    @Test
    fun `定标三条比例覆盖_三条都入账标实测`() {
        val spec = Calibration.apply(
            mapOf(
                "shoulder_biacromial" to FormFixtures.override(0.23),
                "arm_span" to FormFixtures.override(1.02),
                "head_body_ratio" to FormFixtures.override(7.6),
            ),
            FormFixtures.spec(), FormFixtures.props,
        )
        val s = FormFixtures.state(spec)
        assertEquals(3, s.calibration.measuredCount)
        assertTrue(s.calibration.measured.containsAll(listOf("shoulder_biacromial", "arm_span", "head_body_ratio")))
        assertTrue(s.ratiosInPopulationRange)
        assertEquals(170.0, s.frame.layout.topY, 1e-9)
        for (r in listOf(s.frame.ratios.shoulder, s.frame.ratios.armSpan, s.frame.ratios.headBody)) {
            assertEquals(Origin.MEASURED, r.origin)
        }
    }

    @Test
    fun `定标账覆盖生殖器尺寸_覆盖后标实测`() {
        val spec = FormFixtures.spec(
            genital = GenitalInput(
                lengthMm = FormFixtures.override(150.0),
                girthMm = FormFixtures.override(120.0),
            ),
        )
        val s = FormFixtures.state(spec)
        assertTrue(s.calibration.measured.containsAll(listOf("genital.length", "genital.girth")))
        assertTrue(s.calibration.derived.contains("genital.flaccid"))
        assertEquals(Origin.MEASURED, s.genital.lengthErectMm.origin)
        assertEquals("单测夹具实测", s.genital.lengthErectMm.source)
    }

    @Test
    fun `定标覆盖值把身高挤爆即拒_不给兜底`() {
        val spec = Calibration.apply(
            mapOf(
                "height_cm" to FormFixtures.override(170.0),
                "weight_kg" to FormFixtures.override(65.0),
                "thigh" to FormFixtures.override(150.0),
            ),
            FormFixtures.spec(), FormFixtures.props,
        )
        rejects { FormFixtures.state(spec) }
    }

    @Test
    fun `皮肤读数_分区体毛梯度取表且带来源`() {
        val s = FormFixtures.state()
        assertEquals(13, s.skin.hairByZone.size)
        assertEquals(1.0, s.skin.hairByZone.getValue("mons"), 1e-12)
        assertEquals(0.5, s.skin.hairByZone.getValue("radix"), 1e-12)
        assertEquals(0.4, s.skin.hairByZone.getValue("scrotum"), 1e-12)
        assertEquals(0.0, s.skin.hairByZone.getValue("shaft"), 1e-12)
        assertEquals(6, s.skin.hexDigits)
        assertEquals(3.0, s.skin.elasticity.value, 1e-12)
        assertEquals(2.0, s.skin.hair.value, 1e-12)
        assertTrue(s.skin.gradientSource.isNotBlank())
        assertEquals("2026-11-16", s.skin.gradientAsOf)
    }

    @Test
    fun `皮肤档与色值位数超域即拒`() {
        rejects { FormFixtures.state(FormFixtures.spec().copy(skin = SkinSpec(elasticityGrade = 9, zoneColors = FormFixtures.colors()))) }
        rejects { FormFixtures.state(FormFixtures.spec().copy(skin = SkinSpec(hairGrade = -1, zoneColors = FormFixtures.colors()))) }
        val short = FormFixtures.colors() + ("shaft" to ZoneColor("#abc", FormFixtures.COLOR_SOURCE, FormFixtures.AS_OF))
        rejects { FormFixtures.state(FormFixtures.spec().copy(skin = SkinSpec(zoneColors = short))) }
    }

    @Test
    fun `覆盖键悬空即拒_四类键都查`() {
        val base = FormFixtures.spec()
        rejects { FormFixtures.state(base.copy(segments = mapOf("tail" to SegmentOverride(lengthCm = FormFixtures.override(10.0))))) }
        rejects { FormFixtures.state(base.copy(sections = mapOf("knee" to SectionOverride(widthCm = FormFixtures.override(10.0))))) }
        rejects {
            FormFixtures.state(base.copy(genital = GenitalInput(zoneRadiusMm = mapOf("elbow" to FormFixtures.override(5.0)))))
        }
        rejects {
            FormFixtures.state(base.copy(skin = SkinSpec(zoneColors = FormFixtures.colors() + ("elbow" to ZoneColor("#abcdef", "x", "2026-11-18")))))
        }
    }

    @Test
    fun `缺任一区色值即拒_防编色`() {
        for (zone in FormFixtures.zones.zones.map { it.zoneId }) {
            val colors = FormFixtures.colors() - zone
            rejects { FormFixtures.state(FormFixtures.spec().copy(skin = SkinSpec(zoneColors = colors))) }
        }
    }

    @Test
    fun `load与of同果`() {
        val spec = FormFixtures.spec()
        val a = BodyModel.load(spec, FormFixtures.repoRoot())
        val b = FormFixtures.state(spec)
        assertEquals(a.frame.segments, b.frame.segments)
        assertEquals(a.genital.zones, b.genital.zones)
        assertEquals(a.proxies, b.proxies)
        assertEquals(a.skin, b.skin)
        assertEquals(a.flex, b.flex)
        assertEquals(a.jointLimits, b.jointLimits)
        assertEquals(a.calibration, b.calibration)
        assertEquals(a.heightField.grid, b.heightField.grid)
    }

    @Test
    fun `换底演练_假FactSource下关系型判据仍全绿`() {
        val fake = FormFixtures.fakeFacts()
        val s = FormFixtures.state(facts = fake)
        assertTrue("换底后硬误写仍须全绿", s.ok)
        assertTrue("换底后三比例仍落区间", s.ratiosInPopulationRange)
        assertEquals(17, s.proxies.segments.size)
        assertEquals(15, s.proxies.genital.size)
        assertEquals(13, s.genital.zones.map { it.zoneId }.toSet().size)
        assertEquals(6, s.genital.diameterProfile.size)
        assertTrue(s.advisories.isEmpty())

        val a = s.genital.zones.first { it.zoneId == "radix" }.radiusMm.mid()
        val b = s.genital.zones.first { it.zoneId == "shaft" }.radiusMm.mid()
        val c = s.genital.zones.first { it.zoneId == "glans" }.radiusMm.mid()
        assertNotEquals(a, b, 1e-9)
        assertNotEquals(b, c, 1e-9)

        assertTrue(s.genital.diameterErectMm.value <= fake.defaultCapMm)
        assertTrue(s.genital.lengthErectMm.value < fake.rareLength.valueMm)
        assertEquals(FormFixtures.FAKE_SOURCE, s.genital.lengthErectMm.source)
        assertEquals(FormFixtures.FAKE_SOURCE, s.genital.girthErectMm.source)

        val left = s.genital.zones.first { it.zoneId == "testis" && it.sideLabel == "left" }
        val right = s.genital.zones.first { it.zoneId == "testis" && it.sideLabel == "right" }
        assertTrue(left.centerCm.y < right.centerCm.y)
    }

    @Test
    fun `换底演练_换底真的换了数_不是空操作`() {
        val real = FormFixtures.state()
        val fake = FormFixtures.state(facts = FormFixtures.fakeFacts(1.1))
        assertNotEquals(real.genital.lengthErectMm.value, fake.genital.lengthErectMm.value, 1e-9)
        assertNotEquals(real.genital.diameterErectMm.value, fake.genital.diameterErectMm.value, 1e-9)
        assertEquals(real.frame.segments, fake.frame.segments)
        assertEquals(real.heightField.grid, fake.heightField.grid)
    }

    @Test
    fun `换底演练_存在域上界跟着FactSource走`() {
        val fake = FormFixtures.fakeFacts(1.1)
        val spec = FormFixtures.spec(genital = GenitalInput(lengthMm = FormFixtures.override(190.0)))
        assertEquals("假底下 190mm 在存在域内", 190.0, FormFixtures.state(spec, fake).genital.lengthErectMm.value, 1e-12)
        rejects { FormFixtures.state(spec) }
    }

    @Test
    fun `换底演练_充血与温度联动仍单调`() {
        val fake = FormFixtures.fakeFacts()
        fun g(turgor: Int, temp: Double) = FormFixtures.state(
            FormFixtures.spec(genital = GenitalInput(turgorGrade = turgor, skinTempC = temp)), fake,
        ).genital
        val mids = (0..4).map { g(it, 34.0).zones.first { z -> z.zoneId == "shaft" }.radiusMm.mid() }
        for (i in 1..4) assertTrue(mids[i] > mids[i - 1])
        assertTrue(g(0, 36.0).scrotum.hangMm > g(0, 30.0).scrotum.hangMm)
        assertTrue(g(4, 34.0).scrotum.hangMm < g(0, 34.0).scrotum.hangMm)
        assertTrue(g(4, 34.0).bulgeCm > g(0, 34.0).bulgeCm)
    }

    @Test
    fun `换底演练_体脂判据与事实底无关仍成立`() {
        val fake = FormFixtures.fakeFacts()
        val readings = listOf(15.0, 25.0, 35.0).map { fat ->
            val s = FormFixtures.state(FormFixtures.spec(bodyFatPct = fat), fake)
            s.heightField.reliefNear(s.frame.sections.first { it.id == "waist" }.yCm)
        }
        for (i in 1 until readings.size) {
            assertTrue(readings[i].mid() < readings[i - 1].mid())
            assertTrue(readings[i].width > readings[i - 1].width)
        }
    }

    @Test
    fun `偏离留痕只报不改写_不拉低ok`() {
        val spec = FormFixtures.spec().copy(shoulderRatio = FormFixtures.override(0.30))
        val s = FormFixtures.state(spec)
        assertFalse(s.ratiosInPopulationRange)
        assertTrue(s.deviations.isNotEmpty())
        assertTrue("留痕不是硬误写，ok 仍真", s.ok)
        assertEquals(0.30, s.frame.ratios.shoulder.value, 1e-12)
    }

    @Test
    fun `同输入两次逐字节一致`() {
        val spec = FormFixtures.spec(genital = GenitalInput(turgorGrade = 2, curvature = 0.3))
        val a = FormFixtures.state(spec)
        val b = FormFixtures.state(spec)
        assertEquals(a.proxies, b.proxies)
        assertEquals(a.skin, b.skin)
        assertEquals(a.flex, b.flex)
        assertEquals(a.jointLimits, b.jointLimits)
        assertEquals(a.calibration, b.calibration)
        assertEquals(a.vocabErrors, b.vocabErrors)
        assertEquals(a.advisories, b.advisories)
        assertEquals(a.frame.segments, b.frame.segments)
        assertEquals(a.genital.zones, b.genital.zones)
        assertEquals(a.heightField.grid, b.heightField.grid)
        assertEquals(a.heightField.contours, b.heightField.contours)
        assertEquals(a.heightField.silhouette, b.heightField.silhouette)
    }
}