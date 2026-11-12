// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百零六批 · MCP 探针的**账本级**判据测试（12 键按 602 应归类落账；PeerFailureCode 闭集不增键）。
//
// 判据（派工单 606 ①②③④）：
// · 404→endpoint；418/405→protocol；400 点名 version→version；500→network（既有）；
// · 404 回退后原分类留在账上（两路都失败）；
// · 握手缺 capabilities → 账行 protocol，不冒充「ok + 0 工具」；
// · 非 ASCII 凭据 → 账行 config，requestCount==0；
// · initialized 通知被拒：仍 ok，但账行写 initialized=rejected（吞法 D 不再只落日志）。
// 靶子：裸 MockWebServer + 租约闸（发探前授权，与 548 同款）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.mcp.McpServerConfig
import com.example.zhengdao.baopu.mcp.McpTokenProvider
import com.example.zhengdao.testkit.TempSandboxPool
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpPeerProbeGapsTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        root = TempSandboxPool.create("peer606")
        AgentRegistry.installRoot { root }
        PeerEventsLedger.installRoot { root }
        LeaseLedger.installRoot { root }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        LeaseStore.resetForTest()
        AgentRegistry.resetForTest()
        PeerEventsLedger.resetForTest()
        TempSandboxPool.clean()
    }

    private fun mcpUrl() = "http://127.0.0.1:${server.port}/mcp"

    private fun config() = McpServerConfig(
        id = "srv606",
        name = "606探针靶",
        url = mcpUrl(),
        connectTimeoutMs = 5_000,
        callTimeoutMs = 5_000,
    )

    private fun http(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun probe(
        cfg: McpServerConfig = config(),
        tokenProvider: McpTokenProvider? = null,
    ): PeerProbeOutcome {
        LeaseOutboundGate.grantProbeLease(cfg.url, McpPeerProbe.SESSION_MANUAL, "test-606", NOW)
        return McpPeerProbe(http()).probe(cfg, root = root, tokenProvider = tokenProvider, nowMs = NOW)
    }

    private fun probeRows(): List<JSONObject> =
        PeerEventsLedger.replay().filter { it.optString("kind") == PeerEventKinds.PROBE }

    private fun bodyOf(req: RecordedRequest): JSONObject {
        val text = req.body?.utf8().orEmpty()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun okResult(req: RecordedRequest, resultJson: String): MockResponse {
        val id = bodyOf(req).opt("id")
        return MockResponse.Builder()
            .setHeader("Content-Type", "application/json")
            .body("""{"jsonrpc":"2.0","id":$id,"result":$resultJson}""").build()
    }

    private val initOk =
        """{"protocolVersion":"2026-07-28","capabilities":{"tools":{}},""" +
            """"serverInfo":{"name":"fake","version":"1"}}"""

    // ── ① 4xx 按序分落账 ────────────────────────────────────────────────

    @Test
    fun `收口十_探针账_404归endpoint_服务在那这条路没有`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                return MockResponse.Builder().code(404).build()
            }
        }
        val out = probe()
        assertEquals("404 该归 ENDPOINT（键已在 main，606 接线）", PeerFailureCode.ENDPOINT, out.code)
        assertEquals("账行按键对账：result=endpoint", "endpoint", probeRows().single().optString("result"))
        assertNull("失败收场不带清单行", out.entry)
        assertTrue("人话点端点：${out.human}", out.human.contains("没有这条接口"))
    }

    @Test
    fun `收口十一_探针账_418归protocol_500归network_两把码不混`() {
        listOf(
            418 to PeerFailureCode.PROTOCOL,
            500 to PeerFailureCode.NETWORK,
        ).forEach { (code, expected) ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(req: RecordedRequest): MockResponse {
                    return MockResponse.Builder().code(code).build()
                }
            }
            val out = probe()
            assertEquals("HTTP $code 该归 $expected", expected, out.code)
            // replay() 新行在前：最新一探的账行是 first()。
            assertEquals("账行 result=${expected.wire}", expected.wire, probeRows().first().optString("result"))
        }
    }

    @Test
    fun `收口十二_探针账_400点名version归version`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                return MockResponse.Builder()
                    .code(400)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-32002,""" +
                            """"message":"Unsupported protocol version","data":{"supported":["2025-03-26"]}}}""",
                    ).build()
            }
        }
        val out = probe()
        assertEquals(PeerFailureCode.VERSION_MISMATCH, out.code)
        assertEquals("账行 result=version", "version", probeRows().single().optString("result"))
    }

    // ── ② 回退留账 ──────────────────────────────────────────────────────

    @Test
    fun `收口十三_回退后原404分类留在账上_不被legacy的500顶掉`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                return when {
                    bodyOf(req).optString("method") == "initialize" -> MockResponse.Builder().code(404).build()
                    req.method == "GET" -> MockResponse.Builder().code(500).build()
                    else -> MockResponse.Builder().code(400).build()
                }
            }
        }
        val out = probe()
        assertEquals("两路都失败仍按原 404 收：endpoint", PeerFailureCode.ENDPOINT, out.code)
        assertEquals("endpoint", probeRows().single().optString("result"))
    }

    // ── ③ 握手缺 capabilities ───────────────────────────────────────────

    @Test
    fun `收口十四_探针缺capabilities_落protocol账_零工具不冒充OK`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                val method = bodyOf(req).optString("method")
                return when (method) {
                    "initialize" -> okResult(
                        req,
                        """{"protocolVersion":"2026-07-28","serverInfo":{"name":"mute","version":"1"}}""",
                    )
                    else -> MockResponse.Builder().code(400).build()
                }
            }
        }
        val out = probe()
        assertEquals("缺 capabilities 是形状坏（protocol），不是 ok+0 工具", PeerFailureCode.PROTOCOL, out.code)
        assertEquals("protocol", probeRows().single().optString("result"))
        assertNull(out.entry)
    }

    @Test
    fun `收口十五_initialized被拒_仍OK_但账行写rejected`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                return when (bodyOf(req).optString("method")) {
                    "initialize" -> okResult(req, initOk)
                    "notifications/initialized" -> MockResponse.Builder().code(500).build()
                    "tools/list" -> okResult(req, """{"tools":[]}""")
                    else -> MockResponse.Builder().code(400).build()
                }
            }
        }
        val out = probe()
        assertEquals("通知被拒不阻断连接（215 设计）", PeerFailureCode.OK, out.code)
        val row = probeRows().single()
        assertEquals("ok", row.optString("result"))
        assertTrue("账行要看得见握手没走完：${row.optString("detail")}", row.optString("detail").contains("initialized=rejected"))
    }

    // ── ④ 非 ASCII 凭据 ─────────────────────────────────────────────────

    @Test
    fun `收口十六_探针非ASCII凭据_归config账_一个字节不发`() {
        // 不投喂任何响应：请求根本不该出发。
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = MockResponse.Builder().code(200).build()
        }
        val out = probe(
            tokenProvider = object : McpTokenProvider {
                override fun accessToken(): String = "中文token-不合法"
                override fun refreshAfterUnauthorized(): String? = null
            },
        )
        assertEquals("本机配置问题归 CONFIG（606⑤后不再压平）", PeerFailureCode.CONFIG, out.code)
        assertEquals("账行 result=config", "config", probeRows().single().optString("result"))
        assertTrue("人话指路：${out.human}", out.human.contains("ASCII"))
        assertEquals("发出前拦：探针一个请求都没发", 0, server.requestCount)
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
