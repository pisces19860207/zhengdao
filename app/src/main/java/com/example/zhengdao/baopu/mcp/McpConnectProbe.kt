// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu.mcp

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 第二百四十五批 · B3：「测试连接」的失败面。
 *
 * 每一项都要能翻成一句具体的话（派工单 B3：失败不许只说「连接失败」）。
 * 这里只枚举**原因**，人话在界面层拼（`McpServerScreen`）。
 */
internal enum class McpProbeFailure { BAD_URL, DNS, TLS, TIMEOUT, REFUSED, NETWORK }

/**
 * 一次「测试连接」的结论。
 *
 * 只有状态码与布尔量——**没有**响应体、没有响应头、没有 token：
 * 探针只回答「走到没走到、服务器怎么答」这一个问题。
 */
internal data class McpProbeResult(
    /** HTTP 状态码；null ＝ 没走到 HTTP（DNS／证书／超时／拒连／地址不合法）。 */
    val statusCode: Int? = null,
    val failure: McpProbeFailure? = null,
    /** 401 时：discovery（well-known）里有没有授权服务器。 */
    val discoveryFound: Boolean = false,
) {
    val reachable: Boolean get() = statusCode != null
    val needsAuth: Boolean get() = statusCode == 401
}

/**
 * 第二百四十五批 · B3：「测试连接」探针。
 *
 * 只做两件事（派工单 B3）：
 * 1. 一次**未授权**的 `initialize`（不带 Authorization）——看服务器是不是 401；
 * 2. 401 时探一次 discovery（well-known 受保护资源元数据）——看它有没有公布授权服务器。
 *
 * 结构上就碰不到凭据与会话：本文件不引用 token 库、不引用授权流程、不建会话、
 * 不注册工具、不打印任何头或体。响应体只在 discovery 那一步按 64 KB 上限读一次
 * （那是公开元数据，不是凭据）。
 */
internal object McpConnectProbe {

    /** 单次探测的总超时（连接、读、写、整个调用共用这一档）。 */
    const val TIMEOUT_MS = 10_000L

    private const val MAX_DISCOVERY_BYTES = 64L * 1024
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * 探一次 [url]。
     *
     * @param http 生产用 [McpOAuthClient.defaultHttp]（不跟重定向、不重试）；单测注入假栈
     * @param timeoutMs 整个调用的上限
     */
    fun probe(
        url: String,
        http: OkHttpClient = McpOAuthClient.defaultHttp(),
        timeoutMs: Long = TIMEOUT_MS,
    ): McpProbeResult {
        if (McpUrlRules.check(url) != null) return McpProbeResult(failure = McpProbeFailure.BAD_URL)
        val client = http.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val code = try {
            // 只读状态码：响应体一个字都不读（连 OAuth 错误体也不读——那是别人的服务器说了算）
            client.newCall(initializeRequest(url)).execute().use { it.code }
        } catch (e: Exception) {
            return McpProbeResult(failure = failureOf(e))
        }
        if (code != 401) return McpProbeResult(statusCode = code)
        return McpProbeResult(statusCode = code, discoveryFound = hasDiscovery(client, url))
    }

    /** 与真正连接时同一个请求形状（只是**不带** Authorization）。 */
    private fun initializeRequest(url: String): Request {
        val body = JSONObject()
            .put("jsonrpc", McpJsonRpc.VERSION)
            .put("id", 1)
            .put("method", "initialize")
            .put(
                "params",
                JSONObject()
                    .put("protocolVersion", McpJsonRpc.PROTOCOL)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "zhengdao-baopu").put("version", "v1")),
            )
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", McpJsonRpc.PROTOCOL)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    /**
     * discovery：按 RFC 9728 的候选地址挨个 GET，第一个 200 且解析出
     * `authorization_servers` 的就算找到。失败一律 false（已经拿到 401，不再加戏）。
     */
    private fun hasDiscovery(client: OkHttpClient, url: String): Boolean {
        for (candidate in McpOAuthDiscovery.resourceMetadataUrls(url)) {
            val found = runCatching {
                val req = Request.Builder().url(candidate).get().build()
                client.newCall(req).execute().use { r ->
                    if (r.code != 200) return@use false
                    // 只读公开元数据，且按上限截断（peekBody 不消费流）
                    val json = JSONObject(r.peekBody(MAX_DISCOVERY_BYTES).string())
                    McpOAuthDiscovery.parseResourceMetadata(json, candidate).authorizationServers.isNotEmpty()
                }
            }.getOrDefault(false)
            if (found) return true
        }
        return false
    }

    /** 异常 → 具体原因（DNS／证书／超时／拒连／其它网络错误）。 */
    private fun failureOf(e: Exception): McpProbeFailure = when (e) {
        is UnknownHostException -> McpProbeFailure.DNS
        is SSLException -> McpProbeFailure.TLS
        is InterruptedIOException -> McpProbeFailure.TIMEOUT
        is ConnectException -> McpProbeFailure.REFUSED
        is IllegalArgumentException -> McpProbeFailure.BAD_URL
        else -> McpProbeFailure.NETWORK
    }
}
