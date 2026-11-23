// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百六十六批 · 判据测试：四件齐、体脂联动、完全对称提示、不对称原因、厚度正交。
package com.example.zhengdao.baopu.state.topography

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import com.example.zhengdao.baopu.state.bodyparts.FatBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopoChecksTest {

    private val f = TopoFixtures

    // ── ① 四件齐 ────────────────────────────────────────────────────

    @Test
    fun `判据一_四件齐出账`() {
        val r = TopoChecks.completeness(f.person("甲"))
        assertEquals(TopoVerdict.PASS, r.verdict)
        assertEquals("TOPO-COMPLETE", r.code)
        assertTrue(r.why.contains("凸 5／凹 5／厚 5"))
        assertTrue(r.why.contains("左右 11 对"))
        assertTrue(r.why.contains("中轴 4 维单记"))
    }

    // ── ② 与体脂联动 ────────────────────────────────────────────────

    @Test
    fun `判据二_胖而锁骨上窝深判红`() {
        val p = f.person("甲", FatBand.HIGHER,
            f.topo(f.hollow(HollowDim.SUPRACLAVICULAR, HollowGrade.DEEP, HollowGrade.DEEP)))
        val r = TopoChecks.fatHollow(p)
        assertEquals(TopoVerdict.DENY, r.verdict)
        assertEquals("FAT-HOLLOW", r.code)
        assertTrue(r.why.contains("锁骨上窝 左=深 右=深"))
        assertTrue("要说清为什么矛盾", r.why.contains("脂多先把窝填浅"))
    }

    @Test
    fun `判据二_胖而一处在中只提示`() {
        val p = f.person("甲", FatBand.HIGHER,
            f.topoOn(f.heavyTopo(), f.hollow(HollowDim.SUPRACLAVICULAR, HollowGrade.MID, HollowGrade.MID)))
        val r = TopoChecks.fatHollow(p)
        assertEquals(TopoVerdict.HINT, r.verdict)
        assertEquals("FAT-HOLLOW-MID", r.code)
        assertTrue(r.why.contains("锁骨上窝"))
    }

    @Test
    fun `判据二_胖而凹陷都在浅及以下判过`() {
        val p = f.person("甲", FatBand.HIGHER, f.heavyTopo())
        val r = TopoChecks.fatHollow(p)
        assertEquals(TopoVerdict.PASS, r.verdict)
        assertEquals("FAT-HOLLOW-OK", r.code)
    }

    @Test
    fun `判据二_不胖时不启用凹陷规则`() {
        // 偏瘦的人锁骨上窝本来就深：这条规则不许越界咬他（负控制）
        val p = f.person("甲", FatBand.LEAN,
            f.topo(f.hollow(HollowDim.SUPRACLAVICULAR, HollowGrade.DEEP, HollowGrade.DEEP)))
        val r = TopoChecks.fatHollow(p)
        assertEquals(TopoVerdict.PASS, r.verdict)
        assertEquals("FAT-HOLLOW-NA", r.code)
    }

    @Test
    fun `判据二_胖而骨性隆起清楚判红`() {
        val p = f.person("甲", FatBand.HIGHER,
            f.topo(f.relief(ReliefDim.RIBS, ReliefGrade.CLEAR, ReliefGrade.CLEAR)))
        val r = TopoChecks.fatRelief(p)
        assertEquals(TopoVerdict.DENY, r.verdict)
        assertEquals("FAT-RELIEF", r.code)
        assertTrue(r.why.contains("肋骨轮廓 左=清楚 右=清楚"))
        assertTrue(r.why.contains("先被盖住"))
    }

    @Test
    fun `判据二_胖但腹直肌沟与斜方肌坡不算被覆盖`() {
        // 沟是沟、坡是坡：脂多反而把沟撑深，两条都不该被「覆盖」规则咬到（负控制）
        val p = f.person("甲", FatBand.HIGHER, f.topoOn(f.heavyTopo(),
            f.relief(ReliefDim.RECTUS_GROOVE, ReliefGrade.PRONOUNCED, ReliefGrade.PRONOUNCED),
            f.relief(ReliefDim.TRAP_SLOPE, ReliefGrade.PRONOUNCED, ReliefGrade.PRONOUNCED),
        ))
        val r = TopoChecks.fatRelief(p)
        assertEquals(TopoVerdict.PASS, r.verdict)
        assertEquals("FAT-RELIEF-OK", r.code)
        assertTrue("只管三个骨性点", r.why.contains("三处骨性隆起"))
    }

    @Test
    fun `判据二_不胖时不启用覆盖规则`() {
        val p = f.person("甲", FatBand.LEAN,
            f.topo(f.relief(ReliefDim.SCAPULA, ReliefGrade.PRONOUNCED, ReliefGrade.PRONOUNCED)))
        assertEquals("FAT-RELIEF-NA", TopoChecks.fatRelief(p).code)
    }

    @Test
    fun `判据二_大腹常态六条全过`() {
        val p = f.person("甲", FatBand.HIGHER, f.heavyTopo())
        assertEquals("通过 6／提示 0／红 0", TopoChecks.summary(TopoChecks.all(p)))
    }

    // ── ③ 完全对称提示 ──────────────────────────────────────────────

    @Test
    fun `判据三_左右全同档判提示`() {
        val even = f.topo(f.relief(ReliefDim.SCAPULA, ReliefGrade.FAINT, ReliefGrade.FAINT))
        val r = TopoChecks.symmetry(f.person("甲", topo = even))
        assertEquals(TopoVerdict.HINT, r.verdict)
        assertEquals("ASYM-ALL-EVEN", r.code)
        assertTrue(r.why.contains("人几乎没有完全对称"))
    }

    @Test
    fun `判据三_有一对差一档就放行`() {
        val r = TopoChecks.symmetry(f.person("甲"))
        assertEquals(TopoVerdict.PASS, r.verdict)
        assertEquals("ASYM-VARIED", r.code)
        assertTrue(r.why.contains("左右有差的 1 对"))
    }

    @Test
    fun `判据三_中轴不参与对称判断`() {
        // 四条中轴读数两侧同档是硬要求，不能拿它们凑「完全对称」
        val even = f.topo(f.relief(ReliefDim.SCAPULA, ReliefGrade.FAINT, ReliefGrade.FAINT))
        assertEquals(4, even.midline().size)
        assertEquals("中轴四条不进对称判据", 11, even.lateral().size)
        assertTrue(TopoChecks.symmetry(f.person("甲", topo = even)).why.contains("左右 11 对"))
    }

    // ── ④ 常态不对称须给原因 ────────────────────────────────────────

    @Test
    fun `判据四_差两档没原因判红`() {
        val p = f.person("甲", topo = f.topo(
            f.hollow(HollowDim.AXILLA, HollowGrade.SHALLOW, HollowGrade.DEEP)))
        val r = TopoChecks.asymCause(p)
        assertEquals(TopoVerdict.DENY, r.verdict)
        assertEquals("ASYM-NO-CAUSE", r.code)
        assertTrue(r.why.contains("腋窝 左=浅 右=深"))
        assertTrue(r.why.contains("差 2 档"))
        assertTrue("要点名可写的原因", r.why.contains("惯用侧"))
    }

    @Test
    fun `判据四_差两档给了原因就放行`() {
        val p = f.person("甲", topo = f.topo(
            f.hollow(HollowDim.AXILLA, HollowGrade.SHALLOW, HollowGrade.DEEP, AsymCause.OLD_INJURY)))
        val r = TopoChecks.asymCause(p)
        assertEquals(TopoVerdict.PASS, r.verdict)
        assertEquals("ASYM-EXPLAINED", r.code)
        assertTrue(r.why.contains("旧伤"))
    }

    @Test
    fun `判据四_差一档是常态不用原因`() {
        val r = TopoChecks.asymCause(f.person("甲"))
        assertEquals(TopoVerdict.PASS, r.verdict)
        assertEquals("ASYM-WITHIN", r.code)
        assertTrue(r.why.contains("最大差 1 档"))
    }

    @Test
    fun `判据四_从薄跨到厚也算超常态`() {
        val p = f.person("甲", topo = f.topo(
            f.depth(DepthDim.THIGH_FRONT, DepthGrade.THIN, DepthGrade.THICK)))
        val r = TopoChecks.asymCause(p)
        assertEquals(TopoVerdict.DENY, r.verdict)
        assertTrue(r.why.contains("大腿前凸"))
        assertTrue(r.why.contains("差 2 档"))
    }

    // ── ⑤ 厚度与形状正交 ────────────────────────────────────────────

    @Test
    fun `判据五_厚度换遍三档凸凹不动`() {
        val r = TopoChecks.depthOrthogonal(f.person("甲"))
        assertEquals(TopoVerdict.PASS, r.verdict)
        assertEquals("DEPTH-ORTHOGONAL", r.code)
        assertTrue(r.why.contains("厚度换遍 3 档"))
        assertTrue(r.why.contains("厚度≠形状"))
    }

    @Test
    fun `判据五_厚度改了不影响四条判据的结果`() {
        val thin = f.person("甲", topo = f.topo(f.depth(DepthDim.CHEST, DepthGrade.THIN)))
        val thick = f.person("甲", topo = f.topo(f.depth(DepthDim.CHEST, DepthGrade.THICK)))
        val a = TopoChecks.all(thin).filterNot { it.code == "DEPTH-ORTHOGONAL" }
        val b = TopoChecks.all(thick).filterNot { it.code == "DEPTH-ORTHOGONAL" }
        assertEquals("厚度只是厚度：其余判据逐字不变", a.map { it.line() }, b.map { it.line() })
    }

    // ── 汇总与出口 ──────────────────────────────────────────────────

    @Test
    fun `判据顺序固定_六条`() {
        assertEquals(
            listOf("TOPO-COMPLETE", "FAT-HOLLOW-NA", "FAT-RELIEF-NA",
                "ASYM-VARIED", "ASYM-WITHIN", "DEPTH-ORTHOGONAL"),
            TopoChecks.all(f.person("甲")).map { it.code },
        )
    }

    @Test
    fun `取最严_红压提示提示压通过`() {
        val pass = TopoChecks.completeness(f.person("甲"))
        val hint = TopoChecks.symmetry(f.person("甲", topo = f.topo(
            f.relief(ReliefDim.SCAPULA, ReliefGrade.FAINT, ReliefGrade.FAINT))))
        val deny = TopoChecks.fatHollow(f.person("甲", FatBand.HIGHER,
            f.topo(f.hollow(HollowDim.SUPRACLAVICULAR, HollowGrade.DEEP, HollowGrade.DEEP))))
        assertEquals(TopoVerdict.HINT, TopoChecks.worst(listOf(pass, hint)))
        assertEquals(TopoVerdict.DENY, TopoChecks.worst(listOf(pass, hint, deny)))
        assertEquals(TopoVerdict.PASS, TopoChecks.worst(emptyList()))
    }

    @Test
    fun `出口汇总与逐条_无禁词`() {
        val p = f.person("甲", FatBand.HIGHER, f.heavyTopo())
        val text = TopoChecks.sceneLine(p)
        assertTrue(text.startsWith("通过 6／提示 0／红 0"))
        assertTrue(text.contains("TOPO-COMPLETE"))
        assertTrue(BodyPartGuard.measureHit(text) == null)
        assertTrue(BodyPartGuard.diagnosisHits(text).isEmpty())
        assertFalse(text.contains("%"))
    }

    @Test
    fun `结论自带来源与日期`() {
        val r = TopoChecks.completeness(f.person("甲"))
        assertEquals(TOPO_SOURCE, r.source)
        assertEquals(TOPO_AS_OF, r.asOf)
        assertTrue(r.line().endsWith("（" + TOPO_SOURCE + " " + TOPO_AS_OF + "）"))
    }

    @Test
    fun `确定性_两次逐字一致`() {
        val p = f.person("甲", FatBand.HIGHER, f.heavyTopo())
        assertEquals(TopoChecks.all(p).map { it.line() }, TopoChecks.all(p).map { it.line() })
        assertEquals(TopoChecks.sceneLine(p), TopoChecks.sceneLine(p))
    }
}
