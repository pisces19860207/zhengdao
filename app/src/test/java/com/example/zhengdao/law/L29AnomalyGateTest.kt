// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十三批 · 法典门禁 **L29 灾异定义**（法典卷十 L29；316 对账时是五条真缺口之一，本批落地）。
//
// 判据落进生产 `baopu/law/AnomalyGate.kt`（四类阈值写死成常量）；「判定为灾异 ⇒ 必须进紧急通道」
// 的口径复用 315 的 EmergencyGate／EmergencyWiring（不许复制一套紧急机制）。
//
// ## 判据（可执行，判的是生产 AnomalyGate＋EmergencyGate＋EmergencyWiring）
// 四类（数据损毁／外发泄露／权限失控／大面积不可用）命中任一**且不可局部回滚**且**有证据行**
// ⇒ 灾异；灾异必须按 L21 紧急卡上报——当普通动作送进来 ⇒ 拒，缺什么点名什么。
//
// ## 负例（造违规即红，红在生产判定上）
// 灾异被当普通卡（缺标记／缺致命级／缺 L21）⇒ normalChannelRejection 必须拦；
// 没有证据或可局部回滚 ⇒ 不许判成灾异（紧急权不许被"感觉很糟"贬值）。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.EMERGENCY_MARKER
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.Source
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.isEmergencyCard
import com.example.zhengdao.baopu.inspect.requiresApproval
import com.example.zhengdao.baopu.inspect.selfRulingRejection
import com.example.zhengdao.baopu.law.AnomalyFacts
import com.example.zhengdao.baopu.law.AnomalyGate
import com.example.zhengdao.baopu.law.AnomalyKind
import com.example.zhengdao.baopu.law.EmergencyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L29AnomalyGateTest {

    /** 证据行（机器键形状，与 Opinion.evidence 同一套）。 */
    private val evidence = listOf("evidence:destroyed=7", "runlog:2026-10-31T10:00:00")

    private fun facts(
        destroyed: Int = 0,
        leaked: Int = 0,
        runaway: Int = 0,
        outageFails: Int = 0,
        outageMs: Long = 0L,
        reversible: Boolean = false,
        lines: List<String> = evidence,
    ) = AnomalyFacts(
        destroyedItems = destroyed,
        leakedItems = leaked,
        runawayGrants = runaway,
        outageConsecutiveFails = outageFails,
        outageMs = outageMs,
        locallyReversible = reversible,
        evidenceLines = lines,
    )

    /** 灾异按紧急卡上报后的那张 Opinion（生产接线层的形状，315 建）。 */
    private fun escalated() = Opinion(
        source = Source.CLASSIFIER,
        severity = Severity.CRITICAL,
        gist = "数据损毁",
        action = ActionCode.SUGGEST_PAUSE,
        lawRef = LawRef.L21,
        evidence = listOf(EMERGENCY_MARKER, "evidence:destroyed=7"),
    )

    // ── 正例：四类命中任一且不可回滚且有证据 ⇒ 灾异，必须进紧急通道 ──────────────

    @Test
    fun `L29_正例_四类命中判成灾异且必须以紧急卡上报`() {
        // ①数据损毁：不可逆 ≥1 条即成（不可逆本身就是量词）
        val destroyed = AnomalyGate.classify(facts(destroyed = 1))
        assertEquals(AnomalyKind.DATA_DESTROYED, destroyed.kind)
        assertTrue(destroyed.isAnomaly)
        // ②外发泄露：流出 ≥1 条即成
        val leak = AnomalyGate.classify(facts(leaked = 1))
        assertEquals(AnomalyKind.LEAK, leak.kind)
        assertTrue(leak.isAnomaly)
        // ③权限失控：≥3 次且还在发生
        val runaway = AnomalyGate.classify(facts(runaway = 3))
        assertEquals(AnomalyKind.GRANT_RUNAWAY, runaway.kind)
        assertTrue(runaway.isAnomaly)
        // ④大面积不可用：连续失败 ≥5 次，或持续 ≥10 分钟——两档命中任一即成
        val outageByFails = AnomalyGate.classify(facts(outageFails = 5))
        assertEquals(AnomalyKind.OUTAGE, outageByFails.kind)
        val outageByTime = AnomalyGate.classify(facts(outageFails = 1, outageMs = AnomalyGate.OUTAGE_MS))
        assertEquals(AnomalyKind.OUTAGE, outageByTime.kind)
        // 四类都必须要求进紧急通道
        listOf(destroyed, leak, runaway, outageByFails).forEach { verdict ->
            assertTrue("灾异必须要求按紧急卡上报（${verdict.kind}）", verdict.mustRaiseEmergencyCard)
            assertTrue("怎么报要点名 L21（${verdict.kind}）", verdict.howToReport.contains("L21"))
        }
        // 进紧急通道的实感：上报后的卡在生产接线层必须是"人批"，自决路径撞上必拒
        //（口径唯一出自 EmergencyGate／EmergencyWiring——315 的机制，本门禁不复制）
        val card = escalated()
        assertTrue("三样齐了就是紧急卡（EmergencyGate 口径）", card.isEmergencyCard)
        assertTrue("灾异的紧急卡必须人批", card.requiresApproval)
        assertNotNull("自决路径撞上灾异卡必须拒", card.selfRulingRejection())
    }

    @Test
    fun `L29_正例_阈值差一点不算灾异_边界写在常量上`() {
        // 阈值写死可测：差一点都不行——这就是"改阈值须御批"的实感
        assertNull("4 次连续失败不算大面积不可用", AnomalyGate.classify(facts(outageFails = 4)).kind)
        assertNull("2 次越权不算权限失控", AnomalyGate.classify(facts(runaway = 2)).kind)
        assertNull("差一毫秒不算持续 10 分钟", AnomalyGate.classify(facts(outageMs = AnomalyGate.OUTAGE_MS - 1)).kind)
        assertEquals("到线即成（次数档）", AnomalyKind.OUTAGE, AnomalyGate.classify(facts(outageFails = 5)).kind)
        assertEquals("到线即成（时长档）", AnomalyKind.OUTAGE, AnomalyGate.classify(facts(outageMs = AnomalyGate.OUTAGE_MS)).kind)
    }

    // ── 负例：当普通动作放过／没证据／可回滚，都不行 ────────────────────────────

    @Test
    fun `L29_负例_灾异被当普通动作送进来必须拦下且缺什么点名什么`() {
        // 灾异事实在手，却按普通意见走（无标记／非致命／非 L21）——三种缺样都要被拦
        val none = AnomalyGate.normalChannelRejection(facts(destroyed = 2), hasMarker = false, severityIsCritical = false, lawRefIsL21 = false)
        assertNotNull("灾异走普通通道必须拦", none)
        assertTrue("拒因要点名标记", none!!.contains("EMERGENCY"))
        assertTrue("拒因要点名致命级", none.contains("致命级"))
        assertTrue("拒因要点名 L21", none.contains("L21"))

        val noMarkerOnly = AnomalyGate.normalChannelRejection(facts(destroyed = 2), hasMarker = false, severityIsCritical = true, lawRefIsL21 = true)
        assertNotNull("缺标记也一样拦", noMarkerOnly)
        val noCritical = AnomalyGate.normalChannelRejection(facts(destroyed = 2), hasMarker = true, severityIsCritical = false, lawRefIsL21 = true)
        assertNotNull("不是致命级也一样拦", noCritical)
        val notL21 = AnomalyGate.normalChannelRejection(facts(destroyed = 2), hasMarker = true, severityIsCritical = true, lawRefIsL21 = false)
        assertNotNull("不挂 L21 也一样拦", notL21)
        assertEquals("缺样不同、拒因各不相同（不许一句话糊弄）", 4, setOf(none, noMarkerOnly, noCritical, notL21).size)

        // 真按紧急卡上报了 ⇒ 放行进紧急通道（拒因消失）
        assertNull(
            "按 L21 紧急卡上报的灾异不再拦",
            AnomalyGate.normalChannelRejection(facts(destroyed = 2), hasMarker = true, severityIsCritical = true, lawRefIsL21 = true),
        )
    }

    @Test
    fun `L29_负例_没证据或可局部回滚都不许判成灾异`() {
        // 没有证据行：不许凭"感觉很糟"判灾异——紧急权不许被贬值
        val noEvidence = AnomalyGate.classify(facts(destroyed = 3, lines = emptyList()))
        assertFalse("没有证据不许判灾异", noEvidence.isAnomaly)
        assertFalse("证据不算数 ⇒ 也不许拦普通通道", noEvidence.evidenceStated)
        assertNull(
            "没证据的『灾异』拦不得普通动作",
            AnomalyGate.normalChannelRejection(facts(destroyed = 3, lines = emptyList()), hasMarker = false, severityIsCritical = false, lawRefIsL21 = false),
        )
        // 证据是空话（空白行）同样不算——EmergencyGate.reasonStated 的复数口径
        val blankEvidence = AnomalyGate.classify(facts(destroyed = 3, lines = listOf("  ")))
        assertFalse("空白证据不算说清", blankEvidence.isAnomaly)
        // 可局部回滚：走普通通道照样追责，不抬进紧急通道
        val reversible = AnomalyGate.classify(facts(destroyed = 3, reversible = true))
        assertFalse("可局部回滚的不算灾异", reversible.isAnomaly)
        // 一类都没命中：更不算
        val quiet = AnomalyGate.classify(facts())
        assertNull("四类都不命中 ⇒ 没有灾异", quiet.kind)
        assertFalse(quiet.isAnomaly)
    }
}
