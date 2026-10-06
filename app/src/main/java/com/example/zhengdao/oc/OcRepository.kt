// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：OpenCode 官方 serve 模式 REST 端点 + SSE 事件流。
package com.example.zhengdao.oc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 太极 Tab 的状态归约层 —— **UI 与网络之间的唯一真相源**。
 *
 * ## 归约为什么集中在这里
 *
 * 流式增量（`delta`）与重连后的全量补齐，两种更新方式**互斥**；混用会导致文字
 * 重复或闪烁。把判据集中在 [reduce] 一处，才可能写单元测试（见类内注释）。
 *
 * ## 单一真相源
 *
 * Compose state 是唯一真相；本类不持有跨进程的持久状态。进程被系统杀掉后，
 * 重新 [open] 即可从 serve 恢复（会话本就由宿主进程的 serve 持有）。
 */
class OcRepository(
    private val http: OcClient,
    private val sse: SseClient,
) {

    private val _state = MutableStateFlow(TaijiState())
    val state: StateFlow<TaijiState> = _state.asStateFlow()

    private var sseJob: Job? = null
    private var attempt = 0

    // ── 生命周期 ──────────────────────────────────────────────────────

    /**
     * 打开一个会话：先全量拉取，再接SSE 增量。
     *
     * @param sessionId 为空时自动新建会话（单会话模型，见设计文档 §3.4）。
     */
    suspend fun open(scope: CoroutineScope, sessionId: String?) {
        val id = sessionId ?: createSession()?.also { logSession(it) }
        if (id == null) {
            _state.update { it.copy(phase = TaijiPhase.Failed("无法创建会话", retryable = true)) }
            return
        }
        _state.update {
            it.copy(phase = TaijiPhase.Loading, sessionId = id, connection = ConnectionState.Connecting)
        }

        // ① 全量（失败不阻断——SSE 仍可能补上后续消息）
        runCatching { loadAll(id) }
            .onFailure { ocLog("初始全量加载失败，交由 SSE 补齐：${it.message}") }

        // ② SSE 增量
        connectSse(scope, id)
    }

    private suspend fun createSession(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = "{}".toPostRequest(http.url("/api/session"))
            http.client.newCall(req).execute().use { resp ->
                parseBody(resp) { JSONObject(it).optString("id") }
            }
        }.onFailure { ocLog("创建会话失败：${it.message}") }.getOrNull()
    }?.takeIf { it.isNotEmpty() }

    /** 全量拉取并**重建**列表（不是合并）——重连补齐必须走这里。 */
    private suspend fun loadAll(sessionId: String) {
        val arr = fetchJsonArray(http.url("/api/session/$sessionId/message")) ?: return
        val parsed = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            // 兼容两种形态：裸 info 对象，或 OpenCode v2 的 {info, parts} 包装
            val msg = parseMessage(o.optJSONObject("info") ?: o) ?: return@mapNotNull null
            val outer = o.optJSONArray("parts")?.let { p ->
                (0 until p.length()).mapNotNull { j -> p.optJSONObject(j)?.let(::parsePart) }
            }
            if (outer.isNullOrEmpty()) msg else msg.copy(parts = outer)
        }
        _state.update {
            it.copy(
                messages = parsed.distinctBy { m -> m.id },
                parts = parsed.flatMap { m -> m.parts }.associateBy { p -> p.id },
                loadedOnce = true,
            )
        }
        ocLog("全量加载 ${parsed.size} 条消息")
    }

    /**
     * GET 一个返回 JSON 数组的端点。
     *
     * ⚠️ 必须走 [OcClient]，不能裸 `URL.openConnection()`：鉴权头由
     * [AuthInterceptor] 统一注入，绕过它拿到的只会是 401。
     * 历史教训：本文件原有一个裸连的 `getJsonArray()`，会让"重连后全量补齐"
     * 这个最关键的恢复路径必然失败，且失败被 runCatching 吞成"空列表"——
     * 表现为"转屏/切后台回来消息全没了"，极难归因。
     */
    private suspend fun fetchJsonArray(url: String): JSONArray? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).get().build()
            http.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return@runCatching null
                if (!resp.isSuccessful) throw OcHttpException(resp.code, "GET $url HTTP ${resp.code}")
                JSONArray(text)
            }
        }.onFailure { ocLog("GET $url 失败：${it.message}") }.getOrNull()
    }

    private fun connectSse(scope: CoroutineScope, sessionId: String) {
        sseJob?.cancel()
        sseJob = scope.launch {
            val req = http.sseRequest("/api/event")
            sse.connect("/api/event", req) { raw ->
                runCatching { JSONObject(raw).optString("sessionID").takeIf { it.isNotEmpty() } }.getOrNull()
            }.collect { ev -> handle(ev, scope) }
        }
    }

    private fun handle(ev: SseClient.Event, scope: CoroutineScope) {
        when (ev) {
            is SseClient.Event.Connected -> _state.update {
                it.copy(connection = ConnectionState.Connected, lastError = null)
            }

            is SseClient.Event.Reconnected -> {
                attempt = 0
                _state.update {
                    it.copy(connection = ConnectionState.Connected, isStreaming = false)
                }
                // 🔺 关键：重连后必须全量补齐——断线期间的消息**只存在于**全量结果里
                scope.launch { runCatching { loadAll(_state.value.sessionId.orEmpty()) } }
            }

            is SseClient.Event.Disconnected -> {
                attempt++
                _state.update {
                    it.copy(
                        connection = ConnectionState.Reconnecting,
                        lastError = ev.cause?.message ?: "连接已断开，正在重连",
                        reconnectAttempt = attempt,
                    )
                }
            }

            is SseClient.Event.PartUpdated -> reducePartUpdated(ev)
            is SseClient.Event.MessageUpdated -> reduceMessageUpdated(ev.raw)

            is SseClient.Event.PartRemoved -> {
                val partId = parsePartRemoved(ev.raw) ?: return
                _state.update { s ->
                    s.copy(parts = s.parts - partId, messages = s.messages.map { m ->
                        if (m.id == parseSessionOf(ev.raw)) m.copy(parts = m.parts.filterNot { it.id == partId })
                        else m
                    })
                }
            }

            is SseClient.Event.PermissionAsked -> {
                val p = parsePermission(ev.raw) ?: return
                _state.update { it.copy(pendingPermission = p) }   // UI 弹底部抽屉
            }

            is SseClient.Event.TodoUpdated -> {
                _state.update { it.copy(todos = parseTodos(ev.raw)) }
            }

            is SseClient.Event.SessionIdle -> _state.update {
                it.copy(isStreaming = false, pendingPermission = null)
            }

            is SseClient.Event.Unknown ->
                // 🔺 未知事件不丢弃、不崩UI——仅记录。上游新增类型属正常演进。
                ocLog("未知 SSE 事件 type=${ev.type}，已忽略但保留原文")

            else -> Unit
        }
    }

    fun close() {
        sseJob?.cancel()
        sseJob = null
    }

    // ── 归约：delta 与全量严格互斥 ─────────────────────────────────────

    /**
     * `PartUpdated` 归约 —— **整个方案最容易出错的地方**。
     *
     * 判据（三行，务必保持）：
     * - part 不存在 → 新建，取`delta ?: part.text`
     * - part 已存在且有 `delta` → **追加**
     * - part 已存在且无 `delta` → **全量覆盖**
     *
     * 混用（该覆盖时追加、该追加时覆盖）会导致文字重复或闪烁。
     */
    private fun reducePartUpdated(ev: SseClient.Event.PartUpdated) {
        val obj = runCatching { JSONObject(ev.raw) }.getOrNull() ?: return
        val part = obj.optJSONObject("part") ?: return
        val id = part.optString("id").takeIf { it.isNotEmpty() } ?: return
        val type = part.optString("type")
        val fullText = if (part.has("text")) part.optString("text") else null

        val existing = _state.value.parts[id]
        val merged: OcPart = when (type) {
            "text", "reasoning" -> {
                // ⚠️ 核心判据
                // delta 优先取已解析的 JSONObject（org.json 会自动还原 \n / \" / \uXXXX）；
                // 只有 part 里没有 delta 字段时，才退回 SseClient 用正则抠出的原始串——
                // 那是**未反转义**的，手工 unescape 处理不了 \uXXXX，中文会出乱码。
                val jsonDelta = if (part.has("delta")) part.optString("delta") else null
                val inc = jsonDelta ?: ev.delta?.let(::unescape)
                val text = inc?.let { d -> (existing?.textSafe() ?: "") + d }
                    ?: fullText
                    ?: existing?.textSafe()
                    ?: ""
                val isText = type == "text"
                if (isText) OcPart.Text(id, text) else OcPart.Reasoning(id, text)
            }
            "tool" -> OcPart.Tool(
                id = id,
                toolName = part.optString("tool").takeIf { it.isNotEmpty() } ?: part.optString("name"),
                state = parseToolState(part.optJSONObject("state")),
                input = part.optJSONObject("input")?.toString(),
                output = part.optJSONObject("output")?.let { o ->
                    o.optString("title").takeIf { it.isNotEmpty() } ?: o.toString()
                },
            )
            "file" -> OcPart.File(id, part.optString("filename"), part.optString("mime"))
            else -> OcPart.Unknown(id, type, part.toString())
        }
        _state.update { it.copy(parts = it.parts + (id to merged)) }
    }

    private fun reduceMessageUpdated(raw: String) {
        val msg = parseMessage(JSONObject(raw).optJSONObject("info")) ?: return
        _state.update { s ->
            val idx = s.messages.indexOfFirst { it.id == msg.id }
            s.copy(messages = if (idx >= 0) s.messages.toMutableList().also { it[idx] = msg }
            else s.messages + msg)
        }
    }

    // ── 用户操作 ──────────────────────────────────────────────────────

    // ⚠️ 以下三个写操作都包了 withContext(Dispatchers.IO)：
    //    OkHttp 的 execute() 是阻塞调用，调用方是 UI 的 rememberCoroutineScope（主线程），
    //    不切线程会直接抛 NetworkOnMainThreadException（与 targetSdk 无关，是 Android 框架检查）。

    suspend fun prompt(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val id = _state.value.sessionId ?: error("未打开会话")
            // 真实载荷是 {text, files[], agents[], skills[]}，不是 {parts:[...]}
            val body = JSONObject().apply { put("text", text) }
            val req = body.toString().toPostRequest(http.url("/api/session/$id/prompt"))
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw OcHttpException(resp.code, "发送失败 HTTP ${resp.code}")
            }
            _state.update { it.copy(isStreaming = true, input = "") }
        }.onFailure { ocLog("发送失败：${it.message}") }
    }

    suspend fun abort(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val id = _state.value.sessionId ?: return@runCatching
            // 真实端点是 interrupt（不存在 /abort），且无请求体
            val req = EMPTY_BODY.toPostRequestVoid(http.url("/api/session/$id/interrupt"))
            http.client.newCall(req).execute().use { }
            _state.update { it.copy(isStreaming = false) }
        }.onFailure { ocLog("中止失败：${it.message}") }
    }

    /** 权限批准 —— 不实现则 Agent 改文件时卡死（设计文档 §4.3 门禁）。 */
    suspend fun respondPermission(
        permissionId: String, allow: Boolean, remember: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val id = _state.value.sessionId ?: error("未打开会话")
            // 三态（实测确认）：once=一次性 / always=永久写入 saved / reject=拒绝
            val decision = when {
                !allow -> "reject"
                remember -> "always"
                else -> "once"
            }
            val body = JSONObject().apply { put("decision", decision) }
            val req = body.toString().toPostRequest(
                http.url("/api/session/$id/permission/$permissionId/reply")
            )
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw OcHttpException(resp.code, "授权失败 HTTP ${resp.code}")
            }
            _state.update { it.copy(pendingPermission = null) }
        }.onFailure { ocLog("授权提交失败：${it.message}") }
    }

    fun setInput(text: String) = _state.update { it.copy(input = text) }

    fun dismissError() = _state.update { it.copy(lastError = null) }

    // ── 状态模型 ──────────────────────────────────────────────────────

}

// ── JSON 解析辅助（手写，避免为此引入序列化依赖）─────────────────────

private val jsonMediaType = "application/json".toMediaType()

private fun String.toJsonBody(): RequestBody = toRequestBody(jsonMediaType)

private fun String.toPostRequest(url: String): Request =
    Request.Builder().url(url).post(toJsonBody()).build()

/** 无 body 的 POST（abort 用）。 */
private fun RequestBody.toPostRequestVoid(url: String): Request =
    Request.Builder().url(url).post(this).build()

private val EMPTY_BODY: RequestBody = "".toRequestBody(jsonMediaType)

/** 去掉 JSON 转义的反斜杠（仅用于 delta 拼接的轻量还原）。 */
internal fun unescape(raw: String): String = buildString(raw.length) {
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            when (val n = raw[i + 1]) {
                'n' -> { append('\n'); i += 2 }
                't' -> { append('\t'); i += 2 }
                'r' -> { append('\r'); i += 2 }
                '"' -> { append('"'); i += 2 }
                '\\' -> { append('\\'); i += 2 }
                else -> { append(c); append(n); i += 2 }
            }
        } else { append(c); i++ }
    }
}

internal fun OcPart.textSafe(): String = when (this) {
    is OcPart.Text -> text
    is OcPart.Reasoning -> text
    else -> ""
}
// ── 解析辅助（手写 JSON 映射，避免为此引入序列化依赖）────────────────

internal fun parseBody(resp: Response, pick: (String) -> String?): String? {
    val text = resp.body?.string() ?: return null
    if (!resp.isSuccessful) throw OcHttpException(resp.code, "HTTP ${resp.code}: ${text.take(120)}")
    return pick(text)
}

internal fun parseMessage(info: JSONObject?): OcMessage? {
    info ?: return null
    val id = info.optString("id").takeIf { it.isNotEmpty() } ?: return null
    // ⚠️ 真实消息用 **type** 区分（user / assistant / system / idle…），**没有 role 字段**。
    //    原实现读 role 会把所有消息都判成 SYSTEM。
    val role = when (info.optString("type")) {
        "user" -> OcMessage.Role.USER
        "assistant" -> OcMessage.Role.ASSISTANT
        else -> OcMessage.Role.SYSTEM
    }
    val time = info.optJSONObject("time")?.optLong("created")
    // Assistant 的多段内容在 **content[]**（不是 parts[]）
    val arr = info.optJSONArray("content") ?: info.optJSONArray("parts")
    val parts = if (arr != null) {
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parsePart(it, fallbackId = "$id:$i") }
        }
    } else {
        // User（及纯文本消息）：正文直接在 text 字段
        info.optString("text").takeIf { it.isNotEmpty() }
            ?.let { listOf(OcPart.Text("$id:0", it)) } ?: emptyList()
    }
    return OcMessage(id, role, parts, time)
}

internal fun parsePart(obj: JSONObject?, fallbackId: String? = null): OcPart? {
    obj ?: return null
    // ⚠️ 只有 tool 段自带 id；text / reasoning 段**没有 id 字段**。
    //    原实现「无 id 直接丢弃」会把助手正文整段丢光——必须回退到 <消息id>:<序号>。
    val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: fallbackId ?: return null
    return when (obj.optString("type")) {
        "text" -> OcPart.Text(id, obj.optString("text"))
        "reasoning" -> OcPart.Reasoning(id, obj.optString("text"))
        // 工具名在 **name** 字段（不是 tool）
        "tool" -> OcPart.Tool(
            id, obj.optString("name"),
            parseToolState(obj.optJSONObject("state")),
            obj.optJSONObject("input")?.toString(),
            obj.optJSONObject("output")?.optString("title"),
        )
        "file" -> OcPart.File(id, obj.optString("filename"), obj.optString("mime"))
        else -> OcPart.Unknown(id, obj.optString("type"), obj.toString())
    }
}

internal fun parseToolState(state: JSONObject?): ToolState = when (state?.optString("status")) {
    "running", "pending" -> ToolState.Running
    "completed", "success" -> ToolState.Success
    "error", "failed" -> ToolState.Error
    else -> ToolState.Unknown
}

internal fun parsePermission(raw: String): OcPermission? = runCatching {
    val o = JSONObject(raw)
    val props = o.optJSONObject("properties") ?: o
    OcPermission(
        permissionId = props.optString("permissionID").takeIf { it.isNotEmpty() } ?: return null,
        sessionId = props.optString("sessionID"),
        title = props.optString("title").takeIf { it.isNotEmpty() } ?: "工具请求授权",
        detail = (props.optJSONObject("metadata")?.optString("command")
            ?: props.optString("description") ?: props.optString("path")),
        type = props.optString("type"),
    )
}.getOrNull()

internal fun parseTodos(raw: String): List<OcTodo> = runCatching {
    val o = JSONObject(raw)
    val arr = o.optJSONArray("todos") ?: return emptyList()
    (0 until arr.length()).mapNotNull { i ->
        val t = arr.optJSONObject(i) ?: return@mapNotNull null
        OcTodo(
            id = t.optString("id"),
            content = t.optString("content"),
            status = when (t.optString("status")) {
                "running" -> OcTodo.TodoStatus.Running
                "completed" -> OcTodo.TodoStatus.Completed
                "cancelled" -> OcTodo.TodoStatus.Cancelled
                else -> OcTodo.TodoStatus.Pending
            },
        )
    }
}.getOrDefault(emptyList())

internal fun parsePartRemoved(raw: String): String? = runCatching {
    JSONObject(raw).optJSONObject("properties")?.optString("partID")?.takeIf { it.isNotEmpty() }
}.getOrNull()

internal fun parseSessionOf(raw: String): String? = runCatching {
    JSONObject(raw).optJSONObject("properties")?.optString("sessionID")
}.getOrNull()

private fun logSession(id: String) = ocLog("会话已就绪 id=$id")
