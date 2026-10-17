// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提问卡纯函数单测（刀 2 C 段；判据 §5.7）。
 *
 * 钉的形状（用户逐字定的口径）：推荐项必在第一、选项 2–4、**空白行永远存在**、
 * 选项为空要报错。负例演练第二条（把空白行去掉）的落点就是
 * 「空白行必须永远在」那条——它必须能红。
 */
class AskUserCardTest {

    private fun card(
        question: String = "怎么处理投递里的件？",
        options: List<AskUserOption> = listOf(
            AskUserOption(id = "move", label = "搬进 App 私有（推荐）", recommended = true),
            AskUserOption(id = "copy", label = "复制一份，共享区留底"),
        ),
        freeTextPrompt: String = AskUserCardRules.FREE_TEXT_PROMPT,
    ) = AskUserCard(question = question, options = options, freeTextPrompt = freeTextPrompt)

    private fun problems(c: AskUserCard): List<String> =
        (AskUserCardRules.validate(c) as? AskUserCheck.Invalid)?.problems ?: emptyList()

    @Test
    fun `合法卡片_推荐第一加空白行`() {
        assertTrue(AskUserCardRules.isValid(card()))
    }

    @Test
    fun `四个选项也合法_顶到上限`() {
        val four = card(
            options = listOf(
                AskUserOption(id = "a", label = "甲", recommended = true),
                AskUserOption(id = "b", label = "乙"),
                AskUserOption(id = "c", label = "丙"),
                AskUserOption(id = "d", label = "丁"),
            ),
        )
        assertTrue(AskUserCardRules.isValid(four))
    }

    @Test
    fun `推荐项必须排第一`() {
        val bad = card(
            options = listOf(
                AskUserOption(id = "copy", label = "复制一份"),
                AskUserOption(id = "move", label = "搬进私有（推荐）", recommended = true),
            ),
        )
        val p = problems(bad)
        assertTrue(p.any { it.contains("推荐项必须排第一") })
    }

    @Test
    fun `必须恰好一个推荐项`() {
        val two = card(
            options = listOf(
                AskUserOption(id = "a", label = "甲", recommended = true),
                AskUserOption(id = "b", label = "乙", recommended = true),
            ),
        )
        assertTrue(problems(two).any { it.contains("必须恰好一个推荐项") })

        val none = card(
            options = listOf(
                AskUserOption(id = "a", label = "甲"),
                AskUserOption(id = "b", label = "乙"),
            ),
        )
        assertTrue(problems(none).any { it.contains("必须恰好一个推荐项") })
    }

    @Test
    fun `选项数必须在2到4之间`() {
        val one = card(options = listOf(AskUserOption(id = "a", label = "甲", recommended = true)))
        assertTrue(problems(one).any { it.contains("选项要 2–4 个") })

        val five = card(
            options = listOf(
                AskUserOption(id = "a", label = "甲", recommended = true),
                AskUserOption(id = "b", label = "乙"),
                AskUserOption(id = "c", label = "丙"),
                AskUserOption(id = "d", label = "丁"),
                AskUserOption(id = "e", label = "戊"),
            ),
        )
        assertTrue(problems(five).any { it.contains("选项要 2–4 个") })
    }

    @Test
    fun `空白行必须永远在_去掉就报错`() {
        // 负例演练第二条的钉子：把 freeTextPrompt 抹空 ⇒ 必须报错
        val noBlank = card(freeTextPrompt = "")
        val p = problems(noBlank)
        assertTrue("空白行被去掉时必须报错", p.any { it.contains("必须留一行空白输入") })
        assertFalse(AskUserCardRules.isValid(noBlank))
    }

    @Test
    fun `问题不能为空`() {
        assertTrue(problems(card(question = "   ")).any { it.contains("问题不能为空") })
    }

    @Test
    fun `选项id重复或文案为空都要报错`() {
        val dupId = card(
            options = listOf(
                AskUserOption(id = "x", label = "甲", recommended = true),
                AskUserOption(id = "x", label = "乙"),
            ),
        )
        assertTrue(problems(dupId).any { it.contains("id 重复") })

        val blankLabel = card(
            options = listOf(
                AskUserOption(id = "a", label = "  ", recommended = true),
                AskUserOption(id = "b", label = "乙"),
            ),
        )
        assertTrue(problems(blankLabel).any { it.contains("文案为空") })

        val blankId = card(
            options = listOf(
                AskUserOption(id = "", label = "甲", recommended = true),
                AskUserOption(id = "b", label = "乙"),
            ),
        )
        assertTrue(problems(blankId).any { it.contains("id 为空") })
    }

    @Test
    fun `一次列全全部问题_不让调用方猜第二条`() {
        val bad = card(
            question = "",
            options = listOf(AskUserOption(id = "a", label = "甲")),
            freeTextPrompt = "",
        )
        val check = AskUserCardRules.validate(bad)
        assertTrue(check is AskUserCheck.Invalid)
        val p = (check as AskUserCheck.Invalid).problems
        assertTrue("问题数应一次列全：$p", p.size >= 4)
    }

    @Test
    fun `默认空白行提示语非空`() {
        assertTrue(AskUserCardRules.FREE_TEXT_PROMPT.isNotBlank())
        assertEquals(AskUserCardRules.MIN_OPTIONS, 2)
        assertEquals(AskUserCardRules.MAX_OPTIONS, 4)
    }
}
