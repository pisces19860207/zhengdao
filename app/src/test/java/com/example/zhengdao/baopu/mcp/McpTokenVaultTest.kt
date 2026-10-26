// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十六批 · MCP OAuth 2.1：token 保险柜的单测。
//
// 这一份钉的是**最硬的红线**（用户原话：令牌只进 EncryptedSharedPreferences，
// 永不进日志/报告/台账）在"逻辑层"能钉住的部分：
// · 按服务器隔离（A 的 token 不在 B 的键下）；
// · 受众不符 ⇒ 当没有 token（负例④的另一半）；
// · 断开/删除/全部断开都清得干净（不留孤儿）；
// · toString 与日志里**没有** token 原文（加密与备份排除由源码级门禁钉，见负例测试）。
package com.example.zhengdao.baopu.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTokenVaultTest {

    private val resourceA = "https://a.example.com/mcp"
    private val resourceB = "https://b.example.com/mcp"

    private fun tokens(
        access: String = "at-1234567890",
        refresh: String? = "rt-0987654321",
        expiresInMs: Long = 3600_000,
        resource: String = resourceA,
        now: Long = 1_000_000,
    ) = McpTokenSet(
        accessToken = access,
        refreshToken = refresh,
        tokenType = "Bearer",
        scope = "files:read",
        expiresAtMs = now + expiresInMs,
        resource = resource,
    )

    @Test
    fun `存了能取回来_字段逐字相等`() {
        val vault = McpTokenVault(InMemoryMcpSecretStore())
        vault.save("a", tokens())
        val back = vault.load("a", resourceA)
        assertNotNull(back)
        assertEquals("at-1234567890", back!!.accessToken)
        assertEquals("rt-0987654321", back.refreshToken)
        assertEquals("files:read", back.scope)
        assertEquals(resourceA, back.resource)
    }

    @Test
    fun `负例_按服务器隔离_A的token在B的键下取不到`() {
        val vault = McpTokenVault(InMemoryMcpSecretStore())
        vault.save("a", tokens())
        assertNull("B 没有存过 token", vault.load("b", resourceB))
        // 即使有人把 A 的记录搬到 B 的键下，受众校验也会拦住（下一条）。
        assertNotNull(vault.load("a", resourceA))
    }

    @Test
    fun `负例_受众不符_当没有token`() {
        val store = InMemoryMcpSecretStore()
        val vault = McpTokenVault(store)
        vault.save("b", tokens(resource = resourceA))
        assertNull("A 的 token 不能用在 B 上", vault.load("b", resourceB))
        assertTrue("但也不能把它删掉（它可能属于另一台服务器）", store.keys().contains("tok.b"))
    }

    @Test
    fun `状态_未授权_有效_过期`() {
        val vault = McpTokenVault(InMemoryMcpSecretStore())
        assertEquals(McpAuthStatus.UNAUTHORIZED, vault.status("a", resourceA, 1_000_000))
        vault.save("a", tokens(expiresInMs = 3600_000, now = 1_000_000))
        assertEquals(McpAuthStatus.VALID, vault.status("a", resourceA, 1_100_000))
        // 提前 60s 判过期：1_000_000 + 3_600_000 - 60_000 之后就算过期。
        assertEquals(McpAuthStatus.EXPIRED, vault.status("a", resourceA, 1_000_000 + 3_600_000))
    }

    @Test
    fun `撤销_清token_留标记`() {
        val store = InMemoryMcpSecretStore()
        val vault = McpTokenVault(store)
        vault.save("a", tokens())
        vault.markRevoked("a")
        assertNull("撤销后不许再有 token", vault.load("a", resourceA))
        assertEquals(McpAuthStatus.REVOKED, vault.status("a", resourceA, 1_000_000))
        assertFalse("token 键要删掉", store.keys().contains("tok.a"))
        assertTrue("只留一个无内容的撤销标记", store.keys().contains("rev.a"))
        assertEquals("标记里不许有凭据", "1", store.read("rev.a"))
    }

    @Test
    fun `重新授权_撤销标记作废`() {
        val vault = McpTokenVault(InMemoryMcpSecretStore())
        vault.markRevoked("a")
        assertEquals(McpAuthStatus.REVOKED, vault.status("a", resourceA, 1_000_000))
        vault.save("a", tokens())
        assertEquals(McpAuthStatus.VALID, vault.status("a", resourceA, 1_000_000))
    }

    @Test
    fun `断开_清token与标记_再取是未授权`() {
        val store = InMemoryMcpSecretStore()
        val vault = McpTokenVault(store)
        vault.save("a", tokens())
        vault.clear("a")
        assertNull(vault.load("a", resourceA))
        assertEquals(McpAuthStatus.UNAUTHORIZED, vault.status("a", resourceA, 1_000_000))
        assertTrue("键也删掉（不留空壳）", store.keys().none { it.startsWith("tok.") || it.startsWith("rev.") })
    }

    @Test
    fun `全部断开_只清token类键_不动别的`() {
        val store = InMemoryMcpSecretStore()
        val vault = McpTokenVault(store)
        vault.save("a", tokens())
        vault.save("b", tokens(resource = resourceB))
        store.write("other.keep", "x")
        vault.clearAll()
        assertNull(vault.load("a", resourceA))
        assertNull(vault.load("b", resourceB))
        assertEquals("别的键不许被误删", "x", store.read("other.keep"))
    }

    @Test
    fun `负例_toString不含token`() {
        val text = tokens().toString()
        assertFalse("access token 不许出现", text.contains("at-1234567890"))
        assertFalse("refresh token 不许出现", text.contains("rt-0987654321"))
        assertTrue(text.contains("***"))
    }

    @Test
    fun `负例_日志不含token`() {
        val logs = mutableListOf<String>()
        val vault = McpTokenVault(InMemoryMcpSecretStore(), log = { logs.add(it) })
        vault.save("a", tokens())
        vault.load("a", resourceA)
        vault.markRevoked("a")
        vault.clear("a")
        vault.clearAll()
        assertTrue("应当有日志（否则这条断言是空的）", logs.isNotEmpty())
        assertFalse("日志里出现 access token", logs.any { it.contains("at-1234567890") })
        assertFalse("日志里出现 refresh token", logs.any { it.contains("rt-0987654321") })
    }

    @Test
    fun `坏数据_解不开就当没有_并清掉`() {
        val store = InMemoryMcpSecretStore()
        store.write("tok.a", "{not json")
        val vault = McpTokenVault(store)
        assertNull(vault.load("a", resourceA))
        assertFalse("坏记录要顺手清掉，免得每次启动都白试", store.keys().contains("tok.a"))
    }

    @Test
    fun `授权会话_可持久化_不含token`() {
        val store = InMemoryMcpSecretStore()
        val vault = McpTokenVault(store)
        val session = McpAuthSession(
            metadata = McpAuthServerMetadata(
                issuer = "https://auth.example.com",
                authorizationEndpoint = "https://auth.example.com/authorize",
                tokenEndpoint = "https://auth.example.com/token",
                registrationEndpoint = "https://auth.example.com/register",
                scopesSupported = listOf("files:read"),
                codeChallengeMethods = listOf("S256"),
                tokenAuthMethods = listOf("none"),
                grantTypes = listOf("authorization_code", "refresh_token"),
            ),
            registration = McpClientRegistration("client-1", listOf("zhengdao://mcp/oauth"), "none"),
            scope = "files:read",
            resource = resourceA,
        )
        vault.saveSession("a", session)
        val back = vault.loadSession("a")
        assertEquals("client-1", back?.registration?.clientId)
        assertEquals("https://auth.example.com/token", back?.metadata?.tokenEndpoint)
        assertEquals(resourceA, back?.resource)
        assertFalse("会话里不许有 token 字段", store.read("sess.a").orEmpty().contains("access_token"))
    }
}
