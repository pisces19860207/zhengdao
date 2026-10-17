// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.2（ToolRegistryTest：坏 JSON、参数不合 Schema、未知工具名）
// + 负例演练①（解码不校验 Schema ⇒ 坏参数用例必红——本文件就是红的那批）。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(
            ToolSpec(
                name = "read_file",
                description = "读文件",
                inputSchema = jsonObjectOf(
                    "type" to "object",
                    "properties" to jsonObjectOf(
                        "path" to jsonObjectOf("type" to "string"),
                        "offset" to jsonObjectOf("type" to "integer", "minimum" to 0),
                        "limit" to jsonObjectOf("type" to "integer", "minimum" to 1, "maximum" to 100),
                        "mode" to jsonObjectOf("type" to "string", "enum" to jsonArrayOf("text", "lines")),
                    ),
                    "required" to jsonArrayOf("path"),
                    "additionalProperties" to false,
                ),
            ) { ToolOutcome.Ok("ok") },
        )
    }

    // ── 注册表校验（① 段） ──────────────────────────────────────────────

    @Test
    fun `未知工具名_拒绝并列出已注册`() {
        val err = registry().validateCall("write_file", JSONObject())
        assertNotNull("未知工具必须拒绝", err)
        assertTrue("错误原文要点名未知工具", err!!.contains("write_file"))
        assertTrue("错误原文要列出已注册的（让模型自己改）", err.contains("read_file"))
    }

    @Test
    fun `缺必填参数_拒绝并点名`() {
        val err = registry().validateCall("read_file", JSONObject())
        assertNotNull(err)
        assertTrue(err!!.contains("path"))
    }

    @Test
    fun `未知参数_一律拒绝_不直通`() {
        // 模型幻觉出的参数名（负例①靶心）：不拒绝的话，模型会以为参数生效了
        val err = registry().validateCall("read_file", JSONObject().put("path", "a.md").put("encodin", "utf8"))
        assertNotNull("未知参数必须拒绝", err)
        assertTrue(err!!.contains("encodin"))
    }

    @Test
    fun `类型不对_逐个点名`() {
        assertTrue(registry().validateCall("read_file", JSONObject().put("path", 5))!!.contains("path"))
        assertTrue(registry().validateCall("read_file", JSONObject().put("path", "a").put("offset", "3"))!!.contains("offset"))
        assertTrue(registry().validateCall("read_file", JSONObject().put("path", "a").put("limit", true))!!.contains("limit"))
    }

    @Test
    fun `整数范围_上下界都拦`() {
        assertTrue(registry().validateCall("read_file", JSONObject().put("path", "a").put("offset", -1))!!.contains("offset"))
        assertTrue(registry().validateCall("read_file", JSONObject().put("path", "a").put("limit", 101))!!.contains("limit"))
    }

    @Test
    fun `枚举成员外_拒绝`() {
        val err = registry().validateCall("read_file", JSONObject().put("path", "a").put("mode", "binary"))
        assertNotNull(err)
        assertTrue(err!!.contains("mode"))
        assertTrue(err.contains("text")) // 允许范围要写进错误原文
    }

    @Test
    fun `合法参数_全过`() {
        assertNull(
            registry().validateCall(
                "read_file",
                JSONObject().put("path", "a.md").put("offset", 10).put("limit", 50).put("mode", "lines"),
            ),
        )
    }

    // ── tools 请求形状（协议 A 请求侧） ─────────────────────────────────

    @Test
    fun `OpenAI兼容tools形状_type_function三件套`() {
        val arr = registry().toolsJsonOpenAi()
        assertEquals(1, arr.length())
        val fn = arr.getJSONObject(0)
        assertEquals("function", fn.getString("type"))
        assertEquals("read_file", fn.getJSONObject("function").getString("name"))
        assertTrue(fn.getJSONObject("function").has("parameters"))
    }

    @Test
    fun `Anthropic tools形状_schema直接铺平`() {
        val arr = registry().toolsJsonAnthropic()
        assertEquals(1, arr.length())
        val t = arr.getJSONObject(0)
        assertEquals("read_file", t.getString("name"))
        assertTrue(t.has("input_schema"))
        assertFalse("Anthropic 形状没有 function 包一层", t.has("function"))
    }

    @Test
    fun `空注册表_tools数组为空`() {
        assertEquals(0, ToolRegistry().toolsJsonOpenAi().length())
        assertEquals(0, ToolRegistry().toolsJsonAnthropic().length())
    }

    @Test
    fun `同名覆盖_后注册赢_不双注册`() {
        val r = registry()
        r.register(
            ToolSpec("read_file", "第二版", jsonObjectOf("type" to "object", "properties" to JSONObject()), ) { ToolOutcome.Ok("v2") },
        )
        assertEquals(1, r.all().size)
        assertTrue(r.all().single().description.contains("第二版"))
    }
}
