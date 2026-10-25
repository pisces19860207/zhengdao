// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · CSV 读法（派工单 §1「CSV 优先」那一档）。
//
// 语义对齐 `baopu/agent/tools/DrawTable.kt:139` 的 parseCsv（双引号、成对 "" 转义、
// 空行丢弃、尾格未闭合即拒），但**没有直接复用**：那份是绘图工具的私有函数、
// 且把「只有表头」当错误抛 `DrawBad`；本批要的是「行号可回溯＋编码可判定＋错误是一句给用户的话」，
// 两处需求差别足够大，共用会把两个工具的判据绑在一起改（186 批的 DrawSourceGateTest 还钉着它）。
package com.example.zhengdao.baopu.logistics

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 一张表的读取结局：要么给出一张方格（带注记），要么给出一句能转述给用户的话。 */
sealed interface ReadOutcome {
    data class Ok(val grid: Grid) : ReadOutcome
    data class Failed(val reason: String) : ReadOutcome
}

/**
 * T9「另存为 CSV」那种文件的读法。
 *
 * ## 编码为什么算得出而不算猜
 * 中文 Windows 的 Excel 另存 CSV 默认是 **GBK**，直接按 UTF-8 读会得到一串乱码列名，
 * 然后 [LogisticsContract] 报「表头变了」——那句假话会把人引去改表头，而表头没错。
 * 判定用的是硬证据：**严格 UTF-8 解码失败**（[CodingErrorAction.REPORT] 会抛）⇒ 才试 GBK。
 * 顺序反过来（先 GBK）才是猜：GBK 什么字节都解得出来，永远不会失败。
 * 两把都解不出 ⇒ 直接拒读，不再试第三把编码。
 */
object LogisticsCsv {

    /** 单份 CSV 原文上限（200 行配货单远小于这个数；超了说明拿错了文件）。 */
    const val MAX_TEXT_CHARS = 4_000_000

    /**
     * 严格 UTF-8 解码器（每次新建）：[java.nio.charset.CharsetDecoder] 自带状态，
     * 共享一个实例等于把「上一次解码剩了多少」带给下一次。
     */
    private fun utf8Strict(): java.nio.charset.CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    /**
     * 从字节读。
     *
     * @return 注记里会出现「这份 CSV 不是 UTF-8，按 GBK 读的」——编码是要告诉用户的事，
     *   因为它决定了「同一份表两次导入得到同样的字」这条幂等前提。
     */
    fun parseBytes(bytes: ByteArray): ReadOutcome {
        if (bytes.isEmpty()) return ReadOutcome.Failed("这个文件是空的（0 字节），没有可读的表。")
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val body = if (bom) bytes.copyOfRange(3, bytes.size) else bytes
        runCatching { utf8Strict().decode(java.nio.ByteBuffer.wrap(body)).toString() }
            .getOrNull()
            ?.let { return parse(it) }
        // UTF-8 严格解码没过 ⇒ 只剩 GBK 这一把有依据的候选（GB18030 是 GBK 的超集，取超集少一个坑）
        val gb = runCatching {
            String(body, Charset.forName("GB18030"))
        }.getOrNull()
            ?: return ReadOutcome.Failed("这份 CSV 既不是 UTF-8 也不是 GB18030，读不出文字。请在导出时选「CSV UTF-8」。")
        return parse(gb).let {
            if (it is ReadOutcome.Ok) {
                ReadOutcome.Ok(it.grid.copy(notes = it.grid.notes + "这份 CSV 不是 UTF-8，按 GB18030 读的（列名与字段值已还原）。"))
            } else it
        }
    }

    /** 从已解好的文本读（纯函数，JVM 直测；表头是第 1 行）。 */
    fun parse(text: String): ReadOutcome {
        if (text.length > MAX_TEXT_CHARS) {
            return ReadOutcome.Failed("这份 CSV 太大（${text.length} 字），不像是当天的配货单导出，先确认拿的是哪个文件。")
        }
        val rows = ArrayList<MutableList<String>>()
        val rowLines = ArrayList<Int>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var line = 1        // 当前行号（1 起，真实换行数出来的）
        var rowLine = 1     // 本行第一个字符所在的行号
        var rowOpen = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when (c) {
                    '"' -> {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            cell.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    }
                    // 引号里的换行仍然计入行号：证据「第 N 行」要跟用户在编辑器里数出来的一致。
                    // 同时**这个换行是格子里的字**（CSV 规矩：引号里的一切都属于值），丢掉它
                    // 会把「钢材\n长料」粘成「钢材长料」——那是把两行字读成一个词，静默改数据。
                    '\r' -> Unit
                    '\n' -> { line++; rowOpen = false; cell.append('\n') }
                    else -> cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> { if (!rowOpen) { rowOpen = true; rowLine = line }; inQuotes = true }
                    ',' -> {
                        if (!rowOpen) { rowOpen = true; rowLine = line }
                        row.add(cell.toString())
                        cell.setLength(0)
                    }
                    // \r 直接跳过：CRLF 与 LF 两种导出都收，行边界只认 \n
                    '\r' -> Unit
                    '\n' -> {
                        flush(rows, rowLines, row, cell, rowLine, rowOpen)
                        row = ArrayList()
                        rowOpen = false
                        line++
                        rowLine = line
                    }
                    else -> {
                        if (!rowOpen) { rowOpen = true; rowLine = line }
                        cell.append(c)
                    }
                }
            }
            i++
        }
        if (inQuotes) {
            return ReadOutcome.Failed("这份 CSV 里有没闭合的双引号——多半是导出被截断了，请重新导出一份完整的。")
        }
        flush(rows, rowLines, row, cell, rowLine, rowOpen)
        // 只读的行与行号成对留下：空行（含只填横杠的行）不算数据行，但也不能把行号错位
        val keptIdx = rows.indices.filter { r -> rows[r].any { !LogisticsGrid.isBlankish(it) } }
        if (keptIdx.isEmpty()) return ReadOutcome.Failed("这份 CSV 里没有可读的行。")
        val header = rows[keptIdx.first()]
        if (header.size > LogisticsGrid.MAX_COLS) {
            return ReadOutcome.Failed("这份 CSV 表头有 ${header.size} 列，超过 ${LogisticsGrid.MAX_COLS} 列上限，不是当天配货单的形状。")
        }
        val bodyIdx = keptIdx.drop(1)
        if (bodyIdx.isEmpty()) return ReadOutcome.Failed("这份 CSV 只有表头、没有数据行——当天这张单子是空的？")
        if (bodyIdx.size > LogisticsGrid.MAX_ROWS) {
            return ReadOutcome.Failed("这份 CSV 有 ${bodyIdx.size} 行，超过单表 ${LogisticsGrid.MAX_ROWS} 行上限，先按天拆开再导。")
        }
        val body = bodyIdx.map { rows[it] }
        val bodyLines = bodyIdx.map { rowLines[it] }
        val (aligned, notes) = LogisticsGrid.align(header, body.map { r -> r.map { LogisticsGrid.cell(it) } }, bodyLines)
        val head = header.map { LogisticsGrid.cell(it) }
        if (head.none { it.isNotEmpty() }) return ReadOutcome.Failed("表头是空的：第一行没有列名。")
        return ReadOutcome.Ok(Grid(head, aligned, notes, bodyLines))
    }

    /** 收尾一行（行末或文件末）：整行空白时不落这一行，免得空行占一个行号。 */
    private fun flush(
        rows: ArrayList<MutableList<String>>,
        rowLines: ArrayList<Int>,
        row: ArrayList<String>,
        cell: StringBuilder,
        rowLine: Int,
        rowOpen: Boolean,
    ) {
        if (!rowOpen && row.isEmpty() && cell.isEmpty()) return
        val out = ArrayList(row)
        out.add(cell.toString())
        cell.setLength(0)
        rows.add(out)
        rowLines.add(rowLine)
    }
}
