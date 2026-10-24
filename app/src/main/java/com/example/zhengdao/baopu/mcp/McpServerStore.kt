// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第一百七十批 B · MCP 真入口：server 配置的**落盘**（prefs/JSON，纯 JVM 编解码）。
//
// 红线三条（与第一百二十三批一脉相承）：
// 1. 不碰 BaopuDb：配置是「用户手填的地址 + 名字」，没有关系、没有迁移、没有版本号；
//    换成 DB 表就等于要动 VERSION，本批明令不许。
// 2. 请求头（可能带 key）**不落盘**：写盘只用白名单字段（id/name/url/两个超时），
//    从盘里读回来的配置 headers 恒为空——见 [McpServerStore.encode]。
// 3. 落盘 ≠ 连接：load 回来的 server 一律 OFFLINE，联网只在 McpToolSource.connect。
package com.example.zhengdao.baopu.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一份字符串存储（唯一目的是让编解码在纯 JVM 里可测）。
 *
 * 生产实现是 SharedPreferences（[PrefsMcpServerStorage]）；单测注入内存假件。
 */
internal interface McpServerStorage {
    fun read(): String?

    /** 写入整份 JSON（覆盖）；实现方失败可以抛，[McpServerStore.save] 会兜住并返回 false。 */
    fun write(json: String)
}

/**
 * MCP server 配置的本地账（JSON，最多 [MAX_SERVERS] 条）。
 *
 * 为什么整份覆盖而不是逐条增删：条数上限只有 16，整份写 = 不会出现「删了一半」的中间态，
 * 也不需要在 SP 里维护索引。
 */
internal class McpServerStore(
    private val storage: McpServerStorage,
    private val log: (String) -> Unit = {},
) {

    /**
     * 读回上次登记的配置。
     *
     * 纪律：**坏条目只跳过自己，不整份丢**（用户可能手改过 prefs，或旧版本写过异构字段）；
     * 任何一条构造失败都不打印地址/异常原文（`McpServerConfig` 的 require 消息里带 URL）。
     */
    fun load(): List<McpServerConfig> {
        val raw = try {
            storage.read()
        } catch (e: Exception) {
            log("[mcp] 读取已保存的 server 配置失败（${e.javaClass.simpleName}），按空处理")
            return emptyList()
        }
        if (raw.isNullOrBlank()) return emptyList()
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            log("[mcp] 已保存的 server 配置不是合法 JSON，按空处理")
            return emptyList()
        }
        val array = root.optJSONArray(KEY_SERVERS) ?: return emptyList()

        val out = ArrayList<McpServerConfig>(minOf(array.length(), MAX_SERVERS))
        val seen = HashSet<String>(array.length())
        for (i in 0 until array.length()) {
            if (out.size >= MAX_SERVERS) {
                log("[mcp] 已保存的 server 超过 $MAX_SERVERS 条，多余的没读")
                break
            }
            val one = array.optJSONObject(i)
            if (one == null) {
                log("[mcp] 已保存的第 ${i + 1} 条不是对象，已跳过")
                continue
            }
            val config = decode(one, i)
            if (config == null) continue
            if (!seen.add(config.id)) {
                log("[mcp] 已保存的配置里 id 重复（第 ${i + 1} 条），只留第一条")
                continue
            }
            out += config
        }
        return out
    }

    /**
     * 写回整份配置。
     *
     * @return false = 写失败（SP 异常）；调用方**不**因此中断内存里的登记（内存是这次会话的真相）。
     */
    fun save(configs: List<McpServerConfig>): Boolean {
        val kept = configs.take(MAX_SERVERS)
        val array = JSONArray()
        kept.forEach { array.put(encode(it)) }
        val root = JSONObject().put("v", FORMAT_VERSION).put(KEY_SERVERS, array)
        return try {
            storage.write(root.toString())
            if (configs.size > kept.size) {
                log("[mcp] 最多保存 $MAX_SERVERS 个 server，第 ${kept.size + 1} 条起没写")
            }
            true
        } catch (e: Exception) {
            log("[mcp] 保存 server 配置失败（${e.javaClass.simpleName}）")
            false
        }
    }

    /**
     * 配置 → JSON：**只写白名单字段**。
     *
     * 请求头一律不写（哪怕内存里填了）：prefs 是明文，写进去等于把 key 留在手机上。
     * 这条不是"暂时没做"，是设计 §1 的红线；反序列化侧同样恒为空。
     */
    private fun encode(config: McpServerConfig): JSONObject = JSONObject()
        .put("id", config.id)
        .put("name", config.name)
        .put("url", config.url)
        .put("call_timeout_ms", config.callTimeoutMs)
        .put("connect_timeout_ms", config.connectTimeoutMs)

    /** 单条解码；不合法返回 null（[McpServerConfig] 的 require 是唯一的形状闸）。 */
    private fun decode(raw: JSONObject, index: Int): McpServerConfig? {
        val id = raw.optString("id").trim()
        val name = raw.optString("name").trim()
        val url = raw.optString("url").trim()
        return try {
            McpServerConfig(
                id = id,
                name = name,
                url = url,
                // 红线：盘里没有头，读回来也就没有头。
                headers = emptyMap(),
                callTimeoutMs = timeout(raw, "call_timeout_ms", McpServerConfig.DEFAULT_CALL_TIMEOUT_MS),
                connectTimeoutMs = timeout(raw, "connect_timeout_ms", McpServerConfig.DEFAULT_CONNECT_TIMEOUT_MS),
            )
        } catch (e: IllegalArgumentException) {
            // 不打印 e.message：McpServerConfig 的 require 文案里带 url / 头名。
            log("[mcp] 已保存的第 ${index + 1} 条 server 配置不合法，已跳过")
            null
        }
    }

    private fun timeout(raw: JSONObject, key: String, fallback: Long): Long =
        raw.optLong(key, fallback).coerceIn(McpServerConfig.MIN_TIMEOUT_MS, McpServerConfig.MAX_TIMEOUT_MS)

    companion object {
        /** 条数上限（prefs 是整份改写，条数越多每次写越贵；16 条远超实际用量）。 */
        const val MAX_SERVERS = 16

        private const val FORMAT_VERSION = 1
        private const val KEY_SERVERS = "servers"
    }
}

/**
 * 从用户输入的地址造一份 [McpServerConfig]（纯函数；UI 与单测共用）。
 *
 * id 由地址派生（`s` + 地址短哈希）：同地址自然撞同一个 id ⇒ `addServer` 拒收重复，
 * 面板上不会出现两个一样的服务器。
 */
internal object McpServerDraft {

    sealed interface Result {
        data class Ok(val config: McpServerConfig) : Result

        /** 人话原因（已是给用户看的中文，UI 直接拼）。 */
        data class Invalid(val message: String) : Result
    }

    fun fromUrl(raw: String): Result {
        val url = raw.trim()
        if (url.isEmpty()) return Result.Invalid("地址不能为空")
        // 复用 McpUrlRules（https / 环回 http / 不许 userInfo / 不许 fragment）——
        // 与 Provider.isPermittedBaseUrl 同口径，UI 不复刻第二套判断。
        McpUrlRules.check(url)?.let { return Result.Invalid(it) }

        val host = try {
            java.net.URI(url).host.orEmpty()
        } catch (e: Exception) {
            ""
        }
        val name = displayName(host)
        return try {
            Result.Ok(
                McpServerConfig(
                    id = "s" + McpText.shortHash(url),
                    name = name,
                    url = url,
                ),
            )
        } catch (e: IllegalArgumentException) {
            Result.Invalid(e.message ?: "地址不可用")
        }
    }

    /** 名字只用来在面板上认人：取去掉 www. 的主机名，压平控制字符并截断。 */
    private fun displayName(host: String): String {
        val flat = McpText.flat(host.lowercase().removePrefix("www.")).ifBlank { "MCP" }
        return McpText.clip(flat, 40)
    }
}
