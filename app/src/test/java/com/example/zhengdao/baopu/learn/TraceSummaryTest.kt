// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百五十四批 · 轨迹摘要（判据⑤）：≤300 字、只有步骤序号＋动作＋定位种类、
// 敏感字段整条丢弃、页面正文一个字都不许漏。
//
// 判据⑤的夹具刻意把「正文关键词」与「敏感样本」都放进同一次轨迹：
//   · 页标题/节点名里带正文关键词（张三、对账单金额）——摘要里出现任何一个就算红；
//   · 一条密码输入位的操作——它必须整条不出现（连「第几步」都不给）。
package com.example.zhengdao.baopu.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TraceSummaryTest {

    private val openedAt = 1_000_000L

    private fun faces(
        name: String = "查询",
        tag: String = "BUTTON",
        testid: String = "",
        placeholder: String = "",
        xp: String = "/html/body/button[1]",
    ) = ObserveFaces(
        tag = tag, role = "button", name = name, aria = "", testid = testid, id = "",
        inputName = "", placeholder = placeholder, inputType = "", auto = "",
        formLabel = "", xp = xp, cls = emptyList(),
    )

    private fun event(
        kind: ObserveKind,
        seq: Int,
        faces: ObserveFaces,
        url: String = "https://example.test/order",
    ) = ObserveEvent(
        kind = kind, ts = openedAt + seq * 1_000L, docId = "doc1", seq = seq,
        url = url, nodeCount = 12, faces = faces, shape = null,
    )

    /** 带正文的页（标题＋节点名都是正文敏感内容）；摘要碰它们一下就是漏。 */
    private fun page() = ObservePage(
        ts = openedAt + 500, docId = "doc1", url = "https://example.test/order",
        title = "张三的对账单 金额 12800", nodeCount = 12,
        nodes = listOf(
            ObserveNode(faces("张三的货款明细", xp = "/html/body/div[2]"), sensitive = false),
        ),
    )

    private fun snapshot(events: List<ObserveEvent>) =
        ObserveSnapshot(openedAt, events, listOf(page()), mapOf("doc1" to page()))

    // ── 判据⑤：≤300 字、无正文、敏感整条丢弃 ──────────────────────────────────

    @Test
    fun `判据5_摘要不超300字_不含正文关键词_敏感操作整条丢弃`() {
        // 上限是派工单写死的数，这里钉字面量——用常量自证等于恒真（改常量也不会红）
        assertEquals("300 字是派工单写死的上限", 300, TraceSummary.MAX_CHARS)
        val snapshot = snapshot(
            listOf(
                event(ObserveKind.CLICK, 1, faces(name = "查询", testid = "q-btn")),
                // 敏感输入位（placeholder 含「密码」）⇒ 整条丢弃
                event(ObserveKind.TYPE, 2, faces(name = "", tag = "INPUT", placeholder = "登录密码", xp = "/html/body/input[1]")),
                event(ObserveKind.TYPE, 3, faces(name = "单号", tag = "INPUT", xp = "/html/body/input[2]")),
            ),
        )
        val summary = TraceSummary.of(snapshot)
        assertTrue("不许超 300 字（实到 ${summary.length}）", summary.length <= 300)
        assertTrue("要有步骤序号：$summary", summary.contains("1.") && summary.contains("2."))
        assertTrue("要有动作名：$summary", summary.contains("点击") && summary.contains("输入"))
        assertTrue("要有定位种类：$summary", summary.contains("testid"))
        assertTrue("要说清略去了敏感操作：$summary", summary.contains("已略去 1 条敏感操作"))
        // 正文一个字都不许漏（页标题/节点名/填过的内容/密码字样）
        listOf("张三", "对账单", "12800", "货款", "密码", "登录密码").forEach { leak ->
            assertFalse("摘要漏了正文「$leak」：$summary", summary.contains(leak))
        }
        assertFalse("不许出现页面 URL", summary.contains("example.test"))
    }

    @Test
    fun `判据5_长轨迹压到300字以内_并标已截断`() {
        val events = (1..80).map { i -> event(ObserveKind.CLICK, i, faces(name = "按钮$i", xp = "button[$i]")) }
        val summary = TraceSummary.of(snapshot(events))
        assertTrue("80 步也必须 ≤300 字（实到 ${summary.length}）", summary.length <= 300)
        assertTrue("要如实标截断：$summary", summary.contains("共 80 步") && summary.contains("摘要已截断"))
        assertTrue("截断后仍要有可读的步骤：$summary", summary.contains("1. 点击"))
    }

    @Test
    fun `只有滚动或空会话_如实说没有可成步的操作`() {
        val onlyScroll = snapshot(listOf(event(ObserveKind.SCROLL, 1, faces())))
        assertEquals(TraceSummary.NO_STEPS, TraceSummary.of(onlyScroll))
        assertEquals(TraceSummary.NO_STEPS, TraceSummary.of(ObserveSnapshot(openedAt, emptyList(), emptyList())))
    }

    @Test
    fun `定位种类_按身份面点名_全空说无定位`() {
        val blank = ObserveFaces(
            tag = "", role = "", name = "", aria = "", testid = "", id = "",
            inputName = "", placeholder = "", inputType = "", auto = "", formLabel = "",
            xp = "", cls = emptyList(),
        )
        val s = snapshot(
            listOf(
                event(ObserveKind.CLICK, 1, faces(name = "查询", testid = "q", xp = "/a")),
                event(ObserveKind.CLICK, 2, blank),
            ),
        )
        val summary = TraceSummary.of(s)
        assertTrue("要列出命中哪几级定位：$summary", summary.contains("testid") && summary.contains("xp"))
        assertTrue("全空的面要如实说无定位：$summary", summary.contains("无定位"))
    }

    @Test
    fun `压低上限时_截断不越界（上限是硬约束）`() {
        val events = (1..20).map { i -> event(ObserveKind.CLICK, i, faces(name = "按钮$i", xp = "button[$i]")) }
        val tight = TraceSummary.of(snapshot(events), maxChars = 60)
        assertTrue("60 字上限必须守住（实到 ${tight.length}）：$tight", tight.length <= 60)
        assertTrue("截断要留话：$tight", tight.contains("截断") || tight.contains("共 20 步"))
    }
}
