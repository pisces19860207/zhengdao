// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百一十五批 · 工具自修 v1：**失败归因与修复建议**（人批，不自动）。
//
// 病灶：工具失败后，系统只会把错误原文回灌一次；「这错到底能不能靠改参数修、
// 该换工具还是该问人」全靠模型自己猜。本文件把这一步收成**纯函数**：
// 输入（工具名、参数、错误原文）→ 输出（归因 ＋ 一条可读建议 ＋ 可选的建议参数）。
//
// 边界（派工单 §2/§3 红线，物理上成立而不是靠自觉）：
// - 不持有、不接收 `ToolRegistry` / `ToolSpec` / 执行器 ⇒ 不可能自动调用工具，
//   也没有可以改写的 schema；
// - 没有 IO、没有时钟、没有线程、没有 Android 依赖 ⇒ JVM 直测；
// - [ToolRepairAdvice] 只是数据，执行与否由用户/模型决定；本文件不产生任何副作用。
//
// ⚠️ 错误原文的解析只认**现有错误文案里已有的结构**（字段名、期望类型、允许值）。
// 拿不到结构化线索就回 `UNKNOWN`/`NONE`，不硬猜（派工单 §1 原话）。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject

/** 工具失败的错误类型（派工单 §1 点名的七类，一一对应）。 */
internal enum class ToolFailureType(val label: String) {
    SCHEMA("参数 schema 不合规"),
    TYPE("参数类型不对"),
    ENUM("参数取值越界"),
    TIMEOUT("工具执行超时"),
    PERMISSION("权限或可用性受限"),
    NETWORK("网络问题"),
    UNKNOWN("原因不明"),
}

/** 失败归因（派工单 §1 的五类）。 */
internal enum class ToolFailureCause(val label: String) {
    ARGUMENTS_FIXABLE("参数可修"),
    ALTERNATIVE_TOOL("需要换工具"),
    NEEDS_USER("需要用户"),
    ENVIRONMENT("环境问题"),
    UNKNOWN("未知"),
}

/** 修复建议（派工单 §2 的五类；只生成，不执行）。 */
internal enum class ToolRepairSuggestion(val label: String) {
    RETRY_WITH_ARGS("带修正后的参数重试"),
    TRY_ALTERNATIVE("换一种工具或路径"),
    ASK_USER("先问用户"),
    ENV_ISSUE("按环境问题排查"),
    NONE("不建议重试"),
}

/**
 * 归因证据（从现有失败结果/trace 取）。
 *
 * @param arguments 失败时的调用参数；**只读**（建议层会复制后再改，绝不原地改）
 * @param errorText 错误原文（现有 [GuardPipeline.Result.Failed] / [Rejected] 的文本）
 * @param failureKind 已有分类（可选；用于区分「工具超时」与「网络超时」这类同词不同因）
 */
internal data class ToolFailureEvidence(
    val toolName: String,
    val errorText: String,
    val arguments: JSONObject? = null,
    val failureKind: FailureKind? = null,
)

/**
 * 一条修复建议（纯数据）。
 *
 * @param suggestedArgs 建议参数（完整参数对象，不是补丁）；null = 原文里没有足够证据给出具体值
 * @param reason 一句可读原因（只含字段名/类型/允许值，不含参数内容）
 * @param action 建议动作（给模型/用户看；不代表已经执行）
 */
internal data class ToolRepairAdvice(
    val toolName: String,
    val type: ToolFailureType,
    val cause: ToolFailureCause,
    val suggestion: ToolRepairSuggestion,
    val reason: String,
    val action: String,
    val suggestedArgs: JSONObject? = null,
) {
    /** 回灌给模型/展示给用户的一句话（单行；不执行任何动作）。 */
    fun feedbackSuffix(): String =
        "（归因：${type.label}／${cause.label}；建议：${suggestion.label}。" +
            "$action；只给建议，不自动执行。）"
}

/**
 * 失败归因与建议（**唯一入口**；纯函数）。
 *
 * 判类顺序有讲究：先认「工具超时/崩溃」这类**不是参数问题**的，再认「换工具/权限」，
 * 最后才解析参数错误——否则 `连接超时` 会被当成 `执行超时`、`未知工具` 会被当成
 * `未知参数`，建议就指错方向。
 */
internal object ToolFailureAdvisor {

    /** 日志锚（可 grep 出所有建议行；本批不写技能/人设）。 */
    const val TAG = "[tool-repair]"

    /** 缺少必填参数时建议参数里的占位值（明确标记「待填」，不冒充真值）。 */
    const val PLACEHOLDER = "<待填>"

    private val MISSING_REQUIRED = Regex("""缺少必填参数「([^」]+)」""")
    private val UNKNOWN_PARAM = Regex("""未知参数「([^」]+)」""")
    private val TYPE_MISMATCH = Regex("""参数「([^」]+)」类型不对：期望 ([^，]+)，实到 (.+)""")
    private val ENUM_OUT = Regex("""参数「([^」]+)」取值不在允许范围（([^）]*)）""")
    private val NUMBER_RANGE =
        Regex("""参数「([^」]+)」不能(小于|大于) (-?[0-9][0-9.]*(?:[eE][+-]?[0-9]+)?)""")

    /**
     * 生成建议。
     *
     * 纯函数：同样的证据永远得到同样的建议；不读设置、不看开关、不碰注册表。
     */
    fun advise(evidence: ToolFailureEvidence): ToolRepairAdvice {
        val tool = evidence.toolName.ifBlank { "tool" }
        val text = evidence.errorText

        // 用户已停止：任何「重试」都是跟用户对着干。
        if (evidence.failureKind == FailureKind.CANCELLED) {
            return none(tool, "用户已停止本轮", "不要自动重试；等用户重新下指令")
        }

        // 工具自身超时：不是参数能修好的问题（与「连接超时」分开判）。
        if (evidence.failureKind == FailureKind.TOOL_TIMEOUT || text.contains("执行超时")) {
            return env(
                tool,
                ToolFailureType.TIMEOUT,
                "工具执行超时，不是参数能修好的问题",
                "检查设备/工作区状态，或把任务拆小；是否重试由用户决定",
            )
        }

        // 工具崩了 / 注册表 schema 配置错：都不是模型能靠改参数解决的。
        if (text.contains("执行崩了") || text.contains("注册表配置错误")) {
            return env(
                tool,
                ToolFailureType.UNKNOWN,
                "工具实现或注册表配置出了问题，改参数不会好",
                "保留错误原文交用户/开发者排查；不要原样重试",
            )
        }

        // 名字不存在 / 明确说「改用别的」：换工具或换路径，不是改参数。
        if (text.contains("未知工具") || text.contains("暂不支持") || text.contains("请改用")) {
            return ToolRepairAdvice(
                toolName = tool,
                type = ToolFailureType.UNKNOWN,
                cause = ToolFailureCause.ALTERNATIVE_TOOL,
                suggestion = ToolRepairSuggestion.TRY_ALTERNATIVE,
                reason = "按原文，这个工具/写法本身走不通",
                action = "换一个已注册工具或换一种写法；若是未解锁的工具组，交给用户决定是否启用",
            )
        }

        // 权限/可用性：只有人能解，重试同一调用没用。
        if (isPermission(text)) {
            return ToolRepairAdvice(
                toolName = tool,
                type = ToolFailureType.PERMISSION,
                cause = ToolFailureCause.NEEDS_USER,
                suggestion = ToolRepairSuggestion.ASK_USER,
                reason = "被权限、可用性或工作区边界挡住了",
                action = "把原因交给用户；不要绕过边界或反复撞同一堵墙",
            )
        }

        // 缺少必填参数：给出带占位值的完整参数（占位不是真值，明确标出）。
        MISSING_REQUIRED.find(text)?.let { m ->
            val key = m.groupValues[1]
            val suggested = copyArgs(evidence.arguments).put(key, PLACEHOLDER)
            return ToolRepairAdvice(
                toolName = tool,
                type = ToolFailureType.SCHEMA,
                cause = ToolFailureCause.ARGUMENTS_FIXABLE,
                suggestion = ToolRepairSuggestion.RETRY_WITH_ARGS,
                reason = "缺少必填参数「$key」",
                action = "补上「$key」后再调用（建议参数里的 $PLACEHOLDER 只是占位，值由模型/用户补）",
                suggestedArgs = suggested,
            )
        }

        // 未知参数：直接去掉（这是原文点名的、不需要猜的修法）。
        UNKNOWN_PARAM.find(text)?.let { m ->
            val key = m.groupValues[1]
            val suggested = copyArgs(evidence.arguments)
            suggested.remove(key)
            return ToolRepairAdvice(
                toolName = tool,
                type = ToolFailureType.SCHEMA,
                cause = ToolFailureCause.ARGUMENTS_FIXABLE,
                suggestion = ToolRepairSuggestion.RETRY_WITH_ARGS,
                reason = "参数「$key」不在该工具接受的参数里",
                action = "去掉「$key」后重试（建议参数已附）",
                suggestedArgs = suggested,
            )
        }

        // 类型不对：能从现有值无损转换才给建议参数，否则只给方向。
        TYPE_MISMATCH.find(text)?.let { m ->
            val key = m.groupValues[1]
            val expected = m.groupValues[2].trim()
            val current = evidence.arguments?.opt(key)
            val converted = convertTo(current, expected)
            val suggested = converted?.let { copyArgs(evidence.arguments).put(key, it) }
            return ToolRepairAdvice(
                toolName = tool,
                type = ToolFailureType.TYPE,
                cause = ToolFailureCause.ARGUMENTS_FIXABLE,
                suggestion = ToolRepairSuggestion.RETRY_WITH_ARGS,
                reason = "参数「$key」类型不对：期望$expected",
                action = if (suggested != null) {
                    "把「$key」改成$expected 后重试（建议参数已附）"
                } else {
                    "把「$key」改成$expected；具体值无法从错误原文确定，需模型/用户补"
                },
                suggestedArgs = suggested,
            )
        }

        // 枚举外：只有大小写这种无损修正才给建议参数，绝不替模型挑一个值。
        ENUM_OUT.find(text)?.let { m ->
            val key = m.groupValues[1]
            val allowed = m.groupValues[2].split("、").map { it.trim() }.filter { it.isNotEmpty() }
            val current = evidence.arguments?.opt(key)
            val fixed = if (current is String) {
                allowed.firstOrNull { it.equals(current, ignoreCase = true) }
            } else {
                null
            }
            val suggested = fixed?.let { copyArgs(evidence.arguments).put(key, it) }
            return ToolRepairAdvice(
                toolName = tool,
                type = ToolFailureType.ENUM,
                cause = ToolFailureCause.ARGUMENTS_FIXABLE,
                suggestion = ToolRepairSuggestion.RETRY_WITH_ARGS,
                reason = if (allowed.isEmpty()) {
                    "参数「$key」的取值不在允许范围内"
                } else {
                    "参数「$key」只能是 ${allowed.joinToString("/")} 之一"
                },
                action = if (suggested != null) {
                    "把「$key」改成允许值后重试（建议参数已附）"
                } else {
                    "从允许值里选一个再重试；不替模型猜具体值"
                },
                suggestedArgs = suggested,
            )
        }

        // 数值范围：能按边界夹紧就夹紧（有证据，不算猜）。
        NUMBER_RANGE.find(text)?.let { m ->
            val key = m.groupValues[1]
            val direction = m.groupValues[2]
            val boundText = m.groupValues[3]
            val fixed = clamp(evidence.arguments?.opt(key), direction, boundText.toDoubleOrNull())
            val suggested = fixed?.let { copyArgs(evidence.arguments).put(key, it) }
            return ToolRepairAdvice(
                toolName = tool,
                type = ToolFailureType.SCHEMA,
                cause = ToolFailureCause.ARGUMENTS_FIXABLE,
                suggestion = ToolRepairSuggestion.RETRY_WITH_ARGS,
                reason = "参数「$key」不能$direction $boundText",
                action = if (suggested != null) {
                    "把「$key」改到边界内后重试（建议参数已附）"
                } else {
                    "把「$key」改到 $boundText 的允许范围再重试"
                },
                suggestedArgs = suggested,
            )
        }

        // 网络：同一条工具再试可能就好，但本层只给建议、不自动重试。
        if (isNetwork(text)) {
            return env(
                tool,
                ToolFailureType.NETWORK,
                "网络/连接层失败，不是参数问题",
                "检查网络后由用户决定是否重试；不要自动重发",
            )
        }

        // 只剩「超时」字样（前面已排除工具执行超时与网络）：按环境问题处理。
        if (text.contains("超时") || text.contains("timeout", ignoreCase = true)) {
            return env(
                tool,
                ToolFailureType.TIMEOUT,
                "调用超时，原文没有更多结构化线索",
                "由用户决定是否重试或换个方式；不要自动重试",
            )
        }

        // 拿不到证据：不硬猜。
        return none(tool, "错误原文里没有可归因的结构化线索", "按原文/日志人工判断后由用户决定是否重试")
    }

    /**
     * 审计行（单行、无参数内容；可 grep `[tool-repair]`）。
     *
     * 为什么只记建议参数的**键名**：建议参数里可能带着用户正文（`write_file.content`），
     * 日志不该成为第二个正文出口。错误原文截 160 字，与既有日志口径一致。
     */
    fun auditLine(advice: ToolRepairAdvice, errorText: String): String {
        val keys = advice.suggestedArgs?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: "-"
        return "$TAG tool=${flat(advice.toolName)} type=${advice.type.name} cause=${advice.cause.name} " +
            "suggestion=${advice.suggestion.name} suggested_keys=$keys " +
            "reason=${flat(advice.reason)} error=${flat(errorText).take(160)}"
    }

    // ── 内部：分类与建议参数 ──────────────────────────────────────────────

    private fun isPermission(text: String): Boolean =
        text.contains("已被用户关闭") || text.contains("未解锁") ||
            text.contains("路径越界") || text.contains("原件区只读") ||
            text.contains("权限") || text.contains("未授权") || text.contains("不允许")

    private fun isNetwork(text: String): Boolean =
        text.contains("网络") || text.contains("连接") || text.contains("断流") ||
            text.contains("socket", ignoreCase = true) || text.contains("connect", ignoreCase = true) ||
            text.contains("429") || text.contains("HTTP 5") || text.contains("5xx") ||
            text.contains("DNS", ignoreCase = true)

    private fun env(tool: String, type: ToolFailureType, reason: String, action: String): ToolRepairAdvice =
        ToolRepairAdvice(
            toolName = tool,
            type = type,
            cause = ToolFailureCause.ENVIRONMENT,
            suggestion = ToolRepairSuggestion.ENV_ISSUE,
            reason = reason,
            action = action,
        )

    private fun none(tool: String, reason: String, action: String): ToolRepairAdvice =
        ToolRepairAdvice(
            toolName = tool,
            type = ToolFailureType.UNKNOWN,
            cause = ToolFailureCause.UNKNOWN,
            suggestion = ToolRepairSuggestion.NONE,
            reason = reason,
            action = action,
        )

    /** 参数对象深复制（JSONObject 构造会重建子树；建议层永不原地改调用方的参数）。 */
    private fun copyArgs(args: JSONObject?): JSONObject =
        args?.let { runCatching { JSONObject(it.toString()) }.getOrNull() } ?: JSONObject()

    /** 类型错误的**无损**转换；转不了返回 null（不猜）。 */
    private fun convertTo(value: Any?, expected: String): Any? = when (expected) {
        "字符串" -> when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> null
        }

        "整数" -> when (value) {
            is Int, is Long -> value
            is Double -> if (value % 1.0 == 0.0) value.toLong() else null
            is String -> value.trim().toLongOrNull()
            else -> null
        }

        "数字" -> when (value) {
            is Number -> value
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }

        "布尔值" -> when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }

            else -> null
        }

        "数组" -> when (value) {
            is JSONArray -> value
            is String -> runCatching { JSONArray(value) }.getOrNull()
            else -> null
        }

        "对象" -> when (value) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        }

        else -> null
    }

    /** 数值越界按边界夹紧；原值不是数字或边界解析失败 ⇒ null。 */
    private fun clamp(value: Any?, direction: String, bound: Double?): Any? {
        if (bound == null) return null
        val number = (value as? Number)?.toDouble() ?: return null
        val fixed = if (direction == "小于") maxOf(number, bound) else minOf(number, bound)
        return if (value is Int || value is Long) fixed.toLong() else fixed
    }

    private fun flat(text: String): String =
        text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim()
}
