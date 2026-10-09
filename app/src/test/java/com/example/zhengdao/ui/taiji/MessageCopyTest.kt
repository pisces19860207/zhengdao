// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：本项目自己的数据模型（OcMessage / OcPart）。
package com.example.zhengdao.ui.taiji

import com.example.zhengdao.oc.OcMessage
import com.example.zhengdao.oc.OcPart
import com.example.zhengdao.oc.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「长按消息 → 复制」的文本口径（2026-10-09 用户需求）。
 *
 * 为什么值得一组单测：复制是**用户马上会去别处粘贴**的操作，剪贴板里到底是什么，
 * 界面上看不出来（面板只显示两三行预览）。一旦规则跑偏——比如工具卡复制出空串、
 * 或者把整段思考过程混进"复制这条消息"——用户要到粘贴的那一刻才发现，而且
 * 那时已经无从追溯。这里是纯函数，必须能在没有设备的情况下把口径钉住。
 */
class MessageCopyTest {

    private fun user(vararg parts: OcPart) = OcMessage(
        id = "m1",
        role = OcMessage.Role.USER,
        parts = parts.toList(),
    )

    private fun assistant(vararg parts: OcPart) = OcMessage(
        id = "m2",
        role = OcMessage.Role.ASSISTANT,
        parts = parts.toList(),
    )

    @Test
    fun `正文优先_有正文时不夹带思考过程与工具输出`() {
        val msg = assistant(
            OcPart.Reasoning("r1", "我先看看目录"),
            OcPart.Tool("t1", toolName = "shell", state = ToolState.Success, input = "ls -a", output = "a b c"),
            OcPart.Text("p1", "目录里有 a、b、c 三项。"),
        )
        // 只复制正文——思考过程与工具输出是辅助信息，混进去会把用户要的那句话淹掉
        assertEquals("目录里有 a、b、c 三项。", MessageCopy.textOf(msg))
        assertFalse(MessageCopy.textOf(msg).contains("ls -a"))
    }

    @Test
    fun `多段正文之间用空行连接_两端空白被裁掉`() {
        val msg = assistant(
            OcPart.Text("p1", "  第一段  "),
            OcPart.Text("p2", "\n第二段\n"),
        )
        assertEquals("第一段\n\n第二段", MessageCopy.textOf(msg))
    }

    @Test
    fun `只有工具调用时也能复制出工具名与状态`() {
        // 真机实测过这种消息：Agent 只调工具、一句话不说 ⇒ 整条消息没有任何 Text part。
        // 若只取 Text，长按复制会得到空串——"复制成功了但剪贴板是空的"，正是要避免的形态。
        val msg = assistant(
            OcPart.Tool(
                "t1",
                toolName = "shell",
                state = ToolState.Success,
                input = "ls -a",
                output = "a b c",
            ),
        )
        val text = MessageCopy.textOf(msg)
        assertTrue(text.contains("shell"))
        assertTrue(text.contains("已完成"))
        assertTrue(text.contains("ls -a"))
        assertTrue(text.contains("a b c"))
    }

    @Test
    fun `工具失败状态文案是失败`() {
        val msg = assistant(OcPart.Tool("t1", toolName = "edit", state = ToolState.Error, output = "boom"))
        assertTrue(MessageCopy.textOf(msg).contains("失败"))
    }

    @Test
    fun `只有思考过程时复制思考内容并标注`() {
        val msg = assistant(OcPart.Reasoning("r1", "先读文件再改"))
        val text = MessageCopy.textOf(msg)
        assertTrue(text.contains("思考过程"))
        assertTrue(text.contains("先读文件再改"))
    }

    @Test
    fun `附件与未知内容都有可读兜底`() {
        val file = assistant(OcPart.File("f1", "notes.md", "text/markdown"))
        assertTrue(MessageCopy.textOf(file).contains("notes.md"))

        val unknown = assistant(OcPart.Unknown("u1", type = "mystery", raw = """{"x":1}"""))
        val t = MessageCopy.textOf(unknown)
        assertTrue(t.contains("mystery"))
        assertTrue(t.contains("""{"x":1}"""))
    }

    @Test
    fun `完全空的消息复制出空串_面板据此禁用复制入口`() {
        assertEquals("", MessageCopy.textOf(assistant()))
        assertEquals("", MessageCopy.textOf(user(OcPart.Text("p1", "   "))))
    }

    @Test
    fun `超长工具输出被截断且明确标注`() {
        val long = "x".repeat(MessageCopy.TOOL_FIELD_LIMIT + 500)
        val msg = assistant(OcPart.Tool("t1", toolName = "shell", state = ToolState.Success, output = long))
        val text = MessageCopy.textOf(msg)
        assertTrue(text.contains("已截断"))
        assertTrue(text.contains("原文 ${long.length} 字"))
        // 截断后不该还是原文那么长（留一点余量给标注行）
        assertTrue(text.length < long.length)
    }

    @Test
    fun `复制整段对话_每条消息带角色抬头并保住顺序`() {
        val conversation = listOf(
            user(OcPart.Text("p1", "帮我看看目录")),
            assistant(OcPart.Text("p2", "目录里有 a、b、c。")),
        )
        val text = MessageCopy.textOfConversation(conversation)
        assertTrue(text.startsWith("我：\n帮我看看目录"))
        val iUser = text.indexOf("帮我看看目录")
        val iAssistant = text.indexOf("目录里有 a、b、c。")
        assertTrue(iUser in 0 until iAssistant) // 顺序＝消息顺序（老在上）
        assertTrue(text.contains("助手："))
    }

    @Test
    fun `没有可复制内容的消息不占整段对话的位置`() {
        val conversation = listOf(
            user(OcPart.Text("p1", "   ")), // 空的系统事件类消息
            assistant(OcPart.Text("p2", "有内容")),
        )
        val text = MessageCopy.textOfConversation(conversation)
        assertFalse(text.contains("我："))
        assertTrue(text.contains("助手："))
    }

    @Test
    fun `角色抬头与状态文案是中文`() {
        assertEquals("我", MessageCopy.roleLabel(user()))
        assertEquals("助手", MessageCopy.roleLabel(assistant()))
        assertEquals("系统", MessageCopy.roleLabel(OcMessage(id = "s", role = OcMessage.Role.SYSTEM)))
        assertEquals("执行中", MessageCopy.stateText(ToolState.Running))
        assertEquals("已完成", MessageCopy.stateText(ToolState.Success))
        assertEquals("失败", MessageCopy.stateText(ToolState.Error))
        assertEquals("未知", MessageCopy.stateText(ToolState.Unknown))
    }
}
