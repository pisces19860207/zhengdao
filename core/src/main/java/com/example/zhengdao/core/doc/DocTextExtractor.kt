// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开规范：MS-CFB（Compound File Binary Format：512 字节头、FAT/DIFAT、目录项、
//   mini stream）、MS-DOC（Word 97–2003 二进制格式：FibBase/FibRgLw97/FibRgFcLcb97、
//   分片表 PlcPcd 的 CP→FC 映射）、CP1252。
//   **实现只用 JDK 自带件（ByteArray/StringBuilder），不引 POI、不引任何解析库。**
//
// 第六百四十二批（2026-11-14）· `.doc`（Word 97–2003）**只读**正文抽取。
//
// 为什么值得自己写（而不是"报个错说只支持 .docx"）：
//   用户的旧资料里有大量 .doc —— 合同、论文、单位模板。打不开就等于"资料库看不见一半"。
//   `.docx` 是 zip+XML（`DocxTextExtractor` 已做）；`.doc` 是**复合文档**（CFB）：
//   一个"小文件系统"里装着 `WordDocument` 和 `0Table/1Table` 两条流，正文按**分片表**
//   （PlcPcd）从 `WordDocument` 里切出来——这层结构是公开规范，能自己读，不必引库。
//
// 三条纪律：
//   1. **只读**：本文件不写任何东西（派工单 §三：不做 .doc 写出）；
//   2. **失败给一句人话**：加密的、Word 95 及更早的、改名的假 .doc 分别说清 —— 
//      上层据此回"只支持 .docx"，而不是让模型以为"路径不对"反复重试；
//   3. **纯函数**：不碰 Context、不碰文件路径；输入 ByteArray，输出文本。JVM 直测。
package com.example.zhengdao.core.doc

/** `.doc` 抽取的三种结局（成功／明确不支持）。 */
sealed interface DocRead {

    /** 抽出来了（可能为空串——真的一份空白文档）。 */
    data class Ok(val text: String, val chars: Int) : DocRead

    /** 抽不出来，[reason] 是给人看的一句话（调用方会接在「只支持 .docx」后面）。 */
    data class Unsupported(val reason: String) : DocRead
}

/**
 * Word 97–2003 `.doc` 正文抽取（**纯逻辑、零第三方依赖**）。
 */
object DocTextExtractor {

    /** 单个 `.doc` 文件大小上限（超过不试，省得白白解一个巨大文件）。 */
    const val MAX_DOC_BYTES = 40L * 1024 * 1024

    /** 抽出来的正文长度上限（与 `.docx` 那条路同口径）。 */
    const val MAX_TEXT_CHARS = 200_000

    /** 按扩展名判断能不能处理（不猜内容——内容由 [extract] 自己验）。 */
    fun canHandle(name: String): Boolean = name.lowercase().endsWith(".doc")

    /**
     * 从 `.doc` 的**原始字节**里抽正文。
     *
     * @return [DocRead.Ok] 或 [DocRead.Unsupported]（**永不抛异常**）
     */
    fun extract(bytes: ByteArray): DocRead {
        if (bytes.isEmpty()) return DocRead.Unsupported("文件是空的")
        if (!isCfb(bytes)) {
            return DocRead.Unsupported(
                "这不是 Word 97–2003 的二进制文件（可能是 RTF/HTML/纯文本改了后缀，或是 Word 95 及更早的格式）",
            )
        }
        val cfb = runCatching { Cfb(bytes) }.getOrElse {
            return DocRead.Unsupported("复合文档结构坏了（文件被截断或损坏）")
        }
        val word = cfb.stream("WordDocument")
            ?: return DocRead.Unsupported("里面没有 WordDocument 流（不是 Word 文档）")
        if (word.size < 0x200) return DocRead.Unsupported("WordDocument 流过短（文件被截断）")
        if (u16(word, 0) != 0xA5EC) return DocRead.Unsupported("WordDocument 流的文件头不对（不是 Word 文档）")
        val nFib = u16(word, 2)
        if (nFib < 0x00C1) {
            return DocRead.Unsupported(
                "这是 Word 95 或更早的格式（nFib=0x${nFib.toString(16)}），正文结构不同，暂不支持",
            )
        }
        val flags = u16(word, 0x0A)
        if (flags and 0x0100 != 0) return DocRead.Unsupported("这份 .doc 设了打开口令（加密），读不了")
        val tableName = if (flags and 0x0200 != 0) "1Table" else "0Table"
        val table = cfb.stream(tableName)
            ?: return DocRead.Unsupported("里面没有 $tableName 流（文件不完整）")
        val ccpText = i32(word, 0x4C)
        val raw = runCatching { pieces(word, table, ccpText) }.getOrNull()
            ?: return DocRead.Unsupported("分片表读不出来（文件损坏）")
        val text = normalize(clean(raw))
        if (text.isBlank()) return DocRead.Unsupported("这份 .doc 里抽不出文字（可能是纯图片扫描件）")
        val capped = if (text.length > MAX_TEXT_CHARS) text.take(MAX_TEXT_CHARS) + "\n…（已截断）" else text
        return DocRead.Ok(capped, capped.length)
    }

    // ── 复合文档（CFB）──────────────────────────────────────────────────

    /** CFB 头签名（`D0 CF 11 E0 A1 B1 1A E1`，MS-CFB §2.2）。 */
    private val CFB_SIG = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )

    private fun isCfb(bytes: ByteArray): Boolean {
        if (bytes.size < 512) return false
        for (i in CFB_SIG.indices) if (bytes[i] != CFB_SIG[i]) return false
        return true
    }

    /** 一条目录项（MS-CFB §2.6）：名字、类型（2=流、5=根）、起始扇区、字节长度。 */
    private class DirEntry(val name: String, val type: Int, val start: Int, val size: Long)

    /**
     * 复合文档读取器（够用即止：只做"按名字取一条流"）。
     *
     * 结构照 MS-CFB：头（512B，含 109 个 FAT 扇区号 + DIFAT 链）→ FAT（扇区链表）→
     * 目录（128B 一项）→ 流；小于 4096 字节的流放在 **mini stream**（根目录项的流）里。
     */
    private class Cfb(private val data: ByteArray) {

        private val sectorSize: Int
        private val miniSectorSize: Int
        private val miniCutoff: Int
        private val fat: IntArray
        private val miniFat: IntArray
        private val entries: List<DirEntry>
        private val miniStream: ByteArray

        init {
            val shift = u16(data, 30)
            sectorSize = if (shift in 9..12) 1 shl shift else 512
            val miniShift = u16(data, 32)
            miniSectorSize = if (miniShift in 5..8) 1 shl miniShift else 64
            miniCutoff = i32(data, 56).let { if (it <= 0) 4096 else it }
            fat = readFat()
            val dirStart = i32(data, 48)
            entries = readDirectory(dirStart)
            val root = entries.firstOrNull { it.type == 5 } ?: error("没有根目录项")
            miniStream = readNormalChain(root.start, root.size.toInt())
            miniFat = readMiniFat()
        }

        /** 按名字取一条流（大小写不敏感；OLE 里名字本就是大写不敏感）。 */
        fun stream(name: String): ByteArray? {
            val entry = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
            val size = entry.size.toInt()
            if (size <= 0) return ByteArray(0)
            return if (size < miniCutoff) readMiniChain(entry.start, size) else readNormalChain(entry.start, size)
        }

        private fun readFat(): IntArray {
            val ids = ArrayList<Int>()
            for (i in 0 until 109) {
                val id = i32(data, 76 + i * 4)
                if (id >= 0) ids += id
            }
            // DIFAT 链：每个 DIFAT 扇区放 (每扇区项数-1) 个 FAT 扇区号，最后 4 字节是下一个 DIFAT 扇区
            var next = i32(data, 68)
            val per = sectorSize / 4 - 1
            var guard = 0
            while (next >= 0 && guard++ < 4096) {
                val sec = sector(next) ?: break
                for (i in 0 until per) {
                    val id = i32(sec, i * 4)
                    if (id >= 0) ids += id
                }
                next = i32(sec, sectorSize - 4)
            }
            val perFat = sectorSize / 4
            val out = IntArray(ids.size * perFat)
            ids.forEachIndexed { index, id ->
                val sec = sector(id) ?: return@forEachIndexed
                for (i in 0 until perFat) {
                    val v = i32(sec, i * 4)
                    if (v != 0) out[index * perFat + i] = v
                }
            }
            return out
        }

        private fun readMiniFat(): IntArray {
            val start = i32(data, 60)
            val count = i32(data, 64)
            if (start < 0 || count <= 0) return IntArray(0)
            val bytes = readNormalChain(start, count * sectorSize)
            return IntArray(bytes.size / 4) { i32(bytes, it * 4) }
        }

        private fun readDirectory(start: Int): List<DirEntry> {
            val out = ArrayList<DirEntry>()
            var id = start
            var guard = 0
            val per = sectorSize / 128
            while (id in fat.indices && guard++ < 4096) {
                val sec = sector(id) ?: break
                for (i in 0 until per) {
                    val nameLen = u16(sec, i * 128 + 64)
                    val type = sec[i * 128 + 66].toInt() and 0xFF
                    if (type != 2 && type != 5) continue
                    val chars = ((nameLen.coerceIn(0, 64)) / 2 - 1).coerceAtLeast(0)
                    val sb = StringBuilder(chars)
                    for (c in 0 until chars) sb.append(((sec[i * 128 + c * 2].toInt() and 0xFF) or
                        ((sec[i * 128 + c * 2 + 1].toInt() and 0xFF) shl 8)).toChar())
                    val sizeLo = i32(sec, i * 128 + 120).toLong() and 0xFFFFFFFFL
                    val sizeHi = i32(sec, i * 128 + 124).toLong() and 0xFFFFFFFFL
                    out += DirEntry(sb.toString(), type, i32(sec, i * 128 + 116), (sizeHi shl 32) or sizeLo)
                }
                id = fat.getOrElse(id) { -1 }
            }
            return out
        }

        /** 普通链：按 FAT 一格一格串起来（`-1/负值`＝链尾）。 */
        private fun readNormalChain(start: Int, size: Int): ByteArray {
            val out = ByteArray(size.coerceAtLeast(0))
            var id = start
            var written = 0
            var guard = 0
            while (id >= 0 && written < out.size && guard++ < fat.size + 8) {
                val sec = sector(id) ?: break
                val n = minOf(sectorSize, out.size - written)
                System.arraycopy(sec, 0, out, written, n)
                written += n
                id = fat.getOrElse(id) { -1 }
            }
            return out
        }

        /** mini 链：扇区来自根目录那条 mini stream，每格 [miniSectorSize] 字节。 */
        private fun readMiniChain(start: Int, size: Int): ByteArray {
            val out = ByteArray(size)
            var id = start
            var written = 0
            var guard = 0
            while (id >= 0 && written < out.size && guard++ < miniFat.size + 8) {
                val off = id * miniSectorSize
                if (off < 0 || off >= miniStream.size) break
                val n = minOf(miniSectorSize, out.size - written, miniStream.size - off)
                System.arraycopy(miniStream, off, out, written, n)
                written += n
                id = miniFat.getOrElse(id) { -1 }
            }
            return out
        }

        /** 第 [id] 个扇区的字节（头占第一个扇区 ⇒ 偏移是 `(id+1)*sectorSize`）。 */
        private fun sector(id: Int): ByteArray? {
            if (id < 0) return null
            val off = (id + 1) * sectorSize
            if (off < 0 || off >= data.size) return null
            val end = minOf(off + sectorSize, data.size)
            return data.copyOfRange(off, end)
        }
    }

    // ── Word 正文（分片表）──────────────────────────────────────────────

    /** 分片表的一条：CP 区间 → 文件里的字节位置。 */
    private class Piece(val cpStart: Int, val cpEnd: Int, val offset: Int, val compressed: Boolean)

    /**
     * 按分片表把正文拼出来。
     *
     * `WordDocument` 里的字节**不是**一段连续正文：Word 会把「改过的段落」挪到文件后面，
     * 靠分片表（`Clx` 里的 `PlcPcd`）说清"CP 从 a 到 b 的那段在 fc 处、是不是 8 位压缩"。
     * 只读 `ccpText` 这一段（正文），不读页眉/脚注/批注（它们在 CP 后面，用户不需要）。
     */
    private fun pieces(word: ByteArray, table: ByteArray, ccpText: Int): String {
        val fcClx = i32(word, 0x01A2)
        val lcbClx = i32(word, 0x01A6)
        if (fcClx < 0 || lcbClx <= 0) return ""
        val end = minOf(fcClx + lcbClx, table.size)
        var pos = fcClx
        var plcStart = -1
        var plcLen = 0
        while (pos + 5 <= end) {
            when (table[pos].toInt() and 0xFF) {
                1 -> { // Prc：2 字节长度 + 数据，跳过
                    val cb = u16(table, pos + 1)
                    pos += 3 + cb
                }
                2 -> { // Pcdt：4 字节长度 + PlcPcd
                    plcLen = i32(table, pos + 1)
                    plcStart = pos + 5
                    break
                }
                else -> return ""
            }
        }
        if (plcStart < 0 || plcLen < 16 || plcStart + plcLen > table.size) return ""
        val count = (plcLen - 4) / 12
        if (count <= 0) return ""
        val cps = IntArray(count + 1) { i32(table, plcStart + it * 4) }
        val pcdBase = plcStart + (count + 1) * 4
        val sb = StringBuilder()
        for (i in 0 until count) {
            val pcd = pcdBase + i * 8
            if (pcd + 8 > table.size) break
            val fcRaw = i32(table, pcd + 2)
            val compressed = (fcRaw and 0x40000000) != 0
            val fc = fcRaw and 0x3FFFFFFF
            val offset = if (compressed) fc / 2 else fc
            val from = cps[i]
            val to = if (ccpText in 1..(cps[i + 1])) minOf(cps[i + 1], ccpText) else cps[i + 1]
            if (to <= from) continue
            for (cp in from until to) {
                val at = offset + (if (compressed) cp - from else (cp - from) * 2)
                if (at < 0 || at >= word.size) break
                if (compressed) {
                    sb.append(CP1252[word[at].toInt() and 0xFF])
                } else {
                    if (at + 1 >= word.size) break
                    sb.append(
                        ((word[at].toInt() and 0xFF) or ((word[at + 1].toInt() and 0xFF) shl 8)).toChar(),
                    )
                }
            }
        }
        return sb.toString()
    }

    // ── 文本清洗 ────────────────────────────────────────────────────────

    /**
     * Word 的二进制度量字符 → 人能读的文字（MS-DOC §2.4.1 的控制字符表）。
     *
     * `0x13/0x14/0x15` 是**域**的三段（开始指令／指令与结果分界／结束）：
     * 指令里是 `PAGE`、`HYPERLINK "…"` 这种代码，取了会变成正文里的乱码，所以整段跳过、
     * 只留结果；`0x07` 是单元格/行结束（表格里靠它分格），换成制表符。
     */
    private fun clean(raw: String): String {
        val sb = StringBuilder(raw.length)
        var inInstr = false
        for (c in raw) {
            when (c.code) {
                0x13 -> { inInstr = true; continue }
                0x14 -> { inInstr = false; continue }
                0x15 -> { inInstr = false; continue }
            }
            if (inInstr) continue
            when (c.code) {
                0x0D, 0x0B, 0x0C -> sb.append('\n')
                0x07 -> sb.append('\t')
                0x1E -> sb.append('-')
                0x1F -> Unit
                0xA0 -> sb.append(' ')
                0x01, 0x02, 0x05, 0x08 -> Unit // 图片锚点/脚注引用等占位符
                else -> if (c.code < 0x20) sb.append(' ') else sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 空白收敛（与 `DocxTextExtractor` 同口径：段内多空格压一个、连续空行压一个）。 */
    private fun normalize(s: String): String {
        val lines = s.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { it.replace(Regex("[ \\t]{2,}"), " ").trimEnd() }
        val out = ArrayList<String>(lines.size)
        var blank = false
        for (l in lines) {
            if (l.isBlank()) {
                if (!blank && out.isNotEmpty()) out.add("")
                blank = true
            } else {
                out.add(l)
                blank = false
            }
        }
        return out.joinToString("\n").trim()
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    /** CP1252 的 0x80–0x9F 段（其余与 Latin-1 相同）；压缩分片按它解。 */
    private val CP1252 = CharArray(256) { i ->
        when (i) {
            0x80 -> '\u20AC'; 0x82 -> '\u201A'; 0x83 -> '\u0192'; 0x84 -> '\u201E'
            0x85 -> '\u2026'; 0x86 -> '\u2020'; 0x87 -> '\u2021'; 0x88 -> '\u02C6'
            0x89 -> '\u2030'; 0x8A -> '\u0160'; 0x8B -> '\u2039'; 0x8C -> '\u0152'
            0x8E -> '\u017D'; 0x91 -> '\u2018'; 0x92 -> '\u2019'; 0x93 -> '\u201C'
            0x94 -> '\u201D'; 0x95 -> '\u2022'; 0x96 -> '\u2013'; 0x97 -> '\u2014'
            0x98 -> '\u02DC'; 0x99 -> '\u2122'; 0x9A -> '\u0161'; 0x9B -> '\u203A'
            0x9C -> '\u0153'; 0x9E -> '\u017E'; 0x9F -> '\u0178'
            else -> i.toChar()
        }
    }

    private fun u16(b: ByteArray, at: Int): Int {
        if (at + 2 > b.size) return 0
        return (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun i32(b: ByteArray, at: Int): Int {
        if (at + 4 > b.size) return -1
        return (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
    }
}
