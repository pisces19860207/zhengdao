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
    /** 解码结果里 U+FFFD 替换符的占比（GBK 误判 Big5 的证据）。 */
    val replacementRatio: Double,
)

internal object EncodingReport {

    /** 替换符占比超过这个值 ⇒ 报「编码不可信，拒绝」（0.2% 是正常坏字节的量级；Big5 不触发——见文件头）。 */
    const val SUSPECT_RATIO = 0.002

    /**
     * 把 TextDecode 的结果翻成报告。ok=false ⇒ 不启动（预检拒跑，原因写明）。
     */
    fun analyze(decoded: TextDecode.Decoded, fileName: String): EncodingVerdict {
        if (decoded.text == null) {
            return EncodingVerdict(
                encoding = decoded.encoding,
                ok = false,
                note = "${decoded.encoding} 读不了：${decoded.error ?: "未知原因"}（$fileName）",
                replacementRatio = 1.0,
            )
        }
        val text = decoded.text
        val bad = text.count { it == '\uFFFD' }
        val ratio = if (text.isEmpty()) 0.0 else bad.toDouble() / text.length
        return when {
            decoded.encoding == FileInfoTool.Encodings.GBK && ratio > SUSPECT_RATIO -> EncodingVerdict(
                encoding = decoded.encoding,
                ok = false,
                note = "$fileName 按 GBK 判定，但替换符占比 ${"%.2f".format(ratio * 100)}%——" +
                    "疑似 Big5/繁体（TextDecode 认不出 Big5，字节配对与 GBK 同形）。" +
                    "先用 convert_encoding 转码，别硬读成乱码。",
                replacementRatio = ratio,
            )
            decoded.encoding == FileInfoTool.Encodings.GBK -> EncodingVerdict(
                encoding = decoded.encoding,
                ok = true,
                note = "$fileName 按 GBK 解码正常（替换符 ${"%.2f".format(ratio * 100)}%）。" +
                    "注意：GBK 判据认不出 Big5（字节配对同形，实测 Big5 硬按 GBK 解不出替换符、" +
                    "只会解成怪字）——繁体老书请先用 convert_encoding 转成 UTF-8 再通读。",
                replacementRatio = ratio,
            )
            else -> EncodingVerdict(
                encoding = decoded.encoding,
                ok = true,
                note = "$fileName：${decoded.encoding}，解码正常（替换符 ${"%.2f".format(ratio * 100)}%）。",
                replacementRatio = ratio,
            )
        }
    }
}
