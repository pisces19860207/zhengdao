// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十三批 · MCP v1：tools/list → ToolSpec 的 schema 白名单/命名/描述。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.agent.SchemaCheck
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpSchemaTest {

    private fun schema(json: String): JSONObject = JSONObject(json)

    private fun normalize(json: String): JSONObject =
        McpSchema.normalizeSchema("t", schema(json))

    private fun descriptor(
        name: String,
        json: String = """{"type":"object","properties":{}}""",
        readOnly: Boolean = false,
    ) = McpToolDescriptor(
        serverId = "srv",
        serverName = "演示 server",
        name = name,
        description = "",
        inputSchema = McpSchema.normalizeSchema(name, schema(json)),
        readOnly = readOnly,
    )

    // ── schema 白名单 ──────────────────────────────────────────────────

    @Test
    fun `合法 schema_缺省补 additionalProperties=false`() {
        val out = normalize(
            """
            {"type":"object",
             "properties":{
               "path":{"type":"string","description":"相对路径"},
               "count":{"type":"integer","minimum":1,"maximum":9},
               "tags":{"type":"array","items":{"type":"string"}}
             },
             "required":["path"]}
            """,
        )
        assertEquals("object", out.getString("type"))
        assertFalse(out.getBoolean("additionalProperties"))
        assertEquals("path", out.getJSONArray("required").getString(0))
        assertEquals(1, out.getJSONObject("properties").getJSONObject("count").getInt("minimum"))
        assertEquals(
            "string",
            out.getJSONObject("properties").getJSONObject("tags").getJSONObject("items").getString("type"),
        )
        // 与守卫管线同一个校验器：缺必填/未知参数都在本地拦
        assertTrue(SchemaCheck.validate(out, JSONObject())!!.contains("path"))
        assertTrue(
            SchemaCheck.validate(out, JSONObject().put("path", "a").put("hallucinated", 1))!!
                .contains("hallucinated"),
        )
        assertEquals(null, SchemaCheck.validate(out, JSONObject().put("path", "a")))
    }

    @Test
    fun `服务端显式 additionalProperties=true_尊重`() {
        val out = normalize("""{"type":"object","properties":{},"additionalProperties":true}""")
        assertTrue(out.getBoolean("additionalProperties"))
    }

    @Test
    fun `不支持的结构_整工具拒收`() {
        val bad = listOf(
            """{"type":"object","properties":{"x":{"${'$'}ref":"#/defs/x"}}}""",
            """{"type":"object","properties":{"x":{"oneOf":[{"type":"string"}]}}}""",
            """{"type":"object","properties":{"x":{"type":"object","properties":{}}}}""",
            """{"type":"object","properties":{"x":{"type":"array","items":{"type":"object"}}}}""",
            """{"type":"object","properties":{"x":{"type":"string","pattern":"^a"}}}""",
            """{"type":"object","properties":{"x":{"type":"integer","enum":[1]}}}""",
            """{"type":"object","properties":{"x":{"type":"string","minimum":1}}}""",
            """{"type":"object","properties":{"x":{"type":"string"}},"required":["y"]}""",
            """{"type":"string"}""",
            """{"type":"object","additionalProperties":{"type":"string"}}""",
        )
        bad.forEach { json ->
            try {
                normalize(json)
                fail("应当拒收：$json")
            } catch (e: McpException) {
                assertEquals("$json ⇒ ${e.userMessage}", McpFailure.SCHEMA, e.failure)
            }
        }
    }

    // ── 命名/描述 ──────────────────────────────────────────────────────

    @Test
    fun `工具名_前缀化_清洗_64 字符上限`() {
        assertEquals("mcp_srv_do_thing", McpSchema.wireName("srv", "Do.Thing"))
        assertEquals("mcp_srv_tool", McpSchema.wireName("srv", "纯中文名"))
        val long = McpSchema.wireName("srv", "a".repeat(120))
        assertEquals(64, long.length)
        assertTrue(long.startsWith("mcp_srv_a"))
        assertEquals("稳定哈希", long, McpSchema.wireName("srv", "a".repeat(120)))
    }

    @Test
    fun `转换_撞本地工具_整 server 拒收`() {
        try {
            McpSchema.convertAll(listOf(descriptor("echo")), setOf("mcp_srv_echo"))
            fail("撞本地工具名应拒收")
        } catch (e: McpException) {
            assertEquals(McpFailure.SCHEMA, e.failure)
            assertTrue(e.userMessage.contains("本地工具"))
        }
    }

    @Test
    fun `转换_撞其它 MCP_哈希避让_不覆盖`() {
        val out = McpSchema.convertAll(
            listOf(descriptor("echo")),
            existingNames = setOf("mcp_srv_echo"),
            mcpOwnedNames = setOf("mcp_srv_echo"),
        )
        assertEquals(1, out.size)
        assertNotEquals("mcp_srv_echo", out[0].wireName)
        assertTrue(out[0].wireName.length <= 64)
        assertEquals("mcp_srv_echo", out[0].wireName.substringBeforeLast("_"))
    }

    @Test
    fun `转换_同批重名_第二个哈希`() {
        val out = McpSchema.convertAll(listOf(descriptor("a"), descriptor("a")), emptySet())
        assertEquals(2, out.size)
        assertNotEquals(out[0].wireName, out[1].wireName)
    }

    @Test
    fun `描述_压平_前缀_截断 500`() {
        val d = McpToolDescriptor(
            serverId = "srv",
            serverName = "演示\nserver",
            name = "x",
            description = "第一行\n第二行\t".repeat(200),
            inputSchema = JSONObject(),
            readOnly = false,
        )
        val text = McpSchema.describe(d)
        assertTrue(text.startsWith("【外部工具 · 演示 server】"))
        assertTrue(text.length <= 500)
        assertFalse(text.contains("\n"))
    }

    // ── tools/list 解析 ────────────────────────────────────────────────

    @Test
    fun `列表_读只读提示_不认 openWorld`() {
        val result = JSONObject().put(
            "tools",
            JSONArray()
                .put(
                    JSONObject().put("name", "read_one")
                        .put("description", "读")
                        .put("inputSchema", schema("""{"type":"object","properties":{}}"""))
                        .put(
                            "annotations",
                            JSONObject().put("readOnlyHint", true).put("destructiveHint", false)
                                .put("openWorldHint", true),
                        ),
                )
                .put(
                    JSONObject().put("name", "write_one")
                        .put("inputSchema", schema("""{"type":"object","properties":{}}"""))
                        .put(
                            "annotations",
                            JSONObject().put("readOnlyHint", true).put("destructiveHint", true),
                        ),
                )
                .put(
                    JSONObject().put("name", "plain")
                        .put("inputSchema", schema("""{"type":"object","properties":{}}""")),
                ),
        )
        val list = McpSchema.parseToolList("srv", "演示", result)
        assertEquals(3, list.size)
        assertTrue(list[0].readOnly)
        assertFalse("destructive 压过 readOnly", list[1].readOnly)
        assertFalse("没有注解按危险处理", list[2].readOnly)
    }

    @Test
    fun `列表_坏 schema_整 server 拒收`() {
        val result = JSONObject().put(
            "tools",
            JSONArray().put(
                JSONObject().put("name", "bad")
                    .put("inputSchema", schema("""{"type":"object","properties":{"x":{"${'$'}ref":"#/x"}}}""")),
            ),
        )
        try {
            McpSchema.parseToolList("srv", "演示", result)
            fail("坏 schema 应拒收")
        } catch (e: McpException) {
            assertEquals(McpFailure.SCHEMA, e.failure)
        }
    }

    @Test
    fun `列表_重名与超量_拒收`() {
        val dup = JSONObject().put(
            "tools",
            JSONArray()
                .put(JSONObject().put("name", "x").put("inputSchema", schema("""{"type":"object"}""")))
                .put(JSONObject().put("name", "x").put("inputSchema", schema("""{"type":"object"}"""))),
        )
        try {
            McpSchema.parseToolList("srv", "演示", dup)
            fail("重名应拒收")
        } catch (e: McpException) {
            assertEquals(McpFailure.SCHEMA, e.failure)
        }

        val many = JSONArray()
        repeat(McpSchema.MAX_TOOLS + 1) {
            many.put(JSONObject().put("name", "t$it").put("inputSchema", schema("""{"type":"object"}""")))
        }
        try {
            McpSchema.parseToolList("srv", "演示", JSONObject().put("tools", many))
            fail("超量应拒收")
        } catch (e: McpException) {
            assertEquals(McpFailure.SCHEMA, e.failure)
        }
    }
}
