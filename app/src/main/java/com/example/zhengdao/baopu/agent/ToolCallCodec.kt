// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §4（协议 A 原生 function calling 定为默认；坏 JSON/坏参数
// 把错误原文回灌让模型重试 1 次，再坏即停）+ §5（协议只是解码器，执行只有一条路）。
// 帧级解析与流式累积在 baopu/LlmClient.kt 的 LlmSse（依赖单向：agent → baopu）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RawToolCall
import org.json.JSONArray
import org.json.JSONObject

/** 一个已解码的工具调用（协议 A 的产物；执行前还要过注册表校验）。 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject,
)

/**
 * 协议 A 解码与校验（程枢版 §4/§5）。
 *
 * **协议 A 与（将来的）协议 B 只是解码器**：本对象只负责把模型输出翻译成
 * [ToolCall] + 校验，执行一律走 ToolRegistry + GuardPipeline。
 *
 * 坏 JSON / 坏参数 ⇒ [DecodeResult.Bad] 带**错误原文**（回灌让模型重试，
 * 重试计数归引擎：同一轮里只给一次重试机会，再坏即停——程枢版 §4/§8）。
 */
internal object ToolCallCodec {

    sealed interface DecodeResult {
        /** 解码 + 校验全过（可直接进 GuardPipeline）。 */
        data class Ok(val calls: List<ToolCall>) : DecodeResult

        /** 坏 JSON / 坏参数 / 未知工具（error 是**错误原文**，回灌给模型）。 */
        data class Bad(val error: String) : DecodeResult
    }

    /**
     * 从流式累积出的原始调用 decode（协议 A 主线路径）。
     * @param registry 校验用（名字存在？参数合 Schema？）；null = 只解码不校验（单测钉纯解码）。
     */
    fun decode(raws: List<RawToolCall>, registry: ToolRegistry? = null): DecodeResult {
        if (raws.isEmpty()) return DecodeResult.Bad("模型声明了工具调用但没有给出任何调用内容")
        val out = mutableListOf<ToolCall>()
        for ((i, raw) in raws.withIndex()) {
            if (raw.name.isBlank()) {
                return DecodeResult.Bad("第 ${i + 1} 个工具调用缺少名字（tool_calls[$i].function.name 为空）")
            }
            val args = parseArgs(raw.argumentsJson)
                ?: return DecodeResult.Bad("工具「${raw.name}」的参数不是合法 JSON：${raw.argumentsJson}")
            registry?.validateCall(raw.name, args)?.let { return DecodeResult.Bad(it) }
            out += ToolCall(id = raw.id.ifBlank { "call_$i" }, name = raw.name, arguments = args)
        }
        return DecodeResult.Ok(out)
    }

    /**
     * 从 OpenAI 兼容响应的 `tool_calls` **数组** decode（非流式完整 JSON / 测试用）。
     * 与 [decode] 同一套判据——两处入口一张表。
     */
    fun decodeOpenAi(toolCalls: JSONArray, registry: ToolRegistry? = null): DecodeResult {
        if (toolCalls.length() == 0) return DecodeResult.Bad("模型返回了空的 tool_calls 数组")
        val raws = (0 until toolCalls.length()).mapNotNull { i ->
            val tc = toolCalls.optJSONObject(i) ?: return@mapNotNull null
            val fn = tc.optJSONObject("function")
            if (fn == null) null
            else RawToolCall(
                id = tc.optString("id").ifBlank { "call_$i" },
                name = fn.optString("name"),
                argumentsJson = fn.optString("arguments", "{}"),
            )
        }
        if (raws.size != toolCalls.length()) {
            return DecodeResult.Bad("tool_calls 里有条目不是对象或缺少 function 字段")
        }
        return decode(raws, registry)
    }

    /** 从 Anthropic 响应的 `content` 块里挑 `tool_use` 块解码（形状：input 已是对象）。 */
    fun decodeAnthropic(content: JSONArray, registry: ToolRegistry? = null): DecodeResult {
        val blocks = (0 until content.length())
            .map { content.optJSONObject(it) }
            .filter { it != null && it.optString("type") == "tool_use" }
        if (blocks.isEmpty()) return DecodeResult.Bad("Anthropic 响应里没有 tool_use 块")
        val out = mutableListOf<ToolCall>()
        blocks.forEachIndexed { i, b ->
            val name = b!!.optString("name")
            if (name.isBlank()) return DecodeResult.Bad("tool_use[$i].name 为空")
            val args = b.optJSONObject("input") ?: JSONObject()
            registry?.validateCall(name, args)?.let { return DecodeResult.Bad(it) }
            out += ToolCall(id = b.optString("id").ifBlank { "toolu_$i" }, name = name, arguments = args)
        }
        return DecodeResult.Ok(out)
    }

    /** arguments JSON 字符串 parse（**坏 JSON 不抛**：返回 null 由调用方转 Bad）。空串按空对象。 */
    fun parseArgs(raw: String): JSONObject? {
        val s = raw.trim()
        if (s.isEmpty()) return JSONObject()
        return runCatching { JSONObject(s) }.getOrNull()
    }
}
