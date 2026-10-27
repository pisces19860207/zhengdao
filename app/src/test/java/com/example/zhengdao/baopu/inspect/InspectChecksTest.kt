// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批 §1：五项勘验的判据用例（I1–I5）。
//
// 每条判据至少钉三件事：**命中变红**、**清白变绿**、**算不出不许冒充绿**（NO_DATA）。
// 第三件是本批立批的理由——把「查不了」写成「查过没问题」就是纠察自己要纠的弊，
// 所以每个 check 都单独有一条断言盯着 NO_DATA 那一路。
//
// 全是纯函数＋手搓行：JVM 里跑不了 SQLite（`android.jar` 是桩），
// 取数那一段（`PatrolSources`）的 SQL 只能真机验，交付报告里如实标。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionGroup
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectChecksTest {

    private val now = 1_760_000_000_000L
    private val min = 60_000L

    // ── 夹具 ───────────────────────────────────────────────────────────────

    private fun act(atMs: Long, tool: String = "file_write", sid: Long = 1L, step: Int = 3) =
        WriteTrace(atMs = atMs, tool = tool, sessionId = sid, step = step)

    private fun autoAct(
        id: String,
        atMs: Long,
        tool: String = "file_write",
        target: String = "a.txt",
    ) = DecisionRecord(
        id = id,
        kind = DecisionKind.AUTO_ACT,
        group = DecisionGroup.GRANT,
        actor = DecisionActor.MODEL,
        reason = "免问直行：$tool",
        evidence = "tool:$tool:$target",
        before = "",
        after = "",
        atMs = atMs,
        sessionId = -1L,
        reversible = true,
        rollbackRef = "",
    )

    // ── I1 审计漏行 ─────────────────────────────────────────────────────────

    @Test
    fun `I1_有痕迹有账_清白`() {
        val res = AuditGapCheck.compare(
            acts = listOf(act(now)),
            ledger = listOf(autoAct("r1", now + 800L)),
        )
        assertEquals(0, res.gaps.size)
        assertEquals(1, res.checked)
        assertEquals(0, res.beyondLedgerWindow)
        assertEquals(InspectVerdict.CLEAR, AuditGapCheck.check(listOf(act(now)), listOf(autoAct("r1", now))).verdict)
    }

    @Test
    fun `I1_漏一条就报_阈值写在派工单`() {
        val finding = AuditGapCheck.check(
            acts = listOf(act(now), act(now + min, tool = "dir_delete", step = 4)),
            ledger = listOf(autoAct("r1", now)),
        )
        assertEquals(InspectVerdict.HIT, finding.verdict)
        assertEquals(1, finding.count)
        assertTrue(finding.fitsLine)
        // 建议必须给"去哪儿查"，不是空喊（负例⑤的前半）
        assertTrue(finding.suggestion.isNotBlank())
        assertTrue(finding.evidence.first().contains("dir_delete"))
    }

    @Test
    fun `I1_同名不同时刻不配_配不上就是漏`() {
        // 方向有讲究：账行**晚于**痕迹过容差 ⇒ 痕迹落进"账窗看不见"那一格（下一条正是那种），
        // 就测不到"配不上＝漏"了；这里让账行早过容差，配的仍然是同一个工具名。
        val res = AuditGapCheck.compare(
            acts = listOf(act(now)),
            ledger = listOf(autoAct("r1", now - InspectLimits.GAP_TOLERANCE_MS - 10_000L)),
        )
        assertEquals(1, res.gaps.size)
        assertEquals(1, res.checked)
        assertEquals(0, res.beyondLedgerWindow)
    }

    @Test
    fun `I1_账窗外的痕迹不计漏_只如实标看不见`() {
        val res = AuditGapCheck.compare(
            acts = listOf(act(now - 10 * min), act(now)),
            ledger = listOf(autoAct("r1", now)),
        )
        assertEquals(0, res.gaps.size)
        assertEquals(1, res.checked)
        assertEquals(1, res.beyondLedgerWindow)
    }

    @Test
    fun `I1_两痕一账_贪心不重复占用`() {
        val res = AuditGapCheck.compare(
            acts = listOf(act(now), act(now + 1_000L)),
            ledger = listOf(autoAct("r1", now)),
        )
        assertEquals(1, res.gaps.size)
        assertEquals(now + 1_000L, res.gaps.first().atMs)
    }

    @Test
    fun `I1_208一行都没有_无从比对`() {
        val finding = AuditGapCheck.check(listOf(act(now)), emptyList())
        assertEquals(InspectVerdict.NO_DATA, finding.verdict)
        assertEquals(0, finding.count)
    }

    @Test
    fun `I1_非tool房型不当任何工具的账`() {
        assertNullSafe(AuditGapCheck.toolOfEvidence("skill:global/写作"))
        assertEquals("file_write", AuditGapCheck.toolOfEvidence("tool:file_write:C:\\a\\b"))
        assertNullSafe(AuditGapCheck.toolOfEvidence("tool::x"))
    }

    private fun assertNullSafe(v: String?) {
        org.junit.Assert.assertNull(v)
    }

    // ── I2 数字无出处 ───────────────────────────────────────────────────────

    private fun sample(id: Long, answer: String, ground: String) = AnswerSample(
        messageId = id,
        sessionId = 1L,
        atMs = now + id * 1000L,
        answer = answer,
        ground = ground,
    )

    @Test
    fun `I2_数字有回灌出处_清白`() {
        val pool = listOf(sample(1L, "共 12 条记录", "count=12 rows"))
        val res = NumberGroundingCheck.run(pool)
        assertEquals(1, res.sampled)
        assertEquals(0, res.hits.size)
        assertEquals(InspectVerdict.CLEAR, NumberGroundingCheck.check(pool).verdict)
    }

    @Test
    fun `I2_数字无出处_命中`() {
        val pool = listOf(sample(1L, "上月营收 48.6 万元", "没有这个数字"))
        val finding = NumberGroundingCheck.check(pool)
        assertEquals(InspectVerdict.HIT, finding.verdict)
        assertEquals(1, finding.count)
    }

    @Test
    fun `I2_抽十条封顶_一百条池子也只取十`() {
        val pool = (1L..100L).map { sample(it, "第 $it 次", "无") }
        val picked = NumberGroundingCheck.sample(pool)
        assertEquals(InspectLimits.ANSWER_SAMPLE, picked.size)
        assertEquals(10, InspectLimits.ANSWER_SAMPLE)
        // 等距：不是只取最新十条（负例④「全检」的另一半是「只盯昨天」）
        assertEquals(pool.first(), picked.first())
        assertTrue(picked.last().messageId > pool.first().messageId)
    }

    @Test
    fun `I2_空池不是通过`() {
        assertEquals(InspectVerdict.NO_DATA, NumberGroundingCheck.check(emptyList()).verdict)
    }

    @Test
    fun `I2_虚报口径不自造_用227的另两条`() {
        val clean = sample(1L, "查到 3 条", "共 3 条")
        assertEquals(0, NumberGroundingCheck.overclaimRounds(listOf(clean)))
        // 说了没做：自述"没找到"却又下结论
        val claim = sample(2L, "估计有 100 项", "空")
        assertEquals(1, NumberGroundingCheck.overclaimRounds(listOf(claim)))
    }

    // ── I3 契约空洞 ─────────────────────────────────────────────────────────

    /** 三段齐的正文（契约行房型逐字取自 233 的 `SkillContract.render`）。 */
    private val goodSkill = listOf(
        "# 查订单",
        "<!-- contract appliesWhen workspace_has=配货单-*.xlsx -->",
        "<!-- contract success text_nonempty=运单状态 -->",
        "<!-- contract protected 不改原件区 -->",
    ).joinToString("\n")

    /** 派工单点名的那一种：类型合法、值是裸的「完成」。 */
    private val hollowSkill = listOf(
        "# 随便写",
        "<!-- contract appliesWhen workspace_has=*.xlsx -->",
        "<!-- contract success element_appears=完成 -->",
    ).joinToString("\n")

    private fun skill(name: String, body: String, scope: String = "global") =
        SkillSample(name = name, body = body, scope = scope)

    @Test
    fun `I3_抽五条封顶`() {
        val pool = (1..20).map { skill("s$it", goodSkill) }
        assertEquals(InspectLimits.SKILL_SAMPLE, ContractHollowCheck.sample(pool).size)
    }

    @Test
    fun `I3_空契约命中`() {
        val finding = ContractHollowCheck.check(listOf(skill("空洞", hollowSkill)))
        assertEquals(InspectVerdict.HIT, finding.verdict)
        assertEquals(1, finding.count)
        assertTrue(finding.fitsLine)
    }

    @Test
    fun `I3_判据不自造_与233同一张清单`() {
        // 同一个 success=完成：233 说什么，纠察就说什么（两处口径不能两套）
        val from233 = com.example.zhengdao.baopu.SkillContract.validate(hollowSkill)
        val problems = ContractHollowCheck.problemsOf(skill("空洞", hollowSkill))
        assertEquals(from233, problems)
        assertTrue(from233.isNotEmpty())
        assertTrue(ContractHollowCheck.problemsOf(skill("好", goodSkill)).isEmpty())
    }

    @Test
    fun `I3_空目录不算通过`() {
        assertEquals(InspectVerdict.NO_DATA, ContractHollowCheck.check(emptyList()).verdict)
    }

    // ── I4 调和器偏袒（四率）────────────────────────────────────────────────

    private fun bias(
        silent: Int = 0,
        rb: Int = 0,
        confirms: Int = 0,
        tasks: Int = 0,
        rounds: Int = 0,
        overclaim: Int = 0,
        gaps: List<ApprovalGap> = emptyList(),
    ) = BiasInputs(silent, rb, confirms, tasks, rounds, overclaim, gaps)

    @Test
    fun `I4_免问回滚率越线`() {
        val f = MediatorBiasCheck.check(bias(silent = 100, rb = 20, confirms = 4, tasks = 2,
            rounds = 10, gaps = List(10) { ApprovalGap(now, 1L, it, 30_000L) }))
        assertEquals(InspectVerdict.HIT, f.verdict)
        assertTrue(f.oneLine.contains("免问回滚率"))
    }

    @Test
    fun `I4_确认密度越线`() {
        val f = MediatorBiasCheck.check(bias(silent = 100, confirms = 12, tasks = 2,
            rounds = 10, gaps = List(10) { ApprovalGap(now, 1L, it, 30_000L) }))
        assertEquals(InspectVerdict.HIT, f.verdict)
        // 目标 2 与红线 5 同屏出现，不合成一个数
        assertTrue(f.evidence.any { it.contains("目标 ≤2") })
    }

    @Test
    fun `I4_虚报率越线`() {
        val f = MediatorBiasCheck.check(bias(silent = 100, confirms = 4, tasks = 2,
            rounds = 10, overclaim = 5, gaps = List(10) { ApprovalGap(now, 1L, it, 30_000L) }))
        assertEquals(InspectVerdict.HIT, f.verdict)
        assertTrue(f.oneLine.contains("虚报率"))
    }

    @Test
    fun `I4_秒批率越线`() {
        val f = MediatorBiasCheck.check(bias(
            silent = 100, confirms = 4, tasks = 2, rounds = 10,
            gaps = List(10) { ApprovalGap(now, 1L, it, if (it < 8) 800L else 30_000L) },
        ))
        assertEquals(InspectVerdict.HIT, f.verdict)
        assertTrue(f.oneLine.contains("秒批率"))
    }

    @Test
    fun `I4_四率都在界内_清白`() {
        val f = MediatorBiasCheck.check(bias(
            silent = 100, rb = 1, confirms = 4, tasks = 2, rounds = 10,
            gaps = List(10) { ApprovalGap(now, 1L, it, 30_000L) },
        ))
        assertEquals(InspectVerdict.CLEAR, f.verdict)
        assertEquals(0, f.count)
    }

    @Test
    fun `I4_样本不够报查不了_不报清白`() {
        val f = MediatorBiasCheck.check(bias(silent = 3, confirms = 1, tasks = 1))
        assertEquals(InspectVerdict.NO_DATA, f.verdict)
        // 实话那句写死在判据侧，UI 不许自己编
        assertTrue(f.evidence.contains(MediatorBiasCheck.NO_DATA_NOTE))
        assertFalse(f.oneLine.contains("没问题"))
    }

    @Test
    fun `I4_样本不足但已出现回滚_照报保守数`() {
        val rates = MediatorBiasCheck.rates(bias(silent = 3, rb = 1))
        val first = rates.first { it.key == "silent_rollback" }
        assertTrue(first.computable)
        assertTrue(first.breached)
    }

    @Test
    fun `I4_免问判据_同会话同步没摆过卡`() {
        val confirms = listOf(ConfirmTrace(now, 7L, 3, "file_write"))
        assertTrue(MediatorBiasCheck.isSilent(act(now, sid = 8L, step = 3), confirms))
        assertFalse(MediatorBiasCheck.isSilent(act(now, sid = 7L, step = 3), confirms))
    }

    // ── I5 反复失败未归档 ───────────────────────────────────────────────────

    @Test
    fun `I5_七天内三次没收拾_命中`() {
        val f = UnarchivedFailureCheck.check(
            failures = listOf(
                SkillFailure("查订单", now),
                SkillFailure("查订单", now - min),
                SkillFailure("查订单", now - 2 * min),
            ),
            archivedNames = emptySet(),
            nowMs = now,
        )
        assertEquals(InspectVerdict.HIT, f.verdict)
        assertEquals(1, f.count)
        assertTrue(f.evidence.first().contains("查订单"))
    }

    @Test
    fun `I5_两次不到三次_清白`() {
        val f = UnarchivedFailureCheck.check(
            listOf(SkillFailure("查订单", now), SkillFailure("查订单", now - min)),
            emptySet(), now,
        )
        assertEquals(InspectVerdict.CLEAR, f.verdict)
    }

    @Test
    fun `I5_已退役豁免`() {
        val f = UnarchivedFailureCheck.check(
            listOf(
                SkillFailure("查订单", now),
                SkillFailure("查订单", now - min),
                SkillFailure("查订单", now - 2 * min),
            ),
            archivedNames = setOf("查订单"),
            nowMs = now,
        )
        assertEquals(InspectVerdict.CLEAR, f.verdict)
        assertTrue(f.oneLine.contains("已收拾"))
    }

    @Test
    fun `I5_窗口外的失败不算`() {
        val f = UnarchivedFailureCheck.check(
            listOf(
                SkillFailure("查订单", now),
                SkillFailure("查订单", now - min),
                SkillFailure("查订单", now - UnarchivedFailureCheck.windowMs() - 1),
            ),
            emptySet(), now,
        )
        assertEquals(InspectVerdict.CLEAR, f.verdict)
    }

    @Test
    fun `I5_零失败不当通过`() {
        assertEquals(
            InspectVerdict.NO_DATA,
            UnarchivedFailureCheck.check(emptyList(), emptySet(), now).verdict,
        )
    }

    // ── 取数侧的纯函数（不碰库的那三条）─────────────────────────────────────

    @Test
    fun `取数_秒批间隔只从痕迹推_配不上不出样本`() {
        val confirms = listOf(ConfirmTrace(now - 5_000L, 1L, 3, "file_write"))
        val gaps = PatrolSources.approvalGaps(listOf(act(now)), confirms)
        assertEquals(1, gaps.size)
        assertEquals(5_000L, gaps.first().deltaMs)
        assertTrue(PatrolSources.approvalGaps(emptyList(), confirms).isEmpty())
    }

    @Test
    fun `取数_免问计数与摆卡互斥`() {
        val acts = listOf(act(now, sid = 1L, step = 1), act(now, sid = 1L, step = 2))
        val confirms = listOf(ConfirmTrace(now - 1_000L, 1L, 2, "file_write"))
        assertEquals(1, PatrolSources.silentActCount(acts, confirms))
    }

    @Test
    fun `取数_额度档只从allowance行读回_回滚过的跳过`() {
        assertEquals(AllowanceBand.RAISED, PatrolSources.bandOfEvidence("allowance:raised:patrol:1"))
        org.junit.Assert.assertNull(PatrolSources.bandOfEvidence("tool:file_write:a"))
        val held = DecisionRecord(
            id = "a1", kind = DecisionKind.TOOL_GRANT, group = DecisionGroup.GRANT,
            actor = DecisionActor.AUTO, reason = "额度", evidence = "allowance:raised:p",
            before = "", after = "", atMs = now, sessionId = -1L,
            reversible = true, rollbackRef = "",
        )
        val rolled = held.copy(id = "a2", atMs = now + 1, rolledBack = true)
        assertEquals(AllowanceBand.RAISED, PatrolSources.currentBand(listOf(held)))
        assertEquals(AllowanceBand.DEFAULT, PatrolSources.currentBand(listOf(rolled)))
        assertEquals(AllowanceBand.DEFAULT, PatrolSources.currentBand(emptyList()))
    }

    @Test
    fun `取数_写侧名册来自219表_不是空表`() {
        val names = PatrolSources.writeToolNames()
        assertTrue("写侧名册为空就是假绿", names.isNotEmpty())
        //  exempt 里的工具一条都不许进来
        RiskTableExemptProbe.assertNoExempt(names)
    }

    /** 名册与 219 的豁免表不许有交集（这一条在 219 回填时会自动跟着变，不需要改判据）。 */
    private object RiskTableExemptProbe {
        fun assertNoExempt(names: Set<String>) {
            val table = com.example.zhengdao.baopu.RiskTable.DEFAULT
            names.forEach { n ->
                val rule = table.tools[n]
                assertTrue("$n 在名册里却整条规则没有", rule != null)
                assertFalse("$n 已被 219 豁免，不该进写侧名册", table.exempt.contains(n))
            }
        }
    }

    @Test
    fun `落盘_巡按台账写一行读一行`() {
        val root = java.nio.file.Files.createTempDirectory("zd-239-patrol-").toFile()
        val report = Inspector.report(
            listOf(
                InspectFinding(
                    check = InspectCheck.AUDIT_GAP,
                    verdict = InspectVerdict.HIT,
                    oneLine = InspectText.line("审计漏 1 条"),
                    suggestion = "查写点",
                    evidence = listOf("10-27 12:00 写动作 file_write，208 无行"),
                    count = 1,
                ),
            ),
            now,
        )
        assertTrue(PatrolLedger.append(root, PatrolLedger.entryOf(report, manual = true, elapsedMs = 42L)))
        val back = PatrolLedger.read(root)
        assertEquals(1, back.size)
        assertEquals(1, back.first().hits)
        assertEquals(42L, back.first().elapsedMs)
        assertTrue(back.first().manual)
        assertEquals(1, back.first().findings.size)
        assertEquals(InspectCheck.AUDIT_GAP, back.first().findings.first().check)
    }
}
