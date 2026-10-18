// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 刀 4-B（第二十八批）· 预览槽的**第一个调用方**。
//
// 为什么要这一层：A 段（刀 4）把槽与服务造出来了，但**没有任何调用方**——那是蓝总管立的规矩
// （不许为测试造死接口）。B 段就是把它接上：抱朴把「最近一条回复」渲成一张极简 HTML 卡片
// ⇒ `PreviewSlots.put` ⇒ 用玄览打开 `http://127.0.0.1:<port>/p/<uuid>`。
//
// 口径（裁决后）：
//   1. **本文件只提供两个可被界面调用的函数**：`previewLastReply()` 与 `clearPreviewCache()`。
//      抽屉里那两行由第二十九批加（抽屉整体要重排成工作区树，两个人同时改 `BaopuDrawer.kt` 必打架）
//      ⇒ R8：`ui/baopu/BaopuDrawer.kt` 本批零改动。
//   2. **点了才渲**（R3）：本文件里没有 `@Composable`、没有 `LaunchedEffect`、没有 `init`，
//      `PreviewSlots.put` 只在 `previewLastReply` 的函数体里出现一次。自动渲 / 后台预渲都不许。
//   3. **打开只走 B 路径**（R4）：`BrowserHolder.pendingUrl` ＋ `requestBrowserTab()`，
//      本文件不 new WebView、不新建 Activity、不引依赖。
//   4. **失败不许静默**（R5/R7）：起不来、空回复、清场 0 张，都回**一句可以显示的话**（文案集中在
//      [PreviewNotice] 一处，29 批接线时换成 `stringResource` 也只用改那里）。
//
// 转义纪律（R2）：所有来自回复的文本**先转义再拼标签**——回复里出现 `<script>` 时，卡片里那是
// 六个可见字符，不是可执行内容。整段 HTML 里没有任何一处把未转义的回复文本插进标签或属性。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.preview.PreviewServer
import com.example.zhengdao.preview.PreviewSlots
import com.example.zhengdao.ui.browser.BrowserHolder

/** 一句预览动作的结果。失败必须带**可以直接显示**的一句话（R5）。 */
sealed class PreviewResult {
    /**
     * @param url 已放好、且已交给玄览打开的预览地址（`http://127.0.0.1:<port>/p/<uuid>`）
     * @param id 本次的 uuid（给「删掉这张卡顺手撤掉预览」那种后续留着）
     * @param evicted 本次因满槽被淘汰的 uuid（槽上限 8；没挤掉别人 ⇒ null，它从此 404）
     */
    class Opened(val url: String, val id: String, val evicted: String?) : PreviewResult()

    /** 没渲、没放槽、也没动玄览。 */
    class Failed(val reason: String) : PreviewResult()
}

/**
 * 预览相关的**全部**用户可见文案（一处集中，方便第二十九批接线时换成 `stringResource`）。
 *
 * 为什么写成字面量而不是资源键：[previewLastReply] 与 [clearPreviewCache] 是**无 Context 的普通函数**
 * （R1 要求渲染与判定能在 JVM 里直测），拿不到 `stringResource`。本批因此**不加 strings 键**——
 * 抽屉两行的标题/说明归 29 批，同文件同批加键才不会和蓝总管打架（R6 的对称门禁天然不破）。
 */
object PreviewNotice {
    /** §1.4 钉死的那句话的前半；真起不来时后面还会带上端口候选的失败明细。 */
    const val SERVICE_DOWN = "预览服务起不来（端口被占）"

    /** 没有正文可渲（空回复 / 只有空白）。 */
    const val EMPTY_REPLY = "这条回复没有正文，渲不出卡片"

    /** 槽那边给的其它原因（没绑定端口等）原样带出，只加一个前缀，不改写。 */
    fun putFailed(detail: String): String = "预览没放进去：$detail"

    /** 「已清空 N 张」；**N=0 也要说**（R7：不许静默）。 */
    fun cleared(n: Int): String =
        if (n > 0) "已清空 $n 张预览" else "已清空 0 张预览（槽里本来就没有）"
}

/**
 * 把**最近一条回复**渲成卡片、放进预览槽、交给玄览打开（整条链路一个调用，R3/R4/R5）。
 *
 * 顺序是刻意的：先确认起得来服务 ⇒ 再渲 ⇒ 再放槽 ⇒ **只有放好了**才写 `pendingUrl`。
 * 任何一步失败都直接回 [PreviewResult.Failed]，此时玄览**不受影响**（不给一个连不上的 url）。
 *
 * 服务由本函数在**点击这一刻**起（`PreviewServer.start()` 幂等：已在跑回 AlreadyBound）。
 * 派工单没写「谁来 start」——这是本批的推断，理由：§1.1「点了才渲、不许后台预渲」省电省内存，
 * 一条常驻 socket 线程与这条口径相反；起不来正好落进 §1.4 那句要说话的分支。
 *
 * **真机时序（写在这里免得当成 bug）**：`requestBrowserTab()` 的请求由 MainActivity 的 **ON_RESUME**
 * 消费。终端是独立 Activity（finish ⇒ 必然 ON_RESUME），抱朴是主页里的一个 Tab——**在同一页里点，
 * Tab 不会立刻切过去**，url 已在 `BrowserHolder.pendingUrl` 里等下一次回前台被消费。
 * 修法要么给 29 批的抽屉行传一个「切到玄览」的回调，要么把主页那份消费改成组合期轮询——两处都不在本批白名单里。
 */
fun previewLastReply(assistantText: String): PreviewResult {
    val placed = renderAndPlace(assistantText)
    if (placed is PreviewResult.Opened) openInXuanlan(placed.url)
    return placed
}

/**
 * 清空预览槽，返回**清掉的张数**（R7）。绑定状态不动：槽空了服务还在，下次点仍能放。
 *
 * 张数由调用方显示（见 [PreviewNotice.cleared]）；0 张也要说话，不许「什么都不发生」。
 */
fun clearPreviewCache(): Int {
    val before = PreviewSlots.stats().used
    PreviewSlots.clear()
    return before
}

// ── 渲染（R1：纯函数，JVM 直测，不碰 Android） ──────────────────────────────

/**
 * 一条回复正文 ⇒ 一张极简 HTML 卡片（R1/R2）。
 *
 * 只认三件事：标题、``` 围栏代码块（按 `<pre>` 包一下）、空行分段。
 * **不引 markdown 库**：行内 `**粗体**`、单个反引号、列表都按原样显示（转义后是纯文本），
 * 这是刻意的减法——卡片的作用是「把这条回复读顺」，不是复刻排版。
 *
 * @param markdown 回复正文（可以带围栏代码块）
 * @param title 卡片标题（也会被转义）
 */
fun replyToHtml(markdown: String, title: String): String {
    val body = renderBody(markdown, title)
    return buildString(512) {
        append("<!doctype html>\n")
        append("<html lang=\"zh-CN\">\n<head>\n")
        append("<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>").append(escapeHtml(title)).append("</title>\n")
        append("<style>").append(CARD_CSS).append("</style>\n")
        append("</head>\n<body>\n<article>\n")
        append("<h1>").append(escapeHtml(title)).append("</h1>\n")
        append(body)
        append("</article>\n</body>\n</html>\n")
    }
}

/**
 * 卡片标题：取正文第一行非空白、去掉行首的 markdown 记号，截断到 42 个字符。
 *
 * 为什么不取会话标题：会话标题在库里（要一次 db 读），而这条是**点击路径上的即时读数**——
 * 用回复自己的第一行，纯函数、可单测、也不用碰 `BaopuChatController`（红线）。
 */
fun cardTitle(text: String): String {
    val first = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.replace(Regex("^[#>*+\\-]+\\s*"), "")
        ?.trim()
        .orEmpty()
    if (first.isEmpty()) return "抱朴回复"
    return if (first.length <= TITLE_MAX) first else first.take(TITLE_MAX).trimEnd() + "…"
}

/**
 * `&` 必须**先**转：后转会把 `&lt;` 里的 `&` 再吃一遍变成 `&amp;lt;`。
 * 引号一并转（虽然现在没有把文本插进属性的地方，留着这条纪律免得以后有人加）。
 */
internal fun escapeHtml(raw: String): String = buildString(raw.length + 16) {
    for (ch in raw) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}

/** 正文 ⇒ 卡片主体（分段 ＋ 围栏代码块；全部文本都过 [escapeHtml]）。 */
private fun renderBody(markdown: String, title: String): String {
    val lines = markdown.split("\n")
    // 标题已经在 `<h1>` 里显示过一次：正文第一个非空行如果与它**同源**（同一个派生结果）就不再排第二遍。
    // 这是去重，不是 markdown 解析——`##`／`**`／`-` 这些记号仍然按原样显示（§1.2「不引 markdown 库」）。
    val firstIndex = lines.indexOfFirst { it.isNotBlank() }
    val skipFirst = firstIndex >= 0 && cardTitle(lines[firstIndex]) == title
    val out = StringBuilder(markdown.length + 128)
    var paragraph = ArrayList<String>()   // 当前段里的行（非空、非代码）
    var code: ArrayList<String>? = null   // 非 null ⇒ 正在围栏里
    var sawContent = false

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        out.append("<p>")
        paragraph.forEachIndexed { i, line ->
            if (i > 0) out.append("<br>\n")
            out.append(escapeHtml(line))
        }
        out.append("</p>\n")
        paragraph = ArrayList()
        sawContent = true
    }

    fun flushCode() {
        val block = code ?: return
        out.append("<pre><code>")
        out.append(escapeHtml(block.joinToString("\n")))
        out.append("</code></pre>\n")
        code = null
        sawContent = true
    }

    for ((index, line) in lines.withIndex()) {
        if (skipFirst && index == firstIndex) continue
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            // 围栏切换：开栏先冲掉上一段，闭栏把代码块吐出来（信息串 `java` 之类直接丢掉）
            if (code == null) {
                flushParagraph()
                code = ArrayList()
            } else {
                flushCode()
            }
            continue
        }
        val buffer = code
        if (buffer != null) {
            buffer.add(line)
            continue
        }
        if (trimmed.isEmpty()) {
            flushParagraph()
            continue
        }
        paragraph.add(trimmed)
    }
    // 结尾收口：围栏没闭合也照样吐出来（宁可显示成代码，也不吞用户的半截话）
    flushParagraph()
    flushCode()
    if (!sawContent) out.append("<p class=\"empty\">（这条回复没有正文）</p>\n")
    return out.toString()
}

// ── 接线（可测的接缝：服务起不来这件事要能在 JVM 里演一遍） ──────────────────

/**
 * 渲 ＋ 放槽（**不碰玄览**）——[previewLastReply] 的前半，抽出来是为了能在 JVM 里把三条失败
 * 分支都走一遍：空回复、服务起不来、槽那边拒绝。
 *
 * @param ensureService 默认调 [ensureService]；单测里注入一个「回一句原因」的桩，
 *                      于是 R5 的失败分支不必真把 10 个端口占满也能测（§3 R5 的口径）。
 */
internal fun renderAndPlace(
    assistantText: String,
    ensureService: () -> String? = ::ensureService,
): PreviewResult {
    if (assistantText.isBlank()) return PreviewResult.Failed(PreviewNotice.EMPTY_REPLY)
    ensureService().let { down ->
        if (down != null) return PreviewResult.Failed(down)
    }
    val html = replyToHtml(assistantText, cardTitle(assistantText))
    return when (val placed = PreviewSlots.put(html)) {
        is PreviewSlots.PutResult.Placed ->
            PreviewResult.Opened(url = placed.url, id = placed.id, evicted = placed.evicted)
        is PreviewSlots.PutResult.Failed -> PreviewResult.Failed(PreviewNotice.putFailed(placed.reason))
    }
}

/** 服务就绪 ⇒ null；起不来 ⇒ 那句可以显示的话（§1.4 那句话 ＋ 端口明细）。 */
internal fun ensureService(): String? = when (val started = PreviewServer.start()) {
    is PreviewServer.StartResult.Bound -> null
    is PreviewServer.StartResult.AlreadyBound -> null
    is PreviewServer.StartResult.Failed -> "${PreviewNotice.SERVICE_DOWN}：${started.reason}"
}

/**
 * 交卷给玄览（R4 的全部动作）：**先写 url，再请求切 Tab**——反过来会有一瞬切过去却 load 旧页。
 * 本函数不许 new WebView、不许新建 Activity（与终端链接点击同一条 B 路径）。
 */
internal fun openInXuanlan(url: String) {
    BrowserHolder.pendingUrl = url
    BrowserHolder.requestBrowserTab()
}

/** 卡片标题截断长度。 */
private const val TITLE_MAX = 42

/**
 * 内联样式（§1.2：不引样式框架，深浅各一套）。两套都写在 `@media` 里，
 * 没有「默认色板」——因为 `prefers-color-scheme` 之外还有强制浅/深的场景，
 * 漏一套就会掉进 `color-scheme` 的默认底色上（白字白底）。
 */
private const val CARD_CSS = """
:root{color-scheme:light dark}
body{margin:0;padding:20px 16px 44px;font:16px/1.65 system-ui,-apple-system,"Noto Sans CJK SC",sans-serif;word-break:break-word}
article{max-width:680px;margin:0 auto}
h1{font-size:19px;line-height:1.4;margin:0 0 14px;font-weight:600}
p{margin:0 0 12px}
pre{margin:0 0 14px;padding:12px 14px;overflow:auto;border-radius:8px;font:13px/1.55 ui-monospace,"DM Mono",monospace;white-space:pre-wrap}
.empty{opacity:.6;font-style:italic}
@media (prefers-color-scheme:light){body{background:#fbfaf8;color:#1c1c1e}h1{color:#141416}pre{background:#efece6;color:#242426}}
@media (prefers-color-scheme:dark){body{background:#141518;color:#e6e6e8}h1{color:#f2f2f4}pre{background:#1f2126;color:#dcdcdf}}
"""
