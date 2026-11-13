// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 622 判据⑤ + A④：回放失败的「四件套」——**卡在第 N 步＋快照＋原因＋建议**；
 * 每步证据与终态取证一个不缺；报告里只有结果与证据引用，不整段回灌页面快照（判据⑥）。
 */
class TeachExecutorEvidenceTest {

    private val startUrl = "https://demo.example.com/list"

    /** 两步技能：第 1 步点「查询」，第 2 步点「详情」。 */
    private fun skill(firstExpect: TeachExpect? = TeachExpect(TeachExpectKind.TEXT_APPEARS, "WB1")): TeachSkill = TeachSkill(
        name = "取证",
        purpose = "",
        createdAtMs = 0,
        startUrl = startUrl,
        allowedDomains = listOf("demo.example.com"),
        clickTagWhitelist = listOf("BUTTON"),
        fingerprint = TeachFingerprint(listOf(TeachFingerprintAnchor("header", "单号"))),
        steps = listOf(
            TeachStep(
                index = 1, action = TeachAction.TYPE, url = startUrl,
                locators = listOf(TeachLocator(TeachLocatorKind.ELEM_ID, "q")),
                value = "WB1",
                snapshot = TeachElementSnapshot("INPUT", "searchbox", null, null, "text"),
                expect = listOfNotNull(firstExpect),
            ),
            TeachStep(
                index = 2, action = TeachAction.CLICK, url = startUrl,
                locators = listOf(TeachLocator(TeachLocatorKind.ROLE_NAME, "button|详情")),
                value = null,
                snapshot = TeachElementSnapshot("BUTTON", "button", "详情", "详情", null),
                expect = listOf(TeachExpect(TeachExpectKind.URL_CHANGED, null)),
            ),
        ),
    )

    /** 假 DOM：[failLocatorValue] 指定的定位链查无命中（触发定位失败路径），其余照常。 */
    private inner class Dom(private val failLocatorValue: String?) : TeachDom {
        var url: String = startUrl
        val textQueries = mutableListOf<String>()

        override fun ask(query: TeachQuery): TeachReply = when (query) {
            is TeachQuery.Scan ->
                if (query.locator.value == failLocatorValue) {
                    TeachReply.Hits(emptyList())
                } else {
                    TeachReply.Hits(
                        listOf(
                            TeachHit(
                                tag = if (query.locator.value == "q") "INPUT" else "BUTTON",
                                name = if (query.locator.value == "q") null else "详情",
                                text = if (query.locator.value == "q") null else "详情",
                                inputType = if (query.locator.value == "q") "text" else null,
                                inputName = if (query.locator.value == "q") "wb" else null,
                            ),
                        ),
                    )
                }
            is TeachQuery.FindBySnapshot -> TeachReply.Hits(emptyList())
            TeachQuery.CurrentUrl -> TeachReply.Url(url)
            is TeachQuery.TextPresent -> {
                textQueries += query.text
                TeachReply.Flag(query.text == "WB1")
            }
            TeachQuery.RowCount -> TeachReply.Count(3)
            TeachQuery.HeadersAndButtons -> TeachReply.Anchors(listOf("单号"), listOf("查询", "详情"))
            is TeachQuery.Act -> {
                if (query.action == TeachAction.CLICK) url = "https://demo.example.com/detail"
                TeachReply.Acted(ok = true, reason = null, urlAfter = url)
            }
            TeachQuery.DomSummary -> TeachReply.Summary("title=演示页 rows=3 interactive=[查询,详情]")
        }
    }

    /** 收集证据的 sink：记录每一次 step/final 的调用（内存实现，JVM 直测调用面）。 */
    private inner class CollectingSink : TeachEvidenceSink {
        val stepCalls = mutableListOf<Triple<Int, TeachAction, String>>()
        var finalSummary: String? = null
        var finishedReport: TeachRunReport? = null
        private var dir: String? = null

        override fun begin(skillName: String, stamp: String): String? {
            dir = "$skillName-$stamp"
            return dir
        }

        override fun step(index: Int, action: TeachAction, summary: String, screenshotRef: String?) {
            stepCalls += Triple(index, action, summary)
        }

        override fun final(summary: String, screenshotRef: String?) {
            finalSummary = summary
        }

        override fun finish(report: TeachRunReport) {
            finishedReport = report
        }
    }

    private fun run(
        skill: TeachSkill,
        failLocatorValue: String?,
    ): Pair<TeachRunReport, CollectingSink> {
        val dom = Dom(failLocatorValue)
        val sink = CollectingSink()
        // 假钟要会走：expect 轮询靠它判定超时（静止假钟会让轮询永不超时）
        var t = 0L
        val executor = TeachExecutor(dom = dom, evidence = sink, clock = { t }, sleeper = { t += 150 })
        return executor.run(skill) to sink
    }

    @Test
    fun `A④_定位失败停在对应步_失败处留快照_带原因与建议`() {
        val (report, sink) = run(skill(), failLocatorValue = "button|详情")
        assertFalse(report.ok)
        assertEquals("卡在第 2 步（该步定位失败）", 2, report.failedStep)
        assertTrue(report.stopReason!!.contains("第 2 步"))
        // 快照：失败处当场取证（失败取证行带页面 DOM 摘要）
        val failureEvidence = sink.stepCalls.last { it.first == 2 }
        assertTrue(failureEvidence.third.contains("失败取证"))
        assertTrue(failureEvidence.third.contains("title=演示页"))
        // 建议：定位失败类的话术
        assertNotNull(report.suggestion)
        assertTrue(report.suggestion!!.contains("重新教"))
        // finish 带着报告走（审计行的数据源）
        assertTrue(sink.finishedReport === report || sink.finishedReport == report)
    }

    @Test
    fun `A④_校验点不满足停在该步_建议指向改校验点`() {
        val (report, sink) = run(skill(firstExpect = TeachExpect(TeachExpectKind.TEXT_APPEARS, "页面上没有的词")), failLocatorValue = null)
        assertFalse(report.ok)
        assertEquals(1, report.failedStep)
        assertTrue(report.stopReason!!.contains("期望断言不满足"))
        assertNotNull(report.suggestion)
        assertTrue(report.suggestion!!.contains("校验点"))
        assertTrue(sink.stepCalls.any { it.first == 1 && it.third.contains("失败取证") })
    }

    @Test
    fun `判据⑤_成功回放每步留证_终态取证必采_报告只回结果与引用`() {
        val (report, sink) = run(skill(), failLocatorValue = null)
        assertTrue(report.ok)
        assertEquals(2, report.stepsDone)
        // 每步都有取证调用（DOM 摘要）
        assertEquals(setOf(1, 2), sink.stepCalls.map { it.first }.toSet() - 0)
        // 终态取证（最终快照）必采
        assertTrue(sink.finalSummary!!.contains("title=演示页"))
        // 判据⑥：报告与建议里没有整段页面内容（DOM 摘要的正文不进报告/建议）
        val line = com.example.zhengdao.baopu.agent.TeachTools.reportLine(report)
        assertTrue(line.contains("回放完成"))
        assertFalse("页面文本不回灌", line.contains("interactive="))
        assertFalse("页面文本不回灌", line.contains("title=演示页"))
    }

    @Test
    fun `A④_建议是纯函数映射_每类失败有一句人话`() {
        assertTrue(teachSuggestionOf("第 2 步执行失败：执行端口拦下：拒点：候选文案含「提交」", 2).contains("外部不可逆"))
        assertTrue(teachSuggestionOf("当前页不在授权域（evil.com），回放只在授权域内执行", null).contains("信任名单"))
        assertTrue(teachSuggestionOf("页面结构变了，请重新教一遍（关键锚点缺失：单号）", null).contains("重新教"))
        assertTrue(teachSuggestionOf("第 1 步被安全闸拦下：拒点", 1).contains("人工"))
        assertTrue(teachSuggestionOf("某类说不清的失败", 3).contains("第 3 步"))
    }

    @Test
    fun `A④_审计行带建议字段_报告文本可读`() {
        val (report, _) = run(skill(), failLocatorValue = "button|详情")
        val line = report.auditLine(123L, "op-1")
        assertTrue(line.contains("\"suggestion\""))
        assertTrue(line.contains("\"failedStep\":2"))
        assertTrue(line.contains("\"op\":\"op-1\""))
    }
}
