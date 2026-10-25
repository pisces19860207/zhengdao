// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百批 · 技能参数化：通用底座的最后一块。
//
// 一句话：**别把「北京」写死进技能**——录一遍，把「北京」从步骤里拔出来变成
// `${direction}`；明天说「查天津」，同一条技能换参数再跑。
//
// 五件事，全在这个文件里「算得清」（DOM/落盘/UI 一律走端口，本文件不碰它们）：
// 1. [collect]        录完之后按来源列候选（URL query/path、输入值、下拉项；页面固定文本列但不可勾）
// 2. [parameterize]   勾了才参数化：把字面量**从步骤里拔掉**换成占位符（否则等于没参数化）
// 3. [parse]          执行期从一句话里取值（相对日期当场换算成具体日期并显示换成了哪天）
// 4. [applyValues]    取值填回占位符（填不满 ⇒ 绝不下发，绝不带 `${day}` 去请求）
// 5. [TeachParamsEditor] 编辑器只做四件事：改参数名/改默认值/删参数（回退字面量）/调换步骤顺序
//
// 四条硬口径（负例 1/2/3/4/5 的靶子）：
// - 字面量拔干净：[residualLiterals] 解析 JSON 断言，执行面里一个字都不许剩；
// - 不许猜值：没有词表、形状对不上、两处都能取 ⇒ 反问一行，绝不选第一个；
// - 默认值不静默生效：[parse] 永远不把 [TeachParam.default] 塞进 values，只作为反问里的「提议」；
// - 参数值不进日志：[redact] 把审计行/取证摘要里的值换回占位符。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ── 候选（录完之后「哪些字能变成参数」）────────────────────────────────────────

/**
 * 一条候选。**只在内存与界面里活**：
 * [example] 是示教时的实际值，落盘前就会被占位符替掉，技能 JSON 里永远不出现它。
 */
data class TeachParamCandidate(
    /** 建议参数名（英文小写；用户可改）。 */
    val name: String,
    /** 给人看的标签（表单标签/列名/占位提示；反问一句时用它说话）。 */
    val label: String,
    /** 示教时的实际值——**不落盘**。 */
    val example: String,
    val source: TeachParamSource,
    val shape: TeachParamShape,
    val slots: List<TeachSlot>,
    /** 勾选时默认帮我勾上吗（派工单 §1 那张表）。 */
    val defaultSelected: Boolean,
    /** 非空 ⇒ 只能看不能勾（页面固定文本＝结构锚，替换掉定位就断了）。 */
    val blockReason: String? = null,
    val options: List<TeachOption> = emptyList(),
) {
    val selectable: Boolean get() = blockReason == null

    /** 人可读一行（编辑器候选行）。 */
    fun describe(): String = buildString {
        append(name).append(" ← 「").append(example).append("」")
        append("（").append(source.wire).append("/").append(shape.wire).append("）")
        append(" · ").append(slots.joinToString("、") { it.describe() })
        if (options.isNotEmpty()) append(" · 可选 ").append(options.take(6).joinToString("/") { it.label })
        blockReason?.let { append(" · ").append(it) }
    }
}

/** 用户勾定的一条参数化指令（勾选＋改名＋默认值之后的产物）。 */
data class TeachParamPick(
    val name: String,
    val label: String,
    val example: String,
    val source: TeachParamSource,
    val shape: TeachParamShape,
    val slots: List<TeachSlot>,
    val default: String? = null,
    val options: List<TeachOption> = emptyList(),
)

/** 参数化结果：skill 非空＝成功（步骤里已经换成占位符）。 */
data class ParameterizeResult(
    val skill: TeachSkill?,
    val errors: List<String>,
    val applied: List<String> = emptyList(),
) {
    val ok: Boolean get() = skill != null
}

// ── 执行期取值结果 ─────────────────────────────────────────────────────────────

/** 一个取到的参数（[note] 用来在回答里显示「昨天 → 2026-10-24」这种换算）。 */
data class TeachParamValue(
    val name: String,
    val label: String,
    /** 用户原话里的那一截（原来长什么样）。 */
    val spoken: String,
    /** 真正填进技能的値。 */
    val value: String,
    /** 换算说明（相对日期必填；其余 null）。 */
    val note: String? = null,
) {
    /** 回答里的一行。 */
    fun answerLine(): String =
        if (note != null) "$label：$note" else "$label：$value"
}

/** 从一句话里取参数的结果。 */
sealed class TeachParamParse {
    /** 全取到了：[values] 是「参数名 → 值」。 */
    data class Ok(
        val values: Map<String, String>,
        val display: List<TeachParamValue>,
    ) : TeachParamParse()

    /**
     * 取不到 ⇒ **只反问一行**。不给值、不执行、不拿默认值顶。
     * [offered] 是给界面用的「默认值提议」（用户点了才算确认，仍然要显式一次）。
     */
    data class Ask(
        val line: String,
        val missing: List<String>,
        val offered: Map<String, String?> = emptyMap(),
    ) : TeachParamParse()
}

// ── 主体 ──────────────────────────────────────────────────────────────────────

internal object TeachParams {

    /** query 参数名/标签抽取的上限（防止一页 40 个 query 把界面刷爆）。 */
    const val CANDIDATE_MAX = 24

    /** 页面固定文本候选上限（结构锚只是「列给你看」，不该喧宾夺主）。 */
    const val PAGE_TEXT_MAX = 6

    /** 反问一句里列几个选项。 */
    const val ASK_OPTIONS_MAX = 6

    // ══ ① 候选识别 ═══════════════════════════════════════════════════════════

    /**
     * 录完一遍 → 列出「可以变成参数」的值。规则（派工单 §1 那张表）：
     *
     * | 来源 | 默认勾 | 说明 |
     * |---|---|---|
     * | 输入框里填的值 | 是 | 最可靠：值就是用户亲手打的 |
     * | 下拉/单选的选中项 | 是 | 顺带把选项表存成词表（反问与取值校验都用它） |
     * | URL 的 query 值 | 是 | 若同一个字面量已经被输入类认领，只并入落点，不另立参数 |
     * | URL 的 path 段 | 是 | 只认「长得像数据」的段（纯数字/日期/字母数字混） |
     * | 页面上的固定文本 | 否 | 多半是结构锚：列出来，但**不许勾**（替换掉定位就断了） |
     *
     * 合并口径：**同一个字面量只变成一个参数**（占位替换是按值拔的，两个参数共用一个值
     * 会互相踩）。所以 `?direction=北京` 与输入框里的「北京」合成一条，落点相加。
     */
    fun collect(skill: TeachSkill): List<TeachParamCandidate> {
        val out = mutableListOf<TeachParamCandidate>()
        val byLiteral = LinkedHashMap<String, Int>()

        fun put(c: TeachParamCandidate) {
            if (c.example.isBlank()) return
            val idx = byLiteral[c.example]
            if (idx == null) {
                if (out.size >= CANDIDATE_MAX) return
                byLiteral[c.example] = out.size
                out += c
            } else {
                val prev = out[idx]
                // 输入/下拉是「值被亲手打过」的地方，比对 URL 更可信：换主来源
                val promote = prev.source == TeachParamSource.URL_QUERY &&
                    (c.source == TeachParamSource.INPUT || c.source == TeachParamSource.SELECT)
                val mergedSlots = (prev.slots + c.slots).distinctBy { it.step to it.field }
                out[idx] = if (promote) {
                    prev.copy(
                        name = c.name, label = c.label, source = c.source, shape = c.shape,
                        slots = mergedSlots, options = c.options, defaultSelected = c.defaultSelected,
                    )
                } else {
                    prev.copy(slots = mergedSlots, options = prev.options.ifEmpty { c.options })
                }
            }
        }

        // ① 输入/下拉（主来源）
        skill.steps.forEach { s ->
            val v = s.value?.takeIf { it.isNotBlank() } ?: return@forEach
            val kind = when (s.action) {
                TeachAction.TYPE -> TeachParamSource.INPUT
                TeachAction.SELECT -> TeachParamSource.SELECT
                else -> return@forEach
            }
            val slots = mutableListOf(TeachSlot(s.index, TeachSlotField.VALUE))
            // 输入类的 expect 一般就是「输入框里出现了刚打的值」——参数化后这条断言也得跟着换，
            // 否则回放时会去等「北京」出现（而这次填的是天津）
            if (s.expect.any { it.value == v }) slots += TeachSlot(s.index, TeachSlotField.EXPECT)
            put(
                TeachParamCandidate(
                    name = uniqueName(suggestName(s, kind), out.map { it.name }.toSet(), kind),
                    label = labelOf(s),
                    example = v,
                    source = kind,
                    shape = shapeOf(v, s.options),
                    slots = slots,
                    defaultSelected = true,
                    options = s.options,
                ),
            )
        }

        // ② URL 的 query / path（并入上面的字面量；新的另立）
        if (skill.startUrl.isNotBlank()) {
            urlCandidates(skill.startUrl, stepIndex = 0) { name, label, example, source, slot ->
                put(
                    TeachParamCandidate(
                        name = uniqueName(name, out.map { it.name }.toSet(), source),
                        label = label, example = example, source = source,
                        shape = shapeOf(example, emptyList()),
                        slots = listOf(slot), defaultSelected = true,
                    ),
                )
            }
        }
        skill.steps.forEach { s ->
            urlCandidates(s.url, stepIndex = s.index) { name, label, example, source, slot ->
                put(
                    TeachParamCandidate(
                        name = uniqueName(name, out.map { it.name }.toSet(), source),
                        label = label, example = example, source = source,
                        shape = shapeOf(example, emptyList()),
                        slots = listOf(slot), defaultSelected = true,
                    ),
                )
            }
        }

        // ③ 页面固定文本（列出来，但默认不勾、且不许勾）
        // 只看**点击步**：按钮/链接/表头才是「结构锚」；输入框的无障碍名是标签，不该混进来
        val claimed = byLiteral.keys
        val seenText = linkedSetOf<String>()
        for (s in skill.steps) {
            if (s.action != TeachAction.CLICK) continue
            val texts = s.locators.filter { it.kind == TeachLocatorKind.TEXT }.map { it.value } +
                listOfNotNull(s.snapshot.text, s.snapshot.name)
            for (t in texts) {
                val v = t.trim()
                if (v.isBlank() || v in claimed || v in seenText) continue
                if (seenText.size >= PAGE_TEXT_MAX) break
                seenText += v
                out += TeachParamCandidate(
                    name = uniqueName("text${out.size + 1}", out.map { it.name }.toSet(), TeachParamSource.PAGE_TEXT),
                    label = "页面文本",
                    example = v,
                    source = TeachParamSource.PAGE_TEXT,
                    shape = TeachParamShape.TEXT,
                    slots = s.locators.filter { it.kind == TeachLocatorKind.TEXT && it.value == v }
                        .map { TeachSlot(s.index, TeachSlotField.VALUE) },
                    defaultSelected = false,
                    blockReason = "这是定位文本（结构锚）——参数化会让这一步找不到元素，只能改技能重教",
                )
            }
        }
        return out
    }

    /** URL 里的 query 值 / path 数据段，逐个交给 [emit]（name/label/example/source/slot）。 */
    private fun urlCandidates(
        url: String,
        stepIndex: Int,
        emit: (name: String, label: String, example: String, source: TeachParamSource, slot: TeachSlot) -> Unit,
    ) {
        val slotField = if (stepIndex == 0) TeachSlotField.START_URL else TeachSlotField.URL
        val slot = TeachSlot(stepIndex, slotField)
        for ((k, v) in queryPairs(url)) {
            if (v.isBlank()) continue
            emit(safeName(k, "q"), k, v, TeachParamSource.URL_QUERY, slot)
        }
        for (seg in pathSegments(url)) {
            if (!looksLikeData(seg)) continue
            emit(safeName("path", "path"), "路径段", seg, TeachParamSource.URL_PATH, slot)
        }
    }

    /** 值的形状（执行期按形状选取值规则）。 */
    fun shapeOf(example: String, options: List<TeachOption>): TeachParamShape = when {
        options.isNotEmpty() -> TeachParamShape.ENUM
        isDateShaped(example) -> TeachParamShape.DATE
        example.all { it.isDigit() } && example.length <= 12 -> TeachParamShape.NUMBER
        example.any { it.isDigit() } && example.any { it.isLetter() } -> TeachParamShape.CODE
        else -> TeachParamShape.TEXT
    }

    /** 名字：优先输入框的 name 属性 → 占位提示/表单标签 → 序号兜底。 */
    private fun suggestName(step: TeachStep, source: TeachParamSource): String {
        step.locators.firstOrNull { it.kind == TeachLocatorKind.INPUT_NAME }?.let { loc ->
            val n = safeName(loc.value, "")
            if (n.isNotEmpty()) return n
        }
        val fromLabel = asciiName(labelOf(step))
        if (fromLabel.isNotEmpty()) return fromLabel
        return if (source == TeachParamSource.SELECT) "select" else "input"
    }

    private fun uniqueName(base: String, taken: Set<String>, source: TeachParamSource): String {
        val seed = base.ifBlank { if (source == TeachParamSource.SELECT) "select" else "p" }
        if (seed !in taken) return seed
        var i = 2
        while ("${seed}_$i" in taken) i++
        return "${seed}_$i"
    }

    /** 把任意键名折成合法参数名（`?direction` → `direction`；中文 → 空串，交给兜底）。 */
    fun safeName(raw: String, fallback: String): String {
        val s = raw.trim().lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9' || it == '_') it else '_' }
            .joinToString("")
            .trim('_')
            .replace(Regex("_+"), "_")
        val cut = s.take(32).trimEnd('_')
        return if (TeachParam.NAME_RE.matches(cut)) cut else fallback
    }

    /** 只要 ASCII 部分（中文标签里夹的英文键名）。 */
    private fun asciiName(raw: String): String = safeName(raw, "")

    /** 标签：表单标签/列名 → 占位提示 → 元素名 → 文本。 */
    fun labelOf(step: TeachStep): String {
        step.locators.firstOrNull { it.kind == TeachLocatorKind.FORM_CONTEXT }?.let { loc ->
            val label = loc.value.substringBefore('|').trim()
            if (label.isNotBlank()) return label
        }
        step.locators.firstOrNull { it.kind == TeachLocatorKind.PLACEHOLDER }?.let { loc ->
            if (loc.value.isNotBlank()) return loc.value.trim()
        }
        return (step.snapshot.name ?: step.snapshot.text ?: "").trim()
    }

    // ══ ② 参数化（把字面量从步骤里拔掉）═══════════════════════════════════════

    /**
     * 用户勾定之后：把每个 `example` 从**执行面**里连根拔掉，换成 `${name}`。
     *
     * 执行面＝步骤的 value / expect.value / 页面 URL / 起点 URL。
     * 定位面（locators/snapshot）**不许动**：那是「怎么找到这个元素」的身份，动了就找不到。
     * 因此：一个字面量如果同时出现在定位面里，这条不能参数化（只能改技能重教）——
     * 这一条正是负例 2「参数取错值」的最后一道闸。
     */
    fun parameterize(skill: TeachSkill, picks: List<TeachParamPick>): ParameterizeResult {
        val errors = mutableListOf<String>()
        if (picks.isEmpty()) return ParameterizeResult(null, listOf("没有勾任何参数——要不就别参数化"))

        val names = mutableSetOf<String>()
        skill.params.map { it.name }.forEach { names += it }
        val ordered = picks.sortedBy { it.name }
        for (p in ordered) {
            if (!TeachParam.NAME_RE.matches(p.name)) {
                errors += "参数名「${p.name}」不合法（英文小写开头，只能 [a-z0-9_]，最长 32）"
            } else if (!names.add(p.name)) {
                errors += "参数名「${p.name}」重复了（一个技能里参数名必须唯一）"
            }
            if (p.example.isBlank()) errors += "参数「${p.name}」没有示例值——没有值就没法拔"
            if (p.example.contains("\${")) errors += "参数「${p.name}」的示例值里已经带占位符，先别参数化"
            if (p.slots.isEmpty()) errors += "参数「${p.name}」没有落点（不知道该替换哪一步）"
        }
        if (errors.isNotEmpty()) return ParameterizeResult(null, errors)

        var startUrl = skill.startUrl
        var steps = skill.steps

        for (p in ordered) {
            val ph = placeholderOf(p.name)
            for (slot in p.slots.distinctBy { it.step to it.field }) {
                when (slot.field) {
                    TeachSlotField.START_URL -> startUrl = rewriteUrl(startUrl, p.example, ph)
                    TeachSlotField.URL -> steps = steps.map { if (it.index == slot.step) it.copy(url = rewriteUrl(it.url, p.example, ph)) else it }
                    TeachSlotField.VALUE -> {
                        val target = steps.firstOrNull { it.index == slot.step }
                        if (target == null) {
                            errors += "参数「${p.name}」指向第 ${slot.step} 步，但技能里没有这一步"
                        } else if (target.value == p.example) {
                            steps = steps.map { if (it.index == slot.step) it.copy(value = ph) else it }
                        } else if (target.value != ph) {
                            errors += "参数「${p.name}」要替换第 ${slot.step} 步的值，但那里现在是「${target.value ?: "空"}」不是「${p.example}」"
                        }
                    }
                    TeachSlotField.EXPECT -> {
                        steps = steps.map { s ->
                            if (s.index != slot.step) s
                            else s.copy(expect = s.expect.map { e -> if (e.value == p.example) e.copy(value = ph) else e })
                        }
                    }
                }
            }
            // 词表搬到参数上之后，步骤里不再驮一份（同一份数据只存一处；执行器本来也不读它）
            if (p.options.isNotEmpty()) {
                steps = steps.map { s -> if (p.slots.any { it.step == s.index }) s.copy(options = emptyList()) else s }
            }
        }

        // 拔干净自检：执行面里若还留着这个字面量 ⇒ 拒绝（宁可报错，不许「半参数化」）
        val interim = skill.copy(startUrl = startUrl, steps = steps)
        for (p in ordered) {
            val left = residualLiterals(interim, listOf(p.example))
            if (left.isNotEmpty()) {
                errors += "参数「${p.name}」没拔干净：${left.joinToString("；")}（这个值多半同时是定位文本）"
            }
        }
        if (errors.isNotEmpty()) return ParameterizeResult(null, errors)

        val newParams = skill.params + ordered.map { p ->
            TeachParam(
                name = p.name,
                label = p.label,
                source = p.source,
                shape = p.shape,
                slots = p.slots.distinctBy { it.step to it.field },
                default = p.default?.takeIf { it.isNotBlank() },
                options = p.options,
            )
        }
        val out = interim.copy(params = newParams)
        // 序列化再解析一遍：形态合法才算数（免得把半截对象交给落盘层）
        val round = TeachSkill.fromJson(out.toJsonText(), strict = true)
            ?: return ParameterizeResult(null, listOf("参数化后的技能不符合 zl-teach/1 形态，已拒绝"))
        return ParameterizeResult(round, emptyList(), ordered.map { it.name })
    }

    /**
     * 执行面里还剩哪些字面量（负例 1 的门禁入口；`单测`与「参数化自检」共用同一份口径）。
     *
     * 只看**会真的发出去/去等的东西**：起点 URL、每步 URL、步骤值、期望断言值。
     * 刻意不看 `params` 里的 default/options——那是用户明确写下的声明，不是残留。
     */
    fun residualLiterals(skill: TeachSkill, literals: Collection<String>): List<String> {
        val targets = literals.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return emptyList()
        val hits = mutableListOf<String>()
        val o = runCatching { JSONObject(skill.toJsonText()) }.getOrNull() ?: return listOf("技能 JSON 解析失败")
        fun check(where: String, text: String) {
            for (t in targets) if (text.contains(t)) hits += "「$t」@ $where"
        }
        check("startUrl", o.optString("startUrl"))
        val arr = o.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val n = s.optInt("i", i + 1)
            check("第 $n 步 value", s.optString("value"))
            check("第 $n 步 url", s.optString("url"))
            val ea = s.optJSONArray("expect") ?: JSONArray()
            for (j in 0 until ea.length()) {
                check("第 $n 步 expect", ea.optJSONObject(j)?.optString("value").orEmpty())
            }
        }
        return hits
    }

    // ══ ③ 执行期：从这句话里取参数 ═════════════════════════════════════════════

    /**
     * 一句话 → 参数值。顺序（先硬的、后软的，取不到就反问，**不猜**）：
     *
     * 0. 显式赋值：`方向=天津`／`方向：天津`（名字或标签，用户自己写死的，最可靠）
     * 1. 日期（只有 DATE 形状）：相对日期（今天/昨天/前天/N天前…）当场换算成具体日期
     * 2. 枚举（只有 ENUM，且有选项表）：句子里的选项词命中 ⇒ 取它的 value
     * 3. 编码（CODE）：字母数字混排的 token（单号、车牌）
     * 4. 数字（NUMBER）：纯数字 token
     * 5. 引号（TEXT）：`「」`／`""` 里的原话
     * 6. 还差 ⇒ [TeachParamParse.Ask] 一行反问
     *
     * 歧义一律反问：两处都能取、多个 token 不知给谁、多个日期参数只有一个日期 —— 全都不选第一个。
     */
    fun parse(
        params: List<TeachParam>,
        sentence: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TeachParamParse {
        if (params.isEmpty()) return TeachParamParse.Ok(emptyMap(), emptyList())
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

        var rest = sentence.trim()
        val values = LinkedHashMap<String, String>()
        val display = mutableListOf<TeachParamValue>()
        val conflicts = mutableListOf<String>()

        // ── 0. 显式赋值：名字/标签 = 值 ──
        for (p in params) {
            val keys = listOf(p.name, p.label).filter { it.isNotBlank() }.distinct()
            for (k in keys) {
                val re = Regex("${Regex.escape(k)}\\s*[=＝:：]\\s*([^\\s，。；、,;]{1,32})")
                val m = re.find(rest) ?: continue
                values[p.name] = m.groupValues[1].trim()
                display += TeachParamValue(p.name, labelOf(p), m.value.trim(), values[p.name]!!, null)
                rest = rest.removeRange(m.range)
                break
            }
        }

        // ── 1. 日期 ──
        val dateParams = params.filter { it.shape == TeachParamShape.DATE && it.name !in values }
        if (dateParams.isNotEmpty()) {
            val found = dateExpressions(rest, today)
            when {
                found.isEmpty() -> Unit
                found.size > 1 -> conflicts += "这句话里有 ${found.size} 个日期，不知道该用哪个"
                dateParams.size > 1 -> conflicts += "技能里有 ${dateParams.size} 个日期参数，一句话只给了一个日期，不知道该填哪个"
                else -> {
                    val p = dateParams.single()
                    val d = found.single()
                    values[p.name] = d.iso
                    display += TeachParamValue(
                        name = p.name,
                        label = labelOf(p),
                        spoken = d.raw,
                        value = d.iso,
                        note = "${d.raw} → ${d.iso}",
                    )
                    rest = rest.removeRange(d.range)
                }
            }
        }

        // ── 2. 枚举（选项表就是词表）──
        val enumParams = params.filter { it.shape == TeachParamShape.ENUM && it.name !in values && it.options.isNotEmpty() }
        if (enumParams.isNotEmpty()) {
            data class Hit(val p: TeachParam, val opt: TeachOption, val start: Int, val len: Int, val matched: String)
            val hits = mutableListOf<Hit>()
            for (p in enumParams) {
                for (opt in p.options) {
                    for (w in listOf(opt.label, opt.value).filter { it.isNotBlank() }.distinct()) {
                        if (w.length < 2) continue // 单字选项太容易误命中（「是」「否」）
                        val at = rest.indexOf(w)
                        if (at >= 0) hits += Hit(p, opt, at, w.length, w)
                    }
                }
            }
            val best = hits.sortedWith(compareByDescending<Hit> { it.len }.thenBy { it.start })
            val claimed = mutableListOf<IntRange>()
            for (h in best) {
                val range = h.start until (h.start + h.len)
                if (h.p.name in values) continue
                if (claimed.any { r -> r.first <= range.first && range.last <= r.last }) continue
                values[h.p.name] = h.opt.value
                display += TeachParamValue(h.p.name, labelOf(h.p), h.matched, h.opt.value, null)
                claimed += range
            }
            // 同一段话被两个枚举参数同时认领 ⇒ 分不清，反问（绝不选一个）
            val claimedTwice = hits.groupBy { it.start }.filterValues { it.map { h -> h.p.name }.distinct().size > 1 }
            if (claimedTwice.isNotEmpty()) {
                claimedTwice.values.first().map { it.p.name }.distinct().let {
                    conflicts += "这一段话里「${claimedTwice.values.first().first().matched}」可能属于 ${it.joinToString("、")} 两个参数"
                }
            }
            // 认领过的字要从事后文本里挖掉（先记后删：边删边算索引会错位）
            for (r in claimed.sortedByDescending { it.first }) rest = rest.removeRange(r)
        }

        // ── 3. 编码 / 4. 数字 / 5. 引号 ──
        for (shape in listOf(TeachParamShape.CODE, TeachParamShape.NUMBER, TeachParamShape.TEXT)) {
            val pending = params.filter { it.shape == shape && it.name !in values }
            if (pending.isEmpty()) continue
            val tokens = when (shape) {
                TeachParamShape.CODE -> codeTokens(rest)
                TeachParamShape.NUMBER -> numberTokens(rest)
                else -> quotedTokens(rest)
            }
            when {
                tokens.isEmpty() -> Unit
                tokens.size > 1 -> conflicts += "${labelOf(pending.first())}给了 ${tokens.size} 个候选（${tokens.joinToString("/") { it } }），不知道该用哪个"
                pending.size > 1 -> conflicts += "${pending.joinToString("、") { labelOf(it) }} 都是${shapeName(shape)}，不知道该给哪个填「${tokens.single()}」"
                else -> {
                    val p = pending.single()
                    val tok = tokens.single()
                    values[p.name] = tok
                    display += TeachParamValue(p.name, labelOf(p), tok, tok, null)
                    rest = rest.replace(tok, " ")
                }
            }
        }

        // ── 6. 还差就反问一行（冲突优先级最高）──
        val missing = params.filter { it.name !in values }
        if (conflicts.isNotEmpty()) {
            return TeachParamParse.Ask(
                line = singleLine(conflicts.first()),
                missing = missing.map { it.name },
                offered = missing.associate { it.name to it.default },
            )
        }
        if (missing.isNotEmpty()) {
            return TeachParamParse.Ask(
                line = askLine(missing),
                missing = missing.map { it.name },
                offered = missing.associate { it.name to it.default },
            )
        }
        return TeachParamParse.Ok(values, display)
    }

    private fun labelOf(p: TeachParam): String = p.label.ifBlank { p.name }

    private fun shapeName(shape: TeachParamShape): String = when (shape) {
        TeachParamShape.DATE -> "日期"
        TeachParamShape.CODE -> "单号/编码"
        TeachParamShape.NUMBER -> "数字"
        TeachParamShape.ENUM -> "选项"
        TeachParamShape.TEXT -> "文本"
    }

    /**
     * 反问：**只一行**。有选项就报选项，有默认值只说「提议」（要不要用由用户再说一句）。
     * 派工单 §2 的原型：「查哪个方向？（可选：北京/天津…）」
     */
    fun askLine(missing: List<TeachParam>): String {
        if (missing.isEmpty()) return ""
        if (missing.size == 1) {
            val p = missing.single()
            val label = labelOf(p)
            val opts = if (p.options.isEmpty()) {
                ""
            } else {
                val more = if (p.options.size > ASK_OPTIONS_MAX) "…" else ""
                "（可选：${p.options.take(ASK_OPTIONS_MAX).joinToString("/") { it.label }}$more）"
            }
            val dft = p.default?.let { "（默认 $it 不算数，要用就说「就用$it」）" } ?: ""
            val head = if (p.options.isNotEmpty()) "「$label」要哪个？" else "「$label」用什么值？"
            return singleLine(head + opts + dft)
        }
        val names = missing.take(4).joinToString("、") { "「${labelOf(it)}」" }
        val more = if (missing.size > 4) " 等 ${missing.size} 项" else ""
        val first = missing.first()
        val opts = if (first.options.isNotEmpty()) {
            "（${labelOf(first)}可选：${first.options.take(ASK_OPTIONS_MAX).joinToString("/") { it.label }}）"
        } else {
            ""
        }
        return singleLine("还差 $names$more——分别是什么？$opts")
    }

    /** 无论如何压成一行（反问必须只有一行：多行会被界面拆成「追问轰炸」）。 */
    private fun singleLine(s: String): String =
        s.replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\s{2,}"), " ").trim()

    // ── 日期 ──

    internal data class DateHit(val raw: String, val iso: String, val range: IntRange)

    /** 相对日期词（只做「天」这一族；「上周」这类需要周起始口径，宁可反问）。 */
    private val RELATIVE_DAYS: List<Pair<String, Long>> = listOf(
        "大前天" to -3L, "大后天" to 3L,
        "前天" to -2L, "后天" to 2L,
        "昨天" to -1L, "昨日" to -1L,
        "今天" to 0L, "今日" to 0L,
        "明天" to 1L, "明日" to 1L,
    )

    private val ISO_DATE = Regex("\\d{4}-\\d{1,2}-\\d{1,2}")
    private val SLASH_DATE = Regex("\\d{4}/\\d{1,2}/\\d{1,2}")
    private val CN_DATE = Regex("\\d{1,2}月\\d{1,2}[日号]")
    private val N_DAYS_AGO = Regex("(\\d{1,3})\\s*天\\s*(前|后)")

    private fun isDateShaped(v: String): Boolean =
        ISO_DATE.matches(v) || SLASH_DATE.matches(v) || CN_DATE.matches(v)

    /**
     * 找出句子里所有「日期表达」并换算成 ISO（相对日期靠 [today] 推）。
     * 返回多个 ⇒ 调用方必须反问（不许挑第一个）。
     */
    internal fun dateExpressions(text: String, today: LocalDate): List<DateHit> {
        val out = mutableListOf<DateHit>()
        fun add(raw: String, date: LocalDate, start: Int) {
            val iso = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            out += DateHit(raw, iso, start until (start + raw.length))
        }
        // 先长后短：大前天 在 前天 之前，避免「大前天」被「前天」吃掉
        for ((word, delta) in RELATIVE_DAYS.sortedByDescending { it.first.length }) {
            val at = text.indexOf(word)
            if (at >= 0) add(word, today.plusDays(delta), at)
        }
        for (m in N_DAYS_AGO.findAll(text)) {
            val n = m.groupValues[1].toLongOrNull() ?: continue
            val sign = if (m.groupValues[2] == "前") -1L else 1L
            add(m.value, today.plusDays(sign * n), m.range.first)
        }
        for (m in ISO_DATE.findAll(text)) parseIso(m.value)?.let { add(m.value, it, m.range.first) }
        for (m in SLASH_DATE.findAll(text)) parseIso(m.value.replace('/', '-'))?.let { add(m.value, it, m.range.first) }
        for (m in CN_DATE.findAll(text)) {
            val parts = Regex("(\\d{1,2})月(\\d{1,2})[日号]").find(m.value) ?: continue
            val mo = parts.groupValues[1].toIntOrNull() ?: continue
            val d = parts.groupValues[2].toIntOrNull() ?: continue
            runCatching { LocalDate.of(today.year, mo, d) }.getOrNull()?.let { add(m.value, it, m.range.first) }
        }
        // 去重叠：同一段只留最长的那条
        return out.sortedByDescending { it.range.last - it.range.first }
            .fold(mutableListOf<DateHit>()) { acc, h ->
                if (acc.none { it.range.first <= h.range.last && h.range.first <= it.range.last }) acc += h
                acc
            }
    }

    private fun parseIso(s: String): LocalDate? = runCatching {
        val (y, m, d) = s.split('-').map { it.toInt() }
        LocalDate.of(y, m, d)
    }.getOrNull()

    // ── token ──

    /** 编码型 token（单号/车牌）：字母数字混排，排除日期与纯数字。 */
    private fun codeTokens(text: String): List<String> {
        val dates = dateLikeSpans(text).map { text.substring(it.first, it.last + 1) }.toSet()
        val all = linkedSetOf<String>()
        for (m in Regex("[\\u4e00-\\u9fa5][A-Za-z][A-Za-z0-9]{4,6}").findAll(text)) all += m.value
        for (m in Regex("[A-Za-z0-9][A-Za-z0-9\\-_]{3,19}").findAll(text)) {
            val t = m.value
            if (t in dates || t.all { it.isDigit() }) continue
            if (dates.any { d -> d.isNotEmpty() && (t.startsWith(d) || d.startsWith(t)) }) continue
            if (t.any { it.isDigit() } && t.any { it.isLetter() }) all += t
        }
        // 去重叠：`冀A12345` 与它内部的 `A12345` 是同一个东西，只能算一个候选（否则「不知给谁」）
        val kept = mutableListOf<String>()
        for (t in all.sortedByDescending { it.length }) {
            if (kept.none { it.contains(t) }) kept += t
        }
        return kept
    }

    /** 数字型 token（排除日期里的数）。 */
    private fun numberTokens(text: String): List<String> {
        val masked = maskDates(text)
        val out = linkedSetOf<String>()
        for (m in Regex("\\d{1,12}").findAll(masked)) out += m.value
        return out.toList()
    }

    /** 日期样子的片段（ISO/斜杠/中文日期/N天前）。 */
    private fun dateLikeSpans(text: String): List<IntRange> =
        (ISO_DATE.findAll(text).map { it.range } +
            SLASH_DATE.findAll(text).map { it.range } +
            CN_DATE.findAll(text).map { it.range } +
            N_DAYS_AGO.findAll(text).map { it.range }).toList()

    /** 把日期片段涂成空格（数字/编码抽取时不至于把 2026-10-24 当成三个数字）。 */
    private fun maskDates(text: String): String {
        val chars = text.toCharArray()
        for (r in dateLikeSpans(text)) for (i in r) if (i in chars.indices) chars[i] = ' '
        return String(chars)
    }

    /** 引号里的原话（中英文引号都算）。 */
    private fun quotedTokens(text: String): List<String> {
        val out = linkedSetOf<String>()
        for (m in Regex("「([^」]{1,32})」|『([^』]{1,32})』|《([^》]{1,32})》|\"([^\"]{1,32})\"|“([^”]{1,32})”").findAll(text)) {
            val v = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: continue
            out += v
        }
        return out.toList()
    }

    /**
     * 执行前的一次性计划（纯函数，JVM 直测）：这句话能不能跑、跑之前要不要先导航。
     * 三种结局：反问一行 / 停下说清原因 / 可以跑（带填好值的技能）。
     */
    sealed class TeachRunPlan {
        data class Ask(val line: String, val missing: List<String>) : TeachRunPlan()

        /** 结构性问题（占位没值、起点跳域、手改坏了）——一句话说清，不动页面。 */
        data class Stop(val reason: String) : TeachRunPlan()

        data class Ready(
            val skill: TeachSkill,
            val values: Map<String, String>,
            val display: List<TeachParamValue>,
            /** 非空 ⇒ 开跑前先导航到这儿（起点 URL 里的参数变了）。 */
            val navigateTo: String? = null,
        ) : TeachRunPlan()
    }

    /** 这句话 → 计划。取不到就反问；取到了才允许动页面。 */
    fun plan(
        skill: TeachSkill,
        sentence: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TeachRunPlan {
        val declared = skill.params
        val phs = placeholders(skill)
        if (declared.isEmpty()) {
            return if (phs.isEmpty()) {
                TeachRunPlan.Ready(skill, emptyMap(), emptyList())
            } else {
                TeachRunPlan.Stop("技能里有占位符 ${phs.joinToString("、") { "\${$it}" }} 但没有参数声明（手改坏了），先重新参数化")
            }
        }
        when (val parsed = parse(declared, sentence, nowMs, zone)) {
            is TeachParamParse.Ask -> return TeachRunPlan.Ask(parsed.line, parsed.missing)
            is TeachParamParse.Ok -> {
                val applied = applyValues(skill, parsed.values)
                    ?: return TeachRunPlan.Stop(
                        "参数没取全（${unresolvedPlaceholders(skill, parsed.values).joinToString("、")}），没开跑——不会带占位符去请求",
                    )
                // 起点 URL 变了 ⇒ 先过授权域再导航（绝不把人带到域外）
                val nav = applied.startUrl.takeIf { it.isNotBlank() && it != skill.startUrl }
                if (nav != null && !TeachSafety.domainAllowed(nav, skill.allowedDomains)) {
                    return TeachRunPlan.Stop("换参数后的起点 URL 不在授权域（${TeachSafety.hostOf(nav) ?: "?"}），停下")
                }
                return TeachRunPlan.Ready(applied, parsed.values, parsed.display, nav)
            }
        }
    }

    // ══ ④ 填回占位符 ═══════════════════════════════════════════════════════════
    /**
     * 把值填回技能里的 `${name}`。**填不满 ⇒ null**（绝不把 `${day}` 原样发去请求）。
     */
    fun applyValues(skill: TeachSkill, values: Map<String, String>): TeachSkill? {
        val unresolved = unresolvedPlaceholders(skill, values)
        if (unresolved.isNotEmpty()) return null
        fun sub(s: String): String {
            var out = s
            for ((k, v) in values) out = out.replace(placeholderOf(k), v)
            return out
        }
        return skill.copy(
            startUrl = sub(skill.startUrl),
            steps = skill.steps.map { s ->
                s.copy(
                    url = sub(s.url),
                    value = s.value?.let { sub(it) },
                    expect = s.expect.map { e -> e.value?.let { e.copy(value = sub(it)) } ?: e },
                )
            },
        )
    }

    /** 技能里所有占位名（含起点 URL／每步 URL／步骤值／断言值）。 */
    fun placeholders(skill: TeachSkill): Set<String> {
        val out = linkedSetOf<String>()
        out += placeholderNamesIn(skill.startUrl)
        skill.steps.forEach { s ->
            s.url.let { out += placeholderNamesIn(it) }
            s.value?.let { out += placeholderNamesIn(it) }
            s.expect.forEach { e -> e.value?.let { out += placeholderNamesIn(it) } }
        }
        return out
    }

    /** 哪些占位没有值（非空 ⇒ 不许执行）。 */
    fun unresolvedPlaceholders(skill: TeachSkill, values: Map<String, String>): List<String> =
        placeholders(skill).filter { values[it].isNullOrBlank() }.toList()

    // ══ ⑤ 参数值不进日志 ═══════════════════════════════════════════════════════

    /**
     * 把文本里的参数值换回占位符（审计行／取证摘要／RunLog 共用一个口径）。
     * 长的先替（防止「冀A1234」先命中把「冀A12345」切碎）。
     */
    fun redact(text: String, values: Map<String, String>): String {
        if (text.isEmpty() || values.isEmpty()) return text
        var out = text
        for ((k, v) in values.entries.filter { it.value.isNotBlank() }.sortedByDescending { it.value.length }) {
            out = out.replace(v, placeholderOf(k))
        }
        return out
    }

    // ══ ⑥ 编辑器（只做四件事；拖拽/条件/循环明确不做）═════════════════════════

    /**
     * 编辑器**唯一**能表达的四种操作。这个 sealed 集合就是能力边界：
     * 没有「拖成树」「加条件」「加循环」的表达方式，所以做不出来（负例 6 的结构面）。
     */
    sealed class TeachEditOp {
        /** 改参数名（占位符一起改）。 */
        data class RenameParam(val old: String, val new: String) : TeachEditOp()

        /** 改/清默认值。 */
        data class SetDefault(val name: String, val value: String?) : TeachEditOp()

        /** 删参数 ⇒ 回退成字面量（用它的默认值；没默认值就不许删）。 */
        data class DeleteParam(val name: String) : TeachEditOp()

        /** 调换步骤顺序（[from]/[to] 都是 1 起的步号）。 */
        data class MoveStep(val from: Int, val to: Int) : TeachEditOp()
    }

    /** 编辑结果：skill 非空＝成功。 */
    data class TeachEditResult(val skill: TeachSkill?, val error: String?) {
        val ok: Boolean get() = skill != null
    }

    object Editor {

        fun apply(skill: TeachSkill, op: TeachEditOp): TeachEditResult = when (op) {
            is TeachEditOp.RenameParam -> rename(skill, op.old, op.new)
            is TeachEditOp.SetDefault -> setDefault(skill, op.name, op.value)
            is TeachEditOp.DeleteParam -> delete(skill, op.name)
            is TeachEditOp.MoveStep -> move(skill, op.from, op.to)
        }

        private fun rename(skill: TeachSkill, old: String, new: String): TeachEditResult {
            val p = skill.params.firstOrNull { it.name == old }
                ?: return TeachEditResult(null, "没有这个参数：$old")
            if (!TeachParam.NAME_RE.matches(new)) {
                return TeachEditResult(null, "参数名「$new」不合法（英文小写开头，只能 [a-z0-9_]，最长 32）")
            }
            if (skill.params.any { it.name == new }) return TeachEditResult(null, "参数名「$new」已经被占了")
            fun sub(s: String): String = s.replace(placeholderOf(old), placeholderOf(new))
            val out = skill.copy(
                startUrl = sub(skill.startUrl),
                steps = skill.steps.map { s ->
                    s.copy(
                        url = sub(s.url),
                        value = s.value?.let { sub(it) },
                        expect = s.expect.map { e -> e.value?.let { e.copy(value = sub(it)) } ?: e },
                    )
                },
                params = skill.params.map { if (it.name == old) p.copy(name = new) else it },
            )
            return verify(out)
        }

        private fun setDefault(skill: TeachSkill, name: String, value: String?): TeachEditResult {
            if (skill.params.none { it.name == name }) return TeachEditResult(null, "没有这个参数：$name")
            val out = skill.copy(
                params = skill.params.map {
                    if (it.name == name) it.copy(default = value?.takeIf { v -> v.isNotBlank() }) else it
                },
            )
            return verify(out)
        }

        private fun delete(skill: TeachSkill, name: String): TeachEditResult {
            val p = skill.params.firstOrNull { it.name == name }
                ?: return TeachEditResult(null, "没有这个参数：$name")
            val fallback = p.default
                ?: return TeachEditResult(
                    null,
                    "参数「$name」没留默认值——删掉就不知道原来填的是什么了；先给它一个默认值再删",
                )
            if (fallback.contains("\${")) return TeachEditResult(null, "默认值里带占位符，不能当回退字面量")
            fun sub(s: String): String = s.replace(placeholderOf(name), fallback)
            val out = skill.copy(
                startUrl = sub(skill.startUrl),
                steps = skill.steps.map { s ->
                    s.copy(
                        url = sub(s.url),
                        value = s.value?.let { sub(it) },
                        expect = s.expect.map { e -> e.value?.let { e.copy(value = sub(it)) } ?: e },
                    )
                },
                params = skill.params.filterNot { it.name == name },
            )
            return verify(out)
        }

        private fun move(skill: TeachSkill, from: Int, to: Int): TeachEditResult {
            val n = skill.steps.size
            if (from !in 1..n || to !in 1..n) return TeachEditResult(null, "步号越界：$from → $to（共 $n 步）")
            if (from == to) return TeachEditResult(skill, null)
            val list = skill.steps.toMutableList()
            val item = list.removeAt(from - 1)
            list.add(to - 1, item)
            // 顺序变了 ⇒ 步号重排（定位/断言跟着步骤走，编号只是位置）
            val renumbered = list.mapIndexed { i, s -> s.copy(index = i + 1) }
            val oldToNew = list.withIndex().associate { (i, s) -> s.index to (i + 1) }
            val fixedParams = skill.params.map { p ->
                p.copy(slots = p.slots.map { sl -> if (sl.step == 0) sl else sl.copy(step = oldToNew[sl.step] ?: sl.step) })
            }
            return verify(skill.copy(steps = renumbered, params = fixedParams))
        }

        /** 每次编辑都过一遍严格解析：编完还能被 196 读回来，才算改对了。 */
        private fun verify(skill: TeachSkill): TeachEditResult {
            val back = TeachSkill.fromJson(skill.toJsonText(), strict = true)
                ?: return TeachEditResult(null, "改完的技能不符合 zl-teach/1 形态，已拒绝")
            return TeachEditResult(back, null)
        }

        /**
         * 技能卡的「步骤＋参数」视图：每步一行，参数表在旁边（派工单 §3）。
         * 纯字符串，UI 只负责摆位置。
         */
        fun stepsView(skill: TeachSkill): List<String> = skill.steps.map { s ->
            val line = TeachLearner.stepLine(s)
            val names = (listOfNotNull(s.value) + s.expect.mapNotNull { it.value } + listOf(s.url))
                .flatMap { placeholderNamesIn(it) }
                .distinct()
            if (names.isEmpty()) line else "$line ⟵ 参数 ${names.joinToString("/") { "\${$it}" }}"
        }

        /** 参数表视图（名字／形状／默认值／选项），UI 一行一条。 */
        fun paramsView(skill: TeachSkill): List<String> = skill.params.map { it.describe() }
    }

    // ══ 工具 ═══════════════════════════════════════════════════════════════════

    /** url 的 query 键值对（原样，不解码；顺序保留）。 */
    internal fun queryPairs(url: String): List<Pair<String, String>> {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isBlank()) return emptyList()
        return q.split('&').mapNotNull { seg ->
            if (seg.isBlank()) return@mapNotNull null
            val k = seg.substringBefore('=')
            val v = seg.substringAfter('=', "")
            if (k.isBlank()) null else k to v
        }
    }

    /** url 的 path 段（不含 host、不含最后一段的文件名判断之外的东西——只是切分）。 */
    internal fun pathSegments(url: String): List<String> {
        val afterHost = when {
            url.contains("://") -> url.substringAfter("://").substringAfter('/', "")
            url.startsWith("//") -> url.substring(2).substringAfter('/', "")
            else -> url.substringAfter('/', "")
        }
        val path = afterHost.substringBefore('?').substringBefore('#')
        if (path.isBlank()) return emptyList()
        return path.split('/').filter { it.isNotBlank() && !it.contains('.') }
    }

    /** path 段长得像「数据」吗（纯数字 / 日期 / 字母数字混；`list`、`api` 这类静态段不算）。 */
    internal fun looksLikeData(seg: String): Boolean {
        if (seg.isEmpty() || seg.length > 24) return false
        if (seg.all { it.isDigit() }) return true
        if (isDateShaped(seg)) return true
        return seg.any { it.isDigit() } && seg.any { it.isLetter() }
    }

    /** 把 url 里等于 [example] 的 query 值 / path 段换成 [replacement]。 */
    internal fun rewriteUrl(url: String, example: String, replacement: String): String {
        if (url.isEmpty() || example.isEmpty()) return url
        val hash = url.substringAfter('#', "").let { if (it.isEmpty() && !url.contains('#')) "" else "#$it" }
        val noHash = url.substringBefore('#')
        val prefix = if (noHash.contains('?')) noHash.substringBefore('?') else noHash
        val query = if (noHash.contains('?')) noHash.substringAfter('?') else ""
        val newQuery = if (query.isEmpty()) query else query.split('&').joinToString("&") { seg ->
            val k = seg.substringBefore('=')
            val v = seg.substringAfter('=', "")
            if (v.isEmpty()) seg else if (valueMatches(v, example)) "$k=$replacement" else seg
        }
        // path 段
        val (head, tail) = splitHeadPath(prefix)
        val newPath = tail.split('/').joinToString("/") { seg ->
            if (seg.isNotBlank() && valueMatches(seg, example)) replacement else seg
        }
        val base = head + newPath
        return buildString {
            append(base)
            if (newQuery.isNotEmpty()) append('?').append(newQuery)
            append(hash)
        }
    }

    private fun valueMatches(raw: String, example: String): Boolean {
        if (raw == example) return true
        val a = decodeOrNull(raw)
        val b = decodeOrNull(example)
        return a != null && b != null && a == b
    }

    private fun decodeOrNull(s: String): String? =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()

    /** 把 `https://h/a/b` 切成（`https://h`，`/a/b`）。 */
    private fun splitHeadPath(prefix: String): Pair<String, String> {
        val idx = prefix.indexOf('/', startIndex = prefix.indexOf("://").let { if (it < 0) 0 else it + 3 })
        return if (idx < 0) prefix to "" else prefix.substring(0, idx) to prefix.substring(idx)
    }

    /** 相对日期换算的「今天」口径（测试与真机同一个入口）。 */
    internal fun todayIso(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)

    /** 两个日期差几天（报告里「昨天到今天」这类说明用）。 */
    internal fun daysBetween(aIso: String, bIso: String): Long? = runCatching {
        val a = parseIso(aIso) ?: return@runCatching null
        val b = parseIso(bIso) ?: return@runCatching null
        ChronoUnit.DAYS.between(a, b)
    }.getOrNull()
}
