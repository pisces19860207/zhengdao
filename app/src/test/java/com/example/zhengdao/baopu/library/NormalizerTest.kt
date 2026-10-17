// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Normalizer 纯函数单测（刀 2 B 段第 3 步）。
 *
 * 钉死派工单原话级口径：**只归一编码**（去 BOM、裁行尾空白），
 * **行尾终结符原样**（CRLF 不许被偷偷转成 LF），非 UTF-8 一律拒绝（不猜 GBK）。
 */
class NormalizerTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun `去BOM_单个与多个都去`() {
        val r = Normalizer.normalize(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes("正文"))
        assertTrue(r.ok)
        assertEquals("正文", r.text)

        val double = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes("正文")
        assertEquals("正文", Normalizer.normalize(double).text)
    }

    @Test
    fun `裁行尾空白_但CRLF原样`() {
        val src = "第一行   \r\n第二行\t\r\n无空白行"
        val r = Normalizer.normalize(bytes(src))
        assertTrue(r.ok)
        assertEquals("第一行\r\n第二行\r\n无空白行", r.text)
    }

    @Test
    fun `裸LF与裸CR也原样`() {
        assertEquals("a\nb", Normalizer.normalize(bytes("a \nb")).text)
        assertEquals("a\rb", Normalizer.normalize(bytes("a \rb")).text)
    }

    @Test
    fun `行尾全是空白_内容清空但换行留下`() {
        assertEquals("\n", Normalizer.normalize(bytes("   \n")).text)
        assertEquals("", Normalizer.normalize(bytes("   ")).text)
    }

    @Test
    fun `非UTF8_拒绝且理由写明`() {
        // GBK 字节（"中文" 的 GBK 编码不是合法 UTF-8）
        val gbk = byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte())
        val r = Normalizer.normalize(gbk)
        assertFalse(r.ok)
        assertNull(r.text)
        assertTrue(r.reason.contains("不是 UTF-8 编码"))
        assertTrue(r.reason.contains("不猜"))
    }

    @Test
    fun `空输入_合法空文本`() {
        val r = Normalizer.normalize(ByteArray(0))
        assertTrue(r.ok)
        assertEquals("", r.text)
    }

    @Test
    fun `BOM与空白组合`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val r = Normalizer.normalize(bom + bytes("第一行  \n第二行"))
        assertEquals("第一行\n第二行", r.text)
    }

    // ── 产出命名 ──────────────────────────────────────────────────────────

    @Test
    fun `产出名_统一md`() {
        assertEquals("a.md", Normalizer.outputName("a.txt"))
        assertEquals("a.md", Normalizer.outputName("a.md"))
        assertEquals("a.md", Normalizer.outputName("a.json"))
        assertEquals("a.b.md", Normalizer.outputName("a.b.txt")) // 只剥最后一段扩展名
        assertEquals("README.md", Normalizer.outputName("README")) // 无扩展名兜底拼 .md
    }

    @Test
    fun `来源头_只给非txt_md的件且无时间戳`() {
        assertFalse(Normalizer.needsSourceHeader("a.txt"))
        assertFalse(Normalizer.needsSourceHeader("a.md"))
        assertFalse(Normalizer.needsSourceHeader("a.markdown"))
        assertTrue(Normalizer.needsSourceHeader("a.json"))
        assertTrue(Normalizer.needsSourceHeader("a.kt"))

        val line = Normalizer.sourceHeaderLine("资料库/原始/a.kt")
        assertTrue(line.startsWith("> 来源："))
        assertTrue(line.contains("a.kt"))
        assertTrue(!line.matches(Regex(".*\\d{4}-\\d{2}.*"))) // 无时间戳 ⇒ 同输入同字节
    }
}
