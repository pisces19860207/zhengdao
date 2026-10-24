// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 · 测试用内存假 DOM：把 TeachScripts 里十条哑查询的语义在 JVM 里
// 复刻一份（可见性/隐式 role/可访问名/十档匹配/最佳唯一锚），让执行器与学习器的
// 判据不靠真机就能打到真行为。生产 JS 与本镜像的口径逐条对应：
//   zlV（可见）↔ [visible]；zlR（隐式 role）↔ [roleOf]；zlN（可访问名）↔ [accName]；
//   matcherFor ↔ [scan]；zlBest ↔ [bestUniqueLocator]。
package com.example.zhengdao.baopu

/** 假元素：录制/回放关心的语义面＋点击副作用。 */
class FakeEl(
    val tag: String,
    val id: String? = null,
    val classes: List<String> = emptyList(),
    val text: String = "",
    val role: String? = null, // 显式 role（aria 的）
    val ariaLabel: String? = null,
    val testid: String? = null,
    val nameAttr: String? = null,
    val placeholder: String? = null,
    val inputType: String? = null,
    val formLabel: String? = null, // 所在表单/列名的文本（T8 锚）
    var value: String? = null,
    var visible: Boolean = true,
    val onClick: ((FakeDom) -> Unit)? = null,
) {
    val upperTag: String get() = tag.uppercase()

    /** 元素身份（点击日志/断言用）。 */
    fun identity(): String = "$upperTag/${accNameOf(this).orEmpty().ifEmpty { "?" }}"
}

/** 可访问名（aria-label → 文本；FakeDom 的匹配与这里同一份）。 */
private fun accNameOf(el: FakeEl): String? =
    el.ariaLabel?.takeIf { it.isNotBlank() } ?: el.text.takeIf { it.isNotBlank() }

/** 一页：URL → 元素列表（可变——「翻页改行数」「改类名」这类变异测试要原地改）。 */
class FakePage(
    val url: String,
    elements: List<FakeEl>,
) {
    var elements: MutableList<FakeEl> = elements.toMutableList()
}

/** 内存假 DOM：实现 [TeachDom]，另记动作日志与「诱饵被点」哨兵。 */
class FakeDom(
    pages: Map<String, FakePage>,
    startUrl: String,
) : TeachDom {

    var currentUrl: String = startUrl
        private set

    private val pageMap: MutableMap<String, FakePage> = pages.toMutableMap()

    /** 点击日志（元素身份），负例断言「没点过谁」就查这里。 */
    val clicks = mutableListOf<String>()

    /** 输入日志（元素身份→值）。 */
    val typed = mutableListOf<Pair<String, String>>()

    /** 诱饵哨兵：任何被拒绝名单点中的元素名记到这（负例 3 的红线断言）。 */
    val poisonClicked = mutableListOf<String>()

    // ── 语义口径（与 TeachScripts 的 JS 逐条对应）────────────────────────────

    private fun roleOf(el: FakeEl): String? = el.role?.lowercase() ?: when (el.upperTag) {
        "BUTTON", "SUMMARY" -> "button"
        "A" -> "link"
        "SELECT" -> "combobox"
        "TEXTAREA" -> "textbox"
        "TH" -> "columnheader"
        "INPUT" -> when (el.inputType) {
            "checkbox" -> "checkbox"
            "radio" -> "radio"
            "search" -> "searchbox"
            else -> "textbox"
        }
        else -> null
    }

    private fun accName(el: FakeEl): String? = accNameOf(el)

    /** TEXT 档的候选元素面（与 JS 的选择器列表一致）。 */
    private fun textCandidate(el: FakeEl): Boolean = when {
        el.upperTag in setOf("BUTTON", "A", "SUMMARY", "OPTION", "TH", "LABEL") -> true
        el.upperTag == "INPUT" && el.inputType in setOf("button", "submit") -> true
        el.role?.lowercase() in setOf("button", "link", "tab") -> true
        else -> false
    }

    private fun visibleEls(): List<FakeEl> = page().elements.filter { it.visible }

    private fun page(): FakePage = pageMap.getValue(currentUrl)

    /** 稳定 CSS 类（与 TeachLearner.stableCssClass / JS 的 /^[A-Za-z_-]+$/ 同口径）。 */
    private fun stableClasses(el: FakeEl): List<String> =
        el.classes.filter { it.length in 2..30 && it.none { c -> c.isDigit() } && it.all { c -> c.isLetter() || c == '-' || c == '_' } }

    private fun stableId(id: String?): Boolean =
        !id.isNullOrBlank() && id.length <= 40 && !id.contains(":r") &&
            !Regex("[-_]\\d+$").containsMatchIn(id)

    /** 按一条定位器找当前页的可见元素（十档匹配，mirror matcherFor）。 */
    fun scan(loc: TeachLocator): List<FakeEl> {
        val els = visibleEls()
        return when (loc.kind) {
            TeachLocatorKind.ROLE_NAME -> {
                val (role, name) = loc.value.split("|", limit = 2).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
                els.filter { roleOf(it) == role && accName(it) == name }
            }
            TeachLocatorKind.TESTID -> els.filter { it.testid == loc.value }
            TeachLocatorKind.ARIA_LABEL -> els.filter { it.ariaLabel == loc.value }
            TeachLocatorKind.ELEM_ID -> els.filter { it.id == loc.value }
            TeachLocatorKind.INPUT_NAME -> els.filter { it.nameAttr == loc.value }
            TeachLocatorKind.PLACEHOLDER -> els.filter { it.placeholder == loc.value }
            TeachLocatorKind.TEXT -> els.filter { textCandidate(it) && it.text.trim() == loc.value }
            TeachLocatorKind.FORM_CONTEXT -> {
                val (label, sec) = loc.value.split("|", limit = 2).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
                els.filter { el ->
                    val secHit = el.text.trim() == sec || el.nameAttr == sec || accName(el) == sec
                    secHit && el.formLabel?.contains(label) == true
                }
            }
            TeachLocatorKind.CSS_CLASS -> {
                val wanted = loc.value.split(".").filter { it.isNotBlank() }
                els.filter { el -> wanted.all { w -> w in el.classes } }
            }
            TeachLocatorKind.XPATH -> emptyList() // 假 DOM 不实现 XPath（测试不落这一档）
        }
    }

    /** 元素的最佳唯一定位器（mirror zlBest：十档序，逐档验当前页唯一）。 */
    fun bestUniqueLocator(el: FakeEl): TeachLocator? {
        val candidates = mutableListOf<TeachLocator>()
        val role = roleOf(el)
        val name = accName(el)
        if (role != null && name != null) candidates += TeachLocator(TeachLocatorKind.ROLE_NAME, "$role|$name")
        el.testid?.let { candidates += TeachLocator(TeachLocatorKind.TESTID, it) }
        el.ariaLabel?.let { candidates += TeachLocator(TeachLocatorKind.ARIA_LABEL, it) }
        if (stableId(el.id)) candidates += TeachLocator(TeachLocatorKind.ELEM_ID, el.id!!)
        el.nameAttr?.let { candidates += TeachLocator(TeachLocatorKind.INPUT_NAME, it) }
        el.placeholder?.let { candidates += TeachLocator(TeachLocatorKind.PLACEHOLDER, it) }
        if (el.text.isNotBlank()) candidates += TeachLocator(TeachLocatorKind.TEXT, el.text.trim())
        val stable = stableClasses(el)
        if (stable.isNotEmpty()) candidates += TeachLocator(TeachLocatorKind.CSS_CLASS, stable.take(3).joinToString("."))
        return candidates.sortedBy { it.tier }.firstOrNull { scan(it).size == 1 }
    }

    private fun hitOf(el: FakeEl, heal: Boolean): TeachHit = TeachHit(
        tag = el.upperTag,
        role = roleOf(el),
        name = accName(el),
        text = el.text.trim().takeIf { it.isNotBlank() },
        ariaLabel = el.ariaLabel,
        testid = el.testid,
        inputType = if (el.upperTag == "INPUT") (el.inputType ?: "text") else null,
        inputName = el.nameAttr,
        placeholder = el.placeholder,
        inputId = el.id,
        healLocator = if (heal) bestUniqueLocator(el) else null,
    )

    // ── TeachDom 实现 ────────────────────────────────────────────────────────

    override fun ask(query: TeachQuery): TeachReply = when (query) {
        is TeachQuery.Scan -> TeachReply.Hits(scan(query.locator).take(5).map { hitOf(it, heal = false) })
        is TeachQuery.FindBySnapshot -> {
            val snap = query.snapshot
            val matches = visibleEls().filter { el ->
                el.upperTag == snap.tag &&
                    (snap.role == null || roleOf(el) == snap.role) &&
                    (snap.name == null || accName(el) == snap.name) &&
                    (snap.text == null || el.text.trim() == snap.text)
            }
            TeachReply.Hits(matches.take(3).map { hitOf(it, heal = true) })
        }
        TeachQuery.CurrentUrl -> TeachReply.Url(currentUrl)
        is TeachQuery.TextPresent -> TeachReply.Flag(
            visibleEls().any { it.text.contains(query.text) || it.value?.contains(query.text) == true },
        )
        TeachQuery.RowCount -> TeachReply.Count(visibleEls().count { it.upperTag in setOf("TR", "LI") || it.role == "row" })
        TeachQuery.HeadersAndButtons -> TeachReply.Anchors(
            headers = visibleEls().filter { it.upperTag == "TH" }.map { it.text.trim() }.filter { it.isNotEmpty() },
            buttons = visibleEls().filter { it.upperTag in setOf("BUTTON", "A") || it.role == "button" }
                .mapNotNull { accName(it)?.take(30) },
        )
        is TeachQuery.Act -> {
            val hits = scan(query.locator)
            if (hits.size != 1) {
                TeachReply.Acted(ok = false, reason = "定位命中 ${hits.size} 个，不动手", urlAfter = currentUrl)
            } else {
                val el = hits.first()
                when (query.action) {
                    TeachAction.CLICK -> {
                        clicks += el.identity()
                        if (el.text.contains(Regex("提交|确认|删除|作废|发货|派车|结算|支付|充值"))) {
                            poisonClicked += el.identity()
                        }
                        el.onClick?.invoke(this)
                    }
                    TeachAction.TYPE, TeachAction.SELECT -> {
                        el.value = query.value
                        typed += el.identity() to (query.value ?: "")
                    }
                    TeachAction.SCROLL, TeachAction.WAIT -> Unit
                }
                TeachReply.Acted(ok = true, reason = null, urlAfter = currentUrl)
            }
        }
        TeachQuery.DomSummary -> TeachReply.Summary(
            "url=$currentUrl rows=${ask(TeachQuery.RowCount)} els=${page().elements.size}",
        )
    }

    /** 导航（点击副作用用）。 */
    fun navigate(url: String) {
        currentUrl = url
    }

    /** 原地换一页的元素列表（「改类名/改表头」的变异测试用）。 */
    fun replacePage(url: String, transform: (List<FakeEl>) -> List<FakeEl>) {
        val p = pageMap.getValue(url)
        p.elements = transform(p.elements).toMutableList()
    }
}
