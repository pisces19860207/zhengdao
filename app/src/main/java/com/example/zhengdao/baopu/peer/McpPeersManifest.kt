// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · 对端**能力清单**（`产物/.mcp-peers.json`）与指纹对比。
//
// 每个 peer 记（派工单 548「能力清单」）：endpoint · 协议版本 · capabilities ·
// 工具列表（名字/描述/入参 schema 的 sha256 指纹）· 上次探测时间 · 探测结果。
// TTL 默认 24h，过期标「陈旧」——**不自动沿用**（判据⑬），要新数据就再探一次。
//
// 指纹对比是 rug-pull 防线：这次探到的与上次存的对，schema 指纹变了就报
// 「能力变了」并且**不自动启用**（判据③⑪；探测本身从不注册工具、更不调用）。
package com.example.zhengdao.baopu.peer

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 清单里一个工具条目（名字/描述/入参 schema 指纹——**没有** schema 本体，防清单变大）。 */
internal data class PeerToolFp(
    val name: String,
    val description: String,
    val schemaSha256: String,
) {
    fun toJson(): JSONObject =
        JSONObject().put("name", name).put("description", description).put("schemaSha256", schemaSha256)

    companion object {
        fun fromJson(o: JSONObject): PeerToolFp? {
            val name = o.optString("name")
            if (name.isEmpty()) return null
            return PeerToolFp(
                name = name,
                description = o.optString("description"),
                schemaSha256 = o.optString("schemaSha256"),
            )
        }
    }
}

/** 一台 MCP 对端的探测清单行。 */
internal data class PeerManifestEntry(
    val agentId: String,
    val serverId: String,
    val name: String,
    val endpoint: String,
    val protocolVersion: String,
    /** 传输模式（`streamable-http` / `legacy-sse`）。 */
    val mode: String,
    /** 服务器在 initialize 里声明的能力键（tools/resources/prompts/logging…，只存键名）。 */
    val capabilities: List<String>,
    val tools: List<PeerToolFp>,
    val probedAtMs: Long,
    /** 上一次探测结果（[PeerFailureCode.wire]）。 */
    val probeResult: String,
) {
    /** TTL（默认 24h）：过期 ⇒ 陈旧，调用方不得当新鲜数据用。 */
    fun isStale(nowMs: Long, ttlMs: Long = DEFAULT_TTL_MS): Boolean = nowMs - probedAtMs >= ttlMs

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("agentId", agentId)
        .put("serverId", serverId)
        .put("name", name)
        .put("endpoint", endpoint)
        .put("protocolVersion", protocolVersion)
        .put("mode", mode)
        .put("capabilities", JSONArray(capabilities))
        .put("tools", JSONArray().apply { tools.forEach { put(it.toJson()) } })
        .put("probedAt", probedAtMs)
        .put("probeResult", probeResult)

    companion object {
        const val VERSION = 1
        const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L

        fun fromJson(o: JSONObject): PeerManifestEntry? {
            val agentId = o.optString("agentId")
            val endpoint = o.optString("endpoint")
            if (agentId.isEmpty() || endpoint.isEmpty()) return null
            return PeerManifestEntry(
                agentId = agentId,
                serverId = o.optString("serverId"),
                name = o.optString("name"),
                endpoint = endpoint,
                protocolVersion = o.optString("protocolVersion"),
                mode = o.optString("mode"),
                capabilities = o.optJSONArray("capabilities")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }
                }.orEmpty(),
                tools = o.optJSONArray("tools")?.let { a ->
                    (0 until a.length()).mapNotNull { PeerToolFp.fromJson(a.optJSONObject(it) ?: return@mapNotNull null) }
                }.orEmpty(),
                probedAtMs = o.optLong("probedAt", 0L),
                probeResult = o.optString("probeResult"),
            )
        }
    }
}

internal object McpPeersManifest {

    const val FILE_NAME = ".mcp-peers.json"

    fun fileIn(root: File): File = File(File(root, "产物"), FILE_NAME)

    /** 全量读（agentId → 行）。文件不存在/坏了 ⇒ 空表，不抛（清单是证据不是闸门）。 */
    fun load(root: File): Map<String, PeerManifestEntry> {
        val f = fileIn(root)
        if (!f.isFile) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val arr = o.optJSONArray("peers") ?: JSONArray()
            (0 until arr.length()).mapNotNull { PeerManifestEntry.fromJson(arr.optJSONObject(it) ?: return@mapNotNull null) }
                .associateBy { it.agentId }
        }.getOrDefault(emptyMap())
    }

    /** 全量写（read-modify-write 由调用方持根；写失败返回 false，不动旧文件内容语义）。 */
    fun save(root: File, entries: Collection<PeerManifestEntry>): Boolean {
        val arr = JSONArray()
        entries.sortedBy { it.agentId }.forEach { arr.put(it.toJson()) }
        val doc = JSONObject().put("v", PeerManifestEntry.VERSION).put("peers", arr)
        return runCatching {
            val f = fileIn(root)
            f.parentFile?.mkdirs()
            f.writeText(doc.toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /** 合并写入一行（其余行原样保留）。 */
    fun upsert(root: File, entry: PeerManifestEntry): Boolean {
        val merged = load(root).toMutableMap()
        merged[entry.agentId] = entry
        return save(root, merged.values)
    }
}

/** 能力变化报告（上次清单 vs 这次探测；rug-pull 防线的结论件）。 */
internal data class CapabilityChangeReport(
    val changed: Boolean,
    val addedTools: List<String>,
    val removedTools: List<String>,
    /** schema 指纹变了的工具（**这台悄悄改了入参约定**——最要命的一种）。 */
    val schemaChanged: List<String>,
    val capabilityKeysChanged: Boolean,
) {
    /** 给人看的那句（判据③：提示「能力变了」，不静默接受）。 */
    fun human(): String {
        if (!changed) return ""
        val parts = ArrayList<String>(3)
        if (capabilityKeysChanged) parts.add("声明的 capabilities 变了")
        addedTools.take(3).forEach { parts.add("新增工具「$it」") }
        if (addedTools.size > 3) parts.add("…共 ${addedTools.size} 个新工具")
        removedTools.take(3).forEach { parts.add("少了工具「$it」") }
        if (removedTools.size > 3) parts.add("…共 ${removedTools.size} 个工具消失")
        schemaChanged.take(3).forEach { parts.add("「$it」的入参约定变了") }
        if (schemaChanged.size > 3) parts.add("…共 ${schemaChanged.size} 个工具改了入参")
        return "这台设备的能力变了（" + parts.joinToString("；") + "）。已按新清单记录，但没有自动启用任何工具——要用请先过一遍"
    }

    companion object {
        val NONE = CapabilityChangeReport(false, emptyList(), emptyList(), emptyList(), false)
    }
}

internal fun diffCapabilities(old: PeerManifestEntry?, new: PeerManifestEntry): CapabilityChangeReport {
    if (old == null) return CapabilityChangeReport.NONE
    val oldTools = old.tools.associateBy { it.name }
    val newTools = new.tools.associateBy { it.name }
    val added = (newTools.keys - oldTools.keys).sorted()
    val removed = (oldTools.keys - newTools.keys).sorted()
    val schemaChanged = newTools.keys.intersect(oldTools.keys)
        .filter { newTools[it]!!.schemaSha256 != oldTools[it]!!.schemaSha256 }
        .sorted()
    val capsChanged = old.capabilities.toSortedSet() != new.capabilities.toSortedSet()
    val changed = added.isNotEmpty() || removed.isNotEmpty() || schemaChanged.isNotEmpty() || capsChanged
    return CapabilityChangeReport(changed, added, removed, schemaChanged, capsChanged)
}
