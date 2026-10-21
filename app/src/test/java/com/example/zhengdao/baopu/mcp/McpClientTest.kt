// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十三批 · MCP v1：真实 OkHttp 栈 + MockWebServer 的线形单测。
// 覆盖 Streamable HTTP（JSON / SSE）、旧式 SSE 回退、超时、断连、HTTP 错误、
// JSON-RPC error、isError、session 头与「密钥不进日志」。
package com.example.zhengdao.baopu.mcp

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import java.util.concurrent.TimeUnit

class McpClientTest {

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

    private fun config(
        connectTimeoutMs: Long = 10_000,
        callTimeoutMs: Long = 10_000,
        headers: Map<String, String> = emptyMap(),
        query: String = "",
    ) = McpServerConfig(
        id = "srv",
        name = "假 server",
        url = "http://127.0.0.1:${server.port}/mcp$query",
        headers = headers,
        connectTimeoutMs = connectTimeoutMs,
        callTimeoutMs = callTimeoutMs,
    )

    private fun client(cfg: McpServerConfig, log: (String) -> Unit = {}) =
        McpClient(cfg, OkHttpClient(), log)

    private fun bodyOf(req: RecordedRequest): JSONObject {
        // clone：同一个 RecordedRequest 会被 dispatcher 与断言各读一次，Buffer 读完就空。
        val text = req.body.clone().readUtf8()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun result(req: RecordedRequest, resultJson: String): MockResponse {
        val id = bodyOf(req).opt("id")
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""")
    }

    private fun error(req: RecordedRequest, message: String): MockResponse {
        val id = bodyOf(req).opt("id")
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"$message"}}""")
    }

    private val initJson =
        """{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}"""

    private val toolOne =
        """{"name":"echo","description":"回显","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]},"annotations":{"readOnlyHint":true}}"""

    /** 标准 JSON 传输：initialize / initialized / list / call。 */
    private fun jsonDispatcher(
        listBody: String = """{"tools":[$toolOne]}""",
        callResponse: (RecordedRequest) -> MockResponse = { req ->
            result(req, """{"content":[{"type":"text","text":"pong"}],"isError":false}""")
        },
    ) = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            seen.add(req)
            val method = bodyOf(req).optString("method")
            return when {
                method == "initialize" -> result(req, initJson)
                    .setHeader("Mcp-Session-Id", "session-1")
                method == "notifications/initialized" -> MockResponse().setResponseCode(202)
                method == "tools/list" -> result(req, listBody)
                method == "tools/call" -> callResponse(req)
                else -> MockResponse().setResponseCode(400)
            }
        }
    }

    // ── Streamable HTTP ─────────────────────────────────────────────────

    @Test
    fun `连接_列表_调用_JSON`() {
        server.dispatcher = jsonDispatcher()
        val c = client(config())
        c.connect()
        assertEquals("streamable-http", c.mode)
        assertEquals("2025-03-26", c.protocolVersion)

        val tools = c.listTools()
        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].name)
        assertTrue(tools[0].readOnly)

        val out = c.callTool("echo", JSONObject().put("text", "hi"))
        assertEquals("pong", out.text)
        assertFalse(out.isError)

        val posts = seen.filter { it.method == "POST" }
        assertEquals(4, posts.size)
        val init = posts.first { bodyOf(it).optString("method") == "initialize" }
        assertNull("initialize 不带协议版本头", init.getHeader("Mcp-Protocol-Version"))
        assertNull("initialize 不带 session", init.getHeader("Mcp-Session-Id"))

        val list = posts.first { bodyOf(it).optString("method") == "tools/list" }
        assertEquals("2025-03-26", list.getHeader("Mcp-Protocol-Version"))
        assertEquals("session-1", list.getHeader("Mcp-Session-Id"))

        val call = posts.first { bodyOf(it).optString("method") == "tools/call" }
        val params = bodyOf(call).getJSONObject("params")
        assertEquals("echo", params.getString("name"))
        assertEquals("hi", params.getJSONObject("arguments").getString("text"))
    }

    @Test
    fun `列表_SSE 响应_跳过通知与服务器请求`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                seen.add(req)
                val body = bodyOf(req)
                return when (body.optString("method")) {
                    "initialize" -> result(req, initJson)
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> {
                        val id = body.opt("id")
                        MockResponse()
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody(
                                "event: message\n" +
                                    "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
                                    "event: message\n" +
                                    "data: {\"jsonrpc\":\"2.0\",\"id\":\"srv-9\",\"method\":\"sampling/createMessage\",\"params\":{}}\n\n" +
                                    "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"tools\":[$toolOne]}}\n\n",
                            )
                    }
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        val c = client(config())
        c.connect()
        val tools = c.listTools()
        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].name)
    }

    @Test
    fun `旧式 SSE_404 后回退`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                seen.add(req)
                if (req.method == "GET") {
                    // Streamable 的第一次 initialize 已占掉 id=1（即使 404 也先取号），
                    // 旧 SSE 的 initialize 从 id=2 起。这里把 1..4 两套都摆上，客户端只认
                    // 自己发出去的那个 id——也顺带证明「不匹配的 id 会被跳过」。
                    return MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(
                            "event: endpoint\ndata: /messages\n\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":$initJson}\n\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":$initJson}\n\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[$toolOne]}}\n\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[$toolOne]}}\n\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"legacy-pong\"}]}}\n\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"legacy-pong\"}]}}\n\n",
                        )
                }
                if (req.method == "POST" && req.path?.startsWith("/messages") == true) {
                    return MockResponse().setResponseCode(202)
                }
                val method = bodyOf(req).optString("method")
                return if (method == "initialize") {
                    MockResponse().setResponseCode(404)
                } else {
                    MockResponse().setResponseCode(400)
                }
            }
        }
        val c = client(config())
        c.connect()
        assertEquals("legacy-sse", c.mode)
        assertEquals(1, c.listTools().size)
        assertEquals("legacy-pong", c.callTool("echo", JSONObject()).text)
        assertTrue(seen.any { it.method == "GET" })
        assertTrue(seen.any { it.method == "POST" && it.path?.startsWith("/messages") == true })
    }

    // ── 失败路径 ────────────────────────────────────────────────────────

    @Test
    fun `连接超时_可读_TIMEOUT`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"jsonrpc":"2.0","id":1,"result":$initJson}""")
                    .setBodyDelay(2, TimeUnit.SECONDS)
        }
        val c = client(config(connectTimeoutMs = 500))
        try {
            c.connect()
            fail("应当超时")
        } catch (e: McpException) {
            assertEquals(McpFailure.TIMEOUT, e.failure)
            assertTrue(e.userMessage.contains("超时"))
            assertTrue(e.userMessage.contains("假 server"))
        }
    }

    @Test
    fun `调用超时_可读_TIMEOUT`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                when (bodyOf(req).optString("method")) {
                    "initialize" -> result(req, initJson)
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/call" -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    else -> MockResponse().setResponseCode(400)
                }
        }
        val c = client(config(callTimeoutMs = 500))
        c.connect()
        try {
            c.callTool("echo", JSONObject())
            fail("应当超时")
        } catch (e: McpException) {
            assertEquals(McpFailure.TIMEOUT, e.failure)
            assertTrue("要说是哪一步、哪台 server：${e.userMessage}", e.userMessage.contains("调用工具"))
            assertTrue(e.userMessage.contains("超时"))
            assertTrue(e.userMessage.contains("假 server"))
        }
    }

    @Test
    fun `断连_可读_CONNECT`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        }
        val c = client(config())
        try {
            c.connect()
            fail("应当断连")
        } catch (e: McpException) {
            assertEquals(McpFailure.CONNECT, e.failure)
            // 人话里要点名「谁、哪一步、怎么了」，不含 URL/请求头
            assertTrue(e.userMessage.contains("MCP 服务器"))
            assertTrue(e.userMessage.contains("没有返回响应"))
        }
    }

    @Test
    fun `401_认证失败_不回退旧 SSE`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                seen.add(req)
                return MockResponse().setResponseCode(401)
            }
        }
        val c = client(config(headers = mapOf("Authorization" to "Bearer SECRET")))
        try {
            c.connect()
            fail("应当认证失败")
        } catch (e: McpException) {
            assertEquals(McpFailure.AUTH, e.failure)
            assertTrue(e.userMessage.contains("认证失败"))
            assertFalse("错误原文不带密钥", e.userMessage.contains("SECRET"))
        }
        assertFalse("401 不许回退", seen.any { it.method == "GET" })
    }

    @Test
    fun `JSON-RPC_error_可读_TOOL`() {
        server.dispatcher = jsonDispatcher(callResponse = { req -> error(req, "remote boom") })
        val c = client(config())
        c.connect()
        try {
            c.callTool("echo", JSONObject())
            fail("应当抛错")
        } catch (e: McpException) {
            assertEquals(McpFailure.TOOL, e.failure)
            assertTrue(e.userMessage.contains("remote boom"))
        }
    }

    @Test
    fun `isError_原样交回`() {
        server.dispatcher = jsonDispatcher(
            callResponse = { req ->
                result(req, """{"content":[{"type":"text","text":"server failed"}],"isError":true}""")
            },
        )
        val c = client(config())
        c.connect()
        val out = c.callTool("echo", JSONObject())
        assertTrue(out.isError)
        assertEquals("server failed", out.text)
    }

    @Test
    fun `非法 schema_整 server 拒收`() {
        server.dispatcher = jsonDispatcher(
            listBody = """{"tools":[{"name":"bad","inputSchema":{"type":"object","properties":{"x":{"${'$'}ref":"#/x"}}}}]}""",
        )
        val c = client(config())
        c.connect()
        try {
            c.listTools()
            fail("应当拒收")
        } catch (e: McpException) {
            assertEquals(McpFailure.SCHEMA, e.failure)
        }
    }

    @Test
    fun `非法 session 头_协议错误`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                result(req, initJson).setHeader("Mcp-Session-Id", "bad value")
        }
        val c = client(config())
        try {
            c.connect()
            fail("非法 session 应当拒")
        } catch (e: McpException) {
            assertEquals(McpFailure.PROTOCOL, e.failure)
        }
    }

    @Test
    fun `重定向_不跟随_认证头不带到第三方`() {
        val other = MockWebServer()
        other.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(req: RecordedRequest): MockResponse =
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", other.url("/steal").toString())
            }
            val cfg = config(headers = mapOf("Authorization" to "Bearer SECRET"))
            // 用生产同款 client（关掉 followRedirects）；测试里裸 OkHttpClient() 会跟随。
            val c = McpClient(cfg, McpClient.defaultHttp(cfg))
            try {
                c.connect()
                fail("应当把 3xx 当 HTTP 失败，而不是跟过去")
            } catch (e: McpException) {
                assertEquals(McpFailure.HTTP, e.failure)
                assertFalse("错误文案不许带认证头", e.userMessage.contains("SECRET"))
            }
            assertEquals("第三方一个请求都不许收到", 0, other.requestCount)
        } finally {
            runCatching { other.shutdown() }
        }
    }

    @Test
    fun `响应体超过 4 MB_边读边停_不做解析`() {
        // chunked：没有 Content-Length，只有真·边读边数才能拦住（旧实现先 bytes() 再检查）。
        val huge = "x".repeat(4 * 1024 * 1024 + 1024)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse =
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setChunkedBody("""{"jsonrpc":"2.0","id":1,"result":{"junk":"$huge"}}""", 8 * 1024)
        }
        val c = client(config())
        try {
            c.connect()
            fail("应当中止")
        } catch (e: McpException) {
            assertEquals(McpFailure.PROTOCOL, e.failure)
            assertTrue(e.userMessage.contains("4 MB"))
        }
    }

    @Test
    fun `日志与错误_不带请求头密钥`() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = jsonDispatcher()
        val c = client(
            config(headers = mapOf("Authorization" to "Bearer SECRET"), query = "?token=SECRET"),
            log = { logs.add(it) },
        )
        c.connect()
        c.listTools()
        c.callTool("echo", JSONObject())
        assertFalse("日志不含密钥", logs.any { it.contains("SECRET") })
        assertFalse("日志不含 URL query", logs.any { it.contains("token=") })
    }
}
