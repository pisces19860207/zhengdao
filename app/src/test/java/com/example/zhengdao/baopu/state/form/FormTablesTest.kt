// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 两张口径表核验：fail-closed・确定性・键序无关・口径四件齐・表里不放色值・柔韧档口径。
// 只读 docs/facts 两件，零写盘、零临时目录。
package com.example.zhengdao.baopu.state.form

import com.example.zhengdao.baopu.state.geometry.JointNames
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormTablesTest {

    private val propsText = File(FormFixtures.repoRoot(), FormTables.PROPORTIONS_REL_PATH).readText()
    private val zonesText = File(FormFixtures.repoRoot(), FormTables.ZONES_REL_PATH).readText()

    private fun propsJson() = JSONObject(propsText)

    private fun zonesJson() = JSONObject(zonesText)

    private fun rejects(block: () -> Unit) =
        assertThrows(IllegalArgumentException::class.java) { block() }

    @Test
    fun `条目数_十段四截面十三区六不变式`() {
        val p = FormTables.parseProportions(propsText)
        val z = FormTables.parseZones(zonesText)
        assertEquals(10, p.segments.size)
        assertEquals(4, p.sections.size)
        assertEquals(13, z.zones.size)
        assertEquals(6, z.invariants.size)
    }

    @Test
    fun `load与parse同果`() {
        val root = FormFixtures.repoRoot()
        assertEquals(FormTables.parseProportions(propsText), FormTables.loadProportions(root))
        assertEquals(FormTables.parseZones(zonesText), FormTables.loadZones(root))
    }

    @Test
    fun `解析确定性_两次同果`() {
        assertEquals(FormTables.parseProportions(propsText), FormTables.parseProportions(propsText))
        assertEquals(FormTables.parseZones(zonesText), FormTables.parseZones(zonesText))
    }

    @Test
    fun `键序打乱后仍同果`() {
        val reversed = JSONObject()
        val o = propsJson()
        for (k in o.keys().asSequence().sortedDescending()) reversed.put(k, o.get(k))
        assertEquals(FormTables.parseProportions(propsText), FormTables.parseProportions(reversed.toString()))
    }

    @Test
    fun `schema不符即拒`() {
        rejects { FormTables.parseProportions(propsJson().put("schema", "body-proportions/v2").toString()) }
        rejects { FormTables.parseZones(zonesJson().put("schema", "body-zones/v0").toString()) }
    }

    @Test
    fun `尺寸参数缺source_ref即拒`() {
        val o = zonesJson()
        o.getJSONArray("zones").getJSONObject(1).remove("source_ref")
        rejects { FormTables.parseZones(o.toString()) }
    }

    @Test
    fun `尺寸参数缺length_ref即拒`() {
        val o = zonesJson()
        o.getJSONArray("zones").getJSONObject(1).remove("length_ref")
        rejects { FormTables.parseZones(o.toString()) }
    }

    @Test
    fun `数值缺口径四件任一即拒`() {
        for (key in listOf("caliber", "source", "asOf", "evidence")) {
            val o = propsJson()
            o.getJSONArray("ratios").getJSONObject(0).remove(key)
            rejects { FormTables.parseProportions(o.toString()) }
        }
    }

    @Test
    fun `未知证据等级即拒`() {
        val o = propsJson()
        o.getJSONArray("ratios").getJSONObject(0).put("evidence", "hearsay")
        rejects { FormTables.parseProportions(o.toString()) }
    }

    @Test
    fun `区间倒置即拒`() {
        val o = propsJson()
        o.getJSONArray("ratios").getJSONObject(0).put("low", 0.9).put("high", 0.1)
        rejects { FormTables.parseProportions(o.toString()) }
    }

    @Test
    fun `默认值落区间外即拒`() {
        val o = propsJson()
        o.getJSONArray("ratios").getJSONObject(0).put("default", 0.99)
        rejects { FormTables.parseProportions(o.toString()) }
    }

    @Test
    fun `分段id重复即拒`() {
        val o = propsJson()
        val a = o.getJSONArray("segments")
        a.getJSONObject(1).put("id", a.getJSONObject(0).getString("id"))
        rejects { FormTables.parseProportions(o.toString()) }
    }

    @Test
    fun `分段长度来源须恰一种`() {
        val both = propsJson()
        both.getJSONArray("segments").getJSONObject(0).put("length_frac", 0.1)
        rejects { FormTables.parseProportions(both.toString()) }
        val neither = propsJson()
        neither.getJSONArray("segments").getJSONObject(0).remove("length_from")
        rejects { FormTables.parseProportions(neither.toString()) }
    }

    @Test
    fun `分区id重复即拒`() {
        val o = zonesJson()
        val a = o.getJSONArray("zones")
        a.getJSONObject(1).put("zone_id", a.getJSONObject(0).getString("zone_id"))
        rejects { FormTables.parseZones(o.toString()) }
    }

    @Test
    fun `空分区表即拒`() {
        rejects { FormTables.parseZones(zonesJson().put("zones", org.json.JSONArray()).toString()) }
    }

    @Test
    fun `不变式指向不存在的区即拒`() {
        val o = zonesJson()
        o.getJSONArray("invariants").getJSONObject(0).put("zone_id", "nowhere")
        rejects { FormTables.parseZones(o.toString()) }
    }

    @Test
    fun `代理体类型或方位不在枚举即拒`() {
        val a = zonesJson()
        a.getJSONArray("zones").getJSONObject(0).put("proxy", "torus")
        rejects { FormTables.parseZones(a.toString()) }
        val b = zonesJson()
        b.getJSONArray("zones").getJSONObject(0).put("side", "sideways")
        rejects { FormTables.parseZones(b.toString()) }
    }

    @Test
    fun `阴毛梯度缺系数或越界或缺来源即拒`() {
        val noFactor = propsJson()
        val g = noFactor.getJSONObject("skin").getJSONObject("hair_gradient")
        for (k in g.keys().asSequence().filter { it.endsWith("_factor") }.toList()) g.remove(k)
        rejects { FormTables.parseProportions(noFactor.toString()) }

        val outOfRange = propsJson()
        outOfRange.getJSONObject("skin").getJSONObject("hair_gradient").put("mons_factor", 1.5)
        rejects { FormTables.parseProportions(outOfRange.toString()) }

        val noSource = propsJson()
        noSource.getJSONObject("skin").getJSONObject("hair_gradient").remove("source")
        rejects { FormTables.parseProportions(noSource.toString()) }
    }

    @Test
    fun `每条数值都带口径四件`() {
        for (s in allSpecs()) {
            assertTrue("${s.id} 缺 caliber", s.caliber.isNotBlank())
            assertTrue("${s.id} 缺 source", s.source.isNotBlank())
            assertTrue("${s.id} 缺 asOf", s.asOf.isNotBlank())
            assertTrue("${s.id} 区间倒置", s.low <= s.high)
            assertTrue("${s.id} 默认值落区间外", s.default in s.low..s.high)
        }
        assertTrue("数值条数须够多（防遍历漏项）", allSpecs().size >= 60)
        assertEquals(
            "柔韧口径须逐条入账：三件＋十七关节",
            20, allSpecs().count { it.id.startsWith("flexibility.") },
        )
    }

    @Test
    fun `分区表里不放色值_防编色`() {
        assertFalse("分区表不许有 hex 字段", "\"hex\"" in zonesText)
        assertFalse("分区表不许有 #RRGGBB 字面量", Regex("#[0-9a-fA-F]{6}").containsMatchIn(zonesText))
        assertTrue("分区表须写明色值由调用方带来源传入", "source" in zonesText)
    }

    @Test
    fun `凸起口径只产h_硬度系数随充血单调`() {
        val b = FormTables.parseZones(zonesText).bulge
        assertTrue("口径须点名抹平归 666", "666" in b.caliber)
        assertEquals(b.hardnessLow, b.coefficient(0.0), 1e-12)
        assertEquals(b.hardnessHigh, b.coefficient(1.0), 1e-12)
        assertTrue(b.coefficient(0.25) < b.coefficient(0.75))
        rejects { b.coefficient(1.5) }
    }

    @Test
    fun `接触恢复系数落在655可用域`() {
        val c = FormTables.parseZones(zonesText).contact
        assertTrue(c.restitution.low >= 0.0 && c.restitution.high <= 1.0)
        assertTrue(c.stiffness.low >= 1.0)
    }

    @Test
    fun `归一值越界即拒_不外推`() {
        val s = FormTables.parseProportions(propsText).ratio("shoulder_biacromial")
        rejects { s.at(-0.1) }
        rejects { s.at(1.1) }
        assertEquals(s.low, s.at(0.0), 1e-12)
        assertEquals(s.high, s.at(1.0), 1e-12)
    }

    @Test
    fun `柔韧口径块齐全_十七关节四年龄段三提示`() {
        val f = FormTables.parseProportions(propsText).flexibility
        assertEquals(JointNames.ALL.toSet(), f.jointBaseDeg.keys)
        assertEquals(setOf("youth", "adult", "middle", "senior"), f.ageBandDefault.keys)
        assertEquals(setOf("neck", "spine", "chest"), f.riskNotes.keys)
        assertEquals(1.0, f.grade.low, 1e-12)
        assertEquals(5.0, f.grade.high, 1e-12)
        assertEquals(3.0, f.grade.default, 1e-12)
        assertEquals(0.6, f.limitScale.low, 1e-12)
        assertEquals(1.4, f.limitScale.high, 1e-12)
        assertEquals(1.15, f.marginalBand.default, 1e-12)
        assertEquals(Evidence.ASSUMPTION, f.jointBaseDeg.getValue("neck").evidence)
    }

    @Test
    fun `柔韧口径三处caliber都真被读进模型_不是表里写着没人读`() {
        val f = FormTables.parseProportions(propsText).flexibility
        assertTrue("年龄段口径须写明不内置年龄推算", "不内置年龄推算" in f.ageBandCaliber)
        assertTrue("风险提示口径须写明只提示不禁止", "只提示不禁止" in f.riskCaliber)
        assertTrue("关节基准口径须写明非临床值域", "非临床值域" in f.jointBaseDeg.getValue("neck").caliber)
        assertTrue("总口径须写明限位不是常数表", "档位系数" in f.caliber)
        assertTrue("总口径须写明不内置人类不能清单", "人类不能" in f.caliber)
        assertTrue("柔韧口径须带来源", f.source.isNotBlank())
        assertEquals("2026-11-18", f.asOf)
    }

    @Test
    fun `柔韧块缺项或越界即拒_不给兜底默认`() {
        val emptyBase = propsJson()
        emptyBase.getJSONObject("flexibility").put("joint_base_deg", JSONObject())
        rejects { FormTables.parseProportions(emptyBase.toString()) }

        val emptyBands = propsJson()
        emptyBands.getJSONObject("flexibility").put("age_band_default", JSONObject().put("caliber", "空"))
        rejects { FormTables.parseProportions(emptyBands.toString()) }

        val narrowBand = propsJson()
        narrowBand.getJSONObject("flexibility").getJSONObject("marginal_band").put("value", 0.9)
        rejects { FormTables.parseProportions(narrowBand.toString()) }

        val zeroScale = propsJson()
        zeroScale.getJSONObject("flexibility").getJSONObject("limit_scale").put("low", 0.0)
        rejects { FormTables.parseProportions(zeroScale.toString()) }

        val badGrade = propsJson()
        badGrade.getJSONObject("flexibility").getJSONObject("grade").put("default", 9)
        rejects { FormTables.parseProportions(badGrade.toString()) }

        val inverted = propsJson()
        inverted.getJSONObject("flexibility").getJSONObject("joint_base_deg").getJSONObject("neck")
            .put("low", 70).put("high", 30)
        rejects { FormTables.parseProportions(inverted.toString()) }

        for (path in listOf("source", "asOf", "caliber")) {
            val o = propsJson()
            o.getJSONObject("flexibility").remove(path)
            rejects { FormTables.parseProportions(o.toString()) }
        }
        for (block in listOf("age_band_default", "risk_notes", "joint_base_deg")) {
            val o = propsJson()
            o.getJSONObject("flexibility").getJSONObject(block).remove("caliber")
            rejects { FormTables.parseProportions(o.toString()) }
        }
    }

    @Test
    fun `柔韧块整块缺失即拒`() {
        val o = propsJson()
        o.remove("flexibility")
        assertThrows(Exception::class.java) { FormTables.parseProportions(o.toString()) }
    }

    @Test
    fun `档位系数按档线性_两端取表值_超域即拒`() {
        val f = FormTables.parseProportions(propsText).flexibility
        for ((g, k) in mapOf(1 to 0.6, 2 to 0.8, 3 to 1.0, 4 to 1.2, 5 to 1.4)) {
            assertEquals("档 $g 系数", k, f.scale(g), 1e-12)
        }
        rejects { f.scale(0) }
        rejects { f.scale(6) }
    }

    @Test
    fun `年龄段默认档取表_未知年龄段即拒`() {
        val f = FormTables.parseProportions(propsText).flexibility
        assertEquals(4, f.defaultGrade("youth"))
        assertEquals(3, f.defaultGrade("adult"))
        assertEquals(2, f.defaultGrade("middle"))
        assertEquals(1, f.defaultGrade("senior"))
        assertEquals(3, f.defaultGrade(null))
        rejects { f.defaultGrade("nowhere") }
    }

    private fun allSpecs(): List<NumericSpec> {
        val p = FormTables.parseProportions(propsText)
        val z = FormTables.parseZones(zonesText)
        val out = mutableListOf<NumericSpec>()
        out.addAll(p.ratios.values)
        out.addAll(p.constants.values)
        out.addAll(p.parts.values)
        out.addAll(listOf(p.skin.elasticity, p.skin.hair, p.skin.hexDigits))
        for (s in p.segments) out.addAll(listOfNotNull(s.split, s.length, s.radius))
        for (s in p.sections) out.addAll(listOf(s.station, s.width, s.thickness))
        out.addAll(z.domains.values)
        out.addAll(listOf(z.rootY, z.contact.restitution, z.contact.stiffness))
        for (s in z.zones) out.addAll(listOf(s.radiusFactor, s.axial, s.gain))
        out.addAll(listOf(p.flexibility.grade, p.flexibility.limitScale, p.flexibility.marginalBand))
        out.addAll(p.flexibility.jointBaseDeg.values)
        return out.toList()
    }
}