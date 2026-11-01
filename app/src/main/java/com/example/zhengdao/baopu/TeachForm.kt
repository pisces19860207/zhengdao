// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零一批 · 表单类平台场景（风月制卡 v1：录一遍、批量填、发布你自己点）。
//
// 与 196（示教→回放）的关系：**同一套定位器与安全闸，换掉动作集**。
//   196 的动作集是 click/type/select/scroll/wait —— 有 click，所以能点到「提交」；
//   201 的动作集只有「填」与「读回」：**没有点击、没有快捷键**（[FormControl] 里没有 click，
//   [FormDom] 端口里也没有 click 通道）。红线不是靠自觉，是靠**端口没有这条线**。
//
// 一句人话：抱朴把内容填好、给你看，最后一下「创建/发布」永远你自己点。
//
// 三层结构（与 196 同形，判据全在纯函数里、JVM 直测）：
//   ① [TeachFormRules]：分类（哪个控件是什么）、校验（回读对不对）、参数化（卡怎么来）；
//   ② [FormFiller]：一张卡一条字段地填——探针 → 拒填安全闸 → 填 → **回读** → 校验；
//   ③ [FormBatch]：一张一行、填完就出「待发布卡」、**等你说下一张才动下一张**。
//
// 两条出口（第二百零一批本批补的，之前这三层合了却一个调用点都没有）：
//   · 批量 → `form_fill` 的 `batch` 分支调 [TeachForm.batch]，模板在 start 时从当前页
//     现扫（[TeachForm.scanPage]）；
//   · 回放 → 196 的示教回放在 TYPE/SELECT 两支改走 [FormFiller]（`TeachReplay.kt` 的
//     `FormFillingTeachDom`）——「批量填」与「回放填」用的是同一个填表器，没有第二套。
//
// 六条负例的落点（派工单 §3）：
//   1 点到创建 ⇒ [TeachFormRules.refuseSubmitLike] + 端口无点击通道 + 源级门禁；
//   2 填进密码框 ⇒ 扫描期排除 + 动手前探针再拒（[TeachSafety.refuseTypeReason]）；
//   3 富文本降级 ⇒ [TeachFormRules.check] 比「行数」而不只比文字；
//   4 多段只填第一段 ⇒ [TeachFormRules.precheck] 先查缺段，填完再查每段回读；
//   5 回读不一致却报填好 ⇒ [FieldOutcome.ok] 只由回读校验决定，填成功不算数；
//   6 批量重名覆盖 ⇒ [TeachFormRules.uniqueName]（`-2`/`-3`）+ 证据目录不覆盖。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.BaopuBrowser
import com.example.zhengdao.bridge.BrowserCommands
import com.example.zhengdao.bridge.jsStringLiteral
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ── ① 控件类别（v1 只支持这四类叶子控件＋一种固定条数的重复块）───────────────

/**
 * 叶子控件的四类。**刻意没有 CLICK / SUBMIT**：这是「不点创建」的第一层保证——
 * 类型系统里根本没有那个动作，写不出「点一下」这种代码。
 */
enum class FormControl(val wire: String) {
    /** 单行输入：`<input type=text|search|email|tel|url|number>`。 */
    SINGLE_LINE("single"),

    /** 多行/富文本：`<textarea>` 与 `contenteditable`（换行与分段要保住）。 */
    RICH_TEXT("rich"),

    /** 下拉：`<select>`（值必须落在录制时的选项表里）。 */
    SELECT("select"),

    /** 单选：`<input type=radio>`（同 name 一组，按选项值/选项文案挑一个）。 */
    RADIO("radio"),
    ;

    companion object {
        fun fromWire(w: String): FormControl? = entries.firstOrNull { it.wire == w }
    }
}

/** 一个叶子字段（不可变：模板全程只读，填表不回写模板）。 */
data class FormField(
    /** 字段键（参数名口径：[TeachParam.NAME_RE]）：`name` / `persona` / `worldbook1_title`。 */
    val key: String,
    /** 给人看的字段名（页面标签文本，取不到就用键）。 */
    val label: String,
    val control: FormControl,
    /** 十级定位链（与 196 同一套 [TeachLocator]，回放口径一致）。 */
    val locators: List<TeachLocator>,
    /** 下拉/单选的选项表（录制时页面给的；空 = 非下拉或录制端没给）。 */
    val options: List<TeachOption> = emptyList(),
    /** 重复块的第几段（1 起；0 = 不在重复块里）。 */
    val blockIndex: Int = 0,
    val required: Boolean = true,
    /** 录制时的控件事實（闸门判据的兜底面：探针取不到时才用）。 */
    val inputType: String? = null,
    val inputName: String? = null,
    val placeholder: String? = null,
    val maxLen: Int? = null,
) {
    /** 人可读一行（卡面/报告用）。 */
    fun describe(): String = buildString {
        append(label.ifBlank { key })
        append("（").append(key).append(" · ")
        append(
            when (control) {
                FormControl.SINGLE_LINE -> "单行"
                FormControl.RICH_TEXT -> "多行富文本"
                FormControl.SELECT -> "下拉"
                FormControl.RADIO -> "单选"
            },
        )
        if (blockIndex > 0) append(" · 第 ").append(blockIndex).append(" 段")
        if (!required) append(" · 选填")
        append("）")
    }

    fun canonical(): String = buildString {
        append("{\"key\":\"").append(teachJsonEsc(key)).append('"')
        append(",\"label\":\"").append(teachJsonEsc(label)).append('"')
        append(",\"control\":\"").append(control.wire).append('"')
        append(",\"locators\":[").append(
            locators.joinToString(",") {
                "{\"tier\":" + it.tier + ",\"kind\":\"" + it.kind.wire + "\",\"value\":\"" +
                    teachJsonEsc(it.value) + "\"}"
            },
        ).append(']')
        if (options.isNotEmpty()) {
            append(",\"options\":[").append(
                options.joinToString(",") {
                    "{\"v\":\"" + teachJsonEsc(it.value) + "\",\"t\":\"" + teachJsonEsc(it.label) + "\"}"
                },
            ).append(']')
        }
        if (blockIndex > 0) append(",\"blockIndex\":").append(blockIndex)
        if (!required) append(",\"required\":false")
        inputType?.let { append(",\"inputType\":\"").append(teachJsonEsc(it)).append('"') }
        inputName?.let { append(",\"inputName\":\"").append(teachJsonEsc(it)).append('"') }
        placeholder?.let { append(",\"placeholder\":\"").append(teachJsonEsc(it)).append('"') }
        maxLen?.let { append(",\"maxLen\":").append(it) }
        append('}')
    }

    companion object {
        val KEYS: Set<String> = setOf(
            "key", "label", "control", "locators", "options", "blockIndex",
            "required", "inputType", "inputName", "placeholder", "maxLen",
        )

        fun fromJson(o: JSONObject): FormField? {
            val key = o.optString("key")
            if (!TeachParam.NAME_RE.matches(key)) return null
            val control = FormControl.fromWire(o.optString("control")) ?: return null
            val locs = mutableListOf<TeachLocator>()
            val arr = o.optJSONArray("locators") ?: return null
            for (i in 0 until arr.length()) {
                val loc = arr.optJSONObject(i) ?: continue
                TeachLocator.fromJson(loc)?.let { locs += it }
            }
            if (locs.isEmpty()) return null
            return FormField(
                key = key,
                label = o.optString("label").ifBlank { key },
                control = control,
                locators = locs.sortedBy { it.tier },
                options = run {
                    val oa = o.optJSONArray("options") ?: JSONArray()
                    (0 until oa.length()).mapNotNull { i -> oa.optJSONObject(i)?.let(TeachOption::fromJson) }
                },
                blockIndex = o.optInt("blockIndex", 0),
                required = o.optBoolean("required", true),
                inputType = o.optString("inputType").takeIf { it.isNotEmpty() },
                inputName = o.optString("inputName").takeIf { it.isNotEmpty() },
                placeholder = o.optString("placeholder").takeIf { it.isNotEmpty() },
                maxLen = o.optInt("maxLen", 0).takeIf { it > 0 },
            )
        }
    }
}

/**
 * 重复块的一段（世界书条目这类「＋一条」的循环体）。
 *
 * **只支持固定条数**：段数在录一遍那一刻就定死（[FormTemplate.blocks] 的长度），
 * 动态增删条目不在 v1——条数不对就整张卡拒填，不猜用户想加几条。
 */
data class FormBlock(val index: Int, val label: String, val fields: List<FormField>) {
    fun canonical(): String = buildString {
        append("{\"i\":").append(index)
        append(",\"label\":\"").append(teachJsonEsc(label)).append('"')
        append(",\"fields\":[").append(fields.joinToString(",") { it.canonical() }).append("]}")
    }

    companion object {
        fun fromJson(o: JSONObject): FormBlock? {
            val i = o.optInt("i", 0)
            if (i <= 0) return null
            val arr = o.optJSONArray("fields") ?: return null
            val fs = mutableListOf<FormField>()
            for (k in 0 until arr.length()) {
                val f = arr.optJSONObject(k) ?: continue
                FormField.fromJson(f)?.let { fs += it }
            }
            if (fs.isEmpty()) return null
            return FormBlock(i, o.optString("label").ifBlank { "第 $i 段" }, fs)
        }
    }
}

/** 模板里声明的一个参数（复用 200 的形状口径：形状决定怎么校验，不是猜出来的）。 */
data class FormParamDecl(val name: String, val label: String, val shape: TeachParamShape) {
    fun canonical(): String = "{\"name\":\"" + teachJsonEsc(name) + "\",\"label\":\"" +
        teachJsonEsc(label) + "\",\"shape\":\"" + shape.wire + "\"}"

    fun describe(): String = "$name（$label · ${shape.wire}）"

    companion object {
        val KEYS: Set<String> = setOf("name", "label", "shape")

        fun fromJson(o: JSONObject): FormParamDecl? {
            val n = o.optString("name")
            if (!TeachParam.NAME_RE.matches(n)) return null
            return FormParamDecl(
                name = n,
                label = o.optString("label").ifBlank { n },
                shape = TeachParamShape.fromWire(o.optString("shape")) ?: TeachParamShape.TEXT,
            )
        }
    }
}

/**
 * 一张表模板（录一遍的产物）。**模板里不含任何样例值**——只有字段、定位器、选项表，
 * 值一律在 [FormCard] 里（同 200 的「字面量必须从步骤里拔掉」口径：模板本身可入库、可复用人传人）。
 */
data class FormTemplate(
    val name: String,
    val startUrl: String,
    val allowedDomains: List<String>,
    val fields: List<FormField>,
    val blocks: List<FormBlock> = emptyList(),
    val params: List<FormParamDecl> = emptyList(),
    val createdAtMs: Long = 0L,
) {
    /** 全部叶子字段（主体字段 + 重复块里的字段，按段序展开）。 */
    val allFields: List<FormField>
        get() = fields + blocks.sortedBy { it.index }.flatMap { it.fields }

    fun fieldOf(key: String): FormField? = allFields.firstOrNull { it.key == key }

    fun describe(): String = buildString {
        appendLine("表模板：$name（${fields.size} 个字段" +
            (if (blocks.isEmpty()) "" else " ＋ ${blocks.size} 段重复块") + "）")
        fields.forEach { appendLine("· " + it.describe()) }
        blocks.sortedBy { it.index }.forEach { b ->
            appendLine("· 第 ${b.index} 段 ${b.label}")
            b.fields.forEach { appendLine("  - " + it.describe()) }
        }
    }

    fun canonical(): String = buildString {
        append("{\"schema\":\"").append(SCHEMA).append('"')
        append(",\"name\":\"").append(teachJsonEsc(name)).append('"')
        append(",\"createdAt\":").append(createdAtMs)
        append(",\"startUrl\":\"").append(teachJsonEsc(startUrl)).append('"')
        append(",\"allowedDomains\":[").append(allowedDomains.joinToString(",") { "\"" + teachJsonEsc(it) + "\"" }).append(']')
        append(",\"fields\":[").append(fields.joinToString(",") { it.canonical() }).append(']')
        if (blocks.isNotEmpty()) {
            append(",\"blocks\":[").append(blocks.sortedBy { it.index }.joinToString(",") { it.canonical() }).append(']')
        }
        if (params.isNotEmpty()) {
            append(",\"params\":[").append(params.joinToString(",") { it.canonical() }).append(']')
        }
        append('}')
    }

    companion object {
        const val SCHEMA = "zl-form/1"

        val TOP_KEYS: Set<String> = setOf(
            "schema", "name", "createdAt", "startUrl", "allowedDomains", "fields", "blocks", "params",
        )

        fun fromJson(text: String, strict: Boolean = false): FormTemplate? = runCatching {
            val o = JSONObject(text)
            if (o.optString("schema") != SCHEMA) return@runCatching null
            if (strict && o.keys().asSequence().any { it !in TOP_KEYS }) return@runCatching null
            val fields = readFields(o.optJSONArray("fields"), strict) ?: return@runCatching null
            val blocks = run {
                val arr = o.optJSONArray("blocks") ?: JSONArray()
                val out = mutableListOf<FormBlock>()
                for (i in 0 until arr.length()) {
                    val b = arr.optJSONObject(i) ?: continue
                    FormBlock.fromJson(b)?.let { out += it }
                }
                out
            }
            if (fields.isEmpty() && blocks.isEmpty()) return@runCatching null
            val keys = (fields + blocks.flatMap { it.fields }).map { it.key }
            if (keys.toSet().size != keys.size) return@runCatching null
            FormTemplate(
                name = o.optString("name").ifBlank { return@runCatching null },
                startUrl = o.optString("startUrl"),
                allowedDomains = readStrings(o.optJSONArray("allowedDomains")),
                fields = fields,
                blocks = blocks,
                params = run {
                    val arr = o.optJSONArray("params") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(FormParamDecl::fromJson) }
                },
                createdAtMs = o.optLong("createdAt", 0L),
            )
        }.getOrNull()

        private fun readFields(arr: JSONArray?, strict: Boolean): List<FormField>? {
            if (arr == null) return emptyList()
            val out = mutableListOf<FormField>()
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                if (strict && f.keys().asSequence().any { it !in FormField.KEYS }) return null
                out += FormField.fromJson(f) ?: return null
            }
            return out
        }

        private fun readStrings(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        }
    }
}

// ── ② 扫描回来的候选控件（页面语义面；分类判据在 Kotlin）────────────────────

/** 一张卡的值（一条要填的记录）。键＝[FormField.key]，值＝要填的原文。 */
data class FormCard(val name: String, val values: Map<String, String>) {
    fun valueOf(key: String): String? = values[key]
}

data class FormCandidate(
    val tag: String,
    val inputType: String? = null,
    val contentEditable: Boolean = false,
    val elemId: String? = null,
    val testid: String? = null,
    val ariaLabel: String? = null,
    val inputName: String? = null,
    val placeholder: String? = null,
    val labelText: String? = null,
    val formLabel: String? = null,
    /**
     * `autocomplete` 属性原文（第二百二十四批补）。
     *
     * 为什么必须有：`autocomplete=\"one-time-code\"` 的验证码框，`type` 常是 `text`、
     * `name` 也可能叫 `code`/`vcode`——只看 type/name 会把它当普通字段填进去。
     * 这一列由 SCAN 脚本原样带回，判定仍走 [TeachSafety.refuseTypeReason]（同一份词表）。
     */
    val autocomplete: String? = null,
    val role: String? = null,
    val text: String? = null,
    val classes: List<String> = emptyList(),
    val xpath: String? = null,
    val options: List<TeachOption> = emptyList(),
    val checked: Boolean = false,
    val disabled: Boolean = false,
    val readOnly: Boolean = false,
    val visible: Boolean = true,
)

/** 扫描结果：能填的、以及**被排除的**（排除也要报，别让人以为「页面就这几个字段」）。 */
data class FormScanResult(
    val fields: List<FormField>,
    val blocks: List<FormBlock>,
    val excluded: List<FormExcluded>,
) {
    val total: Int get() = fields.size + blocks.sumOf { it.fields.size }
}

data class FormExcluded(val label: String, val reason: String)

// ── ③ 端口（生产＝玄览 execJs；测试＝内存假 DOM）──────────────────────────────

/** 探针答复：动手**之前**先问「你是什么」。 */
data class FormProbe(
    val found: Boolean,
    val hitCount: Int,
    val tag: String = "",
    val inputType: String? = null,
    val contentEditable: Boolean = false,
    val inputName: String? = null,
    val elemId: String? = null,
    val placeholder: String? = null,
    val text: String? = null,
    /** `autocomplete` 实测值（第二百二十四批）：`one-time-code` 一律拒填，判据同 [TeachSafety]。 */
    val autocomplete: String? = null,
    val reason: String? = null,
)

/** 回读答复（只装「读回来什么」，不装「对不对」——对不对是 [TeachFormRules.check] 的事）。 */
data class FormReadback(
    val text: String,
    val value: String = "",
    val checked: Boolean = false,
)

/** 一次表单查询的答复。 */
sealed class FormReply {
    data class Probed(val probe: FormProbe) : FormReply()
    data class Filled(val ok: Boolean, val reason: String?) : FormReply()
    data class Read(val read: FormReadback) : FormReply()
    data class Failed(val reason: String) : FormReply()
}

/**
 * 表单端口。**只有四个动作**：扫、探、填、读——没有 click、没有 keyPress、没有 evaluate。
 * 「不许点创建」在端口签名上就是写不出来的（负例 1 的结构面）。
 */
interface FormDom {
    /** 扫一遍页面上的可填控件（null ＝ 取不到页面）。 */
    fun scan(): List<FormCandidate>?

    /** 按定位链问「这是不是它、它是什么」。 */
    fun probe(locators: List<TeachLocator>): FormReply

    /** 填（[optionValue] 只对下拉/单选有意义）。 */
    fun fill(locators: List<TeachLocator>, control: FormControl, value: String, optionValue: String?): FormReply

    /** 把输入框里的值读回来（不信任「我填了」）。 */
    fun read(locators: List<TeachLocator>, control: FormControl): FormReply
}

// ── ④ 判据层（纯函数：分类／校验／预检／参数化／去重／卡片）─────────────────

/** 一次回读校验的结论。 */
data class FieldCheck(val ok: Boolean, val actual: String, val reason: String? = null)

/** 一个字段的填表结局（[ok] 只由回读校验决定）。 */
data class FieldOutcome(
    val key: String,
    val label: String,
    val control: FormControl,
    val blockIndex: Int,
    val ok: Boolean,
    val expected: String,
    val actual: String,
    val reason: String? = null,
) {
    /** 证据行（报告与卡面共用同一句）。 */
    fun line(): String = buildString {
        append(if (ok) "✓" else "✗").append(' ')
        if (blockIndex > 0) append("第 ").append(blockIndex).append(" 段 · ")
        append(label.ifBlank { key })
        append(" = ").append(TeachFormRules.preview(expected))
        if (!ok) {
            append(" —— ").append(reason ?: "回读不一致")
            append("（读到：").append(TeachFormRules.preview(actual)).append("）")
        }
    }
}

/** 一张卡的填表结局。 */
data class CardOutcome(
    val cardName: String,
    val ok: Boolean,
    val fields: List<FieldOutcome>,
    val missingSegments: List<Int> = emptyList(),
    val stopReason: String? = null,
) {
    val filledOk: Int get() = fields.count { it.ok }

    /** 没填上的字段（卡面标红那几行）。 */
    val failures: List<FieldOutcome> get() = fields.filter { !it.ok }
}

internal object TeachFormRules {

    // ── 分类：这个控件该不该填、算什么类别 ──────────────────────────────────

    /**
     * 提交/创建/保存/发布类控件的词表（**比 196 的拒点名单更宽**：还包括「创建/保存/发布/新增/上架」）。
     *
     * 英文面用词边界（`postal_code` 这种不许被 `post` 误伤）；中文面不用——
     * 中文没有词边界，「创建人备注」这种命中就命中，误拒方向安全。
     */
    val SUBMIT_LIKE: Regex = Regex(
        "提交|确认|保存|创建|发布|新建|新增|上架|投稿|发送|" +
            "(?i)(?<![a-z])(submit|confirm|save|create|publish|post|deploy|upsert|store)(?![a-z])",
    )

    /** 分类结论。 */
    sealed class Classified {
        data class Accepted(val control: FormControl) : Classified()
        data class Excluded(val reason: String) : Classified()
    }

    /**
     * 控件分类（纯判据，顺序即优先级）：
     * 看不见 → 只读/禁用 → 提交类 → 凭证类 → 不支持的类型 → 四类叶子。
     *
     * **提交类先于凭证类**：一个「保存」按钮同时长得像密码框时，报的应该是「提交类不点」，
     * 而不是「疑似凭证」——先说最要命的那条。
     */
    fun classify(c: FormCandidate): Classified {
        val label = c.labelText ?: c.ariaLabel ?: c.inputName ?: c.placeholder ?: c.tag
        val type = c.inputType?.lowercase().orEmpty()
        val submitFace = listOfNotNull(c.text, c.labelText, c.ariaLabel, c.testid, c.inputName, c.elemId)
            .firstOrNull { SUBMIT_LIKE.containsMatchIn(it) }
        excludeReason(c, type, submitFace, label)?.let { return Classified.Excluded(it) }
        val control = acceptedControl(c, type)
        return if (control == null) Classified.Excluded("不是可填控件") else Classified.Accepted(control)
    }

    /** 排除判据（顺序即优先级，第一条命中就是答案）；null ＝ 没被排除。 */
    private fun excludeReason(c: FormCandidate, type: String, submitFace: String?, label: String): String? {
        if (!c.visible) return "看不见（宽高为 0）"
        if (c.disabled) return "禁用状态"
        if (c.readOnly) return "只读"
        if (c.tag.uppercase() == "BUTTON" || c.role == "button" || type in setOf("submit", "button", "reset", "image")) {
            return "提交/创建/保存类控件（${submitFace ?: label}）——抱朴不点，也不填"
        }
        if (submitFace != null) {
            return "字段名像是提交/创建（$submitFace）——一律不填"
        }
        // 第二百二十四批：autocomplete 也进拒填判据（`one-time-code` 的验证码框 type 常是 text）
        TeachSafety.refuseTypeReason(c.inputType, c.inputName, c.elemId, c.placeholder, c.autocomplete)?.let {
            return it
        }
        if (type == "hidden") return "隐藏域（type=hidden），没有可填的值"
        if (type in setOf("file", "checkbox", "color", "range", "date", "time", "month", "week", "datetime-local")) {
            return "$type 控件不在 v1 支持范围（单行/多行/下拉/单选/固定条数重复块）"
        }
        return null
    }

    /** 准入映射：认得的控件 → 四类叶子之一；null ＝ 不是可填控件。 */
    private fun acceptedControl(c: FormCandidate, type: String): FormControl? = when {
        c.tag.uppercase() == "SELECT" -> FormControl.SELECT
        type == "radio" -> FormControl.RADIO
        c.tag.uppercase() == "TEXTAREA" -> FormControl.RICH_TEXT
        c.contentEditable -> FormControl.RICH_TEXT
        c.tag.uppercase() == "INPUT" && type in setOf("text", "search", "email", "tel", "url", "number", "") ->
            FormControl.SINGLE_LINE
        else -> null
    }

    /**
     * 拒填判据（动手前对**探针实测**再过一遍，不信模板自述）：
     * 探针说是按钮/提交类 ⇒ 拒；探针说是密码/验证码 ⇒ 拒。
     * 返回 null ＝ 放行。
     */
    fun refuseSubmitLike(probe: FormProbe): String? {
        val tag = probe.tag.uppercase()
        val type = probe.inputType?.lowercase().orEmpty()
        if (tag == "BUTTON" || tag == "A" || type in setOf("submit", "button", "reset", "image")) {
            return "拒填：目标是提交/创建类控件 <${probe.tag}>（抱朴只填输入框，不点也不填这类）"
        }
        val face = listOfNotNull(probe.text, probe.inputName, probe.elemId, probe.placeholder)
            .firstOrNull { SUBMIT_LIKE.containsMatchIn(it) }
        if (face != null) return "拒填：目标像提交/创建类（$face）——一律不填"
        return null
    }

    // ── 扫描 → 模板 ─────────────────────────────────────────────────────────

    /** `worldbook[0][title]` → (worldbook, 1, title)；不是这个形状 ⇒ null。 */
    fun parseBlockName(inputName: String?): Triple<String, Int, String?>? {
        if (inputName.isNullOrBlank()) return null
        val m = Regex("^([A-Za-z][A-Za-z0-9_]*)\\[(\\d+)\\](?:\\[([A-Za-z0-9_]+)\\])?$").find(inputName.trim())
            ?: return null
        val base = m.groupValues[1]
        val idx = m.groupValues[2].toIntOrNull() ?: return null
        val sub = m.groupValues[3].takeIf { it.isNotBlank() }
        return Triple(base, idx + 1, sub)
    }

    /** 字段键：英文/数字/下划线小写；空 ⇒ `fieldN`。 */
    fun keyOf(raw: String?, fallbackIndex: Int): String {
        val cleaned = raw.orEmpty().lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
        val trimmed = if (cleaned.length > 28) cleaned.take(28).trim('_') else cleaned
        val head = if (trimmed.isEmpty() || !trimmed.first().isLetter()) "f_$trimmed" else trimmed
        val safe = head.trim('_')
        return if (TeachParam.NAME_RE.matches(safe)) safe else "field$fallbackIndex"
    }

    /** 同一模板内键唯一：撞了就 `-2`/`-3`（参数名规矩：连字符不合法 ⇒ 用 `_2`）。 */
    fun uniqueKey(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    /**
     * 扫描结果 → 字段表＋重复块表。判据全在这里，端口只负责把页面事实搬回来。
     *
     * 重复块识别：控件的 `name` 形如 `worldbook[0][title]` ⇒ 落到第 1 段；段内键 = `worldbook1_title`。
     */
    fun buildFields(candidates: List<FormCandidate>): FormScanResult {
        val acc = FieldAcc()
        candidates.forEachIndexed { i, c ->
            when (val v = classify(c)) {
                is Classified.Excluded -> acc.excluded += FormExcluded(
                    c.labelText ?: c.ariaLabel ?: c.inputName ?: c.inputType ?: c.tag,
                    v.reason,
                )
                is Classified.Accepted -> acceptCandidate(c, i, v.control, acc)
            }
        }
        val blocks = acc.blockFields.toSortedMap().map { (idx, fs) ->
            FormBlock(idx, acc.blockLabels[idx] ?: "第 $idx 段", fs)
        }
        return FormScanResult(acc.leaf, blocks, acc.excluded)
    }

    /** 扫描过程中的五本账：叶子字段／重复块字段／块名／排除项／已占用键。 */
    private class FieldAcc {
        val leaf = mutableListOf<FormField>()
        val blockFields = mutableMapOf<Int, MutableList<FormField>>()
        val blockLabels = mutableMapOf<Int, String>()
        val excluded = mutableListOf<FormExcluded>()
        val taken = mutableSetOf<String>()
        // 单选是「一组选一个」：同 name 的多个 radio 只留第一个字段（选项表在它身上）
        val radioGroups = mutableSetOf<String>()
    }

    /** 一个被准入的控件：radio 去重 → 生成键 → 建字段 → 落到重复块或叶子。 */
    private fun acceptCandidate(c: FormCandidate, i: Int, control: FormControl, acc: FieldAcc) {
        if (control == FormControl.RADIO) {
            val group = c.inputName ?: c.elemId ?: c.labelText
            if (group != null && !acc.radioGroups.add(group)) return
        }
        val block = parseBlockName(c.inputName)
        val key = keyFor(c, i, block, acc.taken)
        acc.taken += key
        val field = fieldOf(c, key, control, block?.second ?: 0)
        if (block != null) {
            acc.blockFields.getOrPut(block.second) { mutableListOf() } += field
            acc.blockLabels.putIfAbsent(block.second, block.first)
        } else {
            acc.leaf += field
        }
    }

    /** 字段键：块内键 = `base{段}_sub`，叶子 = 控件名；同模板内撞了就 `_2`/`_3`。 */
    private fun keyFor(c: FormCandidate, i: Int, block: Triple<String, Int, String?>?, taken: Set<String>): String {
        if (block == null) return uniqueKey(keyOf(c.inputName ?: c.labelText, i + 1), taken)
        val base = keyOf(block.first, i + 1)
        val sub = block.third
        return uniqueKey(
            if (sub == null) "$base${block.second}" else "${base}${block.second}_${keyOf(sub, i + 1)}",
            taken,
        )
    }

    /** 控件事实 → 字段（定位子由 [TeachLearner.buildLocators] 从同一份事实生成，不另存一份）。 */
    private fun fieldOf(c: FormCandidate, key: String, control: FormControl, blockIndex: Int): FormField {
        val info = TeachElementInfo(
            tag = c.tag,
            role = c.role,
            name = c.labelText ?: c.ariaLabel,
            text = c.text,
            ariaLabel = c.ariaLabel,
            testid = c.testid,
            elemId = c.elemId,
            inputName = c.inputName,
            placeholder = c.placeholder,
            inputType = c.inputType,
            formLabel = c.formLabel,
            classes = c.classes,
            xpath = c.xpath,
        )
        return FormField(
            key = key,
            label = c.labelText ?: c.ariaLabel ?: c.inputName ?: key,
            control = control,
            locators = TeachLearner.buildLocators(info),
            options = c.options,
            blockIndex = blockIndex,
            required = true,
            inputType = c.inputType,
            inputName = c.inputName,
            placeholder = c.placeholder,
        )
    }

    /** 从字段表推导参数声明（形状：有选项 ⇒ ENUM；number 型 ⇒ NUMBER；否则 TEXT）。 */
    fun deriveParams(scan: FormScanResult): List<FormParamDecl> =
        scan.fields.map { paramOf(it) } + scan.blocks.flatMap { b -> b.fields.map { paramOf(it) } }

    private fun paramOf(f: FormField): FormParamDecl = FormParamDecl(
        name = f.key,
        label = f.label.ifBlank { f.key },
        shape = when {
            f.options.isNotEmpty() -> TeachParamShape.ENUM
            f.inputType?.lowercase() == "number" -> TeachParamShape.NUMBER
            else -> TeachParamShape.TEXT
        },
    )

    /** 扫描结果 → 模板（[name]/[startUrl]/[allowedDomains] 由调用方给：页面事实，不猜）。 */
    fun templateFrom(
        scan: FormScanResult,
        name: String,
        startUrl: String,
        allowedDomains: List<String>,
        nowMs: Long,
    ): FormTemplate = FormTemplate(
        name = name,
        startUrl = startUrl,
        allowedDomains = allowedDomains,
        fields = scan.fields,
        blocks = scan.blocks,
        params = deriveParams(scan),
        createdAtMs = nowMs,
    )

    // ── 预检：这张卡能不能填（填之前就把缺的段说清）──────────────────────────

    sealed class Precheck {
        data class Ok(val ops: List<FormOp>) : Precheck()
        data class Rejected(val reasons: List<String>) : Precheck()
    }

    data class FormOp(val field: FormField, val value: String, val optionValue: String?)

    /**
     * 预检（**不动页面**）。四类拒：
     * 1. 卡里有多余字段（模板没有）——防手滑把别的卡的值糊进来；
     * 2. 必填字段缺值；
     * 3. 重复块某段缺字段（负例 4 的前半：先查段，再动手）；
     * 4. 参数形状不符（日期/数字/选项表）。
     */
    fun precheck(template: FormTemplate, card: FormCard): Precheck {
        val reasons = mutableListOf<String>()
        val known = template.allFields.associateBy { it.key }
        val extra = card.values.keys.filter { it !in known }
        if (extra.isNotEmpty()) {
            reasons += "卡里有模板没有的字段：${extra.joinToString("、")}（是不是拿错了卡？）"
        }
        val shapes = template.params.associateBy { it.name }
        val ops = leafOps(template.fields, card.values, shapes, reasons)
        val blockResult = blockOps(template.blocks, card.values, shapes)
        ops += blockResult.first
        reasons += blockResult.second
        return if (reasons.isEmpty()) Precheck.Ok(ops) else Precheck.Rejected(reasons)
    }

    /** 叶子字段 → 填表指令；缺必填 / 形状不合规的写进 [reasons]。 */
    private fun leafOps(
        fields: List<FormField>,
        values: Map<String, String>,
        shapes: Map<String, FormParamDecl>,
        reasons: MutableList<String>,
    ): MutableList<FormOp> {
        val ops = mutableListOf<FormOp>()
        fields.forEach { f ->
            val v = values[f.key].orEmpty()
            if (v.isBlank()) {
                if (f.required) reasons += "缺「${f.label}」（${f.key}）"
                return@forEach
            }
            shapeProblem(f, v, shapes)?.let { reasons += it; return@forEach }
            resolveOp(f, v)?.let { ops += it } ?: reasons.add(shapeReason(f, v))
        }
        return ops
    }

    /** 重复块 → 填表指令；某段没填全就整段不进 ops，改成一句原因（负例 4 的前半：先查段，再动手）。 */
    private fun blockOps(
        blocks: List<FormBlock>,
        values: Map<String, String>,
        shapes: Map<String, FormParamDecl>,
    ): Pair<MutableList<FormOp>, List<String>> {
        val ops = mutableListOf<FormOp>()
        val reasons = mutableListOf<String>()
        blocks.sortedBy { it.index }.forEach { b ->
            val missingHere = mutableListOf<String>()
            val here = mutableListOf<FormOp>()
            b.fields.forEach { f ->
                val v = values[f.key].orEmpty()
                if (v.isBlank()) {
                    if (f.required) missingHere += f.label
                    return@forEach
                }
                shapeProblem(f, v, shapes)?.let { missingHere += f.label; return@forEach }
                resolveOp(f, v)?.let { here += it } ?: missingHere.add(shapeReason(f, v))
            }
            if (missingHere.isNotEmpty()) {
                reasons += "第 ${b.index} 段（${b.label}）没填全：${missingHere.joinToString("、")}"
            } else {
                ops += here
            }
        }
        return ops to reasons
    }

    private fun shapeReason(f: FormField, v: String): String = "「${f.label}」的值不合要求（$v）"

    /** 声明的形状校验（日期/数字/编码/选项表）：不合要求就说清要什么形状。 */
    private fun shapeProblem(f: FormField, v: String, shapes: Map<String, FormParamDecl>): String? {
        val decl = shapes[f.key] ?: return null
        if (shapeOk(decl.shape, v, f.options)) return null
        return "「${f.label}」的值形状不对（要求 ${decl.shape.wire}）：$v"
    }

    /** 一个字段 + 一个值 → 一条填表指令（下拉/单选把中文文案映射回页面要的 value）。 */
    fun resolveOp(f: FormField, raw: String): FormOp? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        return when (f.control) {
            // 下拉必须有选项表：选不中的值不能就这么塞进去（页面会静默落回第一项）
            FormControl.SELECT -> {
                val opt = matchOption(f.options, v) ?: return null
                FormOp(f, v, opt.value)
            }
            // 单选：有词表就按词表映射；没词表（录制端没给组内 value）就按原值试，
            // 回读校验会拦住「选不中却报成功」。
            FormControl.RADIO -> FormOp(f, v, matchOption(f.options, v)?.value ?: v)
            FormControl.RICH_TEXT -> FormOp(f, normalizeMultiline(raw), null)
            FormControl.SINGLE_LINE -> FormOp(f, v, null)
        }
    }

    /** 选项匹配：先按 value 全等，再按文案全等，最后按文案包含（唯一命中才算）。 */
    fun matchOption(options: List<TeachOption>, text: String): TeachOption? {
        if (options.isEmpty()) return null
        return options.firstOrNull { it.value == text }
            ?: options.firstOrNull { it.label == text }
            ?: options.filter { it.label.contains(text) || text.contains(it.label) }.takeIf { it.size == 1 }?.first()
    }

    // ── 回读校验（负例 3/5 的靶子）──────────────────────────────────────────

    /**
     * 回读校验。**只认回读到的值**，填动作返回「成功」不算数。
     *
     * 富文本多比一项「行数」：纯文本塞进 `contenteditable` 会把换行吃掉，
     * 文字可能全等而结构已丢——这正是负例 3。
     */
    fun check(field: FormField, expected: String, optionValue: String?, read: FormReadback): FieldCheck =
        when (field.control) {
            FormControl.SINGLE_LINE -> checkSingleLine(expected, read.text)
            FormControl.RICH_TEXT -> checkRichText(expected, read.text)
            FormControl.SELECT -> checkSelect(expected, optionValue, read)
            FormControl.RADIO -> checkRadio(expected, optionValue, read)
        }

    private fun checkSingleLine(expected: String, actual: String): FieldCheck =
        if (normalize(actual) == normalize(expected)) {
            FieldCheck(true, actual)
        } else {
            FieldCheck(false, actual, "回读不一致")
        }

    /**
     * 富文本多比一项「行数」：纯文本塞进 `contenteditable` 会把换行吃掉，
     * 文字可能全等而结构已丢——这正是负例 3。
     */
    private fun checkRichText(expected: String, actual: String): FieldCheck {
        val want = normalizeMultiline(expected)
        val got = normalizeMultiline(actual)
        val wantLines = nonEmptyLines(want)
        val gotLines = nonEmptyLines(got)
        return when {
            got == want -> if (wantLines > 1 && gotLines < wantLines) {
                FieldCheck(
                    false, actual,
                    "富文本被塞成纯文本：要 $wantLines 行、回读只有 $gotLines 行（换行/分段丢了）",
                )
            } else {
                FieldCheck(true, actual)
            }
            // 文字一个不少、只是空白变了 ⇒ 典型「纯文本塞进富文本编辑器」：结构丢了
            squeeze(got) == squeeze(want) -> FieldCheck(
                false, actual,
                "富文本被塞成纯文本：字都在，换行/分段没了（要 $wantLines 行）",
            )
            else -> FieldCheck(false, actual, "回读不一致")
        }
    }

    private fun checkSelect(expected: String, optionValue: String?, read: FormReadback): FieldCheck {
        val want = optionValue ?: expected
        // 读回面：value 字段优先；有些站点把选中项放 text
        val got = read.value.ifBlank { read.text }
        return if (got == want || normalize(got) == normalize(want)) {
            FieldCheck(true, got)
        } else {
            FieldCheck(false, got, "下拉没选中「$want」")
        }
    }

    private fun checkRadio(expected: String, optionValue: String?, read: FormReadback): FieldCheck {
        val want = optionValue ?: expected
        val got = read.value
        return when {
            !read.checked -> FieldCheck(false, got, "单选没被选中")
            got == want || normalize(got) == normalize(want) -> FieldCheck(true, got)
            else -> FieldCheck(false, got, "选中的是「$got」不是「$want」")
        }
    }

    // ── 文本口径（全仓唯一：空白只在这里归一）──────────────────────────────

    /** 单行口径：CRLF→LF、去行尾空白、去首尾空白。 */
    fun normalize(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n').trim()

    /** 多行口径：按行去行尾空白、去首尾空行，行内容不动（分段要保住）。 */
    fun normalizeMultiline(s: String): String =
        s.replace("\r\n", "\n").replace('\r', '\n')
            .lines().joinToString("\n") { it.trimEnd() }
            .trim('\n')

    fun nonEmptyLines(s: String): Int = s.lines().count { it.isNotBlank() }

    /** 去掉全部空白的「骨架」：用来判「字都在、结构没了」这种降级。 */
    fun squeeze(s: String): String = s.filterNot { it.isWhitespace() }

    /** 卡面预览：单行化 + 截断（多行值在证据里也只显第一行，全文进「复制全文」）。 */
    fun preview(s: String, max: Int = 24): String {
        val one = s.replace("\r\n", "\n").replace('\n', '⏎').trim()
        return if (one.length <= max) one else one.take(max) + "…"
    }

    // ── 去重（负例 6）─────────────────────────────────────────────────────

    /** 重名就 `-2`/`-3`（**不覆盖**：前一张的截图可能正被读图管线用着）。 */
    fun uniqueName(base: String, taken: Set<String>): String {
        val b = base.trim().ifBlank { "未命名" }
        if (b !in taken) return b
        var n = 2
        while ("$b-$n" in taken) n++
        return "$b-$n"
    }

    // ── 待发布卡与「复制全文」──────────────────────────────────────────────

    /**
     * 待发布卡的**全文**（用户手动粘到平台上的那一份）。
     *
     * 顺序＝模板字段序（主体在前、重复块按段），值原样（多行值不压行）——
     * 这是「复制全文」按钮唯一的内容来源，与卡面行同一份数据。
     */
    fun copyText(template: FormTemplate, card: FormCard): String = buildString {
        appendLine("【${card.name}】")
        template.fields.forEach { f ->
            appendLine("${f.label.ifBlank { f.key }}：${card.values[f.key].orEmpty()}")
        }
        template.blocks.sortedBy { it.index }.forEach { b ->
            appendLine("〔第 ${b.index} 段 · ${b.label}〕")
            b.fields.forEach { f ->
                appendLine("${f.label.ifBlank { f.key }}：${card.values[f.key].orEmpty()}")
            }
        }
    }.trim()

    /** 出卡：最终值＋截图＋两个动作（复制全文 / 打开页面）。 */
    fun publishCard(
        template: FormTemplate,
        card: FormCard,
        outcome: CardOutcome,
        shotPath: String?,
    ): PublishCard {
        val notes = mutableListOf<String>()
        if (!outcome.ok) {
            notes += outcome.stopReason ?: "有 ${outcome.failures.size} 处没填对，先看红行"
        }
        if (outcome.missingSegments.isNotEmpty()) {
            notes += "缺段：${outcome.missingSegments.joinToString("、") { "第 $it 段" }}"
        }
        if (shotPath == null) notes += NO_SHOT_NOTE
        return PublishCard(
            title = "待发布 · ${card.name}",
            rows = rowsOf(outcome.fields),
            shotPath = shotPath,
            openUrl = template.startUrl,
            copyText = copyText(template, card),
            notes = notes,
            ok = outcome.ok,
        )
    }

    /**
     * 出卡（**示教回放路径**，第二百零一批 ②）：这一路没有模板——技能是一步一步录下来的，
     * 行与全文直接从「逐字段的回读结局」组。
     *
     * 为什么不走 [publishCard]：它要的 `FormTemplate`/`FormCard` 在这里不存在（回放没有
     * 「一张卡」的概念，值来自技能步骤或 200 的参数）。行映射与截图缺位的话说同一句话，
     * 都收在这里，两边不分叉。
     */
    fun publishCardFromSteps(
        skillName: String,
        outcomes: List<FieldOutcome>,
        openUrl: String,
        shotPath: String?,
    ): PublishCard {
        val bad = outcomes.filter { !it.ok }
        val notes = mutableListOf<String>()
        if (bad.isNotEmpty()) notes += "有 ${bad.size} 处回读不一致，先看红行"
        if (shotPath == null) notes += NO_SHOT_NOTE
        return PublishCard(
            title = "待发布 · $skillName",
            rows = rowsOf(outcomes),
            shotPath = shotPath,
            openUrl = openUrl,
            copyText = outcomes.joinToString("\n") { "${it.label.ifBlank { it.key }}：${it.expected}" }.trim(),
            notes = notes,
            ok = outcomes.isNotEmpty() && bad.isEmpty(),
        )
    }

    /** 卡面行：模板路径与回放路径共用这一份映射（别在两处各写一遍）。 */
    private fun rowsOf(fields: List<FieldOutcome>): List<PublishRow> =
        fields.map { PublishRow(it.label.ifBlank { it.key }, it.expected, it.ok, it.reason) }

    /** 没截到图时卡上那句（两条出卡路径同一句话）。 */
    private const val NO_SHOT_NOTE: String = "这次没取到截图（页面可能没渲染完）"

    // ── 批量输入：JSON / CSV → 卡（复用 200 的参数口径）─────────────────────

    /**
     * JSON：数组，每项 `{ "card": "名字", "values": {...} }`，也接受裸 `{...}`（名字取 `card`/`name`）。
     * 重复块可以直接给数组：`"worldbook": [{"title":"…","content":"…"}, …]` ⇒ 按段序展平成
     * `worldbook1_title` 这类键（[expandNested]）。
     */
    fun parseCardsJson(text: String, template: FormTemplate): List<FormCard> {
        val root = runCatching { JSONArray(text) }.getOrNull()
        val objs = when {
            root != null -> (0 until root.length()).mapNotNull { root.optJSONObject(it) }
            else -> listOfNotNull(runCatching { JSONObject(text) }.getOrNull())
        }
        return objs.mapIndexedNotNull { i, o ->
            val name = o.optString("card").ifBlank { "第 ${i + 1} 张" }
            val values = when {
                o.has("values") -> readMap(o.optJSONObject("values") ?: JSONObject())
                else -> readMap(o)
            }.toMutableMap()
            // 只摘掉卡名那一列：`name` 是合法的**字段键**（角色名），不许当卡名吃掉
            values.remove("card")
            expandNested(values, o, template)
            if (values.isEmpty()) null else FormCard(name, values)
        }
    }

    /** CSV：首行表头（字段键；`card` 列给卡名，可省），其余每行一张卡。 */
    fun parseCardsCsv(text: String, template: FormTemplate): List<FormCard> {
        val lines = text.replace("\r\n", "\n").split('\n').filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val header = splitCsvLine(lines.first())
        val nameCol = header.indexOfFirst { it == "card" }
        return lines.drop(1).mapIndexedNotNull { i, line ->
            val cells = splitCsvLine(line)
            val values = mutableMapOf<String, String>()
            header.forEachIndexed { c, key ->
                if (c == nameCol) return@forEachIndexed
                val v = cells.getOrElse(c) { "" }
                if (v.isNotBlank()) values[key] = v.replace("\\n", "\n")
            }
            if (values.isEmpty()) return@mapIndexedNotNull null
            val name = if (nameCol >= 0) cells.getOrElse(nameCol) { "" }.ifBlank { "第 ${i + 1} 张" } else "第 ${i + 1} 张"
            FormCard(name, values)
        }
    }

    private fun readMap(o: JSONObject): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (key in o.keys()) {
            val v = o.opt(key) ?: continue
            when (v) {
                is String -> if (v.isNotBlank()) out[key] = v
                is Number -> out[key] = v.toString()
                is Boolean -> out[key] = v.toString()
                else -> Unit
            }
        }
        return out
    }

    /** 把 `worldbook: [{...}]` 这类嵌套数组按段序展平成模板的字段键。 */
    private fun expandNested(values: MutableMap<String, String>, o: JSONObject, template: FormTemplate) {
        template.blocks.sortedBy { it.index }.forEach { b ->
            val baseName = b.fields.mapNotNull { parseBlockName(it.inputName)?.first }.firstOrNull()
            val arr = baseName?.let { o.optJSONArray(it) } ?: return@forEach
            for (k in b.fields.indices) {
                if (k >= arr.length()) break
                val seg = arr.optJSONObject(k) ?: continue
                b.fields[k].let { f ->
                    val sub = parseBlockName(f.inputName)?.third
                    val v = if (sub != null) seg.optString(sub) else seg.optString("value")
                    if (v.isNotBlank()) values[f.key] = v
                }
            }
        }
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    out += sb.toString()
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out.map { it.trim() }
    }

    /** 形状校验（[FormParamShape]）：只校验声明过的形状，不猜。 */
    fun shapeOk(shape: TeachParamShape, value: String, options: List<TeachOption>): Boolean = when (shape) {
        TeachParamShape.DATE -> Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value)
        TeachParamShape.NUMBER -> value.all { it.isDigit() } && value.isNotEmpty()
        TeachParamShape.CODE -> Regex("^[A-Za-z0-9\\-]{2,64}$").matches(value)
        TeachParamShape.ENUM -> options.isEmpty() || matchOption(options, value) != null
        TeachParamShape.TEXT -> value.isNotBlank()
    }
}

// ── ⑤ 填表器（一张卡一条字段地填；填成功不算数，回读一致才算）─────────────────

internal class FormFiller(private val dom: FormDom) {

    /**
     * 填一个字段。顺序铁律：
     * **空值 → 探针 → 提交类拒填 → 凭证拒填 → 长度 → 填 → 回读 → 校验**。
     * 任何一环不过 ⇒ 这个字段记失败并给原因，**绝不**因为「填动作返回 ok」就记成功。
     */
    fun fillField(op: TeachFormRules.FormOp): FieldOutcome {
        val f = op.field
        fun fail(reason: String, actual: String = ""): FieldOutcome =
            FieldOutcome(f.key, f.label, f.control, f.blockIndex, false, op.value, actual, reason)

        if (op.value.isBlank()) return fail("值是空的，不填")
        f.maxLen?.let { if (op.value.length > it) return fail("超过长度上限 $it（现在 ${op.value.length} 字）") }
        if (f.locators.isEmpty()) return fail("模板里没有这个字段的定位器")

        val probe = when (val r = dom.probe(f.locators)) {
            is FormReply.Probed -> r.probe
            is FormReply.Failed -> return fail(r.reason)
            else -> return fail("探针答复形状不对")
        }
        if (!probe.found) return fail(probe.reason ?: "定位不到这个字段（页面可能还没加载完）")
        // 单选是「一组命中多个」，其余控件必须唯一命中（多命中不猜，问人）
        if (probe.hitCount > 1 && f.control != FormControl.RADIO) {
            return fail("命中 ${probe.hitCount} 个元素，不猜——请人指定")
        }

        TeachFormRules.refuseSubmitLike(probe)?.let { return fail(it) }
        TeachSafety.refuseTypeReason(
            inputType = probe.inputType ?: f.inputType,
            inputName = probe.inputName ?: f.inputName,
            inputId = probe.elemId,
            placeholder = probe.placeholder ?: f.placeholder,
            // 第二百二十四批：autocomplete 走探针实测（`one-time-code` 的验证码框只在这个属性上露馅）
            autocomplete = probe.autocomplete,
        )?.let { return fail(it) }

        when (val r = dom.fill(f.locators, f.control, op.value, op.optionValue)) {
            is FormReply.Filled -> if (!r.ok) return fail(r.reason ?: "填动作失败")
            is FormReply.Failed -> return fail(r.reason)
            else -> return fail("填动作答复形状不对")
        }

        // ── 回读：不信任「我填了」──
        val read = when (val r = dom.read(f.locators, f.control)) {
            is FormReply.Read -> r.read
            is FormReply.Failed -> return fail("回读失败：${r.reason}")
            else -> return fail("回读答复形状不对")
        }
        val check = TeachFormRules.check(f, op.value, op.optionValue, read)
        return FieldOutcome(
            key = f.key,
            label = f.label,
            control = f.control,
            blockIndex = f.blockIndex,
            ok = check.ok,
            expected = op.value,
            actual = check.actual,
            reason = check.reason,
        )
    }

    /**
     * 填一张卡：预检（不动页面）→ 逐字段填＋回读 → 段完整性复查。
     * [CardOutcome.ok] ＝ 全部字段都过 **且** 每一段都填满了。
     */
    fun fillCard(template: FormTemplate, card: FormCard): CardOutcome {
        when (val p = TeachFormRules.precheck(template, card)) {
            is TeachFormRules.Precheck.Rejected -> return CardOutcome(
                cardName = card.name,
                ok = false,
                fields = emptyList(),
                stopReason = p.reasons.joinToString("；"),
            )
            is TeachFormRules.Precheck.Ok -> {
                val outs = p.ops.map { fillField(it) }
                val missingSegments = template.blocks.sortedBy { it.index }
                    .filter { b ->
                        b.fields.any { f ->
                            val o = outs.firstOrNull { it.key == f.key }
                            o == null || !o.ok || o.actual.isBlank()
                        }
                    }
                    .map { it.index }
                val ok = outs.isNotEmpty() && outs.all { it.ok } && missingSegments.isEmpty() &&
                    outs.size == template.allFields.count { card.values[it.key].orEmpty().isNotBlank() }
                return CardOutcome(card.name, ok, outs, missingSegments, if (ok) null else "有字段没填对")
            }
        }
    }
}

// ── ⑥ 证据（每张卡一份：字段回读 + 卡结局；目录不覆盖）───────────────────────

/** 每张卡的取证口（生产落 `<工作区>/产物/填表/<卡名>-<时间>/`）。 */
interface FormEvidenceSink {
    fun begin(cardName: String, stamp: String): String?
    fun field(dir: String?, outcome: FieldOutcome)
    fun finish(dir: String?, outcome: CardOutcome)

    /** 不落盘（测试/失败）。 */
    object None : FormEvidenceSink {
        override fun begin(cardName: String, stamp: String): String? = null
        override fun field(dir: String?, outcome: FieldOutcome) = Unit
        override fun finish(dir: String?, outcome: CardOutcome) = Unit
    }
}

internal class FileFormEvidence(private val root: File) : FormEvidenceSink {
    private val taken = mutableSetOf<String>()

    override fun begin(cardName: String, stamp: String): String? {
        val name = TeachFormRules.uniqueName(cardName, taken)
        taken += name
        return runCatching {
            var dir = File(root, "$name-$stamp")
            var n = 2
            while (dir.exists()) {
                dir = File(root, "$name-$stamp-$n")
                n++
            }
            dir.mkdirs()
            dir.absolutePath
        }.getOrElse { null }
    }

    override fun field(dir: String?, outcome: FieldOutcome) {
        val d = dir ?: return
        runCatching {
            File(d, "fields.txt").appendText(outcome.line() + "\n", Charsets.UTF_8)
        }
    }

    override fun finish(dir: String?, outcome: CardOutcome) {
        val d = dir ?: return
        runCatching {
            File(d, "card.json").writeText(
                "{\"card\":\"" + teachJsonEsc(outcome.cardName) + "\",\"ok\":" + outcome.ok +
                    ",\"fields\":" + outcome.fields.size + ",\"okFields\":" + outcome.filledOk +
                    ",\"missingSegments\":" + JSONArray(outcome.missingSegments) +
                    ",\"reason\":\"" + teachJsonEsc(outcome.stopReason ?: "") + "\"}",
                Charsets.UTF_8,
            )
        }
    }
}

// ── ⑦ 待发布卡（数据；渲染在 ui/baopu/FormFillSheet.kt）─────────────────────

/** 卡面一行：字段名 / 最终值 / 回读过没过。 */
data class PublishRow(
    val label: String,
    val value: String,
    val ok: Boolean,
    val reason: String? = null,
)

/**
 * 「待发布卡」：最终值 ＋ 截图 ＋ 两个动作（[copyText]／[openUrl]）。
 *
 * **没有第三个动作**：这个类里没有 submit/publish 字段，渲染层也没有第三个按钮——
 * 「最后一下你自己点」在数据结构上就是成立的。
 */
data class PublishCard(
    val title: String,
    val rows: List<PublishRow>,
    val shotPath: String?,
    val openUrl: String,
    val copyText: String,
    val notes: List<String> = emptyList(),
    val ok: Boolean,
) {
    val badRows: List<PublishRow> get() = rows.filter { !it.ok }
}

// ── ⑧ 批量（一张一行、逐张核对、逐张等你说下一张）───────────────────────────

/**
 * 批量跑卡。**一次只填一张**：`next()` 填完就停下等你（[pending] 非空期间
 * `next()` 一律 Blocked）——「不一次全填完再让你点」是结构保证，不是流程建议。
 */
internal class FormBatch(
    private val template: FormTemplate,
    private val cards: List<FormCard>,
    private val filler: FormFiller,
    private val evidence: FormEvidenceSink = FormEvidenceSink.None,
    private val shot: (cardName: String, index: Int) -> String? = { _, _ -> null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed class Step {
        /** 填完一张，出卡等你点发布。 */
        data class Filled(val card: PublishCard) : Step()

        /** 上一张还没走（没点创建/没点下一张）⇒ 不许越过去。 */
        data class Blocked(val reason: String) : Step()

        /** 全填完了。 */
        data object Done : Step()
    }

    private var index = 0
    private var pendingCard: PublishCard? = null
    private val taken = mutableSetOf<String>()

    /** 已出的卡（报告用）。 */
    val done: MutableList<CardOutcome> = mutableListOf()

    val total: Int get() = cards.size

    /** 当前进度（1 起，给人看）。 */
    fun progressText(): String = when {
        index >= cards.size -> "全部 ${cards.size} 张已出卡"
        else -> "第 ${index + 1} / ${cards.size} 张"
    }

    fun pending(): PublishCard? = pendingCard

    /** 填下一张（上一张没走 ⇒ Blocked；全填完 ⇒ Done）。 */
    fun next(): Step {
        pendingCard?.let {
            return Step.Blocked("「${it.title}」还没发布：先在页面上点创建，再点「下一张」")
        }
        if (index >= cards.size) return Step.Done
        val card = cards[index]
        val name = TeachFormRules.uniqueName(card.name, taken)
        taken += name
        val named = if (name == card.name) card else card.copy(name = name)
        val outcome = filler.fillCard(template, named)
        val dir = evidence.begin(name, stamp(clock()))
        outcome.fields.forEach { evidence.field(dir, it) }
        evidence.finish(dir, outcome)
        done += outcome
        val shotPath = shot(name, index)
        val publish = TeachFormRules.publishCard(template, named, outcome, shotPath)
        pendingCard = publish
        return Step.Filled(publish)
    }

    /** 用户确认这张已发布（或明确跳过）⇒ 放行下一张。 */
    fun advance(): Boolean {
        if (pendingCard == null) return false
        pendingCard = null
        index++
        return true
    }

    /** 收工一句话（报告与卡尾共用）。 */
    fun summary(): String = buildString {
        append("已出卡 ${done.size}/${cards.size} 张")
        val bad = done.filter { !it.ok }
        if (bad.isNotEmpty()) {
            append("；其中 ${bad.size} 张有没填对的地方：")
            append(bad.joinToString("、") { it.cardName })
        }
        if (bad.isEmpty() && done.isNotEmpty()) append("；全部回读一致")
    }
}

// ── ⑨ 生产端（玄览 execJs；端口实现只搬事实、判据仍在上面）──────────────────

/**
 * 表单查询脚本族（全部自包含 IIFE、返回 JSON 字符串、**无 eval**）。
 * 复用 196 的 [TeachScripts.HELPERS] 与十档匹配器：定位口径只有一个来源。
 */
internal object FormScripts {

    /**
     * 逐档找元素：返回**第一个有命中的档**的命中数组（与 [multiFinder] 同序，与 196 的
     * resolve 口径一致：低档只在前面的档全空时才有机会）。
     */
    private fun finder(locators: List<TeachLocator>): String {
        if (locators.isEmpty()) return "[]"
        val parts = locators.sortedBy { it.tier }.joinToString(";") { loc ->
            "h=" + TeachScripts.matcherFor(loc.kind, loc.value) + ";if(h.length>0)return h"
        }
        return "(function(){var h;$parts return []})()"
    }

    /**
     * 多档定位：逐档找，第一个有命中的档胜出（与 196 的 resolve 同序）。
     * 命中里带回「它是什么」：tag / type / contenteditable / name / id / placeholder / text。
     */
    private fun multiFinder(locators: List<TeachLocator>): String {
        if (locators.isEmpty()) return "[]"
        val payload = "JSON.stringify({n:hits.length,el:{tag:hits[0].tagName," +
            "type:(hits[0].getAttribute('type')||''),ce:(hits[0].getAttribute('contenteditable')||'')," +
            "name:(hits[0].getAttribute('name')||''),id:(hits[0].id||'')," +
            "ph:(hits[0].getAttribute('placeholder')||''),ac:(hits[0].getAttribute('autocomplete')||''),text:zlT(hits[0])}})"
        val parts = locators.sortedBy { it.tier }.joinToString(";") { loc ->
            "hits=" + TeachScripts.matcherFor(loc.kind, loc.value) + ";if(hits.length>0)return $payload"
        }
        return "(function(){$parts return JSON.stringify({n:0})})()"
    }

    /** 扫描：页面上所有可填控件的语义面（分类判据在 Kotlin）。 */
    val SCAN: String = "(function(){" + TeachScripts.HELPERS + """
function zlXp(el){var parts=[],n=el;while(n&&n.nodeType==1&&n!=document.body){var idx=1,sib=n.previousElementSibling;while(sib){if(sib.tagName==n.tagName)idx++;sib=sib.previousElementSibling}parts.unshift(n.tagName.toLowerCase()+'['+idx+']');n=n.parentElement}return '/html/body'+(parts.length?'/'+parts.join('/'):'')}
function zlFl(el){var c=el.closest('form,fieldset,td,th,label');if(!c)return '';var lb=c.querySelector('label,legend,th');var t=lb?(lb.textContent||'').trim():(c.getAttribute('aria-label')||'');return t.replace(/\s+/g,' ').slice(0,40)}
function zlLb(el){var lb=el.getAttribute('aria-labelledby');if(lb){var e=document.getElementById(lb);if(e)return (e.textContent||'').trim().slice(0,40)}
if(el.id){try{var l=document.querySelector('label[for="'+el.id+'"]');if(l)return (l.textContent||'').trim().slice(0,40)}catch(e){/* 非法 id：当无标签 */}}
var wl=el.closest('label');if(wl)return (wl.textContent||'').trim().slice(0,40);
var al=el.getAttribute('aria-label');if(al)return al.slice(0,40);return ''}
function zlOp(el){var out=[];if(el.tagName=='SELECT'&&el.options){for(var i=0;i<el.options.length&&out.length<60;i++){out.push({v:el.options[i].value||'',t:((el.options[i].text||'').trim()).slice(0,30)})}}
else if((el.getAttribute('type')||'')=='radio'&&el.name){var g=document.getElementsByName(el.name);
for(var j=0;j<g.length&&out.length<30;j++){out.push({v:(g[j].getAttribute('value')||''),t:(zlN(g[j])||'').slice(0,30)})}}
return out}
var out=[],els=document.querySelectorAll('input,textarea,select,[contenteditable]');
for(var i=0;i<els.length&&out.length<80;i++){var el=els[i];var r=el.getBoundingClientRect();
var ce=el.getAttribute('contenteditable');
out.push({tag:el.tagName,type:(el.getAttribute('type')||''),ce:(ce=='true'||ce==''),
 id:el.id||'',testid:el.getAttribute('data-testid')||'',aria:el.getAttribute('aria-label')||'',
 name:el.getAttribute('name')||'',ph:el.getAttribute('placeholder')||'',ac:el.getAttribute('autocomplete')||'',
 label:zlLb(el),form:zlFl(el),role:zlR(el),text:zlT(el),
 classes:(function(){var c=[];for(var k=0;k<el.classList.length&&c.length<8;k++)c.push(el.classList[k]);return c})(),
 xp:zlXp(el),options:zlOp(el),checked:!!el.checked,disabled:!!el.disabled,ro:!!el.readOnly,
 vis:(r.width>0||r.height>0)})}
return JSON.stringify({c:out})})()"""

    /** 探针：它是什么（命中几个、tag/type/contenteditable/name/placeholder）。 */
    fun probe(locators: List<TeachLocator>): String =
        "(function(){" + TeachScripts.HELPERS + "var r=" + multiFinder(locators) + ";return r})()"

    /**
     * 填：**唯一命中**才动手；单选例外（一组 radio 按 name 天然命中多个，挑一个）。
     * 全脚本**没有任何 click 调用**（负例 1 的结构面）：`el.click(` 在 201 的文件里不存在。
     */
    fun fill(locators: List<TeachLocator>, control: FormControl, value: String, optionValue: String?): String {
        val els = finder(locators)
        val v = jsStringLiteral(value)
        val ov = jsStringLiteral(optionValue ?: value)
        val guard = if (control == FormControl.RADIO) {
            "if(els.length<1)return JSON.stringify({ok:false,r:'这一组单选一个都没找到'});"
        } else {
            "if(els.length!=1)return JSON.stringify({ok:false,r:'定位命中 '+els.length+' 个，不动手'});"
        }
        val doFill = when (control) {
            FormControl.SINGLE_LINE ->
                "var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');" +
                    "if(d&&d.set)d.set.call(el,$v);else el.value=$v;" +
                    "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));"
            FormControl.RICH_TEXT ->
                "if(el.tagName=='TEXTAREA'){var d2=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');" +
                    "if(d2&&d2.set)d2.set.call(el,$v);else el.value=$v;}" +
                    // 富文本：按行分段塞 <p>，保住换行与分段（不是把纯文本塞进去）
                    "else{function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}" +
                    "var ps=$v.split('\\n'),h='';for(var i=0;i<ps.length;i++){h+='<p>'+esc(ps[i])+'</p>'}el.innerHTML=h;}" +
                    "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));"
            FormControl.SELECT ->
                "var okSel=false;for(var i=0;i<el.options.length;i++){if(el.options[i].value==$ov){el.selectedIndex=i;okSel=true;break}}" +
                    "if(!okSel)return JSON.stringify({ok:false,r:'下拉里没有这个选项：'+$ov});" +
                    "el.dispatchEvent(new Event('change',{bubbles:true}));"
            FormControl.RADIO ->
                "var grp=el.name?document.getElementsByName(el.name):[el];var hit=null;" +
                    "for(var i=0;i<grp.length;i++){var lbv=grp[i].getAttribute('value')||'';var lbt=zlN(grp[i])||'';" +
                    "if(lbv==$ov||lbt==$v){hit=grp[i];break}}" +
                    "if(!hit)return JSON.stringify({ok:false,r:'这一组单选里没有这个选项：'+$v});" +
                    "hit.checked=true;hit.dispatchEvent(new Event('change',{bubbles:true}));"
        }
        return "(function(){" + TeachScripts.HELPERS + "var els=$els;" + guard +
            "var el=els[0];$doFill return JSON.stringify({ok:true})})()"
    }

    /**
     * 回读：把输入框里的值读回来（富文本读 innerText，行数在 Kotlin 侧算）。
     * 单选读「这一组里哪一个被选中」。
     */
    fun read(locators: List<TeachLocator>, control: FormControl): String {
        val els = finder(locators)
        val guard = if (control == FormControl.RADIO) {
            "if(els.length<1)return JSON.stringify({ok:false,r:'这一组单选一个都没找到'});"
        } else {
            "if(els.length!=1)return JSON.stringify({ok:false,r:'回读命中 '+els.length+' 个'});"
        }
        val body = when (control) {
            FormControl.RICH_TEXT -> "t=(el.tagName=='TEXTAREA')?(el.value||''):(el.innerText||el.textContent||'');v=t;c=false;"
            FormControl.SELECT -> "t=(el.options&&el.selectedIndex>=0)?(el.options[el.selectedIndex].text||''):'';v=el.value||'';c=false;"
            FormControl.RADIO ->
                "var grp=el.name?document.getElementsByName(el.name):[el];var hit=null;" +
                    "for(var i=0;i<grp.length;i++){if(grp[i].checked){hit=grp[i];break}}" +
                    "t=hit?(zlN(hit)||''):'';v=hit?(hit.getAttribute('value')||''):'';c=!!hit;"
            FormControl.SINGLE_LINE -> "t=el.value||'';v=t;c=false;"
        }
        return "(function(){" + TeachScripts.HELPERS + "var els=$els;" + guard +
            "var el=els[0],t='',v='',c=false;$body" +
            "return JSON.stringify({ok:true,t:t,v:v,c:c})})()"
    }
}

/** [FormDom] 的玄览实现（经既有 `BrowserCommands.execJs`，不新开桥）。 */
internal class BrowserFormDom(private val tabId: Int?) : FormDom {

    private fun exec(code: String): JSONObject? {
        val raw = BrowserCommands.execJs(code, tabId?.toString())
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!o.optBoolean("ok")) return null
        val result = o.optString("result").takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSONObject(result) }.getOrNull()
    }

    override fun scan(): List<FormCandidate>? {
        val o = exec(FormScripts.SCAN) ?: return null
        val arr = o.optJSONArray("c") ?: return null
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            FormCandidate(
                tag = c.optString("tag").uppercase(),
                inputType = c.optString("type").takeIf { it.isNotEmpty() },
                contentEditable = c.optBoolean("ce"),
                elemId = c.optString("id").takeIf { it.isNotEmpty() },
                testid = c.optString("testid").takeIf { it.isNotEmpty() },
                ariaLabel = c.optString("aria").takeIf { it.isNotEmpty() },
                inputName = c.optString("name").takeIf { it.isNotEmpty() },
                placeholder = c.optString("ph").takeIf { it.isNotEmpty() },
                autocomplete = c.optString("ac").takeIf { it.isNotEmpty() },
                labelText = c.optString("label").takeIf { it.isNotEmpty() },
                formLabel = c.optString("form").takeIf { it.isNotEmpty() },
                role = c.optString("role").takeIf { it.isNotEmpty() },
                text = c.optString("text").takeIf { it.isNotEmpty() },
                classes = run {
                    val ca = c.optJSONArray("classes") ?: return@run emptyList<String>()
                    (0 until ca.length()).mapNotNull { k -> ca.optString(k).takeIf { it.isNotBlank() } }
                },
                xpath = c.optString("xp").takeIf { it.isNotEmpty() },
                options = run {
                    val oa = c.optJSONArray("options") ?: return@run emptyList<TeachOption>()
                    (0 until oa.length()).mapNotNull { k ->
                        val oo = oa.optJSONObject(k) ?: return@mapNotNull null
                        val v = oo.optString("v")
                        if (v.isBlank() && oo.optString("t").isBlank()) null
                        else TeachOption(v, oo.optString("t").ifBlank { v })
                    }
                },
                checked = c.optBoolean("checked"),
                disabled = c.optBoolean("disabled"),
                readOnly = c.optBoolean("ro"),
                visible = c.optBoolean("vis", true),
            )
        }
    }

    override fun probe(locators: List<TeachLocator>): FormReply {
        val o = exec(FormScripts.probe(locators)) ?: return FormReply.Failed("探针失败（页面没响应）")
        val n = o.optInt("n", 0)
        if (n <= 0) return FormReply.Probed(FormProbe(found = false, hitCount = 0, reason = "页面里找不到这个字段"))
        val el = o.optJSONObject("el")
        val ce = el?.optString("ce").orEmpty()
        return FormReply.Probed(
            FormProbe(
                found = true,
                hitCount = n,
                tag = el?.optString("tag").orEmpty(),
                inputType = el?.optString("type")?.takeIf { it.isNotEmpty() },
                // contenteditable="" 与 "true" 都算可编辑（HTML 规范：空值＝true）
                contentEditable = ce == "true" || ce == "",
                inputName = el?.optString("name")?.takeIf { it.isNotEmpty() },
                elemId = el?.optString("id")?.takeIf { it.isNotEmpty() },
                placeholder = el?.optString("ph")?.takeIf { it.isNotEmpty() },
                autocomplete = el?.optString("ac")?.takeIf { it.isNotEmpty() },
                text = el?.optString("text")?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    override fun fill(
        locators: List<TeachLocator>,
        control: FormControl,
        value: String,
        optionValue: String?,
    ): FormReply {
        val o = exec(FormScripts.fill(locators, control, value, optionValue))
            ?: return FormReply.Failed("填动作失败（页面没响应）")
        return FormReply.Filled(o.optBoolean("ok"), o.optString("r").takeIf { it.isNotEmpty() })
    }

    override fun read(locators: List<TeachLocator>, control: FormControl): FormReply {
        val o = exec(FormScripts.read(locators, control))
            ?: return FormReply.Failed("回读失败（页面没响应）")
        if (!o.optBoolean("ok")) {
            return FormReply.Failed(o.optString("r").ifBlank { "回读失败" })
        }
        return FormReply.Read(
            FormReadback(
                text = o.optString("t"),
                value = o.optString("v"),
                checked = o.optBoolean("c"),
            ),
        )
    }
}

/**
 * 生产入口（第二百零一批 ① 之后有了调用点：`form_fill` 的 `batch` 分支——
 * 之前这三层合了却一个调用点都没有，这一条就是本批要补的出口）。
 */
internal object TeachForm {

    /**
     * 默认截图手：玄览当前标签截一张核对图。
     *
     * `album=false` ＝**核对图、不进相册**（一次任务几十张，刷相册会把用户相册占满）。
     * 截不到就返回 null——卡上会明说「这次没取到截图」，不许留白让人以为有图。
     * 单独成一个函数：工具层（`form_fill` 的 batch 分支）要能在 JVM 单测里把它换成假手。
     */
    fun browserShot(): String? = runCatching {
        BaopuBrowser.require()?.screenshot(fullPage = false, album = false)?.relPath
    }.getOrNull()

    /**
     * 批量跑卡的生产壳。[dom]/[shot] 是参数而不是写死：批量这条路要能被 JVM 单测驱动
     * （假 DOM ＋ 临时工作区），而工具层的 `dom` 本来就是注入点——这里再自己 new 一个
     * `BrowserFormDom` 就等于「测试填一套、生产填另一套」。默认值仍是生产实现，不传就是真页面。
     *
     * 证据落 `<工作区>/产物/填表/`：卡名重名由 [TeachFormRules.uniqueName] 加 `-2`，
     * 目录重名由 [FileFormEvidence] 自己加（负例 6 两道都不覆盖）。
     */
    fun batch(
        workspace: File,
        template: FormTemplate,
        cards: List<FormCard>,
        dom: FormDom = BrowserFormDom(null),
        shot: (cardName: String, index: Int) -> String? = { _, _ -> browserShot() },
    ): FormBatch = FormBatch(
        template = template,
        cards = cards,
        filler = FormFiller(dom),
        evidence = FileFormEvidence(File(MemoryLayout.productsDir(workspace), "填表")),
        shot = shot,
    )
}
