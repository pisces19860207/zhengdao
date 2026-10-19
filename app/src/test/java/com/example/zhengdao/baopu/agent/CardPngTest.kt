// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 判据：F7（CRC 与结构）／F8（往返逐字，含 BOM）／F9（拒绝覆盖）／F10（非酒馆 PNG 明确报错）
//       ／P5（两块不一致取 ccv3）
// 夹具：CardFixtures（ImageIO 现画 1×1，运行时生成，不落盘）
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.card.CardFormat
import com.example.zhengdao.baopu.card.CardJson
import com.example.zhengdao.baopu.card.CardPng
import com.example.zhengdao.baopu.card.FormatKind
import com.example.zhengdao.baopu.card.PngRead
import com.example.zhengdao.baopu.card.PngWrite
import java.io.File
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardPngTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 测试自己的块解析（**不复用产品代码**，否则 CRC 是「自证」）──────

    private data class Ch(val type: String, val data: ByteArray, val crc: Long, val offset: Int)

    private fun chunks(png: ByteArray): List<Ch> {
        val out = ArrayList<Ch>()
        var off = 8
        while (off < png.size) {
            val len = ((png[off].toInt() and 0xFF) shl 24) or
                ((png[off + 1].toInt() and 0xFF) shl 16) or
                ((png[off + 2].toInt() and 0xFF) shl 8) or
                (png[off + 3].toInt() and 0xFF)
            val type = String(png, off + 4, 4, Charsets.US_ASCII)
            val data = png.copyOfRange(off + 8, off + 8 + len)
            val crc = (((png[off + 8 + len].toInt() and 0xFF).toLong() shl 24) or
                ((png[off + 9 + len].toInt() and 0xFF).toLong() shl 16) or
                ((png[off + 10 + len].toInt() and 0xFF).toLong() shl 8) or
                (png[off + 11 + len].toInt() and 0xFF).toLong())
            out += Ch(type, data, crc, off)
            off += 12 + len
            if (type == "IEND") break
        }
        return out
    }

    private fun crc32(type: String, data: ByteArray): Long {
        val c = CRC32()
        c.update(type.toByteArray(Charsets.US_ASCII))
        c.update(data)
        return c.value
    }

    // ── F7 ───────────────────────────────────────────────────────────────

    @Test
    fun `png_export_crc_and_structure`() {
        val base = CardFixtures.basePng()
        val out = File(tmp.root, "P1.png")
        val r = CardPng.export(CardFixtures.J4.trim(), base, out)
        assertTrue("导出应成功：$r", r is PngWrite.Ok)

        val png = out.readBytes()
        assertArrayEquals(CardPng.SIGNATURE, png.copyOfRange(0, 8))

        val ch = chunks(png)
        assertEquals(listOf("IHDR", "IDAT", "tEXt", "tEXt", "IEND"), ch.map { it.type })
        assertEquals("IEND 长度必须是 0", 0, ch.last().data.size)
        val kws = ch.filter { it.type == "tEXt" }.map { c ->
            val z = c.data.indexOf(0.toByte())
            String(c.data, 0, z, Charsets.ISO_8859_1)
        }
        assertEquals(listOf("chara", "ccv3"), kws)

        // 每个块的 CRC 独立复算（type+data，不含 length）
        for (c in ch) {
            assertEquals("${c.type}@${c.offset} CRC 不对", crc32(c.type, c.data), c.crc)
        }

        // 原 IHDR/IDAT 逐字节不变
        val baseCh = chunks(base)
        assertEquals(baseCh.first { it.type == "IHDR" }.data.toList(), ch.first { it.type == "IHDR" }.data.toList())
        assertArrayEquals(baseCh.first { it.type == "IDAT" }.data, ch.first { it.type == "IDAT" }.data)
    }

    // ── F8 ───────────────────────────────────────────────────────────────

    @Test
    fun `png_roundtrip_is_byte_identical`() {
        val s = "{\"z\":1,\"a\":\"\\u4e2d\",\"m\":\"中\"}"
        val out = File(tmp.root, "rt.png")
        assertTrue(CardPng.export(s, null, out) is PngWrite.Ok)
        val read = CardPng.read(out.readBytes())
        assertTrue("读回失败：$read", read is PngRead.Ok)
        assertEquals(s, (read as PngRead.Ok).jsonText)
        assertEquals(s.length, read.jsonText.length)
    }

    @Test
    fun `bom_survives_roundtrip`() {
        val s = "\uFEFF{\"a\":1}"
        val out = File(tmp.root, "bom.png")
        assertTrue(CardPng.export(s, null, out) is PngWrite.Ok)
        val read = CardPng.read(out.readBytes()) as PngRead.Ok
        assertEquals(s, read.jsonText)
        assertTrue(read.jsonText.startsWith("\uFEFF"))
    }

    // ── F9 ───────────────────────────────────────────────────────────────

    @Test
    fun `png_export_refuses_overwrite`() {
        val base = CardFixtures.basePng()
        val out = File(tmp.root, "keep.png")
        out.writeText("keep")
        val before = CardFixtures.sha256(out.readBytes())
        val baseBefore = CardFixtures.sha256(base)

        val r = CardPng.export("""{"a":1}""", base, out)
        assertTrue("目标已存在必须失败：$r", r is PngWrite.Failed)
        assertTrue((r as PngWrite.Failed).message.contains("目标已存在，未覆盖："))
        assertEquals("目标内容一个字节都不许动", before, CardFixtures.sha256(out.readBytes()))
        assertEquals("底图一个字节都不许动", baseBefore, CardFixtures.sha256(base))
        assertEquals("不许残留临时文件", 1, tmp.root.listFiles()!!.count { it.name.startsWith("keep") })
    }

    // ── F10 ──────────────────────────────────────────────────────────────

    @Test
    fun `plain_png_has_no_card_block`() {
        val bytes = CardFixtures.basePng()
        val read = CardPng.read(bytes)
        assertTrue(read is PngRead.Failed)
        assertEquals("这不是酒馆角色卡：PNG 里找不到 chara 或 ccv3 文本块。", (read as PngRead.Failed).message)
        assertNull(CardFormat.recognize(bytes).embeddedJson)
    }

    @Test
    fun `broken_base64_reports_failure`() {
        val bytes = CardFixtures.withText(CardFixtures.basePng(), "chara", "!!!not-base64!!!")
        val read = CardPng.read(bytes)
        assertTrue(read is PngRead.Failed)
        assertEquals("这是 PNG，但卡数据解不开：base64 解码失败。", (read as PngRead.Failed).message)
        assertNull(CardFormat.recognize(bytes).embeddedJson)
    }

    @Test
    fun `payload_not_json_reports_failure`() {
        val bytes = CardFixtures.withText(CardFixtures.basePng(), "chara", CardFixtures.b64("hello"))
        val read = CardPng.read(bytes)
        assertTrue(read is PngRead.Failed)
        assertEquals("这是 PNG，但卡数据解不开：内容不是 JSON。", (read as PngRead.Failed).message)
        assertNull(CardFormat.recognize(bytes).embeddedJson)
    }

    @Test
    fun `payload_not_object_is_reported_separately`() {
        val bytes = CardFixtures.withText(CardFixtures.basePng(), "chara", CardFixtures.b64("[1,2]"))
        val read = CardPng.read(bytes)
        assertTrue(read is PngRead.Failed)
        assertEquals("这是 PNG，但卡数据解不开：JSON 最外层不是对象。", (read as PngRead.Failed).message)
    }

    // ── P5 · 两块不一致取 ccv3 ───────────────────────────────────────────

    @Test
    fun `two_blocks_prefer_ccv3_with_info`() {
        val bytes = CardFixtures.pngTwoVersions()
        val read = CardPng.read(bytes) as PngRead.Ok
        assertEquals("ccv3", read.block)
        assertEquals(1, read.priority)
        assertTrue(read.jsonText.contains("chara_card_v3"))
        assertTrue("两块不一致要报 INFO：${read.notes}", read.notes.any { it.contains("已取 ccv3") })
        assertEquals(FormatKind.ST_CARD_PNG, CardFormat.recognize(bytes).format)
    }

    // ── F20-ii · 导出→读回：顶层键集合与键序不变 ────────────────────────

    @Test
    fun `roundtrip_keeps_top_level_key_order`() {
        val json = """{"entries":{"0":{"uid":0,"key":["甲"],"content":"示例","disable":false,"order":1}},"zeta":2,"alpha":3}"""
        val out = File(tmp.root, "order.png")
        assertTrue(CardPng.export(json, null, out) is PngWrite.Ok)
        val read = (CardPng.read(out.readBytes()) as PngRead.Ok).jsonText
        assertEquals(json, read)
        assertEquals(listOf("entries", "zeta", "alpha"), CardJson.topLevelKeys(read))
    }

    // ── 底图：产品现画（不走 ImageIO）─────────────────────────────────────

    @Test
    fun `builtin_base_is_a_valid_png`() {
        val png = CardPng.whiteOnePixelPng()
        assertTrue(CardPng.isPng(png))
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks(png).map { it.type })
        for (c in chunks(png)) assertEquals(c.type, crc32(c.type, c.data), c.crc)
    }
}
