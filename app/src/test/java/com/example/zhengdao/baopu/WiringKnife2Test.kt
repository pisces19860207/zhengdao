// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十一批 · 接线刀二的判据测试（派工单 §2：每项一条正向＋一条负例）。
// 只测本批接进来的口（判据本体在 WiringKnife2.kt 与被接线的既有层），不重测
// 既有层的内部行为——那些已有各自的测试文件钉着。
package com.example.zhengdao.baopu

import com.example.zhengdao.IncomingIntent
import com.example.zhengdao.IncomingRoute
import com.example.zhengdao.baopu.agent.multiagent.SubTask
import com.example.zhengdao.baopu.agent.multiagent.SubTaskResult
import com.example.zhengdao.baopu.agent.multiagent.SubTaskStatus
import com.example.zhengdao.routeIncomingIntent
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WiringKnife2Test {

    // ── #2 召回预算按模型窗口（recallBudgetOf） ───────────────────────────

    @Test
    fun `正向_换模型窗口预算跟着变`() {
        // 32k 小窗口 ⇒ 窗口 ÷64 = 512（旧口径一律 2k，本批起小窗口收缩）
        assertEquals(512, recallBudgetOf(32 * 1024))
        // 16k ⇒ 256，触到下限
        assertEquals(256, recallBudgetOf(16 * 1024))
        // 128k 及以上 ⇒ 封顶 2k，与大窗口旧口径一致
        assertEquals(ContextAssembler.RECALL_TOKEN_BUDGET, recallBudgetOf(128 * 1024))
        assertEquals(ContextAssembler.RECALL_TOKEN_BUDGET, recallBudgetOf(1_048_576))
    }

    @Test
    fun `负例_再大不超上限_再小不破下限`() {
        val cap = ContextAssembler.RECALL_TOKEN_BUDGET
        for (w in listOf(1, 100, 1024, 8 * 1024, 200_000, 1_048_576, 100_000_000)) {
            val b = recallBudgetOf(w)
            assertTrue("窗口 $w 的预算 $b 必须在 [下限, 上限] 内", b in RECALL_BUDGET_FLOOR..cap)
        }
        // 非正窗口（调用方传坏值）也不许炸：钳到下限，不抛异常
        assertEquals(RECALL_BUDGET_FLOOR, recallBudgetOf(0))
        assertEquals(RECALL_BUDGET_FLOOR, recallBudgetOf(-5))
    }

    // ── #3 记忆质量分三态（memoryQualityBadgeOf） ──────────────────────────

    private fun entry(scoredAt: Long, score: Int = MemoryQuality.DEFAULT_SCORE) = MemoryEntry(
        id = "m1",
        scope = MemoryScope.GLOBAL,
        kind = MemoryKind.PREFERENCE,
        title = "偏好",
        body = "正文",
        qualityScore = score,
        scoredAt = scoredAt,
    )

    @Test
    fun `正向_评过的记忆按落账分数给等级`() {
        assertEquals(MemoryQualityLevel.GOOD, memoryQualityBadgeOf(entry(100L, 87)).level)
        assertEquals(87, memoryQualityBadgeOf(entry(100L, 87)).score)
        assertEquals(MemoryQualityLevel.FAIR, memoryQualityBadgeOf(entry(100L, 50)).level)
        assertEquals(MemoryQualityLevel.POOR, memoryQualityBadgeOf(entry(100L, MemoryQuality.STALE_BELOW - 1)).level)
    }

    @Test
    fun `负例_没评过的显示未评估_不现算冒充`() {
        // scoredAt=0 ⇒ 未评估；即使 qualityScore 列带着默认满分也不许当成「已评估 100 分」
        val badge = memoryQualityBadgeOf(entry(0L, MemoryQuality.MAX_SCORE))
        assertEquals(MemoryQualityLevel.UNASSESSED, badge.level)
        // 对照：同一条记忆真评过（scoredAt>0）才出分数
        assertEquals(MemoryQualityLevel.GOOD, memoryQualityBadgeOf(entry(1L, MemoryQuality.MAX_SCORE)).level)
    }

    // ── #5 子 Agent token 汇总（subAgentUsageOf / subAgentUsageLine） ─────

    private fun result(id: String, status: SubTaskStatus = SubTaskStatus.DONE, inTok: Long? = null, outTok: Long? = null) =
        SubTaskResult(id = id, status = status, tokensIn = inTok, tokensOut = outTok)

    @Test
    fun `正向_子任务token计入总数且各条只计一次`() {
        val usage = subAgentUsageOf(
            listOf(
                result("t-1", inTok = 100, outTok = 40),
                result("t-2", status = SubTaskStatus.FAILED, inTok = 30, outTok = null),
                result("t-3"), // 没读到读数的条目不贡献
            ),
        )
        assertNotNull(usage)
        assertEquals(130L, usage!!.tokensIn)
        assertEquals(40L, usage.tokensOut)
        assertEquals(2, usage.readings)
        val line = subAgentUsageLine("parent-1", usage)!!
        assertTrue("run_log 行要可 grep（锚在前）", line.startsWith("[baopu-subtask]"))
        assertTrue("行里要有入/出两个数", line.contains("入 130") && line.contains("出 40"))
        // 同一批结果再汇总一遍仍是同一组数（纯函数、无隐藏累加）
        assertEquals(usage, subAgentUsageOf(listOf(result("t-1", inTok = 100, outTok = 40), result("t-2", inTok = 30))))
    }

    @Test
    fun `负例_一条读数都没有_不出声不编零`() {
        assertNull(subAgentUsageOf(listOf(result("t-1"), result("t-2", status = SubTaskStatus.CANCELLED))))
        assertNull(subAgentUsageOf(emptyList()))
        assertNull(subAgentUsageLine("parent-1", null))
    }

    // ── #6 「修一下」的修复请求（toolRepairRequestText） ──────────────────

    @Test
    fun `正向_参数错给得出归因与建议的预填句`() {
        val text = toolRepairRequestText("write_file", "失败：缺少必填参数「path」")
        assertNotNull(text)
        assertTrue("要点名是哪个工具", text!!.contains("write_file"))
        assertTrue("要带上归因与建议（走既有顾问）", text.contains("归因") && text.contains("建议"))
        assertTrue("只预填不代发——句式是请模型处理", text.contains("请修一下"))
    }

    @Test
    fun `负例_顾问说不建议重试_不预填不把用户点进死胡同`() {
        // 无结构化线索的原文 ⇒ 顾问 NONE ⇒ null（调用方不出预填，UI 给人话）
        assertNull(toolRepairRequestText("some_tool", "一段完全看不出规律的天书错误 ξωφ"))
    }

    // ── #1 decidePending 的 include 拦选（生产接线＝控制器拒绝留痕） ────────

    private fun candidate(id: String) = ProposalCandidate(
        id = id,
        kind = ProposalKind.SKILL_BODY,
        target = "技能$id",
        payload = "正文$id",
        evidence = ProposalEvidence(runLogIds = listOf(1L), skillEventIds = listOf(2L), atMs = 1L),
        createdAt = 1L,
    )

    private class RecordingSink : EvolutionAuditSink {
        val rows = mutableListOf<EvolutionAuditEntry>()
        override fun record(entry: EvolutionAuditEntry) {
            rows += entry
        }
    }

    @Test
    fun `正向_decidePending按include只判该判的_被拦的不落审计`() {
        val dir = Files.createTempDirectory("wk2-store").toFile()
        val store = ProposalCandidateStore(dir, log = {})
        store.appendNew(listOf(candidate("c-1"), candidate("c-2")), 1L)
        val sink = RecordingSink()
        val port = EvolutionDecisionPort(audit = sink)
        val decisions = port.decidePending(
            store = store,
            assessment = EvolutionAssessmentSource {
                EvolutionAssessment(riskCategory = EvolutionRiskCategory.SKILL_BODY_MINOR)
            },
            operatorId = "settle",
        ) { it.id == "c-1" }
        assertEquals("只判了 1 条", 1, decisions.size)
        assertEquals("c-1", decisions.first().candidateId)
        assertEquals("被拦的候选不决策、不落审计", 1, sink.rows.size)
        assertEquals("c-1", sink.rows.first().input.candidateId)
        // 默认参数仍是全收（既有口径不变）：两条都判
        val sink2 = RecordingSink()
        assertEquals(2, EvolutionDecisionPort(audit = sink2).decidePending(store, EvolutionAssessmentSource { EvolutionAssessment() }, "x").size)
    }

    @Test
    fun `负例_无候选不硬造_decidePending空表零审计`() {
        val dir = Files.createTempDirectory("wk2-empty").toFile()
        val store = ProposalCandidateStore(dir, log = {})
        val sink = RecordingSink()
        val decisions = EvolutionDecisionPort(audit = sink).decidePending(
            store,
            EvolutionAssessmentSource { EvolutionAssessment() },
            "settle",
        ) { false }
        assertTrue("没有候选 ⇒ 没有决策", decisions.isEmpty())
        assertTrue("没有候选 ⇒ 没有审计（不硬造决策记录）", sink.rows.isEmpty())
    }

    // ── #4 分享收图路由（routeIncomingIntent 的图片分支） ─────────────────

    @Test
    fun `正向_单图与多图分享都路由到收图`() {
        val one = routeIncomingIntent(
            IncomingIntent(action = "android.intent.action.SEND", type = "image/png", imageCount = 1),
        )
        assertTrue(one is IncomingRoute.ShareImagesToBaopu)
        assertEquals(1, (one as IncomingRoute.ShareImagesToBaopu).count)

        val many = routeIncomingIntent(
            IncomingIntent(action = "android.intent.action.SEND_MULTIPLE", type = "image/*; charset=binary", imageCount = 3),
        )
        assertTrue(many is IncomingRoute.ShareImagesToBaopu)
        assertEquals(3, (many as IncomingRoute.ShareImagesToBaopu).count)
    }

    @Test
    fun `负例_图片零张与多选文本仍被拒_文本单发口径不变`() {
        // SEND image/* 但 EXTRA_STREAM 空 ⇒ 拒（EMPTY_TEXT，不造 0 张图的路由）
        val empty = routeIncomingIntent(
            IncomingIntent(action = "android.intent.action.SEND", type = "image/jpeg", imageCount = 0),
        )
        assertTrue(empty is IncomingRoute.Rejected)
        // SEND_MULTIPLE 文本不在本单范围 ⇒ 拒
        val multi = routeIncomingIntent(
            IncomingIntent(action = "android.intent.action.SEND_MULTIPLE", type = "text/plain", text = "abc", imageCount = 0),
        )
        assertTrue(multi is IncomingRoute.Rejected)
        // 既有口径回归：SEND text/plain 照旧走正文预填
        val text = routeIncomingIntent(
            IncomingIntent(action = "android.intent.action.SEND", type = "text/plain", text = "正文"),
        )
        assertTrue(text is IncomingRoute.ShareToBaopu)
    }
}
