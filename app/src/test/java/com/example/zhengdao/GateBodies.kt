// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百五十七批：门禁的「函数体／调用实参」结构定位（唯一一把尺，自证在 GateBodiesTest）。
// 为什么不用「切到下一个 private fun」：465 的教训——中间夹一层改可见性，切片就把不相干
// 的函数吞进来（547 为此在生产里多绕一层 citationGateMessages 转发）。括号配对只认代码
// 结构：可见性、局部名、参数序变了都不影响。
// 结构扫描在 [CodeText.codeOnly] 空间跑（字符串／注释已清空，字面量里的 `{` 顶不歪配对——
// 402 批实测过这个坑）；CodeText 保留换行 ⇒ 行号与原文对齐，返回片段按行切回**原文**，
// 断言仍看得到字符串内容（「产物/台账.md」这类负例才咬得住）。
package com.example.zhengdao

internal object GateBodies {

    /**
     * 函数体（含签名行，按原文行切回）。marker 写 `fun name(` 或 `fun name()`（不带可见性）。
     * 以 `(` 收尾时先配平参数表（默认值里的 `=` 不算表达式体）；块体配 `{}`，
     * 表达式体（`= …`）取到深度 0 的第一个换行。找不到 ⇒ null（由调用方断言，不在此_throw_）。
     */
    fun functionBody(src: String, marker: String): String? {
        val code = CodeText.codeOnly(src)
        val at = code.indexOf(marker)
        if (at < 0) return null
        var i = at + marker.length
        if (code[i - 1] == '(') i = balancedEnd(code, i - 1)
        while (i < code.length && code[i] != '{' && code[i] != '=') i++
        if (i >= code.length) return null
        val end = if (code[i] == '{') balancedEnd(code, i) else expressionEnd(code, i + 1)
        return sliceByLines(src, code, at, end)
    }

    /**
     * 每个匹配点的实参段（含括号，按原文行切回）。[callMarker] 以 `(` 收尾（如 `noteDeviation(`）。
     * 定义点也会匹配（`fun noteDeviation(` 含同一子串）——要不要排除由调用方按片段内容判。
     */
    fun callArgs(src: String, callMarker: String): List<String> {
        val code = CodeText.codeOnly(src)
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = code.indexOf(callMarker, from)
            if (at < 0) return out
            val end = balancedEnd(code, at + callMarker.length - 1)
            out += sliceByLines(src, code, at, end)
            from = end
        }
    }

    /**
     * 语句块体（含锚定行，按原文行切回）。[anchor] 在 CodeText 空间匹配语句头
     * （如 `if\s*\(\s*\w+\.rejected\s*\)`——不写局部名，改名照样切）；
     * 取匹配后第一个 `{` 配平到 `}`。锚本身以 `{` 收尾时直接用它。
     */
    fun blockBody(src: String, anchor: Regex): String? {
        val code = CodeText.codeOnly(src)
        val m = anchor.find(code) ?: return null
        val open = if (code[m.range.last] == '{') m.range.last else code.indexOf('{', m.range.last + 1)
        if (open < 0) return null
        return sliceByLines(src, code, m.range.first, balancedEnd(code, open))
    }

    /** 从 [openAt] 的开括号配到闭括号，返回闭括号**之后**的下标（code 空间；三类括号统一计数）。 */
    fun balancedEnd(code: String, openAt: Int): Int {
        require(code[openAt] in "({[") { "不是开括号：${code[openAt]} @$openAt" }
        var depth = 0
        var i = openAt
        while (i < code.length) {
            when (code[i]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        error("括号没配上：@$openAt")
    }

    /** 表达式体终点：吃过非空白后、深度回到 0 的第一个换行（单行／多行平衡调用都收得住）。 */
    private fun expressionEnd(code: String, from: Int): Int {
        var depth = 0
        var seen = false
        var i = from
        while (i < code.length) {
            val c = code[i]
            when (c) {
                '(', '{', '[' -> {
                    depth++
                    seen = true
                }
                ')', '}', ']' -> depth--
                '\n' -> if (depth == 0 && seen) return i
                else -> if (!c.isWhitespace()) seen = true
            }
            i++
        }
        return code.length
    }

    /** code 空间的下标区间 ⇒ 原文按行切（CodeText 保留换行，行号两边对齐；末行含闭括号）。 */
    private fun sliceByLines(src: String, code: String, codeStart: Int, codeEnd: Int): String {
        val startLine = code.substring(0, codeStart).count { it == '\n' }
        val endLine = code.substring(0, codeEnd).count { it == '\n' }
        val lines = src.split("\n")
        return lines.subList(startLine, (endLine + 1).coerceAtMost(lines.size)).joinToString("\n")
    }
}