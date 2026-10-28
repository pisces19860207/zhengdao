// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十五批 · 假服务器抓包（交付物生成器）。
//
// 真跑 `McpClient` + 真 OkHttp + MockWebServer，把「客户端发出的每一个 HTTP 请求
// （方法 / 路径 / 全部请求头 / 正文）」与「服务器回的响应」逐条落成文本，供交付报告引用。
// 两份：
//   app/build/mcp-captures/215-新版成功.txt        —— 只接受 2026-07-28 的服务器
//   app/build/mcp-captures/215-旧服务器明确失败.txt —— 只讲 2025-03-26 的服务器
//
// 打印口径：请求头全量打印（Authorization/Cookie/token 一类会打码——本抓包的配置
// 没有这类头，所以能全量看到版本头/Accept/会话头的真实样子）。
// 每次跑全量单测都会重新生成这两份（落在 build/ 里，不污染仓库）。
package com.example.zhengdao.baopu.mcp

import okhttp3.OkHttpClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.Collections

class McpTransportCaptureTest {

    private lateinit var server: MockWebServer
    private val dump = StringBuilder()
    private var seq = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        dump.setLength(0)
        seq = 0
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun config() = McpServerConfig(
        id = "srv",
        name = "假 server",
        url = "http://127.0.0.1:${server.port}/mcp",
        connectTimeoutMs = 10_000,
        callTimeoutMs = 10_000,
    )

    private fun bodyOf(req: RecordedRequest): JSONObject {
        val text = req.body?.utf8().orEmpty()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private val initJson2026 =
        """{"protocolVersion":"2026-07-28","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}"""

    private val toolOne =
        """{"name":"echo","description":"回显","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]},"annotations":{"readOnlyHint":true}}"""

    /** 请求块（方法 / 路径 / 全量头 / 正文原样）。 */
    private fun recordRequest(req: RecordedRequest): String {
        seq++
        val sb = StringBuilder()
        sb.append("── 请求 #$seq ").append("─".repeat(52)).append('\n')
        sb.append(req.method).append(' ').append(req.target).append(" HTTP/1.1\n")
        for (name in req.headers.names()) {
            val value = req.headers[name].orEmpty()
            val masked = name.lowercase().let {
                it.contains("authorization") || it.contains("cookie") ||
                    it.contains("token") || it.contains("key")
            }
            sb.append(name).append(": ").append(if (masked) "***" else value).append('\n')
        }
        if (req.headers["Mcp-Session-Id"] == null) sb.append("（无 Mcp-Session-Id 头）\n")
        val body = req.body?.utf8().orEmpty()
        sb.append("正文：").append(if (body.isBlank()) "（空）" else body).append("\n\n")
        dump.append(sb)
        return sb.toString()
    }

    /** 响应块（状态行 / 全量头 / 正文原样）。 */
    private fun recordResponse(status: Int, headers: List<Pair<String, String>>, body: String) {
        val sb = StringBuilder()
        sb.append("── 响应 #$seq ").append("─".repeat(52)).append('\n')
        sb.append("HTTP/1.1 ").append(status).append('\n')
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append('\n') }
        sb.append("正文：").append(if (body.isBlank()) "（空）" else body).append("\n\n")
        dump.append(sb)
    }

    /** 记一笔响应并真的回它（避免去反射 MockResponse 内部状态）。 */
    private fun send(code: Int, contentType: String?, body: String = ""): MockResponse {
        val headers = if (contentType != null) listOf("Content-Type" to contentType) else emptyList()
        recordResponse(code, headers, body)
        val r = MockResponse.Builder().code(code)
        if (contentType != null) r.setHeader("Content-Type", contentType)
        if (body.isNotEmpty()) r.body(body)
        return r.build()
    }

    private fun resultBody(req: RecordedRequest, resultJson: String): String =
        """{"jsonrpc":"2.0","id":${bodyOf(req).opt("id")},"result":$resultJson}"""

    private fun write(name: String) {
        val dir = File("build/mcp-captures")
        dir.mkdirs()
        val f = File(dir, name)
        f.writeText(dump.toString(), Charsets.UTF_8)
        assertTrue("抓包必须落盘：${f.absolutePath}", f.isFile && f.length() > 0)
    }

    // ── 抓包一：新版服务器（只接受 2026-07-28）⇒ 协商成功 ──────────────

    @Test
    fun `抓包_新版服务器_协商成功`() {
        dump.append(
            """
            |证道 · 第二百一十五批（MCP 传输校正）· 抓包一：新版服务器协商成功
            |============================================================
            |客户端：证道 McpClient（PROTOCOL = ${McpJsonRpc.PROTOCOL}），真实 OkHttp 4.12
            |服务器：假服务器（MockWebServer），规则 =
            |  · 只接受 2026-07-28：每个 POST 都要求 MCP-Protocol-Version 头 = 2026-07-28
            |    且 body params._meta.protocolVersion = 2026-07-28，否则 400 HeaderMismatch；
            |  · GET / DELETE 一律 405（2026-07-28 已删除协议级会话，这是正规行为）；
            |  · 全程不回 Mcp-Session-Id（证明客户端不依赖会话也能跑完）。
            |配置：无自定义请求头（headers = 空），所以下面看到的头都是客户端自己发的。
            |
            |
            """.trimMargin() + "\n",
        )

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                recordRequest(req)
                if (req.method == "GET" || req.method == "DELETE") {
                    return send(405, null)
                }
                val body = bodyOf(req)
                val headerVersion = req.headers["MCP-Protocol-Version"]
                val metaVersion = body.optJSONObject("params")
                    ?.optJSONObject("_meta")
                    ?.optString("protocolVersion")
                if (headerVersion != McpJsonRpc.PROTOCOL || metaVersion != McpJsonRpc.PROTOCOL) {
                    return send(
                        400,
                        "application/json",
                        """{"jsonrpc":"2.0","id":${body.opt("id")},"error":{"code":-32000,"message":"HeaderMismatch"}}""",
                    )
                }
                return when (body.optString("method")) {
                    "initialize" -> send(200, "application/json", resultBody(req, initJson2026))
                    "notifications/initialized" -> send(202, null)
                    "tools/list" -> send(200, "application/json", resultBody(req, """{"tools":[$toolOne]}"""))
                    "tools/call" -> send(
                        200,
                        "application/json",
                        resultBody(req, """{"content":[{"type":"text","text":"pong"}],"isError":false}"""),
                    )
                    else -> send(400, null)
                }
            }
        }

        val logs = Collections.synchronizedList(mutableListOf<String>())
        val c = McpClient(config(), OkHttpClient(), { logs.add(it) })
        c.connect()
        val tools = c.listTools()
        val out = c.callTool("echo", JSONObject().put("text", "hi"))

        dump.append(
            """
            |── 结局 ────────────────────────────────────────────────
            |传输：${c.mode}
            |协商出的协议版本：${c.protocolVersion}
            |tools/list：${tools.size} 个工具（${tools.joinToString { it.name }}）
            |tools/call「echo」：${out.text}（isError=${out.isError}）
            |客户端日志：
            |${logs.joinToString("\n") { "  $it" }}
            |看这里：① 每个 POST 都带 MCP-Protocol-Version: 2026-07-28 与 Accept 双类型；
            |② 每个 POST 的正文里 params._meta.protocolVersion 都等于同一个常量；
            |③ 全程没有 GET/DELETE，也没有任何 Mcp-Session-Id —— 会话真是可选的。
            |
            """.trimMargin(),
        )
        write("215-新版成功.txt")

        assertEquals("streamable-http", c.mode)
        assertEquals("2026-07-28", c.protocolVersion)
        assertEquals(1, tools.size)
        assertEquals("pong", out.text)
    }

    // ── 抓包二：旧服务器（只讲 2025-03-26）⇒ 明确失败 ──────────────────

    @Test
    fun `抓包_旧服务器_明确失败`() {
        dump.append(
            """
            |证道 · 第二百一十五批（MCP 传输校正）· 抓包二：旧服务器明确失败（不降级）
            |============================================================
            |客户端：证道 McpClient（PROTOCOL = ${McpJsonRpc.PROTOCOL}），真实 OkHttp 4.12
            |服务器：假服务器（MockWebServer），规则 =
            |  · 只讲 2025-03-26（和更早的 2025-06-18）：initialize 回 HTTP 400 +
            |    JSON-RPC error「Unsupported protocol version」，data.supported 列出它支持的版本。
            |配置：无自定义请求头。
            |
            |
            """.trimMargin() + "\n",
        )

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                recordRequest(req)
                val err = """{"jsonrpc":"2.0","id":${bodyOf(req).opt("id")},"error":{"code":-32002,"message":"Unsupported protocol version","data":{"supported":["2025-03-26","2025-06-18"]}}}"""
                return send(400, "application/json", err)
            }
        }

        val logs = Collections.synchronizedList(mutableListOf<String>())
        val c = McpClient(config(), OkHttpClient(), { logs.add(it) })
        var failure: String? = null
        try {
            c.connect()
            fail("只讲旧协议的服务器必须明确失败")
        } catch (e: McpException) {
            failure = e.userMessage
        }

        dump.append(
            """
            |── 结局 ────────────────────────────────────────────────
            |连接结果：失败（没有降级、没有继续列工具）
            |连接方式：${c.mode}
            |给用户/UI 的人话：
            |  $failure
            |客户端日志：
            |${logs.joinToString("\n") { "  $it" }}
            |看这里：① 客户端只发了这一个 initialize，就用 2026-07-28 申报（头与 _meta 同源）；
            |② 服务器说它只支持 2025-03-26 / 2025-06-18 —— 这句话被原样带进了人话与日志，
            |   用户知道该升级服务器，而不是对着「连接失败」猜；
            |③ 没有回退旧式 SSE（没有 GET）：降级等于拿旧协议跑，本批明确不做。
            |
            """.trimMargin(),
        )
        write("215-旧服务器明确失败.txt")

        assertTrue("必须有失败人话", failure!!.contains("2025-03-26") && failure.contains("不会自动降到旧版"))
        assertEquals("不降级：没有建立传输", "未连接", c.mode)
    }
}
