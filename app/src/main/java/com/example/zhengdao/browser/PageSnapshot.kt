package com.example.zhengdao.browser

/**
 * 玄览页面摘要器 —— 第⑲刀「给 agent 的眼睛」第一版的**纯函数内核**。
 *
 * 派工单要求：「摘要器做成纯函数（HTML 字符串 → 摘要）」，门禁 `BrowserPageSnapshotTest`
 * 覆盖 input / select / textarea / aria-label / 隐藏节点剔除 / 超长截断 / 空页面。
 * 因此本对象**只吃 String、只吐数据类**——不碰 Android、不碰 Context、不碰 WebView，
 * 于是整条判定链能在纯 JVM 单测里逐条钉死（项目房型：审计类缺陷修成「纯函数 + 表驱动单测」）。
 *
 * ## 为什么自己写 HTML 解析而不用现成库
 * 仓库没有 jsoup（`app/build.gradle.kts` 依赖清单里没有），而本刀的红线之一是**不新增依赖**。
 * 页面摘要是"够用就好"的需求（要的是可交互节点 + 可见正文），所以这里是一个**容错的小分词器**：
 * 任何畸形输入都不许抛异常（碰到就按当前位置降级继续），最差返回"空摘要"。
 *
 * ## 三件事的边界（与派工单逐字对齐）
 * 1. [summarize]：HTML → 摘要。交互节点逐行给 `role | 名称 | 值 | 状态 | 序号`，
 *    另附标题与正文（去 `script`/`style`，超 [MAX_TEXT_CHARS] 截断并标注）。
 * 2. [scrubSecrets]：**明文禁项**过滤——导出内容里不许出现 Cookie / Authorization /
 *    localStorage / sessionStorage 以及同类凭据的**值**。它作用于落盘前的 HTML 文本，
 *    所以 [summarize] 的输入也是过滤后的，摘要里不可能漏出未过滤的原文。
 * 3. [clampHtmlBytes]：tab.html 的上限（512KB）与截断标记。
 *
 * ## 一处**刻意偏离**（要你知道，写在报告里）
 * `tab.html` 按派工单是「渲染后 DOM（document.documentElement.outerHTML）」，但本实现把
 * `<script>` / `<style>` 的**正文掏空**（标签留着）——理由两条：① agent 读页面不需要脚本源码；
 * ② 脚本正文恰恰是内联 `token` / 配置常量 / 读 cookie 的代码最容易出现的地方，
 * 与"明文禁项"直接冲突。掏空后 tab.html ≈「DOM 结构 + 页面可见文本」，与"只导 DOM 与页面可见文本"
 * 的措辞一致。这一条属设计取舍，非疏漏。
 */
object PageSnapshot {

    /** 正文上限（字符）：超过即截断，并在 tab.txt 里标注。 */
    const val MAX_TEXT_CHARS = 2000

    /** tab.html 上限（字节，UTF-8）：超过即截断，并在 meta.json 里标记。 */
    const val MAX_HTML_BYTES = 512 * 1024

    /** 摘要里最多列多少个可交互节点（超过只列前 N 个并标注）。 */
    const val MAX_NODES = 300

    /** 脱敏替换符——与 [SensitiveRedactor.MASK] 同值（同一个"一律 ***"口径）。 */
    const val MASK = "***"

    // ─────────────────────────────────────────────────────────────────────
    // 数据模型
    // ─────────────────────────────────────────────────────────────────────

    /** 一个可交互节点（摘要的一行）。 */
    data class Node(
        /** 角色：`role` 属性优先，否则按标签/type 推导（textbox / checkbox / button / link / combobox…）。 */
        val role: String,
        /** 名称：aria-label → placeholder → 按钮/链接的可见文字 → title → name。 */
        val name: String,
        /** 当前值（输入框 value / textarea 文本 / select 选中项文本）。 */
        val value: String,
        /** 状态：`disabled` / `checked` / `required` 的空格连接；空串表示无。 */
        val states: String,
    )

    /**
     * 摘要结果。
     * @param nodes 实际列出的节点（可能被 [MAX_NODES] 截断）
     * @param nodesTotal 页面里可交互节点的**总数**（未截断）
     */
    data class Summary(
        val title: String,
        val nodes: List<Node>,
        val nodesTotal: Int,
        val text: String,
        val textTruncated: Boolean,
    ) {
        /** 节点是否被列数上限截断。 */
        val nodesTruncated: Boolean get() = nodesTotal > nodes.size
    }

    // ─────────────────────────────────────────────────────────────────────
    // 1. 摘要（纯函数：HTML 字符串 → Summary）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 解析 HTML 并产出摘要。**不抛异常**：畸形 HTML 一律降级（最多得到空摘要）。
     */
    fun summarize(html: String): Summary {
        val doc = runCatching { parse(html) }.getOrElse { Doc(El("#document"), "") }
        val nodes = ArrayList<Node>()
        val text = StringBuilder()
        runCatching { walk(doc.root, nodes, text) }
        val nodesTotal = nodes.size
        val listed = if (nodesTotal > MAX_NODES) nodes.subList(0, MAX_NODES).toList() else nodes.toList()
        val raw = normalizeText(text.toString())
        val truncated = raw.length > MAX_TEXT_CHARS
        val body = if (truncated) raw.substring(0, MAX_TEXT_CHARS) else raw
        return Summary(doc.title, listed, nodesTotal, body, truncated)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. 明文禁项过滤（纯函数：HTML 字符串 → 已脱敏 HTML 字符串）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 凭据类键名（派工单点名的四个在前；其余是同类——token / csrf / 密码 / 会话）。
     * 命中即把**值**换成 [MASK]，键名保留（agent 仍能看出"这里有个凭据字段"）。
     */
    private val SENSITIVE_WORDS = listOf(
        "cookie", "authorization", "localstorage", "sessionstorage",
        "token", "csrf", "xsrf", "secret", "password", "passwd", "pwd",
        "apikey", "api_key", "api-key", "sessionid", "session_id", "jwt", "bearer", "credential",
    )

    private val SENSITIVE_ALT = SENSITIVE_WORDS.joinToString("|") { Regex.escape(it) }

    /** HTML 注释：不是可见内容，且常被用来塞调试信息 —— 整段删掉。 */
    private val COMMENT = Regex("(?s)<!--.*?-->")

    /** `<script>` / `<style>` 正文（保留标签，掏空内容 —— 见类 KDoc 的偏离说明）。 */
    private val SCRIPT_STYLE_BODY = Regex("(?is)<(script|style)\\b([^>]*)>(.*?)(</\\1\\s*>)")

    /**
     * 属性名本身就像凭据的（`data-token="…"` / `authorization="…"` / `csrf_token="…"`）：
     * 把它的值换成 [MASK]。
     */
    private val SENSITIVE_ATTR = Regex(
        "(?is)\\b([a-z0-9_:-]*(?:$SENSITIVE_ALT)[a-z0-9_:-]*)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s<>]+)"
    )

    /** 一个完整的标签（用来查"名字字段是凭据 → 它携带的值也要打码"）。 */
    private val ANY_TAG = Regex("(?is)<[a-zA-Z][^>]*>")

    /** 标签里的 `value=` / `content=` 属性（`<meta name="csrf-token" content="…">` 是常见形态）。 */
    private val VALUE_ATTR = Regex("(?is)\\b(value|content)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s<>]+)")

    /** 标签里的 `name=` / `id=` / `property=` 属性（判断这个字段是不是凭据字段）。 */
    private val NAMEISH_ATTR = Regex(
        "(?is)\\b(?:name|id|property|formcontrolname|autocomplete)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"
    )

    /**
     * 正文/脚本里的 `key=值` 或 `key: 值`（`cookie=…` / `Authorization: Bearer …`）。
     * 键后允许一个**认证方案词**（Bearer / Basic / Digest / Token）——否则
     * `Authorization: Bearer <真值>` 会只吃掉 "Bearer" 而把真值留在原地。
     */
    private val SECRET_KV = Regex(
        "(?i)\\b($SENSITIVE_ALT)\\b\\s*[:=][\\s\"']*(?:(?:bearer|basic|digest)\\s+)?([^\\s\"',;<>{}\\[\\]]+)[\"']?"
    )

    /**
     * 明文禁项过滤：把导出的 HTML 文本里所有**凭据类值**换成 `***`。
     *
     * 四道（按顺序，前一道的产物喂后一道）：
     * 1. 删 HTML 注释（不是可见内容，且常塞调试信息）；
     * 2. 掏空 `<script>` / `<style>` 正文（见类 KDoc 偏离说明）；
     * 3. 属性名像凭据 → 值打码；任何标签的 `name`/`id`/`property` 像凭据 → 它的
     *    `value`/`content` 打码（`<input type="hidden" name="csrf_token" value="…">` 与
     *    `<meta name="csrf-token" content="…">` 是最常见的两种形态，这时凭据在 **name 的值**里、
     *    要打码的却是 **value/content**，光看属性名抓不到）；
     * 4. 文本里的 `key=值` / `key: 值`（含 `Authorization: Bearer <真值>` 这类认证方案前缀）。
     *
     * 宁可误报（如 prose 里的 "authorization: 请先登录" 被打码）不漏报 —— 与
     * [SensitiveRedactor] 的既定口径一致（用户 2026-10-13 定调：隐私优先）。
     */
    fun scrubSecrets(html: String): String {
        if (html.isEmpty()) return html
        var out = COMMENT.replace(html, "")
        out = SCRIPT_STYLE_BODY.replace(out) { m ->
            "<" + m.groupValues[1] + m.groupValues[2] + ">" + m.groupValues[4]
        }
        out = SENSITIVE_ATTR.replace(out) { m ->
            m.groupValues[1] + "=\"" + MASK + "\""
        }
        out = ANY_TAG.replace(out) { m ->
            val tag = m.value
            val namesLookSensitive = NAMEISH_ATTR.findAll(tag).any { hit ->
                containsSensitiveWord(hit.groupValues[1])
            }
            if (!namesLookSensitive) tag
            else VALUE_ATTR.replace(tag) { v -> v.groupValues[1] + "=\"" + MASK + "\"" }
        }
        out = SECRET_KV.replace(out) { m -> m.groupValues[1] + "=" + MASK }
        return out
    }

    /** 一段文本里是否含凭据类词（大小写无关；`api_key`/`api-key` 都算）。 */
    internal fun containsSensitiveWord(text: String): Boolean {
        val t = text.lowercase()
        return SENSITIVE_WORDS.any { t.contains(it) }
    }

    /**
     * URL 里**额外**要认的凭据参数名（只在 [scrubUrl] 生效，不进 [SENSITIVE_WORDS]）。
     *
     * 为什么单独一张表：`session` / `auth` / `sig` 这些词当**属性名**匹配时会误伤常见表单字段
     * （`name="author"`、`id="authorname"`），进了 [SENSITIVE_WORDS] 会把整页普通字段的值打码、
     * 把摘要读坏；而它们出现在 **URL 参数名**上时几乎一定是凭据（`?session=…` / `?auth=…` /
     * `X-Amz-Signature=…` / `?ticket=…`）。所以按"在哪出现"分表，而不是放宽全局词表。
     */
    private val URL_EXTRA_SENSITIVE = listOf(
        "session", "auth", "oauth", "signature", "sig", "ticket", "sso", "saml",
    )

    /**
     * URL 里的凭据参数值打码 —— [scrubSecrets] 的**第五条漏网路**。
     *
     * `location.href` 是与页面 HTML **完全独立**的一条数据：OAuth 回调常见的
     * `…#access_token=…`、`?session=…`、`?signature=…` 全在 URL 里，不经过 HTML 过滤，
     * 却会原样写进 `tab.txt`（`URL:` 行）与 `meta.json`（`"url"` 字段）——那是明文泄到共享目录。
     *
     * 口径：**保留** scheme / host / path 与参数名（agent 仍知道"这是哪个页面、带什么参数"），
     * 只把**名字像凭据**的参数值换成 [MASK]。非凭据参数（`?page=2`、`?lang=zh`）一字不动。
     *
     * ⚠️ query 与 fragment 要**分开过**（2026-10-15 蓝总管验收补丁）：URL 可以两者都有 ——
     * `…?code=a&lang=zh#refresh_token=X`。若不切开 `#`，整个 fragment 会被粘在最后一个 query
     * 参数的值里（key 仍是 `lang` ⇒ 判为不敏感 ⇒ **原样输出**），fragment 里的凭据就漏了。
     */
    fun scrubUrl(url: String): String {
        if (url.isEmpty()) return url
        val qMark = url.indexOf('?')
        val hash = url.indexOf('#')
        if (qMark < 0 && hash < 0) return url
        // 第一段（scheme/host/path）在 `?` 或 `#` 里**先出现**的那个之前结束
        val headEnd = when {
            qMark >= 0 && (hash < 0 || qMark < hash) -> qMark
            else -> hash
        }
        val queryEnd = if (hash >= 0) hash else url.length
        val query = if (qMark in 0 until queryEnd) url.substring(qMark + 1, queryEnd) else null
        val fragment = if (hash >= 0) url.substring(hash + 1) else null
        return buildString(url.length) {
            append(url, 0, headEnd)
            if (query != null) append('?').append(scrubParams(query))
            if (fragment != null) append('#').append(scrubParams(fragment))
        }
    }

    /** 把 `a=1&b=2` 里名字像凭据的参数值换成 [MASK]；非凭据参数一字不动（[scrubUrl] 的两段共用）。 */
    private fun scrubParams(params: String): String {
        if (params.isEmpty()) return params
        return params.split('&').joinToString("&") { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) {
                part
            } else {
                val key = part.substring(0, eq)
                val bare = key.trimStart('/', '?', '#')
                val sensitive = containsSensitiveWord(bare) ||
                    URL_EXTRA_SENSITIVE.any { bare.lowercase().contains(it) }
                if (sensitive) "$key=$MASK" else part
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. tab.html 上限
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 按 **UTF-8 字节**截到 [limit]，并在多字节字符边界回退（不切出半个字）。
     * @return 截断后的文本 to 是否真的截断过
     */
    fun clampHtmlBytes(html: String, limit: Int = MAX_HTML_BYTES): Pair<String, Boolean> {
        val bytes = html.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limit || limit <= 0) return html to false
        var end = limit
        // UTF-8 续字节形如 10xxxxxx；回退到首个非续字节，保证不劈开多字节字符
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) to true
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. 渲染（纯函数：摘要 + 元信息 → 落盘文本）
    // ─────────────────────────────────────────────────────────────────────

    /** tab.txt 的表头（门禁按它断言格式；改这里等于改 agent 看到的口径）。 */
    const val TXT_HEADER = "玄览页面摘要（给 agent 读）：字段用 | 分隔，空值写 -"

    /**
     * 渲染 `tab.txt`。
     * 结构：头部元信息（标题/URL/导出时间/标签序号）→ 可交互节点逐行 → 正文。
     */
    fun renderTxt(
        summary: Summary,
        url: String,
        exportedAt: String,
        tabIndex: Int,
        tabCount: Int,
    ): String = buildString {
        append(TXT_HEADER).append('\n')
        append("标题: ").append(oneLine(summary.title)).append('\n')
        append("URL: ").append(oneLine(url)).append('\n')
        append("导出时间: ").append(oneLine(exportedAt)).append('\n')
        append("标签: ").append(tabIndex).append('/').append(tabCount).append('\n')
        append("可交互节点: ").append(summary.nodesTotal).append(" 个")
        if (summary.nodesTruncated) append("（只列前 ").append(summary.nodes.size).append(" 个）")
        append('\n')
        append("---\n")
        append("角色 | 名称/aria-label/placeholder | 当前值 | 状态(disabled/checked/required) | 序号\n")
        if (summary.nodes.isEmpty()) {
            append("(无)\n")
        } else {
            summary.nodes.forEachIndexed { i, n ->
                append(oneLine(n.role).ifEmpty { "-" }).append(" | ")
                    .append(oneLine(n.name).ifEmpty { "-" }).append(" | ")
                    .append(oneLine(n.value).ifEmpty { "-" }).append(" | ")
                    .append(oneLine(n.states).ifEmpty { "-" }).append(" | ")
                    .append(i + 1).append('\n')
            }
        }
        append("---\n")
        append("正文（纯文本：已去脚本/样式/隐藏节点）:\n")
        if (summary.text.isBlank()) append("(空)\n") else append(summary.text).append('\n')
        if (summary.textTruncated) {
            append("[正文超过 ").append(MAX_TEXT_CHARS).append(" 字，已截断]\n")
        }
    }

    /** 渲染 `meta.json`（手写 JSON：一个文件三个字段，引 jsoup/Gson 不划算）。 */
    fun renderMetaJson(
        url: String,
        title: String,
        exportedAt: String,
        tabIndex: Int,
        tabCount: Int,
        htmlTruncated: Boolean,
        htmlBytes: Int,
        textTruncated: Boolean,
        nodes: Int,
    ): String = buildString {
        append("{\n")
        append("  \"url\": \"").append(jsonEscape(url)).append("\",\n")
        append("  \"title\": \"").append(jsonEscape(title)).append("\",\n")
        append("  \"exportedAt\": \"").append(jsonEscape(exportedAt)).append("\",\n")
        append("  \"tabIndex\": ").append(tabIndex).append(",\n")
        append("  \"tabCount\": ").append(tabCount).append(",\n")
        append("  \"truncated\": ").append(htmlTruncated || textTruncated).append(",\n")
        append("  \"htmlTruncated\": ").append(htmlTruncated).append(",\n")
        append("  \"textTruncated\": ").append(textTruncated).append(",\n")
        append("  \"htmlBytes\": ").append(htmlBytes).append(",\n")
        append("  \"nodes\": ").append(nodes).append('\n')
        append("}\n")
    }

    /** JSON 字符串转义（手写，覆盖控制字符）。 */
    internal fun jsonEscape(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u").append(String.format("%04x", c.code)) else append(c)
            }
        }
    }

    /** 单行化：`|` 换 `/`（保住分隔语义）、空白折叠成单空格（agent 按行读）。 */
    private fun oneLine(s: String): String =
        s.replace('|', '/').replace(Regex("\\s+"), " ").trim()

    // ─────────────────────────────────────────────────────────────────────
    // 分词器（容错、无异常、无 Android 依赖）
    // ─────────────────────────────────────────────────────────────────────

    private class El(val tag: String) {
        var attrs: Map<String, String> = emptyMap()
        val kids = ArrayList<Any>() // El 或 String（文本节点）
        var parent: El? = null
    }

    private class Doc(val root: El, val title: String)

    /** 空元素（HTML 规范里的 void elements）：不参与配对，不进栈。 */
    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )

    /**
     * 正文/节点都不看的标签（脚本、样式、无脚本降级、模板）。
     *
     * ⚠️ 这里**刻意不含 `head`**：`<title>` 住在 head 里，若把 head 当原始文本整段吃掉，
     * 标题就取不到了（页面的 `<title>` 正是 agent 判断"这是什么页面"的第一线索）。
     * 改成让 walk 递归进 head，由 head 的各个子标签（title/meta/link/base/style/script）
     * 各自被跳过——只有 head 里那种非法的裸文本会漏进来，而空白行在规整阶段会被丢掉。
     */
    private val SKIP_TAGS = setOf(
        "script", "style", "noscript", "template", "title", "meta", "link", "base",
    )

    /** 正文里换行的块级标签（让纯文本保留段落感）。 */
    private val BLOCK_TAGS = setOf(
        "address", "article", "aside", "blockquote", "div", "dl", "dd", "dt", "fieldset",
        "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6",
        "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "table",
        "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
    )

    private const val HTML_LIMIT_CHARS = 512 * 1024 // JS 侧同值（见 BrowserPageExporter）

    /** 解析 HTML 成树；容错（畸形输入不抛）。 */
    private fun parse(html: String): Doc {
        val root = El("#document")
        var cur = root
        var title = ""
        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                appendRawText(cur, html, i, n)
                break
            }
            if (lt > i) appendRawText(cur, html, i, lt)
            if (html.startsWith("<!--", lt)) {
                val end = html.indexOf("-->", lt + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (html.startsWith("<!", lt) || html.startsWith("<?", lt)) {
                val end = html.indexOf('>', lt)
                i = if (end < 0) n else end + 1
                continue
            }
            val gt = findTagEnd(html, lt)
            if (gt < 0) break
            val inner = html.substring(lt + 1, gt)
            i = gt + 1
            if (inner.isEmpty()) continue
            if (inner[0] == '/') {
                val name = inner.substring(1).trim().lowercase().substringBefore(' ')
                var t: El? = cur
                while (t != null && t !== root && t.tag != name) t = t.parent
                if (t != null && t !== root) cur = t.parent ?: root
                continue
            }
            val selfClose = inner.endsWith("/")
            val body = if (selfClose) inner.dropLast(1) else inner
            val sp = body.indexOfFirst { it.isWhitespace() }
            val name = (if (sp < 0) body else body.substring(0, sp)).lowercase()
            if (name.isEmpty() || !name[0].isLetter()) continue
            val attrs = if (sp < 0) emptyMap() else parseAttrs(body.substring(sp))

            // ── 原始文本类标签（内容原样吃，不做标签解析）──
            // ⚠️ 必须排除**空元素**（meta / link / base）：它们没有闭合标签，若也走"找 </meta>"
            //    这条路，就会 `end < 0 ⇒ i = n`、把 `<meta>` 之后的**整篇文档**吞掉
            //    （2026-10-14 实测踩到：带 `<meta name="csrf-token">` 的页面摘要全空）。
            //    空元素只该落成一个无子节点的元素，交给 walk 按 SKIP_TAGS 忽略。
            if (name in SKIP_TAGS && name !in VOID_TAGS) {
                val end = indexOfIgnoreCase(html, "</$name", i)
                if (name == "title" && title.isEmpty()) {
                    val raw = if (end < 0) html.substring(i) else html.substring(i, end)
                    title = decodeEntities(raw).trim()
                }
                i = if (end < 0) n else (html.indexOf('>', end).let { if (it < 0) n else it + 1 })
                continue
            }
            if (name == "textarea") {
                val end = indexOfIgnoreCase(html, "</textarea", i)
                val raw = if (end < 0) html.substring(i) else html.substring(i, end)
                val el = El(name)
                el.attrs = attrs
                el.parent = cur
                el.kids.add(decodeEntities(raw))
                cur.kids.add(el)
                i = if (end < 0) n else (html.indexOf('>', end).let { if (it < 0) n else it + 1 })
                continue
            }

            val el = El(name)
            el.attrs = attrs
            el.parent = cur
            cur.kids.add(el)
            if (!selfClose && name !in VOID_TAGS) cur = el
        }
        return Doc(root, title)
    }

    /** 找标签结束的 `>`（跳过引号内的 `>`）。 */
    private fun findTagEnd(html: String, from: Int): Int {
        var i = from + 1
        var quote = ' '
        while (i < html.length) {
            val c = html[i]
            if (quote != ' ') {
                if (c == quote) quote = ' '
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            }
            i++
        }
        return -1
    }

    private fun indexOfIgnoreCase(hay: String, needle: String, from: Int): Int {
        val limit = hay.length - needle.length
        var i = from
        while (i <= limit) {
            if (hay.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }

    private fun parseAttrs(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && (s[i].isWhitespace() || s[i] == '/')) i++
            if (i >= n) break
            val start = i
            while (i < n && !s[i].isWhitespace() && s[i] != '=' && s[i] != '/') i++
            val key = s.substring(start, i).lowercase()
            while (i < n && s[i].isWhitespace()) i++
            var value = ""
            if (i < n && s[i] == '=') {
                i++
                while (i < n && s[i].isWhitespace()) i++
                if (i < n && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]
                    i++
                    val vs = i
                    while (i < n && s[i] != q) i++
                    value = s.substring(vs, i)
                    if (i < n) i++
                } else {
                    val vs = i
                    while (i < n && !s[i].isWhitespace()) i++
                    value = s.substring(vs, i)
                }
            }
            if (key.isNotEmpty()) out[key] = decodeEntities(value)
            if (i == start) i++ // 兜底：任何情况下都要前进，杜绝死循环
        }
        return out
    }

    private fun appendRawText(cur: El, html: String, from: Int, to: Int) {
        val raw = html.substring(from, to)
        if (raw.isEmpty()) return
        cur.kids.add(decodeEntities(raw))
    }

    // ── 可见性 / 交互性判定 ──

    private fun isHidden(el: El): Boolean {
        if (el.attrs.containsKey("hidden")) return true
        if (el.attrs["aria-hidden"]?.equals("true", ignoreCase = true) == true) return true
        val style = el.attrs["style"]?.filterNot { it == ' ' || it == '\t' }?.lowercase() ?: return false
        return style.contains("display:none") || style.contains("visibility:hidden")
    }

    private fun isInteractive(el: El): Boolean = when (el.tag) {
        "input" -> el.attrs["type"]?.lowercase() != "hidden"
        "a" -> !el.attrs["href"].isNullOrEmpty()
        "select", "textarea", "button", "summary" -> true
        else -> el.attrs.containsKey("role") ||
            el.attrs.containsKey("contenteditable") ||
            el.attrs.containsKey("onclick")
    }

    private fun roleOf(el: El): String {
        el.attrs["role"]?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
        return when (el.tag) {
            "a" -> "link"
            "button" -> "button"
            "select" -> "combobox"
            "textarea" -> "textbox"
            "summary" -> "summary"
            "input" -> when (el.attrs["type"]?.lowercase() ?: "text") {
                "checkbox" -> "checkbox"
                "radio" -> "radio"
                "submit", "button", "reset", "image" -> "button"
                "search" -> "searchbox"
                "range" -> "slider"
                "file" -> "file"
                "number" -> "spinbutton"
                else -> "textbox"
            }
            else -> el.tag
        }
    }

    private fun nameOf(el: El): String {
        el.attrs["aria-label"]?.takeIf { it.isNotBlank() }?.let { return it }
        val type = el.attrs["type"]?.lowercase() ?: ""
        val inputLike = el.tag == "input" || el.tag == "textarea" || el.tag == "select"
        if (inputLike) {
            el.attrs["placeholder"]?.takeIf { it.isNotBlank() }?.let { return it }
            if (el.tag == "input" && type in setOf("submit", "button", "reset", "image")) {
                el.attrs["value"]?.takeIf { it.isNotBlank() }?.let { return it }
            }
            el.attrs["title"]?.takeIf { it.isNotBlank() }?.let { return it }
            return el.attrs["name"]?.takeIf { it.isNotBlank() } ?: ""
        }
        val own = innerText(el)
        if (own.isNotBlank()) return own
        el.attrs["title"]?.takeIf { it.isNotBlank() }?.let { return it }
        return el.attrs["name"]?.takeIf { it.isNotBlank() } ?: ""
    }

    private fun valueOf(el: El): String = when (el.tag) {
        "textarea" -> innerText(el)
        "select" -> selectedOptionText(el)
        "input" -> if ((el.attrs["type"]?.lowercase() ?: "") in setOf("submit", "button", "reset", "image")) {
            ""
        } else {
            el.attrs["value"] ?: ""
        }
        else -> ""
    }

    private fun statesOf(el: El): String {
        val out = ArrayList<String>(3)
        if (el.attrs.containsKey("disabled") || el.attrs["aria-disabled"]?.equals("true", true) == true) {
            out.add("disabled")
        }
        val type = el.attrs["type"]?.lowercase()
        if (type == "checkbox" || type == "radio") {
            if (el.attrs.containsKey("checked") || el.attrs["aria-checked"]?.equals("true", true) == true) {
                out.add("checked")
            }
        }
        if (el.attrs.containsKey("required") || el.attrs["aria-required"]?.equals("true", true) == true) {
            out.add("required")
        }
        return out.joinToString(" ")
    }

    private fun selectedOptionText(select: El): String {
        val opts = ArrayList<El>()
        collectTag(select, "option", opts)
        val picked = opts.firstOrNull { it.attrs.containsKey("selected") } ?: opts.firstOrNull() ?: return ""
        return innerText(picked)
    }

    private fun collectTag(el: El, tag: String, out: MutableList<El>) {
        for (k in el.kids) {
            val c = k as? El ?: continue
            if (c.tag == tag) out.add(c)
            collectTag(c, tag, out)
        }
    }

    /** 元素的可见文字（跳过隐藏/脚本类子树），压成单行。 */
    private fun innerText(el: El): String {
        val sb = StringBuilder()
        collectText(el, sb, depth = 0)
        return normalizeText(sb.toString()).replace('\n', ' ')
    }

    private fun collectText(el: El, sb: StringBuilder, depth: Int) {
        if (depth > 64) return // 深树兜底，防爆栈（畸形输入）
        for (k in el.kids) {
            if (k is String) {
                if (k.isNotBlank()) sb.append(k).append(' ')
                continue
            }
            val c = k as El
            if (c.tag in SKIP_TAGS || isHidden(c)) continue
            collectText(c, sb, depth + 1)
        }
    }

    /**
     * 遍历产出（节点列表 + 正文）。隐藏子树**整体**跳过（节点与正文都不收），
     * 表单控件的内部文本（textarea 的值 / select 的选项）不进正文——它们已经在节点行里了。
     */
    private fun walk(el: El, nodes: MutableList<Node>, text: StringBuilder) {
        for (k in el.kids) {
            if (k is String) {
                if (k.isNotBlank()) text.append(k).append(' ')
                continue
            }
            val c = k as El
            if (c.tag in SKIP_TAGS || isHidden(c)) continue
            if (c.tag in BLOCK_TAGS) text.append('\n')
            if (isInteractive(c)) nodes.add(Node(roleOf(c), nameOf(c), valueOf(c), statesOf(c)))
            when (c.tag) {
                "br" -> text.append('\n')
                "img" -> c.attrs["alt"]?.let { if (it.isNotBlank()) text.append(it).append(' ') }
                "input", "textarea", "select" -> Unit // 控件内容不进正文
                else -> walk(c, nodes, text)
            }
            if (c.tag in BLOCK_TAGS) text.append('\n')
        }
    }

    // ── 文本规整 / 实体解码 ──

    private fun normalizeText(s: String): String =
        s.replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map { it.replace(Regex("[ \\t\\u000B\\f]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private val ENTITY = Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]*);?")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "copy" to "©", "reg" to "®", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "middot" to "·", "times" to "×",
    )

    /** 解码常见 HTML 实体（数值型 + 少量常用命名型；未认识的保持原样）。 */
    internal fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return ENTITY.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
                else -> NAMED_ENTITIES[body.lowercase()] ?: m.value
            }
        }
    }

    private fun codePoint(cp: Int): String =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else ""

    /** JS 侧与 Kotlin 侧共用同一个字符上限（导出器在 JS 里按它预截，Kotlin 再按字节卡）。 */
    internal const val JS_CHAR_LIMIT = HTML_LIMIT_CHARS
}
