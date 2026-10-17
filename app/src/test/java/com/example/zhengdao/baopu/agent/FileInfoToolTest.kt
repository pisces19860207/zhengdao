// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FileInfoTool 单测（刀 2 追加包；判据追加⑤：GBK txt 判出 GBK）。
 *
 * 覆盖：GBK / UTF-8 / UTF-8 BOM / UTF-16LE / UTF-16BE / 二进制 / 未知；
 * 大小与行数；**只读前 64 KB**（大文件前半 UTF-8 后半乱码仍判 UTF-8）；
 * 越界即拒；目录与不存在分别报。
 */
class FileInfoToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun setup() {
        root = tmp.newFolder("workspace")
    }

    private fun info(f: File) = FileInfoTool.info(root, f)

    private fun body(r: ToolOutcome): String = (r as ToolOutcome.Ok).text

    private fun file(name: String, bytes: ByteArray): File {
        val f = File(root, name)
        f.writeBytes(bytes)
        return f
    }

    // ── 判据⑤：GBK 判出 GBK ──────────────────────────────────────────────

    @Test
    fun `GBK文本_判出GBK`() {
        setup()
        // 「中文」两字的 GBK 编码（D6 D0 CE C4）——不是合法 UTF-8
        val f = file("gbk.txt", byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte()))

        val out = body(info(f))

        assertTrue("必须判出 GBK：$out", out.contains("编码（猜测）：GBK"))
        assertTrue(out.contains("类型：文本"))
    }

    @Test
    fun `GBK长文本_混合ASCII也判GBK`() {
        setup()
        val head = "第一行\n第二行\n".toByteArray(Charsets.UTF_8)
        val gbk = "内容".toByteArray(charset("GBK"))
        val f = file("mixed.txt", head + gbk)

        // 纯 GBK（无 UTF-8 段落）判 GBK
        assertTrue(body(info(file("pure.txt", gbk + gbk))).contains("编码（猜测）：GBK"))
        // UTF-8 与 GBK 混排 ⇒ 两种判定都不成立 ⇒ 未知（启发式的诚实边界：
        // 这种文件既不是合法 UTF-8 也不是配对一致的 GBK，硬猜只会误导）
        assertTrue(body(info(f)).contains("未知"))
    }

    // ── 其他编码 ─────────────────────────────────────────────────────────

    @Test
    fun `UTF8判UTF8`() {
        setup()
        val f = file("u8.txt", "中文加 English".toByteArray(Charsets.UTF_8))
        assertTrue(body(info(f)).contains("编码（猜测）：UTF-8\n"))
    }

    @Test
    fun `UTF8带BOM`() {
        setup()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val f = file("bom.txt", bom + "正文".toByteArray(Charsets.UTF_8))
        assertTrue(body(info(f)).contains("UTF-8（带 BOM）"))
    }

    @Test
    fun `UTF16LE与BE_靠BOM认`() {
        setup()
        // 无 BOM 的 UTF-16 与 GBK 不可区分（字节层面同形）⇒ 只认带 BOM 的（标准启发式）
        val le = file("le.txt", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "中文".toByteArray(Charsets.UTF_16LE))
        assertTrue(body(info(le)).contains("UTF-16LE"))

        val be = file("be.txt", byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "中文".toByteArray(Charsets.UTF_16BE))
        assertTrue(body(info(be)).contains("UTF-16BE"))
    }

    @Test
    fun `多行UTF16_不判二进制_换行字节里有NUL`() {
        // 2026-10-17 真缺陷：'\n' 的 UTF-16 字节是 0A 00，旧序「见 NUL 即二进制」
        // 把所有多行 UTF-16 文本判成二进制 ⇒ read_file 拒读。
        setup()
        val f = file(
            "multi.txt",
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "第一行\n第二行".toByteArray(Charsets.UTF_16LE),
        )
        val out = body(info(f))
        assertTrue("要认出是 UTF-16LE：$out", out.contains("UTF-16LE"))
        assertTrue("不许判成二进制：$out", out.contains("类型：文本"))
        assertTrue("行数要能给：$out", out.contains("行数：2 行"))
    }

    @Test
    fun `二进制_NUL即判且行数不适用`() {
        setup()
        val f = file("b.bin", byteArrayOf(0x00, 0x01, 0x02, 0x00))
        val out = body(info(f))
        assertTrue(out.contains("二进制（不猜编码）"))
        assertTrue(out.contains("类型：二进制"))
        assertTrue(out.contains("行数：不适用"))
    }

    @Test
    fun `空文件判UTF8零行`() {
        setup()
        val f = file("empty.txt", ByteArray(0))
        val out = body(info(f))
        assertTrue(out.contains("编码（猜测）：UTF-8"))
        assertTrue(out.contains("行数：0 行"))
        assertTrue(out.contains("大小：0 B"))
    }

    // ── 大小与行数 ───────────────────────────────────────────────────────

    @Test
    fun `大小与行数_人类可读`() {
        setup()
        val f = file("a.txt", (1..5).joinToString("\n").toByteArray())
        val out = body(info(f))
        assertTrue(out.contains("行数：5 行"))
        assertTrue(out.contains("B（"))
        // 末行无换行也算一行
        assertEquals(5, File(root, "a.txt").readLines().size)
    }

    @Test
    fun `大小KB换算_小数点不随语言变`() {
        setup()
        val f = file("k.txt", ByteArray(2048))
        assertTrue(body(info(f)).contains("2.0 KB"))
    }

    // ── 只读前 64 KB ─────────────────────────────────────────────────────

    @Test
    fun `编码判断只读前64KB_后半不影响判定`() {
        setup()
        // 前 64KB+ 全是合法 UTF-8 中文，GBK 字节放在 64 KB **之外** ⇒ 嗅探看不到 ⇒ 仍判 UTF-8
        val head = "字".repeat(21846).toByteArray(Charsets.UTF_8) // 65538 B > 64 KB
        val tail = "中文".toByteArray(charset("GBK")) // 落在嗅探窗口外
        val f = file("bigish.txt", head + tail)

        assertTrue(body(info(f)).contains("编码（猜测）：UTF-8"))
    }

    @Test
    fun `编码判断_64KB边界截断在多字节中间宽松判过`() {
        setup()
        // 65536 正好把一个 3 字节汉字劈开（65535 + 1）⇒ 截断 ⇒ 宽松判过，不误报 GBK
        val f = file("cut.txt", "字".repeat(21846).toByteArray(Charsets.UTF_8))
        assertTrue(body(info(f)).contains("编码（猜测）：UTF-8"))
    }

    @Test
    fun `超过8MB不数字数但明说`() {
        setup()
        // 真文本内容（不能 setLength 拉长度——那会用 NUL 填充，先被二进制判定吃掉）
        val f = file("huge.txt", ByteArray(FileInfoTool.LINE_COUNT_MAX_BYTES.toInt() + 1024) { 'A'.code.toByte() })

        val out = body(info(f))

        assertTrue(out.contains("类型：文本"))
        assertTrue(out.contains("行数：未数"))
        assertTrue(out.contains("8 MB"))
    }

    // ── 拒绝 ─────────────────────────────────────────────────────────────

    @Test
    fun `越界即拒`() {
        setup()
        val outside = File(tmp.newFolder("outside"), "a.txt").apply { writeText("x") }
        assertTrue(info(outside) is ToolOutcome.Rejected)
    }

    @Test
    fun `不存在与目录分别报`() {
        setup()
        val r1 = info(File(root, "none.txt"))
        assertTrue(r1 is ToolOutcome.Failed)
        assertTrue((r1 as ToolOutcome.Failed).error.contains("不存在"))

        val r2 = info(root)
        assertTrue(r2 is ToolOutcome.Failed)
        assertTrue((r2 as ToolOutcome.Failed).error.contains("只列目录") || (r2 as ToolOutcome.Failed).error.contains("只看文件"))
    }

    @Test
    fun `权限FREE且Schema单参`() {
        val spec = FileInfoTool.spec { root }
        assertEquals("file_info", spec.name)
        assertEquals(ToolPermission.FREE, spec.permission)
        assertEquals(1, spec.inputSchema.getJSONObject("properties").length())
        assertFalse(spec.inputSchema.optBoolean("additionalProperties"))
    }

    // ── 编码判据纯函数 ───────────────────────────────────────────────────

    @Test
    fun `UTF8判定_合法与非法序列`() {
        assertTrue(FileInfoTool.looksLikeUtf8("abc".toByteArray()))
        assertTrue(FileInfoTool.looksLikeUtf8("中文".toByteArray(Charsets.UTF_8)))
        assertFalse(FileInfoTool.looksLikeUtf8(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))) // 续字节开头
        assertFalse(FileInfoTool.looksLikeUtf8(byteArrayOf(0xC0.toByte(), 0x80.toByte()))) // 过长编码
        // 截断在多字节序列中间 ⇒ 宽松判过
        assertTrue(FileInfoTool.looksLikeUtf8("中".toByteArray(Charsets.UTF_8).dropLast(1).toByteArray()))
    }

    @Test
    fun `GBK判定_至少要见过双字节`() {
        assertFalse(FileInfoTool.looksLikeGbk("abc".toByteArray())) // 纯 ASCII 不算
        assertTrue(FileInfoTool.looksLikeGbk("中文".toByteArray(charset("GBK"))))
        // 双字节第二字节落在 0x40..0xFE 之外 ⇒ 不像 GBK
        assertFalse(FileInfoTool.looksLikeGbk(byteArrayOf(0xD6.toByte(), 0x20.toByte())))
    }

    @Test
    fun `编码优先级_BOM压过二进制判定`() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(FileInfoTool.Encodings.UTF8_BOM, FileInfoTool.guessEncoding(utf8Bom, binary = false))
        // 2026-10-17 改判：BOM 先于二进制。BOM 是文本的强证据，而 UTF-16 文本的
        // 换行/ASCII 段字节里天然有 0x00——旧序会把每一个多行 UTF-16 文件判成二进制。
        val utf16Le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x0A, 0x00)
        assertEquals(FileInfoTool.Encodings.UTF16_LE, FileInfoTool.guessEncoding(utf16Le, binary = true))
        // 没有 BOM 的 NUL 文件照旧判二进制
        assertEquals(FileInfoTool.Encodings.BINARY, FileInfoTool.guessEncoding(byteArrayOf(0x41, 0x00, 0x42), binary = true))
    }
}
