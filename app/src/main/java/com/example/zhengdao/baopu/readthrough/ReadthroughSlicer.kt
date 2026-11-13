// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第六百一十批（通读框架 · 切片）。三条规则：
// 1. 先按章切（复用 [ChapterSplitter] 同一份判定）——一章 ≤ maxChars 就整章一片，
//    完全不下刀；「切点不跨对话段」的最好保证是「根本不切」；
// 2. 超长章按块收片：每片 ≤ maxChars 字（按「行字数+1 换行」计，与 ChapterSplitter
//    同口径）。块（对话连段/单个非对话段）是**原子**——切点只落在块与块的边界上，
//    对话与对话之间永不出现切点；
// 3. 实在切不开（一个块自己就超过 maxChars：一整场超长对话/一个超长段）⇒ 就近下刀 +
//    异常逐条报（[SlicingAnomaly]），不静默。
package com.example.zhengdao.baopu.readthrough

import com.example.zhengdao.baopu.library.Chapter

/**
 * 一片（行号 0 起、含端点，与 read_file 的 offset 严格同口径——通读完的每条事实
 * 要能点回「章＋行」，行号只有一套口径才点得回）。
 *
 * @param chapterIndex 章（[Chapter.index]，0 起；卷首是 0）
 * @param sliceIndex   片在章内的序号（0 起）
 * @param sliceCount   该章总共几片（≥1；单章单片 ⇒ 这片就是整章）
 * @param midLine      endLine 是否被行内截断（超长段内下刀时 true；点回时该行只有部分在本片）
 */
internal data class ReadSlice(
    val chapterIndex: Int,
    val chapterTitle: String,
    val sliceIndex: Int,
    val sliceCount: Int,
    val startLine: Int,
    val endLine: Int,
    val midLine: Boolean = false,
    /** midLine=true 时本片在 endLine 行内的字符区间 [charStart, charEnd)。 */
    val charStart: Int = -1,
    val charEnd: Int = -1,
) {
    /** 片 id（断点账本与归并树的主键；全书唯一）。 */
    val id: String get() = "c${chapterIndex}t${sliceIndex}"

    /**
     * 片文本（调用方保证传同一份 lines——行号口径不能变）。
     * 行内切片（charStart≥0）⇒ 只取行内 [charStart, charEnd)，不把整行塞进两片——
     * **末片也适用**（它 midLine=false 但同样是半行）。
     */
    fun textOf(lines: List<String>): String {
        if (charStart >= 0 && charEnd >= 0) {
            val line = lines[endLine.coerceAtMost(lines.size - 1)]
            return line.substring(charStart.coerceAtLeast(0), charEnd.coerceAtMost(line.length))
        }
        return lines.subList(startLine, (endLine + 1).coerceAtMost(lines.size)).joinToString("\n")
    }
}

/** 切分异常（「逐条报」的那一条；机器码给人查账，人话在 detail 里）。 */
internal data class SlicingAnomaly(
    val chapterIndex: Int,
    val kind: String,
    val detail: String,
    val startLine: Int,
    val endLine: Int,
) {
    fun render(): String = "第${chapterIndex}章（行 $startLine–$endLine）[$kind] $detail"
}

internal data class SlicedBook(
    val slices: List<ReadSlice>,
    val anomalies: List<SlicingAnomaly>,
)

/**
 * 通读切片器（**纯函数**：行清单＋章清单进、片清单出，无 IO 无 Android，JVM 直测）。
 *
 * ## 为什么块是原子的
 * 对话段（引号开头的段）一旦被切开，两片各拿半场对话，抽出来的人物关系就断头——
 * 这就是「切点不跨对话段」。实现上把**对话连段**（引号段连着引号段，中间隔空行也算
 * 同一场；前一段引号没闭合，下一段无论是不是引号开头都算接着说）与单个非对话段都
 * 收成不可分的块，切点只允许落在块边界 ⇒ **结构上保证**，不靠事后检查。唯一破例：
 * 一个块自己就超过 maxChars，此时在块内按行/句边界就近下刀并记异常——宁可报异常，
 * 不可装作没切。
 */
internal object ReadthroughSlicer {

    /** 单片字数上限（任务口径 ≤6k 字）。 */
    const val MAX_SLICE_CHARS = 6000

    /** 异常 kind（机器码；报告与 .anomalies.jsonl 用）。 */
    const val ANOMALY_DIALOGUE_RUN_CUT = "DIALOGUE_RUN_CUT"
    const val ANOMALY_MID_PARAGRAPH_CUT = "MID_PARAGRAPH_CUT"
    const val ANOMALY_EMPTY_CHAPTER = "EMPTY_CHAPTER"

    /** 引号开头 ＝ 对话段（前导空白跳过；中英引号都认；句中引用不算——那是叙事）。 */
    private val DIALOGUE_HEAD = Regex("""^\s*[「『“"']""")

    private const val OPEN_QUOTES = "「『“\"'"
    private const val CLOSE_QUOTES = "」』……”\"'"

    /** 行内引号是否闭合（开 ≤ 关；开了没关 ⇒ 话没说完，下一段接着说）。 */
    fun quoteBalanced(line: String): Boolean {
        val opens = line.count { it in OPEN_QUOTES }
        val closes = line.count { it in CLOSE_QUOTES }
        return opens <= closes
    }

    /** 是否对话段。 */
    fun isDialogue(line: String): Boolean = DIALOGUE_HEAD.containsMatchIn(line)

    /**
     * 切整本书：逐章调 [sliceChapter]，异常汇总。
     *
     * @param lines    全书行清单（TextDecode.splitLines 口径）
     * @param chapters [ChapterSplitter.split] 的输出（基于同一份 lines）
     */
    fun sliceBook(
        lines: List<String>,
        chapters: List<Chapter>,
        maxChars: Int = MAX_SLICE_CHARS,
    ): SlicedBook {
        val slices = mutableListOf<ReadSlice>()
        val anomalies = mutableListOf<SlicingAnomaly>()
        chapters.forEach { ch ->
            val (parts, issues) = sliceChapter(lines, ch, maxChars)
            slices += parts
            anomalies += issues
        }
        return SlicedBook(slices, anomalies)
    }

    /**
     * 切一章。章 ≤ maxChars ⇒ 整章一片（哪怕全空也出一片——保证「每章至少一片」，
     * 覆盖率的分母才不缺章）；否则按块收片。返回（片清单，异常清单）。
     */
    fun sliceChapter(
        lines: List<String>,
        chapter: Chapter,
        maxChars: Int = MAX_SLICE_CHARS,
    ): Pair<List<ReadSlice>, List<SlicingAnomaly>> {
        val lo = chapter.startLine
        val hi = (chapter.endLine + 1).coerceAtMost(lines.size)
        val issues = mutableListOf<SlicingAnomaly>()
        val blocks = blocksOf(lines, lo, hi)
        if (blocks.isEmpty()) {
            issues += SlicingAnomaly(
                chapter.index, ANOMALY_EMPTY_CHAPTER, "本章全是空白行", lo, hi - 1,
            )
            return listOf(ReadSlice(chapter.index, chapter.title, 0, 1, lo, hi - 1)) to issues
        }
        if (charsOf(lines, lo, hi) <= maxChars) {
            // 「空章」＝除了标题行没有别的内容（引号标题只有一行、后面全空白）
            if (blocks.size == 1 && lines[blocks.first().startLine].trim() == chapter.title.trim()) {
                issues += SlicingAnomaly(
                    chapter.index, ANOMALY_EMPTY_CHAPTER, "本章除标题外全是空白行", lo, hi - 1,
                )
            }
            return listOf(
                ReadSlice(chapter.index, chapter.title, 0, 1, blocks.first().startLine, blocks.last().endLine),
            ) to issues
        }
        val (packed, oversizeIssues) = packBlocks(chapter, lines, blocks, maxChars)
        return packed to (issues + oversizeIssues)
    }

    // ── 块的划分（对话连段是原子；块平铺整章，不留缝） ─────────────────────

    private data class Block(val startLine: Int, val endLine: Int, val dialogue: Boolean)

    /**
     * 一章的行 → 块清单（平铺 [lo, hi)，不留缝也不重叠）。
     *
     * 对话连段的合并判据（从第一个非空行起，逐个非空行向后看）：
     * 1. 本块是对话、下一行也是对话 ⇒ 接着说（中间隔的空行并入块）；
     * 2. 上一非空行引号没闭合 ⇒ 话没说完，无论本行是不是引号开头都并进块。
     * 块的 endLine 向后吃掉尾随空行（切点落在「空行之后、下一段之前」，
     * 空行天然归上片——归哪边都不产生怪片，归上片让下片以正文开头更好读）。
     */
    private fun blocksOf(lines: List<String>, lo: Int, hi: Int): List<Block> {
        val cores = mutableListOf<Block>()
        var i = lo
        while (i < hi) {
            if (lines[i].isBlank()) { i++; continue }
            val dialogue = isDialogue(lines[i])
            var last = i
            var k = i + 1
            while (k < hi) {
                if (lines[k].isBlank()) { k++; continue }
                val prev = lines[last]
                val continues = (dialogue && isDialogue(lines[k])) || !quoteBalanced(prev)
                if (!continues) break
                last = k
                k++
            }
            cores += Block(i, last, dialogue)
            i = last + 1
        }
        // 尾随空行并入前一块（最后一块吃不到章节边界外的行，hi-1 兜底）
        return cores.mapIndexed { idx, b ->
            val nextStart = if (idx + 1 < cores.size) cores[idx + 1].startLine else hi
            var end = b.endLine
            while (end + 1 < nextStart) end++
            b.copy(endLine = end)
        }
    }

    // ── 装片 ────────────────────────────────────────────────────────────────

    /** 装片：块按预算打包；单块超预算 ⇒ 块内下刀并报异常（异常单独带回）。 */
    private fun packBlocks(
        chapter: Chapter,
        lines: List<String>,
        blocks: List<Block>,
        maxChars: Int,
    ): Pair<List<ReadSlice>, List<SlicingAnomaly>> {
        val out = mutableListOf<ReadSlice>()
        val issues = mutableListOf<SlicingAnomaly>()
        var acc = 0
        var startLine = blocks.first().startLine
        var lastEnd = startLine
        fun flush(endLine: Int, mid: Boolean) {
            out += ReadSlice(chapter.index, chapter.title, out.size, -1, startLine, endLine, mid)
            startLine = endLine + 1
            acc = 0
        }
        blocks.forEach { b ->
            val size = charsOf(lines, b.startLine, b.endLine + 1)
            if (size > maxChars) {
                if (acc > 0) flush(lastEnd, false)
                issues += oversizeIssue(chapter, b)
                cutOversizeBlock(chapter, lines, b, maxChars, out)
                startLine = b.endLine + 1
                lastEnd = b.endLine
                return@forEach
            }
            if (acc + size > maxChars) flush(lastEnd, false)
            acc += size
            lastEnd = b.endLine
        }
        if (acc > 0 || out.isEmpty()) flush(lastEnd, false)
        val numbered = out.mapIndexed { i, s -> s.copy(sliceIndex = i, sliceCount = out.size) }
        return numbered to issues
    }

    private fun oversizeIssue(chapter: Chapter, block: Block): SlicingAnomaly {
        val kind = if (block.dialogue) ANOMALY_DIALOGUE_RUN_CUT else ANOMALY_MID_PARAGRAPH_CUT
        val what = if (block.dialogue) "对话连段超过单片上限，块内下刀（对话被切开）" else "单段超过单片上限，段内下刀"
        return SlicingAnomaly(chapter.index, kind, what, block.startLine, block.endLine)
    }

    /**
     * 超长块：块内下刀。优先按行边界收（对话段通常一行一段）；单行就超限的
     * 再按句边界切，midLine=true 如实标记。是否异常由调用方（packBlocks）记。
     */
    private fun cutOversizeBlock(
        chapter: Chapter,
        lines: List<String>,
        block: Block,
        maxChars: Int,
        out: MutableList<ReadSlice>,
    ) {
        var start = block.startLine
        var acc = 0
        var lastEnd = start
        var i = block.startLine
        while (i <= block.endLine) {
            val len = lines[i].length + 1
            if (len > maxChars) {
                if (acc > 0) {
                    out += ReadSlice(chapter.index, chapter.title, out.size, -1, start, lastEnd)
                    acc = 0
                }
                var offset = 0
                val pieces = splitLongLine(lines[i], maxChars)
                pieces.forEachIndexed { pi, piece ->
                    val mid = pi != pieces.size - 1
                    out += ReadSlice(
                        chapter.index, chapter.title, out.size, -1, i, i, mid,
                        charStart = offset, charEnd = offset + piece.length,
                    )
                    offset += piece.length
                }
                start = i + 1
                lastEnd = i
                i++
                continue
            }
            if (acc + len > maxChars && acc > 0) {
                out += ReadSlice(chapter.index, chapter.title, out.size, -1, start, lastEnd)
                start = lastEnd + 1
                acc = 0
            }
            acc += len
            lastEnd = i
            i++
        }
        if (acc > 0 && start <= lastEnd) {
            out += ReadSlice(chapter.index, chapter.title, out.size, -1, start, lastEnd)
        }
    }

    /** 超长单行按句边界切（。！？…”』」之后是候选切点；候选太靠前或没有就硬切）。 */
    private fun splitLongLine(line: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = line
        while (rest.length > maxChars) {
            val head = rest.take(maxChars)
            val cut = head.lastIndexOfAny(charArrayOf('。', '！', '？', '…', '”', '』', '」'))
                .takeIf { it >= maxChars / 2 }
                ?: (maxChars - 1)
            val piece = rest.take(cut + 1)
            out += piece
            rest = rest.removePrefix(piece)
        }
        if (rest.isNotEmpty() || out.isEmpty()) out += rest
        return out
    }

    private fun charsOf(lines: List<String>, from: Int, until: Int): Int =
        (from until until.coerceAtMost(lines.size)).sumOf { lines[it].length + 1 }
}
