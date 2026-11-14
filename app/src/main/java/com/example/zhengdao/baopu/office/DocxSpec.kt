// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十二批 · 轻办公写出的**文档模型**（docx）
//
// 为什么先有模型再有 XML：模型是「用户/模型说的那件事」，XML 是「Word 要的那种形状」。
// 把两者混在一处，判据就只能靠"打开看看像不像"；分开之后，
// 「表格 11 列要明确报错」「图片超过 2 张要报错」这类判断全在纯函数里可测。
//
// 边界（派工单 §一①，逐条落在这里）：
//   · 标题只做 1–3 级；段落；无序/有序列表；粗斜体（`**粗**` / `*斜*` / `***都***`）；
//   · 表格 ≤ [OfficeLimits.DOCX_MAX_COLUMNS] 列；图片 ≤ [OfficeLimits.DOCX_MAX_IMAGES] 张；
//   · **不做样式编辑**：只有一套内置样式（[DocxStyle]），模型改不了字体/字号/行距。
package com.example.zhengdao.baopu.office

import org.json.JSONArray
import org.json.JSONObject

/** 轻办公两条写出的上限（超限一律**明确报错**，不静默截断——632 判据④）。 */
internal object OfficeLimits {

    /** docx 表格列数上限（派工单 §一①）。 */
    const val DOCX_MAX_COLUMNS = 10

    /** docx 图片张数上限。 */
    const val DOCX_MAX_IMAGES = 2

    /** docx 块数上限（防一次塞进几千段把手机和 Word 一起拖死）。 */
    const val DOCX_MAX_BLOCKS = 200

    /** docx 单张表格行数上限（不含表头）。 */
    const val DOCX_MAX_TABLE_ROWS = 500

    /** 单张图片的字节上限（20MB；再大就别塞进 Word 了）。 */
    const val DOCX_MAX_IMAGE_BYTES = 20L * 1024 * 1024

    /** xlsx sheet 数上限（派工单 §一②）。 */
    const val XLSX_MAX_SHEETS = 5

    /** 单 sheet 行数上限（含表头）。 */
    const val XLSX_MAX_ROWS = 2000

    /** sheet 名长度上限（ECMA-376 规定 31，超过 Excel 直接拒开）。 */
    const val XLSX_MAX_SHEET_NAME = 31

    /** xlsx 单格文本长度上限（超过就说明"这不是表格该装的东西"）。 */
    const val XLSX_MAX_CELL_CHARS = 1000
}

/**
 * 「一步要么成、要么带一句人话」的小结果类型（docx/xlsx 两份解析共用）。
 *
 * 为什么不抛异常：解析的是**模型给的 JSON**，坏输入是常态而不是异常；
 * 抛异常会把「补个参数再来」变成「工具崩了」，模型下一轮只会重试同一份坏参数。
 */
internal sealed interface Step<out T> {
    data class Ok<T>(val value: T) : Step<T>
    data class Bad(val reason: String) : Step<Nothing>

    /** 成功就取值、失败给 null（调用方/测试的便捷读法；判据仍以 [Ok]/[Bad] 分支为准）。 */
    fun valueOrNull(): T? = (this as? Ok<T>)?.value
}

/** docx 的一块内容（模型 → 文档的中间形态）。 */
internal sealed interface DocxBlock {

    /** 标题（level 只认 1–3）。 */
    data class Heading(val level: Int, val text: String) : DocxBlock

    /** 普通段落（`text` 里支持 `**粗**` / `*斜*` 行内标注）。 */
    data class Paragraph(val text: String) : DocxBlock

    /** 无序/有序列表（一个块 = 一个列表，序号按块重新起）。 */
    data class Bullets(val ordered: Boolean, val items: List<String>) : DocxBlock

    /** 表格：`columns` 是表头（加粗），`rows` 是数据行。 */
    data class Table(val columns: List<String>, val rows: List<List<String>>) : DocxBlock

    /** 图片（按工作区/绝对路径读；只认 PNG/JPEG）。 */
    data class Picture(val path: String) : DocxBlock
}

/**
 * 一份 docx 的完整描述。
 *
 * @param fileBase 落盘用的文件基名（不含扩展名与时间戳）
 * @param header 页眉那一行（空串＝不建页眉）
 */
internal data class DocxSpec(
    val fileBase: String,
    val header: String,
    val blocks: List<DocxBlock>,
) {

    /** 各类块的件数（回执与账行用；也是判据①「结构断言」的读数来源）。 */
    fun counts(): String {
        val headings = blocks.count { it is DocxBlock.Heading }
        val paragraphs = blocks.count { it is DocxBlock.Paragraph }
        val lists = blocks.count { it is DocxBlock.Bullets }
        val tables = blocks.count { it is DocxBlock.Table }
        val pictures = blocks.count { it is DocxBlock.Picture }
        return "标题 $headings / 段落 $paragraphs / 列表 $lists / 表格 $tables / 图片 $pictures"
    }
}

/** 行内标注切出来的一个 run（一段同样式文字）。 */
internal data class DocxRun(val text: String, val bold: Boolean, val italic: Boolean)

/**
 * 行内标注解析（`**粗**`、`*斜*`、`***又粗又斜***`）。
 *
 * 为什么用「成对才算」而不是状态机一直翻转：模型写技术文本时真的会出现裸星号
 * （`2*3`、`*`、脚注号）——一直翻转会把后半段全变成斜体，用户看到的是"文件坏了"。
 * 这里遇到星号先看**后面有没有同长度的收尾**，没有就按原文字面输出。
 */
internal object DocxInline {

    fun runs(text: String): List<DocxRun> {
        if ('*' !in text) return listOf(DocxRun(text, bold = false, italic = false))
        val out = mutableListOf<DocxRun>()
        var plain = StringBuilder()
        var bold = false
        var italic = false
        var i = 0
        while (i < text.length) {
            if (text[i] != '*') {
                plain.append(text[i])
                i++
                continue
            }
            val mark = "*".repeat(runLength(text, i))
            // 收尾标记：这一种样式**已经开着**，又看见同长度的星号 ⇒ 它就是收尾（不必再往后找）。
            // 只看"后面还有没有"会把收尾当成新的开头（`a**粗**b` 的第二个 `**` 后面没有 `**`，
            // 于是被判成字面量，粗体永远关不掉——这条是测试先红才发现的）。
            if (!isClosing(mark.length, bold, italic) && text.indexOf(mark, i + mark.length) < 0) {
                // 没有收尾 ⇒ 字面星号（`2*3` 这类）
                plain.append(mark)
                i += mark.length
                continue
            }
            flush(out, plain, bold, italic)
            when (mark.length) {
                1 -> italic = !italic
                2 -> bold = !bold
                else -> {
                    bold = !bold
                    italic = !italic
                }
            }
            i += mark.length
        }
        flush(out, plain, bold, italic)
        return if (out.isEmpty()) listOf(DocxRun(text, false, false)) else out
    }

    /** 这个标注是不是「关掉已开的样式」（1=斜、2=粗、3=两者都要开着）。 */
    private fun isClosing(length: Int, bold: Boolean, italic: Boolean): Boolean = when (length) {
        1 -> italic
        2 -> bold
        else -> bold && italic
    }

    /** 从 [at] 起连续星号个数（最多 3；再多按 3 处理）。 */
    private fun runLength(text: String, at: Int): Int {
        var n = 0
        while (at + n < text.length && text[at + n] == '*' && n < 3) n++
        return n
    }

    private fun flush(out: MutableList<DocxRun>, buf: StringBuilder, bold: Boolean, italic: Boolean) {
        if (buf.isEmpty()) return
        out += DocxRun(buf.toString(), bold, italic)
        buf.setLength(0)
    }
}

/** `blocks`/`name`/`title`/`header` 的解析与校验（**纯函数**，零 IO）。 */
internal object DocxSpecParser {

    const val ARG_NAME = "name"
    const val ARG_HEADER = "header"
    const val ARG_BLOCKS = "blocks"

    fun parse(args: JSONObject): Step<DocxSpec> {
        val array = args.optJSONArray(ARG_BLOCKS)
            ?: return Step.Bad("缺少必填参数「$ARG_BLOCKS」：把要写的内容按块列出来（标题/段落/列表/表格/图片）")
        if (array.length() == 0) return Step.Bad("「$ARG_BLOCKS」是空的——没有内容可写")
        if (array.length() > OfficeLimits.DOCX_MAX_BLOCKS) {
            return Step.Bad("块数 ${array.length()} 超过上限 ${OfficeLimits.DOCX_MAX_BLOCKS} 块；请拆成多份文档")
        }
        val blocks = mutableListOf<DocxBlock>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: return Step.Bad("第 ${i + 1} 块不是对象（每块要写成 {\"type\":\"paragraph\",\"text\":\"…\"} 这种形状）")
            when (val one = blockOf(obj, i)) {
                is Step.Bad -> return one
                is Step.Ok -> blocks += one.value
            }
        }
        return Step.Ok(
            DocxSpec(
                fileBase = baseName(args, blocks),
                header = args.optString(ARG_HEADER).trim(),
                blocks = blocks,
            ),
        )
    }

    /** 文件名基：显式 `name` → 第一个标题 → 「文档」。 */
    private fun baseName(args: JSONObject, blocks: List<DocxBlock>): String {
        args.optString(ARG_NAME).trim().takeIf { it.isNotEmpty() }?.let { return it }
        blocks.filterIsInstance<DocxBlock.Heading>().firstOrNull()?.text?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        blocks.filterIsInstance<DocxBlock.Paragraph>().firstOrNull()?.text?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it.take(20) }
        return "文档"
    }

    private fun blockOf(obj: JSONObject, index: Int): Step<DocxBlock> {
        val at = "第 ${index + 1} 块"
        return when (val type = obj.optString("type").trim().lowercase()) {
            "heading" -> headingOf(obj, at)
            "paragraph" -> paragraphOf(obj, at)
            "list", "bullets", "ordered_list", "unordered_list" -> listBlock(obj, at)
            "table" -> tableOf(obj, at)
            "image", "picture" -> pictureOf(obj, at)
            else -> Step.Bad("$at 的 type「$type」不认得（可写 heading / paragraph / list / table / image）")
        }
    }

    private fun headingOf(obj: JSONObject, at: String): Step<DocxBlock> {
        val level = obj.optInt("level", 1)
        if (level !in 1..3) return Step.Bad("$at 的 level=$level 不支持：标题只做 1–3 级")
        val text = obj.optString("text").trim()
        if (text.isEmpty()) return Step.Bad("$at（标题）的 text 是空的")
        return Step.Ok(DocxBlock.Heading(level, text))
    }

    private fun paragraphOf(obj: JSONObject, at: String): Step<DocxBlock> {
        val text = obj.optString("text")
        if (text.isBlank()) return Step.Bad("$at（段落）的 text 是空的")
        return Step.Ok(DocxBlock.Paragraph(text))
    }

    private fun listBlock(obj: JSONObject, at: String): Step<DocxBlock> {
        val items = obj.optJSONArray("items")
            ?: return Step.Bad("$at（列表）缺 items 数组")
        if (items.length() == 0) return Step.Bad("$at（列表）的 items 是空的")
        val texts = (0 until items.length()).map { items.optString(it).trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return Step.Bad("$at（列表）的 items 里没有有效条目")
        return Step.Ok(DocxBlock.Bullets(ordered = obj.optBoolean("ordered", false), items = texts))
    }

    private fun tableOf(obj: JSONObject, at: String): Step<DocxBlock> {
        val columns = textsOf(obj.optJSONArray("columns"))
        val rows = rowsOf(obj.optJSONArray("rows"))
            ?: return Step.Bad("$at（表格）的 rows 必须是二维数组（每行一个数组）")
        val widest = maxOf(columns.size, rows.maxOfOrNull { it.size } ?: 0)
        if (widest == 0) return Step.Bad("$at（表格）一个格子都没有")
        if (widest > OfficeLimits.DOCX_MAX_COLUMNS) {
            return Step.Bad(
                "$at（表格）有 $widest 列，超过上限 ${OfficeLimits.DOCX_MAX_COLUMNS} 列——" +
                    "docx 表格最多 ${OfficeLimits.DOCX_MAX_COLUMNS} 列，请拆成两张表或删掉几列（没有静默截断）",
            )
        }
        if (rows.size > OfficeLimits.DOCX_MAX_TABLE_ROWS) {
            return Step.Bad("$at（表格）有 ${rows.size} 行，超过上限 ${OfficeLimits.DOCX_MAX_TABLE_ROWS} 行；请拆成多张表")
        }
        // 补齐成矩形：短行补空串（Word 的 tblGrid 要求每行格子数与列数一致）
        val width = maxOf(columns.size, widest)
        return Step.Ok(
            DocxBlock.Table(
                columns = (0 until width).map { columns.getOrElse(it) { "" } },
                rows = rows.map { row -> (0 until width).map { row.getOrElse(it) { "" } } },
            ),
        )
    }

    private fun pictureOf(obj: JSONObject, at: String): Step<DocxBlock> {
        val path = obj.optString("path").trim()
        if (path.isEmpty()) return Step.Bad("$at（图片）缺 path（要给图片在手机上的路径）")
        return Step.Ok(DocxBlock.Picture(path))
    }

    private fun textsOf(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it).trim() }

    private fun rowsOf(array: JSONArray?): List<List<String>>? {
        if (array == null) return emptyList()
        val out = mutableListOf<List<String>>()
        for (i in 0 until array.length()) {
            val row = array.optJSONArray(i) ?: return null
            out += (0 until row.length()).map { row.optString(it).trim() }
        }
        return out
    }
}
