// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十五批 · 技能正文里的函数/工具示例按 provider 规则**写前预检**
// （2026-10-24 用户口径：「技能正文里的函数/工具示例要按 provider schema 预检，
// 别又像 Gemini 大写 type」）。
//
// 背景：Gemini 的 `parametersJsonSchema` 吃的是**标准 JSON Schema**，type 必须小写
// （`object`/`string`/…）。早先把老 `Schema.type` 的枚举大写（OBJECT/STRING）抄进去，
// 服务端不报错但模型当没拿到参数表——真机复现与修复都钉在 `GeminiWire.sanitizeSchema`
// 的注释里（2026-10-23 真机二连）。发送侧能救一次请求，救不了技能库：坏示例一旦沉淀
// 进 SKILL.md，下次照着错。本预检把这条口径挪到**写技能之前**：示例不合当前 provider
// 规则就拒写（`SkillStore.plan` 的 blocked 链尾），技能卡同步显示通过/失败原因。
//
// 三条红线（派工单 §0.5）：
//   1. 只做预检/拒绝/标注：不改 provider 请求层（`wire/` 一个字不碰）、不联网校验；
//   2. 能复用就不手写：Gemini 关键词白名单**镜像** `GeminiWire.sanitizeSchema` 的 keep 集
//      （逐字同步门禁见 `SkillSchemaPrecheckTest`）；仓库没有通用 JSON Schema 校验器，
//      也不为此加新依赖——org.json 就是仓库全线在用的解析器，三家规则差在**协议外壳**
//      （parametersJsonSchema / function.parameters / input_schema），外壳判据本来就
//      无法靠通用校验器代劳（报告 §二 有展开）；
//   3. 失败不许静默：findings 非空 ⇒ 拒写；预检器自己出错也按「不过」报原因，
//      绝不返回「通过」。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject

/**
 * 技能正文的 schema 预检（**纯函数**：不碰盘、不碰网、不碰时钟）。
 *
 * 规则只按**协议家族**（Gemini/OpenAI/Anthropic 三家的公开现行口径）判，
 * 不写死「某模型支持/不支持」——那是 [ModelCapabilityTable] 的事，且它对 UNKNOWN 的
 * 口径是「没依据 ≠ 不支持」，拿来挡写盘就是把没依据当依据。本类只管**形状**。
 */
internal object SkillSchemaPrecheck {

    /** 三家协议家族（不是某个模型：支持与否不写死，见类 KDoc）。 */
    enum class Family(val label: String) {
        GEMINI("Gemini"),
        OPENAI("OpenAI"),
        ANTHROPIC("Anthropic"),
    }

    /** 预检结果：[checked] = 实际按 provider 规则验过的工具声明数；[findings] 空 = 全过。 */
    data class Result(val checked: Int, val findings: List<String>)

    /**
     * Gemini Schema 关键词白名单：与 `GeminiWire.sanitizeSchema` 的 keep 集**逐字一致**，
     * 另补结构键 `properties`/`items`（keep 集是发送侧压码用的，那两键本来也放行）。
     * 白名单外的键发送侧会被压掉——技能示例里写了就是教人写「发过去会丢」的字段。
     */
    val GEMINI_SCHEMA_KEYWORDS: Set<String> = setOf(
        "type", "format", "description", "nullable", "enum", "required",
        "minItems", "maxItems", "title", "properties", "items",
    )

    /** OpenAI strict（structured outputs）支持的常用 JSON Schema 关键词子集。 */
    private val OPENAI_STRICT_KEYWORDS: Set<String> = setOf(
        "type", "properties", "required", "additionalProperties", "items", "enum",
        "description", "title", "format", "default", "anyOf", "\$ref", "\$defs", "definitions",
    )

    /** 标准 JSON Schema 的小写 type 集（大写 = 老 OpenAPI 枚举，按当前 API 拒）。 */
    private val JSON_TYPES: Set<String> = setOf(
        "object", "array", "string", "number", "integer", "boolean", "null",
    )

    /** name 合法性：OpenAI/Anthropic 公开口径「字母/数字/_/-，1–64 个」。 */
    private val NAME_PLAIN = Regex("[a-zA-Z0-9_-]{1,64}")

    /** name 合法性：Gemini 须以字母或下划线开头（公开文档口径），长度同上限。 */
    private val NAME_GEMINI = Regex("[a-zA-Z_][a-zA-Z0-9_-]{0,63}")

    /** ```json 围栏块抽取（语言标记大小写不敏感；围栏没闭合的块不算——那本来就没写完）。 */
    private val FENCE = Regex(
        "^```json[ \t]*\r?\n(.*?)^```[ \t]*",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 什么样的围栏块才按工具示例验（先按子串粗筛再解析，防把普通 JSON 配置误伤成「不合法」）。 */
    private val TOOL_MARKERS = listOf(
        "parametersJsonSchema", "input_schema", "functionDeclarations", "\"function\"",
    )

    /**
     * 给 `SkillStore.plan` 的 blocked 链尾用：null = 通过（或正文里没有可预检的示例）；
     * 非 null = 一句能直接给模型/用户看的原因（拒写就拒在这句话上）。
     *
     * 预检器自己出错也返回**拒因**而不是 null——「检查不了」不能变成「放行」。
     */
    fun blockedReason(body: String): String? {
        val r = runCatching { precheck(body) }.getOrElse {
            return "schema 预检器自身出错（${it.javaClass.simpleName}），按不过处理——先改正文再写"
        }
        if (r.findings.isEmpty()) return null
        val head = r.findings.take(3).joinToString("；")
        val more = if (r.findings.size > 3) "（等 ${r.findings.size} 处）" else ""
        return "schema 预检不过：$head$more"
    }

    /**
     * 扫一遍技能正文：抽 ```json 围栏块，认出工具声明的按**它自己声明的家族**验。
     *
     * 自动判定只认带当前 API 标记键的声明（`parametersJsonSchema`/`input_schema`/
     * `function.parameters`/`functionDeclarations`）——只有老 `parameters` 的裸声明
     * **不自动判定**（与 OpenAI 内层同名，猜了必误伤）；要按 Gemini 老形态验，
     * 调 [checkDeclaration] 显式给 [Family.GEMINI]。
     */
    fun precheck(body: String): Result {
        val findings = mutableListOf<String>()
        var checked = 0
        FENCE.findAll(body).forEachIndexed { bi, m ->
            val raw = m.groupValues[1]
            if (TOOL_MARKERS.none { raw.contains(it) }) return@forEachIndexed
            val parsed: Any? = runCatching { JSONObject(raw) }.getOrElse {
                runCatching { JSONArray(raw) }.getOrNull()
            }
            if (parsed == null) {
                findings += "第${bi + 1}个 ```json 块：看着是工具示例但 JSON 解析失败" +
                    "（少逗号/写成 JS 注释都会让照抄的人踩坑）"
                return@forEachIndexed
            }
            checked += walk(parsed, "第${bi + 1}个 ```json 块", findings)
        }
        return Result(checked, findings)
    }

    /**
     * 显式按指定家族验一条声明（自动扫描内部也走这里）。
     * 串了家族就拒——OpenAI 形状按 Anthropic 验时没有 `input_schema`，必报。
     */
    fun checkDeclaration(o: JSONObject, family: Family, where: String): List<String> = when (family) {
        Family.GEMINI -> checkGemini(o, where)
        Family.OPENAI -> checkOpenAi(o, where)
        Family.ANTHROPIC -> checkAnthropic(o, where)
    }

    // ── 家族外壳判据（每家：外壳键表 → name → 参数 schema） ─────────────────

    /** Gemini：`{name, description, parametersJsonSchema}`；当前 API 只认 parametersJsonSchema。 */
    private fun checkGemini(o: JSONObject, where: String): List<String> {
        val f = mutableListOf<String>()
        for (k in o.keys()) {
            when (k) {
                "name", "description", "parametersJsonSchema" -> Unit
                "parameters" ->
                    f += "$where：parameters 与 parametersJsonSchema 只留一个——当前 API 用 " +
                        "parametersJsonSchema（老的 parameters 是 OpenAPI 形态，2026-10-23 真机不认）"
                else -> f += "$where：多余字段「$k」（Gemini 函数声明只有 name/description/parametersJsonSchema）"
            }
        }
        if (o.optString("name").isEmpty()) {
            f += "$where：缺 name（Gemini 函数声明必须有 name）"
        } else if (!NAME_GEMINI.matches(o.optString("name"))) {
            f += "$where：name「${o.optString("name")}」不合 Gemini 口径（字母/下划线开头，只含字母/数字/_/-，≤64）"
        }
        val schema = o.optJSONObject("parametersJsonSchema")
        if (schema == null) {
            f += "$where：缺 parametersJsonSchema（当前 API 的 Gemini 函数声明必须带它，type 用小写标准 JSON Schema）"
        } else {
            f += checkSchema(schema, Family.GEMINI, strict = false, path = "$where.parametersJsonSchema", isRoot = true)
        }
        return f
    }

    /** OpenAI：`{type:"function", function:{name, description, parameters, strict}}`。 */
    private fun checkOpenAi(o: JSONObject, where: String): List<String> {
        val f = mutableListOf<String>()
        for (k in o.keys()) {
            if (k == "type" || k == "function") continue
            f += "$where：多余字段「$k」（OpenAI 工具条目只有 type/function）"
        }
        if (o.has("type") && o.optString("type") != "function") {
            f += "$where：type 必须是 \"function\"，现在是「${o.optString("type")}」"
        }
        val fn = o.optJSONObject("function")
        if (fn == null) {
            f += "$where：缺 function 对象（OpenAI 工具声明包在 function 里）"
            return f
        }
        for (k in fn.keys()) {
            if (k !in setOf("name", "description", "parameters", "strict")) {
                f += "$where.function：多余字段「$k」（function 里只有 name/description/parameters/strict）"
            }
        }
        if (fn.optString("name").isEmpty()) {
            f += "$where.function：缺 name"
        } else if (!NAME_PLAIN.matches(fn.optString("name"))) {
            f += "$where.function：name「${fn.optString("name")}」不合 OpenAI 口径（字母/数字/_/-，≤64）"
        }
        val params = fn.optJSONObject("parameters")
        if (params != null) {
            f += checkSchema(
                params,
                Family.OPENAI,
                strict = fn.optBoolean("strict", false),
                path = "$where.function.parameters",
                isRoot = true,
            )
        }
        return f
    }

    /** Anthropic：`{name, description, input_schema}`；input_schema 顶层必须 type:"object"。 */
    private fun checkAnthropic(o: JSONObject, where: String): List<String> {
        val f = mutableListOf<String>()
        for (k in o.keys()) {
            if (k in setOf("name", "description", "input_schema", "type", "cache_control")) continue
            f += "$where：多余字段「$k」（Anthropic 工具只有 name/description/input_schema，外加 type/cache_control）"
        }
        if (o.optString("name").isEmpty()) {
            f += "$where：缺 name"
        } else if (!NAME_PLAIN.matches(o.optString("name"))) {
            f += "$where：name「${o.optString("name")}」不合 Anthropic 口径（字母/数字/_/-，≤64）"
        }
        val schema = o.optJSONObject("input_schema")
        if (schema == null) {
            f += "$where：缺 input_schema（Anthropic 工具声明必须带它，顶层 type:\"object\"）"
        } else {
            f += checkSchema(schema, Family.ANTHROPIC, strict = false, path = "$where.input_schema", isRoot = true)
        }
        return f
    }

    // ── schema 体判据（三家共用；provider 差异只体现在 type 大小写与白名单上） ──

    private fun checkSchema(
        s: JSONObject,
        family: Family,
        strict: Boolean,
        path: String,
        isRoot: Boolean,
    ): List<String> {
        val f = mutableListOf<String>()
        val lowerCaseNote = if (family == Family.GEMINI) {
            "——parametersJsonSchema 的 type 大写会被服务端当没拿到参数表（2026-10-23 真机）"
        } else {
            ""
        }

        // ① type：字符串且在标准小写集里；顶层必须是 object（三家参数表的共同口径）。
        val typeVal = s.opt("type")
        val type: String? = when {
            typeVal == null -> {
                f += "$path：缺 \"type\"" + if (isRoot) "（参数表顶层必须是 {\"type\": \"object\"}）" else ""
                null
            }
            typeVal is String -> {
                if (typeVal !in JSON_TYPES) {
                    f += "$path：type 必须是标准 JSON Schema 的小写类型（object/array/string/…），" +
                        "现在是「$typeVal」$lowerCaseNote"
                }
                typeVal
            }
            typeVal is JSONArray -> {
                for (i in 0 until typeVal.length()) {
                    val t = typeVal.optString(i)
                    if (t.isEmpty() || t !in JSON_TYPES) {
                        f += "$path：type 数组里的「$t」不是标准小写 JSON Schema 类型$lowerCaseNote"
                    }
                }
                null
            }
            else -> {
                f += "$path：type 得是字符串（或字符串数组）"
                null
            }
        }
        if (isRoot && type != null && type != "object") {
            f += "$path：参数表顶层 type 必须是 \"object\"，现在是「$type」（函数参数描述的是一个对象）"
        }

        // ② 关键词白名单：Gemini 全程、OpenAI 仅 strict（非 strict 的未知键官方是忽略不是 400）。
        val whitelist = when {
            family == Family.GEMINI -> GEMINI_SCHEMA_KEYWORDS
            strict -> OPENAI_STRICT_KEYWORDS
            else -> null
        }
        if (whitelist != null) {
            for (k in s.keys()) {
                if (k !in whitelist) {
                    val why = if (family == Family.GEMINI) {
                        "Gemini 不认的关键词（白名单外发过去会被 400/丢参数表；白名单与 GeminiWire.sanitizeSchema 同步）"
                    } else {
                        "OpenAI strict 模式不支持的关键词"
                    }
                    f += "$path：${why}「$k」"
                }
            }
        }

        // ③ 结构：properties / required / items / additionalProperties / enum。
        // strict 的两条硬规则（additionalProperties:false、required 列全）只压在
        // **object 型 schema** 上——{"type":"string"} 这类标量层官方不要求（否则正例全灭）。
        val isObjectSchema = type == "object" ||
            (type == null && (s.has("properties") || s.has("required")))
        val props = s.optJSONObject("properties")
        s.opt("properties")?.let {
            if (props == null) f += "$path.properties：得是对象（字段名 → schema）"
        }
        props?.let { p ->
            for (k in p.keys()) {
                val sub = p.optJSONObject(k)
                if (sub == null) {
                    f += "$path.properties.$k：每个字段都得是 schema 对象"
                } else {
                    f += checkSchema(sub, family, strict, path = "$path.properties.$k", isRoot = false)
                }
            }
        }
        s.opt("required")?.let { req ->
            val names = mutableListOf<String>()
            if (req is JSONArray) {
                for (i in 0 until req.length()) {
                    val n = req.optString(i)
                    if (n.isEmpty()) {
                        f += "$path.required：得是字符串数组"
                    } else {
                        names += n
                    }
                }
            } else {
                f += "$path.required：得是字符串数组"
            }
            for (n in names) {
                if (props == null || !props.has(n)) {
                    f += "$path：required 里的「$n」在 properties 里没有对应字段（补上定义或从 required 拿掉）"
                }
            }
            if (strict && isObjectSchema && props != null) {
                for (k in props.keys()) {
                    if (k !in names) {
                        f += "$path：strict 模式 required 要列全所有字段（缺「$k」；可选字段用 [\"x\", \"null\"] 型表达）"
                    }
                }
            }
        }
        s.opt("items")?.let { items ->
            val o = items as? JSONObject
            if (o == null) {
                f += "$path.items：得是单个 schema 对象（不支持元组数组）"
            } else {
                f += checkSchema(o, family, strict, path = "$path.items", isRoot = false)
            }
        }
        if (isObjectSchema) {
            s.opt("additionalProperties")?.let { ap ->
                when {
                    strict && ap is JSONObject ->
                        f += "$path.additionalProperties：strict 模式只能是 false，不能是子 schema"
                    strict && ap != false ->
                        f += "$path：strict 模式每个 object 都要 \"additionalProperties\": false"
                    ap is JSONObject ->
                        f += checkSchema(ap, family, strict, path = "$path.additionalProperties", isRoot = false)
                }
            }
            if (strict && !s.has("additionalProperties")) {
                f += "$path：strict 模式每个 object 都要 \"additionalProperties\": false（缺）"
            }
        }
        s.opt("enum")?.let { e ->
            if (e !is JSONArray || e.length() == 0) f += "$path.enum：得是非空数组"
        }
        return f
    }

    // ── 正文遍历：认出声明本体与包装层 ─────────────────────────────────────

    /** 返回这次验过的声明数（findings 就地累加）。 */
    private fun walk(node: Any?, where: String, findings: MutableList<String>): Int = when (node) {
        is JSONObject -> walkObject(node, where, findings)
        is JSONArray -> {
            var n = 0
            for (i in 0 until node.length()) n += walk(node.opt(i), "$where[$i]", findings)
            n
        }
        else -> 0
    }

    private fun walkObject(o: JSONObject, where: String, findings: MutableList<String>): Int {
        // Gemini 请求体的 tools 包装：{"functionDeclarations":[…]}——逐条按 Gemini 验。
        o.optJSONArray("functionDeclarations")?.let { decls ->
            var n = 0
            for (i in 0 until decls.length()) {
                n++
                val el = decls.optJSONObject(i)
                if (el == null) {
                    findings += "$where.functionDeclarations[$i]：函数声明得是对象"
                } else {
                    findings += checkGemini(el, "$where.functionDeclarations[$i]")
                }
            }
            return n
        }
        val family = when {
            o.has("parametersJsonSchema") -> Family.GEMINI
            o.has("input_schema") -> Family.ANTHROPIC
            o.optJSONObject("function") != null -> Family.OPENAI
            else -> {
                // 不是声明本体：看它是不是还包着一层（{"tools":[…]} 的请求体片段）。
                o.optJSONArray("tools")?.let { return walk(it, "$where.tools", findings) }
                return 0
            }
        }
        findings += checkDeclaration(o, family, where)
        return 1
    }
}
