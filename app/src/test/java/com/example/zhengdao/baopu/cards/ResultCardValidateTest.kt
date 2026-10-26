// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二百一十二批 · 校验样式判据（派工单 §2.6：红框＋人话、置灰＋小计、可点跳转）。
 *
 * 三条负例在这里贴红：**只变红不给文案**（[CardValidate.messageOf] 恒非空）、
 * **提交没置灰**（有错必 enabled=false）、**点小计不跳**（有错必给 firstErrorKey）。
 * 还有一条更重要的：**校验逻辑不进壳**——本类不判必答/数字/上限，只翻译别的卡算好的错，
 * `BaopuResultCardGateTest` 会扫源码钉住这一点。
 */
class ResultCardValidateTest {

    private val lookup: (String) -> String = { k ->
        when (k) {
            "baopu_card_error_required" -> "这项必填"
            "baopu_card_error_number" -> "请输入数字"
            "baopu_card_error_too_many" -> "最多选 %1\$d 项"
            "baopu_card_error_generic" -> "这一项要检查一下"
            "baopu_card_error_summary" -> "还差 %1\$d 项"
            else -> k
        }
    }

    @Test
    fun `没有错就是能提交`() {
        val plan = CardValidate.planOf(emptyList())
        assertTrue(plan.enabled)
        assertEquals(0, plan.needed)
        assertEquals(null, plan.summaryKey)
        assertEquals(null, plan.firstErrorKey)
    }

    @Test
    fun `有错必置灰＋必有小计＋必可跳`() {
        val plan = CardValidate.planOf(
            listOf(
                CardFieldError("name", CardErrorKind.REQUIRED),
                CardFieldError("phone", CardErrorKind.NOT_NUMBER),
            ),
        )
        assertFalse("有错时提交必须置灰", plan.enabled)
        assertEquals(2, plan.needed)
        assertEquals(CardValidate.SUMMARY_KEY, plan.summaryKey)
        assertNotNull("点小计必须有落点", plan.firstErrorKey)
    }

    @Test
    fun `同一项两种错只算一项`() {
        val plan = CardValidate.planOf(
            listOf(
                CardFieldError("phone", CardErrorKind.REQUIRED),
                CardFieldError("phone", CardErrorKind.NOT_NUMBER),
            ),
        )
        assertEquals("一项填错不该在「还差 N 项」里数两遍", 1, plan.needed)
    }

    @Test
    fun `每一类错都有人话_永远不为空`() {
        CardErrorKind.entries.forEach { kind ->
            val msg = CardValidate.messageOf(CardFieldError("k", kind, detail = null), lookup)
            assertTrue("$kind 没有文案（红框不许哑着）", msg.isNotBlank())
        }
        // 上限那类要把数说出来，不然「最多选几项」等于没说
        assertEquals("最多选 3 项", CardValidate.messageOf(CardFieldError("k", CardErrorKind.TOO_MANY, "3"), lookup))
    }

    @Test
    fun `卡自己给的句子用原话_空话兜底`() {
        assertEquals(
            "这一格和上一格重复了",
            CardValidate.messageOf(CardFieldError("k", CardErrorKind.CUSTOM, "这一格和上一格重复了"), lookup),
        )
        assertEquals(
            "空串必须落兜底句，不许画一个哑红框",
            "这一项要检查一下",
            CardValidate.messageOf(CardFieldError("k", CardErrorKind.CUSTOM, "   "), lookup),
        )
    }

    @Test
    fun `跳转按字段顺序取第一处_不按报警先后`() {
        val errors = listOf(
            CardFieldError("phone", CardErrorKind.REQUIRED), // 先报的
            CardFieldError("name", CardErrorKind.REQUIRED), // 但在更上面
        )
        val order = listOf("name", "phone", "note")
        assertEquals("name", CardValidate.jumpTarget(errors, order))
        // 没有字段顺序时退回第一个（不让跳转变成 null）
        assertEquals("phone", CardValidate.jumpTarget(errors, emptyList()))
    }

    @Test
    fun `空错表没有跳转`() {
        assertEquals(null, CardValidate.jumpTarget(emptyList(), listOf("a")))
    }
}
