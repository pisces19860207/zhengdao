// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批（artifact-first）：HTML/SVG 的**消毒判据**（纯函数、零 IO、零 Android）。
//
// 要洗的是**模型写出来的整页 HTML / 整段 SVG**（`render_artifact`），所以口径是白名单重建：
//
// 1. `script` / `iframe` / `object` / `embed` / `foreignObject` 这类**可执行或可外联**的元素，
//    连内容一起丢；没闭合的那种「从开标签一路丢到文末」（否则浏览器会把后面的正文
//    当成它的内容，页面变成空白——空白冒充成功是本批的负例之一）。
// 2. `on*=` 一律丢。
// 3. **外链全剥**：`href` 只留 `#锚点`；`src` 只留 `data:image/…`（内联图，不出网）；
//    `srcset` / `action` / `formaction` / `poster` / `xlink:href` 同理；属性里的
//    `url(…)` 只留 `url(#本页锚点)`；`<style>` 里的 `@import` / 外链 `url()` 全部换掉。
// 4. `<style>` **保留**（模型写的页面几乎都靠它排版），但内容先过 CSS 消毒：
//    去 `@import`、`expression()`、`behavior`、`-moz-binding`、外链 URL，
//    并抹掉 `</`（免得从样式块里提前闭合、把后面的正文变成脚本或样式）。
// 5. 白名单重建：只认本批会渲染的标签/属性；属性值统一转义后写回，属性名保持原大小写
//    （`viewBox` 这类 SVG 属性不能被折成小写）。
//
// 这一层**不负责**断网与禁 JS——那是渲染那一侧的事（离线沙箱：JS 关、网络全拦）。
// 这一层的承诺只有一句：**落到预览/PNG 里的只会是静态标记**。
package com.example.zhengdao.baopu.agent

/** 一段被清洗过的文字：`removed` = 剥掉的标签/危险属性/危险前缀处数。 */
internal data class SanitizedText(val text: String, val removed: Int)

/** 成品标记的消毒结果；[removed] 用于回执，[problems] 非空时调用方必须报错。 */
internal data class SanitizedMarkup(
    val text: String,
    val removed: Int,
    val problems: List<String> = emptyList(),
) {
    val ok: Boolean get() = problems.isEmpty()
}

internal object MarkupSanitizer {

    private val COMMENT = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL))

    private val XML_DECL = Regex("""<\?.*?\?>""", setOf(RegexOption.DOT_MATCHES_ALL))

    /**
     * 连同内容一起丢掉的元素（可执行 / 可外联 / 会改变整页行为的壳）。
     *
     * 只收**有闭合标签**的元素：它们的"内容"可能是脚本、内嵌文档或另一份完整页面，
     * 留着文字没有意义。`input` / `img` 这类空元素走 [DROP_VOID]。
     */
    private val DANGEROUS_BLOCKS = listOf(
        "script", "iframe", "frame", "frameset", "object", "embed",
        "applet", "foreignobject", "template",
    )

    /** 自闭合/空元素：只丢标记，不牵动后面的内容（丢错一个就把正文吞了）。 */
    private val DROP_VOID = listOf(
        "image", "img", "svg:image", "link", "base", "source", "track",
        "video", "audio", "canvas", "input", "param",
    )

    private val TAG = Regex(
        """<\s*(/?)\s*([a-zA-Z][a-zA-Z0-9:_-]*)((?:[^>"']|"[^"]*"|'[^']*')*)>""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val ATTR = Regex(
        """([a-zA-Z_:][a-zA-Z0-9_:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""",
    )

    private val ON_ATTR = Regex(
        """\bon[a-zA-Z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s;>]+)""",
        setOf(RegexOption.IGNORE_CASE),
    )

    private val TAG_LIKE = Regex("""<[^>]*>""", setOf(RegexOption.DOT_MATCHES_ALL))

    private val DANGEROUS_PREFIX = Regex(
        """(?:javascript|vbscript|data\s*:\s*text/html)\s*:""",
        setOf(RegexOption.IGNORE_CASE),
    )

    private val CONTROL = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")

    /** 外链属性：除下面 [safeLink] 放行的两种形状外一律剥掉（「外链全剥」）。 */
    private val LINK_ATTRS = setOf(
        "href", "src", "srcset", "xlink:href", "action", "formaction",
        "poster", "data", "srcdoc",
    )

    private val SVG_TAGS = setOf(
        "svg", "g", "defs", "path", "rect", "line", "polyline", "polygon", "circle",
        "ellipse", "text", "tspan", "title", "desc", "a", "style",
        "lineargradient", "radialgradient", "stop",
        "clippath", "mask", "pattern", "marker", "symbol",
    )

    private val HTML_TAGS = setOf(
        "html", "head", "meta", "title", "body", "h1", "h2", "h3", "h4", "h5", "h6",
        "p", "div", "section", "article", "header", "footer", "main", "aside",
        "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption",
        "colgroup", "col", "span", "br", "hr", "strong", "b", "em", "i", "u",
        "code", "pre", "blockquote", "ul", "ol", "li", "dl", "dt", "dd",
        "figure", "figcaption", "small", "sub", "sup", "a", "style",
    ) + SVG_TAGS

    private val SVG_ATTRS = setOf(
        "xmlns", "version", "width", "height", "viewbox", "preserveaspectratio",
        "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry", "fx", "fy",
        "points", "d", "transform", "offset", "id", "class", "style",
        "gradientunits", "gradienttransform", "spreadmethod",
        "patternunits", "patterncontentunits", "clippathunits", "maskunits",
        "markerwidth", "markerheight", "refx", "refy", "orient",
        "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width",
        "stroke-dasharray", "stroke-linecap", "stroke-linejoin", "stroke-opacity",
        "opacity", "stop-color", "stop-opacity", "clip-rule", "clip-path",
        "font-size", "font-family", "font-weight", "font-style", "letter-spacing",
        "text-anchor", "dominant-baseline",
    )

    private val HTML_ATTRS = setOf(
        "charset", "name", "content", "style", "class", "id", "title", "lang", "dir",
        "colspan", "rowspan", "align", "valign", "width", "height",
        "border", "cellpadding", "cellspacing", "bgcolor", "scope", "datetime",
    ) + SVG_ATTRS

    /**
     * 模型给的字进图/进表前的第一道闸。
     *
     * `<script>…</script>` 连内容一起丢掉（内容不是用户要看的文字），其余标签只丢标记；
     * `on*=` 与 `javascript:` 这类危险片段整段剥掉。换行折成空格——图里的标签/单元格
     * 是单行排版，保留换行只会让宽度估算与视觉对不上。
     */
    fun cleanText(raw: String): SanitizedText {
        var s = raw.replace(CONTROL, "")
        var removed = 0
        for (tag in DANGEROUS_BLOCKS) {
            val re = Regex(
                """<\s*$tag\b[^>]*>.*?<\s*/\s*$tag\s*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            removed += re.findAll(s).count()
            s = re.replace(s, "")
        }
        removed += TAG_LIKE.findAll(s).count()
        s = TAG_LIKE.replace(s, "")
        removed += ON_ATTR.findAll(s).count()
        s = ON_ATTR.replace(s, "")
        removed += DANGEROUS_PREFIX.findAll(s).count()
        s = DANGEROUS_PREFIX.replace(s, "")
        s = s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
        while (s.contains("  ")) s = s.replace("  ", " ")
        return SanitizedText(s.trim(), removed)
    }

    /** SVG 成品消毒（白名单重建；允许静态 `style` 与 `<style>` 块，外链一律剥）。 */
    fun sanitizeSvg(raw: String): SanitizedMarkup = sanitize(raw, SVG_TAGS, SVG_ATTRS)

    /** HTML 成品消毒（同上；标签表是 HTML 那一套 + 内联 SVG 那一套）。 */
    fun sanitizeHtml(raw: String): SanitizedMarkup = sanitize(raw, HTML_TAGS, HTML_ATTRS)

    private fun sanitize(raw: String, allowedTags: Set<String>, allowedAttrs: Set<String>): SanitizedMarkup {
        var removed = 0
        val problems = mutableListOf<String>()
        // 注释与 XML 声明不是「危险标记」，不计入 removed（回执说的是「剥掉 N 处可执行/外链」）。
        var s = COMMENT.replace(raw, "")
            .replace(XML_DECL, "")
            .replace(CONTROL, "")

        // ① 危险元素连内容丢。
        for (tag in DANGEROUS_BLOCKS) {
            val re = Regex(
                """<\s*$tag\b[^>]*>.*?<\s*/\s*$tag\s*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            removed += re.findAll(s).count()
            s = re.replace(s, "")
        }

        // ② 还没闭合的危险开标签：从它丢到文末。按 TAG 解析（不按裸字符串），
        //    免得把属性值里出现的 "<script" 当成开标签、把后面的正文全吞掉。
        val strayBlock = TAG.findAll(s).firstOrNull { m ->
            m.groupValues[1].isEmpty() && m.groupValues[2].lowercase() in DANGEROUS_BLOCKS
        }
        if (strayBlock != null) {
            removed++
            s = s.substring(0, strayBlock.range.first)
        }

        // ③ 空元素只丢标记（不碰后面的内容）。
        for (tag in DROP_VOID) {
            val re = Regex(
                """<\s*/?\s*$tag\b((?:[^>"']|"[^"]*"|'[^']*')*)/?\s*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
            removed += re.findAll(s).count()
            s = re.replace(s, "")
        }

        // ④ `<style>` 内容先过 CSS 消毒（留着外壳，内容重写）。
        val styleBlock = Regex(
            """<style\b[^>]*>(.*?)</style\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        s = styleBlock.replace(s) { m ->
            val (css, dropped) = sanitizeCss(m.groupValues[1])
            removed += dropped
            "<style>$css</style>"
        }
        val strayStyle = TAG.findAll(s).firstOrNull { m ->
            m.groupValues[1].isEmpty() &&
                m.groupValues[2].equals("style", ignoreCase = true) &&
                // 只有「后面再也找不到 </style>」的才是没闭合的；已经闭合的样式块必须留。
                !Regex("""</\s*style\s*>""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(s.substring(m.range.last + 1))
        }
        if (strayStyle != null) {
            // 没闭合的样式块：它后面的全部是 CSS 文本，留着只会被浏览器吞成空白。
            removed++
            s = s.substring(0, strayStyle.range.first)
        }

        // ⑤ 白名单重建（标签与属性都只认表里的；属性名保持原大小写）。
        val out = StringBuilder(s.length)
        var last = 0
        for (m in TAG.findAll(s)) {
            out.append(s, last, m.range.first)
            last = m.range.last + 1
            val closing = m.groupValues[1].isNotEmpty()
            val rawName = m.groupValues[2]
            val name = rawName.lowercase()
            val attrText = m.groupValues[3]
            if (name !in allowedTags) {
                removed++
                continue
            }
            if (closing) {
                out.append("</").append(rawName).append('>')
                continue
            }
            if (name == "meta" && Regex("""http-equiv""", RegexOption.IGNORE_CASE).containsMatchIn(attrText)) {
                removed++ // meta refresh 也是一条跳转通道
                continue
            }
            val selfClosing = attrText.trimEnd().endsWith("/")
            val attrs = StringBuilder()
            for (a in ATTR.findAll(attrText)) {
                val rawAttr = a.groupValues[1]
                val attr = rawAttr.lowercase()
                val value = when {
                    a.groupValues[2].isNotEmpty() -> a.groupValues[2]
                    a.groupValues[3].isNotEmpty() -> a.groupValues[3]
                    else -> a.groupValues[4]
                }
                when {
                    attr.startsWith("on") -> removed++
                    attr in LINK_ATTRS -> {
                        if (!safeLink(attr, value)) removed++ else {
                            attrs.append(' ').append(rawAttr)
                                .append("=\"").append(xmlAttr(value)).append('"')
                        }
                    }
                    attr == "style" -> {
                        if (safeStyle(value)) {
                            attrs.append(' ').append(rawAttr)
                                .append("=\"").append(xmlAttr(value)).append('"')
                        } else {
                            removed++
                        }
                    }
                    attr !in allowedAttrs -> removed++
                    !safeValue(value) -> removed++
                    else -> attrs.append(' ').append(rawAttr)
                        .append("=\"").append(xmlAttr(value)).append('"')
                }
            }
            out.append('<').append(rawName).append(attrs)
            if (selfClosing) out.append("/>") else out.append('>')
        }
        out.append(s, last, s.length)
        val text = out.toString().trim()
        if (text.isEmpty()) problems += "消毒后内容为空（上游给的是空串，或全是会被剥掉的标记）"
        if (text.contains("<script", ignoreCase = true)) problems += "仍有 <script> 残留"
        return SanitizedMarkup(text, removed, problems)
    }

    /** 链接属性：`#锚点` 与内联图片（`data:image/…`）放行，其余一律算外链。 */
    private fun safeLink(attr: String, value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        if (v.startsWith("#")) return true
        if (attr == "src" && v.startsWith("data:image/", ignoreCase = true)) return true
        return false
    }

    /** `style` 属性只收静态样式：禁 `url()`（本页锚点除外）/ `expression()` / `javascript:` / 反斜杠转义。 */
    private fun safeStyle(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty() || v.length > 800) return false
        if (!Regex("""^[a-zA-Z0-9#%.,:;()\-_'"/!\s]+$""").matches(v)) return false
        if ("\\" in v) return false
        val lower = v.lowercase()
        if (listOf("expression", "javascript", "vbscript", "@import", "behavior", "-moz-binding")
                .any { it in lower }
        ) {
            return false
        }
        if ("http:" in lower || "https:" in lower) return false
        return urlsAreLocal(lower)
    }

    /** 属性值里所有 `url(…)` 都只能指向本页锚点。 */
    private fun urlsAreLocal(lower: String): Boolean {
        var i = lower.indexOf("url(")
        while (i >= 0) {
            val end = lower.indexOf(')', i)
            if (end < 0) return false
            val target = lower.substring(i + 4, end).trim().trim('\'', '"', ' ')
            if (!target.startsWith("#")) return false
            i = lower.indexOf("url(", end)
        }
        return true
    }

    private fun safeValue(value: String): Boolean {
        val lower = value.lowercase()
        if ("javascript:" in lower || "vbscript:" in lower || "expression(" in lower) return false
        return urlsAreLocal(lower)
    }

    /** CSS 消毒：返回（洗过的 CSS, 剥掉几处）。 */
    private fun sanitizeCss(raw: String): Pair<String, Int> {
        var removed = 0
        var s = raw
        if ("\\" in s) {
            // CSS 反斜杠转义（`\75 rl(...)`）能绕过下面的关键字检查 ⇒ 整段抹掉。
            removed++
            s = s.replace("\\", "")
        }
        fun cut(re: Regex) {
            val n = re.findAll(s).count()
            if (n > 0) removed += n
            s = re.replace(s, "")
        }
        cut(Regex("""@import\b[^;]*;?""", setOf(RegexOption.IGNORE_CASE)))
        cut(Regex("""@charset\b[^;]*;?""", setOf(RegexOption.IGNORE_CASE)))
        cut(Regex("""expression\s*\(""", setOf(RegexOption.IGNORE_CASE)))
        cut(Regex("""javascript\s*:""", setOf(RegexOption.IGNORE_CASE)))
        cut(Regex("""vbscript\s*:""", setOf(RegexOption.IGNORE_CASE)))
        cut(Regex("""behavior\s*:""", setOf(RegexOption.IGNORE_CASE)))
        cut(Regex("""-moz-binding\s*:""", setOf(RegexOption.IGNORE_CASE)))
        val url = Regex("""url\(\s*(['"]?)([^)]*?)\1\s*\)""", setOf(RegexOption.IGNORE_CASE))
        s = url.replace(s) { m ->
            val target = m.groupValues[2].trim()
            if (target.startsWith("data:image/", ignoreCase = true) || target.startsWith("#")) {
                m.value
            } else {
                removed++
                "none"
            }
        }
        // 不带 url() 的外链（image-set("https://…") / @namespace 之类）也要断掉。
        val external = Regex("""(?:https?:)?//[^\s)"';]+""", setOf(RegexOption.IGNORE_CASE))
        val n = external.findAll(s).count()
        if (n > 0) {
            removed += n
            s = external.replace(s, "")
        }
        if ("</" in s) {
            removed++
            s = s.replace("</", "")
        }
        return s to removed
    }

    private fun xmlAttr(s: String): String = s
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
