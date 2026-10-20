// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十八批 §3：风险映射、建议链、置信度、L3 开关（关／开／条件不足／全满足）。
// 纯 JVM，不碰 Android/DB/UI。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionDecisionPolicyTest {

    private fun okCheck(caseIds: List<String> = listOf("selfcheck:case-1")) =
        SelfCheckEvidence(passed = true, rollbackAvailable = true, caseIds = caseIds)

    private fun badCheck() = SelfCheckEvidence(
        passed = false,
        rollbackAvailable = true,
        caseIds = listOf("selfcheck:case-1"),
        failedRuleIds = listOf("selfcheck:rule-9"),
    )

    private fun noRollbackCheck() = SelfCheckEvidence(
        passed = true,
        rollbackAvailable = false,
        caseIds = listOf("selfcheck:case-2"),
    )

    private fun goodEval() = EvolutionEval(score = 0.9, caseIds = listOf("eval:case-1"))

    private fun lowEval() = EvolutionEval(score = 0.5, caseIds = listOf("eval:case-2"))

    private fun goodEffect() = EffectSnapshot(
        successes = 3,
        failures = 0,
        corrections = 0,
        retries = 1,
        signalIds = listOf("skill_event:11"),
    )

    private fun weakEffect() = EffectSnapshot(successes = 1, signalIds = listOf("skill_event:12"))

    private fun badEffect() = EffectSnapshot(failures = 3, signalIds = listOf("skill_event:13"))

    private fun input(
        risk: EvolutionRiskCategory = EvolutionRiskCategory.SKILL_BODY_MINOR,
        kind: ProposalKind = ProposalKind.SKILL_BODY,
        selfCheck: SelfCheckEvidence? = okCheck(),
        eval: EvolutionEval? = goodEval(),
        effect: EffectSnapshot = goodEffect(),
        evidence: List<String> = listOf("run_log:3", "skill_event:1"),
    ) = EvolutionDecisionInput(
        candidateId = "candidate-1",
        kind = kind,
        riskCategory = risk,
        evidenceIds = evidence,
        selfCheck = selfCheck,
        eval = eval,
        effect = effect,
    )

    // ── 风险映射 ────────────────────────────────────────────────────────────

    @Test
    fun `风险映射_低中高各一例`() {
        assertEquals(EvolutionRisk.LOW, EvolutionRiskCategory.SKILL_BODY_MINOR.riskClass)
        assertEquals(EvolutionRisk.MEDIUM, EvolutionRiskCategory.SKILL_BODY_STRUCTURE.riskClass)
        assertEquals(EvolutionRisk.HIGH, EvolutionRiskCategory.PERSONA_CORE.riskClass)
    }

    @Test
    fun `风险类别全表_高风险三类_中风险两类_低风险两类`() {
        val high = EvolutionRiskCategory.values().filter { it.riskClass == EvolutionRisk.HIGH }.toSet()
        val medium = EvolutionRiskCategory.values().filter { it.riskClass == EvolutionRisk.MEDIUM }.toSet()
        val low = EvolutionRiskCategory.values().filter { it.riskClass == EvolutionRisk.LOW }.toSet()
        assertEquals(
            setOf(
                EvolutionRiskCategory.PERSONA_CORE,
                EvolutionRiskCategory.TOOL_PERMISSION,
                EvolutionRiskCategory.ROOTFS_SYSTEM,
            ),
            high,
        )
        assertEquals(
            setOf(
                EvolutionRiskCategory.SKILL_BODY_STRUCTURE,
                EvolutionRiskCategory.CROSS_SKILL_IMPACT,
            ),
            medium,
        )
        assertEquals(
            setOf(
                EvolutionRiskCategory.PROMPT_TWEAK,
                EvolutionRiskCategory.SKILL_BODY_MINOR,
            ),
            low,
        )
    }

    @Test
    fun `默认风险_86三种kind都保守`() {
        assertEquals(
            EvolutionRisk.HIGH,
            EvolutionRiskCategory.defaultFor(ProposalKind.PERSONA_APPEND).riskClass,
        )
        assertEquals(
            EvolutionRisk.HIGH,
            EvolutionRiskCategory.defaultFor(ProposalKind.TOOL_RULE).riskClass,
        )
        assertEquals(
            EvolutionRisk.MEDIUM,
            EvolutionRiskCategory.defaultFor(ProposalKind.SKILL_BODY).riskClass,
        )
    }

    // ── 人批：高风险／中风险／开关关闭 ──────────────────────────────────────

    @Test
    fun `高风险_开关打开且三条件全满足_仍需人批`() {
        val judgement = EvolutionPolicy(autoLowRiskEnabled = true)
            .judge(input(risk = EvolutionRiskCategory.ROOTFS_SYSTEM))
        assertEquals(EvolutionRecommendation.ADOPT, judgement.recommendation)
        assertEquals(EvolutionRisk.HIGH, judgement.riskClass)
        assertTrue("高风险永远人批", judgement.requiresApproval)
        assertTrue(
            "原因必须写明高风险",
            judgement.reasons.any { it.code == EvolutionReasonCode.HIGH_RISK_ALWAYS_APPROVAL },
        )
        assertFalse(
            "高风险不许出现 L3 免人批原因",
            judgement.reasons.any { it.code == EvolutionReasonCode.L3_AUTO_LOW_RISK_ELIGIBLE },
        )
    }

    @Test
    fun `中风险_开关打开也人批`() {
        val judgement = EvolutionPolicy(autoLowRiskEnabled = true)
            .judge(input(risk = EvolutionRiskCategory.CROSS_SKILL_IMPACT))
        assertEquals(EvolutionRecommendation.ADOPT, judgement.recommendation)
        assertEquals(EvolutionRisk.MEDIUM, judgement.riskClass)
        assertTrue("中风险 v1 固定人批", judgement.requiresApproval)
        assertTrue(
            judgement.reasons.any { it.code == EvolutionReasonCode.MEDIUM_RISK_HUMAN_APPROVAL },
        )
    }

    @Test
    fun `开关关闭_任何类别都人批`() {
        val policy = EvolutionPolicy()
        for (category in EvolutionRiskCategory.values()) {
            val judgement = policy.judge(input(risk = category))
            assertTrue("$category 必须人批", judgement.requiresApproval)
            when (category.riskClass) {
                EvolutionRisk.HIGH -> assertTrue(
                    judgement.reasons.any { it.code == EvolutionReasonCode.HIGH_RISK_ALWAYS_APPROVAL },
                )
                EvolutionRisk.MEDIUM -> assertTrue(
                    judgement.reasons.any { it.code == EvolutionReasonCode.MEDIUM_RISK_HUMAN_APPROVAL },
                )
                EvolutionRisk.LOW -> assertTrue(
                    judgement.reasons.any { it.code == EvolutionReasonCode.LOW_RISK_HUMAN_APPROVAL_DEFAULT },
                )
            }
        }
    }

    // ── 人批：开关打开但条件不足 ────────────────────────────────────────────

    @Test
    fun `开关打开_低风险三条件缺一_仍人批`() {
        val policy = EvolutionPolicy(autoLowRiskEnabled = true)
        val cases = listOf(
            "自检没跑" to input(selfCheck = null),
            "自检失败" to input(selfCheck = badCheck()),
            "eval 没跑" to input(eval = null),
            "eval 未过线" to input(eval = lowEval()),
            "回滚不可用" to input(selfCheck = noRollbackCheck()),
            "效果不足" to input(effect = weakEffect()),
            "效果为负" to input(effect = badEffect()),
        )
        for ((label, source) in cases) {
            val judgement = policy.judge(source)
            assertTrue("$label：仍须人批", judgement.requiresApproval)
            assertTrue(
                "$label：必须写 L3_BLOCKED",
                judgement.reasons.any { it.code == EvolutionReasonCode.L3_AUTO_LOW_RISK_BLOCKED },
            )
            assertFalse(
                "$label：不许出现 L3 免人批原因",
                judgement.reasons.any { it.code == EvolutionReasonCode.L3_AUTO_LOW_RISK_ELIGIBLE },
            )
        }
    }

    @Test
    fun `开关打开_低风险且三条件全满足_才免人批`() {
        val judgement = EvolutionPolicy(autoLowRiskEnabled = true).judge(input())
        assertEquals(EvolutionRecommendation.ADOPT, judgement.recommendation)
        assertEquals(EvolutionRisk.LOW, judgement.riskClass)
        assertFalse("低风险＋eval 过线＋效果正向＋回滚可用：唯一自动口", judgement.requiresApproval)
        assertTrue(
            judgement.reasons.any { it.code == EvolutionReasonCode.L3_AUTO_LOW_RISK_ELIGIBLE },
        )
    }

    @Test
    fun `L3开关默认关闭_常量与实例`() {
        assertFalse("派工单 §2：开关默认 false", EvolutionPolicy.L3_AUTO_LOW_RISK)
        assertFalse("实例默认也必须关", EvolutionPolicy().autoLowRiskEnabled)
    }

    // ── 建议链与置信度 ──────────────────────────────────────────────────────

    @Test
    fun `建议链_ADOPT_REJECT_DEFER`() {
        val policy = EvolutionPolicy()
        assertEquals(EvolutionRecommendation.ADOPT, policy.judge(input()).recommendation)
        assertEquals(
            EvolutionRecommendation.REJECT,
            policy.judge(input(selfCheck = badCheck())).recommendation,
        )
        assertEquals(
            EvolutionRecommendation.REJECT,
            policy.judge(input(eval = lowEval())).recommendation,
        )
        assertEquals(
            EvolutionRecommendation.REJECT,
            policy.judge(input(effect = badEffect())).recommendation,
        )
        assertEquals(
            EvolutionRecommendation.DEFER,
            policy.judge(input(selfCheck = null)).recommendation,
        )
        assertEquals(
            EvolutionRecommendation.DEFER,
            policy.judge(input(eval = null)).recommendation,
        )
        assertEquals(
            EvolutionRecommendation.DEFER,
            policy.judge(input(selfCheck = noRollbackCheck())).recommendation,
        )
        assertEquals(
            EvolutionRecommendation.DEFER,
            policy.judge(input(effect = weakEffect())).recommendation,
        )
    }

    @Test
    fun `建议链原因码_逐项点名`() {
        val policy = EvolutionPolicy()
        fun codes(source: EvolutionDecisionInput) =
            policy.judge(source).reasons.map { it.code }.toSet()
        assertTrue(codes(input(selfCheck = null)).contains(EvolutionReasonCode.SELF_CHECK_NOT_RUN))
        assertTrue(codes(input(selfCheck = badCheck())).contains(EvolutionReasonCode.SELF_CHECK_FAILED))
        assertTrue(codes(input(eval = null)).contains(EvolutionReasonCode.EVAL_NOT_RUN))
        assertTrue(codes(input(eval = lowEval())).contains(EvolutionReasonCode.EVAL_BELOW_FLOOR))
        assertTrue(codes(input(selfCheck = noRollbackCheck())).contains(EvolutionReasonCode.ROLLBACK_UNAVAILABLE))
        assertTrue(codes(input(effect = weakEffect())).contains(EvolutionReasonCode.EFFECT_INSUFFICIENT))
        assertTrue(codes(input(effect = badEffect())).contains(EvolutionReasonCode.EFFECT_NEGATIVE))
        val adopt = codes(input())
        assertTrue(adopt.containsAll(
            setOf(
                EvolutionReasonCode.SELF_CHECK_PASSED,
                EvolutionReasonCode.EVAL_PASSED,
                EvolutionReasonCode.EFFECT_POSITIVE,
                EvolutionReasonCode.ROLLBACK_AVAILABLE,
            ),
        ))
    }

    @Test
    fun `置信度_边界与证据强弱`() {
        val policy = EvolutionPolicy()
        assertEquals(1.0, policy.judge(input()).confidence, 1e-9)
        assertEquals(
            0.0,
            policy.judge(input(selfCheck = badCheck(), eval = lowEval(), effect = badEffect())).confidence,
            1e-9,
        )
        assertEquals(
            "自检/eval 都没跑 + 效果不足：0.5-0.2-0.1",
            0.2,
            policy.judge(input(selfCheck = null, eval = null, effect = weakEffect())).confidence,
            1e-9,
        )
        for (selfCheck in listOf(null, okCheck(), badCheck(), noRollbackCheck())) {
            for (eval in listOf(null, goodEval(), lowEval())) {
                for (effect in listOf(goodEffect(), weakEffect(), badEffect())) {
                    val confidence = policy.judge(
                        input(selfCheck = selfCheck, eval = eval, effect = effect),
                    ).confidence
                    assertTrue("置信度越界：$confidence", confidence in 0.0..1.0)
                }
            }
        }
    }

    @Test
    fun `阈值可注入_evalFloor与样本下限`() {
        assertEquals(
            "恰好过线算过",
            EvolutionRecommendation.ADOPT,
            EvolutionPolicy(evalFloor = 0.5)
                .judge(input(eval = EvolutionEval(score = 0.5)))
                .recommendation,
        )
        assertEquals(
            EvolutionRecommendation.REJECT,
            EvolutionPolicy(evalFloor = 0.95)
                .judge(input(eval = EvolutionEval(score = 0.9)))
                .recommendation,
        )
        val two = EffectSnapshot(successes = 2, signalIds = listOf("skill_event:21"))
        assertEquals(
            "样本下限 2：两次成功算正向",
            EvolutionRecommendation.ADOPT,
            EvolutionPolicy(minEffectSamples = 2).judge(input(effect = two)).recommendation,
        )
        assertEquals(
            "默认样本下限 3：两次成功还不够",
            EvolutionRecommendation.DEFER,
            EvolutionPolicy().judge(input(effect = two)).recommendation,
        )
    }

    @Test
    fun `原因_每条决策都带候选证据_证据id无空白`() {
        val judgement = EvolutionPolicy().judge(input())
        val candidate = judgement.reasons.first { it.code == EvolutionReasonCode.CANDIDATE_EVIDENCE }
        assertEquals(listOf("run_log:3", "skill_event:1"), candidate.evidenceIds)
        for (reason in judgement.reasons) {
            assertTrue(
                "原因里的证据 id 不能有空白：$reason",
                reason.evidenceIds.none { it.isBlank() },
            )
        }
    }

    // ── 构造校验 ────────────────────────────────────────────────────────────

    @Test
    fun `构造校验_分数计数与开关参数越界`() {
        assertThrows(IllegalArgumentException::class.java) { EvolutionEval(score = 1.01) }
        assertThrows(IllegalArgumentException::class.java) { EvolutionEval(score = -0.01) }
        assertThrows(IllegalArgumentException::class.java) { EvolutionEval(score = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            EffectSnapshot(successes = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvolutionPolicy(evalFloor = 1.2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvolutionPolicy(minEffectSamples = 0)
        }
    }

    @Test
    fun `构造校验_决策必须有原因且置信度有界`() {
        val reason = listOf(EvolutionReason(EvolutionReasonCode.CANDIDATE_EVIDENCE))
        assertThrows(IllegalArgumentException::class.java) {
            EvolutionDecision(
                candidateId = "candidate-1",
                recommendation = EvolutionRecommendation.ADOPT,
                confidence = 0.9,
                riskClass = EvolutionRisk.LOW,
                requiresApproval = true,
                reasons = emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvolutionDecision(
                candidateId = "",
                recommendation = EvolutionRecommendation.ADOPT,
                confidence = 0.9,
                riskClass = EvolutionRisk.LOW,
                requiresApproval = true,
                reasons = reason,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EvolutionDecision(
                candidateId = "candidate-1",
                recommendation = EvolutionRecommendation.ADOPT,
                confidence = 1.1,
                riskClass = EvolutionRisk.LOW,
                requiresApproval = true,
                reasons = reason,
            )
        }
    }
}
