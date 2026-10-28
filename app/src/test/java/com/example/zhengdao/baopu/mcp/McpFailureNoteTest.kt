// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十九批 B 段第 4 条（257 审计 A-P2-14）的复现用例。
//
// 缺陷原文：MCP 的两个 HTTP 客户端把「错误体读不动／不是合法 JSON／id 对不上／压根没有体／
// 声明体积超限」统统收成同一个 null 或同一个空串——于是用户只看到「HTTP 400」，授权侧只
// 看到「返回了空响应」（其实是被拒读），日志里连「服务器确实解释过」这件事都不存在。
//
// 派工单改判据（安全优先）：一条线索只许含 ①HTTP 码 ②RFC 6749 §5.2 的**白名单**枚举值
// ③响应体字节数＋sha256 前 8 位 ④失败环节分类。**响应体原文、请求头、URL、token、账号
// 标识都不许进来**；也不做"原文脱敏"。下面每条都用金丝雀串盯住"原文进不了线索"。
//
// 两个客户端都吃真实 OkHttp + MockWebServer（本仓 mcp 测试的既有做法），所以这些都是
// 行为级用例，不是源闸——只有"接线还在不在"那一条用源码钉。
package com.example.zhengdao.baopu.mcp

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.Collections

class McpFailureNoteTest {

    private lateinit var server: MockWebServer
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun cfg(): McpServerConfig = McpServerConfig(
        id = "srv",
        name = "假 server",
        url = "http://127.0.0.1:${server.port}/mcp",
    )

    private fun client(): McpClient = McpClient(cfg(), OkHttpClient(), log = { logs.add(it) })

    private fun oauth(): McpOAuthClient = McpOAuthClient(OkHttpClient(), log = { logs.add(it) })

    private val metadata: McpAuthServerMetadata
        get() = McpAuthServerMetadata(
            issuer = null,
            authorizationEndpoint = "http://127.0.0.1:${server.port}/authorize",
            tokenEndpoint = "http://127.0.0.1:${server.port}/token",
            registrationEndpoint = null,
            scopesSupported = emptyList(),
            codeChallengeMethods = null,
            tokenAuthMethods = null,
            grantTypes = null,
        )

    private val registration = McpClientRegistration(
        clientId = "cid-public",
        redirectUris = emptyList(),
        tokenAuthMethod = "none",
    )

    /** 线索行 = 带「[mcp] 线索」固定标记的那一行；金丝雀只许出现在它以外。 */
    private fun noteLines(): List<String> = logs.filter { it.contains("[mcp] 线索 ") }

    private fun refreshOnce(body: String, status: Int): String {
        server.enqueue(
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body),
        )
        val res = runCatching {
            oauth().refresh(metadata, registration, "rt-轮换前的旧值", "https://example.com/mcp", 1_000_000L)
        }
        val err = res.exceptionOrNull()
        assertTrue("这条响应必须失败（真换到 token 说明读侧判据没生效）", err != null)
        return (err as? McpAuthException)?.userMessage ?: err.toString()
    }

    // ── 判据内核 ──────────────────────────────────────────────────────────

    /**
     * ②白名单：只有 RFC 里写死的枚举值才允许被记录。
     *
     * **改前表现**：没有白名单这个东西（也没有线索），非标准值一旦被拼进文案就是泄漏面。
     */
    @Test
    fun `白名单_标准固定串才记_其余一个字不记`() {
        assertEquals("invalid_grant", McpHttpNotes.oauthErrorCode("invalid_grant"))
        assertEquals("server_error", McpHttpNotes.oauthErrorCode("  server_error  "))
        assertNull("拿不准就不记", McpHttpNotes.oauthErrorCode("token_expired"))
        assertNull("像是凭据的值不记", McpHttpNotes.oauthErrorCode("sk-abc123"))
        assertNull(McpHttpNotes.oauthErrorCode("invalid_grant_for_bob=rt-77aa"))
        assertNull(McpHttpNotes.oauthErrorCode(""))
        assertNull(McpHttpNotes.oauthErrorCode(null))
        // 白名单本身必须是"干净形状"：一项里都不许带能拼出 URL／头部／键值的字符。
        McpHttpNotes.STANDARD_ERROR_CODES.forEach {
            assertTrue("白名单项不干净：$it", it.matches(Regex("^[a-z_]{4,40}$")))
        }
    }

    /** ③指纹：可对账、不可逆、改一个字就变。 */
    @Test
    fun `digest8_同一份坏响应两次一致_改一个字就变`() {
        assertEquals("sha256(\"abc\") 的前 8 位", "ba7816bf", McpHttpNotes.digest8("abc"))
        assertEquals(McpHttpNotes.digest8("同一份坏响应"), McpHttpNotes.digest8("同一份坏响应"))
        assertFalse(McpHttpNotes.digest8("同一份坏响应") == McpHttpNotes.digest8("同一份坏响应 "))
        assertTrue(McpHttpNotes.digest8("x").matches(Regex("^[0-9a-f]{8}$")))
        assertFalse("指纹不许带出原文", McpHttpNotes.digest8("rt-CANARY-77aa").contains("CANARY"))
    }

    /** ④分类：没有体的时候不许对用户说"服务器给了说明但没采用"——那是猜。 */
    @Test
    fun `线索行只装四样_没取到体时不给用户猜`() {
        val got = McpFailureNote("调用工具", "错误体不是合法 JSON", httpStatus = 400, bytes = 18L, digest8 = "abcd1234")
        val line = got.logLine("srv")
        assertTrue(line, line.contains("[mcp] 线索 srv 调用工具"))
        assertTrue(line, line.contains("HTTP 400") && line.contains("18 字节") && line.contains("sha256 abcd1234"))
        assertFalse("线索行不许带 URL：$line", line.contains("://"))
        assertTrue("取到了体才说「没采用」", got.hint.orEmpty().contains("没采用：错误体不是合法 JSON"))
        val none = McpFailureNote("连接", "错误体读不出（连接中断）")
        assertNull("压根没取到体 ⇒ 不给用户那句猜", none.hint)
        assertFalse(none.logLine("srv").contains("HTTP"))
        assertFalse(none.logLine("srv").contains("字节"))
    }

    // ── McpClient：非 2xx 错误体的四种「坏了」──────────────────────────────

    /**
     * ① 真跑：HTTP 400 + Content-Type json + 半截 JSON。
     *
     * **改前表现**：`McpJsonRpc.parse` 抛异常 ⇒ `catch (e: Exception) return null` ⇒ 用户只有
     * 「…返回 HTTP 400」一句，日志里零线索（这条断言写不出来）。
     */
    @Test
    fun `真请求_错误体不是合法JSON_说得出环节_金丝雀不进日志`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"sk-CANARY-9d4f7a"),
        )
        try {
            client().connect()
            fail("400 必须明确失败")
        } catch (e: McpException) {
            assertTrue("要说得出「服务器解释过但没采用」：${e.userMessage}", e.userMessage.contains("没采用：错误体不是合法 JSON"))
            assertFalse("异常文案不带原文：${e.userMessage}", e.userMessage.contains("CANARY"))
        }
        val line = noteLines().firstOrNull().orEmpty()
        assertTrue("日志里要有那条线索：$logs", line.contains("HTTP 400") && line.contains("sha256 "))
        assertFalse("线索里不许有原文", line.contains("CANARY"))
        assertFalse("任何日志都不许有原文", logs.any { it.contains("CANARY") })
        assertFalse("任何日志都不许带 URL", logs.any { it.contains("://") })
    }

    /**
     * ① 真跑：错误体是**合法 JSON**、但 id 对不上——改前与「不是合法 JSON」同一个 null。
     */
    @Test
    fun `真请求_id对不上_与不是合法JSON分得开`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":424242,"error":{"code":-32000,"message":"别的请求"}}"""),
        )
        try {
            client().connect()
            fail("400 必须明确失败")
        } catch (e: McpException) {
            assertTrue("分开说：${e.userMessage}", e.userMessage.contains("没采用：错误体是别的请求的（id 对不上）"))
        }
        val line = noteLines().firstOrNull().orEmpty()
        assertTrue(line, line.contains("id 对不上") && line.contains("sha256 "))
        assertFalse("不是 JSON 那句不许串到这条", line.contains("不是合法 JSON"))
    }

    /** ① 真跑：代理回 HTML 错误页 ⇒「压根没给 JSON」与「给了但坏掉」也要分得开。 */
    @Test
    fun `真请求_错误体不是json_只进日志_异常文案不加猜`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>nginx 502 sk-CANARY-HTML</html>"),
        )
        try {
            client().connect()
            fail("500 必须明确失败")
        } catch (e: McpException) {
            assertFalse("没取到可呈现的东西就不说「没采用」：${e.userMessage}", e.userMessage.contains("没采用"))
            assertFalse("异常文案不许带 URL/原文：${e.userMessage}", e.userMessage.contains("CANARY"))
        }
        val line = noteLines().firstOrNull().orEmpty()
        assertTrue("日志要说明是 Content-Type 不对：$logs", line.contains("Content-Type 没声明 json") && line.contains("HTTP 500"))
        assertFalse("原文不进日志", logs.any { it.contains("CANARY") })
    }

    // ── McpOAuthClient：读体三态 ──────────────────────────────────────────

    /**
     * ① 真跑：token 端点回 200 但体积超限。
     *
     * **改前表现**：`readCapped` 在 `declared > MAX_BODY_BYTES` 时 `return ""` ⇒
     * `executeJson` 把"被拒读"报成「刷新 token 返回了空响应」——「坏了」冒充「没有」。
     */
    @Test
    fun `刷新_响应体超限_不再冒充空响应`() {
        val huge = "{\"access_token\":\"" + "x".repeat(300 * 1024) + "\"}"
        val msg = refreshOnce(huge, 200)
        assertTrue("要说得出是被拒读：$msg", msg.contains("超过 256 KB 上限，已拒读"))
        assertFalse("不许再冒充空响应：$msg", msg.contains("空响应"))
        val line = noteLines().firstOrNull().orEmpty()
        assertTrue(line, line.contains("HTTP 200") && line.contains("字节"))
        assertFalse("超限体一个字都不进日志", logs.any { it.contains("access_token") })
    }

    /** ① 真跑：400 + §5.2 标准错误码 ⇒ 记码、记字节数与指纹，不记原文。 */
    @Test
    fun `刷新_400_记标准错误码_不记原文`() {
        val msg = refreshOnce("""{"error":"invalid_grant","error_description":"rt-CANARY-77aa 已被轮换"}""", 400)
        val line = noteLines().firstOrNull().orEmpty()
        assertTrue("要带上 HTTP 码：$line", line.contains("HTTP 400"))
        assertTrue("要带上白名单错误码：$line", line.contains("error=invalid_grant"))
        assertTrue("要能对账：$line", line.contains("字节") && line.contains("sha256 "))
        assertFalse("线索行不许有原文", line.contains("CANARY"))
        // 216 批既定口径（错误只取 error/error_description）本批**没动**：异常文案仍带 description。
        // 是否把它也收进白名单，见报告 §7 的 259-04 待评估项。
        assertTrue(msg.contains("invalid_grant"))
    }

    /** ① 真跑：非标准的 error 值（可能是服务器回显的键值）一个字都不记。 */
    @Test
    fun `刷新_400_非标准错误码_一个字不记`() {
        refreshOnce("""{"error":"made_up_error=rt-CANARY-88bb","error_description":"奇怪的回显"}""", 400)
        val line = noteLines().firstOrNull().orEmpty()
        assertTrue("HTTP 码仍然要记：$line", line.contains("HTTP 400"))
        assertFalse("白名单外的值不记：$line", line.contains("made_up_error"))
        assertFalse("回显里的凭据不记：$line", line.contains("CANARY"))
        assertFalse("任何线索行都不许有它", logs.any { it.contains("CANARY") })
    }

    /**
     * ① 真跑：换 token **成功**时一个字的线索都不许多。
     *
     * 反向闸：指纹口径哪天被"顺手"扩到成功响应上，token 体的字节数与指纹就成了能被比对的
     * 旁路——这条立刻红。
     */
    @Test
    fun `成功响应一个字都不记_好体也不进指纹`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"token_type\":\"Bearer\",\"access_token\":\"at-CANARY-GOOD\",\"expires_in\":3600}"),
        )
        val set = oauth().refresh(metadata, registration, "rt-CANARY-OLD", "https://example.com/mcp", 1_000_000L)
        assertEquals("at-CANARY-GOOD", set.accessToken)
        assertTrue("成功路径不许有线索行：$logs", noteLines().isEmpty())
        assertFalse("token 原文不许进日志", logs.any { it.contains("CANARY") })
    }

    // ── 接线（源码钉） ────────────────────────────────────────────────────

    /**
     * ② 源码：两处读数都必须把线索**交出去**（返回值），不许只在原地打一行日志就了事
     * （派工单 §3 负例 4：只加日志不返回值 ⇒ 调用方仍无法区分）。
     */
    @Test
    fun `接线_两个客户端都把线索交给调用方`() {
        val client = File("src/main/java/com/example/zhengdao/baopu/mcp/McpClient.kt").readText()
        val oauthSrc = File("src/main/java/com/example/zhengdao/baopu/mcp/McpOAuthClient.kt").readText()
        assertTrue("MCP 客户端读数要返回分类线索", client.contains("Pair<String?, McpFailureNote?>"))
        assertTrue("调用方拿线索记日志", client.contains("withheld?.let { log(it.logLine(config.id)) }"))
        assertTrue("调用方拿线索补文案", client.contains("detail = usable ?: withheld?.hint"))
        assertFalse("改前那种一律 return null 的收口不许回来", client.contains("if (text.isBlank()) return null"))
        assertFalse("Content-Type 不对也不许静默", client.contains("if (!contentType.contains(\"json\")) return null"))
        assertTrue("OAuth 读体三态化", oauthSrc.contains("private fun readCapped(r: Response, stage: String): Pair<String?, McpFailureNote?>"))
        assertTrue("坏体不再冒充空串", oauthSrc.contains("String(bytes, Charsets.UTF_8) to null"))
        assertFalse("超限一律空串的写法不许回来", oauthSrc.contains("if (declared > MAX_BODY_BYTES) return \"\""))
        assertTrue("非 2xx 的线索带白名单码", oauthSrc.contains("oauthError = McpHttpNotes.oauthErrorCode(code)"))
    }
}
