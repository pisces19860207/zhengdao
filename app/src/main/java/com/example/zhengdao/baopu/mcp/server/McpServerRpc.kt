// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十四单 · MCP 服务端侧的 JSON-RPC 2.0 线形（纯 JVM，无网络/Android）。
//
// 与客户端侧 McpJsonRpc 的分工：那边是「客户端怎么发、怎么读服务器的回」；
// 这边是「服务器怎么收、怎么回客户端」——同一个协议版本常量（McpJsonRpc.PROTOCOL）
// 两边共用，握手版本不可能各说各话。批量（数组）v1 不做：一次一个请求，回 -32600。
package com.example.zhengdao.baopu.mcp.server

import com.example.zhengdao.baopu.mcp.McpJsonRpc
import org.json.JSONObject

internal object McpServerRpc {

    /** 一条收到的请求（通知 = 没有 id；params 可缺）。 */
    data class RpcRequest(
        val id: Any?,
        val method: String,
        val params: JSONObject?,
    ) {
        val isNotification: Boolean get() = id == null
    }

    /** 错误码（JSON-RPC 2.0 标准区间；服务端自定义业务码走 -32000 起的 Server error 区）。 */
    object Codes {
        const val PARSE = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL = -32603

        /** 服务端业务拒绝（白名单/租约）——JSON-RPC 保留区间之外的应用码。 */
        const val SERVER_DENIED = -32000
    }

    /**
     * 解析一条请求；**非对象（数组/标量）或没有 method ⇒ null**（调用方回 -32600）。
     * 解析失败不抛——服务端对垃圾输入的第一反应永远是"礼貌地拒"，不是崩。
     */
    fun parse(raw: String): RpcRequest? {
        val node = try {
            org.json.JSONTokener(raw.trim()).nextValue()
        } catch (e: Exception) {
            return null
        }
        if (node !is JSONObject) return null
        val method = node.optString("method").takeIf { it.isNotBlank() } ?: return null
        val id = if (node.has("id") && !node.isNull("id")) node.opt("id") else null
        val params = node.optJSONObject("params")
        return RpcRequest(id = normalizeId(id), method = method, params = params)
    }

    /** id 只认数字/字符串（JSON-RPC 2.0 的合法集）；别的形状按 null（通知）处理。 */
    private fun normalizeId(raw: Any?): Any? = when (raw) {
        is Number, is String -> raw
        else -> null
    }

    /** 成功响应（id 原样带回；通知不该走到这里）。 */
    fun result(id: Any?, result: JSONObject): String = JSONObject()
        .put("jsonrpc", McpJsonRpc.VERSION)
        .put("id", idOrEmpty(id))
        .put("result", result)
        .toString()

    /** 错误响应（message 进账前已在调用方清洗：不回显请求参数）。 */
    fun error(id: Any?, code: Int, message: String): String = JSONObject()
        .put("jsonrpc", McpJsonRpc.VERSION)
        .put("id", idOrEmpty(id))
        .put(
            "error",
            JSONObject()
                .put("code", code)
                .put("message", message),
        )
        .toString()

    /** id 缺失（解析半路失败）时回 null id——JSON-RPC 允许 error 响应带 null id。 */
    private fun idOrEmpty(id: Any?): Any = id ?: JSONObject.NULL
}
