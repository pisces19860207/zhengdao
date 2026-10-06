// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.taiji

import com.example.zhengdao.oc.OcPart
import com.example.zhengdao.oc.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.1 第二阶段「消息视觉层级」核心纯逻辑单测：验证 [groupParts] 的归并边界。
 *
 * 这是第二阶段唯一可脱离真机自动验证的部分——渲染外观需真机看，但"哪些 part 归为一组、
 * 哪段 reasoning 该合并"是可断言的逻辑，必须在 JVM 侧锁死。
 */
class TaijiRenderGroupingTest {

    private fun text(id: String, s: String) = OcPart.Text(id = id, text = s)
    private fun reason(id: String, s: String) = OcPart.Reasoning(id = id, text = s)
    private fun tool(id: String, name: String) =
        OcPart.Tool(id = id, toolName = name, state = ToolState.Success)

    @Test
    fun `相邻 reasoning 合并为一组`() {
        // R R T → [Group(2), Text]
        val items = groupParts(listOf(reason("r1", "第一步"), reason("r2", "第二步"), text("t1", "答案")))
        assertEquals(2, items.size)
        assertTrue(items[0] is RenderItem.ReasoningGroup)
        assertEquals(2, (items[0] as RenderItem.ReasoningGroup).parts.size)
        assertTrue(items[1] is RenderItem.TextPart)
    }

    @Test
    fun `被文本分隔的 reasoning 不跨段合并`() {
        // R T R → [Group(1), Text, Group(1)]：两段思考属于不同阶段，不得合并
        val items = groupParts(listOf(reason("r1", "甲"), text("t1", "中间"), reason("r2", "乙")))
        assertEquals(3, items.size)
        assertEquals(1, (items[0] as RenderItem.ReasoningGroup).parts.size)
        assertTrue(items[1] is RenderItem.TextPart)
        assertEquals(1, (items[2] as RenderItem.ReasoningGroup).parts.size)
    }

    @Test
    fun `工具 part 单独成项且打断 reasoning 合并`() {
        // R Tool R → [Group(1), Tool, Group(1)]
        val items = groupParts(listOf(reason("r1", "想"), tool("k1", "shell"), reason("r2", "再想")))
        assertEquals(3, items.size)
        assertTrue(items[0] is RenderItem.ReasoningGroup)
        assertTrue(items[1] is RenderItem.ToolPart)
        assertTrue(items[2] is RenderItem.ReasoningGroup)
        assertEquals("shell", (items[1] as RenderItem.ToolPart).part.toolName)
    }

    @Test
    fun `尾部 reasoning 正确 flush`() {
        // T R R → [Text, Group(2)]：循环结束后的残留缓冲必须被 flush，不能丢
        val items = groupParts(listOf(text("t1", "答案"), reason("r1", "甲"), reason("r2", "乙")))
        assertEquals(2, items.size)
        assertTrue(items[0] is RenderItem.TextPart)
        assertEquals(2, (items[1] as RenderItem.ReasoningGroup).parts.size)
    }

    @Test
    fun `纯文本消息不产生空组`() {
        val items = groupParts(listOf(text("t1", "只有正文")))
        assertEquals(1, items.size)
        assertTrue(items[0] is RenderItem.TextPart)
    }

    @Test
    fun `空列表返回空`() {
        assertEquals(0, groupParts(emptyList()).size)
    }

    @Test
    fun `文件与未知 part 归入 Other 且打断合并`() {
        // R File R Unknown → [Group(1), Other, Group(1), Other]
        val items = groupParts(
            listOf(
                reason("r1", "甲"),
                OcPart.File(id = "f1", filename = "a.txt"),
                reason("r2", "乙"),
                OcPart.Unknown(id = "u1", type = "foo", raw = "{}"),
            )
        )
        assertEquals(4, items.size)
        assertTrue(items[0] is RenderItem.ReasoningGroup)
        assertTrue(items[1] is RenderItem.Other)
        assertTrue(items[2] is RenderItem.ReasoningGroup)
        assertTrue(items[3] is RenderItem.Other)
    }
}
