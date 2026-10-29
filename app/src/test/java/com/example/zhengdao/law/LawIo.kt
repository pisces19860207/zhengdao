// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁补齐的**只读探针设施**（与 253 的 GateIo 同一条纪律）。
//
// 这里只做三件事：找源码、把「注释」与「字面量内容」抹成空白（行号不变）、按判据正则找命中。
// 不碰生产代码、不加反射出口、不改可见性。
//
// 为什么复用 253 的 GateIo 而不是再写一份：去注释那台状态机（认原始串、普通串、字符与转义）
// 已经被 253 的五条门禁用过一整轮，重写第二份只会让「同一个判据两种读法」。
// 本文件只补它没做的两件：
//   ① **抹字面量内容**——L2「判据高于隐喻」判的是**标识符/判定常量**，文案里出现官职词不算违规；
//   ② **代码面扫描**——把「去注释 + 抹字面量」合成一次扫描，避免每条门禁各写一遍。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.constitution.GateIo
import com.example.zhengdao.baopu.constitution.SourceHit
import java.io.File

internal object LawIo {

    /** 主源码根（单测 cwd 是 `app/` 模块目录，与 253 的门禁同一口径）。 */
    const val MAIN_ROOT = "src/main/java/com/example/zhengdao"

    /** 主源码根（相对仓根；从 IDE 直跑时 cwd 可能是仓根，兜一层）。 */
    fun mainRoot(): File = GateIo.repoFile(MAIN_ROOT)

    /** 递归收 .kt 源文本（相对路径 to 源文本，按路径稳定排序）。 */
    fun sources(relRoot: String = MAIN_ROOT): List<Pair<String, String>> = GateIo.kotlinSources(relRoot)

    fun read(rel: String): String = GateIo.read(rel)

    fun stripComments(src: String): String = GateIo.stripComments(src)

    /**
     * 把字符串／字符字面量的**内容**抹成空格（保留引号、保留换行 ⇒ 行号与列宽都不变）。
     *
     * 入参必须是**已去注释**的源（[stripComments] 的输出）：注释里的引号不参与配对，
     * 反过来的顺序会让一句「他说"算了"」把后面整段当字符串吞掉。
     */
    fun blankLiterals(commentFree: String): String {
        val out = StringBuilder(commentFree.length)
        val n = commentFree.length
        var i = 0
        var inRaw = false
        var inStr = false
        var inChar = false
        while (i < n) {
            val c = commentFree[i]
            when {
                inRaw -> {
                    if (commentFree.startsWith("\"\"\"", i)) {
                        inRaw = false
                        out.append("\"\"\"")
                        i += 3
                    } else {
                        // 原始串里的换行必须留着，否则行号会漂
                        out.append(if (c == '\n') '\n' else ' ')
                        i++
                    }
                }
                inStr -> {
                    if (c == '\\' && i + 1 < n) {
                        out.append("  ")
                        i += 2
                    } else {
                        if (c == '"') inStr = false
                        out.append(if (c == '"') '"' else if (c == '\n') '\n' else ' ')
                        i++
                    }
                }
                inChar -> {
                    if (c == '\\' && i + 1 < n) {
                        out.append("  ")
                        i += 2
                    } else {
                        if (c == '\'') inChar = false
                        out.append(if (c == '\'') '\'' else if (c == '\n') '\n' else ' ')
                        i++
                    }
                }
                commentFree.startsWith("\"\"\"", i) -> {
                    inRaw = true
                    out.append("\"\"\"")
                    i += 3
                }
                c == '"' -> {
                    inStr = true
                    out.append(c)
                    i++
                }
                c == '\'' -> {
                    inChar = true
                    out.append(c)
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 代码面：去注释 + 抹字面量内容（判「标识符/判定常量」用它）。 */
    fun codeOnly(src: String): String = blankLiterals(stripComments(src))

    /** 单文件代码面扫描；一行只记一条（法不责众：同一处违规按一处计）。 */
    fun scanCodeLine(relPath: String, src: String, apis: Map<String, Regex>): List<SourceHit> {
        val hits = mutableListOf<SourceHit>()
        codeOnly(src).lines().forEachIndexed { idx, line ->
            for ((api, re) in apis) {
                if (re.containsMatchIn(line)) {
                    hits += SourceHit(relPath, idx + 1, line.trim().take(180), api)
                    return@forEachIndexed
                }
            }
        }
        return hits
    }

    /** 整棵树按代码面扫（报告里的「文件:行」就是 [SourceHit.where]）。 */
    fun scanCodeTree(relRoot: String, apis: Map<String, Regex>): List<SourceHit> =
        sources(relRoot).flatMap { (rel, src) -> scanCodeLine("$relRoot/$rel", src, apis) }

    /**
     * 反空转下限：扫描器必须至少看见 [min] 个文件、[min] 行代码，否则「零命中」不算数。
     *
     * 为什么要有：路径写错（多一层 `app/`、模块搬家）时扫描结果是**空集**，而空集永远零命中
     * ——那就是一条恒绿的门禁。253 的门禁都带这条下限，本批照抄。
     */
    fun assertNotBlind(relRoot: String, minFiles: Int, minLines: Int): Pair<Int, Int> {
        val files = sources(relRoot)
        val lines = files.sumOf { (_, src) -> codeOnly(src).count { it == '\n' } }
        check(files.size >= minFiles) {
            "扫描器只看见 ${files.size} 个文件（应 ≥$minFiles）：路径 $relRoot 写错了？cwd=${File("").absolutePath}"
        }
        check(lines >= minLines) {
            "扫描器只看见 $lines 行代码（应 ≥$minLines）：判据会恒绿"
        }
        return files.size to lines
    }
}
