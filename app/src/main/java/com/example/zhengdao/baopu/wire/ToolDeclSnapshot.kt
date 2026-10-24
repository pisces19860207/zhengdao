// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十八批 §1.7（2026-10-24 用户点头加的第 7 件，最值钱的一件）：
// 运行期「工具声明快照」——每次发模型请求前记**一行**结构化日志。
//
// 病灶：那晚调试用的 `[gemini-decls]` 是临时日志，已经不在代码里（只剩注释
// 提到 LlmClient.kt:361）⇒ 运行时没有任何「这次到底发了哪些工具声明」的记录
// ⇒ 抱朴再说「工具无法调用」时只能靠猜。本文件把那一行变成**常驻**判据：
//
//   [tool-decls] channel=<id> protocol=<协议> tools.count=<n> tools.digest=<8hex>
//     schema.digest=<8hex> filtered=<none|名=原因;…> budget=<裁掉几个|0>
//
// 四问定位（「工具不可用」时靠这一行就能答）：
//   ① 压根没发出去？⇒ tools.count=0；
//   ② 发出去了但形状被改？⇒ schema.digest 与预期不符（转换逻辑改了它必变）；
//   ③ 被过滤？⇒ filtered 非 none；
//   ④ 被预算裁掉？⇒ budget 非 0。
//
// 隐私口径：只记**哈希与名单摘要**，绝不记参数值（schema.digest 是 schema 形状的
// 哈希，不是参数内容；工具名是名单摘要，允许）。
package com.example.zhengdao.baopu.wire

import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import java.security.MessageDigest
import org.json.JSONArray

/**
 * 工具声明快照（纯函数：吃 protocol + OpenAI 形状的 tools 数组，出一行日志）。
 *
 * 为什么 digest 不直接哈希请求体：请求体含历史正文（泄漏面＋体积）；这里只哈希
 * **工具名清单**与**各家转换后的 schema**——同一组工具多次请求必须一致，变了
 * 就是被过滤/被截断/转换逻辑被改。
 */
internal object ToolDeclSnapshot {

    /** 行首锚（可 grep；与历史上的 `[gemini-decls]` 同风格）。 */
    const val TAG = "[tool-decls]"

    /**
     * @param provider 当前通道（channel id ＋ protocol）
     * @param tools 本次实际发出的工具声明（OpenAI 形状；Gemini 在 encode 里转）
     * @param filtered 被能力/工具组过滤掉的名单摘要（`none` 或 `名=原因;…`，调用方算）
     * @param budgetCut 上下文预算下被裁掉的条数（当前没有任何裁剪机制 ⇒ 恒 0；
     *                  字段先立住，将来有裁剪时这里就是「问题④」的答案）
     */
    fun of(
        provider: Provider,
        tools: JSONArray,
        filtered: String = "none",
        budgetCut: Int = 0,
    ): String {
        val names = toolNames(tools)
        return "$TAG channel=${provider.id} protocol=${provider.protocol.name} " +
            "tools.count=${tools.length()} tools.digest=${sha8(names.joinToString(","))} " +
            "schema.digest=${sha8(schemaText(provider.protocol, tools))} " +
            "filtered=$filtered budget=$budgetCut"
    }

    /** 工具名清单（OpenAI 平铺与 Anthropic/Gemini 的两种形状都吃）。 */
    fun toolNames(tools: JSONArray): List<String> = (0 until tools.length()).map { i ->
        val o = tools.optJSONObject(i)
        o?.optJSONObject("function")?.optString("name") ?: o?.optString("name").orEmpty()
    }

    /**
     * schema 摘要的原料：**各家转换后**的形状。
     *
     * Gemini 走 [GeminiWire.toolDecls]（含 sanitizeSchema 白名单）——改一行
     * 转换逻辑，digest 必变（这条防「改了转换没人知道」）；另两档原样即是线上形状。
     */
    private fun schemaText(protocol: LlmProtocol, tools: JSONArray): String = when (protocol) {
        LlmProtocol.GEMINI_NATIVE -> GeminiWire.toolDecls(tools).toString()
        LlmProtocol.OPENAI_COMPAT, LlmProtocol.ANTHROPIC -> tools.toString()
    }

    /** sha256 前 8 位十六进制（够区分，又不把整表写进日志）。 */
    private fun sha8(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)
}
