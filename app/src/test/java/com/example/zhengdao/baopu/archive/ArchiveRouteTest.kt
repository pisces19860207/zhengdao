// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单 §2（分流）＋判据⑤（rar/7z 明确拒）＋负例③（rar 当 zip 解）。
//
// 这一层测的是**策略**：`NativeExtractCall` 可注入，假的只负责「把文件写进暂存目录」，
// 同名判定、超限判定、暂存清理走的都是生产代码。真 .so 的那一次真跑在 androidTest
// （`ArchiveDeviceTest`）与交付报告里。
package com.example.zhengdao.baopu.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveRouteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dest(): File = File(tmp.root, "out")

    private fun touch(name: String, text: String = "x"): File =
        File(tmp.root, name).apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    /** 假 native：把 [files] 写进目标目录，并记下被调用的次数（用来断言「压根没走到 native」）。 */
    private fun fakeNative(vararg files: Pair<String, ByteArray>): Pair<NativeExtractCall, () -> Int> {
        var calls = 0
        val call: NativeExtractCall = { _, targetDir ->
            calls++
            for ((name, data) in files) {
                val f = File(targetDir, name)
                f.parentFile?.mkdirs()
                f.writeBytes(data)
            }
            Triple(files.size.toLong(), files.sumOf { it.second.size.toLong() }, "deadbeef")
        }
        return call to { calls }
    }

    private fun neverCalled(): NativeExtractCall = { _, _ -> throw AssertionError("不该走到 Rust 解包") }

    @Test
    fun `rar明确拒_不拿它当zip解`() {
        val rar = touch("资料.rar", "Rar!\u0001\u0000")
        val r = ArchiveExtractor.extract(rar, dest(), native = neverCalled())
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        val msg = (r as ExtractOutcome.Rejected).reason
        assertTrue("要点名 rar：$msg", msg.contains(".rar"))
        assertTrue("要说清没有解码器：$msg", msg.contains("不支持"))
        assertFalse("不许建目标目录", dest().exists())
    }

    @Test
    fun `7z明确拒`() {
        val sevenZ = touch("资料.7z", "7z\u00BC\u00AF\u0027\u001C")
        val r = ArchiveExtractor.extract(sevenZ, dest(), native = neverCalled())
        assertTrue(r is ExtractOutcome.Rejected)
        assertTrue((r as ExtractOutcome.Rejected).reason.contains(".7z"))
    }

    @Test
    fun `zip走Java_不碰native`() {
        val zip = File(tmp.root, "a.zip")
        ZipOutputStream(zip.outputStream(), Charsets.UTF_8).use { zos ->
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("hi".toByteArray())
            zos.closeEntry()
        }
        val r = ArchiveExtractor.extract(zip, dest(), native = neverCalled())
        assertTrue("zip 应当由 Java 解开：$r", r is ExtractOutcome.Ok)
        assertEquals("hi", File(dest(), "a.txt").readText())
    }

    @Test
    fun `tar走native_解开且暂存清干净`() {
        val tar = File(tmp.root, "b.tar")
        RawTar.writeTar(tar, listOf(RawTarEntry("文档.txt", "内容".toByteArray())))
        val (call, calls) = fakeNative("文档.txt" to "内容".toByteArray())

        val r = ArchiveExtractor.extract(tar, dest(), native = call)
        assertTrue("应当成功：$r", r is ExtractOutcome.Ok)
        assertEquals(1, calls())
        // 回归：顶层就是一个文件时，条目数不许数成 0（曾因只按目录递归而数漏）。
        assertEquals(1, (r as ExtractOutcome.Ok).entries)
        assertEquals("内容".toByteArray().size.toLong(), r.bytes)
        assertEquals("内容", File(dest(), "文档.txt").readText())
        val leftovers = dest().listFiles().orEmpty().filter { it.name.startsWith(".zd-extract-") }
        assertTrue("暂存目录必须清掉：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun `tar同名_整包拒且暂存清干净`() {
        val tar = File(tmp.root, "c.tar")
        RawTar.writeTar(tar, listOf(RawTarEntry("已有.txt", "新".toByteArray())))
        val (call, _) = fakeNative("已有.txt" to "新".toByteArray())
        val out = dest().apply { mkdirs() }
        val existing = File(out, "已有.txt").apply { writeText("旧") }

        val r = ArchiveExtractor.extract(tar, out, native = call)
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        assertTrue((r as ExtractOutcome.Rejected).reason.contains("已有同名文件"))
        assertEquals("旧", existing.readText())
        val leftovers = out.listFiles().orEmpty().filter { it.name.startsWith(".zd-extract-") }
        assertTrue("暂存目录必须清掉：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun `tar超2GB_预扫描在落盘前拒_压根不调native`() {
        val tar = File(tmp.root, "big.tar")
        RawTar.writeTar(tar, listOf(RawTarEntry("大.bin", ByteArray(4096) { 1 })))
        val r = ArchiveExtractor.extract(tar, dest(), maxBytes = 1024, native = neverCalled())
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        assertTrue((r as ExtractOutcome.Rejected).reason.contains("超过上限"))
        assertFalse("预扫描应当发生在建目录之前", dest().exists())
    }

    @Test
    fun `tar_gz超2GB_同样预扫描拒`() {
        val tgz = File(tmp.root, "big.tar.gz")
        RawTar.writeTarGz(tgz, listOf(RawTarEntry("大.bin", ByteArray(4096) { 2 })))
        val r = ArchiveExtractor.extract(tgz, dest(), maxBytes = 1024, native = neverCalled())
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
    }

    @Test
    fun `tar_zst超限_落暂存后判_目标目录不脏`() {
        val zst = touch("包.tar.zst", "假的 zstd 帧（只验策略，不解码）")
        val (call, _) = fakeNative("大.bin" to ByteArray(4096) { 3 })
        val r = ArchiveExtractor.extract(zst, dest(), maxBytes = 1024, native = call)
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        val leftovers = dest().listFiles().orEmpty()
        assertTrue("暂存要清掉、目标里不许留东西：${leftovers.toList()}", leftovers.isEmpty())
    }

    @Test
    fun `txz_解不开时说明是缺xz解码器`() {
        val txz = touch("包.tar.xz", "假的 xz")
        val call: NativeExtractCall = { _, _ -> throw IllegalStateException("tar: unexpected EOF") }
        val r = ArchiveExtractor.extract(txz, dest(), native = call)
        assertTrue("应当是 Failed：$r", r is ExtractOutcome.Failed)
        val msg = (r as ExtractOutcome.Failed).reason
        assertTrue("要点名 xz：$msg", msg.contains("xz"))
        assertTrue("要说清 .so 没有解码器：$msg", msg.contains("没有 xz 解码器"))
        val leftovers = dest().listFiles().orEmpty()
        assertTrue("暂存要清掉：${leftovers.toList()}", leftovers.isEmpty())
    }

    @Test
    fun `认不出的扩展名_明确拒`() {
        val weird = touch("东西.xyz")
        val r = ArchiveExtractor.extract(weird, dest(), native = neverCalled())
        assertTrue(r is ExtractOutcome.Rejected)
    }

    @Test
    fun `单个gz不是归档_明确拒`() {
        val gz = touch("日志.gz")
        val r = ArchiveExtractor.extract(gz, dest(), native = neverCalled())
        assertTrue(r is ExtractOutcome.Rejected)
        assertTrue((r as ExtractOutcome.Rejected).reason.contains("tar"))
    }
}
