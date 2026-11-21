// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.core.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 765 · 坏名判定纯函数（[ScanNameRules]）的判定面：五类原因码逐类、判定顺序固定、
 * 转义不含原始字节。全部字符串直测，不碰文件系统（IO 行为在 [ScanToleranceTest]）。
 */
class ScanNameRulesTest {

    /** 300 个 UTF-8 字节的 CJK 名（100 字 × 3B；字符数 100 < 255，NTFS 建得出来）。 */
    private val tooLongName = "设".repeat(100)

    @Test
    fun `五类原因码的wire名与派工单逐字一致`() {
        assertEquals(
            listOf("decode-fail", "too-long", "reserved", "attr-fail", "denied"),
            listOf(
                ScanNameRules.REASON_DECODE_FAIL,
                ScanNameRules.REASON_TOO_LONG,
                ScanNameRules.REASON_RESERVED,
                ScanNameRules.REASON_ATTR_FAIL,
                ScanNameRules.REASON_DENIED,
            ),
        )
    }

    @Test
    fun `decode_fail_名字含替换符`() {
        assertEquals(ScanNameRules.REASON_DECODE_FAIL, ScanNameRules.badNameReasonOf("人\uFFFD设定.md"))
        assertEquals(ScanNameRules.REASON_DECODE_FAIL, ScanNameRules.badNameReasonOf("\uFFFD"))
    }

    @Test
    fun `too_long_按UTF_8字节算_255是边界`() {
        // 100 个 CJK 字 = 300 字节 > 255；字符数才 100（NTFS 255 字符限内建得出来）
        assertEquals(ScanNameRules.REASON_TOO_LONG, ScanNameRules.badNameReasonOf(tooLongName))
        // 255 字节恰好合规（85 个 CJK 字）：边界内不误报
        assertNull(ScanNameRules.badNameReasonOf("设".repeat(85)))
        // 256 字节（86 个 CJK 字）越界
        assertEquals(ScanNameRules.REASON_TOO_LONG, ScanNameRules.badNameReasonOf("设".repeat(86)))
    }

    @Test
    fun `reserved_保留设备名_大小写与主名口径`() {
        assertEquals(ScanNameRules.REASON_RESERVED, ScanNameRules.badNameReasonOf("CON"))
        assertEquals(ScanNameRules.REASON_RESERVED, ScanNameRules.badNameReasonOf("con.md"))
        assertEquals(ScanNameRules.REASON_RESERVED, ScanNameRules.badNameReasonOf("Com9.tar.gz"))
        assertEquals(ScanNameRules.REASON_RESERVED, ScanNameRules.badNameReasonOf("nul"))
        assertEquals(ScanNameRules.REASON_RESERVED, ScanNameRules.badNameReasonOf("LPT1.txt"))
        // CON 不是主名（XCON / CONX）⇒ 正常
        assertNull(ScanNameRules.badNameReasonOf("XCON.md"))
        assertNull(ScanNameRules.badNameReasonOf("CONX.md"))
    }

    @Test
    fun `reserved_尾空格或尾点也是Windows保留形态`() {
        assertEquals(ScanNameRules.REASON_RESERVED, ScanNameRules.badNameReasonOf("笔记 "))
        assertEquals(ScanNameRules.REASON_RESERVED, ScanNameRules.badNameReasonOf("笔记."))
        // 尾部是中文句号不算（只有 ASCII 点/空格是 Windows 会改的）
        assertNull(ScanNameRules.badNameReasonOf("笔记。"))
    }

    @Test
    fun `判定顺序固定_先中先记_一条只给一个原因`() {
        // decode-fail + 超长 ⇒ 先中 decode-fail（字节数已不可信）
        assertEquals(
            ScanNameRules.REASON_DECODE_FAIL,
            ScanNameRules.badNameReasonOf("\uFFFD" + tooLongName),
        )
        // 保留名 + 尾点 ⇒ 都归 reserved，只记一条
        assertEquals(ScanNameRules.REASON_RESERVED, ScanNameRules.badNameReasonOf("NUL."))
    }

    @Test
    fun `正常名一律null`() {
        assertNull(ScanNameRules.badNameReasonOf("第三章-十二点七.md"))
        assertNull(ScanNameRules.badNameReasonOf("人物设定.md"))
        assertNull(ScanNameRules.badNameReasonOf("S-023"))
        assertNull(ScanNameRules.badNameReasonOf("1756123456789-七号井量测规程.md"))
    }

    @Test
    fun `escapeName_控制符与替换符转义_不含原始字节`() {
        assertEquals("\\uFFFD", ScanNameRules.escapeName("\uFFFD"))
        assertEquals("行\\n首", ScanNameRules.escapeName("行\n首"))
        assertEquals("回\\r车", ScanNameRules.escapeName("回\r车"))
        assertEquals("制\\t表", ScanNameRules.escapeName("制\t表"))
        assertEquals("a\\\\b", ScanNameRules.escapeName("a\\b"))
        // 控制字符（如 0x01）转成 \uXXXX
        assertEquals("\\u0001", ScanNameRules.escapeName("\u0001"))
        // 正常中文与点原样
        assertEquals("人物设定.md", ScanNameRules.escapeName("人物设定.md"))
    }

    @Test
    fun `escapeName_转义后不含任何控制符或替换符`() {
        val nasty = "\uFFFD\n\r\t\\\u0001\u007F正文.md"
        val escaped = ScanNameRules.escapeName(nasty)
        for (ch in escaped) {
            assertTrue(
                "转义结果里不允许再出现控制符/替换符（坏名要能安全进账行）：$escaped",
                ch >= ' ' && ch != '\u007F' && ch != '\uFFFD',
            )
        }
        assertTrue(escaped.contains("\\uFFFD"))
    }
}
