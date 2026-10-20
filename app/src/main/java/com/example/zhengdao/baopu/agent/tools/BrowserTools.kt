// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：刀 2 收尾后用户口径「还有浏览器工具你也别忘了，刀2好了，你就也顺手做了啊」＋
// 玄览操控入账（抱朴六工具：open / snapshot / click / type / scroll / screenshot）。
//
// 四条硬纪律：
// 1. **进程内直调**：抱朴与玄览同进程，走 [BaopuBrowser.gateway] 直接指挥 WebView，
//    不经 HTTP 桥、不要 token（桥是给终端里的 agent 用的另一条路）。
// 2. 读类免确认（snapshot / screenshot / scroll），写类走 CONFIRM（open / click / type）；
//    真正执行确认归刀 3，本刀先把权限级钉对。
// 3. 输出必须先收口：快照与页面文本都按字符上限截断并**明说截断**——静默截断等于骗模型。
// 4. 本文件**零 Android 依赖**：只认 [BrowserGateway] 接口，真身（WebView）由
//    `BaopuBrowserWiring` 在运行时注入；JVM 单测用假网关就能验完全部语义。
package com.example.zhengdao.baopu.agent

/**
 * 抱朴指挥玄览的**唯一接口**（进程内直调）。
 *
 * 为什么不是直接 import `BrowserCommands`：那是 Android/WebView 类，工具层一旦 import 它，
 * 单测就只能在仪器化测试里跑；接口化后 JVM 单测用假实现就能验「截断、权限、错误话术」。
 */
internal interface BrowserGateway {

    /** 在玄览里打开地址（新标签或当前标签）。 */
    fun open(url: String, newTab: Boolean): String

    /** 取当前页的结构快照（可交互元素 + 可见文本摘要）。 */
    fun snapshot(selector: String?): String

    /** 点一个目标（CSS 选择器；`@eN` 句柄归玄览侧后续支持）。 */
    fun click(target: String): String

    /** 往输入框里打字（原生 value setter，触发 input/change）。 */
    fun typeInto(target: String, text: String): String

    /** 滚动当前页（方向：up/down/left/right）。 */
    fun scroll(direction: String): String

    /** 截一张当前视口图，返回**文件路径**（不是像素数据）。 */
    fun screenshot(fullPage: Boolean): String
}

/**
 * 网关的运行时持有者（进程级单例）。
 *
 * 为什么是可变全局：玄览的 `WebView` 只有活跃标签才存在，用户切走就可能被回收；
 * 工具层若在构造时抓死一个引用，就会出现「指向已经销毁的 WebView」（玄览那个老 bug）。
 * ⇒ **每次调用现取**，拿不到就老实回「玄览还没打开」。
 */
internal object BaopuBrowser {
    @Volatile
    var gateway: BrowserGateway? = null

    /** 取网关；没有就回人话（不抛异常——不许把「浏览器没开」升级成整轮崩）。 */
    fun require(): BrowserGateway? = gateway
}

/** 六个浏览器工具（表在 [BaopuToolCatalog]，开关在设置页）。 */
internal object BrowserTools {

    const val ARG_URL = "url"
    const val ARG_NEW_TAB = "new_tab"
    const val ARG_SELECTOR = "selector"
    const val ARG_TARGET = "target"
    const val ARG_TEXT = "text"
    const val ARG_DIRECTION = "direction"
    const val ARG_FULL_PAGE = "full_page"

    /** 快照字符上限（约 1.5k token；超了就截断并明说）。 */
    const val MAX_SNAPSHOT_CHARS = 6_000

    /** 其它页面文本上限。 */
    const val MAX_TEXT_CHARS = 2_000

    /** 拿不到网关时的统一话术。 */
    const val NO_BROWSER = "玄览还没打开：先在底部切到「玄览」并打开一个页面，再让我操作。" +
        "（抱朴不能替你切页签，也不许绕过这条）"

    // ── 六个 spec ────────────────────────────────────────────────────────────

    fun openSpec(): ToolSpec = ToolSpec(
        name = "browser_open",
        description = "在玄览里打开一个网址（http/https）。只认 http(s)，其它 scheme 一律拒。" +
            "打开是异步的：本工具立刻返回，页面读完再用 browser_snapshot 看内容。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_URL to jsonObjectOf(
                    "type" to "string",
                    "description" to "完整网址（如 https://m.bing.com）；省略 scheme 按 https 补",
                ),
                ARG_NEW_TAB to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "true = 新标签打开；默认 false = 当前标签",
                ),
            ),
            "required" to jsonArrayOf(ARG_URL),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val url = args.optString(ARG_URL).trim()
        if (url.isEmpty()) {
            return@ToolSpec ToolOutcome.Failed("url 不能为空")
        }
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme.isNotEmpty() && scheme != "http" && scheme != "https") {
            return@ToolSpec ToolOutcome.Rejected("只允许 http/https（拿到的是「$scheme:」）——其它 scheme 一律拒")
        }
        val gateway = BaopuBrowser.require()
            ?: return@ToolSpec ToolOutcome.Failed(NO_BROWSER)
        var err: String? = null
        val text = runCatching { gateway.open(url, args.optBoolean(ARG_NEW_TAB, false)) }
            .onFailure { err = it.message ?: it.javaClass.simpleName }
            .getOrNull()
        if (text == null) {
            return@ToolSpec ToolOutcome.Failed("打开失败：${err ?: "未知"}")
        }
        ToolOutcome.Ok("已发起打开：$url（异步，稍后用 browser_snapshot 确认）\n" + cap(text, MAX_TEXT_CHARS))
    }

    fun snapshotSpec(): ToolSpec = ToolSpec(
        name = "browser_snapshot",
        description = "取玄览当前页的结构快照：可交互元素（角色/名称/序号）＋可见文本摘要。" +
            "不含像素、不含整页 HTML。超过 ${MAX_SNAPSHOT_CHARS} 字符会截断并明说。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_SELECTOR to jsonObjectOf(
                    "type" to "string",
                    "description" to "只看某个 CSS 选择器范围内的内容（省略 = 整页）",
                ),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        // parallelSafe 缺省 false：只读盘，但抢的是**同一个 WebView** ⇒ 不许并行
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val gateway = BaopuBrowser.require() ?: return@ToolSpec ToolOutcome.Failed(NO_BROWSER)
        val raw = runCatching { gateway.snapshot(args.optString(ARG_SELECTOR).ifBlank { null }) }
            .getOrElse { return@ToolSpec ToolOutcome.Failed("取快照失败：${it.message ?: it.javaClass.simpleName}") }
        ToolOutcome.Ok(cap(raw, MAX_SNAPSHOT_CHARS))
    }

    fun clickSpec(): ToolSpec = ToolSpec(
        name = "browser_click",
        description = "点玄览当前页里的一个元素。target 传 CSS 选择器（如 #submit、a[href*=login]）。" +
            "点之前建议先 browser_snapshot 看清有哪些元素。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_TARGET to jsonObjectOf(
                    "type" to "string",
                    "description" to "CSS 选择器（@eN 句柄待玄览侧支持，暂不接受）",
                ),
            ),
            "required" to jsonArrayOf(ARG_TARGET),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val target = args.optString(ARG_TARGET).trim()
        if (target.isEmpty()) return@ToolSpec ToolOutcome.Failed("target 不能为空")
        if (target.startsWith("@")) {
            return@ToolSpec ToolOutcome.Failed("暂不支持 @eN 句柄，请改用 CSS 选择器")
        }
        val gateway = BaopuBrowser.require() ?: return@ToolSpec ToolOutcome.Failed(NO_BROWSER)
        val text = runCatching { gateway.click(target) }
            .getOrElse { return@ToolSpec ToolOutcome.Failed("点击失败：${it.message ?: it.javaClass.simpleName}") }
        ToolOutcome.Ok(cap(text, MAX_TEXT_CHARS))
    }

    fun typeSpec(): ToolSpec = ToolSpec(
        name = "browser_type",
        description = "往玄览当前页的一个输入框里写入文本（原生 setter，会触发 input/change 事件）。" +
            "不自动回车：需要提交时再 browser_click 那个按钮。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_TARGET to jsonObjectOf("type" to "string", "description" to "输入框的 CSS 选择器"),
                ARG_TEXT to jsonObjectOf("type" to "string", "description" to "要写入的文本（可为空 = 清空）"),
            ),
            "required" to jsonArrayOf(ARG_TARGET, ARG_TEXT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val target = args.optString(ARG_TARGET).trim()
        if (target.isEmpty()) return@ToolSpec ToolOutcome.Failed("target 不能为空")
        if (target.startsWith("@")) {
            return@ToolSpec ToolOutcome.Failed("暂不支持 @eN 句柄，请改用 CSS 选择器")
        }
        val text = args.optString(ARG_TEXT)
        val gateway = BaopuBrowser.require() ?: return@ToolSpec ToolOutcome.Failed(NO_BROWSER)
        val out = runCatching { gateway.typeInto(target, text) }
            .getOrElse { return@ToolSpec ToolOutcome.Failed("写入失败：${it.message ?: it.javaClass.simpleName}") }
        ToolOutcome.Ok(cap(out, MAX_TEXT_CHARS))
    }

    fun scrollSpec(): ToolSpec = ToolSpec(
        name = "browser_scroll",
        description = "滚动玄览当前页（up/down/left/right）。用于把下方内容滚进视口后再看快照。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_DIRECTION to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf("up", "down", "left", "right"),
                    "description" to "方向",
                ),
            ),
            "required" to jsonArrayOf(ARG_DIRECTION),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val dir = args.optString(ARG_DIRECTION).lowercase()
        if (dir !in setOf("up", "down", "left", "right")) {
            return@ToolSpec ToolOutcome.Failed("direction 只能是 up/down/left/right")
        }
        val gateway = BaopuBrowser.require() ?: return@ToolSpec ToolOutcome.Failed(NO_BROWSER)
        val text = runCatching { gateway.scroll(dir) }
            .getOrElse { return@ToolSpec ToolOutcome.Failed("滚动失败：${it.message ?: it.javaClass.simpleName}") }
        ToolOutcome.Ok(cap(text, MAX_TEXT_CHARS))
    }

    fun screenshotSpec(): ToolSpec = ToolSpec(
        name = "browser_screenshot",
        description = "给玄览当前页截一张图，只返回**文件路径**（像素不进上下文）。" +
            "页面还没排版时可能失败，失败会说原因。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_FULL_PAGE to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "true = 整页长图；默认 false = 当前视口",
                ),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        // parallelSafe 缺省 false：只读盘，但抢的是**同一个 WebView** ⇒ 不许并行
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val gateway = BaopuBrowser.require() ?: return@ToolSpec ToolOutcome.Failed(NO_BROWSER)
        val text = runCatching { gateway.screenshot(args.optBoolean(ARG_FULL_PAGE, false)) }
            .getOrElse { return@ToolSpec ToolOutcome.Failed("截图失败：${it.message ?: it.javaClass.simpleName}") }
        ToolOutcome.Ok(cap(text, MAX_TEXT_CHARS))
    }

    /** 六个 spec（顺序即 tools 数组顺序）。 */
    fun all(): List<ToolSpec> = listOf(
        openSpec(), snapshotSpec(), clickSpec(), typeSpec(), scrollSpec(), screenshotSpec(),
    )

    /**
     * 截断（**纯函数**，JVM 直测）。
     *
     * 触顶必须明说：模型看不见「后面还有」，就会以为页面只有这么多内容。
     */
    internal fun cap(text: String, max: Int): String {
        if (text.length <= max) return text
        val omitted = text.length - max
        return text.substring(0, max) + "\n…（已截断：还有约 $omitted 字符没显示；" +
            "需要某一段就带 selector 再看，或先 scroll）"
    }
}
