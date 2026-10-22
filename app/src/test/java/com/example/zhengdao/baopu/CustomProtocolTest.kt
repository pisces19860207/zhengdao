// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百三十六批 · 自定义通道协议选择》§2：
// - resolve 在两种协议下产出 Provider.protocol 正确；缺省/脏值回退 OpenAI；
// - 自定义 Anthropic 请求走 `/v1/messages`，不误走 `/v1/chat/completions`；
// - Key 不出现在任何字符串化路径；baseUrl 校验照旧。
//
// 为什么这里有一条真发请求的用例：`Provider.protocol` 只对了一半不算数——本仓的
// 请求层（LlmWire）按协议决定端点与头。单看 resolve 的字段会漏掉「协议存对了、
// 请求仍打旧端点」这种半残；MockWebServer 把最后一跳也钉住（同 LlmClientTest 的房型）。
package com.example.zhengdao.baopu

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CustomProtocolTest {

    private lateinit var server: MockWebServer

    private val key = "sk-ant-sentinel-136-do-not-log"

    @Before
    fun setUp() {
        server = MockWebServer()
        // 同 LlmClientTest：显式绑 127.0.0.1（本机 localhost 反查会被 URL 许可判据正确拒掉）。
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 走生产同一个 resolve 口构造自定义 Provider（Key 只经 keyFor 进内存）。 */
    private fun custom(protocol: String?) = ProviderPrefs.resolve(
        channelId = ProviderPrefs.CHANNEL_CUSTOM,
        keyFor = { key },
        customBase = "http://127.0.0.1:${server.port}/v1",
        customModel = "claude-sonnet-4-5",
        customProtocol = protocol,
    )

    private fun sendOnce(p: Provider): RecordedRequest {
        val events = mutableListOf<LlmEvent>()
        val latch = CountDownLatch(1)
        LlmClient(p).streamAsync(listOf(LlmMessage(LlmMessage.ROLE_USER, "hi"))) { ev ->
            synchronized(events) { events += ev }
            if (ev is LlmEvent.Finished || ev is LlmEvent.Failed) latch.countDown()
        }
        assertTrue("5 秒内没有终止事件（$events）", latch.await(5, TimeUnit.SECONDS))
        assertTrue("请求没有到达假服务器（$events）", server.requestCount >= 1)
        return server.takeRequest(5, TimeUnit.SECONDS)!!
    }

    // ── 1. 两种协议：Provider.protocol 与端点 ──────────────────────────────

    @Test
    fun 缺省协议_自定义仍是OpenAI兼容() {
        val p = custom(null)
        assertEquals(ProviderPrefs.CHANNEL_CUSTOM, p.id)
        assertEquals(LlmProtocol.OPENAI_COMPAT, p.protocol)
        assertEquals("http://127.0.0.1:${server.port}/v1/chat/completions", LlmWire.endpoint(p.protocol, p.baseUrl))
    }

    @Test
    fun 选了Anthropic_Provider协议是Anthropic() {
        val p = custom(ProviderPrefs.PROTOCOL_ANTHROPIC)
        assertEquals(LlmProtocol.ANTHROPIC, p.protocol)
        assertEquals("http://127.0.0.1:${server.port}/v1/messages", LlmWire.endpoint(p.protocol, p.baseUrl))
        val h = LlmWire.headersOf(p)
        assertEquals(key, h["x-api-key"])
        assertEquals(LlmWire.ANTHROPIC_VERSION, h["anthropic-version"])
        assertNull("Anthropic 不许再带 OpenAI 的 Authorization 头", h["Authorization"])
    }

    @Test
    fun 缺省与脏值一律回OpenAI兼容() {
        listOf(null, "", "  ", "openai_compat", "claude", "Anthropic", "ANTHROPIC", "anthropic-v1")
            .forEach { stored ->
                assertEquals(
                    "协议存储值 <$stored> 必须回退 OpenAI 兼容",
                    LlmProtocol.OPENAI_COMPAT,
                    custom(stored).protocol,
                )
                assertEquals(
                    "线值也要归一，不许把脏值原样写回",
                    ProviderPrefs.PROTOCOL_OPENAI_COMPAT,
                    ProviderPrefs.protocolKeyOf(stored),
                )
            }
    }

    @Test
    fun 只有精确anthropic才算Anthropic_前后空白容忍() {
        assertEquals(ProviderPrefs.PROTOCOL_ANTHROPIC, ProviderPrefs.protocolKeyOf(" anthropic "))
        assertEquals(LlmProtocol.ANTHROPIC, ProviderPrefs.protocolOf("anthropic"))
        assertEquals("前空白也容忍", ProviderPrefs.PROTOCOL_ANTHROPIC, ProviderPrefs.protocolKeyOf("  anthropic"))
        assertEquals("多一个后缀就是脏值", ProviderPrefs.PROTOCOL_OPENAI_COMPAT, ProviderPrefs.protocolKeyOf("anthropic-v1"))
    }

    // ── 2. 最后一跳：真发请求的端点与头 ────────────────────────────────────

    @Test
    fun 自定义Anthropic_真发请求走messages不误走chat_completions() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"),
        )
        val req = sendOnce(custom(ProviderPrefs.PROTOCOL_ANTHROPIC))
        assertEquals("POST", req.method)
        assertEquals("/v1/messages", req.path)
        assertEquals(key, req.getHeader("x-api-key"))
        assertEquals(LlmWire.ANTHROPIC_VERSION, req.getHeader("anthropic-version"))
        assertNull(req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue("Anthropic 要的是顶层 system/messages 形状", body.contains("\"messages\":"))
        assertFalse("请求体里不许出现任何 Key 原文", body.contains(key))
    }

    @Test
    fun 自定义缺省协议_真发请求仍走chat_completions() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n",
                ),
        )
        val req = sendOnce(custom(null))
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer $key", req.getHeader("Authorization"))
        assertNull(req.getHeader("x-api-key"))
    }

    // ── 3. 候选名单与校验：协议跟着自定义通道走，但不绕过 URL 闸 ────────────

    @Test
    fun 候选名单与回退里的自定义通道也按协议构造() {
        val preset = Providers.all().first { !it.builtinFree }
        val opts = ProviderPrefs.routeCandidates(
            currentId = preset.id,
            keyFor = { id -> if (id == ProviderPrefs.CHANNEL_CUSTOM) key else null },
            customBase = "https://api.anthropic.com/v1",
            customModel = "claude-sonnet-4-5",
            customProtocol = ProviderPrefs.PROTOCOL_ANTHROPIC,
        )
        val custom = opts.single { it.id == ProviderPrefs.CHANNEL_CUSTOM }
        assertEquals(LlmProtocol.ANTHROPIC, custom.protocol)

        val next = ProviderPrefs.fallbackFrom(
            currentId = preset.id,
            keyFor = { id -> if (id == ProviderPrefs.CHANNEL_CUSTOM) key else null },
            customBase = "https://api.anthropic.com/v1",
            customModel = "claude-sonnet-4-5",
            customProtocol = ProviderPrefs.PROTOCOL_ANTHROPIC,
        )
        assertEquals(ProviderPrefs.CHANNEL_CUSTOM, next?.id)
        assertEquals(LlmProtocol.ANTHROPIC, next?.protocol)
    }

    @Test
    fun 协议选择不绕过baseUrl校验_非法地址仍回免费车道() {
        val p = ProviderPrefs.resolve(
            channelId = ProviderPrefs.CHANNEL_CUSTOM,
            keyFor = { key },
            customBase = "http://example.com/v1",
            customModel = "claude-sonnet-4-5",
            customProtocol = ProviderPrefs.PROTOCOL_ANTHROPIC,
        )
        assertEquals("明文 http 非环回：先过 URL 闸，协议选择不能把它放进来", Providers.OPENROUTER_FREE.id, p.id)
        assertEquals(LlmProtocol.OPENAI_COMPAT, p.protocol)
    }

    // ── 4. Key 的纪律 ─────────────────────────────────────────────────────

    @Test
    fun Key不进端点与协议提示这些字符串路径() {
        val p = custom(ProviderPrefs.PROTOCOL_ANTHROPIC)
        assertEquals(key, p.apiKey)
        assertFalse(LlmWire.endpoint(p.protocol, p.baseUrl).contains(key))
        assertFalse(ModelPickerRules.ANTHROPIC_MANUAL_MODEL_HINT.contains(key))
        assertFalse(ProviderPrefs.protocolKeyOf(ProviderPrefs.PROTOCOL_ANTHROPIC).contains(key))
        assertFalse(ProviderPrefs.PROTOCOL_ANTHROPIC.contains(key))
        // 协议提示是给用户的一句话，不是把任何凭据拼进去的模板。
        assertEquals("该协议需手填模型名", ModelPickerRules.ANTHROPIC_MANUAL_MODEL_HINT)
    }
}
