// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单 §3「安全」——zip-slip 必拒、总量 ≤2GB、默认不覆盖。
// 三条判据都要求**先看清单再落盘**，而 `java.util.zip` 不给我们这三样东西：
//   · 符号链接标记（Unix mode 在「外部属性」高 16 位，ZipEntry 不暴露）；
//   · 解压后大小（ZipInputStream 读到条目时 size 常为 -1）；
//   · 原始名字字节（乱码时无从补救）。
// ⇒ 自己走一遍中央目录（只读索引，不解压任何数据）。这不是「重写解压器」：
// 数据仍然由 ZipInputStream 解，本文件只负责把**清单**读出来给策略层判。
package com.example.zhengdao.baopu.archive

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 中央目录里的一个成员（策略层只认这几个字段，别的一概不读）。 */
internal class ZipMember(
    /** 已按条目声明的编码解码出来的名字（可能是乱码——原样保留，拒绝文案里要能看见它）。 */
    val name: String,
    /** 原始名字字节：解码是否可信由 [utf8Flag] 决定，交付报告里要能贴出字节。 */
    val rawName: ByteArray,
    val method: Int,
    /** 声明的解压后字节数（中央目录原值；不可信，所以落盘时还要再数一遍）。 */
    val declaredSize: Long,
    /** 通用标志位 bit 11（EFS）＝名字是 UTF-8。 */
    val utf8Flag: Boolean,
    /** 通用标志位 bit 0＝加密。 */
    val encrypted: Boolean,
    /** Unix mode 是 S_IFLNK（符号链接条目）。 */
    val symlink: Boolean,
) {
    val isDirectory: Boolean get() = name.endsWith("/") || name.endsWith("\\")
}

/** 索引结果：能读＝给清单；读不动＝给一句人话（不抛异常，调用方包成 ToolOutcome.Failed）。 */
internal sealed interface ZipIndexResult {
    data class Ok(val members: List<ZipMember>) : ZipIndexResult
    data class Bad(val reason: String) : ZipIndexResult
}

/**
 * 只读 zip 中央目录，产出成员清单。
 *
 * 三种编码兜底（顺序即优先级）：
 * 1. EFS 置位 ⇒ 严格 UTF-8（解不动就按坏包报，不猜）；
 * 2. EFS 未置位 ⇒ 先试严格 UTF-8（很多老工具写中文名却不置位），再试 GBK（Windows 中文区常见）；
 * 3. 都不行 ⇒ 按 ISO-8859-1 原样映射（至少名字唯一、拒绝文案里能看见原文）。
 *
 * 为什么必须自己找 EOCD：中央目录偏移只在 EOCD 里，而 EOCD 后面可能跟着注释
 * （最多 65535 字节），所以要从尾部往前扫签名。
 */
internal object ZipIndex {

    private const val SIG_EOCD = 0x06054b50L
    private const val SIG_EOCD64_LOCATOR = 0x07064b50L
    private const val SIG_EOCD64 = 0x06064b50L
    private const val SIG_CENTRAL = 0x02014b50L

    /** 尾部扫描窗口：EOCD 22 字节 ＋ 最长注释 65535 字节。 */
    private const val EOCD_WINDOW = 22 + 0xFFFF

    /** 中央目录读取上限：7 万条目约 4 MB，64 MB 够用且防「声明一个 4GB 中央目录」的坏包。 */
    private const val MAX_CENTRAL_BYTES = 64L * 1024 * 1024

    /** Unix 文件类型掩码与符号链接类型（tar/zip 共用的 `S_IFLNK`）。 */
    private const val S_IFMT = 0xF000
    private const val S_IFLNK = 0xA000

    private val GBK: Charset? = runCatching { Charset.forName("GBK") }.getOrNull()

    fun parse(file: File): ZipIndexResult {
        if (!file.isFile) return ZipIndexResult.Bad("不是一个文件：${file.name}")
        if (file.length() < 22L) return ZipIndexResult.Bad("不是有效的 zip：文件只有 ${file.length()} 字节，连目录尾都放不下")
        return runCatching { RandomAccessFile(file, "r").use { read(it, file.length()) } }
            .getOrElse { ZipIndexResult.Bad("读不懂这个 zip 的目录结构（${it.javaClass.simpleName}: ${it.message ?: "无细节"}）") }
    }

    private fun read(raf: RandomAccessFile, fileLength: Long): ZipIndexResult {
        val tailLen = minOf(fileLength, EOCD_WINDOW.toLong()).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(fileLength - tailLen)
        raf.readFully(tail)

        val eocdInTail = findEocd(tail)
            ?: return ZipIndexResult.Bad("不是有效的 zip：文件尾部找不到目录结束标记（可能是 tar/rar，或者文件被截断）")
        val eocdPos = fileLength - tailLen + eocdInTail

        var entries = u16(tail, eocdInTail + 10)
        var centralSize = u32(tail, eocdInTail + 12)
        var centralOffset = u32(tail, eocdInTail + 16)

        // Zip64：三个字段任一被写成「满值」就要去 EOCD64 取真值（4GB/65535 条以上）。
        if (entries == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) {
            val z64 = readZip64(raf, eocdPos)
                ?: return ZipIndexResult.Bad("zip64 目录尾缺失或损坏（条目数/大小超过 4GB 的包必须带它）")
            entries = z64.first.toInt()
            centralSize = z64.second
            centralOffset = z64.third
        }

        if (centralOffset < 0 || centralSize < 0 || centralOffset + centralSize > fileLength) {
            return ZipIndexResult.Bad("目录偏移越界（声明 $centralOffset+$centralSize，文件只有 $fileLength 字节），拒绝解析")
        }
        if (centralSize > MAX_CENTRAL_BYTES) {
            return ZipIndexResult.Bad("中央目录声明 ${centralSize / 1024 / 1024} MB，超过 ${MAX_CENTRAL_BYTES / 1024 / 1024} MB 上限，拒绝解析")
        }

        val cd = ByteArray(centralSize.toInt())
        raf.seek(centralOffset)
        raf.readFully(cd)

        val members = ArrayList<ZipMember>(minOf(entries, 4096))
        var p = 0
        while (p + 46 <= cd.size) {
            if (u32(cd, p) != SIG_CENTRAL) break
            val madeBy = u16(cd, p + 4)
            val flags = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            var usize = u32(cd, p + 24)
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            val externalAttr = u32(cd, p + 38)
            val nameStart = p + 46
            val extraStart = nameStart + nameLen
            val next = extraStart + extraLen + commentLen
            if (nameStart + nameLen > cd.size || next > cd.size) break

            val rawName = cd.copyOfRange(nameStart, nameStart + nameLen)

            // Zip64 扩展字段：只有「32 位字段被写满值」的那些才有内容，且顺序固定
            // （usize → csize → offset → disk）。这里只要 usize，所以只在 usize 满值时读它；
            // 若误在「只有 csize 满值」时也读，就会把 csize 当成 usize（大小判据跟着错）。
            if (usize == 0xFFFFFFFFL) {
                readZip64Size(cd, extraStart, extraLen)?.let { usize = it }
            }

            val utf8Flag = (flags and 0x0800) != 0
            val hostUnix = (madeBy ushr 8) == 3
            val symlink = hostUnix && ((externalAttr ushr 16).toInt() and S_IFMT) == S_IFLNK

            members.add(
                ZipMember(
                    name = decodeName(rawName, utf8Flag),
                    rawName = rawName,
                    method = method,
                    declaredSize = usize,
                    utf8Flag = utf8Flag,
                    encrypted = (flags and 0x0001) != 0,
                    symlink = symlink,
                ),
            )
            p = next
        }

        if (members.isEmpty() && entries > 0) {
            return ZipIndexResult.Bad("目录里声明了 $entries 个成员，但一条也没解析出来（目录损坏）")
        }
        return ZipIndexResult.Ok(members)
    }

    /** 从尾部往前扫 EOCD 签名（注释里可能也出现签名，所以要校验注释长度自洽）。 */
    private fun findEocd(tail: ByteArray): Int? {
        for (i in tail.size - 22 downTo 0) {
            if (u32(tail, i) != SIG_EOCD) continue
            val commentLen = u16(tail, i + 20)
            if (i + 22 + commentLen <= tail.size) return i
        }
        return null
    }

    /** 读 Zip64 目录尾：返回（条目数, 中央目录大小, 中央目录偏移）。 */
    private fun readZip64(raf: RandomAccessFile, eocdPos: Long): Triple<Long, Long, Long>? {
        if (eocdPos < 20) return null
        val loc = ByteArray(20)
        raf.seek(eocdPos - 20)
        raf.readFully(loc)
        if (u32(loc, 0) != SIG_EOCD64_LOCATOR) return null
        val z64Pos = u64(loc, 8)
        if (z64Pos < 0 || z64Pos + 56 > raf.length()) return null
        val z64 = ByteArray(56)
        raf.seek(z64Pos)
        raf.readFully(z64)
        if (u32(z64, 0) != SIG_EOCD64) return null
        return Triple(u64(z64, 32), u64(z64, 40), u64(z64, 48))
    }

    /** 解 Zip64 扩展字段里的解压后大小（0x0001 的第一项就是 usize）。 */
    private fun readZip64Size(cd: ByteArray, start: Int, len: Int): Long? {
        var p = start
        val end = start + len
        while (p + 4 <= end) {
            val id = u16(cd, p)
            val size = u16(cd, p + 2)
            val body = p + 4
            if (id == 0x0001 && body + 8 <= end) return u64(cd, body)
            p = body + size
        }
        return null
    }

    private fun decodeName(raw: ByteArray, utf8Flag: Boolean): String {
        if (utf8Flag) return strictDecode(raw, Charsets.UTF_8) ?: String(raw, Charsets.UTF_8)
        strictDecode(raw, Charsets.UTF_8)?.let { return it }
        if (GBK != null) strictDecode(raw, GBK)?.let { return it }
        return String(raw, Charsets.ISO_8859_1)
    }

    /** 严格解码：遇到非法字节返回 null（宽松解码会把坏名字变成一堆 U+FFFD，判不出是哪个包写坏的）。 */
    private fun strictDecode(raw: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(raw))
            .toString()
    }.getOrNull()

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

    private fun u64(b: ByteArray, at: Int): Long = u32(b, at) or (u32(b, at + 4) shl 32)
}
