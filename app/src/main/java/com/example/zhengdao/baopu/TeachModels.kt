// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 · 学习器＋执行器（示教一次 → 技能 → 可靠回放）。
//
// 技能是 JSON、不是代码（hermes-agent `record`+`/learn` 的产品形态）：本文件是回放技能
// 的**唯一数据形状**，学习器产出它、执行器消费它、`TeachReplayStore` 落盘它。
//
// 红线（派工单 §1/§2）：不录坐标（只录语义）；技能不自动改（改要人确认）——所以本模型
// 是不可变 data class，执行器全程只读。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject

// ── 十级定位优先级（§0，arXiv 2603.20358 的十级链）────────────────────────────
//
// tier 越小越优先。派工单锚定的五档：role(T1) → data-testid(T2) → aria-label(T3) →
// 可见文本(T7) → CSS 片段(T9)，兜底链末档是 XPath(T10)。
// 置信度（CANVAS 语义意图定位＋置信度门控）：语义档 ≥0.60，低档 CSS/XPath ≤0.40——
// 低档永远过不了执行闸（EXECUTE_MIN=0.55），这正是「只命中低档 ⇒ 停下问人」的落点。

/** 定位档置信度（唯一命中时的基线；多命中一律降级为「问人」）。 */
fun tierConfidence(tier: Int): Double = when (tier) {
    1 -> 0.95 // role + 可访问名（W3C 语义，最强）
    2 -> 0.90 // data-testid
    3 -> 0.85 // aria-label
    4 -> 0.80 // 元素 id（原生）
    5 -> 0.75 // 表单控件 name 属性
    6 -> 0.70 // placeholder
    7 -> 0.65 // 可见文本
    8 -> 0.60 // 表单标签/列名上下文（label 关联文本）
    9 -> 0.40 // CSS 类片段（只取稳定类，构建哈希类丢弃）——低档
    10 -> 0.30 // XPath —— 低档，最后一档
    else -> 0.0
}

/** 执行闸：唯一命中且置信度 ≥ 此值才允许动手；低档（T9/T10）永远低于它。 */
const val TEACH_EXECUTE_MIN_CONFIDENCE = 0.55

/** 低档 tier 的集合（只命中这些 ⇒ 停下问人，不硬点）。 */
val TEACH_LOW_TIERS = setOf(9, 10)

/** 定位器的判别类型（与 tier 一一对应的语义；序列化成 kind 字符串）。 */
enum class TeachLocatorKind(val tier: Int, val wire: String) {
    ROLE_NAME(1, "roleName"),
    TESTID(2, "testid"),
    ARIA_LABEL(3, "ariaLabel"),
    ELEM_ID(4, "elemId"),
    INPUT_NAME(5, "inputName"),
    PLACEHOLDER(6, "placeholder"),
    TEXT(7, "text"),
    FORM_CONTEXT(8, "formContext"),
    CSS_CLASS(9, "cssClass"),
    XPATH(10, "xpath"),
    ;

    companion object {
        fun fromWire(w: String): TeachLocatorKind? = entries.firstOrNull { it.wire == w }
    }
}

/**
 * 一条定位器。每步至少记 2 档（派工单 §1 判据），回放按 tier 升序逐档试。
 *
 * @param value 主判别值：ROLE_NAME 为 `role|name`；TESTID/ARIA_LABEL/ELEM_ID/INPUT_NAME/
 *   PLACEHOLDER/TEXT 为属性或文本原值；FORM_CONTEXT 为 `标签文本|控件文本`；CSS_CLASS 为
 *   稳定类名（`.` 连接）；XPATH 为 XPath 表达式。
 */
data class TeachLocator(val kind: TeachLocatorKind, val value: String) {
    val tier: Int get() = kind.tier

    /** 人可读的一行（漂移报告与确认卡用）。 */
    fun describe(): String = "T$tier ${kind.wire}=$value"

    fun toJson(): JSONObject = JSONObject()
        .put("tier", tier)
        .put("kind", kind.wire)
        .put("value", value)

    companion object {
        fun fromJson(o: JSONObject): TeachLocator? {
            val kind = TeachLocatorKind.fromWire(o.optString("kind")) ?: return null
            val value = o.optString("value")
            if (value.isBlank()) return null
            return TeachLocator(kind, value)
        }
    }
}

/** 动作类型（§1：click / type / select / scroll / waitFor；翻页抽象成 click＋waitFor 行数变化）。 */
enum class TeachAction(val wire: String) {
    CLICK("click"),
    TYPE("type"),
    SELECT("select"),
    SCROLL("scroll"),
    WAIT("waitFor"),
    ;

    companion object {
        fun fromWire(w: String): TeachAction? = entries.firstOrNull { it.wire == w }
    }
}

/** 每步自动补的期望断言（§1：URL 变化 / 关键文本出现 / 列表行数变化 / 元素消失）。 */
enum class TeachExpectKind(val wire: String) {
    URL_CHANGED("urlChanged"),
    TEXT_APPEARS("textAppears"),
    ROW_COUNT_CHANGE("rowCountChange"),
    ELEMENT_GONE("elementGone"),
    ;

    companion object {
        fun fromWire(w: String): TeachExpectKind? = entries.firstOrNull { it.wire == w }
    }
}

data class TeachExpect(val kind: TeachExpectKind, val value: String?) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind.wire).put("value", value ?: "")

    companion object {
        fun fromJson(o: JSONObject): TeachExpect? {
            val k = TeachExpectKind.fromWire(o.optString("kind")) ?: return null
            val v = o.optString("value").takeIf { it.isNotEmpty() }
            return TeachExpect(k, v)
        }
    }
}

/** 示教那一刻目标元素的语义快照（自愈时用来认「还是不是同一个元素」，不参与正常定位）。 */
data class TeachElementSnapshot(
    val tag: String,
    val role: String?,
    val name: String?, // 可访问名
    val text: String?, // 可见文本（截 40 字）
    val inputType: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tag", tag)
        .put("role", role ?: "")
        .put("name", name ?: "")
        .put("text", text ?: "")
        .put("inputType", inputType ?: "")

    companion object {
        fun fromJson(o: JSONObject): TeachElementSnapshot = TeachElementSnapshot(
            tag = o.optString("tag").ifBlank { "?" },
            role = o.optString("role").takeIf { it.isNotEmpty() },
            name = o.optString("name").takeIf { it.isNotEmpty() },
            text = o.optString("text").takeIf { it.isNotEmpty() },
            inputType = o.optString("inputType").takeIf { it.isNotEmpty() },
        )
    }
}

/** 一个技能步骤（不可变；执行器只读，永不回写）。 */
data class TeachStep(
    val index: Int,
    val action: TeachAction,
    val url: String,
    val locators: List<TeachLocator>, // 按 tier 升序，至少 2 条（学习器保证）
    val value: String?, // type/select 的值；click/scroll/wait 为 null
    val snapshot: TeachElementSnapshot,
    val expect: List<TeachExpect>,
    /**
     * 第二百批：`select` 步录制时页面给的全部选项（值＋显示文案）。参数化用它出「可选：北京/天津」，
     * 执行期用它把用户说的中文映射回页面要的 value。空 = 非下拉步或录制端没给。
     */
    val options: List<TeachOption> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("i", index)
        .put("action", action.wire)
        .put("url", url)
        .put("locators", JSONArray(locators.map { it.toJson() }))
        .put("value", value ?: "")
        .apply { if (options.isNotEmpty()) put("options", JSONArray(options.map { it.toJson() })) }
        .put("snapshot", snapshot.toJson())
        .put("expect", JSONArray(expect.map { it.toJson() }))

    companion object {
        /** 步骤允许的键（严格解析用）。 */
        val KEYS: Set<String> = setOf("i", "action", "url", "locators", "value", "options", "snapshot", "expect")

        fun fromJson(o: JSONObject): TeachStep? {
            val action = TeachAction.fromWire(o.optString("action")) ?: return null
            val locs = mutableListOf<TeachLocator>()
            val arr = o.optJSONArray("locators") ?: return null
            for (i in 0 until arr.length()) {
                val loc = arr.optJSONObject(i) ?: continue
                TeachLocator.fromJson(loc)?.let { locs += it }
            }
            // WAIT 步只等 expect、没有目标元素，允许空定位链；其余动作至少要有一条
            if (locs.isEmpty() && action != TeachAction.WAIT) return null
            return TeachStep(
                index = o.optInt("i", 0),
                action = action,
                url = o.optString("url"),
                locators = locs.sortedBy { it.tier },
                value = o.optString("value").takeIf { it.isNotEmpty() },
                snapshot = TeachElementSnapshot.fromJson(o.optJSONObject("snapshot") ?: JSONObject()),
                expect = run {
                    val out = mutableListOf<TeachExpect>()
                    val ea = o.optJSONArray("expect") ?: JSONArray()
                    for (i in 0 until ea.length()) {
                        val e = ea.optJSONObject(i) ?: continue
                        TeachExpect.fromJson(e)?.let { out += it }
                    }
                    out
                },
                options = run {
                    val arr = o.optJSONArray("options") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(TeachOption::fromJson) }
                },
            )
        }
    }
}

/** 结构指纹锚点：关键表头或关键按钮的可访问名/文本（§2「页面结构变了」的判据）。 */
data class TeachFingerprintAnchor(val kind: String, val name: String) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("name", name)

    companion object {
        fun fromJson(o: JSONObject): TeachFingerprintAnchor? {
            val n = o.optString("name")
            if (n.isBlank()) return null
            return TeachFingerprintAnchor(kind = o.optString("kind").ifBlank { "button" }, name = n)
        }
    }
}

/** 整体结构指纹（起点页关键表头/关键按钮的 name 集合；任一缺失 ⇒ 停手）。 */
data class TeachFingerprint(val anchors: List<TeachFingerprintAnchor>) {
    fun toJson(): JSONObject = JSONObject()
        .put("anchors", JSONArray(anchors.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): TeachFingerprint = TeachFingerprint(
            anchors = run {
                val out = mutableListOf<TeachFingerprintAnchor>()
                val arr = o.optJSONArray("anchors") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    TeachFingerprintAnchor.fromJson(a)?.let { out += it }
                }
                out
            },
        )
    }
}

/**
 * 一份回放技能。schema 只认 [SCHEMA]；格式变更必须升版本号，不许原地改语义。
 */
data class TeachSkill(
    val name: String,
    val purpose: String,
    val createdAtMs: Long,
    val startUrl: String,
    val allowedDomains: List<String>,
    val clickTagWhitelist: List<String>,
    val fingerprint: TeachFingerprint,
    val steps: List<TeachStep>,
    /**
     * 第二百批：参数表（空 = 没参数化，字节与 196 的技能文件完全一致）。
     * 参数值**不在**这里出现——步骤里只有 `${name}` 占位，声明的默认值与下拉项是
     * 「用户明确写下的东西」，不算残留字面量（见 [TeachParams.residualLiterals] 的口径）。
     */
    val params: List<TeachParam> = emptyList(),
) {
    companion object {
        const val SCHEMA = "zl-teach/1"

        /**
         * 顶层允许的键（严格解析用；[strict]=true 时多一个键就整份不收）。
         * 第二百批的意义：编辑器**不可能**表达条件分支/循环/拖拽树——因为 schema 里没有
         * 这些键，严格解析见到 `loop`/`if`/`repeat` 一律拒收（负例 6 的结构面）。
         */
        val TOP_KEYS: Set<String> = setOf(
            "schema", "name", "purpose", "createdAt", "startUrl", "allowedDomains",
            "clickTagWhitelist", "fingerprint", "steps", "params",
        )

        fun fromJson(text: String, strict: Boolean = false): TeachSkill? = runCatching {
            val o = JSONObject(text)
            if (o.optString("schema") != SCHEMA) return@runCatching null
            if (strict && unknownKeys(o, TOP_KEYS).isNotEmpty()) return@runCatching null
            val steps = mutableListOf<TeachStep>()
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                if (strict && unknownKeys(s, TeachStep.KEYS).isNotEmpty()) return@runCatching null
                TeachStep.fromJson(s)?.let { steps += it }
            }
            if (steps.isEmpty()) return@runCatching null
            val domains = mutableListOf<String>()
            val da = o.optJSONArray("allowedDomains") ?: JSONArray()
            for (i in 0 until da.length()) da.optString(i).takeIf { it.isNotBlank() }?.let { domains += it }
            val tags = mutableListOf<String>()
            val ta = o.optJSONArray("clickTagWhitelist") ?: JSONArray()
            for (i in 0 until ta.length()) ta.optString(i).takeIf { it.isNotBlank() }?.let { tags += it }
            val params = mutableListOf<TeachParam>()
            val pa = o.optJSONArray("params") ?: JSONArray()
            for (i in 0 until pa.length()) {
                val p = pa.optJSONObject(i) ?: continue
                if (strict && unknownKeys(p, TeachParam.KEYS).isNotEmpty()) return@runCatching null
                TeachParam.fromJson(p)?.let { params += it }
            }
            if (strict && params.map { it.name }.toSet().size != params.size) return@runCatching null
            TeachSkill(
                name = o.optString("name").ifBlank { return@runCatching null },
                purpose = o.optString("purpose"),
                createdAtMs = o.optLong("createdAt", 0L),
                startUrl = o.optString("startUrl"),
                allowedDomains = domains,
                clickTagWhitelist = tags,
                fingerprint = TeachFingerprint.fromJson(o.optJSONObject("fingerprint") ?: JSONObject()),
                steps = steps,
                params = params,
            )
        }.getOrNull()

        private fun unknownKeys(o: JSONObject, allowed: Set<String>): List<String> =
            o.keys().asSequence().filter { it !in allowed }.toList()
    }

    /**
     * 规范序列化（**手写固定键序**，不经 JSONObject.toString）：同一份数据任何进程、
     * 任何 org.json 实现（maven 版 HashMap 键序不定）下字节一致——「技能未变（哈希不变）」
     * 的负例判据靠它成立。解析仍走 [fromJson]（解析不依赖键序）。
     */
    fun toJsonText(): String = buildString {
        append("{\"schema\":").append(q(SCHEMA))
        append(",\"name\":").append(q(name))
        append(",\"purpose\":").append(q(purpose))
        append(",\"createdAt\":").append(createdAtMs)
        append(",\"startUrl\":").append(q(startUrl))
        append(",\"allowedDomains\":[").append(allowedDomains.joinToString(",") { q(it) }).append(']')
        append(",\"clickTagWhitelist\":[").append(clickTagWhitelist.joinToString(",") { q(it) }).append(']')
        append(",\"fingerprint\":{\"anchors\":[")
            .append(fingerprint.anchors.joinToString(",") { "{\"kind\":" + q(it.kind) + ",\"name\":" + q(it.name) + "}" })
            .append("]}")
        append(",\"steps\":[").append(steps.joinToString(",") { it.canonical() }).append(']')
        // 第二百批：参数表只在有内容时出现（空 ⇒ 与 196 的技能文件字节一致）
        if (params.isNotEmpty()) {
            append(",\"params\":[").append(params.joinToString(",") { it.canonical() }).append(']')
        }
        append('}')
    }

    private fun q(s: String): String = "\"" + teachJsonEsc(s) + "\""
}

/** 规范序列化用的字符串转义（与 PageSnapshot.jsonEscape 同语义：引号/反斜杠/控制字符）。 */
internal fun teachJsonEsc(s: String): String = buildString {
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}

/** 步骤的规范序列化（键序固定：i/action/url/locators/value/snapshot/expect）。 */
internal fun TeachStep.canonical(): String = buildString {
    append("{\"i\":").append(index)
    append(",\"action\":").append('"').append(action.wire).append('"')
    append(",\"url\":").append('"').append(teachJsonEsc(url)).append('"')
    append(",\"locators\":[").append(
        locators.joinToString(",") {
            "{\"tier\":" + it.tier + ",\"kind\":" + '"' + it.kind.wire + '"' +
                ",\"value\":" + '"' + teachJsonEsc(it.value) + '"' + "}"
        },
    ).append(']')
    append(",\"value\":").append('"').append(teachJsonEsc(value ?: "")).append('"')
    // 第二百批：选项表只在有内容时出现（空 ⇒ 老技能文件序列化字节一字不变，金样照旧）
    if (options.isNotEmpty()) {
        append(",\"options\":[").append(
            options.joinToString(",") {
                "{\"v\":" + '"' + teachJsonEsc(it.value) + '"' + ",\"t\":" + '"' + teachJsonEsc(it.label) + '"' + "}"
            },
        ).append(']')
    }
    append(",\"snapshot\":{\"tag\":").append('"').append(teachJsonEsc(snapshot.tag)).append('"')
    append(",\"role\":").append('"').append(teachJsonEsc(snapshot.role ?: "")).append('"')
    append(",\"name\":").append('"').append(teachJsonEsc(snapshot.name ?: "")).append('"')
    append(",\"text\":").append('"').append(teachJsonEsc(snapshot.text ?: "")).append('"')
    append(",\"inputType\":").append('"').append(teachJsonEsc(snapshot.inputType ?: "")).append("\"}")
    append(",\"expect\":[").append(
        expect.joinToString(",") {
            "{\"kind\":" + '"' + it.kind.wire + '"' + ",\"value\":" + '"' + teachJsonEsc(it.value ?: "") + '"' + "}"
        },
    ).append("]}")
}

// ── 第二百批 · 参数（技能里「可以换的那几个值」）────────────────────────────────
//
// 一句话：别把「北京」写死进技能。录一遍，把「北京」拔出来变成 `${direction}`，
// 明天说「查天津」就填 `${direction}=天津` 复用同一条技能。
//
// 三条不许松的口：
// 1. **字面量必须从步骤里拔掉**——参数化的判据不是「多了一个 params 字段」，而是
//    「步骤里再也找不到那个值」（[TeachParams.residualLiterals] 解析 JSON 断言）；
// 2. **默认值不许静默生效**——[TeachParam.default] 只是「反问时可以说『就用北京』」的
//    候选，执行期永远要用户显式确认（[TeachParams.parse] 永不把 default 塞进 values）；
// 3. **参数值不进日志**——审计行/取证摘要里的值一律换成占位符（[TeachParams.redact]）。

/** 参数值的来源（派工单 §1 的四类；页面固定文本是「列出来但不许勾」的那类）。 */
enum class TeachParamSource(val wire: String) {
    /** URL 的 query 值：`?direction=北京`。 */
    URL_QUERY("urlQuery"),

    /** URL 的 path 段：`/list/2026-10-24/`。 */
    URL_PATH("urlPath"),

    /** 输入框里填的值。 */
    INPUT("input"),

    /** 下拉/单选的选中项。 */
    SELECT("select"),

    /** 页面上的固定文本（多为结构锚，参数化会打断定位 ⇒ 默认不勾且不可参数化）。 */
    PAGE_TEXT("pageText"),
    ;

    companion object {
        fun fromWire(w: String): TeachParamSource? = entries.firstOrNull { it.wire == w }
    }
}

/** 参数值的形状（决定执行期怎么从一句话里认它；**不是**从值本身猜出来的运态判据）。 */
enum class TeachParamShape(val wire: String) {
    /** 日期：`2026-10-24`；相对日期（昨天/上周）在执行期换算成具体日期。 */
    DATE("date"),

    /** 编码类：字母＋数字（单号、车牌）。 */
    CODE("code"),

    /** 纯数字（件数、条数）。 */
    NUMBER("number"),

    /** 枚举：有选项表（下拉项）⇒ 可以按选项词表认，也可以按词表校验。 */
    ENUM("enum"),

    /** 自由文本（没有词表也没有形状）⇒ 只认引号里的原话，否则反问。 */
    TEXT("text"),
    ;

    companion object {
        fun fromWire(w: String): TeachParamShape? = entries.firstOrNull { it.wire == w }
    }
}

/** 一个占位落点：第几步的哪个字段。（0 号步 = 技能起点 URL。） */
enum class TeachSlotField(val wire: String) {
    VALUE("value"),
    EXPECT("expect"),
    URL("url"),
    START_URL("startUrl"),
    ;

    companion object {
        fun fromWire(w: String): TeachSlotField? = entries.firstOrNull { it.wire == w }
    }
}

/** 占位落点（位置即证据：参数到底替换了哪几个字）。 */
data class TeachSlot(val step: Int, val field: TeachSlotField) {
    fun toJson(): JSONObject = JSONObject().put("i", step).put("f", field.wire)

    /** 人可读一行（编辑器与报告用）。 */
    fun describe(): String = when (field) {
        TeachSlotField.VALUE -> "第 $step 步的输入值"
        TeachSlotField.EXPECT -> "第 $step 步的期望断言"
        TeachSlotField.URL -> "第 $step 步的页面 URL"
        TeachSlotField.START_URL -> "起点 URL"
    }

    companion object {
        fun fromJson(o: JSONObject): TeachSlot? {
            val f = TeachSlotField.fromWire(o.optString("f")) ?: return null
            return TeachSlot(o.optInt("i", 0), f)
        }
    }
}

/** 下拉项：页面要的值（v）与给人看的文案（t）。 */
data class TeachOption(val value: String, val label: String) {
    /** 给人看的一行（反问与编辑器用）。 */
    fun display(): String = if (label.isBlank() || label == value) value else "$label（$value）"

    fun toJson(): JSONObject = JSONObject().put("v", value).put("t", label)

    companion object {
        fun fromJson(o: JSONObject): TeachOption? {
            val v = o.optString("v")
            val t = o.optString("t").ifBlank { v }
            if (v.isBlank()) return null
            return TeachOption(v, t)
        }
    }
}

/**
 * 一个参数。落盘只存「声明」不存「样例」：
 * 名字/来源/形状/占位落点/默认值/选项表——**示教时那个值本身不落盘**，
 * 否则「技能 JSON 里不再出现这两个字面量」这条判据永远过不了。
 */
data class TeachParam(
    val name: String,
    val label: String,
    val source: TeachParamSource,
    val shape: TeachParamShape,
    val slots: List<TeachSlot>,
    val default: String? = null,
    val options: List<TeachOption> = emptyList(),
) {
    /** 步骤里用的占位符形态：`${name}`。 */
    val placeholder: String get() = placeholderOf(name)

    fun canonical(): String = buildString {
        append("{\"name\":\"").append(teachJsonEsc(name)).append('"')
        append(",\"label\":\"").append(teachJsonEsc(label)).append('"')
        append(",\"source\":\"").append(source.wire).append('"')
        append(",\"shape\":\"").append(shape.wire).append('"')
        append(",\"slots\":[").append(
            slots.joinToString(",") { "{\"i\":" + it.step + ",\"f\":\"" + it.field.wire + "\"}" },
        ).append(']')
        if (default != null) append(",\"default\":\"").append(teachJsonEsc(default)).append('"')
        if (options.isNotEmpty()) {
            append(",\"options\":[").append(
                options.joinToString(",") {
                    "{\"v\":\"" + teachJsonEsc(it.value) + "\",\"t\":\"" + teachJsonEsc(it.label) + "\"}"
                },
            ).append(']')
        }
        append('}')
    }

    /** 人可读一行（编辑器参数表）。 */
    fun describe(): String = buildString {
        append(name)
        if (label.isNotBlank() && label != name) append("（$label）")
        append(" · ").append(shape.wire)
        append(" · ").append(slots.joinToString("、") { it.describe() })
        default?.let { append(" · 默认 ").append(it).append("（要用得说一句）") }
    }

    fun toJson(): JSONObject = canonical().let { JSONObject(it) }

    companion object {
        /** 参数名规矩：英文小写开头，只留 [a-z0-9_]，最长 32。 */
        val NAME_RE: Regex = Regex("^[a-z][a-z0-9_]{0,31}$")

        val KEYS: Set<String> = setOf("name", "label", "source", "shape", "slots", "default", "options")

        fun fromJson(o: JSONObject): TeachParam? {
            val name = o.optString("name")
            if (!NAME_RE.matches(name)) return null
            val source = TeachParamSource.fromWire(o.optString("source")) ?: return null
            val shape = TeachParamShape.fromWire(o.optString("shape")) ?: TeachParamShape.TEXT
            val slots = run {
                val arr = o.optJSONArray("slots") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(TeachSlot::fromJson) }
            }
            val options = run {
                val arr = o.optJSONArray("options") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(TeachOption::fromJson) }
            }
            return TeachParam(
                name = name,
                label = o.optString("label").ifBlank { name },
                source = source,
                shape = shape,
                slots = slots,
                default = o.optString("default").takeIf { it.isNotEmpty() },
                options = options,
            )
        }
    }
}

/** 占位符形态（全仓唯一口径：`${name}`）。 */
internal fun placeholderOf(name: String): String = "\${$name}"

/** 文本里出现的全部占位名（`${...}` 里的内容）。 */
internal fun placeholderNamesIn(text: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        val open = text.indexOf("\${", i)
        if (open < 0) break
        val close = text.indexOf('}', open + 2)
        if (close < 0) break
        val name = text.substring(open + 2, close)
        if (TeachParam.NAME_RE.matches(name)) out += name
        i = close + 1
    }
    return out
}
