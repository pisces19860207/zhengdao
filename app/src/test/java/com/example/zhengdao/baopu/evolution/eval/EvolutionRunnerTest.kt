// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十三批 · 执行器纪律（设计 §3.2／§7.3／§9）：确定性、两臂顺序无关、
// 危险工具只问不做、脚本越界明确失败、单候选入口、报告可回读。
package com.example.zhengdao.baopu.evolution.eval

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionRunnerTest {

    private fun runner() = EvolutionRunner(EvolutionTestSupport.cases(), EvolutionTestSupport.policy())

    @Test
    fun sameArmTwiceIsByteIdentical() {
        val a = runner().run(null, "baseline")
        val b = runner().run(null, "baseline")
        assertEquals(a.readingText(), b.readingText())
        assertEquals(a.tokensIn, b.tokensIn)
        assertEquals(a.cases.map { it.steps }, b.cases.map { it.steps })
    }

    @Test
    fun armOrderDoesNotMatter() {
        val candidate = EvolutionTestSupport.candidate("better")
        val first = runner()
        val baselineFirst = first.run(null, "baseline")
        val candidateFirst = first.run(candidate, "candidate")

        val second = runner()
        val candidateSecond = second.run(candidate, "candidate")
        val baselineSecond = second.run(null, "baseline")

        assertEquals(baselineFirst.readingText(), baselineSecond.readingText())
        assertEquals(candidateFirst.readingText(), candidateSecond.readingText())
    }

    @Test
    fun dangerToolIsAskedButNeverExecuted() {
        val arm = runner().run(null, "baseline")
        val del = arm.cases.first { it.id == "evo-danger-delete" }
        assertEquals(1, del.dangerAsked)
        assertEquals(0, del.dangerEscaped)
        assertTrue("文件必须还在（没执行删除）", del.ok)

        val outside = arm.cases.first { it.id == "evo-danger-outside" }
        assertEquals(1, outside.dangerRejected)
        assertEquals(0, outside.dangerEscaped)
        assertTrue(outside.ok)
    }

    @Test
    fun scriptBeyondPolicyIsHardFailure() {
        val policy = FakePolicyModel.parse(
            """
            {"model":"fake-policy/1","temperature":0.0,"seed":0,
             "rules":[{"id":"never","when":{"promptContains":"不存在的字样"},
                       "steps":[{"final":"x"}]}]}
            """.trimIndent(),
        )
        val only = EvolutionCase(
            id = "only",
            note = "",
            prompt = "hello",
            maxSteps = 3,
            files = emptyList(),
            asserts = listOf(EvolutionAssert("steps_leq", n = 3)),
        )
        val arm = EvolutionRunner(listOf(only), policy).run(null, "baseline")
        val run = arm.cases.single()
        assertFalse(run.ok)
        assertTrue(run.hardFailure!!.startsWith("script_exhausted"))
    }

    @Test
    fun entryAcceptsExactlyOneCandidate() {
        val one = EvolutionCandidate("c1", EvolutionKind.SKILL_BODY, "t", "d")
        assertTrue(EvolutionRequest.select(listOf(one)) is CandidateSelection.Single)

        val none = EvolutionRequest.select(emptyList())
        assertTrue(none is CandidateSelection.Rejected)
        assertEquals(EvolutionRequest.CODE_SINGLE_CANDIDATE, (none as CandidateSelection.Rejected).code)

        val two = EvolutionRequest.select(listOf(one, EvolutionCandidate("c2", EvolutionKind.TOOL_RULE, "t", "d")))
        assertTrue(two is CandidateSelection.Rejected)
        assertEquals(EvolutionRequest.CODE_SINGLE_CANDIDATE, (two as CandidateSelection.Rejected).code)
    }

    @Test
    fun candidateFrom162PendingSourceDefersWithoutRunning() {
        val pending = EvolutionCandidate(
            candidateId = "real-memory-001",
            kind = EvolutionKind.MEMORY_NOTE,
            target = "记忆条",
            delta = "用户偏好先备份",
            evidenceIds = listOf("run_log:12"),
        )
        val out = EvolutionEval.evaluate(
            candidate = pending,
            cases = EvolutionTestSupport.cases(),
            casesSha256 = EvolutionCases.sha256(EvolutionTestSupport.casesFile()),
            policy = EvolutionTestSupport.policy(),
            ranAt = 1L,
        )
        assertEquals("DEFER", out.report.getString("verdict"))
        assertTrue(EvolutionTestSupport.codes(out.report).contains(EvolutionReasons.CANDIDATE_NOT_READY))
        assertTrue(out.report.isNull("baseline"))
        assertNull(out.baseline)
    }

    @Test
    fun reportIsWrittenAndReadBack() {
        val dir = Files.createTempDirectory("zhengdao-evo-report-").toFile()
        try {
            val out = EvolutionTestSupport.evaluate("better", reportDir = dir)
            val file = dir.listFiles()!!.single()
            assertEquals("fixture-better.json", file.name)
            val back = EvolutionReport.read(file)
            assertEquals(out.report.getString("schema"), back.getString("schema"))
            assertEquals("ADOPT", back.getString("verdict"))
            assertFalse(back.getBoolean("autoPromote"))
            assertTrue(back.getBoolean("requiresHumanApproval"))
            val config = back.getJSONObject("config")
            assertEquals(64, config.getString("casesSha256").length)
            assertEquals(64, config.getString("fakePolicySha256").length)
            assertEquals(64, config.getString("candidateSha256").length)
            assertEquals(64, config.getString("configSha256").length)
        } finally {
            dir.deleteRecursively()
        }
    }
}
