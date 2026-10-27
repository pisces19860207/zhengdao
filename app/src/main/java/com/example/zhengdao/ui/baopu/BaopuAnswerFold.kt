// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

/**
 * 第二百四十五批 · B1：答案消息的「详情 ▸」折叠**纯判据**。
 *
 * 213 把「答案先行」写进了提示层与落库口（首句就是答案、细节另起一段），
 * 但界面侧一直没做——细节照样整段铺在屏幕上。这里只决定「从哪里切开」，
 * 渲染与手势在 [BaopuMessageList]。
 *
 * 两条硬口径（派工单 B1）：
 * 1. **首行常显**，其余默认折叠；
 * 2. **代码块／列表／表格整块**：第一块是这三种之一时，整块留在首屏，
 *    绝不从中间切——切成两半的代码块比不折叠更糟。
 *
 * 切片是**按行边界**做的，且 [AnswerFold.head] ＋ [AnswerFold.tail] 逐字等于原文
 * （单测对每个样例都断言一次），所以「展开」不会丢行、不会改写空白。
 */
internal data class AnswerFold(
    /** 首屏常显的那一块（不含切点之后的换行）。 */
    val head: String,
    /** 折叠起来的部分（以切点处的换行开头；与 [head] 拼接等于原文）。 */
    val tail: String,
) {
    /** 折叠里有没有真内容——只有换行不算，不给出空折叠。 */
    val foldable: Boolean get() = tail.any { !it.isWhitespace() }

    /** 展开后的全文（逐字等于原文）。 */
    fun expand(): String = head + tail
}

internal object BaopuAnswerFoldPolicy {

    private val FENCE = Regex("^(```|~~~)")
    // 「1、」这类中文序号不带空格也常写，顿号分支允许零空格；其余形式要跟空白才认列表项。
    private val LIST_ITEM = Regex("^(?:[-*+]\\s+|\\d+、\\s*|\\d+[.)]\\s+)")
    private val TABLE_ROW = Regex("^\\|")
    private val QUOTE = Regex("^>")

    /**
     * 把一条答案切成「常显的首块」＋「折叠的其余」。
     *
     * 首块怎么定：
     * - 第一个非空行是 ``` / ~~~ ⇒ 整段围栏代码块（含收尾围栏；没收尾 ⇒ 整篇都算首块，不折叠）；
     * - 是 `|` 表格行 ⇒ 连续的表格行；
     * - 是 `- ` / `* ` / `+ ` / `1. ` 列表项 ⇒ 整串列表（含缩进续行与项间空行）；
     * - 是 `>` 引用 ⇒ 连续引用行；
     * - 其余 ⇒ 只有第一行。
     *
     * 切点落在首块最后一行的行尾：换行留给 [AnswerFold.tail]，
     * 这样两句拼接就是原文，一个字节都不改。
     */
    fun split(text: String): AnswerFold {
        if (text.isBlank()) return AnswerFold(text, "")
        val starts = lineStarts(text)
        val first = starts.indices.firstOrNull { lineOf(text, starts, it).isNotBlank() }
            ?: return AnswerFold(text, "")
        val end = blockEnd(text, starts, first)
        // 首块之后还有内容 ⇒ 切点回退到首块最后一行行尾（换行交给 tail）
        var cut = if (end < starts.size) starts[end] else text.length
        if (end < starts.size) {
            while (cut > 0 && (text[cut - 1] == '\n' || text[cut - 1] == '\r')) cut--
        }
        return AnswerFold(text.substring(0, cut), text.substring(cut))
    }

    /** 这条文本该不该出现「详情 ▸」（折叠里有内容才给按钮）。 */
    fun isFoldable(text: String): Boolean = split(text).foldable

    // ── 行工具 ────────────────────────────────────────────────────────────

    private fun lineStarts(text: String): IntArray {
        val out = ArrayList<Int>()
        out += 0
        for (i in text.indices) if (text[i] == '\n') out += i + 1
        return out.toIntArray()
    }

    private fun lineOf(text: String, starts: IntArray, i: Int): String {
        val from = starts[i]
        val to = if (i + 1 < starts.size) starts[i + 1] - 1 else text.length
        return text.substring(from, to).trimEnd('\r')
    }

    /** 首块之后的第一行行号（独占上界）。 */
    private fun blockEnd(text: String, starts: IntArray, first: Int): Int {
        val head = lineOf(text, starts, first).trimStart()
        return when {
            head.startsWith("```") || head.startsWith("~~~") -> fenceEnd(text, starts, first)
            TABLE_ROW.containsMatchIn(head) -> runEnd(text, starts, first) { l ->
                TABLE_ROW.containsMatchIn(l.trimStart())
            }
            LIST_ITEM.containsMatchIn(head) -> listEnd(text, starts, first)
            QUOTE.containsMatchIn(head) -> runEnd(text, starts, first) { l ->
                QUOTE.containsMatchIn(l.trimStart())
            }
            else -> first + 1
        }
    }

    private fun fenceEnd(text: String, starts: IntArray, first: Int): Int {
        val marker = lineOf(text, starts, first).trimStart().take(3)
        for (i in first + 1 until starts.size) {
            if (lineOf(text, starts, i).trimStart().startsWith(marker)) return i + 1
        }
        // 没收尾的围栏：整篇都是这一块，不给折叠（切开等于切一半代码）
        return starts.size
    }

    private inline fun runEnd(text: String, starts: IntArray, first: Int, match: (String) -> Boolean): Int {
        var i = first + 1
        while (i < starts.size && match(lineOf(text, starts, i))) i++
        return i
    }

    /** 列表整块：列表项、缩进续行、以及**两项之间**的空行都留在一起。 */
    private fun listEnd(text: String, starts: IntArray, first: Int): Int {
        var last = first + 1
        var i = first + 1
        while (i < starts.size) {
            val line = lineOf(text, starts, i)
            when {
                LIST_ITEM.containsMatchIn(line.trimStart()) -> {
                    last = i + 1
                    i++
                }
                line.isBlank() -> {
                    // 空行后面还是列表项 ⇒ 空行属于列表；否则列表到此为止
                    val next = i + 1
                    if (next < starts.size && LIST_ITEM.containsMatchIn(lineOf(text, starts, next).trimStart())) {
                        last = next + 1
                        i = next + 1
                    } else break
                }
                line.first() == ' ' || line.first() == '\t' -> {
                    last = i + 1
                    i++
                }
                else -> break
            }
        }
        return last
    }
}
