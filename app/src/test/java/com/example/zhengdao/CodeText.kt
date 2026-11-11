// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五十九批（工具渐进披露）两条源码门禁要「只钉代码、不钉注释」：把注释与字符串字面量
// 从判据里去掉。原来各处现抄的三条正则（块注释 / 行注释 / 字符串）在 §2 那条
// 「全仓扫一遍不许有第二条过滤链」上直接跑崩：`"(\.|[^"\])*"` 在 Java 正则里是**逐字符递归**，
// 一个未闭合的引号（行尾注释里写个 `"xx"` 就够）会让它从那一路扩到文件尾 ⇒ StackOverflowError
// （2026-10-19 实测：ProgressiveToolsTest G1）。
//
// 手写状态机没有回溯，并且认得正则版两样都不认的东西：Kotlin 的三引号原始串
// （主源码里有 20 个文件在用）与字符字面量（`'"'`、`'\''`）。
// 放在测试源码集里：它是门禁的工具，不是产品行为。
//
// 第五百八十七批（切片尺子修复）：六个态的**边界分离**本来就是对的（三引号先于单引号判，
// 原始串整段吃到底），但 RAW 态当时只找结束的 `"""`，没像其它五态那样回填串内换行。
// GateBodies 在 code 空间数换行定位行号再切回原文 ⇒ 含跨行原始串的文件整体前移 N 行
// （N＝串内换行数）。568 在 LowRiskEvoGateTest.kt:47-50 记下盲区并绕开；本批把换行补回来。
package com.example.zhengdao

internal object CodeText {

    private const val CODE = 0
    private const val BLOCK = 1
    private const val LINE = 2
    private const val STR = 3
    private const val CHAR = 4
    private const val RAW = 5

    /**
     * 去掉注释与字面量内容后的源码。
     *
     * 四条口径：
     * - 字符串/字符/原始串**整体换成一个空串占位**（`""` / `''`），这样 `"a" + "b"` 里
     *   两个串各留一格，判据想匹配字面量内容时匹配不到（那正是我们要的：门禁钉代码）；
     * - 换行一律保留 ⇒ 行号与原文对齐，门禁失败时报的行号能用。**六个态都要回填**
     *   （含 RAW：原始串可以跨任意多行，漏一个换行就让后面所有函数的切片前移一行）；
     * - CODE 态里三引号必须**先于**单个 `"` 判定：三引号是一整段原始串，不是三次
     *   「开串／闭串／再开串」；原始串内部的单个／连续两个引号、花括号、注释符号都不切态；
     * - 遇到"字符串里出现裸换行"就退出字符串态（Kotlin 里这只会发生在没写对的源码上，
     *   编译器会先拒，本函数不需要为它兜底，兜了反而把后面的代码全吞掉）。
     */
    fun codeOnly(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var st = CODE
        while (i < src.length) {
            val c = src[i]
            val nx = if (i + 1 < src.length) src[i + 1] else ' '
            var jump = 1
            when (st) {
                CODE -> when {
                    c == '/' && nx == '*' -> { st = BLOCK; jump = 2 }
                    c == '/' && nx == '/' -> { st = LINE; jump = 2 }
                    // 三引号先判：不许把 """ 当成三次单引号边界（587 夹具第 4 例钉住）。
                    src.startsWith(RAW_QUOTE, i) -> { st = RAW; out.append(STR_MARK); jump = 3 }
                    c == '"' -> { st = STR; out.append(STR_MARK) }
                    c == '\'' -> { st = CHAR; out.append("'") }
                    else -> out.append(c)
                }

                BLOCK -> when {
                    c == '*' && nx == '/' -> { st = CODE; jump = 2 }
                    c == '\n' -> out.append(c)
                }

                LINE -> if (c == '\n') {
                    st = CODE
                    out.append(c)
                }

                STR -> when (c) {
                    '\\' -> jump = 2
                    '"' -> st = CODE
                    '\n' -> { st = CODE; out.append(c) }
                }

                CHAR -> when (c) {
                    '\\' -> jump = 2
                    '\'' -> st = CODE
                    '\n' -> { st = CODE; out.append(c) }
                }

                // 587：与其它五态一样回填串内换行 ⇒ code 空间行号与原文逐行对齐。
                // 只回填换行、不回填内容：原始串里的伪括号／伪函数头仍不进代码空间。
                RAW -> when {
                    src.startsWith(RAW_QUOTE, i) -> {
                        st = CODE
                        jump = 3
                    }
                    c == '\n' -> out.append(c)
                }
            }
            i += jump
        }
        return out.toString()
    }

    private const val RAW_QUOTE = "\"\"\""
    private const val STR_MARK = "\"\""
}
