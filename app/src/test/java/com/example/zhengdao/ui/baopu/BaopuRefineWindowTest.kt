// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十二批（§B 80 结构切片）：BaopuRefineWindow 的 JVM 单测 ＋ 源码门禁。
//
// 这段判据原先只能靠真机观察（「这次学到」重复刷／该提炼没提炼），现在可以逐条钉住。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryKind
import com.example.zhengdao.baopu.MemoryScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuRefineWindowTest {

    private fun msg(id: Long, role: String, vararg parts: BaopuUiPart) =
        BaopuUiMessage(id = id, role = role, parts = parts.toList(), createdAt = id * 1_000)

    private fun text(t: String) = BaopuUiPart(kind = "text", text = t)
    private fun tool(name: String) = BaopuUiPart(kind = "tool", text = "结果", tool = name)

    private fun entry(body: String, id: String = "m-$body") = MemoryEntry(
        id = id,
        scope = MemoryScope.PROJECT,
        kind = MemoryKind.FACT,
        title = body.take(8),
        body = body,
    )

    private fun controller(): String {
        val f = listOf(
            File("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"),
            File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"),
        ).firstOrNull { it.isFile } ?: error("找不到 BaopuChatController.kt")
        return f.readText()
    }

    // ── 游标切片 ──────────────────────────────────────────────────────────

    @Test
    fun `R1_首次提炼取全部用户原话_工具只算本轮且去重保序`() {
        val messages = listOf(
            msg(1, "user", text("第一句")),
            msg(2, "assistant", tool("read_file"), tool("read_file"), tool("")),
            msg(3, "user", text("第二句")),
            msg(4, "assistant", tool("write_file")),
        )
        val s = BaopuRefineWindow.slice(messages, sessionId = 9L, markSession = -1L, markCount = -1)
        requireNotNull(s)
        assertEquals(listOf("第一句", "第二句"), s.freshUserTexts)
        assertEquals("工具去重保序、空名丢掉", listOf("read_file", "write_file"), s.toolsUsed)
        assertEquals("游标写回的是累计条数", 2, s.markCount)
    }

    @Test
    fun `R2_同会话第二次只取新增_工具只算新那一轮`() {
        val messages = listOf(
            msg(1, "user", text("第一句")),
            msg(2, "assistant", tool("read_file")),
            msg(3, "user", text("第二句")),
            msg(4, "assistant", tool("write_file")),
        )
        val s = BaopuRefineWindow.slice(messages, sessionId = 9L, markSession = 9L, markCount = 1)
        requireNotNull(s)
        assertEquals(listOf("第二句"), s.freshUserTexts)
        assertEquals("上一轮的工具不许再算一遍", listOf("write_file"), s.toolsUsed)
        assertEquals(2, s.markCount)
    }

    @Test
    fun `R3_换会话游标失效_从头算`() {
        val messages = listOf(
            msg(1, "user", text("上一会话的话")),
            msg(2, "user", text("这一会话的话")),
        )
        val s = BaopuRefineWindow.slice(messages, sessionId = 2L, markSession = 1L, markCount = 5)
        requireNotNull(s)
        assertEquals(listOf("上一会话的话", "这一会话的话"), s.freshUserTexts)
    }

    @Test
    fun `R4_没有新用户输入返回null_游标不前进`() {
        val messages = listOf(msg(1, "user", text("只有这一句")), msg(2, "assistant", tool("read_file")))
        assertNull(BaopuRefineWindow.slice(messages, sessionId = 1L, markSession = 1L, markCount = 1))
        assertNull("空消息列表也不提炼", BaopuRefineWindow.slice(emptyList(), 1L, -1L, -1))
    }

    @Test
    fun `R5_只有工具卡没有用户文本_不提炼`() {
        val messages = listOf(msg(1, "assistant", tool("read_file")))
        assertNull(BaopuRefineWindow.slice(messages, sessionId = 1L, markSession = -1L, markCount = -1))
    }

    // ── 待过目候选合并 ────────────────────────────────────────────────────

    @Test
    fun `M1_同正文去重_老候选保序在前`() {
        val merge = BaopuRefineWindow.mergePending(
            old = listOf(entry("甲", "old-1"), entry("乙", "old-2")),
            incoming = listOf(entry("乙", "new-1"), entry("丙", "new-2")),
            max = 6,
        )
        assertEquals(listOf("甲", "乙", "丙"), merge.kept.map { it.body })
        assertEquals("老候选那条 id 必须留着（先到先得）", "old-2", merge.kept[1].id)
        assertEquals(0, merge.dropped)
    }

    @Test
    fun `M2_超上限截断并给出被截条数`() {
        val merge = BaopuRefineWindow.mergePending(
            old = (1..4).map { entry("旧$it") },
            incoming = (1..3).map { entry("新$it") },
            max = 6,
        )
        assertEquals(6, merge.kept.size)
        assertEquals(1, merge.dropped)
        assertEquals("旧1", merge.kept.first().body)
    }

    @Test
    fun `M3_上限为零也不抛_全部计为截断`() {
        val merge = BaopuRefineWindow.mergePending(listOf(entry("甲")), listOf(entry("乙")), max = 0)
        assertTrue(merge.kept.isEmpty())
        assertEquals(2, merge.dropped)
    }

    // ── 源码门禁 ──────────────────────────────────────────────────────────

    @Test
    fun `G1_控制器只剩调用_去重与切片不留第二份`() {
        val c = controller()
        assertTrue(c.contains("BaopuRefineWindow.slice("))
        assertTrue(c.contains("BaopuRefineWindow.mergePending("))
        assertTrue("body 去重只许在 helper 里", !c.contains("distinctBy { it.body }"))
        assertTrue("游标切片判据不许在控制器里再抄一份", !c.contains("userTexts.drop(already)"))
    }
}
