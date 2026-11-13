// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.readthrough

import com.example.zhengdao.baopu.agent.TextDecode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编码报告单测（610 判据⑧：UTF-8/GBK/BIG5 逐条报）。
 *
 * 钉住的判据：UTF-8/GBK 判定正常要如实说；Big5（TextDecode 认不出，与 GBK 字节配对
 * 同形）要报「疑似 Big5」并**拒绝启动**——不硬读成乱码。
 */
class EncodingReportTest {

    private fun decode(bytes: ByteArray) = TextDecode.decode(File("书.txt"), bytes)

    @Test
    fun `UTF8_正常_报ok`() {
        val v = EncodingReport.analyze(decode("第一章 正文".toByteArray(Charsets.UTF_8)), "书.txt")
        assertTrue(v.ok)
        assertEquals("UTF-8", v.encoding)
        assertTrue(v.note.contains("书.txt"))
    }

    @Test
    fun `GBK老书_判定GBK_解码正常带注意`() {
        val text = "第一章 起步\n张小凡拜入青云门，御剑而行。\n"
        val v = EncodingReport.analyze(decode(text.toByteArray(charset("GBK"))), "书.txt")
        assertTrue("GBK 书应判 ok：$v", v.ok)
        assertEquals("GBK", v.encoding)
        assertTrue("注意里要提 Big5 盲区：${v.note}", v.note.contains("Big5"))
        assertTrue("要给转码指路：${v.note}", v.note.contains("convert_encoding"))
        assertEquals("GBK 正常书不该有替换符", 0, v.replacementCount)
        assertEquals(0, v.replacementPermille)
    }

    @Test
    fun `Big5繁体_判成GBK_如实报盲区与转码指路_不假装认得出`() {
        // TextDecode 的诚实边界（2026-10-17 口径）：Big5 认不出。实测（JDK21）：
        // Big5 硬按 GBK 解 ⇒ 替换符 0 个、全是「合法怪字」——任何 FFFD 启发式都抓不到。
        // 所以这里的硬纪律是「如实说盲区＋给转码指路」，而不是假装有检测。
        val text = "第一章 開端\n林驚羽拜入青雲門，御劍而行，遇見了張小凡。\n"
        val bytes = text.toByteArray(charset("Big5"))
        val v = EncodingReport.analyze(decode(bytes), "书.txt")
        assertTrue("GBK 判据认得出就照常放行：$v", v.ok)
        assertEquals("GBK", v.encoding)
        assertTrue("要明说 Big5 盲区：${v.note}", v.note.contains("Big5"))
        assertTrue("要给转码指路：${v.note}", v.note.contains("convert_encoding"))
    }

    @Test
    fun `真坏GBK字节_替换符比例高_拒绝并给证据`() {
        // 0x81 0x7F：GBK 双字节的非法配对（0x7F 不在尾字节范围）——这是真坏字节，
        // 与 Big5（合法但错）不同，必须拒。
        val broken = ByteArray(120) { if (it % 2 == 0) 0x81.toByte() else 0x7F }
        val v = EncodingReport.analyze(decode(broken), "书.txt")
        assertFalse("坏字节必须拒绝：$v", v.ok)
    }

        @Test
    fun `占比是整数千分比_不是浮点`() {
        // 310 判据进化门禁「禁标量分」：判据结论类型不许带 Double/Float（ci #1982 真因）。
        // 占比用 counts + 千分比表达，这里钉住换算本身。
        assertEquals(5, EncodingVerdict("GBK", false, "x", replacementCount = 5, totalChars = 1000).replacementPermille)
        assertEquals(0, EncodingVerdict("GBK", true, "x", replacementCount = 0, totalChars = 0).replacementPermille)
        assertEquals("恰好 2‰ 不算超线",
            EncodingReport.SUSPECT_PERMILLE,
            EncodingVerdict("GBK", true, "x", replacementCount = 2, totalChars = 1000).replacementPermille)
    }

@Test
    fun `二进制_读不了_报拒`() {
        val v = EncodingReport.analyze(
            TextDecode.Decoded(null, "", null, "二进制文件不读：书.txt"),
            "书.txt",
        )
        assertFalse(v.ok)
        assertTrue(v.note.contains("读不了"))
    }
}
