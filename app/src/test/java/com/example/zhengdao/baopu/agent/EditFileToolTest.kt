// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * EditFileTool 单测（工具补强刀）。
 *
 * 钉住的判据：① 越界即拒（Rejected）；② 只改目标行，别处一字不动；
 * ③ 行号越界**拒绝**而不是「尽力而为」；④ 写前先备份，备份失败不写；
 * ⑤ 不能改 `.baopu-bak/`；⑥ 无删除能力（Schema 里没有 delete 参数）。
 */
class EditFileToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun setup() {
        root = tmp.newFolder("workspace")
    }

    private fun put(rel: String, text: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    private fun edit(
        path: String,
        op: String,
        from: Int? = null,
        to: Int? = null,
        content: String = "新内容",
    ) = EditFileTool.edit(root, path, op, from, to, content, stamp = "20261017-170000")

    // ── ① 越界即拒 ──────────────────────────────────────────────────────

    @Test
    fun `越界_相对路径逃逸被拒`() {
        setup()
        put("a.txt", "一\n二\n")
        val r = edit("../outside.txt", EditFileTool.OP_REPLACE, 1, 1)
        assertTrue("越界必须 Rejected：$r", r is ToolOutcome.Rejected)
    }

    @Test
    fun `越界_绝对路径在工作区外被拒`() {
        setup()
        val outside = File(tmp.root, "outside.txt")
        outside.writeText("x\n", Charsets.UTF_8)
        val r = edit(outside.absolutePath, EditFileTool.OP_REPLACE, 1, 1)
        assertTrue(r is ToolOutcome.Rejected)
    }

    @Test
    fun `绝对路径在工作区内_能改且备份名不带冒号`() {
        // 2026-10-17 进化门禁抓到：path 参数在管线里会被改写成**绝对路径**，备份名原来
        // 直接拼 rel ⇒ 文件名里带 `C:` 的冒号 ⇒ Windows 上 copyTo 抛异常 ⇒ 工具回一句
        // 「备份旧版本失败，已放弃」（模型只能承认「我也不知道为什么」）。
        setup()
        val f = put("b.md", "第一行\n第二行是错的\n第三行\n")
        val r = EditFileTool.edit(
            root, f.absolutePath, EditFileTool.OP_REPLACE, 2, 2, "已修正", stamp = "s1",
        )
        assertTrue("绝对路径也要能改：$r", r is ToolOutcome.Ok)
        assertTrue("改动要落盘", f.readText(Charsets.UTF_8).contains("已修正"))
        val baks = File(root, EditFileTool.BACKUP_DIR_NAME).listFiles().orEmpty()
        assertEquals("必须留下一个备份", 1, baks.size)
        assertFalse("备份名不许含冒号：${baks[0].name}", baks[0].name.contains(":"))
    }

    // ── 编码（2026-10-17：以前硬读 UTF-8，GBK 老书会被改成乱码）────────────────

    @Test
    fun `改GBK老书_内容正确且按原编码写回`() {
        setup()
        val f = File(root, "老书.txt")
        f.writeBytes("第一章\n主人公名叫王大锤，家住城西。\n".toByteArray(charset("GBK")))
        val r = EditFileTool.edit(root, "老书.txt", EditFileTool.OP_REPLACE, 2, 2, "主人公改名叫王二锤。")
        assertTrue("GBK 文件也要能按行改：$r", r is ToolOutcome.Ok)
        val bytes = f.readBytes()
        assertTrue("内容要按 GBK 解得出新句子", String(bytes, charset("GBK")).contains("王二锤"))
        assertEquals(
            "不许偷偷把整本转成 UTF-8",
            FileInfoTool.Encodings.GBK,
            FileInfoTool.guessEncoding(bytes, FileInfoTool.looksBinary(bytes)),
        )
    }

    @Test
    fun `改UTF16文件_保留BOM与原编码`() {
        setup()
        val f = File(root, "wide.txt")
        f.writeBytes(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "第一行\n第二行\n".toByteArray(charset("UTF-16LE")),
        )
        val r = EditFileTool.edit(root, "wide.txt", EditFileTool.OP_REPLACE, 2, 2, "第二行改过了")
        assertTrue("UTF-16 文件也要能按行改：$r", r is ToolOutcome.Ok)
        val bytes = f.readBytes()
        assertTrue(
            "BOM 必须仍在（丢了 BOM 等于把文件悄悄换成另一种编码）",
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte(),
        )
        val text = String(bytes.copyOfRange(2, bytes.size), charset("UTF-16LE"))
        assertTrue("内容要改对：$text", text.contains("第二行改过了") && text.contains("第一行"))
    }

    // ── ② 只改目标行 ────────────────────────────────────────────────────

    @Test
    fun `替换中段_只改指定行其余一字不动`() {
        setup()
        val f = put("b.md", "第一行\n第二行\n第三行\n第四行\n")
        val r = EditFileTool.edit(
            root, "b.md", EditFileTool.OP_REPLACE, 2, 3, "替换后的第二行", stamp = "s1",
        )
        assertTrue("应成功：$r", r is ToolOutcome.Ok)
        assertEquals("第一行\n替换后的第二行\n第四行\n", f.readText(Charsets.UTF_8))
    }

    @Test
    fun `省略_toLine_等价于只改一行`() {
        setup()
        val f = put("c.md", "a\nb\nc\n")
        val r = EditFileTool.edit(root, "c.md", EditFileTool.OP_REPLACE, 2, null, "B", stamp = "s2")
        assertTrue(r is ToolOutcome.Ok)
        assertEquals("a\nB\nc\n", f.readText(Charsets.UTF_8))
    }

    @Test
    fun `插入_锚点0插到最前`() {
        setup()
        val f = put("d.md", "正文1\n正文2\n")
        val r = EditFileTool.edit(
            root, "d.md", EditFileTool.OP_INSERT_AFTER, 0, null, "# 标题", stamp = "s3",
        )
        assertTrue(r is ToolOutcome.Ok)
        assertEquals("# 标题\n正文1\n正文2\n", f.readText(Charsets.UTF_8))
    }

    @Test
    fun `插入_在第二行后插入`() {
        setup()
        val f = put("e.md", "1\n2\n3\n")
        val r = EditFileTool.edit(
            root, "e.md", EditFileTool.OP_INSERT_AFTER, 2, null, "2.5", stamp = "s4",
        )
        assertTrue(r is ToolOutcome.Ok)
        assertEquals("1\n2\n2.5\n3\n", f.readText(Charsets.UTF_8))
    }

    @Test
    fun `追加_接在末尾`() {
        setup()
        val f = put("f.md", "甲\n乙\n")
        val r = EditFileTool.edit(root, "f.md", EditFileTool.OP_APPEND, null, null, "丙", stamp = "s5")
        assertTrue(r is ToolOutcome.Ok)
        assertEquals("甲\n乙\n丙\n", f.readText(Charsets.UTF_8))
    }

    @Test
    fun `追加_目标不存在时当新建用`() {
        setup()
        val r = EditFileTool.edit(root, "new.md", EditFileTool.OP_APPEND, null, null, "第一段", stamp = "s6")
        assertTrue("应允许 append 新建：$r", r is ToolOutcome.Ok)
        assertEquals("第一段\n", File(root, "new.md").readText(Charsets.UTF_8))
    }

    // ── ③ 行号越界拒 ────────────────────────────────────────────────────

    @Test
    fun `行号越界_拒绝而不是尽力而为`() {
        setup()
        val f = put("g.md", "只有两行\n第二行\n")
        val before = f.readText(Charsets.UTF_8)
        val r = edit("g.md", EditFileTool.OP_REPLACE, 5, 9)
        assertTrue("必须 Failed：$r", r is ToolOutcome.Failed)
        assertEquals("越界时文件不许被改", before, f.readText(Charsets.UTF_8))
    }

    @Test
    fun `行号非法_to小于from被拒`() {
        setup()
        put("h.md", "1\n2\n3\n")
        val r = edit("h.md", EditFileTool.OP_REPLACE, 3, 1)
        assertTrue(r is ToolOutcome.Failed)
    }

    @Test
    fun `文件不存在_替换被拒并提示用write_file`() {
        setup()
        val r = edit("nope.md", EditFileTool.OP_REPLACE, 1, 1)
        assertTrue(r is ToolOutcome.Failed)
        assertTrue("提示里应指路 write_file：$r", (r as ToolOutcome.Failed).error.contains("write_file"))
    }

    // ── ④ 备份 ──────────────────────────────────────────────────────────

    @Test
    fun `写前备份_旧版本落在baopu-bak`() {
        setup()
        put("i.md", "旧的一行\n")
        val r = EditFileTool.edit(root, "i.md", EditFileTool.OP_APPEND, null, null, "新的", stamp = "20261017-170000")
        assertTrue(r is ToolOutcome.Ok)
        val backupDir = File(root, EditFileTool.BACKUP_DIR_NAME)
        val backups = backupDir.listFiles()?.map { it.name } ?: emptyList()
        assertTrue("备份目录应有 i.md 的旧版本：$backups", backups.any { it.startsWith("i.md.") && it.endsWith(".bak") })
        val backup = backupDir.listFiles()!!.first { it.name.startsWith("i.md.") }
        assertEquals("旧的一行\n", backup.readText(Charsets.UTF_8))
    }

    @Test
    fun `不能改备份区里的文件`() {
        setup()
        val f = put("${EditFileTool.BACKUP_DIR_NAME}/x.md", "备份内容\n")
        val r = edit("${EditFileTool.BACKUP_DIR_NAME}/x.md", EditFileTool.OP_APPEND, null, null, "加")
        assertTrue(r is ToolOutcome.Failed)
        assertEquals("备份内容\n", f.readText(Charsets.UTF_8))
    }

    // ── ⑤ 其它失败路径 ──────────────────────────────────────────────────

    @Test
    fun `超两百行被拒`() {
        setup()
        put("big.md", "一行\n")
        val many = (1..201).joinToString("\n") { "行$it" }
        val r = edit("big.md", EditFileTool.OP_APPEND, null, null, many)
        assertTrue(r is ToolOutcome.Failed)
        assertEquals("一行\n", File(root, "big.md").readText(Charsets.UTF_8))
    }

    @Test
    fun `二进制文件被拒`() {
        setup()
        val f = File(root, "bin.dat")
        f.writeBytes(byteArrayOf(0x41, 0x00, 0x42))
        val r = edit("bin.dat", EditFileTool.OP_APPEND, null, null, "文本")
        assertTrue(r is ToolOutcome.Failed)
    }

    @Test
    fun `未知op被拒`() {
        setup()
        put("j.md", "x\n")
        val r = edit("j.md", "delete_lines", 1, 1)
        assertTrue(r is ToolOutcome.Failed)
    }

    @Test
    fun `内容没变时报告未写盘`() {
        setup()
        val f = put("k.md", "同上\n")
        val r = EditFileTool.edit(root, "k.md", EditFileTool.OP_REPLACE, 1, 1, "同上", stamp = "s7")
        assertTrue(r is ToolOutcome.Ok)
        assertTrue("应说明未写盘：$r", (r as ToolOutcome.Ok).text.contains("没有变化"))
        assertEquals("同上\n", f.readText(Charsets.UTF_8))
    }

    // ── ⑥ 声明层（权限／Schema） ────────────────────────────────────────

    @Test
    fun `权限必须是CONFIRM`() {
        setup()
        val spec = EditFileTool.spec { root }
        assertEquals(ToolPermission.CONFIRM, spec.permission)
        assertEquals("edit_file", spec.name)
    }

    @Test
    fun `Schema_禁止额外参数所以没有删除参数`() {
        setup()
        val spec = EditFileTool.spec { root }
        assertFalse(spec.inputSchema.getBoolean("additionalProperties"))
        assertTrue("不超过 500 字符的说明上限", spec.description.length <= ToolSpec.MAX_DESC)
    }
}
