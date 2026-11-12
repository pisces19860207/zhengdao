// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百零六批 · OAuth 发现链「四种吞法」之 B 的判据测试（602 落点：McpOAuthClient 350-367/116-119、
// McpAuthFlow 103-107）。
//
// 判据（派工单 606 ③）：OAuth 发现链 DNS/证书/超时是「网络没通到 HTTP」，
// **不得说「不支持 OAuth」**——失败键按因分（NETWORK/TLS/TIMEOUT），不进 unsupported，
// 面板状态不翻「不支持 OAuth」；「该服务器不支持现代授权」只许下给「问到了、确实没有」。
package com.example.zhengdao.baopu.mcp

import java.net.InetAddress
import java.net.ServerSocket
import javax.net.ssl.SSLException
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpOAuthGapsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    private fun mcpUrl() = "http://127.0.0.1:${server.port}/mcp"

    private fun config(url: String = mcpUrl()) = McpServerConfig(
        id = "srv606",
        name = "606授权靶",
        url = url,
    )

    private fun newFlow(): McpAuthFlow =
        McpAuthFlow(McpTokenVault(InMemoryMcpSecretStore()), McpOAuthClient(OkHttpClient()), log = {})

    // ── ③B 传输层故障不说「不支持」 ─────────────────────────────────────

    @Test
    fun `收口七_发现链拒连_不说unsupported_状态不翻不支持OAuth`() {
        // 先占一个端口再放掉 ⇒ 这个端口确定是关的（不猜魔法端口）。
        val port = ServerSocket(0).use { it.localPort }
        val cfg = config("http://127.0.0.1:$port/mcp")
        val flow = newFlow()
        val begin = flow.begin(cfg)
        assertTrue("拒连该是明确失败", begin is McpAuthBegin.Failed)
        val failed = begin as McpAuthBegin.Failed
        assertEquals("拒连归 NETWORK（606 新键），不归「没有元数据」", McpAuthFailure.NETWORK, failed.failure)
        assertFalse(
            "不许下「不支持现代授权」的结论：${failed.message}",
            failed.message.contains("不支持现代授权"),
        )
        assertTrue("人话要指网络：${failed.message}", failed.message.contains("连接失败"))
        assertEquals("状态不得翻成「不支持 OAuth」", McpAuthStatus.UNAUTHORIZED, flow.status(cfg))
    }

    @Test
    fun `收口八_发现链全404_问到了确实没有_才说不支持_照旧`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                return MockResponse.Builder().code(404).build()
            }
        }
        val flow = newFlow()
        val begin = flow.begin(config())
        assertTrue(begin is McpAuthBegin.Failed)
        val failed = begin as McpAuthBegin.Failed
        assertEquals("问到了 HTTP、确实没有元数据 ⇒ 照旧 NO_METADATA", McpAuthFailure.NO_METADATA, failed.failure)
        assertTrue(
            "这时才可以说「不支持」：${failed.message}",
            failed.message.contains("不支持现代授权"),
        )
        assertEquals("真不支持 ⇒ 照旧记 unsupported，面板说真话", McpAuthStatus.UNSUPPORTED, flow.status(config()))
    }

    // ── ③B 传输异常 → 键 的映射（可直测；execute 按它组句） ─────────────

    @Test
    fun `收口九_传输异常映射_超时TLS各归各_都进不了unsupported`() {
        assertEquals(McpAuthFailure.TIMEOUT, McpOAuthClient.transportFailureOf(java.io.InterruptedIOException("t")))
        assertEquals(McpAuthFailure.TLS, McpOAuthClient.transportFailureOf(SSLException("bad cert")))
        assertEquals(McpAuthFailure.NETWORK, McpOAuthClient.transportFailureOf(java.net.ConnectException("refused")))
        assertEquals(
            "本机地址不合法归 BAD_METADATA（形状类，同样不进 unsupported）",
            McpAuthFailure.BAD_METADATA,
            McpOAuthClient.transportFailureOf(IllegalArgumentException("url")),
        )
        // 进 unsupported 的判定面：这四个键都不许被 begin 记成「这台不支持 OAuth」。
        val notUnsupported = setOf(
            McpAuthFailure.NETWORK,
            McpAuthFailure.TLS,
            McpAuthFailure.TIMEOUT,
            McpAuthFailure.BAD_METADATA,
        )
        assertTrue(notUnsupported.none { it == McpAuthFailure.NO_METADATA || it == McpAuthFailure.REGISTRATION_FAILED })
    }
}
