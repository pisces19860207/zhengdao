// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 13 区生殖器核验：半径互异・弯曲单调・充血区间・温度联动・六条硬误写负例・防编色。
package com.example.zhengdao.baopu.state.form

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenitalModelTest {

    private fun model(
        genital: GenitalInput = GenitalInput(),
        colors: Map<String, ZoneColor> = FormFixtures.colors(),
        heightCm: Double = 170.0,
        fat: Double = 18.0,
        facts: FactSource = FactsFromMd,
        table: ZoneTable = FormFixtures.zones,
    ): GenitalModel {
        val spec = FormFixtures.spec(heightCm, 65.0, fat, genital)
            .copy(skin = SkinSpec(zoneColors = colors))
        return GenitalModel(genital, spec, BodyFrame(spec, FormFixtures.props), facts, table, FormFixtures.anatomy)
    }

    private fun withZone(id: String, f: (ZoneSpec) -> ZoneSpec): ZoneTable {
        val z = FormFixtures.zones
        return z.copy(zones = z.zones.map { if (it.zoneId == id) f(it) else it })
    }

    private fun zone(m: GenitalModel, id: String, side: String = "mid") =
        m.zones.first { it.zoneId == id && it.sideLabel == side }

    private fun rejects(block: () -> Unit) =
        assertThrows(IllegalArgumentException::class.java) { block() }

    @Test
    fun `十三区齐全_左右各一的区出两条_共十五个实例`() {
        val m = model()
        assertEquals(13, m.zones.map { it.zoneId }.toSet().size)
        assertEquals(15, m.zones.size)
        assertEquals(m.zones.map { it.zoneId }.toSet(), FormFixtures.anatomy.map { it.zoneId }.toSet())
        assertEquals(2, m.zones.count { it.zoneId == "testis" })
        assertEquals(2, m.zones.count { it.zoneId == "cord" })
        for (z in m.zones) assertTrue("${z.zoneId} 区名为空", z.name.isNotBlank())
    }

    @Test
    fun `分区表与词表不一一对应即拒`() {
        val z = FormFixtures.zones
        rejects { model(table = z.copy(zones = z.zones.drop(1))) }
    }

    @Test
    fun `负例_根部柱身龟头三区半径互异_不得被平均`() {
        val m = model()
        val a = zone(m, "radix").radiusMm.mid()
        val b = zone(m, "shaft").radiusMm.mid()
        val c = zone(m, "glans").radiusMm.mid()
        assertNotEquals("根部与柱身半径被平均了", a, b, 1e-9)
        assertNotEquals("柱身与龟头半径被平均了", b, c, 1e-9)
        assertNotEquals("根部与龟头半径被平均了", a, c, 1e-9)
        assertTrue("龟头应比柱身粗（冠状缘外扩）", c > b)
    }

    @Test
    fun `区内半径覆盖生效且自带来源`() {
        val m = model(GenitalInput(zoneRadiusMm = mapOf("shaft" to FormFixtures.override(20.0))))
        val r = zone(m, "shaft").radiusMm
        assertEquals(20.0, r.low, 1e-12)
        assertEquals(20.0, r.high, 1e-12)
        assertEquals("单测夹具实测", r.source)
        assertEquals(Evidence.VERIFIED, r.evidence)
        assertNotEquals("别的区不许被牵连", 20.0, zone(m, "glans").radiusMm.mid(), 1e-9)
    }

    @Test
    fun `curvature零点半一_轴线末端偏移单调`() {
        val tips = listOf(0.0, 0.5, 1.0).map { c ->
            val m = model(GenitalInput(curvature = c))
            val p = m.axisPoint(m.lengthErectMm.value)
            Triple(hypot(p.x, p.y), p.z, p.y)
        }
        assertEquals(0.0, tips[0].first, 1e-12)
        assertTrue("0→0.5 偏移须增", tips[1].first > tips[0].first)
        assertTrue("0.5→1 偏移须增", tips[2].first > tips[1].first)
        assertTrue("弯起后轴向投影须缩短", tips[2].second < tips[0].second)
        assertTrue("默认向上弯 ⇒ y 为正", tips[2].third > 0.0)
        assertEquals("capsule", model(GenitalInput(curvature = 0.0)).shape)
        assertEquals("curved", model(GenitalInput(curvature = 0.5)).shape)
    }

    @Test
    fun `弯曲方向四向都指向该侧`() {
        for ((dir, sign) in listOf("up" to 1.0, "down" to -1.0)) {
            val m = model(GenitalInput(curvature = 1.0, bendDirection = dir))
            val p = m.axisPoint(m.lengthErectMm.value)
            assertTrue("$dir 的 y 符号错", p.y * sign > 0.0)
            assertEquals(0.0, p.x, 1e-12)
        }
        for ((dir, sign) in listOf("left" to -1.0, "right" to 1.0)) {
            val m = model(GenitalInput(curvature = 1.0, bendDirection = dir))
            val p = m.axisPoint(m.lengthErectMm.value)
            assertTrue("$dir 的 x 符号错", p.x * sign > 0.0)
            assertEquals(0.0, p.y, 1e-12)
        }
        rejects { model(GenitalInput(bendDirection = "sideways")) }
        rejects { model(GenitalInput(curvature = 1.5)) }
    }

    @Test
    fun `硬度档对齐EHS_零到四_超域即拒`() {
        for (g in 0..4) assertEquals(g, model(GenitalInput(turgorGrade = g)).hardnessGrade)
        rejects { model(GenitalInput(turgorGrade = 5)) }
        rejects { model(GenitalInput(turgorGrade = -1)) }
        assertEquals(5, FactsFromMd.hardnessGradeCount)
    }

    @Test
    fun `充血升则柱身半径升且始终落在声明区间内`() {
        val mids = (0..4).map { g ->
            val m = model(GenitalInput(turgorGrade = g))
            val r = zone(m, "shaft").radiusMm
            assertTrue("半径区间倒置", r.low <= r.high)
            assertTrue("中值须落区间内", r.mid() in r.low..r.high)
            r.mid()
        }
        for (i in 1..4) assertTrue("档 $i 半径须比上一档大", mids[i] > mids[i - 1])
        val full = model(GenitalInput(turgorGrade = 4))
        val spec = FormFixtures.zones.zone("shaft")
        val ceiling = full.refRadiusMm * spec.radiusFactor.default * (1.0 + spec.gain.high)
        assertTrue("满档半径不得超区间上界", zone(full, "shaft").radiusMm.high <= ceiling + 1e-9)
    }

    @Test
    fun `褶皱随充血消失_龟头恒比柱身光滑一档_满档仍分得开`() {
        assertEquals("pronounced", model(GenitalInput(turgorGrade = 0)).surfaceGrade)
        assertEquals("fine_wrinkle", model(GenitalInput(turgorGrade = 2)).surfaceGrade)
        assertEquals("smooth", model(GenitalInput(turgorGrade = 4)).surfaceGrade)
        for (g in 0..4) {
            val m = model(GenitalInput(turgorGrade = g))
            assertTrue("档 $g 龟头秩须高于柱身", zone(m, "glans").surfaceRank > zone(m, "shaft").surfaceRank)
        }
    }

    @Test
    fun `温度升则阴囊松垂档与悬垂长都升`() {
        val cold = model(GenitalInput(skinTempC = 30.0))
        val warm = model(GenitalInput(skinTempC = 36.0))
        assertTrue(warm.scrotum.sagGradeEffective > cold.scrotum.sagGradeEffective)
        assertTrue(warm.scrotum.hangMm > cold.scrotum.hangMm)
        assertEquals(36.0, warm.scrotum.tempC, 1e-12)
        assertEquals("输入档不许被改写", warm.scrotum.sagGradeInput, cold.scrotum.sagGradeInput)
    }

    @Test
    fun `充血升则阴囊收紧_悬垂长缩短`() {
        val soft = model(GenitalInput(turgorGrade = 0))
        val hard = model(GenitalInput(turgorGrade = 4))
        assertTrue(hard.scrotum.sagGradeEffective < soft.scrotum.sagGradeEffective)
        assertTrue(hard.scrotum.hangMm < soft.scrotum.hangMm)
    }

    @Test
    fun `阴囊容积档升则半径升`() {
        val small = model(GenitalInput(scrotumVolumeGrade = 1))
        val big = model(GenitalInput(scrotumVolumeGrade = 5))
        assertTrue(big.scrotum.radiusMm.mid() > small.scrotum.radiusMm.mid())
        rejects { model(GenitalInput(scrotumVolumeGrade = 6)) }
        rejects { model(GenitalInput(scrotumSagGrade = 0)) }
    }

    @Test
    fun `左侧睾丸常略低_单侧差异使左右半径不同`() {
        val m = model()
        val l = zone(m, "testis", "left")
        val r = zone(m, "testis", "right")
        assertTrue("左睾须更低", l.centerCm.y < r.centerCm.y)
        assertTrue("左睾须偏左", l.centerCm.x < 0.0)
        assertTrue("右睾须偏右", r.centerCm.x > 0.0)
        assertTrue("单侧差异须让左右半径不同", l.radiusMm.mid() > r.radiusMm.mid())
        val sym = model(GenitalInput(testisAsymmetryMm = 0.0))
        assertEquals(zone(sym, "testis", "left").radiusMm.mid(), zone(sym, "testis", "right").radiusMm.mid(), 1e-12)
        rejects { model(GenitalInput(testisAsymmetryMm = -1.0)) }
    }

    @Test
    fun `血管可见度是个体常量_不随充血漂移`() {
        for (g in 0..4) assertEquals("low", model(GenitalInput(turgorGrade = g)).vesselVisibility)
        assertEquals("high", model(GenitalInput(vesselVisibility = "high")).vesselVisibility)
        rejects { model(GenitalInput(vesselVisibility = "bulging")) }
    }

    @Test
    fun `默认抽样不超直径上限_也不拿罕见长度当默认`() {
        val m = model()
        assertTrue("默认等效直径 ${m.diameterErectMm.value} 超上限", m.diameterErectMm.value <= FactsFromMd.defaultCapMm)
        assertTrue("默认长度不得等于罕见长度", m.lengthErectMm.value < FactsFromMd.rareLength.valueMm)
        assertEquals(Origin.DERIVED, m.lengthErectMm.origin)
        assertEquals("事实底盘 md §四", m.lengthErectMm.source)
        assertTrue(m.advisories.isEmpty())
        val p99 = FactsFromMd.girthRangePct.at(99)
        assertTrue("99 分位直径仍须在上限内", FactsFromMd.equivalentDiameterMm(p99) <= FactsFromMd.defaultCapMm)
    }

    @Test
    fun `直径提示按档位附带_超上限与罕见长度都要说话`() {
        val wide = model(GenitalInput(girthMm = FormFixtures.override(150.0)))
        assertTrue("4.6–5.0cm 须附极罕见提示", wide.advisories.any { "极罕见" in it })
        val rare = model(GenitalInput(lengthMm = FormFixtures.override(180.0)))
        assertTrue("18cm 须标极少见", rare.advisories.any { "极少见" in it && "1/600" in it })
        assertTrue("18cm 超常见观察域须另附说明", rare.advisories.any { "常见观察域" in it })
        assertEquals(Origin.MEASURED, rare.lengthErectMm.origin)
        assertTrue("18cm 可存在，不许直接拒", rare.ok)
    }

    @Test
    fun `尺寸超存在域即拒_缺来源即拒`() {
        rejects { model(GenitalInput(lengthMm = FormFixtures.override(190.0))) }
        rejects { model(GenitalInput(lengthMm = FormFixtures.override(50.0))) }
        rejects { model(GenitalInput(girthMm = FormFixtures.override(300.0))) }
        rejects { model(GenitalInput(lengthMm = Override(150.0, "", "2026-11-18"))) }
        rejects { model(GenitalInput(flaccidRatio = 0.9)) }
    }

    @Test
    fun `禁止身高推尺寸_身高变了长度周长与柱身半径一字不动`() {
        val a = model(heightCm = 160.0)
        val b = model(heightCm = 195.0)
        assertEquals(a.lengthErectMm.value, b.lengthErectMm.value, 0.0)
        assertEquals(a.girthErectMm.value, b.girthErectMm.value, 0.0)
        assertEquals(a.diameterErectMm.value, b.diameterErectMm.value, 0.0)
        assertEquals(a.shaftRadius().mid(), b.shaftRadius().mid(), 0.0)
        assertEquals(a.lengthFlaccidMm.value, b.lengthFlaccidMm.value, 0.0)
        assertTrue("相关系数只允许弱相关", FactsFromMd.statureCorrelationR.high <= 0.6)
    }

    @Test
    fun `每区颜色必带来源_缺色即拒_缺来源即拒_位数不符即拒`() {
        val noGlans = FormFixtures.colors() - "glans"
        rejects { model(colors = noGlans) }
        rejects { ZoneColor("#a86a5a", "", "2026-11-18") }
        rejects { ZoneColor("#a86a5a", "色板", "") }
        rejects { ZoneColor("a86a5a", "色板", "2026-11-18") }
        rejects { ZoneColor("#a86a5g", "色板", "2026-11-18") }
        val m = model()
        for (z in m.zones) {
            assertTrue("${z.zoneId} 色值缺来源", z.colorSource.isNotBlank())
            assertTrue("${z.zoneId} 色值缺日期", z.colorAsOf.isNotBlank())
        }
    }

    @Test
    fun `六条硬误写_改表即报名且指名区与正确说法`() {
        val cases = listOf(
            Triple("INV-01", withZone("frenulum") { it.copy(side = "dorsal") }, "腹侧"),
            Triple("INV-02", withZone("meatus") { it.copy(form = "round_hole") }, "纵裂"),
            Triple("INV-03", withZone("glans") { it.copy(surface = null) }, "光滑"),
            Triple("INV-04", withZone("scrotum") { it.copy(tempLinked = false) }, "持续变化"),
            Triple("INV-05", withZone("testis") { it.copy(leftLower = false) }, "略低"),
            Triple("INV-06", withZone("corona") { it.copy(form = "bulb") }, "犬科"),
        )
        for ((ruleId, table, keyword) in cases) {
            val m = model(table = table)
            assertFalse("$ruleId 应报 vocab_error", m.ok)
            val e = m.vocabErrors.first { it.ruleId == ruleId }
            assertTrue("$ruleId 正确说法须含 $keyword：${e.correction}", keyword in e.correction)
            assertTrue("$ruleId 须指名区", e.zoneName.isNotBlank())
            assertTrue("$ruleId 须带来源", e.source.isNotBlank())
        }
        assertTrue("原表必须全绿", model().ok)
    }

    @Test
    fun `龟头颜色浅于柱身也报INV03_行为侧有牙`() {
        val colors = FormFixtures.colors() + ("glans" to ZoneColor("#f0d0c0", FormFixtures.COLOR_SOURCE, FormFixtures.AS_OF))
        val m = model(colors = colors)
        assertFalse(m.ok)
        assertTrue(m.vocabErrors.any { it.ruleId == "INV-03" })
    }

    @Test
    fun `紫红防漂锁_勒压且未降温即报_降温后放行`() {
        val stuck = model(GenitalInput(constricted = true, skinTempC = 34.0))
        assertFalse(stuck.ok)
        val e = stuck.vocabErrors.first { it.ruleId == "VG-PURPLE" }
        assertTrue("须写明必须伴温度下降", "温度下降" in e.correction)
        assertTrue(model(GenitalInput(constricted = true, skinTempC = 30.0)).ok)
        assertTrue("不勒压时不许误报", model(GenitalInput(constricted = false)).ok)
    }

    @Test
    fun `直径剖面六点在5到8之间_轴向递增_不取尿道口`() {
        val m = model()
        val p = m.diameterProfile
        assertEquals(6, p.size)
        assertTrue(p.size in 5..8)
        for (i in 1 until p.size) assertTrue("剖面轴向须递增", p[i].axialFrac > p[i - 1].axialFrac)
        assertEquals(0.0, p.first().axialFrac, 1e-12)
        assertEquals(1.0, p.last().axialFrac, 1e-12)
        val meatusR = zone(m, "meatus").radiusMm.mid() * 2.0
        assertTrue("剖面末点不得取尿道口那条缝", p.last().diameterMm.mid() > meatusR)
        for (pt in p) assertTrue("剖面直径须为正区间", pt.diameterMm.low > 0.0 && pt.diameterMm.width >= 0.0)
    }

    @Test
    fun `tapered形状使剖面自根向尖收窄`() {
        val m = model(GenitalInput(shape = "tapered", curvature = 0.0))
        val p = m.diameterProfile
        assertTrue(p.last().diameterMm.mid() < p.first().diameterMm.mid())
        rejects { model(GenitalInput(shape = "banana")) }
    }

    @Test
    fun `裆缝凸起高随充血单调_只产h不乘清晰度`() {
        val hs = (0..4).map { model(GenitalInput(turgorGrade = it)).bulgeCm }
        for (i in 1..4) assertTrue("档 $i 凸起须更高", hs[i] > hs[i - 1])
        val m = model(GenitalInput(turgorGrade = 4))
        val expect = FormFixtures.zones.bulge.diameterFactor *
            (m.shaftRadius().mid() * 2.0 / 10.0) * FormFixtures.zones.bulge.coefficient(1.0)
        assertEquals(expect, m.bulgeCm, 1e-12)
    }

    @Test
    fun `接触参数当前值随充血落在恢复系数区间内`() {
        for (g in 0..4) {
            val c = model(GenitalInput(turgorGrade = g)).contact
            assertTrue("current 须落区间", c.current in c.restitution.low..c.restitution.high)
            assertTrue(c.stiffnessGrade in 1..5)
        }
        assertTrue(model(GenitalInput(turgorGrade = 4)).contact.current >
            model(GenitalInput(turgorGrade = 0)).contact.current)
    }

    @Test
    fun `根部挂点在髋线下方骨盆之前_轴端点随区走`() {
        val spec = FormFixtures.spec()
        val frame = BodyFrame(spec, FormFixtures.props)
        val m = GenitalModel(spec.genital, spec, frame, FactsFromMd, FormFixtures.zones, FormFixtures.anatomy)
        val root = m.rootCm()
        assertTrue("根部须低于髋线", root.y < frame.layout.hipY)
        assertEquals(frame.layout.hipY - FormFixtures.zones.rootY.default * spec.heightCm, root.y, 1e-12)
        assertTrue("根部须在骨盆截面之前", root.z > 0.0)
        val shaft = zone(m, "shaft")
        val (a, b) = m.zoneEnds(shaft)
        assertTrue("柱身轴端须沿轴递进", b.z > a.z)
        val scrotum = zone(m, "scrotum")
        val (sa, sb) = m.zoneEnds(scrotum)
        assertTrue("悬垂区须沿 −Y", sa.y < 0.0 && sb.y < 0.0)
    }

    @Test
    fun `同输入两次逐字节一致`() {
        val a = model(GenitalInput(turgorGrade = 2, curvature = 0.4, skinTempC = 35.0))
        val b = model(GenitalInput(turgorGrade = 2, curvature = 0.4, skinTempC = 35.0))
        assertEquals(a.zones, b.zones)
        assertEquals(a.scrotum, b.scrotum)
        assertEquals(a.diameterProfile, b.diameterProfile)
        assertEquals(a.advisories, b.advisories)
        assertEquals(a.vocabErrors, b.vocabErrors)
        assertEquals(a.bulgeCm, b.bulgeCm, 0.0)
        assertEquals(a.contact, b.contact)
    }
}