// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批 §2.1／§3／§5：报告形制、抽检规模、巡按发起闸门。
// 负例②（长篇报告超三行）、④（全检）、⑤（只查不建建议）与 §2.1 的低峰判据钉在这里；
// ①（有写权限）与 ③（明细进模型上下文）只有读源码才验得到，在 `InspectReadOnlyGateTest`。
package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectReportFormTest {

    private val now = 1_760_000_000_000L

    private fun hit(check: InspectCheck, text: String) = InspectFinding(
        check = check,
        verdict = InspectVerdict.HIT,
        oneLine = InspectText.line(text),
        suggestion = "改法一句",
        evidence = (1..30).map { "明细第 $it 行：$text $text $text" },
        count = 3,
        atMs = now,
    )

    // ── §3 三行形制（负例②）─────────────────────────────────────────────────

    @Test
    fun `形制_六项全命中也只有三行`() {
        val all = InspectCheck.entries.map { hit(it, "${it.cn} 命中三条，明细见下") }
        val report = Inspector.report(all, now)
        assertEquals(6, report.hits)
        // 一行标题 ＋ 最多两行正文 = 三行，多一条都不许
        assertEquals(InspectLimits.MAX_REPORT_LINES - 1, report.lines.size)
        assertTrue(report.fitsForm)
    }

    @Test
    fun `形制_每一行都不超过四十字`() {
        val long = hit(InspectCheck.AUDIT_GAP, "审计漏行 12 条：" + "证据原文".repeat(20))
        assertTrue(long.fitsLine)
        val report = Inspector.report(listOf(long, hit(InspectCheck.CONTRACT_HOLLOW, "契约空洞 3 条")), now)
        assertTrue(report.headline.length <= InspectLimits.MAX_LINE_CHARS)
        report.lines.forEach { assertTrue(it.length <= InspectLimits.MAX_LINE_CHARS) }
    }

    @Test
    fun `形制_正文之外的命中在标题里数出来`() {
        val report = Inspector.report(InspectCheck.entries.take(4).map { hit(it, "${it.cn} 命中") }, now)
        assertEquals(4, report.hits)
        assertTrue(report.headline.contains("4 项"))
        assertTrue(report.headline.contains("余 2"))
    }

    @Test
    fun `形制_查不了不上正文但标题露字`() {
        val report = Inspector.report(
            listOf(
                InspectFinding(InspectCheck.MEDIATOR_BIAS, InspectVerdict.NO_DATA, "两项无从算", "先落库"),
                hit(InspectCheck.AUDIT_GAP, "审计漏 1 条"),
            ),
            now,
        )
        assertEquals(0, report.lines.count { it.contains("无从算") })
        assertTrue(report.headline.contains("查不了 1"))
        assertEquals(1, report.noData)
    }

    @Test
    fun `形制_顺序按检查号不按严重度`() {
        val report = Inspector.report(
            listOf(
                hit(InspectCheck.SHADOW_MISMATCH, "影子不符"),
                hit(InspectCheck.AUDIT_GAP, "审计漏"),
                hit(InspectCheck.CONTRACT_HOLLOW, "契约空洞"),
            ),
            now,
        )
        assertEquals(
            listOf(InspectCheck.AUDIT_GAP, InspectCheck.CONTRACT_HOLLOW, InspectCheck.SHADOW_MISMATCH),
            report.findings.map { it.check },
        )
    }

    @Test
    fun `形制_同一项出两条按检查号去重`() {
        val report = Inspector.report(
            listOf(hit(InspectCheck.AUDIT_GAP, "第一条"), hit(InspectCheck.AUDIT_GAP, "第二条")),
            now,
        )
        assertEquals(1, report.findings.size)
        assertEquals("第二条", report.findings.first().oneLine.trim())
    }

    // ── 负例③：给模型的永远只有一句 ─────────────────────────────────────────

    @Test
    fun `模型侧_只有标题那一句`() {
        val all = InspectCheck.entries.map { hit(it, "${it.cn} 命中") }
        val report = Inspector.report(all, now)
        assertEquals(report.headline, report.modelLine)
        // 那一句里不许有任何一条明细
        report.findings.flatMap { it.evidence }.forEach {
            assertFalse("明细漏进了模型那一句：$it", report.modelLine.contains(it.take(8)))
        }
        assertTrue(report.modelLine.length <= InspectLimits.MAX_LINE_CHARS)
    }

    // ── 负例⑤：每一项都要有一句改法 ─────────────────────────────────────────

    @Test
    fun `建议_六项判据真跑一遍都非空`() {
        val day = 86_400_000L
        val findings = listOf(
            AuditGapCheck.check(listOf(WriteTrace(now, "file_write", 1L, 1)), emptyList<com.example.zhengdao.baopu.DecisionRecord>()),
            NumberGroundingCheck.check(listOf(AnswerSample(1L, 1L, now, "共 999 项", "空"))),
            ContractHollowCheck.check(listOf(SkillSample("空洞", "# 空洞"))),
            MediatorBiasCheck.check(BiasInputs(silentActs = 100, rollbacksAfterSilent = 30)),
            UnarchivedFailureCheck.check(
                listOf(SkillFailure("查订单", now), SkillFailure("查订单", now - day), SkillFailure("查订单", now - 2 * day)),
                emptySet(), now,
            ),
            ShadowCheck.check(listOf(ShadowTrace(now, 1L, 1, "dir_delete", true)), emptyList()),
        )
        findings.forEach { f ->
            assertTrue("${f.check.cn} 没有一句结论", f.oneLine.isNotBlank())
            assertTrue("${f.check.cn} 没有改法或下一步", f.suggestion.isNotBlank())
            assertTrue("${f.check.cn} 那一行超了 40 字", f.fitsLine)
        }
        // 六项各一条，聚合后仍是三行
        assertTrue(Inspector.report(findings, now).fitsForm)
    }

    // ── 负例④：抽检不是全检 ─────────────────────────────────────────────────

    @Test
    fun `抽样_一千条池子也只取十条`() {
        val pool = (1..1000).toList()
        assertEquals(InspectLimits.ANSWER_SAMPLE, InspectSample.spread(pool, InspectLimits.ANSWER_SAMPLE).size)
        assertEquals(InspectLimits.SKILL_SAMPLE, InspectSample.spread(pool, InspectLimits.SKILL_SAMPLE).size)
    }

    @Test
    fun `抽样_等距覆盖整个窗口不只看最新`() {
        val pool = (1L..50L).map { AnswerSample(it, 1L, now - it * 1000L, "数字 $it", "无") }
        val picked = NumberGroundingCheck.sample(pool)
        assertEquals(10, picked.size)
        assertEquals(1L, picked.first().messageId)
        assertTrue(picked.last().messageId >= 41L)
        // 中间层也被抽到（不是头十条也不是尾十条）
        assertTrue(picked.map { it.messageId }.containsAll(listOf(6L, 11L)))
    }

    @Test
    fun `抽样_不足k条全取_k为零不出样本`() {
        val pool = listOf(AnswerSample(1L, 1L, now, "1 条", "1"))
        assertEquals(1, InspectSample.spread(pool, 10).size)
        assertTrue(InspectSample.spread(pool, 0).isEmpty())
        assertTrue(InspectSample.spread<Int>(emptyList(), 10).isEmpty())
    }

    @Test
    fun `钳形_换行一定被压掉`() {
        // 台账是 JSONL 一行一条：留着换行写进盘，下次读回来就是两条坏行
        val fitted = InspectText.fit("审计漏 1 条：\n第二段\t制表符", 40)
        assertFalse(fitted.contains("\n"))
        assertFalse(fitted.contains("\t"))
        assertEquals("审计漏 1 条： 第二段 制表符", fitted)
    }

    // ── §2.1 发起判据 ───────────────────────────────────────────────────────

    @Test
    fun `调度_夜间窗口只有那三小时`() {
        assertTrue(PatrolScheduler.inNightWindow(2))
        assertTrue(PatrolScheduler.inNightWindow(4))
        assertFalse(PatrolScheduler.inNightWindow(5))
        assertFalse(PatrolScheduler.inNightWindow(13))
    }

    @Test
    fun `调度_每日一次靠二十小时间隔`() {
        assertTrue(PatrolScheduler.autoGate(now - 21L * 3_600_000L, 3, now).run)
        assertFalse(PatrolScheduler.autoGate(now - 19L * 3_600_000L, 3, now).run)
        // 从没巡过 ⇒ 今夜第一趟就跑
        assertTrue(PatrolScheduler.autoGate(null, 3, now).run)
        // 不在窗口里 ⇒ 间隔再久也不巡
        assertFalse(PatrolScheduler.autoGate(null, 9, now).run)
    }

    @Test
    fun `调度_有任务在跑就让路`() {
        val g = PatrolScheduler.autoGate(null, 3, now, running = true)
        assertFalse(g.run)
        assertTrue(g.why.contains("在跑"))
        assertFalse(PatrolScheduler.manualGate(running = true).run)
        assertTrue(PatrolScheduler.manualGate(running = false).run)
    }

    @Test
    fun `调度_闸门永远有字`() {
        listOf(
            PatrolScheduler.autoGate(null, 3, now),
            PatrolScheduler.autoGate(now, 3, now),
            PatrolScheduler.manualGate(true),
        ).forEach { assertTrue(it.why.isNotBlank()) }
    }

    @Test
    fun `调度_上次巡按时刻从台账取`() {
        org.junit.Assert.assertNull(PatrolScheduler.lastPatrolAtMs(emptyList()))
        val e = PatrolLedger.entryOf(Inspector.report(emptyList(), now), manual = false, elapsedMs = 1L)
        assertEquals(now, PatrolScheduler.lastPatrolAtMs(listOf(e)))
    }
}
