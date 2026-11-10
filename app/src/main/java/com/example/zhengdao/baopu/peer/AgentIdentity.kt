// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · 外部 agent 的身份三件：形态、稳定 id、名册行。
//
// 稳定 id 为什么从 endpoint 导出：名字会重名、会改，地址规范化后才是那台机器的锚。
// 同一地址重新注册必须拿到同一个 id（派工单判据①：同 url 重注册不新增）。
package com.example.zhengdao.baopu.peer

import org.json.JSONObject
import java.net.URI

/** 接入形态（v1 闭集只有两种；加新形态 = 改这里，不许在调用点散字符串）。 */
internal enum class AgentForm(val wire: String) {
    MCP("mcp"),
    A2A("a2a"),
    ;

    companion object {
        fun ofWire(wire: String): AgentForm? = entries.firstOrNull { it.wire == wire }
    }
}

/** 稳定 id 与 endpoint 规范化（唯一口径）。 */
internal object AgentIds {

    /**
     * 规范化：scheme/host 小写、去默认端口（80/443）、去 query/fragment、路径去尾斜杠。
     * 解析不了 ⇒ 原串 trim 后小写兜底（注册侧另有 URL 合法性闸，进不了名册）。
     */
    fun normalize(endpoint: String): String {
        val raw = endpoint.trim()
        return runCatching {
            val u = URI(raw)
            val scheme = (u.scheme ?: raw.substringBefore("://", "")).lowercase()
            val host = u.host?.lowercase() ?: return fallback(raw)
            var port = u.port
            if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) port = -1
            val path = u.path.orEmpty().trimEnd('/')
            buildString {
                append(scheme).append("://").append(host)
                if (port > 0) append(':').append(port)
                append(path)
            }
        }.getOrElse { fallback(raw) }
    }

    private fun fallback(raw: String): String = raw.lowercase().trimEnd('/')

    /** 稳定 id：规范化 endpoint 的 sha256 前 12 位（`ag-` 前缀，账面可读）。 */
    fun stableId(endpoint: String): String =
        "ag-" + PeerFingerprints.sha256Hex(PeerFingerprints.idSeed(normalize(endpoint))).take(12)
}

/**
 * 名册里一行身份（`.agents.jsonl` 的有效行）。
 *
 * [credentialFingerprint]／[capabilityFingerprint] 都是**指纹不是本体**：
 * 凭据本体只进 McpVault，能力本体在 `.mcp-peers.json`。空串 = 没有那一项。
 */
internal data class AgentRecord(
    val id: String,
    val name: String,
    val form: AgentForm,
    val endpoint: String,
    val credentialFingerprint: String,
    val capabilityFingerprint: String,
    val addedAtMs: Long,
    val updatedAtMs: Long,
    val removedAtMs: Long? = null,
) {
    val removed: Boolean get() = removedAtMs != null

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("id", id)
        .put("name", name)
        .put("form", form.wire)
        .put("endpoint", endpoint)
        .put("credFp", credentialFingerprint)
        .put("capFp", capabilityFingerprint)
        .put("addedAt", addedAtMs)
        .put("updatedAt", updatedAtMs)
        .put("removedAt", removedAtMs ?: JSONObject.NULL)

    companion object {
        const val VERSION = 1

        /** 读不出/形状不对 ⇒ null（调用方按没有这条处理，不让一条坏行毒死整本名册）。 */
        fun fromJson(o: JSONObject): AgentRecord? {
            val id = o.optString("id")
            val name = o.optString("name")
            val form = AgentForm.ofWire(o.optString("form")) ?: return null
            val endpoint = o.optString("endpoint")
            if (id.isEmpty() || name.isEmpty() || endpoint.isEmpty()) return null
            val removedAt = if (o.isNull("removedAt")) null else o.optLong("removedAt", 0L).takeIf { it > 0 }
            return AgentRecord(
                id = id,
                name = name,
                form = form,
                endpoint = endpoint,
                credentialFingerprint = o.optString("credFp"),
                capabilityFingerprint = o.optString("capFp"),
                addedAtMs = o.optLong("addedAt", 0L),
                updatedAtMs = o.optLong("updatedAt", 0L),
                removedAtMs = removedAt,
            )
        }
    }
}
