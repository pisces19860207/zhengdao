// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百九十批 · 形轨.方位可达：功能测试。锚点全部来自 fixture 实跑读数（660 夹具＋655 内核）。
// 只读 docs/facts 三件，零写盘、零临时目录（水位口径）。
package com.example.zhengdao.baopu.state.bearing

import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.geometry.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BearingReachTest {

    private val body: BodyState by lazy { FormFixtures.state() }

    private fun judge(
        zoneId: String,
        bearing: Bearing6?,
        distance: DistanceTier?,
        subzone: String? = null,
    ): BearingOutcome = BearingReach.judge(body, Environment(0.0, 0.0), zoneId, subzone, bearing, distance)

    private fun judged(zoneId: String, bearing: Bearing6, distance: DistanceTier, subzone: String? = null): BearingVerdict {
        val out = judge(zoneId, bearing, distance, subzone)
        assertTrue("期望已判（七项齐），得 incomplete：$out", out is BearingOutcome.Judged)
        return (out as BearingOutcome.Judged).verdict
    }

    private fun incompleteOf(zoneId: String, bearing: Bearing6?, distance: DistanceTier?, subzone: String? = null): BearingIncomplete {
        val out = judge(zoneId, bearing, distance, subzone)
        assertTrue("期望 incomplete，得已判：$out", out is BearingOutcome.Incomplete)
        return (out as BearingOutcome.Incomplete).incomplete
    }

    // ── 验收①：同区同距离只改方位，结论必须能变 ────────────────────────────

    @Test
    fun 验收一_同区把方位从前改成后_结论必须能不同() {
        val front = judged("glans", Bearing6.FRONT, DistanceTier.CONTACT)
        val back = judged("glans", Bearing6.BACK, DistanceTier.CONTACT)
        assertEquals(BearingReachState.REACHABLE, front.state)
        assertEquals(BearingReachState.UNREACHABLE, back.state)
        assertTrue("unreachable 必带 blockedBy", back.path.blockedBy.isNotEmpty())
    }

    @Test
    fun 验收一_龟头接触档六方位结论不止一种() {
        val states = Bearing6.entries.map { judged("glans", it, DistanceTier.CONTACT).state }
        assertTrue("六方位结论全相同＝没判方位（判据①）：$states", states.distinct().size >= 2)
    }

    @Test
    fun 验收一_柱身与睾丸方位敏感性_前后结论不同() {
        val shaftF = judged("shaft", Bearing6.FRONT, DistanceTier.CONTACT)
        val shaftU = judged("shaft", Bearing6.UP, DistanceTier.CONTACT)
        assertTrue("柱身前后结论须不同：${shaftF.state}/${shaftU.state}", shaftF.state != shaftU.state)
        val testisF = judged("testis", Bearing6.FRONT, DistanceTier.CONTACT)
        val testisB = judged("testis", Bearing6.BACK, DistanceTier.CONTACT)
        assertTrue("睾丸前后结论须不同：${testisF.state}/${testisB.state}", testisF.state != testisB.state)
    }

    @Test
    fun 验收一_距离轴有牙_远档把够得着变成跨距卡死() {
        val contact = judged("glans", Bearing6.FRONT, DistanceTier.CONTACT)
        val farDown = judged("glans", Bearing6.DOWN, DistanceTier.FAR)
        assertEquals(BearingReachState.REACHABLE, contact.state)
        assertEquals(BearingReachState.UNREACHABLE, farDown.state)
        assertEquals(listOf(BearingReach.REACH_SPAN), farDown.path.blockedBy)
    }

    // ── 判据③：unreachable 必带 blockedBy，取值不出 655 四项＋跨距 ─────────

    @Test
    fun 判据三_unreachable必带blockedBy且取值与655四项一致() {
        val allowed = setOf(
            "LIMIT_EXCEEDED", "SELF_COLLISION", "GROUND_PENETRATION", "SAND_PENETRATION", BearingReach.REACH_SPAN,
        )
        val cases = listOf(
            Triple("glans", Bearing6.BACK, DistanceTier.CONTACT),
            Triple("shaft", Bearing6.FRONT, DistanceTier.CONTACT),
            Triple("shaft", Bearing6.BACK, DistanceTier.CONTACT),
            Triple("testis", Bearing6.BACK, DistanceTier.CONTACT),
            Triple("glans", Bearing6.DOWN, DistanceTier.FAR),
        )
        for ((zone, b, t) in cases) {
            val v = judged(zone, b, t)
            if (v.state == BearingReachState.UNREACHABLE) {
                assertTrue("$zone/$b/$t unreachable 缺 blockedBy", v.path.blockedBy.isNotEmpty())
                assertTrue(
                    "$zone/$b/$t blockedBy 出闭集：${v.path.blockedBy}",
                    v.path.blockedBy.all { it in allowed },
                )
                assertTrue("$zone/$b/$t 路径必须记不可行", !v.path.feasible)
            }
        }
    }

    @Test
    fun 判据三_卡在哪要点名段与区() {
        val back = judged("glans", Bearing6.BACK, DistanceTier.CONTACT)
        assertEquals(BearingReachState.UNREACHABLE, back.state)
        assertTrue("证据注须点名卡在哪（段×区）", back.evidenceNote.contains("hand_") && back.evidenceNote.contains("SELF_COLLISION"))
    }

    // ── 判据②：三轴缺一即 incomplete，缺哪个点哪个 ─────────────────────────

    @Test
    fun 判据二_缺方位即incomplete并点名() {
        val inc = incompleteOf("glans", null, DistanceTier.CONTACT)
        assertTrue(inc.missing.first().startsWith("方位"))
    }

    @Test
    fun 判据二_缺距离即incomplete并点名() {
        val inc = incompleteOf("glans", Bearing6.FRONT, null)
        assertTrue(inc.missing.first().startsWith("距离"))
    }

    @Test
    fun 判据二_目标区悬空即incomplete并点名() {
        val inc = incompleteOf("no_such_zone", Bearing6.FRONT, DistanceTier.CONTACT)
        assertTrue(inc.missing.first().startsWith("目标区"))
    }

    @Test
    fun 判据二_多轴缺按固定序全列出() {
        val inc = incompleteOf("glans", null, null)
        assertEquals(2, inc.missing.size)
        assertTrue(inc.missing[0].startsWith("方位") && inc.missing[1].startsWith("距离"))
    }

    // ── 判据④⑤：granularity 与子区缝 ──────────────────────────────────────

    @Test
    fun 判据四_只给区不给子区_降granularity为zone并标原因() {
        val v = judged("glans", Bearing6.FRONT, DistanceTier.CONTACT)
        assertEquals(BearingGranularity.ZONE, v.granularity)
        assertTrue("须标原因（未给子区）", v.granularityReason.contains("未给子区"))
    }

    @Test
    fun 判据五_请求子区即incomplete_不假装判到子区() {
        val inc = incompleteOf("glans", Bearing6.FRONT, DistanceTier.CONTACT, "背侧")
        assertTrue("缺轴须点名子区", inc.missing.first().startsWith("子区"))
        assertTrue("须点名 775 未落地", inc.missing.first().contains("775"))
        assertTrue(inc.missing.first().contains("背侧"))
    }

    @Test
    fun 判据五_raphe按区连续段判_不另开区() {
        val v = judged("raphe", Bearing6.FRONT, DistanceTier.CONTACT)
        assertEquals(BearingGranularity.ZONE, v.granularity)
        assertEquals("raphe.mid", v.zoneInstance)
    }

    // ── 判据⑦：确定性（同输入同结论，逐字节） ──────────────────────────────

    @Test
    fun 判据七_同输入两次判定_结论与toString逐字节一致() {
        val a = judged("glans", Bearing6.BACK, DistanceTier.CONTACT)
        val b = judged("glans", Bearing6.BACK, DistanceTier.CONTACT)
        assertEquals(a, b)
        assertEquals(a.toString(), b.toString())
    }

    @Test
    fun 判据七_六方位整表两次判定_逐字节一致() {
        val first = Bearing6.entries.map { judged("glans", it, DistanceTier.CONTACT) }
        val second = Bearing6.entries.map { judged("glans", it, DistanceTier.CONTACT) }
        assertEquals(first, second)
        assertEquals(first.map { it.toString() }, second.map { it.toString() })
    }

    // ── 七项输出的结构闭集 ─────────────────────────────────────────────────

    @Test
    fun 七项_jointTiers五键齐全档位在一到五() {
        val v = judged("glans", Bearing6.FRONT, DistanceTier.CONTACT)
        val map = v.jointTiers.asMap()
        assertEquals(listOf("shoulder", "elbow", "wrist", "hip", "knee"), map.keys.toList())
        assertTrue(map.values.all { it in 1..5 })
    }

    @Test
    fun 七项_baseline固定fixture并写明真形体归699() {
        val v = judged("glans", Bearing6.FRONT, DistanceTier.CONTACT)
        assertEquals(BearingBaseline.FIXTURE, v.baseline)
        assertTrue(v.baselineReason.contains("660"))
        assertTrue(v.baselineReason.contains("699"))
    }

    @Test
    fun 七项_notThisOrder三项闭集() {
        val v = judged("glans", Bearing6.FRONT, DistanceTier.CONTACT)
        assertEquals(
            listOf(NotThisOrder.ENTRY_779, NotThisOrder.CONTAINMENT_741_795, NotThisOrder.PENETRATION_686),
            v.notThisOrder,
        )
    }

    @Test
    fun 七项_双实例区按实例判且取更优侧() {
        val v = judged("testis", Bearing6.DOWN, DistanceTier.CONTACT)
        assertTrue("睾丸须判到双实例之一：${v.zoneInstance}", v.zoneInstance.startsWith("testis."))
        assertTrue(v.usedArm in setOf("l", "r"))
        assertNotNull(v.samplesTried)
        assertTrue(v.samplesTried >= BearingGeometry.SAMPLES)
    }

    @Test
    fun 七项_输出类型零浮点_证据只有整数毫米与档() {
        val v = judged("shaft", Bearing6.LEFT, DistanceTier.CONTACT)
        assertTrue(v.bestGapMm >= 0)
        assertTrue(v.jointTiers.asMap().values.all { it in 1..5 })
        assertTrue(v.assumption.contains("工程假设○"))
        assertEquals("模拟非测量", v.note)
    }
}
