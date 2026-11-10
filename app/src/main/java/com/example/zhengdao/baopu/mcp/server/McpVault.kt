// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十四单 · App 内 MCP 服务端的**凭据库**（进来的连接认谁：一把服务端 token）。
//
// ── TODO(548) 边界（派工单原话级）─────────────────────────────────────────
// 「传输层与凭据库**复用 548**（548 未合时按接口名实现并写 TODO）」。548（外部 Agent
// 接入，M2 L3 批）截至本单基线（main@f0e89c882）未合入、feat/agent-client 工作树为空，
// 所以本文件按派工单点名的接口名 **McpVault** 先立接口，实现临时落在**本仓既有**的
// 加密 prefs 通道（[com.example.zhengdao.baopu.mcp.PrefsMcpSecretStore]：AndroidKeyStore
// 一把不可导出的 AES-256 密钥 + AES/GCM 逐条加密）。548 合入后：换掉 [PrefsMcpVault]
// 这一个实现类即可，接口与调用点（传输层校验 / 设置页人工显抄）不动。
//
// 红线（照 McpTokenVault 216 批同一条，用户原话级纪律）：
//   · token 明文只进加密存储；本文件**不打印、不 toString** 任何 token；
//     日志只记「生成了/轮换了/没配」，不带内容（行里连前缀都不带）；
//   · 比较**只用常数时间**（[MessageDigest.isEqual]），不给时序侧信道留口；
//   · 明文出口只有两处：传输层校验（[McpVault.matches]）与设置页人工显/抄
//     （[McpVault.reveal]，人手点按才走）；审计账里永远没有它。
package com.example.zhengdao.baopu.mcp.server

import com.example.zhengdao.baopu.mcp.McpSecretStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 服务端凭据库（**接口就是 548 的缝**；实现可整体替换）。
 *
 * 四个动作：生成（[ensureToken]）、轮换（[rotate]）、校验（[matches]）、人工显抄
 * （[reveal]）。没有「删除」：服务端开着就必须有一把 token——关服务走停机开关，
 * 不走"删 token"（删了等于下次启动静默再生成，账上反而看不出换过钥匙）。
 */
interface McpVault {

    /** 现在的 token；还没有 ⇒ null（调用方先 [ensureToken]）。 */
    fun currentToken(): String?

    /** 没有则生成、有则原样返回（幂等；启动装配用）。 */
    fun ensureToken(): String

    /** 轮换（设置页"换钥匙"）：旧 token 立即失效，返回新 token。 */
    fun rotate(): String

    /**
     * 候选值是不是这把 token（**常数时间比较**；候选为空串/null ⇒ false，不抛）。
     * 这是传输层 401 判定的唯一入口（判据②的闸就压在这里）。
     */
    fun matches(candidate: String?): Boolean

    /** 设置页人工显/抄（唯一的"给人看"出口；返回 null ＝ 还没生成）。 */
    fun reveal(): String?

    /** 配套事实（设置页与日志用；永远不含 token 本体）。 */
    fun fingerprint(): String?
}

/** 生产实现：加密 prefs（复用 216 批的 [PrefsMcpSecretStore] 通道；见文件头 TODO(548)）。 */
internal class PrefsMcpVault(
    private val store: McpSecretStore,
    private val log: (String) -> Unit = {},
    random: SecureRandom = SecureRandom(),
) : McpVault {

    private val random = random

    @get:Synchronized
    private var cached: String? = null

    @Synchronized
    override fun currentToken(): String? {
        cached?.let { return it }
        val raw = runCatching { store.read(KEY) }.getOrNull() ?: return null
        if (raw.isBlank()) return null
        cached = raw
        return raw
    }

    @Synchronized
    override fun ensureToken(): String {
        currentToken()?.let { return it }
        val token = newToken()
        write(token)
        return token
    }

    @Synchronized
    override fun rotate(): String {
        val token = newToken()
        write(token)
        return token
    }

    override fun matches(candidate: String?): Boolean {
        val token = currentToken() ?: return false
        if (candidate.isNullOrEmpty()) return false
        // 常数时间比较：不等长时 isEqual 的耗时由最长串决定，不泄漏前缀匹配位数。
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8),
        )
    }

    override fun reveal(): String? = currentToken()

    /** 指纹（设置页/账上辨认用）：SHA-256 前 8 位 hex——不可逆推 token 本体。 */
    override fun fingerprint(): String? = currentToken()?.let {
        val d = MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
        d.take(4).joinToString("") { b -> "%02x".format(b) }
    }

    /** 写入失败不抛：本轮内存里照用，重启后 [ensureToken] 会再生成一把（同 McpTokenVault 口径）。 */
    private fun write(token: String) {
        cached = token
        runCatching { store.write(KEY, token) }
            .onFailure { log("[mcp-server] token 没存住（${it.javaClass.simpleName}）：本轮可用，重启后需重生成") }
    }

    /** 256 位随机 → base64url（无填充；43 字符，放得进 Authorization 头不磕碰）。 */
    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        /** 存储键（加密 prefs 的同一条命名规则；与客户端 OAuth 的 `tok.*` 键空间不撞）。 */
        const val KEY = "srv.token.v1"
    }
}

/** 内存实现（单测）：形状与生产一致，校验/轮换语义直接可测。 */
class InMemoryMcpVault(random: SecureRandom = SecureRandom()) : McpVault {
    private val impl = PrefsMcpVault(InMemoryStore(), log = {}, random = random)

    private class InMemoryStore : McpSecretStore {
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
    }

    override fun currentToken(): String? = impl.currentToken()
    override fun ensureToken(): String = impl.ensureToken()
    override fun rotate(): String = impl.rotate()
    override fun matches(candidate: String?): Boolean = impl.matches(candidate)
    override fun reveal(): String? = impl.reveal()
    override fun fingerprint(): String? = impl.fingerprint()
}
