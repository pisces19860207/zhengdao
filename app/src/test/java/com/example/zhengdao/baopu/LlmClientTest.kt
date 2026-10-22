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

    // ── stream_options 与 usage（刀 3 B 段 · 第十四批 J5/J6） ───────────────
    // 病灶：全仓无 include_usage ⇒ 服务端流末不回 usage 帧 ⇒ run_log 五列永远是猜的。

    @Test
    fun `J5_OpenAI请求体带stream_options_include_usage_Anthropic不带`() {
        val opts = bodyJson(providerWith(LlmProtocol.OPENAI_COMPAT)).getJSONObject("stream_options")
        assertTrue(opts.getBoolean("include_usage"))
        // 仅 OpenAI 兼容分支：Anthropic 的用量在 message_start/message_delta 事件里
        // 天然带回，没有这个开关（③ 按公开文档）——给它反而多个未知字段
        assertFalse(bodyJson(providerWith(LlmProtocol.ANTHROPIC)).has("stream_options"))
    }

    @Test
    fun `J6a_SSE带usage帧_Finished带上五列读数`() {
        // 假服务器**按请求体决定回不回 usage**：请求带了 stream_options.include_usage
        // 才回流末那帧纯 usage——负例 2（去掉 stream_options）下这条必须红
        // （派工单 §5 原话：「J5 必须红、J6 必须失败（因为后端不会再回 usage）」）。
        // 只喂固定 SSE 的话，负例只红 J5 不红 J6，那条负例就名不副实。
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (request.body.readUtf8().contains("include_usage")) {
                    sse(
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"}}]}\n\n" +
                            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":34," +
                            "\"total_tokens\":46,\"completion_tokens_details\":{\"reasoning_tokens\":20}," +
                            "\"prompt_cache_hit_tokens\":8,\"prompt_cache_miss_tokens\":4}}\n\n" +
                            "data: [DONE]\n\n",
                    )
                } else {
                    sse(
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"}}]}\n\n" +
                            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                            "data: [DONE]\n\n",
                    )
                }
        }
        val events = collect(provider(LlmProtocol.OPENAI_COMPAT), listOf(LlmMessage("user", "hi")))
        val fin = events.last() as LlmEvent.Finished
        assertEquals("stop", fin.reason)
        assertEquals(
            LlmUsage(
                promptTokens = 12, completionTokens = 34, reasoningTokens = 20,
                cacheHitTokens = 8, cacheMissTokens = 4,
            ),
            fin.usage,
        )
    }

    @Test
    fun `J6b_整条流没有usage_Finished的usage为null且不抛`() {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val events = collect(provider(LlmProtocol.OPENAI_COMPAT), listOf(LlmMessage("user", "hi")))
        val fin = events.last() as LlmEvent.Finished
        assertNull("缺 usage 必须记 null 不报错（派工单 B 段第 6 条）", fin.usage)
    }

    @Test
    fun `usage解析_DeepSeek实测原样形状_同帧带finish_reason`() {
        // ① DeepSeek 官方通道实测 D:\zd-tools\ds-stream-probe.txt frame#65 的原样载荷：
        // usage 与 finish_reason 同帧（不是流末单独帧）——两种形态都必须吃
        val u = LlmSse.usageOf(
            LlmProtocol.OPENAI_COMPAT,
            "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]," +
                "\"usage\":{\"prompt_tokens\":36,\"completion_tokens\":64,\"total_tokens\":100," +
                "\"prompt_tokens_details\":{\"cached_tokens\":0}," +
                "\"completion_tokens_details\":{\"reasoning_tokens\":61}," +
                "\"prompt_cache_hit_tokens\":0,\"prompt_cache_miss_tokens\":36}}",
        )
        assertEquals(
            LlmUsage(
                promptTokens = 36, completionTokens = 64, reasoningTokens = 61,
                cacheHitTokens = 0, cacheMissTokens = 36,
            ),
            u,
        )
    }

    @Test
    fun `usage解析_OpenAI官方cached_tokens回落_无usage与坏JSON返回null`() {
        val u = LlmSse.usageOf(
            LlmProtocol.OPENAI_COMPAT,
            "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5," +
                "\"prompt_tokens_details\":{\"cached_tokens\":6}}}",
        )
        // 没有 prompt_cache_hit_tokens ⇒ 回落 prompt_tokens_details.cached_tokens
        assertEquals(LlmUsage(promptTokens = 10, completionTokens = 5, cacheHitTokens = 6), u)
        assertNull(LlmSse.usageOf(LlmProtocol.OPENAI_COMPAT, "{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}"))
        assertNull(LlmSse.usageOf(LlmProtocol.OPENAI_COMPAT, "{\"usage\":null}"))
        assertNull(LlmSse.usageOf(LlmProtocol.OPENAI_COMPAT, "{坏"))
    }

    @Test
    fun `usage解析_Anthropic跨两帧合并_后到且非null的赢`() {
        val start = LlmSse.usageOf(
            LlmProtocol.ANTHROPIC,
            "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":25," +
                "\"cache_read_input_tokens\":7,\"cache_creation_input_tokens\":3}}}",
        )
        assertEquals(LlmUsage(promptTokens = 25, cacheHitTokens = 7, cacheMissTokens = 3), start)
        val delta = LlmSse.usageOf(
            LlmProtocol.ANTHROPIC,
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
                "\"usage\":{\"output_tokens\":12}}",
        )
        assertEquals(LlmUsage(completionTokens = 12), delta)
        assertEquals(
            LlmUsage(promptTokens = 25, completionTokens = 12, cacheHitTokens = 7, cacheMissTokens = 3),
            LlmSse.mergeUsage(start, delta!!),
        )
        // null 字段不抹掉旧值（Anthropic 的 message_delta 只带 output_tokens）：
        // 拿一帧全空的合进来，旧读数原样留着——不因为新帧没给就抹成 null
        assertEquals(
            LlmUsage(promptTokens = 25, cacheHitTokens = 7, cacheMissTokens = 3),
            LlmSse.mergeUsage(start, LlmUsage()),
        )
        // 合并的起点可以是 null（首帧）
        assertEquals(delta, LlmSse.mergeUsage(null, delta!!))
    }

    @Test
    fun `anthropic_用量两帧合并进Finished_首个finish_reason赢`() {
        server.enqueue(
            sse(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":25,\"cache_read_input_tokens\":7,\"cache_creation_input_tokens\":3}}}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n" +
                    "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":12}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            ),
        )
        val events = collect(provider(LlmProtocol.ANTHROPIC), listOf(LlmMessage("user", "hi")))
        val fin = events.last() as LlmEvent.Finished
        // message_delta 的 end_turn 赢；message_stop 的 stop 不许覆盖（刀 0 口径）
        assertEquals("end_turn", fin.reason)
        assertEquals(
            LlmUsage(promptTokens = 25, completionTokens = 12, cacheHitTokens = 7, cacheMissTokens = 3),
            fin.usage,
        )
    }

    // ── reasoning_effort 与 reasoning_content（刀 3 B 段 C 段 · J7/J8/J9） ──

    @Test
    fun `J7_四档effort按声明进请求体_没声明不带字段_脏值不发`() {
        // 四档映射：界面值 = 服务端值，一字不差（① DeepSeek 实测 8 档枚举）
        listOf("none", "low", "high", "max").forEach { level ->
            val body = bodyJson(providerWith(LlmProtocol.OPENAI_COMPAT).copy(reasoningEffort = level))
            assertEquals(level, body.getString("reasoning_effort"))
        }
        // DeepSeek 预设自带 high（服务端默认也是 high）
        assertEquals("high", bodyJson(Providers.DEEPSEEK).getString("reasoning_effort"))
        // 没声明 ⇒ 不带字段（不替不认这个字段的通道瞎发——ModelParams KDoc 分界）
        assertFalse(bodyJson(providerWith(LlmProtocol.OPENAI_COMPAT)).has("reasoning_effort"))
        // Anthropic 的形状是 thinking 参数不是这个字段，且预设从不声明 ⇒ 不发
        assertFalse(bodyJson(providerWith(LlmProtocol.ANTHROPIC)).has("reasoning_effort"))
        // 脏值不发（服务端对非法值 422 并把枚举报回来）
        assertFalse(
            bodyJson(providerWith(LlmProtocol.OPENAI_COMPAT).copy(reasoningEffort = "banana"))
                .has("reasoning_effort"),
        )
    }

    @Test
    fun `J8_请求体不含effort与max_reasoning_effort两个假字段`() {
        val body = bodyJson(Providers.DEEPSEEK)
        assertFalse(body.has("effort"))
        assertFalse(body.has("max_reasoning_effort"))
        // 与 J7 互为一面：真的发了、假的没发（假字段塞 banana 也返回 200，静默忽略）
        assertTrue(body.has("reasoning_effort"))
    }

    @Test
    fun `J9_reasoning_content只累计字数_不进正文`() {
        server.enqueue(
            sse(
                // ① DeepSeek 实测形状：content 与 reasoning_content 同帧、两个独立字段
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"答案是 42\",\"reasoning_content\":\"先想想\"}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"。\",\"reasoning_content\":\"再想想\"}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val events = collect(provider(LlmProtocol.OPENAI_COMPAT), listOf(LlmMessage("user", "hi")))
        val text = events.filterIsInstance<LlmEvent.Delta>().joinToString("") { it.text }
        // 正文只有 content；思考原文一个字都不许进来（不进正文、不进历史）
        assertEquals("答案是 42。", text)
        assertFalse(text.contains("先想想"))
        // 只累计字数：「先想想」3 + 「再想想」3 = 6
        assertEquals(6, (events.last() as LlmEvent.Finished).reasoningChars)
    }

    @Test
    fun `J9b_没有reasoning_content的流_reasoningChars为null`() {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"普通回复\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val events = collect(provider(LlmProtocol.OPENAI_COMPAT), listOf(LlmMessage("user", "hi")))
        assertNull((events.last() as LlmEvent.Finished).reasoningChars)
    }

    @Test
    fun `reasoningText解析_两种协议_坏JSON不抛`() {
        assertEquals(
            "先想想",
            LlmSse.reasoningText(
                LlmProtocol.OPENAI_COMPAT,
                "{\"choices\":[{\"delta\":{\"content\":\"x\",\"reasoning_content\":\"先想想\"}}]}",
            ),
        )
        assertNull(LlmSse.reasoningText(LlmProtocol.OPENAI_COMPAT, "{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}"))
        assertNull(LlmSse.reasoningText(LlmProtocol.OPENAI_COMPAT, "{坏"))
        // Anthropic 的思考在 thinking_delta 里（③ 按公开文档）
        assertEquals(
            "hmm",
            LlmSse.reasoningText(
                LlmProtocol.ANTHROPIC,
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}",
            ),
        )
        // text_delta 是正文不是思考（别把正文当思考数字数）
        assertNull(
            LlmSse.reasoningText(
                LlmProtocol.ANTHROPIC,
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hmm\"}}",
            ),
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
        // 第一百三十一批 ①：免费车道**不内置 Key** ⇒ 没 Key 就一个鉴权头都不发
        //（旧的 "Bearer null" 占位与 zen 的伪装 User-Agent 随整条通道一起删掉了）
        val free = LlmWire.headersOf(Providers.OPENROUTER_FREE)
        assertNull("没填 Key 不许发 Authorization（更不许发占位的 Bearer null）", free["Authorization"])
        assertFalse("伪装头随内置免费通道一起删除：${free.keys}", free.containsKey("User-Agent"))
        assertEquals("application/json", free["Content-Type"])
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

    /**
     * J2（第十三批 §6.1）：压缩请求体**不带 `tools`、不带流式**（派工单 §3.3 红线）。
     *
     * 为什么钉在 `completeBody` 上：它是 [LlmClient.completeOnce] 唯一的组包出口，
     * 出口只有一个 ⇒ "压缩请求里混进 tools" 这种事只可能发生在这里，钉住它就够。
     * `stream` 允许出现但必须是 false（显式关比省略更不容易被兼容通道私自流式化）。
     */
    @Test
    fun `J2_压缩请求体_不带tools_不带流式`() {
        val p = Provider(
            id = "t", label = "t", protocol = LlmProtocol.OPENAI_COMPAT,
            baseUrl = "https://x/v1", model = "m",
        )
        val body = LlmClient(p).completeBody(
            messages = listOf(LlmMessage(LlmMessage.ROLE_SYSTEM, "系统"), LlmMessage(LlmMessage.ROLE_USER, "正文")),
            maxTokens = 1024,
            temperature = 0.2,
        )
        val json = org.json.JSONObject(body)

        assertFalse("压缩请求不许带 tools", json.has("tools"))
        assertFalse("压缩请求不许带 stream_options", json.has("stream_options"))
        assertFalse(
            "压缩请求不许开流式",
            json.has("stream") && json.optBoolean("stream", false),
        )
        assertEquals("摘要上限 ≤1024", 1024, json.optInt("max_tokens"))
        assertEquals("摘要要稳定：温度 0.2", 0.2, json.optDouble("temperature"), 1e-9)
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
