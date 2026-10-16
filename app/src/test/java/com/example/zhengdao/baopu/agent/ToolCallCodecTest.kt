// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.2（ToolCallCodecTest：坏 JSON、参数不合 Schema、未知工具名、
// 协议 A 的多工具调用——一轮多个 tool_calls）+ 程枢版 §4（坏 JSON/坏参数 ⇒ 错误原文
// 回灌让模型重试 1 次，再坏即停 ⇒ Bad 的错误原文就是回灌内容）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RawToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallCodecTest {

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(
            ToolSpec(
                name = "read_file",
                description = "读文件",
                inputSchema = jsonObjectOf(
                    "type" to "object",
                    "properties" to jsonObjectOf("path" to jsonObjectOf("type" to "string")),
                    "required" to jsonArrayOf("path"),
                    "additionalProperties" to false,
                ),
            ) { ToolOutcome.Ok("ok") },
        )
        register(
            ToolSpec(
                name = "list_dir",
                description = "列目录",
                inputSchema = jsonObjectOf(
                    "type" to "object",
                    "properties" to jsonObjectOf("path" to jsonObjectOf("type" to "string")),
                    "required" to jsonArrayOf("path"),
                ),
            ) { ToolOutcome.Ok("ok") },
        )
    }

    // ── 坏 JSON ─────────────────────────────────────────────────────────

    @Test
    fun `坏JSON_转Bad_带错误原文_不抛`() {
        val r = ToolCallCodec.decode(
            listOf(RawToolCall("c1", "read_file", "{\"path\": \"a.md\"")), // 少右花括号
            registry(),
        )
        assertTrue("坏 JSON 必须转 Bad（不抛、给原文）", r is ToolCallCodec.DecodeResult.Bad)
        assertTrue((r as ToolCallCodec.DecodeResult.Bad).error.contains("不是合法 JSON"))
    }

    @Test
    fun `空arguments_按空对象_缺必填再拒`() {
        val r = ToolCallCodec.decode(listOf(RawToolCall("c1", "read_file", "")), registry())
        assertTrue(r is ToolCallCodec.DecodeResult.Bad)
        assertTrue((r as ToolCallCodec.DecodeResult.Bad).error.contains("path"))
    }

    @Test
    fun `arguments不是对象_拒绝`() {
        val r = ToolCallCodec.decode(listOf(RawToolCall("c1", "read_file", "[1,2,3]")), registry())
        assertTrue(r is ToolCallCodec.DecodeResult.Bad)
    }

    // ── 参数不合 Schema / 未知工具 ──────────────────────────────────────

    @Test
    fun `参数不合Schema_拒绝_错误原文含参数名`() {
        val r = ToolCallCodec.decode(
            listOf(RawToolCall("c1", "read_file", """{"path": 5}""")),
            registry(),
        )
        assertTrue(r is ToolCallCodec.DecodeResult.Bad)
        assertTrue((r as ToolCallCodec.DecodeResult.Bad).error.contains("path"))
    }

    @Test
    fun `未知工具_拒绝_列出已注册`() {
        val r = ToolCallCodec.decode(
            listOf(RawToolCall("c1", "delete_everything", """{"path": "a"}""")),
            registry(),
        )
        assertTrue(r is ToolCallCodec.DecodeResult.Bad)
        val err = (r as ToolCallCodec.DecodeResult.Bad).error
        assertTrue(err.contains("delete_everything"))
        assertTrue(err.contains("read_file") && err.contains("list_dir"))
    }

    @Test
    fun `工具名为空_拒绝`() {
        val r = ToolCallCodec.decode(listOf(RawToolCall("c1", " ", "{}")), registry())
        assertTrue(r is ToolCallCodec.DecodeResult.Bad)
        assertTrue((r as ToolCallCodec.DecodeResult.Bad).error.contains("名字"))
    }

    @Test
    fun `registry为空_只解码不校验`() {
        val r = ToolCallCodec.decode(listOf(RawToolCall("c1", "read_file", """{"path": "a.md"}""")))
        assertTrue(r is ToolCallCodec.DecodeResult.Ok)
    }

    // ── 协议 A 多工具调用（一轮多个 tool_calls） ─────────────────────────

    @Test
    fun `一轮多个调用_按序全部解出`() {
        val r = ToolCallCodec.decode(
            listOf(
                RawToolCall("c1", "read_file", """{"path": "a.md"}"""),
                RawToolCall("c2", "list_dir", """{"path": "资料库"}"""),
            ),
            registry(),
        )
        assertTrue(r is ToolCallCodec.DecodeResult.Ok)
        val calls = (r as ToolCallCodec.DecodeResult.Ok).calls
        assertEquals(2, calls.size)
        assertEquals("c1", calls[0].id)
        assertEquals("read_file", calls[0].name)
        assertEquals("a.md", calls[0].arguments.getString("path"))
        assertEquals("list_dir", calls[1].name)
    }

    @Test
    fun `多调用里一个坏_整批Bad_不半跑`() {
        val r = ToolCallCodec.decode(
            listOf(
                RawToolCall("c1", "read_file", """{"path": "a.md"}"""),
                RawToolCall("c2", "list_dir", "{\"path\": "), // 坏 JSON
            ),
            registry(),
        )
        assertTrue("一个坏就整批拒（半跑会让模型以为第一个成功了）", r is ToolCallCodec.DecodeResult.Bad)
    }

    @Test
    fun `空调用列表_Bad`() {
        assertTrue(ToolCallCodec.decode(emptyList(), registry()) is ToolCallCodec.DecodeResult.Bad)
    }

    // ── OpenAI 数组入口（非流式/测试用；与 decode 同一张表） ─────────────

    @Test
    fun `OpenAI数组入口_与列表入口同判`() {
        val arr = org.json.JSONArray()
            .put(
                JSONObject()
                    .put("id", "c1")
                    .put("type", "function")
                    .put("function", JSONObject().put("name", "read_file").put("arguments", """{"path":"a.md"}""")),
            )
        val r = ToolCallCodec.decodeOpenAi(arr, registry())
        assertTrue(r is ToolCallCodec.DecodeResult.Ok)
        assertEquals("a.md", (r as ToolCallCodec.DecodeResult.Ok).calls[0].arguments.getString("path"))
    }

    @Test
    fun `OpenAI数组入口_缺function_Bad`() {
        val arr = org.json.JSONArray().put(JSONObject().put("id", "c1"))
        assertTrue(ToolCallCodec.decodeOpenAi(arr, registry()) is ToolCallCodec.DecodeResult.Bad)
    }

    @Test
    fun `OpenAI数组入口_空数组_Bad`() {
        assertTrue(ToolCallCodec.decodeOpenAi(org.json.JSONArray(), registry()) is ToolCallCodec.DecodeResult.Bad)
    }

    // ── Anthropic 入口 ─────────────────────────────────────────────────

    @Test
    fun `Anthropic_toolUse块_解出且input已是对象`() {
        val content = org.json.JSONArray()
            .put(JSONObject().put("type", "text").put("text", "我看看"))
            .put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", "toolu_1")
                    .put("name", "read_file")
                    .put("input", JSONObject().put("path", "a.md")),
            )
        val r = ToolCallCodec.decodeAnthropic(content, registry())
        assertTrue(r is ToolCallCodec.DecodeResult.Ok)
        val calls = (r as ToolCallCodec.DecodeResult.Ok).calls
        assertEquals(1, calls.size)
        assertEquals("toolu_1", calls[0].id)
        assertEquals("a.md", calls[0].arguments.getString("path"))
    }

    @Test
    fun `Anthropic_无toolUse块_Bad`() {
        val content = org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "纯文本"))
        assertTrue(ToolCallCodec.decodeAnthropic(content, registry()) is ToolCallCodec.DecodeResult.Bad)
    }

    // ── parseArgs 单元 ─────────────────────────────────────────────────

    @Test
    fun `parseArgs_空串按空对象_坏JSON返回null不抛`() {
        assertNotNull(ToolCallCodec.parseArgs(""))
        assertNotNull(ToolCallCodec.parseArgs("   "))
        assertEquals(0, ToolCallCodec.parseArgs("{}")!!.length())
        assertFalse("坏 JSON 不抛，返回 null", ToolCallCodec.parseArgs("{oops") != null)
    }
}
