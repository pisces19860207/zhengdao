// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百七十批 · **A2A 自测床**（测试专用，**不进生产**：引用只许出现在 app/src/test，
// 由 570BedTest 的源码门禁把守）。
//
// 为什么单起一个文件：548 里已经有一份「起步版」（[Peer570Fixture]，A2A 只够 548 自己的
// 四条用例）。570 要的是**可编排的靶子**——三种剧本 × 三种失败注入，且同一剧本重跑读数
// 必须逐条一致。两件事混在一个类里会让 548 的既有用例跟着改，所以并排放，不动旧件。
//
// 线形与 548 的手写客户端严格对齐（A2A v1.0，JSON-RPC 2.0 over HTTP POST）：
//   GET  {base}/.well-known/agent-card.json  → 卡（548 客户端走这条）
//   GET  {base}/.well-known/agent.json       → 同一张卡（派工单 570 ① 写的路径，别名给上）
//   POST {base}/  message/send {message}     → Task（state=submitted）
//   POST {base}/  tasks/get   {id}           → Task（按剧本推：working → 终态）
//   POST {base}/  tasks/cancel{id}           → Task（canceled，且此后 tasks/get 一律 canceled）
//
// 剧本（[A2aBedScript]）：正常完成 · 中途 input-required · 失败 failed。
// 失败注入（[A2aBedFault]）：协议版本不符 · 错 token · 超时（给 548 的失败分类当靶子）。
//   · BAD_PROTOCOL：卡上公布旧版 protocolVersion，且所有 RPC 一律回 -32001（对端拒收）；
//   · BAD_TOKEN：本床只认 [token]，卡与 RPC 都验 X-Api-Key，错/没带 ⇒ 401；
//   · TIMEOUT：**只延时 RPC**（拉卡照常快回，这样用例能先拿到卡再让发送超时）。
//
// 幂等（判据④）：不用随机数、不读时钟——任务号按发送序号递增、时间戳写死常量、
// 每个任务一条独立状态游标（新 send 建新任务 ⇒ 游标从头走），所以同一剧本跑多少遍，
// [servedTicks] 逐条相同。取消是粘住的：置了 canceled 就不再动游标。
//
// 第五百四十九批 · **续任务**（回复回路的另一半）：message/send 带着已有任务的
// taskId 回来 ⇒ **不建新任务**，同一 TaskCell 的游标换到 [replyScript] 从头走，
// 回执报 working（A2A spec §3.2：续话是对同一任务继续处理）。续过几次记在
// [replies]（断言"是续，不是重开"就数它）。不带 taskId 的 send 一字不改——
// 570 既有用例钉的行为原样。
package com.example.zhengdao.testkit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/** 三种可编排的剧本。[states] 是 `tasks/get` 依次吐的状态，走完停最后一个。 */
enum class A2aBedScript(
    internal val states: List<String>,
    /** 只有 completed 才带 artifacts；null ⇒ 不带。 */
    internal val artifactText: String?,
    /** 状态里的说明（`status.message.content`，548 读这个字段）。 */
    internal val statusText: String,
) {
    NORMAL(listOf("working", "completed"), "570 的产出正文", "已收下，这就去办"),
    INPUT_REQUIRED(listOf("working", "input-required"), null, "还差一份原文，请先补上"),
    FAILED(listOf("working", "failed"), null, "上游拒了这次请求（570 剧本三）"),
}

/** 失败注入（闭集；一次只开一路，好让「哪一路」在断言里说得清）。 */
enum class A2aBedFault {
    NONE,

    /** 卡上公布旧版协议，RPC 一律 -32001（对端明说不认这个版本）。 */
    BAD_PROTOCOL,

    /** 只认 [Peer570A2aBed.TOKEN]；错 token / 没带 ⇒ 401。 */
    BAD_TOKEN,

    /** RPC 先睡 [Peer570A2aBed.faultDelayMs] 毫秒（客户端超时要短于它）。 */
    TIMEOUT,
}

/**
 * 本机假 A2A 服务端。用法：`start()` → 用 548 的 `A2aClient` 连 [base] → `close()`。
 *
 * 全类不碰文件系统（不建临时目录、不落盘），也不发起任何对外请求——只监听 127.0.0.1。
 */
class Peer570A2aBed {

    val server = MockWebServer()

    /** 用哪套剧本。运行中可改（下一个 `message/send` 起生效）。 */
    @Volatile
    var script: A2aBedScript = A2aBedScript.NORMAL

    /**
     * 续任务的剧本（第五百四十九批）：input-required 之后对方回话续任务，
     * 游标换到这一套从头走（缺省就是正常完成——"补上料就办完了"）。
     */
    @Volatile
    var replyScript: A2aBedScript = A2aBedScript.NORMAL

    /** 失败注入。 */
    @Volatile
    var fault: A2aBedFault = A2aBedFault.NONE

    /** BAD_TOKEN 认的那把 token。 */
    var token: String = TOKEN

    /** TIMEOUT 的睡眠时长（默认远大于用例里给的客户端超时）。 */
    @Volatile
    var faultDelayMs: Long = 1_500L

    /** 卡上公布的名字（改它 = 换对端身份）。 */
    var agentName: String = "守夜人-570床"

    /** 收到的拉卡路径（按序；断言「两条 well-known 都给」）。 */
    val cardPaths: MutableList<String> = CopyOnWriteArrayList()

    /** 收到的 RPC 方法序列（断言剧本确实被走到）。 */
    val rpcMethods: MutableList<String> = CopyOnWriteArrayList()

    /** 本床实际吐出去的状态序列；幂等判据的断言对象。 */
    val servedTicks: MutableList<String> = CopyOnWriteArrayList()

    /** 被续过的任务号（按续话顺序；断言"是续，不是重开"就数它）。 */
    val replies: MutableList<String> = CopyOnWriteArrayList()

    private val tasks = ConcurrentHashMap<String, TaskCell>()
    private val sendCount = AtomicInteger(0)

    private class TaskCell(val id: String) {
        var cursor: Int = 0
        var canceled: Boolean = false

        /** 第五百四十九批：被回话续过 ⇒ 游标走 [Peer570A2aBed.replyScript]。 */
        var resumed: Boolean = false
    }

    fun start() {
        server.dispatcher = BedDispatcher()
        server.start()
    }

    fun close() {
        server.close()
    }

    /** 548 的 `A2aClient(baseUrl)` 要的就是这个（环回明文，端口随机）。 */
    fun base(): String = "http://127.0.0.1:${server.port}"

    /** 清空吐出的状态序列（同一剧本连跑两遍时用）。 */
    fun clearTicks() {
        servedTicks.clear()
    }

    // ── 内部：路由 ───────────────────────────────────────────────────────

    private inner class BedDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
    }

    private fun handle(req: RecordedRequest): MockResponse {
        val path = req.url?.encodedPath.orEmpty()
        if (req.method == "GET") {
            if (!isCardPath(path)) return notFound()
            cardPaths += path
            if (unauthorized(req)) return MockResponse.Builder().code(401).build()
            return json(200, cardJson())
        }
        if (req.method != "POST") return notFound()
        return rpc(bodyOf(req), req)
    }

    private fun isCardPath(path: String): Boolean =
        path.endsWith("/.well-known/agent-card.json") || path.endsWith("/.well-known/agent.json")

    private fun notFound(): MockResponse = MockResponse.Builder().code(404).build()

    // ── 内部：RPC ───────────────────────────────────────────────────────

    private fun rpc(body: JSONObject, req: RecordedRequest): MockResponse {
        val method = body.optString("method")
        if (method.isNotEmpty()) rpcMethods += method
        if (unauthorized(req)) return MockResponse.Builder().code(401).build()
        if (fault == A2aBedFault.BAD_PROTOCOL) {
            return delayed(rpcError(body, BAD_VERSION_CODE, "unsupported protocol version $cardProtocolVersion"))
        }
        val resp = when (method) {
            "message/send" -> messageSend(body)
            "tasks/get" -> taskGet(body)
            "tasks/cancel" -> taskCancel(body)
            else -> rpcError(body, METHOD_NOT_FOUND_CODE, "570 床不认这个方法：$method")
        }
        return delayed(resp)
    }

    private fun messageSend(body: JSONObject): MockResponse {
        val replyTo = body.optJSONObject("params")?.optJSONObject("message")?.optString("taskId").orEmpty()
        val existing = tasks[replyTo]
        if (existing != null) return resume(existing, body)
        val cell = TaskCell("task-570-${sendCount.incrementAndGet()}")
        tasks[cell.id] = cell
        tick(SUBMITTED, script)
        return rpcResult(body, taskJson(cell, SUBMITTED, script))
    }

    /** 第五百四十九批 · 续任务：同一 TaskCell 游标换到续剧本，回执报 working，不建新任务。 */
    private fun resume(cell: TaskCell, body: JSONObject): MockResponse {
        cell.resumed = true
        cell.cursor = 0
        replies += cell.id
        tick(WORKING, replyScript)
        return rpcResult(body, taskJson(cell, WORKING, replyScript))
    }

    private fun taskGet(body: JSONObject): MockResponse {
        val cell = cellOf(body) ?: return unknownTask(body)
        val state = nextState(cell)
        tick(state, activeScriptOf(cell))
        return rpcResult(body, taskJson(cell, state, activeScriptOf(cell)))
    }

    private fun taskCancel(body: JSONObject): MockResponse {
        val cell = cellOf(body) ?: return unknownTask(body)
        cell.canceled = true
        tick(CANCELED, activeScriptOf(cell))
        return rpcResult(body, taskJson(cell, CANCELED, activeScriptOf(cell)))
    }

    private fun cellOf(body: JSONObject): TaskCell? =
        tasks[body.optJSONObject("params")?.optString("id").orEmpty()]

    private fun unknownTask(body: JSONObject): MockResponse =
        rpcError(body, BAD_PARAMS_CODE, "本床没有这个任务")

    /** 取消是粘住的：置了 canceled 就不再动游标，所以「再查仍是 canceled」。 */
    private fun nextState(cell: TaskCell): String {
        if (cell.canceled) return CANCELED
        val states = activeScriptOf(cell).states
        val idx = cell.cursor.coerceAtMost(states.size - 1)
        cell.cursor += 1
        return states[idx]
    }

    /** 这个任务此刻听哪套剧本的（续过 ⇒ 续剧本）。 */
    private fun activeScriptOf(cell: TaskCell): A2aBedScript = if (cell.resumed) replyScript else script

    private fun tick(state: String, src: A2aBedScript) {
        val text = if (state == COMPLETED) src.artifactText.orEmpty() else ""
        servedTicks += if (text.isEmpty()) state else "$state|$text"
    }

    // ── 内部：两张 JSON ──────────────────────────────────────────────────

    private fun cardJson(): String {
        val caps = JSONObject().put("streaming", false).put("pushNotifications", false)
        val skill = JSONObject()
            .put("id", "summarize")
            .put("name", "摘要")
            .put("description", "把长文变短（570 床）")
            .put("tags", JSONArray().put("text"))
        val scheme = JSONObject().put("apiKey", JSONObject().put("type", "apiKey").put("in", "header").put("name", TOKEN_HEADER))
        return JSONObject()
            .put("name", agentName)
            .put("description", "570 假 A2A：本机测试床，不是真服务")
            .put("url", base() + "/")
            .put("version", "1.0")
            .put("protocolVersion", cardProtocolVersion)
            .put("capabilities", caps)
            .put("defaultInputModes", JSONArray().put("text"))
            .put("defaultOutputModes", JSONArray().put("text"))
            .put("skills", JSONArray().put(skill))
            .put("securitySchemes", scheme)
            .toString()
    }

    private fun taskJson(cell: TaskCell, state: String, src: A2aBedScript): JSONObject {
        val status = JSONObject()
            .put("state", state)
            .put("timestamp", FIXED_TIMESTAMP)
            .put("message", JSONObject().put("kind", "message").put("content", src.statusText))
        val task = JSONObject()
            .put("id", cell.id)
            .put("contextId", CONTEXT_ID)
            .put("kind", "task")
            .put("status", status)
        src.artifactText?.takeIf { state == COMPLETED }?.let { text ->
            task.put("artifacts", JSONArray().put(artifactJson(text)))
        }
        return task
    }

    private fun artifactJson(text: String): JSONObject = JSONObject()
        .put("artifactId", ARTIFACT_ID)
        .put("name", "摘要稿")
        .put("parts", JSONArray().put(JSONObject().put("kind", "text").put("text", text)))

    // ── 内部：小件 ──────────────────────────────────────────────────────

    private fun bodyOf(req: RecordedRequest): JSONObject =
        runCatching { JSONObject(req.body?.utf8().orEmpty()) }.getOrDefault(JSONObject())

    private fun json(code: Int, body: String): MockResponse =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun rpcResult(body: JSONObject, result: JSONObject): MockResponse = json(
        200,
        JSONObject().put("jsonrpc", JSONRPC).put("id", body.opt("id")).put("result", result).toString(),
    )

    private fun rpcError(body: JSONObject, code: Int, message: String): MockResponse = json(
        200,
        JSONObject().put("jsonrpc", JSONRPC).put("id", body.opt("id"))
            .put("error", JSONObject().put("code", code).put("message", message)).toString(),
    )

    /** 超时路：把响应挂住（只挂 RPC；拉卡要快，用例才好先拿到卡再让发送超时）。 */
    private fun delayed(resp: MockResponse): MockResponse =
        if (fault == A2aBedFault.TIMEOUT) {
            resp.newBuilder().headersDelay(faultDelayMs, TimeUnit.MILLISECONDS).build()
        } else {
            resp
        }

    private fun unauthorized(req: RecordedRequest): Boolean =
        fault == A2aBedFault.BAD_TOKEN && req.headers[TOKEN_HEADER] != token

    private val cardProtocolVersion: String get() =
        if (fault == A2aBedFault.BAD_PROTOCOL) UNSUPPORTED_PROTOCOL else PROTOCOL

    companion object {
        const val PROTOCOL = "1.0"
        const val UNSUPPORTED_PROTOCOL = "0.2.5"
        const val TOKEN = "sk-bed-570"
        const val TOKEN_HEADER = "X-Api-Key"
        const val CONTEXT_ID = "ctx-570"
        const val ARTIFACT_ID = "art-570"

        /** 失败注入要的三样：状态名、坏版本错误码、方法不存在错误码。 */
        const val SUBMITTED = "submitted"
        const val WORKING = "working"
        const val CANCELED = "canceled"
        const val COMPLETED = "completed"
        const val BAD_VERSION_CODE = -32001
        const val BAD_PARAMS_CODE = -32602
        const val METHOD_NOT_FOUND_CODE = -32601

        /** 时间戳写死：幂等判据④不许拿时钟做判定（读时钟 ⇒ 每遍读数都不同）。 */
        private const val FIXED_TIMESTAMP = "2026-11-11T00:00:00Z"
        private const val JSONRPC = "2.0"
    }
}
