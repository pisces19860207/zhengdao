// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十六批 · MCP OAuth 2.1：**215 差距账的结账单**（逐项对账）。
//
// 215 留下的 [McpAuthGap] 是"现状 vs OAuth 要求"的差距清单（只列不实现）。本批按它
// 逐项接上，这个文件是**回执**：每一项现在落在哪个文件/哪个符号、由哪条用例证明。
//
// 为什么单独一份而不是改 215 那份：差距账是**当时的事实**（215 那天确实没有），
// 改了它就把"我们曾经缺什么"抹掉了。回执单独记账，两边都能查。
//
// 门禁（McpAuthClosureTest）钉三件事：
// ① 覆盖必须与差距账**逐项一致**（不增不减，改了差距账而没结账就红）；
// ② 每条实现点指向的文件必须存在、且里面真有那个符号（防"写了文件路径但代码删了"）；
// ③ 每条证明指向的用例名必须真出现在测试源码里（防"报告里写了测试，实际没写"）。
package com.example.zhengdao.baopu.mcp

/**
 * 一条结账记录。
 *
 * @param topic 与 [McpAuthGap.ITEMS] 的 `topic` 逐字一致（门禁按它配对）
 * @param impl 实现点，`相对 app/src/main/java/com/example/zhengdao/ 的路径::符号`
 * @param proofs 证明它的用例名（测试函数名，中文反引号里的那串）
 * @param note 口径变化 / 遗留风险（写给人看）
 */
internal data class McpAuthClosureItem(
    val topic: String,
    val impl: List<String>,
    val proofs: List<String>,
    val note: String = "",
)

internal object McpAuthClosure {

    /** 顺序与 [McpAuthGap.ITEMS] 一致（门禁按顺序比对 topic）。 */
    val ITEMS: List<McpAuthClosureItem> = listOf(
        McpAuthClosureItem(
            topic = "授权服务器发现",
            impl = listOf(
                "baopu/mcp/McpOAuthDiscovery.kt::parseWwwAuthenticate",
                "baopu/mcp/McpOAuthDiscovery.kt::resourceMetadataUrls",
                "baopu/mcp/McpOAuthDiscovery.kt::parseResourceMetadata",
                "baopu/mcp/McpOAuthClient.kt::discover",
                "baopu/mcp/McpOAuthClient.kt::probeChallenge",
            ),
            proofs = listOf(
                "挑战_解析resource_metadata与scope",
                "PRM_地址_路径插入优先_根地址兜底",
                "全流程_发现_注册_S256_换token_带Bearer连上",
            ),
            note = "三条路都走：401 挑战里的 resource_metadata → well-known PRM（路径插入+根）" +
                "→ 回落 MCP origin 上的 AS 元数据。全失败 ⇒ 判「不支持现代授权」，不降级。",
        ),
        McpAuthClosureItem(
            topic = "客户端注册",
            impl = listOf(
                "baopu/mcp/McpOAuthClient.kt::register",
                "baopu/mcp/McpModels.kt::McpServerConfig",
                "baopu/mcp/McpServerStore.kt::encode",
            ),
            proofs = listOf(
                "全流程_发现_注册_S256_换token_带Bearer连上",
                "负例_client_secret不许进APK",
            ),
            note = "优先 RFC 7591 动态注册（token_endpoint_auth_method=none，公共客户端）；" +
                "服务器没有 registration_endpoint ⇒ 要求填预注册 client_id（配置里可存，非机密）。" +
                "**client_secret 一律不要**：装不住，硬编码等于公开。",
        ),
        McpAuthClosureItem(
            topic = "resource 参数",
            impl = listOf(
                "baopu/mcp/McpOAuthModels.kt::McpResource",
                "baopu/mcp/McpOAuthDiscovery.kt::authorizationUrl",
                "baopu/mcp/McpAuthFlow.kt::refresh",
            ),
            proofs = listOf(
                "resource_规范化_去端口去query去尾斜杠",
                "授权URL_必带S256_state_resource",
                "负例4_授权请求必带resource",
                "负例4_跨服复用token取不到",
            ),
            note = "resource = 规范化后的 MCP 服务器 URI；取 token、刷新、用 token 三处都核。" +
                "受众不符 ⇒ 当没有 token（不删，它可能属于另一台）。",
        ),
        McpAuthClosureItem(
            topic = "access token 的获取与使用",
            impl = listOf(
                "baopu/mcp/McpOAuthClient.kt::exchangeCode",
                "baopu/mcp/McpClient.kt::StreamableTransport",
                "baopu/mcp/McpAuthFlow.kt::providerFor",
            ),
            proofs = listOf(
                "全流程_发现_注册_S256_换token_带Bearer连上",
                "PKCE_S256_官方测试向量",
            ),
            note = "「每个请求带头」的现成机制原样复用：token 由 [McpTokenProvider] 每次现取，" +
                "传输层不认识 OAuth。授权码流程带 S256 challenge（RFC 7636 官方向量已验证）。",
        ),
        McpAuthClosureItem(
            topic = "refresh token 刷新",
            impl = listOf(
                "baopu/mcp/McpAuthFlow.kt::refresh",
                "baopu/mcp/McpClient.kt::request",
                "baopu/mcp/McpTokenVault.kt::markRevoked",
            ),
            proofs = listOf(
                "401_刷新一次_成功_只重放一次",
                "负例_刷新失败_明确报错_不循环不降级",
                "负例5_刷新失败要有闸",
            ),
            note = "401 ⇒ 刷新一次 → 重放一次；刷新失败 ⇒ 打「已撤销」标记 + 明确报错，" +
                "之后**一个包都不再发**（闸是持久标记，不是每次重来）。不静默降级成匿名。",
        ),
        McpAuthClosureItem(
            topic = "scope challenge 与 step-up 授权",
            impl = listOf(
                "baopu/mcp/McpClient.kt::httpError",
                "baopu/mcp/McpModels.kt::McpFailure",
                "baopu/mcp/McpToolSource.kt::connect",
            ),
            proofs = listOf(
                "负例_insufficient_scope_要stepup不是普通失败",
                "负例6_scope不足要能区分出来",
            ),
            note = "403 + `insufficient_scope` 有自己的失败码与 `missingScope`；管理器拿它当" +
                "scope 参数重新出授权链接（step-up），文案说清缺哪个权限。",
        ),
        McpAuthClosureItem(
            topic = "手机端无浏览器回调",
            impl = listOf(
                "baopu/mcp/McpAuthFlow.kt::DEFAULT_REDIRECT_URI",
                "baopu/mcp/McpOAuthCallback.kt::McpOAuthCallbacks",
                "ui/baopu/McpServerScreen.kt::openAuthorization",
                "IncomingIntentRouter.kt::routeMcpOAuth",
            ),
            proofs = listOf(
                "回调入口_Manifest与代码一致",
                "负例2_授权不许内嵌WebView",
                "负例_state不匹配_拒绝且不存token",
            ),
            note = "**拍板变了**：215 建议环回端口，本批按派工单 §2.3 用自定义 scheme " +
                "`zhengdao://mcp/oauth` + 系统浏览器。理由：环回要在 App 里起本地 HTTP 服务（端口占用/防火墙/被杀进程都出问题），" +
                "而 RFC 8252 对自定义 scheme 的劫持风险，由 PKCE + state + 一次性 code 抵消（劫持者拿不到 verifier）。",
        ),
        McpAuthClosureItem(
            topic = "token 存放与泄漏面",
            impl = listOf(
                "baopu/mcp/McpTokenVault.kt::McpTokenVault",
                "baopu/mcp/McpTokenStoreAndroid.kt::PrefsMcpSecretStore",
                "baopu/mcp/McpTokenStoreAndroid.kt::McpKeystoreCipher",
            ),
            proofs = listOf(
                "负例_日志不含token",
                "负例_toString不含token",
                "负例1_日志里不许出现token",
                "负例7_token存储必须加密",
                "负例7_token不许进云备份",
            ),
            note = "**等价 EncryptedSharedPreferences**：AndroidKeyStore 里不可导出的 AES-256 " +
                "密钥 + 每条值 AES/GCM（随机 IV），密文进独立私有 prefs，并被两份备份规则按名排除。" +
                "不引 security-crypto（已进维护模式，且本批不许引新加密库）。",
        ),
    )

    /** 按 topic 查（报告渲染用）。 */
    fun of(topic: String): McpAuthClosureItem? = ITEMS.firstOrNull { it.topic == topic }
}
