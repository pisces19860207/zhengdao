// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// E-168 / D-03（2026-10-19 真机走查 46 批）：edit_file 必须保住原文件的行尾与尾换行。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EditFileEolTest {

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

    private fun edit(rel: String, from: Int, content: String) =
        EditFileTool.edit(root, rel, "replace_lines", from, from, content, stamp = "20261019-180000")

    private fun count(s: String, sub: String): Int = s.windowed(sub.length).count { it == sub }

    @Test
    fun crlf_file_keeps_crlf_after_editing_one_line() {
        setup()
        val f = put("a.txt", "第一行\r\n第二行\r\n第三行\r\n")
        val r = edit("a.txt", 2, "改过的第二行")
        assertTrue("要成功：$r", r is ToolOutcome.Ok)
        val out = f.readText(Charsets.UTF_8)
        assertEquals("第一行\n改过的第二行\n第三行\n", out.replace("\r\n", "\n"))
        assertEquals("CRLF 条数必须不变（3）", 3, count(out, "\r\n"))
        assertFalse("不许出现裸 LF", out.replace("\r\n", "").contains("\n"))
        assertTrue("尾换行必须保住", out.endsWith("\r\n"))
    }

    @Test
    fun no_trailing_newline_is_not_invented() {
        setup()
        val f = put("b.txt", "A\r\nB")
        edit("b.txt", 1, "AA")
        val out = f.readText(Charsets.UTF_8)
        assertEquals("AA\r\nB", out)
        assertFalse("原来没有尾换行，不许补", out.endsWith("\n"))
    }

    @Test
    fun trailing_lf_is_kept_for_lf_file() {
        setup()
        val f = put("c.txt", "x\ny\n")
        edit("c.txt", 1, "xx")
        val out = f.readText(Charsets.UTF_8)
        assertEquals("xx\ny\n", out)
        assertFalse("不许自己变成 CRLF", out.contains("\r"))
    }

    @Test
    fun cr_only_file_keeps_cr() {
        setup()
        val f = put("d.txt", "一\r二\r")
        edit("d.txt", 2, "三")
        val out = f.readText(Charsets.UTF_8)
        assertEquals("一\r三\r", out)
    }

    @Test
    fun detect_and_join_roundtrip_is_identity() {
        val cases = listOf(
            "a\nb\n" to true,
            "a\nb" to false,
            "a\r\nb\r\n" to true,
            "a\rb" to false,
        )
        cases.forEach { (text, ends) ->
            val style = EditFileTool.detectEol(text)
            assertEquals("endsWithNewline(${text.replace("\r", "\\r").replace("\n", "\\n")})", ends, style.endsWithNewline)
            val body = text.removeSuffix("\r\n").removeSuffix("\n").removeSuffix("\r")
            val lines = if (body.isEmpty()) emptyList() else body.split("\r\n", "\n", "\r")
            assertEquals("往返必须逐字相等", text, EditFileTool.joinWithEol(lines, style))
        }
    }
}
