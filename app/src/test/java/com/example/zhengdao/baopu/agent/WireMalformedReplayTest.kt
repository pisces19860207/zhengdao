// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

// 第一百八十八批 §1.4（下半）：三家 golden 的**畸形** replay（2026-10-24）。
//
// 与 `WireGoldenTest`（encode 侧字节对账）合起来才是完整的「三家 golden」：
// 本文件走**解码侧**——用 MockWebServer 真回放各家的最小成功与四类畸形响应，
// 断言「该红的红、该绿的绿、错误文案里能读出哪家/哪层」。
//
// 四类畸形（派工单 §1.4 点名）：
//   ① 缺 name；② 半截 JSON 参数（`{"cmd":`）；③ 未知工具名；④ 400 原始响应体。
//
// ⚠️ 一处如实记录的不对称（不改行为，本批只钉现状）：
// 缺 name 时 OpenAI/Anthropic 会在**解码层**给出可读 Bad（点名 tool_calls[i]/
// tool_use[i]），而 Gemini 的 functionCall 部件在**帧级**就被 LlmSse 丢掉
// （没有 name 就没有 index 键，硬造一个幻觉调用比丢更糟）⇒ 回放结果是
// 「没有工具调用」而不是一条 Bad。这条差异钉在下面的 Gemini 用例里。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.LlmClient
import com.example.zhengdao.baopu.LlmEvent
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WireMalformedReplayTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        // 绑环回 IP（同 LlmClientTest：本机 localhost 反查是 Docker 宿主别名，
        // Provider 的 URL 许可判据会正确地拒掉它；字面拼 127.0.0.1 两边都满足）。
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun provider(protocol: LlmProtocol) = Provider(
        id = "replay",
        label = "replay",
        protocol = protocol,
        baseUrl = "http://127.0.0.1:${server.port}/v1",
        model = "replay-model",
        apiKey = "k",
    )

    private fun sse(body: String) = MockResponse.Builder()
        .setHeader("Content-Type", "text/event-stream")
        .body(body).build()

    /** 跑一次流式，收到终止事件（Finished/Failed）或 5 秒超时即返回。 */
    private fun collect(p: Provider): List<LlmEvent> {
        val events = Collections.synchronizedList(mutableListOf<LlmEvent>())
        val latch = CountDownLatch(1)
        LlmClient(p).streamAsync(listOf(LlmMessage(LlmMessage.ROLE_USER, "读一下 a.txt"))) { ev ->
            events.add(ev)
            if (ev is LlmEvent.Finished || ev is LlmEvent.Failed) latch.countDown()
        }
        assertTrue("流式 5 秒内没有终止事件（事件：$events）", latch.await(5, TimeUnit.SECONDS))
        return events.toList()
    }

    /** 真注册表（真 Schema）：未知工具/缺必填的错文案要列出已注册工具。 */
    private fun registry(): ToolRegistry {
        val root = createTempDirectory("zd-replay-").toFile()
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        return reg
    }

    private fun finishedOf(events: List<LlmEvent>): LlmEvent.Finished {
        val f = events.lastOrNull { it is LlmEvent.Finished }
        assertTrue("应当正常结束（事件：$events）", f is LlmEvent.Finished)
        return f as LlmEvent.Finished
    }

    private fun failedOf(events: List<LlmEvent>): LlmEvent.Failed {
        val f = events.lastOrNull { it is LlmEvent.Failed }
        assertTrue("应当失败（事件：$events）", f is LlmEvent.Failed)
        return f as LlmEvent.Failed
    }

    // ── 最小成功：一家一条，绿 ─────────────────────────────────────────

    @Test
    fun `最小成功_OpenAI_一个工具调用解码通过`() {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                    "{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                    "{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val f = finishedOf(collect(provider(LlmProtocol.OPENAI_COMPAT)))
        assertEquals(1, f.toolCalls.size)
        val decoded = ToolCallCodec.decode(f.toolCalls, registry())
        assertTrue("一个合法调用必须解码通过：$decoded", decoded is ToolCallCodec.DecodeResult.Ok)
        val call = (decoded as ToolCallCodec.DecodeResult.Ok).calls.single()
        assertEquals("read_file", call.name)
        assertEquals("a.txt", call.arguments.getString("path"))
    }

    @Test
    fun `最小成功_Anthropic_tool_use块拼齐解码通过`() {
        server.enqueue(
            sse(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{}}\n\n" +
                    "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0," +
                    "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{}}}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0," +
                    "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}\n\n" +
                    "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            ),
        )
        val f = finishedOf(collect(provider(LlmProtocol.ANTHROPIC)))
        assertEquals(1, f.toolCalls.size)
        val decoded = ToolCallCodec.decode(f.toolCalls, registry())
        assertTrue("$decoded", decoded is ToolCallCodec.DecodeResult.Ok)
        assertEquals("a.txt", (decoded as ToolCallCodec.DecodeResult.Ok).calls.single().arguments.getString("path"))
    }

    @Test
    fun `最小成功_Gemini_functionCall部件解码通过且合成call_id`() {
        server.enqueue(
            sse(
                "data: {\"candidates\":[{\"content\":{\"parts\":[" +
                    "{\"functionCall\":{\"name\":\"read_file\",\"args\":{\"path\":\"a.txt\"}}}]}}]}\n\n" +
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]},\"finishReason\":\"STOP\"}]}\n\n",
            ),
        )
        val f = finishedOf(collect(provider(LlmProtocol.GEMINI_NATIVE)))
        assertEquals(1, f.toolCalls.size)
        val decoded = ToolCallCodec.decode(f.toolCalls, registry())
        assertTrue("$decoded", decoded is ToolCallCodec.DecodeResult.Ok)
        val call = (decoded as ToolCallCodec.DecodeResult.Ok).calls.single()
        assertEquals("a.txt", call.arguments.getString("path"))
        // Gemini 无 call id ⇒ IR 层显式合成（§0「已经做对的不要重做」之一）
        assertTrue("合成 id 要能看出是 Gemini 第几个部件：${call.id}", call.id.startsWith("gemini-"))
    }

    // ── 畸形一：缺 name ───────────────────────────────────────────────

    @Test
    fun `畸形_缺name_OpenAI_解码层给可读Bad并点名tool_calls层`() {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                    "{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val f = finishedOf(collect(provider(LlmProtocol.OPENAI_COMPAT)))
        assertEquals(1, f.toolCalls.size)
        val bad = ToolCallCodec.decode(f.toolCalls, registry()) as ToolCallCodec.DecodeResult.Bad
        assertTrue("错误要能读出是哪一层：${bad.error}", bad.error.contains("tool_calls[0].function.name"))
    }

    @Test
    fun `畸形_缺name_Anthropic_解码层给可读Bad并点名缺名字`() {
        server.enqueue(
            sse(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{}}\n\n" +
                    "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0," +
                    "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"input\":{}}}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0," +
                    "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            ),
        )
        val f = finishedOf(collect(provider(LlmProtocol.ANTHROPIC)))
        val bad = ToolCallCodec.decode(f.toolCalls, registry()) as ToolCallCodec.DecodeResult.Bad
        // 如实钉一处既有不对称：**流式**路径三家共用 `ToolCallCodec.decode`（一份判据），
        // 所以错文案是共享解码层的措辞（tool_calls[i].function.name）；Anthropic 专属的
        // 「tool_use[i].name 为空」只在**非流式整包**解码器里（ToolCallCodecProtocolTest 钉着）。
        // 两条都点名「第几个调用缺名字」这一层 ⇒ 该红的红，不静默跳过。
        assertTrue("错误要能读出缺名字这一层：${bad.error}", bad.error.contains("缺少名字"))
        assertTrue("错误要能读出是第几个调用：${bad.error}", bad.error.contains("[0]"))
    }

    @Test
    fun `畸形_缺name_Gemini_帧级就丢_不造幻觉调用`() {
        // 如实钉差异：没有 name 的 functionCall 部件在帧级被跳过（见 LlmSse），
        // 回放结果是「零工具调用」而不是一条 Bad——硬造调用比丢更糟。
        server.enqueue(
            sse(
                "data: {\"candidates\":[{\"content\":{\"parts\":[" +
                    "{\"functionCall\":{\"args\":{\"path\":\"a.txt\"}}}]}}]}\n\n" +
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]},\"finishReason\":\"STOP\"}]}\n\n",
            ),
        )
        val f = finishedOf(collect(provider(LlmProtocol.GEMINI_NATIVE)))
        assertEquals("没有 name 的部件不许变成调用", 0, f.toolCalls.size)
    }

    // ── 畸形二：半截 JSON 参数 ────────────────────────────────────────

    @Test
    fun `畸形_半截JSON_OpenAI_语法层Bad且错误里带服务端原话`() {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                    "{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"cmd\\\":\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val f = finishedOf(collect(provider(LlmProtocol.OPENAI_COMPAT)))
        val bad = ToolCallCodec.decode(f.toolCalls, registry()) as ToolCallCodec.DecodeResult.Bad
        assertEquals("半截 JSON 归语法层", ToolCallCodec.BadArgsKind.JSON_SYNTAX, bad.kind)
        assertTrue("错误原文要逐字保留（模型靠它知道错在哪）：${bad.error}", bad.error.contains("{\"cmd\":"))
    }

    @Test
    fun `畸形_半截JSON_Anthropic_语法层Bad且错误里带服务端原话`() {
        server.enqueue(
            sse(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{}}\n\n" +
                    "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0," +
                    "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_file\",\"input\":{}}}\n\n" +
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0," +
                    "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"cmd\\\":\"}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            ),
        )
        val f = finishedOf(collect(provider(LlmProtocol.ANTHROPIC)))
        val bad = ToolCallCodec.decode(f.toolCalls, registry()) as ToolCallCodec.DecodeResult.Bad
        assertEquals(ToolCallCodec.BadArgsKind.JSON_SYNTAX, bad.kind)
        assertTrue("错误原文要逐字保留：${bad.error}", bad.error.contains("{\"cmd\":"))
    }

    @Test
    fun `畸形_半截JSON_Gemini_args是字符串时落schema层_缺必填点名path`() {
        // Gemini 的 args 在线上是对象；模型不守规矩发**字符串**时，帧级取不到对象
        // ⇒ 按 {} 解 ⇒ 落注册表 Schema 层（缺必填 path）——可读、点名到字段。
        server.enqueue(
            sse(
                "data: {\"candidates\":[{\"content\":{\"parts\":[" +
                    "{\"functionCall\":{\"name\":\"read_file\",\"args\":\"{\\\"cmd\\\":\"}}]}}]}\n\n" +
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]},\"finishReason\":\"STOP\"}]}\n\n",
            ),
        )
        val f = finishedOf(collect(provider(LlmProtocol.GEMINI_NATIVE)))
        val bad = ToolCallCodec.decode(f.toolCalls, registry()) as ToolCallCodec.DecodeResult.Bad
        assertEquals("合法 JSON 但过不了 Schema ⇒ SCHEMA 层", ToolCallCodec.BadArgsKind.SCHEMA, bad.kind)
        assertTrue("错误要能读出缺哪个字段：${bad.error}", bad.error.contains("path"))
    }

    // ── 畸形三：未知工具名 ────────────────────────────────────────────

    @Test
    fun `畸形_未知工具_三家都给出已注册清单`() {
        val args = "{\\\"path\\\":\\\"a.txt\\\"}"
        // OpenAI
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                    "{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"不存在的工具\",\"arguments\":\"$args\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        var f = finishedOf(collect(provider(LlmProtocol.OPENAI_COMPAT)))
        var bad = ToolCallCodec.decode(f.toolCalls, registry()) as ToolCallCodec.DecodeResult.Bad
        assertTrue("未知工具要列出已注册清单：${bad.error}", bad.error.contains("未知工具") && bad.error.contains("read_file"))

        // Anthropic
        server.enqueue(
            sse(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{}}\n\n" +
                    "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0," +
                    "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"不存在的工具\",\"input\":{\"path\":\"a.txt\"}}}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            ),
        )
        f = finishedOf(collect(provider(LlmProtocol.ANTHROPIC)))
        bad = ToolCallCodec.decode(f.toolCalls, registry()) as ToolCallCodec.DecodeResult.Bad
        assertTrue("未知工具要列出已注册清单：${bad.error}", bad.error.contains("未知工具") && bad.error.contains("read_file"))

        // Gemini
        server.enqueue(
            sse(
                "data: {\"candidates\":[{\"content\":{\"parts\":[" +
                    "{\"functionCall\":{\"name\":\"不存在的工具\",\"args\":{\"path\":\"a.txt\"}}}]}}]}\n\n" +
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]},\"finishReason\":\"STOP\"}]}\n\n",
            ),
        )
        f = finishedOf(collect(provider(LlmProtocol.GEMINI_NATIVE)))
        bad = ToolCallCodec.decode(f.toolCalls, registry()) as ToolCallCodec.DecodeResult.Bad
        assertTrue("未知工具要列出已注册清单：${bad.error}", bad.error.contains("未知工具") && bad.error.contains("read_file"))
    }

    // ── 畸形四：400 原始响应体（服务端原话）──────────────────────────

    @Test
    fun `畸形_400原始体_OpenAI_原文进Failed且不重试`() {
        val serverWords =
            "{\"error\":{\"message\":\"Invalid schema for tool 'read_file': string is too short\",\"type\":\"invalid_request_error\",\"code\":\"invalid_function_parameters\"}}"
        server.enqueue(MockResponse.Builder().code(400).body(serverWords).build())
        val failed = failedOf(collect(provider(LlmProtocol.OPENAI_COMPAT)))
        assertEquals("HTTP 400", failed.error.message)
        assertEquals("服务端原话要逐字进 raw：${failed.raw}", serverWords, failed.raw)
        assertEquals("4xx 不重试（只 5xx 重试一次）：白跑一次就是多烧一次钱", 1, server.requestCount)
    }

    @Test
    fun `畸形_400原始体_Anthropic_原文进Failed且不重试`() {
        val serverWords =
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"tools.0.input_schema: required field 'type' is missing\"}}"
        server.enqueue(MockResponse.Builder().code(400).body(serverWords).build())
        val failed = failedOf(collect(provider(LlmProtocol.ANTHROPIC)))
        assertEquals("HTTP 400", failed.error.message)
        assertEquals(serverWords, failed.raw)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `畸形_400原始体_Gemini_原文进Failed且不重试`() {
        val serverWords =
            "{\"error\":{\"code\":400,\"message\":\"Invalid value at 'tools[0].functionDeclarations[0].parametersJsonSchema'\",\"status\":\"INVALID_ARGUMENT\"}}"
        server.enqueue(MockResponse.Builder().code(400).body(serverWords).build())
        val failed = failedOf(collect(provider(LlmProtocol.GEMINI_NATIVE)))
        assertEquals("HTTP 400", failed.error.message)
        assertEquals(serverWords, failed.raw)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `对照_500才重试一次_再失败按原口径转Failed`() {
        // 与 400 对照：5xx 重试一次（158 口径），第二次仍 5xx ⇒ 按普通 Failed 收
        // （allowRetry=false 的那次不再抛 Retryable5xx，直接 sink Failed＋原文）。
        server.enqueue(MockResponse.Builder().code(503).body("{\"error\":\"busy\"}").build())
        server.enqueue(MockResponse.Builder().code(503).body("{\"error\":\"still busy\"}").build())
        val failed = failedOf(collect(provider(LlmProtocol.OPENAI_COMPAT)))
        assertEquals("HTTP 503", failed.error.message)
        assertEquals("第二次的服务端原话要进 raw", "{\"error\":\"still busy\"}", failed.raw)
        assertEquals("只重试一次（白跑一次就是多烧一次钱）", 2, server.requestCount)
    }
}
