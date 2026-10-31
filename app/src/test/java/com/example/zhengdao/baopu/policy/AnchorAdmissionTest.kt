// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十批 · 锚定集与准入：判据二（任一条不一致 ⇒ 退回＋清单）、负例二（锚定集被静默改）。
//
// 这一套只测 policy 侧的结构与算式（AnchorSet／CriteriaEvolution）；JSONL 的只增不删
// 与签名台账由 `tools/check-criteria-gates.py` 在 CI 上用 git 历史再核一遍——
// 两边用同一把尺，但 CI 侧能看见「上一版」，JVM 侧只看得见「这一份」。
package com.example.zhengdao.baopu.policy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AnchorAdmissionTest {

    // ── fixtures ─────────────────────────────────────────────────────────

    private fun anchor(
        id: String,
        verdict: String = VERDICT_PASS,
        signed: Boolean = true,
        evidence: String = "FEATURE-LEDGER#row-$id",
    ) = AnchorCase(
        id = id,
        scene = "场景 $id",
        oldVerdict = "旧判据当时怎么判的",
        humanVerdict = verdict,
        evidence = evidence,
        screenedBy = "蓝总管",
        signedBy = if (signed) "老婆" else "",
        signedAt = if (signed) "2026-10-31" else "",
    )

    private fun signedAnchors(): List<AnchorCase> =
        listOf(anchor("A1"), anchor("A2"), anchor("A3", VERDICT_FAIL))

    private fun candidate(
        target: String = "app/src/main/java/com/example/zhengdao/baopu/eval/EvalFloor.kt#floorOf",
        verdicts: Map<String, String> = mapOf("A1" to "通过", "A2" to "通过", "A3" to "不通过"),
        anomalies: Set<String> = emptySet(),
    ) = CriteriaCandidate(
        id = "C-1",
        intent = "把 eval floor 的判据写得更可读",
        target = target,
        outputForm = CriteriaOutputForm.CHECK_ITEMS,
        origin = CriteriaOrigin.HUMAN_PROPOSED,
        replay = ReplayProof(moreBlocked = 3, lessBlocked = 0, moreAsked = 0, ranAtMs = 0L),
        verdicts = verdicts,
        exemptActions = anomalies,
    )

    // ── 状态三态 ─────────────────────────────────────────────────────────

    @Test
    fun `锚定集三态_空_未签齐_生效`() {
        assertEquals(AnchorStatus.EMPTY, AnchorSet.status(emptyList()))
        assertEquals(AnchorStatus.UNSIGNED, AnchorSet.status(listOf(anchor("A1"), anchor("A2", signed = false))))
        assertEquals(AnchorStatus.IN_FORCE, AnchorSet.status(signedAnchors()))
        assertEquals("生效条数只数三样齐的行", 1, AnchorSet.inForceCount(listOf(anchor("A1"), anchor("A2", signed = false))))
    }

    @Test
    fun `没有证据出处的行不算锚定样本`() {
        val noEvidence = anchor("A1", evidence = " ")
        assertFalse("证据空白 ⇒ 未生效", noEvidence.inForce())
        assertEquals(AnchorStatus.UNSIGNED, AnchorSet.status(listOf(noEvidence)))
    }

    // ── 只增不删（负例二：锚定集被静默改）─────────────────────────────────

    @Test
    fun `删掉一条锚定样本直接红`() {
        val before = signedAnchors()
        val after = before.filterNot { it.id == "A2" }
        val violation = AnchorSet.appendOnlyViolation(before, after)
        assertNotNull("删行必须被抓住", violation)
        assertTrue("要指名删了哪条：$violation", violation!!.contains("A2") && violation.contains("删行"))
    }

    @Test
    fun `旧行被改而不补签直接红_负例二`() {
        val before = signedAnchors()
        val tampered = before.map { if (it.id == "A1") it.copy(humanVerdict = VERDICT_FAIL) else it }
        val violation = AnchorSet.appendOnlyViolation(before, tampered)
        assertNotNull("改标准答案又不重新签字 ⇒ 必须红", violation)
        assertTrue("要指名 A1：$violation", violation!!.contains("A1"))
    }

    @Test
    fun `补签合法_新增合法`() {
        val before = listOf(anchor("A1"))
        val reSigned = listOf(
            anchor("A1").copy(signedBy = "老婆", signedAt = "2026-11-01"),
            anchor("A2"),
        )
        assertNull("只补签不改正文字段，不算改锚", AnchorSet.appendOnlyViolation(before, reSigned))
        assertNull("新增一条不算违规", AnchorSet.appendOnlyViolation(before, before + anchor("A2")))
    }

    // ── 一致比对：无法判定 ≠ 通过 ────────────────────────────────────────

    @Test
    fun `草案缺条或给不出两个关键词都算不一致`() {
        val cases = signedAnchors()
        val mismatches = AnchorSet.mismatches(
            cases,
            mapOf("A1" to "通过", "A3" to "基本通过"), // A2 缺条；A3 不是关键词打头
        )
        assertEquals("两条不合格", 2, mismatches.size)
        assertEquals(setOf("A2", "A3"), mismatches.map { it.anchorId }.toSet())
        assertEquals("缺条按无法判定记", VERDICT_UNKNOWN, mismatches.first { it.anchorId == "A2" }.candidateSide)
    }

    // ── 准入：fail closed ＋ 退回附清单 ──────────────────────────────────

    @Test
    fun `未签字的锚定集_不放行也不退回`() {
        val unsigned = listOf(anchor("A1", signed = false), anchor("A2", signed = false))
        val admission = CriteriaEvolution.admit(candidate(), unsigned)
        assertEquals(AdmissionState.REFUSED, admission.state)
        assertEquals(RefuseCode.ANCHOR_NOT_IN_FORCE, admission.refuseCode)
        assertTrue("未生效时连退回清单都不许产出（否则等于伪造一轮已核验）", admission.mismatches.isEmpty())
        assertTrue(
            "检查条目要写清为什么：",
            admission.checks.any { !it.passed && it.detail.contains("锚定集状态") },
        )
    }

    @Test
    fun `任一条不一致_退回候选区并附清单`() {
        val admission = CriteriaEvolution.admit(
            candidate(verdicts = mapOf("A1" to "通过", "A2" to "不通过", "A3" to "不通过")),
            signedAnchors(),
        )
        assertEquals(AdmissionState.RETURNED, admission.state)
        assertNull("退回不是硬拒，不挂拒绝码", admission.refuseCode)
        assertEquals("只有 A2 不一致", listOf("A2"), admission.mismatches.map { it.anchorId })
        val m = admission.mismatches.single()
        assertEquals(VERDICT_PASS, m.humanSide)
        assertEquals(VERDICT_FAIL, m.candidateSide)
        assertEquals("退回清单要带证据出处", "FEATURE-LEDGER#row-A2", m.evidence)
        assertFalse("检查条目要明写不通过", admission.checks.last().passed)
    }

    @Test
    fun `逐条同侧才准入`() {
        val admission = CriteriaEvolution.admit(candidate(), signedAnchors())
        assertEquals(AdmissionState.ADMIT, admission.state)
        assertTrue(admission.admitted())
        assertTrue("每次准入都留下逐条检查", admission.checks.size >= 4)
        assertTrue(admission.checks.all { it.passed })
    }

    // ── 真实锚定集（存在才核；不存在显式跳过）────────────────────────────

    private fun anchorFile(): File =
        listOf(
            File("../../_zd-private/docs/anchor-set.jsonl"), // 单测工作目录 = app/
            File("../_zd-private/docs/anchor-set.jsonl"), // 工作目录 = 仓根
        ).firstOrNull { it.exists() } ?: File("../../_zd-private/docs/anchor-set.jsonl")

    private fun parse(file: File): List<AnchorCase> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val o = JSONObject(line)
            AnchorCase(
                id = o.getString("id"),
                scene = o.getString("scene"),
                oldVerdict = o.getString("oldVerdict"),
                humanVerdict = o.getString("humanVerdict"),
                evidence = o.getString("evidence"),
                screenedBy = o.optString("screenedBy", ""),
                signedBy = o.optString("signedBy", ""),
                signedAt = o.optString("signedAt", ""),
            )
        }

    @Test
    fun `真实锚定集_条数三十到五十_id唯一_三样齐`() {
        val file = anchorFile()
        assumeTrue(
            "锚定集文件不在本机（CI 侧不可得是既有口径，显式跳过；append-only 由 python 门禁核）",
            file.exists(),
        )
        val cases = parse(file)
        assertTrue("锚定样本条数应在 30–50，实测 ${cases.size}", cases.size in 30..50)
        assertEquals("id 不许重复", cases.size, cases.map { it.id }.toSet().size)
        cases.forEach { case ->
            assertTrue("${case.id} 缺场景", case.scene.isNotBlank())
            assertTrue("${case.id} 缺旧判据结论", case.oldVerdict.isNotBlank())
            assertTrue("${case.id} 缺证据出处", case.evidence.isNotBlank())
            assertTrue("${case.id} 必须初筛人署名", case.screenedBy.isNotBlank())
            assertTrue(
                "${case.id} 人工结论只认「通过／不通过」打头：${case.humanVerdict}",
                case.side() != VERDICT_UNKNOWN,
            )
        }
        val signedCount = cases.count { it.signedBy.isNotBlank() }
        assertTrue(
            "签字要么整批要么没有，不许半批（半批＝有些行的标准答案没被授权）",
            signedCount == 0 || signedCount == cases.size,
        )
        val pass = cases.count { it.side() == VERDICT_PASS }
        val fail = cases.count { it.side() == VERDICT_FAIL }
        assertTrue(
            "锚定集不许一边倒（实测 通过 $pass／不通过 $fail）：全同一侧时，新判据只要照抄那一侧就能 100% 通过",
            pass > 0 && fail > 0,
        )
        assertEquals(
            "整批签齐 ⇒ IN_FORCE；一条没签 ⇒ UNSIGNED",
            if (signedCount == 0) AnchorStatus.UNSIGNED else AnchorStatus.IN_FORCE,
            AnchorSet.status(cases),
        )
    }
}
