// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开标准：UTF-8（RFC 3629）／UTF-8 BOM（EF BB BF，解码为 U+FEFF）；
// java.nio.charset.CharsetDecoder 的 REPORT 动作语义（JDK 标准库）。
package com.example.zhengdao.baopu.library

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 编码归一（刀 2 B 段第 3 步）——**纯函数**：字节进、文本（或拒绝理由）出。
 *
 * ## 归一只做三件事（派工单 §1 B 段原话：「只归一编码」）
 * 1. **统一 utf-8**：严格解码，**非 UTF-8 ⇒ 拒绝**（理由「不是 UTF-8 编码」）——
 *    不猜 GBK/Big5，猜错就是把乱码当正文写进 `整理/`，比不整理糟糕得多；
 * 2. **去 BOM**：开头的 U+FEFF 去掉（BOM 会让 agent 与 diff 工具都犯恶心）；
 * 3. **去行尾空白**：每行尾部空格/TAB 去掉。
 *
 * ## 行尾 deliberately 不动（偏差，报告里挂账）
 * 派工单写「CRLF→LF 落盘时保留原样判断，只归一编码」；§E.11 写「LF」。
 * 两处口径冲突时按**派工单**执行：只归一编码 ⇒ **CRLF 原样保留**
 * （本类的行尾处理因此必须按「终结符原样、只裁内容尾部空白」实现——
 *  直接 `trimEnd()` 会把 `\r` 当空白吃掉，那就偷偷转了行尾）。
 *
 * ## 产出命名
 * `整理/<去扩展名>.md`；`txt`/`md` **直通**（不加来源头），其它文本件加一行来源头。
 */
internal object Normalizer {

    /** 归一结果。[text] = null 表示拒绝（[reason] 进 `_待外部转换.json`）。 */
    data class Result(val text: String?, val reason: String = "") {
        val ok: Boolean get() = text != null
    }

    private val strictDecoder: ThreadLocal<java.nio.charset.CharsetDecoder> =
        ThreadLocal.withInitial {
            java.nio.charset.Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        }

    /**
     * 归一一段字节。**不抛异常**——所有失败都变成 [Result]（调用方要能逐件报账）。
     *
     * 判据（单测钉死）：UTF-8 带 BOM ⇒ 去 BOM；CRLF ⇒ 行尾原样；行尾空白 ⇒ 裁掉；
     * 非 UTF-8（如 GBK 字节）⇒ 拒绝且理由写明「不是 UTF-8 编码」。
     */
    fun normalize(bytes: ByteArray): Result {
        val text = try {
            val buf = ByteBuffer.wrap(bytes)
            strictDecoder.get().reset().decode(buf).toString()
        } catch (e: CharacterCodingException) {
            return Result(null, "不是 UTF-8 编码（不猜 GBK，请先在外部转成 UTF-8）")
        }
        return Result(stripBom(text).let { trimTrailingSpaces(it) })
    }

    /** 去开头的 BOM（U+FEFF；可能有一个或多个——都去掉）。 */
    internal fun stripBom(text: String): String {
        var i = 0
        while (i < text.length && text[i] == '\uFEFF') i++
        return if (i == 0) text else text.substring(i)
    }

    /**
     * 去每行尾部的空格/TAB，**行尾终结符原样保留**（`\r\n`/`\n`/`\r` 都不动）。
     * 这就是「不转行尾」的实现细节：裁的是内容，不是终结符。
     */
    internal fun trimTrailingSpaces(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var lineStart = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                var end = i + 1
                if (c == '\r' && end < text.length && text[end] == '\n') end++ // \r\n 算一个终结符
                appendTrimmed(sb, text, lineStart, i)
                sb.append(text, i, end)
                lineStart = end
                i = end
            } else {
                i++
            }
        }
        appendTrimmed(sb, text, lineStart, text.length)
        return sb.toString()
    }

    private fun appendTrimmed(sb: StringBuilder, text: String, start: Int, end: Int) {
        var e = end
        while (e > start && (text[e - 1] == ' ' || text[e - 1] == '\t')) e--
        sb.append(text, start, e)
    }

    // ── 产出命名 ─────────────────────────────────────────────────────────────

    /**
     * `整理/` 里的产出名：`<去扩展名>.md`（`a.txt`→`a.md`、`a.md`→`a.md`、`a.json`→`a.md`）。
     * 无扩展名的（本来也进不了流水线）兜底拼 `.md`。
     */
    fun outputName(sourceName: String): String {
        val ext = sourceName.substringAfterLast('.', "")
        val base = if (ext.isEmpty() || sourceName == ext) sourceName else sourceName.dropLast(ext.length + 1)
        return if (base.isEmpty()) sourceName else "$base.md"
    }

    /** `txt`/`md` 直通（不加来源头）；其它文本件加一行来源头（派工单 §1 B 段）。 */
    fun needsSourceHeader(sourceName: String): Boolean {
        val ext = sourceName.substringAfterLast('.', "").lowercase()
        return ext !in setOf("txt", "md", "markdown")
    }

    /** 来源头（**一行**；无时间戳 ⇒ 同输入两次生成字节相同）。 */
    fun sourceHeaderLine(relPath: String): String =
        "> 来源：$relPath（本文件是归一化副本，原件在 App 私有目录）"
}
