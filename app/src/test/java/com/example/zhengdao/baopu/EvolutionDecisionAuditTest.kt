// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八十八批 §3：审计——每决策一条、字段齐全、只存 id/数值、不含候选正文、写失败不返回。
package com.example.zhengdao.baopu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class EvolutionDecisionAuditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingSink : EvolutionAuditSink {
        val entries = mutableListOf<EvolutionAuditEntry>()
        override fun record(entry: EvolutionAuditEntry) {
            entries += entry
        }
    }

    private fun okCheck() = SelfCheckEvidence(
        passed = true,
        rollbackAvailable = true,
        caseIds = listOf("selfcheck:case-1"),
    )

    private fun goodEval() = EvolutionEval(score = 0.9, caseIds = listOf("eval:case-1"))

    private fun goodEffect() = EffectSnapshot(
        successes = 3,
        retries = 1,
        signalIds = listOf("skill_event:11"),
    )

    private fun input(
        risk: EvolutionRiskCategory = EvolutionRiskCategory.SKILL_BODY_MINOR,
        selfCheck: SelfCheckEvidence? = okCheck(),
        eval: EvolutionEval? = goodEval(),
    ) = EvolutionDecisionInput(
        candidateId = "candidate-1",
        kind = ProposalKind.SKILL_BODY,
        riskCategory = risk,
        evidenceIds = listOf("run_log:3", "skill_event:1"),
        selfCheck = selfCheck,
        eval = eval,
        effect = goodEffect(),
    )

    private fun jvmPort(
        policy: EvolutionPolicy = EvolutionPolicy(),
        sink: EvolutionAuditSink = RecordingSink(),
        at: Long = 1234L,
    ) = EvolutionDecisionPort(policy = policy, audit = sink, clock = { at })

    // ── 每决策一条 ──────────────────────────────────────────────────────────

    @Test
    fun `每决策一条审计_含输入输出是否人批操作者`() {
        val sink = RecordingSink()
        val port = jvmPort(policy = EvolutionPolicy(autoLowRiskEnabled = true), sink = sink)
        val low = port.decide(input(), "user:guoli")
        val high = port.decide(input(risk = EvolutionRiskCategory.PERSONA_CORE), "user:guoli")

        assertEquals("两次决策两条审计", 2, sink.entries.size)
        assertEquals(
            "低风险三条件全满足：免人批",
            false,
            low.requiresApproval,
        )
        assertEquals("高风险：仍需人批", true, high.requiresApproval)

        val first = sink.entries[0]
        assertEquals(1234L, first.atMs)
        assertEquals("user:guoli", first.operatorId)
        assertEquals(low, first.decision)
        assertEquals("candidate-1", first.input.candidateId)
        assertEquals(false, first.decision.requiresApproval)
        assertEquals("L3 开关状态必须进审计", true, first.policy.l3AutoLowRisk)

        val second = sink.entries[1]
        assertEquals(high, second.decision)
        assertEquals(true, second.decision.requiresApproval)
        assertEquals(EvolutionRisk.HIGH, second.decision.riskClass)
    }

    @Test
    fun `操作者为空_拒绝决策`() {
        val sink = RecordingSink()
        val port = jvmPort(sink = sink)
        assertThrows(IllegalArgumentException::class.java) {
            port.decide(input(), "  ")
        }
        assertTrue("没有审计就不该有决策", sink.entries.isEmpty())
    }

    // ── JSONL 落盘 ──────────────────────────────────────────────────────────

    @Test
    fun `审计落盘_JSONL每行一条_键齐全`() {
        val file = File(tmp.root, "决策审计.jsonl")
        val port = jvmPort(sink = EvolutionAuditLog(file), at = 5678L)
        port.decide(input(), "tester")

        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals("一行一条", 1, lines.size)
        val row = JSONObject(lines[0])
        assertEquals(
            "顶层键固定",
            setOf(
                "at_ms",
                "operator",
                "policy",
                "candidate_id",
                "kind",
                "risk_category",
                "evidence_ids",
                "input",
                "recommendation",
                "confidence",
                "risk_class",
                "requires_approval",
                "reasons",
                // 第一百批（口径变更记账）：采纳后效果这一格键恒在；没有就是 JSON null
                "post_adoption",
            ),
            row.keys().asSequence().toSet(),
        )
        assertEquals(5678L, row.getLong("at_ms"))
        assertEquals("tester", row.getString("operator"))
        assertEquals("candidate-1", row.getString("candidate_id"))
        assertEquals("SKILL_BODY", row.getString("kind"))
        assertEquals("SKILL_BODY_MINOR", row.getString("risk_category"))
        assertEquals("ADOPT", row.getString("recommendation"))
        assertEquals("LOW", row.getString("risk_class"))
        assertEquals(true, row.getBoolean("requires_approval"))

        val policy = row.getJSONObject("policy")
        assertEquals(
            setOf("l3_auto_low_risk", "eval_floor", "min_effect_samples"),
            policy.keys().asSequence().toSet(),
        )
        assertEquals(false, policy.getBoolean("l3_auto_low_risk"))

        val summary = row.getJSONObject("input")
        assertEquals(
            setOf("self_check", "eval", "effect"),
            summary.keys().asSequence().toSet(),
        )
        assertEquals(
            setOf("passed", "rollback_available", "case_ids", "rule_ids"),
            summary.getJSONObject("self_check").keys().asSequence().toSet(),
        )
        assertEquals(
            // 第一百六十八批：source/ranAt 键恒在（同 post_adoption 口径）——
            // 「这条决策当时看的是谁产的证据、什么时候产的」以后要能回答。
            setOf("score", "case_ids", "source", "ranAt"),
            summary.getJSONObject("eval").keys().asSequence().toSet(),
        )
        // 夹具 goodEval() 不带来源信息 ⇒ 默认按发版证据记，ranAt 写 JSON null（不编日期）
        assertEquals("RELEASE", summary.getJSONObject("eval").getString("source"))
        assertTrue(summary.getJSONObject("eval").isNull("ranAt"))
        assertEquals(
            setOf("successes", "failures", "corrections", "retries", "signal_ids"),
            summary.getJSONObject("effect").keys().asSequence().toSet(),
        )

        val reasons = row.getJSONArray("reasons")
        assertTrue("原因链不能为空", reasons.length() > 0)
        assertEquals(
            setOf("code", "evidence_ids"),
            reasons.getJSONObject(0).keys().asSequence().toSet(),
        )
        assertEquals("CANDIDATE_EVIDENCE", reasons.getJSONObject(0).getString("code"))
    }

    @Test
    fun `审计落盘_自检eval未跑写null_不编造`() {
        val file = File(tmp.root, "决策审计.jsonl")
        val port = jvmPort(sink = EvolutionAuditLog(file))
        val decision = port.decide(input(selfCheck = null, eval = null), "tester")
        assertEquals(EvolutionRecommendation.DEFER, decision.recommendation)

        val summary = JSONObject(file.readLines(Charsets.UTF_8).first()).getJSONObject("input")
        assertTrue("自检没跑必须是 null", summary.isNull("self_check"))
        assertTrue("eval 没跑必须是 null", summary.isNull("eval"))
    }

    @Test
    fun `审计不含候选正文`() {
        val root = tmp.newFolder("store")
        val store = ProposalCandidateStore(root, log = {})
        val secret = "绝密正文-只在候选档里-88"
        store.appendNew(
            listOf(
                ProposalCandidate(
                    id = "candidate-1",
                    kind = ProposalKind.SKILL_BODY,
                    target = "技能A",
                    payload = secret,
                    evidence = ProposalEvidence(
                        runLogIds = listOf(9L),
                        skillEventIds = listOf(10L),
                        atMs = 1L,
                    ),
                    createdAt = 1L,
                ),
            ),
        )
        val auditFile = File(tmp.root, "audit.jsonl")
        val port = jvmPort(sink = EvolutionAuditLog(auditFile))
        val decisions = port.decidePending(
            store = store,
            assessment = EvolutionAssessmentSource {
                EvolutionAssessment(
                    riskCategory = EvolutionRiskCategory.SKILL_BODY_MINOR,
                    selfCheck = okCheck(),
                    eval = goodEval(),
                    effect = goodEffect(),
                )
            },
            operatorId = "tester",
        )

        assertEquals(1, decisions.size)
        assertFalse("决策对象不许带候选正文", decisions.toString().contains(secret))
        assertFalse(
            "审计文件不许带候选正文",
            auditFile.readText(Charsets.UTF_8).contains(secret),
        )
        assertTrue(
            "候选档本身当然还留着正文（决策口不删不改）",
            store.file().readText(Charsets.UTF_8).contains(secret),
        )
    }

    @Test
    fun `审计写失败_决策不返回`() {
        val failing = EvolutionAuditSink { throw IOException("disk full") }
        val port = jvmPort(sink = failing)
        assertThrows(IOException::class.java) {
            port.decide(input(), "tester")
        }
    }

    @Test
    fun `审计只追加_不覆盖历史`() {
        val file = File(tmp.root, "audit.jsonl")
        val port = jvmPort(sink = EvolutionAuditLog(file))
        port.decide(input(), "first")
        val first = file.readText(Charsets.UTF_8)
        port.decide(input(), "second")
        val all = file.readText(Charsets.UTF_8)

        assertTrue("旧行必须原样保留", all.startsWith(first))
        assertEquals("两次决策两行", 2, all.lines().count { it.isNotBlank() })
    }

    @Test
    fun `审计落点_抱朴记忆下的决策审计jsonl`() {
        val root = tmp.newFolder("memory")
        val log = EvolutionAuditLog.under(root)
        assertEquals(File(root, "决策审计.jsonl"), log.file())
    }

    @Test
    fun `审计落盘_个人生产证据_来源与日期都记`() {
        // 第一百六十八批：prod 证据进审计必须带 source=PERSONAL_PROD 与 ranAt，
        // 与发版证据（RELEASE/ranAt 缺省）在账本里分得开（派工单 §2「来源和日期必须标」）。
        val file = File(tmp.root, "决策审计.jsonl")
        val port = jvmPort(sink = EvolutionAuditLog(file), at = 5678L)
        val prodEval = EvolutionEval(
            score = 0.95,
            caseIds = listOf("eval:prod-1"),
            source = com.example.zhengdao.baopu.eval.EvalEvidenceSource.PERSONAL_PROD,
            ranAt = 5_000L,
        )
        port.decide(input(eval = prodEval), "tester")

        val evalJson = JSONObject(file.readLines(Charsets.UTF_8).first())
            .getJSONObject("input")
            .getJSONObject("eval")
        assertEquals("PERSONAL_PROD", evalJson.getString("source"))
        assertEquals(5_000L, evalJson.getLong("ranAt"))
        assertEquals(0.95, evalJson.getDouble("score"), 1e-9)
    }
}
