// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十五批 · 负例7「报告里没有鉴权差距清单」的守门用例。
// 清单本体在 McpAuthGap（代码即账本）：少一项、空一项、结论跑出三选一，这里就红——
// 报告表格从同一份数据渲染，不可能出现「代码里有、报告里缺」。
package com.example.zhengdao.baopu.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpAuthGapTest {

    @Test
    fun `清单覆盖派工单点名六项`() {
        val topics = McpAuthGap.ITEMS.map { it.topic }
        McpAuthGap.REQUIRED_TOPICS.forEach { required ->
            assertTrue("差距清单少了「$required」", required in topics)
        }
    }

    @Test
    fun `每项都有现状_要求_结论_非空`() {
        assertTrue("清单不能空", McpAuthGap.ITEMS.isNotEmpty())
        McpAuthGap.ITEMS.forEach { item ->
            assertTrue("「${item.topic}」没写现状", item.current.isNotBlank())
            assertTrue("「${item.topic}」没写 OAuth 要求", item.required.isNotBlank())
            assertTrue(
                "「${item.topic}」的结论必须是三选一，实到 ${item.status}",
                item.status in McpAuthGapStatus.entries,
            )
        }
    }

    @Test
    fun `至少一项缺_一项已满足_一项需另立单_三类结论都出现过`() {
        val statuses = McpAuthGap.ITEMS.map { it.status }.toSet()
        assertTrue("全是「已满足」等于没有差距账", McpAuthGapStatus.缺 in statuses)
        assertTrue("没有「需另立单」就没有给 216 划边界", McpAuthGapStatus.需另立单 in statuses)
    }

    @Test
    fun `手机端回调项给了明确建议`() {
        val phone = McpAuthGap.ITEMS.first { it.topic == "手机端无浏览器回调" }
        assertTrue("要给出可执行的路线建议：${phone.note}", phone.note.contains("环回"))
    }
}
