// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口与仓库内既有做法：
//   - OkHttp 4.12 官方 API（Request / Call / Response / body.source()）；
//   - Server-Sent Events 文本事件流格式（text/event-stream：data: 行 + 空行分隔）；
//   - SSE 解析房型沿用本仓库既有做法：readUtf8Line 逐行 + Accept-Encoding: identity
//     （禁压缩，否则事件流被缓冲不逐条 flush——经典坑）。原参照件 oc/SseClient.kt 已于
//     2026-10-16 旧抱朴线清场随 oc/ 删除，房型记录在本文件，不再指向仓库外文件名。
package com.example.zhengdao.baopu

import com.example.zhengdao.rootfs.RunLog
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * 一条对话消息。
 *
 * [images]（第二十四批 A 段）：本轮随消息一起发的图片，每项是 **data URL**
 * （`data:image/jpeg;base64,...`）。空 = 纯文本（**走原来的字符串 content，逐字节等价**，
 * 判据 V1）。
 *
 * 为什么是 data URL 而不是本机路径：路径发到模型侧就是泄漏（V6）。data URL 自带
 * MIME，OpenAI 与 Anthropic 两种形状都能从它拆出来，不需要额外字段。
 * 谁决定发哪几张、发多大：只有 [VisionRules.select]（`Provider.vision` 说了算，V5）。
 */
data class LlmMessage(
    val role: String,
    val content: String,
    val images: List<String> = emptyList(),
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/**
 * 流式累积中的原始工具调用（arguments 还是 JSON **字符串**——parse 与校验归
 * `agent/ToolCallCodec`，坏 JSON 在那里转"错误原文回灌"，不在网络层崩）。
 * 定义在 baopu 包：LlmSse（本文件）产、ToolCallCodec（agent 包）消费 ⇒ 依赖单向。
 */
data class RawToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** 一帧里的 tool_call 增量（id/name 一般首帧给，arguments 分多帧到货）。 */
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val name: String? = null,
    val argumentsDelta: String? = null,
)

/**
 * 一轮的用量读数（刀 3 B 段 · 可空 = 没测到）。
 *
 * 五列全部可空是有意的：**没测到就不编数**——「0 命中」与「没读到」是两件事，
 * 混为一谈后面的缓存账全乱（与 run_log 缓存两列同口径，见 BaopuSql.insertRunLog）。
 *
 * 各家字段名到本结构的映射（① DeepSeek 实测 / ③ 按公开文档）：
 * - OpenAI 系：`usage.prompt_tokens` / `completion_tokens` /
 *   `completion_tokens_details.reasoning_tokens` / `prompt_cache_hit_tokens` /
 *   `prompt_cache_miss_tokens`（DeepSeek 实测形状）；OpenAI 官方另有
 *   `prompt_tokens_details.cached_tokens` ⇒ 命中部分的回落来源；
 * - Anthropic 系：`message_delta.usage` 的 `input_tokens` / `output_tokens` /
 *   `cache_read_input_tokens` / `cache_creation_input_tokens`（③）。
 */
data class LlmUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cacheHitTokens: Int? = null,
    val cacheMissTokens: Int? = null,
)

/** 流式事件（给调用方的唯一出口）。 */
sealed interface LlmEvent {
    /** 一段增量文本。 */
    data class Delta(val text: String) : LlmEvent

    /**
     * 正常结束（携带 finish reason；拿不到为 null）。
     * [toolCalls] = 本轮累积出的原始工具调用（协议 A；没有则为空表）——
     * 流式期间内存累积、结束一次性交出（程枢版 §9 施工细节第 1 条）。
     *
     * [usage] = 本轮用量读数（刀 3 B 段；请求体带 `stream_options.include_usage`
     * 时服务端在流末回一帧纯 usage；没回 ⇒ null，**不报错**——派工单 B 段第 6 条）。
     *
     * [reasoningChars] = 本轮思考内容的总字数（刀 3 B 段 C 段；服务端没回
     * `reasoning_content` ⇒ null）。**只有字数**：思考原文不进正文、不进历史
     * （派工单 C 段第 10 条，判据 J9）——这里故意不带原文，想让原文也出不去。
     */
    data class Finished(
        val reason: String?,
        val toolCalls: List<RawToolCall> = emptyList(),
        val usage: LlmUsage? = null,
        val reasoningChars: Int? = null,
    ) : LlmEvent

    /**
     * 失败。异常**原文只进这里与 RunLog**，给用户看的文案由调用方人话化
     * （与 RawErrorToUserGuardTest 钉的口径一致）。
     */
    /**
     * @param raw 服务端原文片段（≤300 字，只给 run_log.err 落库用；UI 拿的仍是
     *            humanize 后的人话——原文不进界面，这是「HTTP错误转Failed且原文只进日志」
     *            判据的延续：判据管的是 UI 文案，err 列是工程取证，两者不冲突）。
     */
    data class Failed(val error: Throwable, val raw: String? = null) : LlmEvent
}

/**
 * 协议形状的**纯函数**层（无 OkHttp、无 Android，可 JVM 单测）。
 *
 * 为什么单独抽：URL 许可、端点路径、请求头优先级、请求体字段这四张表是
 * "换 provider 时会不会静默走错协议"的唯一判据，必须在单测里逐格钉死。
 */
internal object LlmWire {

    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Anthropic 要求的版本头（公开文档固定值）。 */
    const val ANTHROPIC_VERSION = "2023-06-01"

    /** Anthropic 必填的 max_tokens 默认值（其 API 不接受缺省）。 */
    const val ANTHROPIC_MAX_TOKENS = 4096

    /**
     * OpenAI 兼容分支 `max_tokens` 的回落值（刀 3 B 段）：provider 没声明
     * [Provider.maxOutput] 时用这个——不发送上限让服务端猜，是本批要修的病灶之一。
     */
    const val OPENAI_MAX_TOKENS_FALLBACK = 8192

    /**
     * 端点路径：baseUrl 一律自带版本前缀（`/v1`、`/zen/v1`），这里只拼资源路径。
     * 尾部斜杠容忍——用户手填 baseUrl 带不带 `/` 都能用。
     */
    fun endpoint(protocol: LlmProtocol, baseUrl: String): String = when (protocol) {
        LlmProtocol.OPENAI_COMPAT -> baseUrl.trimEnd('/') + "/chat/completions"
        LlmProtocol.ANTHROPIC -> baseUrl.trimEnd('/') + "/messages"
    }

    /**
     * 请求头。合并顺序＝协议默认 → 用户自定义（[Provider.headers]）**最后放，
     * 允许覆盖协议默认**（免费通道的伪装头、用户自建网关的特殊头都走这条）。
     */
    fun headersOf(p: Provider): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        when (p.protocol) {
            LlmProtocol.OPENAI_COMPAT ->
                if (p.apiKey.isNotEmpty()) put("Authorization", "Bearer ${p.apiKey}")

            LlmProtocol.ANTHROPIC -> {
                if (p.apiKey.isNotEmpty()) put("x-api-key", p.apiKey)
                put("anthropic-version", ANTHROPIC_VERSION)
            }
        }
        putAll(p.headers)
    }

    /** SSE 专用头（仓库既有口径：禁压缩，否则事件流不逐条 flush）。 */
    fun sseHeaders(): Map<String, String> = mapOf(
        "Accept" to "text/event-stream",
        "Accept-Encoding" to "identity",
    )

    /**
     * 请求体。四个特殊处理：
     * 1. `system` 角色**不是消息**，要提到顶层 `system` 字段（多条合并）；
     * 2. `max_tokens` 取 [Provider.maxOutput]（刀 3 B 段：它以前只用于内部预算，
     *    根本没进请求体——输出上限全靠服务端猜）；拿不到时 OpenAI 分支回落
     *    [OPENAI_MAX_TOKENS_FALLBACK]、Anthropic 分支回落 [ANTHROPIC_MAX_TOKENS]；
     * 3. 流式时 OpenAI 兼容分支带 `stream_options.include_usage = true`（刀 3 B 段）：
     *    向服务端要用量读数，否则流末那帧纯 usage 不回。Anthropic 的用量在
     *    `message_start`/`message_delta` 事件里天然带回，没有这个开关（③按公开文档）；
     * 4. OpenAI 兼容分支按 [Provider.reasoningEffort] 发 `reasoning_effort`
     *    （刀 3 B 段 C 段）。**只发这一个字段名**——`effort` / `max_reasoning_effort`
     *    是假字段（塞非法值仍返回 200，静默忽略；① 蓝总管实测
     *    `D:\zd-tools\ds-effort-probe2.txt`）。provider 没声明 ⇒ 不带字段
     *    （不替不认这个字段的通道瞎发）；脏值 ⇒ 不发（服务端对非法值 422，
     *    [ModelParams.normalizeEffort] 过一遍）。Anthropic 的形状是 `thinking`
     *    参数不是这个字段，且它的预设从不声明 effort ⇒ 不加。
     *
     * [tools] = 协议 A 的工具数组（`ToolRegistry.toolsJsonOpenAi/Anthropic` 产出）。
     * null/空 = 不带字段（**向后兼容刀 0b 的 15 条单测**：老调用点不传 tools）。
     */
    fun bodyOf(
        p: Provider,
        messages: List<LlmMessage>,
        stream: Boolean = true,
        tools: JSONArray? = null,
    ): String =
        when (p.protocol) {
            LlmProtocol.OPENAI_COMPAT -> JSONObject()
                .put("model", p.model)
                .put("stream", stream)
                .put("max_tokens", p.maxOutput ?: OPENAI_MAX_TOKENS_FALLBACK)
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
                            obj.put("content", contentBlocks(m, LlmProtocol.OPENAI_COMPAT))
                        }
                        arr.put(obj)
                    }
                })
                .apply { if (tools != null && tools.length() > 0) put("tools", tools) }
                .toString()

            LlmProtocol.ANTHROPIC -> {
                val system = messages.filter { it.role == LlmMessage.ROLE_SYSTEM }
                    .joinToString("\n\n") { it.content }
                val rest = messages.filter { it.role != LlmMessage.ROLE_SYSTEM }
                JSONObject()
                    .put("model", p.model)
                    .put("stream", stream)
                    .put("max_tokens", p.maxOutput ?: ANTHROPIC_MAX_TOKENS)
                    .apply { if (system.isNotEmpty()) put("system", system) }
                    .put("messages", JSONArray().also { arr ->
                        rest.forEach { m ->
                            val obj = JSONObject().put("role", m.role)
                            if (m.images.isEmpty()) {
                                obj.put("content", m.content) // V1：同上，逐字节等价
                            } else {
                                obj.put("content", contentBlocks(m, LlmProtocol.ANTHROPIC))
                            }
                            arr.put(obj)
                        }
                    })
                    .apply { if (tools != null && tools.length() > 0) put("tools", tools) }
                    .toString()
            }
        }

        /**
         * 多段 content（判据 V2）：`[{type:text}, {type:image…}…]`。
         *
         * **text 段在前、图片段在后**——不只是审美：image 夹在 text 中间时部分端点
         * 会拒（公开文档都把图放在最后），而且 text 在前对人类可读的日志更友好。
         * `content` 为空则不造空 text 段（空串的 text 块有的端点会 400）。
         *
         * 两种协议形状不同，故按 protocol 分派：
         * - OpenAI 兼容：`{"type":"image_url","image_url":{"url":"data:…"}}`（公开文档形态）
         * - Anthropic：`{"type":"image","source":{"type":"base64","media_type":…,"data":…}}`
         */
        private fun contentBlocks(m: LlmMessage, protocol: LlmProtocol): JSONArray {
            val blocks = JSONArray()
            if (m.content.isNotEmpty()) {
                blocks.put(JSONObject().put("type", "text").put("text", m.content))
            }
            for (url in m.images) {
                when (protocol) {
                    LlmProtocol.OPENAI_COMPAT -> blocks.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", url)),
                    )
                    LlmProtocol.ANTHROPIC -> {
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
                }
            }
            return blocks
        }

        /**
         * 非流式响应的正文（**纯函数**，坏 JSON 返回 null 不抛）。
         *
         * 压缩只吃这一条路径（[LlmClient.completeOnce] 用）；流式那边走
         * [LlmSse.deltaText]，两条不混——非流式与流式的字段形状本来就不一样
         * （`choices[0].message.content` vs `choices[0].delta.content`），
         * 硬凑一个函数只会两边都将就。
         */
        fun plainTextOf(protocol: LlmProtocol, raw: String): String? = runCatching {
            val json = JSONObject(raw)
            when (protocol) {
                LlmProtocol.OPENAI_COMPAT -> json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")

                LlmProtocol.ANTHROPIC -> {
                    val arr = json.optJSONArray("content")
                    if (arr == null) {
                        null
                    } else {
                        buildString {
                            for (i in 0 until arr.length()) {
                                val b = arr.optJSONObject(i) ?: continue
                                if (b.optString("type") == "text") append(b.optString("text"))
                            }
                        }.takeIf { it.isNotEmpty() }
                    }
                }
            }
        }.getOrNull()
}

/**
 * SSE 帧解析（纯函数，无 OkHttp/Android）。
 *
 * 三条语义与既有 SseEventParser 对齐：
 * 1. `data:` 多行用 `\n` 连接成一个载荷（SSE 规范）；
 * 2. `:` 开头是注释行，忽略；
 * 3. **坏 JSON 绝不抛**——解析失败返回 null，由调用方决定跳过还是记日志
 *       （"不因解析失败而崩"是既有设计约束）。
 */
internal object LlmSse {

    /** OpenAI 兼容的终止标记。 */
    const val TERMINATOR = "[DONE]"

    /** 一帧的累积器：喂行，出完整 data 载荷（无则 null）。 */
    class FrameBuffer {
        private val data = StringBuilder()
        private var sawData = false

        fun line(line: String): String? = when {
            line.isEmpty() -> {
                val out = if (sawData) data.toString() else null
                data.setLength(0); sawData = false
                out
            }

            line.startsWith(":") -> null
            line.startsWith("data:") -> {
                if (sawData) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
                sawData = true
                null
            }

            else -> null // event: / id: / retry: 本项目不消费（事件语义从载荷 JSON 里取）
        }
    }

    fun isTerminator(payload: String): Boolean = payload.trim() == TERMINATOR

    /** 取一帧里的增量文本；没有增量（或坏 JSON）返回 null。 */
    fun deltaText(protocol: LlmProtocol, payload: String): String? = runCatching {
        val json = JSONObject(payload)
        when (protocol) {
            LlmProtocol.OPENAI_COMPAT ->
                json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("delta")?.optString("content")
                    ?.takeIf { it.isNotEmpty() && it != "null" }

            LlmProtocol.ANTHROPIC ->
                if (json.optString("type") == "content_block_delta") {
                    json.optJSONObject("delta")
                        ?.takeIf { it.optString("type") == "text_delta" }
                        ?.optString("text")
                        ?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
        }
    }.getOrNull()

    /** 取一帧里的 finish reason；没有返回 null。 */
    fun finishReason(protocol: LlmProtocol, payload: String): String? = runCatching {
        val json = JSONObject(payload)
        when (protocol) {
            LlmProtocol.OPENAI_COMPAT ->
                json.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")
                    ?.takeIf { it.isNotEmpty() && it != "null" }

            LlmProtocol.ANTHROPIC -> when (json.optString("type")) {
                "message_stop" -> "stop"
                "message_delta" -> json.optJSONObject("delta")?.optString("stop_reason")
                    ?.takeIf { it.isNotEmpty() && it != "null" }

                else -> null
            }
        }
    }.getOrNull()

    /**
     * 取一帧里的用量读数（刀 3 B 段；该帧没有 usage ⇒ 返回 null，**不报错**——
     * 派工单 B 段第 6 条：stream_options 或 usage 缺失时记 null，界面照常）。
     *
     * OpenAI 兼容（顶层 `usage` 对象）。两种到达形态都吃：
     * - ① DeepSeek 官方通道实测（`D:\zd-tools\ds-stream-probe.txt` frame#65）：
     *   usage 与 `finish_reason` **同帧**；
     * - OpenAI 官方形态：流末**单独一帧**（`choices` 为空数组）纯 usage。
     * 字段映射：`prompt_tokens`/`completion_tokens`；
     * `completion_tokens_details.reasoning_tokens` ⇒ 思考烧掉的量；
     * `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens`（DeepSeek 实测），
     * 回落 `prompt_tokens_details.cached_tokens`（OpenAI 官方）⇒ 命中部分。
     *
     * Anthropic（③ 按公开文档）：用量**不在单独帧**——
     * - `message_start` 的 `message.usage`：`input_tokens` /
     *   `cache_read_input_tokens` / `cache_creation_input_tokens`；
     * - `message_delta` 的 `usage`：`input_tokens` / `output_tokens`。
     * 一帧拿不全 ⇒ 调用方按 [mergeUsage] 逐帧合并才拿得全。
     *
     * 「没测到不编数」：字段缺失 ⇒ 该列 null（0 与 null 严格分开，见 [LlmUsage]）。
     */
    fun usageOf(protocol: LlmProtocol, payload: String): LlmUsage? = runCatching {
        val json = JSONObject(payload)
        when (protocol) {
            LlmProtocol.OPENAI_COMPAT -> {
                val u = json.optJSONObject("usage") ?: return@runCatching null
                LlmUsage(
                    promptTokens = u.optIntOrNull("prompt_tokens"),
                    completionTokens = u.optIntOrNull("completion_tokens"),
                    reasoningTokens = u.optJSONObject("completion_tokens_details")
                        ?.optIntOrNull("reasoning_tokens"),
                    cacheHitTokens = u.optIntOrNull("prompt_cache_hit_tokens")
                        ?: u.optJSONObject("prompt_tokens_details")?.optIntOrNull("cached_tokens"),
                    cacheMissTokens = u.optIntOrNull("prompt_cache_miss_tokens"),
                )
            }

            LlmProtocol.ANTHROPIC -> when (json.optString("type")) {
                "message_start" -> {
                    val u = json.optJSONObject("message")?.optJSONObject("usage")
                        ?: return@runCatching null
                    LlmUsage(
                        promptTokens = u.optIntOrNull("input_tokens"),
                        cacheHitTokens = u.optIntOrNull("cache_read_input_tokens"),
                        cacheMissTokens = u.optIntOrNull("cache_creation_input_tokens"),
                    )
                }

                "message_delta" -> {
                    val u = json.optJSONObject("usage") ?: return@runCatching null
                    LlmUsage(
                        promptTokens = u.optIntOrNull("input_tokens"),
                        completionTokens = u.optIntOrNull("output_tokens"),
                    )
                }

                else -> null
            }
        }
    }.getOrNull()

    /**
     * 合并两帧的用量读数（Anthropic 的 usage 跨 `message_start`/`message_delta`
     * 两帧，必须合并才拿得全；OpenAI 系单帧给全，合并是恒等）。
     * 口径：**后到且非 null 的字段赢**——同字段重复出现取最新读数，null 不抹掉旧值。
     */
    fun mergeUsage(old: LlmUsage?, new: LlmUsage): LlmUsage {
        if (old == null) return new
        return LlmUsage(
            promptTokens = new.promptTokens ?: old.promptTokens,
            completionTokens = new.completionTokens ?: old.completionTokens,
            reasoningTokens = new.reasoningTokens ?: old.reasoningTokens,
            cacheHitTokens = new.cacheHitTokens ?: old.cacheHitTokens,
            cacheMissTokens = new.cacheMissTokens ?: old.cacheMissTokens,
        )
    }

    /** optInt 的「没测到」版：键不存在（或不是数）⇒ null——0 与「没读到」严格分开。 */
    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key)) runCatching { getInt(key) }.getOrNull() else null

    /**
     * 取一帧里的**思考内容**（刀 3 B 段 C 段；没有 ⇒ null，坏 JSON 不抛）。
     *
     * ① DeepSeek 官方通道实测（`D:\zd-tools\ds-stream-probe.txt`：每帧 delta 同时带
     * `content` 与 `reasoning_content`，**两个独立字段**）：OpenAI 兼容取
     * `choices[0].delta.reasoning_content`。
     * Anthropic（③ 按公开文档）：`content_block_delta` 且 `delta.type ==
     * "thinking_delta"` ⇒ 取 `delta.thinking`。
     *
     * ⚠️ 调用方只许拿它**累计字数**（见 [LlmEvent.Finished.reasoningChars]）：
     * 不许拼进正文、不许进对话历史——派工单 C 段第 10 条，判据 J9。
     */
    fun reasoningText(protocol: LlmProtocol, payload: String): String? = runCatching {
        val json = JSONObject(payload)
        when (protocol) {
            LlmProtocol.OPENAI_COMPAT ->
                json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("delta")?.optString("reasoning_content")
                    ?.takeIf { it.isNotEmpty() && it != "null" }

            LlmProtocol.ANTHROPIC ->
                if (json.optString("type") == "content_block_delta") {
                    json.optJSONObject("delta")
                        ?.takeIf { it.optString("type") == "thinking_delta" }
                        ?.optString("thinking")
                        ?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
        }
    }.getOrNull()

    /**
     * 取一帧里的 tool_call 增量（协议 A；坏 JSON 不抛，返回空表）。
     * OpenAI 兼容：`choices[0].delta.tool_calls[]`（index/id/function.name/function.arguments）；
     * Anthropic：`content_block_start`（tool_use 的 id/name）＋ `content_block_delta`
     * （input_json_delta 的 partial_json）。
     */
    fun toolCallDeltas(protocol: LlmProtocol, payload: String): List<ToolCallDelta> = runCatching {
        val json = JSONObject(payload)
        when (protocol) {
            LlmProtocol.OPENAI_COMPAT -> {
                val arr = json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("delta")?.optJSONArray("tool_calls")
                if (arr == null) {
                    emptyList()
                } else {
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val fn = o.optJSONObject("function")
                        ToolCallDelta(
                            index = o.optInt("index", i),
                            id = o.optString("id").takeIf { it.isNotEmpty() && it != "null" },
                            name = fn?.optString("name")?.takeIf { it.isNotEmpty() && it != "null" },
                            argumentsDelta = fn?.optString("arguments")?.takeIf { it.isNotEmpty() && it != "null" },
                        )
                    }
                }
            }

            LlmProtocol.ANTHROPIC -> when (json.optString("type")) {
                "content_block_start" -> {
                    val b = json.optJSONObject("content_block") ?: return@runCatching emptyList()
                    if (b.optString("type") != "tool_use") emptyList()
                    else listOf(
                        ToolCallDelta(
                            index = json.optInt("index", 0),
                            id = b.optString("id").takeIf { it.isNotEmpty() && it != "null" },
                            name = b.optString("name").takeIf { it.isNotEmpty() && it != "null" },
                        ),
                    )
                }

                "content_block_delta" -> {
                    val d = json.optJSONObject("delta") ?: return@runCatching emptyList()
                    if (d.optString("type") != "input_json_delta") emptyList()
                    else listOf(
                        ToolCallDelta(
                            index = json.optInt("index", 0),
                            argumentsDelta = d.optString("partial_json").takeIf { it.isNotEmpty() && it != "null" },
                        ),
                    )
                }

                else -> emptyList()
            }
        }
    }.getOrDefault(emptyList())

    /**
     * tool_call 累积器（流式）：按 index 把多帧增量合并，结束时 [finish] 出原始调用。
     * arguments 是 JSON 字符串分片拼接，**结束后才 parse**（流式不能逐片 parse）。
     */
    class ToolCallAccumulator {
        private data class Slot(
            var id: String = "",
            var name: String = "",
            val args: StringBuilder = StringBuilder(),
        )

        private val slots = HashMap<Int, Slot>()

        fun accept(deltas: List<ToolCallDelta>) {
            for (d in deltas) {
                val slot = slots.getOrPut(d.index) { Slot() }
                d.id?.let { slot.id = it }
                d.name?.let { slot.name = it }
                d.argumentsDelta?.let { slot.args.append(it) }
            }
        }

        fun isEmpty(): Boolean = slots.isEmpty()

        /** 结束累积，产出按 index 排序的原始调用。 */
        fun finish(): List<RawToolCall> = slots.entries
            .sortedBy { it.key }
            .map { (_, s) -> RawToolCall(id = s.id, name = s.name, argumentsJson = s.args.toString()) }
    }
}

/**
 * LLM 客户端（刀 0 · 2026-10-16）：OkHttp + SSE 流式。
 *
 * ## 线程模型
 * 阻塞解析跑在**进程级单线程** [netExecutor]（`zd-baopu-net`，daemon）上：
 * 刀 0/1 是单活跃会话单流，串行足够；将来真要多流，再换线程池并回来改
 * BareThreadGuardTest 登记（换名字 = 改对账单，不是悄悄加）。
 *
 * ## 超时口径（仓库既有 SSE 客户端口径，记录在此）
 * readTimeout 60s 是**活性兜底**（高于服务端心跳），不是数据超时；
 * callTimeout 绝不设（默认 0）——它限制整个长连接寿命，对流式致命。
 * （原参照件 oc/OcClient.kt 已随 oc/ 清场删除，口径不再指向仓库外文件名。）
 */
/**
 * 一次性补全的结果（第十三批 A 段 · 压缩专用）。
 *
 * 为什么**不复用** [LlmEvent]：那条是流式的（Delta/Finished/Failed 三态、带 usage
 * 与 tool_calls 累积）；压缩只要「整段文本 / 失败原因」两态，塞进流式事件会让
 * 调用方为每个字段写一坨无关分支。
 *
 * [Ok.model] 必须回传（落 `compression.model` 列——压过之后要能追溯是谁压的）。
 */
sealed interface CompletionResult {
    data class Ok(val text: String, val model: String) : CompletionResult

    /** [code] = HTTP 状态码；-1 = 响应里没文本；-2 = 传输层异常（超时/断连）。 */
    data class Failed(val code: Int, val message: String) : CompletionResult
}

class LlmClient(private val provider: Provider) {

    private val http: OkHttpClient = defaultHttp()

    /**
     * 构造请求（纯形状，不执行）。[tools] 为 null/空时不带字段（向后兼容）。
     *
     * 刀 3 B 段 C 段：思考深度**每轮现读**用户档位（[ModelParams.liveOverlay]）——
     * 设置页改完下一轮请求体就变，不用重建引擎（进程级缓存由装配期
     * [ModelParams.initEffort] 与设置页落盘时各刷一次）。只盖通道显式声明了
     * effort 的：不替不认这个字段的通道瞎发。
     */
    fun chatRequest(messages: List<LlmMessage>, tools: JSONArray? = null): Request {
        val body = LlmWire.bodyOf(
            ModelParams.liveOverlay(provider),
            messages,
            tools = tools,
        ).toRequestBody(LlmWire.JSON_MEDIA_TYPE)
        return Request.Builder()
            .url(LlmWire.endpoint(provider.protocol, provider.baseUrl))
            .apply {
                post(body)
                LlmWire.headersOf(provider).forEach { (k, v) -> header(k, v) }
                LlmWire.sseHeaders().forEach { (k, v) -> header(k, v) }
            }
            .build()
    }

    /** 异步流式：把阻塞解析丢到 [netExecutor]，事件回 [sink]。 */
    fun streamAsync(messages: List<LlmMessage>, tools: JSONArray? = null, sink: (LlmEvent) -> Unit) {
        val call = http.newCall(chatRequest(messages, tools))
        netExecutor.execute { stream(call, sink) }
    }

    /**
     * 阻塞流式解析（调用方供给线程）。
     *
     * 失败一律转 [LlmEvent.Failed]，**不抛**——抛异常会让调用方在两处处理
     * （try/catch + sink），转事件只有一条出口。协程取消除外：那是取消不是失败。
     *
     * tool_calls 在流内按 index 累积（[LlmSse.ToolCallAccumulator]），
     * usage 逐帧合并（[LlmSse.mergeUsage]），[LlmEvent.Finished] 一次性带出
     * （判据：流式期间内存累积，不每帧落库）。
     */
    fun stream(call: Call, sink: (LlmEvent) -> Unit) {
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val snippet = runCatching {
                        resp.body?.string()?.take(ERROR_BODY_SNIPPET)
                    }.getOrNull()
                    RunLog.log(
                        "抱朴 LLM：HTTP ${resp.code} ${resp.message}" +
                            if (snippet.isNullOrBlank()) "" else " body=${snippet.replace('\n', ' ')}"
                    )
                    // 原文跟事件走（不进异常 message——那条判据钉死了 UI 文案不带原文），
                    // 引擎把它落 run_log.err，蓝总管侧才拿得到第一手错因（刀 1 补丁硬要求）。
                    sink(LlmEvent.Failed(IOException("HTTP ${resp.code}"), raw = snippet))
                    return
                }
                val body = resp.body ?: run {
                    sink(LlmEvent.Failed(IOException("响应无 body")))
                    return
                }
                val frames = LlmSse.FrameBuffer()
                val toolCalls = LlmSse.ToolCallAccumulator()
                val source = body.source()
                // 刀 3 B 段：循环从「见到 finish_reason 立即收尾」改成「读到终止标记
                // 或 EOF 再收尾」——usage 帧可能在 finish_reason **之后**（OpenAI 官方
                // 形态：流末单独一帧 choices 为空；① DeepSeek 官方通道实测
                // ds-stream-probe.txt：usage 与 finish_reason 同帧、[DONE] 紧跟）。
                // 两种形态都吃：每帧都查 usage 并合并，收尾时一并带出。
                // 已核实的代价：finish_reason 后服务端若既不发 [DONE] 也不关连接，
                // 会读到 readTimeout（60s）才判失败——按 SSE/OpenAI 规范不该发生，
                // 且主力通道（DeepSeek 官方）实测 [DONE] 紧跟其后（①）。
                var usage: LlmUsage? = null
                var stopReason: String? = null
                var sawTerminator = false
                var reasoningChars = 0
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    val payload = frames.line(line) ?: continue
                    if (LlmSse.isTerminator(payload)) {
                        sawTerminator = true
                        break
                    }
                    LlmSse.deltaText(provider.protocol, payload)?.let { sink(LlmEvent.Delta(it)) }
                    toolCalls.accept(LlmSse.toolCallDeltas(provider.protocol, payload))
                    LlmSse.usageOf(provider.protocol, payload)?.let {
                        usage = LlmSse.mergeUsage(usage, it)
                    }
                    // 思考内容只数字数（刀 3 B 段 C 段：不进正文不进历史，J9）。
                    // 0 字（没回过 reasoning）⇒ 报 null 不报 0——「没想」与「想了 0 字」
                    // 在这条读数上没有区别的必要，但 null 的语义更诚实。
                    LlmSse.reasoningText(provider.protocol, payload)?.let { reasoningChars += it.length }
                    val reason = LlmSse.finishReason(provider.protocol, payload)
                    // 首个 finish_reason 赢：Anthropic 的 message_delta（end_turn）之后
                    // 还有 message_stop（stop），后者不许覆盖前者（刀 0 口径延续）。
                    if (reason != null && stopReason == null) stopReason = reason
                }
                val chars = reasoningChars.takeIf { it > 0 }
                when {
                    // 见到过完成标记 ⇒ 正常结束（usage 有就带上；没有 ⇒ null，不报错）
                    stopReason != null ->
                        sink(LlmEvent.Finished(stopReason, toolCalls.finish(), usage, chars))

                    // 只有 [DONE]、没有 finish_reason 帧 ⇒ 按老口径当 stop
                    // （部分兼容通道不发 finish_reason，刀 0 起就是这么容的）
                    sawTerminator ->
                        sink(LlmEvent.Finished("stop", toolCalls.finish(), usage, chars))

                    // 流在完成标记前就结束了 = 截断。**不报告成功**——
                    // "看着成功其实没有"是本项目最反感的事故形态（E-020/E-045/E-054 同款）。
                    else ->
                        sink(LlmEvent.Failed(IOException("流在完成标记前结束（疑似截断）")))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sink(LlmEvent.Failed(e))
        }
    }

    /**
     * 压缩请求的请求体（**纯函数**，给 J2 当靶子）。
     *
     * 两条红线在这里钉死（派工单 §3.3）：**不带 `tools`**（压缩不许触发工具调用）、
     * **不带 `stream`**（压缩要一次拿到整段文本）。做法是直接复用
     * [LlmWire.bodyOf] 的 `stream = false` 分支——它顺带也不加 `stream_options`，
     * 两条红线都由既有纯函数兜住，不另写一份 body 拼装（两份必漂移）。
     *
     * `max_tokens` 盖成压缩上限（≤ [COMPLETE_MAX_TOKENS]，摘要不该长篇），
     * `temperature` 压到 0.2（摘要要稳定，不要每次压出不同版本）。
     */
    fun completeBody(messages: List<LlmMessage>, maxTokens: Int, temperature: Double): String =
        JSONObject(LlmWire.bodyOf(provider, messages, stream = false))
            .put("max_tokens", maxTokens.coerceAtMost(COMPLETE_MAX_TOKENS))
            .put("temperature", temperature)
            .toString()

    /**
     * 一次请求、拿整段文本（第十三批 A 段 · 压缩用；**不动**流式路径）。
     *
     * 与 [streamAsync] 的三处不同，都是压缩这个场景要的：
     * 1. **非流式**：一次 `execute()` 拿完整 body，不进 `zd-baopu-net` 线程、
     *    不产生用户可见气泡（派工单红线：压缩不许进对话气泡）；
     * 2. **带 `callTimeout`**：流式那边绝不设（会砍长连接寿命，见类 KDoc），
     *    这里是一次请求，超时必须真的能砍 ⇒ 单独 clone 一份 client；
     * 3. **失败一律转 [CompletionResult.Failed]，不抛**——与 [stream] 同口径
     *    （"不把异常抛给整轮"是本项目的一贯要求：抛上去整轮就炸了，J6 钉的就是这条）。
     */
    fun completeOnce(
        messages: List<LlmMessage>,
        maxTokens: Int = COMPLETE_MAX_TOKENS,
        temperature: Double = COMPLETE_TEMPERATURE,
        timeoutMs: Long = COMPLETE_TIMEOUT_MS,
    ): CompletionResult {
        val body = completeBody(messages, maxTokens, temperature)
            .toRequestBody(LlmWire.JSON_MEDIA_TYPE)
        val req = Request.Builder()
            .url(LlmWire.endpoint(provider.protocol, provider.baseUrl))
            .apply {
                post(body)
                LlmWire.headersOf(provider).forEach { (k, v) -> header(k, v) }
            }
            .build()
        val http = defaultHttp().newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val snippet = runCatching {
                        resp.body?.string()?.take(ERROR_BODY_SNIPPET)
                    }.getOrNull()
                    RunLog.log(
                        "抱朴 LLM（压缩）：HTTP ${resp.code} ${resp.message}" +
                            if (snippet.isNullOrBlank()) "" else " body=${snippet.replace('\n', ' ')}",
                    )
                    return CompletionResult.Failed(resp.code, "HTTP ${resp.code} ${resp.message}")
                }
                val raw = resp.body?.string().orEmpty()
                val text = LlmWire.plainTextOf(provider.protocol, raw)
                if (text.isNullOrBlank()) {
                    CompletionResult.Failed(NO_TEXT_CODE, "响应里没有可用文本（长度 ${raw.length}）")
                } else {
                    CompletionResult.Ok(text, provider.model)
                }
            }
        } catch (e: Exception) {
            CompletionResult.Failed(TRANSPORT_CODE, e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        /** HTTP 错误体记日志时的截断长度（只取开头，够定位）。 */
        private const val ERROR_BODY_SNIPPET = 300

        /** 压缩摘要的 token 上限（派工单 §3.3：≤ 1024，摘要不需要长篇）。 */
        const val COMPLETE_MAX_TOKENS = 1024

        /** 压缩温度（摘要要稳定，压两次不该压出两个版本）。 */
        const val COMPLETE_TEMPERATURE = 0.2

        /** 压缩请求默认超时（60s，与流式 readTimeout 同量级，靠 callTimeout 真的砍）。 */
        const val COMPLETE_TIMEOUT_MS = 60_000L

        /** [CompletionResult.Failed.code]：响应里没有文本。 */
        const val NO_TEXT_CODE = -1

        /** [CompletionResult.Failed.code]：传输层异常（超时/断连，没有 HTTP 状态码）。 */
        const val TRANSPORT_CODE = -2

        /**
         * 进程级单线程网络 lane（`zd-baopu-net`，daemon）。
         * lazy：第一个客户端才建线程；没建过就不占资源。
         */
        val netExecutor: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "zd-baopu-net").apply { isDaemon = true }
            }
        }

        /** 共享 OkHttp 实例（连接池全局复用；超时口径见类 KDoc）。 */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)   // 活性兜底，非数据超时
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)     // 重连是引擎层的事，客户端不暗自重试
            .build()
    }
}
