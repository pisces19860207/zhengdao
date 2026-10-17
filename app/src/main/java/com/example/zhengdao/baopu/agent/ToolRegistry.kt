// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §5（守卫管线：注册表是唯一真相；每个工具 = 名称 +
// JSON Schema + 权限级 + 超时 + Kotlin 执行器）+ §9 模块划分（agent/ToolRegistry.kt）
// ＋ 刀 2 追加包「工具集表＋开关」：表在 [BaopuToolCatalog]，开关在 [ToolPrefs]，
// 引擎不再写死工具清单。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 工具权限级（程枢版 §5 管线第 ③ 段）。
 *
 * 刀 2 追加包起内置五个工具：read_file / list_dir / search_in_files / file_info
 * 是 [FREE]；write_file 是 [CONFIRM]（每次写都走单步确认，RunEngine 现有
 * AwaitConfirm 骨架；确认后的执行链归下一刀）。
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
 * 工具开关存储（刀 2 追加包：工具集「表＋开关」的存储侧）。
 *
 * 为什么抽接口：注册表本身零 Android 依赖（JVM 直测），SharedPreferences
 * 不能进到这里 ⇒ 接口留在本文件，Android 侧实现（`BaopuToolPrefs`）在
 * [com.example.zhengdao.baopu.BaopuRuntime]——装配层才有 Context。
 *
 * 语义：存的是「被用户关掉的工具名」集合（关是显式动作，开是默认）。
 */
internal interface ToolPrefs {
    fun isDisabled(name: String): Boolean
    fun setDisabled(name: String, disabled: Boolean)
    fun disabledNames(): Set<String>
}

/** 内存实现（单测与无 Context 环境用；进程内生效，不持久）。 */
internal class InMemoryToolPrefs(initial: Set<String> = emptySet()) : ToolPrefs {
    private val disabled = initial.toMutableSet()
    override fun isDisabled(name: String): Boolean = name in disabled
    override fun setDisabled(name: String, disabled: Boolean) {
        if (disabled) this.disabled.add(name) else this.disabled.remove(name)
    }

    override fun disabledNames(): Set<String> = disabled.toSet()
}

/**
 * 工具注册表（**唯一真相**，程枢版 §5）。
 *
 * 协议 A（原生 function calling）与协议 B（文本协议，刀 3–5 之间）都只是
 * 「把模型的话翻译成注册表条目」的解码器——执行永远走这里 + [GuardPipeline]。
 *
 * 刀 2 追加包（表＋开关）：本类只认「表里有什么、开关是什么」——
 * 表由 [BaopuToolCatalog] 提供，开关由 [prefs] 持有。**被关掉的工具仍然注册**
 * （[find] 取到时现换成桩形态：同 Schema、免确认、执行一律回「该工具已被用户关闭」），
 * 但**不进 `tools` 数组**（模型根本看不见）；模型若幻觉调用，拿到的是
 * 这条拒绝（不自动打开）。
 */
internal class ToolRegistry(
    private val prefs: ToolPrefs = InMemoryToolPrefs(),
) {

    private val specs = LinkedHashMap<String, ToolSpec>()

    /** 注册（同名覆盖：工具集演化时后注册的赢，调用方显式覆盖而非默默双注册）。 */
    fun register(spec: ToolSpec) {
        specs[spec.name] = spec
    }

    /**
     * 取一个工具的**当前形态**（刀 2 追加包：开关是**调用时**判的，不是加载时）。
     *
     * 为什么不在 [loadInto] 时装桩：注册表是进程级单例，设置页改开关时它已经建好了——
     * 加载时判定的话，加载后才被关的工具仍以真身注册着（CONFIRM 权限 + 真执行器），
     * 模型幻觉调用它照样弹确认框、照样真写文件（2026-10-17 单测实测出来的洞）。
     * 这里每次取都现查：被关 ⇒ 回桩（免确认 + 执行一律 Rejected）。
     */
    fun find(name: String): ToolSpec? {
        val spec = specs[name] ?: return null
        return if (isEnabled(name)) spec else BaopuToolCatalog.disabledStub(spec)
    }

    fun names(): List<String> = specs.keys.toList()

    fun all(): List<ToolSpec> = specs.values.toList()

    /** 该工具当前是否启用（开关热改：引擎/管线持同一实例，下一轮请求即生效）。 */
    fun isEnabled(name: String): Boolean = !prefs.isDisabled(name)

    /** 改开关（热改；设置页落点）。被关的工具保持已注册的桩，不进 tools 数组。 */
    fun setDisabled(name: String, disabled: Boolean) {
        prefs.setDisabled(name, disabled)
    }

    /** 启用中的工具名（设置页对照用）。 */
    fun enabledNames(): List<String> = specs.keys.filter { isEnabled(it) }

    /**
     * 第 ① 段：注册表校验（名字存在？参数合 Schema？）。
     * @return null = 过；非 null = 错误原文（协议 A/B 共用，回灌让模型重试）。
     */
    fun validateCall(name: String, args: JSONObject): String? {
        val spec = specs[name] ?: return "未知工具「$name」（已注册：${specs.keys.joinToString("、")}）"
        return SchemaCheck.validate(spec.inputSchema, args)
    }

    /** OpenAI 兼容 `tools` 数组（协议 A 请求侧；**只含启用的**——被关的模型看不见）。 */
    fun toolsJsonOpenAi(): JSONArray = JSONArray().also { arr ->
        all().filter { isEnabled(it.name) }.forEach { s ->
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

    /** Anthropic `tools` 数组（形状不同：schema 直接铺平；同样只含启用的）。 */
    fun toolsJsonAnthropic(): JSONArray = JSONArray().also { arr ->
        all().filter { isEnabled(it.name) }.forEach { s ->
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("description", s.description)
                    .put("input_schema", s.inputSchema),
            )
        }
    }
}

/**
 * 工具集**一张表**（刀 2 追加包：spec＋permission＋factory 全在这里，
 * 不许再在引擎里写死哪几个工具）。
 *
 * factory 统一收 `workspaceRoot: () -> File`：大多数工具不需要根（守卫在
 * 管线第 ② 段按 path 参数名做），但 write_file 的备份目录、search_in_files /
 * file_info 的相对路径渲染都要它——签名统一，加工具时不用想「这个要不要根」。
 *
 * 零 Android 依赖：本文件与工具实现同在 `com.example.zhengdao.baopu.agent` 包，
 * 不需要 import；JVM 单测可直接加载整张表。
 */
internal object BaopuToolCatalog {

    /** 表的一条。 */
    data class Entry(
        val name: String,
        val factory: (workspaceRoot: () -> File) -> ToolSpec,
    )

    /**
     * 默认启用集合。
     *
     * 全部默认开：读类（read_file / list_dir / search_in_files / find_files / book_outline /
     * file_info）只读不改；写类（write_file / edit_file / move_path / convert_encoding）全是
     * CONFIRM，够不着「静默动手」——真要防，用户自己在设置里关。
     */
    val DEFAULT_ENABLED: Set<String> = setOf(
        "read_file",
        "list_dir",
        "search_in_files",
        "find_files",
        "book_outline",
        "file_info",
        "write_file",
        "edit_file",
        "move_path",
        "convert_encoding",
    )

    /** 一张表（顺序即 tools 数组顺序——稳定，缓存友好；读类在前，写类在后）。 */
    val ENTRIES: List<Entry> = listOf(
        Entry("read_file") { ReadFileTool.spec() },
        Entry("list_dir") { ListDirTool.spec() },
        Entry("search_in_files") { SearchInFilesTool.spec(it) },
        Entry("find_files") { FindFilesTool.spec(it) },
        Entry("book_outline") { BookOutlineTool.spec(it) },
        Entry("file_info") { FileInfoTool.spec(it) },
        Entry("write_file") { WriteFileTool.spec(it) },
        Entry("edit_file") { EditFileTool.spec(it) },
        Entry("move_path") { MovePathTool.spec(it) },
        Entry("convert_encoding") { ConvertEncodingTool.spec(it) },
    )

    /**
     * 把整张表装进注册表（**只装真身**；被关的形态由 [ToolRegistry.find] 在调用时判）。
     *
     * 为什么不在加载时分流：见 [ToolRegistry.find] 的 KDoc——注册表进程级长寿，
     * 开关随时会变，加载时判定等于把「当时的状态」钉死。
     */
    fun loadInto(registry: ToolRegistry, workspaceRoot: () -> File) {
        ENTRIES.forEach { entry ->
            registry.register(entry.factory(workspaceRoot))
        }
    }

    /**
     * 被关工具的桩（Schema 不变：幻觉调用也要先过 SchemaCheck，再拿这条拒绝）。
     *
     * 权限改 FREE 的理由：它根本不执行，没有「可确认的东西」——若保留 CONFIRM，
     * 用户会先看到一个确认框，点完才被告知「这工具被关了」（确认框是纯骚扰）。
     */
    fun disabledStub(spec: ToolSpec): ToolSpec = spec.copy(
        permission = ToolPermission.FREE, // 免确认——它根本不执行，没有可确认的东西
        executor = { _ ->
            ToolOutcome.Rejected(
                "该工具已被用户关闭（在 设置 → 终端与资料 → 抱朴工具 里打开）。" +
                    "本轮不自动打开，也不改写成别的工具。",
            )
        },
    )
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
