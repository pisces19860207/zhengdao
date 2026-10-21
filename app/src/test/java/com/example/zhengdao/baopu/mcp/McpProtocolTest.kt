// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十三批 · MCP v1：JSON-RPC/URL/文本清洗的纯 JVM 单测。
package com.example.zhengdao.baopu.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpProtocolTest {

    // ── JSON-RPC 信封 ────────────────────────────────────────────────────

    @Test
    fun `请求与通知_形状正确`() {
        val req = McpJsonRpc.request(7, "tools/list", JSONObject().put("cursor", "c1"))
        assertEquals("2.0", req.getString("jsonrpc"))
        assertEquals(7L, req.getLong("id"))
        assertEquals("tools/list", req.getString("method"))
        assertEquals("c1", req.getJSONObject("params").getString("cursor"))

        val note = McpJsonRpc.notification("notifications/initialized")
        assertEquals("2.0", note.getString("jsonrpc"))
        assertEquals("notifications/initialized", note.getString("method"))
        assertFalse("通知不带 id", note.has("id"))
    }

    @Test
    fun `响应_id 对上才认`() {
        val incoming = McpJsonRpc.parse("""{"jsonrpc":"2.0","id":9,"result":{"ok":true}}""")
        assertTrue(incoming is McpJsonRpc.Incoming.Response)
        val r = incoming as McpJsonRpc.Incoming.Response
        assertEquals(9L, r.id)
        assertEquals(true, r.result?.getBoolean("ok"))
        assertNull(r.error)
    }

    @Test
    fun `服务器请求与通知_分类正确`() {
        val call = McpJsonRpc.parse(
            """{"jsonrpc":"2.0","id":"srv-1","method":"sampling/createMessage","params":{}}""",
        )
        assertTrue(call is McpJsonRpc.Incoming.ServerRequest)
        assertEquals("sampling/createMessage", (call as McpJsonRpc.Incoming.ServerRequest).method)

        val note = McpJsonRpc.parse(
            """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}""",
        )
        assertTrue(note is McpJsonRpc.Incoming.Notification)
        assertEquals("notifications/progress", (note as McpJsonRpc.Incoming.Notification).method)
    }

    @Test
    fun `批量消息_逐条分类`() {
        val incoming = McpJsonRpc.parse(
            """[
              {"jsonrpc":"2.0","id":1,"result":{}},
              {"jsonrpc":"2.0","method":"notifications/message","params":{}}
            ]""",
        )
        assertTrue(incoming is McpJsonRpc.Incoming.Batch)
        assertEquals(2, (incoming as McpJsonRpc.Incoming.Batch).items.size)
    }

    @Test
    fun `错误文案_不带 data`() {
        val error = JSONObject()
            .put("code", -32001)
            .put("message", "boom\nsecond line")
            .put("data", "SECRET-TOKEN")
        val text = McpJsonRpc.responseErrorText(error)
        assertTrue(text.contains("boom second line"))
        assertFalse("data 不许进人话", text.contains("SECRET-TOKEN"))
    }

    @Test
    fun `坏 JSON_协议错误`() {
        try {
            McpJsonRpc.parse("{not json")
            fail("应当抛 McpException")
        } catch (e: McpException) {
            assertEquals(McpFailure.PROTOCOL, e.failure)
        }
    }

    // ── tools-call 内容 ─────────────────────────────────────────────────

    @Test
    fun `内容_只回文本_图片音频给占位`() {
        val result = JSONObject().put(
            "content",
            org.json.JSONArray()
                .put(JSONObject().put("type", "text").put("text", "第一段"))
                .put(JSONObject().put("type", "image").put("data", "AAAA").put("mimeType", "image/png"))
                .put(
                    JSONObject().put("type", "resource").put(
                        "resource",
                        JSONObject().put("uri", "file:///x").put("text", "资源正文"),
                    ),
                ),
        )
        val text = McpJsonRpc.toolContent(result)
        assertTrue(text.contains("第一段"))
        assertTrue(text.contains("资源正文"))
        assertTrue(text.contains("[图片内容未展示"))
        assertFalse("base64 不许进上下文", text.contains("AAAA"))
    }

    @Test
    fun `内容_structuredContent 兜底`() {
        val result = JSONObject().put(
            "structuredContent",
            JSONObject().put("answer", 42),
        )
        assertTrue(McpJsonRpc.toolContent(result).contains("\"answer\":42"))
    }

    @Test
    fun `内容_超长截断`() {
        val huge = "a".repeat(McpJsonRpc.MAX_RESULT_CHARS + 10)
        val result = JSONObject().put(
            "content",
            org.json.JSONArray().put(JSONObject().put("type", "text").put("text", huge)),
        )
        val text = McpJsonRpc.toolContent(result)
        assertTrue(text.length <= McpJsonRpc.MAX_RESULT_CHARS + 100)
        assertTrue(text.contains("已截断"))
    }

    // ── URL 许可 ────────────────────────────────────────────────────────

    @Test
    fun `URL_只放行 https 与环回 http`() {
        assertNull(McpUrlRules.check("https://mcp.example.com/mcp"))
        assertNull(McpUrlRules.check("http://127.0.0.1:8080/mcp"))
        assertNull(McpUrlRules.check("http://localhost:8080/mcp"))
        assertNull(McpUrlRules.check("http://[::1]:8080/mcp"))

        assertTrue(McpUrlRules.check("http://mcp.example.com/mcp")!!.contains("环回"))
        assertTrue(McpUrlRules.check("ftp://example.com/mcp")!!.contains("http"))
        assertTrue(McpUrlRules.check("https://user:pass@example.com/mcp")!!.contains("用户名"))
        assertTrue(McpUrlRules.check("https://example.com/mcp#frag")!!.contains("fragment"))
        // 关键负例：字符串前缀骗不过解析
        assertTrue(McpUrlRules.check("http://127.0.0.1.evil.com/mcp")!!.contains("环回"))
    }

    @Test
    fun `URL_日志脱敏_不带 query`() {
        val redacted = McpUrlRules.redact("https://example.com/mcp?token=SECRET&x=1")
        assertTrue(redacted.contains("https://example.com/mcp"))
        assertFalse(redacted.contains("SECRET"))
        assertFalse(redacted.contains("token"))
    }

    @Test
    fun `旧 SSE_endpoint_必须同源`() {
        val base = "http://127.0.0.1:8080/mcp"
        assertEquals(
            "http://127.0.0.1:8080/messages",
            McpUrlRules.resolveSameOrigin(base, "/messages"),
        )
        try {
            McpUrlRules.resolveSameOrigin(base, "http://evil.example.com/messages")
            fail("跨源 endpoint 应被拒")
        } catch (e: McpException) {
            assertEquals(McpFailure.PROTOCOL, e.failure)
        }
    }

    // ── 配置与清洗 ──────────────────────────────────────────────────────

    @Test
    fun `配置_保留头拒绝_toString 不带值`() {
        val cfg = McpServerConfig(
            id = "demo",
            name = "演示",
            url = "https://example.com/mcp?token=SECRET",
            headers = mapOf("Authorization" to "Bearer SECRET"),
        )
        val text = cfg.toString()
        assertFalse(text.contains("SECRET"))
        assertTrue(text.contains("headers=1"))
        assertTrue(text.contains("https://example.com/mcp"))
    }

    @Test
    fun `配置_保留头_一律拒`() {
        try {
            McpServerConfig(
                id = "demo", name = "演示", url = "https://example.com/mcp",
                headers = mapOf("Mcp-Session-Id" to "x"),
            )
            fail("保留头应当被拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("由客户端管理"))
        }
    }

    @Test
    fun `文本清洗_压平与截断`() {
        assertEquals("a b c", McpText.flat(" a\nb\tc "))
        val clipped = McpText.clip("abcdef", 4)
        assertEquals(4, clipped.length)
        assertTrue(clipped.endsWith("…"))
        assertEquals(
            McpText.shortHash("同一个"),
            McpText.shortHash("同一个"),
        )
        assertEquals(6, McpText.shortHash("x").length)
    }
}
