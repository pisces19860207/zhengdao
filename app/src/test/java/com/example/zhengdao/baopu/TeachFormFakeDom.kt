// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零一批 · 测试用内存假表单 DOM：把 [FormScripts] 的四个查询
// （scan/probe/fill/read）语义在 JVM 里复刻一份，让填表判据不靠真机就能打到真行为。
//
// 与 196 的 [FakeDom] 同一条路子（那边复刻十条哑查询，这边复刻表单四查询）：
//   十档匹配 ↔ [matchTier]；可见性 ↔ [FakeFormEl.hidden]；可访问名 ↔ [accName]。
//
// 三个「能造坏」的开关是本文件存在于测试侧的全部理由：
//   [FakeFormEl.ignoreFill]  填了也不存   ⇒ 负例 4「多段只填第一段」；
//   [FakeFormEl.store]       存的时候改样 ⇒ 负例 3「富文本被塞成纯文本」；
//   [FakeFormDom.readOverride] 回读返回别的 ⇒ 负例 5「回读不一致」。
package com.example.zhengdao.baopu

/** 一个假控件（语义面与 FormScripts.SCAN 的返回字段一一对应）。 */
class FakeFormEl(
    val tag: String,
    val nameAttr: String? = null,
    val id: String? = null,
    val label: String? = null,
    val type: String? = null,
    val placeholder: String? = null,
    val ariaLabel: String? = null,
    val testid: String? = null,
    val classes: List<String> = emptyList(),
    val options: List<TeachOption> = emptyList(),
    val hidden: Boolean = false,
    val disabled: Boolean = false,
    val readOnly: Boolean = false,
    val contentEditable: Boolean = false,
    /** 填的时候页面怎么处理这个值（null ＝ 原样存下来）。 */
    val store: ((String) -> String)? = null,
    /** true ＝ 填了也不存（页面把这格当装饰，静默丢弃）。 */
    val ignoreFill: Boolean = false,
    /** 第二百二十四批：`autocomplete` 属性（`one-time-code` 的验证码框靠它露馅）。 */
    val autocomplete: String? = null,
    var value: String = "",
    var checked: Boolean = false,
) {
    val upperTag: String get() = tag.uppercase()

    fun identity(): String = "$upperTag/${nameAttr ?: label ?: id ?: "?"}"
}

/** 内存假表单页：实现 [FormDom]，另记动作日志（负例 1 的红线断言查这里）。 */
class FakeFormDom(val elements: MutableList<FakeFormEl>) : FormDom {

    /** 每一次「填」的落点（元素身份）。 */
    val fillCalls = mutableListOf<String>()

    /** 被填到的提交/创建类控件（**必须是空的**）。 */
    val poisonFilled = mutableListOf<String>()

    /** 点击次数——表单端口没有点击通道，这个数只能是 0。 */
    var clickCount: Int = 0

    /** 回读改写器（负例 5：回读返回别的值）。 */
    var readOverride: ((FakeFormEl, FormControl) -> FormReadback?)? = null

    /** 探针改写器（模拟「模板说它是输入框，页面上其实是个按钮」）。 */
    var probeOverride: ((FakeFormEl) -> FormProbe?)? = null

    private fun accName(el: FakeFormEl): String? =
        el.label?.takeIf { it.isNotBlank() } ?: el.ariaLabel?.takeIf { it.isNotBlank() }

    private fun roleOf(el: FakeFormEl): String? = when (el.upperTag) {
        "BUTTON" -> "button"
        "A" -> "link"
        "SELECT" -> "combobox"
        "TEXTAREA" -> "textbox"
        "INPUT" -> when (el.type) {
            "radio" -> "radio"
            "checkbox" -> "checkbox"
            "search" -> "searchbox"
            else -> "textbox"
        }
        else -> null
    }

    private fun visible(): List<FakeFormEl> = elements.filter { !it.hidden }

    /** 十档匹配（只实现表单里真正会用到的档；XPath 与假 DOM 同惯例不实现）。 */
    fun matchTier(loc: TeachLocator): List<FakeFormEl> {
        val els = visible()
        return when (loc.kind) {
            TeachLocatorKind.ROLE_NAME -> {
                val (role, name) = loc.value.split("|", limit = 2).let {
                    it.getOrElse(0) { "" } to it.getOrElse(1) { "" }
                }
                els.filter { roleOf(it) == role && accName(it) == name }
            }
            TeachLocatorKind.TESTID -> els.filter { it.testid == loc.value }
            TeachLocatorKind.ARIA_LABEL -> els.filter { it.ariaLabel == loc.value }
            TeachLocatorKind.ELEM_ID -> els.filter { it.id == loc.value }
            TeachLocatorKind.INPUT_NAME -> els.filter { it.nameAttr == loc.value }
            TeachLocatorKind.PLACEHOLDER -> els.filter { it.placeholder == loc.value }
            TeachLocatorKind.TEXT -> els.filter { it.label?.trim() == loc.value || it.value.trim() == loc.value }
            TeachLocatorKind.FORM_CONTEXT -> {
                val (label, sec) = loc.value.split("|", limit = 2).let {
                    it.getOrElse(0) { "" } to it.getOrElse(1) { "" }
                }
                els.filter { el ->
                    val secHit = el.label?.trim() == sec || el.nameAttr == sec || accName(el) == sec
                    secHit && el.label?.contains(label) == true
                }
            }
            TeachLocatorKind.CSS_CLASS -> {
                val wanted = loc.value.split(".").filter { it.isNotBlank() }
                els.filter { el -> wanted.all { it in el.classes } }
            }
            TeachLocatorKind.XPATH -> emptyList()
        }
    }

    /** 逐档找：第一个有命中的档胜出（mirror FormScripts.finder）。 */
    fun hits(locators: List<TeachLocator>): List<FakeFormEl> {
        for (loc in locators.sortedBy { it.tier }) {
            val h = matchTier(loc)
            if (h.isNotEmpty()) return h
        }
        return emptyList()
    }

    // ── FormDom ─────────────────────────────────────────────────────────────

    override fun scan(): List<FormCandidate>? = visible().map { el ->
        FormCandidate(
            tag = el.upperTag,
            inputType = el.type,
            contentEditable = el.contentEditable,
            elemId = el.id,
            testid = el.testid,
            ariaLabel = el.ariaLabel,
            inputName = el.nameAttr,
            placeholder = el.placeholder,
            autocomplete = el.autocomplete,
            labelText = el.label,
            formLabel = null,
            role = roleOf(el),
            text = el.label,
            classes = el.classes,
            options = el.options,
            checked = el.checked,
            disabled = el.disabled,
            readOnly = el.readOnly,
            visible = !el.hidden,
        )
    }

    override fun probe(locators: List<TeachLocator>): FormReply {
        val h = hits(locators)
        if (h.isEmpty()) return FormReply.Probed(FormProbe(found = false, hitCount = 0, reason = "页面里找不到这个字段"))
        val el = h.first()
        probeOverride?.invoke(el)?.let { return FormReply.Probed(it.copy(hitCount = h.size)) }
        return FormReply.Probed(
            FormProbe(
                found = true,
                hitCount = h.size,
                tag = el.upperTag,
                inputType = el.type,
                contentEditable = el.contentEditable,
                inputName = el.nameAttr,
                elemId = el.id,
                placeholder = el.placeholder,
                text = el.label,
                autocomplete = el.autocomplete,
            ),
        )
    }

    override fun fill(
        locators: List<TeachLocator>,
        control: FormControl,
        value: String,
        optionValue: String?,
    ): FormReply {
        val h = hits(locators)
        if (h.isEmpty()) return FormReply.Filled(false, "定位命中 0 个，不动手")
        if (control != FormControl.RADIO && h.size != 1) {
            return FormReply.Filled(false, "定位命中 ${h.size} 个，不动手")
        }
        val el = h.first()
        fillCalls += el.identity()
        if (el.upperTag == "BUTTON" || el.type in setOf("submit", "button")) {
            // 这条分支在正常流程里永远不该走到（端口层就该拒），留作哨兵
            poisonFilled += el.identity()
            return FormReply.Filled(false, "提交类控件拒填")
        }
        if (el.disabled || el.readOnly) return FormReply.Filled(false, "只读/禁用，填不进去")
        if (el.ignoreFill) return FormReply.Filled(true, null) // 静默丢弃：动作成功、值没落
        when (control) {
            FormControl.SINGLE_LINE -> el.value = el.store?.invoke(value) ?: value
            FormControl.RICH_TEXT -> el.value = el.store?.invoke(value) ?: value
            FormControl.SELECT -> {
                val want = optionValue ?: value
                if (el.options.none { it.value == want }) return FormReply.Filled(false, "下拉里没有这个选项：$want")
                el.value = want
            }
            FormControl.RADIO -> {
                val want = optionValue ?: value
                val group = elements.filter { it.nameAttr == el.nameAttr && it.type == "radio" }
                val hit = group.firstOrNull { it.value == want } ?: group.firstOrNull { it.label == value }
                if (hit == null) return FormReply.Filled(false, "这一组单选里没有这个选项：$value")
                group.forEach { it.checked = it === hit }
            }
        }
        return FormReply.Filled(true, null)
    }

    override fun read(locators: List<TeachLocator>, control: FormControl): FormReply {
        val h = hits(locators)
        if (h.isEmpty()) return FormReply.Failed("回读命中 0 个")
        val el = h.first()
        readOverride?.invoke(el, control)?.let { return FormReply.Read(it) }
        return FormReply.Read(
            when (control) {
                FormControl.RADIO -> {
                    val group = elements.filter { it.nameAttr == el.nameAttr && it.type == "radio" }
                    val hit = group.firstOrNull { it.checked }
                    FormReadback(text = hit?.label.orEmpty(), value = hit?.value.orEmpty(), checked = hit != null)
                }
                FormControl.SELECT -> FormReadback(
                    text = el.options.firstOrNull { it.value == el.value }?.label.orEmpty(),
                    value = el.value,
                )
                else -> FormReadback(text = el.value, value = el.value)
            },
        )
    }
}
