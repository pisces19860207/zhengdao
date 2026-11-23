// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百四十八批 · 判据测试：差异下限（判据②）、只有体积差（判据③）、档案矛盾（判据④）。
package com.example.zhengdao.baopu.state.bodyparts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TorsoChecksTest {

    private val f = TorsoFixtures

    // ── 判据② 差异下限 ───────────────────────────────────────────────

    @Test
    fun `判据二_差两维判过`() {
        val a = f.person("甲")
        val b = f.person("乙", shape = f.shape(linea = LineaAlba.DEEP, trap = TrapSlope.STEEP))
        val r = TorsoChecks.pair(a, b)
        assertEquals(TorsoVerdict.PASS, r.verdict)
        assertEquals("SHAPE-OK", r.code)
        assertTrue(r.why.contains("形状差 2 维"))
        assertTrue(r.why.contains("白线深浅=浅→深"))
    }

    @Test
    fun `判据二_只差一维判红`() {
        val a = f.person("甲")
        val b = f.person("乙", shape = f.shape(linea = LineaAlba.DEEP))
        val r = TorsoChecks.pair(a, b)
        assertEquals(TorsoVerdict.DENY, r.verdict)
        assertEquals("SHAPE-THIN", r.code)
        assertTrue(r.why.contains("只差 1 维"))
        assertTrue(r.why.contains("未达下限 2 维"))
    }

    @Test
    fun `判据二_八维全同判红`() {
        val r = TorsoChecks.pair(f.person("甲"), f.person("乙"))
        assertEquals(TorsoVerdict.DENY, r.verdict)
        assertEquals("SHAPE-IDENTICAL", r.code)
        assertTrue(r.why.contains("八维全同"))
    }

    @Test
    fun `判据二_同组两维也按两维算`() {
        // 白线深浅与左右列同属形状项①：按维计是 2 维，够下限；按「项」计只有 1 项。
        // 本包按维计（见 TorsoItem 注释）：分组的用处是描述人话，不是算差异。
        val a = f.person("甲")
        val b = f.person("乙", shape = f.shape(linea = LineaAlba.MEDIUM,
            align = BlockAlignment.CLEAR_OFFSET))
        assertEquals(TorsoItem.RECTUS, itemOf(TorsoDimension.LINEA_ALBA))
        assertEquals(TorsoItem.RECTUS, itemOf(TorsoDimension.BLOCK_ALIGNMENT))
        assertEquals(TorsoVerdict.PASS, TorsoChecks.pair(a, b).verdict)
    }

    // ── 判据③ 只有体积差 ─────────────────────────────────────────────

    @Test
    fun `判据三_只有体积差判提示`() {
        val a = f.person("甲")
        val b = f.person("乙", volume = f.volume(pec = PecVolume.THICK))
        val r = TorsoChecks.pair(a, b)
        assertEquals(TorsoVerdict.HINT, r.verdict)
        assertEquals("SHAPE-VOLUME-ONLY", r.code)
        assertTrue(r.why.contains("只差体积"))
        assertTrue(r.why.contains("胸肌 微隆→厚实"))
        assertTrue("要说出这是同一副躯干", r.why.contains("胖瘦"))
    }

    @Test
    fun `判据三_提示不越界成红_场景汇总也取提示`() {
        val a = f.person("甲")
        val b = f.person("乙", volume = f.volume(abs = AbsBlock.DISTINCT))
        val r = TorsoChecks.scene(listOf(a, b))
        assertEquals(TorsoVerdict.HINT, r.verdict)
    }

    @Test
    fun `判据二_差一维即使体积也不同仍判红`() {
        val a = f.person("甲")
        val b = f.person("乙", shape = f.shape(linea = LineaAlba.DEEP),
            volume = f.volume(pec = PecVolume.THICK))
        val r = TorsoChecks.pair(a, b)
        assertEquals("有形状差就不是「只有体积差」", TorsoVerdict.DENY, r.verdict)
        assertEquals("SHAPE-THIN", r.code)
    }

    // ── 判据② 场景与可见性 ──────────────────────────────────────────

    @Test
    fun `判据二_三人任一对不达下限即红`() {
        val jia = f.person("甲")
        val yi = f.person("乙", shape = f.shape(linea = LineaAlba.DEEP, trap = TrapSlope.STEEP))
        val bing = f.person("丙", shape = f.shape(linea = LineaAlba.DEEP))
        val r = TorsoChecks.scene(listOf(jia, yi, bing))
        assertEquals(TorsoVerdict.DENY, r.verdict)
        assertTrue("要点名那一对", r.why.contains("甲 与 丙"))
    }

    @Test
    fun `判据二_三人两两都达下限即过`() {
        val jia = f.person("甲")
        val yi = f.person("乙", shape = f.shape(linea = LineaAlba.DEEP, trap = TrapSlope.STEEP))
        val bing = f.person("丙", shape = f.shape(blocks = RectusBlocks.FOUR,
            pecLower = PecLowerTrend.FLAT, lat = LatFlare.V_TAPER, serratus = Serratus.OBVIOUS))
        assertEquals(TorsoVerdict.PASS, TorsoChecks.scene(listOf(jia, yi, bing)).verdict)
    }

    @Test
    fun `判据二_穿着者不参与比对`() {
        val jia = f.person("甲")
        val yi = f.person("乙", shape = f.shape(linea = LineaAlba.DEEP, trap = TrapSlope.STEEP))
        val bing = f.person("丙", visible = false) // 与甲完全同形，但躯干不可见
        assertEquals("穿着者不进差异下限", TorsoVerdict.PASS,
            TorsoChecks.scene(listOf(jia, yi, bing)).verdict)
        // 同一张形状一旦可见，立刻撞脸
        val bing2 = f.person("丙")
        assertEquals(TorsoVerdict.DENY, TorsoChecks.scene(listOf(jia, yi, bing2)).verdict)
    }

    @Test
    fun `判据二_可见者不足两人时下限不启用`() {
        val r = TorsoChecks.scene(listOf(f.person("甲"), f.person("乙", visible = false)))
        assertEquals(TorsoVerdict.PASS, r.verdict)
        assertEquals("SCENE-TOO-FEW", r.code)
        assertTrue(r.why.contains("可见者 1 人"))
    }

    @Test
    fun `不可见者直接进比对即拒`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            TorsoChecks.pair(f.person("甲"), f.person("乙", visible = false))
        }
        assertEquals(TorsoChecks.INVISIBLE_REJECT, e.message)
    }

    // ── 判据④ 档案矛盾 ──────────────────────────────────────────────

    @Test
    fun `判据四_久坐却白线深加斜方陡判红`() {
        val p = f.person("甲", shape = f.shape(linea = LineaAlba.DEEP, trap = TrapSlope.STEEP))
        val r = TorsoChecks.profileVerdict(p)
        assertEquals(TorsoVerdict.DENY, r.verdict)
        assertEquals("PROFILE-CONFLICT", r.code)
        assertTrue(r.why.contains("2 维与倾向相反"))
        assertTrue(r.why.contains("白线深浅=深，应为 被脂覆盖／浅／中"))
        assertTrue(r.why.contains("斜方肌坡度=陡，应为 平缓／中"))
    }

    @Test
    fun `判据四_单维相反只判提示`() {
        val p = f.person("甲", shape = f.shape(linea = LineaAlba.DEEP))
        val r = TorsoChecks.profileVerdict(p)
        assertEquals(TorsoVerdict.HINT, r.verdict)
        assertEquals("PROFILE-DEVIATE", r.code)
        assertTrue(r.why.contains("1 维与倾向相反"))
        assertTrue("一维要允许个体差异", r.why.contains("个体差异"))
    }

    @Test
    fun `判据四_健身型四维全中判过`() {
        val p = f.person("甲", shape = f.athleticShape(), profile = TorsoProfile.ATHLETIC)
        val r = TorsoChecks.profileVerdict(p)
        assertEquals(TorsoVerdict.PASS, r.verdict)
        assertTrue(r.why.contains("4 维全部落在允许档内"))
    }

    @Test
    fun `判据四_大腹白线深且下缘外高内低判红`() {
        val p = f.person("甲", shape = f.shape(linea = LineaAlba.DEEP,
            pecLower = PecLowerTrend.OUTER_HIGH), profile = TorsoProfile.BELLY)
        assertEquals(TorsoVerdict.DENY, TorsoChecks.profileVerdict(p).verdict)
    }

    @Test
    fun `判据四_大腹白线被覆盖且下缘平判过`() {
        val p = f.person("甲", shape = f.shape(linea = LineaAlba.COVERED,
            pecLower = PecLowerTrend.FLAT), profile = TorsoProfile.BELLY)
        assertEquals(TorsoVerdict.PASS, TorsoChecks.profileVerdict(p).verdict)
    }

    @Test
    fun `判据四_体力劳动三项相反判红`() {
        val p = f.person("甲", shape = f.shape(serratus = Serratus.NONE,
            pecLower = PecLowerTrend.OUTER_HIGH, lat = LatFlare.V_TAPER),
            profile = TorsoProfile.LABOR)
        val r = TorsoChecks.profileVerdict(p)
        assertEquals(TorsoVerdict.DENY, r.verdict)
        assertTrue(r.why.contains("3 维与倾向相反"))
    }

    @Test
    fun `判据四_老年只有一条可判约束_最多提示`() {
        // 派工单另写「老年＝下缘下滑」：下滑是形体位置（660 域），本包不新造第九维。
        // 于是老年只剩「块界」一条，单约束下不可能凑到 2 维 ⇒ 最多提示。这是有意降级，不是漏判。
        assertEquals(1, TorsoTendencies.of(TorsoProfile.ELDER).size)
        val p = f.person("甲", shape = f.athleticShape(), profile = TorsoProfile.ELDER)
        val r = TorsoChecks.profileVerdict(p)
        assertEquals(TorsoVerdict.HINT, r.verdict)
        assertTrue(r.why.contains("块多") || r.why.contains("腹直肌块数"))
    }

    @Test
    fun `档案表不硬凑_两维全程不参与`() {
        assertEquals(listOf(TorsoDimension.BLOCK_ALIGNMENT, TorsoDimension.PEC_GAP),
            TorsoTendencies.NEVER_CONSTRAINED)
        // 未参与的维即使写成「明显错／宽」，也不该被档案判据咬到
        val p = f.person("甲", shape = f.shape(align = BlockAlignment.CLEAR_OFFSET,
            gap = PecGapWidth.WIDE))
        assertEquals(TorsoVerdict.PASS, TorsoChecks.profileVerdict(p).verdict)
    }

    // ── 汇总与出口 ──────────────────────────────────────────────────

    @Test
    fun `取最严_红压提示提示压通过`() {
        val pass = TorsoChecks.pair(f.person("甲"),
            f.person("乙", shape = f.shape(linea = LineaAlba.DEEP, trap = TrapSlope.STEEP)))
        val hint = TorsoChecks.pair(f.person("甲"),
            f.person("丙", volume = f.volume(pec = PecVolume.THICK)))
        val deny = TorsoChecks.pair(f.person("甲"), f.person("丁"))
        assertEquals(TorsoVerdict.PASS, pass.verdict)
        assertEquals(TorsoVerdict.HINT, hint.verdict)
        assertEquals(TorsoVerdict.DENY, deny.verdict)
        assertEquals(TorsoVerdict.HINT, TorsoChecks.worst(listOf(pass, hint)))
        assertEquals(TorsoVerdict.DENY, TorsoChecks.worst(listOf(pass, hint, deny)))
        assertEquals(TorsoVerdict.PASS, TorsoChecks.worst(emptyList()))
    }

    @Test
    fun `汇总计数与出口无禁词`() {
        val persons = listOf(
            f.person("甲"),
            f.person("乙", shape = f.shape(linea = LineaAlba.DEEP, trap = TrapSlope.STEEP)),
        )
        val findings = TorsoChecks.all(persons)
        assertEquals(3, findings.size) // 1 条场景 + 2 条档案
        assertEquals("通过 2／提示 0／红 1", TorsoChecks.summary(findings))
        val text = TorsoChecks.sceneLine(persons)
        assertTrue(text.startsWith("通过 2／提示 0／红 1"))
        assertTrue(BodyPartGuard.diagnosisHits(text).isEmpty())
        assertTrue(BodyPartGuard.measureHit(text) == null)
        assertFalse(text.contains("%"))
    }

    @Test
    fun `确定性_两次逐字一致`() {
        val persons = listOf(
            f.person("甲"),
            f.person("乙", shape = f.shape(linea = LineaAlba.DEEP)),
            f.person("丙", shape = f.athleticShape(), profile = TorsoProfile.ATHLETIC),
        )
        assertEquals(
            TorsoChecks.all(persons).map { it.line() },
            TorsoChecks.all(persons).map { it.line() },
        )
        assertEquals(TorsoChecks.sceneLine(persons), TorsoChecks.sceneLine(persons))
    }

    private fun itemOf(dim: TorsoDimension): TorsoItem =
        TorsoItem.entries.first { dim in it.dimensions }
}
