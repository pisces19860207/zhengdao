// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十三批 · MCP 工具接入 v1：HTTP/SSE 传输 + MCP 客户端。
// 红线：只发 HTTP(S) 请求，不拉进程、不读本地配置、不执行服务器请求/代码。
package com.example.zhengdao.baopu.mcp

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 一次已建立的 MCP 会话（[McpClient] 的真实实现；单测可注入假件）。
 *
 * 抽接口的唯一目的：把「授权/注册/执行器闸」与网络分开测——`McpToolSourceTest`
 * 用假会话数请求次数，`McpClientTest` 用 MockWebServer 测线形。
 */
internal interface McpSession : Closeable {
    val mode: String
    val protocolVersion: String
    fun connect()
    fun listTools(): List<McpToolDescriptor>
    fun callTool(remoteName: String, arguments: JSONObject): McpCallResult
}

/**
 * 一个 MCP server 的客户端（**一次只发一个请求**）。
 *
 * 连接只在 [connect] 时发生——构造器不发任何网络请求。传输优先 Streamable HTTP；
 * 仅当它在 initialize 阶段明确回 404/405/415 时才回退旧式 HTTP+SSE（见设计 §3）。
 *
 * 不打印请求头/URL/响应体；[log] 只拿 server id、阶段、工具数、连接方式。
 */
internal class McpClient(
    private val config: McpServerConfig,
    private val http: OkHttpClient = defaultHttp(config),
    private val log: (String) -> Unit = {},
) : McpSession {

    private val lock = Any()
    private var nextId = 1L

    /** 串行发包也要防两个入口同时取号（防御性；协议本身要求一次一个）。 */
    private fun newId(): Long = synchronized(lock) { nextId++ }
    private var transport: Transport? = null
    private var closed = false

    /** 实际用上的传输（`streamable-http` / `legacy-sse`；测试与报告用）。 */
    @Volatile
    override var mode: String = "未连接"
        private set

    /** 协商出的 MCP 协议版本（服务器不认时保留客户端版本）。 */
    @Volatile
    override var protocolVersion: String = McpJsonRpc.PROTOCOL
        private set

    /** 统一出口：失败一律 [McpException]，userMessage 已清洗。 */
    override fun connect() {
        synchronized(lock) {
            check(!closed) { "MCP 客户端已关闭" }
            if (transport != null) return
            val streamable = StreamableTransport(config, http, log)
            try {
                streamable.open(config.connectTimeoutMs)
                protocolVersion = handshake(streamable)
                transport = streamable
                mode = "streamable-http"
            } catch (e: McpException) {
                runCatching { streamable.close() }
                if (!e.fallbackEligible) throw e
                log("[mcp] ${config.id} Streamable HTTP 不可用（${e.userMessage}），回退旧式 SSE")
                // 长连接不能有读超时；call.cancel 负责关。
                val legacyHttp = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
                val legacy = LegacySseTransport(config, legacyHttp, log)
                try {
                    legacy.open(config.connectTimeoutMs)
                    protocolVersion = handshake(legacy)
                    transport = legacy
                    mode = "legacy-sse"
                } catch (e2: McpException) {
                    runCatching { legacy.close() }
                    throw McpException(
                        e2.failure,
                        "MCP 服务器「${config.name}」连接失败：${e2.userMessage}" +
                            "（Streamable HTTP 也不可用：${e.userMessage}）",
                        cause = e2,
                    )
                }
            }
        }
    }

    /** `tools/list`（含分页；最多 [McpSchema.MAX_TOOLS] 个）。 */
    override fun listTools(): List<McpToolDescriptor> {
        val t = transportOrFail()
        val all = ArrayList<McpToolDescriptor>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val result = t.request(newId(), "tools/list", params, LIST_TIMEOUT_MS)
            val page = McpSchema.parseToolList(config.id, config.name, result)
            all.addAll(page)
            if (all.size > McpSchema.MAX_TOOLS) {
                throw McpException(
                    McpFailure.SCHEMA,
                    "MCP 服务器「${config.name}」公布了 ${all.size} 个工具，超过 v1 上限 ${McpSchema.MAX_TOOLS}",
                )
            }
            pages++
            if (pages > MAX_PAGES) {
                throw McpException(McpFailure.PROTOCOL, "MCP 服务器「${config.name}」的 tools/list 分页过多，已停止")
            }
            // 显式判 null：org.json 各实现对 JSONObject.NULL 的 optString 行为不一，
            // 直接 optString 可能得到字符串 "null" ⇒ 拿它当 cursor 再请求一轮（白打一次服务器）。
            cursor = if (result.isNull("nextCursor")) null
            else result.optString("nextCursor").takeIf { it.isNotBlank() }
        } while (cursor != null)
        log("[mcp] ${config.id} tools/list 完成：${all.size} 个工具")
        return all
    }

    /**
     * `tools/call`。
     *
     * 只做一件事：把 JSON 发出去、把 `content[]` 的文本收回来。**不执行**任何返回值；
     * `isError=true` 原样交给调用方按工具失败回灌。
     */
    override fun callTool(remoteName: String, arguments: JSONObject): McpCallResult {
        val t = transportOrFail()
        val params = JSONObject()
            .put("name", remoteName)
            .put("arguments", arguments)
        val result = t.request(newId(), "tools/call", params, config.callTimeoutMs)
        val text = McpJsonRpc.toolContent(result)
        return McpCallResult(
            text = text.ifEmpty { "（MCP 工具「$remoteName」没有返回文本内容）" },
            isError = result.optBoolean("isError", false),
        )
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            runCatching { transport?.close() }
            transport = null
            mode = "已断开"
        }
    }

    private fun transportOrFail(): Transport {
        synchronized(lock) {
            check(!closed) { "MCP 客户端已关闭" }
            return transport ?: throw McpException(McpFailure.CLOSED, "MCP 服务器「${config.name}」尚未连接")
        }
    }

    /** initialize → 记版本 → notifications/initialized。 */
    private fun handshake(t: Transport): String {
        val params = JSONObject()
            .put("protocolVersion", McpJsonRpc.PROTOCOL)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "zhengdao-baopu").put("version", "v1"))
        val result = t.request(newId(), "initialize", params, config.connectTimeoutMs)
        val rawVersion = result.optString("protocolVersion").trim()
        val negotiated = if (VERSION_RE.matches(rawVersion)) rawVersion else McpJsonRpc.PROTOCOL
        if (negotiated != rawVersion) {
            log("[mcp] ${config.id} 服务器返回未知协议版本「${McpText.clip(McpText.flat(rawVersion), 40)}」，按 ${McpJsonRpc.PROTOCOL} 继续")
        }
        t.protocolVersion = negotiated
        // initialized 是通知，没有响应；失败不阻断（有的实现回 202、有的干脆不接受）。
        runCatching { t.notify("notifications/initialized", null, config.connectTimeoutMs) }
            .onFailure { log("[mcp] ${config.id} initialized 通知未被接受（${it.javaClass.simpleName}）") }
        log("[mcp] ${config.id} 已连接（${t.mode}）")
        return negotiated
    }

    // ── 传输抽象 ─────────────────────────────────────────────────────────

    private interface Transport : Closeable {
        val mode: String
        var protocolVersion: String
        fun open(timeoutMs: Long)
        fun request(id: Long, method: String, params: JSONObject?, timeoutMs: Long): JSONObject
        fun notify(method: String, params: JSONObject?, timeoutMs: Long)
    }

    /**
     * Streamable HTTP：POST 单一端点，响应可以是 JSON 或 SSE。
     *
     * 404/405/415 只对 **initialize** 标 `fallbackEligible`（设计 §3）；其余阶段就当普通
     * HTTP 失败，绝不悄悄换传输。
     */
    private class StreamableTransport(
        private val config: McpServerConfig,
        private val http: OkHttpClient,
        private val log: (String) -> Unit,
    ) : Transport {

        override val mode = "streamable-http"

        @Volatile
        override var protocolVersion: String = McpJsonRpc.PROTOCOL

        @Volatile
        private var sessionId: String? = null

        override fun open(timeoutMs: Long) = Unit

        /** Streamable HTTP 没有长连接要关（每个请求一个 call，`response.use` 已收口）。 */
        override fun close() = Unit

        override fun request(id: Long, method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
            val reply = post(McpJsonRpc.request(id, method, params), timeoutMs, method, readBody = true)
            val raw = reply ?: throw McpException(
                McpFailure.PROTOCOL,
                "MCP 服务器「${config.name}」在${stage(method)}返回了空响应",
            )
            val found = findResponse(raw, id, method)
            return found ?: throw McpException(
                McpFailure.PROTOCOL,
                "MCP 服务器「${config.name}」${stage(method)}的响应 id 对不上（期望 $id）",
            )
        }

        override fun notify(method: String, params: JSONObject?, timeoutMs: Long) {
            post(McpJsonRpc.notification(method, params), timeoutMs, method, readBody = false)
        }

        private fun post(
            body: JSONObject,
            timeoutMs: Long,
            method: String,
            readBody: Boolean,
        ): String? {
            val req = baseRequest(method)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            val call = http.newCall(req)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            val response = execute(call, method, timeoutMs, config.name)
            response.use { r ->
                if (!r.isSuccessful) {
                    throw httpError(r.code, method, config.name, fallback = method == "initialize")
                }
                if (method == "initialize") captureSession(r.header("Mcp-Session-Id"))
                if (!readBody) return null
                val contentType = r.header("Content-Type").orEmpty().lowercase()
                return if (contentType.contains("text/event-stream")) {
                    readSseForId(r, body.optLong("id"), method)
                } else {
                    val text = readText(r)
                    if (text.isBlank()) {
                        // 断连/代理吞响应时最常见的样子：HTTP 头到了，正文是空的。
                        // 归到 CONNECT（可读、可重连），不是「协议 id 对不上」。
                        throw McpException(
                            McpFailure.CONNECT,
                            "MCP 服务器「${config.name}」${stage(method)}没有返回响应（连接可能已断）",
                        )
                    }
                    if (text.trimStart().startsWith("data:")) {
                        findResponseInSseText(text, body.optLong("id"), method)
                            ?: throw McpException(
                                McpFailure.PROTOCOL,
                                "MCP 服务器「${config.name}」${stage(method)}的 SSE 响应里没有 id=${body.optLong("id")}",
                            )
                    } else {
                        text
                    }
                }
            }
        }

        private fun baseRequest(method: String): Request.Builder {
            val b = Request.Builder()
                .url(config.url)
                .header("Accept", "application/json, text/event-stream")
            config.headers.forEach { (k, v) -> b.header(k, v) }
            if (method != "initialize") {
                b.header("MCP-Protocol-Version", protocolVersion)
                sessionId?.let { b.header("Mcp-Session-Id", it) }
            }
            return b
        }

        private fun captureSession(raw: String?) {
            val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (!SESSION_RE.matches(v)) {
                throw McpException(McpFailure.PROTOCOL, "MCP 服务器返回了不合法的 Mcp-Session-Id（含控制字符/超长），已拒绝")
            }
            sessionId = v
        }

        private fun readSseForId(r: Response, id: Long, method: String): String {
            val source = r.body?.source()
                ?: throw McpException(McpFailure.PROTOCOL, "MCP 服务器「${config.name}」${stage(method)}的 SSE 响应没有正文")
            source.use { src ->
                val data = StringBuilder()
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    if (line.isEmpty()) {
                        if (data.isNotEmpty()) {
                            val hit = findResponseInSseText(data.toString(), id, method)
                            if (hit != null) return hit
                            data.clear()
                        }
                    } else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").removePrefix(" "))
                        if (data.length > MAX_EVENT_CHARS) {
                            throw McpException(McpFailure.PROTOCOL, "MCP 服务器「${config.name}」的 SSE 事件超过 ${MAX_EVENT_CHARS} 字符，已中止")
                        }
                    }
                }
                if (data.isNotEmpty()) {
                    findResponseInSseText(data.toString(), id, method)?.let { return it }
                }
            }
            throw McpException(
                McpFailure.PROTOCOL,
                "MCP 服务器「${config.name}」${stage(method)}的 SSE 流结束了也没等到 id=$id 的响应",
            )
        }

        private fun findResponseInSseText(text: String, id: Long, method: String): String? =
            try {
                if (findResponse(text, id, method) != null) text.trim() else null
            } catch (e: McpException) {
                throw e
            } catch (e: Exception) {
                throw McpException(McpFailure.PROTOCOL, "MCP 服务器「${config.name}」返回了不可解析的 SSE 数据", cause = e)
            }

        private fun readText(r: Response): String {
            val body = r.body ?: return ""
            val declared = body.contentLength()
            if (declared > MAX_BODY_BYTES) throw tooLarge()
            val out = ByteArrayOutputStream(
                if (declared in 1..MAX_BODY_BYTES) declared.toInt() else READ_CHUNK_BYTES,
            )
            val buf = ByteArray(READ_CHUNK_BYTES)
            try {
                // 边读边数：不等 `bytes()` 整块读完才检查——否则一个无限流的服务器
                // 可以在检查之前就让进程 OOM，4 MB 上限等于没设。
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (out.size().toLong() + n > MAX_BODY_BYTES) throw tooLarge()
                        out.write(buf, 0, n)
                    }
                }
            } catch (e: McpException) {
                throw e
            } catch (e: InterruptedIOException) {
                throw McpException(McpFailure.TIMEOUT, "MCP 服务器「${config.name}」响应读取超时", cause = e)
            } catch (e: IOException) {
                throw McpException(McpFailure.CONNECT, "MCP 服务器「${config.name}」响应读取失败（${e.javaClass.simpleName}）", cause = e)
            }
            return out.toString(Charsets.UTF_8.name())
        }

        private fun tooLarge(): McpException = McpException(
            McpFailure.PROTOCOL,
            "MCP 服务器「${config.name}」响应超过 ${MAX_BODY_BYTES / 1024 / 1024} MB，已中止",
        )
    }

    /**
     * 旧式 HTTP+SSE：GET 长连接等 `endpoint` 事件，POST 到该端点，响应从长连接里按 id 取。
     *
     * 长连接的读取用 OkHttp 的 `enqueue` 回调（不新开裸线程——BareThreadGuardTest 的对账单
     * 盯着每一处 `Thread(...)`）。endpoint 必须与配置 URL **同源**（防凭据被带到第三方）。
     */
    private class LegacySseTransport(
        private val config: McpServerConfig,
        private val http: OkHttpClient,
        private val log: (String) -> Unit,
    ) : Transport {

        override val mode = "legacy-sse"

        @Volatile
        override var protocolVersion: String = McpJsonRpc.PROTOCOL

        private val events = LinkedBlockingQueue<Event>(MAX_QUEUED_EVENTS)

        @Volatile
        private var endpoint: String? = null

        @Volatile
        private var getCall: Call? = null

        @Volatile
        private var closed = false

        private sealed interface Event {
            data class Endpoint(val url: String) : Event
            data class Message(val data: String) : Event
            data class Failure(val error: McpException) : Event
            data object StreamClosed : Event
        }

        override fun open(timeoutMs: Long) {
            val builder = Request.Builder()
                .url(config.url)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .get()
            config.headers.forEach { (k, v) -> builder.header(k, v) }
            val call = http.newCall(builder.build())
            getCall = call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!closed) offer(Event.Failure(connectError(e, "建立 SSE 长连接", config.name)))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        if (!r.isSuccessful) {
                            if (!closed) offer(Event.Failure(httpError(r.code, "initialize", config.name)))
                            return
                        }
                        val source = r.body?.source()
                        if (source == null) {
                            if (!closed) offer(Event.Failure(McpException(McpFailure.PROTOCOL, "MCP 服务器「${config.name}」的 SSE 长连接没有正文")))
                            return
                        }
                        source.use { src ->
                            var eventName = ""
                            val data = StringBuilder()
                            try {
                                while (!closed) {
                                    val line = src.readUtf8Line() ?: break
                                    if (line.isEmpty()) {
                                        dispatch(eventName, data.toString())
                                        eventName = ""
                                        data.clear()
                                    } else if (line.startsWith("event:")) {
                                        eventName = line.removePrefix("event:").trim()
                                    } else if (line.startsWith("data:")) {
                                        if (data.isNotEmpty()) data.append('\n')
                                        data.append(line.removePrefix("data:").removePrefix(" "))
                                        if (data.length > MAX_EVENT_CHARS) {
                                            offer(Event.Failure(McpException(McpFailure.PROTOCOL, "MCP 服务器「${config.name}」的 SSE 事件超过 ${MAX_EVENT_CHARS} 字符，已中止")))
                                            call.cancel()
                                            return@use
                                        }
                                    }
                                }
                                if (data.isNotEmpty()) dispatch(eventName, data.toString())
                            } catch (e: IOException) {
                                if (!closed && !call.isCanceled()) offer(Event.Failure(connectError(e, "读取 SSE 长连接", config.name)))
                            } finally {
                                if (!closed) offer(Event.StreamClosed)
                            }
                        }
                    }
                }
            })

            // 等 endpoint 事件（10s 默认）；期间到达的消息先留在队列里。
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                val remain = deadline - System.nanoTime()
                if (remain <= 0) {
                    runCatching { call.cancel() }
                    throw McpException(McpFailure.TIMEOUT, "MCP 服务器「${config.name}」等待旧式 SSE endpoint 超时（${timeoutMs / 1000}s）")
                }
                val ev = try {
                    events.poll(remain, TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw McpException(McpFailure.CLOSED, "MCP 连接被中断", cause = e)
                } ?: continue
                when (ev) {
                    is Event.Endpoint -> {
                        endpoint = ev.url
                        return
                    }
                    is Event.Failure -> throw ev.error
                    Event.StreamClosed -> throw McpException(McpFailure.CLOSED, "MCP 服务器「${config.name}」的 SSE 长连接在收到 endpoint 前就关闭了")
                    is Event.Message -> Unit // 先于 endpoint 的消息（少见）留在队列里
                }
            }
        }

        override fun request(id: Long, method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
            val ep = endpoint ?: throw McpException(McpFailure.CLOSED, "MCP 服务器「${config.name}」的 SSE endpoint 尚未就绪")
            post(McpJsonRpc.request(id, method, params), ep, timeoutMs, method)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                val remain = deadline - System.nanoTime()
                if (remain <= 0) {
                    throw McpException(McpFailure.TIMEOUT, "MCP 服务器「${config.name}」${stage(method)}超时（${timeoutMs / 1000}s）")
                }
                val ev = try {
                    events.poll(remain, TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw McpException(McpFailure.CLOSED, "MCP 调用被中断", cause = e)
                } ?: continue
                when (ev) {
                    is Event.Message -> {
                        val incoming = try {
                            McpJsonRpc.parse(ev.data)
                        } catch (e: McpException) {
                            log("[mcp] ${config.id} 忽略一条不可解析的 SSE 消息")
                            continue
                        }
                        findResponse(incoming, id, method)?.let { return it }
                    }
                    is Event.Failure -> throw ev.error
                    Event.StreamClosed -> throw McpException(McpFailure.CLOSED, "MCP 服务器「${config.name}」的 SSE 长连接已关闭")
                    is Event.Endpoint -> endpoint = ev.url
                }
            }
        }

        override fun notify(method: String, params: JSONObject?, timeoutMs: Long) {
            val ep = endpoint ?: return
            post(McpJsonRpc.notification(method, params), ep, timeoutMs, method)
        }

        private fun post(body: JSONObject, url: String, timeoutMs: Long, method: String) {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/event-stream")
                .post(body.toString().toRequestBody(JSON_MEDIA))
            config.headers.forEach { (k, v) -> builder.header(k, v) }
            val call = http.newCall(builder.build())
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            val response = execute(call, method, timeoutMs, config.name)
            response.use { r ->
                if (!r.isSuccessful) throw httpError(r.code, method, config.name)
                // 大多数旧式实现回 202 空正文；少数直接回 JSON 响应。
                if (r.body?.contentLength() == 0L) return
                val contentType = r.header("Content-Type").orEmpty().lowercase()
                if (contentType.contains("application/json")) {
                    // 响应也可能走长连接；这里只读掉，避免连接泄漏。
                    runCatching { r.body?.bytes() }
                }
            }
        }

        private fun dispatch(eventName: String, data: String) {
            if (data.isEmpty()) return
            when (eventName) {
                "endpoint" -> {
                    val url = try {
                        McpUrlRules.resolveSameOrigin(config.url, data)
                    } catch (e: McpException) {
                        offer(Event.Failure(e))
                        getCall?.cancel()
                        return
                    }
                    offer(Event.Endpoint(url))
                }
                "error" -> offer(Event.Failure(McpException(McpFailure.PROTOCOL, "MCP 服务器「${config.name}」报告了 SSE 错误事件")))
                else -> offer(Event.Message(data))
            }
        }

        private fun offer(ev: Event) {
            if (!events.offer(ev)) {
                log("[mcp] ${config.id} SSE 事件队列已满（${MAX_QUEUED_EVENTS}），丢弃一条")
            }
        }

        override fun close() {
            closed = true
            runCatching { getCall?.cancel() }
            offer(Event.StreamClosed)
        }
    }

    companion object {
        /** `tools/list` 的阶段超时（设计 §6）。 */
        const val LIST_TIMEOUT_MS = 15_000L

        private const val MAX_PAGES = 20
        private const val MAX_BODY_BYTES = 4L * 1024 * 1024
        private const val READ_CHUNK_BYTES = 8 * 1024
        private const val MAX_EVENT_CHARS = 1_000_000
        private const val MAX_QUEUED_EVENTS = 256

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val VERSION_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val SESSION_RE = Regex("^[A-Za-z0-9._~+/=-]{1,256}$")

        fun defaultHttp(config: McpServerConfig): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            // 读超时置 0：旧式 SSE 是长连接，不能被 callTimeout 掐断。每次 POST 都有自己的
            // call.timeout(...)（连接/列表/调用各自的时限），等 endpoint 也有 10s 截止并 cancel，
            // 不会因为这里放开而无限等。
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(config.callTimeoutMs, TimeUnit.MILLISECONDS)
            // 不跟重定向：自定义认证头是**用户填的**，跨主机/跨 scheme 跟随就等于把 key
            // 送去第三方（https→http 还会明文）。3xx 按普通 HTTP 失败回人话，让用户填最终地址。
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private fun stage(method: String): String = when (method) {
            "initialize" -> "连接/初始化"
            "tools/list" -> "列工具"
            "tools/call" -> "调用工具"
            "notifications/initialized" -> "初始化通知"
            else -> "请求「$method」"
        }

        private fun httpError(code: Int, method: String, name: String, fallback: Boolean = false): McpException =
            when (code) {
                401, 403 -> McpException(
                    McpFailure.AUTH,
                    "MCP 服务器「$name」认证失败（HTTP $code）——请检查请求头/服务端权限",
                )
                404, 405, 415 -> McpException(
                    McpFailure.HTTP,
                    "MCP 服务器「$name」在${stage(method)}返回 HTTP $code（端点不存在/方法不允许）",
                    fallbackEligible = fallback,
                )
                else -> McpException(McpFailure.HTTP, "MCP 服务器「$name」在${stage(method)}返回 HTTP $code")
            }

        private fun connectError(e: IOException, stageText: String, name: String): McpException =
            if (e is InterruptedIOException) {
                McpException(McpFailure.TIMEOUT, "MCP 服务器「$name」${stageText}超时", cause = e)
            } else {
                McpException(McpFailure.CONNECT, "MCP 服务器「$name」${stageText}失败（${e.javaClass.simpleName}）", cause = e)
            }

        /** 带统一异常翻译的同步请求（网络层的 IOException 只在这里变成人话）。 */
        private fun execute(call: Call, method: String, timeoutMs: Long, name: String): Response = try {
            call.execute()
        } catch (e: InterruptedIOException) {
            throw McpException(
                McpFailure.TIMEOUT,
                "MCP 服务器「$name」${stage(method)}超时（${timeoutMs / 1000}s）",
                cause = e,
            )
        } catch (e: IOException) {
            throw McpException(
                McpFailure.CONNECT,
                "MCP 服务器「$name」${stage(method)}连接失败（${e.javaClass.simpleName}）",
                cause = e,
            )
        } catch (e: IllegalArgumentException) {
            throw McpException(
                McpFailure.CONFIG,
                "MCP 服务器「$name」的请求头/URL 不合法（${e.javaClass.simpleName}）",
                cause = e,
            )
        }

        /** 把 `Incoming` 里 id 对上的响应取出来；JSON-RPC error / 服务器请求在这里处理。 */
        private fun findResponse(raw: String, id: Long, method: String): JSONObject? =
            findResponse(McpJsonRpc.parse(raw), id, method)

        private fun findResponse(incoming: McpJsonRpc.Incoming, id: Long, method: String): JSONObject? =
            when (incoming) {
                is McpJsonRpc.Incoming.Response -> {
                    if (incoming.id != id) {
                        null
                    } else if (incoming.error != null) {
                        throw McpException(
                            if (method == "tools/call") McpFailure.TOOL else McpFailure.PROTOCOL,
                            "${stage(method)}失败：${McpJsonRpc.responseErrorText(incoming.error)}",
                        )
                    } else {
                        incoming.result ?: JSONObject()
                    }
                }
                is McpJsonRpc.Incoming.Batch -> {
                    var found: JSONObject? = null
                    for (item in incoming.items) {
                        val one = findResponse(item, id, method)
                        if (one != null) found = one
                    }
                    found
                }
                is McpJsonRpc.Incoming.ServerRequest -> null // v1 不执行服务器请求（也不回包）
                is McpJsonRpc.Incoming.Notification -> null
                McpJsonRpc.Incoming.Ignored -> null
            }
    }
}
