// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第六百一十批（通读框架 · 编码报告）。判据⑧：编码 UTF-8/GBK/BIG5 与切分异常
// **逐条报**。解码只有一份实现（TextDecode——2026-10-17 的口径统一），本文件只做
// 「把判定结果翻译成诚实的话」。Big5 的诚实口径（JDK21 实测）：GBK 判据认不出 Big5，
// Big5 硬按 GBK 解 ⇒ 替换符 0 个、全是合法怪字——没有可用的自动检测信号，
// 所以 GBK 判定一律带 Big5 盲区提示＋转码指路，不假装认得出；真坏字节（替换符
// 比例高）才拒。
package com.example.zhengdao.baopu.readthrough

import com.example.zhengdao.baopu.agent.FileInfoTool
import com.example.zhengdao.baopu.agent.TextDecode

internal data class EncodingVerdict(
    /** TextDecode 判出的编码名（UTF-8 / UTF-8+BOM / UTF-16LE / UTF-16BE / GBK…）。 */
    val encoding: String,
    val ok: Boolean,
    /** 逐条报的那句话（人话；ok=true 时也可能带注意事项）。 */
    val note: String,
    /** 解码结果里 U+FFFD 替换符的**个数**（GBK 误判 Big5 的证据；不存比例，见下）。 */
    val replacementCount: Int,
    /** 解码后的字符总数（0 ⇒ 空文本／读不了，占比按 0 处理）。 */
    val totalChars: Int,
) {
    /**
     * 替换符占比，**千分比整数**。
     *
     * 为什么不存 Double：310 判据进化门禁「禁标量分」——判据结论类型带浮点即红
     * （ci #1982/#1983 就是这么红的）。占比是**测量**不是打分，改成 counts + 整数千分比，
     * 判定线也能用整数精确比较（见 [EncodingReport.analyze]）。
     */
    val replacementPermille: Int
        get() = if (totalChars <= 0) 0 else (replacementCount * 1000L / totalChars).toInt()
}

internal object EncodingReport {

    /**
     * 替换符占比超过这个值 ⇒ 报「编码不可信，拒绝」。
     * 千分比 2‰＝0.2%（正常坏字节的量级；Big5 不触发——见文件头）。
     */
    const val SUSPECT_PERMILLE = 2

    /**
     * 把 TextDecode 的结果翻成报告。ok=false ⇒ 不启动（预检拒跑，原因写明）。
     */
    fun analyze(decoded: TextDecode.Decoded, fileName: String): EncodingVerdict {
        if (decoded.text == null) {
            return EncodingVerdict(
                encoding = decoded.encoding,
                ok = false,
                note = "${decoded.encoding} 读不了：${decoded.error ?: "未知原因"}（$fileName）",
                replacementCount = 0,
                totalChars = 0,
            )
        }
        val text = decoded.text
        val bad = text.count { it == '\uFFFD' }
        val suspect = text.isNotEmpty() && bad.toLong() * 1000L > text.length.toLong() * SUSPECT_PERMILLE
        fun percent(): String = percent2(bad, text.length)
        return when {
            decoded.encoding == FileInfoTool.Encodings.GBK && suspect -> EncodingVerdict(
                encoding = decoded.encoding,
                ok = false,
                note = "$fileName 按 GBK 判定，但替换符占比 ${percent()}%——" +
                    "疑似 Big5/繁体（TextDecode 认不出 Big5，字节配对与 GBK 同形）。" +
                    "先用 convert_encoding 转码，别硬读成乱码。",
                replacementCount = bad,
                totalChars = text.length,
            )
            decoded.encoding == FileInfoTool.Encodings.GBK -> EncodingVerdict(
                encoding = decoded.encoding,
                ok = true,
                note = "$fileName 按 GBK 解码正常（替换符 ${percent()}%）。" +
                    "注意：GBK 判据认不出 Big5（字节配对同形，实测 Big5 硬按 GBK 解不出替换符、" +
                    "只会解成怪字）——繁体老书请先用 convert_encoding 转成 UTF-8 再通读。",
                replacementCount = bad,
                totalChars = text.length,
            )
            else -> EncodingVerdict(
                encoding = decoded.encoding,
                ok = true,
                note = "$fileName：${decoded.encoding}，解码正常（替换符 ${percent()}%）。",
                replacementCount = bad,
                totalChars = text.length,
            )
        }
    }

    /** 替换符占比的两位小数百分比（整数算，不用浮点）：0.20 表示 0.20%。 */
    private fun percent2(count: Int, total: Int): String {
        if (total <= 0) return "0.00"
        val hundredths = count * 10_000L / total
        val capped = minOf(hundredths, 10_000L).toInt()
        return "%d.%02d".format(capped / 100, capped % 100)
    }
}
