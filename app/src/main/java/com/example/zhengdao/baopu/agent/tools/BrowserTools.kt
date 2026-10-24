// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：刀 2 收尾后用户口径「还有浏览器工具你也别忘了，刀2好了，你就也顺手做了啊」＋
// 玄览操控入账（抱朴六工具：open / snapshot / click / type / scroll / screenshot）；
// 第一百七十七批（2026-10-24）补 S1 截图像素进模型 ＋ S2 坐标四件/等待 ＋ S3 自校验。
//
// 四条硬纪律：
// 1. **进程内直调**：抱朴与玄览同进程，走 [BaopuBrowser.gateway] 直接指挥 WebView，
//    不经 HTTP 桥、不要 token（桥是给终端里的 agent 用的另一条路）。
// 2. 读类免确认（snapshot / screenshot / scroll / wait），写类走 CONFIRM（open / click /
//    type / tap / swipe / long_press / key）；真正执行确认归刀 3，本刀先把权限级钉对。
//    **截图另有一道引擎侧授权闸**（`RunEngine.screenshotGate`：会话首次确认＋敏感页每次问）。
// 3. 输出必须先收口：快照与页面文本都按字符上限截断并**明说截断**——静默截断等于骗模型。
// 4. 本文件**零 Android 依赖**：只认 [BrowserGateway] 接口，真身（WebView）由
//    `BaopuBrowserWiring` 在运行时注入；JVM 单测用假网关就能验完全部语义。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.SensitivePage

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

    /**
     * 截一张当前视口图（第一百七十七批：返回**文本 ＋ 工作区相对路径**）。
     *
     * 相对路径是给引擎读图用的（`VisionFiles.load`）；拿不到路径时 [Shot.relPath] 为 null，
     * 工具回执里会明说「只有文件没有像素」——不假装模型能看见。
     */
    fun screenshot(fullPage: Boolean): Shot

    /** 点坐标（CSS px，视口坐标）。回执带 S3 校验行。 */
    fun tap(cssX: Int, cssY: Int): String

    /** 滑动（两端都是 CSS px 视口坐标）。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String

    /** 长按（CSS px ＋ 按住毫秒）。 */
    fun longPress(cssX: Int, cssY: Int, durationMs: Int): String

    /** 按键（enter/tab/backspace/escape）。 */
    fun key(key: String): String

    /** 等待：给 ms 就纯等，给 selector 就轮询到它出现（或预算用完）。 */
    fun waitFor(ms: Int, selector: String?): String

    /**
     * 当前页身份（url/title）。
     *
     * 为什么要它：截图授权闸要判断「这是不是敏感页」，卡片上也要写「截的是哪一页」。
     * 拿不到（玄览没开/没有标签）⇒ null，闸按「未知页面」处理（照样要授权）。
     */
    fun pageInfo(): PageInfo?
}

/** 一次截图的结果：文本（进模型）+ 工作区相对路径（进读图管线，可空）。 */
internal data class Shot(val text: String, val relPath: String?)

/** 当前页身份。 */
internal data class PageInfo(val url: String, val title: String)

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

/** 十一个浏览器工具（表在 [BaopuToolCatalog]，开关在设置页）。 */
internal object BrowserTools {

    const val ARG_URL = "url"
    const val ARG_NEW_TAB = "new_tab"
    const val ARG_SELECTOR = "selector"
    const val ARG_TARGET = "target"
    const val ARG_TEXT = "text"
    const val ARG_DIRECTION = "direction"
    const val ARG_FULL_PAGE = "full_page"
    const val ARG_X = "x"
    const val ARG_Y = "y"
    const val ARG_X1 = "x1"
    const val ARG_Y1 = "y1"
    const val ARG_X2 = "x2"
    const val ARG_Y2 = "y2"
    const val ARG_DURATION = "duration_ms"
    const val ARG_MS = "ms"
    const val ARG_KEY = "key"

    /** 快照字符上限（约 1.5k token；超了就截断并明说）。 */
    const val MAX_SNAPSHOT_CHARS = 6_000

    /** 其它页面文本上限。 */
    const val MAX_TEXT_CHARS = 2_000

    /** 等待上限（毫秒）：与 `BrowserCommands.MAX_WAIT_MS` 同口径，双保险。 */
    const val MAX_WAIT_MS = 5_000

    /** 按住/滑动时长上限（毫秒）。 */
    const val MAX_PRESS_MS = 3_000

    /** 工具名常量（引擎侧的截图授权闸按名字认）。 */
    const val NAME_SCREENSHOT = "browser_screenshot"

    /** 拿不到网关时的统一话术。 */
    const val NO_BROWSER = "玄览还没打开：先在底部切到「玄览」并打开一个页面，再让我操作。" +
        "（抱朴不能替你切页签，也不许绕过这条）"

    // ── 六个老工具 spec（第一百七十七批：screenshot 加了「像素进模型」） ─────────

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
            "点之前建议先 browser_snapshot 看清有哪些元素。回执里会带「点了之后页面变没变」的校验。",
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
            "不自动回车：需要提交时再 browser_click 那个按钮，或 browser_key enter。",
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
        description = "滚动玄览当前页（up/down/left/right）。用于把下方内容滚进视口后再看快照或截图。",
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
        name = NAME_SCREENSHOT,
        description = "给玄览当前页截图。**会尝试把这张图直接给模型看**（通道支持看图时；" +
            "本轮首次截图会先问你一次，敏感页每次都问）。回执带文件路径（证道/截屏/）。" +
            "页面没排版时可能失败，失败会说原因。",
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
        val shot = runCatching { gateway.screenshot(args.optBoolean(ARG_FULL_PAGE, false)) }
            .getOrElse { return@ToolSpec ToolOutcome.Failed("截图失败：${it.message ?: it.javaClass.simpleName}") }
        val warn = if (isSensitivePage(gateway)) "⚠ 敏感页截图。\n" else ""
        val tail = if (shot.relPath == null) {
            "\n（这张图没有可读路径，模型看不到像素；要看内容请用 browser_snapshot）"
        } else {
            ""
        }
        ToolOutcome.Ok(
            warn + cap(shot.text, MAX_TEXT_CHARS) + tail,
            imageRelPaths = listOfNotNull(shot.relPath),
        )
    }

    // ── 第一百七十七批：坐标四件 ＋ 等待 ─────────────────────────────────────

    fun tapSpec(): ToolSpec = ToolSpec(
        name = "browser_tap",
        description = "按坐标点一下玄览当前页（坐标单位 CSS px，**视口坐标**，与截图同一坐标系；" +
            "左上角是 0,0）。适合 canvas/自定义控件这类没有 CSS 选择器的元素。" +
            "回执会带「点了之后页面变没变」的校验。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_X to jsonObjectOf("type" to "integer", "minimum" to 0, "description" to "横坐标（CSS px）"),
                ARG_Y to jsonObjectOf("type" to "integer", "minimum" to 0, "description" to "纵坐标（CSS px）"),
            ),
            "required" to jsonArrayOf(ARG_X, ARG_Y),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val x = args.optInt(ARG_X, -1)
        val y = args.optInt(ARG_Y, -1)
        if (x < 0 || y < 0) return@ToolSpec ToolOutcome.Failed("x/y 必须是非负整数（CSS px）")
        val gateway = BaopuBrowser.require() ?: return@ToolSpec ToolOutcome.Failed(NO_BROWSER)
        val text = runCatching { gateway.tap(x, y) }
            .getOrElse { return@ToolSpec ToolOutcome.Failed("点击失败：${it.message ?: it.javaClass.simpleName}") }
        ToolOutcome.Ok(cap(text, MAX_TEXT_CHARS))
    }

    fun swipeSpec(): ToolSpec = ToolSpec(
        name = "browser_swipe",
        description = "从一点滑到另一点（CSS px 视口坐标），duration_ms 是滑完用时。" +
            "用于翻页、拖动画布；慢滑（800ms 以上）才拖得动可拖元素，快滑是甩动。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_X1 to jsonObjectOf("type" to "integer", "minimum" to 0, "description" to "起点 x"),
                ARG_Y1 to jsonObjectOf("type" to "integer", "minimum" to 0, "description" to "起点 y"),
                ARG_X2 to jsonObjectOf("type" to "integer", "minimum" to 0, "description" to "终点 x"),
                ARG_Y2 to jsonObjectOf("type" to "integer", "minimum" to 0, "description" to "终点 y"),
                ARG_DURATION to jsonObjectOf(
                    "type" to "integer", "minimum" to 60, "maximum" to MAX_PRESS_MS,
                    "description" to "滑完用时（毫秒，默认 300）",
                ),
            ),
            "required" to jsonArrayOf(ARG_X1, ARG_Y1, ARG_X2, ARG_Y2),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val x1 = args.optInt(ARG_X1, -1)
        val y1 = args.optInt(ARG_Y1, -1)
        val x2 = args.optInt(ARG_X2, -1)
        val y2 = args.optInt(ARG_Y2, -1)
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            return@ToolSpec ToolOutcome.Failed("x1/y1/x2/y2 必须是非负整数（CSS px）")
        }
        val dur = args.optInt(ARG_DURATION, 300).coerceIn(60, MAX_PRESS_MS)
        val gateway = BaopuBrowser.require() ?: return@ToolSpec ToolOutcome.Failed(NO_BROWSER)
        val text = runCatching { gateway.swipe(x1, y1, x2, y2, dur) }
            .getOrElse { return@ToolSpec ToolOutcome.Failed("滑动失败：${it.message ?: it.javaClass.simpleName}") }
        ToolOutcome.Ok(cap(text, MAX_TEXT_CHARS))
    }

    fun longPressSpec(): ToolSpec = ToolSpec(
        name = "browser_long_press",
        description = "在一点按住再松手（CSS px 视口坐标），duration_ms 是按住时长（默认 500）。" +
            "常用于唤出长按菜单、选中文字、拖动前的拾起。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_X to jsonObjectOf("type" to "integer", "minimum" to 0, "description" to "横坐标（CSS px）"),
                ARG_Y to jsonObjectOf("type" to "integer", "minimum" to 0, "description" to "纵坐标（CSS px）"),
                ARG_DURATION to jsonObjectOf(
                    "type" to "integer", "minimum" to 50, "maximum" to MAX_PRESS_MS,
                    "description" to "按住毫秒（默认 500）",
                ),
            ),
            "required" to jsonArrayOf(ARG_X, ARG_Y),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val x = args.optInt(ARG_X, -1)
        val y = args.optInt(ARG_Y, -1)
        if (x < 0 || y < 0) return@ToolSpec ToolOutcome.Failed("x/y 必须是非负整数（CSS px）")
        val dur = args.optInt(ARG_DURATION, 500).coerceIn(50, MAX_PRESS_MS)
        val gateway = BaopuBrowser.require() ?: return@ToolSpec ToolOutcome.Failed(NO_BROWSER)
        val text = runCatching { gateway.longPress(x, y, dur) }
            .getOrElse { return@ToolSpec ToolOutcome.Failed("长按失败：${it.message ?: it.javaClass.simpleName}") }
        ToolOutcome.Ok(cap(text, MAX_TEXT_CHARS))
    }

    fun keySpec(): ToolSpec = ToolSpec(
        name = "browser_key",
        description = "给页面按一个键（enter/tab/backspace/escape），发给当前聚焦元素。" +
            "⚠ 回车是页面内合成事件：监听 keydown 的页面能收到，**原生表单隐式提交不保证**。" +
            "要提交表单，优先 browser_click 那个提交按钮。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_KEY to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf("enter", "tab", "backspace", "escape"),
                    "description" to "按键名",
                ),
            ),
            "required" to jsonArrayOf(ARG_KEY),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val key = args.optString(ARG_KEY).trim().lowercase()
        if (key !in setOf("enter", "tab", "backspace", "escape")) {
            return@ToolSpec ToolOutcome.Failed("key 只能是 enter/tab/backspace/escape")
        }
        val gateway = BaopuBrowser.require() ?: return@ToolSpec ToolOutcome.Failed(NO_BROWSER)
        val text = runCatching { gateway.key(key) }
            .getOrElse { return@ToolSpec ToolOutcome.Failed("按键失败：${it.message ?: it.javaClass.simpleName}") }
        ToolOutcome.Ok(cap(text, MAX_TEXT_CHARS))
    }

    fun waitSpec(): ToolSpec = ToolSpec(
        name = "browser_wait",
        description = "等一下：给 ms 就纯等（最多 $MAX_WAIT_MS 毫秒）；给 selector 就等到那个元素出现" +
            "（最多等 ms，默认 2000）。页面加载慢、动画没结束时用它，比反复 snapshot 省事。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_MS to jsonObjectOf(
                    "type" to "integer", "minimum" to 0, "maximum" to MAX_WAIT_MS,
                    "description" to "等待预算（毫秒，默认 2000）",
                ),
                ARG_SELECTOR to jsonObjectOf(
                    "type" to "string",
                    "description" to "等这个 CSS 选择器出现（省略 = 纯等 ms）",
                ),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        timeoutMs = ToolTimeout.BROWSER_MS,
    ) { args ->
        val ms = args.optInt(ARG_MS, 2000).coerceIn(0, MAX_WAIT_MS)
        val selector = args.optString(ARG_SELECTOR).trim().ifBlank { null }
        val gateway = BaopuBrowser.require() ?: return@ToolSpec ToolOutcome.Failed(NO_BROWSER)
        val text = runCatching { gateway.waitFor(ms, selector) }
            .getOrElse { return@ToolSpec ToolOutcome.Failed("等待失败：${it.message ?: it.javaClass.simpleName}") }
        ToolOutcome.Ok(cap(text, MAX_TEXT_CHARS))
    }

    /** 十一个 spec（顺序即 tools 数组顺序）。 */
    fun all(): List<ToolSpec> = listOf(
        openSpec(), snapshotSpec(), clickSpec(), typeSpec(), scrollSpec(), screenshotSpec(),
        tapSpec(), swipeSpec(), longPressSpec(), keySpec(), waitSpec(),
    )

    /** 当前页是不是敏感页（拿不到页面就按「不是」，但授权闸照样会问）。 */
    private fun isSensitivePage(gateway: BrowserGateway): Boolean = runCatching {
        gateway.pageInfo()?.let { SensitivePage.isSensitive(it.url, it.title) } ?: false
    }.getOrDefault(false)

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
