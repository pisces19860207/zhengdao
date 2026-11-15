// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十一批 · **原文定位器**：把小说源文件读成「章 → 行」的坐标系。
//
// 改编判据要的三件事全在这里：
//   1. 大纲/分场稿里的 `source {chapter, from, to}` 能**真的**解析到原文的哪几行；
//   2. `script_trace` 抽一场就能把那段原文原样贴出来（用户点得回去，不是模型复述）；
//   3. 对不上（章不存在 / 行越界 / 区间倒挂 / 区间跨章）当场拒，不猜、不就近取。
//
// 章的口径（写死，避免"标题里的数字"和"第几个章块"打架）：
//   **chapter = 第几个章节块，从 1 数**；报错时带上那一块的标题，
//   所以「第3章（第三章 夜访）」这种话读者也能核对。
// 行的口径：**抽取出来的纯文本里的绝对行号，从 1 数**（.docx/.doc 的口径是
// 抽取后文本的行号——见下方 [ofText] 的说明）。
package com.example.zhengdao.baopu.script

import com.example.zhengdao.core.doc.DocRead
import com.example.zhengdao.core.doc.DocTextExtractor
import com.example.zhengdao.core.doc.DocxTextExtractor
import java.io.File

/** 一个章节块（[startLine] / [endLine] 都是 1 起的闭区间）。 */
internal data class Chapter(val index: Int, val title: String, val startLine: Int, val endLine: Int)

/** 原文的坐标系（[lines] 是 0 起的数组，对外一律用 1 起的行号）。 */
internal class NovelSource(
    val file: File,
    val lines: List<String>,
    val chapters: List<Chapter>,
    /** 抽取方式（txt / md / docx / doc）——报错与报告里说清"行号是怎么数的"。 */
    val kind: String,
) {

    /** 原文总行数（1 起的最大行号）。 */
    val lineCount: Int get() = lines.size

    /**
     * 取一段原文（闭区间，1 起的行号）。
     *
     * @return 每行都带行号（`L123｜正文`）——用户拿它对源文件一搜就中。
     */
    fun excerpt(from: Int, to: Int): List<String> {
        if (from < 1 || to < from || to > lines.size) return emptyList()
        return (from..to).map { "L$it｜${lines[it - 1]}" }
    }

    /** 第 [chapter] 个章节块（越界 ⇒ null）。 */
    fun chapter(index: Int): Chapter? = chapters.firstOrNull { it.index == index }

    /**
     * 校验一个 [SourceRef]（**纯判定**，返回 null ＝通过）。
     *
     * 四条都拒：章不存在、区间倒挂、行越界、区间跨章。
     * 最后一条为什么必须拒：`第3章 L100–L260` 里若 L200 已经是第 4 章，那"第 3 章"
     * 这个标签就是假的——读者顺着标签找会对不上。
     */
    fun check(ref: SourceRef): String? {
        val ch = chapter(ref.chapter)
            ?: return "原文没有第 ${ref.chapter} 章（共 ${chapters.size} 章）"
        if (ref.from > ref.to) {
            return "来源区间倒挂：第${ref.chapter}章 from=${ref.from} > to=${ref.to}"
        }
        if (ref.from < 1 || ref.to > lines.size) {
            return "来源行号越界：第${ref.chapter}章 L${ref.from}–L${ref.to}，" +
                "原文一共 $lineCount 行"
        }
        if (ref.from < ch.startLine || ref.to > ch.endLine) {
            return "来源区间跨章：第${ref.chapter}章（${ch.title}）只到 L${ch.startLine}–L${ch.endLine}，" +
                "而给的是 L${ref.from}–L${ref.to}"
        }
        return null
    }

    companion object {

        /** 源文件大小上限（抽取文本再大也不适合整本进模型；改编按章按行取，不需要整本载入内存 N 次）。 */
        const val MAX_BYTES = 40L * 1024 * 1024

        /** 章节标题识别：`第三章 …` / `第 12 回 …` / `Chapter 7 …`。 */
        private val CHAPTER = Regex(
            """^\s*(?:第\s*[0-9一二三四五六七八九十百千零两]+\s*[章回节]|(?:Chapter|CHAPTER)\s+[0-9IVXLC]+).*$""",
        )

        /**
         * 读源文件（按扩展名选抽取器；不猜内容）。
         *
         * `.docx` / `.doc` 走 :core 既有的两个抽取器（642 已交付，纯 JDK 实现），
         * 行号口径＝**抽取后文本**的行号——这一点写进 [kind] 与工具描述，不让用户
         * 拿 Word 界面上的行号来对（Word 的分页/自动换行会骗人）。
         */
        fun load(file: File): Parsed<NovelSource> {
            if (!file.isFile) return Parsed.Bad("原文不存在：${file.name}（先确认路径；改编要给得到的那份）")
            if (file.length() > MAX_BYTES) {
                return Parsed.Bad("原文有 ${file.length() / 1024 / 1024} MB，超过 ${MAX_BYTES / 1024 / 1024} MB 上限")
            }
            val (text, kind) = when (val read = readText(file)) {
                is Parsed.Bad -> return read
                is Parsed.Ok -> read.value
            }
            return ofText(file, text, kind)
        }

        /** 按扩展名选抽取器（不猜内容）；返回（正文，行号口径说明）。 */
        private fun readText(file: File): Parsed<Pair<String, String>> {
            val name = file.name.lowercase()
            return when {
                name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown") -> plain(file)
                name.endsWith(".docx") -> docx(file)
                name.endsWith(".doc") -> doc(file)
                else -> Parsed.Bad(
                    "原文格式不支持：${file.name}（只收 .txt / .md / .docx / .doc；.pdf 不在本批范围）",
                )
            }
        }

        /** 纯文本：非 UTF-8 直接说清（不要静默换编码读成乱码）。 */
        private fun plain(file: File): Parsed<Pair<String, String>> {
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                ?: return Parsed.Bad("原文读不出（${file.name}）：不是 UTF-8 文本？先转换编码再试")
            return Parsed.Ok(text to "txt")
        }

        /** `.docx`：走 :core 的 DocxTextExtractor（642 交付，纯 JDK）。 */
        private fun docx(file: File): Parsed<Pair<String, String>> {
            val bytes = runCatching { file.readBytes() }.getOrNull()
                ?: return Parsed.Bad("原文读不出（${file.name}）：权限或 IO 错误")
            val text = DocxTextExtractor.extract(bytes)
            if (text.isBlank()) return Parsed.Bad("${file.name} 抽不出正文（不是 .docx，或文档是空的）")
            return Parsed.Ok(text to "docx（行号＝抽取后文本的行号）")
        }

        /** `.doc`（Word 97–2003）：走 :core 的 DocTextExtractor；打不开就说清为什么。 */
        private fun doc(file: File): Parsed<Pair<String, String>> {
            val bytes = runCatching { file.readBytes() }.getOrNull()
                ?: return Parsed.Bad("原文读不出（${file.name}）：权限或 IO 错误")
            return when (val read = DocTextExtractor.extract(bytes)) {
                is DocRead.Ok -> {
                    if (read.text.isBlank()) {
                        Parsed.Bad("${file.name} 抽不出正文（文档是空的？）")
                    } else {
                        Parsed.Ok(read.text to "doc（行号＝抽取后文本的行号）")
                    }
                }

                is DocRead.Unsupported -> Parsed.Bad("${file.name} 打不开：${read.reason}；请另存为 .docx 或 .txt")
            }
        }

        /** 从纯文本建坐标系（单测直接喂文本，不经过磁盘）。 */
        fun ofText(file: File, text: String, kind: String = "txt"): Parsed<NovelSource> {
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            if (lines.all { it.isBlank() }) return Parsed.Bad("原文是空的（${file.name}）")
            // 章块的唯一算法：以每个章节标题行为界，[标题行 .. 下一个标题行-1] 就是那一章。
            // 标题行自己属于它那一章（读者按标题找内容时就是这么找的）；标题之前的正文进「题前」。
            val heads = lines.indices.filter { CHAPTER.matches(lines[it]) }
            val chapters = ArrayList<Chapter>()
            if (heads.isEmpty()) {
                chapters += Chapter(1, "（全文）", 1, lines.size)
            } else {
                if (heads.first() > 0) chapters += Chapter(1, "（题前）", 1, heads.first())
                heads.forEachIndexed { k, i ->
                    val end = if (k + 1 < heads.size) heads[k + 1] else lines.size
                    chapters += Chapter(chapters.size + 1, lines[i].trim().take(40), i + 1, end)
                }
            }
            return Parsed.Ok(NovelSource(file, lines, chapters, kind))
        }
    }
}
