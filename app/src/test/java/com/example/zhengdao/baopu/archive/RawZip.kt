// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 测试用「手搓 zip」：判据②（zip-slip）与③（>2GB）要的包，用 java.util.zip **造不出来**——
// 它不让我们写 `../x` 这种条目名（会规范化），也不让声明一个「解压后 3GB」的假大小。
// 而这两种正是真实攻击样本的形态，所以测试必须能原样构造出来。
package com.example.zhengdao.baopu.archive

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/** 一条要写进 zip 的成员（大小与 Unix mode 都可伪造）。 */
internal class RawZipEntry(
    val name: String,
    val data: ByteArray = ByteArray(0),
    /** 声明在目录里的解压后大小（默认＝真实长度；伪造它就得到一个「炸弹样本」）。 */
    val declaredSize: Long = data.size.toLong(),
    /** 本地头里的解压后大小（默认＝真实长度；与 [declaredSize] 不一致＝「目录与本地头打架」样本）。 */
    val localSize: Long = data.size.toLong(),
    /** Unix mode 高 16 位（默认 0o100644 普通文件；符号链接用 [SYMLINK_MODE]）。 */
    val unixMode: Int = REGULAR_MODE,
) {
    companion object {
        const val REGULAR_MODE = 0x81A4 // 0o100644
        const val SYMLINK_MODE = 0xA1FF // 0o120777
    }
}

/** 最小 zip 写入器（只支持 STORED，够造样本用）。 */
internal object RawZip {

    fun write(file: File, entries: List<RawZipEntry>) {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        for (e in entries) {
            val nameBytes = e.name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(e.data) }.value
            val offset = out.size()
            // 本地文件头
            u32(out, 0x04034b50)
            u16(out, 20) // version needed
            u16(out, 0x0800) // flags：EFS（UTF-8 名）
            u16(out, 0) // method = STORED
            u16(out, 0) // time
            u16(out, 0x21) // date（1980-01-01）
            u32(out, crc)
            u32(out, e.data.size.toLong())
            u32(out, e.localSize)
            u16(out, nameBytes.size)
            u16(out, 0) // extra len
            out.write(nameBytes)
            out.write(e.data)
            // 中央目录项
            u32(central, 0x02014b50)
            u16(central, 0x031E) // made by：Unix(3) / spec 30
            u16(central, 20)
            u16(central, 0x0800)
            u16(central, 0)
            u16(central, 0)
            u16(central, 0x21)
            u32(central, crc)
            u32(central, e.data.size.toLong())
            u32(central, e.declaredSize)
            u16(central, nameBytes.size)
            u16(central, 0)
            u16(central, 0)
            u16(central, 0)
            u16(central, 0)
            u32(central, e.unixMode.toLong() shl 16)
            u32(central, offset.toLong())
            central.write(nameBytes)
        }
        val cdOffset = out.size()
        out.write(central.toByteArray())
        // 目录结束
        u32(out, 0x06054b50)
        u16(out, 0)
        u16(out, 0)
        u16(out, entries.size)
        u16(out, entries.size)
        u32(out, central.size().toLong())
        u32(out, cdOffset.toLong())
        u16(out, 0)
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }

    private fun u16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun u32(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 24) and 0xFF).toInt())
    }
}
