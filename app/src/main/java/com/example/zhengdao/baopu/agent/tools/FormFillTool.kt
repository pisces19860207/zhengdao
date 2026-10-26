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
//   · 排队 + 逐张核对 → `FormBatch.next/advance`、`TeachFormRules.parseCardsJson`/`uniqueName`（201 的批量层）
//   · 端口            → `FormDom`（**签名里没有 click**：永不自点提交是结构保证，不是自觉）
//
// 结构（本文件只做五件 201 没做的事；判据与跑卡逻辑仍然只在 201 那边）：
//   ① [FormFillRules]：**按名字找字段**（标签→name→placeholder 三档，多命中即停）＋ 按钮面收集；
//   ② [FormFillReport]：单发那一趟的回执（每字段一条「选择器 / 前值→后值」）；
//   ③ [FormFillRunner]：一次调用内「填 → 证据 → 失败即停 → 回滚」的编排；
//   ④ [FormBatchRunner]／[FormBatchSessions]／[FormBatchReport]：`batch` 分支——把 201 的
//      [FormBatch]「一次一张、等你说下一张」原样搬进工具会话（跑卡逻辑一条都没新写）；
//   ⑤ [FormFillTool]：JSON Schema ＋ 工具描述（「只填不提交」写进给模型看的那句话里）。
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
// 第二百零一批的六条负例（批量分支逐条对得上；判据都在 201，这里只接线）：
//   1 点到创建 ⇒ [FormDom] 没有 click 通道 ＋ `submit=true` 在最外层就拒（批量分支也走同一道）；
//   2 填进密码框 ⇒ 模板由 `TeachFormRules.buildFields` 现扫生成，密码/验证码进不了模板；
//   3 富文本降成纯文本 ⇒ [FormFiller] 的 RICH_TEXT 回读比行数（本文件不碰页面细节，降不了）；
//   4 多段块只填第一段 ⇒ [FormFiller.fillCard] 出卡前复查缺段，缺哪段写进卡的 `notes`；
//   5 回读不一致却报「填好」⇒ 回执里的 `card.ok` 直接抄 [PublishCard.ok]，而它只由回读决定；
//   6 批量第二张盖掉第一张 ⇒ 卡名 `-2` 由 [TeachFormRules.uniqueName]、证据目录 `-2` 由
//     [com.example.zhengdao.baopu.FileFormEvidence]，两道都在 201，且逐张都带自己的截图。
//
// 两条出口（第二百零一批补齐——224 只交了「单发填点名的那几个字段」这一条）：
//   · 批量 → 本文件 [FormBatchRunner]（`form_fill` 的 `batch` 参数）→ [TeachForm.batch] → [FormBatch]；
//   · 回放 → `TeachReplay.kt` 的 TYPE/SELECT 两支走 201 的 [FormFiller]（`FormFillingTeachDom`）。
//
// 224 原本把回放那一条挂账成「等 226 合入后改调本文件的 [FormFillRunner]」——第二百零一批做完了，
// 但接线点是 [FormFiller] 而**不是** [FormFillRunner]：回放的定位来自技能（十档链＋自愈），
// 不是「按名字找字段」；把 [FormFillRunner] 塞进回放等于丢掉十档与自愈。能复用、也该复用的是
// **动手那一句**（探针→拒填闸→填→回读校验），那正是 [FormFiller.fillField]。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.BrowserFormDom
import com.example.zhengdao.baopu.FormBatch
import com.example.zhengdao.baopu.FormCandidate
import com.example.zhengdao.baopu.FormControl
import com.example.zhengdao.baopu.FormDom
import com.example.zhengdao.baopu.FormField
import com.example.zhengdao.baopu.FormFiller
import com.example.zhengdao.baopu.FormReply
import com.example.zhengdao.baopu.FormScanResult
import com.example.zhengdao.baopu.FormTemplate
import com.example.zhengdao.baopu.PublishCard
import com.example.zhengdao.baopu.TeachForm
import com.example.zhengdao.baopu.TeachFormRules
import com.example.zhengdao.baopu.TeachSafety
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

// ── ④ 批量出口（会话 ＋ 回执 ＋ 执行体；跑卡的逻辑一条都不在这里）─────────────

/**
 * 一条批量会话＝模板 ＋ 卡队列 ＋ 跑到哪一张（进度、以及「上一张还等着发布」都住在 [batch] 里）。
 *
 * 为什么只存进程、不落盘：[FormBatch] 的「逐张核对」靠的是内存里的 [FormBatch.pending]——
 * 一旦允许从磁盘重建会话，「拿同一份模板重新 start」就能绕过「等你点创建」。代价说清：
 * App 重启＝会话没了，但每一张的截图与逐字段回读**已经落在** `产物/填表/`，卡不丢。
 */
internal class FormBatchSession(
    val id: String,
    val template: FormTemplate,
    val batch: FormBatch,
)

/** 会话表（进程级、有上限：一次任务开十几批，也不许把十几套页面网关留在内存里）。 */
internal object FormBatchSessions {

    /** 同时在飞的批量会话上限；超了挤掉最老的（卡已落盘，挤掉不丢数据）。 */
    private const val MAX_SESSIONS = 8

    private var seq = 0
    private val sessions = LinkedHashMap<String, FormBatchSession>()

    /** 开一条新会话。id 由这里发——模型不许自拟，自拟会撞进别人的会话。 */
    @Synchronized
    fun open(template: FormTemplate, batch: FormBatch): FormBatchSession {
        val session = FormBatchSession(id = "fb-${++seq}", template = template, batch = batch)
        while (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.keys.first())
        sessions[session.id] = session
        return session
    }

    @Synchronized
    fun get(id: String): FormBatchSession? = sessions[id]
}

/** 批量分支的回执（只装事实；「一次只填一张」由 [FormBatch] 保证，这里不重复判）。 */
internal data class FormBatchReport(
    val sessionId: String,
    val action: String,
    val templateName: String,
    val progress: String,
    val total: Int,
    /** 已出卡张数（含回读没过的那几张——出过卡就算一张，报告不许藏）。 */
    val cardsOut: Int,
    val finished: Boolean,
    /** 这一趟交出的「待发布卡」（没卡＝放行后/收工后/status 无卡可看）。 */
    val card: PublishCard?,
    /** 非 null ＝ 这一趟被规则挡下（上一张还没发布），页面一个字段都没动。 */
    val blocked: String?,
    val summary: String,
) {
    fun toJson(): String = JSONObject()
        .put("mode", "batch")
        .put("sessionId", sessionId)
        .put("action", action)
        .put("template", templateName)
        .put("submitted", false)
        .put("progress", progress)
        .put("total", total)
        .put("cardsOut", cardsOut)
        .put("finished", finished)
        .put("card", card?.let { cardJson(it) } ?: JSONObject.NULL)
        .put("blocked", blocked ?: JSONObject.NULL)
        .put("summary", summary)
        .put("note", note())
        .toString()

    /** 人话一句（回执尾部；卡片级原因在 `card.notes` 里，这里不重复）。 */
    private fun note(): String = buildString {
        when {
            blocked != null -> {
                append(blocked)
                append("——这一张的卡还挂着，下一张没动（你不说放行，抱朴不填第二张）。")
            }

            card != null -> {
                append("已填出「").append(card.title).append("」：")
                append(if (card.ok) "每格回读一致" else "有 ${card.badRows.size} 格回读没过，先看红行")
                append("。创建/发布这一步你自己点，点过之后再让我 next。")
            }

            finished -> append("全部 ").append(total).append(" 张都出过卡，页面没再动。").append(summary)

            action == "advance" ->
                append("已放行，现在可以填下一张（").append(progress).append("）。")

            else -> append(progress).append("；这一趟没填新卡（只看不碰页面）。").append(summary)
        }
    }

    companion object {
        /**
         * 卡面（派工单 §1：**只有「复制全文」「打开页面」两个动作**——回执里把这条写死，
         * 模型照着念给用户时才念得出来；渲染在 `FormFillSheet`，230/235 的地盘）。
         */
        private fun cardJson(c: PublishCard): JSONObject = JSONObject()
            .put("title", c.title)
            .put("ok", c.ok)
            .put("rows", JSONArray().also { arr ->
                c.rows.forEach {
                    arr.put(
                        JSONObject()
                            .put("label", it.label)
                            .put("value", it.value)
                            .put("ok", it.ok)
                            .put("reason", it.reason ?: JSONObject.NULL),
                    )
                }
            })
            .put("shotPath", c.shotPath ?: JSONObject.NULL)
            .put("openUrl", c.openUrl)
            .put("copyText", c.copyText)
            .put("notes", JSONArray(c.notes))
            .put("actions", jsonArrayOf("复制全文", "打开页面"))
    }
}

/**
 * 批量分支的执行体（第二百零一批 ①）。四个动作，**一个都不点提交**：
 * `start`（现扫当前页成模板＋排队＋填第一张）→ `next`（填下一张）→ `advance`（人已点过创建，放行）
 * → `status`（只看不动页面）。
 *
 * 「一次只填一张」不在这里判：那是 [FormBatch.next] 的 `Blocked`（pending 非空就不许过），
 * 本类只负责把它翻译成回执。`advance` 必须由模型单独调一次 ⇒ 配合 `ToolPermission.CONFIRM`，
 * 一张卡一次批准＝逐张核对。
 *
 * [shot] 是注入点：生产默认截玄览当前页，JVM 单测要能证明「每张卡带的是**自己那张**截图、
 * 第二张不覆盖第一张」（负例 6），而截图像素这一拍由真机验收覆盖。
 */
internal class FormBatchRunner(
    private val dom: FormDom,
    private val workspace: File?,
    private val pageUrl: () -> String?,
    private val shot: (cardName: String, index: Int) -> String? = { _, _ -> TeachForm.browserShot() },
) {

    fun run(batch: JSONObject): ToolOutcome {
        // SchemaCheck 只查顶层（不递归进嵌套对象）⇒ batch 里的键在这里自己守一遍：
        // 拼错的键不许被静吃掉（静吃掉就等于「你以为排了队，其实一张都没排」）。
        unknownKey(batch)?.let { return ToolOutcome.Failed(it) }
        val action = batch.optString(ARG_ACTION).trim().lowercase()
        return when (action) {
            "start" -> start(batch)
            "next" -> step(batch)
            "advance" -> advance(batch)
            "status" -> status(batch)
            else -> ToolOutcome.Failed(ACTION_HINT)
        }
    }

    private fun unknownKey(batch: JSONObject): String? {
        val unknown = batch.keys().asSequence().filter { it !in ALLOWED_KEYS }.sorted().toList()
        return if (unknown.isEmpty()) null else
            "batch 里有不认识的键：${unknown.joinToString("、")}（只认 ${ALLOWED_KEYS.sorted().joinToString("、")}）"
    }

    /** start：现扫 → 模板 → 排队 → 立刻填第一张（不填第一张的 start 等于让模型再猜一次键名）。 */
    private fun start(batch: JSONObject): ToolOutcome {
        val workspace = workspace ?: return ToolOutcome.Failed(NO_WORKSPACE)
        val candidates = dom.scan()
            ?: return ToolOutcome.Failed("没读到页面（玄览没开／没有标签页／还没加载完）——模板要从当前页现扫，先把要填的那一页打开")
        val scan = TeachFormRules.buildFields(candidates)
        if (scan.fields.isEmpty() && scan.blocks.isEmpty()) {
            return ToolOutcome.Failed(
                "这一页没有能填的字段。" +
                    if (scan.excluded.isEmpty()) ""
                    else "被排除的有：${scan.excluded.joinToString("、") { "${it.label}（${it.reason}）" }}",
            )
        }
        val cards = batch.optJSONArray(ARG_CARDS)
            ?: return ToolOutcome.Failed("start 要给 batch.cards：[{\"card\":\"名字\",\"values\":{\"字段\":\"值\"}}, …]")
        val url = pageUrl().orEmpty()
        val template = TeachFormRules.templateFrom(
            scan = scan,
            name = batch.optString(ARG_NAME).trim().ifBlank { "表单·" + (TeachSafety.hostOf(url) ?: "当前页") },
            startUrl = url,
            allowedDomains = TeachSafety.hostOf(url)?.let { listOf(it) } ?: emptyList(),
            nowMs = System.currentTimeMillis(),
        )
        val queue = TeachFormRules.parseCardsJson(cards.toString(), template)
        if (queue.isEmpty()) return ToolOutcome.Failed("batch.cards 里没有一张带值的卡（每张至少要有一个非空字段）")
        val session = FormBatchSessions.open(
            template, TeachForm.batch(workspace, template, queue, dom, shot),
        )
        return ok(session, "start", session.batch.next())
    }

    /** next：填下一张（上一张没放行 ⇒ Blocked，页面不动）。 */
    private fun step(batch: JSONObject): ToolOutcome {
        val session = require(batch) ?: return ToolOutcome.Failed(missingSession(batch))
        return ok(session, "next", session.batch.next())
    }

    /** advance：**用户已经在页面上点过创建/发布**（或明确说这张不要了）⇒ 放行下一张。 */
    private fun advance(batch: JSONObject): ToolOutcome {
        val session = require(batch) ?: return ToolOutcome.Failed(missingSession(batch))
        if (!session.batch.advance()) {
            return ToolOutcome.Failed(
                "现在没有「等着发布」的卡：这一张要么还没填（先 next），要么已经放行过了。" +
                    "（进度不确定就 action=status）",
            )
        }
        return ToolOutcome.Ok(report(session, "advance", null, null).toJson())
    }

    /** status：只读——不动页面、不填字段、不放行。 */
    private fun status(batch: JSONObject): ToolOutcome {
        val session = require(batch) ?: return ToolOutcome.Failed(missingSession(batch))
        return ToolOutcome.Ok(report(session, "status", session.batch.pending(), null).toJson())
    }

    /** [FormBatch.Step] → 回执（Blocked 时仍把还挂着的那张卡附上：用户要看的正是它）。 */
    private fun ok(session: FormBatchSession, action: String, step: FormBatch.Step): ToolOutcome = when (step) {
        is FormBatch.Step.Filled -> ToolOutcome.Ok(report(session, action, step.card, null).toJson())
        is FormBatch.Step.Blocked -> ToolOutcome.Ok(report(session, action, session.batch.pending(), step.reason).toJson())
        FormBatch.Step.Done -> ToolOutcome.Ok(report(session, action, null, null).toJson())
    }

    private fun report(
        session: FormBatchSession,
        action: String,
        card: PublishCard?,
        blocked: String?,
    ): FormBatchReport {
        val batch = session.batch
        return FormBatchReport(
            sessionId = session.id,
            action = action,
            templateName = session.template.name,
            progress = batch.progressText(),
            total = batch.total,
            cardsOut = batch.done.size,
            finished = batch.done.size >= batch.total,
            card = card,
            blocked = blocked,
            summary = batch.summary(),
        )
    }

    private fun require(batch: JSONObject): FormBatchSession? =
        FormBatchSessions.get(batch.optString(ARG_SESSION_ID).trim())

    private fun missingSession(batch: JSONObject): String {
        val id = batch.optString(ARG_SESSION_ID).trim()
        return if (id.isEmpty()) {
            "没给 batch.sessionId：$ACTION_HINT"
        } else {
            "没有这个批量会话「$id」（App 重启过，或已超出同时在飞上限）。" +
                "已出的卡都在 `产物/填表/` 里没丢；要接着填请重新 action=start 排一次队"
        }
    }

    companion object {
        private const val ARG_ACTION = "action"
        private const val ARG_CARDS = "cards"
        private const val ARG_NAME = "name"
        private const val ARG_SESSION_ID = "sessionId"

        /** `batch` 里认识的键（其余一律拒，见 [unknownKey]）。 */
        private val ALLOWED_KEYS: Set<String> = setOf(ARG_ACTION, ARG_SESSION_ID, ARG_NAME, ARG_CARDS)

        private const val ACTION_HINT =
            "batch.action 只认四个：start（现扫当前页排队，会填第一张）／next（填下一张，" +
                "要带 sessionId）／advance（用户已点过创建，放行下一张）／status（只看不动）"

        private const val NO_WORKSPACE =
            "批量要往工作区 `产物/填表/` 落每张卡的回读记录，装配层没把工作区交给本工具——" +
                "单发的 fields 分支不受影响"
    }
}

// ── ⑤ 工具面（schema ＋ 描述 ＋ 注册）────────────────────────────────────────

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
            "（选择器、前值→后值）与 needsHumanClick。" +
            "批量走 batch：action=start 现扫当前页排队并填第一张，之后一次只填一张（next），" +
            "用户在页面上点过创建之后才 advance 放行下一张；status 只看不碰页面。"

    private const val ARG_FIELDS = "fields"
    private const val ARG_SUBMIT = "submit"
    private const val ARG_ROLLBACK = "rollbackOnFailure"

    /** 批量分支（第二百零一批 ①）：`FormBatch` 的工具出口。 */
    private const val ARG_BATCH = "batch"

    /** 两个入口都没给时的一句话（schema 的 required 已放开，判据只能落在这里）。 */
    private const val FIELDS_OR_BATCH: String =
        "要么给 fields（{\"标签或name\":\"值\"}，一次填点名的几个字段），" +
            "要么给 batch（{\"action\":\"start\",…}，批量逐张）——两个都没给"

    /** `submit=true` 时的拒绝文案（工具层最后一道：连「代点」这个念头都在这里掐掉）。 */
    const val SUBMIT_REFUSAL: String =
        "form_fill 不做提交：它只填字段。提交/保存/登录/支付这一步必须由用户在页面上自己点" +
            "（把填好的页面留着，告诉用户点哪个按钮）。"

    /**
     * 生产装配：默认接玄览当前标签（`BrowserFormDom` 是 201 的端口实现，不新开桥）。
     *
     * [workspace] 只有批量分支要——每张卡的逐字段回读要落 `产物/填表/`；注册表把它传进来，
     * 单测给临时目录。拿不到工作区时批量明确拒绝（不许假装落过盘），单发的 `fields` 不受影响。
     * [pageUrl] 是模板的 `startUrl` 来源：页面事实，不猜。
     */
    fun spec(
        dom: () -> FormDom = { BrowserFormDom(null) },
        workspace: () -> File? = { null },
        pageUrl: () -> String? = { BaopuBrowser.require()?.pageInfo()?.url },
    ): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_FIELDS to jsonObjectOf(
                    "type" to "object",
                    "description" to "要填的字段：{\"标签或name\":\"值\"}。只填这里点名的字段，页面上其余字段不碰（与 batch 二选一）",
                ),
                ARG_BATCH to jsonObjectOf(
                    "type" to "object",
                    "description" to "批量逐张填：{\"action\":\"start|next|advance|status\",\"sessionId\":\"start 回执里的 id\"," +
                        "\"name\":\"模板名（可省）\",\"cards\":[{\"card\":\"卡名\",\"values\":{\"字段键\":\"值\"}}]}。" +
                        "start 现扫当前页成模板并立刻填第一张；一次只填一张，advance 代表用户已经在页面上点过创建",
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
            // required 放开（第二百零一批 ①）：fields 与 batch 二选一，这种「至少给一个」
            // SchemaCheck 表达不了，只能在执行体里判（FIELDS_OR_BATCH）。
            // 空数组 + REQUIRED_EMPTY_OK 登记是 94/119 批定的口径（同 `ask_user` 的两套形状），
            // 不是漏写必填——少了这一段 S4/S5 门禁会红。
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.BROWSER_MS,
        riskTier = "R2",
        readOnly = false,
    ) { args -> execute(args, dom(), workspace(), pageUrl) }

    /** 执行体（JVM 直测：单元测试传假 [FormDom]，不碰 WebView）。 */
    fun execute(
        args: JSONObject,
        dom: FormDom,
        workspace: File? = null,
        pageUrl: () -> String? = { BaopuBrowser.require()?.pageInfo()?.url },
        shot: (cardName: String, index: Int) -> String? = { _, _ -> TeachForm.browserShot() },
    ): ToolOutcome {
        if (args.optBoolean(ARG_SUBMIT, false)) return ToolOutcome.Rejected(SUBMIT_REFUSAL)
        if (args.has(ARG_BATCH)) {
            val batch = args.optJSONObject(ARG_BATCH)
                ?: return ToolOutcome.Failed("batch 必须是对象：{\"action\":\"start\",\"cards\":[…]}")
            return FormBatchRunner(dom, workspace, pageUrl, shot).run(batch)
        }
        val obj = args.optJSONObject(ARG_FIELDS)
            ?: return ToolOutcome.Failed(
                if (args.has(ARG_FIELDS)) "fields 必须是对象：{\"标签或name\":\"值\"}" else FIELDS_OR_BATCH,
            )
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
