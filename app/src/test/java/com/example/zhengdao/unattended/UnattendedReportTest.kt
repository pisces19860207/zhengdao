// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 388 判据"被中断要补报"的报告侧：中断逐条列、没中断也显式说；每一节空也要写"无"——
// "没写"与"没有"必须分得清（报告不许交白卷，也不许编）。
package com.example.zhengdao.unattended

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnattendedReportTest {

    private val t0 = 1_700_000_000_000L

    private fun ev(kind: UnattendedEventKind, atMs: Long, text: String) =
        UnattendedEvent(kind, atMs, text)

    // ── 空手不交白卷 ────────────────────────────────────────────────────

    @Test
    fun `什么都没发生_每一节都显式写无`() {
        val doc = UnattendedReport.build(UnattendedReportInput(armedAtMs = t0, closedAtMs = t0 + 60_000L))
        val text = doc.text()
        assertTrue(text.contains("A 级（要她处理/知道）：无"))
        assertTrue(text.contains("入队（等她授权）：无"))
        assertTrue(text.contains("B 级汇总：无"))
        assertTrue(text.contains("中断补报：值守期间无中断"))
        assertTrue(text.contains("未做项：无"))
    }

    @Test
    fun `头一行带值守窗口时长与事件条数`() {
        val doc = UnattendedReport.build(
            UnattendedReportInput(
                armedAtMs = t0,
                closedAtMs = t0 + 90 * 60_000L,
                events = listOf(ev(UnattendedEventKind.ARM, t0, "开")),
            ),
        )
        assertTrue(doc.text().contains("值守窗口 1 小时 30 分钟"))
        assertTrue(doc.text().contains("事件 1 条"))
    }

    @Test
    fun `期中报告_写值守中_积压不写未做`() {
        val doc = UnattendedReport.build(
            UnattendedReportInput(
                armedAtMs = t0,
                closedAtMs = null,
                events = emptyList(),
                queueLines = listOf("删哪些旧稿？"),
            ),
        )
        assertTrue(doc.text().contains("值守中"))
        assertTrue(doc.text().contains("当前积压 1 件"))
        assertFalse("期中还没有'未做'可言", doc.text().contains("未做项"))
    }

    // ── A 级（要她处理/知道的事） ───────────────────────────────────────

    @Test
    fun `A级的四格各记一条_停手_降级_暂停_复工`() {
        val doc = UnattendedReport.build(
            UnattendedReportInput(
                armedAtMs = t0,
                closedAtMs = t0 + 60_000L,
                events = listOf(
                    ev(UnattendedEventKind.STOP, t0 + 1, "外发拦截"),
                    ev(UnattendedEventKind.PAUSE, t0 + 2, "电量低"),
                    ev(UnattendedEventKind.RESUME, t0 + 3, "电量低"),
                    ev(UnattendedEventKind.DEGRADE, t0 + 4, "B级超时"),
                ),
            ),
        )
        val text = doc.text()
        assertTrue("整篇：\n$text", text.contains("A 级（要她处理/知道）4 条："))
        assertTrue(text.contains("· 红线停手｜外发拦截"))
        assertTrue(text.contains("· 环境暂停｜电量低"))
        assertTrue(text.contains("· 环境复工｜电量低"))
        assertTrue(text.contains("· 降级停手｜B级超时"))
    }

    // ── 入队 / B 级汇总 ─────────────────────────────────────────────────

    @Test
    fun `入队列出每一件_她回场第一眼看这里`() {
        val doc = UnattendedReport.build(
            UnattendedReportInput(
                armedAtMs = t0,
                closedAtMs = t0 + 60_000L,
                events = listOf(
                    ev(UnattendedEventKind.QUEUE, t0 + 1, "删哪些旧稿？"),
                    ev(UnattendedEventKind.QUEUE, t0 + 2, "要不要装这个更新？"),
                ),
            ),
        )
        assertTrue(doc.text().contains("入队（等她授权）2 件："))
        assertTrue(doc.text().contains("· 删哪些旧稿？"))
        assertTrue(doc.text().contains("· 要不要装这个更新？"))
    }

    @Test
    fun `B级汇总列条数与最近一条`() {
        val doc = UnattendedReport.build(
            UnattendedReportInput(
                armedAtMs = t0,
                closedAtMs = t0 + 3 * 60 * 60_000L,
                events = listOf(
                    ev(UnattendedEventKind.SUMMARY, t0 + 1, "B级汇总｜此段事件 0 条"),
                    ev(UnattendedEventKind.SUMMARY, t0 + 2, "B级汇总｜此段事件 2 条"),
                ),
            ),
        )
        assertTrue(doc.text().contains("B 级汇总 2 条，最近一条："))
        assertTrue(doc.text().contains("· B级汇总｜此段事件 2 条"))
    }

    @Test
    fun `超2小时没汇总_报告要点名去看降级账`() {
        val doc = UnattendedReport.build(
            UnattendedReportInput(armedAtMs = t0, closedAtMs = t0 + 3 * 60 * 60_000L),
        )
        assertTrue(doc.text().contains("若值守超 2h，见降级/看门狗账"))
    }

    // ── 中断补报（判据原话：被中断要补报） ──────────────────────────────

    @Test
    fun `有中断_逐条补报_断档时长在列`() {
        val doc = UnattendedReport.build(
            UnattendedReportInput(
                armedAtMs = t0,
                closedAtMs = t0 + 60_000L,
                events = listOf(
                    ev(UnattendedEventKind.INTERRUPT, t0 + 1, "服务中断，断档 25 分钟（本笔为复工补报）"),
                ),
            ),
        )
        assertTrue(doc.text().contains("中断补报 1 次："))
        assertTrue(doc.text().contains("断档 25 分钟"))
    }

    // ── 未做项 ──────────────────────────────────────────────────────────

    @Test
    fun `未做项用收场快照_与入队的事件账各是各的源`() {
        val doc = UnattendedReport.build(
            UnattendedReportInput(
                armedAtMs = t0,
                closedAtMs = t0 + 60_000L,
                events = listOf(ev(UnattendedEventKind.QUEUE, t0 + 1, "后来已答的一件")),
                queueLines = listOf("还压着的一件"),
            ),
        )
        assertTrue(doc.text().contains("· 后来已答的一件"))
        assertTrue(doc.text().contains("未做项 1 件："))
        assertTrue(doc.text().contains("· 还压着的一件"))
    }

    // ── 时长的人话 ──────────────────────────────────────────────────────

    @Test
    fun `时长三档_不足1分钟_分钟_小时`() {
        assertEquals("不足 1 分钟", UnattendedReport.formatSpan(30_000L))
        assertEquals("25 分钟", UnattendedReport.formatSpan(25 * 60_000L))
        assertEquals("1 小时 0 分钟", UnattendedReport.formatSpan(60 * 60_000L))
        assertEquals("2 小时 5 分钟", UnattendedReport.formatSpan(125 * 60_000L))
    }
}
