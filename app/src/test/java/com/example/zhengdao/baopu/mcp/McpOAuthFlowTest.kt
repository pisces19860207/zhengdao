// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十六批 · MCP OAuth 2.1：**真网络栈的端到端单测**（MockWebServer 当假授权服务器）。
//
// 一个假 AS + 一个假 MCP server，把整条链走通：
//   401 → well-known PRM → AS 元数据 → 动态注册 → 授权页（S256/state/resource）
//   → 回调换 token → 带 Bearer 调 tools/list → 401 刷新一次 → 重放
//
// 这里钉的判据（派工单 §3 正例）：
// · token 过期 ⇒ 刷新成功后再调（且只刷新一次、只重放一次）；
// · 刷新失败 ⇒ **明确报错**，不静默降级成匿名、不循环重试；
// · `insufficient_scope` ⇒ 报出缺的 scope 并触发一次 step-up（重新出授权链接）。
package com.example.zhengdao.baopu.mcp

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.URLDecoder
import java.util.Collections

class McpOAuthFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String

    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val tokenForms = Collections.synchronizedList(mutableListOf<Map<String, String>>())
    private val mcpAuthHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private val registrations = Collections.synchronizedList(mutableListOf<JSONObject>())

    /** 服务器当前认的 access token（测试用它模拟"过期/被换掉"）。 */
    @Volatile
    private var acceptedAccess = "at-1"

    /** 授权页上出现的 challenge（回调时用它核对 verifier 是否对得上）。 */
    @Volatile
    private var lastChallenge: String? = null

    /** 让 `/mcp` 回 403 insufficient_scope（step-up 用例）。 */
    @Volatile
    private var requireScope: String? = null

    /** 让 `/token` 的 refresh 分支失败（invalid_grant）。 */
    @Volatile
    private var refreshFails = false

    /** 有没有 OAuth 元数据（"不支持 OAuth 的旧式服务器"用例）。 */
    @Volatile
    private var hasOAuthMetadata = true

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        base = "http://127.0.0.1:${server.port}"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = handle(req)
        }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ── 假服务器 ─────────────────────────────────────────────────────────

    private fun handle(req: RecordedRequest): MockResponse {
        val path = req.path.orEmpty().substringBefore('?')
        return when {
            path == "/.well-known/oauth-protected-resource" -> {
                if (!hasOAuthMetadata) return notFound()
                json(
                    JSONObject()
                        .put("resource", "$base/mcp")
                        .put("authorization_servers", org.json.JSONArray().put(base))
                        .put("scopes_supported", org.json.JSONArray().put("files:read").put("files:write")),
                )
            }
            path == "/.well-known/oauth-authorization-server" -> {
                if (!hasOAuthMetadata) return notFound()
                json(
                    JSONObject()
                        .put("issuer", base)
                        .put("authorization_endpoint", "$base/authorize")
                        .put("token_endpoint", "$base/token")
                        .put("registration_endpoint", "$base/register")
                        .put(
                            "code_challenge_methods_supported",
                            org.json.JSONArray().put("S256"),
                        )
                        .put(
                            "grant_types_supported",
                            org.json.JSONArray().put("authorization_code").put("refresh_token"),
                        )
                        .put("token_endpoint_auth_methods_supported", org.json.JSONArray().put("none")),
                )
            }
            path == "/register" -> {
                val body = JSONObject(req.body.readUtf8())
                registrations += body
                json(
                    JSONObject()
                        .put("client_id", "client-abc")
                        .put("redirect_uris", body.optJSONArray("redirect_uris") ?: org.json.JSONArray())
                        .put("token_endpoint_auth_method", "none"),
                )
            }
            path == "/token" -> handleToken(req)
            path == "/mcp" -> handleMcp(req)
            else -> notFound()
        }
    }

    private fun handleToken(req: RecordedRequest): MockResponse {
        val form = parseForm(req.body.readUtf8())
        tokenForms += form
        return when (form["grant_type"]) {
            "authorization_code" -> {
                if (form["code"] != "code-1") return oauthError("invalid_grant", "bad code")
                val verifier = form["code_verifier"].orEmpty()
                if (!McpPkce.isWellFormed(verifier)) return oauthError("invalid_grant", "bad verifier")
                if (lastChallenge != null && McpPkce.challengeS256(verifier) != lastChallenge) {
                    return oauthError("invalid_grant", "PKCE mismatch")
                }
                if (form["resource"] != "$base/mcp") return oauthError("invalid_target", "no resource")
                acceptedAccess = "at-2"
                json(
                    JSONObject()
                        .put("access_token", "at-2")
                        .put("refresh_token", "rt-1")
                        .put("token_type", "Bearer")
                        .put("expires_in", 3600)
                        .put("scope", "files:read"),
                )
            }
            "refresh_token" -> {
                if (refreshFails) return oauthError("invalid_grant", "refresh token expired")
                if (form["resource"] != "$base/mcp") return oauthError("invalid_target", "no resource")
                acceptedAccess = "at-3"
                json(
                    JSONObject()
                        .put("access_token", "at-3")
                        .put("token_type", "Bearer")
                        .put("expires_in", 3600),
                )
            }
            else -> oauthError("unsupported_grant_type", "nope")
        }
    }

    private fun handleMcp(req: RecordedRequest): MockResponse {
        mcpAuthHeaders += req.getHeader("Authorization")
        val auth = req.getHeader("Authorization")
        if (auth != "Bearer $acceptedAccess") {
            return MockResponse()
                .setResponseCode(401)
                .setHeader(
                    "WWW-Authenticate",
                    """Bearer resource_metadata="$base/.well-known/oauth-protected-resource"""",
                )
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"invalid_token"}""")
        }
        requireScope?.let { need ->
            return MockResponse()
                .setResponseCode(403)
                .setHeader(
                    "WWW-Authenticate",
                    """Bearer error="insufficient_scope", scope="$need"""",
                )
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"insufficient_scope"}""")
        }
        val body = JSONObject(req.body.readUtf8())
        val id = body.opt("id") ?: 1
        val method = body.optString("method")
        val result = when (method) {
            "initialize" -> JSONObject()
                .put("protocolVersion", McpJsonRpc.PROTOCOL)
                .put("capabilities", JSONObject())
                .put("serverInfo", JSONObject().put("name", "fake").put("version", "1"))
            "tools/list" -> JSONObject().put("tools", org.json.JSONArray())
            "notifications/initialized" -> return MockResponse().setResponseCode(202)
            else -> JSONObject()
        }
        return json(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
    }

    private fun json(body: JSONObject): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body.toString())

    private fun oauthError(code: String, desc: String): MockResponse = MockResponse()
        .setResponseCode(400)
        .setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("error", code).put("error_description", desc).toString())

    private fun notFound(): MockResponse = MockResponse().setResponseCode(404)

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.isNotEmpty() }.associate { part ->
            val k = part.substringBefore('=')
            val v = part.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v.replace("+", "%2B"), "UTF-8")
        }

    // ── 被测装配 ─────────────────────────────────────────────────────────

    private fun config(id: String = "srv", url: String = "$base/mcp") = McpServerConfig(
        id = id,
        name = "假服务器",
        url = url,
        connectTimeoutMs = 5_000,
        callTimeoutMs = 5_000,
    )

    private fun newFlow(vault: McpTokenVault = McpTokenVault(InMemoryMcpSecretStore())): Pair<McpAuthFlow, McpTokenVault> {
        val oauth = McpOAuthClient(OkHttpClient(), log = { logs += it })
        val flow = McpAuthFlow(
            vault = vault,
            oauth = oauth,
            now = { 1_000_000L },
            log = { logs += it },
        )
        return flow to vault
    }

    private fun stateOf(url: String): String {
        val q = url.substringAfter('?', "")
        return q.split('&').first { it.startsWith("state=") }.substringAfter('=')
    }

    private fun challengeOf(url: String): String {
        val q = url.substringAfter('?', "")
        return q.split('&').first { it.startsWith("code_challenge=") }.substringAfter('=')
    }

    // ── 正例 ─────────────────────────────────────────────────────────────

    @Test
    fun `全流程_发现_注册_S256_换token_带Bearer连上`() {
        val (flow, vault) = newFlow()
        val cfg = config()

        val begin = flow.begin(cfg)
        assertTrue("应当拿到授权链接：$begin", begin is McpAuthBegin.Ready)
        val url = (begin as McpAuthBegin.Ready).authorizationUrl
        assertTrue("授权页必须带 S256", url.contains("code_challenge_method=S256"))
        assertTrue("授权页必须带 resource", url.contains("resource="))
        assertEquals("必须用自定义 scheme 回调（不是 WebView）", "zhengdao://mcp/oauth", redirectOf(url))
        lastChallenge = challengeOf(url)

        // 动态注册用的是公共客户端（没有 secret）
        assertEquals(1, registrations.size)
        assertEquals("none", registrations[0].getString("token_endpoint_auth_method"))
        assertFalse("注册请求里不许出现 client_secret", registrations[0].has("client_secret"))

        val done = flow.complete(
            McpOAuthCallback(code = "code-1", state = stateOf(url), error = null, errorDescription = null),
        )
        assertEquals("srv", done.serverId)
        assertEquals("at-2", vault.load("srv", "$base/mcp")?.accessToken)

        // 换 token 的表单：带 code_verifier 与 resource（负例④的另一半）
        val form = tokenForms.last()
        assertEquals("authorization_code", form["grant_type"])
        assertTrue("verifier 形状要合法", McpPkce.isWellFormed(form["code_verifier"].orEmpty()))
        assertEquals("$base/mcp", form["resource"])

        // 带 Bearer 真连一次
        val client = McpClient(cfg, OkHttpClient(), tokenProvider = flow.providerFor(cfg))
        client.connect()
        client.listTools()
        client.close()
        assertEquals("Bearer at-2", mcpAuthHeaders.last())
    }

    @Test
    fun `负例_state不匹配_拒绝且不存token`() {
        val (flow, vault) = newFlow()
        val begin = flow.begin(config()) as McpAuthBegin.Ready
        lastChallenge = challengeOf(begin.authorizationUrl)
        try {
            flow.complete(
                McpOAuthCallback(
                    code = "code-1",
                    state = "forged-state",
                    error = null,
                    errorDescription = null,
                ),
            )
            fail("伪造的 state 必须拒")
        } catch (e: McpAuthException) {
            assertEquals(McpAuthFailure.STATE_MISMATCH, e.failure)
        }
        assertNull("被拒的回调不许留下 token", vault.load("srv", "$base/mcp"))
        assertTrue("也不许去换 token", tokenForms.isEmpty())
    }

    @Test
    fun `负例_回调里的error_如实报出`() {
        val (flow, _) = newFlow()
        val begin = flow.begin(config()) as McpAuthBegin.Ready
        try {
            flow.complete(
                McpOAuthCallback(
                    code = null,
                    state = stateOf(begin.authorizationUrl),
                    error = "access_denied",
                    errorDescription = "user said no",
                ),
            )
            fail("用户拒绝要给明确结论")
        } catch (e: McpAuthException) {
            assertEquals(McpAuthFailure.AUTHORIZATION_DENIED, e.failure)
            assertTrue(e.userMessage.contains("access_denied"))
        }
    }

    @Test
    fun `401_刷新一次_成功_只重放一次`() {
        val (flow, _) = newFlow()
        val cfg = config()
        val begin = flow.begin(cfg) as McpAuthBegin.Ready
        lastChallenge = challengeOf(begin.authorizationUrl)
        flow.complete(
            McpOAuthCallback(code = "code-1", state = stateOf(begin.authorizationUrl), error = null, errorDescription = null),
        )
        // 服务器把 token 换成 at-3 才认（模拟"access token 过期，refresh 换到新的"）
        acceptedAccess = "at-9"

        val client = McpClient(cfg, OkHttpClient(), tokenProvider = flow.providerFor(cfg))
        client.connect()
        client.listTools()
        client.close()

        assertEquals("刷新只发一次", 1, flow.refreshAttempts)
        val refreshForm = tokenForms.last()
        assertEquals("refresh_token", refreshForm["grant_type"])
        assertEquals("刷新也要带 resource", "$base/mcp", refreshForm["resource"])
        assertEquals("刷新后用新 token", "Bearer at-3", mcpAuthHeaders.last())
        // 401 + 重放 + tools/list = 3 次 /mcp 请求（不含 notifications）
        assertTrue("不许循环重试：${mcpAuthHeaders.size} 次", mcpAuthHeaders.size <= 4)
    }

    @Test
    fun `负例_刷新失败_明确报错_不循环不降级`() {
        val (flow, vault) = newFlow()
        val cfg = config()
        val begin = flow.begin(cfg) as McpAuthBegin.Ready
        lastChallenge = challengeOf(begin.authorizationUrl)
        flow.complete(
            McpOAuthCallback(code = "code-1", state = stateOf(begin.authorizationUrl), error = null, errorDescription = null),
        )
        acceptedAccess = "never-matches"
        refreshFails = true

        val client = McpClient(cfg, OkHttpClient(), tokenProvider = flow.providerFor(cfg))
        try {
            client.connect()
            fail("刷新失败必须明确报错")
        } catch (e: McpException) {
            assertEquals(McpFailure.AUTH_REQUIRED, e.failure)
            assertTrue("文案要说清是授权问题", e.userMessage.contains("重新授权"))
            assertFalse("不许说成普通网络错误", e.userMessage.contains("连接失败"))
        }
        assertEquals("刷新只试一次", 1, flow.refreshAttempts)
        assertEquals("授权状态要变成已撤销", McpAuthStatus.REVOKED, flow.status(cfg))
        assertNull("token 要清掉（撤销标记留下）", vault.load("srv", "$base/mcp"))

        // 再问一次：一个包都不许发（负例⑤：刷新循环）
        val before = tokenForms.size
        assertNull(flow.providerFor(cfg).refreshAfterUnauthorized())
        assertEquals("失败后不许再发刷新请求", before, tokenForms.size)
        assertEquals(1, flow.refreshAttempts)
    }

    @Test
    fun `负例_insufficient_scope_要stepup不是普通失败`() {
        val (flow, _) = newFlow()
        val cfg = config()
        val begin = flow.begin(cfg) as McpAuthBegin.Ready
        lastChallenge = challengeOf(begin.authorizationUrl)
        flow.complete(
            McpOAuthCallback(code = "code-1", state = stateOf(begin.authorizationUrl), error = null, errorDescription = null),
        )
        requireScope = "files:write"

        val client = McpClient(cfg, OkHttpClient(), tokenProvider = flow.providerFor(cfg))
        try {
            client.connect()
            fail("403 insufficient_scope 不许当成普通失败")
        } catch (e: McpException) {
            assertEquals(McpFailure.INSUFFICIENT_SCOPE, e.failure)
            assertEquals("files:write", e.missingScope)
            assertTrue("文案要说出缺哪个权限", e.userMessage.contains("files:write"))
        }
    }

    @Test
    fun `负例_不支持OAuth_明确失败_不发匿名请求`() {
        hasOAuthMetadata = false
        val (flow, _) = newFlow()
        val cfg = config()

        val begin = flow.begin(cfg)
        assertTrue("必须明确失败：$begin", begin is McpAuthBegin.Failed)
        val failed = begin as McpAuthBegin.Failed
        assertTrue(
            "失败原因要指向「不支持现代授权」",
            failed.message.contains("不支持现代授权") || failed.message.contains("OAuth"),
        )
        assertEquals("界面要能标「不支持 OAuth」", McpAuthStatus.UNSUPPORTED, flow.status(cfg))
        assertTrue("不许发带凭据的请求", mcpAuthHeaders.all { it == null })
    }

    @Test
    fun `负例_换token失败_不留下半截凭据`() {
        val (flow, vault) = newFlow()
        val begin = flow.begin(config()) as McpAuthBegin.Ready
        lastChallenge = challengeOf(begin.authorizationUrl)
        try {
            flow.complete(
                McpOAuthCallback(
                    code = "wrong-code",
                    state = stateOf(begin.authorizationUrl),
                    error = null,
                    errorDescription = null,
                ),
            )
            fail("code 不对必须失败")
        } catch (e: McpAuthException) {
            assertEquals(McpAuthFailure.TOKEN_EXCHANGE_FAILED, e.failure)
        }
        assertNull(vault.load("srv", "$base/mcp"))
    }

    @Test
    fun `负例_日志不含token与verifier`() {
        val (flow, _) = newFlow()
        val cfg = config()
        val begin = flow.begin(cfg) as McpAuthBegin.Ready
        lastChallenge = challengeOf(begin.authorizationUrl)
        flow.complete(
            McpOAuthCallback(code = "code-1", state = stateOf(begin.authorizationUrl), error = null, errorDescription = null),
        )
        val client = McpClient(cfg, OkHttpClient(), tokenProvider = flow.providerFor(cfg))
        client.connect()
        client.close()

        assertTrue("应当有日志（否则这条断言是空的）", logs.isNotEmpty())
        listOf("at-2", "rt-1", "code-1").forEach { secret ->
            assertFalse("日志里出现 $secret", logs.any { it.contains(secret) })
        }
        assertFalse(
            "日志里出现 verifier",
            logs.any { it.contains("code_verifier") },
        )
    }

    private fun redirectOf(url: String): String =
        URLDecoder.decode(
            url.substringAfter('?').split('&').first { it.startsWith("redirect_uri=") }
                .substringAfter('=').replace("+", "%2B"),
            "UTF-8",
        )
}
