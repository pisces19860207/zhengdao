// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单 §3「解压总量 ≤2GB」——要在**落盘之前**拒，而不是写满 2GB 再回滚。
//
// 为什么 Java 侧还要读一遍 tar 头：Rust 的 `nativeExtract` 只有「解到目录」一个出口，
// 没有「先报清单/总量」的接口，而本批**不许重编 .so**。于是：
// · `.tar` / `.tar.gz` / `.tgz` —— 压缩壳是 Java 自带的（GZIPInputStream 在 java.util.zip 里），
//   所以能在 Java 侧只读头、不落盘地算出总大小（本文件）；
// · `.tar.zst` / `.tar.xz` —— Java 没有 zstd/xz 解码器（266 批已删 tukaani:xz），
//   只能落进暂存目录后再判（见 [NativeExtractor]）。
// 两种路径的**拒绝结果一致**，差别只在「拒绝时磁盘上有没有暂存」——交付报告里写明。
package com.example.zhengdao.baopu.archive

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** tar 头只读扫描（不落盘、不解数据块）。 */
internal object TarScan {

    private const val BLOCK = 512
    private const val SIZE_OFF = 124
    private const val SIZE_LEN = 12

    /**
     * 扫一遍 tar 头，返回声明的解压后总字节数。
     *
     * @return null＝读不动（不是 tar、截断、或压缩壳不认识）——调用方按「无法预判」处理，
     *         不许把 null 当 0（那等于放行一个炸弹）。
     */
    fun uncompressedBytes(archive: File, kind: ArchiveKind): Long? {
        return runCatching {
            openStream(archive, kind)?.use { input -> sum(input) }
        }.getOrNull()
    }

    private fun openStream(archive: File, kind: ArchiveKind): InputStream? = when (kind) {
        ArchiveKind.TAR -> archive.inputStream().buffered(BLOCK)
        ArchiveKind.TAR_GZ -> GZIPInputStream(archive.inputStream().buffered(BLOCK), BLOCK)
        // 其余压缩壳 Java 侧没有解码器 ⇒ 返回 null（交给暂存路径判）
        else -> null
    }

    private fun sum(input: InputStream): Long {
        val header = ByteArray(BLOCK)
        var total = 0L
        var zeroBlocks = 0
        while (true) {
            if (!readFully(input, header)) return total // 正常收尾（没有两块 0 的包也存在）
            if (header.all { it == 0.toByte() }) {
                zeroBlocks++
                if (zeroBlocks >= 2) return total
                continue
            }
            zeroBlocks = 0
            val size = parseSize(header) ?: return total
            // 数据块一律跳过：pax/longname 头本身也是一条记录，它的 size 同样计入总量（保守）。
            total += size
            if (!skip(input, ((size + BLOCK - 1) / BLOCK) * BLOCK)) return total
        }
    }

    /** 解析 12 字节八进制 size（GNU 的 base-256 编码也认）。 */
    private fun parseSize(header: ByteArray): Long? {
        val first = header[SIZE_OFF].toInt() and 0xFF
        if (first and 0x80 != 0) {
            // base-256：首字节是标志，剩下是大端整数。
            var v = (first and 0x7F).toLong()
            for (i in 1 until SIZE_LEN) {
                v = (v shl 8) or (header[SIZE_OFF + i].toLong() and 0xFF)
            }
            return if (v < 0) null else v
        }
        var v = 0L
        var seen = false
        for (i in 0 until SIZE_LEN) {
            val c = header[SIZE_OFF + i].toInt() and 0xFF
            if (c == 0 || c == ' '.code) {
                if (seen) break else continue
            }
            if (c < '0'.code || c > '7'.code) return null
            v = v * 8 + (c - '0'.code)
            seen = true
        }
        return if (seen) v else 0L
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skip(input: InputStream, count: Long): Boolean {
        var left = count
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) return false
            left -= n
        }
        return true
    }
}
