// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.bridge

/**
 * 浏览器命令层的共用参数校验与限长（**纯函数**，零 Android 依赖 ⇒ JVM 表驱动测试）。
 *
 * ## 出身与现状（383 批，2026-11-05）
 *
 * 本文件原是「浏览器桥」——`127.0.0.1:14080` 的 HTTP 端点（给终端里的外部 agent 用）——
 * 的命令白名单与策略文件。桥随终端拆仓失去用途后，已由 383 批按用户裁定**整体删除**：
 * 端点常量（BIND_HOST/PORT/PATH_PREFIX/MAX_BODY_BYTES）、15 条命令白名单（Command）、
 * 命令路由校验（checkCommand/commandFromPath）、Basic token（原 `BridgeToken`）与
 * 硬红线名册（HARD_REDLINES/isRedLine）随之清出，没有替代桥。
 *
 * 这里只留下 [com.example.zhengdao.bridge.BrowserCommands]（抱朴/玄览共用的命令执行层）
 * 仍在用的那部分校验内核：selector / snapshot mode / navigate URL 三道参数闸与结果限长。
 *
 * ## 为什么判定形状保持「白名单式」
 *
 * 白名单的失效模式是"该放的没放"（调用方拿到明确报错）；
 * 黑名单的失效模式是"该拦的没拦"（静默放过，可能永远发现不了）。
 * 对"参数最终会被拼进一段 JS"的这一层，宁可漏放，不可漏拦。
 */
object BridgePolicy {

    /** 单次响应字符上限（防一次拉爆内存/OOM；原 §4.5 execJS 同款要求）。 */
    const val MAX_RESULT_CHARS: Int = 64 * 1024

    /** selector 长度上限（简单 CSS 选择器；防恶意超长串拖垮 JS 注入）。 */
    const val MAX_SELECTOR_CHARS: Int = 512

    // ── 判定结果 ───────────────────────────────────────────────────────────

    /** 一次策略判定的结果。用 sealed interface 而不是 Boolean —— 拒绝时必须带原因（给调用方看）。 */
    sealed interface Verdict {
        data object Allow : Verdict
        data class Deny(val reason: String) : Verdict
    }

    // ── 参数校验 ───────────────────────────────────────────────────────────

    /**
     * navigate 的 URL 校验：**只放行 http/https**。
     *
     * 危险 scheme（`file://` / `intent://` / `tel://` / `content://` / `data:` …）由
     * 浏览器本身拦截（引擎内白名单同口径），但命令这一侧也要显式拒一遍——双重要比
     * 单靠底层稳：万一某个 WebView 版本对某个 scheme 的默认行为变了，本层还拦着。
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
     * 为什么必须拦引号：selector 最终会被拼进一段 JS 源码。若允许 `'`，传
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
     * 三种都属高权限读；**所有离开命令层、流向 AI 上下文的数据都要过
     * [com.example.zhengdao.browser.SensitiveRedactor]**，
     * 脱敏在 BrowserCommands 侧做，不在这里——本类只判"允不允许"，不碰数据。
     */
    fun checkSnapshotMode(raw: String?): Verdict {
        val mode = raw?.trim()?.lowercase()
        return when (mode) {
            null, "", "text", "dom", "full" -> Verdict.Allow
            else -> Verdict.Deny("snapshot 的 mode 只支持 text / dom / full，收到：$raw")
        }
    }
}
