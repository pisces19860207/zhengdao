// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：OpenCode 官方 serve 模式 REST 端点 + SSE 事件流。
package com.example.zhengdao.oc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

    /** REST 轮询任务 —— **主数据源**。见 ERRATA E-008：SSE 只是信号通道。 */
    private var pollJob: Job? = null

    /**
     * 已响应的权限 requestID。
     * 权限**绝不能**"批准了又被下一轮轮询复活" —— 回执后把 id 记下，轮询直接跳过。
     */
    private val answeredPermissions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ── 生命周期 ──────────────────────────────────────────────────────

    /**
     * 打开一个会话：先全量拉取，再接SSE 增量。
     *
     * @param sessionId 为空时自动新建会话（单会话模型，见设计文档 §3.4）。
     */
    suspend fun open(scope: CoroutineScope, sessionId: String?) {
        // 单会话模型下的会话选择优先级：
        //   ① 转屏/配置变更保留下来的 id（savedSession）
        //   ② **服务端最近的会话** —— 否则每进一次太极就新建一个空会话
        //      （实测服务端已累积 7 个），用户在太极 Tab 看到的历史也会每次归零
        //   ③ 都没有才新建
        val id = sessionId
            ?: latestSessionId()?.also { ocLog("复用服务端最近会话 id=$it") }
            ?: createSession()?.also { logSession(it) }
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

        // ② REST 轮询（**主数据源**）——绕开 /api/event「连上即关」的服务端缺陷（ERRATA E-008）
        startPolling(scope, id)

        // ③ SSE 信号通道（仅表示「就绪」，不承载数据）
        connectSse(scope, id)
    }

    private suspend fun createSession(): String? = withContext(Dispatchers.IO) {
        runCatching {
            // ⚠️ 响应带 {"data": …} 信封，id 在 data.id；取顶层 id 永远是空串
            val req = "{}".toPostRequest(http.url("/api/session"))
            http.client.newCall(req).execute().use { resp ->
                parseBody(resp) { JSONObject(it).unwrapData().optString("id") }
            }
        }.onFailure { ocLog("创建会话失败：${it.message}") }.getOrNull()
    }?.takeIf { it.isNotEmpty() }

    /**
     * 取服务端最近的一个会话 id（用于「复用而不是每次新建」）。
     *
     * `GET /api/session` 返回 `{"data":[…]}`（**信封数组**），且按时间倒序——
     * 实测最新创建的排在最前，取第 0 个即可。
     * 拿不到就返回 null，由调用方降级为新建会话（失败不得阻断进入太极）。
     */
    private suspend fun latestSessionId(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(http.url("/api/session")).get().build()
            http.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: return@runCatching null
                if (!resp.isSuccessful) return@runCatching null
                val arr = unwrapArray(text)
                if (arr.length() == 0) null
                else arr.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }
            }
        }.onFailure { ocLog("取最近会话失败（将新建）：${it.message}") }.getOrNull()
    }

    /** 全量拉取并**重建**列表（不是合并）——首载 / 重连补齐走这里。 */
    private suspend fun loadAll(sessionId: String) {
        val arr = fetchJsonArray(http.url("/api/session/$sessionId/message")) ?: return
        val parsed = parseMessages(arr)
        _state.update {
            it.copy(
                messages = parsed,
                parts = parsed.flatMap { m -> m.parts }.associateBy { p -> p.id },
                loadedOnce = true,
            )
        }
        ocLog("全量加载 ${parsed.size} 条消息")
    }

    /**
     * 把 `GET /api/session/{id}/message` 的数组解析成消息列表。
     *
     * 兼容两种形态：裸 info 对象，或 OpenCode v2 的 `{info, parts}` 包装；
     * 按 id 去重（首载/轮询都复用，保证归约口径一致）。
     */
    private fun parseMessages(arr: JSONArray): List<OcMessage> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val msg = parseMessage(o.optJSONObject("info") ?: o) ?: return@mapNotNull null
            val outer = o.optJSONArray("parts")?.let { p ->
                (0 until p.length()).mapNotNull { j -> p.optJSONObject(j)?.let(::parsePart) }
            }
            if (outer.isNullOrEmpty()) msg else msg.copy(parts = outer)
        }.distinctBy { m -> m.id }

    // ── REST 轮询（主数据源）─────────────────────────────────────────────
    // OpenCode /api/event 存在服务端缺陷（Issue #38458，见 ERRATA E-008）：连上即关、
    // 只给第一波数据然后静默。故消息数据改由本组轮询获取，SSE 仅当「就绪」信号。

    /**
     * 启动自适应 REST 轮询，直到 [pollJob] 被取消。
     *
     * 节奏：有变化 → 1s 快轮询；无变化 → 3s / 5s 逐步放慢；失败 → 指数退避并如实报 UI。
     */
    private fun startPolling(scope: CoroutineScope, sessionId: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var idleStreak = 0
            var errStreak = 0
            while (isActive) {
                val changed = try {
                    val c = pollOnce(sessionId)
                    // 权限兜底：即使消息轮询成功也要查一次（SSE 可能已因空闲断开）
                    pollPermissions()
                    errStreak = 0
                    c
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 失败必须可见：退避并告知 UI「正在重连」
                    errStreak = minOf(errStreak + 1, POLL_ERR_BACKOFF_MS.lastIndex)
                    _state.update {
                        it.copy(
                            connection = ConnectionState.Reconnecting,
                            lastError = e.message ?: "轮询失败，正在重试",
                            reconnectAttempt = errStreak,
                        )
                    }
                    delay(POLL_ERR_BACKOFF_MS[errStreak])
                    continue
                }
                val waitMs = if (changed) {
                    idleStreak = 0
                    POLL_FAST_MS
                } else {
                    idleStreak = minOf(idleStreak + 1, 2)
                    if (idleStreak == 1) POLL_SLOW_MS else POLL_IDLE_MS
                }
                delay(waitMs)
            }
        }
    }

    /**
     * 轮询一次消息列表并归约（**全量重建**，天然幂等）。
     * 返回相对上次是否有变化，供自适应节奏使用。
     *
     * @throws Exception 网络/HTTP 失败时抛出，交由 [startPolling] 做退避。
     */
    private suspend fun pollOnce(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val arr = fetchJsonArrayOrThrow(http.url("/api/session/$sessionId/message"))
        val parsed = parseMessages(arr)
        val changed = parsed != _state.value.messages
        _state.update {
            if (changed) {
                it.copy(
                    messages = parsed,
                    parts = parsed.flatMap { m -> m.parts }.associateBy { p -> p.id },
                    loadedOnce = true,
                    connection = ConnectionState.Connected,
                    lastError = null,
                    reconnectAttempt = 0,
                )
            } else {
                // 内容稳定 → 视为本轮生成已结束，清掉"流式中"指示（SSE 不可用，改由轮询推断）
                it.copy(
                    loadedOnce = true,
                    connection = ConnectionState.Connected,
                    lastError = null,
                    isStreaming = false,
                )
            }
        }
        changed
    }

    /** 同 [fetchJsonArray]，但**失败时抛异常**（供轮询做退避判断）。 */
    private suspend fun fetchJsonArrayOrThrow(url: String): JSONArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: throw java.io.IOException("空响应 $url")
            if (!resp.isSuccessful) throw OcHttpException(resp.code, "GET $url HTTP ${resp.code}")
            unwrapArray(text)
        }
    }

    // ── 权限兜底轮询 ───────────────────────────────────────────────────
    // SSE 的 permission.asked 是主通道，但 SSE 空闲会断（ERRATA E-008）——
    // 权限请求**绝不能漏**（漏了 Agent 卡死，或用户被静默授予高危权限），故额外轮询兜底。

    /**
     * 拉一次待处理权限并同步到 UI。
     *
     * `GET /api/permission/request` → `{"location":{…},"data":[Permission.Request]}`
     * 取第一条未响应的挂到 [TaijiState.pendingPermission]（UI 弹抽屉）；无则清空。
     */
    private suspend fun pollPermissions() {
        val pending = runCatching { fetchPermissionRequests() }.getOrNull() ?: return
        val first = pending.firstOrNull { it.permissionId !in answeredPermissions }
        val current = _state.value.pendingPermission
        if (first == null) {
            if (current != null) _state.update { it.copy(pendingPermission = null) }
        } else if (current?.permissionId != first.permissionId) {
            _state.update { it.copy(pendingPermission = first) }
        }
    }

    private suspend fun fetchPermissionRequests(): List<OcPermission> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(http.url("/api/permission/request")).get().build()
        http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: throw java.io.IOException("空响应 permission/request")
            if (!resp.isSuccessful) {
                throw OcHttpException(resp.code, "GET permission/request HTTP ${resp.code}")
            }
            // 该端点带 location 信封：{"location":{…},"data":[…]}
            val arr = JSONObject(text).optJSONArray("data") ?: unwrapArray(text)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { parsePermission(it.toString()) }
            }
        }
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
                unwrapArray(text)
            }
        }.onFailure { ocLog("GET $url 失败：${it.message}") }.getOrNull()
    }

    /**
     * 剥掉 OpenCode 的 **`{"data": …}` 响应信封**。
     *
     * 实测（真机 2.0.22，带 auth）：
     * - `POST /api/session` → `{"data":{"id":"ses_…"}}`
     * - `GET  /api/session` → `{"data":[…]}`
     * - `GET  /api/session/{id}/message` → `{"data":[…],"cursor":{…}}`
     *
     * ⚠️ 不剥的后果是**静默失败**：`optString("id")` 取顶层 id 恒为空串 →
     *   `takeIf { isNotEmpty() }` → null → UI 报「无法创建会话」，而 runCatching
     *   **没有异常可捕**、`onFailure` 不触发 → RunLog 一行都没有（本次最难定位处）。
     *
     * 少数端点（如 `/api/config`）返回**裸数组、无信封**，故退化用「有 data 取 data，否则原文」。
     */
    private fun JSONObject.unwrapData(): JSONObject = optJSONObject("data") ?: this

    /** 解析「可能是信封数组」的响应体：裸数组直接用，对象则取其 `data` 数组。 */
    private fun unwrapArray(text: String): JSONArray {
        val t = text.trimStart()
        if (t.startsWith("[")) return JSONArray(text)
        val o = JSONObject(text)
        return o.optJSONArray("data")
            ?: throw org.json.JSONException("响应既非数组也无 data 数组：${text.take(100)}")
    }

    private fun connectSse(scope: CoroutineScope, sessionId: String) {
        sseJob?.cancel()
        // 🔴 必须显式指定 Dispatchers.IO。
        //    调用方是 UI 的 rememberCoroutineScope（= Main）。SSE 是**阻塞长连接**：
        //    在 Main 线程 execute() 会被框架直接拒绝（NetworkOnMainThreadException），
        //    请求根本发不出去 → 服务端零记录 → 每次重连都失败 → 无限「正在重连」。
        //    这正是本轮「第 10 次重连、服务端零请求」的成因。
        sseJob = scope.launch(Dispatchers.IO) {
            val req = http.sseRequest("/api/event")
            sse.connect("/api/event", req) { raw ->
                runCatching { JSONObject(raw).optString("sessionID").takeIf { it.isNotEmpty() } }.getOrNull()
            }.collect { ev -> handle(ev) }
        }
    }

    private fun handle(ev: SseClient.Event) {
        when (ev) {
            // ── SSE 仅作「信号通道」────────────────────────────────────────
            // 连上（HTTP 200）即表示就绪；**数据以 REST 轮询为准**（ERRATA E-008）。
            is SseClient.Event.Connected,
            is SseClient.Event.Reconnected -> {
                if (_state.value.connection != ConnectionState.Connected) {
                    _state.update {
                        it.copy(connection = ConnectionState.Connected, lastError = null)
                    }
                }
            }

            // 已知服务端缺陷：/api/event 每次 flush 后即关流。**绝不改连接状态**
            //（否则 UI 会在 Connected/Reconnecting 之间每秒闪烁）；健康由 REST 轮询驱动。
            is SseClient.Event.Disconnected -> Unit

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

            is SseClient.Event.Unknown -> {
                // 模型池（v1.0）：session.step.started 事件的 model 字段 = 服务端实际在用的模型
                if (ev.type.startsWith("session.step.started")) {
                    parseStepModel(ev.raw)?.let { m ->
                        // 硬证据落日志：切换是否生效以此为准（RunLog 可搜索）
                        ocLog("session.step.started model=$m")
                        _state.update { s ->
                            if (s.modelOverride == null) s.copy(currentModel = m) else s
                        }
                    }
                }
                // 🔺 未知事件不丢弃、不崩UI——仅记录。上游新增类型属正常演进。
                ocLog("未知 SSE 事件 type=${ev.type}，已忽略但保留原文")
            }
        }
    }

    fun close() {
        sseJob?.cancel(); sseJob = null
        pollJob?.cancel(); pollJob = null
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
            // ⚠️ 与 parsePart 同源修正：真实字段在 state.input / state.content[]（顶层没有 input/output）
            "tool" -> {
                val st = part.optJSONObject("state")
                OcPart.Tool(
                    id = id,
                    toolName = part.optString("name").takeIf { it.isNotEmpty() } ?: part.optString("tool"),
                    state = parseToolState(st),
                    input = toolInputToText(st?.optJSONObject("input")),
                    output = toolContentToText(st?.optJSONArray("content")),
                )
            }
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

    // ── 模型池（v1.0 任务一）─────────────────────────────────────────

    /**
     * 拉取可选模型目录（GET /api/model?location[directory]=<工作区>）。
     *
     * **成功也可能为空**：模型目录来自 models.dev，设备网络不可达时服务端返回空数组
     * （实测本机）——空表是合法结果，不是错误，调用方仍必须优雅处理空态。
     *
     * 2026-10-08 审查修复（原先失败完全静默）：这条路径原来写成
     * `runCatching { … }.getOrDefault(emptyList())`——失败也返回空表、不写任何 state，
     * 只有一行没人会看的日志，于是「这次没拉到」与「目录本就为空」在界面上**不可区分**，
     * 用户点刷新只看到「⟳ 刷新 → 拉取中… → ⟳ 刷新」闪一下，以为按钮坏了。
     * 现在失败会：① 把异常原文落 RunLog（「不静默失败」是项目原则）；
     * ② 把一句话原因写进 [TaijiState.lastModelFetchError]，由 ModelSheet 显式显示。
     * 返回值仍是 [List]（失败 = 空表），**签名不变**，调用点无需改动。
     */
    suspend fun fetchModels(directory: String): List<OcModel> = withContext(Dispatchers.IO) {
        runCatching {
            val enc = java.net.URLEncoder.encode(directory, "UTF-8")
            val req = Request.Builder().url(http.url("/api/model?location[directory]=$enc")).get().build()
            http.client.newCall(req).execute().use { resp ->
                // 非 2xx 原先也走 emptyList()（同样与"目录为空"混为一谈）。改成抛出，
                // 与 setSessionModel 等同类写操作共用 OcHttpException 口径。
                if (!resp.isSuccessful) throw OcHttpException(resp.code, "拉取模型目录 HTTP ${resp.code}")
                val text = resp.body?.string()
                    ?: throw IllegalStateException("响应体为空")
                unwrapArray(text).let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { OcModel.fromJson(it) }
                    }
                }
            }
        }.fold(
            onSuccess = { models ->
                // 成功：填列表 + 清掉上一次的失败原因（失败文案不许在成功路径上残留）
                _state.update { it.copy(models = models, lastModelFetchError = null) }
                models
            },
            onFailure = { t ->
                // 失败可见：原文落日志（ocLog 自带「太极: 」前缀，见 OcClient.kt:145），
                // 一句话原因进 state 供 UI 显示。列表保持原样（不动 models）——
                // 弱网下拉取失败不该把已缓存的可用列表擦掉。
                ocLog("拉取模型目录失败 ${t.javaClass.simpleName}: ${t.message}")
                _state.update {
                    it.copy(lastModelFetchError = t.message?.take(120) ?: t.javaClass.simpleName)
                }
                emptyList()
            },
        )
    }

    /**
     * 切换当前会话的模型（POST /api/session/{id}/model，body {model:{id,providerID}}）。
     * 仅影响当前会话，不重写全局配置。
     */
    suspend fun setSessionModel(model: OcModel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sid = _state.value.sessionId ?: throw IllegalStateException("会话未就绪")
            val body = JSONObject().put(
                "model", JSONObject().put("id", model.id).put("providerID", model.providerID)
            )
            val req = body.toString().toPostRequest(http.url("/api/session/$sid/model"))
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw OcHttpException(resp.code, "切换模型 HTTP ${resp.code}")
                parseBody(resp) { "" }
            }
            _state.update { it.copy(modelOverride = model, currentModel = "${model.providerID}/${model.id}") }
        }
    }

    /** session.step.started 事件里的 model 字段 → 显示名。字段路径/形态做防御式解析
     *  （信封与否、model 为对象或字符串都兼容——以实测为准的教训）。 */
    private fun parseStepModel(raw: String): String? = runCatching {
        val o = JSONObject(raw)
        val data = o.optJSONObject("data") ?: o
        val m = data.opt("model") ?: data.optJSONObject("properties")?.opt("model") ?: return@runCatching null
        when (m) {
            is String -> m
            is JSONObject -> {
                val pid = m.optString("providerID")
                val mid = m.optString("modelID").ifEmpty { m.optString("id") }
                listOf(pid, mid).filter { it.isNotEmpty() }.joinToString("/").ifEmpty { null }
            }
            else -> null
        }
    }.getOrNull()

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
            // 记下已响应，避免下一轮权限轮询把它"复活"（权限抽屉刚点完又弹回来）
            answeredPermissions.add(permissionId)
            _state.update { it.copy(pendingPermission = null) }
        }.onFailure { ocLog("授权提交失败：${it.message}") }
    }

    fun setInput(text: String) = _state.update { it.copy(input = text) }

    fun dismissError() = _state.update { it.copy(lastError = null) }

    // ── 会话管理（v1.1 第一阶段「会话完整化」）──────────────────────────
    // 🔒 架构冻结红线（《v1.1 计划》"不许动 OcClient / OcRepository 接口"）：
    //    以下全部是**新增**方法，未触碰任何既有方法的签名或语义——既有单会话
    //    路径（open 自动复用最近会话 / 新建）保持原样，Agent B 不受影响。
    //    之前只有"单会话模型"，用户无法主动新建 / 查看历史 / 切换，本组补齐。

    /**
     * 拉取会话历史列表（含消息条数）。
     *
     * 端点 `GET /api/session`（响应 `{"data":[…]}`，按时间**倒序**）。
     * 条数由 `GET /api/session/{id}/message` **并发**补取——单个失败只让该条
     * [OcSessionSummary.messageCount] 为 null，**不拖垮整个列表**（失败不阻断）。
     */
    suspend fun listSessions(): List<OcSessionSummary> = withContext(Dispatchers.IO) {
        val raw = runCatching { fetchSessions() }
            .onFailure { ocLog("拉取会话列表失败：${it.message}") }
            .getOrDefault(emptyList())
        if (raw.isEmpty()) return@withContext emptyList()
        coroutineScope {
            raw.map { s ->
                async { s.toSummary(runCatching { countMessages(s.id) }.getOrNull()) }
            }.awaitAll()
        }
    }

    /** `GET /api/session` → 会话元数据列表。 */
    private suspend fun fetchSessions(): List<OcSession> {
        val arr = fetchJsonArrayOrThrow(http.url("/api/session"))
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(::parseSession)
        }
    }

    /** 单个会话的消息条数（历史列表展示用）。 */
    private suspend fun countMessages(sessionId: String): Int =
        fetchJsonArrayOrThrow(http.url("/api/session/$sessionId/message")).length()

    private fun OcSession.toSummary(messageCount: Int?): OcSessionSummary =
        OcSessionSummary(id, title, timeUpdated ?: timeCreated, messageCount)

    /**
     * 新建会话并切入。
     *
     * @return 新会话 id；失败返回 null，并把 [TaijiState.phase] 置为失败态
     *   （项目原则「失败必须可见」，绝不静默）。
     */
    suspend fun startNewSession(scope: CoroutineScope): String? {
        val id = createSession()
        if (id == null) {
            _state.update { it.copy(phase = TaijiPhase.Failed("无法创建新会话", retryable = true)) }
            return null
        }
        ocLog("新建会话 id=$id")
        switchSession(scope, id)
        return id
    }

    /**
     * 切换到指定会话（历史列表点击「恢复」）。
     *
     * 先清空消息并置 Loading —— 否则旧会话内容会在新会话加载完成前"闪现"，
     * 用户会以为切错了。之后复用 [open]（全量拉取 + 轮询 + SSE）。
     */
    suspend fun switchSession(scope: CoroutineScope, sessionId: String) {
        _state.update {
            it.copy(
                messages = emptyList(),
                parts = emptyMap(),
                loadedOnce = false,
                input = "",
                isStreaming = false,
                pendingPermission = null,
                phase = TaijiPhase.Loading,
                sessionId = sessionId,
            )
        }
        open(scope, sessionId)
    }

    /**
     * 删除会话（`DELETE /api/session/{id}`）。
     *
     * ⚠️ **不可撤销**：服务端连消息一起删。二次确认由 UI 负责（历史抽屉长按 → 确认弹窗），
     * 本方法只发请求并**如实返回结果**——绝不静默失败、绝不假装成功（项目原则）。
     *
     * 刻意**不碰** [TaijiState.sessionId]：删掉别的会话不该把当前会话切走；
     * 若删的正是当前会话，由调用方决定后续（UI 选择另起新会话）。
     *
     * @return true = 服务端已确认删除；false = 网络异常或服务端拒绝（原因已写日志）。
     */
    suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // 带空 body（Content-Length: 0）：部分服务端对无 body 的 DELETE 会直接 400。
            val req = Request.Builder()
                .url(http.url("/api/session/$sessionId"))
                .delete(EMPTY_BODY)
                .build()
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw OcHttpException(resp.code, "DELETE /api/session/$sessionId HTTP ${resp.code}")
                }
            }
            ocLog("已删除会话 id=$sessionId")
            true
        }.onFailure { ocLog("删除会话失败（id=$sessionId）：${it.message}") }.getOrDefault(false)
    }

    // ── 状态模型 ──────────────────────────────────────────────────────

}

// ── JSON 解析辅助（手写，避免为此引入序列化依赖）─────────────────────

private val jsonMediaType = "application/json".toMediaType()

// ── REST 轮询节奏（毫秒）───────────────────────────────────────────────
private const val POLL_FAST_MS = 1_000L      // 有变化：快轮询
private const val POLL_SLOW_MS = 3_000L      // 无变化：放慢
private const val POLL_IDLE_MS = 5_000L      // 持续无变化：最慢档
private val POLL_ERR_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)

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

/**
 * 解析会话元数据（`GET /api/session` 的元素）。
 *
 * 时间字段与消息同构：`{time:{created, updated}}`（毫秒）。缺失时回退 null，
 * 由 UI 用「更早」分组兜底——**不臆造时间**（沿用 E-005 "不装作知道"的纪律）。
 */
internal fun parseSession(o: JSONObject): OcSession {
    val time = o.optJSONObject("time")
    return OcSession(
        id = o.optString("id"),
        title = o.optString("title").takeIf { it.isNotEmpty() },
        timeCreated = time?.optLong("created")?.takeIf { it > 0 },
        timeUpdated = time?.optLong("updated")?.takeIf { it > 0 },
    )
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
        // ⚠️ 真实结构（实测 2.0.22）：{ type, id, name, executed, state:{ status, input, content, metadata }, time }
        //    工具名在顶层 **name**；入参在 **state.input**（对象）；结果在 **state.content[]**（数组）。
        //    顶层**没有** input/output —— 原实现读顶层 → 两者恒为 null，这正是「工具卡只显示状态图标」的真因。
        "tool" -> {
            val st = obj.optJSONObject("state")
            OcPart.Tool(
                id = id,
                toolName = obj.optString("name").takeIf { it.isNotEmpty() } ?: obj.optString("tool"),
                state = parseToolState(st),
                input = toolInputToText(st?.optJSONObject("input")),
                output = toolContentToText(st?.optJSONArray("content")),
            )
        }
        "file" -> OcPart.File(id, obj.optString("filename"), obj.optString("mime"))
        else -> OcPart.Unknown(id, obj.optString("type"), obj.toString())
    }
}

internal fun parseToolState(state: JSONObject?): ToolState = when (state?.optString("status")) {
    "running", "pending", "in_progress" -> ToolState.Running
    "completed", "success", "succeeded" -> ToolState.Success
    "error", "failed", "failure" -> ToolState.Error
    else -> ToolState.Unknown
}

/**
 * 工具入参 → 可读文本。
 *
 * 优先取 command / path 等**单值字段**（用户要的「命令 / 文件路径」），拿不到再回退整段 JSON。
 * 实测 shell 的 `state.input` = `{"command":"ls -A1","workdir":"…"}`。
 */
internal fun toolInputToText(o: JSONObject?): String? {
    o ?: return null
    for (k in listOf("command", "filePath", "path", "pattern", "query", "url")) {
        val v = o.optString(k)
        if (v.isNotEmpty()) return v
    }
    return o.toString().takeIf { it.isNotEmpty() && it != "{}" }
}

/**
 * 工具结果 → 可读文本。
 *
 * 实测 `state.content` = `[{"type":"text","text":"33\n"}]`（数组），拼接各段的 text。
 */
internal fun toolContentToText(arr: JSONArray?): String? {
    arr ?: return null
    val sb = StringBuilder()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val t = o.optString("text").takeIf { it.isNotEmpty() }
            ?: o.optString("content").takeIf { it.isNotEmpty() }
            ?: o.toString()
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(t)
    }
    return sb.toString().takeIf { it.isNotEmpty() }
}

/**
 * 解析权限请求（v2 真实载荷，见 openapi `Permission.Request`）。
 *
 * `{ id(^per), sessionID(^ses), action, resources:[], save:[], metadata, source, message }`
 *
 * ⚠️ 旧实现读 `permissionID` / `title` / `description` —— 本版**都没有**，
 * 结果是解析出空标题、空目标的抽屉（"请求授权但什么都不显示"）。
 * 事件可能带 `{type, properties:{…}}` 外壳，故 properties 与自身都尝试。
 */
internal fun parsePermission(raw: String): OcPermission? = runCatching {
    val o = JSONObject(raw)
    // 三种外衣都要认（实测）：
    //   SSE 事件信封 `{id(evt_),created,type,location,data:{Permission.Request}}` → 取 **data**；
    //   `/api/permission/request` 的 data[] 元素 = 裸的 Permission.Request → 取自身；
    //   旧版 `{type, properties:{…}}` → 取 properties。
    // ⚠️ 不剥 data 会取到**事件 id（evt_…）**当 requestID → 回执必然 HTTP 400（已踩）。
    val p = o.optJSONObject("data") ?: o.optJSONObject("properties") ?: o
    val id = p.optString("id").takeIf { it.isNotEmpty() }
        ?: p.optString("permissionID").takeIf { it.isNotEmpty() }
        ?: return null
    val resources = p.optJSONArray("resources")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() } }
    } ?: emptyList()
    OcPermission(
        permissionId = id,
        sessionId = p.optString("sessionID"),
        title = p.optString("action").takeIf { it.isNotEmpty() } ?: "工具请求授权",
        detail = resources.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: p.optJSONObject("metadata")?.optString("command")?.takeIf { it.isNotEmpty() }
            ?: p.optString("message").takeIf { it.isNotEmpty() },
        type = p.optString("action").takeIf { it.isNotEmpty() },
        resources = resources,
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
