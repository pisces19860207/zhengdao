// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零二批 · 自动 C「粘贴即解析」的**纯函数**半边：
// 收到一段恰好是一个 http(s) 地址的文本 ⇒ 自动判名称（取自主机）与候选传输类型。
//
// 两条纪律：
// 1. **零网络**：这里只看字符串形状，不做任何请求——对外连接的探测归
//    [McpToolSource.connect]（那一步已过授权档）；
// 2. **不越权判死**：传输类型只能给「候选」，真相在连接时实测（Streamable HTTP
//    连不上会自动回退旧式 SSE，见 McpClient.connect）。
package com.example.zhengdao.baopu.mcp

/**
 * 粘贴即解析（自动 C）。
 *
 * 两个消费口共用这里：
 * · 聊天输入：`BaopuChatController.send` 收到单 URL 时落一行解析账（日志证据）；
 * · MCP 面板：URL 输入框一有合法地址就提示「名称将取为 X」（复用现有提示位）。
 */
internal object McpPasteRule {

    /** 一次解析的结论。 */
    data class Parsed(
        /** 清理后的 URL（去首尾空白与句尾标点）。 */
        val url: String,
        /** 自动取的名称（与 [McpServerDraft.fromUrl] 同源）；解析失败为空串。 */
        val name: String,
        /** 候选传输说明（人话，可直接展示/回灌）。 */
        val transportHint: String,
        /** 非 null = 形状像 URL 但不许可（原因人话，来自 [McpUrlRules]）。 */
        val invalid: String?,
    )

    /**
     * 文本是不是「恰好一个 URL」；是 ⇒ 给解析，不是 ⇒ null（调用方什么都不做）。
     *
     * 「恰好一个」的口径：去首尾空白后不含任何换行/全角空格/内部空白，且整体以
     * http(s):// 开头。尾部的中文句号/逗号等标点剥掉再试一次（聊天里最常见的粘贴形）。
     */
    fun singleUrl(raw: String): Parsed? {
        val candidate = trimEdges(raw) ?: return null
        if (!candidate.startsWith("http://", ignoreCase = true) &&
            !candidate.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        if (candidate.any { it.isWhitespace() || it.code == 0x3000 }) return null
        val draft = McpServerDraft.fromUrl(candidate)
        return when (draft) {
            is McpServerDraft.Result.Ok -> Parsed(
                url = draft.config.url,
                name = draft.config.name,
                transportHint = transportHint(draft.config.url),
                invalid = null,
            )
            is McpServerDraft.Result.Invalid ->
                Parsed(candidate, "", transportHint(candidate), draft.message)
        }
    }

    /**
     * 候选传输类型：只按路径形状说「候选」，真相连接时实测。
     *
     * · 路径以 `/sse` 结尾 ⇒ 旧式 SSE 服务器的惯例；
     * · 其余（`/mcp`、根路径、带 query）⇒ Streamable HTTP 是 2025-03-26 起的默认传输。
     */
    fun transportHint(url: String): String {
        val path = try {
            java.net.URI(url).path.orEmpty().trimEnd('/')
        } catch (e: Exception) {
            ""
        }
        val candidate = if (path.endsWith("/sse")) "旧式 SSE" else "Streamable HTTP"
        return "候选传输：$candidate（连接时实测为准，Streamable 连不上会自动回退 SSE）"
    }

    /** 去首尾空白；再去一次句尾标点（。．.，,；;！!？?）后再去空白。都不剩 ⇒ null。 */
    private fun trimEdges(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        text = text.trimEnd('。', '．', '.', '，', ',', '；', ';', '！', '!', '？', '?', '）', ')').trim()
        return text.ifEmpty { null }
    }
}
