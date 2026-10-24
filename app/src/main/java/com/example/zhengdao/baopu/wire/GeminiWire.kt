// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十八批 §1.3（2026-10-24）：encode 收口——本文件是 **Gemini 原生**协议的
// 请求体拼装，从 `baopu/LlmClient.kt` 的 `LlmWire.bodyOf` 字符级搬出（纯搬家，
// 请求体逐字节相同，对账尺见 `WireGoldenTest`）。工具声明转换（[toolDecls]）与
// schema 白名单（[sanitizeSchema]）随本文件一起搬家——§1.3 点名含它俩。
//
// put 序（= 真机上的字节序）：[systemInstruction], contents, [tools], generationConfig
package com.example.zhengdao.baopu.wire

import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.LlmWire
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.VisionRules
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini 原生协议（第一百五十四批）。
 *
 * 与 OpenAI/Anthropic 的差别（公开文档）：
 * - 没有 `messages`，是 `contents[]`，角色只有 `user` / `model`；
 * - system 走顶层 `systemInstruction.parts`；
 * - 工具是 `tools[].functionDeclarations[]`（**不是** `type:function` 包装）；
 * - 图片是 `inline_data{mime_type,data}`（base64，不带 data URL 前缀）；
 * - 输出上限在 `generationConfig.maxOutputTokens`，不是 `max_tokens`。
 */
internal object GeminiWire {

    fun body(
        p: Provider,
        messages: List<LlmMessage>,
        stream: Boolean = true,
        tools: JSONArray? = null,
    ): String {
        val system = messages.filter { it.role == LlmMessage.ROLE_SYSTEM }
            .joinToString("\n\n") { it.content }
        val rest = messages.filter { it.role != LlmMessage.ROLE_SYSTEM }
        val contents = JSONArray()
        rest.forEach { m ->
            val parts = JSONArray()
            if (m.content.isNotEmpty()) parts.put(JSONObject().put("text", m.content))
            m.images.forEach { url ->
                VisionRules.splitDataUrl(url)?.let { (mime, data) ->
                    parts.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject().put("mime_type", mime).put("data", data),
                        ),
                    )
                }
            }
            val role = if (m.role == LlmMessage.ROLE_ASSISTANT) "model" else "user"
            contents.put(JSONObject().put("role", role).put("parts", parts))
        }
        return JSONObject().apply {
            if (system.isNotEmpty()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
                )
            }
            put("contents", contents)
            if (tools != null && tools.length() > 0) {
                val decls = toolDecls(tools)
                put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put("functionDeclarations", decls),
                    ),
                )
            }
            put(
                "generationConfig",
                JSONObject().put("maxOutputTokens", p.maxOutput ?: LlmWire.GEMINI_MAX_TOKENS_FALLBACK),
            )
        }.toString()
    }

    /**
     * 压缩请求体（复用 [body] 的非流式分支；两条红线见 [OpenAiWire.completeBody]）。
     *
     * 与另两档的差别：上限字段在 `generationConfig.maxOutputTokens` 里
     * （body 里已经有一个，就地盖掉，不新建对象）。
     */
    fun completeBody(
        p: Provider,
        messages: List<LlmMessage>,
        maxTokens: Int,
        temperature: Double,
    ): String {
        val base = JSONObject(body(p, messages, stream = false, tools = null))
        val cfg = base.optJSONObject("generationConfig") ?: JSONObject().also {
            base.put("generationConfig", it)
        }
        cfg.put("maxOutputTokens", maxTokens)
        cfg.put("temperature", temperature)
        return base.toString()
    }

    /** OpenAI 形状的工具数组 → Gemini `functionDeclarations`（参数 schema 过一遍白名单）。 */
    internal fun toolDecls(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
            val d = JSONObject()
                .put("name", fn.optString("name"))
                .put("description", fn.optString("description"))
            fn.optJSONObject("parameters")?.let { schema ->
                val clean = sanitizeSchema(schema)
                // 2026-10-23 真机（Gemini 3.0 Flash Preview）：`parameters`（OpenAPI 风格）
                // 模型不认——我们发过去的 capabilities 参数表是完整的（日志 `[gemini-decls]`
                // 逐字可证），模型仍回 `{"items":[...]}` 而不是 `{"enable":[...]}`。
                // 改发 `parametersJsonSchema`（Gemini 也吃的 JSON Schema 形态，字段同名同义），
                // 两种都带上由服务端按支持的取：老模型走 parameters，新模型走 JsonSchema。
                // 只发 JsonSchema（两者同发会被服务端 400）
                d.put("parametersJsonSchema", clean)
            }
            out.put(d)
        }
        return out
    }

    /**
     * Gemini 只吃 OpenAPI 子集；其余关键词（`$schema`/`additionalProperties`…）会 400。
     *
     * 白名单每一项都有 fixture 钉住「哪家会丢哪个关键词、丢了会怎样」——
     * 见 `GeminiSchemaWhitelistTest`（第一百八十八批 §1.5：注释里那些考古结论
     * 已经变成测试名，改白名单前先看那儿）。
     */
    internal fun sanitizeSchema(src: JSONObject): JSONObject {
        val out = JSONObject()
        val keep = setOf(
            "type", "format", "description", "nullable", "enum", "required",
            "minItems", "maxItems", "title",
        )
        for (k in src.keys()) {
            when {
                k == "properties" -> {
                    val props = src.optJSONObject("properties") ?: continue
                    val o = JSONObject()
                    for (pk in props.keys()) {
                        props.optJSONObject(pk)?.let { o.put(pk, sanitizeSchema(it)) }
                    }
                    out.put("properties", o)
                }
                k == "items" -> src.optJSONObject("items")?.let { out.put("items", sanitizeSchema(it)) }
                // Gemini 的 Schema.type 是枚举（STRING/OBJECT/INTEGER/…），2026-10-23 真机：
                // 原样发小写时模型收不到参数表（它自己猜 keys：capabilities 传 items、
                // novel_create 漏 title），改发枚举名字后按 schema 出参。
                // 2026-10-23 真机二连：`parametersJsonSchema` 要的是**标准 JSON Schema**，
                // type 必须小写（`object`/`string`/`integer`…）。早先按 Gemini 老 Schema
                // 枚举发过 `OBJECT`/`STRING`，服务端不报错但模型当作没拿到参数表——
                // 真机表现就是 `list_dir` 回 `{"items":[…]}`、`capabilities` 回 `items`
                // （日志 20:05 复现）。这里统一小写。
                k == "type" -> out.put("type", src.optString("type").lowercase())
                k in keep -> out.put(k, src.get(k))
                else -> Unit
            }
        }
        return out
    }
}
