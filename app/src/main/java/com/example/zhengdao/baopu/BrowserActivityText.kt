// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19：顶栏跑马灯里给「玄览正在干什么」一行（用户：「顶栏显示 AI 当前在干什么
// （玄览 · 正在读 xxx），别只留工具卡在消息流里」）。
//
// 只做**一句人话**：从引擎的 ToolCallVisible.summary（形如
// `browser_open（{"url":"https://x/y"}）→ ...`）里抠出 url，给出「正在读 host」。
// 抠不出来就退回工具名——不许编一个看起来对的地址。
package com.example.zhengdao.baopu

/** 顶栏一行：玄览当前动作。null ⇒ 本轮没有浏览器动作，别显示。 */
internal fun browserActivityLine(tool: String, summary: String): String? {
    if (!tool.startsWith("browser_")) return null
    val url = extractUrl(summary)
    val host = url?.let { hostOf(it) }
    val action = when (tool) {
        "browser_open" -> if (host != null) "正在读 $host" else "正在开页"
        "browser_snapshot" -> if (host != null) "正在读 $host" else "正在读页面"
        "browser_click" -> if (host != null) "正在点 $host" else "正在点页面"
        "browser_type" -> if (host != null) "正在填 $host" else "正在填输入框"
        "browser_scroll" -> if (host != null) "正在翻 $host" else "正在翻页"
        "browser_screenshot" -> if (host != null) "正在截 $host" else "正在截图"
        else -> return if (host != null) "正在用玄览（$host）" else "正在用玄览"
    }
    return action
}

/** 从 summary 里找 `"url":"…"`；找不到返回 null（不猜）。 */
internal fun extractUrl(summary: String): String? {
    val key = "\"url\""
    val at = summary.indexOf(key)
    if (at < 0) return null
    val colon = summary.indexOf(':', at + key.length)
    if (colon < 0) return null
    var i = colon + 1
    while (i < summary.length && summary[i] == ' ') i++
    if (i >= summary.length) return null
    val quote = summary[i]
    if (quote != '"' && quote != '\'') return null
    val end = summary.indexOf(quote, i + 1)
    if (end <= i + 1) return null
    return summary.substring(i + 1, end)
}

/** 只取主机名（含端口），路径太长在顶栏没意义。 */
internal fun hostOf(url: String): String? {
    val schemeEnd = url.indexOf("://")
    val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
    if (start >= url.length) return null
    val slash = url.indexOf('/', start)
    val host = if (slash > start) url.substring(start, slash) else url.substring(start)
    return host.ifBlank { null }
}
