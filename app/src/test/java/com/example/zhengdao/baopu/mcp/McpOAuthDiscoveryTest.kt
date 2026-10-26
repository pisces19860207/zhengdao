// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十六批 · MCP OAuth 2.1：发现链与 PKCE 的纯函数单测（无网络）。
//
// 覆盖：
// · RFC 7636 官方测试向量（S256 挑战值逐字相等）——PKCE 算错的唯一可靠证据；
// · `WWW-Authenticate` 的解析（多段挑战、引号内逗号、`insufficient_scope`）；
// · well-known 地址的两种排法（RFC 9728 路径插入 / RFC 8414 与 OIDC 的差异）；
// · 元数据的四道闸（缺端点、明文 http、只支持 plain、issuer 与来源主机不一致）；
// · 授权页地址必须同时带 `code_challenge_method=S256`、`state`、`resource`。
package com.example.zhengdao.baopu.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLDecoder

class McpOAuthDiscoveryTest {

    // ── PKCE ─────────────────────────────────────────────────────────────

    @Test
    fun `PKCE_S256_官方测试向量`() {
        // RFC 7636 Appendix B：verifier 与 challenge 是标准给出的，算错一位就不相等。
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            McpPkce.challengeS256(verifier),
        )
        assertTrue("这个 verifier 必须是合法形状", McpPkce.isWellFormed(verifier))
    }

    @Test
    fun `PKCE_只认S256_没有plain分支`() {
        assertEquals("S256", McpPkce.METHOD)
        // challenge 恒等于 SHA-256 变换后的值，绝不等于 verifier 本身（plain 的定义）。
        repeat(20) {
            val v = McpPkce.verifier()
            assertTrue("verifier 长度要 43–128：${v.length}", v.length in 43..128)
            assertTrue("verifier 只能用 unreserved 字符", McpPkce.isWellFormed(v))
            assertFalse("不许出现 plain（challenge == verifier）", McpPkce.challengeS256(v) == v)
        }
    }

    @Test
    fun `state_必须逐字相等_长度不同直接拒`() {
        val s = McpPkce.state()
        assertTrue(McpPkce.stateMatches(s, s))
        assertFalse(McpPkce.stateMatches(s, s.dropLast(1)))
        assertFalse(McpPkce.stateMatches(s, null))
        assertFalse(McpPkce.stateMatches(s, ""))
        assertFalse(McpPkce.stateMatches("", ""))
    }

    // ── resource（RFC 8707） ──────────────────────────────────────────────

    @Test
    fun `resource_规范化_去端口去query去尾斜杠`() {
        assertEquals(
            "https://mcp.example.com/mcp",
            McpResource.canonical("HTTPS://MCP.Example.com:443/mcp/?token=x#frag"),
        )
        assertEquals(
            "http://127.0.0.1:8080/mcp",
            McpResource.canonical("http://127.0.0.1:8080/mcp"),
        )
        assertEquals("https://a.example.com", McpResource.canonical("https://a.example.com/"))
        assertNull("带 userInfo 的地址不做 resource", McpResource.canonical("https://u:p@a.example.com/mcp"))
    }

    @Test
    fun `resource_受众判定_不同服务器不相等`() {
        assertTrue(
            McpResource.sameAudience(
                "https://a.example.com/mcp",
                "https://a.example.com/mcp/",
            ),
        )
        assertFalse(
            McpResource.sameAudience(
                "https://a.example.com/mcp",
                "https://b.example.com/mcp",
            ),
        )
        assertFalse("空值不算同一个受众", McpResource.sameAudience(null, "https://a.example.com/mcp"))
    }

    // ── WWW-Authenticate ─────────────────────────────────────────────────

    @Test
    fun `挑战_解析resource_metadata与scope`() {
        val c = McpOAuthDiscovery.parseWwwAuthenticate(
            """Bearer resource_metadata="https://mcp.example.com/.well-known/oauth-protected-resource", """ +
                """error="insufficient_scope", scope="files:read files:write"""",
        )
        assertEquals(
            "https://mcp.example.com/.well-known/oauth-protected-resource",
            c?.resourceMetadataUrl,
        )
        assertEquals("insufficient_scope", c?.error)
        assertEquals("files:read files:write", c?.scope)
        assertTrue("403 + insufficient_scope 要能被识别成 step-up", c!!.insufficientScope)
    }

    @Test
    fun `挑战_引号里的逗号不切段_多段挑战只认Bearer`() {
        val c = McpOAuthDiscovery.parseWwwAuthenticate(
            """Basic realm="a, b", Bearer error="invalid_token", error_description="expired, please re-auth"""",
        )
        assertEquals("invalid_token", c?.error)
        assertEquals("expired, please re-auth", c?.errorDescription)
        assertFalse("不是 insufficient_scope", c!!.insufficientScope)
    }

    @Test
    fun `挑战_没有Bearer就是null`() {
        assertNull(McpOAuthDiscovery.parseWwwAuthenticate("""Basic realm="x""""))
        assertNull(McpOAuthDiscovery.parseWwwAuthenticate(null))
        assertNull(McpOAuthDiscovery.parseWwwAuthenticate(""))
        // 裸 Bearer（没有参数）仍算一次挑战，只是没线索。
        val bare = McpOAuthDiscovery.parseWwwAuthenticate("Bearer")
        assertNull(bare?.resourceMetadataUrl)
        assertNull(bare?.error)
    }

    // ── well-known 地址 ──────────────────────────────────────────────────

    @Test
    fun `PRM_地址_路径插入优先_根地址兜底`() {
        assertEquals(
            listOf(
                "https://mcp.example.com/.well-known/oauth-protected-resource/mcp",
                "https://mcp.example.com/.well-known/oauth-protected-resource",
            ),
            McpOAuthDiscovery.resourceMetadataUrls("https://mcp.example.com/mcp"),
        )
        assertEquals(
            listOf("https://mcp.example.com/.well-known/oauth-protected-resource"),
            McpOAuthDiscovery.resourceMetadataUrls("https://mcp.example.com"),
        )
    }

    @Test
    fun `AS元数据_地址_RFC8414与OIDC两种排法`() {
        assertEquals(
            listOf(
                "https://auth.example.com/.well-known/oauth-authorization-server/tenant1",
                "https://auth.example.com/tenant1/.well-known/openid-configuration",
            ),
            McpOAuthDiscovery.authServerMetadataUrls("https://auth.example.com/tenant1"),
        )
        assertEquals(
            listOf(
                "https://auth.example.com/.well-known/oauth-authorization-server",
                "https://auth.example.com/.well-known/openid-configuration",
            ),
            McpOAuthDiscovery.authServerMetadataUrls("https://auth.example.com"),
        )
    }

    // ── 元数据解析的四道闸 ───────────────────────────────────────────────

    private fun asJson(vararg extra: Pair<String, Any>): JSONObject = JSONObject()
        .put("issuer", "https://auth.example.com")
        .put("authorization_endpoint", "https://auth.example.com/authorize")
        .put("token_endpoint", "https://auth.example.com/token")
        .put("registration_endpoint", "https://auth.example.com/register")
        .put("code_challenge_methods_supported", org.json.JSONArray().put("S256"))
        .put("grant_types_supported", org.json.JSONArray().put("authorization_code").put("refresh_token"))
        .also { o -> extra.forEach { (k, v) -> o.put(k, v) } }

    @Test
    fun `AS元数据_正常解析`() {
        val meta = McpOAuthDiscovery.parseAuthServerMetadata(
            asJson(),
            "https://auth.example.com/.well-known/oauth-authorization-server",
        )
        assertEquals("https://auth.example.com/authorize", meta.authorizationEndpoint)
        assertEquals("https://auth.example.com/token", meta.tokenEndpoint)
        assertEquals("https://auth.example.com/register", meta.registrationEndpoint)
        assertFalse(meta.rejectsS256)
        assertTrue(meta.supportsRefresh)
    }

    @Test
    fun `AS元数据_只支持plain_拒`() {
        try {
            McpOAuthDiscovery.parseAuthServerMetadata(
                asJson("code_challenge_methods_supported" to org.json.JSONArray().put("plain")),
                "https://auth.example.com/.well-known/oauth-authorization-server",
            )
            fail("只支持 plain 的服务器必须拒（负例③）")
        } catch (e: McpAuthException) {
            assertEquals(McpAuthFailure.BAD_METADATA, e.failure)
            assertTrue(e.userMessage.contains("S256"))
        }
    }

    @Test
    fun `AS元数据_明文http端点_拒`() {
        try {
            McpOAuthDiscovery.parseAuthServerMetadata(
                asJson("token_endpoint" to "http://auth.example.com/token"),
                "https://auth.example.com/.well-known/oauth-authorization-server",
            )
            fail("非环回的 http token 端点必须拒")
        } catch (e: McpAuthException) {
            assertEquals(McpAuthFailure.BAD_METADATA, e.failure)
        }
    }

    @Test
    fun `AS元数据_issuer与来源主机不一致_拒`() {
        try {
            McpOAuthDiscovery.parseAuthServerMetadata(
                asJson("issuer" to "https://evil.example.net"),
                "https://auth.example.com/.well-known/oauth-authorization-server",
            )
            fail("issuer 与来源主机不一致必须拒")
        } catch (e: McpAuthException) {
            assertEquals(McpAuthFailure.BAD_METADATA, e.failure)
            assertTrue(e.userMessage.contains("issuer"))
        }
    }

    @Test
    fun `AS元数据_缺token端点_拒`() {
        val json = asJson()
        json.remove("token_endpoint")
        try {
            McpOAuthDiscovery.parseAuthServerMetadata(
                json,
                "https://auth.example.com/.well-known/oauth-authorization-server",
            )
            fail("缺 token_endpoint 必须拒")
        } catch (e: McpAuthException) {
            assertEquals(McpAuthFailure.BAD_METADATA, e.failure)
        }
    }

    @Test
    fun `PRM_authorization_servers为空_拒`() {
        try {
            McpOAuthDiscovery.parseResourceMetadata(
                JSONObject().put("resource", "https://mcp.example.com/mcp"),
                "https://mcp.example.com/.well-known/oauth-protected-resource",
            )
            fail("没有 authorization_servers 就没有下一步")
        } catch (e: McpAuthException) {
            assertEquals(McpAuthFailure.NO_METADATA, e.failure)
        }
    }

    @Test
    fun `PRM_授权服务器是明文http_拒`() {
        try {
            McpOAuthDiscovery.parseResourceMetadata(
                JSONObject().put(
                    "authorization_servers",
                    org.json.JSONArray().put("http://auth.example.com"),
                ),
                "https://mcp.example.com/.well-known/oauth-protected-resource",
            )
            fail("明文 http 的授权服务器必须拒")
        } catch (e: McpAuthException) {
            assertEquals(McpAuthFailure.BAD_METADATA, e.failure)
        }
    }

    // ── 授权页地址 ───────────────────────────────────────────────────────

    @Test
    fun `授权URL_必带S256_state_resource`() {
        val meta = McpOAuthDiscovery.parseAuthServerMetadata(
            asJson(),
            "https://auth.example.com/.well-known/oauth-authorization-server",
        )
        val url = McpOAuthDiscovery.authorizationUrl(
            metadata = meta,
            clientId = "client-1",
            redirectUri = McpAuthFlow.DEFAULT_REDIRECT_URI,
            state = "STATE123",
            codeChallenge = McpPkce.challengeS256("v".repeat(43)),
            resource = "https://mcp.example.com/mcp",
            scope = "files:read files:write",
        )
        val params = queryOf(url)
        assertEquals("code", params["response_type"])
        assertEquals("client-1", params["client_id"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("STATE123", params["state"])
        assertEquals("https://mcp.example.com/mcp", params["resource"])
        assertEquals("zhengdao://mcp/oauth", params["redirect_uri"])
        assertEquals("files:read files:write", params["scope"])
        assertTrue("challenge 必须是 S256 结果", params["code_challenge"]!!.length == 43)
        assertFalse("授权页地址里不许出现 verifier", url.contains("code_verifier"))
    }

    @Test
    fun `授权URL_端点自带query时用&拼接`() {
        val meta = McpOAuthDiscovery.parseAuthServerMetadata(
            asJson("authorization_endpoint" to "https://auth.example.com/authorize?tenant=a"),
            "https://auth.example.com/.well-known/oauth-authorization-server",
        )
        val url = McpOAuthDiscovery.authorizationUrl(
            meta, "c", McpAuthFlow.DEFAULT_REDIRECT_URI, "s", "ch", "https://m.example.com/mcp", null,
        )
        assertTrue(url.startsWith("https://auth.example.com/authorize?tenant=a&response_type=code"))
        assertNull("没给 scope 就不带 scope 参数", queryOf(url)["scope"])
    }

    /** 授权页的 query 是 **form 编码**（空格 → `+`），所以这里按 form 语义解码。 */
    private fun queryOf(url: String): Map<String, String> {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').filter { it.isNotEmpty() }.associate { part ->
            val k = part.substringBefore('=')
            val v = part.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}
