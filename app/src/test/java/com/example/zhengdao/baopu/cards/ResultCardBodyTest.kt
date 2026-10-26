// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二百一十二批 · 「卡里有 ⇒ 正文闭嘴」（派工单 §1.5 六条 ＋ §1.6 分工）。
 *
 * 每一条负例都在这里贴红：复述卡里字段／铺垫词／推断词／两行／空正文。
 * 正例只有一条——本批自己产出的那一行锚点必须过体检，否则规则会变成「谁都过不了」。
 */
class ResultCardBodyTest {

    private val lookup: (String) -> String = { k ->
        when (k) {
            "baopu_card_body_line" -> "查到 %1\$d 条，见卡"
            "baopu_card_strip_line" -> "%1\$s · %2\$d 条"
            else -> k
        }
    }

    private fun card(): ResultCardSpec = ResultCardSpec(
        templateId = "logistics.waybill",
        title = "物流查询结果",
        groups = listOf(
            CardGroup(
                "doc",
                "单据",
                listOf(
                    CardRow("waybill", "单号", "1234567", filled = true),
                    CardRow("plate", "车牌号", "冀A12345", filled = true),
                    CardRow("goods", "货物", "未填", filled = false),
                ),
            ),
        ),
        actions = emptyList(),
        itemCount = 1,
    )

    @Test
    fun `自己产出的那行锚点必须过体检`() {
        val body = ResultCardBody.anchorLine(card(), lookup)
        assertEquals("查到 1 条，见卡", body)
        assertEquals(emptyList<BodyViolation>(), ResultCardBody.violations(body, card()))
    }

    @Test
    fun `小条那一行也不许复述字段值`() {
        val strip = ResultCardBody.stripLine(card(), lookup)
        assertEquals("物流查询结果 · 1 条", strip)
        assertTrue(ResultCardBody.violations(strip, card()).isEmpty())
    }

    @Test
    fun `负例 正文复述卡里字段`() {
        val v = ResultCardBody.violations("查到 1 票：单号 1234567，车牌 冀A12345", card())
        assertTrue("复述了字段值必须红：$v", v.contains(BodyViolation.REPEATS_CARD))
    }

    @Test
    fun `负例 铺垫词`() {
        assertTrue(ResultCardBody.violations("我帮你查了一下，结果在卡里", card()).contains(BodyViolation.FILLER))
        assertTrue(ResultCardBody.violations("希望对你有所帮助", card()).contains(BodyViolation.FILLER))
        assertTrue(ResultCardBody.violations("根据你提供的信息", card()).contains(BodyViolation.FILLER))
    }

    @Test
    fun `负例 推断词`() {
        listOf("可能已经装车", "大概发走了", "应该明天到", "估计不够").forEach { body ->
            assertTrue("「$body」里的推断词必须红", ResultCardBody.violations(body, card()).contains(BodyViolation.PREDICTION))
        }
    }

    @Test
    fun `负例 两行或超长`() {
        assertTrue(ResultCardBody.violations("查到 1 条\n见卡", card()).contains(BodyViolation.TOO_LONG))
        val long = "查到一条结果，".repeat(6) + "见卡"
        assertTrue(long.length > ResultCardBody.MAX_BODY_CHARS)
        assertTrue(ResultCardBody.violations(long, card()).contains(BodyViolation.TOO_LONG))
    }

    @Test
    fun `负例 空正文`() {
        assertEquals(listOf(BodyViolation.EMPTY), ResultCardBody.violations("   ", card()))
    }

    @Test
    fun `未填不算复述_单字不比`() {
        // 卡里「未填」是占位不是数据；单字值（如「1」）也一律不比，免得条条假红
        val c = ResultCardSpec(
            templateId = "t",
            title = "t",
            groups = listOf(CardGroup("g", null, listOf(CardRow("k", "件数", "1")))),
            actions = emptyList(),
        )
        assertFalse(ResultCardBody.repeatsCard("查到 1 条，见卡", c))
        val c2 = ResultCardSpec(
            templateId = "t",
            title = "t",
            groups = listOf(CardGroup("g", null, listOf(CardRow("k", "货物", "未填", filled = false)))),
            actions = emptyList(),
        )
        assertFalse(ResultCardBody.repeatsCard("有一项未填，见卡", c2))
    }

    @Test
    fun `落日志那行不含任何字段值`() {
        var s = ResultCardState()
        s = ResultCards.push(s, card())
        val line = ResultCardBody.logLine(s)
        assertTrue("日志要能看出是哪份模板、几张卡", line.contains("logistics.waybill") && line.contains("cards=1"))
        listOf("1234567", "冀A12345").forEach {
            assertFalse("字段值「$it」不许进日志（§2：姓名与手机号同理）", line.contains(it))
        }
    }
}
