// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十五批 · B3：216「测试连接」——只探 discovery 与一次未授权请求看是否 401。
//
// 判据（派工单 B3）：
// · 每台服务器一行一颗按钮（清单层门禁钉住）；
// · **不建会话、不存 token、不打印 token／敏感头**（结构面门禁 ＋ 行为面回读）；
// · 失败给**具体原因**：DNS／证书／超时／拒连／HTTP 状态，不许只说「连接失败」。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.agent.ToolRegistry
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpConnectProbeTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    // MockWebServer.url() 的主机名取自本机规范名（Windows 上是机器名），
    // 而 McpUrlRules 只放行环回明文 http ⇒ 一律用 127.0.0.1 拼。
    private fun mcpUrl(path: String = "/mcp"): String = "http://127.0.0.1:${server.port}$path"

    // ── 正例：连通判定 ────────────────────────────────────────────────────

    @Test
    fun `正例_200_连通_且这次探针不带Authorization`() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"jsonrpc":"2.0","id":1}""").build())
        val r = McpConnectProbe.probe(mcpUrl())
        assertEquals(200, r.statusCode)
        assertTrue(r.reachable)
        assertNull(r.failure)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertNull("探针必须是未授权请求：不许带 Authorization", req.headers["Authorization"])
        assertNull("也不许带 Cookie", req.headers["Cookie"])
        assertEquals("initialize", org.json.JSONObject(req.body?.utf8().orEmpty()).optString("method"))
    }

    @Test
    fun `正例_401_且discovery里有授权服务器`() {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"resource":"http://localhost:${server.port}/mcp",
                    "authorization_servers":["http://localhost:${server.port}"]}""",
            ).build(),
        )
        val r = McpConnectProbe.probe(mcpUrl())
        assertEquals(401, r.statusCode)
        assertTrue(r.needsAuth)
        assertTrue("discovery 里给了授权服务器", r.discoveryFound)
    }

    @Test
    fun `正例_401_但discovery没有元数据`() {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        val r = McpConnectProbe.probe(mcpUrl())
        assertEquals(401, r.statusCode)
        assertFalse(r.discoveryFound)
    }

    @Test
    fun `正例_404_原样报状态码_不冒充连通失败原因`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        val r = McpConnectProbe.probe(mcpUrl())
        assertEquals(404, r.statusCode)
        assertNull("有状态码就不是网络层失败", r.failure)
    }

    // ── 正例：失败原因要具体 ──────────────────────────────────────────────

    @Test
    fun `正例_DNS失败给DNS原因`() {
        val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
            })
            .build()
        val r = McpConnectProbe.probe("https://probe-245.invalid/mcp", http)
        assertEquals(McpProbeFailure.DNS, r.failure)
        assertNull(r.statusCode)
    }

    @Test
    fun `正例_拒连给拒连原因`() {
        // 先占一个端口再放掉 ⇒ 这个端口确定是关的（不猜魔法端口）
        val port = ServerSocket(0).use { it.localPort }
        val r = McpConnectProbe.probe("http://127.0.0.1:$port/mcp")
        assertEquals(McpProbeFailure.REFUSED, r.failure)
    }

    @Test
    fun `正例_超时给超时原因`() {
        server.enqueue(MockResponse.Builder().code(200).headersDelay(3, TimeUnit.SECONDS).build())
        val r = McpConnectProbe.probe(mcpUrl(), timeoutMs = 400)
        assertEquals(McpProbeFailure.TIMEOUT, r.failure)
    }

    @Test
    fun `正例_地址不合法直接判_且一个请求都不发`() {
        val http = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
            })
            .build()
        // 外部明文 http：McpUrlRules 不许 ⇒ 连 DNS 都不该走到
        val r = McpConnectProbe.probe("http://example.com/mcp", http)
        assertEquals(McpProbeFailure.BAD_URL, r.failure)
    }

    // ── 行为面：只测连通，不改清单状态、不建会话 ──────────────────────────

    @Test
    fun `正例_探针前后清单一个字都没变`() {
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        val source = McpToolSource(ToolRegistry())
        assertTrue(
            source.addServer(McpServerConfig(id = "s1", name = "探针样本", url = mcpUrl())),
        )
        val before = source.snapshot()
        val r = source.probe("s1")
        assertNotNull(r)
        assertEquals(401, r!!.statusCode)
        val after = source.snapshot()
        assertEquals("探针不许改状态／工具数／最后连接时间", before, after)
        assertEquals(McpServerStatus.OFFLINE, after.single().status)
        assertEquals(0, after.single().toolCount)
    }

    @Test
    fun `正例_清单里没有的id探针返回null_不联网`() {
        val source = McpToolSource(ToolRegistry())
        assertNull(source.probe("no-such-server"))
        assertEquals(0, server.requestCount)
    }

    // ── 门禁：结构面（token／会话／头／打印） ─────────────────────────────

    @Test
    fun `门禁_探针不碰token不建会话`() {
        val src = codeOnly(readSource("baopu/mcp/McpConnectProbe.kt"))
        assertFalse("不许出现 token（任何形式）", Regex("(?i)token").containsMatchIn(src))
        assertFalse(src.contains("McpTokenVault"))
        assertFalse(src.contains("McpAuthFlow"))
        assertFalse(src.contains("McpToolSource"))
        assertFalse(src.contains("McpServerStore"))
        assertFalse("不许建会话／连接", Regex("""\bconnect\(""").containsMatchIn(src))
        assertFalse("探针是未授权请求", src.contains("Authorization"))
    }

    @Test
    fun `门禁_探针只读状态码_不读响应头不打印`() {
        val src = codeOnly(readSource("baopu/mcp/McpConnectProbe.kt"))
        assertFalse("不读响应头", Regex("""\br\.header""").containsMatchIn(src))
        assertFalse("不读响应头集合", Regex("""\.headers\b""").containsMatchIn(src))
        assertFalse("不整段读响应体", Regex("""\.body\?\.(string|bytes)""").containsMatchIn(src))
        assertTrue(
            "discovery 只按上限读公开元数据",
            Regex("""peekBody\(MAX_DISCOVERY_BYTES\)""").containsMatchIn(src),
        )
        assertFalse("不许打印任何东西", Regex("""println\(|android\.util\.Log""").containsMatchIn(src))
    }

    @Test
    fun `门禁_清单层用探针_不用连接代替测试`() {
        val source = codeOnly(readSource("baopu/mcp/McpToolSource.kt"))
        val at = source.indexOf("fun probe(")
        assertTrue("McpToolSource 必须提供 probe", at >= 0)
        val end = source.indexOf("\n    }\n", at)
        val body = source.substring(at, if (end > 0) end else source.length)
        assertTrue("必须委托给探针", body.contains("McpConnectProbe.probe("))
        assertFalse("探针不许造会话", body.contains("clientFactory"))
        assertFalse("探针不许取 token", body.contains("tokenProvider"))

        val ui = codeOnly(readSource("ui/baopu/McpServerScreen.kt"))
        assertTrue("每行都要接上探针", ui.contains("onProbe = { probeServer(view) }"))
        assertTrue(Regex("""\.probe\(target\.id\)""").containsMatchIn(ui))
        val probeFn = ui.indexOf("fun probeServer(")
        assertTrue(probeFn >= 0)
        val probeClose = ui.indexOf("\n    }\n", probeFn)
        assertTrue("要能切出 probeServer 的函数体", probeClose > probeFn)
        val probeBody = ui.substring(probeFn, probeClose)
        assertTrue("切片里必须真的包住探针调用", probeBody.contains(".probe(target.id)"))
        assertFalse("测试连接不许登记服务器", probeBody.contains("addServer"))
        assertFalse("测试连接不许真的连接", Regex("""\.connect\(""").containsMatchIn(probeBody))
    }

    @Test
    fun `门禁_失败原因逐条都有话说_没有兜底连接失败`() {
        val ui = codeOnly(readSource("ui/baopu/McpServerScreen.kt"))
        for (key in listOf(
            "baopu_mcp_probe_bad_url",
            "baopu_mcp_probe_dns",
            "baopu_mcp_probe_tls",
            "baopu_mcp_probe_timeout",
            "baopu_mcp_probe_refused",
            "baopu_mcp_probe_network",
            "baopu_mcp_probe_http",
            "baopu_mcp_probe_auth",
        )) {
            assertTrue("界面必须为 $key 给出具体一句话", ui.contains("R.string.$key"))
        }
    }

    @Test
    fun `门禁_中英文案键一一对应`() {
        val zh = readRes("values/strings_baopu_mcp.xml")
        val en = readRes("values-en/strings_baopu_mcp.xml")
        val keys = Regex("""<string name="([^"]+)">""").findAll(zh).map { it.groupValues[1] }.toList()
        val keysEn = Regex("""<string name="([^"]+)">""").findAll(en).map { it.groupValues[1] }.toList()
        assertEquals("英文镜像必须键对键一致", keys, keysEn)
        assertTrue(keys.contains("baopu_mcp_probe"))
        assertEquals("键不许重复", keys.size, keys.toSet().size)
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/src/main/java").isDirectory) d = d.parentFile
        return d ?: File("").absoluteFile
    }

    private fun readSource(rel: String): String =
        File(repoRoot(), "app/src/main/java/com/example/zhengdao/$rel").readText()

    private fun readRes(rel: String): String =
        File(repoRoot(), "app/src/main/res/$rel").readText()

    private fun codeOnly(src: String): String =
        src.replace("\r\n", "\n").split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
}
