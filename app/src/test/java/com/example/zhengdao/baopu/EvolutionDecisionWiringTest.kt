// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十八批 §3 的**接线与源码门禁**：候选只读、决策不写盘、不碰引擎/终端/系统/UI，
// 高风险永远先于人批开关。每条门禁都配一条负例样本——门禁本身必须能红。
package com.example.zhengdao.baopu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EvolutionDecisionWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String =
        repoFile(rel).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private val decisionSrc = raw("src/main/java/com/example/zhengdao/baopu/EvolutionDecision.kt")
    private val policySrc = raw("src/main/java/com/example/zhengdao/baopu/EvolutionPolicy.kt")
    private val auditSrc = raw("src/main/java/com/example/zhengdao/baopu/EvolutionAudit.kt")
    private val inputsSrc = raw("src/main/java/com/example/zhengdao/baopu/EvolutionInputs.kt")
    private val all = decisionSrc + policySrc + auditSrc + inputsSrc

    private fun candidate(id: String, createdAt: Long) = ProposalCandidate(
        id = id,
        kind = ProposalKind.SKILL_BODY,
        target = "技能A",
        payload = "候选正文-$id",
        evidence = ProposalEvidence(
            runLogIds = listOf(3L, 4L),
            skillEventIds = listOf(1L, 2L),
            atMs = 99L,
        ),
        createdAt = createdAt,
    )

    private fun okCheck() = SelfCheckEvidence(
        passed = true,
        rollbackAvailable = true,
        caseIds = listOf("selfcheck:case-1"),
    )

    private fun goodEval() = EvolutionEval(score = 0.9, caseIds = listOf("eval:case-1"))

    private fun goodEffect() = EffectSnapshot(
        successes = 3,
        signalIds = listOf("skill_event:11"),
    )

    private class RecordingSink : EvolutionAuditSink {
        val entries = mutableListOf<EvolutionAuditEntry>()
        override fun record(entry: EvolutionAuditEntry) {
            entries += entry
        }
    }

    // ── 候选只读 ────────────────────────────────────────────────────────────

    @Test
    fun `读候选_只调listPending_候选档零变化`() {
        val root = tmp.newFolder("store")
        val store = ProposalCandidateStore(root, log = {})
        assertEquals(2, store.appendNew(listOf(candidate("c1", 1L), candidate("c2", 2L)), stamp = 5L))
        val before = store.file().readBytes()

        val sink = RecordingSink()
        val port = EvolutionDecisionPort(audit = sink, clock = { 1L })
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

        assertEquals(listOf("c1", "c2"), decisions.map { it.candidateId })
        assertEquals("两条候选两条审计", 2, sink.entries.size)
        assertArrayEquals("决策口不许改候选档一个字节", before, store.file().readBytes())
        assertEquals("候选目录里不许出现审计/临时文件", 1, root.listFiles()!!.size)
        assertTrue("读口必须是 listPending", decisionSrc.contains("store.listPending()"))
        assertFalse("决策口不许追加候选", decisionSrc.contains("appendNew"))
    }

    @Test
    fun `候选到输入_只取id_kind_证据id`() {
        val source = candidate("c1", 1L)
        val input = toDecisionInput(
            source,
            EvolutionAssessment(
                riskCategory = EvolutionRiskCategory.SKILL_BODY_MINOR,
                selfCheck = okCheck(),
                eval = goodEval(),
                effect = goodEffect(),
            ),
        )
        assertEquals("c1", input.candidateId)
        assertEquals(ProposalKind.SKILL_BODY, input.kind)
        assertEquals(
            listOf("run_log:3", "run_log:4", "skill_event:1", "skill_event:2"),
            input.evidenceIds,
        )
        assertFalse("决策源码不许出现候选正文", decisionSrc.contains("payload"))
    }

    @Test
    fun `候选证据进原因_正文不进决策`() {
        val input = toDecisionInput(
            candidate("c1", 1L),
            EvolutionAssessment(
                riskCategory = EvolutionRiskCategory.SKILL_BODY_MINOR,
                selfCheck = okCheck(),
                eval = goodEval(),
                effect = goodEffect(),
            ),
        )
        val judgement = EvolutionPolicy().judge(input)
        val candidateReason = judgement.reasons.first {
            it.code == EvolutionReasonCode.CANDIDATE_EVIDENCE
        }
        assertEquals(
            listOf("run_log:3", "run_log:4", "skill_event:1", "skill_event:2"),
            candidateReason.evidenceIds,
        )
        assertFalse(judgement.toString().contains("候选正文-c1"))
    }

    // ── 不写盘（门禁＋负例） ────────────────────────────────────────────────

    private fun noDirectWrite(src: String): Boolean = listOf(
        "appendNew(",
        "applyProposal(",
        "SkillStore.apply(",
        "SkillStore.plan(",
        "writeLearnedSkill(",
        ".restore(",
        "PersonaFiles.",
        "Files.move",
        "SQLite",
        "execSQL",
        "CREATE TABLE",
    ).none { src.contains(it) }

    @Test
    fun `决策口不写盘_负例必须红`() {
        assertTrue("真源码必须过门禁", noDirectWrite(all))
        assertFalse(
            "负例：决策口追加候选必须红",
            noDirectWrite("store.appendNew(listOf(candidate))"),
        )
        assertFalse(
            "负例：决策口写到人设必须红",
            noDirectWrite("PersonaFiles.applyProposal(proposal)"),
        )
        assertFalse(
            "负例：决策口写到技能必须红",
            noDirectWrite("SkillStore.apply(plan, pf, text)"),
        )
        assertFalse(
            "负例：决策口执行回滚必须红",
            noDirectWrite("snapshot.restore(backup)"),
        )
    }

    // ── 高风险永远先于人批开关（门禁＋负例） ────────────────────────────────

    private fun highRiskGuarded(src: String): Boolean {
        val start = src.indexOf("private fun approval(")
        if (start < 0) return false
        val end = src.indexOf("private fun confidence(", start)
        if (end < 0) return false
        val block = src.substring(start, end)
        val high = block.indexOf("EvolutionRisk.HIGH")
        val l3 = block.indexOf("autoLowRiskEnabled")
        return high >= 0 && l3 >= 0 && high < l3
    }

    @Test
    fun `高风险自动批_负例必须红`() {
        assertTrue("真策略必须高风险先返回", highRiskGuarded(policySrc))
        val bad = """
            private fun approval(input: EvolutionDecisionInput): Boolean {
                if (!autoLowRiskEnabled) return true
                if (riskClass == EvolutionRisk.HIGH) return false
                return true
            }
            private fun confidence(input: EvolutionDecisionInput): Double = 0.5
        """.trimIndent()
        assertFalse("负例：把 L3 开关放到高风险之前必须红", highRiskGuarded(bad))
    }

    // ── 红线路径与 UI ───────────────────────────────────────────────────────

    @Test
    fun `不碰引擎终端rootfs与UI`() {
        for (token in listOf(
            "RunEngine",
            "TerminalActivity",
            "rootfs/",
            "androidx.compose",
            "ui.baopu",
            "Compose",
        )) {
            assertFalse("新逻辑不许碰：$token", all.contains(token))
        }
        val engine = raw("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        val controller = raw("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        // 第九十九批起边界改写：控制器是**合法消费点**（收尾判定 → 审计 → 可观察状态），
        // 引擎仍然一行不接。旧的「控制器不许出现」门禁在这里换成「必须接上」——防的是
        // 接线被悄悄删掉又变回死代码（判据：EvolutionDecision 有非测试引用）。
        for (token in listOf("EvolutionDecision", "EvolutionPolicy", "EvolutionAudit")) {
            assertFalse("RunEngine 不许接线：$token", engine.contains(token))
        }
        assertTrue("控制器必须接上决策口", controller.contains("EvolutionDecisionPort"))
        assertTrue("判定链必须用 EvolutionDecision", controller.contains("EvolutionDecision"))
        assertTrue("审计落点必须接上", controller.contains("EvolutionAuditLog"))
    }

    @Test
    fun `没有新表_migration_SQL_版本号`() {
        for (token in listOf(
            "SQLite",
            "execSQL",
            "CREATE TABLE",
            "migration",
            "versionCode",
            "versionName",
        )) {
            assertFalse("本批不改：$token", all.contains(token))
        }
    }

    @Test
    fun `审计源_不出现正文类字段`() {
        for (key in listOf("\"payload\"", "\"body\"", "\"content\"", "\"detail\"", "\"text\"")) {
            assertFalse("审计不许出现正文键：$key", auditSrc.contains(key))
        }
    }
}
