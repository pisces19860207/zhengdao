// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴线刀 2 追加包（章节级抽取流水线 · 切章）。三条规则逐条落地：
// 1. 中文章节标记：行首（允许前导空白）「第…章 / 节 / 回」；
// 2. 英文章节标记：行首 `Chapter N`（大小写不敏感）；
// 3. 固定字数兜底：一条标记都没有时按字数切。
// 输入文本 + 选项 ⇒ List<Chapter>(index, title, startLine, endLine)；空文本 ⇒ 空表。
package com.example.zhengdao.baopu.library

/**
 * 一章（行号 0 起、含端点——与 read_file 的 offset 口径一致，模型拿 startLine
 * 直接当 offset 用，不用换算）。
 *
 * @param index    在输出表里的序号（0 起；有卷首时卷首是 0，正文从 1 起）
 * @param title    章标题（标记行原文去空白；兜底切分时是「第 N 节」）
 * @param startLine 起始行（含）
 * @param endLine   结束行（含）
 */
internal data class Chapter(
    val index: Int,
    val title: String,
    val startLine: Int,
    val endLine: Int,
) {
    /** 章文本（调用方已按同一份 lines 切；直接切片拼回，不重新读文件）。 */
    fun textOf(lines: List<String>): String =
        lines.subList(startLine, (endLine + 1).coerceAtMost(lines.size)).joinToString("\n")
}

/** 切章选项。 */
internal data class SplitOptions(
    /** 兜底切分的每章目标字数（按行边界收章，不切断行）。 */
    val fallbackChars: Int = 2000,
)

/**
 * 章节切分（**纯函数**：文本进、清单出，无 IO 无 Android，JVM 直测）。
 *
 * 为什么标记行必须**在行首**（只允许前导空白）：小说正文里「第三章」常常在
 * 句中冒出来（「话说第三章那次……」）——不限行首会把一本好好的书切得稀烂。
 * 代价是标题行必须单独占一行，这在中英文小说里都是惯例。
 */
internal object ChapterSplitter {

    /** 中文标记：第 + 数字（阿拉伯/全角/中文数字）+ 章/回/节。 */
    private val CN_MARKER = Regex("""^\s*第\s*[0-9０-９一二三四五六七八九十百千零两]+\s*[章回节]""")

    /** 英文标记：Chapter + 空白 + 数字（大小写不敏感）。 */
    private val EN_MARKER = Regex("""^\s*chapter\s+\d+""", RegexOption.IGNORE_CASE)

    /** 标题最长截断（病态长行不至于把预览撑爆）。 */
    private const val MAX_TITLE = 60

    /**
     * 切章。
     *
     * 规则优先级：中文/英文标记（找到几个用几个，两种混用也认）⇒ 固定字数兜底。
     * 标记前的非空白内容（书名页/简介）单独成一章「（卷首）」——不丢内容，
     * 也不把它硬塞进第一章（那章的 sha 会跟着变，索引就白做了）。
     */
    fun split(text: String, options: SplitOptions = SplitOptions()): List<Chapter> {
        if (text.isBlank()) return emptyList()
        val lines = text.lines()
        val markers = lines.indices.filter { isMarker(lines[it]) }
        if (markers.isEmpty()) return splitByChars(lines, options.fallbackChars)
        val out = mutableListOf<Chapter>()
        // 卷首（第一个标记之前的非空白内容）
        if (markers.first() > 0 && lines.subList(0, markers.first()).any { it.isNotBlank() }) {
            out += Chapter(0, "（卷首）", 0, markers.first() - 1)
        }
        markers.forEachIndexed { i, lineNo ->
            val start = lineNo
            val end = if (i + 1 < markers.size) markers[i + 1] - 1 else lines.size - 1
            out += Chapter(out.size, titleOf(lines[lineNo]), start, end)
        }
        return out
    }

    /** 是否章节标记行（中文或英文）。 */
    fun isMarker(line: String): Boolean =
        CN_MARKER.containsMatchIn(line) || EN_MARKER.containsMatchIn(line)

    /** 标题（标记行去空白 + 截断；截断加省略号——静默截断会让用户以为标题就到这）。 */
    fun titleOf(line: String): String {
        val t = line.trim()
        return if (t.length <= MAX_TITLE) t else t.take(MAX_TITLE - 1) + "…"
    }

    /**
     * 固定字数兜底切分。
     *
     * 按行边界收章：单行超目标字数也不切断（切断会让「第几行」对不上原文），
     * 那一章就会超——明说在摘要里，不静默。
     */
    private fun splitByChars(lines: List<String>, fallbackChars: Int): List<Chapter> {
        val budget = fallbackChars.coerceAtLeast(200) // 防呆：选项传 0/负也不至于每行一章
        val out = mutableListOf<Chapter>()
        var start = 0
        var acc = 0
        for (i in lines.indices) {
            acc += lines[i].length + 1
            val last = i == lines.size - 1
            if (acc >= budget || last) {
                out += Chapter(out.size, "第 ${out.size + 1} 节", start, i)
                start = i + 1
                acc = 0
            }
        }
        return out
    }
}
