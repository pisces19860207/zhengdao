// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L20 未经御批的制度变更不得生效**（派工单 271）
// ＋ 同一号在法典卷六的另一半：**改完必对拍**（不通过对拍就按原字节回滚）。
//
// ## 判据（可执行）
// ① **制度类改动（B/C 档）不许自动写盘**：`AutonomyRules.decide` 对 B 档必须给
//    `NeedsApproval`、对 C 档必须给 `NeverAuto`，且 C 档**连确认卡都不许摆**
//    （`offerableAsApproval == false`）；
// ② **执行壳必须短路**：`AutonomySelfEdit.run` 的第一句就是「判据不是 A ⇒ 直接 NotWritten」，
//    拆掉这一句，B/C 档就会被写下去。
//
// ## 负例（本文件自证会红）
// ① 改契约（B 档）若被判成可自动写 ⇒ 红；权限位（C 档）若被当成可确认 ⇒ 红；
// ② `run` 的短路块若被拆掉 ⇒ 源码探针红（那就是"跳过御批直接生效"的样子）。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.AutonomyChangeKind
import com.example.zhengdao.baopu.AutonomyProposal
import com.example.zhengdao.baopu.AutonomyRules
import com.example.zhengdao.baopu.AutonomyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L20ApprovalBeforeEffectGateTest {

    private fun proposal(
        kind: AutonomyChangeKind,
        current: String = "# 整理台账\n- 按日期排",
        next: String = "# 整理台账\n- 按日期从早到晚排",
        scopeProject: Boolean = true,
        rollbackReady: Boolean = true,
        auditReady: Boolean = true,
        evalRunnable: Boolean = true,
    ) = AutonomyProposal(
        kind = kind,
        target = "整理台账",
        currentText = current,
        newText = next,
        scopeProject = scopeProject,
        rollbackReady = rollbackReady,
        auditReady = auditReady,
        evalRunnable = evalRunnable,
    )

    // ── 正例：A 档（可回滚+留痕+可对拍）才允许自动写 ────────────────────────

    @Test
    fun `L20_正例_A档三件齐才可自动写_其余一律要人批`() {
        val verdict = AutonomyRules.decide(proposal(AutonomyChangeKind.SKILL_WORDING_EDIT))
        assertTrue("A 档三件齐应放行自动写：$verdict", verdict is AutonomyVerdict.AutoEdit)
        assertFalse("A 档不该弹确认", verdict.requiresApproval)

        // 三件缺任何一件 ⇒ 降回人批（不是"提醒一下"）
        listOf(
            proposal(AutonomyChangeKind.SKILL_WORDING_EDIT, rollbackReady = false),
            proposal(AutonomyChangeKind.SKILL_WORDING_EDIT, auditReady = false),
            proposal(AutonomyChangeKind.SKILL_WORDING_EDIT, evalRunnable = false),
            proposal(AutonomyChangeKind.SKILL_WORDING_EDIT, scopeProject = false),
        ).forEach { p ->
            assertTrue(
                "三件缺一必须降回人批（$p）",
                AutonomyRules.decide(p) is AutonomyVerdict.NeedsApproval,
            )
        }
    }

    // ── 负例①：制度类（B/C 档）不许自动生效 ────────────────────────────────

    @Test
    fun `L20_负例1_制度变更跳过御批必须被拒`() {
        val contractEdit = AutonomyRules.decide(proposal(AutonomyChangeKind.CONTRACT_EDIT))
        assertFalse("改契约（B 档）被自动写了＝绕过御批", contractEdit is AutonomyVerdict.AutoEdit)
        assertTrue("改契约必须人批：$contractEdit", contractEdit is AutonomyVerdict.NeedsApproval)
        assertTrue("B 档必须弹确认", contractEdit.requiresApproval)

        val permission = AutonomyRules.decide(proposal(AutonomyChangeKind.PERMISSION_BIT))
        assertFalse("改权限位（C 档）被自动写了", permission is AutonomyVerdict.AutoEdit)
        assertTrue("权限位必须永不自改：$permission", permission is AutonomyVerdict.NeverAuto)
        assertFalse("C 档连确认卡都不许摆", AutonomyRules.offerableAsApproval(AutonomyChangeKind.PERMISSION_BIT))

        // 对照：A 档是可确认的（防「一律不可确认」的假绿）
        assertTrue("A 档当然可以摆确认卡", AutonomyRules.offerableAsApproval(AutonomyChangeKind.SKILL_WORDING_EDIT))
        // 冒充：声明成 A 却动了契约行 ⇒ 按真实的 B 档重判
        val smuggled = AutonomyRules.decide(
            proposal(
                AutonomyChangeKind.SKILL_WORDING_EDIT,
                current = "# 台账\n<!-- contract appliesWhen url_domain=a.com -->\n- 排",
                next = "# 台账\n<!-- contract appliesWhen url_domain=b.com -->\n- 排",
            ),
        )
        assertFalse("声明成 A 却改了契约行，不许当 A 写下去", smuggled is AutonomyVerdict.AutoEdit)
    }

    // ── 负例②：执行壳必须短路（拆了就红） ──────────────────────────────────

    @Test
    fun `L20_负例2_执行壳的御批短路块必须在`() {
        val src = LawIo.codeOnly(LawIo.read("${LawIo.MAIN_ROOT}/baopu/AutonomySelfEdit.kt"))
        assertTrue(
            "AutonomySelfEdit.run 的「判据不是 A ⇒ NotWritten」短路不见了：B/C 档会被直接写下去",
            src.contains("verdict !is AutonomyVerdict.AutoEdit") && src.contains("SelfEditOutcome.NotWritten"),
        )
        assertEquals(
            "对拍闸必须存在（改完必对拍，不通过就回滚）",
            true,
            Regex("""AutonomyEvalGate\.passedWithoutRegression""").containsMatchIn(src),
        )
    }
}
