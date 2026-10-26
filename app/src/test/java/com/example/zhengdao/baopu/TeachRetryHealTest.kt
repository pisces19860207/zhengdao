// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十八批 C 片 §C-3：失败自愈（act 失败＋指纹未变 ⇒ 滚入视口重试**恰一次**）。
//
// 四条都打在真执行器＋假 DOM 上（不 mock 判据）：
//   正例   第一步点不动 → 滚入视口 → 重试成 ⇒ retried 记账、审计行带 retries；
//   负例 A act 永远失败 ⇒ 初始＋滚入＋重试恰好 3 次动手，停——**不许无限重试**；
//   负例 B 失败后指纹复核不过（页面可能已离开起点）⇒ 连滚入视口都不许做，直接停；
//   负例 C expect 失败**不重试**：动作已经执行过，重试＝双击双提交——点击恰好 1 次。
//
// 顺带钉死一条边界：指纹锚是**起点页**的，离开起点页后重试不可判 ⇒ fail-closed 停
// （负例 B 的判据同一份，只是触发方式不同）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeachRetryHealTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val START = "https://search.example.com/start"
    private val RESULTS = "https://search.example.com/results?wb=WB12345678"
    private val EXPORT = "https://search.example.com/export"

    /** 委托壳：只在点名的查询上改答案，其余原样转交 [inner]（真执行器打的是真判据）。 */
    private open class Shell(private val inner: FakeDom) : TeachDom {
        override fun ask(query: TeachQuery): TeachReply = inner.ask(query)
        val dom: FakeDom get() = inner
    }

    /** 第一次 CLICK 失败（失败＝没点上去，页面没动），之后一切正常。 */
    private class FirstClickFails(inner: FakeDom) : Shell(inner) {
        private var clickAttempts = 0
        val acts = mutableListOf<TeachAction>()
        override fun ask(query: TeachQuery): TeachReply {
            if (query is TeachQuery.Act) acts += query.action
            if (query is TeachQuery.Act && query.action == TeachAction.CLICK) {
                clickAttempts += 1
                if (clickAttempts == 1) {
                    return TeachReply.Acted(ok = false, reason = "点不动（首次，假 DOM）", urlAfter = dom.currentUrl)
                }
            }
            return super.ask(query)
        }
    }

    /** 每一次 CLICK 都失败（滚入视口救不回来的那种）。 */
    private class AllClicksFail(inner: FakeDom) : Shell(inner) {
        val acts = mutableListOf<TeachAction>()
        override fun ask(query: TeachQuery): TeachReply {
            if (query is TeachQuery.Act) {
                acts += query.action
                if (query.action == TeachAction.CLICK) {
                    return TeachReply.Acted(ok = false, reason = "点不动（一直，假 DOM）", urlAfter = dom.currentUrl)
                }
            }
            return super.ask(query)
        }
    }

    /** 第一次 CLICK 失败之后，把指纹探针也弄瞎（页面可能已经不在起点页）。 */
    private class FingerprintGoesBlind(inner: FakeDom) : Shell(inner) {
        private var clickAttempts = 0
        val acts = mutableListOf<TeachAction>()
        override fun ask(query: TeachQuery): TeachReply = when {
            query is TeachQuery.Act && query.action == TeachAction.CLICK -> {
                clickAttempts += 1
                acts += query.action
                if (clickAttempts == 1) {
                    TeachReply.Acted(ok = false, reason = "点不动（首次，假 DOM）", urlAfter = dom.currentUrl)
                } else {
                    super.ask(query)
                }
            }
            query is TeachQuery.HeadersAndButtons && clickAttempts >= 1 ->
                TeachReply.Anchors(headers = emptyList(), buttons = emptyList())
            else -> super.ask(query)
        }
    }

    /** 搜索这下「点了但没跳走」（把页面拽回起点）⇒ URL_CHANGED 永远不满足。 */
    private class ClickButNoNavigation(inner: FakeDom, private val startUrl: String) : Shell(inner) {
        override fun ask(query: TeachQuery): TeachReply {
            val reply = super.ask(query)
            if (query is TeachQuery.Act && query.action == TeachAction.CLICK &&
                dom.currentUrl != startUrl
            ) {
                dom.navigate(startUrl) // 点了，但页面没换成 RESULTS
            }
            return reply
        }
    }

    private fun skill() = TeachLearnerTestAccess.run {
        TeachLearner.buildDraft(
            fiveEvents(), "查货", "查当日报文",
            TeachPageFacts(START, listOf("关键词"), listOf("搜索", "提交")),
            nowMs = 1_728_000_000_000L,
        ).skill
    }

    private fun executor(dom: TeachDom, audits: MutableList<String> = mutableListOf()): TeachExecutor {
        val sink = FileTeachEvidenceSink(tmp.newFolder())
        var now = 0L
        return TeachExecutor(
            dom = dom,
            evidence = sink,
            audit = { report, ts -> audits += report.auditLine(ts) },
            clock = { now },
            sleeper = { now += TeachExecutor.EXPECT_POLL_MS },
        )
    }

    private fun rows(n: Int, prefix: String = "行"): List<FakeEl> =
        (1..n).map { FakeEl(tag = "tr", text = "$prefix$it") }

    private fun pages(): Map<String, FakePage> {
        val startPage = FakePage(
            START,
            listOf(
                FakeEl(tag = "th", text = "关键词"),
                FakeEl(
                    tag = "input", id = "search-input", nameAttr = "q", inputType = "search",
                    placeholder = "输入关键词", formLabel = "关键词",
                ),
                FakeEl(
                    tag = "button", text = "搜索", testid = "search-btn", id = "btn-search",
                    classes = listOf("btn", "primary"),
                    onClick = { it.navigate(RESULTS) },
                ),
                FakeEl(tag = "button", text = "提交", classes = listOf("trap")),
            ),
        )
        val resultsPage = FakePage(
            RESULTS,
            listOf(
                FakeEl(tag = "th", text = "运单号"),
                FakeEl(
                    tag = "input", id = "filter-input", nameAttr = "filter", inputType = "text",
                    placeholder = "筛选运单号", formLabel = "筛选",
                ),
                FakeEl(
                    tag = "button", text = "下一页", classes = listOf("pager-next"),
                    onClick = { dom -> dom.replacePage(RESULTS) { els -> els.filter { it.upperTag != "TR" } + rows(15, "次") } },
                ),
                FakeEl(
                    tag = "button", text = "导出", classes = listOf("btn", "export"),
                    onClick = { it.navigate(EXPORT) },
                ),
            ),
        )
        val exportPage = FakePage(EXPORT, listOf(FakeEl(tag = "th", text = "导出清单")))
        return mapOf(START to startPage, RESULTS to resultsPage, EXPORT to exportPage)
    }

    // ── 正例：滚入视口重试一次，成了 ────────────────────────────────────────

    @Test
    fun `正例_首次点击失败_滚入视口重试一次后成功_retried记账`() {
        val dom = FirstClickFails(FakeDom(pages(), START))
        val audits = mutableListOf<String>()
        val report = executor(dom, audits).run(skill())

        assertTrue("重试后应跑完：${report.stopReason}", report.ok)
        assertEquals("只有第 2 步重试过", listOf(2), report.retried)
        // 动手次数：TYPE×2 ＋ 第 2 步 CLICK 两次（失败＋成功）＋ SCROLL 一次 ＋ 后两步 CLICK×2
        assertEquals(1, dom.acts.count { it == TeachAction.SCROLL })
        assertEquals("点击共 4 次（3 步＋1 次重试）", 4, dom.acts.count { it == TeachAction.CLICK })
        assertTrue("审计行要带 retries：${audits[0]}", audits[0].contains("\"retries\":[2]"))
    }

    // ── 负例 A：act 永远失败 ⇒ 恰好 3 次动手（初始＋滚入＋重试一次），停 ──────

    @Test
    fun `负例A_act一直失败_滚入视口重试恰一次后停_不许无限重试`() {
        val dom = AllClicksFail(FakeDom(pages(), START))
        val report = executor(dom).run(skill())

        assertFalse(report.ok)
        assertEquals("停在第 2 步（第一次点击）", 2, report.failedStep)
        assertTrue("停因要点名重试过：${report.stopReason}", report.stopReason!!.contains("滚入视口重试一次后仍失败"))
        // 动手次数封顶：初始 CLICK ＋ SCROLL ＋ 重试 CLICK = 3 条 Act（然后立刻停）
        assertEquals(1, dom.acts.count { it == TeachAction.SCROLL })
        assertEquals("点击恰好两次（初始＋重试一次），没有第三次", 2, dom.acts.count { it == TeachAction.CLICK })
        assertEquals("失败时 retried 不记账", emptyList<Int>(), report.retried)
    }

    // ── 负例 B：失败后指纹复核不过 ⇒ 连滚入视口都不做，fail-closed 停 ────────

    @Test
    fun `负例B_指纹复核不通过_不滚入不重试_直接停`() {
        val dom = FingerprintGoesBlind(FakeDom(pages(), START))
        val report = executor(dom).run(skill())

        assertFalse(report.ok)
        assertTrue("停因要说指纹：${report.stopReason}", report.stopReason!!.contains("指纹复核不通过"))
        assertEquals("只动手过一次（没有 SCROLL、没有重试）", 1, dom.acts.size)
        assertEquals(TeachAction.CLICK, dom.acts[0])
        assertEquals("只到第 2 步（TYPE 两步已过）", 2, report.failedStep)
    }

    // ── 负例 C：expect 失败不重试（动作已执行过，重试＝双击双提交） ───────────

    @Test
    fun `负例C_动作成功但expect不满足_不重试_那个按钮只被点过一次`() {
        val dom = ClickButNoNavigation(FakeDom(pages(), START), START)
        val report = executor(dom).run(skill())

        assertFalse(report.ok)
        assertEquals(2, report.failedStep)
        assertTrue("停因是期望断言：${report.stopReason}", report.stopReason!!.contains("期望断言不满足"))
        assertEquals("「搜索」恰好被点过 1 次——expect 失败绝不补点", 1, dom.dom.clicks.count { it == "BUTTON/搜索" })
        assertEquals("expect 失败不进 retried", emptyList<Int>(), report.retried)
    }
}
