// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二百一十二批 · 三态机（派工单 §1 交互 ＋ §3 负例①②⑦）。
 *
 * 负例①②是**结构**保证而不是纪律，所以这里断言的是「引用没变」：
 * 收起再开时手上那张卡必须是原来那个对象——只要没有重新查一次，就不可能变。
 */
class ResultCardStateTest {

    private fun card(template: String, value: String, identity: String = ""): ResultCardSpec = ResultCardSpec(
        templateId = template,
        title = template,
        groups = listOf(CardGroup("g", null, listOf(CardRow("k", "标签", value)))),
        actions = emptyList(),
        itemCount = 1,
        identity = identity,
    )

    @Test
    fun `到达即展开_最新那张在上面`() {
        var s = ResultCardState()
        s = ResultCards.push(s, card("a", "1"))
        assertEquals(CardPhase.EXPANDED, s.phase)
        assertEquals(0, s.activeIndex)
        s = ResultCards.push(s, card("b", "2"))
        assertEquals(1, s.activeIndex)
        assertEquals(2, s.cards.size)
        assertEquals(1, s.foldedCount) // 另一张折成小条
    }

    @Test
    fun `收起不丢数据_再开是同一张（负例①②）`() {
        var s = ResultCards.push(ResultCardState(), card("a", "1"))
        val before = s.active
        s = ResultCards.collapse(s)
        assertEquals(CardPhase.COLLAPSED, s.phase)
        assertEquals("收起只是呈现，列表与被收起的那张都还在", 1, s.cards.size)
        assertSame("收起不许重建卡对象（否则「没重查」就成了自觉）", before, s.cards[0])
        s = ResultCards.expand(s)
        assertEquals(CardPhase.EXPANDED, s.phase)
        assertSame("再开必须还是落库前那一份", before, s.active)
    }

    @Test
    fun `点小条能换到指定那张`() {
        var s = ResultCardState()
        s = ResultCards.push(s, card("a", "1"))
        s = ResultCards.push(s, card("b", "2"))
        s = ResultCards.expand(s, 0)
        assertEquals(0, s.activeIndex)
        // 下标越界 ⇒ 留在当前展开的那张（不把用户刚打开的那张换掉）
        s = ResultCards.expand(s, 99)
        assertEquals(0, s.activeIndex)
        // 连当前都没有 ⇒ 落到最近一张
        assertEquals(1, ResultCards.expand(ResultCardState(cards = s.cards), 99).activeIndex)
    }

    @Test
    fun `同一批结果不许弹两张一样的卡（负例⑦）`() {
        // ① 没给身份 ⇒ 逐行比值：一模一样的两张只留一张
        var s = ResultCardState()
        s = ResultCards.push(s, card("a", "1"))
        s = ResultCards.push(s, card("a", "1"))
        assertEquals(1, s.cards.size)
        // 值不同 ⇒ 是另一件事，宁可两张也不许误吞
        s = ResultCards.push(s, card("a", "2"))
        assertEquals(2, s.cards.size)

        // ② 给了身份 ⇒ 同一条（值被刷新）原地换值
        var t = ResultCardState()
        t = ResultCards.push(t, card("a", "1", identity = "单号1"))
        t = ResultCards.push(t, card("a", "9", identity = "单号1"))
        assertEquals(1, t.cards.size)
        assertEquals("9", t.cards[0].rows[0].value)
        // 身份不同 ⇒ 另一条，并排
        t = ResultCards.push(t, card("a", "9", identity = "单号2"))
        assertEquals(2, t.cards.size)
    }

    @Test
    fun `整组收起留计数_点开回小条`() {
        var s = ResultCards.push(ResultCardState(), card("a", "1"))
        s = ResultCards.push(s, card("b", "2"))
        s = ResultCards.collapseAll(s)
        assertEquals(CardPhase.HIDDEN, s.phase)
        assertEquals(2, s.hiddenCount)
        assertEquals("整组收起不清空（否则数据真丢了）", 2, s.cards.size)
        s = ResultCards.restore(s)
        assertEquals(CardPhase.COLLAPSED, s.phase)
        assertEquals(0, s.hiddenCount)
    }

    @Test
    fun `空状态点什么都不炸`() {
        val empty = ResultCardState()
        assertEquals(CardPhase.COLLAPSED, empty.phase)
        assertEquals(empty, ResultCards.collapse(empty))
        assertEquals(empty, ResultCards.expand(empty))
        assertFalse(empty.cards.isNotEmpty())
        assertTrue(ResultCards.clear().cards.isEmpty())
    }
}
