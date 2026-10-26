// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十六批 · MCP OAuth 2.1：**发现链的纯函数**（解析 401 挑战、算 well-known
// 地址、校验元数据）。网络在 McpOAuthClient，这里一行 IO 都没有。
//
// 官方口径（2026-07-28 Authorization 章 / RFC 9728 / RFC 8414 / RFC 6750）：
//   401 + `WWW-Authenticate: Bearer resource_metadata="…"` ⇒ 取受保护资源元数据
//   ⇒ 从中拿 authorization_servers ⇒ 取授权服务器元数据（authorization/token/registration）。
package com.example.zhengdao.baopu.mcp

import org.json.JSONObject
import java.net.URI

/**
 * 一次 `WWW-Authenticate: Bearer …` 挑战（RFC 6750 §3 的 auth-param 子集）。
 *
 * 只取三个参数：`resource_metadata`（去哪找授权服务器）、`error`/`scope`
 * （`insufficient_scope` 时缺什么权限——step-up 的唯一线索）。
 */
internal data class McpBearerChallenge(
    val resourceMetadataUrl: String?,
    val error: String?,
    val errorDescription: String?,
    val scope: String?,
) {
    /** 403 + `insufficient_scope`：这不是"没登录"，是"权限不够"，要 step-up 而不是重新登录。 */
    val insufficientScope: Boolean
        get() = error?.equals("insufficient_scope", ignoreCase = true) == true
}

internal object McpOAuthDiscovery {

    /** `insufficient_scope` 的字面值（RFC 6750 §3.1），集中一处防拼错。 */
    const val ERR_INSUFFICIENT_SCOPE = "insufficient_scope"

    /**
     * 解析 `WWW-Authenticate`（可能有多段挑战，例如 `Basic realm="x", Bearer …`）。
     *
     * 解析纪律：
     * · 只认 `Bearer` 那一段；找不到返回 null（调用方按「该服务器不支持 OAuth」处理）；
     * · 引号内的逗号不算分隔符（`error_description="a, b"` 是合法的）；
     * · 未知参数忽略，不猜。
     */
    fun parseWwwAuthenticate(header: String?): McpBearerChallenge? {
        if (header.isNullOrBlank()) return null
        var inBearer = false
        var sawBearer = false
        val params = LinkedHashMap<String, String>()
        for (raw in splitTopLevel(header)) {
            var seg = raw.trim()
            if (seg.isEmpty()) continue
            // 段首可能带着 scheme（`Bearer resource_metadata="…"` 是一段里的两截）。
            // `key=value` 不会被 SCHEME_LEAD 匹配（scheme 后面必须跟空白或结束）。
            val lead = SCHEME_LEAD.matchEntire(seg)
            if (lead != null) {
                val scheme = lead.groupValues[1]
                inBearer = scheme.equals("Bearer", ignoreCase = true)
                if (inBearer) sawBearer = true
                seg = lead.groupValues[2].trim()
                if (seg.isEmpty()) continue
            }
            if (!inBearer) continue
            val eq = seg.indexOf('=')
            if (eq <= 0) continue
            val key = seg.substring(0, eq).trim().lowercase()
            val value = unquote(seg.substring(eq + 1).trim())
            if (key.isNotEmpty()) params[key] = value
        }
        if (!sawBearer) return null
        if (params.isEmpty()) {
            // 只有 `Bearer` 一个词（没有参数）：仍然算一次 Bearer 挑战，只是没线索。
            return McpBearerChallenge(null, null, null, null)
        }
        return McpBearerChallenge(
            resourceMetadataUrl = params["resource_metadata"]?.takeIf { it.isNotBlank() },
            error = params["error"]?.takeIf { it.isNotBlank() },
            errorDescription = params["error_description"]?.takeIf { it.isNotBlank() },
            scope = params["scope"]?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 受保护资源元数据（PRM）的候选地址，按优先级排。
     *
     * RFC 9728 §3.1：资源在 `https://host/a/b` 时，PRM 既可放在
     * `https://host/.well-known/oauth-protected-resource/a/b`（路径插入），
     * 也可放在 `https://host/.well-known/oauth-protected-resource`（根）。
     * 两个都试——服务器实现两种都常见，只试一种会「明明有却说没有」。
     */
    fun resourceMetadataUrls(serverUrl: String): List<String> {
        val base = origin(serverUrl) ?: return emptyList()
        val path = pathOf(serverUrl)
        val out = LinkedHashSet<String>()
        if (path.isNotEmpty()) out += "$base/.well-known/oauth-protected-resource$path"
        out += "$base/.well-known/oauth-protected-resource"
        return out.toList()
    }

    /**
     * 授权服务器元数据的候选地址（RFC 8414 §3 + OIDC Discovery 1.0 §4）。
     *
     * issuer 带路径时，RFC 8414 是**路径插入**（`/.well-known/oauth-authorization-server/<path>`），
     * OIDC 是**路径追加**（`<path>/.well-known/openid-configuration`）——两种都试，
     * 因为真实服务器两种都有，而"按 RFC 只试一种"会把能用的服务器判成不支持。
     */
    fun authServerMetadataUrls(issuer: String): List<String> {
        val base = origin(issuer) ?: return emptyList()
        val path = pathOf(issuer)
        val out = LinkedHashSet<String>()
        if (path.isNotEmpty()) {
            out += "$base/.well-known/oauth-authorization-server$path"
        } else {
            out += "$base/.well-known/oauth-authorization-server"
        }
        out += if (path.isNotEmpty()) {
            "$base$path/.well-known/openid-configuration"
        } else {
            "$base/.well-known/openid-configuration"
        }
        return out.toList()
    }

    /**
     * 解析 PRM。
     *
     * `authorization_servers` 必须非空——没有它就没有下一步。每个 AS 地址都要过
     * [McpUrlRules.check]（https 或环回 http）：明文 http 的授权服务器等于把授权码
     * 与 token 放在路上，直接判为不可接受（[McpAuthFailure.BAD_METADATA]）。
     */
    fun parseResourceMetadata(json: JSONObject, sourceUrl: String): McpResourceMetadata {
        val servers = ArrayList<String>()
        json.optJSONArray("authorization_servers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val raw = arr.optString(i).trim()
                if (raw.isEmpty()) continue
                val bad = McpUrlRules.check(raw)
                if (bad != null) {
                    throw McpAuthException(
                        McpAuthFailure.BAD_METADATA,
                        "授权服务器地址不可接受（$bad）——不会把授权码发到明文或非 https 的地址",
                    )
                }
                servers += raw
            }
        }
        if (servers.isEmpty()) {
            throw McpAuthException(
                McpAuthFailure.NO_METADATA,
                "受保护资源元数据里没有 authorization_servers（$sourceUrl）",
            )
        }
        return McpResourceMetadata(
            resource = json.optString("resource").takeIf { it.isNotBlank() },
            authorizationServers = servers,
            scopesSupported = stringList(json, "scopes_supported"),
        )
    }

    /**
     * 解析授权服务器元数据。
     *
     * 三道闸：
     * ① 三个端点必须存在且过 URL 许可（明文 http 非环回 ⇒ 拒）；
     * ② `code_challenge_methods_supported` 若明确声明了却不含 S256 ⇒ 拒（负例③）；
     * ③ `issuer` 若给了，必须与取元数据的那个源**同主机**（防"元数据在 A、issuer 说 B"）。
     */
    fun parseAuthServerMetadata(json: JSONObject, sourceUrl: String): McpAuthServerMetadata {
        val authorizationEndpoint = endpoint(json, "authorization_endpoint", sourceUrl)
        val tokenEndpoint = endpoint(json, "token_endpoint", sourceUrl)
        val registrationEndpoint = json.optString("registration_endpoint")
            .takeIf { it.isNotBlank() }
            ?.also { raw ->
                McpUrlRules.check(raw)?.let {
                    throw McpAuthException(
                        McpAuthFailure.BAD_METADATA,
                        "注册端点地址不可接受（$it）",
                    )
                }
            }
        val issuer = json.optString("issuer").takeIf { it.isNotBlank() }
        if (issuer != null) {
            val issuerHost = hostOf(issuer)
            val sourceHost = hostOf(sourceUrl)
            if (issuerHost == null || sourceHost == null || issuerHost != sourceHost) {
                throw McpAuthException(
                    McpAuthFailure.BAD_METADATA,
                    "授权服务器元数据的 issuer 与来源主机不一致（${issuerHost ?: "?"} ≠ ${sourceHost ?: "?"}）",
                )
            }
        }
        val methods = stringListOrNull(json, "code_challenge_methods_supported")
        if (methods != null && McpPkce.METHOD !in methods.map { it.uppercase() }) {
            throw McpAuthException(
                McpAuthFailure.BAD_METADATA,
                "授权服务器只支持 ${methods.joinToString("、")}，不支持 PKCE S256——本客户端拒绝降级到 plain",
            )
        }
        return McpAuthServerMetadata(
            issuer = issuer,
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            registrationEndpoint = registrationEndpoint,
            scopesSupported = stringList(json, "scopes_supported"),
            codeChallengeMethods = methods,
            tokenAuthMethods = stringListOrNull(json, "token_endpoint_auth_methods_supported"),
            grantTypes = stringListOrNull(json, "grant_types_supported"),
        )
    }

    /**
     * 拼授权页地址（授权码 + PKCE + state + **resource**）。
     *
     * 用 `URLEncoder`（form 编码）而不是手拼：`scope` 里常带空格、`resource` 里有
     * `://`，手拼一定会漏编码，服务器就会把它当成另一个参数。
     */
    fun authorizationUrl(
        metadata: McpAuthServerMetadata,
        clientId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
        resource: String,
        scope: String?,
    ): String {
        val params = LinkedHashMap<String, String>()
        params["response_type"] = "code"
        params["client_id"] = clientId
        params["redirect_uri"] = redirectUri
        params["state"] = state
        params["code_challenge"] = codeChallenge
        params["code_challenge_method"] = McpPkce.METHOD
        // RFC 8707：受众绑定。少了它，token 可能被别的资源拿去用（负例④）。
        params["resource"] = resource
        scope?.takeIf { it.isNotBlank() }?.let { params["scope"] = it }
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${formEncode(v)}" }
        val sep = if (metadata.authorizationEndpoint.contains('?')) '&' else '?'
        return metadata.authorizationEndpoint + sep + query
    }

    /** form 编码（OAuth 用 `application/x-www-form-urlencoded`，空格必须是 `+`）。 */
    fun formEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    // ── 内部 ─────────────────────────────────────────────────────────────

    /** 段首的 scheme：`Bearer` / `Basic` 这类裸 token（后面跟空白或直接结束）。 */
    private val SCHEME_LEAD = Regex("^([A-Za-z][A-Za-z0-9_-]*)(?:\\s+(.*))?$", RegexOption.DOT_MATCHES_ALL)

    /** 顶层逗号切分（引号内的逗号不切）。 */
    private fun splitTopLevel(header: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var escaped = false
        for (c in header) {
            when {
                escaped -> {
                    sb.append(c)
                    escaped = false
                }
                c == '\\' && quoted -> {
                    sb.append(c)
                    escaped = true
                }
                c == '"' -> {
                    sb.append(c)
                    quoted = !quoted
                }
                c == ',' && !quoted -> {
                    out += sb.toString()
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    private fun unquote(raw: String): String {
        val t = raw.trim()
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
            return t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return t
    }

    private fun endpoint(json: JSONObject, key: String, sourceUrl: String): String {
        val raw = json.optString(key).trim()
        if (raw.isEmpty()) {
            throw McpAuthException(
                McpAuthFailure.BAD_METADATA,
                "授权服务器元数据缺 $key（$sourceUrl）",
            )
        }
        McpUrlRules.check(raw)?.let {
            throw McpAuthException(McpAuthFailure.BAD_METADATA, "$key 地址不可接受（$it）")
        }
        return raw
    }

    private fun stringList(json: JSONObject, key: String): List<String> =
        stringListOrNull(json, key) ?: emptyList()

    private fun stringListOrNull(json: JSONObject, key: String): List<String>? {
        val arr = json.optJSONArray(key) ?: return null
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    /** `scheme://host[:port]`（不含路径）。 */
    private fun origin(url: String): String? = try {
        val u = URI(url.trim())
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val hostPart = if (host.contains(':')) "[$host]" else host
        val port = if (u.port == -1) "" else ":${u.port}"
        "$scheme://$hostPart$port"
    } catch (e: Exception) {
        null
    }

    /** 路径（去掉尾部 `/`；根路径返回空串）。 */
    private fun pathOf(url: String): String = try {
        (URI(url.trim()).path ?: "").trimEnd('/')
    } catch (e: Exception) {
        ""
    }

    private fun hostOf(url: String): String? = try {
        URI(url.trim()).host?.lowercase()?.removeSurrounding("[", "]")
    } catch (e: Exception) {
        null
    }
}
