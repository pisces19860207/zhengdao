// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十八批 §1.1（2026-10-24）：历史形状门禁。
//
// 病灶（立单时写错、核过代码后更正）：我们的历史从来是纯文本
// （`HistoryMessage(id, role, text)`，工具结果以 `<tool_result>` 围栏文本回灌），
// 结构上不可能出现「assistant 的 tool_use 落了、result 没落」的孤儿块。真正要防的是
// **将来的人**：谁为了「保真」往历史/请求体里加一条 provider 原生工具块
// （Anthropic `tool_use`/`tool_result`、Gemini `functionCall`、OpenAI `tool_calls`/`role=tool`），
// 换模型/换家就会在真机上炸结构性 400，且**每轮都炸、会话级卡死**——那种坑只在真机上现形。
//
// 本文件把「我们永远不落原生块」钉成一条可执行的判据：纯函数，JVM 直测，红绿各一条。
// 它**不进生产调用链**（判据是源码/单测级的；真要在发送前加一道断言是以后的增强，
// 到时直接复用这两个函数，不必再写第二套）。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject

/**
 * 历史与请求体的形状门禁（第一百八十八批 §1.1）。
 *
 * 两个入口：
 * - [historyViolations]：引擎在途历史（[LlmMessage] 列表）——**我们只发纯文本**，
 *   任何一条消息变成原生工具块即红；
 * - [bodyViolations]：已经拼好的请求体 JSON——消息级内容里出现原生工具块即红
 *   （顶层 `tools` 声明数组是合法的，不扫）。
 *
 * 判据口径（三家公开文档形状）：
 * - Anthropic：`content` 块 `type` ∈ {`tool_use`, `tool_result`} 是原生工具块；
 * - Gemini：`parts` 里的 `functionCall` 是原生工具块；
 * - OpenAI：assistant 消息的 `tool_calls`、以及 `role=tool` 的消息是原生工具块。
 */
internal object HistoryShapeGate {

    /** OpenAI 兼容的原生工具角色（我们的历史只有 user/assistant/system 三个纯文本角色）。 */
    const val ROLE_TOOL = "tool"

    /** Anthropic content 块里的原生工具类型。 */
    private val NATIVE_BLOCK_TYPES = setOf("tool_use", "tool_result")

    /** 原生工具块的 JSON 键（Gemini 部件键 / OpenAI 消息键）。 */
    private val NATIVE_BLOCK_KEYS = setOf("functionCall", "tool_calls")

    /**
     * 在途历史（请求侧 [LlmMessage] 列表）的形状检查（空表 = 干净）。
     *
     * 判据只认**结构**：消息角色是原生 tool 角色，或消息文本整个是一个带原生块键的
     * JSON 对象/数组。纯文本里偶然出现 "tool_use" 二字不算——那是正文，不是块。
     */
    fun historyViolations(messages: List<LlmMessage>): List<String> =
        scanHistory(messages.map { it.role to it.content })

    /** 同上，吃库侧历史（[HistoryMessage]；续跑回放链的形状判据）。 */
    fun replayHistoryViolations(messages: List<HistoryMessage>): List<String> =
        scanHistory(messages.map { it.role to it.text })

    private fun scanHistory(entries: List<Pair<String, String>>): List<String> {
        val out = mutableListOf<String>()
        entries.forEachIndexed { i, (role, content) ->
            if (role == ROLE_TOOL) {
                out += "history[$i] 用了 OpenAI 原生 role=tool（我们的历史只有纯文本三角色）"
            }
            val text = content.trim()
            when {
                text.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()
                    ?.let { out += blockViolations(it, "history[$i]") }

                text.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull()
                    ?.let { arr ->
                        for (j in 0 until arr.length()) {
                            arr.optJSONObject(j)?.let { out += blockViolations(it, "history[$i][$j]") }
                        }
                    }
            }
        }
        return out
    }

    /**
     * 请求体的形状检查（空表 = 干净）。
     *
     * 按协议扫**消息级**内容（OpenAI/Anthropic 的 `messages[]`、Gemini 的
     * `contents[].parts[]`）；顶层 `tools`/`functionDeclarations` 是工具**声明**，
     * 不是历史块，不扫。请求体不是合法 JSON 也算一条（拼错成空串/半截同罪）。
     */
    fun bodyViolations(protocol: LlmProtocol, body: String): List<String> {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return listOf("请求体不是合法 JSON（形状门禁不猜内容）")
        return when (protocol) {
            LlmProtocol.OPENAI_COMPAT, LlmProtocol.ANTHROPIC ->
                messageViolations(root.optJSONArray("messages"), protocol)

            LlmProtocol.GEMINI_NATIVE -> {
                val out = mutableListOf<String>()
                val contents = root.optJSONArray("contents") ?: return out
                for (i in 0 until contents.length()) {
                    val parts = contents.optJSONObject(i)?.optJSONArray("parts") ?: continue
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        if (part.has("functionCall")) {
                            out += "contents[$i].parts[$j] 是 Gemini 原生 functionCall 部件" +
                                "（我们的历史只发纯文本部件）"
                        }
                    }
                }
                out
            }
        }
    }

    /** OpenAI/Anthropic 两档共用的 messages 扫描（原生块形状相同：块 type 或 tool_calls）。 */
    private fun messageViolations(messages: JSONArray?, protocol: LlmProtocol): List<String> {
        val out = mutableListOf<String>()
        if (messages == null) return out
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == ROLE_TOOL) {
                out += "messages[$i] 是 OpenAI 原生 role=tool 消息（${protocol.name} 不该出现）"
            }
            m.optJSONArray("tool_calls")?.let {
                out += "messages[$i] 带 OpenAI 原生 tool_calls（我们的历史只发纯文本）"
            }
            val content = m.opt("content")
            if (content is JSONArray) {
                for (j in 0 until content.length()) {
                    content.optJSONObject(j)?.let { out += blockViolations(it, "messages[$i].content[$j]") }
                }
            }
        }
        return out
    }

    /** 一个 JSON 对象是不是原生工具块（块类型键 / 原生块键二选一即红）。 */
    private fun blockViolations(obj: JSONObject, where: String): List<String> {
        val out = mutableListOf<String>()
        val type = obj.optString("type")
        if (type in NATIVE_BLOCK_TYPES) {
            out += "$where 是 Anthropic 原生工具块 type=$type（我们的历史只发纯文本）"
        }
        NATIVE_BLOCK_KEYS.forEach { key ->
            if (obj.has(key)) out += "$where 带原生工具块键「$key」（我们的历史只发纯文本）"
        }
        return out
    }
}
