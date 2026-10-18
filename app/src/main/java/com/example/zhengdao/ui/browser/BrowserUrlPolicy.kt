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

    // ── 地址胶囊的「两态」显示判据（底栏改版 2026-10-18 · 定稿 §6） ───────────────
    //
    // 为什么单独成三个纯函数：底栏只有一条 56dp 带子，地址胶囊常态宽 **195dp**、有前进历史时
    // **143dp**（374.9dp 直板实测数）。143dp 那一档放不下 `https://www.example.com/a/b/c?q=1`
    // 这种整串，但必须读得出「我在哪个站」⇒ 常态只展示 host（＋次级色 path），点开编辑态才
    // 铺完整 URL。展示层一旦在 Compose 里拼字符串，就再也进不了 JVM 单测 —— 所以判据住这里。
    //
    // 三条口径与 Chrome/Safari 一致：剥 scheme、剥 `www.`、默认端口不显示；
    // **不做 punycode 反解**（仓库没有这个依赖，不为一个显示细节引库）⇒ `xn--…` 原样显示。

    /** host 段最长展示数（超出走 [ellipsisMiddle]，保住尾部的注册域）。 */
    private const val HOST_MAX = 26

    /** path 段最长展示数（超出尾部省略）。 */
    private const val PATH_MAX = 24

    /**
     * 常态胶囊第一段 = 主机名（含**非默认**端口）。
     *
     * - `https://www.Example.COM/x` → `example.com`（剥 scheme、剥 `www.`、host 小写）；
     * - `http://example.com:8080/x` → `example.com:8080`（非默认端口保留），
     *   `https://example.com:443` → `example.com`、`http://example.com:80` → `example.com`；
     * - `https://user:pass@secret.example.com/x` → `secret.example.com`（**userinfo 一律不显示**，
     *   免得把 `user:pass@` 当域名铺在界面上）；
     * - 无 authority 的 URL（`about:blank`、`data:…`）⇒ 原样展示（仍截到 [HOST_MAX]）；
     * - 空串 → 空串。
     */
    fun displayHost(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return ""
        val p = parse(raw)
        val host = if (p.authority.isEmpty()) raw else hostLabel(p.scheme, p.authority)
        return ellipsisMiddle(if (host.isEmpty()) raw else host, HOST_MAX)
    }

    /**
     * 常态胶囊第二段 = 路径（次级色）。根路径与无路径返回**空串**（调用方据此不占位）；
     * 查询串与片段不参与展示（`?a=1`、`#x` 全剥）；超 [PATH_MAX] 尾部省略。
     * 无 authority 的 URL 不拆 path（整串已在 [displayHost] 里显示，别重复）。
     */
    fun displayPath(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return ""
        val p = parse(raw)
        if (p.authority.isEmpty()) return ""
        val path = p.path
        if (path.isEmpty() || path == "/") return ""
        return if (path.length > PATH_MAX) path.take(PATH_MAX - 1) + "…" else path
    }

    /** 是否 https（胶囊上的锁形图标：锁 vs 划掉的锁）。 */
    fun isSecure(url: String): Boolean = parse(url.trim()).scheme == "https"

    /**
     * 拆 URL（不用 `android.net.Uri` —— 本类要能在普通 JVM 单测里跑）。
     *
     * @param authority `//` 之后、第一个 `/ ? #` 之前的整段（含 userinfo 与端口）；
     *                  没有 `//`（`about:blank`、`data:…`）时为空串，调用方据此走「原样展示」。
     * @param path authority 之后到第一个 `?`/`#` 之前，含前导 `/`；无则为空串。
     */
    private fun parse(url: String): ParsedUrl {
        val m = SCHEME.find(url)
        val scheme = m?.groupValues?.get(1)?.lowercase().orEmpty()
        val rest = if (m != null) m.groupValues[2] else url
        if (!rest.startsWith("//")) return ParsedUrl(scheme, "", "")
        val body = rest.substring(2)
        val cut = body.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (cut < 0) body else body.substring(0, cut)
        val tail = if (cut < 0) "" else body.substring(cut)
        return ParsedUrl(scheme, authority, tail.substringBefore('?').substringBefore('#'))
    }

    /** [parse] 的结果。 */
    private data class ParsedUrl(val scheme: String, val authority: String, val path: String)

    /** authority → 展示用 host。 */
    private fun hostLabel(scheme: String, authority: String): String {
        // userinfo 不显示（取最后一个 @ 之后）
        val noUser = authority.substringAfterLast('@')
        var host = noUser
        var port = ""
        when {
            // IPv6 字面量：`[::1]:8080` —— 方括号内的冒号不是端口分隔符
            noUser.startsWith("[") -> {
                val end = noUser.indexOf(']')
                if (end >= 0) {
                    host = noUser.substring(0, end + 1)
                    val after = noUser.substring(end + 1)
                    if (after.startsWith(":") && after.length > 1 &&
                        after.substring(1).all { it.isDigit() }
                    ) {
                        port = after.substring(1)
                    }
                }
            }
            else -> {
                val colon = noUser.lastIndexOf(':')
                val tail = if (colon >= 0) noUser.substring(colon + 1) else ""
                if (colon > 0 && tail.isNotEmpty() && tail.all { it.isDigit() }) {
                    host = noUser.substring(0, colon)
                    port = tail
                }
            }
        }
        host = host.lowercase()
        if (host.length > 4 && host.startsWith("www.")) host = host.substring(4)
        val keepPort = port.isNotEmpty() &&
            !(scheme == "https" && port == "443") &&
            !(scheme == "http" && port == "80")
        return if (keepPort) "$host:$port" else host
    }

    /** 中段省略：留头 12 ＋ `…` ＋ 留尾 11（尾部的注册域必须看得见，否则「我在哪个站」读不出）。 */
    private fun ellipsisMiddle(s: String, max: Int): String {
        if (s.length <= max) return s
        return s.take(12) + "…" + s.takeLast(11)
    }
}
