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

/** 一条对话消息（刀 0 只要纯文本；多模态 part 归后续刀）。 */
data class LlmMessage(val role: String, val content: String) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** 流式事件（给调用方的唯一出口）。 */
sealed interface LlmEvent {
    /** 一段增量文本。 */
    data class Delta(val text: String) : LlmEvent

    /** 正常结束（携带 finish reason；拿不到为 null）。 */
    data class Finished(val reason: String?) : LlmEvent

    /**
     * 失败。异常**原文只进这里与 RunLog**，给用户看的文案由调用方人话化
     * （与 RawErrorToUserGuardTest 钉的口径一致）。
     */
    data class Failed(val error: Throwable) : LlmEvent
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
     * 请求体。Anthropic 的两个特殊处理：
     * 1. `system` 角色**不是消息**，要提到顶层 `system` 字段（多条合并）；
     * 2. `max_tokens` 必填（拿不到用户设置时的默认值）。
     */
    fun bodyOf(p: Provider, messages: List<LlmMessage>, stream: Boolean = true): String =
        when (p.protocol) {
            LlmProtocol.OPENAI_COMPAT -> JSONObject()
                .put("model", p.model)
                .put("stream", stream)
                .put("messages", JSONArray().also { arr ->
                    messages.forEach { m ->
                        arr.put(JSONObject().put("role", m.role).put("content", m.content))
                    }
                })
                .toString()

            LlmProtocol.ANTHROPIC -> {
                val system = messages.filter { it.role == LlmMessage.ROLE_SYSTEM }
                    .joinToString("\n\n") { it.content }
                val rest = messages.filter { it.role != LlmMessage.ROLE_SYSTEM }
                JSONObject()
                    .put("model", p.model)
                    .put("stream", stream)
                    .put("max_tokens", ANTHROPIC_MAX_TOKENS)
                    .apply { if (system.isNotEmpty()) put("system", system) }
                    .put("messages", JSONArray().also { arr ->
                        rest.forEach { m ->
                            arr.put(JSONObject().put("role", m.role).put("content", m.content))
                        }
                    })
                    .toString()
            }
        }
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
class LlmClient(private val provider: Provider) {

    private val http: OkHttpClient = defaultHttp()

    /** 构造请求（纯形状，不执行）。 */
    fun chatRequest(messages: List<LlmMessage>): Request {
        val body = LlmWire.bodyOf(provider, messages).toRequestBody(LlmWire.JSON_MEDIA_TYPE)
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
    fun streamAsync(messages: List<LlmMessage>, sink: (LlmEvent) -> Unit) {
        val call = http.newCall(chatRequest(messages))
        netExecutor.execute { stream(call, sink) }
    }

    /**
     * 阻塞流式解析（调用方供给线程）。
     *
     * 失败一律转 [LlmEvent.Failed]，**不抛**——抛异常会让调用方在两处处理
     * （try/catch + sink），转事件只有一条出口。协程取消除外：那是取消不是失败。
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
                    sink(LlmEvent.Failed(IOException("HTTP ${resp.code}")))
                    return
                }
                val body = resp.body ?: run {
                    sink(LlmEvent.Failed(IOException("响应无 body")))
                    return
                }
                val frames = LlmSse.FrameBuffer()
                val source = body.source()
                var finished = false
                while (!finished) {
                    val line = source.readUtf8Line() ?: break
                    val payload = frames.line(line) ?: continue
                    if (LlmSse.isTerminator(payload)) {
                        sink(LlmEvent.Finished("stop"))
                        finished = true
                        break
                    }
                    LlmSse.deltaText(provider.protocol, payload)?.let { sink(LlmEvent.Delta(it)) }
                    val reason = LlmSse.finishReason(provider.protocol, payload)
                    if (reason != null) {
                        sink(LlmEvent.Finished(reason))
                        finished = true
                    }
                }
                if (!finished) {
                    // 流在完成标记前就结束了 = 截断。**不报告成功**——
                    // "看着成功其实没有"是本项目最反感的事故形态（E-020/E-045/E-054 同款）。
                    sink(LlmEvent.Failed(IOException("流在完成标记前结束（疑似截断）")))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sink(LlmEvent.Failed(e))
        }
    }

    companion object {
        /** HTTP 错误体记日志时的截断长度（只取开头，够定位）。 */
        private const val ERROR_BODY_SNIPPET = 300

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
