// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

// 第一百五十四批：Gemini 原生协议形状（纯函数层，不联网）。
//
// 判据口径与另两档一致：URL/头/请求体/流式解析逐格钉死——"换 provider 时会不会
// 静默走错协议"由这一组测试回答。
package com.example.zhengdao.baopu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNativeWireTest {

    private val provider = Providers.GEMINI.copy(apiKey = "test-key")

    @Test
    fun `端点_stream 与非流式`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            LlmWire.endpoint(LlmProtocol.GEMINI_NATIVE, providersBase(), provider.model, stream = true),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            LlmWire.endpoint(LlmProtocol.GEMINI_NATIVE, providersBase(), provider.model, stream = false),
        )
    }

    private fun providersBase(): String = Providers.GEMINI.baseUrl

    @Test
    fun `鉴权走 x-goog-api-key_不带 Bearer`() {
        val h = LlmWire.headersOf(provider)
        assertEquals("test-key", h["x-goog-api-key"])
        assertFalse("Gemini 不该带 Bearer", h.containsKey("Authorization"))
        assertFalse("Gemini 不该带 anthropic 版本头", h.containsKey("anthropic-version"))
    }

    @Test
    fun `请求体是 contents 与 systemInstruction_不是 messages`() {
        val body = JSONObject(
            LlmWire.bodyOf(
                provider,
                listOf(
                    LlmMessage(LlmMessage.ROLE_SYSTEM, "你是抱朴"),
                    LlmMessage(LlmMessage.ROLE_USER, "你好"),
                    LlmMessage(LlmMessage.ROLE_ASSISTANT, "在"),
                ),
                stream = true,
            ),
        )
        assertFalse("不许出现 messages", body.has("messages"))
        assertEquals("你是抱朴", body.getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = body.getJSONArray("contents")
        assertEquals(2, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("你好", contents.getJSONObject(0).getJSONArray("parts")
            .getJSONObject(0).getString("text"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals(8192, body.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test
    fun `图片走 inline_data_不带 data URL 前缀`() {
        val png = "data:image/png;base64,AAAA"
        val body = JSONObject(
            LlmWire.bodyOf(
                provider,
                listOf(LlmMessage(LlmMessage.ROLE_USER, "看图", images = listOf(png))),
                stream = false,
            ),
        )
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val inline = parts.getJSONObject(1).getJSONObject("inline_data")
        assertEquals("image/png", inline.getString("mime_type"))
        assertEquals("AAAA", inline.getString("data"))
    }

    @Test
    fun `工具转 functionDeclarations_并剥掉 Gemini 不吃的 schema 关键词`() {
        val tools = org.json.JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject()
                    .put("name", "read_file")
                    .put("description", "读文件")
                    .put("parameters", JSONObject()
                        .put("type", "object")
                        .put("additionalProperties", false)
                        .put("properties", JSONObject().put("path", JSONObject()
                            .put("type", "string")
                            .put("description", "路径")))
                        .put("required", org.json.JSONArray().put("path"))),
            ),
        )
        val body = JSONObject(LlmWire.bodyOf(provider, listOf(LlmMessage(LlmMessage.ROLE_USER, "x")), stream = false, tools = tools))
        val decls = body.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(1, decls.length())
        val d = decls.getJSONObject(0)
        assertEquals("read_file", d.getString("name"))
        val params = d.getJSONObject("parametersJsonSchema")
        assertFalse("新形态不再发 parameters", d.has("parameters"))
        assertFalse("additionalProperties 必须被剥掉", params.has("additionalProperties"))
        assertEquals("STRING", params.getJSONObject("properties").getJSONObject("path").getString("type"))
        assertEquals("OBJECT", params.getString("type"))
    }

    @Test
    fun `流式帧解析_文本_思考_工具_用量_停止原因`() {
        val text = """{"candidates":[{"content":{"parts":[{"text":"你好"}]},"finishReason":"STOP"}],
            "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3,"thoughtsTokenCount":7}}""".trimIndent()
        assertEquals("你好", LlmSse.deltaText(LlmProtocol.GEMINI_NATIVE, text))
        assertEquals("stop", LlmSse.finishReason(LlmProtocol.GEMINI_NATIVE, text))
        val u = LlmSse.usageOf(LlmProtocol.GEMINI_NATIVE, text)!!
        assertEquals(10, u.promptTokens)
        assertEquals(3, u.completionTokens)
        assertEquals(7, u.reasoningTokens)

        val thought = """{"candidates":[{"content":{"parts":[{"text":"想想","thought":true}]}}]}"""
        assertNull("思考内容不进正文", LlmSse.deltaText(LlmProtocol.GEMINI_NATIVE, thought))
        assertEquals("想想", LlmSse.reasoningText(LlmProtocol.GEMINI_NATIVE, thought))

        val call = """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_file","args":{"path":"a.txt"}}}]}}]}"""
        val deltas = LlmSse.toolCallDeltas(LlmProtocol.GEMINI_NATIVE, call)
        assertEquals(1, deltas.size)
        assertEquals("read_file", deltas[0].name)
        assertTrue(deltas[0].argumentsDelta!!.contains("\"path\""))
    }

    @Test
    fun `非流式响应取纯文本`() {
        val raw = """{"candidates":[{"content":{"parts":[{"text":"答复"}]}}]}"""
        assertEquals("答复", LlmWire.plainTextOf(LlmProtocol.GEMINI_NATIVE, raw))
    }
}
