// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十二批 · `.doc` 抽取的判据（派工单判据②）。
//
// 两份证据，缺一不可：
//   1. **本文件**：按 MS-CFB / MS-DOC 规范**自己造**一份复合文档（FAT、目录、mini stream、
//      分片表全都手写），逐条验抽取器的分支（UTF-16 分片、8 位压缩分片、多分片、加密、
//      Word 95、假 .doc）；
//   2. **真文件**：Word 2016 生成的 `.doc`（报告里附证据与读数）——「自己造的样本对」
//      不等于「Word 存的能读」，两条都要。
package com.example.zhengdao.core.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocTextExtractorTest {

    // ── ① 能抽：UTF-16 正文（中文就是这样存的）──────────────────────────

    @Test
    fun `单分片UTF16正文_段落按换行分开`() {
        val doc = CfbDoc.of(listOf(CfbDoc.Piece("第一段。\r第二段。", compressed = false)))
        val read = DocTextExtractor.extract(doc)
        assertTrue("应当抽出来：$read", read is DocRead.Ok)
        val text = (read as DocRead.Ok).text
        assertEquals("第一段。\n第二段。", text)
    }

    // ── ② 能抽：8 位压缩分片（纯英文老文档常见）─────────────────────────

    @Test
    fun `压缩分片_按单字节解出来`() {
        val doc = CfbDoc.of(listOf(CfbDoc.Piece("Hello\rWorld", compressed = true)))
        val read = DocTextExtractor.extract(doc)
        assertTrue("应当抽出来：$read", read is DocRead.Ok)
        assertEquals("Hello\nWorld", (read as DocRead.Ok).text)
    }

    // ── ③ 能抽：多分片（改过的段落被挪到文件后面，靠分片表拼回去）────────

    @Test
    fun `两个分片_按CP顺序拼回`() {
        val doc = CfbDoc.of(
            listOf(
                CfbDoc.Piece("甲段\r", compressed = false, at = 0x400),
                CfbDoc.Piece("乙段\r", compressed = false, at = 0x800),
            ),
        )
        val text = (DocTextExtractor.extract(doc) as DocRead.Ok).text
        assertEquals("甲段\n乙段", text)
    }

    // ── ④ 字段代码要跳过（0x13…0x14 之间是 PAGE/HYPERLINK 这类指令）────────

    @Test
    fun `域指令被跳过_只留结果`() {
        val raw = "见第\u0013 PAGE \u00142\u0015页"
        val doc = CfbDoc.of(listOf(CfbDoc.Piece(raw, compressed = false)))
        assertEquals("见第2页", (DocTextExtractor.extract(doc) as DocRead.Ok).text)
    }

    // ── ⑤ 负例：不是复合文档 ────────────────────────────────────────────

    @Test
    fun `假doc_明确说不是二进制Word`() {
        val read = DocTextExtractor.extract("这其实是个 txt 改了后缀".toByteArray())
        assertTrue(read is DocRead.Unsupported)
        assertTrue((read as DocRead.Unsupported).reason, "不是 Word" in read.reason)
    }

    @Test
    fun `空文件_明确说空`() {
        val read = DocTextExtractor.extract(ByteArray(0))
        assertTrue(read is DocRead.Unsupported)
        assertTrue((read as DocRead.Unsupported).reason.contains("空"))
    }

    // ── ⑥ 负例：加密 / Word 95 / 缺流 ───────────────────────────────────

    @Test
    fun `加密的doc_说清是口令`() {
        val doc = CfbDoc.of(listOf(CfbDoc.Piece("秘密", compressed = false)), encrypted = true)
        val read = DocTextExtractor.extract(doc)
        assertTrue(read is DocRead.Unsupported)
        assertTrue((read as DocRead.Unsupported).reason, "加密" in read.reason || "口令" in read.reason)
    }

    @Test
    fun `Word95及更早_说清版本`() {
        val doc = CfbDoc.of(listOf(CfbDoc.Piece("旧", compressed = false)), nFib = 0x0065)
        val read = DocTextExtractor.extract(doc)
        assertTrue(read is DocRead.Unsupported)
        assertTrue((read as DocRead.Unsupported).reason, "Word 95" in read.reason)
    }

    @Test
    fun `缺WordDocument流_说清不是Word文档`() {
        val doc = CfbDoc.of(emptyList(), withWordStream = false)
        val read = DocTextExtractor.extract(doc)
        assertTrue(read is DocRead.Unsupported)
        assertTrue((read as DocRead.Unsupported).reason, "WordDocument" in read.reason)
    }

    @Test
    fun `截断的文件_不抛异常只报结构坏了`() {
        val full = CfbDoc.of(listOf(CfbDoc.Piece("一二三", compressed = false)))
        val read = DocTextExtractor.extract(full.copyOfRange(0, 600))
        assertTrue(read is DocRead.Unsupported)
    }
}

/**
 * **测试专用**的最小复合文档构造器（MS-CFB + MS-DOC 的一个真子集）。
 *
 * 为什么自己造：真 `.doc` 是二进制、不能进仓（本仓没有二进制测试资源）；
 * 而"随便一段字节"又验不到分片表/迷你流这些真机制。这里按规范手写一份：
 * 头（512B）→ FAT（1 扇区）→ 目录（1 扇区）→ WordDocument（8 扇区＝4KB）→
 * mini stream（1 扇区）→ miniFAT（1 扇区）；
 * `0Table` 只有几百字节 ⇒ 走 **mini stream**（小流那条路，真 Word 文件里最常见）。
 */
private object CfbDoc {

    /** 一个分片：文字 + 是否 8 位压缩 + 在 WordDocument 里的摆放位置。 */
    data class Piece(val text: String, val compressed: Boolean, val at: Int = 0x400)

    private const val SECTOR = 512
    private const val MINI = 64

    fun of(
        pieces: List<Piece>,
        encrypted: Boolean = false,
        nFib: Int = 0x00C1,
        withWordStream: Boolean = true,
    ): ByteArray {
        // ① WordDocument：4KB（> 4096 的截止值 ⇒ 走普通 FAT，不必塞迷你流）
        val wordSize = 4096
        val word = ByteArray(wordSize)
        // FibBase
        le16(word, 0, 0xA5EC)
        le16(word, 2, nFib)
        le16(word, 0x0A, if (encrypted) 0x0100 else 0x0000) // fEncrypted
        // FibRgLw97.ccpText（正文的字符数）
        val totalChars = pieces.sumOf { it.text.length }
        le32(word, 0x4C, totalChars)
        // 分片表的正文放到各自位置
        for (p in pieces) {
            if (p.compressed) {
                p.at.let { at -> p.text.forEachIndexed { i, c -> word[at + i] = c.code.toByte() } }
            } else {
                p.text.forEachIndexed { i, c ->
                    word[p.at + i * 2] = (c.code and 0xFF).toByte()
                    word[p.at + i * 2 + 1] = ((c.code shr 8) and 0xFF).toByte()
                }
            }
        }
        // ② 0Table：CLX（clxt=2 的 Pcdt）＋ PlcPcd
        val count = pieces.size
        val plcLen = 12 * count + 4
        val table = ByteArray(SECTOR)
        table[0] = 0x02
        le32(table, 1, plcLen)
        val plc = 5
        for (i in 0..count) {
            val cp = pieces.take(i).sumOf { it.text.length }
            le32(table, plc + i * 4, cp)
        }
        val pcdBase = plc + (count + 1) * 4
        pieces.forEachIndexed { i, p ->
            // PCD：2 字节标志 ＋ 4 字节 fc（bit30 = 压缩）＋ 2 字节 prm
            val fc = if (p.compressed) (p.at * 2) or 0x40000000 else p.at
            le32(table, pcdBase + i * 8 + 2, fc)
        }
        val lcbClx = 5 + plcLen
        le32(word, 0x01A2, 0) // fcClx（表流里的偏移）
        le32(word, 0x01A6, lcbClx)

        // ③ 扇区布局：[0]=FAT [1]=目录 [2..9]=WordDocument [10]=mini stream [11]=miniFAT
        val wordSectors = wordSize / SECTOR
        val dirStart = 1
        val wordStart = 2
        val miniStreamStart = wordStart + wordSectors
        val miniFatStart = miniStreamStart + 1
        val totalSectors = miniFatStart + 1

        val fat = IntArray(SECTOR / 4) { -1 } // -1 = 链尾
        fat[0] = -3 // FATSECT
        fat[dirStart] = -1
        for (i in 0 until wordSectors) {
            val id = wordStart + i
            fat[id] = if (i == wordSectors - 1) -1 else id + 1
        }
        fat[miniStreamStart] = -1
        fat[miniFatStart] = -1

        // mini stream：0Table 按 64 字节一格装
        val miniSectors = (table.size + MINI - 1) / MINI
        val miniStream = ByteArray(miniSectors * MINI)
        System.arraycopy(table, 0, miniStream, 0, table.size)
        val miniFat = IntArray(SECTOR / 4) { -1 }
        for (i in 0 until miniSectors) miniFat[i] = if (i == miniSectors - 1) -1 else i + 1

        // ④ 头
        val header = ByteArray(SECTOR)
        val sig = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
        System.arraycopy(sig, 0, header, 0, sig.size)
        le16(header, 24, 0x003E) // minor
        le16(header, 26, 0x0003) // major
        le16(header, 28, 0xFFFE) // byte order
        le16(header, 30, 9) // sector shift ⇒ 512
        le16(header, 32, 6) // mini sector shift ⇒ 64
        le32(header, 44, 1) // FAT 扇区数
        le32(header, 48, dirStart)
        le32(header, 56, 4096) // mini 截止值
        le32(header, 60, miniFatStart)
        le32(header, 64, 1) // miniFAT 扇区数
        le32(header, 68, -2) // 没有 DIFAT 扇区（ENDOFCHAIN）
        for (i in 0 until 109) le32(header, 76 + i * 4, -1)
        le32(header, 76, 0) // 第一个 FAT 扇区 = 0

        // ⑤ 目录：Root ＋ 两条流
        val dir = ByteArray(SECTOR)
        dirEntry(dir, 0, "Root Entry", type = 5, start = miniStreamStart, size = miniStream.size.toLong())
        var slot = 1
        if (withWordStream) {
            dirEntry(dir, slot++, "WordDocument", type = 2, start = wordStart, size = wordSize.toLong())
        }
        dirEntry(dir, slot, "0Table", type = 2, start = 0, size = table.size.toLong())

        // ⑥ 拼起来
        val out = ByteArray(SECTOR + totalSectors * SECTOR)
        System.arraycopy(header, 0, out, 0, SECTOR)
        put(out, SECTOR, fatSector(fat))
        put(out, SECTOR * 2, dir)
        // 扇区 N 的偏移 = (N + 1) * SECTOR（第 0 个扇区在头之后）
        put(out, SECTOR * (wordStart + 1), word)
        put(out, SECTOR * (miniStreamStart + 1), miniStream)
        put(out, SECTOR * (miniFatStart + 1), miniFatSector(miniFat))
        return out
    }

    private fun put(target: ByteArray, offset: Int, bytes: ByteArray) {
        System.arraycopy(bytes, 0, target, offset, minOf(bytes.size, target.size - offset))
    }

    private fun fatSector(fat: IntArray): ByteArray {
        val out = ByteArray(SECTOR)
        fat.forEachIndexed { i, v -> le32(out, i * 4, v) }
        return out
    }

    private fun miniFatSector(miniFat: IntArray): ByteArray {
        val out = ByteArray(SECTOR)
        miniFat.forEachIndexed { i, v -> le32(out, i * 4, v) }
        return out
    }

    private fun dirEntry(
        dir: ByteArray,
        index: Int,
        name: String,
        type: Int,
        start: Int,
        size: Long,
    ) {
        val base = index * 128
        val bytes = name.toByteArray(Charsets.UTF_16LE)
        System.arraycopy(bytes, 0, dir, base, minOf(bytes.size, 62))
        le16(dir, base + 64, bytes.size + 2) // 名字字节数（含结尾的 0）
        dir[base + 66] = type.toByte()
        dir[base + 67] = 1 // black
        le32(dir, base + 68, -1) // left
        le32(dir, base + 72, -1) // right
        le32(dir, base + 76, -1) // child
        le32(dir, base + 116, start)
        le32(dir, base + 120, (size and 0xFFFFFFFFL).toInt())
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
