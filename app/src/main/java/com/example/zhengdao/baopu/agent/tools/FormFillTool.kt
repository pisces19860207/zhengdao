// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十四批 · 表单能力接线：把 201 的 `TeachForm.kt`（1533 行、零调用点）接进生产。
//
// 一句话：**只填，不提交**。填哪几个字段由你点名，最后一下永远人点。
//
// 复用而不是重写（派工单 §2 负例 8）：
//   · 扫描/分类/排除  → `TeachFormRules.buildFields`（201 的判据层）
//   · 拒填/敏感词表   → `TeachSafety.refuseTypeReason`（196 的三层同源名单）
//   · 填 + 探针 + 回读 → `FormFiller.fillField`（201 的执行层，本文件不碰页面细节）
//   · 端口            → `FormDom`（**签名里没有 click**：永不自点提交是结构保证，不是自觉）
//
// 结构（本文件只做三件 201 没做的事）：
//   ① [FormFillRules]：**按名字找字段**（标签→name→placeholder 三档，多命中即停）＋ 按钮面收集；
//   ② [FormFillRunner]：一次调用内「填 → 证据 → 失败即停 → 回滚」的编排；
//   ③ [FormFillTool]：JSON Schema ＋ 工具描述（「只填不提交」写进给模型看的那句话里）。
//
// 红线落在哪（负例逐条对得上）：
//   1 工具自己点提交 ⇒ 端口没有 click、本文件没有 `.click(`，且 `submit=true` 直接被拒；
//   2 密码/验证码被填 ⇒ 名字档先拒 → 扫描期 classify 排除 → 动手前探针再拒（三道）；
//   3 敏感值进日志 ⇒ 证据行过 [FormFillRules.maskValue]，手机号/密钥量级一律打码；
//   4 歧义时猜填 ⇒ 同一档命中 >1 只报 `ambiguous`，不降档、不取第一个；
//   5 失败无清单/不可撤回 ⇒ [FormFillReport.rolledBack] ＋ `skipped` 原样回灌；
//   6 未注册/未进 R 表 ⇒ 注册表与 RiskMatrix 各加一行（RiskMatrixTest 全覆盖门禁盯着）；
//   7 描述暗示可提交 ⇒ [FormFillTool.DESCRIPTION] 开头就是「只填，不提交」，并有源级门禁钉死；
//   8 另起一套实现 ⇒ 本文件的填表动作**全部**走 `FormFiller`/`FormScripts`，没有第二套匹配器。
//
// 示教入口（本单**不做**，派工单 §1-②）：`TeachReplay.kt` 正被 226 改（录制注入＋桥），
// 本单按红线一个字不碰它；待 226 合入后，回放遇表单的那一支改调本文件的
// [FormFillRunner]（＝ `form_fill` 的执行体），不要再写第二套——挂账在 224 派工单。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.BrowserFormDom
import com.example.zhengdao.baopu.FormCandidate
import com.example.zhengdao.baopu.FormControl
import com.example.zhengdao.baopu.FormDom
import com.example.zhengdao.baopu.FormField
import com.example.zhengdao.baopu.FormFiller
import com.example.zhengdao.baopu.FormReply
import com.example.zhengdao.baopu.FormScanResult
import com.example.zhengdao.baopu.TeachFormRules
import com.example.zhengdao.baopu.TeachSafety
import org.json.JSONArray
import org.json.JSONObject

// ── ① 判据层（纯函数：按名字找字段 / 拒填判据 / 按钮面 / 脱敏）──────────────────

internal object FormFillRules {

    /** 匹配的三档（顺序即优先级：标签 → name → placeholder）。 */
    enum class Tier(val wire: String, val human: String) {
        LABEL("label", "标签"),
        NAME("name", "name"),
        PLACEHOLDER("placeholder", "placeholder"),
    }

    /** 一次「按名字找字段」的结论。 */
    sealed class Match {
        data class One(val field: FormField, val tier: Tier) : Match()
        data class Many(val tier: Tier, val fields: List<FormField>) : Match()
        data object None : Match()
    }

    /** 字段在某一档上的文本（没人填的属性＝空串，空串永不参与命中）。 */
    fun textOf(f: FormField, tier: Tier): String = when (tier) {
        Tier.LABEL -> f.label
        Tier.NAME -> f.inputName.orEmpty()
        Tier.PLACEHOLDER -> f.placeholder.orEmpty()
    }

    /**
     * 按名字找字段：**标签 → name → placeholder** 三档，某档命中多个就停手报歧义。
     *
     * 三条刻意的取舍：
     * - 只做**全等**匹配（归一化后），不做「包含」——包含会把「标题」命中「副标题」，
     *   那是猜，而猜错字段比找不到更伤人（往错格里填了东西，用户可能直接点提交）。
     * - 命中多个**不降档**：标签档歧义就该让人说清，而不是偷偷去 name 档找一个。
     * - 空属性**不参与**命中（否则「没有 placeholder 的字段」会被空串请求一把捞走）。
     */
    fun match(fields: List<FormField>, request: String): Match {
        val want = TeachFormRules.normalize(request)
        if (want.isEmpty()) return Match.None
        for (tier in Tier.entries) {
            val hits = fields.filter { f ->
                val t = TeachFormRules.normalize(textOf(f, tier))
                t.isNotEmpty() && t.equals(want, ignoreCase = true)
            }
            when {
                hits.size == 1 -> return Match.One(hits.first(), tier)
                hits.size > 1 -> return Match.Many(tier, hits)
                else -> Unit
            }
        }
        return Match.None
    }

    /**
     * 请求名本身就是凭证语义 ⇒ 直接拒（第一道闸，动手之前）。
     *
     * 复用 [TeachSafety.refuseTypeReason] 的词表：名字里带「密码/验证码/口令/captcha/
     * password/otp/one-time/secret/token」的一律不填。宁可误拒（停下问人），不可漏填。
     */
    fun refuseName(request: String): String? = TeachSafety.refuseTypeReason(
        inputType = null,
        inputName = request,
        inputId = null,
        placeholder = null,
        autocomplete = null,
    )

    /** 手机号样式（与 RiskTable.sensitiveTextPatterns 同一口径）。 */
    private val PHONE: Regex = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")

    /**
     * 证据行里的值：**敏感值不打日志**（负例 3）。
     *
     * 三档：手机号 → 打码；≥28 字符的连续串（密钥/JWT/Base64 量级，与
     * `TeachSafety.CREDENTIAL_LITERAL` 同判据）→ 打码；其余单行化 + 截断（[TeachFormRules.preview]）。
     */
    fun maskValue(v: String): String {
        if (v.isBlank()) return v
        if (PHONE.containsMatchIn(v)) return TeachSafety.mask(v)
        if (v.length >= 28 && v.none { it.isWhitespace() }) return TeachSafety.mask(v)
        return TeachFormRules.preview(v)
    }

    /** 这个原值能不能被写回/记录：看起来像敏感信息就不撤回、不记原文。 */
    fun sensitiveValue(v: String): Boolean =
        v.isNotBlank() && (PHONE.containsMatchIn(v) || (v.length >= 28 && v.none { it.isWhitespace() }))

    /** 一个字段的定位器一行（证据里的「选择器」列）。 */
    fun selectorOf(f: FormField): String =
        f.locators.minByOrNull { it.tier }?.describe() ?: "（没有定位器）"

    /** 页面上的按钮面（`needsHumanClick` 的清单：**只报，不点**）。 */
    data class ButtonFace(val text: String, val dangerous: Boolean)

    /**
     * 收集「人该自己点」的按钮。
     *
     * 为什么不只收命中高危词的那几个：`完成`/`继续`/`下一步` 这类按钮不在词表里，
     * 但点了照样把表单交出去 ⇒ 只要页面上有按钮，就整份交给人（词表只用来标 ⚠）。
     */
    fun buttons(candidates: List<FormCandidate>): List<ButtonFace> =
        candidates.filter(::isButtonLike)
            .map { c ->
                val text = listOfNotNull(c.text, c.labelText, c.ariaLabel, c.elemId, c.inputName)
                    .firstOrNull { it.isNotBlank() } ?: "（无文字的按钮）"
                ButtonFace(text.trim(), DANGER.containsMatchIn(text))
            }
            .distinctBy { it.text }
            .take(MAX_BUTTONS)

    private fun isButtonLike(c: FormCandidate): Boolean {
        val tag = c.tag.uppercase()
        val type = c.inputType?.lowercase().orEmpty()
        return tag == "BUTTON" || c.role == "button" || type in setOf("submit", "button", "reset", "image")
    }

    /**
     * 高危按钮词（比 `TeachFormRules.SUBMIT_LIKE` 多「登录/支付/结算/下单」几个真实现场里
     * 最常见的提交出口；它只用于**标 ⚠ 和排序**，不用于「点不点」——工具本来就不点）。
     */
    val DANGER: Regex = Regex(
        "提交|确认|保存|创建|发布|登录|登陆|注册|支付|付款|结算|下单|购买|投稿|发送|" +
            "(?i)(?<![a-z])(submit|confirm|save|create|publish|login|sign\\s?in|register|pay|checkout|order|post)(?![a-z])",
    )

    const val MAX_BUTTONS: Int = 8
}

// ── ② 报告（工具回执就是证据：每字段一条「选择器 / 标签 / 前值→后值」）─────────────

/** 一个字段填成了。 */
internal data class FillRecord(
    val request: String,
    val field: FormField,
    val tier: FormFillRules.Tier,
    val before: String,
    val after: String,
) {
    /** 证据行（人可读，与 JSON 里的 evidence 同一句）。 */
    fun evidence(): String = buildString {
        append(field.label.ifBlank { field.key })
        append(" [").append(FormFillRules.selectorOf(field)).append(']')
        append("：").append(FormFillRules.maskValue(before).ifBlank { "（空）" })
        append(" → ").append(FormFillRules.maskValue(after))
    }
}

/** 没填上的（含被拒、找不到、填了但回读不一致）。 */
internal data class SkipRecord(val field: String, val reason: String)

/** 歧义：同一档命中多个，不猜。 */
internal data class AmbiguousRecord(
    val field: String,
    val reason: String,
    val candidates: List<String>,
)

/** 一次调用的全部事实（只装事实，句子在 [toJson] 里拼）。 */
internal data class FormFillReport(
    val filled: List<FillRecord> = emptyList(),
    val skipped: List<SkipRecord> = emptyList(),
    val ambiguous: List<AmbiguousRecord> = emptyList(),
    val buttons: List<FormFillRules.ButtonFace> = emptyList(),
    val rolledBack: List<String> = emptyList(),
    val rollbackSkipped: List<SkipRecord> = emptyList(),
    /** 失败即停时的原因（null ＝ 没停过）。 */
    val stopped: String? = null,
    /** 读不到页面（玄览没开/取不到 DOM）⇒ filled 必空。 */
    val unreadable: String? = null,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("submitted", false)
        o.put("filled", JSONArray().also { arr ->
            filled.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("field", r.request)
                        .put("label", r.field.label)
                        .put("tier", r.tier.wire)
                        .put("control", r.field.control.wire)
                        .put("selector", FormFillRules.selectorOf(r.field))
                        .put("before", FormFillRules.maskValue(r.before))
                        .put("after", FormFillRules.maskValue(r.after))
                        .put("evidence", r.evidence()),
                )
            }
        })
        o.put("skipped", JSONArray().also { arr ->
            skipped.forEach { arr.put(JSONObject().put("field", it.field).put("reason", it.reason)) }
        })
        o.put("ambiguous", JSONArray().also { arr ->
            ambiguous.forEach {
                arr.put(
                    JSONObject()
                        .put("field", it.field)
                        .put("reason", it.reason)
                        .put("candidates", JSONArray(it.candidates)),
                )
            }
        })
        // 按钮安全：页面上要人点的按钮整份交出（工具永不点，端口也没有点击通道）
        o.put("needsHumanClick", buttons.isNotEmpty())
        o.put("buttons", JSONArray().also { arr ->
            buttons.sortedByDescending { if (it.dangerous) 1 else 0 }
                .forEach { arr.put(JSONObject().put("text", it.text).put("dangerous", it.dangerous)) }
        })
        o.put("rolledBack", JSONArray(rolledBack))
        o.put("rollbackSkipped", JSONArray().also { arr ->
            rollbackSkipped.forEach { arr.put(JSONObject().put("field", it.field).put("reason", it.reason)) }
        })
        o.put("stopped", stopped ?: JSONObject.NULL)
        o.put("unreadable", unreadable ?: JSONObject.NULL)
        o.put("note", note())
        return o.toString()
    }

    /** 人话一句（卡面/回执尾部；不重复上面已经说过的字段级原因）。 */
    fun note(): String = buildString {
        if (unreadable != null) {
            append("没读到页面：").append(unreadable).append("。一个字段都没填。")
            return@buildString
        }
        append("填了 ").append(filled.size).append(" 个字段")
        if (skipped.isNotEmpty()) append("，").append(skipped.size).append(" 个没填")
        if (ambiguous.isNotEmpty()) append("，").append(ambiguous.size).append(" 个名字有歧义（没猜）")
        if (rolledBack.isNotEmpty()) append("；已把 ").append(rolledBack.size).append(" 个字段清回原值")
        if (stopped != null) append("；中途停下：").append(stopped)
        append("。没有提交")
        if (buttons.isNotEmpty()) {
            append("——最后一步的「").append(buttons.first().text).append("」你自己点。")
        } else {
            append("。")
        }
    }

    companion object {
        /** 读不到页面时的空报告（filled 必空——不许在没看见页面的情况下声称填过）。 */
        fun blind(reason: String): FormFillReport = FormFillReport(unreadable = reason)
    }
}

// ── ③ 执行编排（填 → 证据 → 失败即停 → 回滚；全部动作走 FormFiller）─────────────

internal class FormFillRunner(private val dom: FormDom) {

    private val filler = FormFiller(dom)

    /** 计划要填的一格。 */
    private data class Planned(
        val request: String,
        val field: FormField,
        val tier: FormFillRules.Tier,
        val value: String,
    )

    /** 已经填成功、可回滚的一格（[before] ＝ 动手前读到的原值）。 */
    private data class Undo(val field: FormField, val before: String)

    fun run(requests: List<Pair<String, String>>, rollbackOnFailure: Boolean): FormFillReport {
        val candidates = dom.scan()
            ?: return FormFillReport.blind("玄览没开、没有标签页，或页面还没加载完")
        val scan = TeachFormRules.buildFields(candidates)
        val buttons = FormFillRules.buttons(candidates)
        val all = scan.fields + scan.blocks.sortedBy { it.index }.flatMap { it.fields }

        val skipped = mutableListOf<SkipRecord>()
        val ambiguous = mutableListOf<AmbiguousRecord>()
        val planned = mutableListOf<Planned>()
        val claimed = mutableSetOf<String>()

        for ((name, value) in requests) {
            if (value.isBlank()) {
                skipped += SkipRecord(name, "值是空的，不填")
                continue
            }
            FormFillRules.refuseName(name)?.let {
                skipped += SkipRecord(name, it)
                continue
            }
            when (val m = FormFillRules.match(all, name)) {
                is FormFillRules.Match.Many -> ambiguous += AmbiguousRecord(
                    name,
                    "「$name」在${m.tier.human}这一档命中 ${m.fields.size} 个字段，不猜——" +
                        "请在名字里带上更精确的写法（name 或 placeholder 原文）",
                    m.fields.map { it.describe() },
                )

                is FormFillRules.Match.None -> skipped += SkipRecord(name, missReason(scan, name))

                is FormFillRules.Match.One -> {
                    if (!claimed.add(m.field.key)) {
                        skipped += SkipRecord(name, "和前面点名的字段是同一个（${m.field.label}），只填一次")
                    } else {
                        planned += Planned(name, m.field, m.tier, value)
                    }
                }
            }
        }

        val filled = mutableListOf<FillRecord>()
        val undo = mutableListOf<Undo>()
        var stopped: String? = null

        // 填的**顺序**按「字段在页面上出现的先后」，不按 JSON 键序。
        //
        // 为什么：`JSONObject.keys()` 在真机是插入序，在 JVM 单测里是哈希序
        // （org.json 参考实现用 HashMap）⇒ 依赖它，同一份入参在两处会填出两种顺序，
        // 「失败即停」停在哪个字段也就不可复现。按页面顺序则两处一致，且符合人填表的直觉。
        val pageIndex = all.withIndex().associate { (i, f) -> f.key to i }
        val ordered = planned.sortedBy { pageIndex[it.field.key] ?: Int.MAX_VALUE }

        for ((idx, p) in ordered.withIndex()) {
            val op = TeachFormRules.resolveOp(p.field, p.value)
            if (op == null) {
                skipped += SkipRecord(p.request, "这个值落不进「${p.field.label}」（下拉里没有这一项？）")
                continue
            }
            val before = readExisting(p.field)
            val outcome = filler.fillField(op)
            if (!outcome.ok) {
                // 失败即停：后面的字段一个都不动（半填的表最难收拾），但每一格都要有交代
                stopped = "「${p.field.label}」没填对：${outcome.reason ?: "回读不一致"}"
                skipped += SkipRecord(p.request, "填失败：${outcome.reason ?: "回读不一致"}")
                ordered.drop(idx + 1).forEach {
                    skipped += SkipRecord(it.request, "上一个字段没填对，已停下（这一格没动）")
                }
                break
            }
            filled += FillRecord(p.request, p.field, p.tier, before, outcome.actual)
            undo += Undo(p.field, before)
        }

        // 回滚：只在「中途停下」时做（全部成功就不动——那是用户想看的结果）
        val rolledBack = mutableListOf<String>()
        val rollbackSkipped = mutableListOf<SkipRecord>()
        if (stopped != null && rollbackOnFailure) {
            undo.asReversed().forEach { u ->
                if (FormFillRules.sensitiveValue(u.before)) {
                    // 原值本身是敏感信息 ⇒ 不写回、也不记录原文（负例 3）
                    rollbackSkipped += SkipRecord(u.field.label, "原值疑似敏感信息，不写回也不记录")
                    return@forEach
                }
                val why = restore(u.field, u.before)
                if (why == null) rolledBack += u.field.label else rollbackSkipped += SkipRecord(u.field.label, why)
            }
        }

        return FormFillReport(filled, skipped, ambiguous, buttons, rolledBack, rollbackSkipped, stopped)
    }

    /** 动手前读原值（读不到就算未知 ⇒ 空串，回滚时会走「写空」那条路）。 */
    private fun readExisting(field: FormField): String {
        val read = when (val r = dom.read(field.locators, field.control)) {
            is FormReply.Read -> r.read
            else -> return ""
        }
        return read.value.ifBlank { read.text }
    }

    /**
     * 把一格清回原值。返回 null ＝ 成功，否则是原因。
     *
     * 两条路：原值非空 ⇒ 复用 [FormFiller.fillField]（同一套探针＋回读校验，不新写一条写路径）；
     * 原值为空 ⇒ [FormFiller] 拒空值，这里走端口写空再回读确认（**仍然验回读**，不信任动作返回值）。
     */
    private fun restore(field: FormField, before: String): String? {
        if (before.isBlank()) {
            val option = if (field.control == FormControl.SELECT || field.control == FormControl.RADIO) "" else null
            when (val r = dom.fill(field.locators, field.control, "", option)) {
                is FormReply.Filled -> if (!r.ok) return r.reason ?: "写回失败"
                is FormReply.Failed -> return r.reason
                else -> return "写回答复形状不对"
            }
            val read = when (val r = dom.read(field.locators, field.control)) {
                is FormReply.Read -> r.read
                else -> return "回读失败（不确定是否清干净）"
            }
            return if (read.text.isBlank() && read.value.isBlank()) null else "回读不是空的（读到：${FormFillRules.maskValue(read.text)}）"
        }
        val op = resolveUndo(field, before)
        val out = filler.fillField(op)
        return if (out.ok) null else (out.reason ?: "回读不一致")
    }

    /** 撤回的指令：下拉/单选的原值是 option value，直接塞回去；其余按原文。 */
    private fun resolveUndo(field: FormField, before: String): TeachFormRules.FormOp =
        TeachFormRules.resolveOp(field, before) ?: TeachFormRules.FormOp(
            field,
            before,
            if (field.control == FormControl.SELECT || field.control == FormControl.RADIO) before else null,
        )

    /** 找不到字段时说清「页面上有什么」（其中「被排除」的原因直接复用 201 的判据）。 */
    private fun missReason(scan: FormScanResult, name: String): String {
        val want = TeachFormRules.normalize(name)
        scan.excluded.firstOrNull { TeachFormRules.normalize(it.label).equals(want, ignoreCase = true) }?.let {
            return "页面上这个名字的控件不能填：${it.reason}"
        }
        val all = scan.fields + scan.blocks.flatMap { it.fields }
        val names = all.map { it.label }.filter { it.isNotBlank() }.distinct().take(8)
        return if (names.isEmpty()) {
            "页面上找不到「$name」（这一页没有能填的字段）"
        } else {
            "页面上找不到「$name」；能填的有：${names.joinToString("、")}"
        }
    }
}

// ── ④ 工具面（schema ＋ 描述 ＋ 注册）────────────────────────────────────────

internal object FormFillTool {

    const val NAME = "form_fill"

    /**
     * 给模型看的说明。三条硬要求（负例 7 的靶子）：**开头就说只填不提交**；
     * 明说提交类按钮由人点；明说敏感字段拒填——描述里不许出现任何「它会帮你提交」的暗示。
     */
    const val DESCRIPTION: String =
        "往玄览当前页的表单里填字段。**只填，不提交**：提交/保存/登录/支付这些按钮本工具永不点击，" +
            "填完只把按钮列给你、由用户自己点。fields 是 {\"标签或name\":\"值\"}，只填你点名的字段；" +
            "名字按「标签 → name → placeholder」三档找，命中多个就停手报歧义（不猜）。" +
            "密码/验证码/一次性码（autocomplete=one-time-code）一律拒填。返回每字段的证据" +
            "（选择器、前值→后值）与 needsHumanClick。"

    private const val ARG_FIELDS = "fields"
    private const val ARG_SUBMIT = "submit"
    private const val ARG_ROLLBACK = "rollbackOnFailure"

    /** `submit=true` 时的拒绝文案（工具层最后一道：连「代点」这个念头都在这里掐掉）。 */
    const val SUBMIT_REFUSAL: String =
        "form_fill 不做提交：它只填字段。提交/保存/登录/支付这一步必须由用户在页面上自己点" +
            "（把填好的页面留着，告诉用户点哪个按钮）。"

    /** 生产装配：默认接玄览当前标签（`BrowserFormDom` 是 201 的端口实现，不新开桥）。 */
    fun spec(dom: () -> FormDom = { BrowserFormDom(null) }): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_FIELDS to jsonObjectOf(
                    "type" to "object",
                    "description" to "要填的字段：{\"标签或name\":\"值\"}。只填这里点名的字段，页面上其余字段不碰",
                ),
                ARG_SUBMIT to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "必须省略或 false。本工具永不提交；传 true 会被直接拒绝",
                ),
                ARG_ROLLBACK to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "某个字段失败时，把已经填好的字段清回原值（默认 true）",
                ),
            ),
            "required" to jsonArrayOf(ARG_FIELDS),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.BROWSER_MS,
        riskTier = "R2",
        readOnly = false,
    ) { args -> execute(args, dom()) }

    /** 执行体（JVM 直测：单元测试传假 [FormDom]，不碰 WebView）。 */
    fun execute(args: JSONObject, dom: FormDom): ToolOutcome {
        if (args.optBoolean(ARG_SUBMIT, false)) return ToolOutcome.Rejected(SUBMIT_REFUSAL)
        val obj = args.optJSONObject(ARG_FIELDS)
            ?: return ToolOutcome.Failed("fields 必须是对象：{\"标签或name\":\"值\"}")
        val requests = mutableListOf<Pair<String, String>>()
        val ignored = mutableListOf<String>()
        for (key in obj.keys()) {
            val v = obj.opt(key)
            val text = when (v) {
                is String -> v
                is Number, is Boolean -> v.toString()
                else -> null
            }
            if (text == null) ignored += key else requests += key to text
        }
        if (requests.isEmpty()) {
            return ToolOutcome.Failed(
                "fields 里没有可填的字段（值只能是文本/数字/布尔）" +
                    if (ignored.isEmpty()) "" else "；这些键的值不是文本：${ignored.joinToString("、")}",
            )
        }
        val report = FormFillRunner(dom).run(requests, args.optBoolean(ARG_ROLLBACK, true))
        return ToolOutcome.Ok(report.toJson())
    }
}
