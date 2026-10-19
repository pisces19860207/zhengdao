// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 判据：F1／F2／F3／F17／F18（docs/格式层规格-判据表F1-F11-2026-10-19.md）
// 夹具：CardFixtures（全部合成，零真实样本）
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.card.CardFormat
import com.example.zhengdao.baopu.card.CardText
import com.example.zhengdao.baopu.card.Confidence
import com.example.zhengdao.baopu.card.FormatKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFormatTest {

    private fun judge(json: String) = CardFormat.recognize(json.toByteArray(Charsets.UTF_8))

    // ── F1 ───────────────────────────────────────────────────────────────

    @Test
    fun `format_recognizes_all_seven_shapes`() {
        val cases = listOf(
            Triple("J1", CardFixtures.J1, FormatKind.WEB_BOOK to "PLAIN"),
            Triple("J2", CardFixtures.J2, FormatKind.WEB_BOOK to "TEMPLATE"),
            Triple("J3", CardFixtures.J3, FormatKind.TAVO_BOOK to null),
            Triple("J4", CardFixtures.J4, FormatKind.ST_WORLD to null),
            Triple("J5", CardFixtures.J5, FormatKind.TAVO_CHAR to null),
            Triple("J6", CardFixtures.J6, FormatKind.TAVO_REGEX to null),
            Triple("J7", CardFixtures.J7, FormatKind.TAVO_PRESET to null),
        )
        for ((name, json, expect) in cases) {
            val v = judge(json)
            assertEquals("$name 判错", expect.first, v.format)
            assertEquals("$name 子类错", expect.second, v.subtype)
            assertEquals("$name 置信度错", Confidence.HIGH, v.confidence)
            assertTrue("$name evidence 不能为空", v.evidence.any { it.isNotBlank() })
        }
        assertEquals(1, judge(CardFixtures.J1).entryCount)
        assertEquals(1, judge(CardFixtures.J2).entryCount)
        assertEquals(1, judge(CardFixtures.J3).entryCount)
        assertEquals(1, judge(CardFixtures.J4).entryCount)
        assertNull("J5 角色卡没有 entries", judge(CardFixtures.J5).entryCount)
        assertEquals(1, judge(CardFixtures.J6).entryCount)
        assertEquals(1, judge(CardFixtures.J7).entryCount)
    }

    @Test
    fun `format_evidence_names_the_winning_signature`() {
        fun ev(json: String) = judge(json).evidence.joinToString("；")
        assertTrue(ev(CardFixtures.J1).contains("world_book"))
        assertTrue(ev(CardFixtures.J2).contains("world_book"))
        assertTrue(ev(CardFixtures.J3).contains("entries") && ev(CardFixtures.J3).contains("数组"))
        assertTrue(ev(CardFixtures.J4).contains("entries") && ev(CardFixtures.J4).contains("字典"))
        assertTrue(ev(CardFixtures.J5).contains("data"))
        assertTrue(ev(CardFixtures.J6).contains("findRegex"))
        assertTrue(ev(CardFixtures.J7).contains("basicPrompts") || ev(CardFixtures.J7).contains("active"))
    }

    // ── F2 ───────────────────────────────────────────────────────────────

    @Test
    fun `web_omitted_defaults_are_not_errors`() {
        val v = judge(CardFixtures.J1)
        assertEquals(FormatKind.WEB_BOOK, v.format)
        assertEquals(listOf("value_configs", "value_type"), v.defaultsApplied.sorted())
        // 认脸阶段不许因为「省略」报缺字段：判不出字段级错误，这里只能验「没把省略当错」
        assertTrue(v.conflicts.none { it.contains("value_type") || it.contains("value_configs") })
    }

    // ── F3 ───────────────────────────────────────────────────────────────

    @Test
    fun `unknown_returns_keys_seen_not_a_guess`() {
        val v = judge(CardFixtures.N2)
        assertEquals(FormatKind.UNKNOWN, v.format)
        assertEquals(listOf("foo", "bar", "baz"), v.keysSeen)
        assertEquals("认不出这个文件是什么格式。看过的顶层键：foo, bar, baz。", v.message)
        assertNull(v.confidence)
    }

    @Test
    fun `unknown_keys_seen_is_capped_at_twenty`() {
        val json = "{" + (1..25).joinToString(",") { "\"k$it\":$it" } + "}"
        val v = judge(json)
        assertEquals(20, v.keysSeen.size)
        assertEquals("k1", v.keysSeen.first())
        assertEquals("k20", v.keysSeen.last())
    }

    // ── F17 ──────────────────────────────────────────────────────────────

    @Test
    fun `ccv3_lorebook_not_misjudged_as_tavo`() {
        val wrapped = """
            {"spec":"lorebook_v3","data":{"entries":[{"keys":["甲"],"content":"示例","enabled":true,"insertion_order":100,"use_regex":false}]}}
        """.trimIndent()
        val bare = """
            {"entries":[{"keys":["甲"],"content":"示例","enabled":true,"insertion_order":100,"use_regex":false}]}
        """.trimIndent()
        for (json in listOf(wrapped, bare)) {
            val v = judge(json)
            assertEquals("CCv3 世界书被误判：$json", FormatKind.CCV3_LOREBOOK, v.format)
            assertEquals(Confidence.HIGH, v.confidence)
            assertTrue(v.format != FormatKind.TAVO_BOOK)
            assertEquals(1, v.entryCount)
        }
    }

    // ── F18 ──────────────────────────────────────────────────────────────

    @Test
    fun `charx_and_unknown_spec_are_unknown`() {
        val card = judge("""{"spec":"chara_card_v3","data":{"name":"示例"}}""")
        assertEquals(FormatKind.UNKNOWN, card.format)
        assertEquals("CCV3_CARD_NOT_IN_SCOPE", card.reason)
        assertEquals(CardText.CCV3_CARD_NOT_IN_SCOPE, card.message)

        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00)
        val charx = CardFormat.recognize(zip)
        assertEquals(FormatKind.UNKNOWN, charx.format)
        assertTrue("zip 要说明不在本批范围：${charx.message}", charx.message!!.contains("不在本批范围"))

        val other = judge("""{"spec":"something_else","foo":1}""")
        assertEquals(FormatKind.UNKNOWN, other.format)
        assertEquals(listOf("spec", "foo"), other.keysSeen)
    }

    // ── PNG 分支（0 步）───────────────────────────────────────────────────

    @Test
    fun `plain_png_is_unknown_not_a_card`() {
        val v = CardFormat.recognize(CardFixtures.basePng())
        assertEquals(FormatKind.UNKNOWN, v.format)
        assertEquals("PNG_NO_CARD_BLOCK", v.reason)
        assertEquals(CardText.PNG_NO_CARD_BLOCK, v.message)
        assertNull(v.embeddedJson)
    }

    @Test
    fun `two_text_blocks_take_ccv3_and_warn`() {
        val v = CardFormat.recognize(CardFixtures.pngTwoVersions())
        assertEquals(FormatKind.ST_CARD_PNG, v.format)
        assertEquals("V3", v.subtype)
        assertTrue(v.embeddedJson!!.contains("chara_card_v3"))
        assertTrue(v.conflicts.any { it.contains("已取 ccv3") })
        assertTrue(v.pngTextKeys.containsAll(listOf("chara", "ccv3")))
    }
}
