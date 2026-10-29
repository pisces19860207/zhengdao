// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百七十八批 §1 判据①「技术字段禁止出现在卡面」＋「≤30 字现状」。
//
// 为什么要单独体检**资源文件里的真文案**：拟稿测试用的是假文案解析器（只测结构），
// 真文案写在 strings_baopu_royal.xml 里——那里混进一个 `L7` 或一个机器键，
// 卡面就会带着技术字段上真机。这条测试直接读 XML，把这件事钉死。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoyalCardStringsTest {

    private fun royalXml(lang: String): File {
        val rel = "app/src/main/res/values$lang/strings_baopu_royal.xml"
        return listOf(File(rel), File("../$rel")).firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel")
    }

    /** `<string name="x">文字</string>` ⇒ name→文字。 */
    private fun entries(file: File): Map<String, String> =
        Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(file.readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun checkFace(lang: String) {
        val m = entries(royalXml(lang))
        val face = listOf("royal_what_l7", "royal_why_l7", "royal_impact_l7",
            "royal_what_l8", "royal_why_l8", "royal_impact_l8",
            "royal_what_l9", "royal_why_l9", "royal_impact_l9")
        face.forEach { key ->
            val text = m[key]
            assertTrue("$lang 缺文案 $key", text != null && text.isNotBlank())
            assertTrue(
                "$lang：$key 超 30 等效字宽（判据：现状行 ≤30 字）——$text",
                RoyalCardGate.visualLength(text!!) <= RoyalCardGate.MAX_LINE_CHARS,
            )
            assertFalse(
                "$lang：$key 里有技术字段（只许进证据区）——$text",
                RoyalCardGate.looksTechnical(text),
            )
        }
    }

    @Test
    fun `判据_中文卡面文案不超三十字且无技术字段`() = checkFace("")

    @Test
    fun `判据_英文卡面文案同样干净`() = checkFace("-en")

    @Test
    fun `判据_三个选项与后果文案齐全`() {
        listOf("", "-en").forEach { lang ->
            val m = entries(royalXml(lang))
            listOf(
                "royal_opt_approve", "royal_opt_approve_consequence",
                "royal_opt_reject", "royal_opt_reject_consequence",
                "royal_opt_hold", "royal_opt_hold_consequence",
            ).forEach { key ->
                assertTrue("$lang 缺 $key", !m[key].isNullOrBlank())
            }
        }
    }

    @Test
    fun `判据_中英文案键一一对应_不许只加一半`() {
        val zh = entries(royalXml("")).keys
        val en = entries(royalXml("-en")).keys
        assertEquals("中英卡面文案必须一一对应", zh, en)
    }
}
