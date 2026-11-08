// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十三批 · 令牌剪贴板通道（派工单 §一）：复制令牌 → MCP 授权处点「从剪贴板读取」
// → 直接写 Vault → 立刻清空剪贴板（尽力而为）。令牌全程不进对话/日志/回执/导出。
//
// 本对象是**纯通道**（JVM 直测）：形状闸＋落库编排。读/清剪贴板在 util/Clipboard.kt
// （Android 侧，只在用户点按钮的当下读一次——不读历史、不做后台监听）。
// 落库复用 McpAuthFlow.importToken（502 的唯一手动写口：加密存储、受众绑定、日志只记事件）；
// 本通道比它多一道更严的形状闸（判据③：含空白/超长即拒——任何空白都不行，首尾也不例外）。
//
// 对话侧只「提示一次」（shouldHintChatToken），绝不自动落库（判据⑤：mcp_set_token 保留，
// 但「从对话里读到的令牌自动进 Vault」这条路不存在——本对象没有任何从对话文本落库的函数）。
package com.example.zhengdao.baopu.mcp

/** 剪贴板 → Vault 的纯通道（513 批）。 */
internal object McpClipboardToken {

    /** 一次通道动作的结论（UI 按它给一句话提示；任何分支都不带令牌内容）。 */
    sealed interface Outcome {
        /** 已进保险柜（内容不回显；调用方随后尽力清空剪贴板）。 */
        data object Saved : Outcome

        /** 拒收（没写库、没落任何内容）。 */
        data class Rejected(val reason: Reason) : Outcome
    }

    enum class Reason {
        /** 剪贴板为空/读不到文本。 */
        EMPTY,

        /** 非令牌形（含空白/控制字符/非 ASCII/超长）。 */
        SHAPE,

        /** 形状过了但写入失败（如地址无法规范化为 resource）。 */
        IMPORT,
    }

    /**
     * 令牌形（判据③的闸）：非空、≤[McpAuthFlow.MAX_IMPORTED_TOKEN]、每个字符都是可见 ASCII
     * （0x21–0x7E）——空白（含空格/换行/首尾空白）、控制字符、中文散文一律拒。
     * 不 trim：判据③的字面口径就是「含空白 ⇒ 拒」。
     */
    fun looksLikeToken(text: String): Boolean =
        text.isNotEmpty() &&
            text.length <= McpAuthFlow.MAX_IMPORTED_TOKEN &&
            text.all { it.code in 0x21..0x7E }

    /**
     * 通道本体：形状闸 → [McpAuthFlow.importToken] 落库。
     * 返回 [Outcome.Saved] 后调用方才清剪贴板；拒收时剪贴板原样不动（不是我们的内容，不代删）。
     */
    fun importText(auth: McpAuthFlow, config: McpServerConfig, clipboardText: String?): Outcome {
        if (clipboardText.isNullOrEmpty()) return Outcome.Rejected(Reason.EMPTY)
        if (!looksLikeToken(clipboardText)) return Outcome.Rejected(Reason.SHAPE)
        return if (auth.importToken(config, clipboardText)) Outcome.Saved else Outcome.Rejected(Reason.IMPORT)
    }

    /**
     * 对话里是否带着令牌形单词（只决定要不要提示，不拦截、不落库、不记内容）。
     * 按空白切词：词要 ≥20 位、只含字母数字 _ . -、且字母数字各至少一个
     * （散文长词/网址段大多被这两条挡掉；提示是劝告，宁可漏报不误报）。
     */
    fun containsTokenWord(text: String): Boolean =
        TOKEN_WORD.findAll(text).any { m ->
            val w = m.value
            w.any { it.isDigit() } && w.any { it.isLetter() }
        }

    /** 「提示一次」的进程内账（true 过一次就永远 false，直到进程重启；测试可清）。 */
    @Volatile
    private var chatHintShown = false

    /** 对话侧提示判定：命中且本进程还没提示过 ⇒ true（恰好一次）。 */
    fun shouldHintChatToken(text: String): Boolean {
        if (chatHintShown) return false
        if (!containsTokenWord(text)) return false
        chatHintShown = true
        return true
    }

    /** 清空（测试专用：谁用谁还）。 */
    fun resetForTest() {
        chatHintShown = false
    }

    private val TOKEN_WORD = Regex("[A-Za-z0-9_.-]{20,}")
}