// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十二批 · 政策表 v1：**A 的执行点确实按表跑**（不是把 JSON 读进内存就完事）。
//
// 四个点各一条：
// 1. 工具组默认可见 —— [ToolGroup.DEFAULT_VISIBLE]；
// 2. 组闸阈值 —— [ToolGroupGate] 的默认参数；
// 3. 风险分级 —— [EvolutionPolicy.judge] 的 riskClass 与人批；
// 4. eval 过线 —— [EvolutionPolicy.judge] 的建议（0.9 在 0.95 下表里必须 REJECT）。
//
// 另有一条兜底：就算有人绕过读表器直接 `EvolutionPolicy(riskLevels = 低)`，
// judge 里的 [Policy.riskFloor] 仍会把高风险类别抬回 HIGH。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.EffectSnapshot
import com.example.zhengdao.baopu.EvolutionDecisionInput
import com.example.zhengdao.baopu.EvolutionEval
import com.example.zhengdao.baopu.EvolutionPolicy
import com.example.zhengdao.baopu.EvolutionRecommendation
import com.example.zhengdao.baopu.EvolutionRisk
import com.example.zhengdao.baopu.EvolutionRiskCategory
import com.example.zhengdao.baopu.ProposalKind
import com.example.zhengdao.baopu.SelfCheckEvidence
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyWiringTest {

    @After
    fun tearDown() {
        PolicyRuntime.reset()
    }

    private fun install(
        visible: Set<ToolGroup> = setOf(ToolGroup.CORE, ToolGroup.COMPAT),
        threshold: Int = 1,
        floor: Double = 0.95,
        minSamples: Int = 5,
        promptTweakHigh: Boolean = true,
    ) {
        val risks = EvolutionRiskCategory.entries.associateWith { it.riskClass }.toMutableMap()
        if (promptTweakHigh) risks[EvolutionRiskCategory.PROMPT_TWEAK] = EvolutionRisk.HIGH
        val p = Policy(
            version = Policy.VERSION,
            toolGroups = ToolGroupPolicy(visible),
            approval = ApprovalPolicy(threshold, risks),
            eval = EvalPolicy(floor, minSamples),
        )
        PolicyRuntime.install(PolicyTable.Load.Loaded(p, "test"))
    }

    private fun input(
        category: EvolutionRiskCategory,
        evalScore: Double? = null,
    ): EvolutionDecisionInput = EvolutionDecisionInput(
        candidateId = "c1",
        kind = ProposalKind.SKILL_BODY,
        riskCategory = category,
        evidenceIds = listOf("run_log:1"),
        selfCheck = SelfCheckEvidence(
            passed = true,
            rollbackAvailable = true,
            caseIds = listOf("selfcheck:structure"),
        ),
        eval = evalScore?.let { EvolutionEval(score = it, caseIds = listOf("case:a1")) },
        effect = EffectSnapshot(
            successes = 3,
            failures = 0,
            corrections = 0,
            signalIds = listOf("skill_event:1"),
        ),
    )

    // ── 1/2：工具组默认可见 ＋ 组闸阈值 ────────────────────────────────────

    @Test
    fun `默认时两个执行点与现行为一致`() {
        assertEquals(
            setOf(ToolGroup.CORE, ToolGroup.EDIT, ToolGroup.COMPAT),
            ToolGroup.DEFAULT_VISIBLE,
        )
        assertEquals(3, ToolGroupGate().threshold)
    }

    @Test
    fun `装表后默认可见与组闸立即按表执行`() {
        install(visible = setOf(ToolGroup.CORE, ToolGroup.COMPAT), threshold = 1)
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.COMPAT), ToolGroup.DEFAULT_VISIBLE)
        assertEquals(1, ToolGroupGate().threshold)
    }

    @Test
    fun `显式传参仍优先于政策表_单测与特例不被表绑死`() {
        install(threshold = 1)
        assertEquals(7, ToolGroupGate(threshold = 7).threshold)
    }

    // ── 3：风险分级 ──────────────────────────────────────────────────────

    @Test
    fun `默认时提示词微调是低风险_表把它抬到HIGH后必须人批`() {
        val before = EvolutionPolicy().judge(input(EvolutionRiskCategory.PROMPT_TWEAK))
        assertEquals(EvolutionRisk.LOW, before.riskClass)
        assertTrue("默认全人批", before.requiresApproval)

        install(promptTweakHigh = true)
        val after = EvolutionPolicy().judge(input(EvolutionRiskCategory.PROMPT_TWEAK))
        assertEquals("表抬高风险必须进判定", EvolutionRisk.HIGH, after.riskClass)
        assertTrue("高风险永远人批", after.requiresApproval)
    }

    @Test
    fun `风险底线在judge里兜底_手写低等级也不放行`() {
        val p = EvolutionPolicy(
            riskLevels = mapOf(EvolutionRiskCategory.PERSONA_CORE to EvolutionRisk.MEDIUM),
        )
        val j = p.judge(input(EvolutionRiskCategory.PERSONA_CORE))
        assertEquals("人设核心不许被降到 MEDIUM", EvolutionRisk.HIGH, j.riskClass)
        assertTrue(j.requiresApproval)
    }

    // ── 4：eval 过线 ─────────────────────────────────────────────────────

    @Test
    fun `eval阈值从表里来_0点9在0点95下面必须REJECT`() {
        val default = EvolutionPolicy().judge(input(EvolutionRiskCategory.SKILL_BODY_MINOR, evalScore = 0.9))
        assertEquals("默认 0.8：0.9 过线", EvolutionRecommendation.ADOPT, default.recommendation)

        install(floor = 0.95)
        val strict = EvolutionPolicy().judge(input(EvolutionRiskCategory.SKILL_BODY_MINOR, evalScore = 0.9))
        assertEquals("表把过线抬到 0.95：0.9 必须拒绝", EvolutionRecommendation.REJECT, strict.recommendation)
    }

    @Test
    fun `效果样本下限从表里来_3次正向上调成5次后只能DEFER`() {
        // effect 固定 3 次成功；默认下限 3 ⇒ POSITIVE ⇒ ADOPT；表调到 5 ⇒ INSUFFICIENT ⇒ DEFER
        val default = EvolutionPolicy().judge(input(EvolutionRiskCategory.SKILL_BODY_MINOR, evalScore = 0.9))
        assertEquals(EvolutionRecommendation.ADOPT, default.recommendation)

        install(floor = 0.8, minSamples = 5)
        val strict = EvolutionPolicy().judge(input(EvolutionRiskCategory.SKILL_BODY_MINOR, evalScore = 0.9))
        assertEquals(EvolutionRecommendation.DEFER, strict.recommendation)
    }

    @Test
    fun `显式注入仍优先于政策表`() {
        install(floor = 0.95, minSamples = 5)
        val p = EvolutionPolicy(evalFloor = 0.6, minEffectSamples = 2)
        assertEquals(0.6, p.evalFloor, 0.0)
        assertEquals(2, p.minEffectSamples)
    }
}
