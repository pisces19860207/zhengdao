// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批 §7.2／§7.3：下放额度三档、滞回、三条硬边界、额度变动留痕。
//
// §7 的四条负例逐条钉在这里：
// ①通过率一高就顺手放开 R3；②勘验拿模型自述当输入（这条由源码门禁钉，见
//   `InspectReadOnlyGateTest`，这里只钉额度那半边——模型不许自己调档）；
// ③额度变动不留痕；④通过率回升后不恢复额度。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionReview
import com.example.zhengdao.baopu.RiskClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowanceLadderTest {

    private val now = 1_760_000_000_000L

    private fun rate(pct: Double, judged: Int = 40) =
        PassRate(pct, judged, (pct * judged / 100).toInt(), judged - (pct * judged / 100).toInt(), 30)

    private fun review(recordId: String, verdict: String, atMs: Long = now) = DecisionReview.Entry(
        recordId = recordId,
        kind = DecisionKind.AUTO_ACT.wire,
        verdict = verdict,
        actionSummary = "免问直行：file_write",
        matrixVersion = "1",
        reasonCode = DecisionKind.AUTO_ACT.wire,
        evidence = "tool:file_write:a.txt",
        atMs = atMs,
    )

    // ── §7.2 三档与滞回 ─────────────────────────────────────────────────────

    @Test
    fun `额度_三档阈值就是派工单那两个数`() {
        assertEquals(95.0, AllowanceLadder.RAISE_PCT, 0.0)
        assertEquals(85.0, AllowanceLadder.REVOKE_PCT, 0.0)
        assertEquals(AllowanceBand.HELD, AllowanceBand.DEFAULT)
    }

    @Test
    fun `额度_九十五以上才放`() {
        assertEquals(AllowanceBand.RAISED, AllowanceLadder.nextBand(AllowanceBand.HELD, rate(95.0)))
        assertEquals(AllowanceBand.HELD, AllowanceLadder.nextBand(AllowanceBand.HELD, rate(94.9)))
    }

    @Test
    fun `额度_跌破八十五即收`() {
        assertEquals(AllowanceBand.REVOKED, AllowanceLadder.nextBand(AllowanceBand.HELD, rate(84.9)))
        assertEquals(AllowanceBand.HELD, AllowanceLadder.nextBand(AllowanceBand.HELD, rate(85.0)))
    }

    @Test
    fun `额度_负例④回升到八十多不恢复_要回到九十五`() {
        // 85–95 这一段是"维持"带：已经收回的档不许在这里偷偷放回去
        assertEquals(AllowanceBand.REVOKED, AllowanceLadder.nextBand(AllowanceBand.REVOKED, rate(90.0)))
        assertEquals(AllowanceBand.HELD, AllowanceLadder.nextBand(AllowanceBand.HELD, rate(90.0)))
        // 只有真的回到 95% 才恢复
        assertEquals(AllowanceBand.RAISED, AllowanceLadder.nextBand(AllowanceBand.REVOKED, rate(96.0)))
    }

    @Test
    fun `额度_算不出不动档`() {
        val unknown = PassRate(PassRate.UNKNOWN_PCT, 0, 0, 0, 30)
        assertEquals(AllowanceBand.RAISED, AllowanceLadder.nextBand(AllowanceBand.RAISED, unknown))
        assertEquals(AllowanceBand.REVOKED, AllowanceLadder.nextBand(AllowanceBand.REVOKED, unknown))
        // 样本不够（<20 条）同样算不出，哪怕百分比好看
        assertFalse(rate(99.0, judged = 5).known)
        assertEquals(AllowanceBand.HELD, AllowanceLadder.nextBand(AllowanceBand.HELD, rate(99.0, judged = 5)))
    }

    @Test
    fun `通过率_只数判过的_改判以最后一条为准`() {
        val entries = listOf(
            review("r1", DecisionRecord.VERDICT_WRONG, now - 1000),
            review("r1", DecisionRecord.VERDICT_CONFIRMED, now),
            review("r2", DecisionRecord.VERDICT_CONFIRMED),
            review("r3", "还没判"),
        )
        val p = PassRate.of(entries, now)
        assertEquals(2, p.judged)
        assertEquals(2, p.confirmed)
        assertEquals(0, p.wrong)
        assertEquals(100.0, p.pct, 0.0)
        assertEquals(PassRate.UNKNOWN_PCT, PassRate.of(emptyList(), now).pct, 0.0)
    }

    @Test
    fun `通过率_窗口外的老账不算`() {
        val old = review("r1", DecisionRecord.VERDICT_CONFIRMED, now - 31L * 86_400_000L)
        assertEquals(0, PassRate.of(listOf(old), now).judged)
    }

    @Test
    fun `额度读数_一句话且有界`() {
        val line = AllowanceLadder.readout(rate(96.0), AllowanceBand.HELD, AllowanceBand.RAISED)
        assertTrue(line.contains("勘验通过率") && line.contains("放"))
        assertTrue(line.length <= InspectLimits.MAX_LINE_CHARS)
        assertTrue(AllowanceLadder.readout(PassRate.of(emptyList(), now), AllowanceBand.HELD, AllowanceBand.HELD).contains("无账"))
    }

    // ── §7.3 三条硬边界 ─────────────────────────────────────────────────────

    @Test
    fun `硬边界①_R3永不自动_放档也不松`() {
        val f = AllowanceLadder.AskFacts(tool = "browser_click", risk = RiskClass.R3, reversible = false)
        assertEquals(AllowanceLadder.Verdict.ASK, AllowanceLadder.verdictOf(f, AllowanceBand.RAISED))
        assertEquals(AllowanceLadder.Verdict.ASK, AllowanceLadder.verdictOf(f, AllowanceBand.HELD))
    }

    @Test
    fun `硬边界②_C类永不自改_与风险档无关`() {
        val f = AllowanceLadder.AskFacts(tool = "skill_write", risk = RiskClass.R1, cClass = true)
        assertEquals(AllowanceLadder.Verdict.ASK, AllowanceLadder.verdictOf(f, AllowanceBand.RAISED))
        // C 类里连 R0 都问：这条判的是"改的是不是规则本身"
        assertEquals(
            AllowanceLadder.Verdict.ASK,
            AllowanceLadder.verdictOf(f.copy(risk = RiskClass.R0), AllowanceBand.RAISED),
        )
    }

    @Test
    fun `硬边界③_219的必问与硬拒不被额度推翻`() {
        val mustAsk = AllowanceLadder.AskFacts(risk = RiskClass.R1, mustAskByRule = true)
        assertEquals(AllowanceLadder.Verdict.ASK, AllowanceLadder.verdictOf(mustAsk, AllowanceBand.RAISED))
        val deny = AllowanceLadder.AskFacts(risk = RiskClass.R1, hardDeny = true)
        assertEquals(AllowanceLadder.Verdict.DENIED, AllowanceLadder.verdictOf(deny, AllowanceBand.REVOKED))
    }

    @Test
    fun `额度_收回档把R1也恢复必问_R0不多收`() {
        // 派工单原文只写「R1 也恢复必问」：R0（读/列目录/搜索）不在额度讨论里
        assertEquals(
            AllowanceLadder.Verdict.SILENT,
            AllowanceLadder.verdictOf(AllowanceLadder.AskFacts(risk = RiskClass.R0), AllowanceBand.REVOKED),
        )
        assertEquals(
            AllowanceLadder.Verdict.ASK,
            AllowanceLadder.verdictOf(AllowanceLadder.AskFacts(risk = RiskClass.R1), AllowanceBand.REVOKED),
        )
    }

    @Test
    fun `额度_维持档只免问R0R1`() {
        assertEquals(
            AllowanceLadder.Verdict.SILENT,
            AllowanceLadder.verdictOf(AllowanceLadder.AskFacts(risk = RiskClass.R1), AllowanceBand.HELD),
        )
        assertEquals(
            AllowanceLadder.Verdict.ASK,
            AllowanceLadder.verdictOf(AllowanceLadder.AskFacts(risk = RiskClass.R2), AllowanceBand.HELD),
        )
    }

    @Test
    fun `额度_没判过档的一律问`() {
        assertEquals(
            AllowanceLadder.Verdict.ASK,
            AllowanceLadder.verdictOf(AllowanceLadder.AskFacts(risk = null), AllowanceBand.RAISED),
        )
    }

    // ── §9 那半边：影子与放行的先后 ─────────────────────────────────────────

    @Test
    fun `影子_不一致一律问_没跑也问`() {
        val r2 = AllowanceLadder.AskFacts(risk = RiskClass.R2, lowRiskR2Subclass = true,
            insideWorkspace = true, reversible = true, rollbackPointReady = true, patrolAvailable = true)
        assertEquals(
            AllowanceLadder.Verdict.ASK,
            AllowanceLadder.verdictOf(r2.copy(shadow = ShadowVerdict.MISMATCH), AllowanceBand.RAISED),
        )
        assertEquals(
            AllowanceLadder.Verdict.ASK,
            AllowanceLadder.verdictOf(r2.copy(shadow = null), AllowanceBand.RAISED),
        )
        assertEquals(
            AllowanceLadder.Verdict.ASK,
            AllowanceLadder.verdictOf(r2.copy(shadow = ShadowVerdict.UNVERIFIABLE), AllowanceBand.RAISED),
        )
        // 三件事全对才免问
        assertEquals(
            AllowanceLadder.Verdict.SILENT,
            AllowanceLadder.verdictOf(r2.copy(shadow = ShadowVerdict.CONSISTENT), AllowanceBand.RAISED),
        )
    }

    @Test
    fun `R2低危子类_四个前提少一个就问`() {
        val f = AllowanceLadder.AskFacts(
            risk = RiskClass.R2, lowRiskR2Subclass = true, insideWorkspace = true,
            reversible = true, rollbackPointReady = true, patrolAvailable = true,
            shadow = ShadowVerdict.CONSISTENT,
        )
        assertTrue(AllowanceLadder.r2SubclassSilent(f))
        listOf(
            f.copy(lowRiskR2Subclass = false),
            f.copy(insideWorkspace = false),
            f.copy(reversible = false),
            f.copy(rollbackPointReady = false),
            f.copy(patrolAvailable = false),
        ).forEach { bad ->
            assertEquals(AllowanceLadder.Verdict.ASK, AllowanceLadder.verdictOf(bad, AllowanceBand.RAISED))
        }
    }

    @Test
    fun `R2子类_非子类不免问_即使R2低危也不白送`() {
        val f = AllowanceLadder.AskFacts(risk = RiskClass.R2, lowRiskR2Subclass = false,
            shadow = ShadowVerdict.CONSISTENT, patrolAvailable = true, rollbackPointReady = true)
        assertEquals(AllowanceLadder.Verdict.ASK, AllowanceLadder.verdictOf(f, AllowanceBand.RAISED))
    }

    // ── §7.2 留痕：谁调的、依据哪次勘验、可回滚 ─────────────────────────────

    private fun change(
        actor: String = DecisionActor.AUTO.wire,
        basis: String = "patrol:$now",
        pct: Double = 96.0,
        before: AllowanceBand = AllowanceBand.HELD,
        after: AllowanceBand = AllowanceBand.RAISED,
    ) = AllowanceLadder.AllowanceChange(
        id = "allow:$now", atMs = now, actor = actor, basisPatrolId = basis,
        passRatePct = pct, samples = 40, before = before, after = after,
    )

    @Test
    fun `额度_负例②模型自调一律拒`() {
        assertNotNull(AllowanceLadder.refusalOf(change(actor = DecisionActor.MODEL.wire)))
        assertNull(AllowanceLadder.refusalOf(change(actor = DecisionActor.USER.wire)))
        assertNull(AllowanceLadder.refusalOf(change()))
    }

    @Test
    fun `额度_负例③没有依据或档没动都不落账`() {
        assertTrue(AllowanceLadder.refusalOf(change(basis = "")).orEmpty().contains("依据"))
        assertNotNull(AllowanceLadder.refusalOf(change(before = AllowanceBand.HELD, after = AllowanceBand.HELD)))
        assertNotNull(AllowanceLadder.refusalOf(change(pct = PassRate.UNKNOWN_PCT)))
        // 拒了的绝无草稿（不给"先落账再说"留口子）
        assertNull(AllowanceLadder.draftOf(change(basis = "")))
    }

    @Test
    fun `额度_草稿复用tool_grant且带得回滚`() {
        val draft = AllowanceLadder.draftOf(change())
        assertNotNull(draft)
        assertEquals(DecisionKind.TOOL_GRANT, draft!!.kind)
        assertEquals(DecisionActor.AUTO, draft.actor)
        assertTrue(draft.evidence.startsWith("${AllowanceLadder.EVIDENCE_PREFIX}raised:patrol:"))
        assertEquals(AllowanceBand.HELD.wire, draft.before)
        assertEquals(AllowanceBand.RAISED.wire, draft.after)
        // 撤销钮拿 rollback_ref 退回上一档
        assertEquals("patrol:$now", draft.rollbackRef)
        assertTrue(draft.reason.contains("勘验"))
    }

    @Test
    fun `额度_档的真相只有账本一份`() {
        // 写的房型与读的口必须互为逆运算，否则"有账读不出"就是第二套口径
        val draft = AllowanceLadder.draftOf(change())!!
        assertEquals(AllowanceBand.RAISED, PatrolSources.bandOfEvidence(draft.evidence))
    }
}
