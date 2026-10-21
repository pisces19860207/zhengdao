// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批 §6 G1–G3：**回灌决策口**。
// 只加了一条窄规则：这条技能采纳后已被判变差 ⇒ 后续同类候选先搁置。
// 其余判定一个字不动——所以 G2 是回归钉子，G3 钉审计里那一格键恒在。
package com.example.zhengdao.baopu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostAdoptionDecisionTest {

    private fun input(post: PostAdoptionEffect?) = EvolutionDecisionInput(
        candidateId = "cand-1",
        kind = ProposalKind.SKILL_BODY,
        riskCategory = EvolutionRiskCategory.SKILL_BODY_STRUCTURE,
        evidenceIds = listOf("run_log:1", "skill_event:2"),
        selfCheck = SelfCheckEvidence(
            passed = true,
            rollbackAvailable = true,
            caseIds = listOf("selfcheck:structure"),
        ),
        eval = EvolutionEval(score = 0.9, caseIds = listOf("eval:1")),
        effect = EffectSnapshot(successes = 3, failures = 0),
        postAdoption = post,
    )

    private fun negative() = PostAdoptionEffect(
        eventId = "adopt-e1-1000",
        verdict = PostAdoptionVerdict.ROLLBACK,
        failures = 3,
    )

    @Test
    fun `G1_负向回灌使后续候选DEFER且带POST_ADOPTION_NEGATIVE`() {
        val judged = EvolutionPolicy().judge(input(negative()))
        assertEquals(EvolutionRecommendation.DEFER, judged.recommendation)
        assertTrue(
            judged.reasons.any { it.code == EvolutionReasonCode.POST_ADOPTION_NEGATIVE },
        )
        val reason = judged.reasons.first { it.code == EvolutionReasonCode.POST_ADOPTION_NEGATIVE }
        assertEquals(listOf("adoption:adopt-e1-1000"), reason.evidenceIds)
        assertTrue("回灌只改建议，不改人批口径", judged.requiresApproval)
    }

    @Test
    fun `G2_postAdoption为null时判定与八十八批一致`() {
        val judged = EvolutionPolicy().judge(input(null))
        assertEquals(EvolutionRecommendation.ADOPT, judged.recommendation)
        assertTrue(judged.requiresApproval) // 中风险固定人批
        assertTrue(judged.reasons.none { it.code == EvolutionReasonCode.POST_ADOPTION_NEGATIVE })
    }

    @Test
    fun `G3_审计行含post_adoption键且null时是JSONnull`() {
        val withPost = EvolutionAuditEntry(
            atMs = 1L,
            operatorId = "settle",
            policy = EvolutionPolicySnapshot(l3AutoLowRisk = false, evalFloor = 0.8, minEffectSamples = 3),
            input = input(negative()),
            decision = EvolutionDecision(
                candidateId = "cand-1",
                recommendation = EvolutionRecommendation.DEFER,
                confidence = 0.5,
                riskClass = EvolutionRisk.MEDIUM,
                requiresApproval = true,
                reasons = listOf(
                    EvolutionReason(EvolutionReasonCode.POST_ADOPTION_NEGATIVE, listOf("adoption:adopt-e1-1000")),
                ),
            ),
        )
        val json = withPost.toJson()
        assertTrue("键恒在", json.has("post_adoption"))
        val post = json.get("post_adoption") as JSONObject
        assertEquals("adopt-e1-1000", post.getString("event_id"))
        assertEquals("ROLLBACK", post.getString("verdict"))
        assertEquals(3, post.getInt("failures"))

        val withoutPost = withPost.copy(input = input(null))
        assertTrue("看过了、没有 ⇒ null（与老版本缺键区分）", withoutPost.toJson().isNull("post_adoption"))
        assertFalse(withoutPost.toJson().getBoolean("requires_approval").not())
    }

    @Test
    fun `G4_KEEP不降级_只影响变差那一条`() {
        val keep = PostAdoptionEffect(
            eventId = "adopt-e2-2000",
            verdict = PostAdoptionVerdict.KEEP,
            successes = 4,
        )
        val judged = EvolutionPolicy().judge(input(keep))
        assertEquals(EvolutionRecommendation.ADOPT, judged.recommendation)
    }
}
