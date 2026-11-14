// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 判据⑤：四类触发各出一条问题卡（≤3 问），且每张卡都是合法的 203 房型
// （恰好一个推荐项排第一＋必留空白行）。判据层纯函数直测；四条负例钉「不该出的不出」。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.baopu.AskUserCardRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class LearnTriggersTest {

    /** 卡片文案（测试用字面量——纯层不碰资源，模板占位与 strings_learn.xml 一致）。 */
    private val texts = LearnCardTexts(
        qAmbiguous = "第 {步} 步「{目标}」在页面上有 {条数} 条走法，你平时用哪条？",
        qNoEvidence = "第 {步} 步点了「{目标}」但页面没变化，这一步想做什么？",
        qParam = "第 {步} 步「{目标}」填的内容每次都不同吗？",
        qIntent = "这次是在「{页}」做哪件事？",
        optParam = "是参数（每次会变）",
        optFixed = "固定值（每次一样）",
        optTeach = "就教这一步",
        optDrop = "这一步不算",
        freeText = "或者直接说你的想法",
    )

    private fun faces(
        tag: String = "BUTTON",
        name: String = "查询",
        inputType: String = "",
        inputName: String = "",
        placeholder: String = "",
        xp: String = "/html/body/div[1]/button[1]",
    ) = ObserveFaces(
        tag = tag, role = "button", name = name, aria = "", testid = "", id = "",
        inputName = inputName, placeholder = placeholder, inputType = inputType, auto = "",
        formLabel = "", xp = xp, cls = emptyList(),
    )

    private fun ev(
        kind: ObserveKind,
        seq: Int,
        faces: ObserveFaces,
        shape: ValueShape? = null,
        url: String = "https://example.test/list",
        nc: Int = 10,
        doc: String = "doc1",
        ts: Long = 1000L + seq,
    ) = ObserveEvent(kind = kind, ts = ts, docId = doc, seq = seq, url = url, nodeCount = nc, faces = faces, shape = shape)

    private fun node(faces: ObserveFaces) = ObserveNode(faces = faces, sensitive = false)

    private fun page(vararg nodes: ObserveNode, url: String = "https://example.test/list", nc: Int = 10, ts: Long = 500L, doc: String = "doc1") =
        ObservePage(ts = ts, docId = doc, url = url, title = "列表页", nodeCount = nc, nodes = nodes.toList())

    private fun snap(events: List<ObserveEvent>, pages: List<ObservePage>) =
        ObserveSnapshot(openedAtMs = 7_777_000L, events = events, pages = pages)

    // ── ① 定位不唯一 ─────────────────────────────────────────────────────────────

    @Test
    fun `判据5_定位不唯一_出卡且合法`() {
        val page = page(node(faces(name = "查询", xp = "button[1]")), node(faces(name = "查询", xp = "button[2]")))
        val snapshot = snap(listOf(ev(ObserveKind.CLICK, 1, faces(name = "查询"))), listOf(page))
        val triggers = LearnTriggers.validate(snapshot)
        assertEquals(1, triggers.size)
        assertEquals(LearnTriggerType.AMBIGUOUS_LOCATOR, triggers[0].type)
        val card = LearnTriggers.card(triggers[0], texts)
        assertTrue(AskUserCardRules.isValid(card))
        assertTrue("占位符要替换成真字", card.question.contains("第 1 步"))
        assertFalse("替换后不许留占位符", card.question.contains("{步}"))
        assertEquals(2, card.options.size)
    }

    @Test
    fun `定位唯一_不出卡`() {
        val page = page(node(faces(name = "查询", xp = "button[1]")))
        val snapshot = snap(listOf(ev(ObserveKind.CLICK, 1, faces(name = "查询"))), listOf(page))
        assertTrue(LearnTriggers.validate(snapshot).none { it.type == LearnTriggerType.AMBIGUOUS_LOCATOR })
    }

    // ── ② 缺证据 ─────────────────────────────────────────────────────────────────

    @Test
    fun `判据5_缺证据_看过没变化才出卡`() {
        val click = ev(ObserveKind.CLICK, 1, faces(name = "展开"), ts = 1000L)
        val later = page(nc = 10, ts = 3000L) // 之后收割过：URL 与节点数都没动
        val snapshot = snap(listOf(click), listOf(later))
        val triggers = LearnTriggers.validate(snapshot)
        assertEquals(1, triggers.size)
        assertEquals(LearnTriggerType.NO_EVIDENCE, triggers[0].type)
        assertTrue(AskUserCardRules.isValid(LearnTriggers.card(triggers[0], texts)))
    }

    @Test
    fun `有增量_不出缺证据卡`() {
        val click = ev(ObserveKind.CLICK, 1, faces(name = "展开"), ts = 1000L)
        val later = page(nc = 25, ts = 3000L) // 节点数变了 ⇒ 证据在
        val snapshot = snap(listOf(click), listOf(later))
        assertTrue(LearnTriggers.validate(snapshot).none { it.type == LearnTriggerType.NO_EVIDENCE })
    }

    @Test
    fun `没看过_也不出缺证据卡_不冤枉最后一次点击`() {
        val click = ev(ObserveKind.CLICK, 1, faces(name = "展开"), ts = 1000L)
        val earlier = page(nc = 10, ts = 500L) // 只有点击之前的收割 ⇒ 说不清
        val snapshot = snap(listOf(click), listOf(earlier))
        assertTrue(LearnTriggers.validate(snapshot).none { it.type == LearnTriggerType.NO_EVIDENCE })
    }

    // ── ③ 参数不确定 ─────────────────────────────────────────────────────────────

    @Test
    fun `判据5_参数不确定_数字或日期形状出卡`() {
        val digits = ev(ObserveKind.TYPE, 2, faces(tag = "INPUT", name = "单号", inputType = "text"), shape = ValueShape.DIGITS)
        val snapshot = snap(listOf(digits), emptyList())
        val triggers = LearnTriggers.validate(snapshot)
        assertEquals(1, triggers.size)
        assertEquals(LearnTriggerType.PARAM_UNCERTAIN, triggers[0].type)
        val card = LearnTriggers.card(triggers[0], texts)
        assertTrue(AskUserCardRules.isValid(card))
        assertTrue(card.options.first().recommended)
    }

    @Test
    fun `日期形状也触发`() {
        val date = ev(ObserveKind.TYPE, 3, faces(tag = "INPUT", name = "开始日期", inputType = "date"), shape = ValueShape.DATE)
        assertTrue(LearnTriggers.validate(snap(listOf(date), emptyList())).any { it.type == LearnTriggerType.PARAM_UNCERTAIN })
    }

    @Test
    fun `短文本输入_不出参数卡`() {
        val short = ev(ObserveKind.TYPE, 2, faces(tag = "INPUT", name = "备注", inputType = "text"), shape = ValueShape.SHORT)
        assertTrue(LearnTriggers.validate(snap(listOf(short), emptyList())).none { it.type == LearnTriggerType.PARAM_UNCERTAIN })
    }

    // ── ④ 意图不确定 ─────────────────────────────────────────────────────────────

    @Test
    fun `判据5_意图不确定_两个搜索框出卡`() {
        val a = ev(ObserveKind.TYPE, 1, faces(tag = "INPUT", inputType = "search", placeholder = "搜物料"), doc = "doc1", url = "https://a.test/")
        val b = ev(ObserveKind.TYPE, 2, faces(tag = "INPUT", inputType = "search", placeholder = "搜供应商"), doc = "doc2", url = "https://b.test/")
        val triggers = LearnTriggers.validate(snap(listOf(a, b), emptyList()))
        assertEquals(1, triggers.size)
        assertEquals(LearnTriggerType.INTENT_UNCERTAIN, triggers[0].type)
        val card = LearnTriggers.card(triggers[0], texts)
        assertTrue(AskUserCardRules.isValid(card))
        assertTrue(card.options.size >= 2)
    }

    @Test
    fun `单个搜索框_不出意图卡`() {
        val a = ev(ObserveKind.TYPE, 1, faces(tag = "INPUT", inputType = "search", placeholder = "搜物料"))
        assertTrue(LearnTriggers.validate(snap(listOf(a), emptyList())).none { it.type == LearnTriggerType.INTENT_UNCERTAIN })
    }

    // ── 合并与封顶 ───────────────────────────────────────────────────────────────

    @Test
    fun `四类各一条_每卡至多一问且合法`() {
        val page = page(node(faces(name = "查询", xp = "button[1]")), node(faces(name = "查询", xp = "button[2]")))
        val snapshot = snap(
            listOf(
                ev(ObserveKind.CLICK, 1, faces(name = "查询")),
                ev(ObserveKind.CLICK, 2, faces(name = "展开")),
                ev(ObserveKind.TYPE, 3, faces(tag = "INPUT", name = "单号", inputType = "text"), shape = ValueShape.DIGITS),
                ev(ObserveKind.TYPE, 4, faces(tag = "INPUT", inputType = "search", placeholder = "搜物料", xp = "input[1]")),
                ev(ObserveKind.TYPE, 5, faces(tag = "INPUT", inputType = "search", placeholder = "搜供应商", xp = "input[2]")),
            ),
            listOf(page(node(faces(name = "查询", xp = "button[1]")), node(faces(name = "查询", xp = "button[2]")), ts = 9_000L)),
        )
        val triggers = LearnTriggers.validate(snapshot)
        assertEquals("四类各出一条（封顶不刷屏）", 4, triggers.size)
        assertEquals(LearnTriggerType.entries.toSet(), triggers.map { it.type }.toSet())
        triggers.map { LearnTriggers.card(it, texts) }.forEach { card ->
            assertTrue(AskUserCardRules.isValid(card))
            assertTrue("卡面必有题面（一卡一问，≤3 问口径）", card.question.isNotBlank())
        }
    }

    @Test
    fun `toItem_卡片题面随条目落盘`() {
        val page = page(node(faces(name = "查询", xp = "button[1]")), node(faces(name = "查询", xp = "button[2]")))
        val snapshot = snap(listOf(ev(ObserveKind.CLICK, 1, faces(name = "查询"))), listOf(page))
        val trigger = LearnTriggers.validate(snapshot).single()
        val item = LearnTriggers.toItem(trigger, LearnTriggers.card(trigger, texts), createdAt = 42L)
        assertEquals(trigger.id, item.id)
        assertEquals(42L, item.createdAt)
        assertTrue(item.question.contains("第 1 步"))
        assertEquals(trigger.candidates.size, item.options.size)
    }
}
