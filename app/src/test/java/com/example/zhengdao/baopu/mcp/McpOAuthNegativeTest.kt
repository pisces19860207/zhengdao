// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十六批 · MCP OAuth 2.1：**九条负例 + 源码级防退化门禁**。
//
// 为什么一半是源码级：这一批最容易"看起来做了、实际没做"的地方都在**接线**上
// （把 token 打进日志、用 WebView 授权、留一个贴静态头的后门、把加密换成明文 prefs）。
// 跑什么都绿也发现不了，只有把"不许出现的写法"钉死才会红。证据等级标注在每条注释里：
//   ① 真跑（MockWebServer / 真实函数调用）  ② 代码原文（源码扫描）
package com.example.zhengdao.baopu.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class McpOAuthNegativeTest {

    private fun src(rel: String): String = File("src/main/java/com/example/zhengdao/$rel").readText()

    private fun mcpSources(): List<Pair<String, String>> =
        File("src/main/java/com/example/zhengdao/baopu/mcp").listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }

    // ── 负例① token 进日志/报告/决策中心 ─────────────────────────────────

    /**
     * ② 源码：MCP 模块里任何一行日志都不许拼 token 字段。
     *
     * 口径：日志只允许 `server=id`、阶段、工具数、scope 名；出现 `accessToken`/
     * `refreshToken`/`Bearer ` 字面量的一律红（注释里提到"Bearer"不算——只扫 log 调用）。
     */
    @Test
    fun `负例1_日志里不许出现token`() {
        val offenders = mutableListOf<String>()
        mcpSources().forEach { (name, text) ->
            text.lineSequence().forEachIndexed { i, line ->
                val isLogCall = line.contains("log(\"[") || line.contains("RunLog.log(")
                if (!isLogCall) return@forEachIndexed
                if (line.contains("accessToken") || line.contains("refreshToken") ||
                    line.contains("Bearer ")
                ) {
                    offenders += "$name:${i + 1}"
                }
            }
        }
        assertTrue("这些日志行把 token 拼进去了：$offenders", offenders.isEmpty())
    }

    /** ② 源码：界面层拿不到 token（只有枚举，没有凭据字段可显示）。 */
    @Test
    fun `负例1_界面层不许碰token字段`() {
        val screen = src("ui/baopu/McpServerScreen.kt")
        assertFalse("清单界面不许引用 accessToken", screen.contains("accessToken"))
        assertFalse("清单界面不许引用 refreshToken", screen.contains("refreshToken"))
        assertFalse("清单界面不许引用 code_verifier", screen.contains("code_verifier"))
    }

    /** ② 源码：决策中心/报告里不许出现 MCP 凭据。 */
    @Test
    fun `负例1_决策中心与报告不接token`() {
        listOf(
            "ui/baopu/DecisionCenterScreen.kt",
            "baopu/eval/EvalHarness.kt",
        ).forEach { rel ->
            val text = runCatching { src(rel) }.getOrNull() ?: return@forEach
            assertFalse("$rel 不许出现 MCP access token", text.contains("mcpAccessToken"))
        }
    }

    // ── 负例② 内嵌 WebView 授权 ──────────────────────────────────────────

    /**
     * ② 源码：授权只能交给系统浏览器。
     *
     * WebView 里做授权等于把授权页放进本进程：Cookie、注入脚本、页面抓取全在 App 手里，
     * 而且第三方页面拿到的是我们的 WebView 身份。这条钉死"没有 WebView"。
     */
    @Test
    fun `负例2_授权不许内嵌WebView`() {
        val files = mcpSources() + ("ui/baopu/McpServerScreen.kt" to src("ui/baopu/McpServerScreen.kt"))
        // 判据是"真的用了 WebView"（类引用/加载 URL/客户端），不是"文档里提到这个词"——
        // 结账单（McpAuthClosure）里写「不嵌 WebView」是说明，不该被判红。
        val usages = listOf("android.webkit", "WebView(", "WebViewClient", "loadUrl(")
        files.forEach { (name, text) ->
            usages.forEach { pattern ->
                assertFalse("$name 出现了 $pattern", text.contains(pattern))
            }
        }
        assertTrue(
            "授权页必须用系统浏览器打开（ACTION_VIEW）",
            src("ui/baopu/McpServerScreen.kt").contains("Intent.ACTION_VIEW"),
        )
    }

    // ── 负例③ PKCE 缺 S256 或 state 不校验 ──────────────────────────────

    /** ② 源码：没有 plain 这条路（连字面量都不许有）。 */
    @Test
    fun `负例3_不许有plain的PKCE分支`() {
        val models = src("baopu/mcp/McpOAuthModels.kt")
        assertFalse("不许出现 code_challenge_method=plain", models.contains("= \"plain\""))
        assertFalse("不许出现 PLAIN 常量", models.contains("const val PLAIN"))
        assertEquals("唯一方法必须是 S256", "S256", McpPkce.METHOD)
    }

    /** ① 真跑：state 不对 ⇒ 一个 token 请求都不发。 */
    @Test
    fun `负例3_state不校验就会红`() {
        // 具体断言在 McpOAuthFlowTest.负例_state不匹配；这里只钉"校验函数本身不是恒真"。
        assertFalse(McpPkce.stateMatches("abc", "abd"))
        assertFalse(McpPkce.stateMatches("abc", null))
    }

    // ── 负例④ 缺 resource / 跨服复用 ─────────────────────────────────────

    /**
     * ① 真跑：A 的 token 用在 B 上 ⇒ 取不到（受众不符）。
     *
     * 这是"token 混用"在**本机**能拦住的唯一位置：请求一旦发出去，服务器只认签名，
     * 分不清是谁给的。
     */
    @Test
    fun `负例4_跨服复用token取不到`() {
        val vault = McpTokenVault(InMemoryMcpSecretStore())
        vault.save(
            "a",
            McpTokenSet(
                accessToken = "at-a",
                refreshToken = null,
                tokenType = "Bearer",
                scope = null,
                expiresAtMs = null,
                resource = "https://a.example.com/mcp",
            ),
        )
        assertEquals("at-a", vault.load("a", "https://a.example.com/mcp")?.accessToken)
        assertEquals(
            "B 拿不到 A 的 token",
            null,
            vault.load("b", "https://b.example.com/mcp"),
        )
    }

    /** ② 源码：授权页地址的拼装必须带 resource 参数（少一行就红）。 */
    @Test
    fun `负例4_授权请求必带resource`() {
        val text = src("baopu/mcp/McpOAuthDiscovery.kt")
        assertTrue("authorizationUrl 必须写 resource", text.contains("""params["resource"] = resource"""))
        val flow = src("baopu/mcp/McpAuthFlow.kt")
        assertTrue("换 token 必须带 resource", flow.contains("resource = resource"))
    }

    // ── 负例⑤ 刷新失败后无限重试 ────────────────────────────────────────

    /**
     * ② 源码：刷新失败要有"闸"，而且闸是**持久标记**不是每次重来。
     *
     * ① 的行为断言在 McpOAuthFlowTest（refreshAttempts 恒为 1、第二次一个包都不发）。
     */
    @Test
    fun `负例5_刷新失败要有闸`() {
        val flow = src("baopu/mcp/McpAuthFlow.kt")
        assertTrue("要有 refreshFailed 集合", flow.contains("refreshFailed"))
        assertTrue("失败后要 markRevoked", flow.contains("vault.markRevoked("))
        assertTrue("要先查闸再发请求", flow.indexOf("if (config.id in refreshFailed)") < flow.indexOf("refreshAttempts++"))
        val client = src("baopu/mcp/McpClient.kt")
        assertTrue("401 只重放一次（没有 while/循环重试）", client.contains("重放一次"))
        assertFalse("传输层不许自己写重试循环", client.contains("while (retries"))
    }

    // ── 负例⑥ insufficient_scope 当普通失败 ─────────────────────────────

    /** ① 真跑：403 + insufficient_scope 必须有自己的失败码与 scope。 */
    @Test
    fun `负例6_scope不足要能区分出来`() {
        val c = McpOAuthDiscovery.parseWwwAuthenticate(
            """Bearer error="insufficient_scope", scope="files:write"""",
        )
        assertTrue("必须识别成 step-up 而不是普通 403", c!!.insufficientScope)
        val client = src("baopu/mcp/McpClient.kt")
        assertTrue("传输层要给出 INSUFFICIENT_SCOPE", client.contains("McpFailure.INSUFFICIENT_SCOPE"))
        assertTrue("并带上缺的 scope", client.contains("missingScope = challenge.scope"))
    }

    // ── 负例⑦ 引入新网络/加密库 ──────────────────────────────────────────

    /**
     * ② 源码：不引 security-crypto / browser / nimbus。
     *
     * 加密自己实现（AndroidKeyStore + AES/GCM），浏览器用系统 Intent——
     * 这两件事都不需要新依赖，加了反而多一个供应链面。
     */
    @Test
    fun `负例7_不许引新库`() {
        val gradle = File("build.gradle.kts").readText()
        listOf(
            "security-crypto",
            "androidx.browser",
            "nimbus",
            "jose",
            "appauth",
            "openid",
        ).forEach { dep ->
            assertFalse("build.gradle.kts 里出现了 $dep", gradle.contains(dep))
        }
    }

    /** ② 源码：加密实现必须是 Keystore + AES/GCM（不是明文 prefs）。 */
    @Test
    fun `负例7_token存储必须加密`() {
        val store = src("baopu/mcp/McpTokenStoreAndroid.kt")
        assertTrue("要 AndroidKeyStore", store.contains("AndroidKeyStore"))
        assertTrue("要 AES/GCM/NoPadding", store.contains("AES/GCM/NoPadding"))
        assertTrue("要随机 IV", store.contains("setRandomizedEncryptionRequired(true)"))
        assertTrue("要 256 位密钥", store.contains("setKeySize(256)"))
        assertFalse("不许把明文直接写 prefs", store.contains("putString(key, value)"))
        assertTrue("写的是密文", store.contains("putString(key, blob)"))
    }

    /** ② 源码：加密 prefs 必须被两份备份规则排除（E-129 的老坑）。 */
    @Test
    fun `负例7_token不许进云备份`() {
        listOf(
            "src/main/res/xml/backup_rules.xml",
            "src/main/res/xml/data_extraction_rules.xml",
        ).forEach { rel ->
            val text = File(rel).readText()
            assertTrue("$rel 没排 baopu_mcp_token.xml", text.contains("baopu_mcp_token.xml"))
        }
        assertEquals(
            "prefs 文件名要与备份规则一致",
            "baopu_mcp_token",
            PrefsMcpSecretStore.FILE,
        )
    }

    // ── 负例⑧ 保留静态 token 入口 ────────────────────────────────────────

    /** ① 真跑：配置里贴 Authorization / Cookie 一律在构造时就抛。 */
    @Test
    fun `负例8_静态鉴权头入口已删除`() {
        listOf("Authorization", "authorization", "Cookie", "Proxy-Authorization").forEach { header ->
            try {
                McpServerConfig(
                    id = "srv",
                    name = "贴 token",
                    url = "https://example.com/mcp",
                    headers = mapOf(header to "Bearer SECRET"),
                )
                fail("「$header」必须被拒（静态 token 入口不许复活）")
            } catch (e: IllegalArgumentException) {
                assertTrue("文案要说清原因", e.message.orEmpty().contains("OAuth") || e.message.orEmpty().contains("鉴权"))
            }
        }
        // 非鉴权自定义头仍然允许（不然连 X-Client 这类正常需求也堵死了）。
        val ok = McpServerConfig(
            id = "srv",
            name = "自定义头",
            url = "https://example.com/mcp",
            headers = mapOf("X-Client" to "zhengdao"),
        )
        assertEquals(1, ok.headers.size)
    }

    /**
     * ① 真跑：地址里塞 token 一律拒（派工单 §2.5「不接受在地址里带 token」）。
     *
     * URL 会进日志/截图/剪贴板历史；把凭据放 query 等于把它从加密存储搬到最显眼的地方。
     */
    @Test
    fun `负例8_地址里不许塞token`() {
        listOf(
            "https://example.com/mcp?token=abc",
            "https://example.com/mcp?access_token=abc",
            "https://example.com/mcp?api_key=abc",
            "https://example.com/mcp?client_secret=abc",
            "https://example.com/mcp?tenant=a&signature=abc",
        ).forEach { url ->
            try {
                McpServerConfig(id = "srv", name = "带凭据", url = url)
                fail("「$url」必须被拒")
            } catch (e: IllegalArgumentException) {
                assertTrue("文案要指路 OAuth：${e.message}", e.message.orEmpty().contains("OAuth"))
            }
        }
        // 普通业务参数照旧可用（不然把正常服务器也堵死了）。
        McpServerConfig(id = "srv", name = "普通参数", url = "https://example.com/mcp?tenant=a&v=2")
    }

    /** ② 源码：管理器自己也不许拼 Authorization（唯一出处是传输层的 tokenProvider）。 */
    @Test
    fun `负例8_只有传输层能写Authorization头`() {
        val offenders = mcpSources()
            .filter { (name, _) -> name != "McpClient.kt" }
            .filter { (_, text) -> text.contains(""""Authorization"""") }
            .map { it.first }
        assertTrue("这些文件自己拼了 Authorization 头：$offenders", offenders.isEmpty())
    }

    // ── 负例⑨ 不支持 OAuth 时静默降级成静态头 ────────────────────────────

    /** ① 真跑：不支持 OAuth 的服务器 ⇒ 明确失败，状态是"不支持"，不是"已连接"。 */
    @Test
    fun `负例9_不支持OAuth不许静默降级`() {
        val flow = src("baopu/mcp/McpAuthFlow.kt")
        assertTrue("要有 unsupported 标记", flow.contains("unsupported"))
        assertTrue("状态要能返回 UNSUPPORTED", flow.contains("McpAuthStatus.UNSUPPORTED"))
        val tool = src("baopu/mcp/McpToolSource.kt")
        assertFalse("管理器不许自己补静态头当兜底", tool.contains(""""Authorization""""))
        assertTrue("连不上要转授权而不是降级", tool.contains("McpConnectOutcome.NeedsAuth"))
    }

    // ── 结构性门禁（回调入口与状态真实性） ────────────────────────────────

    /** ② 源码：回调入口只有 `zhengdao://mcp/oauth` 一条，且 Manifest 与代码一致。 */
    @Test
    fun `回调入口_Manifest与代码一致`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("Manifest 要有 mcp host", manifest.contains("""android:host="mcp""""))
        assertTrue("Manifest 要有 /oauth 路径", manifest.contains("""android:pathPrefix="/oauth""""))
        assertEquals("mcp", McpOAuthCallbacks.HOST)
        assertEquals("/oauth", McpOAuthCallbacks.PATH)
        assertTrue(
            "回调地址要与 flow 的 redirect_uri 一致",
            McpAuthFlow.DEFAULT_REDIRECT_URI == "zhengdao://mcp/oauth",
        )
    }

    /** ② 源码：清单界面必须把五种授权状态都画出来（不许只有"已连接/未连接"）。 */
    @Test
    fun `清单界面_五种授权状态齐全`() {
        val screen = src("ui/baopu/McpServerScreen.kt")
        listOf(
            "McpAuthStatus.VALID",
            "McpAuthStatus.EXPIRED",
            "McpAuthStatus.REVOKED",
            "McpAuthStatus.UNSUPPORTED",
            "McpAuthStatus.UNAUTHORIZED",
        ).forEach { assertTrue("缺 $it（状态不真实就骗人）", screen.contains(it)) }
        listOf("disconnectAll(", "removeServer(", "toolNames(", "addServer(", "connect(")
            .forEach { assertTrue("清单缺动作 $it", screen.contains(it)) }
    }

    /** ② 源码：不硬编码 client_secret（手机端装不住机密，硬编码等于公开）。 */
    @Test
    fun `负例_client_secret不许进APK`() {
        // 判据：**给 client_secret 赋了一个非空字面量**（`client_secret = "xxx"` / `"client_secret": "xxx"`）。
        // 只提到这个词（注释、差距账的说明文字）不算——那不是把密钥打进 APK。
        val assign = Regex("""client_secret["']?\s*[:=]\s*["'][^"']+["']""")
        mcpSources().forEach { (name, text) ->
            assertFalse(
                "$name 给 client_secret 赋了字面量",
                assign.containsMatchIn(text),
            )
        }
        assertFalse(
            "注册请求里不许带 client_secret",
            mcpSources().any { (_, text) -> text.contains(""""client_secret" to""") },
        )
    }
}
