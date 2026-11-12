// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百零六批 · MCP 失败码收口的客户端判据测试（按 602 盘点落点 ①②③④）。
//
// 判据（派工单 606）：
// · ① 4xx 按序分——404→ENDPOINT；405/415 与其余 4xx→PROTOCOL；400/422 点名 version→
//   VERSION_MISMATCH；5xx/3xx→HTTP（账上 network，见映射测试）；401/403→AUTH 族（既有）；
// · ② 404 不当「换传输」开关——两路都失败时原分类留在账上（唯一例外：legacy 要授权）；
// · ③ 握手缺 capabilities ⇒ PROTOCOL，不冒充「成功 0 工具」；initialized 通知被拒不静默；
// · ④ 非 ASCII 凭据**发出前拦**——归 CONFIG，requestCount==0（三通道同尺见
//   McpAuthFlow.tokenShapeRejection / McpClipboardToken）。
// 靶子：裸 MockWebServer（按法投喂）。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import java.net.InetAddress
import java.util.Collections
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class McpClientGapsTest {

    private lateinit var server: MockWebServer
    private val seen = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun bodyOf(req: RecordedRequest): JSONObject {
        val text = req.body?.utf8().orEmpty()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun result(req: RecordedRequest, resultJson: String): MockResponse {
        val id = bodyOf(req).opt("id")
        return MockResponse.Builder()
            .setHeader("Content-Type", "application/json")
            .body("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""").build()
    }

    private fun config() = McpServerConfig(
        id = "srv606",
        name = "606靶",
        url = "http://127.0.0.1:${server.port}/mcp",
    )

    /** 生产同款：不跟重定向（3xx 用例必须用它）。 */
    private fun http(): OkHttpClient = McpClient.defaultHttp(config())

    private fun client(token: String? = null): McpClient = McpClient(
        config(),
        http(),
        tokenProvider = token?.let { t ->
            object : McpTokenProvider {
                override fun accessToken(): String? = t
                override fun refreshAfterUnauthorized(): String? = null
            }
        },
    )

    private fun failureOf(block: () -> Unit): McpException {
        val e = runCatching(block).exceptionOrNull()
        return e as? McpException ?: throw AssertionError("该是 McpException，来的是：$e", e)
    }

    /** initialize 给固定状态码；其余方法按常规答（GET 落 400——覆盖回退二次失败的靶面）。 */
    private fun initDispatcher(codeForInit: Int) = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            seen.add(req)
            return if (bodyOf(req).optString("method") == "initialize") {
                MockResponse.Builder().code(codeForInit).build()
            } else {
                MockResponse.Builder().code(400).build()
            }
        }
    }

    private fun initResultDispatcher(initResult: String) = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            seen.add(req)
            val method = bodyOf(req).optString("method")
            return when {
                method == "initialize" -> result(req, initResult)
                method == "notifications/initialized" -> MockResponse.Builder().code(202).build()
                else -> MockResponse.Builder().code(400).build()
            }
        }
    }

    // ── ① 4xx 按序分 ────────────────────────────────────────────────────

    @Test
    fun `收口一_4xx按序分_404归ENDPOINT_405与其余4xx归PROTOCOL_5xx与3xx归HTTP`() {
        listOf(
            404 to McpFailure.ENDPOINT,
            405 to McpFailure.PROTOCOL,
            415 to McpFailure.PROTOCOL,
            418 to McpFailure.PROTOCOL,
            429 to McpFailure.PROTOCOL,
            500 to McpFailure.HTTP,
        ).forEach { (code, expected) ->
            server.dispatcher = initDispatcher(code)
            val e = failureOf { client().connect() }
            assertEquals("HTTP $code 该归 $expected", expected, e.failure)
        }
        // 3xx：不跟重定向，按普通 HTTP 失败收（账上经 PeerFailureClassify 归 network）。
        server.dispatcher = initDispatcher(302)
        assertEquals(McpFailure.HTTP, failureOf { client().connect() }.failure)
    }

    @Test
    fun `收口一b_400与422点名version归VERSION_MISMATCH_没点名归PROTOCOL`() {
        val versionBody =
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32002,"message":"Unsupported protocol version",""" +
                """"data":{"supported":["2025-03-26"]}}}"""
        listOf(400, 422).forEach { code ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(req: RecordedRequest): MockResponse {
                    seen.add(req)
                    return if (bodyOf(req).optString("method") == "initialize") {
                        MockResponse.Builder()
                            .code(code)
                            .setHeader("Content-Type", "application/json")
                            .body(versionBody).build()
                    } else {
                        MockResponse.Builder().code(400).build()
                    }
                }
            }
            val e = failureOf { client().connect() }
            assertEquals("HTTP $code＋体点名 version ⇒ 版本不符", McpFailure.VERSION_MISMATCH, e.failure)
            assertTrue("人话要提版本：${e.userMessage}", e.userMessage.contains("版本"))
        }
        // 同码不同体：400 但没点 version ⇒ PROTOCOL（不许把一切 400 都说成版本问题）。
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                seen.add(req)
                return if (bodyOf(req).optString("method") == "initialize") {
                    MockResponse.Builder()
                        .code(400)
                        .setHeader("Content-Type", "application/json")
                        .body("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"bad request"}}""")
                        .build()
                } else {
                    MockResponse.Builder().code(400).build()
                }
            }
        }
        assertEquals("400 没点名 version ⇒ 协议不符", McpFailure.PROTOCOL, failureOf { client().connect() }.failure)
    }

    // ── ② 回退不许吞原分类 ──────────────────────────────────────────────

    @Test
    fun `收口二_两路都失败_原404分类留在账上_不再被回退吞掉`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                seen.add(req)
                return when {
                    bodyOf(req).optString("method") == "initialize" -> MockResponse.Builder().code(404).build()
                    req.method == "GET" -> MockResponse.Builder().code(500).build()
                    else -> MockResponse.Builder().code(400).build()
                }
            }
        }
        val e = failureOf { client().connect() }
        assertEquals("404 的 ENDPOINT 留账，不被 legacy 那一发的 500 顶掉", McpFailure.ENDPOINT, e.failure)
        assertEquals("streamable 与 legacy 各试了一发", 2, seen.size)
    }

    @Test
    fun `收口二b_回退后legacy要授权_AUTH不丢_授权流程能接手`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                seen.add(req)
                return when {
                    bodyOf(req).optString("method") == "initialize" -> MockResponse.Builder().code(404).build()
                    req.method == "GET" -> MockResponse.Builder().code(401).build()
                    else -> MockResponse.Builder().code(400).build()
                }
            }
        }
        val e = failureOf { client().connect() }
        assertEquals("找到真传输但要授权：AUTH 族优先（转授权是进度，不是吞没）", McpFailure.AUTH_REQUIRED, e.failure)
    }

    // ── ③ 握手能力面 ────────────────────────────────────────────────────

    @Test
    fun `收口三_回声版本不符归VERSION_MISMATCH_不再借宿PROTOCOL`() {
        server.dispatcher = initResultDispatcher(
            """{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"old","version":"1"}}""",
        )
        val e = failureOf { client().connect() }
        assertEquals(McpFailure.VERSION_MISMATCH, e.failure)
        assertTrue(e.userMessage.contains("只支持旧协议"))
    }

    @Test
    fun `收口四_握手缺capabilities归PROTOCOL_不冒充成功零工具`() {
        server.dispatcher = initResultDispatcher(
            """{"protocolVersion":"2026-07-28","serverInfo":{"name":"mute","version":"1"}}""",
        )
        val e = failureOf { client().connect() }
        assertEquals("形状不符是协议错，不是「没声明能力」", McpFailure.PROTOCOL, e.failure)
        assertTrue("点名 capabilities：${e.userMessage}", e.userMessage.contains("capabilities"))
        assertEquals("明确失败：initialize 之后不再发任何请求", 1, seen.size)
    }

    @Test
    fun `收口四b_initialized通知被拒_留记录_不再只落日志`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                seen.add(req)
                return when (bodyOf(req).optString("method")) {
                    "initialize" -> result(
                        req,
                        """{"protocolVersion":"2026-07-28","capabilities":{"tools":{}},""" +
                            """"serverInfo":{"name":"fake","version":"1"}}""",
                    )
                    "notifications/initialized" -> MockResponse.Builder().code(500).build()
                    else -> result(req, """{"tools":[]}""")
                }
            }
        }
        val c = client()
        c.connect()
        assertNotNull("通知被拒要有记录（探针账行据此写 initialized=rejected）", c.initializedNotice)
        assertTrue(c.initializedNotice!!.contains("initialized"))
    }

    // ── ④ 非 ASCII 凭据发出前拦 ─────────────────────────────────────────

    @Test
    fun `收口五_非ASCII凭据发前拦_零请求归CONFIG_人话指路`() {
        server.dispatcher = initDispatcher(200) // 不该被摸到
        val e = failureOf { client(token = "中文token-不合法").connect() }
        assertEquals("本机配置问题归 CONFIG", McpFailure.CONFIG, e.failure)
        assertTrue("人话指路换 token：${e.userMessage}", e.userMessage.contains("ASCII"))
        assertEquals("发出前拦：一个字节都不发", 0, server.requestCount)

        // 负例：ASCII token 照常握手——拦的是非 ASCII，不是一刀切。
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                seen.add(req)
                return when (bodyOf(req).optString("method")) {
                    "initialize" -> result(
                        req,
                        """{"protocolVersion":"2026-07-28","capabilities":{},""" +
                            """"serverInfo":{"name":"fake","version":"1"}}""",
                    )
                    "notifications/initialized" -> MockResponse.Builder().code(202).build()
                    else -> result(req, """{"tools":[]}""")
                }
            }
        }
        client(token = "sk-ok-606").connect()
        assertTrue("ASCII token 照常发出", server.requestCount >= 1)
    }

    // ── 无响应的归属（602 行 25；按 606 派单①口径归 network） ────────────

    @Test
    fun `收口六_空响应体归CONNECT_账上network_可重连`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                seen.add(req)
                return if (bodyOf(req).optString("method") == "initialize") {
                    MockResponse.Builder().setHeader("Content-Type", "application/json").body("").build()
                } else {
                    MockResponse.Builder().code(202).build()
                }
            }
        }
        val e = failureOf { client().connect() }
        assertEquals("无响应按 606①口径归 network（McpFailure.CONNECT；无 cause ⇒ 账上 network）", McpFailure.CONNECT, e.failure)
    }

    // ── 三通道同一把尺（剪贴板形状闸复用同尺，此处钉 mcp_set_token 通道） ──

    @Test
    fun `收口五b_三通道同一把尺_非ASCII在入库前就拒`() {
        // mcp_set_token 通道（importToken）：中文 token 拒收、不进保险柜。
        val vault = McpTokenVault(InMemoryMcpSecretStore())
        val flow = McpAuthFlow(vault, McpOAuthClient(OkHttpClient()), log = {})
        val cfg = config()
        assertTrue("中文 token 拒收", !flow.importToken(cfg, "中文token-不合法"))
        assertTrue("可见 ASCII 收", flow.importToken(cfg, "sk-ok-606"))
        // 同一把尺的判词：剪贴板闸（visibleAsciiRun）已委托 tokenShapeRejection，
        // 此处直接钉尺本身——三通道行为一致性由结构保证（同一函数）。
        assertEquals("空", McpAuthFlow.tokenShapeRejection(""))
        assertEquals("含控制字符或非 ASCII（只收可见 ASCII）", McpAuthFlow.tokenShapeRejection("中文"))
        assertEquals(null, McpAuthFlow.tokenShapeRejection("ok-token-606"))
        // 配置头通道：头名只收 ASCII token 字符（isLetterOrDigit 的 Unicode 语义已收窄）；
        // 头值非 ASCII 构造期就拒——OkHttp 的 IAE 前移到本机，请求一个字节不发。
        assertTrue("中文头名拒", !McpUrlRules.isHeaderName("中文头"))
        assertTrue("ASCII 头名收", McpUrlRules.isHeaderName("X-Client"))
        val badValue = runCatching {
            McpServerConfig(id = "hd606", name = "头靶", url = "http://127.0.0.1:1/mcp", headers = mapOf("X-Title" to "中文值"))
        }.exceptionOrNull()
        assertTrue("头值非 ASCII 构造期拒", badValue is IllegalArgumentException)
        assertTrue("判词点名非 ASCII：$badValue", badValue!!.message!!.contains("非 ASCII"))
    }

    // ── 生产连接链的机器键（602 行 22/21/16：Failed 不再无机键） ─────────

    @Test
    fun `收口十九_生产连接失败带分类_404带ENDPOINT_配置IAE带CONFIG`() {
        server.dispatcher = initDispatcher(404)
        val source = McpToolSource(ToolRegistry(), clientFactory = { cfg -> McpClient(cfg, http()) })
        source.addServer(config())
        val out = source.connect("srv606")
        assertTrue("404 该是 Failed", out is McpConnectOutcome.Failed)
        assertEquals("Failed 带 ENDPOINT（606①）", McpFailure.ENDPOINT, (out as McpConnectOutcome.Failed).failure)

        val broken = McpToolSource(
            ToolRegistry(),
            clientFactory = { throw IllegalArgumentException("bad config") },
        )
        broken.addServer(McpServerConfig(id = "srvbad", name = "坏靶", url = "http://127.0.0.1:1/mcp"))
        val out2 = broken.connect("srvbad")
        assertTrue(out2 is McpConnectOutcome.Failed)
        assertEquals("配置构造不过检带 CONFIG（602 行 16）", McpFailure.CONFIG, (out2 as McpConnectOutcome.Failed).failure)
    }

    @Test
    fun `收口二十_刷新失败带分类_保留旧清单`() {
        val boom = RefreshBoom()
        val source = McpToolSource(ToolRegistry(), clientFactory = { boom })
        source.addServer(config())
        assertTrue(source.connect("srv606") is McpConnectOutcome.Ok)
        assertEquals(1, source.toolCount("srv606"))
        val second = source.connect("srv606")
        assertTrue("刷新失败该是 Failed", second is McpConnectOutcome.Failed)
        assertEquals("刷新失败也带分类（602 行 21）", McpFailure.ENDPOINT, (second as McpConnectOutcome.Failed).failure)
        assertEquals("旧清单保留（半拆比旧清单更糟）", 1, source.toolCount("srv606"))
    }

    @Test
    fun `收口二十一_runAdd地址不许可_本机配置_未发请求`() {
        val source = McpToolSource(ToolRegistry())
        McpSelfService.env = McpSelfService.Env(
            source = { source },
            auth = { null },
            browser = { true },
            log = {},
            now = { 0L },
        )
        val out = McpSelfService.runAdd("http://example.com/mcp", "")
        assertTrue(out is ToolOutcome.Failed)
        assertTrue("点名地址不许可：${(out as ToolOutcome.Failed).error}", out.error.contains("地址不许可"))
        assertEquals("配置拒收：一个请求都不发", 0, server.requestCount)
    }

    @After
    fun resetSelfServiceEnv() {
        McpSelfService.env = null
        McpSelfService.popupLedger.forgetAllForTest()
    }

    /** 首次 listTools 成功、之后抛 ENDPOINT 的假会话（刷新路径靶；刷新不再 connect）。 */
    private class RefreshBoom : McpSession {
        var lists = 0
        override val mode = "streamable-http"
        override val protocolVersion: String = McpJsonRpc.PROTOCOL
        override fun connect() = Unit
        override fun listTools(): List<McpToolDescriptor> {
            lists++
            if (lists == 1) {
                return listOf(
                    McpToolDescriptor(
                        serverId = "srv606",
                        serverName = "606靶",
                        name = "echo",
                        description = "回显",
                        inputSchema = JSONObject("""{"type":"object"}"""),
                        readOnly = true,
                    ),
                )
            }
            throw McpException(McpFailure.ENDPOINT, "列工具 404（靶）")
        }
        override fun callTool(remoteName: String, arguments: JSONObject): McpCallResult = McpCallResult("", false)
        override fun close() = Unit
    }
}
