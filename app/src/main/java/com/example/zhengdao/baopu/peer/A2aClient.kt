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
        credential?.takeIf { it.value.isNotBlank() }?.let { b.header(it.headerName, it.value) }
        return b
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
            if (!r.isSuccessful) {
                val code = if (r.code == 401 || r.code == 403) PeerFailureCode.AUTH else PeerFailureCode.NETWORK
                val why = if (code == PeerFailureCode.AUTH) {
                    PeerFailureText.human(PeerFailureCode.AUTH, baseUrl) + "（HTTP ${r.code}）"
                } else {
                    "「$baseUrl」的 $stage 返回 HTTP ${r.code}（网络/服务端错误）"
                }
                throw A2aException(code, why)
            }
            val text = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
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

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** 一次「发送＋轮询」的收场。 */
internal data class A2aRun(val task: A2aTask, val timedOut: Boolean) {
    /** 停在 input-required / auth-required：不是失败，是要人。 */
    val needsHuman: Boolean get() = task.state.needsHuman
}
