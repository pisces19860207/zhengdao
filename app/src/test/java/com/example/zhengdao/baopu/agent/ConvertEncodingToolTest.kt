// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/**
 * ConvertEncodingTool 单测（工具补强第二刀）。
 *
 * 钉住的判据：① GBK 落盘转 UTF-8、内容一字不变、**原文先备份**；
 * ② 幂等：本来就是 UTF-8（含 BOM）就明说「没动」，字节不变、不产生备份；
 * ③ 二进制拒绕；④ 越界 Rejected；⑤ `.baopu-bak` 里的不许改；⑥ CONFIRM；⑦ 报行数。
 */
class ConvertEncodingToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun setup() {
        root = tmp.newFolder("workspace")
    }

    private fun putBytes(rel: String, bytes: ByteArray): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    private fun convert(file: File) = ConvertEncodingTool.convert(root, file)

    private fun text(r: ToolOutcome): String = when (r) {
        is ToolOutcome.Ok -> r.text
        is ToolOutcome.Failed -> r.error
        is ToolOutcome.Rejected -> r.reason
    }

    private val gb18030: Charset = Charset.forName("GB18030")

    // ── ① 真转一次 ──────────────────────────────────────────────────────

    @Test
    fun `GBK转UTF8_内容一字不变且原字节有备份`() {
        setup()
        val body = "第一章 起步\n正文一行\n"
        val gbkBytes = body.toByteArray(gb18030)
        val f = putBytes("资料库/原始/老书.txt", gbkBytes)

        val t = text(convert(f))
        assertTrue("应报已转：$t", t.contains("已转"))
        assertTrue("要报行数（标题行＋正文行＝2）：$t", t.contains("2 行"))
        // 落盘后按 UTF-8 读，内容一字不变
        val after = f.readBytes().toString(Charsets.UTF_8)
        assertEquals(body, after)
        // 原字节备份在 .baopu-bak/ 下
        val backups = File(root, ConvertEncodingTool.BACKUP_DIR_NAME).listFiles().orEmpty()
        assertEquals(1, backups.size)
        assertTrue("备份应是原始 GBK 字节", backups[0].readBytes().contentEquals(gbkBytes))
        assertTrue("备份名要带时间戳：${backups[0].name}", backups[0].name.contains(".bak"))
    }

    @Test
    fun `UTF16LE带BOM也会转成UTF8`() {
        setup()
        val body = "第二章 出门\n"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body.toByteArray(Charsets.UTF_16LE)
        val f = putBytes("u16.txt", bytes)
        val t = text(convert(f))
        assertTrue(t, t.contains("已转"))
        assertTrue(t, t.contains("UTF-16LE"))
        assertEquals(body, f.readBytes().toString(Charsets.UTF_8))
    }

    // ── ② 幂等 ──────────────────────────────────────────────────────────

    @Test
    fun `本来就是UTF8_明说没动且不产生备份`() {
        setup()
        val body = "第一章 起步\n正文\n"
        val f = putBytes("a.txt", body.toByteArray(Charsets.UTF_8))
        val t = text(convert(f))
        assertTrue("要明说没动：$t", t.contains("没动"))
        assertEquals(body, f.readBytes().toString(Charsets.UTF_8))
        assertTrue(
            "不该产生备份",
            File(root, ConvertEncodingTool.BACKUP_DIR_NAME).listFiles().orEmpty().isEmpty(),
        )
    }

    @Test
    fun `UTF8带BOM_也算没动`() {
        setup()
        val body = "第一章\n"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.toByteArray(Charsets.UTF_8)
        val f = putBytes("bom.txt", bytes)
        val t = text(convert(f))
        assertTrue("要明说没动：$t", t.contains("没动"))
        assertTrue(t, f.readBytes().contentEquals(bytes))
    }

    // ── ③ 二进制 ────────────────────────────────────────────────────────

    @Test
    fun `二进制文件拒绕`() {
        setup()
        val f = putBytes("a.bin", byteArrayOf(0, 1, 2, 3, 0))
        val r = convert(f)
        assertTrue("必须 Rejected：$r", r is ToolOutcome.Rejected)
        assertTrue(text(r).contains("二进制"))
        assertTrue(f.readBytes().contentEquals(byteArrayOf(0, 1, 2, 3, 0)))
    }

    // ── ④ 越界 / 禁区 ───────────────────────────────────────────────────

    @Test
    fun `工作区外被拒`() {
        setup()
        val outside = File(tmp.root, "外面.txt").apply { writeBytes("x".toByteArray(gb18030)) }
        assertTrue(convert(outside) is ToolOutcome.Rejected)
    }

    @Test
    fun `备份区里的文件不许转`() {
        setup()
        val f = putBytes(".baopu-bak/老书.txt.20261017.bak", "x".toByteArray(gb18030))
        val t = text(convert(f))
        assertTrue(t, t.contains("备份区"))
        assertTrue("原文件不该被改", f.readBytes().contentEquals("x".toByteArray(gb18030)))
    }

    @Test
    fun `不存在要 Failed 并指路`() {
        setup()
        val t = text(convert(File(root, "没有.txt")))
        assertTrue(t, t.contains("不存在"))
        assertTrue(t, t.contains("find_files"))
    }

    @Test
    fun `目录要 Failed`() {
        setup()
        val dir = File(root, "资料库").apply { mkdirs() }
        val t = text(convert(dir))
        assertTrue(t, t.contains("不是一个文件"))
    }

    // ── ⑤ spec ──────────────────────────────────────────────────────────

    @Test
    fun `spec_要确认且只path必填`() {
        val spec = ConvertEncodingTool.spec { tmp.root }
        assertEquals("convert_encoding", spec.name)
        assertEquals(ToolPermission.CONFIRM, spec.permission)
        val required = spec.inputSchema.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("path", required.getString(0))
    }
}
