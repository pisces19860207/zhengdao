// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十八批 §1.3（2026-10-24）：encode 收口——本文件是 **Anthropic**协议的
// 请求体拼装，从 `baopu/LlmClient.kt` 的 `LlmWire.bodyOf` 字符级搬出（纯搬家，
// 请求体逐字节相同，对账尺见 `WireGoldenTest`）。
//
// put 序（= 真机上的字节序）：model, stream, max_tokens, [system], messages, [tools]
package com.example.zhengdao.baopu.wire

import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.LlmWire
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.VisionRules
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic 协议（`/messages`，`x-api-key` + `anthropic-version` 头见 [LlmWire.headersOf]）。
 *
 * 与 OpenAI 档的差别（公开文档）：
 * 1. `system` 角色**不是消息**，要提到顶层 `system` 字段（多条合并）；
 * 2. `max_tokens` 必填（其 API 不接受缺省），回落 [LlmWire.ANTHROPIC_MAX_TOKENS]；
 * 3. 没有 `stream_options` 开关——用量在 `message_start`/`message_delta` 事件里天然带回；
 * 4. 思考深度是 `thinking` 参数不是 `reasoning_effort`，且预设从不声明 effort ⇒ 不加。
 */
internal object AnthropicWire {

    fun body(
        p: Provider,
        messages: List<LlmMessage>,
        stream: Boolean = true,
        tools: JSONArray? = null,
    ): String {
        val system = messages.filter { it.role == LlmMessage.ROLE_SYSTEM }
            .joinToString("\n\n") { it.content }
        val rest = messages.filter { it.role != LlmMessage.ROLE_SYSTEM }
        return JSONObject()
            .put("model", p.model)
            .put("stream", stream)
            .put("max_tokens", p.maxOutput ?: LlmWire.ANTHROPIC_MAX_TOKENS)
            .apply { if (system.isNotEmpty()) put("system", system) }
            .put("messages", JSONArray().also { arr ->
                rest.forEach { m ->
                    val obj = JSONObject().put("role", m.role)
                    if (m.images.isEmpty()) {
                        obj.put("content", m.content) // V1：同上，逐字节等价
                    } else {
                        obj.put("content", contentBlocks(m))
                    }
                    arr.put(obj)
                }
            })
            .apply { if (tools != null && tools.length() > 0) put("tools", tools) }
            .toString()
    }

    /**
     * 压缩请求体（复用 [body] 的非流式分支；两条红线见 [OpenAiWire.completeBody]）。
     *
     * 与 OpenAI 档唯一的差别是上限字段名（`max_tokens` 同名，故实现相同）——
     * 仍然各写一份：两档的 put 序与封顶字段未来随时会分叉，合 share 一个函数
     * 就要为「哪家哪套」再养一个 when，那是本批刚清掉的形式。
     */
    fun completeBody(
        p: Provider,
        messages: List<LlmMessage>,
        maxTokens: Int,
        temperature: Double,
    ): String {
        val base = JSONObject(body(p, messages, stream = false, tools = null))
        return base
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .toString()
    }

    /**
     * 多段 content（判据 V2）：`[{type:text}, {type:image…}…]`。
     *
     * text 段在前的理由同 OpenAI 档；Anthropic 的图片形状是
     * `{"type":"image","source":{"type":"base64","media_type":…,"data":…}}`（公开文档形态）。
     */
    private fun contentBlocks(m: LlmMessage): JSONArray {
        val blocks = JSONArray()
        if (m.content.isNotEmpty()) {
            blocks.put(JSONObject().put("type", "text").put("text", m.content))
        }
        for (url in m.images) {
            val parts = VisionRules.splitDataUrl(url) ?: continue
            blocks.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", parts.first)
                            .put("data", parts.second),
                    ),
            )
        }
        return blocks
    }
}
