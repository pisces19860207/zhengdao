// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开标准：ECMA-376（WordprocessingML 的 `w:t` 文本节点）、ZIP 容器格式（RFC 1951/deflate）。
// 只用 JDK 自带的 java.util.zip —— 依然**零新增依赖**（632 判据⑤的口径本单继续守）。
//
// 第六百四十二批（2026-11-14）· 模板替换（判据④）：
// 「用户给一份 docx 当模板，把 {{占位符}} 换成值」——**只做这一件事**。
//
// 边界（派工单 §一④，逐条落在这里）：
//   · 只替换 `{{名字}}`：不动样式、不做循环（`{{#each}}`）、不做条件（`{{#if}}`）：
//     模板语言一旦有控制流，就变成"半个模板引擎"，出错时用户看到的是"文档少了半页"；
//   · **缺字段一律整次失败**并点名（"缺少字段 X"），绝不静默留空——
//     留空的合同/通知书比报错危险得多，用户以为发出去是对的；
//   · 不碰宏：模板里带 `vbaProject.bin`（.docm）当场拒（安全红线）。
//
// 为什么按 XML 文本节点替换、而不是反序列化整棵树：
//   docx 的排版全在 `document.xml` 的标签里（样式、编号、表格宽度…）。用 DOM 走一遍再
//   序列化回去，命名空间前缀、属性顺序、空白都可能变——Word 打不开或版式位移。
//   只改 `<w:t>` 里的字、其余字节照抄，是唯一"改字不改版"的做法。
package com.example.zhengdao.baopu.office

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 替换结果：新包字节 ＋ 读数（回执给人看）。 */
internal data class TemplateApplied(
    /** 新 docx 的字节。 */
    val bytes: ByteArray,
    /** 模板里出现的占位符（按首次出现顺序）。 */
    val fields: List<String>,
    /** 实际替换掉的 `{{…}}` 处数。 */
    val replaced: Int,
    /** 改动过的部件数（document/header/footer…）。 */
    val parts: Int,
)

/**
 * 模板替换引擎（**纯函数**：进 zip 字节、出 zip 字节，不碰盘、不碰 Context）。
 */
internal object DocxTemplate {

    /** 模板文件体积上限（和 `.docx` 读取同口径）。 */
    const val MAX_TEMPLATE_BYTES = 40L * 1024 * 1024

    /** 单个占位符名字最长多少字（再长就不是字段名，是一段话）。 */
    private const val MAX_FIELD_CHARS = 60

    /** 正文所在的部件（模板里这些才可能有 `{{}}`）。 */
    private const val MAIN_PART = "word/document.xml"

    /** 带宏的标记（`.docm` 里就是这一件）。 */
    private const val MACRO_PART = "word/vbaProject.bin"

    /**
     * 文本节点：`<w:t ...>文字</w:t>`（文字里不可能出现 `<`，XML 会把它转义成 `&lt;`）。
     * 属性段单独成组，替换完原样写回（`xml:space="preserve"` 这种属性不能丢）。
     */
    private val TEXT_NODE = Regex("<w:t(\\s[^>]*)?>([^<]*)</w:t>")

    /** 占位符：`{{名字}}`，名字两侧允许空格（`{{ 姓名 }}` 也算）。 */
    private val PLACEHOLDER = Regex("\\{\\{\\s*([^{}]{1,$MAX_FIELD_CHARS})\\s*\\}\\}")

    /**
     * 套模板。
     *
     * @param zip 模板文件的原始字节
     * @param fields 占位符名 → 值（值按纯文本转义后写入，`<` `&` 不会破坏 XML）
     */
    fun apply(zip: ByteArray, fields: Map<String, String>): Step<TemplateApplied> {
        val entries = readEntries(zip)
            ?: return Step.Bad(
                "只支持 .docx 模板：这个文件不是 .docx（zip 解不开；" +
                    "可能是 .doc、加密文档，或根本不是 Word 文件）",
            )
        if (entries.any { it.name.equals(MACRO_PART, ignoreCase = true) }) {
            return Step.Bad(
                "这份模板带宏（vbaProject.bin，.docm）：宏是安全红线，不执行也不嵌入。" +
                    "请在 Word 里「另存为」普通 .docx（不带宏）再试",
            )
        }
        if (entries.none { it.name == MAIN_PART }) {
            return Step.Bad("只支持 .docx 模板（包里没有 $MAIN_PART）；.doc 老格式不支持")
        }
        val placeholders = LinkedHashSet<String>()
        var replaced = 0
        var touched = 0
        val out = ArrayList<Entry>(entries.size)
        for (entry in entries) {
            if (!isTextPart(entry.name)) {
                out += entry
                continue
            }
            val xml = String(entry.bytes, Charsets.UTF_8)
            val one = replaceInXml(xml, fields, placeholders)
                ?: return Step.Bad(SPLIT_MESSAGE)
            if (one.replaced > 0) touched++
            replaced += one.replaced
            out += Entry(entry.name, one.xml.toByteArray(Charsets.UTF_8), entry.time)
        }
        val missing = placeholders.filter { it !in fields }
        if (missing.isNotEmpty()) {
            return Step.Bad(
                "缺少字段 ${missing.joinToString("、")}（模板里这 ${missing.size} 个占位符没给值）。" +
                    "要么在 fields 里补上，要么把模板里这几个占位符删掉——不会静默留空。",
            )
        }
        return Step.Ok(TemplateApplied(pack(out), placeholders.toList(), replaced, touched))
    }

    /** 占位符被 Word 拆成几段时的话（**明确说做不到**，不猜、不硬拼）。 */
    private const val SPLIT_MESSAGE =
        "模板里有占位符被 Word 拆成了好几段（`{{` 和 `}}` 分别落在不同的文字块里）——" +
            "这通常是编辑时改了其中一半字造成的。请在 Word 里把那个 `{{…}}` 整段删掉重新打一遍再试"

    /** 只处理正文/页眉/页脚/脚注/尾注这几个部件（图表、样式里不会有 `{{}}`）。 */
    private fun isTextPart(name: String): Boolean {
        if (name == MAIN_PART) return true
        if (!name.startsWith("word/") || !name.endsWith(".xml")) return false
        val base = name.removePrefix("word/").removeSuffix(".xml")
        return base.startsWith("header") || base.startsWith("footer") ||
            base == "footnotes" || base == "endnotes"
    }

    private class Replaced(val xml: String, val replaced: Int)

    /**
     * 在一个部件里替换所有 `{{}}`，并把见过的字段名记进 [placeholders]。
     *
     * @return 拆开的占位符（无法替换）⇒ null
     */
    private fun replaceInXml(
        xml: String,
        fields: Map<String, String>,
        placeholders: MutableSet<String>,
    ): Replaced? {
        var count = 0
        val sb = StringBuilder(xml.length)
        var cursor = 0
        for (match in TEXT_NODE.findAll(xml)) {
            val attrs = match.groupValues[1]
            val content = match.groupValues[2]
            if (unbalanced(content)) return null
            val filled = PLACEHOLDER.replace(content) { m ->
                val key = m.groupValues[1].trim()
                placeholders += key
                val value = fields[key]
                if (value == null) {
                    // 缺值：原样留着（最后统一报"缺少字段"），**不写空串**
                    m.value
                } else {
                    count++
                    Xml.text(value)
                }
            }
            sb.append(xml, cursor, match.range.first)
            sb.append("<w:t").append(attrs).append('>').append(filled).append("</w:t>")
            cursor = match.range.last + 1
        }
        if (cursor == 0) return Replaced(xml, 0)
        sb.append(xml, cursor, xml.length)
        return Replaced(sb.toString(), count)
    }

    /**
     * 这段文字里的花括号是不是**不成对**（成对的 `{{x}}` 是正常占位符，缺值也照样成对）。
     *
     * 判法：每个 `{{` 后面必须能找到一个 `}}`；把成对的都挖掉后，剩下的 `}}` 就是孤儿。
     */
    private fun unbalanced(content: String): Boolean {
        var i = 0
        while (true) {
            val open = content.indexOf("{{", i)
            if (open < 0) break
            val close = content.indexOf("}}", open + 2)
            if (close < 0) return true
            i = close + 2
        }
        return content.replace(Regex("\\{\\{[^{}]*\\}\\}"), "").contains("}}")
    }

    // ── zip 读写（只搬字节，不解释内容）───────────────────────────────────

    private class Entry(val name: String, val bytes: ByteArray, val time: Long = 0L)

    private fun readEntries(zip: ByteArray): List<Entry>? = runCatching {
        val out = ArrayList<Entry>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            var entry = zin.nextEntry
            // 防御：条目数上限（正常 docx 几十个；几千个说明不是 docx）
            while (entry != null && out.size <= MAX_ENTRIES) {
                if (!entry.isDirectory) {
                    out += Entry(entry.name, zin.readBytes(), entry.time)
                }
                entry = zin.nextEntry
            }
        }
        // 超上限 ⇒ 认成"不是 docx"（宁可明确报错，也不去解一个几万条目的压缩包）
        out.takeIf { it.size <= MAX_ENTRIES && it.isNotEmpty() }
    }.getOrNull()

    private const val MAX_ENTRIES = 500

    /** 打回 zip：条目顺序照抄（`[Content_Types].xml` 一般就在第一个，Office 喜欢这样）。 */
    private fun pack(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for (entry in entries) {
                val ze = ZipEntry(entry.name)
                // 时间戳沿用模板里的（0 表示没存 ⇒ 用固定值，避免把本机时区写进文件）
                ze.time = if (entry.time > 0) entry.time else 315_532_800_000L
                zos.putNextEntry(ze)
                zos.write(entry.bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
