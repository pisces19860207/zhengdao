// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十五批 · MCP 传输校正判据与负例（派工单 §3）：
//   正例：假服务器只接受 2026-07-28 ⇒ 协商成功（头＋_meta 一致）；
//   负例1 版本不支持时静默失败（必须给出可读原因＋服务器支持列表；不许降级到旧版）；
//   负例2 MCP-Protocol-Version 与 _meta 不一致；
//   负例3 没有会话头就抛异常/中断；
//   负例4 对 405 的 GET/DELETE 当致命错误。
// 任何一条被改坏，本文件里的对应用例先红。
package com.example.zhengdao.baopu.mcp

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Collections

class McpTransport2026Test {

    private lateinit var server: MockWebServer
    private val seen = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun config() = McpServerConfig(
        id = "srv",
        name = "假 server",
        url = "http://127.0.0.1:${server.port}/mcp",
        connectTimeoutMs = 10_000,
        callTimeoutMs = 10_000,
    )

    private fun client(cfg: McpServerConfig = config(), log: (String) -> Unit = {}) =
        McpClient(cfg, OkHttpClient(), log)

    private fun bodyOf(req: RecordedRequest): JSONObject {
        val text = req.body.clone().readUtf8()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun result(req: RecordedRequest, resultJson: String): MockResponse {
        val id = bodyOf(req).opt("id")
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""")
    }

    private val initJson2026 =
        """{"protocolVersion":"2026-07-28","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}"""

    private val toolOne =
        """{"name":"echo","description":"回显","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]},"annotations":{"readOnlyHint":true}}"""

    /**
     * 2026-07-28 假服务器：
     * - 每个 POST 都校验 `MCP-Protocol-Version` 头与 body `params._meta.protocolVersion`
     *   一致且等于 2026-07-28，否则 400 HeaderMismatch（照官方规格的罚法）；
     * - GET/DELETE 一律 405（协议级会话已删的正规行为）；
     * - 按需回 `Mcp-Session-Id`（默认不回——证明客户端不依赖会话）。
     */
    private fun modernDispatcher(
        returnSession: Boolean = false,
        listBody: String = """{"tools":[$toolOne]}""",
    ) = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            seen.add(req)
            if (req.method == "GET" || req.method == "DELETE") {
                return MockResponse().setResponseCode(405)
            }
            val body = bodyOf(req)
            val headerVersion = req.getHeader("MCP-Protocol-Version")
            val metaVersion = body.optJSONObject("params")
                ?.optJSONObject("_meta")
                ?.optString("protocolVersion")
            if (headerVersion != McpJsonRpc.PROTOCOL || metaVersion != McpJsonRpc.PROTOCOL) {
                val id = body.opt("id")
                return MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"HeaderMismatch"}}""",
                    )
            }
            return when (body.optString("method")) {
                "initialize" -> result(req, initJson2026).apply {
                    if (returnSession) setHeader("Mcp-Session-Id", "sess-215")
                }
                "notifications/initialized" -> MockResponse().setResponseCode(202)
                "tools/list" -> result(req, listBody)
                "tools/call" -> result(req, """{"content":[{"type":"text","text":"pong"}],"isError":false}""")
                else -> MockResponse().setResponseCode(400)
            }
        }
    }

    /** 只讲 2025-03-26 的旧服务器：initialize 回 HTTP 400 + UnsupportedProtocolVersionError（带支持列表）。 */
    private fun oldVersionDispatcher() = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            seen.add(req)
            val body = bodyOf(req)
            if (body.optString("method") == "initialize") {
                val id = body.opt("id")
                return MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"jsonrpc":"2.0","id":$id,"error":{"code":-32002,"message":"Unsupported protocol version","data":{"supported":["2025-03-26","2025-06-18"]}}}""",
                    )
            }
            return MockResponse().setResponseCode(400)
        }
    }

    /** 旧服务器的另一种长相：initialize 回 200，但 result.protocolVersion 是 2025-03-26（静默降级陷阱）。 */
    private fun echoOldDispatcher() = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            seen.add(req)
            val body = bodyOf(req)
            return if (body.optString("method") == "initialize") {
                result(
                    req,
                    """{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"old","version":"1"}}""",
                )
            } else {
                MockResponse().setResponseCode(400)
            }
        }
    }

    /**
     * 只提供旧式 HTTP+SSE 传输、但协议讲 2026-07-28 的服务器：
     * POST /mcp 一律 405（客户端应回退），GET /mcp 给 SSE 流。
     */
    private fun legacyOnlyDispatcher() = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            seen.add(req)
            if (req.method == "GET") {
                return MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        "event: endpoint\ndata: /messages\n\n" +
                            "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":$initJson2026}\n\n" +
                            "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[$toolOne]}}\n\n" +
                            "data: {\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"legacy-pong\"}]}}\n\n",
                    )
            }
            if (req.method == "POST" && req.path?.startsWith("/messages") == true) {
                return MockResponse().setResponseCode(202)
            }
            return MockResponse().setResponseCode(405)
        }
    }

    // ── 正例：新版服务器协商成功 ────────────────────────────────────────

    @Test
    fun `新版服务器_协商成功_头与_meta同源_Accept双类型_不发GET_DELETE`() {
        server.dispatcher = modernDispatcher()
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val c = client(log = { logs.add(it) })
        c.connect()
        assertEquals("streamable-http", c.mode)
        assertEquals("2026-07-28", c.protocolVersion)

        val tools = c.listTools()
        assertEquals(1, tools.size)
        assertEquals("pong", c.callTool("echo", JSONObject().put("text", "hi")).text)

        val posts = seen.filter { it.method == "POST" }
        assertEquals(4, posts.size)
        posts.forEach { req ->
            assertEquals("application/json, text/event-stream", req.getHeader("Accept"))
            assertEquals(McpJsonRpc.PROTOCOL, req.getHeader("MCP-Protocol-Version"))
            val params = bodyOf(req).getJSONObject("params")
            assertEquals(McpJsonRpc.PROTOCOL, params.getJSONObject("_meta").getString("protocolVersion"))
        }
        // 负例4 的另一半：协议级会话已删 ⇒ 客户端一个 GET/DELETE 都不发
        assertEquals(0, seen.count { it.method == "GET" })
        assertEquals(0, seen.count { it.method == "DELETE" })
        assertTrue("协商成功要记一句", logs.any { it.contains("协商成功") && it.contains("2026-07-28") })
    }

    @Test
    fun `正例_服务器回会话头_带上_兼容旧服务器`() {
        server.dispatcher = modernDispatcher(returnSession = true)
        val c = client()
        c.connect()
        c.listTools()
        assertEquals("pong", c.callTool("echo", JSONObject()).text)
        val posts = seen.filter { it.method == "POST" }
        assertNull(posts.first().getHeader("Mcp-Session-Id")) // initialize 还不带
        posts.drop(1).forEach { assertEquals("sess-215", it.getHeader("Mcp-Session-Id")) }
    }

    // ── 负例1：版本不支持 ⇒ 明确失败（不降级、不静默、给支持列表） ──────

    @Test
    fun `负例1_旧服务器HTTP400_明确失败并回报支持列表`() {
        server.dispatcher = oldVersionDispatcher()
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val c = client(log = { logs.add(it) })
        try {
            c.connect()
            fail("只讲旧协议的服务器必须明确失败")
        } catch (e: McpException) {
            val msg = e.userMessage
            assertTrue("要说出服务器原话：$msg", msg.contains("Unsupported protocol version"))
            assertTrue("要列出它声明支持的版本：$msg", msg.contains("2025-03-26") && msg.contains("2025-06-18"))
            assertTrue("要写清不降级与出路：$msg", msg.contains("不会自动降到旧版") && msg.contains("升级"))
            assertTrue("日志里也要有支持列表：$logs", logs.any { it.contains("2025-03-26") })
        }
        assertEquals("不静默：只发过 initialize，没有接着列工具", 1, seen.count { it.method == "POST" })
        assertEquals("不降级：没有回退旧式 SSE（零 GET）", 0, seen.count { it.method == "GET" })
    }

    @Test
    fun `负例1b_initialize回声旧版本_明确失败_不继续不发tools_list`() {
        server.dispatcher = echoOldDispatcher()
        val c = client()
        try {
            c.connect()
            fail("回声旧版必须明确失败")
        } catch (e: McpException) {
            val msg = e.userMessage
            assertTrue("点名旧协议：$msg", msg.contains("只支持旧协议") && msg.contains("2025-03-26"))
            assertTrue("不降级：$msg", msg.contains("不会自动降到旧版"))
        }
        assertEquals("一个请求都不许多发", 1, seen.count { it.method == "POST" })
    }

    @Test
    fun `负例1c_initialize不回声协议版本_明确失败`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                seen.add(req)
                val body = bodyOf(req)
                return if (body.optString("method") == "initialize") {
                    result(req, """{"capabilities":{},"serverInfo":{"name":"mute","version":"1"}}""")
                } else {
                    MockResponse().setResponseCode(400)
                }
            }
        }
        val c = client()
        try {
            c.connect()
            fail("不回声版本必须明确失败")
        } catch (e: McpException) {
            assertTrue("说清没回应版本：${e.userMessage}", e.userMessage.contains("没有回应协议版本"))
        }
    }

    // ── 负例2：版本头与 _meta 不一致 ────────────────────────────────────

    @Test
    fun `负例2_版本头与_meta不一致_服务器罚400_直连断言也同源`() {
        // 服务器侧：头与 _meta 不一致 ⇒ 400 HeaderMismatch（官方规格的罚法）；
        // 实现若把两者改成不同来源，这里连接直接失败。
        server.dispatcher = modernDispatcher()
        val c = client()
        c.connect()
        c.listTools()
        // 客户端侧直连断言：每个 POST 的头、_meta、Accept 都对得上
        seen.filter { it.method == "POST" }.forEach { req ->
            assertEquals(McpJsonRpc.PROTOCOL, req.getHeader("MCP-Protocol-Version"))
            val meta = bodyOf(req).getJSONObject("params").getJSONObject("_meta")
            assertEquals(McpJsonRpc.PROTOCOL, meta.getString("protocolVersion"))
        }
    }

    // ── 负例3：没有会话头就抛/中断 ──────────────────────────────────────

    @Test
    fun `负例3_服务器不回会话头_完整链路照常`() {
        server.dispatcher = modernDispatcher(returnSession = false)
        val c = client()
        c.connect()
        assertEquals(1, c.listTools().size)
        val out = c.callTool("echo", JSONObject().put("text", "hi"))
        assertEquals("pong", out.text)
        assertFalse(out.isError)
        val posts = seen.filter { it.method == "POST" }
        assertEquals(4, posts.size)
        posts.forEach { assertNull("没有会话头也继续：${it.requestUrl}", it.getHeader("Mcp-Session-Id")) }
    }

    // ── 负例4：405 不当致命 ─────────────────────────────────────────────

    @Test
    fun `负例4_initialize回405_回退旧SSE_握手仍只认2026-07-28`() {
        server.dispatcher = legacyOnlyDispatcher()
        val c = client()
        c.connect()
        assertEquals("legacy-sse", c.mode)
        assertEquals("2026-07-28", c.protocolVersion)
        assertEquals(1, c.listTools().size)
        assertEquals("legacy-pong", c.callTool("echo", JSONObject()).text)
        // 旧式传输的 POST 也带版本头与 _meta（同一处常量）
        seen.filter { it.method == "POST" && it.path?.startsWith("/messages") == true }.forEach { req ->
            assertEquals(McpJsonRpc.PROTOCOL, req.getHeader("MCP-Protocol-Version"))
            assertEquals(
                McpJsonRpc.PROTOCOL,
                bodyOf(req).getJSONObject("params").getJSONObject("_meta").getString("protocolVersion"),
            )
        }
    }

    @Test
    fun `负例4b_旧式SSE服务器回声旧版本_回退后也明确失败`() {
        // 传输能回退，版本也不将就：旧式服务器讲 2025-03-26 ⇒ 明确失败
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                seen.add(req)
                if (req.method == "GET") {
                    return MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(
                            "event: endpoint\ndata: /messages\n\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{}}}\n\n",
                        )
                }
                if (req.method == "POST" && req.path?.startsWith("/messages") == true) {
                    return MockResponse().setResponseCode(202)
                }
                return MockResponse().setResponseCode(405)
            }
        }
        val c = client()
        try {
            c.connect()
            fail("旧式旧协议服务器必须明确失败")
        } catch (e: McpException) {
            assertTrue(e.userMessage.contains("只支持旧协议") && e.userMessage.contains("2025-03-26"))
        }
    }
}
