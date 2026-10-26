// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十六批 G2 单测：跨导航录制不再依赖「每个文档一个队列」。
// 三条靶子：
//   ① 两条路（即时桥／600ms 轮询）同一事件只留一份；
//   ② 事件顺序按「文档首次到达序＋文档内序号」重排——到达先后不算数；
//   ③ 点击触发跳转那一步仍带 URL_CHANGED（点击→跳页→再点两下 ⇒ ≥3 步）。
// 负例「0 步却当成功」的靶子：TeachStopRules（见过导航 ≠ 没录到任何操作）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachEventBufferTest {

    private val start = "https://example.com/"
    private val next = "https://www.iana.org/help/example-domains"

    private val link = TeachElementInfo(
        tag = "a", role = "link", name = "More information...", text = "More information...",
    )
    private val btn = TeachElementInfo(
        tag = "button", role = "button", name = "下一步", text = "下一步",
        elemId = "next-btn", testid = "next",
    )

    private fun click(url: String, el: TeachElementInfo = btn): TeachRawEvent = TeachRawEvent(
        action = TeachAction.CLICK,
        url = url,
        element = el,
        value = null,
        beforeUrl = url,
        afterUrl = url,
        beforeRows = 0,
        afterRows = 0,
    )

    @Test
    fun `跨导航后事件不丢_按文档序重排且点击跳转那步仍是URL_CHANGED`() {
        val b = TeachEventBuffer()
        b.seedStart(start)
        // 链接点下去 ⇒ 新文档开始加载（onPageStarted／收割都会报这一下）
        b.notePage(next)
        // 即时路先到新文档的两个点击（页面跳走之后）
        assertTrue(b.add("doc2", 1, click(next)))
        assertTrue(b.add("doc2", 2, click(next)))
        // 轮询把旧文档那条补回来（点链接发生在新文档存在之前）
        assertTrue(b.add("doc1", 1, click(start, link)))

        val events = b.snapshot()
        assertEquals("三条事件一条都不许丢", 3, events.size)
        assertEquals("按文档序重排：旧文档那条排第一", start, events[0].url)
        assertEquals("点击跳页那一步的 afterUrl = 落点页", next, events[0].afterUrl)
        assertEquals("跳页之后的两步都在新文档", next, events[1].url)
        assertEquals(next, events[2].url)
        assertTrue("见过跨页", b.sawNavigation)

        // 学习器照常出 ≥3 步；跳页那一步的 expect 必须是 URL_CHANGED（196 的语义一字不丢）
        val draft = TeachLearner.buildDraft(
            events, "跨域点击", "在 example.com 上重复示教的操作",
            TeachPageFacts(start, emptyList(), emptyList()), 0L,
        )
        assertEquals(3, draft.skill.steps.size)
        assertEquals(TeachExpectKind.URL_CHANGED, draft.skill.steps[0].expect.first().kind)
    }

    @Test
    fun `同一事件经即时桥与轮询两路到达只留一份`() {
        val b = TeachEventBuffer()
        b.seedStart(start)
        assertTrue("第一次到达收下", b.add("doc1", 7, click(start)))
        assertFalse("同一 doc#seq 第二次到达丢掉（两路去重）", b.add("doc1", 7, click(start)))
        // 不同文档的同一序号是两条不同事件（跨导航后序号从头数）
        assertTrue(b.add("doc2", 7, click(next)))
        assertEquals(2, b.snapshot().size)
    }

    @Test
    fun `收尾页算最后一步的afterUrl_同页点击不误报跳页`() {
        val b = TeachEventBuffer()
        b.seedStart(start)
        b.add("doc1", 1, click(start))
        b.add("doc1", 2, click(start))
        // 轮询看到的最新页仍是起点页 ⇒ 最后一步 afterUrl 不跳
        b.notePage(start, rows = 12)
        val events = b.snapshot()
        assertEquals(start, events.last().afterUrl)
        assertEquals(12, events.last().afterRows)
        assertFalse(b.sawNavigation)
    }

    @Test
    fun `停止规则_0步见过导航报录到导航而不是没录到`() {
        assertEquals(TeachStopKind.DRAFT, TeachStopRules.kind(3, sawNavigation = false))
        assertEquals(TeachStopKind.DRAFT, TeachStopRules.kind(1, sawNavigation = true))
        assertEquals(
            "0 步但页面确实跳过：不许说「没有录到任何操作」",
            TeachStopKind.EMPTY_NAV_ONLY,
            TeachStopRules.kind(0, sawNavigation = true),
        )
        assertEquals(TeachStopKind.EMPTY_NO_EVENTS, TeachStopRules.kind(0, sawNavigation = false))
    }

    @Test
    fun `容量上限到了不无限涨_先到的被挤掉`() {
        val b = TeachEventBuffer(cap = 3)
        (1..5).forEach { b.add("doc1", it, click(start, btn.copy(name = "第${it}下"))) }
        assertEquals(3, b.size)
        // 先进先出：留下的必须是最后三条（1、2 被挤掉）
        assertEquals(listOf("第3下", "第4下", "第5下"), b.snapshot().map { it.element.name })
    }
}
