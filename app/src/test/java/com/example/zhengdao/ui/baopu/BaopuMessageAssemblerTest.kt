// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十二批（§B 80 结构切片）：BaopuMessageAssembler 的 JVM 单测 ＋ 源码门禁。
//
// 覆盖的是从 readMessages 搬出来的三条判据：消息聚合、plan 不进 parts、tool JSON 解析。
// 门禁盯「控制器只剩调用」——两份实现＝下一次改一处忘一处。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuMessageAssemblerTest {

    private fun row(
        id: Long,
        role: String = "assistant",
        at: Long = 1_000L,
        kind: String = "text",
        payload: String = "",
    ) = BaopuPartRow(messageId = id, role = role, createdAt = at, kind = kind, payload = payload)

    private fun planPayload(vararg items: Pair<String, String>): String =
        items.joinToString(prefix = "{\"items\":[", postfix = "]}") { (t, s) ->
            "{\"text\":\"$t\",\"status\":\"$s\"}"
        }

    private fun controller(): String {
        val f = listOf(
            File("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"),
            File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"),
        ).firstOrNull { it.isFile } ?: error("找不到 BaopuChatController.kt")
        return f.readText()
    }

    @Test
    fun `A1_同一条消息的多个part聚成一条且保序`() {
        val out = BaopuMessageAssembler.assemble(
            listOf(
                row(1, "user", 10L, "text", "你好"),
                row(1, "user", 10L, "text", "第二段"),
                row(2, "assistant", 20L, "text", "在"),
            ),
        )
        assertEquals(2, out.size)
        assertEquals(listOf("你好", "第二段"), out[0].parts.map { it.text })
        assertEquals("user", out[0].role)
        assertEquals(10L, out[0].createdAt)
        assertEquals(listOf("在"), out[1].parts.map { it.text })
    }

    @Test
    fun `A2_plan只进msg_plan不进parts_纯plan消息照样建`() {
        val payload = planPayload("第一步" to "done", "第二步" to "doing")
        val out = BaopuMessageAssembler.assemble(
            listOf(
                row(7, "assistant", 30L, "plan", payload),
                row(8, "assistant", 40L, "text", "干完了"),
            ),
        )
        assertEquals(2, out.size)
        assertEquals("纯 plan 消息 parts 必须为空（payload 不当正文）", 0, out[0].parts.size)
        assertEquals(2, out[0].plan?.items?.size)
        assertEquals("第一步", out[0].plan?.items?.first()?.text)
        assertEquals("普通消息不许带 plan", null, out[1].plan)
    }

    @Test
    fun `A3_plan解析不出条目就当没有_消息也不建`() {
        val out = BaopuMessageAssembler.assemble(
            listOf(
                row(9, "assistant", 50L, "plan", "{坏 JSON"),
                row(10, "assistant", 60L, "plan", "{\"items\":[]}"),
            ),
        )
        assertTrue("解析不出条目的 plan-only 消息整条丢掉（没有可显示的东西）", out.isEmpty())
    }

    @Test
    fun `A4_tool的payload按JSON解析_坏JSON退回原文`() {
        val out = BaopuMessageAssembler.assemble(
            listOf(
                row(11, "assistant", 70L, "tool", "{\"tool\":\"read_file\",\"result\":\"内容\"}"),
                row(12, "assistant", 80L, "tool", "不是 JSON"),
            ),
        )
        assertEquals("read_file", out[0].parts[0].tool)
        assertEquals("内容", out[0].parts[0].text)
        assertEquals("坏 JSON ⇒ 工具名留空、正文用原文（不许抛）", "", out[1].parts[0].tool)
        assertEquals("不是 JSON", out[1].parts[0].text)
    }

    @Test
    fun `A5_未知kind原样保留_不被归一成text`() {
        val out = BaopuMessageAssembler.assemble(
            listOf(row(13, "assistant", 90L, "compressed", "压缩标记")),
        )
        assertEquals("compressed", out[0].parts[0].kind)
        assertEquals("压缩标记", out[0].parts[0].text)
    }

    @Test
    fun `A6_同消息两条plan后一条赢`() {
        val first = planPayload("旧的" to "todo")
        val second = planPayload("新的" to "done")
        val out = BaopuMessageAssembler.assemble(
            listOf(row(14, "assistant", 100L, "plan", first), row(14, "assistant", 100L, "plan", second)),
        )
        assertEquals(1, out.size)
        assertEquals("新的", out[0].plan?.items?.single()?.text)
        assertNull("plan 不进 parts", out[0].parts.firstOrNull())
    }

    // ── 源码门禁：控制器只剩调用 ──────────────────────────────────────────

    @Test
    fun `G1_控制器只调用装配器_不留第二份实现`() {
        val c = controller()
        assertTrue("readMessages 必须调 BaopuMessageAssembler.assemble", c.contains("BaopuMessageAssembler.assemble(rows)"))
        assertTrue("控制器必须把游标摊成 BaopuPartRow", c.contains("BaopuPartRow("))
        assertTrue("plan 解析落点已搬到装配器（控制器不许再有第二份）", !c.contains("BaopuUiPlan.fromPayload"))
        assertTrue("tool JSON 解析不许在控制器里再抄一份", !c.contains("json?.optString(\"result\")"))
    }
}
