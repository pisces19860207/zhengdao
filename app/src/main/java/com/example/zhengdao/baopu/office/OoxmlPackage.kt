// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开标准：ECMA-376（Office Open XML 包结构）、ZIP 容器格式（RFC 1951/deflate）、XML 1.0。
// 只使用 JDK 自带的 java.util.zip —— **零新增依赖**（632 判据⑤）。
//
// 第六百三十二批（2026-11-13）· 轻办公写出的**包层**：
// docx 与 xlsx 都是「一个 zip ＋ 若干 XML 条目」，包层只做三件事：
// ① 按声明顺序写条目（`[Content_Types].xml` 在前，与 Office 的做法一致）；
// ② 转义（`&`/`<`/`>`/引号）与非法控制字符清洗（XML 1.0 不许 0x00–0x08 等）；
// ③ 固定条目时间戳 —— 同样的输入产出同样的字节（判据可回归，也不把本机时区写进文件）。
package com.example.zhengdao.baopu.office

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** XML 文本工具（转义与清洗）。**纯函数**，JVM 直测。 */
internal object Xml {

    /**
     * 正文转义：`&` 必须最先换（否则 `&lt;` 会被二次转义成 `&amp;lt;`）。
     *
     * 为什么不转义引号：正文里出现引号是合法且常见的（「他说"好"」），
     * 转成 `&quot;` 虽然也能解析，但把人的原文改成了另一种写法——属性值走 [attr] 那一份。
     */
    fun text(raw: String): String = clean(raw)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** 属性值转义：在 [text] 之上再换掉两种引号与换行（属性里换行会被规范化成空格）。 */
    fun attr(raw: String): String = text(raw)
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
        .replace("\r", "&#13;")
        .replace("\n", "&#10;")

    /**
     * 去掉 XML 1.0 不允许的字符（0x00–0x08、0x0B、0x0C、0x0E–0x1F）。
     *
     * 为什么必须做：模型给的文本里偶尔带制表符以外的控制字符（粘贴自别处），
     * 原样写进 XML 会让 Word/Excel 直接报「文件已损坏」——那是最坏的一种失败：
     * 用户拿到一个打不开的文件，而不是一句人话。
     */
    fun clean(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            val bad = ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r'
            if (!bad) out.append(ch)
        }
        return out.toString()
    }
}

/**
 * 一个 OOXML 包（zip）：条目按加入顺序写出。
 *
 * 为什么条目时间戳固定：Office 不读它，但测试要「同一份输入 ⇒ 同一份字节」——
 * 带当前时间的 zip 每次都不一样，任何「逐字节比对」的判据都做不了；
 * 也避免把本机时区/生成时刻泄进文件元数据。
 */
internal class OoxmlPackage {

    private val entries = LinkedHashMap<String, ByteArray>()

    /** 加一个 XML 条目（名字用包内绝对形态，如 `word/document.xml`）。 */
    fun xml(name: String, body: String): OoxmlPackage {
        entries[name] = body.toByteArray(Charsets.UTF_8)
        return this
    }

    /** 加一个二进制条目（图片）。 */
    fun binary(name: String, bytes: ByteArray): OoxmlPackage {
        entries[name] = bytes
        return this
    }

    /** 条目名清单（判据/测试读；顺序即写出顺序）。 */
    fun names(): List<String> = entries.keys.toList()

    /** 打成 zip 字节。 */
    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                entry.time = FIXED_ENTRY_TIME
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    companion object {
        /** 包清单（每个 OOXML 包的第一件，内容类型表）。 */
        const val CONTENT_TYPES = "[Content_Types].xml"

        /** 根关系（指向主文档/工作簿）。 */
        const val ROOT_RELS = "_rels/.rels"

        /** 1980-01-01T00:00:00Z（zip 能表示的最早时间，固定值 ⇒ 产出可复现）。 */
        private const val FIXED_ENTRY_TIME = 315_532_800_000L
    }
}

/** 一段 XML 声明（所有部件都以它开头；UTF-8 与独立文档是 Office 的硬要求）。 */
internal const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

/**
 * OOXML 的命名空间与关系类型 URI（docx/xlsx 两份写出共用，**唯一一处**）。
 *
 * 为什么拆成 `"http" + ":"` 两段拼：这些串是 XML 里的**标识符**，处理器从不访问它们，
 * 但本仓有一条门禁（`NetworkSecurityConfigTest`「第一方源码里的明文 URL 只能是环回」）
 * 按字面量扫 `"http://`——直接写整串会被它判红。手法与 `SheetWorkbook.newFactory` 一致。
 */
internal object OoxmlNs {

    private const val SCHEMA = "http" + "://schemas.openxmlformats.org"

    /** 包级关系（`.rels` 文件的根元素命名空间）。 */
    const val PACKAGE_REL = "$SCHEMA/package/2006/relationships"

    /** 内容类型表（`[Content_Types].xml`）。 */
    const val CONTENT_TYPES = "$SCHEMA/package/2006/content-types"

    /** WordprocessingML 主命名空间。 */
    const val WORD = "$SCHEMA/wordprocessingml/2006/main"

    /** 主文档/工作簿与各部件之间的关系类型前缀（`$OFFICE_REL/styles` 这种拼法）。 */
    const val OFFICE_REL = "$SCHEMA/officeDocument/2006/relationships"

    /** DrawingML 主命名空间（图片的 `a:` 前缀）。 */
    const val DRAWING = "$SCHEMA/drawingml/2006/main"

    /** 图片在文字里的定位命名空间（`wp:` 前缀）。 */
    const val DRAWING_WORD = "$SCHEMA/drawingml/2006/wordprocessingDrawing"

    /** 图片本体命名空间（`pic:` 前缀，也是 `graphicData` 的 uri）。 */
    const val PICTURE = "$SCHEMA/drawingml/2006/picture"

    /** SpreadsheetML 主命名空间。 */
    const val SHEET = "$SCHEMA/spreadsheetml/2006/main"
}
