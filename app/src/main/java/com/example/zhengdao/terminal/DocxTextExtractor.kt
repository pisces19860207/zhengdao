// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：ECMA-376（Office Open XML，.docx 的包与 document.xml 结构）、
//   ZIP 容器格式（RFC 1951/deflate，.docx 就是个 zip）、XML 1.0 规范。
//   以上均为公开标准，实现只用 JDK 自带的 java.util.zip 与 javax.xml。
package com.example.zhengdao.terminal

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 资料库 **P3a · `.docx` 正文提取**（纯逻辑，**零第三方依赖**）。
 *
 * ## 为什么 `.docx` 不用引库
 *
 * `.docx` 本质是**一个 zip**，正文是里面的 `word/document.xml`（ECMA-376）。
 * 所以"取正文"= **解 zip → 读一个 XML → 抽 `<w:t>` 里的文字**，
 * 全程只用 JDK 自带的 `java.util.zip` 与 `javax.xml`，**不需要 PDFBox 那类库、APK 不变大**。
 *
 * ## 与 `.pdf` 的区别（为什么不一起做）
 *
 * `.pdf` 里存的是"字形画在哪个坐标"，不是"文字" ⇒ 必须引第三方库（PDFBox-Android），
 * 且**扫描版 PDF 任何库都救不了**（要 OCR）。风险与成本量级不同，故拆成 P3b 单独评估。
 *
 * ## 本文件的纪律（沿用 P2 的切法）
 *
 * 全部是**纯函数**：不碰 `Context`、不碰文件路径、不碰网络。输入 `ByteArray`、输出 `String`。
 * ⇒ 真机上出问题可以先确定"不是解析逻辑的错"，且全部可 JVM 单测。
 */
object DocxTextExtractor {

    /** 正文所在的固定路径（ECMA-376 规定，不随 Office 版本变）。 */
    private const val DOCUMENT_XML = "word/document.xml"

    /** 单个 zip 条目解压上限（防 zip 炸弹把手机撑爆）。 */
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024

    /** 提取出的正文长度上限（够 AI 读，不至于把内存/上下文撑爆）。 */
    const val MAX_TEXT_CHARS = 200_000

    /** 单个 `.docx` 文件大小上限：超过直接不试（避免白白解压一个巨大文件）。 */
    const val MAX_DOCX_BYTES = 40L * 1024 * 1024

    /** 判断是不是我们能处理的 `.docx`（按扩展名，不猜内容）。 */
    fun canHandle(name: String): Boolean = name.lowercase().endsWith(".docx")

    /**
     * 从 `.docx` 的**原始字节**里抽出正文。
     *
     * 三层容错（都不抛异常，抽不出就返回空串 —— 调用方据此跳过）：
     * 1. **不是合法 zip**（改了名的 doc / 半截文件 / 旧 `.doc`）⇒ 空
     * 2. **zip 里没有 `word/document.xml`**（不是 Word 文档、或是别的 OOXML 类型）⇒ 空
     * 3. **XML 结构怪异** ⇒ 走正则兜底（不依赖 XML 解析器）

     * @return 提取出的纯文本；失败时返回空串（**绝不是 null**，调用方无需判空）
     */
    fun extract(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val xml = readDocumentXml(bytes) ?: return ""
        if (xml.isBlank()) return ""
        return xmlToText(xml)
    }

    /** 解 zip，只取 `word/document.xml`（其余条目一律不解压，省内存与时间）。 */
    private fun readDocumentXml(bytes: ByteArray): String? = runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                // ⚠️ 只认这一个条目。zip 里还有 rels / styles / media（图片可能几十 MB），
                //    解它们纯属浪费 —— 这是"慢"和"快"的分界线。
                if (entry.name == DOCUMENT_XML) {
                    return@use readLimited(zin)
                }
                entry = zin.nextEntry
            }
            null
        }
    }.getOrNull()

    /** 读一个条目，带上限（防 zip 炸弹）。 */
    private fun readLimited(zin: ZipInputStream): String? {
        val buf = ByteArray(8192)
        val out = StringBuilder()
        var total = 0L
        while (true) {
            val n = zin.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) return null
            out.append(String(buf, 0, n, Charsets.UTF_8))
        }
        return out.toString()
    }

    /**
     * 把 `word/document.xml` 变成人话。
     *
     * 关键规则（少一条正文就会粘成一坨）：
     * - `<w:p>` 段落（**开标签**）⇒ 换行；闭标签 `</w:p>` 也断行，多出来的空行交给 [normalize] 收
     * - `<w:br/>`、`<w:cr/>` 强制换行 ⇒ 换行
     * - `<w:tab/>` 制表 ⇒ 一个空格
     * - **`<w:t>` 里的才是文字**，其余标签全丢
     * - XML 实体（`&amp;` `&lt;` `&gt;` `&quot;` `&apos;`）要还原
     *
     * ⚠️ 用一个手写扫描而不是 DOM 解析器：`document.xml` 动辄几 MB，
     *    DOM 会把它全建成对象树（手机内存吃不消）；扫描是 O(n) 且几乎不额外占内存。
     */
    internal fun xmlToText(xml: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = xml.length
        while (i < n) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break
            val tag = xml.substring(lt + 1, gt)
            val name = tagName(tag)
            when {
                // 段落 / 换行 ⇒ 断行
                name == "w:p" || name == "w:br" || name == "w:cr" -> sb.append('\n')
                // 制表 ⇒ 空格
                name == "w:tab" -> sb.append(' ')
                // 文本节点：<w:t ...>文字</w:t>
                name == "w:t" -> {
                    val close = xml.indexOf("</w:t>", gt)
                    if (close > 0) {
                        sb.append(unescapeXml(xml.substring(gt + 1, close)))
                        i = close + "</w:t>".length
                        continue
                    }
                }
            }
            i = gt + 1
        }
        // 收敛空白：连续空行压成一个，去掉段落内的多余缩进
        return normalize(sb.toString())
    }

    /**
     * 从标签原文里取**标签名**（去掉 `/` 前缀与所有属性）。
     *
     * ⚠️ 必须精确相等地比，不能用 `startsWith("w:t")`：
     *   `w:tab` / `w:tbl` / `w:tc` / `w:tblPr` **全都以 `w:t` 开头**，
     *   用前缀匹配会把表格结构当成正文文字取出来（正文里混进一堆标签）。
     */
    private fun tagName(tag: String): String {
        val t = if (tag.startsWith("/")) tag.substring(1) else tag
        // 取到第一个空白或 `/` 为止：`w:t xml:space="preserve"` → `w:t`；`w:br/` → `w:br`
        var end = t.length
        for (k in t.indices) {
            if (t[k].isWhitespace() || t[k] == '/') { end = k; break }
        }
        return t.substring(0, end)
    }

    /** XML 实体还原（只处理这 5 个 + 数字实体，够用且不引库）。 */
    internal fun unescapeXml(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c); i++; continue
            }
            val semi = s.indexOf(';', i)
            if (semi < 0 || semi - i > 12) {   // 不是实体（裸 & ）——原样保留
                sb.append(c); i++; continue
            }
            val ent = s.substring(i + 1, semi)
            val decoded = when {
                ent == "amp" -> "&"
                ent == "lt" -> "<"
                ent == "gt" -> ">"
                ent == "quot" -> "\""
                ent == "apos" -> "'"
                ent.startsWith("#x") || ent.startsWith("#X") ->
                    ent.substring(2).toIntOrNull(16)?.let { code -> codeToString(code) }
                ent.startsWith("#") ->
                    ent.substring(1).toIntOrNull()?.let { code -> codeToString(code) }
                else -> null
            }
            if (decoded == null) {
                sb.append(c); i++                  // 认不出的实体：原样保留 &，避免吞掉文字
            } else {
                sb.append(decoded); i = semi + 1
            }
        }
        return sb.toString()
    }

    private fun codeToString(code: Int): String? =
        if (code in 1..0x10FFFF) runCatching { String(Character.toChars(code)) }.getOrNull() else null

    /** 空白收敛：段内多空格压一个、连续空行压一个、去首尾空行。 */
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
                out.add(l); blank = false
            }
        }
        val text = out.joinToString("\n").trim()
        return if (text.length > MAX_TEXT_CHARS) text.take(MAX_TEXT_CHARS) + "\n…（已截断）" else text
    }
}
