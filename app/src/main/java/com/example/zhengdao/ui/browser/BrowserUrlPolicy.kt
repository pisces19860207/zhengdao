// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.browser

/**
 * 地址栏 / 起始页输入的**纯函数策略**（第51刀 ＝ §B 82 的 N7 修复，2026-10-16）。
 *
 * 为什么要把这两条判据单独拎出来：两者原来一并在 `BrowserScreen.tryNavigate` 里，而
 * **顺序错了就永远不可达** ——
 *
 * 真机现象（第㊾刀 C-3，2026-10-16）：地址栏输入 `intent://x` 点「前往」→ 跳去必应搜索
 * `intent://x` 的结果页，**「不支持该协议」的提示一次都没出现过**。
 *
 * 根因：`tryNavigate` 先调 `normalizeInput`。归一化对"不像 URL"的输入（无 `.`、有空格）
 * 一律当搜索词，包成 `https://www.bing.com/search?q=…`；`intent://x` 恰恰不像 URL ⇒
 * 预检拿到的 scheme 恒为 `https` ⇒ `if (scheme !in setOf("http","https")) return false`
 * 这条分支在真实输入上**不可达**（只有归一化后仍是别的 scheme 才可能命中，而这不可能）。
 *
 * 本类不做 Android 调用（`Uri.encode` 由调用方以 [normalize] 的 `encode` 参数注入），
 * 于是两条判据都能被普通 JVM 单测直接打到真行为，而不是靠读源码。
 */
internal object BrowserUrlPolicy {

    /** 显式 scheme：`scheme:` 形态（RFC 3986 的 scheme 字符集）。 */
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*):(.*)$", RegexOption.DOT_MATCHES_ALL)

    /** 唯一放行的两个 scheme —— 与引擎内白名单一致（`BridgePolicy.checkNavigateUrl` 同口径）。 */
    private val ALLOWED = setOf("http", "https")

    /**
     * 点名的"非浏览器协议"（拦下并提示）。为什么不写成"非 http/https 一律拦"：
     * `localhost:3000` / `example.com:8080` 在语法上与 `tel:10086` 无法区分（都是 `字母:数字`），
     * 一刀切会把裸域名带端口误判成协议。所以：**点名协议 + 任何 `xxx://` 形态**才拦。
     */
    private val DENIED = setOf(
        "intent", "javascript", "file", "content", "data", "mailto", "tel", "sms",
        "ftp", "ftps", "blob", "about", "chrome", "market", "ws", "wss", "view-source",
    )

    /**
     * 该输入是否必须**在归一化之前**被拦下（调用方据此给"不支持该协议"的提示）。
     *
     * 判据（三条，尽量少误伤）：
     * 1. 前缀不像 scheme（无 `:`，或首字符不是字母）⇒ 不拦；空串不拦。
     * 2. scheme 是 `http` / `https` ⇒ 不拦（大小写无关，允许前后空白）。
     * 3. 其余：scheme 在 [DENIED] 里 ⇒ 拦；或后段以 `//` 开头（`foo://bar` 这种 authority
     *    形态）⇒ 拦。`example.com:8080`、`localhost:3000/x` 这类"裸域名带端口"不在 [DENIED]
     *    且没有 `//` ⇒ 不拦，交给 [normalize] 按裸域名补 `https://`。
     *
     * 已知取舍：`a:b` 这种"单字母冒号"当搜索词用不会被拦（当裸域名处理）。判据宁可少数
     * 歧义输入走搜索，也要保证真协议（`intent://` 等）一定给提示而不是被静默当搜索词提交。
     */
    fun shouldReject(raw: String): Boolean {
        val input = raw.trim()
        if (input.isEmpty()) return false
        val m = SCHEME.find(input) ?: return false
        val scheme = m.groupValues[1].lowercase()
        if (scheme in ALLOWED) return false
        if (scheme in DENIED) return true
        return m.groupValues[2].startsWith("//")
    }

    /**
     * 输入归一化（§4.2 用户模式要点，行为与第㊽-D 起 `BrowserScreen.normalizeInput` 逐字一致）：
     * - 空串 → 空（调用方无动作）；
     * - 不含点号或含空格 → 当搜索词走 bing（与 `TerminalActivity` 的 openWebSearch 兜底一致）；
     * - `http://` → `https://`（外部明文被网络策略拦，升级避免加载失败）；
     * - 其他 → 补 `https://`。
     *
     * @param encode 搜索词的百分号编码（线上由 `Uri.encode` 注入，测试里可注入恒等函数）。
     */
    fun normalize(raw: String, encode: (String) -> String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val looksLikeUrl = t.contains(".") && !t.contains(" ")
        if (!looksLikeUrl) {
            return "https://www.bing.com/search?q=" + encode(t)
        }
        return when {
            t.startsWith("http://", ignoreCase = true) -> "https://" + t.substring("http://".length)
            t.startsWith("https://", ignoreCase = true) -> t
            else -> "https://$t"
        }
    }
}
