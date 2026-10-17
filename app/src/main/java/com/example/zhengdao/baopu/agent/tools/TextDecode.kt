// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：进化门禁（eval/cases.json 的 gbk-book 用例）在 2026-10-17 逼出的口径统一：
// 读侧只能有**一份**解码实现。此前 `read_file` 手写了宽松解码，而 `edit_file` /
// `search_in_files` 仍各自硬读 UTF-8 —— 同一个工作区里，读得到的内容改不了、搜不到。
package com.example.zhengdao.baopu.agent

import java.io.File
import java.nio.charset.Charset

/**
 * 文本解码的**唯一真源**（2026-10-17 新建）。
 *
 * 之前的问题不是「没做编码」，是**同一件事有多个盒子**：
 * - `read_file` 自己的宽松解码（GBK 老书能读）；
 * - `edit_file` 用 `readText(Charsets.UTF_8)`（GBK 书改出乱码）；
 * - `search_in_files` 用 `readLines(Charsets.UTF_8)`（GBK 书里搜不到，且是**静默**的）。
 *
 * ⇒ 判定与解码收进这里，三个工具共用；写回用 [Decoded.charset] **保持原编码**，
 * 不许「读了 GBK、写回偷偷变 UTF-8」。
 *
 * ## 能力的诚实边界（别把它当万能识别器）
 * 认得：UTF-8（严格）、UTF-8+BOM、UTF-16LE/BE（靠 BOM）、GBK（双字节配对 + 至少一个汉字）。
 * **不认**：Big5／Shift-JIS／EUC-KR 等其它双字节编码（Big5 繁体的字节配对与 GBK 同形，
 * 会被判成 GBK 解成乱码）、无 BOM 的 UTF-16（与 GBK 不可区分）、GB18030 四字节生僻字
 * （过不了双字节判据）、UTF-32。这些情况一律**明说读不了**或按上文猜，不假装知道。
 */
internal object TextDecode {

    /** 解码结果：[text] 非 null 即成功；否则 [error] 是回给模型的拒绝理由。 */
    class Decoded(
        val text: String?,
        val encoding: String,
        val charset: Charset? = null,
        val error: String? = null,
    )

    private val utf16le: Charset by lazy { Charset.forName("UTF-16LE") }
    private val utf16be: Charset by lazy { Charset.forName("UTF-16BE") }

    /**
     * GBK 解码器（Android 与 JDK 都自带；拿不到就退 GB18030，再拿不到就明说读不了）。
     *
     * 为什么不用 CharsetDecoder 的 REPORT：读侧要**宽松**——老书里偶尔一个坏字节
     * 不该让整本读不出来；坏字节显示成 U+FFFD，比把整本判成「读不了」有用。
     */
    val gbk: Charset? by lazy {
        runCatching { Charset.forName("GBK") }.getOrNull()
            ?: runCatching { Charset.forName("GB18030") }.getOrNull()
    }

    /** 按判定出的编码名取解码器（[FileInfoTool.Encodings] 的取值）。 */
    fun charsetFor(encoding: String): Charset? = when (encoding) {
        FileInfoTool.Encodings.UTF8, FileInfoTool.Encodings.UTF8_BOM -> Charsets.UTF_8
        FileInfoTool.Encodings.UTF16_LE -> utf16le
        FileInfoTool.Encodings.UTF16_BE -> utf16be
        FileInfoTool.Encodings.GBK -> gbk
        else -> null
    }

    /**
     * 宽松解码。编码判定复用 [FileInfoTool.guessEncoding]（BOM/UTF-16/UTF-8 严格/GBK
     * 同一套判据，不另起一套），解码器取 [charsetFor]。
     */
    fun decode(file: File, bytes: ByteArray): Decoded {
        if (bytes.isEmpty()) return Decoded("", FileInfoTool.Encodings.UTF8, Charsets.UTF_8)
        val head = if (bytes.size <= FileInfoTool.SNIFF_BYTES) bytes else bytes.copyOf(FileInfoTool.SNIFF_BYTES)
        val guess = FileInfoTool.guessEncoding(head, FileInfoTool.looksBinary(head))
        if (guess == FileInfoTool.Encodings.BINARY) {
            return Decoded(
                null,
                "",
                null,
                "二进制文件不读：${file.name}（先用 file_info 看类型，别硬读）",
            )
        }
        val charset = charsetFor(guess) ?: return Decoded(
            null,
            guess,
            null,
            "读不了：${file.name} 的编码判不出来（既不是 UTF-8 也不是 GBK/UTF-16），" +
                "可能不是文本。先 file_info 确认，别猜内容。",
        )
        val text = runCatching { String(bytes, charset) }.getOrElse {
            return Decoded(null, guess, null, "读不了这个文件（按 $guess 解不开）：${file.name}")
        }
        // BOM 解出来是 U+FEFF（UTF-16 常见）；不裁掉的话第一行会被它污染
        return Decoded(text.trimStart('\uFEFF'), guess, charset)
    }

    /**
     * 按行切（**复刻 BufferedReader.readLine 的语义**：`\r\n`／`\n`／`\r` 都算终结符，
     * 末尾终结符不产生空行）。老书 CRLF 极常见，`\r` 不许留在行内容里。
     */
    fun splitLines(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                out.add(text.substring(start, i))
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                i++
                start = i
            } else {
                i++
            }
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }

    /**
     * 按**原编码**编码回字节（写回用）。
     *
     * 关键细节：UTF-8 带 BOM 与 UTF-16 要把 BOM 自己补回去——`String.toByteArray` 不会加，
     * 丢了 BOM 就等于把文件悄悄换成另一种编码（对外还是「改了一行」，实际上整本变了）。
     */
    fun encode(text: String, encoding: String): ByteArray = when (encoding) {
        FileInfoTool.Encodings.UTF8_BOM ->
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        FileInfoTool.Encodings.UTF16_LE ->
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(utf16le)
        FileInfoTool.Encodings.UTF16_BE ->
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(utf16be)
        FileInfoTool.Encodings.GBK -> (gbk?.let { text.toByteArray(it) } ?: text.toByteArray(Charsets.UTF_8))
        else -> text.toByteArray(Charsets.UTF_8)
    }
}
