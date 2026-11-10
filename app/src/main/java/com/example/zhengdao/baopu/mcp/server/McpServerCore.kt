// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十四单 · MCP 服务端的**核心调度**：五件必备全部压在 handle() 的一条链上——
//
//     停机闸 → 鉴权(token) → 路由 → 白名单 → 租约 → 执行件缝 → 审计
//
// 顺序即安全：停机在最前（停机期连 initialize 都不答，判据④"停机后端口不再监听"
// 的最后一道兜底），鉴权其次（判据②：错 token ⇒ 401 ＋ 拒绝账，**先于**一切协议
// 处理——不给未授权者任何"探测服务器支持什么"的面）。纯 JVM：零 android.*，
// 单测直接打五闸的每一格。
package com.example.zhengdao.baopu.mcp.server

import com.example.zhengdao.baopu.agent.SchemaCheck
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.mcp.McpJsonRpc
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * 执行件的**缝**（与传输层/凭据库同一条 548 TODO）。
 *
 * 本单把五道闸全部立起来（token/白名单/租约/审计/停机），调用穿到最后一格时
 * 执行件由这里提供。缺省实现 **fail closed**：如实回"执行件未接线"，绝不假装成功——
 * 548（外部 Agent 接入）合入后由其执行实现顶替，接口不动。
 */
fun interface McpToolRunner {
    data class Outcome(val text: String, val isError: Boolean)

    fun run(spec: ToolSpec, args: JSONObject, opId: String): Outcome

    companion object {
        val UNAVAILABLE = McpToolRunner { spec, _, _ ->
            Outcome(
                "工具「${spec.name}」通过了鉴权/白名单/租约，但执行通道未接线：" +
                    "执行件归 548 外部 Agent 接入（TODO(548)），本单不越权执行。",
                isError = true,
            )
        }
    }
}

/**
 * 服务端核心。一个实例管一台传输层；线程安全性：本类无可变状态（计数器除外），
 * 由传输层保证请求串行进入各自连接线程。
 */
internal class McpServerCore(
    private val vault: McpVault,
    private val runner: McpToolRunner,
    private val haltProbe: () -> Boolean,
    private val serverVersion: String,
    /** 白名单提供方（默认目录现算；测试注入假表用）。 */
    private val whitelist: () -> List<ToolSpec> = { McpWhitelist.specs() },
    /** 时间源（测试注入）。 */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val opSeq = AtomicLong(0)

    /** 传输层回调的唯一入口：一条 HTTP 请求 → 一条 HTTP 响应。永不抛。 */
    fun handle(request: McpServerTransport.Request): McpServerTransport.Response {
        // ── 闸 0：停机（471）。停机期一概 503，连 initialize 都不答——
        //    端口关闭是主判据，这格是"关得慢一拍"时的兜底（watchdog 1s 内关端口）。
        if (haltProbe()) {
            McpAuditLedger.append(McpAuditLedger.EV_METHOD, mapOf("method" to "halted_drop", "outcome" to "503"))
            return McpServerTransport.Response(503, errJson("server_halted", "停机闸在位（471）：服务已停，先在安全台解除停机"))
        }

        // ── 闸 1：鉴权。错/缺 token ⇒ 401 ＋ 拒绝账（判据②）。比路由还前。
        val token = bearerOf(request.headers["authorization"])
        if (!vault.matches(token)) {
            McpAuditLedger.append(
                McpAuditLedger.EV_AUTH_FAIL,
                mapOf("outcome" to "401", "reason" to if (token == null) "missing_token" else "bad_token"),
            )
            return McpServerTransport.Response(
                401,
                errJson("unauthorized", "token 缺失或不匹配（WWW-Authenticate: Bearer）"),
            )
        }

        // ── 路由：只认 POST /mcp。别的路径/方法不存在（不泄露支持面）。
        if (request.path != MCP_PATH) {
            return McpServerTransport.Response(404, errJson("not_found", "只有 $MCP_PATH 一个端点"))
        }
        if (request.method != "POST") {
            return McpServerTransport.Response(405, errJson("method_not_allowed", "只支持 POST（SSE 长连归 548 传输实现）"))
        }

        // ── JSON-RPC 信封。
        val rpc = McpServerRpc.parse(request.bodyText())
        if (rpc == null) {
            McpAuditLedger.append(McpAuditLedger.EV_METHOD, mapOf("outcome" to "400", "reason" to "bad_jsonrpc"))
            return McpServerTransport.Response(400, McpServerRpc.error(null, McpServerRpc.Codes.PARSE, "不是合法的 JSON-RPC 请求"))
        }

        return when (rpc.method) {
            "initialize" -> onInitialize(rpc)
            "notifications/initialized" -> McpServerTransport.Response(202, "")
            "ping" -> onMethod(rpc, ok = true)
            "tools/list" -> onToolsList(rpc)
            "tools/call" -> onToolsCall(rpc)
            else -> onMethod(rpc, ok = false)
        }
    }

    // ── initialize ────────────────────────────────────────────────────────

    private fun onInitialize(rpc: McpServerRpc.RpcRequest): McpServerTransport.Response {
        McpAuditLedger.append(McpAuditLedger.EV_METHOD, mapOf("method" to "initialize", "outcome" to "ok", "tools" to whitelist().size.toString()))
        val result = JSONObject()
            .put("protocolVersion", McpJsonRpc.PROTOCOL)
            .put(
                "capabilities",
                JSONObject().put("tools", JSONObject().put("listChanged", false)),
            )
            .put(
                "serverInfo",
                JSONObject()
                    .put("name", SERVER_NAME)
                    .put("title", SERVER_TITLE)
                    .put("version", serverVersion),
            )
        return McpServerTransport.Response(200, McpServerRpc.result(rpc.id, result))
    }

    // ── tools/list ────────────────────────────────────────────────────────

    private fun onToolsList(rpc: McpServerRpc.RpcRequest): McpServerTransport.Response {
        val tools = JSONArray()
        whitelist().forEach { spec ->
            tools.put(
                JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("inputSchema", spec.inputSchema)
                    .put(
                        "annotations",
                        JSONObject()
                            .put("readOnlyHint", true)
                            .put("openWorldHint", false),
                    ),
            )
        }
        McpAuditLedger.append(McpAuditLedger.EV_METHOD, mapOf("method" to "tools/list", "outcome" to "ok", "count" to tools.length().toString()))
        return McpServerTransport.Response(200, McpServerRpc.result(rpc.id, JSONObject().put("tools", tools)))
    }

    // ── tools/call：白名单 → schema → 租约 → 执行件 → 审计 ────────────────

    private fun onToolsCall(rpc: McpServerRpc.RpcRequest): McpServerTransport.Response {
        val params = rpc.params ?: JSONObject()
        val name = params.optString("name").trim()
        if (name.isEmpty()) {
            return rpcError(rpc, McpServerRpc.Codes.INVALID_PARAMS, "tools/call 缺 name")
        }
        val args = params.optJSONObject("arguments") ?: JSONObject()

        // ── 闸 2：白名单（判据③）。不在名单 ⇒ 拒 ＋ 记账；一个字都不解释注册表内容。
        val spec = whitelist().firstOrNull { it.name == name }
        if (spec == null) {
            McpAuditLedger.append(
                McpAuditLedger.EV_TOOL_REJECT,
                mapOf("tool" to name, "outcome" to "denied", "reason" to "not_in_whitelist"),
            )
            return rpcError(
                rpc,
                McpServerRpc.Codes.SERVER_DENIED,
                "工具不在白名单：服务端只暴露 R0 只读工具（McpWhitelist），「$name」不可经 MCP 调用",
            )
        }

        // ── schema 校验（与注册表同一把尺 SchemaCheck；参数形状不对不进租约账）。
        SchemaCheck.validate(spec.inputSchema, args)?.let { problem ->
            McpAuditLedger.append(McpAuditLedger.EV_CALL, mapOf("tool" to name, "outcome" to "invalid_params"))
            return rpcError(rpc, McpServerRpc.Codes.INVALID_PARAMS, problem)
        }

        val opId = "mcp-${nowMs()}-${opSeq.incrementAndGet()}"

        // ── 闸 3：租约（每次调用必过；命中扣次，免卡类放行，其余 fail closed）。
        //    一次调用只落**一行** EV_CALL（lease 字段分得清 free / 租约 id / 拒），不拆两行。
        val leaseField: String = when (val verdict = McpCallLease.admit(spec, args, opId, nowMs())) {
            is McpCallLease.Verdict.Reject -> {
                McpAuditLedger.append(
                    McpAuditLedger.EV_LEASE_REJECT,
                    mapOf("tool" to name, "outcome" to "denied", "reason" to verdict.missReason),
                )
                return rpcError(
                    rpc,
                    McpServerRpc.Codes.SERVER_DENIED,
                    "租约未命中（${verdict.missReason}）：外部调用只许 R0 只读免卡类；其余先由用户在 App 内授权",
                )
            }
            is McpCallLease.Verdict.Hit -> verdict.leaseId
            is McpCallLease.Verdict.FreePass -> "free"
        }

        // ── 执行件缝（548）：缺省 UNAVAILABLE fail closed，如实回"未接线"。
        val outcome = try {
            runner.run(spec, args, opId)
        } catch (e: Exception) {
            McpToolRunner.Outcome("执行件异常（${e.javaClass.simpleName}）", isError = true)
        }
        // 租约命中过的把"用一次"落账（免卡类没有 pending，静默）。
        McpCallLease.noteUsed(opId, spec.name, if (outcome.isError) "failed" else "ok")
        McpAuditLedger.append(
            McpAuditLedger.EV_CALL,
            mapOf("tool" to name, "outcome" to if (outcome.isError) "error" else "ok", "lease" to leaseField),
        )
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", outcome.text))
        val result = JSONObject()
            .put("content", content)
            .put("isError", outcome.isError)
        return McpServerTransport.Response(200, McpServerRpc.result(rpc.id, result))
    }

    // ── 杂项 ──────────────────────────────────────────────────────────────

    private fun onMethod(rpc: McpServerRpc.RpcRequest, ok: Boolean): McpServerTransport.Response {
        McpAuditLedger.append(McpAuditLedger.EV_METHOD, mapOf("method" to rpc.method, "outcome" to if (ok) "ok" else "unknown"))
        return if (ok) {
            McpServerTransport.Response(200, McpServerRpc.result(rpc.id, JSONObject()))
        } else {
            McpServerTransport.Response(
                200,
                McpServerRpc.error(rpc.id, McpServerRpc.Codes.METHOD_NOT_FOUND, "没有这个方法：${rpc.method}"),
            )
        }
    }

    private fun rpcError(rpc: McpServerRpc.RpcRequest, code: Int, message: String): McpServerTransport.Response =
        McpServerTransport.Response(200, McpServerRpc.error(rpc.id, code, message))

    /** `Authorization: Bearer <token>` → token；没有/形状不对 ⇒ null。 */
    private fun bearerOf(header: String?): String? {
        val h = header?.trim() ?: return null
        if (!h.startsWith("Bearer ", ignoreCase = true)) return null
        return h.substring(7).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        const val MCP_PATH = "/mcp"
        const val SERVER_NAME = "zhengdao-mcp"
        const val SERVER_TITLE = "证道·抱朴（本机 MCP 服务端）"

        /** 错误小体（非 JSON-RPC 层的 HTTP 语义错误用）。 */
        private fun errJson(code: String, message: String): String =
            JSONObject().put("error", code).put("message", message).toString()
    }
}
