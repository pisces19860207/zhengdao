// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零五批 · 判据正负例：体脂一致性、乳晕矩阵、左右对称、喉结驱动。
// 每组都配一个「同形但合法」的负例，防「只要报警就算过」的假绿。
package com.example.zhengdao.baopu.state.bodyparts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyPartChecksTest {

    private val clean = BodyPartFixtures.clean()

    // ── 判据②：体脂一致性 ────────────────────────────────────────────

    @Test
    fun `判据二_脂低却胸肌软塌与腹肌不显_两条红`() {
        val flat = BodyPartFixtures.sheet() // 全 0 档
        val findings = BodyPartChecks.fatConsistency(flat, FatBand.VERY_LEAN)
        assertEquals(2, findings.size)
        assertTrue(findings.all { it.verdict == Verdict.DENY })
        assertTrue(findings.any { it.code == "FAT-LEAN-ABS-FLAT" })
        assertTrue(findings.any { it.code == "FAT-LEAN-PEC-SOFT" })
    }

    @Test
    fun `判据二_脂低但收紧可见分块_不报腹肌`() {
        val flat = BodyPartFixtures.sheet()
        val body = BodyPartFixtures.with(flat, Dim.ABS_BLOCK, Side.LEFT, Posture.TIGHTENED, AbsBlock.UPPER_MID.level)
        val findings = BodyPartChecks.fatConsistency(body, FatBand.LEAN)
        assertTrue(findings.none { it.code == "FAT-LEAN-ABS-FLAT" })
        assertTrue("胸肌仍是零档，软塌这条该留着", findings.any { it.code == "FAT-LEAN-PEC-SOFT" })
    }

    @Test
    fun `判据二_脂高却分块分明_红`() {
        val body = BodyPartFixtures.with(clean, Dim.ABS_BLOCK, Side.RIGHT, Posture.TIGHTENED, AbsBlock.DISTINCT.level)
        val findings = BodyPartChecks.fatConsistency(body, FatBand.HIGHER)
        assertTrue(findings.any { it.code == "FAT-HIGH-ABS-CUT" })
    }

    @Test
    fun `判据二_脂高却胸肌深沟_红`() {
        val body = BodyPartFixtures.with(clean, Dim.PEC_SEPARATION, Side.LEFT, Posture.FLEXED, PecSeparation.DEEP.level)
        val findings = BodyPartChecks.fatConsistency(body, FatBand.HIGHER)
        assertTrue(findings.any { it.code == "FAT-HIGH-PEC-DEEP" })
    }

    @Test
    fun `判据二_脂高却血管可见_红`() {
        val body = BodyPartFixtures.with(clean, Dim.BICEPS_VESSEL, Side.LEFT, Posture.RELAXED, Vessel.VISIBLE.level)
        val findings = BodyPartChecks.fatConsistency(body, FatBand.HIGHER)
        assertTrue(findings.any { it.code == "FAT-HIGH-VESSEL" })
    }

    @Test
    fun `判据二_脂中等且全零档_不报`() {
        assertTrue(BodyPartChecks.fatConsistency(BodyPartFixtures.sheet(), FatBand.MODERATE).isEmpty())
    }

    // ── 判据⑥：乳晕 ─────────────────────────────────────────────────

    @Test
    fun `判据六_窄间距配五倍以上乳晕_两侧都红`() {
        var body = BodyPartFixtures.with(clean, Dim.AREOLA_RATIO, Side.LEFT, Posture.RELAXED, AreolaRatio.OVER_FIVE.level)
        body = BodyPartFixtures.with(body, Dim.AREOLA_RATIO, Side.RIGHT, Posture.RELAXED, AreolaRatio.OVER_FIVE.level)
        val findings = BodyPartChecks.areolaRule(body, SpacingBand.NARROW)
        assertEquals(2, findings.count { it.code == "AREOLA-RATIO-DENY" })
        assertEquals(2, findings.count { it.verdict == Verdict.DENY })
    }

    @Test
    fun `判据六_档差二_须核不判红`() {
        val body = BodyPartFixtures.with(clean, Dim.AREOLA_RATIO, Side.LEFT, Posture.RELAXED, AreolaRatio.OVER_FIVE.level)
        val findings = BodyPartChecks.areolaRule(body, SpacingBand.MID)
        assertTrue(findings.any { it.code == "AREOLA-RATIO-NEED_CHECK" })
        assertTrue(findings.none { it.verdict == Verdict.DENY })
    }

    @Test
    fun `判据六_乳晕越过胸肌下缘_红`() {
        val body = BodyPartFixtures.with(clean, Dim.AREOLA_REACH, Side.RIGHT, Posture.RELAXED, AreolaReach.CROSS_EDGE.level)
        val findings = BodyPartChecks.areolaRule(body, SpacingBand.VERY_WIDE)
        assertTrue(findings.any { it.code == "AREOLA-CROSS-EDGE" && it.verdict == Verdict.DENY })
    }

    @Test
    fun `判据六_间距很宽配最小乳晕_不报`() {
        assertTrue(BodyPartChecks.areolaRule(clean, SpacingBand.VERY_WIDE).isEmpty())
    }

    @Test
    fun `判据六_矩阵十六格全可判且只有三态`() {
        var denied = 0
        var checked = 0
        var passed = 0
        SpacingBand.entries.forEach { spacing ->
            AreolaRatio.entries.forEach { ratio ->
                when (AreolaMatrix.verdict(spacing, ratio)) {
                    Verdict.DENY -> denied++
                    Verdict.NEED_CHECK -> checked++
                    Verdict.PASS -> passed++
                }
            }
        }
        // 4×4＝16 格：档差范围 −3..3，差＝3 只有 1 格、差＝2 有 2 格、其余 13 格。
        assertEquals(1, denied)
        assertEquals(2, checked)
        assertEquals(13, passed)
        assertEquals(16, denied + checked + passed)
    }

    @Test
    fun `判据五_倍数下限是两倍`() {
        assertEquals(2, AreolaRatio.MIN_FACTOR)
        assertEquals("乳头两到三倍", AreolaRatio.TWO_TO_THREE.label)
        BodyPartGuard.requireFactorAtLeastTwo(2)
        val msg = messageOf { BodyPartGuard.requireFactorAtLeastTwo(1) }
        assertTrue(msg, msg.contains("两倍"))
    }

    // ── 判据⑩：左右对称 ─────────────────────────────────────────────

    @Test
    fun `判据十_左右差两档却写完全对称_红`() {
        val body = BodyPartFixtures.with(clean, Dim.PEC_VOLUME, Side.LEFT, Posture.RELAXED, PecVolume.THICK.level)
        val findings = BodyPartChecks.symmetryRule(body)
        assertTrue(findings.any { it.code == "SYMMETRY-CONTRADICT" && it.verdict == Verdict.DENY })
    }

    @Test
    fun `判据十_对称档本来就写明显不对称_不报`() {
        var body = BodyPartFixtures.with(clean, Dim.PEC_VOLUME, Side.LEFT, Posture.RELAXED, PecVolume.THICK.level)
        body = BodyPartFixtures.with(body, Dim.PEC_SYMMETRY, Side.MIDLINE, Posture.RELAXED, Symmetry.CLEARLY_ASYMMETRIC.level)
        assertTrue(BodyPartChecks.symmetryRule(body).isEmpty())
    }

    @Test
    fun `判据十_无对称档部位左右差两档_须核`() {
        val body = BodyPartFixtures.with(clean, Dim.BICEPS_GIRTH, Side.LEFT, Posture.RELAXED, Girth.THICK.level)
        val findings = BodyPartChecks.symmetryRule(body)
        assertTrue(findings.any { it.code == "SYMMETRY-GAP" && it.verdict == Verdict.NEED_CHECK })
    }

    @Test
    fun `判据十_左右一致_不报`() {
        assertTrue(BodyPartChecks.symmetryRule(clean).isEmpty())
    }

    // ── 判据⑨：喉结驱动 ────────────────────────────────────────────

    @Test
    fun `判据九_喉结零档不要求驱动`() {
        assertTrue(BodyPartChecks.larynxRule(BodyPartFixtures.sheet(), emptySet()).isEmpty())
    }

    @Test
    fun `判据九_喉结明显无驱动_红`() {
        val body = BodyPartFixtures.with(
            clean, Dim.LARYNX, Side.MIDLINE, Posture.RELAXED, LarynxGrade.OBVIOUS.level, "颈细"
        )
        val findings = BodyPartChecks.larynxRule(body, emptySet())
        assertTrue(findings.any { it.code == "LARYNX-NO-DRIVER" })
    }

    @Test
    fun `判据九_喉结明显一条驱动_过`() {
        val body = BodyPartFixtures.with(
            clean, Dim.LARYNX, Side.MIDLINE, Posture.RELAXED, LarynxGrade.OBVIOUS.level, "颈细"
        )
        assertTrue(BodyPartChecks.larynxRule(body, setOf(LarynxDriver.NECK_LENGTH)).isEmpty())
    }

    @Test
    fun `判据九_喉结高凸一条驱动_红`() {
        val body = BodyPartFixtures.with(
            clean, Dim.LARYNX, Side.MIDLINE, Posture.RELAXED, LarynxGrade.PROMINENT.level, "颈细角度低"
        )
        val findings = BodyPartChecks.larynxRule(body, setOf(LarynxDriver.NECK_LENGTH))
        assertTrue(findings.any { it.code == "LARYNX-THIN-BASIS" })
    }

    @Test
    fun `判据九_喉结高凸两条驱动_过`() {
        val body = BodyPartFixtures.with(
            clean, Dim.LARYNX, Side.MIDLINE, Posture.RELAXED, LarynxGrade.PROMINENT.level, "颈细角度低"
        )
        val drivers = setOf(LarynxDriver.NECK_LENGTH, LarynxDriver.ANGLE)
        assertTrue(BodyPartChecks.larynxRule(body, drivers).isEmpty())
    }

    @Test
    fun `判据九_驱动表恰好六项`() {
        assertEquals(6, LarynxDriver.COUNT)
        assertEquals(6, LarynxDriver.entries.size)
    }

    // ── 汇总、确定性与出处 ───────────────────────────────────────────

    @Test
    fun `汇总_干净单零结论`() {
        assertTrue(BodyPartChecks.all(clean, FatBand.MODERATE, SpacingBand.VERY_WIDE).isEmpty())
        assertEquals("过 0／须核 0／红 0", BodyPartChecks.summary(emptyList()))
    }

    @Test
    fun `确定性_两次跑逐字一致`() {
        val a = BodyPartChecks.all(BodyPartFixtures.sheet(), FatBand.VERY_LEAN, SpacingBand.NARROW).map { it.line() }
        val b = BodyPartChecks.all(BodyPartFixtures.sheet(), FatBand.VERY_LEAN, SpacingBand.NARROW).map { it.line() }
        assertTrue("这一组本就有结论，不是空列表对比", a.isNotEmpty())
        assertEquals(a, b)
    }

    @Test
    fun `每条结论都带来源与日期`() {
        val findings = BodyPartChecks.all(BodyPartFixtures.sheet(), FatBand.VERY_LEAN, SpacingBand.NARROW)
        assertTrue(findings.isNotEmpty())
        findings.forEach { f ->
            assertTrue(f.line(), f.line().contains(BODYPART_AS_OF))
            assertFalse(f.attributedSource.isBlank())
        }
    }

    @Test
    fun `每条结论的理由不含长度与诊断词`() {
        val findings = BodyPartChecks.all(BodyPartFixtures.sheet(), FatBand.VERY_LEAN, SpacingBand.NARROW)
        findings.forEach { f ->
            assertNull(f.line(), BodyPartGuard.measureHit(f.why))
            assertTrue(f.line(), BodyPartGuard.diagnosisHits(f.why).isEmpty())
        }
    }

    @Test
    fun `结论缺出处时写无来源而不是留空`() {
        val f = Finding(Verdict.DENY, "X", BodyPart.PEC, Dim.PEC_VOLUME, "左右对不上", source = "")
        assertEquals(NO_SOURCE, f.attributedSource)
        assertTrue(f.line(), f.line().contains(NO_SOURCE))
    }

    @Test
    fun `两态对照_腹肌放松与收紧都出`() {
        val text = BodyPartChecks.twoStates(clean, Dim.ABS_BLOCK)
        assertTrue(text, text.contains("放松"))
        assertTrue(text, text.contains("收紧"))
    }

    private inline fun messageOf(block: () -> Unit): String {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        throw AssertionError("应当拒收但没有抛异常")
    }
}
