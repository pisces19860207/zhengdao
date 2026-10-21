// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十三批 · MCP 工具接入 v1：JSON-RPC 2.0 线形（纯 JVM，无网络/Android）。
// 只做「信封」：请求/通知构造、响应分类、错误文案清洗。传输在 McpClient。
package com.example.zhengdao.baopu.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-RPC 2.0 的最小实现（MCP 的线形）。
 *
 * 三条纪律：
 * 1. **只认自己的 id**：服务器主动发来的请求/通知（sampling、roots、log…）一律不执行；
 *    传输层看到 [Incoming.ServerRequest] 只当作「不是给我的响应」跳过。
 * 2. **错误原文清洗**：[responseErrorText] 只取 code/message，`data` 一律不读——里面可能
 *    有服务器回显的请求参数（甚至 key）。
 * 3. **不做批量优化**：v1 一次一个请求（串行），批量只解析不用。
 */
internal object McpJsonRpc {

    const val VERSION = "2.0"

    /** 客户端会声明的协议版本（MCP 2025-03-26）。 */
    const val PROTOCOL = "2025-03-26"

    fun request(id: Long, method: String, params: JSONObject? = null): JSONObject {
        val o = JSONObject()
            .put("jsonrpc", VERSION)
            .put("id", id)
            .put("method", method)
        params?.let { o.put("params", it) }
        return o
    }

    fun notification(method: String, params: JSONObject? = null): JSONObject {
        val o = JSONObject()
            .put("jsonrpc", VERSION)
            .put("method", method)
        params?.let { o.put("params", it) }
        return o
    }

    /** JSON-RPC 的响应（只用于「服务器主动请求我们」的拒绝；v1 不接服务器请求）。 */
    fun errorResponse(id: Any?, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", VERSION)
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    /** 服务器回来的消息分类。 */
    sealed interface Incoming {
        /** 对我们的请求的响应（id 对得上才有意义）。 */
        data class Response(
            val id: Long,
            val result: JSONObject?,
            val error: JSONObject?,
        ) : Incoming

        /** 服务器通知（没有 id，不需要回应）。 */
        data class Notification(val method: String) : Incoming

        /** 服务器主动请求（sampling/roots 等）——v1 **不执行**。 */
        data class ServerRequest(val id: Any?, val method: String) : Incoming

        /** JSON-RPC 批量数组。 */
        data class Batch(val items: List<Incoming>) : Incoming

        /** 空行/心跳/不认识但无害的消息。 */
        data object Ignored : Incoming
    }

    /**
     * 解析一条 JSON-RPC 消息。
     *
     * @throws McpException [McpFailure.PROTOCOL] 当文本不是合法 JSON 或不是对象/数组。
     */
    fun parse(raw: String): Incoming {
        val text = raw.trim()
        if (text.isEmpty()) return Incoming.Ignored
        val node = try {
            org.json.JSONTokener(text).nextValue()
        } catch (e: Exception) {
            throw McpException(McpFailure.PROTOCOL, "MCP 服务器返回的不是合法 JSON", cause = e)
        }
        return when (node) {
            is JSONArray -> Incoming.Batch((0 until node.length()).map { parseNode(node.opt(it)) })
            is JSONObject -> parseNode(node)
            else -> Incoming.Ignored
        }
    }

    private fun parseNode(node: Any?): Incoming {
        val o = node as? JSONObject ?: return Incoming.Ignored
        val method = o.optString("method").takeIf { it.isNotBlank() }
        if (method != null) {
            val id = o.opt("id")
            return if (o.has("id") && !o.isNull("id")) {
                Incoming.ServerRequest(id, method)
            } else {
                Incoming.Notification(method)
            }
        }
        val id = idOf(o) ?: return Incoming.Ignored
        val result = o.optJSONObject("result")
        val error = o.optJSONObject("error")
        return Incoming.Response(id, result, error)
    }

    /** 数字 id 取 long；字符串 id（非本客户端所发）返回 null => 当作不认识。 */
    fun idOf(o: JSONObject): Long? {
        if (!o.has("id") || o.isNull("id")) return null
        return when (val v = o.opt("id")) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }

    /**
     * JSON-RPC error ⇒ 人话（不含 data/堆栈/URL）。
     */
    fun responseErrorText(error: JSONObject?): String {
        if (error == null) return "MCP 服务器返回了空错误"
        val code = error.optInt("code", 0)
        val msg = McpText.clip(McpText.flat(error.optString("message")), 200)
        return if (msg.isEmpty()) {
            "MCP 服务器返回错误（code=$code）"
        } else {
            "MCP 服务器返回错误（code=$code）：$msg"
        }
    }

    /**
     * `tools/call` 的 `content[]` ⇒ 可回灌文本。
     *
     * - 只取 `type=text` 的 `text`；图片/音频/资源只给一行占位（**绝不把 base64 塞进上下文**）；
     * - 总长封顶 [MAX_RESULT_CHARS]（大结果到了 Guard 还会 spill；这里先防内存暴击）；
     * - `structuredContent`（若服务器只给它）序列化成文本——它仍是**数据**，由围栏包裹。
     */
    fun toolContent(result: JSONObject?): String {
        if (result == null) return ""
        val content = result.optJSONArray("content")
        val out = StringBuilder()
        if (content != null) {
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                val type = item.optString("type").ifEmpty { "unknown" }
                when (type) {
                    "text" -> appendBlock(out, item.optString("text"))
                    "image" -> appendBlock(out, "[图片内容未展示：MCP 服务器返回了 image]")
                    "audio" -> appendBlock(out, "[音频内容未展示：MCP 服务器返回了 audio]")
                    "resource" -> {
                        val res = item.optJSONObject("resource")
                        val text = res?.optString("text")
                        if (!text.isNullOrEmpty()) {
                            appendBlock(out, text)
                        } else {
                            appendBlock(out, "[资源内容未展示：MCP 服务器返回了 resource]")
                        }
                    }
                    "resource_link" -> appendBlock(
                        out,
                        "[资源链接未展开：${McpText.flat(item.optString("name").ifEmpty { item.optString("uri") })}]",
                    )
                    else -> appendBlock(out, "[未展示的 MCP 内容：$type]")
                }
            }
        }
        if (out.isEmpty()) {
            val structured = result.optJSONObject("structuredContent")
            // 2025-03-26 的 structuredContent 是对象；数组/标量也不猜，按原样转文本。
            val anyStructured = result.opt("structuredContent")
            when {
                structured != null -> out.append(structured.toString())
                anyStructured != null && anyStructured !== JSONObject.NULL ->
                    out.append(anyStructured.toString())
            }
        }
        val text = out.toString()
        return if (text.length <= MAX_RESULT_CHARS) {
            text
        } else {
            McpText.clip(text, MAX_RESULT_CHARS) + "\n（结果过长，已截断；共 ${text.length} 字符）"
        }
    }

    private fun appendBlock(out: StringBuilder, raw: String) {
        if (raw.isEmpty()) return
        if (out.isNotEmpty()) out.append("\n\n")
        out.append(raw)
    }

    /** 单次工具结果的上限（不含 Guard 的 spill 阈值；防服务器一次灌爆内存）。 */
    const val MAX_RESULT_CHARS = 200_000
}
