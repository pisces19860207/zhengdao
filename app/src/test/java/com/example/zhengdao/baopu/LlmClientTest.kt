// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：OkHttp MockWebServer（本地环回假服务器，真实走 OkHttp 栈，
// 零外网依赖——与 RootfsDownloaderFallbackTest 同一范式）；OpenAI / Anthropic
// 流式响应的公开文档形态。
package com.example.zhengdao.baopu

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * LlmClient 单测（刀 0 · 2026-10-16）。
 *
 * ## 为什么有这一个文件（对派工单的一处偏差，已报备）
 * 派工单交付物列 3 个测试文件；本文件是第 4 个。理由：SSE 帧解析是**新增逻辑里
 * 风险最高的一块**（协议形状错一个字段就是静默不出字），仓库自己的先例——
 * 解析表抽成纯函数就是为了"在 JVM 单测里逐格钉死"（该先例的原件已随 oc/ 清场
 * 删除，纪律不变）。
 * 不测就交付，等于把风险全额转给刀 1。仍然遵守"只新建、零修改既有文件"。
 *
 * 覆盖：两种协议的请求形状（路径/头/体）、SSE 正例、坏 JSON 不崩、
 * 截断不假装成功、HTTP 错误转失败、自定义头覆盖协议默认头。
 */
class LlmClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        // 显式绑环回 IP（不用默认的 localhost）：本机 localhost 被解析成
        // kubernetes.docker.internal（Docker 宿主别名）——Provider 的 URL 许可判据
        // （只放行 https / 环回）**正确地**拒绝了它。绑 127.0.0.1 才同时满足
        // "测试走环回"与"守卫判据"两边；这也是该判据在测试里第一次抓到真异常。
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(
        protocol: LlmProtocol,
        headers: Map<String, String> = emptyMap(),
        apiKey: String = "k",
    ) = Provider(
        id = "t", label = "t", protocol = protocol,
        // 手工拼字面环回 URL，不用 server.url()：那个 API 报的是 reverse DNS 主机名，
        // 本沙盒里 127.0.0.1 反查 = kubernetes.docker.internal（Docker 宿主别名），
        // 会被 Provider 的 URL 许可判据正确地拒掉。socket 本就绑在 127.0.0.1 上，
        // 拼字面 IP 与它同一目标——确定性在字符串这边，不在 DNS 那边。
        baseUrl = "http://127.0.0.1:${server.port}/v1",
        model = "test-model", apiKey = apiKey, headers = headers,
    )

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    /** 跑一次流式，收到终止事件（Finished/Failed）或超时即返回。 */
    private fun collect(p: Provider, messages: List<LlmMessage>): List<LlmEvent> {
        val events = Collections.synchronizedList(mutableListOf<LlmEvent>())
        val latch = CountDownLatch(1)
        LlmClient(p).streamAsync(messages) { ev ->
            events.add(ev)
            if (ev is LlmEvent.Finished || ev is LlmEvent.Failed) latch.countDown()
        }
        assertTrue("流式 5 秒内没有终止事件（事件：$events）", latch.await(5, TimeUnit.SECONDS))
        return events.toList()
    }

    // ── OpenAI 兼容 ───────────────────────────────────────────────────────

    @Test
    fun `openai兼容_增量文本与完成标记`() {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"，世界\"}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val events = collect(
            provider(LlmProtocol.OPENAI_COMPAT),
            listOf(LlmMessage(LlmMessage.ROLE_USER, "hi")),
        )
        assertEquals(
            listOf(
                LlmEvent.Delta("你好"),
                LlmEvent.Delta("，世界"),
                LlmEvent.Finished("stop"),
            ),
            events,
        )
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer k", req.getHeader("Authorization"))
        assertEquals("text/event-stream", req.getHeader("Accept"))
        assertEquals("identity", req.getHeader("Accept-Encoding"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"role\":\"user\""))
    }

    @Test
    fun `openai兼容_坏JSON不崩_继续收后面的增量`() {
        server.enqueue(
            sse(
                "data: {这不是json\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"还能走\"}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val events = collect(provider(LlmProtocol.OPENAI_COMPAT), listOf(LlmMessage("user", "hi")))
        assertEquals(listOf(LlmEvent.Delta("还能走"), LlmEvent.Finished("stop")), events)
    }

    @Test
    fun `openai兼容_流在完成标记前结束_报截断不报成功`() {
        server.enqueue(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}\n\n"),
        )
        val events = collect(provider(LlmProtocol.OPENAI_COMPAT), listOf(LlmMessage("user", "hi")))
        assertEquals(2, events.size)
        assertTrue(events[0] is LlmEvent.Delta)
        val failed = events[1]
        assertTrue("截断必须报失败：$failed", failed is LlmEvent.Failed)
        assertTrue((failed as LlmEvent.Failed).error.message!!.contains("截断"))
    }

    // ── Anthropic ─────────────────────────────────────────────────────────

    @Test
    fun `anthropic_增量文本与message_stop`() {
        server.enqueue(
            sse(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{}}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"！\"}}\n\n" +
                    "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            ),
        )
        val events = collect(
            provider(LlmProtocol.ANTHROPIC),
            listOf(LlmMessage(LlmMessage.ROLE_USER, "hi")),
        )
        assertEquals(
            listOf(LlmEvent.Delta("你好"), LlmEvent.Delta("！"), LlmEvent.Finished("end_turn")),
            events,
        )
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("k", req.getHeader("x-api-key"))
        assertEquals(LlmWire.ANTHROPIC_VERSION, req.getHeader("anthropic-version"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"max_tokens\":4096"))
    }

    @Test
    fun `anthropic_system角色提到顶层不进messages`() {
        server.enqueue(sse("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
        collect(
            provider(LlmProtocol.ANTHROPIC),
            listOf(
                LlmMessage(LlmMessage.ROLE_SYSTEM, "你是抱朴"),
                LlmMessage(LlmMessage.ROLE_USER, "hi"),
            ),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"system\":\"你是抱朴\""))
        // messages 数组里只许有 user 那条
        val messagesStart = body.indexOf("\"messages\":")
        val messagesPart = body.substring(messagesStart)
        assertTrue(messagesPart.contains("\"role\":\"user\""))
        assertFalse(messagesPart.contains("\"role\":\"system\""))
    }

    // ── max_tokens 进请求体（刀 3 B 段 · 第十四批 J3/J4/J11） ──────────────
    // 病灶：OpenAI 分支根本不发 max_tokens、Anthropic 写死 4096，
    // Provider.maxOutput 只用于内部预算（派工单 §1.2 逐行核过）。

    private fun providerWith(
        protocol: LlmProtocol,
        maxOutput: Int? = null,
    ) = Provider(
        id = "t", label = "t", protocol = protocol,
        baseUrl = "http://127.0.0.1:1/v1", model = "test-model", apiKey = "k",
        maxOutput = maxOutput,
    )

    private fun bodyJson(p: Provider): JSONObject =
        JSONObject(LlmWire.bodyOf(p, listOf(LlmMessage(LlmMessage.ROLE_USER, "hi"))))

    @Test
    fun `J3_显式maxOutput_OpenAI请求体max_tokens等于它`() {
        val body = bodyJson(providerWith(LlmProtocol.OPENAI_COMPAT, maxOutput = 393_216))
        assertEquals(393_216, body.getInt("max_tokens"))
    }

    @Test
    fun `J4_maxOutput缺失_OpenAI回落8192`() {
        val body = bodyJson(providerWith(LlmProtocol.OPENAI_COMPAT))
        assertEquals(8192, body.getInt("max_tokens"))
    }

    @Test
    fun `J11_Anthropic分支也取maxOutput_缺失才回落4096`() {
        assertEquals(
            393_216,
            bodyJson(providerWith(LlmProtocol.ANTHROPIC, maxOutput = 393_216)).getInt("max_tokens"),
        )
        assertEquals(
            4096,
            bodyJson(providerWith(LlmProtocol.ANTHROPIC)).getInt("max_tokens"),
        )
    }

    // ── 失败路径 ──────────────────────────────────────────────────────────

    @Test
    fun `HTTP错误转Failed且原文只进日志`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("服务端炸了"))
        val events = collect(provider(LlmProtocol.OPENAI_COMPAT), listOf(LlmMessage("user", "hi")))
        assertEquals(1, events.size)
        val failed = events[0] as LlmEvent.Failed
        assertTrue(failed.error.message!!.contains("500"))
        // 失败文案不许把响应体原文透出去（只准有 HTTP 码）
        assertFalse(failed.error.message!!.contains("服务端炸了"))
    }

    @Test
    fun `自定义header覆盖协议默认头`() {
        server.enqueue(sse("data: [DONE]\n\n"))
        collect(
            provider(
                LlmProtocol.OPENAI_COMPAT,
                headers = mapOf("Authorization" to "Bearer custom", "X-Trace" to "1"),
            ),
            listOf(LlmMessage("user", "hi")),
        )
        val req = server.takeRequest()
        assertEquals("Bearer custom", req.getHeader("Authorization"))
        assertEquals("1", req.getHeader("X-Trace"))
    }

    @Test
    fun `无key时不发Authorization头`() {
        server.enqueue(sse("data: [DONE]\n\n"))
        collect(provider(LlmProtocol.OPENAI_COMPAT, apiKey = ""), listOf(LlmMessage("user", "hi")))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    // ── LlmWire 纯函数 ────────────────────────────────────────────────────

    @Test
    fun `端点路径容忍尾部斜杠`() {
        assertEquals(
            "https://x/v1/chat/completions",
            LlmWire.endpoint(LlmProtocol.OPENAI_COMPAT, "https://x/v1/"),
        )
        assertEquals("https://x/v1/messages", LlmWire.endpoint(LlmProtocol.ANTHROPIC, "https://x/v1"))
    }

    @Test
    fun `请求头合并顺序_自定义最后且可覆盖`() {
        val p = Provider(
            "t", "t", LlmProtocol.ANTHROPIC, "https://x/v1", "m", apiKey = "k",
            headers = mapOf("x-api-key" to "override"),
        )
        val h = LlmWire.headersOf(p)
        assertEquals("override", h["x-api-key"])
        assertEquals(LlmWire.ANTHROPIC_VERSION, h["anthropic-version"])
        // 免费通道形态：Bearer null 也走同一出口
        val free = LlmWire.headersOf(Providers.BUILTIN_FREE)
        assertEquals("Bearer null", free["Authorization"])
        assertEquals("opencode/1.18.34", free["User-Agent"])
    }

    @Test
    fun `SSE专用头禁压缩`() {
        val h = LlmWire.sseHeaders()
        assertEquals("text/event-stream", h["Accept"])
        assertEquals("identity", h["Accept-Encoding"])
    }

    // ── LlmSse 纯函数 ─────────────────────────────────────────────────────

    @Test
    fun `帧缓冲_多行data用换行合并_注释与event行忽略`() {
        val buf = LlmSse.FrameBuffer()
        assertNull(buf.line(": 心跳注释"))
        assertNull(buf.line("event: content_block_delta"))
        assertNull(buf.line("data: {\"a\":"))
        assertNull(buf.line("data: 1}"))
        assertEquals("{\"a\":\n1}", buf.line(""))
        assertNull(buf.line("")) // 空行且无数据 ⇒ null
    }

    @Test
    fun `终止标记判定`() {
        assertTrue(LlmSse.isTerminator("[DONE]"))
        assertTrue(LlmSse.isTerminator("[DONE]  "))
        assertFalse(LlmSse.isTerminator("[done]"))
        assertFalse(LlmSse.isTerminator("{\"x\":1}"))
    }

    @Test
    fun `增量文本_两种协议各归各位_坏JSON返回null`() {
        assertEquals(
            "abc",
            LlmSse.deltaText(
                LlmProtocol.OPENAI_COMPAT,
                "{\"choices\":[{\"delta\":{\"content\":\"abc\"}}]}",
            ),
        )
        assertNull(LlmSse.deltaText(LlmProtocol.OPENAI_COMPAT, "{\"choices\":[{\"delta\":{}}]}"))
        assertNull(LlmSse.deltaText(LlmProtocol.OPENAI_COMPAT, "{坏"))
        assertEquals(
            "xyz",
            LlmSse.deltaText(
                LlmProtocol.ANTHROPIC,
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"xyz\"}}",
            ),
        )
        // 非 text_delta（比如 thinking delta）不当正文
        assertNull(
            LlmSse.deltaText(
                LlmProtocol.ANTHROPIC,
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"t\"}}",
            ),
        )
        assertNull(
            LlmSse.deltaText(LlmProtocol.ANTHROPIC, "{\"type\":\"message_start\"}"),
        )
    }

    @Test
    fun `完成原因_两种协议`() {
        assertEquals(
            "stop",
            LlmSse.finishReason(LlmProtocol.OPENAI_COMPAT, "{\"choices\":[{\"finish_reason\":\"stop\"}]}"),
        )
        assertNull(LlmSse.finishReason(LlmProtocol.OPENAI_COMPAT, "{\"choices\":[{}]}"))
        assertEquals("stop", LlmSse.finishReason(LlmProtocol.ANTHROPIC, "{\"type\":\"message_stop\"}"))
        assertEquals(
            "max_tokens",
            LlmSse.finishReason(LlmProtocol.ANTHROPIC, "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}"),
        )
        assertNull(LlmSse.finishReason(LlmProtocol.ANTHROPIC, "{坏"))
    }
}
