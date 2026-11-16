// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十二批 · 判据③：裁定卡并排两套定位（描述·命中数齐）＋一帧证据；缺证据只显示
// 「无证据」不崩。卡面是纯层（[AdjudicationCard]），这里直接算读数，不启动 Compose。
package com.example.zhengdao.baopu.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AdjudicationCardTest {

    private val url = "https://example.test/list"

    private fun faces(name: String, xp: String) = ObserveFaces(
        tag = "BUTTON", role = "button", name = name, aria = "", testid = "", id = "",
        inputName = "", placeholder = "", inputType = "", auto = "", formLabel = "",
        xp = xp, cls = emptyList(),
    )

    private fun blank() = ObserveFaces(
        tag = "", role = "", name = "", aria = "", testid = "", id = "", inputName = "",
        placeholder = "", inputType = "", auto = "", formLabel = "", xp = "", cls = emptyList(),
    )

    private fun conflictStep(evidence: List<String> = listOf("观察/会话-1.jsonl#seq2")) = DraftStep(
        index = 2, intent = "查询", kind = "click", url = url, locator = blank(),
        param = null, optional = false,
        candidates = listOf(faces("查询", "button[1]"), faces("查货", "button[2]")),
        expect = listOf("urlChanged"), evidence = evidence,
    )

    private fun draft(step: DraftStep) = SkillDraft(
        id = "d1", goal = "查装车", targetSite = "example.test", params = emptyList(),
        skeleton = listOf(step), variableSteps = emptyList(), mergedFrom = 2,
        sourceSessions = listOf("1", "2"),
    )

    /** 当时那份页面节点表：两个「查询」＋一个「查货」。 */
    private fun snapshot() = ObserveSnapshot(
        openedAtMs = 1_000L,
        events = emptyList(),
        pages = emptyList(),
        nodeTables = mapOf(
            "doc1" to ObservePage(
                ts = 1_000L, docId = "doc1", url = url, title = "列表页", nodeCount = 3,
                nodes = listOf(
                    ObserveNode(faces("查询", "button[1]"), sensitive = false),
                    ObserveNode(faces("查询", "button[3]"), sensitive = false),
                    ObserveNode(faces("查货", "button[2]"), sensitive = false),
                ),
            ),
        ),
    )

    @Test
    fun `判据3_并排两套定位与命中数齐`() {
        val cards = AdjudicationCard.steps(draft(conflictStep()), snapshot(), emptyList())
        assertEquals("一步一张卡", 1, cards.size)
        val card = cards.single()
        assertEquals("A 是候选[0]", DraftChoice.A, card.a.slot)
        assertEquals("B 是候选[1]", DraftChoice.B, card.b.slot)
        assertEquals("A 命中两处（同名不同路径）", 2, card.a.hits)
        assertEquals("B 命中一处", 1, card.b.hits)
        assertEquals("命中数文案齐", "命中 2 处", card.hitsText(card.a))
        assertEquals("命中数文案齐", "命中 1 处", card.hitsText(card.b))
        assertTrue("A 的描述含可读名：${card.a.desc}", card.a.desc.contains("查询"))
        assertTrue("B 的描述含可读名：${card.b.desc}", card.b.desc.contains("查货"))
        assertTrue("描述带路径（两套同名也分得开）：${card.a.desc}", card.a.desc.contains("button[1]"))
        assertNotEquals("两套定位必须可辨", card.a.desc, card.b.desc)
        assertEquals("未裁", null, card.resolved)
    }

    @Test
    fun `判据3_缺证据只显示无证据_不崩`() {
        val card = AdjudicationCard.steps(draft(conflictStep(evidence = emptyList())), snapshot(), emptyList()).single()
        assertNull("没有证据引用", card.evidenceRef)
        assertEquals("如实说无证据", AdjudicationCard.NO_EVIDENCE, card.evidenceText())
    }

    @Test
    fun `判据3_没有观察面_命中数说未知而不编0`() {
        val card = AdjudicationCard.steps(draft(conflictStep()), null, emptyList()).single()
        assertNull("没有页面结构 ⇒ 未知", card.a.hits)
        assertEquals(AdjudicationCard.HITS_UNKNOWN, card.hitsText(card.a))
        assertEquals("证据照旧可读", "观察/会话-1.jsonl#seq2", card.evidenceText())
    }

    @Test
    fun `已裁结果回到卡面_带裁者与时刻`() {
        val cards = AdjudicationCard.steps(
            draft(conflictStep()),
            snapshot(),
            listOf(DraftAdjudication(2, DraftChoice.B, 1_700_000_000_000L, "用户")),
        )
        val card = cards.single()
        assertEquals(DraftChoice.B, card.resolved)
        assertEquals("用户", card.by)
        assertEquals(1_700_000_000_000L, card.atMs)
    }

    @Test
    fun `单候选的步不出卡_两套才出`() {
        val one = conflictStep().copy(candidates = listOf(faces("查询", "button[1]")))
        assertTrue("单候选是坏形，不归这张卡管", AdjudicationCard.steps(draft(one), snapshot(), emptyList()).isEmpty())
    }
}
