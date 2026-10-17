// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExtractAsk 纯函数单测（刀 2 追加包：抽取流程的「一次 ask_user」）。
 *
 * 钉死：三选项 + 推荐第一 + 空白行永远在（过 AskUserCardRules）、
 * 问题行带章数与前 3 章标题、自由输入的确定性解释（纯章号 ⇒ 精读那些章）。
 */
class ExtractAskTest {

    private val labels = ExtractLabels(
        digest = "逐章摘要",
        index = "只要索引，不生成摘要",
        deep = "精读某几章",
        freePrompt = "或者直接输入：摘要 / 索引 / 章号（如 3,5,8）",
    )

    private fun card(count: Int = 5, titles: List<String> = listOf("第一章 甲", "第二章 乙", "第三章 丙", "第四章 丁")) =
        ExtractAsk.card(count, titles, labels)

    @Test
    fun `卡_三选项推荐第一且空白行在`() {
        val c = card()
        assertTrue("必须过提问卡校验", ExtractAsk.isValid(c))
        assertEquals(3, c.options.size)
        assertTrue(c.options.first().recommended)
        assertEquals(ExtractAsk.ID_DIGEST, c.options.first().id)
        assertTrue(c.freeTextPrompt.isNotBlank())
        assertTrue(c.question.contains("5 章"))
        // 只列前 3 章
        assertTrue(c.question.contains("第一章 甲"))
        assertTrue(c.question.contains("第三章 丙"))
        assertFalse(c.question.contains("第四章 丁"))
    }

    @Test
    fun `卡_没有标题时问题行也合法`() {
        val c = ExtractAsk.card(3, emptyList(), labels)
        assertTrue(ExtractAsk.isValid(c))
        assertTrue(c.question.contains("3 章"))
        assertFalse(c.question.contains("（"))
    }

    @Test
    fun `卡_空白标题不产生空段`() {
        val c = ExtractAsk.card(2, listOf("", "  ", "第三章"), labels)
        assertTrue(ExtractAsk.isValid(c))
        assertTrue(c.question.contains("第三章"))
    }

    @Test
    fun `自由输入_纯章号即精读那些章`() {
        assertEquals(ExtractChoice(ExtractMode.DEEP, setOf(3, 5, 8)), ExtractAsk.parse("3,5,8"))
        assertEquals(ExtractChoice(ExtractMode.DEEP, setOf(3, 5, 8)), ExtractAsk.parse(" 3，5、8 "))
        assertEquals(ExtractChoice(ExtractMode.DEEP, setOf(12)), ExtractAsk.parse("12"))
    }

    @Test
    fun `自由输入_关键词判模式`() {
        assertEquals(ExtractChoice(ExtractMode.DIGEST), ExtractAsk.parse("要摘要"))
        assertEquals(ExtractChoice(ExtractMode.INDEX), ExtractAsk.parse("只要索引"))
        assertEquals(ExtractChoice(ExtractMode.DEEP, emptySet()), ExtractAsk.parse("精读几章"))
    }

    @Test
    fun `自由输入_索引优先于摘要`() {
        // 「索引」比「摘要」窄（摘要模式也会建索引）⇒ 说「索引」就按索引办
        assertEquals(ExtractChoice(ExtractMode.INDEX), ExtractAsk.parse("索引，不要摘要"))
    }

    @Test
    fun `自由输入_对不上返回null`() {
        assertNull(ExtractAsk.parse(""))
        assertNull(ExtractAsk.parse("   "))
        assertNull(ExtractAsk.parse("今天天气不错"))
    }

    @Test
    fun `自由输入_精读带章号一起给`() {
        assertEquals(ExtractChoice(ExtractMode.DEEP, setOf(2, 4)), ExtractAsk.parse("精读第2章和第4章"))
    }
}
