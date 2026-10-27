// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十三批 · 法典门禁化的**只读探针设施**（派工单 §3「允许的只读探针」）。
//
// 这里只有三件事：找文件、去注释（保持行号）、按判据正则找命中。
// 不碰生产代码、不加反射出口、不改可见性——五个法条门禁（L1/L3/L6/L16/L19）共用它，
// 但判据（词表、正则、白名单）各自放在各自法条的测试文件里，一个法条一个文件。
package com.example.zhengdao.baopu.constitution

import java.io.File

/** 一处命中：文件（相对 app/ 模块目录）＋ 1 起行号 ＋ 原行 ＋ 命中的判据名。 */
internal data class SourceHit(
    val relPath: String,
    val line: Int,
    val text: String,
    val api: String,
) {
    /** 体检报告用的「文件:行」。 */
    fun where(): String = "$relPath:$line"
}

internal object GateIo {

    /**
     * 单测的 Gradle 工作目录是 `app/` 模块目录（既有门禁先例
     * `ToolSchemaGateTest` / `CompressTriggerWiringGateTest` 都按它解析）；
     * 从 IDE 直跑时工作目录可能是仓根，这里兜一层。
     */
    fun repoFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    fun read(rel: String): String = repoFile(rel).readText()

    /** 递归收集 relRoot 下全部 .kt 源文本，返回「相对路径 to 源文本」（按路径稳定排序）。 */
    fun kotlinSources(relRoot: String): List<Pair<String, String>> {
        val root = repoFile(relRoot)
        check(root.isDirectory) { "扫描根不存在（单测工作目录应是 app/）：${root.absolutePath}" }
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .toList()
    }

    /**
     * 把注释替换成**空格**（行数与每行长度不变，行号因此对得上原文件）。
     * 字符串字面量里的行注释符与块注释符不当注释；认原始串、普通串、字符与转义。
     * （这里不写字面注释符——Kotlin 的块注释会嵌套，字面一写整个 KDoc 就被吞掉。）
     */
    fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        val n = src.length
        var i = 0
        var inRaw = false
        var inStr = false
        var inChar = false
        while (i < n) {
            val c = src[i]
            when {
                inRaw -> {
                    if (src.startsWith("\"\"\"", i)) {
                        inRaw = false
                        out.append("\"\"\"")
                        i += 3
                    } else {
                        if (c == '\\' && i + 1 < n) {
                            out.append(c).append(src[i + 1])
                            i += 2
                        } else {
                            out.append(c)
                            i++
                        }
                    }
                }
                inStr -> {
                    if (c == '\\' && i + 1 < n) {
                        out.append(c).append(src[i + 1])
                        i += 2
                    } else {
                        if (c == '"') inStr = false
                        out.append(c)
                        i++
                    }
                }
                inChar -> {
                    if (c == '\\' && i + 1 < n) {
                        out.append(c).append(src[i + 1])
                        i += 2
                    } else {
                        if (c == '\'') inChar = false
                        out.append(c)
                        i++
                    }
                }
                src.startsWith("\"\"\"", i) -> {
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
                src.startsWith("//", i) -> {
                    while (i < n && src[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                src.startsWith("/*", i) -> {
                    out.append("  ")
                    i += 2
                    while (i < n && !src.startsWith("*/", i)) {
                        out.append(if (src[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < n) {
                        out.append("  ")
                        i += 2
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 在单文件里按「判据名 to 正则」找命中；一行只记一条（法不责众：同一处违规按一处计）。 */
    fun scanLines(relPath: String, src: String, apis: Map<String, Regex>): List<SourceHit> {
        val stripped = stripComments(src)
        val hits = mutableListOf<SourceHit>()
        stripped.lines().forEachIndexed { idx, line ->
            for ((api, re) in apis) {
                re.find(line)?.let {
                    hits += SourceHit(relPath, idx + 1, line.trim().take(180), api)
                    return@forEachIndexed
                }
            }
        }
        return hits
    }

    /** 对目录里全部 .kt 源跑同一组判据（体检报告的「文件:行」就是这里的 [SourceHit.where]）。 */
    fun scanTree(relRoot: String, apis: Map<String, Regex>): List<SourceHit> =
        kotlinSources(relRoot).flatMap { (rel, src) -> scanLines("$relRoot/$rel", src, apis) }
}
