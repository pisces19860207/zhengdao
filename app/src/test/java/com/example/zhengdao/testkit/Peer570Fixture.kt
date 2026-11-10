// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · **570 号自测床**的 JVM 起步版：本机假 MCP（streamable HTTP）＋假 A2A。
// （570 号票＝「A2A 自测床」，全量件待派；本文件只做 548 判据要的那几路，测试专用，
//  不进生产——引用只许出现在 app/src/test。）
//
// 脚本面（默认全绿，各失败路改一个开关）：
// · MCP：initialize 回声协议版本（可改旧版）/可 401 /可延时（超时路）；
//   tools/list 回两个工具（schema 可换 ⇒ 指纹漂移路）。
// · A2A：GET 卡；message/send 回任务；tasks/get 按脚本队列吐状态（终态带 artifacts）。
// 记录面：MCP 收到的 JSON-RPC 方法序列（判据④的断言对象）；A2A 的 GET/轮询计数。
package com.example.zhengdao.testkit

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

class Peer570Fixture {

    val mcp = MockWebServer()
    val a2a = MockWebServer()

    // ── MCP 脚本 ─────────────────────────────────────────────────────────
    /** initialize 回声的协议版本（改成旧版 = 协议不符路）。 */
    @Volatile
    var protocolEcho: String = MCP_PROTOCOL

    /** true ⇒ initialize 直接 401（需鉴权路）。 */
    @Volatile
    var mcpNeedsAuth: Boolean = false

    /** initialize 前睡多少毫秒（超时路；客户端超时要短于它）。 */
    @Volatile
    var initDelayMs: Long = 0L

    /** 工具 A（read_note）的入参 schema（换掉它 = 指纹漂移路）。 */
    @Volatile
    var toolASchema: String = """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}"""

    /** 工具 B（write_note）的入参 schema。 */
    @Volatile
    var toolBSchema: String = """{"type":"object","properties":{"text":{"type":"string"}}}"""

    // ── A2A 脚本 ─────────────────────────────────────────────────────────
    /** tasks/get 依次吐的状态（走完队列停在最后一个）。 */
    @Volatile
    var a2aStates: List<String> = listOf("working", "completed")

    /** 卡上公布的名字。 */
    @Volatile
    var a2aAgentName: String = "守夜人-570"

    /** true ⇒ 卡上多公布一项技能（A2A 能力漂移路：指纹随技能清单变）。 */
    @Volatile
    var a2aExtraSkill: Boolean = false

    // ── 记录面 ───────────────────────────────────────────────────────────
    /** MCP 收到的 JSON-RPC 方法序列（含通知；判据④在这里断言）。 */
    val mcpMethods: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** A2A 拉卡次数。 */
    val a2aCardGets = AtomicInteger(0)

    /** A2A tasks/get 次数。 */
    val a2aPolls = AtomicInteger(0)

    fun start() {
        mcp.dispatcher = mcpDispatcher()
        a2a.dispatcher = a2aDispatcher()
        mcp.start()
        a2a.start()
    }

    fun close() {
        mcp.close()
        a2a.close()
    }

    /** MockWebServer.url() 的主机名是机器名，而 MCP URL 规则只放行环回明文 ⇒ 一律 127.0.0.1 拼。 */
    fun mcpUrl(path: String = "/mcp"): String = "http://127.0.0.1:${mcp.port}$path"

    fun a2aBase(): String = "http://127.0.0.1:${a2a.port}"

    // ── 内部：两台假服务端 ───────────────────────────────────────────────

    private fun bodyOf(req: RecordedRequest): JSONObject =
        runCatching { JSONObject(req.body?.utf8().orEmpty()) }.getOrDefault(JSONObject())

    private fun reply(req: RecordedRequest, resultJson: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(JSONObject().put("jsonrpc", "2.0").put("id", bodyOf(req).opt("id")).put("result", JSONObject(resultJson)).toString())
            .build()

    private fun mcpDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            val body = bodyOf(req)
            val method = body.optString("method")
            if (method.isNotEmpty()) mcpMethods.add(method)
            return when (method) {
                "initialize" -> when {
                    mcpNeedsAuth -> MockResponse.Builder().code(401).build()
                    else -> {
                        if (initDelayMs > 0) Thread.sleep(initDelayMs)
                        reply(req, """{"protocolVersion":"$protocolEcho","capabilities":{"tools":{},"logging":{}},""" +
                            """"serverInfo":{"name":"570-fake","version":"1.0"}}""")
                            .newBuilder().setHeader("Mcp-Session-Id", "session-570").build()
                    }
                }
                "notifications/initialized" -> MockResponse.Builder().code(202).build()
                "tools/list" -> reply(req, toolsJson())
                "tools/call" -> MockResponse.Builder().code(200).body(
                    JSONObject().put("jsonrpc", "2.0").put("id", body.opt("id"))
                        .put("error", JSONObject().put("code", -32601).put("message", "570 夹具不该被调用业务工具")).toString(),
                ).build()
                else -> reply(req, "{}")
            }
        }
    }

    private fun toolsJson(): String {
        val tools = JSONArray()
            .put(JSONObject().put("name", "read_note").put("description", "读一条笔记（570 假件）").put("inputSchema", JSONObject(toolASchema)))
            .put(JSONObject().put("name", "write_note").put("description", "写一条笔记（570 假件）").put("inputSchema", JSONObject(toolBSchema)))
        return JSONObject().put("tools", tools).toString()
    }

    private fun a2aDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            val path = req.url?.encodedPath.orEmpty()
            if (req.method == "GET" && path.contains("agent-card.json")) {
                a2aCardGets.incrementAndGet()
                val skills = if (a2aExtraSkill) {
                    """"skills":[{"id":"summarize","name":"摘要","description":"把长文变短","tags":["text"]},""" +
                        """{"id":"translate","name":"翻译","description":"A 语到 B 语","tags":["text"]}],"""
                } else {
                    """"skills":[{"id":"summarize","name":"摘要","description":"把长文变短","tags":["text"]}],"""
                }
                return MockResponse.Builder().code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"name":"$a2aAgentName","description":"570 假 A2A","url":"${a2aBase()}/",""" +
                            """"version":"1.0","protocolVersion":"1.0","capabilities":{"streaming":false},""" +
                            """"defaultInputModes":["text"],"defaultOutputModes":["text"],""" +
                            skills +
                            """"securitySchemes":{"apiKey":{"type":"apiKey","in":"header","name":"X-Api-Key"}}}""",
                    ).build()
            }
            if (req.method != "POST") return MockResponse.Builder().code(404).build()
            val body = bodyOf(req)
            return when (body.optString("method")) {
                "message/send" -> reply(req, taskJson("submitted", withArtifacts = false))
                "tasks/get" -> {
                    a2aPolls.incrementAndGet()
                    val idx = (a2aPolls.get() - 1).coerceAtMost(a2aStates.size - 1)
                    val state = a2aStates[idx]
                    reply(req, taskJson(state, withArtifacts = state == "completed"))
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
    }

    private fun taskJson(state: String, withArtifacts: Boolean): String {
        val task = JSONObject()
            .put("id", "task-570")
            .put("contextId", "ctx-570")
            .put("kind", "task")
            .put("status", JSONObject().put("state", state).put("timestamp", "2026-11-10T00:00:00Z"))
        if (withArtifacts) {
            task.put(
                "artifacts",
                JSONArray().put(
                    JSONObject().put("artifactId", "art-570").put("name", "摘要稿")
                        .put("parts", JSONArray().put(JSONObject().put("kind", "text").put("text", "570 的产出正文"))),
                ),
            )
        }
        return task.toString()
    }

    companion object {
        /** 与 McpJsonRpc.PROTOCOL 一致（测试里显式写死，防夹具依赖生产实现顺序）。 */
        const val MCP_PROTOCOL = "2026-07-28"
    }
}
