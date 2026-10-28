// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十六批 · MCP OAuth 2.1：**授权服务器的四个 HTTP 动作**
// （发现 / 动态注册 / 换 token / 刷新 token）。
//
// 红线（本文件是"接触 token 的地方"，纪律比别处更硬）：
// 1. **token 原文不落地**：只进 [McpTokenSet] 交给 [McpTokenVault]；本文件的任何日志/异常
//    文案都不含 access_token / refresh_token / code / code_verifier；
// 2. **不跟重定向**：授权服务器是外部主机，跟随 3xx 会把表单（含 code/verifier）送去别处；
// 3. **不引新库**：只用仓里已有的 OkHttp + org.json；
// 4. **错误只取 error/error_description**（RFC 6749 §5.2），响应体其余部分一律不读进文案
//    ——有些实现会在错误体里回显请求参数，那就是把 token 抄进日志的后门。
package com.example.zhengdao.baopu.mcp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** 发现结果：受保护资源元数据（可能来自 401 挑战或 well-known）+ 授权服务器元数据。 */
internal data class McpDiscovery(
    val resourceMetadata: McpResourceMetadata?,
    val authServer: McpAuthServerMetadata,
    /** 取 token 时要带的 `resource`（规范化 URI）。 */
    val resource: String,
)

/**
 * 授权服务器的 HTTP 客户端（一次只做一件事，不做后台重试）。
 *
 * @param http 生产传入 `followRedirects=false` 的实例；单测传 MockWebServer 的普通实例。
 */
internal class McpOAuthClient(
    private val http: OkHttpClient,
    private val log: (String) -> Unit = {},
) {

    /**
     * 走完发现链。
     *
     * 顺序（与派工单 §2.1 一致）：
     * ① 401 挑战里的 `resource_metadata`（有就用它，最准）；
     * ② 服务器 origin 上的 `/.well-known/oauth-protected-resource`（含路径插入变体）；
     * ③ PRM 全失败 ⇒ 回落「AS 元数据直接放在 MCP 服务器上」（旧实现常见）。
     *
     * @throws McpAuthException NO_METADATA / BAD_METADATA
     */
    fun discover(config: McpServerConfig, challenge: McpBearerChallenge? = null): McpDiscovery {
        val resource = McpResource.canonical(config.url)
            ?: throw McpAuthException(McpAuthFailure.BAD_METADATA, "MCP 服务器地址无法规范化成 resource")

        val prmUrls = LinkedHashSet<String>()
        challenge?.resourceMetadataUrl?.let { raw ->
            // 挑战里的地址同样是外部输入：过同一套 URL 许可（https / 环回），否则拒。
            val bad = McpUrlRules.check(raw)
            if (bad != null) {
                throw McpAuthException(McpAuthFailure.BAD_METADATA, "401 挑战里的 resource_metadata 地址不可接受（$bad）")
            }
            prmUrls += raw
        }
        prmUrls += McpOAuthDiscovery.resourceMetadataUrls(config.url)

        var prm: McpResourceMetadata? = null
        var lastError: String? = null
        for (url in prmUrls) {
            try {
                val json = getJson(url, "受保护资源元数据")
                prm = McpOAuthDiscovery.parseResourceMetadata(json, McpUrlRules.redact(url))
                break
            } catch (e: McpAuthException) {
                lastError = e.userMessage
                if (e.failure == McpAuthFailure.BAD_METADATA) throw e
            }
        }

        if (prm != null) {
            var asError: String? = null
            for (issuer in prm.authorizationServers) {
                for (url in McpOAuthDiscovery.authServerMetadataUrls(issuer)) {
                    try {
                        val json = getJson(url, "授权服务器元数据")
                        val meta = McpOAuthDiscovery.parseAuthServerMetadata(json, url)
                        return McpDiscovery(prm, meta, resource)
                    } catch (e: McpAuthException) {
                        asError = e.userMessage
                        if (e.failure == McpAuthFailure.BAD_METADATA) throw e
                    }
                }
            }
            throw McpAuthException(
                McpAuthFailure.NO_METADATA,
                "找到了受保护资源元数据，但授权服务器元数据取不到" +
                    (asError?.let { "（$it）" } ?: ""),
            )
        }

        // ③ 回落：AS 元数据直接放在 MCP 服务器 origin 上（没有 PRM 的老实现）。
        var fallbackError: String? = null
        for (url in McpOAuthDiscovery.authServerMetadataUrls(config.url)) {
            try {
                val json = getJson(url, "授权服务器元数据")
                val meta = McpOAuthDiscovery.parseAuthServerMetadata(json, url)
                log("[mcp] ${config.id} 没有 PRM，按 MCP origin 上的 AS 元数据接入")
                return McpDiscovery(null, meta, resource)
            } catch (e: McpAuthException) {
                fallbackError = e.userMessage
                if (e.failure == McpAuthFailure.BAD_METADATA) throw e
            }
        }
        throw McpAuthException(
            McpAuthFailure.NO_METADATA,
            "该服务器不支持现代授权（OAuth 发现链拉不到）：${lastError ?: fallbackError ?: "没有授权服务器元数据"}",
        )
    }

    /**
     * 探一次未授权的请求，把 401 的 `WWW-Authenticate` 拿回来。
     *
     * 只在「well-known 直接拉不到」时用：有些服务器只把 resource_metadata 写在挑战里。
     * 失败（连不上/不是 401）返回 null，由调用方给出「不支持 OAuth」的明确结论。
     */
    fun probeChallenge(config: McpServerConfig): McpBearerChallenge? {
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
        val req = Request.Builder()
            .url(config.url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", McpJsonRpc.PROTOCOL)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        return try {
            execute(req, config.connectTimeoutMs).use { r ->
                if (r.code != 401) return null
                McpOAuthDiscovery.parseWwwAuthenticate(r.header("WWW-Authenticate"))
            }
        } catch (e: Exception) {
            log("[mcp] ${config.id} 探测 401 挑战失败（${e.javaClass.simpleName}）")
            null
        }
    }

    /**
     * 动态客户端注册（RFC 7591）。
     *
     * 手机端必须是**公共客户端**：`token_endpoint_auth_method=none`，不要 secret
     * （装不住，硬编码进 APK 等于公开）。没有 `registration_endpoint` 时抛
     * [McpAuthFailure.REGISTRATION_FAILED]，由调用方要求用户填预注册 client_id。
     */
    fun register(
        metadata: McpAuthServerMetadata,
        redirectUri: String,
        clientName: String = "证道抱朴",
    ): McpClientRegistration {
        val endpoint = metadata.registrationEndpoint
            ?: throw McpAuthException(
                McpAuthFailure.REGISTRATION_FAILED,
                "该授权服务器不支持动态注册：请在服务器设置里填预注册的 client_id",
            )
        val body = JSONObject()
            .put("client_name", clientName)
            .put("redirect_uris", org.json.JSONArray().put(redirectUri))
            .put("grant_types", org.json.JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", org.json.JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
            .put("application_type", "native")
            .put("code_challenge_method", McpPkce.METHOD)
        val req = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        val json = try {
            executeJson(req, "客户端注册", McpAuthFailure.REGISTRATION_FAILED)
        } catch (e: McpAuthException) {
            throw McpAuthException(McpAuthFailure.REGISTRATION_FAILED, e.userMessage, e)
        }
        val clientId = json.optString("client_id").trim()
        if (clientId.isEmpty() || clientId.length > MAX_CLIENT_ID) {
            throw McpAuthException(
                McpAuthFailure.REGISTRATION_FAILED,
                "授权服务器注册成功但没给可用的 client_id",
            )
        }
        val uris = ArrayList<String>()
        json.optJSONArray("redirect_uris")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { uris += it }
        }
        return McpClientRegistration(
            clientId = clientId,
            redirectUris = uris,
            tokenAuthMethod = json.optString("token_endpoint_auth_method").ifBlank { "none" },
        )
    }

    /** 授权码换 token（`grant_type=authorization_code`，带 `code_verifier` 与 `resource`）。 */
    fun exchangeCode(
        metadata: McpAuthServerMetadata,
        registration: McpClientRegistration,
        code: String,
        verifier: String,
        redirectUri: String,
        resource: String,
        nowMs: Long,
    ): McpTokenSet {
        if (!McpPkce.isWellFormed(verifier)) {
            // 本机先拒：形状不对的 verifier 发出去只会换回 400，还会把这次授权作废。
            throw McpAuthException(McpAuthFailure.TOKEN_EXCHANGE_FAILED, "PKCE verifier 形状不合法，已中止授权")
        }
        val form = LinkedHashMap<String, String>()
        form["grant_type"] = "authorization_code"
        form["code"] = code
        form["redirect_uri"] = redirectUri
        form["client_id"] = registration.clientId
        form["code_verifier"] = verifier
        form["resource"] = resource
        return postToken(metadata, form, resource, nowMs, McpAuthFailure.TOKEN_EXCHANGE_FAILED, "换 token")
    }

    /** 刷新（`grant_type=refresh_token`，**同样带 resource**）。 */
    fun refresh(
        metadata: McpAuthServerMetadata,
        registration: McpClientRegistration,
        refreshToken: String,
        resource: String,
        nowMs: Long,
    ): McpTokenSet {
        val form = LinkedHashMap<String, String>()
        form["grant_type"] = "refresh_token"
        form["refresh_token"] = refreshToken
        form["client_id"] = registration.clientId
        form["resource"] = resource
        return postToken(metadata, form, resource, nowMs, McpAuthFailure.REFRESH_FAILED, "刷新 token")
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /**
     * token 端点 POST（form 编码）。
     *
     * 刷新时服务器**可能不给新的 refresh_token**（RFC 6749 §6 允许不返回）——这时沿用
     * 旧的（不把用户的授权弄丢）。返回的 resource 以请求时带的为准，不信服务器回声。
     */
    private fun postToken(
        metadata: McpAuthServerMetadata,
        form: Map<String, String>,
        resource: String,
        nowMs: Long,
        failure: McpAuthFailure,
        stage: String,
    ): McpTokenSet {
        val body = form.entries.joinToString("&") { (k, v) -> "$k=${McpOAuthDiscovery.formEncode(v)}" }
        val req = Request.Builder()
            .url(metadata.tokenEndpoint)
            .header("Accept", "application/json")
            .post(body.toRequestBody(FORM_MEDIA))
            .build()
        val json = try {
            executeJson(req, stage, failure)
        } catch (e: McpAuthException) {
            throw McpAuthException(failure, e.userMessage, e)
        }
        val access = json.optString("access_token").trim()
        if (access.isEmpty()) {
            throw McpAuthException(failure, "$stage 失败：授权服务器没有返回 access_token")
        }
        val expiresIn = json.optLong("expires_in", -1L)
        return McpTokenSet(
            accessToken = access,
            refreshToken = json.optString("refresh_token").trim().takeIf { it.isNotEmpty() }
                ?: form["refresh_token"],
            tokenType = json.optString("token_type").ifBlank { "Bearer" },
            scope = json.optString("scope").trim().takeIf { it.isNotEmpty() },
            expiresAtMs = if (expiresIn > 0) nowMs + expiresIn * 1000L else null,
            resource = resource,
        )
    }

    private fun getJson(url: String, stage: String): JSONObject {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        return executeJson(req, stage, McpAuthFailure.NO_METADATA)
    }

    /**
     * 发请求并把响应体读成 JSON。
     *
     * 失败时**只**取 OAuth 的 `error`/`error_description`（或 HTTP 码），绝不把响应体原文
     * 拼进异常——那是最常见的"token 泄漏进日志"路径（服务器回显请求参数）。
     */
    private fun executeJson(req: Request, stage: String, failure: McpAuthFailure): JSONObject {
        execute(req, DISCOVERY_TIMEOUT_MS).use { r ->
            val (body, readFail) = readCapped(r, stage)
            val text = body.orEmpty()
            // 没有体／超限／读不出三类故障先记线索（带 HTTP 码、字节数、指纹），再走下面的分支。
            readFail?.let { log(it.logLine()) }
            if (!r.isSuccessful) {
                val (code, desc) = oauthError(text)
                val detail = buildString {
                    if (code != null) append(code)
                    if (!desc.isNullOrBlank()) {
                        if (isNotEmpty()) append("：")
                        append(McpText.clip(McpText.flat(desc), 120))
                    }
                }
                log(
                    McpFailureNote(
                        stage = stage,
                        why = if (detail.isEmpty()) "服务器没给可用的错误说明" else "服务器给了错误说明",
                        httpStatus = r.code,
                        bytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
                        digest8 = if (text.isEmpty()) "" else McpHttpNotes.digest8(text),
                        // 只记白名单内的 §5.2 枚举值；其余值可能是回显的用户输入。
                        oauthError = McpHttpNotes.oauthErrorCode(code),
                    ).logLine(),
                )
                throw McpAuthException(
                    failure,
                    "$stage 失败（HTTP ${r.code}）" + if (detail.isEmpty()) "" else "：$detail",
                )
            }
            if (readFail != null) {
                // 改前：读不出 ⇒ text 为空 ⇒ 报「返回了空响应」——把「坏了」说成「没有」。
                throw McpAuthException(failure, "$stage 失败：${readFail.why}（HTTP ${r.code}）")
            }
            if (text.isBlank()) {
                throw McpAuthException(failure, "$stage 返回了空响应")
            }
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw McpAuthException(failure, "$stage 返回的不是合法 JSON")
            }
        }
    }

    private fun execute(req: Request, timeoutMs: Long): Response {
        val call = http.newCall(req)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            call.execute()
        } catch (e: InterruptedIOException) {
            log(McpFailureNote("授权服务器连接", "连接环节超时（${timeoutMs / 1000}s）").logLine())
            throw McpAuthException(McpAuthFailure.NO_METADATA, "授权服务器连接超时（${timeoutMs / 1000}s）", e)
        } catch (e: Exception) {
            // 环节只分类到「连接／证书-TLS」；不带 URL（授权地址上挂着 code/verifier）。
            val kind = if (e is SSLException) "证书/TLS 握手" else "连接"
            log(McpFailureNote("授权服务器$kind", "失败（${e.javaClass.simpleName}）").logLine())
            throw McpAuthException(
                McpAuthFailure.NO_METADATA,
                "授权服务器${kind}失败（${e.javaClass.simpleName}）",
                e,
            )
        }
    }

    /**
     * 读响应体（有上限）。
     *
     * **259 批 B4**：改前三条路径都 `return ""`（没有体／声明体积超限／读过程故障），
     * 调用方只能把它们统一说成「返回了空响应」——「坏了」冒充「没有」。现在返回
     * `正文 to 分类线索`（至多一个非空）：线索只有环节、字节数与指纹，没有原文
     * （授权响应体里可能就是 token）。
     */
    private fun readCapped(r: Response, stage: String): Pair<String?, McpFailureNote?> {
        val body = r.body ?: return null to McpFailureNote(stage, "响应没有体", r.code)
        val declared = body.contentLength()
        if (declared > MAX_BODY_BYTES) {
            return null to McpFailureNote(
                stage,
                "超过 ${MAX_BODY_BYTES / 1024} KB 上限，已拒读",
                r.code,
                bytes = declared.coerceAtLeast(0L),
            )
        }
        val bytes = try {
            body.byteStream().readNBytes(MAX_BODY_BYTES.toInt())
        } catch (e: Exception) {
            return null to McpFailureNote(stage, "读不出（${e.javaClass.simpleName}）", r.code)
        }
        return String(bytes, Charsets.UTF_8) to null
    }

    /** RFC 6749 §5.2 的错误体：只取这两个字段。 */
    private fun oauthError(text: String): Pair<String?, String?> {
        if (text.isBlank()) return null to null
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null to null
        }
        return json.optString("error").takeIf { it.isNotBlank() } to
            json.optString("error_description").takeIf { it.isNotBlank() }
    }

    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val MAX_BODY_BYTES = 256L * 1024
        private const val MAX_CLIENT_ID = 512

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()

        /** 生产实例：不跟重定向、不重试（授权请求的每一跳都必须是本机算出来的地址）。 */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
