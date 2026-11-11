// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · A2A 客户端（**手写**，OkHttp + org.json，不引 a2a-java-sdk——
// 派工单红线：A2A 手写客户端）。四步线：agent card → message/send → 任务状态轮询 → artifacts。
//
// 线形（A2A v1.0，JSON-RPC 2.0 over HTTP POST）：
//   GET  {base}/.well-known/agent-card.json            → 卡（能力/技能/安全方案的声明）
//   POST {card.url} message/send {message{parts…}}     → Task（status.state=working…）
//   POST {card.url} tasks/get   {id}                   → Task（轮询到终态）
//   Task.artifacts[]                                    → 产物（文本件收进上下文）
//
// 失败与 MCP 探针同一把尺（[PeerFailureClassify]）：DNS/端口/证书/超时/协议/鉴权各是各的类。
// 红线：不打印请求头/凭据；凭据只从调用方给的 [A2aCredential] 过一手内存，本类不落盘。
//
// 第五百九十批 · A2A 收口（四件，立单见交付报告）：
//   ① 卡上 protocolVersion 先判支持区间（[ensureProtocolInRange]），超区间 VERSION_MISMATCH，一个字节不发；
//   ② HTTP 状态码按序分（[classifyHttpFailure]）：401/403→AUTH、404→ENDPOINT、400/422+version→VERSION_MISMATCH、
//      其余 4xx→PROTOCOL、5xx→NETWORK——不再一律 NETWORK；
//   ③ [taskCancel]：A2A tasks/cancel，终态收敛且幂等（spec §3.3.1）；
//   ④ 非 ASCII 凭据本机拦下归 CONFIG（[ensureAsciiCredential]），人话指路「换 ASCII token 或改用自定头」。
package com.example.zhengdao.baopu.peer

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** A2A agent card（只留接入要用的字段；整卡原文不落盘）。 */
internal data class A2aCard(
    val name: String,
    val description: String,
    /** 请求入口（卡上的 url；没有就退回拉卡的那个 base）。 */
    val url: String,
    val version: String,
    val protocolVersion: String,
    val capabilities: List<String>,
    val skills: List<A2aSkill>,
    /** 声明的安全方案名（apiKey/bearer/…的**名字**，不含任何凭据材料）。 */
    val securitySchemeNames: List<String>,
) {
    fun capabilityFingerprint(): String =
        PeerFingerprints.ofAgentCard(capabilities, skills.map { it.id })
}

internal data class A2aSkill(val id: String, val name: String, val description: String)

/** A2A 任务状态（闭集；终态四个，两个「停下来找人」的中间态单独给）。 */
internal enum class A2aTaskState(val wire: String) {
    SUBMITTED("submitted"),
    WORKING("working"),
    INPUT_REQUIRED("input-required"),
    AUTH_REQUIRED("auth-required"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELED("canceled"),
    REJECTED("rejected"),
    UNKNOWN("unknown"),
    ;

    val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED
    /** 轮询在这里停但不许当成功：要人补输入 / 要授权（549 交左相的正是这两种）。 */
    val needsHuman: Boolean get() = this == INPUT_REQUIRED || this == AUTH_REQUIRED

    companion object {
        fun ofWire(wire: String): A2aTaskState =
            entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** 一个产物（文本件；非文本件只记名字，内容不硬读）。 */
internal data class A2aArtifact(val artifactId: String, val name: String, val texts: List<String>)

/** 一次委派的任务读数。 */
internal data class A2aTask(
    val id: String,
    val contextId: String,
    val state: A2aTaskState,
    val statusMessage: String,
    val artifacts: List<A2aArtifact>,
)

/** 出入凭据（调用方从 McpVault 取来；只过内存，A2aClient 不存不记不打印）。 */
internal data class A2aCredential(val headerName: String, val value: String)

/** A2A 的唯一异常形状：failureCode 已归类，human 已是人话。 */
internal class A2aException(val failureCode: PeerFailureCode, human: String, cause: Throwable? = null) :
    Exception(human, cause)

internal class A2aClient(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val log: (String) -> Unit = {},
    private val credential: A2aCredential? = null,
) {

    private var nextId = 1L

    private fun rpcId(): Long = nextId++

    // ── ① agent card ────────────────────────────────────────────────────

    /** 拉卡。404/不是 JSON/缺 name ⇒ 协议不符（对方不是一路 A2A）。 */
    fun fetchCard(cardTimeoutMs: Long = DEFAULT_TIMEOUT_MS): A2aCard {
        val base = baseUrl.trimEnd('/')
        val req = baseRequest("$base/.well-known/agent-card.json")
            .get()
            .build()
        val bodyText = executeText(req, "拉 agent card", cardTimeoutMs)
        val json = parseJson(bodyText, "agent card")
        val name = json.optString("name").trim()
        if (name.isEmpty()) throw A2aException(PeerFailureCode.PROTOCOL, "「$baseUrl」的 agent card 里没有 name：不是一张能用的卡（协议不符）")
        val cardUrl = json.optString("url").trim().ifEmpty { base }
        val caps = json.optJSONObject("capabilities")
        return A2aCard(
            name = name,
            description = json.optString("description").trim(),
            url = cardUrl,
            version = json.optString("version").trim(),
            protocolVersion = json.optString("protocolVersion").trim(),
            capabilities = caps?.keys()?.asSequence()?.sorted()?.toList() ?: emptyList(),
            skills = parseSkills(json.optJSONArray("skills")),
            securitySchemeNames = json.optJSONObject("securitySchemes")?.keys()?.asSequence()?.sorted()?.toList()
                ?: emptyList(),
        )
    }

    private fun parseSkills(arr: JSONArray?): List<A2aSkill> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            if (id.isEmpty()) return@mapNotNull null
            A2aSkill(id, o.optString("name").trim(), o.optString("description").trim())
        }
    }

    // ── ② message/send ──────────────────────────────────────────────────

    /** 发一条消息开任务（或带 taskId 续任务）。返回当时的任务读数。 */
    fun messageSend(card: A2aCard, text: String, taskId: String? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS): A2aTask {
        val message = JSONObject()
            .put("role", "user")
            .put("kind", "message")
            .put("messageId", "zd-${rpcId()}-${System.currentTimeMillis()}")
            .put("parts", JSONArray().put(JSONObject().put("kind", "text").put("text", text)))
        if (taskId != null) message.put("taskId", taskId)
        val params = JSONObject().put("message", message)
        val task = rpcCall(card, "message/send", params, timeoutMs)
        return parseTask(task, "message/send")
    }

    // ── ③ tasks/get（轮询一次） ─────────────────────────────────────────

    fun taskGet(card: A2aCard, taskId: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): A2aTask {
        val params = JSONObject().put("id", taskId)
        val task = rpcCall(card, "tasks/get", params, timeoutMs)
        return parseTask(task, "tasks/get")
    }

    // ── ③ tasks/cancel（590 收口③：补上 570 记的 G-570-3 缺口） ──────────

    /**
     * 请求取消任务（A2A `tasks/cancel`；spec §3.3.1：取消是幂等的，重复取消同效）。
     *
     * 终态收敛：服务器用 JSON-RPC 错误拒了（多半 `TaskNotCancelableError`——任务已终态），
     * 回读一次 `tasks/get`：读到一个终态就照它收（幂等——再取消不炸不重演）；
     * 没读到终态就把**原错**原样抛回去——不许把「取消失败」谎报成「已取消」。
     */
    fun taskCancel(card: A2aCard, taskId: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): A2aTask =
        try {
            parseTask(rpcCall(card, METHOD_CANCEL, JSONObject().put("id", taskId), timeoutMs), METHOD_CANCEL)
        } catch (e: A2aException) {
            if (e.failureCode != PeerFailureCode.PROTOCOL) throw e
            val readback = runCatching { taskGet(card, taskId, timeoutMs) }.getOrNull()
            readback?.takeIf { it.state.terminal } ?: throw e
        }

    /**
     * 发送并轮询到停（终态 / 要人 / 超时）。
     *
     * @param sleeper 等待（可注入，单测零真实睡眠）；返回 false = 调用方喊停
     * @return 最后一次的任务读数；[A2aRun.timedOut] 为 true 时没到终态
     */
    fun sendAndPoll(
        card: A2aCard,
        text: String,
        taskId: String? = null,
        pollEveryMs: Long = 1_000L,
        maxWaitMs: Long = DEFAULT_POLL_MAX_MS,
        sleeper: (Long) -> Boolean = { ms ->
            runCatching { Thread.sleep(ms) }.isSuccess
        },
        nowMs: () -> Long = System::currentTimeMillis,
    ): A2aRun {
        var task = messageSend(card, text, taskId)
        val deadline = nowMs() + maxWaitMs
        while (!task.state.terminal && !task.state.needsHuman) {
            val remain = deadline - nowMs()
            if (remain <= 0) return A2aRun(task, timedOut = true)
            if (!sleeper(minOf(pollEveryMs, remain))) return A2aRun(task, timedOut = false)
            task = taskGet(card, task.id)
        }
        return A2aRun(task, timedOut = false)
    }

    // ── 线下：JSON-RPC 传输 ─────────────────────────────────────────────

    private fun rpcCall(card: A2aCard, method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        ensureProtocolInRange(card)
        val body = JSONObject()
            .put("jsonrpc", JSONRPC_VERSION)
            .put("id", rpcId())
            .put("method", method)
            .put("params", params)
        val req = baseRequest(card.url.trimEnd('/'))
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        val text = executeText(req, method, timeoutMs)
        val json = parseJson(text, method)
        if (json.has("error")) {
            val err = json.optJSONObject("error") ?: JSONObject()
            val msg = err.optString("message").ifBlank { "没有错误说明" }
            throw A2aException(PeerFailureCode.PROTOCOL, "「${card.name}」的 $method 被拒（${err.optInt("code")}）：$msg")
        }
        return json.optJSONObject("result") ?: throw A2aException(
            PeerFailureCode.PROTOCOL,
            "「${card.name}」的 $method 没有返回 result：不是一路 A2A（协议不符）",
        )
    }

    /**
     * 590 收口①：卡上的 protocolVersion 先判支持区间（spec §3.6：按 Major.Minor 比，patch 不参与）。
     * 超区间（含卡上没写/认不出）直接 VERSION_MISMATCH——这一个字节都不发。
     */
    private fun ensureProtocolInRange(card: A2aCard) {
        val major = card.protocolVersion.trim().substringBefore('.').toIntOrNull()
        if (major == SUPPORTED_PROTOCOL_MAJOR) return
        val declared = card.protocolVersion.trim().ifEmpty { "（卡上没写）" }
        throw A2aException(
            PeerFailureCode.VERSION_MISMATCH,
            "「${card.name}」的 A2A 协议版本是「$declared」：本客户端支持 $SUPPORTED_PROTOCOL_MAJOR.x" +
                "——版本对不上，一个字节不发（协议版本不符）",
        )
    }

    private fun parseTask(result: JSONObject, stage: String): A2aTask {
        // A2A 也允许 message/send 直接回一条完成的消息（kind=message）——收进一个完成态任务。
        val task = if (result.optString("kind") == "message") {
            JSONObject()
                .put("id", result.optString("messageId"))
                .put("status", JSONObject().put("state", "completed"))
                .put(
                    "artifacts",
                    JSONArray().put(
                        JSONObject().put("artifactId", "inline").put("name", "reply")
                            .put("parts", result.optJSONArray("parts") ?: JSONArray()),
                    ),
                )
        } else {
            result
        }
        val id = task.optString("id").trim()
        if (id.isEmpty()) {
            throw A2aException(PeerFailureCode.PROTOCOL, "$stage 的结果里没有任务 id：不是一路 A2A（协议不符）")
        }
        val status = task.optJSONObject("status") ?: JSONObject()
        val state = A2aTaskState.ofWire(status.optString("state"))
        return A2aTask(
            id = id,
            contextId = task.optString("contextId"),
            state = state,
            statusMessage = status.optJSONObject("message")?.optString("content").orEmpty(),
            artifacts = parseArtifacts(task.optJSONArray("artifacts")),
        )
    }

    private fun parseArtifacts(arr: JSONArray?): List<A2aArtifact> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val parts = o.optJSONArray("parts") ?: JSONArray()
            val texts = (0 until parts.length()).mapNotNull { j ->
                val p = parts.optJSONObject(j) ?: return@mapNotNull null
                p.optString("text").takeIf { p.optString("kind") == "text" && it.isNotEmpty() }
            }
            A2aArtifact(o.optString("artifactId"), o.optString("name"), texts)
        }
    }

    // ── 传输小件 ────────────────────────────────────────────────────────

    private fun baseRequest(url: String): Request.Builder {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        credential?.takeIf { it.value.isNotBlank() }?.let {
            ensureAsciiCredential(it) // 590 收口④：本机就拦下，归 CONFIG，一个字节不发
            b.header(it.headerName, it.value)
        }
        return b
    }

    /**
     * 590 收口④：HTTP 头值只放 ASCII（OkHttp 对非 ASCII 抛 IllegalArgumentException，
     * 曾顺着分类尺被归成「协议不符」——不对，这是本机配置问题，归 CONFIG）。
     */
    private fun ensureAsciiCredential(cred: A2aCredential) {
        val badInValue = cred.value.firstOrNull { it.code < ASCII_MIN || it.code > ASCII_MAX }
        val badInName = cred.headerName.firstOrNull { it.code < ASCII_MIN || it.code > ASCII_MAX }
        if (badInValue == null && badInName == null) return
        throw A2aException(
            PeerFailureCode.CONFIG,
            "「$baseUrl」的凭据没法装进请求头：token/头名里有非 ASCII 字符" +
                "——请换 ASCII token 或改用自定头（本机配置问题，请求未发出）",
        )
    }

    /**
     * 590 收口②：HTTP 状态码按序分（不再一律 NETWORK）：
     * 401/403→AUTH；404→ENDPOINT；400/422 且错误体点名 version→VERSION_MISMATCH
     * （spec §6.4 的 400 版本拒绝体里就有 "version-not-supported"/"supportedVersions"）；
     * 其余 4xx→PROTOCOL；5xx/3xx→NETWORK。
     */
    private fun classifyHttpFailure(httpCode: Int, errorBody: String): PeerFailureCode = when {
        httpCode == 401 || httpCode == 403 -> PeerFailureCode.AUTH
        httpCode == 404 -> PeerFailureCode.ENDPOINT
        (httpCode == 400 || httpCode == 422) && errorBody.contains("version", ignoreCase = true) ->
            PeerFailureCode.VERSION_MISMATCH
        httpCode in 400..499 -> PeerFailureCode.PROTOCOL
        else -> PeerFailureCode.NETWORK
    }

    private fun executeText(req: Request, stage: String, timeoutMs: Long): String {
        val call = http.newCall(req)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        val response = try {
            call.execute()
        } catch (e: Exception) {
            val code = PeerFailureClassify.ofThrowable(e)
            throw A2aException(code, PeerFailureText.human(code, baseUrl), e)
        }
        response.use { r ->
            val text = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
            if (!r.isSuccessful) {
                val code = classifyHttpFailure(r.code, text)
                throw A2aException(code, "${PeerFailureText.human(code, baseUrl)}（HTTP ${r.code}，$stage）")
            }
            if (text.isBlank()) {
                throw A2aException(PeerFailureCode.PROTOCOL, "「$baseUrl」的 $stage 返回了空响应（协议不符）")
            }
            return text
        }
    }

    private fun parseJson(text: String, stage: String): JSONObject = try {
        JSONObject(text)
    } catch (e: Exception) {
        throw A2aException(PeerFailureCode.PROTOCOL, "「$baseUrl」的 $stage 不是合法 JSON：不是一路 A2A（协议不符）", e)
    }

    companion object {
        const val JSONRPC_VERSION = "2.0"
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /** 委派的默认轮询上限（549 接线时可调）。 */
        const val DEFAULT_POLL_MAX_MS = 120_000L

        /** 支持的 A2A 协议主版本（支持区间 [1.0, 2.0)；patch 不参与比较，spec §3.6）。 */
        const val SUPPORTED_PROTOCOL_MAJOR = 1

        /** 头值/头名的合法字符界（ASCII 可打印＋空白；界外即本机配置错）。 */
        private const val ASCII_MIN = 0x20
        private const val ASCII_MAX = 0x7e

        private const val METHOD_CANCEL = "tasks/cancel"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** 一次「发送＋轮询」的收场。 */
internal data class A2aRun(val task: A2aTask, val timedOut: Boolean) {
    /** 停在 input-required / auth-required：不是失败，是要人。 */
    val needsHuman: Boolean get() = task.state.needsHuman
}
