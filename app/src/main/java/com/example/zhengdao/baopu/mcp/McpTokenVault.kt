// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十六批 · MCP OAuth 2.1：**token 的唯一住处**（按服务器隔离 + 受众校验 + 清除）。
//
// 这条是本批最硬的红线（用户原话：「令牌只进 EncryptedSharedPreferences，永不进日志/报告/台账」）：
//   · 本文件的编码结果**只**交给 [McpSecretStore]；Android 实现（McpTokenStoreAndroid）
//     用 AndroidKeyStore 里的 AES-GCM 密钥加密后再写进私有 SharedPreferences；
//   · 本文件不打印、不 toString 任何 token；日志只记「存了/清了/受众不符」；
//   · 按 serverId 分键：A 的 token 物理上不在 B 的键下（负例④）；
//   · 读回来先核 `resource`：token 的受众不是这台服务器就**当没有**（不"凑合着用"）。
package com.example.zhengdao.baopu.mcp

import org.json.JSONObject

/**
 * 一条「键 → 密文」存储（唯一目的是让 vault 逻辑在纯 JVM 里可测）。
 *
 * 生产实现：[PrefsMcpSecretStore]（AES-GCM 加密后写私有 prefs）；
 * 单测实现：内存假件（同时用来断言"写进去的东西里没有 token 明文"这条**做不到**——
 * 明文在这里是正常的，加密在实现层。所以门禁改成断言实现层用 AES/GCM + 备份排除）。
 */
internal interface McpSecretStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/** 内存实现（单测/无 Android 环境）；[dump] 只给门禁看，生产不用。 */
internal class InMemoryMcpSecretStore : McpSecretStore {
    private val map = LinkedHashMap<String, String>()

    @Synchronized
    override fun read(key: String): String? = map[key]

    @Synchronized
    override fun write(key: String, value: String) {
        map[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        map.remove(key)
    }

    @Synchronized
    override fun keys(): Set<String> = map.keys.toSet()

    @Synchronized
    fun dump(): Map<String, String> = LinkedHashMap(map)
}

/**
 * token 保险柜（纯逻辑）。
 *
 * 三个动作：存（[save]）、取（[load]）、清（[clear] / [clearAll]）。
 * 状态判定（[status]）把「过期」「被撤销」「从没授权」分开——UI 要显示真实状态。
 */
internal class McpTokenVault(
    private val store: McpSecretStore,
    private val log: (String) -> Unit = {},
) {

    /**
     * 存一份 token（整份覆盖该服务器的旧 token）。
     *
     * 写失败（Keystore 不可用/prefs 异常）**不**抛给调用方：本次会话仍可用内存里的
     * token 干活，但下次启动要重新授权。日志只说"没存住"，不带内容。
     */
    fun save(serverId: String, tokens: McpTokenSet) {
        val json = encode(tokens)
        try {
            store.write(tokenKey(serverId), json)
            store.remove(revokedKey(serverId)) // 新授权成功 ⇒ 撤销标记作废
            log("[mcp] server=$serverId 授权已保存（${tokens.scope ?: "默认 scope"}）")
        } catch (e: Exception) {
            log("[mcp] server=$serverId 授权保存失败（${e.javaClass.simpleName}）：本次会话可用，重启后需重新授权")
        }
    }

    /**
     * 取一份 token。
     *
     * @param expectedResource 本服务器的规范 URI；**不等于 token 里的 resource 就返回 null**
     *   （负例④：A 的 token 不许用在 B 上）。
     * @return null = 没有/坏了/受众不符（调用方按"未授权"处理）
     */
    fun load(serverId: String, expectedResource: String?): McpTokenSet? {
        val raw = try {
            store.read(tokenKey(serverId))
        } catch (e: Exception) {
            log("[mcp] server=$serverId 读取授权失败（${e.javaClass.simpleName}）")
            return null
        } ?: return null
        val tokens = decode(raw) ?: run {
            // 解不开（换机/密钥失效/手改）⇒ 当作没有，并清掉，避免每次启动都白试一次。
            log("[mcp] server=$serverId 的授权数据不可读，已清除")
            runCatching { store.remove(tokenKey(serverId)) }
            return null
        }
        if (expectedResource != null && !McpResource.sameAudience(tokens.resource, expectedResource)) {
            // 受众不符：这台服务器不是该 token 的听众。**不删**（它可能属于另一台服务器，
            // 只是键被搬过来了），只当没有——删了会让原服务器的授权凭空消失。
            log("[mcp] server=$serverId 的 token 受众不符（resource 不匹配），按未授权处理")
            return null
        }
        return tokens
    }

    /** 断开/删除：清掉该服务器的 token 与撤销标记。 */
    fun clear(serverId: String) {
        runCatching { store.remove(tokenKey(serverId)) }
        runCatching { store.remove(revokedKey(serverId)) }
        log("[mcp] server=$serverId 的授权已清除")
    }

    /**
     * 存「授权会话」（端点 + client_id；**不含任何 token**）。
     *
     * 存在的唯一理由：进程重启后还能用**当初注册的那个 client_id** 去刷新——
     * 换一个 client 去刷新，授权服务器会按 invalid_grant 拒掉，用户就得重新授权。
     */
    fun saveSession(serverId: String, session: McpAuthSession) {
        val json = JSONObject()
            .put("v", FORMAT_VERSION)
            .put("iss", session.metadata.issuer ?: JSONObject.NULL)
            .put("authz", session.metadata.authorizationEndpoint)
            .put("token", session.metadata.tokenEndpoint)
            .put("reg", session.metadata.registrationEndpoint ?: JSONObject.NULL)
            .put("scopes", org.json.JSONArray(session.metadata.scopesSupported))
            .put("ccm", session.metadata.codeChallengeMethods?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)
            .put("tam", session.metadata.tokenAuthMethods?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)
            .put("gt", session.metadata.grantTypes?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)
            .put("cid", session.registration.clientId)
            .put("cname", session.registration.tokenAuthMethod)
            .put("scope", session.scope ?: JSONObject.NULL)
            .put("res", session.resource)
            .toString()
        runCatching { store.write(sessionKey(serverId), json) }
            .onFailure { log("[mcp] server=$serverId 授权会话保存失败（${it.javaClass.simpleName}）") }
    }

    /** 读回授权会话；没有/坏了返回 null（调用方重新发现+注册）。 */
    fun loadSession(serverId: String): McpAuthSession? {
        val raw = runCatching { store.read(sessionKey(serverId)) }.getOrNull() ?: return null
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        val authz = json.optString("authz").takeIf { it.isNotBlank() } ?: return null
        val token = json.optString("token").takeIf { it.isNotBlank() } ?: return null
        val cid = json.optString("cid").takeIf { it.isNotBlank() } ?: return null
        return McpAuthSession(
            metadata = McpAuthServerMetadata(
                issuer = json.optString("iss").takeIf { it.isNotBlank() },
                authorizationEndpoint = authz,
                tokenEndpoint = token,
                registrationEndpoint = json.optString("reg").takeIf { it.isNotBlank() },
                scopesSupported = json.optJSONArray("scopes")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                }.orEmpty(),
                codeChallengeMethods = json.optJSONArray("ccm")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                },
                tokenAuthMethods = json.optJSONArray("tam")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                },
                grantTypes = json.optJSONArray("gt")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                },
            ),
            registration = McpClientRegistration(
                clientId = cid,
                redirectUris = emptyList(),
                tokenAuthMethod = json.optString("cname").ifBlank { "none" },
            ),
            scope = json.optString("scope").takeIf { it.isNotBlank() },
            resource = json.optString("res"),
        )
    }

    /** 删除服务器时连会话一起清（不留孤儿 client 记录）。 */
    fun clearSession(serverId: String) {
        runCatching { store.remove(sessionKey(serverId)) }
    }

    /** 「全部断开」：清所有服务器的 token（不删配置）。 */
    fun clearAll() {
        val keys = runCatching { store.keys() }.getOrDefault(emptySet())
        var cleared = 0
        keys.filter {
            it.startsWith(PREFIX_TOKEN) || it.startsWith(PREFIX_REVOKED)
        }.forEach { key ->
            runCatching { store.remove(key) }.onSuccess { cleared++ }
        }
        log("[mcp] 全部断开：清除 $cleared 条授权记录")
    }

    /**
     * 标记「被撤销」（refresh 失败/invalid_grant）。
     *
     * 只留一个**没有 token 内容**的标记：UI 能说"被撤销，请重新授权"，而不是笼统的
     * "未连接"；同时把 access/refresh 都清掉（它们已经没用了，留着只是泄漏面）。
     */
    fun markRevoked(serverId: String) {
        runCatching { store.remove(tokenKey(serverId)) }
        runCatching { store.write(revokedKey(serverId), "1") }
        log("[mcp] server=$serverId 的授权已被撤销（refresh 失败），需要重新授权")
    }

    /** 当前授权状态（UI 那一列）。 */
    fun status(serverId: String, expectedResource: String?, nowMs: Long): McpAuthStatus {
        val tokens = load(serverId, expectedResource)
        if (tokens != null) {
            return if (tokens.isExpired(nowMs)) McpAuthStatus.EXPIRED else McpAuthStatus.VALID
        }
        val revoked = runCatching { store.read(revokedKey(serverId)) }.getOrNull() != null
        return if (revoked) McpAuthStatus.REVOKED else McpAuthStatus.UNAUTHORIZED
    }

    // ── 编解码（纯函数，单测直接打） ───────────────────────────────────────

    private fun encode(tokens: McpTokenSet): String = JSONObject()
        .put("v", FORMAT_VERSION)
        .put("at", tokens.accessToken)
        .put("rt", tokens.refreshToken ?: JSONObject.NULL)
        .put("tt", tokens.tokenType)
        .put("sc", tokens.scope ?: JSONObject.NULL)
        .put("exp", tokens.expiresAtMs ?: JSONObject.NULL)
        .put("res", tokens.resource ?: JSONObject.NULL)
        .toString()

    /** 解不开/形状不对返回 null（调用方按"没有授权"处理）。 */
    private fun decode(raw: String): McpTokenSet? {
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        val access = json.optString("at")
        if (access.isEmpty()) return null
        val exp = if (json.isNull("exp")) null else json.optLong("exp", -1L).takeIf { it > 0 }
        return McpTokenSet(
            accessToken = access,
            refreshToken = if (json.isNull("rt")) null else json.optString("rt").takeIf { it.isNotEmpty() },
            tokenType = json.optString("tt").ifBlank { "Bearer" },
            scope = if (json.isNull("sc")) null else json.optString("sc").takeIf { it.isNotEmpty() },
            expiresAtMs = exp,
            resource = if (json.isNull("res")) null else json.optString("res").takeIf { it.isNotEmpty() },
        )
    }

    companion object {
        private const val FORMAT_VERSION = 1
        const val PREFIX_TOKEN = "tok."
        const val PREFIX_REVOKED = "rev."
        const val PREFIX_SESSION = "sess."

        private fun tokenKey(serverId: String) = "$PREFIX_TOKEN$serverId"
        private fun revokedKey(serverId: String) = "$PREFIX_REVOKED$serverId"
        private fun sessionKey(serverId: String) = "$PREFIX_SESSION$serverId"
    }
}
