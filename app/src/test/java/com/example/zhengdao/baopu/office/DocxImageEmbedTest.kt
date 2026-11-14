// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十二批 · 图片格式的判据（派工单判据③）：
//   · webp **直接进包**（Android 原生能画，docx 里按 image/webp 写）；
//   · heic **先解成 PNG 再进包**——断言"包里那份确实是 PNG"，不是"调用过转换函数"。
//
// 两种证据：
//   1. 本文件（JVM）：用**结构正确的最小位流头**验探测与分流，用假解码器验 heic→PNG；
//   2. 真文件（报告里附读数）：真 WebP（gstatic 图）与真 HEIC（libheif 样例）过一遍工具。
// 为什么假解码器也算数：平台解码器（BitmapFactory）在 JVM 里是桩，判据要的是
// "进去是 heic、出来是 png"这条**分流**；解码本身归系统，真机那条在报告里另验。
package com.example.zhengdao.baopu.office

import com.example.zhengdao.baopu.agent.DocxWriteTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

class DocxImageEmbedTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
    }

    // ── ① 探测：三种 WebP 位流都要读得出宽高 ────────────────────────────────

    @Test
    fun `webp三种位流的宽高都读得出来`() {
        val vp8 = webpVp8(320, 200)
        assertEquals(ImageKind.WEBP, ImageProbe.kindOf(vp8))
        assertEquals(ImageInfo("webp", 320, 200), ImageProbe.probe(vp8))

        val vp8l = webpVp8l(64, 48)
        assertEquals(ImageKind.WEBP, ImageProbe.kindOf(vp8l))
        assertEquals(ImageInfo("webp", 64, 48), ImageProbe.probe(vp8l))

        val vp8x = webpVp8x(1024, 768)
        assertEquals(ImageKind.WEBP, ImageProbe.kindOf(vp8x))
        assertEquals(ImageInfo("webp", 1024, 768), ImageProbe.probe(vp8x))
    }

    @Test
    fun `heic按ftyp品牌认出来_GIF之类明确不是`() {
        assertEquals(ImageKind.HEIC, ImageProbe.kindOf(heicBytes()))
        val gif = "GIF89a".toByteArray() + ByteArray(40)
        assertEquals(ImageKind.OTHER, ImageProbe.kindOf(gif))
    }

    // ── ② webp：原字节进包，content-types 写 image/webp ─────────────────────

    @Test
    fun `webp直嵌_包里是webp不是转码`() {
        val bytes = webpVp8(320, 200)
        val f = File(ws, "图/示例.webp").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        val out = DocxWriteTool.run(oneImage("webp 直嵌", "图/" + f.name), ws)
        assertTrue("应当写成功：$out", out is ToolOutcome.Ok)
        val docx = onlyDocx()
        val names = entryNames(docx)
        assertTrue("包里的媒体条目要是 .webp：$names", "word/media/image1.webp" in names)
        assertTrue(
            "content-types 要登记 image/webp",
            entryText(docx, "[Content_Types].xml").contains("ContentType=\"image/webp\""),
        )
        val media = entryBytes(docx, "word/media/image1.webp")
        assertArrayEquals("嵌入的就是原字节（没有转码）", bytes, media)
    }

    // ── ③ heic：解成 PNG 再嵌，包里那份真的是 PNG ──────────────────────────

    @Test
    fun `heic先解成PNG再嵌_包里是png`() {
        val heic = heicBytes()
        File(ws, "图/照片.heic").apply { parentFile?.mkdirs(); writeBytes(heic) }
        val png = PNG_1X1.copyOf()
        var called = 0
        val out = DocxWriteTool.run(
            oneImage("heic 插图", "图/照片.heic"),
            ws,
        ) { input ->
            called++
            assertEquals("解码器收到的必须是 heic 原字节", heic.size, input.size)
            png
        }
        assertTrue("应当写成功：$out", out is ToolOutcome.Ok)
        assertEquals("解码器只该被叫一次", 1, called)
        val docx = onlyDocx()
        val names = entryNames(docx)
        assertTrue("包里要是 .png：$names", "word/media/image1.png" in names)
        assertFalse("包里不许出现 .heic：" + names.joinToString(), names.any { it.endsWith(".heic") })
        val media = entryBytes(docx, "word/media/image1.png")
        assertArrayEquals(
            "嵌进去的必须是解码后的 PNG 字节",
            png,
            media,
        )
        assertEquals("PNG 签名", 0x89.toByte(), media[0])
        assertTrue(
            "content-types 要登记 image/png",
            entryText(docx, "[Content_Types].xml").contains("ContentType=\"image/png\""),
        )
    }

    // ── ④ 负例：解不了 / 解成别的格式 / 不认的格式 ───────────────────────────

    @Test
    fun `生产默认解码器_在JVM里明确失败而不产坏文档`() {
        File(ws, "图/照片.heic").apply { parentFile?.mkdirs(); writeBytes(heicBytes()) }
        // 不注入解码器 ⇒ 走生产默认的 AndroidHeicDecode；JVM 里 android.graphics 是桩
        // （调用即抛 "not mocked"）⇒ 解码器回 null ⇒ 工具必须明确失败，不许产出一份坏文档
        val out = DocxWriteTool.run(oneImage("没解码器", "图/照片.heic"), ws)
        assertTrue("必须失败：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error, "HEIC" in out.error)
        assertEquals("失败不落盘", 0, products().size)
    }

    @Test
    fun `解码器给出非PNG_当场拒_不产坏文档`() {
        File(ws, "图/照片.heic").apply { parentFile?.mkdirs(); writeBytes(heicBytes()) }
        val out = DocxWriteTool.run(oneImage("解错格式", "图/照片.heic"), ws) { JPEG_1X1.copyOf() }
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error, "不是 PNG" in out.error)
        assertEquals(0, products().size)
    }

    @Test
    fun `解码器给出垃圾字节_明确说解不开`() {
        File(ws, "图/照片.heic").apply { parentFile?.mkdirs(); writeBytes(heicBytes()) }
        val out = DocxWriteTool.run(oneImage("解不开", "图/照片.heic"), ws) { ByteArray(8) }
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error, "解不开" in out.error)
        assertEquals(0, products().size)
    }

    @Test
    fun `GIF之类_明确只支持四种`() {
        File(ws, "图/动图.gif").apply {
            parentFile?.mkdirs()
            writeBytes("GIF89a".toByteArray() + ByteArray(64))
        }
        val out = DocxWriteTool.run(oneImage("gif", "图/动图.gif"), ws)
        assertTrue(out is ToolOutcome.Failed)
        val err = (out as ToolOutcome.Failed).error
        assertTrue(err, "只支持" in err && "HEIC" in err)
        assertEquals(0, products().size)
    }

    // ── 夹具：最小但结构正确的位流头 ────────────────────────────────────────

    /** WebP 有损（`VP8 `）：RIFF 头 ＋ 帧标签 ＋ 起始码 9D 01 2A ＋ 宽高（各 2 字节，低 14 位）。 */
    private fun webpVp8(w: Int, h: Int): ByteArray {
        val payload = ByteArray(10).also {
            it[3] = 0x9D.toByte()
            it[4] = 0x01
            it[5] = 0x2A
            it[6] = (w and 0xFF).toByte()
            it[7] = ((w shr 8) and 0x3F).toByte()
            it[8] = (h and 0xFF).toByte()
            it[9] = ((h shr 8) and 0x3F).toByte()
        }
        return riff("VP8 ", payload)
    }

    /** WebP 无损（`VP8L`）：0x2F ＋ 4 字节里 14 位宽-1、14 位高-1。 */
    private fun webpVp8l(w: Int, h: Int): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14)
        val payload = ByteArray(10).also {
            it[0] = 0x2F
            it[1] = (bits and 0xFF).toByte()
            it[2] = ((bits shr 8) and 0xFF).toByte()
            it[3] = ((bits shr 16) and 0xFF).toByte()
            it[4] = ((bits shr 24) and 0xFF).toByte()
        }
        return riff("VP8L", payload)
    }

    /** WebP 扩展（`VP8X`）：标志 1 ＋ 保留 3 ＋ 画布宽-1（3 字节）＋ 高-1（3 字节）。 */
    private fun webpVp8x(w: Int, h: Int): ByteArray {
        val payload = ByteArray(10).also {
            le24(it, 4, w - 1)
            le24(it, 7, h - 1)
        }
        return riff("VP8X", payload)
    }

    /** 组一个 RIFF/WEBP 容器（总长 ≥ 30 字节，探测才认）。 */
    private fun riff(fourcc: String, payload: ByteArray): ByteArray {
        // RIFF(4) + 长度(4) + WEBP(4) + 四字符码(4) + 块长度(4) + 负载
        val body = ByteArray(20 + payload.size)
        "RIFF".toByteArray().copyInto(body, 0)
        le32(body, 4, body.size - 8)
        "WEBP".toByteArray().copyInto(body, 8)
        fourcc.toByteArray().copyInto(body, 12)
        le32(body, 16, payload.size)
        payload.copyInto(body, 20)
        return body
    }

    /** 一个最小的 HEIC：`ftyp` 盒 ＋ `heic` 主品牌（探测只认品牌）。 */
    private fun heicBytes(): ByteArray {
        val b = ByteArray(24)
        le32(b, 0, 16)
        "ftyp".toByteArray().copyInto(b, 4)
        "heic".toByteArray().copyInto(b, 8)
        le32(b, 12, 0)
        "mif1".toByteArray().copyInto(b, 16)
        return b
    }

    private fun le24(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        b[at + 2] = ((v shr 16) and 0xFF).toByte()
    }

    private fun le32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        b[at + 2] = ((v shr 16) and 0xFF).toByte()
        b[at + 3] = ((v shr 24) and 0xFF).toByte()
    }

    // ── 夹具：工具参数与包读取 ─────────────────────────────────────────────

    private fun oneImage(name: String, path: String): JSONObject = JSONObject()
        .put("name", name)
        .put(
            "blocks",
            JSONArray()
                .put(JSONObject().put("type", "paragraph").put("text", "配一张图"))
                .put(JSONObject().put("type", "image").put("path", path)),
        )

    private fun products(): List<File> = File(ws, "产物").listFiles().orEmpty().filter { it.isFile }

    private fun onlyDocx(): File {
        val all = products().filter { it.name.endsWith(".docx") }
        assertEquals("产物里应当恰好一份 docx：${all.map { it.name }}", 1, all.size)
        return all.first()
    }

    private fun entryNames(file: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
            }
        }
        return names
    }

    private fun entryText(file: File, name: String): String =
        entryBytes(file, name).toString(Charsets.UTF_8)

    private fun entryBytes(file: File, name: String): ByteArray {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == name) return zip.readBytes()
            }
        }
        throw AssertionError("包里没有 $name（有：${entryNames(file)}）")
    }

    private fun assertArrayEquals(msg: String, expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(msg, expected, actual)
    }

    private companion object {
        /** 标准 1×1 透明 PNG。 */
        val PNG_1X1: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )

        /** 最小合法 JPEG 头（只为验"解码器给错格式"那条分支）。 */
        val JPEG_1X1: ByteArray = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 0x08,
            0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
            0xFF.toByte(), 0xD9.toByte(),
        )
    }
}
