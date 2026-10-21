// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十三批 · MCP 工具接入 v1：tools/list → 本地工具形状。
// 纯 JVM；schema 白名单与现有 SchemaCheck 的能力边界严格对齐。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.agent.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** 已转换、尚未注册的一个外部工具。 */
internal data class McpConvertedTool(
    /** 注册进 ToolRegistry 的名字（`mcp_<server>_<tool>`，≤64，ASCII）。 */
    val wireName: String,
    /** 服务器上的原始名字（`tools/call` 用它，不是 wireName）。 */
    val remoteName: String,
    val description: String,
    val inputSchema: JSONObject,
    /** 只读提示：首次确认后可免确认；危险工具仍每次确认。 */
    val readOnly: Boolean,
)

/**
 * MCP → ToolSpec 的转换（纯函数，JVM 直测）。
 *
 * 三条口径：
 * 1. **整 server 拒收**：任一工具 schema 出现现有 `SchemaCheck` 审不了的构造，抛
 *    [McpException]（[McpFailure.SCHEMA]）——宁可一个都看不见，也不挂「审不了」的 schema。
 * 2. **不覆盖本地工具**：`mcp_` 前缀撞到本地名字 ⇒ 拒收；撞到另一个 MCP 工具 ⇒ 稳定哈希后缀。
 * 3. **描述是外部数据**：压平、去控制字符、加「外部工具」前缀、截到 [ToolSpec.MAX_DESC]。
 */
internal object McpSchema {

    const val WIRE_PREFIX = "mcp_"
    const val MAX_WIRE_NAME = 64
    const val MAX_TOOLS = 64

    private val PROP_TYPES = setOf("string", "integer", "number", "boolean", "array")
    private val SCALAR_TYPES = setOf("string", "integer", "number", "boolean")

    /** 一个参数允许出现的关键字（其余一律拒收；见文件头第 1 条）。 */
    private val PROP_KEYS = setOf(
        "type", "description", "title", "enum", "minimum", "maximum", "items",
        "default", "examples",
    )

    /** 结构关键字：出现即拒收（现有 SchemaCheck 无法审）。 */
    private val FORBIDDEN_KEYS = setOf(
        "\$ref", "oneOf", "anyOf", "allOf", "not", "if", "then", "else",
        "patternProperties", "propertyNames", "dependencies", "dependentSchemas",
        "dependentRequired", "unevaluatedProperties", "unevaluatedItems",
        "contains", "prefixItems", "additionalItems", "const",
    )

    /**
     * 解析 `tools/list` 的 result。
     *
     * @throws McpException [McpFailure.PROTOCOL] 信封不对 / [McpFailure.SCHEMA] schema 不支持
     */
    fun parseToolList(
        serverId: String,
        serverName: String,
        result: JSONObject?,
    ): List<McpToolDescriptor> {
        if (result == null) throw McpException(McpFailure.PROTOCOL, "tools/list 没有 result")
        val tools = result.optJSONArray("tools")
            ?: throw McpException(McpFailure.PROTOCOL, "tools/list 的 result 里没有 tools 数组")
        if (tools.length() > MAX_TOOLS) {
            throw McpException(
                McpFailure.SCHEMA,
                "服务器公布了 ${tools.length()} 个工具，超过 v1 上限 $MAX_TOOLS——请减少后重连",
            )
        }
        val out = ArrayList<McpToolDescriptor>(tools.length())
        val seen = HashSet<String>()
        for (i in 0 until tools.length()) {
            val item = tools.optJSONObject(i)
                ?: throw McpException(McpFailure.SCHEMA, "tools[$i] 不是对象")
            val rawName = item.optString("name").trim()
            if (rawName.isEmpty() || rawName.length > 128) {
                throw McpException(McpFailure.SCHEMA, "tools[$i] 缺少合法的 name（1–128 字符）")
            }
            if (!seen.add(rawName)) {
                throw McpException(McpFailure.SCHEMA, "服务器重复公布工具「$rawName」")
            }
            val rawSchema = item.optJSONObject("inputSchema")
                ?: throw McpException(McpFailure.SCHEMA, "工具「$rawName」没有 inputSchema 对象")
            val schema = normalizeSchema(rawName, rawSchema)
            val ann = item.optJSONObject("annotations")
            val readOnly = ann?.optBoolean("readOnlyHint", false) == true &&
                ann.optBoolean("destructiveHint", false) != true
            out.add(
                McpToolDescriptor(
                    serverId = serverId,
                    serverName = serverName,
                    name = rawName,
                    description = item.optString("description"),
                    inputSchema = schema,
                    readOnly = readOnly,
                ),
            )
        }
        return out
    }

    /**
     * schema 白名单校验 + 归一。
     *
     * 输出只保留现有 [com.example.zhengdao.baopu.agent.SchemaCheck] 真能校验的部分；
     * `additionalProperties` 缺省补 `false`（服务端显式 `true` 则尊重）。
     *
     * @throws McpException [McpFailure.SCHEMA]
     */
    fun normalizeSchema(toolName: String, raw: JSONObject): JSONObject {
        forbidden(toolName, raw, "")
        val type = raw.optString("type")
        if (!type.equals("object", ignoreCase = true)) {
            throw schemaError(toolName, "顶层 type 必须是 object（实到「${type.ifEmpty { "缺省" }}」）")
        }
        val rawProps = raw.opt("properties")
        val props = when (rawProps) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> rawProps
            else -> throw schemaError(toolName, "properties 必须是对象")
        }
        val out = JSONObject().put("type", "object")

        // 逐个参数
        val outProps = JSONObject()
        for (key in props.keys()) {
            if (key.isBlank() || key.length > 128 || key.any { it.code < 0x20 || it.code == 0x7f }) {
                throw schemaError(toolName, "参数名不合法（空白/控制字符/超 128 字符）")
            }
            val ps = props.optJSONObject(key)
                ?: throw schemaError(toolName, "参数「$key」的 schema 不是对象")
            outProps.put(key, normalizeProperty(toolName, key, ps))
        }
        out.put("properties", outProps)

        // required：字符串数组、无重复、必须真的在 properties 里
        val required = raw.opt("required")
        val outRequired = JSONArray()
        if (required != null && required !== JSONObject.NULL) {
            if (required !is JSONArray) throw schemaError(toolName, "required 必须是字符串数组")
            val seen = HashSet<String>()
            for (i in 0 until required.length()) {
                val name = required.optString(i)
                if (name.isBlank()) throw schemaError(toolName, "required[$i] 不是非空字符串")
                if (!seen.add(name)) throw schemaError(toolName, "required 重复列出「$name」")
                if (!props.has(name)) {
                    throw schemaError(toolName, "required 里的「$name」不在 properties 里")
                }
                outRequired.put(name)
            }
        }
        if (outRequired.length() > 0) out.put("required", outRequired)

        // additionalProperties：缺省 false；显式 true 尊重；对象形态（自定义 schema）拒收
        val addl = raw.opt("additionalProperties")
        when (addl) {
            null, JSONObject.NULL -> out.put("additionalProperties", false)
            is Boolean -> out.put("additionalProperties", addl)
            else -> throw schemaError(toolName, "additionalProperties 只接受布尔值（v1 不支持自定义 schema）")
        }
        return out
    }

    private fun normalizeProperty(toolName: String, key: String, ps: JSONObject): JSONObject {
        // 先扫禁词（含 items 内部）
        for (k in ps.keys()) {
            if (k in FORBIDDEN_KEYS) throw schemaError(toolName, "参数「$key」用了 v1 不支持的关键字「$k」")
        }
        val type = ps.optString("type")
        if (type !in PROP_TYPES) {
            throw schemaError(
                toolName,
                "参数「$key」的类型「${type.ifEmpty { "缺省" }}」不支持（只允许 ${PROP_TYPES.joinToString("/")}）",
            )
        }
        for (k in ps.keys()) {
            if (k !in PROP_KEYS) {
                throw schemaError(toolName, "参数「$key」用了 v1 不支持的关键字「$k」")
            }
        }
        val out = JSONObject().put("type", type)
        val desc = McpText.clip(McpText.flat(ps.optString("description")), 300)
        if (desc.isNotEmpty()) out.put("description", desc)

        // enum：只对 string 开放（SchemaCheck 的 enum 分支只比字符串；别的类型等于没校验）
        ps.optJSONArray("enum")?.let { en ->
            if (type != "string") {
                throw schemaError(toolName, "参数「$key」的 enum 只支持 string 类型")
            }
            if (en.length() == 0) throw schemaError(toolName, "参数「$key」的 enum 不能为空")
            val clean = JSONArray()
            for (i in 0 until en.length()) {
                val v = en.opt(i)
                if (v !is String) throw schemaError(toolName, "参数「$key」的 enum 只支持字符串取值")
                clean.put(v)
            }
            out.put("enum", clean)
        }

        // minimum/maximum：只对数字类型开放
        val hasMin = ps.has("minimum")
        val hasMax = ps.has("maximum")
        if ((hasMin || hasMax) && type != "integer" && type != "number") {
            throw schemaError(toolName, "参数「$key」的 minimum/maximum 只支持数字类型")
        }
        if (hasMin) out.put("minimum", numberOrFail(toolName, key, "minimum", ps.opt("minimum")))
        if (hasMax) out.put("maximum", numberOrFail(toolName, key, "maximum", ps.opt("maximum")))

        // array：items 只允许标量（嵌套 object / 数组 / $ref 一律拒收）
        if (type == "array") {
            val items = ps.optJSONObject("items")
                ?: throw schemaError(toolName, "参数「$key」是 array，但 items 不是对象")
            for (k in items.keys()) {
                if (k in FORBIDDEN_KEYS) {
                    throw schemaError(toolName, "参数「$key」的 items 用了 v1 不支持的关键字「$k」")
                }
                if (k !in setOf("type", "description", "title")) {
                    throw schemaError(toolName, "参数「$key」的 items 用了 v1 不支持的关键字「$k」")
                }
            }
            val itemType = items.optString("type")
            if (itemType !in SCALAR_TYPES) {
                throw schemaError(
                    toolName,
                    "参数「$key」的 items 类型「${itemType.ifEmpty { "缺省" }}」不支持（只允许标量）",
                )
            }
            val cleanItems = JSONObject().put("type", itemType)
            val itemDesc = McpText.clip(McpText.flat(items.optString("description")), 300)
            if (itemDesc.isNotEmpty()) cleanItems.put("description", itemDesc)
            out.put("items", cleanItems)
        } else if (ps.has("items")) {
            throw schemaError(toolName, "参数「$key」不是 array，却带了 items")
        }
        return out
    }

    private fun numberOrFail(toolName: String, key: String, field: String, v: Any?): Number {
        if (v is Number) return v
        throw schemaError(toolName, "参数「$key」的 $field 必须是数字")
    }

    /** 递归找禁词（顶层/参数/items 的每个对象）。 */
    private fun forbidden(toolName: String, o: JSONObject, where: String) {
        for (k in o.keys()) {
            if (k in FORBIDDEN_KEYS) {
                throw schemaError(toolName, "${if (where.isEmpty()) "schema" else where} 用了 v1 不支持的关键字「$k」")
            }
            when (val v = o.opt(k)) {
                is JSONObject -> forbidden(toolName, v, "$where/$k")
                is JSONArray -> for (i in 0 until v.length()) {
                    (v.opt(i) as? JSONObject)?.let { forbidden(toolName, it, "$where/$k[$i]") }
                }
                else -> Unit
            }
        }
    }

    private fun schemaError(toolName: String, reason: String): McpException =
        McpException(McpFailure.SCHEMA, "工具「$toolName」的 schema 不受支持：$reason")

    /**
     * 批量转换（保持服务器给出的顺序）。
     *
     * @param existingNames 注册表当前已有的名字（调用方应扣掉「本 server 上一次注册的」）。
     * @param mcpOwnedNames 其中**属于其它 MCP server** 的名字；只有这些可以哈希避让，
     *        本地工具名一律整 server 拒收（设计 §7）。
     * @throws McpException [McpFailure.SCHEMA] 撞本地工具名 / 哈希后仍撞名
     */
    fun convertAll(
        descriptors: List<McpToolDescriptor>,
        existingNames: Set<String>,
        mcpOwnedNames: Set<String> = emptySet(),
    ): List<McpConvertedTool> {
        val used = HashSet<String>()
        return descriptors.map { d ->
            val base = wireName(d.serverId, d.name)
            var name = base
            if (name in existingNames) {
                if (name !in mcpOwnedNames) {
                    throw McpException(
                        McpFailure.SCHEMA,
                        "外部工具「${d.name}」转换后的名字「$name」撞上了本地工具，已整 server 拒收",
                    )
                }
                name = withHash(base, McpText.shortHash("${d.serverId}/${d.name}"))
            }
            if (name in used) {
                name = withHash(base, McpText.shortHash("${d.serverId}/${d.name}"))
            }
            if (name in existingNames || name in used) {
                throw McpException(
                    McpFailure.SCHEMA,
                    "外部工具「${d.name}」转换后的名字「$name」仍撞名，已整 server 拒收",
                )
            }
            used.add(name)
            McpConvertedTool(
                wireName = name,
                remoteName = d.name,
                description = describe(d),
                inputSchema = d.inputSchema,
                readOnly = d.readOnly,
            )
        }
    }

    /** 描述：外部数据 ⇒ 压平 + 前缀 + 截断。 */
    fun describe(d: McpToolDescriptor): String {
        val body = McpText.flat(d.description)
            .ifEmpty { "服务器没有提供描述；它做什么以工具名为准" }
        val head = "【外部工具 · ${McpText.clip(McpText.flat(d.serverName), 40)}】"
        return McpText.clip(head + body, ToolSpec.MAX_DESC)
    }

    /** `mcp_<serverSlug>_<toolSlug>`；超长走稳定哈希后缀。 */
    fun wireName(serverId: String, remoteName: String): String {
        val server = slug(serverId).ifEmpty { "server" }
        val tool = slug(remoteName).ifEmpty { "tool" }
        val base = "${WIRE_PREFIX}${server}_$tool"
        return if (base.length <= MAX_WIRE_NAME) base else withHash(base, McpText.shortHash("$serverId/$remoteName"))
    }

    /** 64 字符内加上 `_<6位hash>`；不会产生非 ASCII 字符。 */
    private fun withHash(base: String, hash: String): String {
        val room = MAX_WIRE_NAME - hash.length - 1
        val head = base.take(room).trimEnd('_', '-')
        return "${head}_$hash"
    }

    /** 小写 ASCII 化：非字母数字一律并成下划线（工具名必须能被 function calling 接受）。 */
    fun slug(raw: String): String {
        val out = StringBuilder(raw.length)
        var lastUnderscore = false
        for (c in raw.lowercase()) {
            val ok = c in 'a'..'z' || c in '0'..'9'
            if (ok) {
                out.append(c)
                lastUnderscore = false
            } else if (!lastUnderscore && out.isNotEmpty()) {
                out.append('_')
                lastUnderscore = true
            }
        }
        return out.toString().trim('_')
    }
}
