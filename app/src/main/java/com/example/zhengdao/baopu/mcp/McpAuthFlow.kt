// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十六批 · MCP OAuth 2.1：**授权流程的编排**（发现 → 注册 → 授权 → 回调 → 刷新）。
//
// 为什么单独一层：McpClient 只管「带上 token 发 JSON-RPC」，McpOAuthClient 只管
// 「四个 HTTP 动作」，中间的**状态**（这次授权是谁发起的、state 对不对、刷新失败过没有）
// 全在这里。三条纪律落在这里：
//   1. `state` 不匹配 ⇒ 这次回调直接作废（回调是外部可投递的，见 McpPkce.stateMatches）；
//   2. 刷新**只试一次**，失败就打「已撤销」标记并**明确报错**——绝不静默降级成匿名请求
//      （负例⑤：刷新循环/静默降级都红）；
//   3. `resource` 从 [McpResource.canonical] 来，取 token 与用 token 两处都核（负例④）。
package com.example.zhengdao.baopu.mcp

import java.security.SecureRandom

/**
 * 给传输层用的「token 供给」接口（[McpClient] 只认这个，不认识 OAuth）。
 *
 * 两个方法刻意分开：
 * · [accessToken] **不联网**（每次请求都调，不能顺带做网络）；
 * · [refreshAfterUnauthorized] 只在 401 后调一次，返回 null 表示"刷新失败，别重试"。
 */
internal interface McpTokenProvider {
    /** 当前可用的 access token；null = 没有（请求会拿到 401，再走刷新/授权）。 */
    fun accessToken(): String?

    /** 401 之后刷新一次；null = 刷新失败（调用方必须**明确报错**，不许改发匿名请求）。 */
    fun refreshAfterUnauthorized(): String?
}

/**
 * 一次授权回调的收尾结果。
 *
 * 带 [serverId]：UI 拿到之后要**自动重连那一台**（用户点「去授权」就是为了用上它）。
 */
internal data class McpAuthCompletion(val serverId: String, val tokens: McpTokenSet)

/** 一次「开始授权」的结果。 */
internal sealed interface McpAuthBegin {
    /** 把 [authorizationUrl] 交给系统浏览器/Custom Tabs 打开。 */
    data class Ready(val serverId: String, val authorizationUrl: String) : McpAuthBegin

    /** 明确失败（不支持 OAuth / 注册失败 / 元数据不可接受）；[message] 已清洗。 */
    data class Failed(val failure: McpAuthFailure, val message: String) : McpAuthBegin
}

/**
 * 授权流程编排器（进程内单例，见 BaopuRuntime）。
 *
 * 线程：全部方法同步（`synchronized(lock)`）——授权是低频动作，不需要并发；
 * 串行反而避免「两个回调同时换 token」这种把 refresh_token 用废的竞态。
 */
internal class McpAuthFlow(
    private val vault: McpTokenVault,
    private val oauth: McpOAuthClient,
    /** 回调 deeplink 前缀（`zhengdao://mcp/oauth`），由 Manifest 的 intent-filter 对应。 */
    private val redirectUri: String = DEFAULT_REDIRECT_URI,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
    private val log: (String) -> Unit = {},
) {

    private val lock = Any()

    /** 待完成的授权，按 `state` 索引（回调只带 state，别的都不可信）。 */
    private val pending = HashMap<String, McpPendingAuth>()

    /** 刷新失败过的服务器：**在重新授权成功之前**不再尝试刷新（负例⑤的闸）。 */
    private val refreshFailed = HashSet<String>()

    /**
     * 明确判定「不支持 OAuth」的服务器（发现链拉不到 / 动态注册失败且没填 client_id）。
     *
     * 只用于**界面说实话**：列表里这一行标「不支持 OAuth」，而不是让它看起来像
     * "未授权"（用户会以为再点一次就能成）。用户改了地址或填了 client_id 后重新
     * [begin] 成功，标记自动清掉。
     */
    private val unsupported = HashSet<String>()

    /** 只给门禁看：一共真的发过几次刷新请求。 */
    @Volatile
    var refreshAttempts: Int = 0
        private set

    /**
     * 开始一次授权：发现 → 注册（或用预注册 client_id）→ 拼授权页地址。
     *
     * @param scope 这次要申请的权限；null = 服务器默认。step-up 时传「缺的那个」。
     */
    fun begin(config: McpServerConfig, scope: String? = null): McpAuthBegin {
        synchronized(lock) {
            val resource = McpResource.canonical(config.url)
                ?: return McpAuthBegin.Failed(
                    McpAuthFailure.BAD_METADATA,
                    "服务器地址无法规范化，不能作为 resource",
                )

            val session = try {
                sessionFor(config, resource)
            } catch (e: McpAuthException) {
                log("[mcp] server=${config.id} 授权准备失败：${e.failure}")
                if (e.failure == McpAuthFailure.NO_METADATA ||
                    e.failure == McpAuthFailure.REGISTRATION_FAILED
                ) {
                    unsupported += config.id
                }
                return McpAuthBegin.Failed(e.failure, e.userMessage)
            }
            unsupported.remove(config.id)

            val verifier = McpPkce.verifier(random)
            val state = McpPkce.state(random)
            val url = McpOAuthDiscovery.authorizationUrl(
                metadata = session.metadata,
                clientId = session.registration.clientId,
                redirectUri = redirectUri,
                state = state,
                codeChallenge = McpPkce.challengeS256(verifier),
                resource = resource,
                scope = scope ?: session.scope,
            )
            val one = McpPendingAuth(
                serverId = config.id,
                state = state,
                verifier = verifier,
                resource = resource,
                redirectUri = redirectUri,
                metadata = session.metadata,
                registration = session.registration,
                scope = scope ?: session.scope,
                createdAtMs = now(),
            )
            // 同一台服务器只保留最近一次 pending：用户连点两次「授权」时，旧的那次作废
            // （否则两个 state 都算数，等于放宽了校验）。
            pending.entries.removeAll { it.value.serverId == config.id }
            pending[state] = one
            pruneExpired()
            log("[mcp] server=${config.id} 已生成授权链接（scope=${scope ?: session.scope ?: "默认"}）")
            return McpAuthBegin.Ready(config.id, url)
        }
    }

    /**
     * 处理浏览器回调（`zhengdao://mcp/oauth?code=…&state=…`）。
     *
     * **顺序即安全**：先校 state（找不到 pending 就整条丢弃），再看 error，最后才换 token。
     * 失败时 pending 也一并删掉——一次 state 只能换一次 token。
     */
    fun complete(callback: McpOAuthCallback): McpAuthCompletion = synchronized(lock) {
        val one = pending.remove(callback.state)
            ?: throw McpAuthException(
                McpAuthFailure.STATE_MISMATCH,
                "这次授权回调对不上（state 不匹配或已过期）——可能不是本机发起的那次，已忽略",
            )
        if (callback.error != null) {
            throw McpAuthException(
                McpAuthFailure.AUTHORIZATION_DENIED,
                "授权被拒绝：${callback.error}" +
                    (callback.errorDescription?.let { "（${McpText.clip(McpText.flat(it), 120)}）" } ?: ""),
            )
        }
        val code = callback.code
            ?: throw McpAuthException(McpAuthFailure.TOKEN_EXCHANGE_FAILED, "回调里没有授权码")
        val tokens = try {
            oauth.exchangeCode(
                metadata = one.metadata,
                registration = one.registration,
                code = code,
                verifier = one.verifier,
                redirectUri = one.redirectUri,
                resource = one.resource,
                nowMs = now(),
            )
        } catch (e: McpAuthException) {
            log("[mcp] server=${one.serverId} 换 token 失败：${e.failure}")
            throw e
        }
        vault.save(one.serverId, tokens)
        refreshFailed.remove(one.serverId)
        log("[mcp] server=${one.serverId} 授权完成")
        McpAuthCompletion(one.serverId, tokens)
    }

    /**
     * 拿一个能直接用的 access token（不联网）。
     *
     * 过期也返回 null：调用方会拿到 401，再走 [refresh]（一次）——这条路径比"提前 60s
     * 主动刷新"更简单，且 401 是所有服务器都认的信号。
     */
    fun tokenFor(config: McpServerConfig): String? {
        synchronized(lock) {
            val resource = McpResource.canonical(config.url) ?: return null
            val tokens = vault.load(config.id, resource) ?: return null
            if (tokens.isExpired(now())) return null
            return tokens.accessToken
        }
    }

    /** 给 [McpClient] 的供给器（按服务器绑定；刷新失败不抛，返回 null 让传输层报错）。 */
    fun providerFor(config: McpServerConfig): McpTokenProvider = object : McpTokenProvider {
        override fun accessToken(): String? = tokenFor(config)

        override fun refreshAfterUnauthorized(): String? = try {
            refresh(config).accessToken
        } catch (e: McpAuthException) {
            null
        }
    }

    /**
     * 刷新一次（**唯一**会发 refresh 请求的地方）。
     *
     * 闸门（负例⑤）：
     * ① 这台服务器刷新失败过 ⇒ 直接抛 [McpAuthFailure.REFRESH_FAILED]，**一个包都不发**；
     * ② 没有 refresh_token ⇒ 标记已撤销并抛错（不许退回匿名）；
     * ③ 失败后打标记（[refreshFailed] + vault.markRevoked），下次同样直接抛错；
     * ④ 成功则清标记。重新授权成功也会清（[complete]）。
     */
    fun refresh(config: McpServerConfig): McpTokenSet = synchronized(lock) {
        if (config.id in refreshFailed) {
            throw McpAuthException(
                McpAuthFailure.REFRESH_FAILED,
                "「${config.name}」的授权刷新已经失败过：请重新授权（不会自动反复重试，也不会退回匿名请求）",
            )
        }
        val resource = McpResource.canonical(config.url)
            ?: throw McpAuthException(McpAuthFailure.BAD_METADATA, "服务器地址无法规范化")
        val current = vault.load(config.id, resource)
            ?: throw McpAuthException(
                McpAuthFailure.REFRESH_FAILED,
                "「${config.name}」没有可用的授权：请重新授权",
            )
        val refreshToken = current.refreshToken
        if (refreshToken.isNullOrBlank()) {
            refreshFailed += config.id
            vault.markRevoked(config.id)
            throw McpAuthException(
                McpAuthFailure.REFRESH_FAILED,
                "「${config.name}」的授权已过期且没有刷新凭据：请重新授权",
            )
        }
        val session = try {
            sessionFor(config, resource)
        } catch (e: McpAuthException) {
            refreshFailed += config.id
            throw e
        }
        refreshAttempts++
        val fresh = try {
            oauth.refresh(
                metadata = session.metadata,
                registration = session.registration,
                refreshToken = refreshToken,
                resource = resource,
                nowMs = now(),
            )
        } catch (e: McpAuthException) {
            refreshFailed += config.id
            vault.markRevoked(config.id)
            log("[mcp] server=${config.id} 刷新失败：${e.failure}")
            throw McpAuthException(
                McpAuthFailure.REFRESH_FAILED,
                "「${config.name}」的授权刷新失败：请重新授权（不会静默降级成匿名请求）",
                e,
            )
        }
        vault.save(config.id, fresh)
        refreshFailed.remove(config.id)
        log("[mcp] server=${config.id} 刷新成功")
        fresh
    }

    /** 当前授权状态（UI 那一列）。 */
    fun status(config: McpServerConfig): McpAuthStatus {
        synchronized(lock) {
            val resource = McpResource.canonical(config.url) ?: return McpAuthStatus.UNAUTHORIZED
            if (config.id in refreshFailed) return McpAuthStatus.REVOKED
            val stored = vault.status(config.id, resource, now())
            // 有 token 就是有（哪怕之前判过不支持，比如服务器升级后又能用了）。
            if (stored == McpAuthStatus.VALID || stored == McpAuthStatus.EXPIRED) return stored
            if (config.id in unsupported) return McpAuthStatus.UNSUPPORTED
            return stored
        }
    }

    /** 断开/删除：清 token（连同刷新失败标记）。 */
    fun wipe(serverId: String) = synchronized(lock) {
        refreshFailed.remove(serverId)
        unsupported.remove(serverId)
        pending.entries.removeAll { it.value.serverId == serverId }
        vault.clear(serverId)
    }

    /** 删除服务器：连授权会话（client_id/端点）一起清，不留孤儿。 */
    fun clearSession(serverId: String) = synchronized(lock) {
        vault.clearSession(serverId)
    }

    /** 「全部断开」：清所有 token，不删配置。 */
    fun wipeAll() = synchronized(lock) {
        refreshFailed.clear()
        unsupported.clear()
        pending.clear()
        vault.clearAll()
    }

    /** 授权完成后再「有 token 了」：给 UI 判断要不要提示重启连接用。 */
    fun hasValidToken(config: McpServerConfig): Boolean = tokenFor(config) != null

    // ── 内部 ─────────────────────────────────────────────────────────────

    /**
     * 取（或建立）这台服务器的授权会话：元数据 + client_id。
     *
     * 缓存顺序：内存 pending/session → vault 里的持久会话 → 重新发现+注册。
     * **持久化 client_id 不是为了省一次注册**，而是为了让「进程重启后 refresh」仍然用
     * 当初注册的那个 client（换一个 client_id 去刷新，授权服务器会拒）。
     */
    private fun sessionFor(config: McpServerConfig, resource: String): McpAuthSession {
        // 会话可复用，但**用户改了 client_id 就得重建**（否则拿旧 client 去刷新，必被拒）。
        vault.loadSession(config.id)?.let { cached ->
            val sameClient = config.clientId == null || config.clientId == cached.registration.clientId
            if (sameClient && cached.resource == resource) return cached
        }
        // 先按 well-known 找；找不到再探一次 401 挑战（挑战里可能写着 resource_metadata）。
        // 顺序反过来会让每次授权都白打一个未授权请求。
        val discovery = try {
            oauth.discover(config)
        } catch (e: McpAuthException) {
            val challenge = oauth.probeChallenge(config)
            if (challenge == null) throw e else oauth.discover(config, challenge)
        }
        val registration = if (config.clientId != null) {
            McpClientRegistration(
                clientId = config.clientId,
                redirectUris = listOf(redirectUri),
                tokenAuthMethod = "none",
            )
        } else {
            oauth.register(discovery.authServer, redirectUri)
        }
        val session = McpAuthSession(discovery.authServer, registration, null, resource)
        vault.saveSession(config.id, session)
        return session
    }

    /** pending 超过 [PENDING_TTL_MS] 就丢（用户开着浏览器去干别的了）。 */
    private fun pruneExpired() {
        val cutoff = now() - PENDING_TTL_MS
        pending.entries.removeAll { it.value.createdAtMs < cutoff }
    }

    companion object {
        /** 回调地址（与 AndroidManifest 的 `zhengdao://mcp/oauth` 一致）。 */
        const val DEFAULT_REDIRECT_URI = "zhengdao://mcp/oauth"

        /** 一次授权的有效期：10 分钟（超过就当这次授权作废，用户重新点一次）。 */
        const val PENDING_TTL_MS = 10 * 60 * 1000L
    }
}

/**
 * 持久化的授权会话（**不含 token**，只有端点与 client_id）。
 *
 * 为什么可以落盘：`client_id`、端点地址都是公开信息（授权页地址栏里就有）；
 * 真正的凭据只有 token，那条走 [McpTokenVault] 的加密存储。
 */
internal data class McpAuthSession(
    val metadata: McpAuthServerMetadata,
    val registration: McpClientRegistration,
    val scope: String?,
    val resource: String,
)
