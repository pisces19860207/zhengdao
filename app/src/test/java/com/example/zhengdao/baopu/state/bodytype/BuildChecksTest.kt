// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 第八百一十五批 · 判据测试：①–⑥ 逐条，红/提示/通过三态齐全。
package com.example.zhengdao.baopu.state.bodytype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildChecksTest {

    // ── 判据① 五档齐 ────────────────────────────────────────────────
    @Test
    fun `判据一_五档齐出账带五个字段名`() {
        val f = BuildChecks.completeness(BuildFixtures.mid())
        assertEquals(BuildVerdict.PASS, f.verdict)
        assertEquals("TIER-COMPLETE", f.code)
        BodyProfile.TIER_FIELDS.forEach { assertTrue(f.why.contains(it)) }
    }

    // ── 判据② 默认纠偏 ──────────────────────────────────────────────
    @Test
    fun `判据二_默认中中中判通过`() {
        val f = BuildChecks.defaultBias(BodyProfile.DEFAULT)
        assertEquals(BuildVerdict.PASS, f.verdict)
        assertEquals("DEFAULT-MID", f.code)
    }

    @Test
    fun `判据二_低脂高肌无依据判红`() {
        val f = BuildChecks.defaultBias(BuildFixtures.lowFatHighMuscle())
        assertEquals(BuildVerdict.DENY, f.verdict)
        assertEquals("LOW-FAT-HIGH-MUSCLE-NO-BASIS", f.code)
    }

    @Test
    fun `判据二_低脂高肌有依据降为提示`() {
        val f = BuildChecks.defaultBias(
            BuildFixtures.lowFatHighMuscle(basis = Basis.TRAINING_HISTORY),
        )
        assertEquals(BuildVerdict.HINT, f.verdict)
        assertEquals("LOW-FAT-HIGH-MUSCLE-WITH-BASIS", f.code)
    }

    @Test
    fun `判据二_普通组合不误红`() {
        val p = BuildFixtures.mid().copy(bodyFatTier = BodyFatTier.HIGH, muscleTier = MuscleTier.MID)
        assertEquals(BuildVerdict.PASS, BuildChecks.defaultBias(p).verdict)
    }

    // ── 判据③ 与 660 截面一致 ───────────────────────────────────────
    @Test
    fun `判据三_未给截面跳过不假判一致`() {
        val f = BuildChecks.sectionConsistency(BuildFixtures.mid(), null)
        assertEquals(BuildVerdict.PASS, f.verdict)
        assertEquals("SECTION-NA", f.code)
    }

    @Test
    fun `判据三_截面匹配判通过`() {
        val p = BuildFixtures.lowFatHighMuscle()
        val f = BuildChecks.sectionConsistency(p, BuildFixtures.matchedSection(p))
        assertEquals(BuildVerdict.PASS, f.verdict)
        assertEquals("SECTION-OK", f.code)
    }

    @Test
    fun `判据三_宽厚围度全反判红`() {
        val p = BuildFixtures.lowFatHighMuscle()
        val f = BuildChecks.sectionConsistency(p, BuildFixtures.opposedSection(p))
        assertEquals(BuildVerdict.DENY, f.verdict)
        assertEquals("SECTION-CONFLICT", f.code)
    }

    @Test
    fun `判据三_差一档只提示不判红`() {
        val p = BuildFixtures.mid()
        val section = BuildFixtures.matchedSection(p)
        val oneOff = section.copy(width = SectionGrade.WIDE) // 中骨架(1) vs 宽(2)：差一档
        val f = BuildChecks.sectionConsistency(p, oneOff)
        assertEquals(BuildVerdict.HINT, f.verdict)
        assertEquals("SECTION-SOFT", f.code)
    }

    // ── 判据④ 不做身材评价 ──────────────────────────────────────────
    @Test
    fun `判据四_普通档案过评价闸`() {
        assertEquals(BuildVerdict.PASS, BuildChecks.noEvaluation(BuildFixtures.mid()).verdict)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `判据四_出口理由里塞评价词被咬`() {
        BuildGuard.rejectValueWords("这是一副理想身材")
    }

    // ── 判据⑤ 少见表 ────────────────────────────────────────────────
    @Test
    fun `判据五_少见表合格`() {
        val f = BuildChecks.rarity(BuildFixtures.mid())
        assertEquals(BuildVerdict.PASS, f.verdict)
        assertEquals("RARE-TABLE-OK", f.code)
    }

    // ── 判据⑥ 职业只影响倾向 ────────────────────────────────────────
    @Test
    fun `判据六_工人一定低脂判红`() {
        val p = BuildFixtures.mid().copy(
            basis = Basis.OCCUPATION,
            influence = InfluenceEffect.DECISIVE,
        )
        val f = BuildChecks.occupationTendency(p)
        assertEquals(BuildVerdict.DENY, f.verdict)
        assertEquals("OCCUPATION-DECIDES", f.code)
    }

    @Test
    fun `判据六_职业作倾向判通过`() {
        val p = BuildFixtures.mid().copy(basis = Basis.OCCUPATION)
        assertEquals(BuildVerdict.PASS, BuildChecks.occupationTendency(p).verdict)
    }

    // ── 汇总与出口 ──────────────────────────────────────────────────
    @Test
    fun `取最严_红压提示提示压通过`() {
        val all = BuildChecks.worst(listOf(
            BuildFinding(BuildVerdict.PASS, "a", "甲档"),
            BuildFinding(BuildVerdict.HINT, "b", "乙档"),
            BuildFinding(BuildVerdict.DENY, "c", "丙档"),
        ))
        val hintPass = BuildChecks.worst(listOf(
            BuildFinding(BuildVerdict.PASS, "a", "甲档"),
            BuildFinding(BuildVerdict.HINT, "b", "乙档"),
        ))
        assertEquals(BuildVerdict.DENY, all)
        assertEquals(BuildVerdict.HINT, hintPass)
    }

    @Test
    fun `判定数_六条判据齐全`() {
        val findings = BuildChecks.all(BuildFixtures.mid(), BuildFixtures.matchedSection(BuildFixtures.mid()))
        assertEquals(6, findings.size)
    }

    @Test
    fun `出口汇总逐字含三态计数`() {
        val p = BuildFixtures.lowFatHighMuscle()
        val line = BuildChecks.sceneLine(p, null)
        assertTrue(line.contains("红 1"))
        assertTrue(line.contains("LOW-FAT-HIGH-MUSCLE-NO-BASIS"))
    }

    @Test
    fun `确定性_两次出口逐字一致`() {
        val p = BuildFixtures.lowFatHighMuscle(basis = Basis.TRAINING_HISTORY)
        val section = BuildFixtures.opposedSection(p)
        assertEquals(BuildChecks.sceneLine(p, section), BuildChecks.sceneLine(p, section))
    }
}
