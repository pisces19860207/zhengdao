// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §5（守卫管线：注册表是唯一真相；每个工具 = 名称 +
// JSON Schema + 权限级 + 超时 + Kotlin 执行器）+ §9 模块划分（agent/ToolRegistry.kt）。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具权限级（程枢版 §5 管线第 ③ 段）。
 *
 * 本刀只有读工具 ⇒ 两个内置工具都是 [FREE]；需确认的 [CONFIRM] 留给刀 2 的写工具
 * （`write_file` 覆盖已存在文件等，设计 §6 单步确认清单）。
 */
enum class ToolPermission {
    /** 免确认直行。 */
    FREE,

    /** 需确认：整批进 AwaitConfirm（PCE 三段式归刀 2，本刀只留状态与迁移骨架）。 */
    CONFIRM,
}

/**
 * 工具执行结果（执行器的唯一出口形状）。
 *
 * 三种结局分开是因为**处理方式完全不同**：
 * - [Ok] 正常回灌（走围栏 wrap）；
 * - [Failed] 可重试×1（错误原文回灌让模型换路，程枢版 §8）；
 * - [Rejected] 越界/权限被拒 ⇒ **终止本轮**（程枢版 §8：路径越界终止本轮并 RunLog），
 *   与"失败重试"混在一起会让模型反复撞同一堵墙。
 */
sealed interface ToolOutcome {
    data class Ok(val text: String) : ToolOutcome
    data class Failed(val error: String) : ToolOutcome
    data class Rejected(val reason: String) : ToolOutcome
}

/** 工具超时口径（程枢版 §2 刹车数值：文件类 5s / 浏览器类 20s）。 */
object ToolTimeout {
    const val FILE_MS = 5_000L
    const val BROWSER_MS = 20_000L
}

/**
 * 一个工具的完整描述（程枢版 §5：名称 ＋ Schema ＋ 权限级 ＋ 超时 ＋ 执行器）。
 *
 * [description] 是**写进系统提示的提示词**（评估补录：外部工具描述是注入面）——
 * 内置工具的描述由本仓自己写，长度仍受 ≤500 字符自律上限约束（见 [MAX_DESC]）。
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val permission: ToolPermission = ToolPermission.FREE,
    val timeoutMs: Long = ToolTimeout.FILE_MS,
    val executor: (JSONObject) -> ToolOutcome,
) {
    companion object {
        /** 单工具描述长度上限（评估补录：防超长描述吃掉上下文预算）。 */
        const val MAX_DESC = 500
    }
}

/**
 * JSON Schema 最小校验器（**纯函数**，无 Android/OkHttp，JVM 直测）。
 *
 * 手写不引库的理由：零新增依赖是红线；且校验语义必须与协议 B 兜底共用一张表
 * （协议 B 的 JSON 块过的是同一个校验器）。覆盖：
 * - `type`：object / string / integer / number / boolean / array；
 * - `required`：缺字段即错（错误原文里点名缺哪个）；
 * - `properties` 逐个类型校验 + `enum`  membership + `minimum`/`maximum`；
 * - `additionalProperties: false` ⇒ **未知参数一律拒绝**（模型幻觉出的参数名不能直通，
 *   否则它会以为参数生效了——负例演练①的靶子就是这个分支）。
 */
internal object SchemaCheck {

    /** @return null = 通过；非 null = 错误原文（回灌给模型，人话 + 哪个参数错在哪）。 */
    fun validate(schema: JSONObject, args: JSONObject): String? {
        if (!schema.optString("type").equals("object", ignoreCase = true)) {
            return "工具 schema 的顶层 type 必须是 object（注册表配置错误，不是模型错）"
        }
        // required
        val required = schema.optJSONArray("required")
        if (required != null) {
            for (i in 0 until required.length()) {
                val key = required.optString(i)
                if (key.isNotEmpty() && !args.has(key)) {
                    return "缺少必填参数「$key」"
                }
            }
        }
        val props = schema.optJSONObject("properties") ?: JSONObject()
        // additionalProperties: false ⇒ 未知参数拒绝
        if (schema.optBoolean("additionalProperties", true).not()) {
            for (key in args.keys()) {
                if (!props.has(key)) {
                    return "未知参数「$key」（本工具不接受这个参数；可用参数：${props.keys().asSequence().joinToString("、")}）"
                }
            }
        }
        // 逐个类型/取值校验
        for (key in props.keys()) {
            if (!args.has(key)) continue
            val ps = props.optJSONObject(key) ?: continue
            val v = args.opt(key) ?: continue
            typeMismatch(key, ps, v)?.let { return it }
        }
        return null
    }

    /** 单参数类型/取值校验；通过返回 null。 */
    private fun typeMismatch(key: String, ps: JSONObject, v: Any): String? {
        val t = ps.optString("type")
        val bad = fun(expect: String): String = "参数「$key」类型不对：期望 $expect，实到 ${describe(v)}"
        when (t) {
            "string" -> if (v !is String) return bad("字符串")
            "integer" -> if (v !is Int && v !is Long) return bad("整数")
            "number" -> if (v !is Number) return bad("数字")
            "boolean" -> if (v !is Boolean) return bad("布尔值")
            "array" -> if (v !is JSONArray) return bad("数组")
            "object" -> if (v !is JSONObject) return bad("对象")
        }
        // enum
        ps.optJSONArray("enum")?.let { en ->
            if (v is String && (0 until en.length()).none { en.optString(it) == v }) {
                return "参数「$key」取值不在允许范围（${(0 until en.length()).joinToString("、") { en.optString(it) }}）"
            }
        }
        // integer 范围
        if ((t == "integer" || t == "number") && v is Number) {
            ps.optDouble("minimum", Double.NaN).let { if (!it.isNaN() && v.toDouble() < it) return "参数「$key」不能小于 ${fmt(it)}" }
            ps.optDouble("maximum", Double.NaN).let { if (!it.isNaN() && v.toDouble() > it) return "参数「$key」不能大于 ${fmt(it)}" }
        }
        return null
    }

    private fun describe(v: Any): String = when (v) {
        is String -> "字符串"
        is Int, is Long -> "整数"
        is Double, is Float -> "小数"
        is Boolean -> "布尔值"
        is JSONArray -> "数组"
        is JSONObject -> "对象"
        else -> v.javaClass.simpleName
    }

    private fun fmt(d: Double): String = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
}

/**
 * 工具注册表（**唯一真相**，程枢版 §5）。
 *
 * 协议 A（原生 function calling）与协议 B（文本协议，刀 3–5 之间）都只是
 * 「把模型的话翻译成注册表条目」的解码器——执行永远走这里 + [GuardPipeline]。
 */
internal class ToolRegistry {

    private val specs = LinkedHashMap<String, ToolSpec>()

    /** 注册（同名覆盖：工具集演化时后注册的赢，调用方显式覆盖而非默默双注册）。 */
    fun register(spec: ToolSpec) {
        specs[spec.name] = spec
    }

    fun find(name: String): ToolSpec? = specs[name]

    fun names(): List<String> = specs.keys.toList()

    fun all(): List<ToolSpec> = specs.values.toList()

    /**
     * 第 ① 段：注册表校验（名字存在？参数合 Schema？）。
     * @return null = 过；非 null = 错误原文（协议 A/B 共用，回灌让模型重试）。
     */
    fun validateCall(name: String, args: JSONObject): String? {
        val spec = specs[name] ?: return "未知工具「$name」（已注册：${specs.keys.joinToString("、")}）"
        return SchemaCheck.validate(spec.inputSchema, args)
    }

    /** OpenAI 兼容 `tools` 数组（协议 A 请求侧；空注册表给空数组，调用方据此决定不带字段）。 */
    fun toolsJsonOpenAi(): JSONArray = JSONArray().also { arr ->
        all().forEach { s ->
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", s.name)
                            .put("description", s.description)
                            .put("parameters", s.inputSchema),
                    ),
            )
        }
    }

    /** Anthropic `tools` 数组（形状不同：schema 直接铺平）。 */
    fun toolsJsonAnthropic(): JSONArray = JSONArray().also { arr ->
        all().forEach { s ->
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("description", s.description)
                    .put("input_schema", s.inputSchema),
            )
        }
    }
}

/** JSON 构造辅助（工具 Schema 的字面量写法用；org.json 无 Kotlin DSL，包一层少写 boilerplate）。 */
internal fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().also { j ->
    pairs.forEach { (k, v) ->
        when (v) {
            null -> j.put(k, JSONObject.NULL)
            is JSONObject -> j.put(k, v)
            is JSONArray -> j.put(k, v)
            else -> j.put(k, v)
        }
    }
}

/** JSON 数组构造辅助。 */
internal fun jsonArrayOf(vararg items: Any?): JSONArray = JSONArray().also { a ->
    items.forEach { a.put(it) }
}
