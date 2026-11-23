// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百七十五批 · 子区层：三条硬常识与三柱／白膜的判据测试（每条都有反例；反例不红＝闸没牙）。
// 只读源码与 docs/facts，零写盘、零临时目录（水位口径）。
package com.example.zhengdao.baopu.state.subzone

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import com.example.zhengdao.baopu.state.bodyparts.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubzoneRulesTest {

    private fun withRow(id: SubzoneId, change: (SubzoneRow) -> SubzoneRow): List<SubzoneRow> =
        SubzoneTable.rows.map { if (it.id == id) change(it) else it }

    private fun denyIds(findings: List<SubzoneFinding>): List<SubzoneId> =
        findings.filter { it.verdict == Verdict.DENY }.mapNotNull { it.id }

    // ── 硬常识①：系带在腹侧 ──────────────────────────────────────────────

    @Test
    fun 硬常识一_系带记在腹侧() {
        assertEquals("系带在腹侧——结构属性，不是注释", SubzoneSide.VENTRAL, SubzoneId.FRENULUM.side)
        assertEquals(Verdict.PASS, SubzoneRules.frenulumSideVerdict(SubzoneId.FRENULUM))
        assertTrue("真实表上不该报红", SubzoneRules.frenulumOnVentral().isEmpty())
    }

    @Test
    fun 硬常识一_控制项_判据能分辨腹侧与别的侧() {
        assertEquals("控制项：背侧子区必须被判红", Verdict.DENY, SubzoneRules.frenulumSideVerdict(SubzoneId.SHAFT_MID))
        assertEquals("控制项：环周子区必须被判红", Verdict.DENY, SubzoneRules.frenulumSideVerdict(SubzoneId.GLANS_BODY))
    }

    // ── 硬常识②：尿道口是偏腹侧纵裂，不是正中圆孔 ────────────────────────

    @Test
    fun 硬常识二_尿道口记成偏腹侧纵裂() {
        assertEquals("档名表第 0 档必须是纵裂偏腹侧", "纵裂偏腹侧", FormAxis.SLIT.tierName(SubzoneRules.SLIT_VENTRAL))
        val form = SubzoneTable.row(SubzoneId.MEATUS_SLIT).formOf(FormAxis.SLIT)
        assertTrue("裂隙方向必须登记在 MEATUS_SLIT 这一行上", form != null)
        assertEquals(SubzoneRules.SLIT_VENTRAL, form!!.tier)
        assertEquals(Verdict.PASS, SubzoneRules.slitVerdict(form.tier))
        assertEquals("尿道口整体偏腹侧", SubzoneSide.VENTRAL, SubzoneId.MEATUS_SLIT.side)
        assertTrue(SubzoneRules.meatusSlit(SubzoneTable.rows).isEmpty())
    }

    @Test
    fun 硬常识二_圆孔即红_居中纵裂只提示() {
        assertEquals("圆孔是误写，当场红", Verdict.DENY, SubzoneRules.slitVerdict(SubzoneRules.SLIT_ROUND))
        assertEquals("纵裂但记成居中：方向不符，须核", Verdict.NEED_CHECK, SubzoneRules.slitVerdict(SubzoneRules.SLIT_CENTER))
        val round = withRow(SubzoneId.MEATUS_SLIT) {
            it.copy(forms = listOf(FormValue(FormAxis.SLIT, SubzoneRules.SLIT_ROUND)))
        }
        val findings = SubzoneRules.meatusSlit(round)
        assertEquals("改坏即红", 1, findings.size)
        assertEquals(Verdict.DENY, findings[0].verdict)
        assertEquals(SubzoneId.MEATUS_SLIT, findings[0].id)
    }

    @Test
    fun 硬常识二_裂隙方向这一行缺轴或整行缺_都报红() {
        val noAxis = withRow(SubzoneId.MEATUS_SLIT) { it.copy(forms = listOf(FormValue(FormAxis.RIM, 0))) }
        assertTrue("缺轴必须红", SubzoneRules.meatusSlit(noAxis).any { it.verdict == Verdict.DENY })
        val noRow = SubzoneTable.rows.filterNot { it.id == SubzoneId.MEATUS_SLIT }
        val findings = SubzoneRules.meatusSlit(noRow)
        assertEquals("缺行必须红", 1, findings.size)
        assertTrue("必须说清缺的是裂隙方向", findings[0].why.contains("裂隙方向"))
    }

    // ── 硬常识③：龟头更光滑、颜色深一档 ─────────────────────────────────

    @Test
    fun 硬常识三_真实表上龟头比柱身光滑一档且深一档() {
        assertTrue("真实表上三条硬常识都不许报", SubzoneRules.hardCommonSense(SubzoneTable.rows).none { it.verdict == Verdict.DENY })
        val glans = SubzoneId.ofGroup(SubzoneGroup.GLANS).map { SubzoneTable.row(it).texture.rank }
        val shaft = SubzoneId.ofGroup(SubzoneGroup.SHAFT).map { SubzoneTable.row(it).texture.rank }
        assertEquals("龟头应恒为光滑秩", listOf(2, 2, 2), glans)
        assertEquals("柱身应恒为一般秩", listOf(1, 1, 1), shaft)
        assertEquals("差正好一档", 1, glans.min() - shaft.max())
    }

    @Test
    fun 硬常识三_反例_龟头记成一般即红() {
        val rows = withRow(SubzoneId.APEX) { it.copy(texture = Texture.PLAIN) }
        val findings = SubzoneRules.textureOrder(rows)
        assertEquals("改坏即红", listOf(SubzoneId.APEX), denyIds(findings))
        assertTrue("理由要点出「更光滑」", findings[0].why.contains("更光滑"))
    }

    @Test
    fun 硬常识三_反例_龟头记成同或浅即红_深两档只提示() {
        val same = withRow(SubzoneId.GLANS_BODY) { it.copy(color = ColorShift.SAME) }
        assertEquals("记「同」即红", listOf(SubzoneId.GLANS_BODY), denyIds(SubzoneRules.colorOrder(same)))
        val lighter = withRow(SubzoneId.GLANS_CROWN) { it.copy(color = ColorShift.LIGHTER) }
        assertEquals("记「浅」即红", listOf(SubzoneId.GLANS_CROWN), denyIds(SubzoneRules.colorOrder(lighter)))
        val deeper = withRow(SubzoneId.GLANS_BODY) { it.copy(color = ColorShift.DEEPER_2) }
        val findings = SubzoneRules.colorOrder(deeper)
        assertTrue("深两档不许判红（角色个性档）", denyIds(findings).isEmpty())
        assertEquals("但必须提示须核", Verdict.NEED_CHECK, findings.single().verdict)
    }

    @Test
    fun 柱身记成非同一档即红_参照物不能自带偏移() {
        val rows = withRow(SubzoneId.SHAFT_MID) { it.copy(color = ColorShift.DEEPER_1) }
        assertEquals("柱身只能记「同」", listOf(SubzoneId.SHAFT_MID), denyIds(SubzoneRules.shaftBaseline(rows)))
    }

    // ── 三柱与白膜：只给可触及与方位档 ───────────────────────────────────

    @Test
    fun 三柱_一腹两背_三根都不分单根摸不到() {
        assertTrue("真实三柱表不该报红", Tunica.findings(ColumnTable.entries).isEmpty())
        assertEquals("三根柱", 3, ColumnTable.entries.size)
        assertEquals(
            "两根在背、一根在腹",
            listOf(ColumnBearing.DORSAL_LEFT, ColumnBearing.DORSAL_RIGHT, ColumnBearing.VENTRAL_MID),
            ColumnTable.entries.map { it.bearing },
        )
        assertTrue("三根都记不可触及", ColumnTable.entries.all { it.touch == Touchability.IMPALPABLE })
    }

    @Test
    fun 三柱反例_方位写错即红() {
        val bad = ColumnTable.entries.map {
            if (it.column == Column.SPONGIOSUM) it.copy(bearing = ColumnBearing.DORSAL_LEFT) else it
        }
        val findings = Tunica.findings(bad)
        assertTrue("尿道海绵体不该记在背侧", findings.any { it.verdict == Verdict.DENY && it.code == "SZ-COLUMN-BEARING" })
    }

    @Test
    fun 三柱反例_记成可触及即红() {
        val bad = ColumnTable.entries.map {
            if (it.column == Column.CAVERNOSUM_LEFT) it.copy(touch = Touchability.PALPABLE) else it
        }
        assertTrue("单根柱摸不到", Tunica.findings(bad).any { it.code == "SZ-COLUMN-TOUCH" })
    }

    @Test
    fun 白膜_包全三柱且自身摸不到() {
        assertEquals("白膜不可触及", Touchability.IMPALPABLE, Tunica.touch)
        assertEquals("包被三根柱", Column.entries.toSet(), Tunica.wraps.toSet())
        assertEquals("白膜名只一个", "白膜", Tunica.LABEL)
    }

    // ── 结论文本：不许夹带长度与判定词 ───────────────────────────────────

    @Test
    fun 判据结论理由都过三道文字闸() {
        val rows = SubzoneTable.rows.map {
            when (it.id) {
                SubzoneId.APEX -> it.copy(texture = Texture.PLAIN)
                SubzoneId.SHAFT_MID -> it.copy(color = ColorShift.DEEPER_1)
                else -> it
            }
        }
        val findings = SubzoneRules.hardCommonSense(rows) + Tunica.findings(ColumnTable.entries)
        assertTrue("夹具须真产出结论", findings.isNotEmpty())
        for (finding in findings) {
            assertTrue("${finding.code}：理由为空", finding.why.isNotBlank())
            assertTrue("${finding.code}：理由含长度单位", BodyPartGuard.measureHit(finding.why) == null)
            assertTrue("${finding.code}：理由含判定词", BodyPartGuard.diagnosisHits(finding.why).isEmpty())
            assertTrue("${finding.code}：结论行须带判定词与代码", finding.line().startsWith("[") && finding.line().contains(finding.code))
        }
    }
}
