// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十七批 · Mermaid 代码块的**识别与切分**（纯函数、零 Android）。
//
// 为什么单独一层：对话气泡里的正文是一整段字符串，而「哪些是图、哪些是话」这件事
// 必须在**没有任何 Android 对象**的条件下判得出来——空行、收尾围栏、超限、图类型，
// 全部由 JVM 单测钉着。渲染器（MermaidRenderer）与 UI（BaopuMermaidBlock）只消费结果。
//
// 口径（派工单【做什么】①④＋【安全】）：
// - 围栏只认 ``` 与 ~~~，语言标签第一个词是 mermaid（大小写不敏感）才算图；
// - 没有 mermaid 围栏 ⇒ **原样返回单段文本**（一个字节都不动，既有渲染路径零变化）；
// - 代码块上限 20KB（UTF-8 字节数，不是字符数——中文一个字三字节，按字数算会放行三倍）；
// - 超限**不截断**：截断出来的图是残图，等于骗人 ⇒ 交给 UI 直说「超了」；
// - 语法对错不在这里判（那是 mermaid 自己的事，报错首行由渲染页回传）。
package com.example.zhengdao.mermaid

import java.nio.charset.StandardCharsets

/** 一张图的类型（只用来给人看和给文件起名，不参与渲染决策）。 */
enum class MermaidKind(val label: String) {
    FLOWCHART("flowchart"),
    SEQUENCE("sequence"),
    STATE("state"),
    OTHER("diagram"),
}

object MermaidFence {

    /** 单个代码块的上限：20KB（**字节**，见文件头）。 */
    const val MAX_CODE_BYTES: Int = 20 * 1024

    /** 切分后的一段：要么是话，要么是一张图。 */
    sealed interface Segment {
        data class Text(val text: String) : Segment

        /** [bytes] 是源码的 UTF-8 字节数；[overLimit] 为真时 UI 只显示提示、不许渲染。 */
        data class Diagram(val code: String, val bytes: Int, val kind: MermaidKind) : Segment {
            val overLimit: Boolean get() = bytes > MAX_CODE_BYTES
        }
    }

    /** 一次切分的完整结果（[hasDiagram] 与 [segments] 同出一次扫描，避免两遍口径）。 */
    data class Split(val segments: List<Segment>) {
        val hasDiagram: Boolean get() = segments.any { it is Segment.Diagram }
    }

    /**
     * 按行扫描围栏。**没有图时返回单段文本且字符串原样相等**（`segments[0].text === text`
     * 之外还要求内容一致——UI 靠这条判定「不用走新路径」）。
     */
    fun split(text: String): Split {
        if (text.isEmpty()) return Split(listOf(Segment.Text(text)))

        val lines = text.split('\n')
        val segments = mutableListOf<Segment>()
        val plain = mutableListOf<String>()
        var i = 0
        var found = false

        while (i < lines.size) {
            val open = mermaidFenceAt(lines[i])
            if (open == null) {
                plain.add(lines[i])
                i++
                continue
            }
            // 围栏开始：先把攒着的正文交出去（它与围栏之间隔着一个换行，见 flushPlain）
            found = true
            flushPlain(plain, withTrailingNewline = true, into = segments)
            val block = readBlock(lines, i, open)
            segments.add(block.diagram)
            i = block.next
        }

        if (!found) return Split(listOf(Segment.Text(text)))
        flushPlain(plain, withTrailingNewline = false, into = segments)
        return Split(segments)
    }

    /** 一行是不是 mermaid 围栏的**开头**（是 ⇒ 返回围栏词法，否则 null）。 */
    private fun mermaidFenceAt(line: String): FenceOpen? {
        val open = parseFenceOpen(line) ?: return null
        return if (isMermaidInfo(open.info)) open else null
    }

    /** 一段围栏的读结果：[next] 是收尾围栏之后的行号（没闭合就吃到末尾）。 */
    private class Block(val diagram: Segment.Diagram, val next: Int)

    private fun readBlock(lines: List<String>, openIndex: Int, open: FenceOpen): Block {
        val body = StringBuilder()
        var j = openIndex + 1
        var closed = false
        while (j < lines.size) {
            if (isClosingFence(lines[j], open)) {
                closed = true
                break
            }
            if (body.isNotEmpty()) body.append('\n')
            body.append(lines[j])
            j++
        }
        val code = body.toString()
        val bytes = code.toByteArray(StandardCharsets.UTF_8).size
        return Block(Segment.Diagram(code, bytes, kindOf(code)), if (closed) j + 1 else lines.size)
    }

    /**
     * 交出一段正文。`withTrailingNewline` 的区别是真讲究：
     * 围栏前面那一行**自带换行**（它和围栏之间隔着一个 \n），而文件末尾没有——
     * 少这一行，正文末尾的空行就丢了；多这一行，文件末尾会凭空长出一个空行。
     */
    private fun flushPlain(
        plain: MutableList<String>,
        withTrailingNewline: Boolean,
        into: MutableList<Segment>,
    ) {
        if (plain.isEmpty()) return
        val lastLine = plain.last()
        var s = plain.joinToString("\n")
        // 已经以空行结尾的（lastLine 为空）说明那个换行已被 join 带出来，别再补一个
        if (withTrailingNewline && lastLine.isNotEmpty()) s += "\n"
        plain.clear()
        // 纯空白段不产出（UI 那边 isBlank 也是直接 return，两处口径一致）
        if (s.isNotBlank()) into.add(Segment.Text(s))
    }

    /** 有没有 mermaid 围栏（UI 的调用点只用 [split] 一次，这里留给工具与测试）。 */
    fun hasDiagram(text: String): Boolean = split(text).hasDiagram

    /**
     * 图类型只看**第一条有内容的声明行**：`%%` 注释行跳过，`%%{init:...}%%` 也跳过。
     * 认不出来 ⇒ [MermaidKind.OTHER]（不报错：mermaid 还有 pie/gantt/class 等十几种）。
     */
    fun kindOf(code: String): MermaidKind {
        for (raw in code.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("%%")) continue
            val head = line.takeWhile { !it.isWhitespace() }
            return when (head.lowercase()) {
                "graph", "flowchart", "flowchart-elk" -> MermaidKind.FLOWCHART
                "sequencediagram" -> MermaidKind.SEQUENCE
                "statediagram", "statediagram-v2" -> MermaidKind.STATE
                else -> MermaidKind.OTHER
            }
        }
        return MermaidKind.OTHER
    }

    // ── 围栏词法（CommonMark 的窄子集：够用就行，多余的宽容会吞掉真正文）────────────

    private class FenceOpen(val ch: Char, val len: Int, val info: String)

    private fun parseFenceOpen(line: String): FenceOpen? {
        val indent = line.takeWhile { it == ' ' }.length
        if (indent > 3) return null
        val body = line.substring(indent)
        val ch = body.firstOrNull() ?: return null
        if (ch != '`' && ch != '~') return null
        val len = body.takeWhile { it == ch }.length
        if (len < 3) return null
        val info = body.substring(len).trim()
        // ```kotlin 之类：反引号围栏的 info 里不允许再出现反引号（CommonMark）
        if (ch == '`' && info.contains('`')) return null
        return FenceOpen(ch, len, info)
    }

    private fun isMermaidInfo(info: String): Boolean {
        if (info.isEmpty()) return false
        val token = info.takeWhile { !it.isWhitespace() }
        return token.lowercase() == "mermaid" || token.lowercase().startsWith("mermaid{")
    }

    private fun isClosingFence(line: String, open: FenceOpen): Boolean {
        val indent = line.takeWhile { it == ' ' }.length
        if (indent > 3) return false
        val body = line.substring(indent)
        val len = body.takeWhile { it == open.ch }.length
        if (len < open.len) return false
        return body.substring(len).trim().isEmpty()
    }
}
