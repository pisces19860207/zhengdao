// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百六十九批 · **真机证据**：四种格式各真跑一次（zip / tar / tar.gz / tar.zst），
// 外加三条安全线在真机上的表现（zip-slip／超 2GB／同名不覆盖）与 .tar.xz 的诚实失败。
//
// 为什么 JVM 全绿了还要上机（沿用 223/232 的理由）：zip 的 `java.util.zip` 在真机上是
// libcore 的实现，EFS 位是不是真置、`ZipInputStream` 怎么读真 zip，都只有在 Android 上
// 才算跑过；tar 家族更是**必须**上机——`nativeExtract` 背后是真 `.so`，JVM 里根本没有。
//
// 证据同时打 logcat（tag `ZD269`）并写 `cache/zd269-evidence.txt`
// （本机 PGT-AN10 的 logcat 是加密包起来的，取证据用文件）：
//
//   adb exec-out run-as com.example.zhengdao cat cache/zd269-evidence.txt
package com.example.zhengdao.baopu.archive

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ArchiveDeviceTest {

    private val log = StringBuilder()
    private lateinit var root: File
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = ctx.cacheDir
        if (!evidenceReset) {
            File(ctx.cacheDir, "zd269-evidence.txt").delete()
            evidenceReset = true
        }
        root = File(ctx.cacheDir, "zd269")
        root.deleteRecursively()
        root.mkdirs()
    }

    @After
    fun tearDown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "zd269-evidence.txt")
        // 追加：JUnit 每个用例都是新实例，只覆盖会只剩最后一条（第一次跑就踩了这个坑）。
        file.appendText(log.toString())
        Log.i(TAG, "证据已写 ${file.absolutePath}")
    }

    private fun note(s: String) {
        log.append(s).append('\n')
        Log.i(TAG, s)
    }

    // ────────────────────────── ① zip 真跑 ──────────────────────────

    @Test
    fun zip_中文名打包再解开_真跑() {
        val src = File(root, "src").apply { mkdirs() }
        File(src, "会议纪要-2026.txt").writeText("第一条\n第二条\n")
        File(src, "照片.jpg").writeBytes(ByteArray(2048) { (it % 251).toByte() })
        val zip = File(root, "中文包.zip")

        val packed = ZipPacker.pack(zip, listOf(src))
        assertTrue("打包应当成功：$packed", packed is PackResult.Ok)
        // 留一份在 cache 根：报告里的「Windows 资源管理器双击打开名字正确」用它——
        // `adb pull` 出来在 Windows 上解一次，才算真的验过判据①（同一位 EFS 标志位）。
        zip.copyTo(File(cacheDir, "zd269-中文包.zip"), overwrite = true)
        val flags = localHeaderFlags(zip)
        note("① zip 打包：条目=${(packed as PackResult.Ok).entries} 字节=${packed.bytes} 本地头标志位=${flags.joinToString { "0x" + Integer.toHexString(it) }}")
        assertTrue("每个本地头都要置 EFS(0x0800)", flags.isNotEmpty() && flags.all { it and 0x0800 != 0 })

        val out = File(root, "out-zip")
        val r = ArchiveExtractor.extract(zip, out)
        assertTrue("解包应当成功：$r", r is ExtractOutcome.Ok)
        note("① zip 解包：$r")
        assertEquals("第一条\n第二条\n", File(out, "src/会议纪要-2026.txt").readText())
        assertEquals(2048, File(out, "src/照片.jpg").length())
        note("① 解回来的名字：${out.walkTopDown().filter { it.isFile }.map { it.name }.sorted().joinToString("、")}")
    }

    // ────────────────────────── ② tar 真跑（Rust） ──────────────────────────

    @Test
    fun tar_真跑_Rust解包() {
        val tar = File(root, "资料.tar")
        writeTar(tar, listOf("说明.txt" to "tar 里的中文内容".toByteArray()))
        val out = File(root, "out-tar")
        val r = ArchiveExtractor.extract(tar, out)
        note("② tar（Rust nativeExtract）：$r")
        assertTrue("应当成功：$r", r is ExtractOutcome.Ok)
        assertEquals("tar 里的中文内容", File(out, "说明.txt").readText())
    }

    @Test
    fun tgz_真跑_Rust解包() {
        val tar = File(root, "资料.tar")
        writeTar(tar, listOf("说明.txt" to "tgz 里的中文内容".toByteArray()))
        val tgz = File(root, "资料.tar.gz")
        GZIPOutputStream(tgz.outputStream()).use { gz -> gz.write(tar.readBytes()) }

        val out = File(root, "out-tgz")
        val r = ArchiveExtractor.extract(tgz, out)
        note("③ tar.gz（Rust nativeExtract）：$r")
        assertTrue("应当成功：$r", r is ExtractOutcome.Ok)
        assertEquals("tgz 里的中文内容", File(out, "说明.txt").readText())
    }

    @Test
    fun tzst_真跑_Rust解包() {
        val tar = File(root, "资料2.tar")
        writeTar(tar, listOf("说明.txt" to "zst 里的中文内容".toByteArray()))
        val zst = File(root, "资料.tar.zst")
        zst.writeBytes(zstdRawFrame(tar.readBytes()))

        val out = File(root, "out-zst")
        val r = ArchiveExtractor.extract(zst, out)
        note("④ tar.zst（Rust nativeExtract，帧为 zstd raw-block）：$r")
        assertTrue("应当成功：$r", r is ExtractOutcome.Ok)
        assertEquals("zst 里的中文内容", File(out, "说明.txt").readText())
    }

    @Test
    fun txz_诚实失败_说清缺xz解码器() {
        val fake = File(root, "资料.tar.xz")
        // 真 xz 帧造不出来（266 批删了 tukaani:xz），这里只验「走 native 后失败时的说法」。
        fake.writeBytes(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00, 0x00, 0x04))
        val r = ArchiveExtractor.extract(fake, File(root, "out-xz"))
        note("⑤ tar.xz：$r")
        assertTrue("应当是 Failed：$r", r is ExtractOutcome.Failed)
        assertTrue((r as ExtractOutcome.Failed).reason.contains("xz"))
    }

    // ────────────────────────── ③ 安全线在真机上 ──────────────────────────

    @Test
    fun zip_slip_真机拒绝且不落盘() {
        val zip = File(root, "slip.zip")
        rawZip(zip, "../../../etc/x", "pwn".toByteArray(), declaredSize = 3, symlink = false)
        val out = File(root, "out-slip")
        val r = ArchiveExtractor.extract(zip, out)
        note("⑥ zip-slip（../../../etc/x）：$r")
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        assertFalse(File(root, "etc/x").exists())
    }

    @Test
    fun zip_符号链接条目_真机拒绝() {
        val zip = File(root, "link.zip")
        rawZip(zip, "跳板", "../../etc".toByteArray(), declaredSize = 8, symlink = true)
        val r = ArchiveExtractor.extract(zip, File(root, "out-link"))
        note("⑦ 符号链接条目：$r")
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
    }

    @Test
    fun zip_声明超2GB_真机拒绝() {
        val zip = File(root, "bomb.zip")
        rawZip(zip, "大.bin", ByteArray(4), declaredSize = 3L * 1024 * 1024 * 1024, symlink = false)
        val out = File(root, "out-bomb")
        val r = ArchiveExtractor.extract(zip, out)
        note("⑧ 声明 3GB：$r")
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        assertFalse(out.exists())
    }

    @Test
    fun tar_同名不覆盖_真机() {
        val tar = File(root, "dup.tar")
        writeTar(tar, listOf("已有.txt" to "新内容".toByteArray()))
        val out = File(root, "out-dup").apply { mkdirs() }
        val existing = File(out, "已有.txt").apply { writeText("旧内容") }

        val r = ArchiveExtractor.extract(tar, out)
        note("⑨ tar 同名：$r")
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        assertEquals("旧内容", existing.readText())
        val leftovers = out.listFiles().orEmpty().filter { it.name.startsWith(".zd-extract-") }
        assertTrue("暂存要清干净：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun rar_真机明确拒() {
        val rar = File(root, "东西.rar").apply { writeText("Rar!\u0001\u0000") }
        val r = ArchiveExtractor.extract(rar, File(root, "out-rar"))
        note("⑩ rar：$r")
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
    }

    // ────────────────────────── 夹具 ──────────────────────────

    /** 读所有本地文件头的通用标志位（第 6–7 字节）。 */
    private fun localHeaderFlags(zip: File): List<Int> {
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
            flags.add((b[i + 6].toInt() and 0xFF) or ((b[i + 7].toInt() and 0xFF) shl 8))
            val nameLen = (b[i + 26].toInt() and 0xFF) or ((b[i + 27].toInt() and 0xFF) shl 8)
            val extraLen = (b[i + 28].toInt() and 0xFF) or ((b[i + 29].toInt() and 0xFF) shl 8)
            val compSize = (0..3).fold(0L) { acc, k -> acc or ((b[i + 18 + k].toLong() and 0xFF) shl (8 * k)) }
            i += 30 + nameLen + extraLen + compSize.toInt()
        }
        return flags
    }

    /** 手搓 tar（只写普通文件）：`.tar` / `.tar.gz` / `.tar.zst` 三种夹具都从它来。 */
    private fun writeTar(file: File, entries: List<Pair<String, ByteArray>>) {
        val out = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val h = ByteArray(512)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            System.arraycopy(nameBytes, 0, h, 0, minOf(nameBytes.size, 100))
            putOctal(h, 100, 8, 0b110_100_100L)
            putOctal(h, 124, 12, data.size.toLong())
            h[156] = '0'.code.toByte()
            "ustar".forEachIndexed { i, c -> h[257 + i] = c.code.toByte() }
            h[263] = '0'.code.toByte()
            h[264] = '0'.code.toByte()
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            var sum = 0L
            for (byte in h) sum += (byte.toInt() and 0xFF)
            val oct = String.format(java.util.Locale.ROOT, "%06o", sum)
            oct.forEachIndexed { i, c -> h[148 + i] = c.code.toByte() }
            h[154] = 0
            h[155] = ' '.code.toByte()
            out.write(h)
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024))
        file.writeBytes(out.toByteArray())
    }

    private fun putOctal(h: ByteArray, offset: Int, len: Int, value: Long) {
        val s = String.format(java.util.Locale.ROOT, "%0${len - 1}o", value)
        s.forEachIndexed { i, c -> h[offset + i] = c.code.toByte() }
        h[offset + len - 1] = 0
    }

    /**
     * 造一个 zstd 帧（**raw block**：不压缩，但帧结构合法）。
     *
     * 为什么不用真压缩：266 批把 `tukaani:xz` 删了、`zstd-jni` 也早随终端搬走，仓里没有
     * zstd 编码器；而这一条要验的是「.tar.zst 会不会被正确路由到 Rust 并用真 .so 解开」，
     * 不是压缩率。raw block 是 zstd 规范里的一等公民（RFC 8878 §3.1.1.2.1），
     * 真解码器必须认——所以它验的是真链路。
     */
    private fun zstdRawFrame(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())) // magic
        out.write(0x00) // FHD：无内容大小、无校验、无字典
        out.write(0x28) // window descriptor：exponent 5 ⇒ 32 KB
        var off = 0
        val maxBlock = 32 * 1024
        if (data.isEmpty()) {
            out.write(byteArrayOf(0x01, 0x00, 0x00))
            return out.toByteArray()
        }
        while (off < data.size) {
            val n = minOf(maxBlock, data.size - off)
            val last = if (off + n >= data.size) 1 else 0
            val header = (n shl 3) or last // 块类型 0（raw）
            out.write(header and 0xFF)
            out.write((header ushr 8) and 0xFF)
            out.write((header ushr 16) and 0xFF)
            out.write(data, off, n)
            off += n
        }
        return out.toByteArray()
    }

    /** 手搓 zip（只写 STORED 单条目）：`../` 与假大小用 java.util.zip 造不出来。 */
    private fun rawZip(file: File, name: String, data: ByteArray, declaredSize: Long, symlink: Boolean) {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = java.util.zip.CRC32().apply { update(data) }.value
        u32(out, 0x04034b50)
        u16(out, 20)
        u16(out, 0x0800)
        u16(out, 0)
        u16(out, 0)
        u16(out, 0x21)
        u32(out, crc)
        u32(out, data.size.toLong())
        u32(out, declaredSize)
        u16(out, nameBytes.size)
        u16(out, 0)
        out.write(nameBytes)
        out.write(data)

        u32(central, 0x02014b50)
        u16(central, 0x031E)
        u16(central, 20)
        u16(central, 0x0800)
        u16(central, 0)
        u16(central, 0)
        u16(central, 0x21)
        u32(central, crc)
        u32(central, data.size.toLong())
        u32(central, declaredSize)
        u16(central, nameBytes.size)
        u16(central, 0)
        u16(central, 0)
        u16(central, 0)
        u16(central, 0)
        val mode = if (symlink) 0xA1FF else 0x81A4
        u32(central, mode.toLong() shl 16)
        u32(central, 0)
        central.write(nameBytes)

        val cdOffset = out.size()
        out.write(central.toByteArray())
        u32(out, 0x06054b50)
        u16(out, 0)
        u16(out, 0)
        u16(out, 1)
        u16(out, 1)
        u32(out, central.size().toLong())
        u32(out, cdOffset.toLong())
        u16(out, 0)
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

    private companion object {
        const val TAG = "ZD269"
        /** 证据文件只在本次运行的第一条用例前清一次（同一个 instrumentation 进程内共享）。 */
        var evidenceReset = false
    }
}
