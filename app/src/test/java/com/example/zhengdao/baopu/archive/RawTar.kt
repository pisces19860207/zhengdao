// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 测试用「手搓 tar」：`.tar` / `.tar.gz` 的**预扫描**判据（落盘前就按声明总量拒绝）需要
// 一个大小可精确控制的 tar；项目里已无 commons-compress（第二百六十六批删除），
// 而 tar 头是 512 字节定长结构，手写比引依赖便宜。
package com.example.zhengdao.baopu.archive

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/** tar 里的一条成员（只写普通文件，够用）。 */
internal class RawTarEntry(val name: String, val data: ByteArray)

internal object RawTar {

    private const val BLOCK = 512

    fun writeTar(file: File, entries: List<RawTarEntry>) {
        val out = ByteArrayOutputStream()
        for (e in entries) {
            out.write(header(e.name, e.data.size.toLong(), '0'))
            out.write(e.data)
            val pad = (BLOCK - e.data.size % BLOCK) % BLOCK
            if (pad > 0) out.write(ByteArray(pad))
        }
        out.write(ByteArray(BLOCK * 2)) // 两块 0 ＝ 结束
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }

    fun writeTarGz(file: File, entries: List<RawTarEntry>) {
        val plain = File(file.parentFile, file.name + ".plain.tmp")
        writeTar(plain, entries)
        GZIPOutputStream(file.outputStream()).use { gz -> gz.write(plain.readBytes()) }
        plain.delete()
    }

    private fun header(name: String, size: Long, type: Char): ByteArray {
        val h = ByteArray(BLOCK)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, h, 0, minOf(nameBytes.size, 100))
        putOctal(h, 100, 8, 0b110_100_100L) // mode 0644
        putOctal(h, 108, 8, 0L)
        putOctal(h, 116, 8, 0L)
        putOctal(h, 124, 12, size)
        putOctal(h, 136, 12, 0L)
        h[156] = type.code.toByte()
        "ustar".forEachIndexed { i, c -> h[257 + i] = c.code.toByte() }
        h[262] = 0
        h[263] = '0'.code.toByte()
        h[264] = '0'.code.toByte()
        // 校验和：先把校验和字段填成空格，再求和，最后写成 6 位八进制 + NUL + 空格。
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        var sum = 0L
        for (b in h) sum += (b.toInt() and 0xFF)
        val oct = String.format(java.util.Locale.ROOT, "%06o", sum)
        oct.forEachIndexed { i, c -> h[148 + i] = c.code.toByte() }
        h[154] = 0
        h[155] = ' '.code.toByte()
        return h
    }

    private fun putOctal(h: ByteArray, offset: Int, len: Int, value: Long) {
        val s = String.format(java.util.Locale.ROOT, "%0${len - 1}o", value)
        s.forEachIndexed { i, c -> h[offset + i] = c.code.toByte() }
        h[offset + len - 1] = 0
    }
}
