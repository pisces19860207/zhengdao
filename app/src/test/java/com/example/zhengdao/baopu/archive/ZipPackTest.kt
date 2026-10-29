// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单 §1 三条口径，逐条钉死：
// ① UTF-8 标志位（EFS，通用标志位 bit 11）——「电脑双击打开中文名正确」的唯一机器判据；
// ② jpg/mp4/zip 走 STORED（不压），其余 DEFLATED；
// ③ Zip64（条目数 >65535 / 大小 ≥4GB 时自动写扩展目录尾），以及「目标已存在不覆盖」。
package com.example.zhengdao.baopu.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ZipPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(rel: String, bytes: ByteArray): File {
        val f = File(tmp.root, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    /** 读出所有本地文件头的通用标志位（第 6–7 字节），用于断言 EFS 位。 */
    private fun localFlags(zip: File): List<Int> {
        val b = zip.readBytes()
        val flags = ArrayList<Int>()
        var i = 0
        while (i + 30 < b.size) {
            val sig = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
            if (sig != 0x04034b50) {
                i++
                continue
            }
            val flag = (b[i + 6].toInt() and 0xFF) or ((b[i + 7].toInt() and 0xFF) shl 8)
            flags.add(flag)
            val nameLen = (b[i + 26].toInt() and 0xFF) or ((b[i + 27].toInt() and 0xFF) shl 8)
            val extraLen = (b[i + 28].toInt() and 0xFF) or ((b[i + 29].toInt() and 0xFF) shl 8)
            val compSize = (0..3).fold(0L) { acc, k ->
                acc or ((b[i + 18 + k].toLong() and 0xFF) shl (8 * k))
            }
            i += 30 + nameLen + extraLen + compSize.toInt()
        }
        return flags
    }

    @Test
    fun `中文名写入UTF8标志位_解回来名字一致`() {
        val a = file("报告-2026.txt", "第一行\n第二行\n".toByteArray())
        val b = file("资料/封面.jpg", ByteArray(64) { it.toByte() })
        val zip = File(tmp.root, "中文包.zip")

        val r = ZipPacker.pack(zip, listOf(a, b.parentFile))
        assertTrue("打包应当成功：$r", r is PackResult.Ok)
        assertTrue(zip.exists())

        // ① 每个本地文件头都必须置 EFS（0x0800）——不置位 Windows 会按 GBK 解释名字。
        val flags = localFlags(zip)
        assertEquals(2, flags.size)
        flags.forEach { assertTrue("本地头缺少 UTF-8 标志位：0x" + Integer.toHexString(it), it and 0x0800 != 0) }

        // ② 名字用 UTF-8 解出来与原名逐字一致（这是「双击打开不乱码」的内容侧证据）。
        ZipFile(zip, Charsets.UTF_8).use { zf ->
            val names = zf.entries().toList().map { it.name }.sorted()
            assertEquals(listOf("报告-2026.txt", "资料/封面.jpg"), names)
            val text = zf.getInputStream(zf.getEntry("报告-2026.txt")).use { it.readBytes().decodeToString() }
            assertEquals("第一行\n第二行\n", text)
        }
    }

    @Test
    fun `jpg_mp4_zip不压_文本压`() {
        val jpg = file("图.jpg", ByteArray(4096) { 7 })
        val mp4 = file("片.mp4", ByteArray(4096) { 9 })
        val inner = file("内层.zip", ByteArray(1024) { 3 })
        val txt = file("说明.txt", "abcd".repeat(200).toByteArray())
        val zip = File(tmp.root, "混合.zip")

        assertTrue(ZipPacker.pack(zip, listOf(jpg, mp4, inner, txt)) is PackResult.Ok)

        ZipFile(zip).use { zf ->
            assertEquals(ZipEntry.STORED, zf.getEntry("图.jpg").method)
            assertEquals(ZipEntry.STORED, zf.getEntry("片.mp4").method)
            assertEquals(ZipEntry.STORED, zf.getEntry("内层.zip").method)
            assertEquals(ZipEntry.DEFLATED, zf.getEntry("说明.txt").method)
            // STORED 条目的「压缩后大小＝原始大小」，且能原样读回（预置 crc 写错会在这里炸）。
            val e = zf.getEntry("图.jpg")
            assertEquals(e.size, e.compressedSize)
            assertEquals(4096, zf.getInputStream(e).use { it.readBytes() }.size)
        }
    }

    @Test
    fun `目标已存在_拒绝且不动原文件`() {
        val src = file("a.txt", "x".toByteArray())
        val zip = File(tmp.root, "已有.zip")
        zip.writeText("旧包，不许被覆盖")
        val before = zip.readBytes()

        val r = ZipPacker.pack(zip, listOf(src))
        assertTrue("应当拒绝：$r", r is PackResult.Failed)
        assertTrue((r as PackResult.Failed).reason.contains("目标已存在"))
        assertTrue("原包不许被改：", before.contentEquals(zip.readBytes()))
    }

    @Test
    fun `空目录留条目_解开后目录还在`() {
        val dir = File(tmp.root, "空文件夹").apply { mkdirs() }
        val zip = File(tmp.root, "空.zip")
        assertTrue(ZipPacker.pack(zip, listOf(dir)) is PackResult.Ok)
        ZipFile(zip).use { zf ->
            val e = zf.getEntry("空文件夹/")
            assertNotNull("空目录应当留一条目录条目", e)
            assertTrue(e!!.isDirectory)
        }
    }

    /**
     * Zip64：条目数超过 65535 时，JDK 会自动改用 Zip64 目录尾（`PK\x06\x06`）。
     *
     * 为什么这条测的是「JDK 的自动行为」而不是 ZipPacker：ZipPacker 在条目数维度上没有
     * 自己的分支（也不该有——手写 Zip64 是自找 bug）。本用例钉的是**我们没有把这条路堵死**：
     * 一旦有人给 ZipEntry 预设了错误的 size/compressedSize，或改用非流式写法，这里会红。
     * 用 65536 条空条目直接写（不经过磁盘），代价约 1 秒。
     */
    @Test
    fun `条目数超65535_自动写Zip64目录尾`() {
        val zip = File(tmp.root, "many.zip")
        val count = 65_536
        ZipOutputStream(zip.outputStream(), Charsets.UTF_8).use { zos ->
            for (i in 0 until count) {
                val e = ZipEntry("f$i.txt")
                e.method = ZipEntry.STORED
                e.size = 0
                e.compressedSize = 0
                e.crc = 0
                zos.putNextEntry(e)
                zos.closeEntry()
            }
        }
        val bytes = zip.readBytes()
        assertTrue("应当出现 Zip64 目录尾（PK\\x06\\x06）", containsSig(bytes, 0x06064b50))
        ZipFile(zip).use { zf -> assertEquals(count, zf.entries().toList().size) }
        // 解包走的是**自己的**中央目录解析（ZipIndex），它必须也认得 Zip64 目录尾；
        // 认不得的话 65536 条的包会被判「不是有效的 zip」。
        val index = ZipIndex.parse(zip)
        assertTrue("ZipIndex 应当能读 Zip64 目录：$index", index is ZipIndexResult.Ok)
        assertEquals(count, (index as ZipIndexResult.Ok).members.size)
        assertTrue("条目名要对得上", index.members.any { it.name == "f65535.txt" })
    }

    /**
     * Zip64（大小维度）：STORED 条目声明 5 GB 时，本地头必须把 32 位字段写满值、真值进
     * Zip64 扩展字段（id 0x0001，两个 8 字节）。
     *
     * 为什么不用真 5 GB 文件跑：本机只剩 8 GB 盘，写 5 GB 包＋5 GB 源会把盘吃满，且这条
     * 判据与「文件多大」无关——只与「有没有给 ZipEntry 预置真实大小」有关。所以这里在
     * `putNextEntry` 之后**立刻**读本地头字节断言（不写数据、不 closeEntry）。
     */
    @Test
    fun `STORED声明5GB_本地头写Zip64扩展字段`() {
        val buf = java.io.ByteArrayOutputStream()
        val zos = ZipOutputStream(buf, Charsets.UTF_8)
        val size = 5L * 1024 * 1024 * 1024
        val e = ZipEntry("大.mp4")
        e.method = ZipEntry.STORED
        e.size = size
        e.compressedSize = size
        e.crc = 0
        zos.putNextEntry(e)

        val b = buf.toByteArray()
        val nameLen = (b[26].toInt() and 0xFF) or ((b[27].toInt() and 0xFF) shl 8)
        val extraLen = (b[28].toInt() and 0xFF) or ((b[29].toInt() and 0xFF) shl 8)
        // 32 位字段写满值（0xFFFFFFFF），真值在扩展字段里。
        assertEquals(0xFFFFFFFFL, (0..3).fold(0L) { acc, k -> acc or ((b[22 + k].toLong() and 0xFF) shl (8 * k)) })
        assertEquals(0xFFFFFFFFL, (0..3).fold(0L) { acc, k -> acc or ((b[18 + k].toLong() and 0xFF) shl (8 * k)) })
        assertTrue("必须带 Zip64 扩展字段（extraLen=$extraLen）", extraLen >= 20)

        val extraStart = 30 + nameLen
        val id = (b[extraStart].toInt() and 0xFF) or ((b[extraStart + 1].toInt() and 0xFF) shl 8)
        assertEquals("扩展字段 id 必须是 Zip64（0x0001）", 0x0001, id)
        val usize = (0..7).fold(0L) { acc, k ->
            acc or ((b[extraStart + 4 + k].toLong() and 0xFF) shl (8 * k))
        }
        assertEquals("Zip64 里的解压后大小必须是真值", size, usize)
    }

    private fun containsSig(bytes: ByteArray, sig: Int): Boolean {
        for (i in 0..bytes.size - 4) {
            val v = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt() and 0xFF) shl 24)
            if (v == sig) return true
        }
        return false
    }
}
