// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单 §3 与四条负例（zip-slip 放行／覆盖已有／rar 当 zip 解／中文乱码）。
// 每条负例都对应一个「必须红」的用例——**红的方式**也要钉：给的是可读原因，不是抛异常。
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

class ZipExtractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dest(): File = File(tmp.root, "out")

    @Test
    fun `正常包_解开成功`() {
        val zip = File(tmp.root, "好.zip")
        ZipOutputStream(zip.outputStream(), Charsets.UTF_8).use { zos ->
            for (name in listOf("a.txt", "子目录/b.txt")) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(name.toByteArray())
                zos.closeEntry()
            }
        }
        val r = ZipExtractor.extract(zip, dest())
        assertTrue("应当成功：$r", r is ExtractOutcome.Ok)
        assertEquals("a.txt".toByteArray().decodeToString(), File(dest(), "a.txt").readText())
        assertEquals("子目录/b.txt", File(dest(), "子目录/b.txt").readText())
    }

    @Test
    fun `zip-slip_上级目录_整包拒且不落盘`() {
        val zip = File(tmp.root, "slip.zip")
        RawZip.write(zip, listOf(RawZipEntry("../../../etc/x", "pwn".toByteArray())))
        val r = ZipExtractor.extract(zip, dest())
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        val msg = (r as ExtractOutcome.Rejected).reason
        assertTrue("拒绝文案要点名 .. ：$msg", msg.contains(".."))
        assertTrue("拒绝文案要可读：$msg", msg.contains("zip-slip"))
        assertFalse("目标目录不许被建出文件", File(tmp.root, "etc/x").exists())
    }

    @Test
    fun `zip-slip_反斜杠绕过_也拒`() {
        val zip = File(tmp.root, "slip2.zip")
        RawZip.write(zip, listOf(RawZipEntry("..\\..\\x.txt", "pwn".toByteArray())))
        val r = ZipExtractor.extract(zip, dest())
        assertTrue("反斜杠的 .. 同样要拒：$r", r is ExtractOutcome.Rejected)
    }

    @Test
    fun `zip-slip_绝对路径_拒`() {
        val zip = File(tmp.root, "abs.zip")
        RawZip.write(zip, listOf(RawZipEntry("/tmp/evil.txt", "pwn".toByteArray())))
        val r = ZipExtractor.extract(zip, dest())
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        assertTrue((r as ExtractOutcome.Rejected).reason.contains("绝对路径"))
    }

    @Test
    fun `zip-slip_符号链接条目_拒`() {
        val zip = File(tmp.root, "link.zip")
        RawZip.write(
            zip,
            listOf(RawZipEntry("跳板", "../../etc".toByteArray(), unixMode = RawZipEntry.SYMLINK_MODE)),
        )
        val r = ZipExtractor.extract(zip, dest())
        assertTrue("符号链接条目应当整包拒：$r", r is ExtractOutcome.Rejected)
        assertTrue((r as ExtractOutcome.Rejected).reason.contains("符号链接"))
        assertFalse("不许建出链接", File(dest(), "跳板").exists())
    }

    @Test
    fun `超过2GB_按声明值在落盘前拒`() {
        val zip = File(tmp.root, "bomb.zip")
        // 声明 3 GiB、实际只有 4 字节：真实炸弹就是这个形态（声明值撒谎，靠解压时才炸）。
        RawZip.write(
            zip,
            listOf(RawZipEntry("大.bin", ByteArray(4), declaredSize = 3L * 1024 * 1024 * 1024)),
        )
        val r = ZipExtractor.extract(zip, dest())
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        assertTrue((r as ExtractOutcome.Rejected).reason.contains("超过上限"))
        assertFalse("一个字节都不许落盘", dest().exists())
    }

    @Test
    fun `超限按实际字节数判_声明值撒谎也拦住`() {
        val zip = File(tmp.root, "liar.zip")
        // 目录里声明 10 字节、本地头声明 4096（真实数据 4 KB）：预判那一段放行，
        // 靠「边写边数」把 4 KB 拦下来——这正是「目录与本地头打架」的真实形态。
        RawZip.write(
            zip,
            listOf(RawZipEntry("x.bin", ByteArray(4096) { 1 }, declaredSize = 10, localSize = 4096)),
        )
        val r = ZipExtractor.extract(zip, dest(), maxBytes = 1024)
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        assertFalse("已写出的半个文件要回滚掉", File(dest(), "x.bin").exists())
    }

    @Test
    fun `同名不覆盖_原文件一个字节都不变`() {
        val zip = File(tmp.root, "dup.zip")
        ZipOutputStream(zip.outputStream(), Charsets.UTF_8).use { zos ->
            zos.putNextEntry(ZipEntry("已有.txt"))
            zos.write("新内容".toByteArray())
            zos.closeEntry()
        }
        val out = dest().apply { mkdirs() }
        val existing = File(out, "已有.txt").apply { writeText("旧内容") }

        val r = ZipExtractor.extract(zip, out)
        assertTrue("应当拒绝：$r", r is ExtractOutcome.Rejected)
        assertTrue((r as ExtractOutcome.Rejected).reason.contains("已有同名文件"))
        assertEquals("旧内容", existing.readText())
    }

    @Test
    fun `重名条目_拒`() {
        val zip = File(tmp.root, "same.zip")
        RawZip.write(
            zip,
            listOf(RawZipEntry("a.txt", "1".toByteArray()), RawZipEntry("a.txt", "2".toByteArray())),
        )
        val r = ZipExtractor.extract(zip, dest())
        assertTrue("同名条目应当拒：$r", r is ExtractOutcome.Rejected)
    }

    @Test
    fun `不是zip_给可读原因而不是异常`() {
        val fake = File(tmp.root, "假.zip")
        fake.writeText("这根本不是 zip")
        val r = ZipExtractor.extract(fake, dest())
        assertTrue("应当是 Failed：$r", r is ExtractOutcome.Failed)
        assertTrue((r as ExtractOutcome.Failed).reason.contains("不是有效的 zip"))
    }
}
