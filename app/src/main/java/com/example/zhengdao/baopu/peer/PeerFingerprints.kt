// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · 外部 agent 的三把指纹（**全仓唯一实现**，不许各算各的）。
//
// 为什么 schema 指纹要先规范化再哈希：`JSONObject.toString()` 的键序跟解析顺序走，
// 上游只是把 JSON 重排一遍键就会让指纹翻脸——那不是「能力变了」，是尺子不稳。
// 规范化 = 键递归排序 + 数组保序 + 无空白：**内容变了指纹才变**（rug-pull 防线的那把尺）。
// 串表进哈希一律走 JSON 数组的规范化串（每项带引号），拼接天然无粘连歧义。
package com.example.zhengdao.baopu.peer

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal object PeerFingerprints {

    /** 任意 JSON 值的规范化串（键排序、数组保序、无空白）。 */
    fun canonicalJson(o: JSONObject): String {
        val keys = o.keys().asSequence().toSortedSet()
        return keys.joinToString(",", "{", "}") { key -> quote(key) + ":" + canonicalValue(o.opt(key)) }
    }

    private fun canonicalValue(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> canonicalJson(v)
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonicalValue(v.opt(it)) }
        is Boolean, is Number -> v.toString()
        else -> quote(v.toString())
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** 串表 → 规范化 JSON 数组串（每项带引号，拼接无歧义）。 */
    private fun wireOf(items: List<String>): String {
        val arr = JSONArray()
        items.sorted().forEach { arr.put(it) }
        return canonicalValue(arr)
    }

    /** sha256 全量十六进制（小写）。 */
    fun sha256Hex(text: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }.getOrDefault("00000000000000000000000000000000")

    /** 一个工具入参 schema 的指纹（规范化后 sha256；探测清单与判「能力变了」都用它）。 */
    fun ofToolSchema(schema: JSONObject): String = sha256Hex(canonicalJson(schema))

    /**
     * 一台对端的能力清单指纹：能力键 ＋ 每个工具的「名@schema指纹」一起进哈希——
     * 加/删工具、改 schema 都会让它变（`.agents.jsonl` 的能力指纹就是这一位）。
     */
    fun ofCapabilitySet(capabilities: List<String>, tools: List<PeerToolFp>): String =
        sha256Hex(wireOf(capabilities) + wireOf(tools.map { "${it.name}@${it.schemaSha256}" }))

    /** A2A 卡片的能力指纹（capabilities 键 ＋ skill id；卡片本身就是那台的能力清单）。 */
    fun ofAgentCard(capabilities: List<String>, skillIds: List<String>): String =
        sha256Hex("card" + wireOf(capabilities) + wireOf(skillIds))

    /**
     * 凭据指纹：**只回 16 位十六进制**，反推不出凭据本体（本体只进 McpVault）。
     * 没有凭据 ⇒ 空串（「没有」与「有但变了」是两种状态，不许混）。
     */
    fun ofCredential(material: String?): String =
        if (material.isNullOrBlank()) "" else sha256Hex("cred:" + wireOf(listOf(material))).take(16)

    /** 稳定 id 的原料：endpoint 规范化后进哈希（见 [AgentIds.normalize]）。 */
    internal fun idSeed(normalizedEndpoint: String): String = "agent:" + wireOf(listOf(normalizedEndpoint))
}
