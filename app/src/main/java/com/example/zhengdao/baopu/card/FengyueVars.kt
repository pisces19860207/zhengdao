// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：599 派工单段D（2026-11-12）——风月变量模块四件：
//   ai_variable_json（数据骨架）／ai_variable_template（Handlebars＋<style> 皮肤）／
//   ai_variable_prompt（更新指令）／extend.ai_variable_enabled（总开关，默认 false）。
// 结构事实来源：对真件 app_full.json 的**只读探针**（2026-11-12）——四件住在
// model_config 下，前三件是字符串、开关是布尔；骨架是**示例值实例**（不是 JSON Schema）。
// 真件内容一律不进仓；本文件与测试只用合成夹具。
//
// 三个口径决定（都不是「照抄教程」）：
// 1. **校＝引用⊆骨架**：骨架是示例值实例，所以「字段在不在」就是「示例对象里有没有这条路径」。
//    模板引擎是 Handlebars 实测子集：{{path}}、{{#if}}/{{#unless}}/{{#each}}/{{#with}}、
//    {{else}}、{{this}}/{{this.x}}、.length 后缀、子表达式 (eq a b)。子集之外的 helper
//    **不猜、跳过**——猜一个不存在的语义比漏判更糟。
// 2. **空数组元素的 this.* 不猜**：骨架里数组为空 ⇒ 元素形状不可知 ⇒ 其内 this.* 一律跳过。
//    数组有示例元素 ⇒ 按第一个元素的形状校（实测真件就是这么写的）。
// 3. **开关默认 false**：没皮肤就不开——「开了面板没样式」比「没开面板」更难排查。
//    校与开关无关：就算本次不写皮肤，给了皮肤就必须先过校。
package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject

/**
 * 风月变量面板：认（detect）／导（draft）／校（validate）／导出（export）。
 *
 * 纯函数、零 IO、零网络、零 Android 依赖（org.json 在 JVM 与真机两套都有）。
 * 本对象只做格式层；把 detect 接进 CardCheck、把 export 接进 CardTools 是下一批的事。
 */
internal object FengyueVars {

    /** 四件不齐／骨架不是对象／模板引用不存在字段，都抛它（带人话原因，不吞）。 */
    class VarsError(message: String) : Exception(message)

    const val MODEL_CONFIG = "model_config"
    const val KEY_JSON = "ai_variable_json"
    const val KEY_TEMPLATE = "ai_variable_template"
    const val KEY_PROMPT = "ai_variable_prompt"
    const val KEY_EXTEND = "extend"
    const val KEY_ENABLED = "ai_variable_enabled"

    // ── ① 认：体检能报「这张卡用了变量面板」 ─────────────────────────────

    /** 认的结果：三件各自在不在（按**键存在**判，不看值空不空）＋开关三态（开/关/未写）。 */
    data class Detection(
        val hasJson: Boolean,
        val hasTemplate: Boolean,
        val hasPrompt: Boolean,
        val enabled: Boolean?,
    ) {
        val usesPanel: Boolean get() = hasJson || hasTemplate || hasPrompt

        fun message(): String {
            if (!usesPanel) return "这张卡没有变量面板（model_config 下没有 ai_variable_* 三件）。"
            val seen = listOfNotNull(
                KEY_JSON.takeIf { hasJson },
                KEY_TEMPLATE.takeIf { hasTemplate },
                KEY_PROMPT.takeIf { hasPrompt },
            ).joinToString("、")
            val switch = when (enabled) {
                true -> "开"
                false -> "关"
                null -> "未写（缺省按关）"
            }
            return "这张卡用了变量面板：见到 $seen；总开关 extend.$KEY_ENABLED=$switch。"
        }
    }

    fun detect(card: JSONObject): Detection {
        val mc = CardJson.objectOf(card, MODEL_CONFIG)
        return Detection(
            hasJson = mc?.has(KEY_JSON) == true,
            hasTemplate = mc?.has(KEY_TEMPLATE) == true,
            hasPrompt = mc?.has(KEY_PROMPT) == true,
            enabled = CardJson.boolOf(CardJson.objectOf(mc, KEY_EXTEND), KEY_ENABLED),
        )
    }

    // ── ② 导：母档状态字段 → 骨架草稿＋默认皮肤＋更新指令 ──────────────────

    data class Draft(val skeletonJson: String, val template: String, val prompt: String)

    /**
     * 把母档的状态字段（境界/好感/伤势/持有物/日期……调用方给什么收什么）导成三件草稿。
     * 骨架＝状态对象本身（示例值实例口径）；皮肤按骨架顶层键逐行生成；生成后**自校**。
     */
    fun draft(state: JSONObject): Draft {
        val skeletonJson = state.toString(2)
        val template = defaultTemplate(state, skeletonJson)
        validate(skeletonJson, template)
        return Draft(skeletonJson, template, DEFAULT_PROMPT)
    }

    /**
     * 默认皮肤：浅底深字、类名 scoped 在 .fy-vars 下、**不用 :root**（shadow DOM 里失效）。
     * 键序从文本扫（[CardJson.topLevelKeys]），JVM 与真机同一顺序。
     */
    private fun defaultTemplate(state: JSONObject, skeletonJson: String): String {
        val rows = StringBuilder()
        for (key in CardJson.topLevelKeys(skeletonJson, Int.MAX_VALUE)) {
            // 数组字段给 each 草稿；元素是对象时 {{this}} 只是占位——形状不猜，留给作者改。
            val value = if (state.opt(key) is JSONArray) "{{#each $key}}{{this}}、{{/each}}" else "{{$key}}"
            rows.append("<div class=\"row\"><b>").append(esc(key)).append("：</b>").append(value).append("</div>\n")
        }
        return "<style>\n$SAFE_CSS</style>\n<div class=\"fy-vars\">\n$rows</div>"
    }

    /** 键名进 HTML 文本前的最小转义（状态键来自用户输入，是边界）。 */
    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val SAFE_CSS =
        ".fy-vars{font-family:sans-serif;font-size:12px;color:#333;background:#fafafa;" +
            "border:1px solid #ddd;border-radius:8px;padding:8px;max-width:460px}\n" +
            ".fy-vars .row{margin:2px 0;line-height:1.6}\n" +
            ".fy-vars b{color:#111}"

    private const val DEFAULT_PROMPT =
        "每轮回复末尾，用一个独立的 ```json 代码块全量输出变量面板的当前状态。\n" +
            "规则：\n" +
            "1. 键名与结构逐字照抄 ai_variable_json 的骨架，不许新增、改名或删键；\n" +
            "2. 数值只按剧情因果变化：无因不变，有因必变，不许凭空跳变；\n" +
            "3. 面板是状态的唯一事实源：正文与面板冲突时以面板为准，下一轮修正正文；\n" +
            "4. 数组字段按实际增删逐项维护，不丢项。"

    // ── ③ 校：模板引用的字段必须在骨架里 ─────────────────────────────────

    /** 骨架必须是合法 JSON 对象；模板引用（含块参数、子表达式参数）逐条对骨架走查。 */
    fun validate(skeletonJson: String, template: String) {
        requireKnownRefs(parseSkeleton(skeletonJson), template)
    }

    private fun parseSkeleton(skeletonJson: String): JSONObject =
        CardJson.parseObject(skeletonJson)
            ?: throw VarsError("ai_variable_json 不是合法 JSON 对象（数据骨架必须是对象，不是数组/标量/坏文本）")

    private fun requireKnownRefs(skeleton: JSONObject, template: String) {
        val unknown = unknownRefs(skeleton, template)
        if (unknown.isNotEmpty()) {
            throw VarsError(
                "模板引用了骨架里没有的字段：${unknown.joinToString("、")}" +
                    "（共 ${unknown.size} 处；引用不存在字段 ⇒ 报错，不静默放行）",
            )
        }
    }

    private val MUSTACHE = Regex("\\{\\{([^{}]+)\\}\\}")
    private val SUBEXPR = Regex("\\(([^()]*)\\)")
    private val TOKEN = Regex("\"[^\"]*\"|[^\\s\"]+")

    /**
     * 作用域栈帧。[shape]＝this.* 的解析形状（null＝不可知，跳过）；
     * [rebase]＝裸路径是否也改挂到本帧——Handlebars 里 **with 改、each 不改**
     * （each 块内裸路径仍查根，只有 this.* 查元素），这是框架语义不是猜测。
     */
    private class Frame(val shape: JSONObject?, val rebase: Boolean)

    /**
     * 走查模板，返回**骨架里没有**的引用路径（去重、按出现序）。
     * contexts 是 each/with 的作用域栈：形状 null＝不可知（空数组），其内引用跳过。
     */
    private fun unknownRefs(skeleton: JSONObject, template: String): List<String> {
        val unknown = LinkedHashSet<String>()
        val contexts = ArrayList<Frame>()
        for (m in MUSTACHE.findAll(template)) {
            val body = m.groupValues[1].trim()
            when {
                body.isEmpty() || body.startsWith("!") || body.startsWith(">") || body.startsWith("^") -> Unit
                body.startsWith("#") -> blockOpen(body.substring(1).trim(), skeleton, contexts, unknown)
                body.startsWith("/") -> blockClose(body.substring(1).trim(), contexts)
                body == "else" -> Unit
                body.startsWith("else ") -> checkExpression(body.removePrefix("else ").trim(), skeleton, contexts, unknown)
                else -> checkExpression(body, skeleton, contexts, unknown)
            }
        }
        return unknown.toList()
    }

    /** 块开：each/with 校参数并压作用域栈；if/unless 只校参数；其余 helper 不猜、跳过。 */
    private fun blockOpen(
        body: String,
        skeleton: JSONObject,
        contexts: MutableList<Frame>,
        unknown: MutableSet<String>,
    ) {
        val helper = body.substringBefore(' ').trim()
        val arg = body.substringAfter(' ', "").trim()
        when (helper) {
            "each", "with" -> {
                checkExpression(arg, skeleton, contexts, unknown)
                contexts.add(Frame(contextShape(arg, skeleton, contexts), rebase = helper == "with"))
            }
            "if", "unless" -> checkExpression(arg, skeleton, contexts, unknown)
            else -> Unit
        }
    }

    private fun blockClose(helper: String, contexts: MutableList<Frame>) {
        if ((helper == "each" || helper == "with") && contexts.isNotEmpty()) {
            contexts.removeAt(contexts.size - 1)
        }
    }

    /** 表达式：先拆子表达式 (helper arg…)，helper 名与字符串字面量不查，其余 token 按路径查。 */
    private fun checkExpression(
        body: String,
        skeleton: JSONObject,
        contexts: List<Frame>,
        unknown: MutableSet<String>,
    ) {
        var rest = body
        for (sub in SUBEXPR.findAll(body)) {
            val tokens = TOKEN.findAll(sub.groupValues[1]).map { it.value }.toList()
            for ((i, tok) in tokens.withIndex()) {
                if (i == 0 || tok.startsWith("\"")) continue
                checkPath(tok, skeleton, contexts, unknown)
            }
            rest = rest.replace(sub.value, " ")
        }
        // 子集外的嵌套括号不猜语义，但括号本身不该被当成路径字符。
        rest = rest.replace("(", " ").replace(")", " ")
        for (tok in TOKEN.findAll(rest).map { it.value }) {
            if (!tok.startsWith("\"")) checkPath(tok, skeleton, contexts, unknown)
        }
    }

    /** 字面量与 this 本身不是字段引用（数字/布尔/null/字符串在子表达式里是参数值）。 */
    private fun isLiteral(token: String): Boolean =
        token == "this" || token == "." || token == "true" || token == "false" || token == "null" ||
            token.startsWith("\"") || token.toDoubleOrNull() != null

    /**
     * 解析起点：剥 .length；this. 走栈顶形状（栈空＝根）；裸路径在 with 块内走最近的
     * with 形状、否则走根。返回 null＝整条跳过（字面量/@内置/上级路径）；
     * 返回的 first 为 null＝形状不可知（空数组），调用方同样跳过。
     */
    private fun scopeOf(raw: String, skeleton: JSONObject, contexts: List<Frame>): Pair<JSONObject?, String>? {
        var path = raw.trim()
        if (path.isEmpty() || isLiteral(path)) return null
        if (path.startsWith("@") || path.startsWith("../")) return null
        if (path.endsWith(".length")) path = path.removeSuffix(".length")
        if (path.startsWith("this.")) {
            val shape = if (contexts.isEmpty()) skeleton else contexts.last().shape
            return shape to path.removePrefix("this.")
        }
        val with = contexts.lastOrNull { it.rebase }
        return (if (with != null) with.shape else skeleton) to path
    }

    /** 查一条路径：逐段必须在对象里**有键**（值是什么不挑）；查不到记进 unknown。 */
    private fun checkPath(
        raw: String,
        skeleton: JSONObject,
        contexts: List<Frame>,
        unknown: MutableSet<String>,
    ) {
        val (base, path) = scopeOf(raw, skeleton, contexts) ?: return
        if (base == null) return
        var cur: Any? = base
        for (seg in path.split('.')) {
            val obj = cur as? JSONObject
            if (obj == null || !obj.has(seg)) {
                unknown.add(raw.trim())
                return
            }
            cur = obj.opt(seg)
        }
    }

    /** each/with 的元素形状：对象＝本身；数组＝第一个示例元素（空/非对象 ⇒ null，不猜）。 */
    private fun contextShape(arg: String, skeleton: JSONObject, contexts: List<Frame>): JSONObject? {
        val value = resolveValue(arg, skeleton, contexts) ?: return null
        return when (value) {
            is JSONObject -> value
            is JSONArray -> value.optJSONObject(0)
            else -> null
        }
    }

    /** 按当前作用域解析一条路径的值（解析不到 ⇒ null；与 checkPath 的「记未知」职责不同）。 */
    private fun resolveValue(raw: String, skeleton: JSONObject, contexts: List<Frame>): Any? {
        val (base, path) = scopeOf(raw, skeleton, contexts) ?: return null
        var cur: Any? = base ?: return null
        for (seg in path.split('.')) {
            val obj = cur as? JSONObject ?: return null
            cur = obj.opt(seg)
        }
        return cur
    }

    // ── ④ 导出：开关默认 false，没皮肤就不开 ─────────────────────────────

    /** 导出结果：modelConfig 整个放进卡的 model_config 键；notices 是要回灌给用户/模型的人话。 */
    data class Exported(val modelConfig: JSONObject, val notices: List<String>)

    /**
     * 产出 model_config 对象。纪律：
     * - 骨架**恒校形状**（不是对象 ⇒ VarsError）；给了皮肤就**恒校引用**（与开关无关）；
     * - 开关缺省/null ⇒ false；true 但没皮肤 ⇒ 降 false 并提示（免得「开了面板没样式」）；
     * - 不开 ⇒ 不写 ai_variable_template，并提示「要开面板请给皮肤」。
     */
    fun export(skeletonJson: String, template: String?, prompt: String, enabled: Boolean?): Exported {
        val skeleton = parseSkeleton(skeletonJson)
        val skin = template?.takeIf { it.isNotBlank() }
        if (skin != null) requireKnownRefs(skeleton, skin)
        val notices = ArrayList<String>()
        var on = enabled ?: false
        if (on && skin == null) {
            on = false
            notices.add("要开面板请给皮肤：没给 ai_variable_template，总开关已按默认 false 处理。")
        } else if (!on) {
            notices.add("要开面板请给皮肤：总开关缺省/false，本次导出不带皮肤（ai_variable_template 未写出）。")
        }
        val mc = JSONObject()
            .put(KEY_JSON, skeletonJson)
            .put(KEY_PROMPT, prompt)
            .put(KEY_EXTEND, JSONObject().put(KEY_ENABLED, on))
        if (on) mc.put(KEY_TEMPLATE, skin)
        return Exported(mc, notices)
    }
}