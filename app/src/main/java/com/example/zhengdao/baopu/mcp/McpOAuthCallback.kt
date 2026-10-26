// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十六批 · MCP OAuth 2.1：**回调 deeplink 的纯解析**。
//
// 回调是**外部可投递**的（任何 App 都能发 `zhengdao://mcp/oauth?...`），所以这里按
// 「不可信输入」处理：只认 state/code/error 三个字段，重复参数、超长、控制字符一律拒。
// 真正的安全闸在 [McpPkce.stateMatches]（state 必须与本次发起的 pending 逐字相等）。
//
// 纪律：code 是**一次性凭据**——不打印、不进日志、不 toString（[toString] 打码）。
package com.example.zhengdao.baopu.mcp

import java.net.URLDecoder

/** 一次 OAuth 回调（已拍平成纯数据）。 */
internal data class McpOAuthCallback(
    /** 授权码（成功时非空）。 */
    val code: String?,
    /** CSRF 凭据；**必填**（缺了整条回调作废）。 */
    val state: String,
    /** 授权服务器回的错误码（`access_denied` 等）。 */
    val error: String?,
    val errorDescription: String?,
) {
    /** 永远不要把 code 打出来：它一次就能换 token。 */
    override fun toString(): String =
        "McpOAuthCallback(state=${if (state.isEmpty()) "空" else "***"}, " +
            "code=${if (code == null) "无" else "***${code.length} 字符"}, error=${error ?: "无"})"
}

/** 解析结果（畸形与"用户取消"分开：前者记日志、后者给用户看）。 */
internal sealed interface McpOAuthCallbackParse {
    data class Ok(val callback: McpOAuthCallback) : McpOAuthCallbackParse

    /** 畸形（重复参数/超长/控制字符/什么都没带）—— 当作**不是**我们的回调。 */
    data class Invalid(val reason: String) : McpOAuthCallbackParse
}

internal object McpOAuthCallbacks {

    /** 回调 deeplink 的 host 与 path（Manifest 的 intent-filter 与这里必须一致）。 */
    const val HOST = "mcp"
    const val PATH = "/oauth"

    private const val MAX_STATE = 256
    private const val MAX_CODE = 4096
    private const val MAX_ERROR = 64
    private const val MAX_DESC = 512
    private const val MAX_QUERY = 8192

    /**
     * 解析 `code=…&state=…` 这种 query（**未解码**的原文，解码在这里做）。
     *
     * 拒绝口径：
     * · 没有 state ⇒ 拒（没有 CSRF 凭据的回调不能进流程）；
     * · code 与 error 同时有 / 同时没有 ⇒ 拒（语义矛盾，多半是拼出来的）；
     * · 同名参数出现两次 ⇒ 拒（"看到的是 A、用的是 B"的经典构造）。
     */
    fun parse(rawQuery: String?): McpOAuthCallbackParse {
        if (rawQuery.isNullOrEmpty()) return McpOAuthCallbackParse.Invalid("回调没有参数")
        if (rawQuery.length > MAX_QUERY) return McpOAuthCallbackParse.Invalid("回调参数过长")

        val map = HashMap<String, String>()
        for (part in rawQuery.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val rawKey = if (eq < 0) part else part.substring(0, eq)
            val rawValue = if (eq < 0) "" else part.substring(eq + 1)
            val key = decode(rawKey) ?: return McpOAuthCallbackParse.Invalid("回调参数名编码不合法")
            val value = decode(rawValue) ?: return McpOAuthCallbackParse.Invalid("回调参数值编码不合法")
            if (map.containsKey(key)) return McpOAuthCallbackParse.Invalid("回调里「$key」出现了两次")
            map[key] = value
        }

        val state = map["state"]?.trim().orEmpty()
        if (state.isEmpty()) return McpOAuthCallbackParse.Invalid("回调没有 state")
        if (state.length > MAX_STATE) return McpOAuthCallbackParse.Invalid("state 过长")
        if (hasControl(state)) return McpOAuthCallbackParse.Invalid("state 含控制字符")

        val code = map["code"]?.takeIf { it.isNotBlank() }
        val error = map["error"]?.trim()?.takeIf { it.isNotEmpty() }
        if (code == null && error == null) return McpOAuthCallbackParse.Invalid("回调既没有 code 也没有 error")
        if (code != null && error != null) return McpOAuthCallbackParse.Invalid("回调同时带了 code 与 error")
        if (code != null) {
            if (code.length > MAX_CODE) return McpOAuthCallbackParse.Invalid("授权码过长")
            if (hasControl(code)) return McpOAuthCallbackParse.Invalid("授权码含控制字符")
        }
        if (error != null && (error.length > MAX_ERROR || hasControl(error))) {
            return McpOAuthCallbackParse.Invalid("错误码不合法")
        }
        val desc = map["error_description"]?.takeIf { it.isNotBlank() }
            ?.take(MAX_DESC)
            ?.takeIf { !hasControl(it) }

        return McpOAuthCallbackParse.Ok(
            McpOAuthCallback(code = code, state = state, error = error, errorDescription = desc),
        )
    }

    /** URI 口径的百分号解码（`+` 是字面加号，不是空格）。畸形 `%` 序列返回 null。 */
    private fun decode(raw: String): String? = runCatching {
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    }.getOrNull()

    private fun hasControl(text: String): Boolean = text.any { it.code < 0x20 || it.code == 0x7f }
}
