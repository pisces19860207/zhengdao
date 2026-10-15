package com.example.zhengdao.bridge

/**
 * 桥的命令白名单与策略（设计文档 §4.5「Agent / 浏览器桥接协议」）。
 *
 * ## 这个类在整套桥里的位置
 *
 * ```
 * agent ──POST 127.0.0.1:14080/bridge/v1/<cmd> (+ Basic token)──> BrowserAgentBridge
 *                                                                    │ ① 验 token
 *                                                                    │ ② 查 allowlist ← 本文件
 *                                                                    │ ③ 参数校验     ← 本文件
 *                                                                    ▼
 *                                                            BrowserCommands → WebView
 * ```
 *
 * ## 为什么 allowlist 是**白名单**而不是黑名单
 *
 * 白名单的失效模式是"该放的没放"（用户/agent 发现某个命令用不了，报错明确）；
 * 黑名单的失效模式是"该拦的没拦"（静默放过，可能永远发现不了）。
 * 对一条**给外部 agent 开的高权限通道**，宁可漏放，不可漏拦。
 *
 * ## 权限模型（用户 2026-10-13 定调，设计文档 §4.5）
 *
 * | 区 | 范围 | agent 能否动 |
 * |---|---|---|
 * | **高权限区** | navigate 任意 http/https、click/type（含密码框）、snapshot（含 cookie/localStorage 等页面数据）、execJS（页面内 JS）、scroll、newTab/activeTab/closeTab/status、页面触发的下载落盘 | ✅ 全开 |
 * | **硬红线区**（immutable） | ① WebView 安全配置（文件访问/混合内容/SafeBrowsing 开关）② 导航安全拦截规则（危险 scheme 拦截）③ 本桥 allowlist/端口/token ④ addJavascriptInterface 重绑 ⑤ 读写 App 私有数据/其他 App ⑥ 浏览器 UI/引擎/代码 ⑦ 隐私脱敏开关 | ❌ 全禁 |
 *
 * 红线**不是**靠"在参数里检查出来"实现的——agent 从协议面根本够不到那些层面
 * （它只能发 11 个命令之一，参数只有 url/selector/text/坐标）。
 * 红线在这里的意义是：**把它们写成显式集合并单测钉住**，将来谁想加第 12 个命令
 * （比如"改安全配置"、"读 App 私有文件"），必须先看见这堵墙，而不是默默加个分支。
 * 判据见 [isRedLine] 的用例。
 *
 * ## 纯函数纪律
 *
 * 本文件零 Android 依赖（不 import android.*）⇒ 全部逻辑能在 JVM 上表驱动测试。
 * 凡是"要Context的"（token 存取、WebView 操作）都放 [BridgeToken] 与 [BrowserCommands]，
 * 并且在那里也按"Context-free 内核 + 注入"拆（见 BridgeToken 的 KDoc）。
 */
object BridgePolicy {

    // ── 端点常量 ────────────────────────────────────────────────────────────

    /** 只绑本机（`127.0.0.1`），**绝不绑 `0.0.0.0`** —— 外网够不着，proot 内 agent 经同设备环回可达。 */
    const val BIND_HOST: String = "127.0.0.1"

    /**
     * 候选端口 `14080`（设计文档 §4.5，标注"待定"）。
     *
     * 须避开 OpenCode 的 `14000`。选 14080 而不是 18080：18080 是 AI App Bridge
     * （mobileAiDev/ai-app-bridge）的默认端口，设计文档明确"只参考设计、不引入库"，
     * 端口撞车会让将来真装那个库时冲突。
     */
    const val PORT: Int = 14080

    /** Basic 认证的用户名（固定；口令才是 secret）。 */
    const val BASIC_USER: String = "browser"

    /** 路径前缀：`/bridge/v1/<cmd>`。 */
    const val PATH_PREFIX: String = "/bridge/v1/"

    /** 请求体上限（KB 级足够放一个 `{code}`）；超限直接拒，不读 body。 */
    const val MAX_BODY_BYTES: Int = 64 * 1024

    /** 单次响应字符上限（防 agent 一次拉爆内存/OOM；设计文档 §4.5 execJS 同款要求）。 */
    const val MAX_RESULT_CHARS: Int = 64 * 1024

    /** selector 长度上限（简单 CSS 选择器；防恶意超长串拖垮 JS 注入）。 */
    const val MAX_SELECTOR_CHARS: Int = 512

    // ── 命令白名单 ─────────────────────────────────────────────────────────

    /**
     * 11 条命令（设计文档 §4.5「命令清单」全表，一条不多一条不少）。
     *
     * [wireName] 是协议面上 agent 写的名字 —— 与枚举名分开，因为 `EXEC_JS` 在 Kotlin 里
     * 不能叫 `execJS`（常量名规范），而协议面要跟设计文档逐字对齐（ agent 侧照文档写）。
     */
    enum class Command(val wireName: String) {
        /** 返回可见文本/结构 DOM/页面数据（含 cookie、localStorage）；**离开桥的数据强制脱敏 PII**。 */
        SNAPSHOT("snapshot"),

        /** 页面内 JS 自由执行；结果字符串化、限长、过脱敏。 */
        EXEC_JS("execJS"),

        /** 点击任意元素；选择器限简单 CSS。 */
        CLICK("click"),

        /** 向任意输入框写入（含密码框——密码框只是页面里一个输入框）。 */
        TYPE("type"),

        /** 滚动（按坐标或按元素）。 */
        SCROLL("scroll"),

        /** 加载任意 http/https；危险 scheme 由浏览器本身拦截。 */
        NAVIGATE("navigate"),

        /** 开标签（受标签数上限约束）。 */
        NEW_TAB("newTab"),

        /** 切换 agent 操作的活跃标签。 */
        ACTIVE_TAB("activeTab"),

        /** 关标签。 */
        CLOSE_TAB("closeTab"),

        /** 返回活跃标签 `{url, title, loading}`。 */
        STATUS("status"),

        /** 对活跃标签（或指定标签）截屏，PNG 落系统截图目录，返回路径。 */
        SCREENSHOT("screenshot"),
        ;

        companion object {
            /** 协议面名字 → 命令；白名单外一律 null（调用方据此拒绝）。 */
            fun fromWire(name: String): Command? = entries.firstOrNull { it.wireName == name }
        }
    }

    /** 全部 11 条（给门禁与文档用：数量变了就说明有人加了第 12 条，必须先过红线审视）。 */
    val ALL: List<Command> = Command.entries.toList()

    // ── 硬红线（immutable）────────────────────────────────────────────────

    /**
     * 硬红线动作名（设计文档 §4.5「硬红线区」7 条）。
     *
     * 这些**不是命令**——agent 发不出来。它们是"如果将来有人想给桥加这些能力，
     * 必须先撞见这堵墙"的显式清单。用法：[isRedLine]。
     */
    val HARD_REDLINES: Set<String> = setOf(
        "change-security-config", // ① WebView 安全配置（文件访问/混合内容/SafeBrowsing 开关）
        "change-navigation-policy", // ② 导航安全拦截规则（危险 scheme 拦截）
        "change-allowlist", // ③ 本桥 allowlist
        "change-port", // ③ 本桥端口
        "change-token", // ③ 本桥 token（重新生成走设置页，不经桥）
        "rebind-javascript-interface", // ④ addJavascriptInterface 重绑
        "read-app-private-data", // ⑤ 读 App 私有数据
        "write-app-private-data", // ⑤ 写 App 私有数据
        "touch-other-app", // ⑤ 碰其他 App
        "change-browser-ui", // ⑥ 浏览器 UI
        "change-engine", // ⑥ 引擎/代码
        "disable-redaction", // ⑦ 隐私脱敏开关（agent/AI 不可关闭或绕过）
    )

    /** 某个动作是否撞硬红线。agent 可发的命令永远不在这集合里（有单测钉住）。 */
    fun isRedLine(action: String): Boolean = action in HARD_REDLINES

    // ── 判定结果 ───────────────────────────────────────────────────────────

    /** 一次策略判定的结果。用 sealed interface 而不是 Boolean —— 拒绝时必须带原因（给 agent 看）。 */
    sealed interface Verdict {
        data object Allow : Verdict
        data class Deny(val reason: String) : Verdict
    }

    // ── 命令与参数校验 ─────────────────────────────────────────────────────

    /**
     * 命令名校验：白名单外一律拒。
     *
     * 注意大小写**敏感**——协议面名字与设计文档逐字一致（`execJS` 不是 `execjs`）。
     * 宁可让 agent 明确报错，也不要"猜猜它想发什么"。
     */
    fun checkCommand(raw: String?): Verdict {
        if (raw.isNullOrBlank()) return Verdict.Deny("缺命令名")
        val cmd = Command.fromWire(raw)
            ?: return Verdict.Deny(
                "命令不在白名单：$raw（可用：${ALL.joinToString(", ") { it.wireName }}）",
            )
        // 双保险：白名单里的命令也不许撞红线（防将来有人给 Command 加了个红线项）
        if (isRedLine(cmd.wireName)) return Verdict.Deny("命令撞硬红线：${cmd.wireName}")
        return Verdict.Allow
    }

    /**
     * navigate 的 URL 校验：**只放行 http/https**。
     *
     * 危险 scheme（`file://` / `intent://` / `tel://` / `content://` / `data:` …）由
     * 浏览器本身拦截（红线②），但桥这一侧也要显式拒一遍——双重要比单靠底层稳：
     * 万一某个 WebView 版本对某个 scheme 的默认行为变了，桥这层还拦着。
     */
    fun checkNavigateUrl(raw: String?): Verdict {
        if (raw.isNullOrBlank()) return Verdict.Deny("navigate 缺 url")
        val lower = raw.trim().lowercase()
        val ok = lower.startsWith("http://") || lower.startsWith("https://")
        return if (ok) Verdict.Allow else Verdict.Deny("只允许 http/https，拒绝：$raw")
    }

    /**
     * selector 校验：非空、不超长、不含引号/反斜杠（这两个字符能让 JS 注入变语句注入）。
     *
     * 为什么必须拦引号：selector 最终会被拼进一段 JS 源码。若允许 `'`，agent 传
     * `x'}alert(1)//` 就能闭合字符串、执行任意 JS——那不是"点元素"，是逃逸。
     * 这条是 click/type/scroll 共用的前置。
     */
    fun checkSelector(raw: String?): Verdict {
        if (raw.isNullOrBlank()) return Verdict.Deny("缺 selector")
        if (raw.length > MAX_SELECTOR_CHARS) {
            return Verdict.Deny("selector 超长（>$MAX_SELECTOR_CHARS 字符）")
        }
        if (raw.any { it == '\'' || it == '"' || it == '\\' }) {
            return Verdict.Deny("selector 不许含引号或反斜杠（防 JS 注入逃逸）")
        }
        return Verdict.Allow
    }

    /**
     * snapshot 的 mode 校验：`text` / `dom` / `full`（含 cookie 与 localStorage 的页面数据）。
     *
     * 三种都属高权限区（设计文档 §4.5 明确 snapshot 含页面数据）；**但所有离开桥的数据
     * 都要过 [com.example.zhengdao.browser.SensitiveRedactor]**（红线⑦），
     * 脱敏在 BrowserCommands 侧做，不在这里——本类只判"允不允许"，不碰数据。
     */
    fun checkSnapshotMode(raw: String?): Verdict {
        val mode = raw?.trim()?.lowercase()
        return when (mode) {
            null, "", "text", "dom", "full" -> Verdict.Allow
            else -> Verdict.Deny("snapshot 的 mode 只支持 text / dom / full，收到：$raw")
        }
    }

    /** 路径解析：`/bridge/v1/<cmd>` → 命令名；不合形一律 null。 */
    fun commandFromPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (!path.startsWith(PATH_PREFIX)) return null
        val cmd = path.removePrefix(PATH_PREFIX).substringBefore('?').substringBefore('/')
        return cmd.takeIf { it.isNotBlank() }
    }
}
