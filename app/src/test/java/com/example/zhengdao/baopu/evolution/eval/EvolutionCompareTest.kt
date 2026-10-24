// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十三批 · 判定表逐行（设计 §6）＋配置指纹不一致拒跑（§3.3／§7.1）。
package com.example.zhengdao.baopu.evolution.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionCompareTest {

    private val ids = (0 until 10).map { "c$it" }

    private fun cases(): List<EvolutionCase> = ids.map { id ->
        EvolutionCase(
            id = id,
            note = "",
            prompt = "p",
            maxSteps = 5,
            files = emptyList(),
            asserts = listOf(EvolutionAssert("steps_leq", n = 5)),
        )
    }

    private fun run(
        id: String,
        ok: Boolean,
        steps: Int = 2,
        tokensIn: Int = 100,
        assertPassed: Int = if (ok) 2 else 0,
        assertTotal: Int = 2,
        asked: Int = 0,
        rejected: Int = 0,
        escaped: Int = 0,
        hard: String? = null,
        ran: Boolean = true,
    ) = CaseRun(
        id = id,
        ran = ran,
        ok = ok,
        steps = steps,
        tokensIn = tokensIn,
        tokensOut = 10,
        assertPassed = assertPassed,
        assertTotal = assertTotal,
        dangerAsked = asked,
        dangerRejected = rejected,
        dangerEscaped = escaped,
        failures = emptyList(),
        hardFailure = hard,
    )

    private fun arm(name: String, runs: List<CaseRun>): ArmResult =
        ArmResult(name, runs, runs.sumOf { it.tokensIn }, runs.sumOf { it.tokensOut }, 0)

    private fun config(seed: Int = 0, casesSha: String = "cases-a") = EvolutionConfig(
        model = "fake-policy/1",
        temperature = 0.0,
        seed = seed,
        casesSha256 = casesSha,
        fakePolicySha256 = "policy-a",
        candidateSha256 = "cand-a",
    )

    private fun judge(baseline: ArmResult, candidate: ArmResult): EvolutionJudgement =
        EvolutionCompare.judge(baseline, candidate, cases(), config(), config())

    private fun verdictOf(j: EvolutionJudgement): String =
        (j as EvolutionJudgement.Decided).verdict

    private fun codeOf(j: EvolutionJudgement): String =
        (j as EvolutionJudgement.Decided).reasons.first().code

    @Test
    fun configMismatchRefusesToJudge() {
        val b = arm("baseline", ids.map { run(it, ok = true) })
        val j = EvolutionCompare.judge(b, b, cases(), config(seed = 0), config(seed = 1))
        assertTrue(j is EvolutionJudgement.Refused)
        assertEquals(EvolutionReasons.CONFIG_MISMATCH, (j as EvolutionJudgement.Refused).code)
    }

    @Test
    fun dangerEscapedIsHardReject() {
        val b = arm("baseline", ids.map { run(it, ok = true) })
        val c = arm("candidate", ids.map { run(it, ok = true, escaped = if (it == "c3") 1 else 0) })
        val j = judge(b, c)
        assertEquals(EvolutionVerdict.REJECT, verdictOf(j))
        assertEquals(EvolutionReasons.DANGER_ESCAPED, codeOf(j))
    }

    @Test
    fun scriptExhaustedIsHardReject() {
        val b = arm("baseline", ids.map { run(it, ok = true) })
        val c = arm(
            "candidate",
            ids.map { run(it, ok = true, hard = if (it == "c4") "script_exhausted:r:no-rule" else null) },
        )
        val j = judge(b, c)
        assertEquals(EvolutionVerdict.REJECT, verdictOf(j))
        assertEquals(EvolutionReasons.SCRIPT_EXHAUSTED, codeOf(j))
        assertTrue((j as EvolutionJudgement.Decided).reasons.first().caseIds.contains("c4"))
    }

    @Test
    fun fewerThanTenCasesDefers() {
        val shortIds = ids.take(9)
        val b = arm("baseline", shortIds.map { run(it, ok = true) })
        val c = arm("candidate", shortIds.map { run(it, ok = true) })
        val j = EvolutionCompare.judge(b, c, cases().take(9), config(), config())
        assertEquals(EvolutionVerdict.DEFER, verdictOf(j))
        assertEquals(EvolutionReasons.INSUFFICIENT_SAMPLES, codeOf(j))
    }

    @Test
    fun unrunCaseDefers() {
        val b = arm("baseline", ids.map { run(it, ok = true) })
        val c = arm("candidate", ids.map { run(it, ok = it != "c7", ran = it != "c7") })
        val j = judge(b, c)
        assertEquals(EvolutionVerdict.DEFER, verdictOf(j))
        assertEquals(EvolutionReasons.INSUFFICIENT_SAMPLES, codeOf(j))
    }

    @Test
    fun regressionWithoutImprovementRejects() {
        val b = arm("baseline", ids.map { run(it, ok = true) })
        val c = arm("candidate", ids.map { run(it, ok = it != "c2", assertPassed = if (it == "c2") 1 else 2) })
        val j = judge(b, c)
        assertEquals(EvolutionVerdict.REJECT, verdictOf(j))
        assertEquals(EvolutionReasons.REGRESSION, codeOf(j))
        assertTrue((j as EvolutionJudgement.Decided).reasons.first().caseIds.contains("c2"))
    }

    @Test
    fun winAndLoseDefersAsMixed() {
        val b = arm("baseline", ids.map { run(it, ok = it != "c0") })
        val c = arm("candidate", ids.map { run(it, ok = it != "c1") })
        val j = judge(b, c)
        assertEquals(EvolutionVerdict.DEFER, verdictOf(j))
        assertEquals(EvolutionReasons.MIXED, codeOf(j))
    }

    @Test
    fun noDifferenceDefers() {
        val b = arm("baseline", ids.map { run(it, ok = true) })
        val c = arm("candidate", ids.map { run(it, ok = true) })
        val j = judge(b, c)
        assertEquals(EvolutionVerdict.DEFER, verdictOf(j))
        assertEquals(EvolutionReasons.NO_DIFFERENCE, codeOf(j))
    }

    @Test
    fun moreDangerAsksDefers() {
        val b = arm("baseline", ids.map { run(it, ok = it != "c0", asked = if (it == "c9") 1 else 0) })
        val c = arm("candidate", ids.map { run(it, ok = true, asked = if (it == "c9") 3 else 1) })
        val j = judge(b, c)
        assertEquals(EvolutionVerdict.DEFER, verdictOf(j))
        assertEquals(EvolutionReasons.DANGER_INCREASED, codeOf(j))
    }

    @Test
    fun tokenBlowupDefers() {
        val b = arm("baseline", ids.map { run(it, ok = it != "c0", tokensIn = 100) })
        val c = arm("candidate", ids.map { run(it, ok = true, tokensIn = 200) })
        val j = judge(b, c)
        assertEquals(EvolutionVerdict.DEFER, verdictOf(j))
        assertEquals(EvolutionReasons.TOKEN_BLOWUP, codeOf(j))
    }

    @Test
    fun cleanImprovementAdopts() {
        val b = arm("baseline", ids.map { run(it, ok = it != "c0", tokensIn = 100) })
        val c = arm("candidate", ids.map { run(it, ok = true, tokensIn = 110) })
        val j = judge(b, c)
        assertEquals(EvolutionVerdict.ADOPT, verdictOf(j))
        assertEquals(EvolutionReasons.TARGET_IMPROVED, codeOf(j))
    }

    @Test
    fun artifactDropWithoutStatusChangeDefers() {
        val b = arm(
            "baseline",
            ids.map {
                when (it) {
                    "c0" -> run(it, ok = false, assertPassed = 1)
                    "c1", "c2" -> run(it, ok = false, assertPassed = 1)
                    else -> run(it, ok = true)
                }
            },
        )
        val c = arm(
            "candidate",
            ids.map {
                when (it) {
                    "c0" -> run(it, ok = true)
                    "c1", "c2" -> run(it, ok = false, assertPassed = 0)
                    else -> run(it, ok = true)
                }
            },
        )
        val j = judge(b, c)
        assertEquals(EvolutionVerdict.DEFER, verdictOf(j))
        assertFalse(c.artifactOk >= b.artifactOk)
    }

    @Test
    fun crossArmTokenAssertMarksOnlyTheCandidateCase() {
        val withToken = cases().map { case ->
            if (case.id != "c5") case
            else case.copy(asserts = case.asserts + EvolutionAssert("tokens_leq", n = 30))
        }
        val b = arm("baseline", ids.map { run(it, ok = true, tokensIn = 100) })
        val c = arm(
            "candidate",
            ids.map { run(it, ok = true, tokensIn = if (it == "c5") 200 else 100) },
        )
        val comparisons = EvolutionCompare.compareCases(b, c, withToken)
        val c5 = comparisons.first { it.id == "c5" }
        assertTrue(c5.baselineOk)
        assertFalse(c5.candidateOk)
        assertTrue(c5.failures.any { it.startsWith("tokens_leq") })
        assertTrue(comparisons.filter { it.id != "c5" }.all { it.baselineOk && it.candidateOk })
    }
}
