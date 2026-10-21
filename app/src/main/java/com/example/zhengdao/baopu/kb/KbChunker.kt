// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单-2026-10-21-第一百二十八批-知识库v2 §1（切块：按标题/段落，保留来源
// 文件路径/行号）。只用 JDK 与仓内既有件（TextDecode 解码、DocxTextExtractor 抽 docx）。
package com.example.zhengdao.baopu.kb

import com.example.zhengdao.baopu.agent.TextDecode
import com.example.zhengdao.terminal.DocxTextExtractor

/**
 * 切块与格式解析（**纯函数**：吃 String/ByteArray 出 [KbChunk]，零 IO 零 Android，JVM 直测）。
 *
 * ## 三种入口（对应派工单的支持格式）
 * - [chunkText]：**已解码**的纯文本（txt/md/log/源码…）——行号 = 原文件行号；
 * - [htmlToText]：html 剥标签（丢 script/style、块级标签转行、实体还原）——剥完再走
 *   [chunkText]，行号 = 提取文本行号（[KbChunk.source] = `extracted`）；
 * - docx：**不在本文件**——`DocxTextExtractor.extract(bytes)` 已在 121 批交付并单测，
 *   索引器直接调它拿正文再走 [chunkText]（同一把尺子，不重写）。
 *
 * ## 切块规则（设计页 §2）
 * md 的 `#` 标题行开新块（标题记进 [KbChunk.heading]）；其余按空行分段；
 * 块攒到 [TARGET_CHARS] 就硬切（保持行号连续）。不设重叠——行号区间是引用的
 * 锚，重叠块会让「第 12-18 行」与「第 17-23 行」互相重叠，引用对不上原文。
 */
internal object KbChunker {

    /** 块目标字符数（可配：构造参数透传，默认值只此一处）。 */
    const val TARGET_CHARS = 400

    /** 切块风格。 */
    enum class ChunkStyle { PLAIN, MARKDOWN }

    /**
     * 把**已解码**的文本切成块。
     *
     * @param relPath 相对被索引目录的路径（写进每个 chunk——引用自包含）
     * @param text 解码后的正文（txt/md 的原文本；html/docx 的提取文本）
     * @param style PLAIN＝空行分段；MARKDOWN＝`#` 标题行也分段
     * @param source `file`（行号是原文件行号）或 `extracted`（提取文本行号）
     */
    fun chunkText(
        relPath: String,
        text: String,
        style: ChunkStyle = ChunkStyle.PLAIN,
        source: String = KbChunk.SOURCE_FILE,
        targetChars: Int = TARGET_CHARS,
    ): List<KbChunk> {
        if (relPath.isBlank() || text.isBlank()) return emptyList()
        val lines = TextDecode.splitLines(text)
        val out = ArrayList<KbChunk>()
        val buf = ArrayList<String>()
        var start = 1          // 当前块首行行号（1 起）
        var heading = ""       // 当前块所属最近标题
        var chars = 0

        fun flush() {
            if (buf.isEmpty()) return
            val body = buf.joinToString("\n").trim()
            if (body.isNotEmpty()) {
                out += KbChunk(
                    relPath = relPath,
                    lineStart = start,
                    lineEnd = start + buf.size - 1,
                    heading = heading,
                    text = body,
                    source = source,
                )
            }
            buf.clear()
            chars = 0
        }

        for (i in lines.indices) {
            val line = lines[i]
            if (style == ChunkStyle.MARKDOWN && line.trimStart().startsWith("#")) {
                flush()
                // 剥掉所有前导 # 再 trim：记进 heading 的是「第三章」而不是「# 第三章」
                heading = line.trim().trimStart('#').trim()
                // 标题行**本身是内容**：开新块的第一行（行号从它算起）——
                // 只把标题记进 heading 而丢掉这一行，引用区间就对不上原文。
                start = i + 1
                buf += line
                chars = line.length + 1
                continue
            }
            // 空行分段（两种风格同一把尺子：md 的空行也是段落边界）
            if (line.isBlank()) {
                flush()
                start = i + 2
                continue
            }
            if (buf.isEmpty()) start = i + 1
            buf += line
            chars += line.length + 1
            if (chars >= targetChars) flush()
        }
        flush()
        return out
    }

    // ── html 剥离（手写扫描：不引解析库；只认常见块级标签，其余全丢） ──────────────

    /** 整段丢掉内容的标签（html 大小写不敏感）。 */
    private val DROP_BLOCKS = listOf("script", "style")

    /** 当换行看的块级标签。 */
    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "li", "ul", "ol", "tr", "table", "section", "article",
        "h1", "h2", "h3", "h4", "h5", "h6", "header", "footer", "blockquote", "pre",
    )

    /** `&nbsp;`（不换行空格，大小写不敏感）——XML 公共实体集里没有它，先换成普通空格。 */
    private val NBSP = Regex("&nbsp;", RegexOption.IGNORE_CASE)

    /**
     * html → 纯文本（**不抛异常**：结构再怪也给出能看的结果，解析失败由调用方按空串跳过）。
     *
     * 规则：`<script>/<style>` 整段丢；块级标签（含自闭合）⇒ 换行；其余标签只去不换行；
     * 实体还原（`&nbsp;` → 空格，其余走 [DocxTextExtractor.unescapeXml]——XML 与 html
     * 的公共实体集一致，不引库）。
     */
    fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        val sb = StringBuilder(html.length)
        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                sb.append(html, i, n)
                break
            }
            if (lt > i) sb.append(html, i, lt)
            val gt = html.indexOf('>', lt)
            if (gt < 0) break // 半个标签：剩下的当正文
            val tag = html.substring(lt + 1, gt)
            val name = tagName(tag)
            if (name.isEmpty()) {
                i = gt + 1
                continue
            }
            if (name in DROP_BLOCKS) {
                // 找到配对闭标签，整段跳过；没有闭标签就只丢这一个标签
                val close = html.indexOf("</$name", gt + 1, ignoreCase = true)
                i = if (close > 0) {
                    val closeGt = html.indexOf('>', close)
                    if (closeGt < 0) n else closeGt + 1
                } else {
                    gt + 1
                }
                continue
            }
            if (name in BLOCK_TAGS) sb.append('\n')
            i = gt + 1
        }
        // &nbsp;（不换行空格）在 XML 实体集之外，先换成普通空格再走公共实体还原
        return normalize(
            DocxTextExtractor.unescapeXml(sb.toString().replace(NBSP, " ")),
        )
    }

    /** 标签名（去 `/` 前缀与属性；`<br/>` → `br`）。 */
    private fun tagName(tag: String): String {
        val t = tag.trim()
        if (t.startsWith("/")) return ""
        if (t.startsWith("!") || t.startsWith("?")) return "" // 注释/声明/处理指令
        var end = t.length
        for (k in t.indices) {
            if (t[k].isWhitespace() || t[k] == '/') { end = k; break }
        }
        return t.substring(0, end).lowercase()
    }

    /** 空白收敛（与 DocxTextExtractor.normalize 同口径：连续空行压一个、段内多空格压一个）。 */
    private fun normalize(s: String): String {
        val lines = s.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { it.replace(Regex("[ \\t]{2,}"), " ").trimEnd() }
        val out = ArrayList<String>(lines.size)
        var blank = false
        for (l in lines) {
            if (l.isBlank()) {
                if (!blank && out.isNotEmpty()) out.add("")
                blank = true
            } else {
                out.add(l)
                blank = false
            }
        }
        return out.joinToString("\n").trim()
    }
}
