// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百七十批 B · MCP 真入口：配置落盘（prefs JSON）与「地址 → 配置」的纯 JVM 判据。
//
// 这一份钉三件事：
// 1. 写盘只写白名单字段 —— **请求头（可能带 key）绝不落盘**；
// 2. 坏数据只跳过自己（坏 JSON / 单条非法 / 重复 id / 超上限），绝不整份丢、绝不抛；
// 3. 地址 → 配置的闸沿用 McpUrlRules（https 或环回 http），同址同 id。
package com.example.zhengdao.baopu.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerStoreTest {

    private class FakeStorage(var text: String? = null) : McpServerStorage {
        var writes = 0
        var failOnWrite = false

        override fun read(): String? = text

        override fun write(json: String) {
            if (failOnWrite) throw IllegalStateException("模拟写入失败")
            writes++
            text = json
        }
    }

    private fun config(
        id: String = "srv",
        name: String = "演示 server",
        url: String = "http://127.0.0.1:8080/mcp",
        headers: Map<String, String> = emptyMap(),
    ) = McpServerConfig(
        id = id,
        name = name,
        url = url,
        headers = headers,
        callTimeoutMs = 12_000,
        connectTimeoutMs = 5_000,
    )

    @Test
    fun `空存储_读回空列表`() {
        val store = McpServerStore(FakeStorage())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `写入后原样读回`() {
        val storage = FakeStorage()
        val store = McpServerStore(storage)
        assertTrue(store.save(listOf(config())))

        val back = store.load()
        assertEquals(1, back.size)
        assertEquals("srv", back[0].id)
        assertEquals("演示 server", back[0].name)
        assertEquals("http://127.0.0.1:8080/mcp", back[0].url)
        assertEquals("超时要原样带回来（决定 tools-call 超时）", 12_000L, back[0].callTimeoutMs)
        assertEquals(5_000L, back[0].connectTimeoutMs)
    }

    @Test
    fun `负例_请求头绝不落盘`() {
        val storage = FakeStorage()
        val store = McpServerStore(storage)
        store.save(listOf(config(headers = mapOf("Authorization" to "Bearer secret-abc-123"))))

        val raw = storage.text.orEmpty()
        assertFalse("密钥不许进 prefs", raw.contains("secret-abc-123"))
        assertFalse("头名也不留", raw.contains("Authorization"))
        assertTrue("配置本体还是要写的", raw.contains("127.0.0.1"))

        val back = store.load().single()
        assertTrue("读回来 headers 恒为空（盘上根本没有）", back.headers.isEmpty())
    }

    @Test
    fun `负例_坏 JSON 按空处理_不抛`() {
        val store = McpServerStore(FakeStorage("这不是 JSON"))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `负例_顶层是数组_按空处理`() {
        val store = McpServerStore(FakeStorage("""[{"id":"srv"}]"""))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `负例_单条非法只跳过自己`() {
        val good = """{"id":"ok1","name":"好的","url":"https://example.com/mcp"}"""
        // 第二条 URL 是 ftp（McpServerConfig 的 require 会拒）
        val bad = """{"id":"bad1","name":"坏的","url":"ftp://example.com/mcp"}"""
        val json = """{"v":1,"servers":[$good,$bad]}"""
        val back = McpServerStore(FakeStorage(json)).load()

        assertEquals("合法那条要留下", 1, back.size)
        assertEquals("ok1", back[0].id)
    }

    @Test
    fun `负例_重复 id 只留第一条`() {
        val a = """{"id":"dup","name":"第一","url":"https://a.example.com/mcp"}"""
        val b = """{"id":"dup","name":"第二","url":"https://b.example.com/mcp"}"""
        val back = McpServerStore(FakeStorage("""{"v":1,"servers":[$a,$b]}""")).load()

        assertEquals(1, back.size)
        assertEquals("第一", back[0].name)
    }

    @Test
    fun `越界超时被夹回范围_而不是整条丢`() {
        val one = """{"id":"t1","name":"超时怪","url":"https://example.com/mcp","call_timeout_ms":1,"connect_timeout_ms":9999999}"""
        val back = McpServerStore(FakeStorage("""{"v":1,"servers":[$one]}""")).load()

        assertEquals(1, back.size)
        assertEquals(McpServerConfig.MIN_TIMEOUT_MS, back[0].callTimeoutMs)
        assertEquals(McpServerConfig.MAX_TIMEOUT_MS, back[0].connectTimeoutMs)
    }

    @Test
    fun `负例_超上限只写前 MAX_SERVERS 条`() {
        val storage = FakeStorage()
        val store = McpServerStore(storage)
        val many = (1..McpServerStore.MAX_SERVERS + 3).map { config(id = "s$it") }
        assertTrue(store.save(many))

        val back = store.load()
        assertEquals(McpServerStore.MAX_SERVERS, back.size)
        assertEquals("s1", back.first().id)
        assertEquals("s${McpServerStore.MAX_SERVERS}", back.last().id)
        assertFalse("第 17 条不许进盘", storage.text.orEmpty().contains("\"s17\""))
    }

    @Test
    fun `负例_写失败返回 false_不抛`() {
        val storage = FakeStorage().also { it.failOnWrite = true }
        val store = McpServerStore(storage)
        assertFalse(store.save(listOf(config())))
        assertTrue("失败不带头写", storage.text == null)
    }

    // ── 地址 → 配置（McpServerDraft） ────────────────────────────────────

    @Test
    fun `https 地址_名字取主机名_id 稳定`() {
        val a = McpServerDraft.fromUrl("https://www.example.com/mcp")
        val b = McpServerDraft.fromUrl("  https://www.example.com/mcp  ")
        assertTrue(a is McpServerDraft.Result.Ok)
        a as McpServerDraft.Result.Ok
        assertEquals("www. 去掉，面板上认人", "example.com", a.config.name)
        assertEquals("同址同 id（addServer 才能拦住重复）", a.config.id, (b as McpServerDraft.Result.Ok).config.id)
        assertTrue("id 要过 McpServerConfig 的小写闸", McpServerConfig.ID.matches(a.config.id))
    }

    @Test
    fun `环回 http 许可`() {
        listOf("http://127.0.0.1:8080/mcp", "http://localhost:3000/mcp").forEach { url ->
            assertTrue("$url 应该许可", McpServerDraft.fromUrl(url) is McpServerDraft.Result.Ok)
        }
    }

    @Test
    fun `负例_外部明文 http 不许可`() {
        val r = McpServerDraft.fromUrl("http://example.com/mcp")
        assertTrue(r is McpServerDraft.Result.Invalid)
        assertTrue((r as McpServerDraft.Result.Invalid).message.contains("https"))
    }

    @Test
    fun `负例_空串_ftp_带账号_带 fragment 都不许可`() {
        listOf("", "   ", "ftp://example.com/mcp", "https://user:pw@example.com/mcp", "https://example.com/mcp#x")
            .forEach { url ->
                assertTrue("「$url」应该被拒", McpServerDraft.fromUrl(url) is McpServerDraft.Result.Invalid)
            }
    }

    @Test
    fun `不同地址_不同 id`() {
        val a = McpServerDraft.fromUrl("https://a.example.com/mcp") as McpServerDraft.Result.Ok
        val b = McpServerDraft.fromUrl("https://b.example.com/mcp") as McpServerDraft.Result.Ok
        assertNotEquals(a.config.id, b.config.id)
    }
}
