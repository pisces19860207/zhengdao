// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十三批 · 法典门禁 **L19 无证据不采纳**（派工单 253；判据为 adopt 路径必须检查
// 证据非空，无证据只能「搁置」）。
//
// ## 判据（可执行）
// 采纳「改进提案」（技能／提示词／约束类写盘）必须同时满足：
// ① **有对拍证据**（206 对拍真的跑过：`EvalOutcome.Ran`；NotRun/Invalid = 没有证据）；
// ② **证据 id 非空**（进 208 的行 `evidence` 缺了整条不落；决策原因链里的证据 id 不许空串）；
// ③ 证据不足的候选只能 **DEFER（搁置）** 或 REJECT，**不许 ADOPT**。
//
// ## 门禁形态（派工单 253）
// main 里承担这三件事的生产判据各有一处，全部用**真码行为**钉住（不改它们，只钉住它们）：
// ① `DecisionLedger.build` —— 缺证据整条不落（208 账本层）；
// ② `EvolutionPolicy.judge` —— eval 缺/自检缺/回滚不可用 ⇒ DEFER；自检挂/eval 不过线 ⇒ REJECT；
// ③ `AutonomyEvalGate.passedWithoutRegression` —— 没跑成对拍 ⇒ 不许保留（A 档自改）。
// 外加源码锚点：三处证据闸的判据行不许被拆。
//
// ## 负例（本文件自证会红）
// 负例①：假决策口「没跑对拍也照样 ADOPT」⇒ 探针红；守法的假实现（没证据 ⇒ DEFER）不红；
// 负例②：假账本「缺证据也照落」⇒ 探针红；真 `DecisionLedger.build` 对同一条草稿整条拒绝
//         （对照：红的是假实现的形状，真码是守法的——门禁盯的就是别让它退化成假实现那样）。
package com.example.zhengdao.baopu.constitution

import com.example.zhengdao.baopu.AutonomyEvalGate
import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.EvalOutcome
import com.example.zhengdao.baopu.EvalVerdict
import com.example.zhengdao.baopu.EvalCompare
import com.example.zhengdao.baopu.EvalAttempt
import com.example.zhengdao.baopu.EvolutionDecisionInput
import com.example.zhengdao.baopu.EvolutionPolicy
import com.example.zhengdao.baopu.EvolutionRecommendation
import com.example.zhengdao.baopu.EvolutionRiskCategory
import com.example.zhengdao.baopu.ProposalKind
import com.example.zhengdao.baopu.SelfCheckEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L19EvidenceAdoptionGateTest {

    // ── 判据探针（只读；负例的靶）────────────────────────────────────────────

    internal object EvidenceGate {
        /** 采纳的资格：有对拍证据 ＋ 证据 id 非空。不满足 ⇒ 违规一句话。 */
        fun adoptViolation(
            recommendation: String,
            hasEvalEvidence: Boolean,
            evidenceIds: List<String>,
        ): String? =
            if (recommendation == "ADOPT" && (!hasEvalEvidence || evidenceIds.none { it.isNotBlank() })) {
                "采纳必须有对拍证据且证据 id 非空（无证据只能搁置 DEFER）"
            } else {
                null
            }

        /** 208 的一行：证据 id 为空 ⇒ 不落空账。 */
        fun ledgerViolation(evidence: String): String? =
            if (evidence.isBlank()) "208 行缺证据 id——宁可无账，不落空账" else null
    }

    // ── 真码行为①：账本层——缺证据整条不落 ──────────────────────────────────

    @Test
    fun `真码1_账本层_缺证据的草稿整条不落_理由出声`() {
        val base = DecisionDraft(
            kind = DecisionKind.SKILL_PROMOTE,
            actor = DecisionActor.USER,
            reason = "草稿转正",
            evidence = "skill:project/demo",
            before = "draft",
            after = "active",
            atMs = 1L,
            rollbackRef = "skills/demo/SKILL.md",
        )
        val kept = DecisionLedger.build(base)
        assertNotNull(kept)
        assertEquals("skill:project/demo", kept!!.evidence)

        val blankEvidence = DecisionLedger.build(base.copy(evidence = " "))
        assertNull("缺证据必须整条拒绝（宁可无账）", blankEvidence)
        assertTrue(
            "拒绝理由必须点名证据：${DecisionLedger.lastError}",
            DecisionLedger.lastError.orEmpty().contains("证据"),
        )
    }

    // ── 真码行为②：决策口——没有对拍证据不许建议采纳 ────────────────────────

    @Test
    fun `真码2_决策口_eval没跑成_自检没跑_回滚不可用_一律不许ADOPT`() {
        val policy = EvolutionPolicy(evalFloor = 0.8)
        fun input(
            selfCheck: SelfCheckEvidence?,
            eval: com.example.zhengdao.baopu.EvolutionEval?,
        ) = EvolutionDecisionInput(
            candidateId = "c-253",
            kind = ProposalKind.SKILL_BODY,
            riskCategory = EvolutionRiskCategory.defaultFor(ProposalKind.SKILL_BODY),
            evidenceIds = listOf("run_log:12"),
            selfCheck = selfCheck,
            eval = eval,
        )
        val goodSelfCheck = SelfCheckEvidence(passed = true, rollbackAvailable = true, caseIds = listOf("case-1"))

        // 没跑对拍（eval=null）⇒ 只能搁置：
        assertEquals(
            EvolutionRecommendation.DEFER,
            policy.judge(input(goodSelfCheck, eval = null)).recommendation,
        )
        // 自检没跑 ⇒ 搁置；自检挂了 ⇒ 拒绝：
        assertEquals(
            EvolutionRecommendation.DEFER,
            policy.judge(input(selfCheck = null, eval = evalOf(0.9))).recommendation,
        )
        assertEquals(
            EvolutionRecommendation.REJECT,
            policy.judge(
                input(SelfCheckEvidence(passed = false, rollbackAvailable = true, caseIds = listOf("case-1")), evalOf(0.9)),
            ).recommendation,
        )
        // 没有回滚点 ⇒ 搁置（可回滚是采纳的前提）：
        assertEquals(
            EvolutionRecommendation.DEFER,
            policy.judge(
                input(SelfCheckEvidence(passed = true, rollbackAvailable = false, caseIds = listOf("case-1")), evalOf(0.9)),
            ).recommendation,
        )
        // 对拍不过线 ⇒ 拒绝：
        assertEquals(
            EvolutionRecommendation.REJECT,
            policy.judge(input(goodSelfCheck, evalOf(0.5))).recommendation,
        )
    }

    private fun evalOf(score: Double): com.example.zhengdao.baopu.EvolutionEval =
        com.example.zhengdao.baopu.EvolutionEval(score = score, caseIds = listOf("case-1"))

    // ── 真码行为③：A 档自改——没跑成对拍不许保留 ─────────────────────────────

    @Test
    fun `真码3_自改壳_对拍没跑成不许保留_过线无退化才保留`() {
        assertFalse(
            "NotRun = 没有证据 ⇒ 不许保留",
            AutonomyEvalGate.passedWithoutRegression(EvalOutcome.NotRun("对拍没跑：额度满", "quota")),
        )
        assertFalse(
            "Invalid = 证据作废 ⇒ 不许保留",
            AutonomyEvalGate.passedWithoutRegression(EvalOutcome.Invalid("快照不一致")),
        )
        assertFalse(
            "REJECT 判坏了 ⇒ 不许保留",
            AutonomyEvalGate.passedWithoutRegression(ran(EvalVerdict.REJECT)),
        )
        assertFalse(
            "结果列见「变差」⇒ 不许保留",
            AutonomyEvalGate.passedWithoutRegression(ran(EvalVerdict.ADOPT, resultDiff = listOf("产物变差：少 1 件"))),
        )
        assertTrue(
            "过线且无退化 ⇒ 才算有证据",
            AutonomyEvalGate.passedWithoutRegression(ran(EvalVerdict.ADOPT)),
        )
    }

    private fun ran(verdict: EvalVerdict, resultDiff: List<String> = emptyList()): EvalOutcome.Ran {
        val attempt = EvalAttempt(
            ok = true, steps = 2, toolCalls = listOf("read_file", "write_file"),
            failurePoint = null, elapsedMs = 10L, tokensIn = 1, tokensOut = 1, products = listOf("a.txt:abc"),
        )
        return EvalOutcome.Ran(
            compare = EvalCompare(
                fixtureId = "fx-1", skillName = "demo",
                old = attempt, new = attempt,
                behaviorDiff = emptyList(), resultDiff = resultDiff,
                verdict = verdict, reason = "对拍",
            ),
            table = "对拍行",
        )
    }

    // ── 源码锚点：三处证据闸的判据行不许被拆 ─────────────────────────────────

    @Test
    fun `源码锚点_账本证据闸_决策口证据id闸_自改对拍闸_三处都在`() {
        val ledger = GateIo.stripComments(
            GateIo.read("src/main/java/com/example/zhengdao/baopu/DecisionLedger.kt"),
        )
        assertTrue(
            "账本层的证据闸（evidence.isBlank ⇒ 不落）不见了",
            ledger.contains("evidence.isBlank()"),
        )
        val evolution = GateIo.stripComments(
            GateIo.read("src/main/java/com/example/zhengdao/baopu/EvolutionDecision.kt"),
        )
        assertTrue(
            "决策原因的证据 id 非空闸（evidenceIds.none { it.isBlank() }）不见了",
            evolution.contains("evidenceIds.none"),
        )
        val selfEdit = GateIo.stripComments(
            GateIo.read("src/main/java/com/example/zhengdao/baopu/AutonomySelfEdit.kt"),
        )
        assertTrue(
            "A 档自改落 208 的证据列（evidenceKey ＋ 对拍行）不见了",
            selfEdit.contains("AutonomyTell.evidenceKey(auto)") && selfEdit.contains("evalLine.take(80)"),
        )
    }

    // ── 负例①：假决策口没跑对拍也 ADOPT ⇒ 探针红 ────────────────────────────

    @Test
    fun `负例1_假决策口没证据也建议采纳_探针红_守法假实现不红`() {
        class FakeDecider(val hasEvalEvidence: Boolean, val evidenceIds: List<String>) {
            val recommendation: String = "ADOPT" // 有意写成「谁来都采纳」的坏形状
        }
        val rogue = FakeDecider(hasEvalEvidence = false, evidenceIds = emptyList())
        assertNotNull(
            "没跑对拍就 ADOPT 的假实现必须被探针抓住",
            EvidenceGate.adoptViolation(rogue.recommendation, rogue.hasEvalEvidence, rogue.evidenceIds),
        )
        val lawAbiding = FakeDecider(hasEvalEvidence = false, evidenceIds = emptyList()).let {
            object {
                val recommendation: String = "DEFER" // 没证据 ⇒ 搁置（守法形态）
                val hasEvalEvidence: Boolean = it.hasEvalEvidence
                val evidenceIds: List<String> = it.evidenceIds
            }
        }
        assertNull(
            "没证据建议搁置是守法形态，不许红",
            EvidenceGate.adoptViolation(lawAbiding.recommendation, lawAbiding.hasEvalEvidence, lawAbiding.evidenceIds),
        )
        val withEvidence = FakeDecider(hasEvalEvidence = true, evidenceIds = listOf("eval:fx-1"))
        assertNull(
            "有对拍证据＋证据 id 非空 ⇒ 允许采纳",
            EvidenceGate.adoptViolation(withEvidence.recommendation, withEvidence.hasEvalEvidence, withEvidence.evidenceIds),
        )
    }

    // ── 负例②：假账本缺证据也照落 ⇒ 探针红；真账本对同一条草稿整条拒绝 ────────

    @Test
    fun `负例2_假账本缺证据照落_探针红_真账本整条拒绝`() {
        class FakeRow(val evidence: String)

        class FakeLedger { // 有意不查证据的坏形状
            fun build(evidence: String): FakeRow = FakeRow(evidence)
        }
        val row = FakeLedger().build("")
        assertNotNull(
            "假账本落下的空账必须被探针抓住",
            EvidenceGate.ledgerViolation(row.evidence),
        )
        // 对照：真码是守法的——同一条空账在真 build 里整条拒绝（门禁盯的就是别让它退化成假实现）。
        assertNull(
            DecisionLedger.build(
                DecisionDraft(
                    kind = DecisionKind.SKILL_PROMOTE,
                    actor = DecisionActor.USER,
                    reason = "草稿转正",
                    evidence = "",
                    before = "draft",
                    after = "active",
                    atMs = 1L,
                ),
            ),
        )
    }
}
