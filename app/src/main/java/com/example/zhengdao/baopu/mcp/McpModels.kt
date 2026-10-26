// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百二十三批 · MCP 工具接入 v1：配置、URL 许可、错误形状、授权表。
// 纯 JVM（无 Android/OkHttp），单测直接加载。
package com.example.zhengdao.baopu.mcp

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/**
 * 一个外部 MCP server 的配置（用户手动登记；默认**不连接**）。
 *
 * 三条安全口径：
 * - URL 只允许 https，或环回 http（与 `Provider.isPermittedBaseUrl`、Android 明文白名单同口径）；
 * - 请求头只存在内存，**不落盘、不进日志、不进错误原文**；
 * - [toString] 对 URL query/fragment 与头部值打码——把它塞进任何日志都带不出 key。
 *
 * 第二百一十六批：**鉴权只剩 OAuth 一条路**（用户 2026-10-25「不要旧版本了」）——
 * `Authorization`/`Cookie`/`Proxy-Authorization` 一律拒收（见 [AUTH_HEADERS]），
 * 配置里再没有"贴个 token"的入口；[clientId] 只用于**预注册**的公共客户端（非机密）。
 *
 * 为什么不带 `enabled`：v1 的「启用」是一次显式动作（[McpToolSource.connect]），
 * 不是一个随配置自动生效的布尔。配置里放 `enabled=true` 会让「重建对象」变成连接路径。
 */
internal data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** `tools/call` 的 HTTP 总超时（同时决定 ToolSpec 超时 = 它 + 5s）。 */
    val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    /** 连接/initialize 的 HTTP 总超时。 */
    val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    /**
     * 预注册的 client_id（授权服务器不支持动态注册时由用户填）。
     *
     * **不是密钥**：公共客户端的 client_id 会出现在授权页地址栏里，落盘无害；
     * 真正装不住的 client_secret 本仓**没有也不收**（负例⑨：硬编码密钥进 APK）。
     */
    val clientId: String? = null,
) {
    init {
        require(ID.matches(id)) { "MCP server id 只能是小写字母/数字/下划线/连字符（1–32 位）：$id" }
        require(name.isNotBlank() && name.length <= 40) { "MCP server 名称要 1–40 个字符" }
        require(name.none { it.code < 0x20 || it.code == 0x7f }) { "MCP server 名称不能含控制字符" }
        McpUrlRules.check(url)?.let { throw IllegalArgumentException("MCP server URL 不许可：$it") }
        require(callTimeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            "MCP 调用超时要 ${MIN_TIMEOUT_MS}–${MAX_TIMEOUT_MS} ms"
        }
        require(connectTimeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            "MCP 连接超时要 ${MIN_TIMEOUT_MS}–${MAX_TIMEOUT_MS} ms"
        }
        require(headers.size <= MAX_HEADERS) { "MCP 请求头最多 $MAX_HEADERS 个" }
        clientId?.let {
            require(it.length <= MAX_CLIENT_ID && it.none { c -> c.code < 0x20 || c.code == 0x7f }) {
                "MCP client_id 不合法"
            }
        }
        headers.forEach { (k, v) ->
            require(McpUrlRules.isHeaderName(k)) { "MCP 请求头名称不合法：$k" }
            // HTTP 头名大小写不敏感：Mcp-Session-Id 与 mcp-session-id 是同一个头。
            require(k.lowercase() !in RESERVED_HEADERS) { "MCP 请求头「$k」由客户端管理，不许手填" }
            // 216：鉴权只能走 OAuth。静态 Authorization 入口**从形状上**删掉——
            // 不是"UI 不显示"，而是构造配置时就抛（负例⑧）。
            require(k.lowercase() !in AUTH_HEADERS) {
                "MCP 请求头「$k」属于鉴权头：本客户端只用 OAuth 授权，不接受手工填 token"
            }
            require(v.none { it == '\r' || it == '\n' || it.code == 0 }) { "MCP 请求头「$k」的值含换行/控制字符" }
            require(v.length <= MAX_HEADER_VALUE) { "MCP 请求头「$k」的值过长" }
        }
    }

    /** 日志/报错只能用它：URL 去掉 query/fragment，头部只露个数。 */
    override fun toString(): String =
        "McpServerConfig(id=$id, name=$name, url=${McpUrlRules.redact(url)}, " +
            "headers=${headers.size} 个（值不显示）, callTimeoutMs=$callTimeoutMs)"

    companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 30_000L
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
        const val MIN_TIMEOUT_MS = 500L
        const val MAX_TIMEOUT_MS = 120_000L
        const val MAX_HEADERS = 16
        const val MAX_HEADER_VALUE = 2_000
        val ID = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

        /** 客户端自己设置的头，用户再填会打架（尤其 session/版本协议头）。 */
        val RESERVED_HEADERS = setOf(
            "host", "content-length", "transfer-encoding", "connection",
            "accept", "content-type", "mcp-session-id", "mcp-protocol-version",
        )

        /**
         * 鉴权头黑名单（216 起）。
         *
         * `Cookie` 一起拒：它是另一套"贴凭据"的老路，绕开 OAuth 就等于绕开受众绑定。
         * 允许的只有**非鉴权**自定义头（如 `X-Client`）。
         */
        val AUTH_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "www-authenticate")

        const val MAX_CLIENT_ID = 512
    }
}

/**
 * URL 许可与脱敏（纯函数）。
 *
 * 为什么不用 `startsWith("http://127.0.0.1")`：那会把 `127.0.0.1.evil.com` 也放进来。
 * 这里用 [URI] 真解析，逐项判 scheme/host/userInfo/fragment，再判环回白名单。
 */
internal object McpUrlRules {

    private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

    /** @return null = 许可；非 null = 人话原因（给配置构造器抛）。 */
    fun check(raw: String): String? {
        if (raw.isBlank()) return "URL 不能为空"
        if (raw.any { it.code < 0x20 || it.code == 0x7f }) return "URL 不能含控制字符"
        val uri = try {
            URI(raw.trim())
        } catch (e: Exception) {
            return "URL 解析失败"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "只允许 http(s)，实到「${uri.scheme ?: "无"}」"
        if (uri.userInfo != null) return "URL 不许带用户名/密码（认证走 OAuth）"
        if (uri.fragment != null) return "URL 不许带 #fragment"
        // 216（派工单 §2.5）：**地址里塞 token 一律拒**。
        // 为什么卡这个：URL 会进日志、进截图、进剪贴板历史、被别的 App 读到；把凭据放 query
        // 等于把 token 从加密存储里搬到了最容易被看见的地方。
        secretQueryKey(uri.query)?.let { return "URL 不许在 query 里带凭据（$it）：认证走 OAuth" }
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return "URL 缺主机名"
        if (scheme == "http" && host !in LOOPBACK) {
            return "明文 http 只允许环回（127.0.0.1/localhost）；外部主机必须 https"
        }
        if (uri.port !in -1..65535) return "端口不合法"
        return null
    }

    fun isPermitted(raw: String): Boolean = check(raw) == null

    /** 日志用：只留 scheme://host[:port]/path，丢掉 userInfo/query/fragment。 */
    fun redact(raw: String): String = try {
        val u = URI(raw)
        val host = u.host ?: "?"
        val port = if (u.port == -1) "" else ":${u.port}"
        "${u.scheme ?: "?"}://$host$port${u.path ?: ""}"
    } catch (e: Exception) {
        "<无法解析的 URL>"
    }

    /** 请求头名称按 RFC 7230 token 判（不接受空格/冒号/换行）。 */
    fun isHeaderName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 128 &&
            name.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" }

    /**
     * 旧式 SSE 返回的 POST endpoint：只允许**同源**绝对/相对路径。
     *
     * 为什么卡同源：不然一个坏 server 可以让客户端把请求（含认证头）打到别的主机，
     * 等于把用户填的 key 送给第三方（SSRF/凭据外带）。
     */
    fun resolveSameOrigin(base: String, endpoint: String): String {
        val b = URI(base)
        val r = try {
            b.resolve(endpoint.trim())
        } catch (e: Exception) {
            throw McpException(McpFailure.PROTOCOL, "MCP 服务器返回的 endpoint 不是合法 URL")
        }
        val okScheme = r.scheme?.lowercase() == b.scheme?.lowercase()
        val okHost = r.host?.lowercase() == b.host?.lowercase()
        val okPort = effectivePort(r) == effectivePort(b)
        if (!okScheme || !okHost || !okPort || r.userInfo != null || r.fragment != null) {
            throw McpException(McpFailure.PROTOCOL, "MCP 服务器返回的 endpoint 不是同源地址，已拒绝")
        }
        return r.toString()
    }

    /**
     * query 里的**凭据类**参数名（命中即拒）。
     *
     * 口径窄而明确：只拦"一看就是密钥"的名字，不拦普通业务参数（`?tenant=a` 仍可用）。
     * 用 `contains` 兜住 `x-api-key`/`access_token`/`client_secret` 这类变体。
     */
    private fun secretQueryKey(query: String?): String? {
        if (query.isNullOrBlank()) return null
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val key = part.substringBefore('=').trim().lowercase()
            if (key.isEmpty()) continue
            val hit = key in SECRET_QUERY_KEYS ||
                key.contains("token") ||
                key.contains("secret") ||
                key.contains("api_key") ||
                key.contains("apikey") ||
                key.contains("password") ||
                key == "sig" || key == "signature" || key == "auth"
            if (hit) return key
        }
        return null
    }

    /** 一眼就是凭据的参数名（`contains` 兜不住的那些）。 */
    private val SECRET_QUERY_KEYS = setOf("key", "auth", "authorization", "sig", "signature", "pwd")

    private fun effectivePort(u: URI): Int = when {
        u.port != -1 -> u.port
        u.scheme?.lowercase() == "https" -> 443
        else -> 80
    }
}

/** 失败分类（只影响文案与是否回退，不暴露给模型）。 */
internal enum class McpFailure {
    CONFIG,
    CONNECT,
    TIMEOUT,

    /** 401/403 且**刷新也救不回来**（或压根没授权）。 */
    AUTH,

    /** 401：需要走一次授权（[McpAuthFlow] 给 URL）。 */
    AUTH_REQUIRED,

    /** 403 `insufficient_scope`：权限不够，要 step-up（不是重新登录）。 */
    INSUFFICIENT_SCOPE,
    HTTP,
    PROTOCOL,
    SCHEMA,
    TOOL,
    CLOSED,
}

/**
 * MCP 侧唯一异常形状：**userMessage 已是被清洗过的人话**。
 *
 * 纪律：构造时只传 server 名（不是 URL）、阶段、HTTP 码/异常类名；
 * 绝不把请求头、URL query、响应体原文塞进来。
 */
internal class McpException(
    val failure: McpFailure,
    val userMessage: String,
    /** 只有「端点不存在/方法不允许」才允许从 Streamable HTTP 回退旧 SSE。 */
    val fallbackEligible: Boolean = false,
    cause: Throwable? = null,
    /** HTTP 状态码（401/403 的区分靠它；不参与文案）。 */
    val httpStatus: Int? = null,
    /** `insufficient_scope` 时缺的 scope（step-up 的唯一依据）。 */
    val missingScope: String? = null,
) : Exception(userMessage, cause)

/** 从服务器列出来的一个工具（已做形状检查，尚未转成 ToolSpec）。 */
internal data class McpToolDescriptor(
    val serverId: String,
    val serverName: String,
    /** 服务器上的原始名字（`tools/call` 用它）。 */
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    /** `readOnlyHint=true` 且 `destructiveHint != true`；否则按危险处理（每次确认）。 */
    val readOnly: Boolean,
)

/** `tools/call` 的结果（只保留可回灌文本；非文本内容不塞上下文）。 */
internal data class McpCallResult(
    val text: String,
    val isError: Boolean,
)

/** 连接结果（UI 不抛异常；失败也给一句人话）。 */
internal sealed interface McpConnectOutcome {
    data class Ok(val toolCount: Int, val warnings: List<String> = emptyList()) : McpConnectOutcome

    /**
     * 需要授权：把 [authorizationUrl] 交给系统浏览器。
     *
     * 这不是失败——是 OAuth 的正常一步。UI 上给「去授权」，回来后自动重连。
     */
    data class NeedsAuth(
        val authorizationUrl: String,
        val message: String,
        /** step-up 时缺的 scope（普通首次授权为 null）。 */
        val missingScope: String? = null,
    ) : McpConnectOutcome

    data class Failed(val message: String) : McpConnectOutcome
}

/** server 状态（只读给未来设置页；v1 无 UI）。 */
internal enum class McpServerStatus { OFFLINE, CONNECTED, ERROR }

/**
 * 外部工具授权表（按 server + 工具，进程内）。
 *
 * 为什么不是全局一个「MCP 已同意」：一个 server 里的读工具与写工具风险不同；
 * 「按 server/工具显式授权」是派工单 §1 的原话。断连/移除 server 时整块清掉。
 */
internal interface McpGrantStore {
    fun isGranted(serverId: String, toolName: String): Boolean
    fun grant(serverId: String, toolName: String)
    fun revokeServer(serverId: String)

    /** 只给门禁/单测看（证明清得干净）。 */
    fun snapshot(): Map<String, Set<String>>
}

internal class InMemoryMcpGrants : McpGrantStore {
    private val byServer = HashMap<String, MutableSet<String>>()

    @Synchronized
    override fun isGranted(serverId: String, toolName: String): Boolean =
        byServer[serverId]?.contains(toolName) == true

    @Synchronized
    override fun grant(serverId: String, toolName: String) {
        byServer.getOrPut(serverId) { linkedSetOf() }.add(toolName)
    }

    @Synchronized
    override fun revokeServer(serverId: String) {
        byServer.remove(serverId)
    }

    @Synchronized
    override fun snapshot(): Map<String, Set<String>> =
        byServer.mapValues { it.value.toSet() }
}

/** 文本清洗/截断的唯一实现（描述、错误、JSON-RPC message 共用）。 */
internal object McpText {

    /** 去掉控制字符、压平换行（描述会进系统提示，不能带换行/指令段落）。 */
    fun flat(raw: String): String =
        raw.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** 人话截断（不把半个代理对切出来）。 */
    fun clip(raw: String, max: Int): String {
        if (raw.length <= max) return raw
        if (max <= 1) return "…"
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(raw[end - 1])) end--
        return raw.take(end) + "…"
    }

    /** 稳定的短哈希（撞名/超长工具名用；只取前 6 位十六进制）。 */
    fun shortHash(raw: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        // 不用 "%02x".format(Byte)：负 Byte 在 Formatter 里会按无符号还是补码随实现/版本
        // 表述不一；掩码后转十六进制是逐字节确定的。
        return bytes.take(3).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
