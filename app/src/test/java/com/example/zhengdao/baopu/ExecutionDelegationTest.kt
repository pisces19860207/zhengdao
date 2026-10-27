// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十六批 · §2.5 **执行权下放**的判据（派工单 §2.5 四条负例逐条红＋三度量）。
//
// 用户原话是这层的存在理由：「执行权是下放了吧？不然人得累死」。但下放最容易出的四种事，
// 派工单 §2.5.4 点得很死，本文件逐条钉：
//   ① 把 R2 当 R1 免问（越权）；
//   ② 免问后不留回滚点（退不回去）；
//   ③ 无人应答时默认通过（把"没人点"当"点了"）；
//   ④ 免问回滚率 >5% 还不收回（下放没有度量＝把风险藏起来）。
//
// 另钉两条边界：**R0 早就全放**（本层不冒领）与**档位不由本层判**（只吃 219 的结论，
// 不数关键词升档——那是第二张表，223 立过规矩）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionDelegationTest {

    private fun verdict(
        risk: RiskClass?,
        decision: RiskDecision = RiskDecision.PROCEED,
        code: RiskCode = RiskCode.NONE,
    ): RiskVerdict = RiskVerdict(
        risk = risk,
        decision = decision,
        reasons = if (code == RiskCode.NONE) emptyList() else listOf(RiskReason(code, "测试判据")),
    )

    private fun facts(
        risk: RiskClass?,
        decision: RiskDecision = RiskDecision.PROCEED,
        permission: ToolPermission = ToolPermission.CONFIRM,
        inside: Boolean = true,
        reversible: Boolean = true,
        rollback: Boolean = true,
        cClass: Boolean = false,
        human: Boolean = true,
        preauth: Boolean = false,
        silent: Int = 0,
        rolled: Int = 0,
        code: RiskCode = RiskCode.NONE,
        tool: String = "write_file",
    ): DelegationFacts = DelegationFacts(
        verdict = verdict(risk, decision, code),
        tool = tool,
        permission = permission,
        insideWorkspace = inside,
        reversible = reversible,
        rollbackPointReady = rollback,
        cClassTarget = cClass,
        humanPresent = human,
        scheduledTaskPreauth = preauth,
        silentActs = silent,
        rollbacksAfterSilent = rolled,
    )

    /** R1 三条件齐的"最松"输入：任何一条负例都是在这上面改一个事实，而不是改档位。 */
    private fun loose(risk: RiskClass?, block: DelegationFacts.() -> DelegationFacts = { this }) =
        DelegationRules.decide(facts(risk).run(block))

    // ── 正例：下放确实省掉了那次点击 ────────────────────────────────────────

    @Test
    fun `正例_R1三条件齐_免问直行且要求回滚点`() {
        val v = DelegationRules.decide(facts(RiskClass.R1))
        val silent = v as? DelegationVerdict.SilentAct
        assertNotNull("R1 三条件齐却没免问：$v", silent)
        assertTrue("免问却不要求回滚点（负例②的形状）：$v", silent!!.requireRollbackPoint)
        assertFalse("免问还弹卡＝没下放", v.popsCard)
        assertTrue(DelegationTell.line(v).contains("免问直行"))
    }

    @Test
    fun `正例_定时任务的预授权只管R1_话术点名是谁点的头`() {
        val v = DelegationRules.decide(facts(RiskClass.R1, preauth = true))
        assertTrue(v is DelegationVerdict.SilentAct)
        assertTrue("话术没说清依据：${DelegationTell.line(v)}", DelegationTell.line(v).contains("建任务时人点过头"))
        // 预授权救不了 R2/R3——那是"建任务时点过头"管不到的面
        assertTrue(DelegationRules.decide(facts(RiskClass.R2, preauth = true)) is DelegationVerdict.Ask)
        assertTrue(DelegationRules.decide(facts(RiskClass.R3, preauth = true)) is DelegationVerdict.Ask)
    }

    @Test
    fun `正例_R0早就全放_本层不冒领`() {
        val free = DelegationRules.decide(facts(RiskClass.R0, permission = ToolPermission.FREE))
        assertTrue("R0 的 FREE 被算成本层放的权：$free", free is DelegationVerdict.NotApplicable)
        val confirmR0 = DelegationRules.decide(facts(RiskClass.R0, permission = ToolPermission.CONFIRM))
        val silent = confirmR0 as? DelegationVerdict.SilentAct
        assertNotNull("R0 的 CONFIRM 位没被下放：$confirmR0", silent)
        assertFalse("读类动作被要求留回滚点（没有东西可退）：$silent", silent!!.requireRollbackPoint)
    }

    @Test
    fun `正例_矩阵结论优先_硬拒与豁免都不接管`() {
        val reject = DelegationRules.decide(facts(RiskClass.R1, decision = RiskDecision.REJECT, code = RiskCode.PASSWORD_FIELD))
        assertTrue(reject is DelegationVerdict.NotApplicable)
        assertFalse("矩阵已判死的动作被免问了", reject.popsCard)
        val exempt = DelegationRules.decide(facts(null, decision = RiskDecision.EXEMPT, tool = "bash"))
        assertTrue(exempt is DelegationVerdict.NotApplicable)
        // 没有档位（未分类 fail-closed 的那一侧）⇒ 本层一个字都不改它的命运
        val none = DelegationRules.decide(facts(null))
        assertTrue(none is DelegationVerdict.NotApplicable)
    }

    // ── 负例①：R2 绝不能被当成 R1 免问 ──────────────────────────────────────

    @Test
    fun `负例1_R2当R1免问_四样各自都问`() {
        for (risk in listOf(RiskClass.R2, RiskClass.R3)) {
            val ask = loose(risk) as? DelegationVerdict.Ask
            assertNotNull("$risk 三条件一齐就免问了（负例①）", ask)
            assertTrue("$risk 免问了：$ask", ask!!.reasons.isNotEmpty())
            assertTrue("$risk 没弹卡", ask.popsCard)
        }
        // C 类目标（权限位/风险档/白名单/确认口径）：档位再低也问
        val c = DelegationRules.decide(facts(RiskClass.R1, cClass = true))
        assertTrue("C 类被免问：$c", c is DelegationVerdict.Ask)
        assertTrue(DelegationTell.line(c).contains("只能人手动"))
        // 矩阵点名必问（未分类/批量超限/表单提交）⇒ 不因为"看着像 R1"而绕过
        val must = DelegationRules.decide(facts(RiskClass.R1, decision = RiskDecision.MUST_ASK, code = RiskCode.BATCH_OVER_20))
        assertTrue("mustAsk 被免问：$must", must is DelegationVerdict.Ask)
        // R1 三条件缺一即问——"工作区外"这一条最容易被漏
        assertTrue(DelegationRules.decide(facts(RiskClass.R1, inside = false)) is DelegationVerdict.Ask)
        assertTrue(DelegationRules.decide(facts(RiskClass.R1, reversible = false)) is DelegationVerdict.Ask)
    }

    // ── 负例②：免问必须留回滚点 ─────────────────────────────────────────────

    @Test
    fun `负例2_免问后不留回滚点_这次免问作废`() {
        val v = DelegationRules.decide(facts(RiskClass.R1)) as DelegationVerdict.SilentAct
        assertFalse("回滚点没落成还允许写盘", DelegationRules.rollbackPointAccepted(v, backupWritten = false))
        assertTrue(DelegationRules.rollbackPointAccepted(v, backupWritten = true))
        // 判据侧同理：写之前就还没落成 ⇒ 根本不进免问
        val noPoint = DelegationRules.decide(facts(RiskClass.R1, rollback = false))
        assertTrue("回滚点没落成照样免问（负例②）：$noPoint", noPoint is DelegationVerdict.Ask)
        assertTrue(DelegationTell.line(noPoint).contains("回滚点"))
        // R0 不要求回滚点（没有"原件"要保）
        val r0 = DelegationRules.decide(facts(RiskClass.R0, permission = ToolPermission.CONFIRM)) as DelegationVerdict.SilentAct
        assertTrue(DelegationRules.rollbackPointAccepted(r0, backupWritten = false))
        // 非免问的态一律 true：不许"要问的卡"被这句判成不许写
        assertTrue(DelegationRules.rollbackPointAccepted(DelegationVerdict.Ask("x", listOf("R2")), false))
    }

    // ── 负例③：无人应答只停，不替点 ─────────────────────────────────────────

    @Test
    fun `负例3_无人时默认通过_停下且说不清就不写`() {
        for (risk in listOf(RiskClass.R2, RiskClass.R3)) {
            val stop = DelegationRules.decide(facts(risk, human = false)) as? DelegationVerdict.StopAndWait
            assertNotNull("$risk 无人时竞不是停", stop)
            assertFalse("无人还摆卡（弹给谁看）", stop!!.popsCard)
            val text = DelegationTell.stopText(stop)
            assertTrue("话术会被模型读成\"已经做了\"：$text", text.contains("没执行"))
            assertTrue("话术没点明不替人点：$text", text.contains("不会替你点"))
        }
        val c = DelegationRules.decide(facts(RiskClass.R1, cClass = true, human = false))
        assertTrue("C 类无人时竞通过了：$c", c is DelegationVerdict.StopAndWait)
        // 无人 ⇒ 一条 Ask 都不许出现（没有可弹的对象；出现就是"摆给空气看然后超时默认通过"）
        for (risk in listOf(RiskClass.R1, RiskClass.R2, RiskClass.R3)) {
            val v = DelegationRules.decide(facts(risk, human = false))
            assertFalse("$risk 无人时竞摆了卡：$v", v is DelegationVerdict.Ask)
        }
        // 反过来：免问这条路**与人在不在无关**（R1 三条件齐本来就是设计成不打扰人的；
        // 把"没人"错当成"停"，下放就等于没下放）
        assertTrue(DelegationRules.decide(facts(RiskClass.R1, human = false)) is DelegationVerdict.SilentAct)
        // R0 的既有权利不被本层收回：无人时照旧不参与
        assertTrue(
            DelegationRules.decide(facts(RiskClass.R0, permission = ToolPermission.FREE, human = false))
                is DelegationVerdict.NotApplicable,
        )
        assertTrue(DelegationTell.line(DelegationRules.decide(facts(RiskClass.R3, human = false))).contains("停下等人"))
    }

    // ── 负例④：回滚率超标必须自己收回 ───────────────────────────────────────

    @Test
    fun `负例4_回滚率超标还不收回_免问这条路自己关掉`() {
        val over = DelegationRules.decide(facts(RiskClass.R1, silent = 100, rolled = 6))
        val ask = over as? DelegationVerdict.Ask
        assertNotNull("回滚率 6% 竞还免问（负例④）", ask)
        val why = ask!!.reasons.joinToString("；")
        assertTrue("收回的话术没数字：$why", why.contains("收回") && why.contains("6%"))
        assertTrue(why.contains("5%"))
        // 刚好 5%（严格大于才收回）⇒ 还在免问这条路
        assertTrue(DelegationRules.decide(facts(RiskClass.R1, silent = 100, rolled = 5)) is DelegationVerdict.SilentAct)
        assertFalse(DelegationMetrics.revokedByRollbackRate(100, 5))
        // 样本不足却已经出现回滚 ⇒ 也收回（"样本不够就不算超标"会让这条路永远收不回）
        assertTrue(DelegationMetrics.revokedByRollbackRate(10, 1))
        assertTrue(DelegationRules.decide(facts(RiskClass.R1, silent = 10, rolled = 1)) is DelegationVerdict.Ask)
        // 没有样本＝没有比率，不谎报成"未知"糊过收回判据
        assertFalse(DelegationMetrics.revokedByRollbackRate(0, 0))
        assertEquals(0.0, DelegationMetrics.rollbackRatePct(0, 0), 0.0)
        assertEquals(6.0, DelegationMetrics.rollbackRatePct(100, 6), 0.0)
        // 收回只管 R1：本来就要问的 R2/R3 不受影响（不许收回时顺手把它们也免了）
        assertTrue(DelegationRules.decide(facts(RiskClass.R3, silent = 100, rolled = 6)) is DelegationVerdict.Ask)
    }

    // ── 度量（§2.5.4：没有度量的下放就是把风险藏起来）────────────────────────

    @Test
    fun `度量_三个数算得出来也说得出话`() {
        // 派工单点名的三个数：≤2 次/任务、>5% 收回、R2/R3 拦截率
        assertEquals(2.0, DelegationMetrics.CONFIRM_DENSITY_TARGET, 0.0)
        assertEquals(5, DelegationMetrics.ROLLBACK_RATE_LIMIT_PCT)
        assertEquals(2.5, DelegationMetrics.confirmDensity(5, 2), 0.0)
        assertTrue(DelegationMetrics.densityOverTarget(5, 2))
        assertFalse(DelegationMetrics.densityOverTarget(4, 2)) // 正好 2 次/任务＝达标，不"超标"
        assertFalse(DelegationMetrics.densityOverTarget(0, 0)) // 没任务不谈密度
        // 拦截率：差一次就是漏，不给 0.99 这种"差不多"
        assertEquals(1.0, DelegationMetrics.interceptRate(3, 3), 0.0)
        assertTrue(DelegationMetrics.interceptedAll(3, 3))
        assertTrue(DelegationMetrics.interceptedAll(0, 0))
        assertFalse("漏了一次还判合格", DelegationMetrics.interceptedAll(3, 2))
        assertEquals(2.0 / 3.0, DelegationMetrics.interceptRate(3, 2), 1e-9)
        assertEquals("33.3", DelegationMetrics.pctText(33.333))
        assertEquals("5", DelegationMetrics.pctText(5.0))
        val line = DelegationMetrics.line(100, 6, 5, 2, 3, 2)
        assertTrue("度量话术缺项：$line", line.contains("免问") && line.contains("确认密度") && line.contains("R2R3 拦截"))
    }

    @Test
    fun `四态各自只有一种摆卡语义_不许合并`() {
        val silent = DelegationVerdict.SilentAct("t", "R1 齐", true)
        val ask = DelegationVerdict.Ask("t", listOf("R2"))
        val stop = DelegationVerdict.StopAndWait("t", listOf("R3"))
        val na = DelegationVerdict.NotApplicable("t", "矩阵已拒")
        assertFalse(silent.popsCard)
        assertTrue("该问的态不弹卡＝越权", ask.popsCard)
        assertFalse("无人时弹卡＝摆给空气看", stop.popsCard)
        assertFalse(na.popsCard)
        // 四句话各不相同：日志里能一眼分清"免问/仍问/停等/不归我管"
        assertEquals(4, listOf(silent, ask, stop, na).map { DelegationTell.line(it).substringBefore("：") }.distinct().size)
    }
}
