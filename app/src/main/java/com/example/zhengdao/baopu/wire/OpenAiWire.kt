// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十八批 §1.3（2026-10-24）：encode 收口——本文件是 **OpenAI 兼容**协议的
// 请求体拼装，从 `baopu/LlmClient.kt` 的 `LlmWire.bodyOf` 字符级搬出（纯搬家，
// 请求体逐字节相同，对账尺见 `WireGoldenTest`）。
//
// 为什么按协议拆文件：URL 许可、端点路径、请求头优先级、请求体字段这四张表是
// "换 provider 时会不会静默走错协议"的唯一判据（LlmWire 类 KDoc 的原话）。
// 三家的请求体形状差到没有共享行，挤在一个 `when` 里只会让下次改一家时手滑
// 改到另外两家。分叉只许在本文件内部；LlmClient 里不再出现拼请求体的字面。
//
// put 序（= 真机上的字节序，Android 的 org.json 是插入序）：
// model, stream, max_tokens, [stream_options], [reasoning_effort], messages, [tools]
package com.example.zhengdao.baopu.wire

import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.LlmWire
import com.example.zhengdao.baopu.ModelParams
import com.example.zhengdao.baopu.Provider
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容协议（OpenAI 官方 / DeepSeek / 智谱 / OpenRouter…形状一致，共享本文件）。
 *
 * 四个特殊处理（与搬家前逐字相同，判据在 LlmWire.bodyOf 的 KDoc）：
 * 1. `system` 角色**是消息**，留在 `messages` 里（不提到顶层——那是 Anthropic 的形状）；
 * 2. `max_tokens` 取 [Provider.maxOutput]，拿不到回落 [LlmWire.OPENAI_MAX_TOKENS_FALLBACK]；
 * 3. 流式时带 `stream_options.include_usage = true`（向服务端要用量读数）；
 * 4. 按 [Provider.reasoningEffort] 发 `reasoning_effort`（只发这一个字段名；
 *    没声明 ⇒ 不带；脏值 ⇒ 不发，见 [ModelParams.normalizeEffort]）。
 */
internal object OpenAiWire {

    fun body(
        p: Provider,
        messages: List<LlmMessage>,
        stream: Boolean = true,
        tools: JSONArray? = null,
    ): String =
        JSONObject()
            .put("model", p.model)
            .put("stream", stream)
            .put("max_tokens", p.maxOutput ?: LlmWire.OPENAI_MAX_TOKENS_FALLBACK)
            .apply {
                if (stream) put("stream_options", JSONObject().put("include_usage", true))
                // 模型侧思考深度：provider 显式声明了才发（派工单「按预设发」），
                // 且只发 reasoning_effort 这一个字段名（假字段禁发，见 KDoc 第 4 条）
                ModelParams.normalizeEffort(p.reasoningEffort)?.let { put("reasoning_effort", it) }
            }
            .put("messages", JSONArray().also { arr ->
                messages.forEach { m ->
                    val obj = JSONObject().put("role", m.role)
                    if (m.images.isEmpty()) {
                        // V1：纯文本走**原来的**写法，一个字节都不多——这是防回归的
                        // 全部意义（15 条历史单测与真机日志都按这个形状对账）
                        obj.put("content", m.content)
                    } else {
                        obj.put("content", contentBlocks(m))
                    }
                    arr.put(obj)
                }
            })
            .apply { if (tools != null && tools.length() > 0) put("tools", tools) }
            .toString()

    /**
     * 压缩请求体（第十三批 A 段 · 复用 [body] 的非流式分支，两条红线都由它兜住：
     * **不带 tools**（压缩不许触发工具调用）、**不带 stream**（压缩要一次拿到整段））。
     *
     * [maxTokens] 由调用方盖成压缩上限（≤ [com.example.zhengdao.baopu.LlmClient.COMPLETE_MAX_TOKENS]），
     * [temperature] 压到 0.2（摘要要稳定，不要每次压出不同版本）。
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
     * 多段 content（判据 V2）：`[{type:text}, {type:image_url…}…]`。
     *
     * **text 段在前、图片段在后**——不只是审美：image 夹在 text 中间时部分端点
     * 会拒（公开文档都把图放在最后），而且 text 在前对人类可读的日志更友好。
     * `content` 为空则不造空 text 段（空串的 text 块有的端点会 400）。
     */
    private fun contentBlocks(m: LlmMessage): JSONArray {
        val blocks = JSONArray()
        if (m.content.isNotEmpty()) {
            blocks.put(JSONObject().put("type", "text").put("text", m.content))
        }
        for (url in m.images) {
            blocks.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", url)),
            )
        }
        return blocks
    }
}
