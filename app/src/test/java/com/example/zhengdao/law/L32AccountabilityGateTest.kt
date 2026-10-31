// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十三批 · 法典门禁 **L32 追责收紧**（法典卷十 L32；316 对账时是五条真缺口之一，本批落地）。
//
// 判据落进生产 `baopu/law/AccountabilityGate.kt`（本批新建的判定层）：
// **同类第二次 ⇒ 权限必须收紧**（第一次放行只记账、第二次收紧两级回"必须先上奏"、
// 收紧后再犯＝永久需上奏且重开须御批）——派工单点名的"可测"就钉在这里。
//
// ## 判据（可执行，判的是生产 AccountabilityGate）
//   ① 认定要有效：双盲复核任一方提出＋御批确认，缺一 ⇒ 认定无效，不许动权限也不许记账；
//   ② 累进：同类（同类别＋同认定类型）窗口内有效认定第 1/2/≥3 次 ⇒ OPEN／PETITION_FIRST／PETITION_FOREVER；
//   ③ 收紧只落该类别：别的类别照旧（不许一竿子打翻）；别的认定类型分开累进；
//   ④ 窗口归零：30 天外的同类旧账不参与计数（归零的是计数不是记录）。
//
// ## 负例（造违规即红，红在生产判定上）
// 没有双盲提出的认定／没有御批确认的认定 ⇒ 不许收紧；无效认定不许替下一次攒次数；
// 空类别 ⇒ 拒收。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.law.AccountabilityFinding
import com.example.zhengdao.baopu.law.AccountabilityGate
import com.example.zhengdao.baopu.law.AccountabilityKind
import com.example.zhengdao.baopu.law.AccountabilityStanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class L32AccountabilityGateTest {

    private val t0 = 1_800_000_000_000L

    private fun finding(
        kind: AccountabilityKind = AccountabilityKind.OVER_WHITELIST,
        category: String = "隔离",
        atMs: Long = t0,
        raised: Boolean = true,
        confirmed: Boolean = true,
    ) = AccountabilityFinding(
        kind = kind,
        category = category,
        atMs = atMs,
        raisedByBlindReview = raised,
        confirmedByThrone = confirmed,
    )

    // ── 正例：第一次放行、第二次收紧、第三次永久——逐档可测 ──────────────────────

    @Test
    fun `L32_正例_第一次放行第二次收紧两级第三次永久且重开须御批`() {
        val day = 24L * 3600 * 1000
        // 第 1 次：记违规，权限照旧（放行——门不下死）
        val first = AccountabilityGate.decide(finding(), emptyList(), nowMs = t0)
        assertTrue(first.findingValid)
        assertEquals(AccountabilityStanding.OPEN, first.standing)
        assertFalse("第一次不许动权限", first.tightened)
        assertTrue("违规必须记账", first.violationRecorded)
        // 第 2 次（同类同类型，窗口内）：收紧两级，回「必须先上奏」
        val second = AccountabilityGate.decide(
            finding(atMs = t0 + day),
            listOf(finding(atMs = t0)),
            nowMs = t0 + day,
        )
        assertEquals("同类第二次必须收紧", AccountabilityStanding.PETITION_FIRST, second.standing)
        assertTrue(second.tightened)
        assertTrue(second.violationRecorded)
        assertFalse("收紧两级还不是永久", second.reopenRequiresImperialApproval)
        // 第 3 次（收紧后再犯）：永久需上奏，重开须御批
        val third = AccountabilityGate.decide(
            finding(atMs = t0 + 2 * day),
            listOf(finding(atMs = t0), finding(atMs = t0 + day)),
            nowMs = t0 + 2 * day,
        )
        assertEquals(AccountabilityStanding.PETITION_FOREVER, third.standing)
        assertTrue("永久档必须御批才能重开", third.reopenRequiresImperialApproval)
        assertEquals("累进计数＝此前有效次数", 2, third.priorCount)
    }

    @Test
    fun `L32_正例_收紧只落同类别同类型_别的类别照旧`() {
        val day = 24L * 3600 * 1000
        // 历史只有一条同类有效认定（OVER_WHITELIST／隔离）
        val history = listOf(finding(category = "隔离", atMs = t0))
        // 别的类别：第一次 ⇒ 照旧（收紧不许一竿子打翻所有类别）
        val otherCategory = AccountabilityGate.decide(
            finding(category = "回滚", atMs = t0 + day),
            history,
            nowMs = t0 + day,
        )
        assertEquals("别的类别不受牵连", AccountabilityStanding.OPEN, otherCategory.standing)
        // 同类别、别的认定类型：分开累进（本不该判灾异 ≠ 处置超白名单）
        val otherKind = AccountabilityGate.decide(
            finding(kind = AccountabilityKind.MISJUDGED_ANOMALY, atMs = t0 + day),
            history,
            nowMs = t0 + day,
        )
        assertEquals("别的认定类型分开算", AccountabilityStanding.OPEN, otherKind.standing)
        // 同类别同类型：第二次 ⇒ 收紧
        val same = AccountabilityGate.decide(
            finding(atMs = t0 + day),
            history,
            nowMs = t0 + day,
        )
        assertEquals("同类第二次必须收紧", AccountabilityStanding.PETITION_FIRST, same.standing)
    }

    // ── 负例：无御批／无双盲的认定不许动权限；窗口外旧账不计数 ────────────────────

    @Test
    fun `L32_负例_认定无效不许收紧也不许记账_更不许替下一次攒次数`() {
        // 双盲没提出：认定无效
        val noRaise = AccountabilityGate.decide(finding(raised = false), emptyList(), nowMs = t0)
        assertFalse(noRaise.findingValid)
        assertEquals("无效认定不许收紧", AccountabilityStanding.OPEN, noRaise.standing)
        assertFalse("无效认定连违规账都不许记", noRaise.violationRecorded)
        // 御批没确认：同样无效
        val noConfirm = AccountabilityGate.decide(finding(confirmed = false), emptyList(), nowMs = t0)
        assertFalse(noConfirm.findingValid)
        assertFalse(noConfirm.tightened)
        // 无效认定不许替下一次攒次数：无效第 1 次＋有效第 2 次 ⇒ 还是"第一次"（放行）
        val day = 24L * 3600 * 1000
        val afterInvalid = AccountabilityGate.decide(finding(atMs = t0 + day), listOf(finding(raised = false, atMs = t0)), nowMs = t0 + day)
        assertEquals("无效认定不计数", 0, afterInvalid.priorCount)
        assertEquals(AccountabilityStanding.OPEN, afterInvalid.standing)
        // 输入契约：空类别拒收（收紧落在类别上，没类别落不下去）
        assertThrows(IllegalArgumentException::class.java) {
            AccountabilityGate.decide(finding(category = "  "), emptyList(), nowMs = t0)
        }
    }

    @Test
    fun `L32_负例_窗口外旧账不计数_收紧随归零回到第一次的口径`() {
        val windowMs = AccountabilityGate.WINDOW_MS
        // 30 天前有一条同类旧账：窗口外 ⇒ 不参与计数 ⇒ 本次仍是"第一次"（放行）
        val stale = finding(atMs = t0 - windowMs - 1)
        val fresh = AccountabilityGate.decide(finding(atMs = t0), listOf(stale), nowMs = t0)
        assertEquals("窗口外旧账不参与计数", 0, fresh.priorCount)
        assertEquals("归零的是计数不是记录（账还在）", AccountabilityStanding.OPEN, fresh.standing)
        // 窗口内（正好 30 天）就算同类：两条 ⇒ 收紧
        val inWindow = finding(atMs = t0 - windowMs)
        val counted = AccountabilityGate.decide(finding(atMs = t0), listOf(inWindow), nowMs = t0)
        assertEquals("窗口边界（含 30 天整）内的旧账照数", 1, counted.priorCount)
        assertEquals(AccountabilityStanding.PETITION_FIRST, counted.standing)
        // 未来的账不参与（时钟回拨不许提前把累进顶满）
        val future = finding(atMs = t0 + 1)
        assertEquals(0, AccountabilityGate.decide(finding(atMs = t0), listOf(future), nowMs = t0).priorCount)
    }
}
