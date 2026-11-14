// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十二批 · `read_file` 对 `.doc` 的接线判据（派工单判据②）。
//
// 判据②的负例写得很明确：**报"不存在"⇒ 红**。所以这里逐条钉：
//   · 能抽 ⇒ 正文带行号回来（与纯文本同一套行号，模型不用学新格式）；
//   · 抽不出 ⇒ 一句话里必须出现「只支持 .docx」（模型据此换路子，而不是原地重试路径）；
//   · 假 `.doc`（文本改名）⇒ 同上，不许当文本硬读出一堆乱码。
//
// 夹具是**测试里现造**的最小复合文档（本仓不放二进制测试资源）；抽取器本身的分支
// （mini stream / 压缩分片 / 加密 / Word 95）在 `:core` 的 `DocTextExtractorTest` 里。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadFileDocTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
    }

    @Test
    fun `doc老格式_抽出正文并按行带号`() {
        val f = File(ws, "旧合同.doc").apply { writeBytes(docOf("甲方：青灯科技\r乙方：张三\r")) }
        val out = ReadFileTool.read(f, 0, 200)
        assertTrue("应当读出来：$out", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue("正文要在：$text", text.contains("甲方：青灯科技") && text.contains("乙方：张三"))
        assertTrue("行号口径与纯文本一致（从 0 起、制表符分隔）：$text", text.contains("0\t甲方：青灯科技"))
        assertTrue("要说明这是 .doc 抽出来的：$text", text.contains(".doc"))
    }

    @Test
    fun `doc也能分段读_offset生效`() {
        val f = File(ws, "两行.doc").apply { writeBytes(docOf("第一行\r第二行\r")) }
        val out = ReadFileTool.read(f, 1, 1)
        assertTrue(out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue("从第 1 行起只给一行：$text", text.contains("1\t第二行") && !text.contains("0\t第一行"))
    }

    @Test
    fun `改名的假doc_明确说只支持docx`() {
        val f = File(ws, "假的.doc").apply { writeText("这是一个 txt，只是改了后缀") }
        val out = ReadFileTool.read(f, 0, 200)
        assertTrue("不许当文本硬读：$out", out is ToolOutcome.Failed)
        val err = (out as ToolOutcome.Failed).error
        assertTrue("必须点名只支持 .docx：$err", "只支持 .docx" in err)
        assertTrue("还要给出下一步（另存为 .docx）：$err", ".docx" in err)
    }

    @Test
    fun `抽不出文字的doc_明确报错而不是空回显`() {
        val f = File(ws, "空白.doc").apply { writeBytes(docOf("")) }
        val out = ReadFileTool.read(f, 0, 200)
        assertTrue("空文档也要说清：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error, "只支持 .docx" in (out as ToolOutcome.Failed).error)
    }

    @Test
    fun `docx仍走老路_doc分支不抢`() {
        // 一份只有 ZIP 头的假 docx：走的是纯文本那条路（不归 .doc 管），证明分支按扩展名分得清
        val f = File(ws, "说明.docx").apply { writeText("这是一份普通文本内容") }
        val out = ReadFileTool.read(f, 0, 200)
        assertTrue("docx 不归 .doc 分支管：$out", out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("这是一份普通文本内容"))
    }

    // ── 夹具：最小复合文档（CFB）─────────────────────────────────────────

    /**
     * 造一份**能被抽取器读懂**的最小 `.doc`。
     *
     * 结构（每个扇区 512 字节）：头 ｜ FAT ｜ 目录 ｜ WordDocument(4KB) ｜ 0Table(4KB)。
     * 两条流都 ≥ 4096 ⇒ 都走普通 FAT（mini stream 那条路由 `:core` 的测试覆盖）。
     */
    private fun docOf(raw: String): ByteArray {
        val sector = 512
        val wordStart = 2
        val tableStart = wordStart + 8
        val total = tableStart + 8

        val word = ByteArray(4096)
        le16(word, 0, 0xA5EC)
        le16(word, 2, 0x00C1)
        le32(word, 0x4C, raw.length) // ccpText
        raw.forEachIndexed { i, c ->
            word[0x400 + i * 2] = (c.code and 0xFF).toByte()
            word[0x400 + i * 2 + 1] = ((c.code shr 8) and 0xFF).toByte()
        }
        val table = ByteArray(4096)
        table[0] = 0x02
        le32(table, 1, 16) // lcb：一个分片 = 4*(1+1) + 8
        le32(table, 5, 0) // CP0
        le32(table, 9, raw.length) // CP1
        le32(table, 15, 0x400) // PCD.fc（UTF-16，不压缩）
        le32(word, 0x01A2, 0) // fcClx
        le32(word, 0x01A6, 21) // lcbClx = 5 + 16

        val fat = IntArray(sector / 4) { -1 }
        fat[0] = -3
        for (i in 0 until 8) fat[wordStart + i] = if (i == 7) -1 else wordStart + i + 1
        for (i in 0 until 8) fat[tableStart + i] = if (i == 7) -1 else tableStart + i + 1

        val dir = ByteArray(sector)
        dirEntry(dir, 0, "Root Entry", 5, -1, 0)
        dirEntry(dir, 1, "WordDocument", 2, wordStart, 4096)
        dirEntry(dir, 2, "0Table", 2, tableStart, 4096)

        val header = ByteArray(sector)
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
            .copyInto(header, 0)
        le16(header, 24, 0x003E)
        le16(header, 26, 0x0003)
        le16(header, 28, 0xFFFE)
        le16(header, 30, 9)
        le16(header, 32, 6)
        le32(header, 44, 1)
        le32(header, 48, 1) // 目录扇区
        le32(header, 56, 4096)
        le32(header, 60, -2) // 没有 miniFAT
        le32(header, 64, 0)
        le32(header, 68, -2) // 没有 DIFAT
        for (i in 0 until 109) le32(header, 76 + i * 4, -1)
        le32(header, 76, 0)

        val out = ByteArray(sector + total * sector)
        header.copyInto(out, 0)
        fatBytes(fat).copyInto(out, sector)
        dir.copyInto(out, sector * 2)
        // 扇区 N 的偏移 = (N + 1) * sector（第 0 个扇区在头之后）
        word.copyInto(out, sector * (wordStart + 1))
        table.copyInto(out, sector * (tableStart + 1))
        return out
    }

    private fun fatBytes(fat: IntArray): ByteArray {
        val out = ByteArray(512)
        fat.forEachIndexed { i, v -> le32(out, i * 4, v) }
        return out
    }

    private fun dirEntry(dir: ByteArray, index: Int, name: String, type: Int, start: Int, size: Int) {
        val base = index * 128
        val bytes = name.toByteArray(Charsets.UTF_16LE)
        bytes.copyInto(dir, base, 0, minOf(bytes.size, 62))
        le16(dir, base + 64, bytes.size + 2)
        dir[base + 66] = type.toByte()
        dir[base + 67] = 1
        le32(dir, base + 68, -1)
        le32(dir, base + 72, -1)
        le32(dir, base + 76, -1)
        le32(dir, base + 116, start)
        le32(dir, base + 120, size)
        le32(dir, base + 124, 0)
    }

    private fun le16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun le32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        b[at + 2] = ((v shr 16) and 0xFF).toByte()
        b[at + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
