// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 §1 学习器单测：5 个示教事件 → 5 条带定位链与 expect 的步骤；
// 每步 ≥2 档；拒点/密码动作不记；同元素连续输入合并。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachLearnerTest {

    // ── 夹具：一个公开搜索页的示教会话（5 个动作，与执行器正例同一套页面）──────

    val START = "https://search.example.com/start"
    val RESULTS = "https://search.example.com/results?wb=WB12345678"

    private val inputQ = TeachElementInfo(
        tag = "input", role = "searchbox", name = null, text = null,
        ariaLabel = null, testid = null, elemId = "search-input", inputName = "q",
        placeholder = "输入关键词", inputType = "search", formLabel = "关键词",
        classes = listOf("filter-box"),
    )
    private val btnSearch = TeachElementInfo(
        tag = "button", role = "button", name = "搜索", text = "搜索",
        classes = listOf("btn", "primary"), testid = "search-btn", elemId = "btn-search",
    )
    private val btnNext = TeachElementInfo(
        tag = "button", role = "button", name = "下一页", text = "下一页",
        classes = listOf("pager-next"),
    )
    private val inputFilter = TeachElementInfo(
        tag = "input", role = "textbox", name = null, text = null,
        elemId = "filter-input", inputName = "filter", placeholder = "筛选运单号",
        inputType = "text", formLabel = "筛选",
    )
    private val btnExport = TeachElementInfo(
        tag = "button", role = "button", name = "导出", text = "导出",
        classes = listOf("btn", "export"),
    )

    private fun startFacts() = TeachPageFacts(
        url = START,
        headers = listOf("关键词"),
        buttons = listOf("搜索", "提交"), // 诱饵「提交」也在起点页上（录制时不点）
    )

    internal fun fiveEvents(): List<TeachRawEvent> = listOf(
        TeachRawEvent(TYPE, START, inputQ, "WB12345678", START, START, 0, 0),
        TeachRawEvent(CLICK, START, btnSearch, null, START, RESULTS, 0, 20),
        TeachRawEvent(CLICK, RESULTS, btnNext, null, RESULTS, RESULTS, 20, 15),
        TeachRawEvent(TYPE, RESULTS, inputFilter, "WB87654321", RESULTS, RESULTS, 15, 15),
        TeachRawEvent(CLICK, RESULTS, btnExport, null, RESULTS, "https://search.example.com/export", 15, 0),
    )

    private companion object {
        val TYPE = TeachAction.TYPE
        val CLICK = TeachAction.CLICK
    }

    @Test
    fun `判据_5个事件出5步_每步至少2档定位_每步有expect`() {
        val draft = TeachLearner.buildDraft(fiveEvents(), "查货", "查当日报文", startFacts(), 0L)
        val skill = draft.skill
        assertEquals(5, skill.steps.size)
        skill.steps.forEach { s ->
            assertTrue("第 ${s.index} 步定位链不足 2 档：${s.locators}", s.locators.size >= 2)
            assertTrue("第 ${s.index} 步缺 expect", s.expect.isNotEmpty())
            assertEquals(s.locators.map { it.tier }, s.locators.map { it.tier }.sorted())
        }
        // expect 类型逐一对上
        assertEquals(TeachExpectKind.TEXT_APPEARS, skill.steps[0].expect.first().kind)
        assertEquals(TeachExpectKind.URL_CHANGED, skill.steps[1].expect.first().kind)
        assertEquals(TeachExpectKind.ROW_COUNT_CHANGE, skill.steps[2].expect.first().kind)
        assertEquals(TeachExpectKind.TEXT_APPEARS, skill.steps[3].expect.first().kind)
        assertEquals(TeachExpectKind.URL_CHANGED, skill.steps[4].expect.first().kind)
        // 摘要是人话（确认卡能直接给人看）
        assertTrue(draft.summary.contains("5 步"))
        assertTrue(draft.summary.contains("搜索"))
    }

    @Test
    fun `授权域取自示教全程_点击白名单只收点过的类别`() {
        val draft = TeachLearner.buildDraft(fiveEvents(), "查货", "查当日报文", startFacts(), 0L)
        assertEquals(listOf("search.example.com"), draft.skill.allowedDomains)
        assertEquals(listOf("BUTTON"), draft.skill.clickTagWhitelist)
    }

    @Test
    fun `指纹取起点页_表头与按钮都进锚`() {
        val draft = TeachLearner.buildDraft(fiveEvents(), "查货", "查当日报文", startFacts(), 0L)
        val names = draft.skill.fingerprint.anchors.map { it.name }
        assertTrue(names.contains("关键词"))
        assertTrue(names.contains("搜索"))
        assertEquals(START, draft.skill.startUrl)
    }

    @Test
    fun `负例3录制半边_点提交的动作不进步骤且留警示`() {
        val events = fiveEvents() + TeachRawEvent(
            CLICK, RESULTS,
            TeachElementInfo(tag = "button", role = "button", name = "提交", text = "提交"),
            null, RESULTS, RESULTS, 15, 15,
        )
        val draft = TeachLearner.buildDraft(events, "查货", "查", startFacts(), 0L)
        assertEquals(5, draft.skill.steps.size) // 「提交」没进去
        assertTrue(draft.warnings.any { it.contains("拒记") && it.contains("提交") })
    }

    @Test
    fun `负例4录制半边_密码框输入不记_值不进技能`() {
        val pwd = TeachElementInfo(
            tag = "input", role = "textbox", inputType = "password",
            elemId = "login-pwd", inputName = "password",
        )
        val events = fiveEvents() + TeachRawEvent(TYPE, START, pwd, "hunter2secret", START, START, 0, 0)
        val draft = TeachLearner.buildDraft(events, "查货", "查", startFacts(), 0L)
        assertEquals(5, draft.skill.steps.size)
        assertTrue(draft.skill.steps.none { it.value == "hunter2secret" })
        assertTrue(draft.warnings.any { it.contains("拒记") && it.contains("密码") })
    }

    @Test
    fun `同元素连续输入合并一步_记最后一次值`() {
        val events = listOf(
            TeachRawEvent(TYPE, START, inputQ, "WB1", START, START, 0, 0),
            TeachRawEvent(TYPE, START, inputQ, "WB12345678", START, START, 0, 0),
        )
        val draft = TeachLearner.buildDraft(events, "查货", "查", startFacts(), 0L)
        assertEquals(1, draft.skill.steps.size)
        assertEquals("WB12345678", draft.skill.steps[0].value)
    }

    @Test
    fun `稳定类过滤_hash构建类丢掉_普通类保留`() {
        assertTrue(TeachLearner.stableCssClass("btn"))
        assertTrue(TeachLearner.stableCssClass("pager-next"))
        assertTrue(!TeachLearner.stableCssClass("css-1x2y3z"))
        assertTrue(!TeachLearner.stableCssClass("jsx-456"))
        assertTrue(!TeachLearner.stableCssClass("btn2"))
        val locators = TeachLearner.buildLocators(
            TeachElementInfo(tag = "button", classes = listOf("css-1q2w3e", "btn")),
        )
        assertTrue(locators.any { it.kind == TeachLocatorKind.CSS_CLASS && it.value == "btn" })
    }

    @Test
    fun `定位链十档齐全时按tier升序`() {
        val rich = TeachElementInfo(
            tag = "input", role = "textbox", name = "关键词", text = null,
            ariaLabel = "关键词输入", testid = "kw", elemId = "kw-input", inputName = "kw",
            placeholder = "输入关键词", inputType = "text", formLabel = "关键词",
            classes = listOf("kw-box"), xpath = "/html/body/div[1]/input[1]",
        )
        val tiers = TeachLearner.buildLocators(rich).map { it.tier }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 8, 9, 10), tiers) // text 为空 ⇒ T7 缺
    }
}
