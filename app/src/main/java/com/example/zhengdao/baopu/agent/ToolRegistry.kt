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
 * 工具**能力组**（第五十九批 §1.1）——渐进披露的单位。
 *
 * 三条口径（派工单原话级）：
 * 1. **分组表只有一份，就放在本文件**（[ToolGroupTable]）：UI 从这里读、`tools` 数组
 *    从这里算、`capabilities` 的名册也从这里出——不许出现第二张组表；
 * 2. 组控制的是**可见性**，不是权限：[DEFAULT_VISIBLE] 之外的组默认**不进 tools 数组**
 *    （模型根本看不见它），不是「看得见但调用报错」——那是 §4 打回的第一条；
 * 3. 组与 [ToolPermission] 是两件独立的事：解锁只让工具**出现**，CONFIRM 工具解锁后
 *    仍然每次一张确认卡（[GuardPipeline] 的权限语义一个字没动）。
 *
 * 组的划分按**默认可见性 + 用途**，不按工具名前缀：§6 要求默认可见集合覆盖
 * 「读/写/改/列/搜/浏览器读页/ask_user/update_plan/图片改写/card」，而 §2 又硬卡
 * 「默认 tools ≤ 12（core+edit+capabilities）」——两条同时成立只有一个办法：把 §6 点名
 * 的那十件事放进 CORE / EDIT 两组（所以 browser_open / browser_snapshot / image_ops /
 * card_build 在 core/edit，而网页的**交互**四件在 browser）。交付报告里逐字记了这条取法。
 */
enum class ToolGroup(val key: String, val oneLine: String) {
    /** 默认就看得见：抱朴干活的最小闭环（§6 点名项）。 */
    CORE("core", "核心：读文件、列目录、搜内容、问用户、列计划，以及打开网页并读它"),

    /** 默认就看得见：写产物的最小闭环（§6 点名项）。 */
    EDIT("edit", "写与改：写文件、改文件、处理图片、写卡"),

    /** 网页交互（会改页面状态），默认不可见。 */
    BROWSER("browser", "网页操作：点击、输入、滚动、截图"),

    /** 文件管理（改名/移动/删除/转码），默认不可见。 */
    FILES("files", "文件管理：文件详情、按名字找、移动改名、转编码、删除"),

    /** 资料库，默认不可见。 */
    LIBRARY("library", "资料库：列清单、整理归位、书的大纲"),

    /** 格式层的体检与导出（写卡本体在 EDIT，理由见上面的第 3 条）。 */
    CARD("card", "卡片格式：体检角色卡、导出酒馆 PNG"),

    /** 技能与人设自写，默认不可见。 */
    SKILL("skill", "技能与人设：把学到的东西写成 SKILL.md、给自己的条款追加一份"),

    /** 玄览本地预览（只显示、默认断网禁 JS），默认不可见。 */
    PREVIEW("preview", "本地预览：在玄览只显示工作区里的网页/源码，不执行、出不了网"),

    /**
     * 免费通道的兼容占位（read / bash）：由通道决定注册与否，用户管不着、设置页也不列它们。
     * 注册了就**必须**默认可见（否则免费档 403），见 [ToolGroup.DEFAULT_VISIBLE] 的说明。
     */
    COMPAT("compat", "免费通道占位：read / bash 两个兼容条目（由通道决定，没有开关）"),

    ;

    companion object {
        /**
         * 默认可见的组（§1.2：core 与 edit 默认可见，其余组默认**不可见**）。
         * 新会话、没解锁 ⇒ 只有这几组的工具进 tools 数组。
         *
         * ⚠️ [COMPAT] 也在默认可见里，这不是手滑：`read` / `bash` 两条是**免费通道**
         * 的兼容占位（② 台账：免费档要求 tools 数组里含这两个名字，否则服务端 403
         * FreeTierError，见 [com.example.zhengdao.baopu.BaopuRuntime.registry]）。它们
         * 只在 `provider.builtinFree` 时才注册——没注册就不存在"默认可见"这回事，
         * 注册了就必须让模型看见，否则整条免费通道第一次请求就废。
         *
         * 于是 §2 那条「默认 tools ≤ 12（core+edit+capabilities）」的正确读法是：
         * 普通通道 = CORE 8 + EDIT 4 = **12**；免费通道在同一张表上多两条通道自带的
         * 兼容条目 ⇒ **14**（实测读数与这条取法都写进交付报告，留给蓝总管裁定）。
         */
        val DEFAULT_VISIBLE: Set<ToolGroup> = setOf(CORE, EDIT, COMPAT)

        /** 按 key 找组（`capabilities` 的 enable 参数走这里；找不到就是没这个组）。 */
        fun fromKey(raw: String): ToolGroup? = values().firstOrNull { it.key == raw.trim().lowercase() }
    }
}

/**
 * name → group 的**唯一一张分组表**（§1.1「分组表只有一份」）。
 *
 * 为什么不把 group 写进 [BaopuToolCatalog.Entry]：`write_skill`（23 批）与
 * `append_persona`（58 批）要 personaFiles、免费通道的 `read` / `bash` 要按通道分支，
 * 这四条都由 [com.example.zhengdao.baopu.BaopuRuntime] 单独注册——不进 ENTRIES——
 * 组若挂在 Entry 上，这四条就得再开第二张表。一张表按名字覆盖**所有会注册进去的工具**，
 * [ToolRegistry.register] 注册时按它盖章，装配层与表内谁先谁后都不影响结果。
 *
 * 🛑 §6「孤儿工具直接红」：这张表的键集与「ENTRIES ∪ 运行时那四条」**双向相等**，
 * 初始化时就核（[checkCoverage]）。加新工具忘了分组 ⇒ 加载即抛 ⇒ 全量单测直接红，
 * 不需要等某人去点那个工具。
 */
internal object ToolGroupTable {

    /** 由装配层（BaopuRuntime）单独注册的名字——注释里写明为什么它们不在 ENTRIES。 */
    val RUNTIME_REGISTERED: Set<String> = setOf("write_skill", "append_persona", "read", "bash")

    val GROUP_OF: Map<String, ToolGroup> = mapOf(
        // ── CORE：§6 点名的最小闭环（读 / 列 / 搜 / 问人 / 计划 / 浏览器读页）＋ capabilities 本体
        "capabilities" to ToolGroup.CORE,
        "read_file" to ToolGroup.CORE,
        "list_dir" to ToolGroup.CORE,
        "search_in_files" to ToolGroup.CORE,
        "ask_user" to ToolGroup.CORE,
        "update_plan" to ToolGroup.CORE,
        "browser_open" to ToolGroup.CORE,
        "browser_snapshot" to ToolGroup.CORE,
        // ── EDIT：写产物的最小闭环（写 / 改 / 图片 / 写卡）
        "write_file" to ToolGroup.EDIT,
        "edit_file" to ToolGroup.EDIT,
        "image_ops" to ToolGroup.EDIT,
        "card_build" to ToolGroup.EDIT,
        // ── 以下默认不可见，要 capabilities 解锁
        "browser_click" to ToolGroup.BROWSER,
        "browser_type" to ToolGroup.BROWSER,
        "browser_scroll" to ToolGroup.BROWSER,
        "browser_screenshot" to ToolGroup.BROWSER,
        "file_info" to ToolGroup.FILES,
        "find_files" to ToolGroup.FILES,
        "move_path" to ToolGroup.FILES,
        "convert_encoding" to ToolGroup.FILES,
        "delete_path" to ToolGroup.FILES,
        "book_outline" to ToolGroup.LIBRARY,
        "library_list" to ToolGroup.LIBRARY,
        "library_organize" to ToolGroup.LIBRARY,
        "card_inspect" to ToolGroup.CARD,
        "card_export" to ToolGroup.CARD,
        // 58 批 C 层「追加-only」人设通道：与 write_skill 同属自写类，同一组、同样默认不可见
        // （合流时补的分组——补不上 checkCoverage 直接抛，这是 §6「孤儿工具直接红」在跑）
        "write_skill" to ToolGroup.SKILL,
        "append_persona" to ToolGroup.SKILL,
        "preview_local_file" to ToolGroup.PREVIEW,
        "read" to ToolGroup.COMPAT,
        "bash" to ToolGroup.COMPAT,
    )

    /** 该组里的工具名（保持 ENTRIES 的表序，稳定 ⇒ tools 数组顺序不乱、缓存友好）。 */
    fun namesOf(group: ToolGroup): List<String> =
        BaopuToolCatalog.ENTRIES.map { it.name }
            .filter { GROUP_OF[it] == group }
            .plus(RUNTIME_REGISTERED.filter { GROUP_OF[it] == group }.sorted())

    /**
     * 覆盖率自检（§6 孤儿工具直接红）：表里列的名字必须**恰好**等于
     * 「ENTRIES 全部」∪「[RUNTIME_REGISTERED] 四条」——少一个（漏分组）与多一个
     * （分组表里躺着个已删的工具）都算红。
     */
    fun checkCoverage(): String? {
        val declared = BaopuToolCatalog.ENTRIES.map { it.name }.toSet() + RUNTIME_REGISTERED
        val missing = declared - GROUP_OF.keys
        val stale = GROUP_OF.keys - declared
        return when {
            missing.isNotEmpty() -> "这些工具没有分组（孤儿工具）：${missing.sorted()}"
            stale.isNotEmpty() -> "分组表里这些名字根本没注册（过期条目）：${stale.sorted()}"
            else -> null
        }
    }

    init {
        checkCoverage()?.let { error("ToolGroupTable 分组表不完整：$it") }
    }
}

/**
 * 用户侧开关的**快照**（§1.6：组开关与单工具开关并存）。
 *
 * 两种开关、一条链：可见性只由 [visibleTools] 一处算，这里就是它的「用户开关」入参——
 * 组关掉了 ⇒ 组里每个工具都不可见，且 `capabilities` 也解不开（§1.4）；
 * 单工具关掉了 ⇒ 只有那个工具不可见，同组其余照常。
 */
internal data class ToolUserSettings(
    val disabledNames: Set<String> = emptySet(),
    val disabledGroups: Set<ToolGroup> = emptySet(),
)

/**
 * 会话解锁存储（§1.4：解锁状态**按会话**，新会话回默认）。
 *
 * 为什么是接口而不是直接 SharedPreferences：注册表零 Android 依赖（JVM 直测），
 * Context 只能出现在装配层 ⇒ Android 实现（`BaopuToolUnlocks`）在
 * [com.example.zhengdao.baopu.BaopuRuntime]，与 [ToolPrefs] 的 `BaopuToolPrefs` 同房。
 *
 * 🛑 §4 打回第三条：**不许全局共享**（那会串会话）。key 就是 sessionId。
 */
internal interface GroupUnlockStore {
    /** 本会话已解锁的组（默认可见组不在这里——那是 [ToolGroup.DEFAULT_VISIBLE]）。 */
    fun unlocked(sessionId: Long): Set<ToolGroup>

    /** 解锁（只增不减：解锁是累加的，用户没有「重新收起」的入口，下一会话自然回默认）。 */
    fun unlock(sessionId: Long, groups: Collection<ToolGroup>)

    /** 清掉一个会话的解锁（会话删除时顺带清，不留垃圾）。 */
    fun clear(sessionId: Long)
}

/** 内存实现（单测与无 Context 环境用；进程内生效，不持久）。 */
internal class InMemoryGroupUnlocks(
    initial: Map<Long, Set<ToolGroup>> = emptyMap(),
) : GroupUnlockStore {
    private val bySession = initial.toMutableMap()

    override fun unlocked(sessionId: Long): Set<ToolGroup> = bySession[sessionId] ?: emptySet()

    override fun unlock(sessionId: Long, groups: Collection<ToolGroup>) {
        if (groups.isEmpty()) return
        bySession[sessionId] = unlocked(sessionId) + groups
    }

    override fun clear(sessionId: Long) {
        bySession.remove(sessionId)
    }

    /** 只看快照（门禁与单测用：证明解锁确实按 sessionId 分格，不是一格共用）。 */
    fun snapshot(): Map<Long, Set<ToolGroup>> = bySession.toMap()
}

/**
 * **唯一一条工具可见性计算链**（§2 源码门禁的靶心）。
 *
 * 可见 = 组没被用户关掉 ∧ 工具没被用户关掉 ∧（组默认可见 ∪ 组已在本会话解锁）。
 *
 * 纯函数：吃三样输入（表、用户开关、本会话解锁），零 Android、零 IO ⇒ JVM 直测
 * §2 要求的三组用例（默认 / 解锁 / 用户关闭）。
 *
 * ⚠️ 三个出口都必须走它——[ToolRegistry.toolsJsonOpenAi] / [ToolRegistry.toolsJsonAnthropic]
 * （请求侧）与 [ToolRegistry.find]（执行侧的「未解锁就明说」）。谁再另写一遍 filter，
 * 就是 §4 打回第四条「另起第二条工具过滤链」。判据本体是 [isVisibleTool]，
 * [visibleTools] 只是它的整表版本——两者永远同进同退。
 */
internal fun isVisibleTool(
    spec: ToolSpec,
    settings: ToolUserSettings,
    unlockedGroups: Set<ToolGroup>,
): Boolean {
    val visibleGroups = ToolGroup.DEFAULT_VISIBLE + unlockedGroups
    return spec.name !in settings.disabledNames &&
        spec.group !in settings.disabledGroups &&
        spec.group in visibleGroups
}

/** 整表版（[isVisibleTool] 的 filter；tools 数组由它产出，顺序 = 表顺序）。 */
internal fun visibleTools(
    catalog: List<ToolSpec>,
    settings: ToolUserSettings,
    unlockedGroups: Set<ToolGroup>,
): List<ToolSpec> = catalog.filter { isVisibleTool(it, settings, unlockedGroups) }

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

    /**
     * **等用户**的回答上限（第四十八批 P0-1）：确认超时 + 5 秒余量。
     *
     * 为什么单独一条：`ask_user` 的超时不是「工具执行超时」，而是「用户响应时限」——
     * 引擎自己最多等 [RunRules.CONFIRM_TIMEOUT_MS]（15 分钟）后返回 null，工具必须
     * 比它多留 5 秒把「没人回答」如实回灌。复用 [FILE_MS]（5 秒）会让每次点卡都先
     * 被判「执行超时」，且单线程工具池被占死 15 分钟、后续工具逐个 5 秒排队超时
     * （2026-10-19 全量审计 §一 P0-1）。
     */
    const val CONFIRM_MS = RunRules.CONFIRM_TIMEOUT_MS + 5_000L
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
    /**
     * 能力组（第五十九批 §1.1）。
     *
     * 默认值是 [ToolGroup.CORE] 只为让工具实现文件不改动也能编译（红线：本批不碰工具
     * 实现）；真正的组**由注册表在 [ToolRegistry.register] 时按 [ToolGroupTable] 盖章覆盖**，
     * 所以没有任何工具会带着这个默认值进入运行态——漏分组由
     * [ToolGroupTable.checkCoverage] 在类加载时就抛，不等运行。
     */
    val group: ToolGroup = ToolGroup.CORE,
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

    /**
     * 被用户**整组**关掉的组（第五十九批 §1.6：设置页组开关的落点）。
     *
     * 语义与单工具开关同一条链（见 [visibleTools]）：组关 ⇒ 组内工具一律不可见，
     * 且 `capabilities` 也解不开（§1.4「用户既有开关仍然优先」）。
     * 默认实现返回空集 = 组开关全开（刀 2 时代没有组开关，存量实现不必改）。
     */
    fun disabledGroups(): Set<ToolGroup> = emptySet()

    /** 改组开关（热改，与单工具开关同一时机生效：下一轮请求）。 */
    fun setGroupDisabled(group: ToolGroup, disabled: Boolean) {}
}

/** 内存实现（单测与无 Context 环境用；进程内生效，不持久）。 */
internal class InMemoryToolPrefs(
    initial: Set<String> = emptySet(),
    initialGroups: Set<ToolGroup> = emptySet(),
) : ToolPrefs {
    private val disabled = initial.toMutableSet()
    private val disabledGroups = initialGroups.toMutableSet()
    override fun isDisabled(name: String): Boolean = name in disabled
    override fun setDisabled(name: String, disabled: Boolean) {
        if (disabled) this.disabled.add(name) else this.disabled.remove(name)
    }

    override fun disabledNames(): Set<String> = disabled.toSet()

    override fun disabledGroups(): Set<ToolGroup> = disabledGroups.toSet()

    override fun setGroupDisabled(group: ToolGroup, disabled: Boolean) {
        if (disabled) disabledGroups.add(group) else disabledGroups.remove(group)
    }
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
 *
 * 第五十九批（渐进披露）在此之上加**第二层**「组可见性」，仍是一张表、一条链：
 * - 组由 [ToolGroupTable] 在 [register] 时盖章（工具实现文件不写组，见 [ToolSpec.group]）；
 * - 默认可见只有 [ToolGroup.DEFAULT_VISIBLE] 两组，其余要 `capabilities` 按会话解锁
 *   （[unlocks]）；
 * - **请求侧与执行侧共用 [visibleTools] 这一条链**：`tools` 数组由它算，[find] 遇到
 *   未解锁组回的是同一条判据（§4 打回第四条禁止另起第二条）。
 */
internal class ToolRegistry(
    private val prefs: ToolPrefs = InMemoryToolPrefs(),
    private val unlocks: GroupUnlockStore = InMemoryGroupUnlocks(),
) : CapabilitiesSink {

    private val specs = LinkedHashMap<String, ToolSpec>()

    /**
     * 本轮属于哪个会话（第五十九批 §1.4 的「按会话」落点）。
     *
     * 形制照 [RunEngine] 里的 `planSessionId`：引擎是进程级单例、一轮只跑一个会话，
     * 由 [runLoop] 在**组装 tools 数组那一处**写入；[NO_SESSION] = 没有会话上下文
     * （单测、装配期探测）⇒ 只按默认可见组算，行为等于「新会话」。
     */
    @Volatile
    var currentSessionId: Long = NO_SESSION

    /** 注册（同名覆盖：工具集演化时后注册的赢，调用方显式覆盖而非默默双注册）。 */
    fun register(spec: ToolSpec) {
        // 组按那张唯一的表盖章：装配层（BaopuRuntime 的 write_skill / read / bash）与
        // ENTRIES 走同一条路，谁都不用自己记得带组（记得的组也会被表覆盖）。
        specs[spec.name] = spec.copy(group = ToolGroupTable.GROUP_OF[spec.name] ?: spec.group)
    }

    /**
     * 取一个工具的**当前形态**（刀 2 追加包：开关是**调用时**判的，不是加载时；
     * 第五十九批：组解锁同样是调用时判）。
     *
     * 为什么不在 [loadInto] 时装桩：注册表是进程级单例，设置页改开关时它已经建好了——
     * 加载时判定的话，加载后才被关的工具仍以真身注册着（CONFIRM 权限 + 真执行器），
     * 模型幻觉调用它照样弹确认框、照样真写文件（2026-10-17 单测实测出来的洞）。
     * 这里每次取都现查：被关 ⇒ 回桩（免确认 + 执行一律 Rejected）；
     * 组未解锁 ⇒ 回另一种桩（免确认 + 执行一律 Rejected，并告诉模型先调 capabilities）。
     *
     * 两条拒绝的**先后**：用户开关优先（§1.4），所以先判被关、再判未解锁——
     * 用户明确关掉的东西不该被指路成「解锁一下就能用」。
     */
    fun find(name: String): ToolSpec? {
        val spec = specs[name] ?: return null
        return when {
            prefs.isDisabled(name) -> BaopuToolCatalog.disabledStub(spec)
            !isVisible(spec, currentSessionId) -> BaopuToolCatalog.groupLockedStub(spec)
            else -> spec
        }
    }

    fun names(): List<String> = specs.keys.toList()

    fun all(): List<ToolSpec> = specs.values.toList()

    /** 该工具当前是否启用（开关热改：引擎/管线持同一实例，下一轮请求即生效）。 */
    fun isEnabled(name: String): Boolean = !prefs.isDisabled(name)

    /** 改开关（热改；设置页落点）。被关的工具保持已注册的桩，不进 tools 数组。 */
    fun setDisabled(name: String, disabled: Boolean) {
        prefs.setDisabled(name, disabled)
    }

    /** 组级开关（§1.6 设置页落点；与单工具开关并存，同一条链）。 */
    fun setGroupDisabled(group: ToolGroup, disabled: Boolean) {
        prefs.setGroupDisabled(group, disabled)
    }

    fun disabledGroups(): Set<ToolGroup> = prefs.disabledGroups()

    /** 本会话已解锁的组（默认可见组不在其中）。 */
    fun unlockedGroups(sessionId: Long = currentSessionId): Set<ToolGroup> = unlocks.unlocked(sessionId)

    /** 会话解锁存储（[com.example.zhengdao.baopu.BaopuRuntime] 注入持久实现）。 */
    fun unlockStore(): GroupUnlockStore = unlocks

    /** 本会话生效的用户开关快照（[visibleTools] 的第二路输入）。 */
    fun settings(): ToolUserSettings = ToolUserSettings(prefs.disabledNames(), prefs.disabledGroups())

    /**
     * 此刻该模型看见的工具（**唯一一条链的调用点**；顺序 = 表顺序，稳定缓存友好）。
     *
     * @param sessionId 本轮会话；默认可见组 ∪ 本会话解锁组，再 ∩ 用户两种开关。
     */
    fun visibleSpecs(sessionId: Long = currentSessionId): List<ToolSpec> =
        visibleTools(all(), settings(), unlockedGroups(sessionId))

    /** 单个工具此刻是否可见（与 [visibleSpecs] 同一判据本体 [isVisibleTool]，不重写一遍）。 */
    fun isVisible(spec: ToolSpec, sessionId: Long = currentSessionId): Boolean =
        isVisibleTool(spec, settings(), unlockedGroups(sessionId))

    /** 启用中的工具名（设置页对照用；注意这是「用户没关」，不等于「模型看得见」）。 */
    fun enabledNames(): List<String> = specs.keys.filter { isEnabled(it) }

    /**
     * 第 ① 段：注册表校验（名字存在？参数合 Schema？）。
     * @return null = 过；非 null = 错误原文（协议 A/B 共用，回灌让模型重试）。
     */
    fun validateCall(name: String, args: JSONObject): String? {
        val spec = specs[name] ?: return "未知工具「$name」（已注册：${specs.keys.joinToString("、")}）"
        return SchemaCheck.validate(spec.inputSchema, args)
    }

    /**
     * OpenAI 兼容 `tools` 数组（协议 A 请求侧；**只含此刻可见的**——
     * 被用户关掉的、以及组还没解锁的，模型都看不见）。
     */
    fun toolsJsonOpenAi(sessionId: Long = currentSessionId): JSONArray = JSONArray().also { arr ->
        visibleSpecs(sessionId).forEach { s ->
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

    /** Anthropic `tools` 数组（形状不同：schema 直接铺平；同样只含此刻可见的）。 */
    fun toolsJsonAnthropic(sessionId: Long = currentSessionId): JSONArray = JSONArray().also { arr ->
        visibleSpecs(sessionId).forEach { s ->
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("description", s.description)
                    .put("input_schema", s.inputSchema),
            )
        }
    }

    /**
     * `capabilities` 的落点（[CapabilitiesSink]；照 [PlanSink] / ask_user 的接线房型）。
     *
     * 注册表本来就同时握着三样输入（表、用户开关、会话解锁），所以名册与解锁动作
     * 都在这里算——工具文件只负责 Schema 与「没接线就明说」，不再自己拼一份判据。
     */
    override fun handle(args: JSONObject): ToolOutcome {
        val sessionId = currentSessionId
        val want = args.optJSONArray(CapabilitiesTool.ARG_ENABLE)
        val settings = settings()
        // 名册按组走（表序），每组：状态 + 组内工具名 + 「这一组被用户关了」如实标。
        val sb = StringBuilder()
        if (want != null && want.length() > 0) {
            val unknown = mutableListOf<String>()
            val asked = mutableListOf<ToolGroup>()
            for (i in 0 until want.length()) {
                val key = want.optString(i)
                val group = ToolGroup.fromKey(key)
                if (group == null) unknown += key else if (group !in asked) asked += group
            }
            val already = unlockedGroups(sessionId)
            val toAdd = asked.filter { it !in ToolGroup.DEFAULT_VISIBLE && it !in already }
            // 被用户整组关掉的：**不解锁**，如实回原因（§1.4 用户开关优先）。
            val blocked = toAdd.filter { it in settings.disabledGroups }
            val granted = toAdd - blocked.toSet()
            unlocks.unlock(sessionId, granted)
            sb.append("解锁结果（本会话，下一轮生效；新会话回到默认）：\n")
            if (granted.isEmpty() && blocked.isEmpty() && asked.isEmpty()) {
                sb.append("（enable 里没给出可解锁的组）\n")
            }
            granted.forEach { sb.append("- 已解锁：${it.key}（${it.oneLine}）\n") }
            blocked.forEach { sb.append("- 解不开：${it.key} —— 这一组被你用户在设置里关掉了，" +
                "capabilities 不能替用户打开它（要用的话请用户去 抱朴 → 抽屉「抱朴设置」→ 抱朴工具）\n") }
            asked.filter { it in ToolGroup.DEFAULT_VISIBLE }
                .forEach { sb.append("- 本来就默认可见，无需解锁：${it.key}\n") }
            asked.filter { it in already && it !in ToolGroup.DEFAULT_VISIBLE }
                .forEach { sb.append("- 本会话此前已解锁过：${it.key}\n") }
            unknown.forEach { sb.append("- 没有这个组：$it\n") }
            sb.append("可解锁的组名：")
                .append(ToolGroup.values().filter { it != ToolGroup.COMPAT }
                    .joinToString("、") { it.key })
                .append("\n")
        }
        val visible = visibleSpecs(sessionId).map { it.name }.toSet()
        sb.append("当前可见工具（").append(visible.size).append(" 个）：")
            .append(visible.joinToString("、")).append("\n")
        sb.append("全量名册（组 → 状态 → 组内工具）：\n")
        ToolGroup.values().forEach { group ->
            val names = ToolGroupTable.namesOf(group)
            if (names.isEmpty()) return@forEach
            // 名册列的是**此刻真注册着的**工具（[ToolGroupTable] 是分组表，不是注册表）：
            // COMPAT 那两条只有免费通道会注册，普通通道列出来就是骗模型「解锁了就能用」。
            // 这不是第二条过滤链——可见性判据仍只有 [visibleTools] 一处，这里只影响名册文案。
            val registered = names.filter { specs.containsKey(it) }
            val state = when {
                group in settings.disabledGroups -> "已被用户关闭（解不开）"
                group in ToolGroup.DEFAULT_VISIBLE -> "默认可见"
                group in unlockedGroups(sessionId) -> "本会话已解锁"
                else -> "未解锁"
            }
            sb.append("- ${group.key}（$state）：${group.oneLine}\n")
            if (registered.isEmpty()) {
                sb.append("  本通道没有注册这组的工具（表里备着：${names.joinToString("、")}），解锁也不会有东西出现\n")
            } else {
                sb.append("  工具：${registered.joinToString("、")}\n")
            }
        }
        return ToolOutcome.Ok(sb.toString().trimEnd())
    }

    companion object {
        /** 没有会话上下文时的哨兵值（单测/装配期探测；效果 = 只按默认可见组算）。 */
        const val NO_SESSION = -1L
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
        // 浏览器六件（玄览）：读类免确认、写类 CONFIRM；网关没注入时统一回「玄览还没打开」
        "browser_open",
        "browser_snapshot",
        "browser_click",
        "browser_type",
        "browser_scroll",
        "browser_screenshot",
        // 工作计划卡（第十七批）：默认开——关了它，抱朴就不再「先列计划再干活」
        "update_plan",
        // 技能自写（第二十三批 A 段追加）：默认开，但写盘走 CONFIRM ⇒ 不会静默动手
        // （它由 BaopuRuntime 单独注册——要 personaFiles，ENTRIES 的工厂只吃 workspaceRoot）
        "write_skill",
        "append_persona",
        // 资料库两件套（第三十一批）：list 只读；organize 三个动作一律 CONFIRM，够不着「静默动手」
        "library_list",
        "library_organize",
        // 删除（2026-10-18 用户点名「删除工具没加啊？」）：默认开，但**每次一张确认卡**，
        // 且默认只进回收站（.回收站/），permanent=true 才是不可恢复的真删。
        "delete_path",
        // 图片处理（2026-10-18 用户逐字「落」）：默认开，但**每次一张确认卡**，且永不覆盖原图
        "image_ops",
        // 选项卡（2026-10-19）：不写盘、只问人，默认开；没有界面时回人话而不是干等
        "ask_user",
        // 格式层（第三十九批 B）：inspect 只读；export 写 PNG 但**绝不覆盖**，且每次一张确认卡
        "card_inspect",
        "card_export",
        "card_build",
        // 渐进披露（第五十九批 §1.3）：capabilities 是「按需解锁低频组」的入口，默认开
        "capabilities",
        // 玄览本地预览（第五十九批 §5）：只显示、默认断网禁 JS，不执行任何东西
        "preview_local_file",
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
        // 浏览器六件不需要 workspaceRoot（守卫走 URL/scheme，不走路径）
        Entry("browser_open") { BrowserTools.openSpec() },
        Entry("browser_snapshot") { BrowserTools.snapshotSpec() },
        Entry("browser_click") { BrowserTools.clickSpec() },
        Entry("browser_type") { BrowserTools.typeSpec() },
        Entry("browser_scroll") { BrowserTools.scrollSpec() },
        Entry("browser_screenshot") { BrowserTools.screenshotSpec() },
        // 工作计划卡（第十七批）：落点在引擎（PlanTool.sink），表里只放 spec
        Entry("update_plan") { PlanTool.spec() },
        // 资料库两件套（第三十一批）：library_list 只读清单；library_organize 写落地（CONFIRM）
        Entry("library_list") { LibraryTools.listSpec(it) },
        Entry("library_organize") { LibraryTools.organizeSpec(it) },
        // 删除（2026-10-18）：默认进回收站，permanent 才真删；禁区一律拒
        Entry("delete_path") { DeletePathTool.spec(it) },
        // 图片处理（2026-10-18）：缩放/裁剪/转格式/取主色/生成图标各档
        Entry("image_ops") { ImageOpsTool.spec(it) },
        // 选项卡（2026-10-19）：落点在引擎（AskUserTool.sink），表里只放 spec
        Entry("ask_user") { AskUserTool.spec() },
        // 格式层（第三十九批 B）：认脸＋体检（只读）／导出酒馆 PNG（不覆盖）
        Entry("card_inspect") { CardTools.inspectSpec() },
        Entry("card_export") { CardTools.exportSpec(it) },
        // 写卡（第四十五批）：母档 → 三套产物（CONFIRM，不覆盖）
        Entry("card_build") { CardTools.buildSpec(it) },
        // 渐进披露（第五十九批 §1.3）：列出组与解锁状态 / 本会话内解锁某组。
        // FREE：它只改「模型本轮能看见哪些工具」，不碰用户任何文件（与 update_plan 同理，
        // 逼它走确认卡等于每问一次「我有什么工具」就弹一次框）。
        Entry("capabilities") { CapabilitiesTool.spec() },
        // 玄览本地预览（第五十九批 §5.2）：读工作区内一个文件 → 交给玄览「本地预览」页签
        // 只显示。FREE + 只读：不写盘、不发网络请求（断网与禁 JS 在页签那一侧硬拦，§5.2）。
        Entry("preview_local_file") { PreviewTool.spec(it) },
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
        // capabilities 的落点就是注册表本身（名册与解锁都要同时看三样输入：表、用户开关、
        // 会话解锁）。形制照 PlanTool.sink / AskUserTool.sink 的运行时接线房型——
        // spec 注册时拿不到 registry，所以在这里挂。JVM 单测走同一条路，不注假件。
        CapabilitiesTool.sink = registry
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
                "该工具已被用户关闭（在 抱朴 → 抽屉「抱朴设置」→ 抱朴工具 里打开）。" +
                    "本轮不自动打开，也不改写成别的工具。",
            )
        },
    )

    /**
     * 未解锁组的桩（第五十九批 §1.5「诚实」）。
     *
     * 组默认不可见 = **不进 tools 数组**（模型看不见），不是「看得见但报错」——那是 §4
     * 打回第一条。但模型仍可能凭旧上下文或幻觉直接调用（协议 B 尤其如此），
     * 这一支保证它拿到的是**明说**：这个组还没解锁，先调 capabilities。绝不静默。
     *
     * 与 [disabledStub] 的区别要说清：被用户关了的东西 capabilities 也解不开（§1.4），
     * 而未解锁的组是**能**用 capabilities 当轮解开的——所以两条拒绝的文案不同，
     * 且 [ToolRegistry.find] 里先判「被关」（用户开关优先）。
     */
    fun groupLockedStub(spec: ToolSpec): ToolSpec = spec.copy(
        permission = ToolPermission.FREE, // 同上：不执行，没有可确认的东西
        executor = { _ ->
            ToolOutcome.Rejected(
                "该工具组未解锁：先调 capabilities（无参数可看全量名册，" +
                    "带 enable=[\"${spec.group.key}\"] 可在本会话解锁它）。" +
                    "本轮不解锁、不代调，也不改写成别的工具。",
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
