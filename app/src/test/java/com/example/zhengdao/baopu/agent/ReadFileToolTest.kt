// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.2（ReadFileToolTest：offset/limit 生效、越界必拒）
// + 程枢版 §5 补录「spill 必须配套范围读」（负例演练②的靶心：去掉 offset/limit ⇒ 本文件红）。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReadFileToolTest {

    private fun file(lines: List<String>): File {
        val dir = Files.createTempDirectory("baopu-readfile").toFile()
        val f = File(dir, "a.md")
        f.writeText(lines.joinToString("\n"))
        return f
    }

    @Test
    fun `默认读全文_带行号`() {
        val f = file(listOf("一", "二", "三"))
        val text = (ReadFileTool.read(f, offset = 0, limit = 200) as ToolOutcome.Ok).text
        assertTrue(text.contains("0\t一"))
        assertTrue(text.contains("1\t二"))
        assertTrue(text.contains("2\t三"))
        assertTrue(text.contains("共 3 行"))
    }

    @Test
    fun `offset生效_只从该行起`() {
        val f = file(listOf("一", "二", "三", "四"))
        val text = (ReadFileTool.read(f, offset = 2, limit = 200) as ToolOutcome.Ok).text
        assertFalse("offset=2 ⇒ 前两行不给", text.contains("0\t一") || text.contains("1\t二"))
        assertTrue(text.contains("2\t三"))
        assertTrue(text.contains("3\t四"))
    }

    @Test
    fun `limit生效_只给这么多行`() {
        val f = file((1..10).map { "line$it" })
        val text = (ReadFileTool.read(f, offset = 0, limit = 3) as ToolOutcome.Ok).text
        assertTrue(text.contains("0\tline1"))
        assertTrue(text.contains("2\tline3"))
        assertFalse(text.contains("3\tline4"))
        assertTrue("告诉模型还剩多少、怎么续读", text.contains("还剩 7 行") && text.contains("offset"))
    }

    @Test
    fun `offset加limit_范围读_spill回读的形态`() {
        val f = file((1..100).map { "line$it" })
        val text = (ReadFileTool.read(f, offset = 50, limit = 10) as ToolOutcome.Ok).text
        assertTrue(text.contains("50\tline51"))
        assertTrue(text.contains("59\tline60"))
        assertFalse(text.contains("49\tline50"))
        assertFalse(text.contains("60\tline61"))
    }

    @Test
    fun `offset越界_Failed_并告知总行数`() {
        val f = file(listOf("一", "二"))
        val out = ReadFileTool.read(f, offset = 5, limit = 10)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("一共只有 2 行"))
    }

    @Test
    fun `offset恰好末尾_也给一行_不是空`() {
        val f = file(listOf("一", "二"))
        val text = (ReadFileTool.read(f, offset = 1, limit = 10) as ToolOutcome.Ok).text
        assertTrue(text.contains("1\t二"))
    }

    @Test
    fun `limit超上限_钳到上限`() {
        val f = file((1..1500).map { "l$it" })
        val text = (ReadFileTool.read(f, offset = 0, limit = 99_999) as ToolOutcome.Ok).text
        // limit 传 99999 ⇒ 钳到 MAX_LIMIT=1000 ⇒ 只给第 0–999 行
        assertFalse("第 1000 行起不给", text.contains("1000\tl1001"))
        assertTrue(text.contains("第 0–999 行"))
    }

    @Test
    fun `空文件_明说空`() {
        val f = file(emptyList())
        val out = ReadFileTool.read(f, 0, 100)
        assertTrue((out as ToolOutcome.Ok).text.contains("空文件"))
    }

    @Test
    fun `不是文件_Failed`() {
        val dir = Files.createTempDirectory("baopu-readfile-dir").toFile()
        assertTrue(ReadFileTool.read(dir, 0, 100) is ToolOutcome.Failed)
    }

    @Test
    fun `路径不存在_说「不存在」而不是「不是文件」`() {
        // 2026-10-17 真机实测：把不存在的路径报成「不是一个文件」，模型会以为路径对、只改类型
        val ghost = File(Files.createTempDirectory("baopu-readfile-ghost").toFile(), "根本没有.md")
        val out = ReadFileTool.read(ghost, 0, 100)
        assertTrue(out is ToolOutcome.Failed)
        val err = (out as ToolOutcome.Failed).error
        assertTrue("必须明说「不存在」：$err", err.contains("不存在"))
        assertTrue("不许混成「不是文件」：$err", !err.contains("不是一个文件"))
        assertTrue("要指路到 list_dir：$err", err.contains("list_dir"))
    }

    @Test
    fun `超大文件_拒绝整读_说清大小`() {
        val dir = Files.createTempDirectory("baopu-readfile-big").toFile()
        val f = File(dir, "big.bin")
        // 造一个超过上限的文件（只写头部 + 扩容，不真写 2MB 内容）
        f.writeBytes(ByteArray(1024))
        java.io.RandomAccessFile(f, "rw").use { it.setLength(ReadFileTool.MAX_FILE_BYTES + 1) }
        val out = ReadFileTool.read(f, 0, 100)
        assertTrue(out is ToolOutcome.Failed)
        val err = (out as ToolOutcome.Failed).error
        assertTrue(err.contains("太大"))
        assertTrue("提示改用 list_dir + 分段", err.contains("list_dir") && err.contains("offset"))
    }
}
