// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百七十二批 · 会话级三档处置的门禁测试（生产判定在 baopu/law/DispositionGate.kt）。
//
// ## 判据（对应开工令五条验收）
//   ① 五条判定各有正负例（源级钉／有据／二阶升级／可申诉／恢复条件）；
//   ② 四类不可推翻边界各有反例（圣旨／账本／判据／权限），账本另加四条契约
//      （改中间行拒／删行拒／原样重写拒／末尾追加过——钉的是**账本**对 appendOnly 的期望，
//      PersonaRules.appendOnly 的语义被人动时这里先红）；
//   ③ 裁决入口只有人能裁（decidedBy 白名单只有 DECIDER_USER，其余全拒）；
//   ④ 圣旨边界另加一条结构钉：RoyalDecisionLedger 只有 record 这一条写入路，
//      没有 update/delete/rewrite——「只追加、改动须新裁决」是账本的形状，不是注释。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.agent.RoyalDecisionLedger
import com.example.zhengdao.baopu.authz.TaskGrantGate
import com.example.zhengdao.baopu.law.Disposition
import com.example.zhengdao.baopu.law.DispositionGate
import com.example.zhengdao.baopu.law.DispositionOutcome
import com.example.zhengdao.baopu.law.DispositionRequest
import com.example.zhengdao.baopu.law.DispositionTier
import com.example.zhengdao.baopu.policy.ImmutableCriteria
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DispositionGateTest {

    // ── 组装工具（默认值全部合法：负例只破坏被测的那一格） ──────────────────────

    /** 两张同 target 的 FREEZE（判定③的入场券）。 */
    private fun twoFreezes(target: String): List<Disposition> = listOf(
        Disposition(
            tier = DispositionTier.FREEZE,
            target = target,
            reason = "第一次犯",
            evidence = "影子账 MISMATCH",
            appealable = true,
            restoreCondition = "御批解除",
            decidedBy = DispositionGate.DECIDER_USER,
            at = 1L,
        ),
        Disposition(
            tier = DispositionTier.FREEZE,
            target = target,
            reason = "第二次犯",
            evidence = "影子账又 MISMATCH",
            appealable = true,
            restoreCondition = "御批解除",
            decidedBy = DispositionGate.DECIDER_USER,
            at = 2L,
        ),
    )

    private fun request(
        tier: DispositionTier = DispositionTier.REPLY,
        target: String = "agent/SomeTool#step",
        reason: String = "连续三步未推进",
        evidence: String = "影子账三连 MISMATCH",
        restoreCondition: String = "御批解除后恢复",
        decidedBy: String = DispositionGate.DECIDER_USER,
        appealable: Boolean = tier != DispositionTier.REPLY,
        priorFreezes: List<Disposition> = emptyList(),
        ledgerCurrent: String = "",
        ledgerProposed: String = "",
        edictOpinionIds: Set<String> = emptySet(),
    ): DispositionRequest = DispositionRequest(
        tier = tier,
        target = target,
        reason = reason,
        evidence = evidence,
        restoreCondition = restoreCondition,
        decidedBy = decidedBy,
        at = 3L,
        appealable = appealable,
        priorFreezes = priorFreezes,
        ledgerCurrent = ledgerCurrent,
        ledgerProposed = ledgerProposed,
        edictOpinionIds = edictOpinionIds,
    )

    private fun rejectOf(r: DispositionRequest): DispositionOutcome.Rejected {
        val outcome = DispositionGate.decide(r)
        assertTrue("这一格本该拒（code=${(outcome as? DispositionOutcome.Rejected)?.code}）", outcome is DispositionOutcome.Rejected)
        return outcome as DispositionOutcome.Rejected
    }

    private fun allowOf(r: DispositionRequest): Disposition {
        val outcome = DispositionGate.decide(r)
        assertTrue("这一格本该放（reason=${(outcome as? DispositionOutcome.Rejected)?.reason}）", outcome is DispositionOutcome.Allowed)
        return (outcome as DispositionOutcome.Allowed).disposition
    }

    // ── ① 源级钉：只许中心路径发起 ─────────────────────────────────────────────

    @Test
    fun `规则一_正例_中心路径user发起_放行`() {
        val d = allowOf(request(tier = DispositionTier.REPLY))
        assertEquals(DispositionTier.REPLY, d.tier)
        assertEquals(DispositionGate.DECIDER_USER, d.decidedBy)
        assertEquals("agent/SomeTool#step", d.target)
    }

    @Test
    fun `规则一_负例_使臣团或执行体自己发起_拒`() {
        listOf("envoy", "executor", "chancellor", "model", "agent团").forEach { who ->
            val rejected = rejectOf(request(decidedBy = who))
            assertEquals("非中心路径 $who 必须拒", DispositionGate.CODE_NOT_CENTER, rejected.code)
        }
    }

    // ── ② 有据 ────────────────────────────────────────────────────────────────

    @Test
    fun `规则二_正例_证据在手_放行`() {
        allowOf(request(evidence = "影子账三连 MISMATCH，账行见 208"))
    }

    @Test
    fun `规则二_负例_证据空白_拒`() {
        listOf("", "   ").forEach { blank ->
            val rejected = rejectOf(request(evidence = blank))
            assertEquals("evidence 空白必须拒", DispositionGate.CODE_NO_EVIDENCE, rejected.code)
        }
    }

    // ── ③ 二阶升级 ────────────────────────────────────────────────────────────

    @Test
    fun `规则三_正例_同target冻结两次仍犯_镇压放行`() {
        val target = "agent/SomeTool#step"
        val d = allowOf(
            request(tier = DispositionTier.SUPPRESS, target = target, priorFreezes = twoFreezes(target)),
        )
        assertEquals(DispositionTier.SUPPRESS, d.tier)
        assertTrue("镇压必须可申诉（仲裁归人）", d.appealable)
    }

    @Test
    fun `规则三_负例_没冻结够两次就想镇压_拒`() {
        val target = "agent/SomeTool#step"
        listOf(
            "零次冻结" to emptyList(),
            "只冻结一次" to twoFreezes(target).take(1),
        ).forEach { (label, priors) ->
            val rejected = rejectOf(
                request(tier = DispositionTier.SUPPRESS, target = target, priorFreezes = priors),
            )
            assertEquals("$label 不许一步到镇压", DispositionGate.CODE_ESCALATION_NOT_EARNED, rejected.code)
        }
    }

    @Test
    fun `规则三_负例_别处的冻结不算数_拒`() {
        val rejected = rejectOf(
            request(
                tier = DispositionTier.SUPPRESS,
                target = "agent/SomeTool#step",
                priorFreezes = twoFreezes("agent/OtherTool#step"),
            ),
        )
        assertEquals("同 target 才累进", DispositionGate.CODE_ESCALATION_NOT_EARNED, rejected.code)
    }

    // ── ④ 可申诉 ──────────────────────────────────────────────────────────────

    @Test
    fun `规则四_负例_冻结或镇压不可申诉_拒`() {
        listOf(DispositionTier.FREEZE, DispositionTier.SUPPRESS).forEach { tier ->
            val r = if (tier == DispositionTier.SUPPRESS) {
                request(tier = tier, appealable = false, priorFreezes = twoFreezes("agent/SomeTool#step"))
            } else {
                request(tier = tier, appealable = false)
            }
            val rejected = rejectOf(r)
            assertEquals("$tier 不可申诉必须拒", DispositionGate.CODE_NOT_APPEALABLE, rejected.code)
        }
    }

    // ── ⑤ 恢复条件必填 ────────────────────────────────────────────────────────

    @Test
    fun `规则五_负例_恢复条件空白_拒`() {
        listOf("", "  ").forEach { blank ->
            val rejected = rejectOf(request(restoreCondition = blank))
            assertEquals("没有「怎么解」的处置不许落", DispositionGate.CODE_NO_RESTORE, rejected.code)
        }
    }

    // ── 边界·圣旨：御批账不许被处置覆盖 ───────────────────────────────────────

    @Test
    fun `边界圣旨_负例_处置对象指向御批账文件_拒`() {
        val rejected = rejectOf(request(target = "记忆/RoyalDecisionLedger/${RoyalDecisionLedger.FILE_NAME}"))
        assertEquals(DispositionGate.CODE_EDICT_IMMUTABLE, rejected.code)
    }

    @Test
    fun `边界圣旨_负例_处置对象是御批账上已裁定的意见_拒`() {
        val rejected = rejectOf(
            request(
                target = "opinion-42",
                edictOpinionIds = setOf("opinion-42", "opinion-7"),
            ),
        )
        assertEquals(DispositionGate.CODE_EDICT_IMMUTABLE, rejected.code)
    }

    @Test
    fun `边界圣旨_结构钉_御批账只追加_没有改写的第二条路`() {
        val src = LawIo.stripComments(LawIo.read("src/main/java/com/example/zhengdao/baopu/agent/RoyalDecisionLedger.kt"))
        assertTrue("御批账的写入路只有 record（改动须新裁决就是它）", src.contains("fun record("))
        listOf("fun update", "fun delete", "fun remove", "fun rewrite", "fun overwrite", "fun edit").forEach { mutator ->
            assertFalse("御批账不该有改写口 $mutator（只追加）", src.contains(mutator))
        }
    }

    // ── 边界·账本：四条契约（钉账本对 appendOnly 的期望） ──────────────────────

    @Test
    fun `边界账本_契约一_改中间一行_拒`() {
        val current = "第一行\n第二行\n第三行\n"
        val proposed = "第一行\n第二行改了\n第三行\n"
        val rejected = rejectOf(request(ledgerCurrent = current, ledgerProposed = proposed))
        assertEquals(DispositionGate.CODE_LEDGER_APPEND_ONLY, rejected.code)
    }

    @Test
    fun `边界账本_契约二_删一行_拒`() {
        val current = "第一行\n第二行\n第三行\n"
        val proposed = "第一行\n第三行\n"
        val rejected = rejectOf(request(ledgerCurrent = current, ledgerProposed = proposed))
        assertEquals(DispositionGate.CODE_LEDGER_APPEND_ONLY, rejected.code)
    }

    @Test
    fun `边界账本_契约三_原文原样重写没变长_拒`() {
        val current = "第一行\n第二行\n"
        val proposed = "第一行\n第二行\n"
        val rejected = rejectOf(request(ledgerCurrent = current, ledgerProposed = proposed))
        assertEquals(DispositionGate.CODE_LEDGER_APPEND_ONLY, rejected.code)
    }

    @Test
    fun `边界账本_契约四_末尾追加一行_过`() {
        val current = "第一行\n第二行\n"
        val proposed = "第一行\n第二行\n第三行\n"
        allowOf(request(ledgerCurrent = current, ledgerProposed = proposed))
    }

    // ── 边界·判据：不可进化清单不许被处置 ─────────────────────────────────────

    @Test
    fun `边界判据_负例_处置对象命中判据锚点_拒`() {
        val anchor = ImmutableCriteria.ALL.first().anchors.first()
        val rejected = rejectOf(request(target = anchor))
        assertEquals(DispositionGate.CODE_CRITERIA_IMMUTABLE, rejected.code)
    }

    @Test
    fun `边界判据_负例_理由或证据里出现开洞词_拒`() {
        listOf(
            "理由栏开洞" to request(reason = "把这条判据改成可配置的"),
            "证据栏开洞" to request(evidence = "建议免批自动通过"),
        ).forEach { (label, r) ->
            val rejected = rejectOf(r)
            assertEquals("$label 必须拒", DispositionGate.CODE_CRITERIA_RELAXATION, rejected.code)
        }
    }

    // ── 边界·权限：授权边界不因处置被覆盖 ─────────────────────────────────────

    @Test
    fun `边界权限_负例_处置对象点名授权边界结论码_拒`() {
        listOf(
            TaskGrantGate.CODE_HARD_DENY,
            TaskGrantGate.CODE_PROTECTED,
            TaskGrantGate.CODE_ACTION_BANNED,
        ).forEach { code ->
            val rejected = rejectOf(request(target = "TaskGrantGate/$code"))
            assertEquals("授权边界 $code 不许被处置", DispositionGate.CODE_GRANT_IMMUTABLE, rejected.code)
        }
    }

    // ── 裁决入口只有人能裁 ────────────────────────────────────────────────────

    @Test
    fun `裁决入口_只有人能裁_其余全拒`() {
        val everyone = listOf("envoy", "executor", "chancellor", "model", "system", "law", "gate")
        everyone.forEach { who ->
            assertEquals(
                "$who 不是裁决人",
                DispositionGate.CODE_NOT_CENTER,
                rejectOf(request(decidedBy = who)).code,
            )
        }
        assertNull("唯一裁决人是 user", rejectOfOrNull(request(decidedBy = DispositionGate.DECIDER_USER)))
    }

    @Test
    fun `裁决入口_被拒的申请产不出处置`() {
        val outcome = DispositionGate.decide(request(decidedBy = "executor"))
        assertTrue(outcome is DispositionOutcome.Rejected)
        assertFalse(outcome is DispositionOutcome.Allowed)
    }

    private fun rejectOfOrNull(r: DispositionRequest): DispositionOutcome.Rejected? =
        DispositionGate.decide(r) as? DispositionOutcome.Rejected
}
