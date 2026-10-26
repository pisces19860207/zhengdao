// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十六批 · MCP OAuth 2.1 接入：**纯数据 + 纯函数**（无网络、无 Android）。
//
// 这一层只回答四个问题，全部可被普通 JVM 用例逐条钉住：
//   1. PKCE 的 verifier/challenge 怎么生成（S256，**没有 plain 分支**）；
//   2. `state` 怎么生成、怎么比对；
//   3. `resource`（RFC 8707）的规范化形态是什么（受众绑定的唯一依据）；
//   4. token 集合的形状与「过期/被撤销」怎么判。
//
// 纪律：本文件的任何 toString/异常文案都**不含 token 原文**（[McpTokenSet.toString] 打码）。
package com.example.zhengdao.baopu.mcp

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 一台服务器的授权状态（UI 行上的「授权状态」列，也是 [McpServerView] 的一部分）。
 *
 * 为什么不是「连上/没连上」两态：派工单 §2.5 要求**过期就显示过期**、被撤销同理、
 * 不支持 OAuth 的服务器要标「不支持」而不是"已连接"——状态不真实，用户就会以为
 * 自己还能用，直到某次调用莫名其妙失败。
 */
internal enum class McpAuthStatus {
    /** 从没授权过（新登记、或断开后清掉了 token）。 */
    UNAUTHORIZED,

    /** 有 token 且未过期。 */
    VALID,

    /** 有 token 但已过期（还能刷新，UI 给「重新授权/刷新」）。 */
    EXPIRED,

    /** 授权服务器明确拒绝（refresh 失败 / token 被撤销），必须重新授权。 */
    REVOKED,

    /** 发现链拉不到、注册失败、或它根本不支持 OAuth（旧式静态头）。 */
    UNSUPPORTED,
    ;

    /** 能不能直接拿现有 token 发请求。 */
    val usable: Boolean get() = this == VALID
}

/**
 * 一次 OAuth 授权拿到的 token 集合。
 *
 * **受众绑定**：[resource] 是取 token 时带上去的 `resource`（RFC 8707），也是本机唯一
 * 认可的「这个 token 是给谁用的」。用 A 服务器的 token 去访问 B 服务器 = 受众不匹配
 * ⇒ [McpTokenVault] 直接当没有 token（负例④）。
 *
 * **泄漏面**：[toString] 只打码后的长度与过期时间，绝不出现 token 原文；这个类不会
 * 出现在任何日志/报告/决策中心里（[McpTokenVault] 只落 EncryptedSharedPreferences）。
 */
internal data class McpTokenSet(
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String,
    val scope: String?,
    /** 绝对过期时刻（毫秒）；null = 服务器没给 expires_in（当作长期有效，靠 401 兜）。 */
    val expiresAtMs: Long?,
    /** 取 token 时的 `resource` 值（规范化 URI）；null = 老数据/未绑定，按不匹配处理。 */
    val resource: String?,
) {
    fun isExpired(nowMs: Long, skewMs: Long = EXPIRY_SKEW_MS): Boolean =
        expiresAtMs != null && nowMs + skewMs >= expiresAtMs

    /** 能不能刷新（有 refresh_token 才有资格谈刷新）。 */
    val canRefresh: Boolean get() = !refreshToken.isNullOrBlank()

    /**
     * **永远不要**把它拼进日志：这里只给「有没有/多长/什么时候过期」。
     *
     * 单独写一个 toString 是因为 data class 的默认实现会把两个 token 原样打出来，
     * 而这类对象极易被顺手塞进 `log("...$token")`——默认实现本身就是泄漏面。
     */
    override fun toString(): String =
        "McpTokenSet(access=***${accessToken.length} 字符, refresh=" +
            (if (refreshToken.isNullOrBlank()) "无" else "***${refreshToken.length} 字符") +
            ", type=$tokenType, scope=${scope ?: "未声明"}, " +
            "expiresAt=${expiresAtMs ?: "未声明"}, resource=${resource ?: "未绑定"})"

    companion object {
        /** 提前 60s 当过期：避免「发出去正好过期」的边界失败。 */
        const val EXPIRY_SKEW_MS = 60_000L
    }
}

/**
 * PKCE（RFC 7636）——**只实现 S256**。
 *
 * 为什么没有 `plain` 分支：plain 等于把 verifier 明文放在授权请求里，任何能看到
 * 那次请求（日志/代理/别的 App 读剪贴板式攻击）的人都能拿它换 token。派工单负例③
 * 把「PKCE 用 plain」列为必红项；**代码里没有这个分支**比"有分支但默认不用"更硬。
 */
internal object McpPkce {

    /** 唯一允许的 challenge 方法（发给授权服务器的 `code_challenge_method`）。 */
    const val METHOD = "S256"

    /** RFC 7636 §4.1：43–128 个 unreserved 字符。 */
    const val MIN_VERIFIER = 43
    const val MAX_VERIFIER = 128

    private val UNRESERVED = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')

    /**
     * 生成 verifier：32 随机字节 → base64url（无填充）= 43 个字符，正好落在合法区间下界。
     *
     * 用 base64url 而不是自造字符集：它的字母表（A-Za-z0-9-_）是 unreserved 的子集，
     * 且每一位都是 6 bit 满熵。
     */
    fun verifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** `BASE64URL(SHA256(ASCII(verifier)))`，无填充。 */
    fun challengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** verifier 形状检查（服务端回 400 之前，本机先拒，省一次往返）。 */
    fun isWellFormed(verifier: String): Boolean =
        verifier.length in MIN_VERIFIER..MAX_VERIFIER && verifier.all { it in UNRESERVED }

    /** 生成 `state`：32 随机字节 base64url（与 verifier 同强度，防 CSRF/授权码注入）。 */
    fun state(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * `state` 比对：**定长恒定时间**。
     *
     * 回调是外部可投递的（任何 App 都能发 `zhengdao://mcp/oauth?...`），state 是唯一
     * 能证明「这次回调是我发起的那次」的东西。用 `==` 会在第一个不同字符处返回，
     * 给本地恶意 App 逐字符试的空间；恒定时间比较是零成本的好习惯（同 BridgeToken）。
     */
    fun stateMatches(expected: String, actual: String?): Boolean {
        if (actual == null || expected.isEmpty()) return false
        if (expected.length != actual.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor actual[i].code)
        return diff == 0
    }
}

/**
 * `resource` 参数（RFC 8707）的规范化。
 *
 * MCP 2026-07-28：取 token 时必须带 `resource`，值是 MCP server 的**规范 URI**；
 * 授权服务器应把它写进 token 的受众（`aud`）。本机用它做两件事：
 *   · 请求 token 时原样带上；
 *   · 用 token 前核对「token 里的 resource == 本服务器的规范 URI」，不等就不用（负例④）。
 *
 * 规范化口径（保守、可复现）：小写 scheme/host、去掉默认端口、去掉 query/fragment/
 * userInfo、去掉尾部 `/`（根路径除外）。**不做**任何"猜"（不补端口、不改路径大小写）。
 */
internal object McpResource {

    /**
     * @return 规范化 URI；URL 不合法返回 null（调用方按配置错误处理）。
     */
    fun canonical(raw: String): String? {
        val uri = try {
            URI(raw.trim())
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return null
        if (uri.userInfo != null) return null
        val port = when {
            uri.port == -1 -> ""
            scheme == "https" && uri.port == 443 -> ""
            scheme == "http" && uri.port == 80 -> ""
            else -> ":${uri.port}"
        }
        val path = (uri.path ?: "").trimEnd('/')
        val hostPart = if (host.contains(':')) "[$host]" else host
        return "$scheme://$hostPart$port$path"
    }

    /** 两个 resource 是否同一个受众（都非空且规范化后逐字相等）。 */
    fun sameAudience(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return canonical(a) == canonical(b)
    }
}

/**
 * 授权流程里的一次失败（人话已清洗：**不含 token / code / verifier**）。
 *
 * 与 [McpException] 分开：这是"授权这件事"的错误码，UI 要按它决定给「重试」「重新授权」
 * 还是「这台服务器不支持」；[McpException] 是"这次 MCP 请求"的错误。
 */
internal enum class McpAuthFailure {
    /** 发现链拉不到（没有 PRM、没有 AS metadata）。 */
    NO_METADATA,

    /** 授权服务器不支持 S256 / 端点是明文 http 等「不可接受」的元数据。 */
    BAD_METADATA,

    /** 动态注册失败，且没有预注册 client_id。 */
    REGISTRATION_FAILED,

    /** 回调 state 对不上（可能不是我们发起的）。 */
    STATE_MISMATCH,

    /** 用户取消/授权服务器回 error。 */
    AUTHORIZATION_DENIED,

    /** 换 token 失败（code 过期、verifier 不匹配…）。 */
    TOKEN_EXCHANGE_FAILED,

    /** 刷新失败（refresh_token 死了）⇒ 必须重新授权。 */
    REFRESH_FAILED,

    /** token 的 resource 与目标服务器不一致（受众不符）。 */
    AUDIENCE_MISMATCH,

    /** 该服务器不支持 OAuth（旧式静态头）⇒ 明确失败，不降级。 */
    UNSUPPORTED,

    /** 没有待处理的授权（回调来晚了/重启后丢了 pending）。 */
    NO_PENDING,
}

internal class McpAuthException(
    val failure: McpAuthFailure,
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

/** 授权服务器公布的能力（RFC 8414 / OIDC discovery 的子集，只取用得上的）。 */
internal data class McpAuthServerMetadata(
    val issuer: String?,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val scopesSupported: List<String>,
    /** 服务器声明支持的 PKCE 方法；null = 没声明（按「未知」处理，仍发 S256）。 */
    val codeChallengeMethods: List<String>?,
    /** 声明支持的 token 端点认证方式；null = 没声明。 */
    val tokenAuthMethods: List<String>?,
    /** 是否支持 refresh_token 授权（null = 没声明）。 */
    val grantTypes: List<String>?,
) {
    /** 服务器明确声明「不支持 S256」⇒ 不能用（负例③的前半段）。 */
    val rejectsS256: Boolean
        get() = codeChallengeMethods != null && McpPkce.METHOD !in codeChallengeMethods

    val supportsRefresh: Boolean
        get() = grantTypes == null || "refresh_token" in grantTypes
}

/** 受保护资源元数据（RFC 9728）。 */
internal data class McpResourceMetadata(
    val resource: String?,
    val authorizationServers: List<String>,
    val scopesSupported: List<String>,
)

/** 动态注册拿到的客户端身份（**只有 client_id，没有 secret**）。 */
internal data class McpClientRegistration(
    val clientId: String,
    /** 授权服务器给的回调白名单回显（只用于诊断，不参与判断）。 */
    val redirectUris: List<String>,
    /** 注册时声明的 token 端点认证方式；`none` = 公共客户端（手机端唯一正确选择）。 */
    val tokenAuthMethod: String,
)

/**
 * 一次待完成的授权（从「打开浏览器」到「回调换 token」之间挂在内存里的东西）。
 *
 * 只在内存：进程被杀 ⇒ 这次授权作废，用户再点一次「授权」即可（不会留下半截凭据）。
 */
internal data class McpPendingAuth(
    val serverId: String,
    val state: String,
    val verifier: String,
    val resource: String,
    val redirectUri: String,
    val metadata: McpAuthServerMetadata,
    val registration: McpClientRegistration,
    /** 这次授权要的 scope（空 = 服务器默认）。 */
    val scope: String?,
    val createdAtMs: Long,
)
