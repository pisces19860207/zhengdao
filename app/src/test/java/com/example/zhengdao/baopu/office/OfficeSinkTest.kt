// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十二批 · 落盘口的判据（文件名清洗、同名不覆盖、绝不覆盖已有文件）。
package com.example.zhengdao.baopu.office

import com.example.zhengdao.baopu.agent.OfficeSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfficeSinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `文件名清洗_分隔符与非法字符换下划线_空名给兜底`() {
        assertEquals("季度_小结_定稿", OfficeSink.baseOf("季度/小结:定稿", "文档"))
        assertEquals("Windows 非法字符", "a_b_c_d", OfficeSink.baseOf("a*b?c\"d", "文档"))
        assertEquals("空名用兜底", "文档", OfficeSink.baseOf("   ", "文档"))
        assertEquals("只有点号也算空", "表格", OfficeSink.baseOf("...", "表格"))
        assertEquals("超长裁到 40 字", 40, OfficeSink.baseOf("字".repeat(80), "文档").length)
    }

    @Test
    fun `同名不覆盖_连写三次是三份_序号递增`() {
        val ws = tmp.newFolder("ws")
        val names = (1..3).map { i ->
            val done = OfficeSink.write(ws, "报告", "docx", byteArrayOf(i.toByte()), "20261113-1200")
            assertTrue("第 $i 次应当成功：$done", done is Step.Ok)
            done.valueOrNull()!!
        }.map { it.name }
        assertEquals(listOf("报告-20261113-1200.docx", "报告-20261113-1200-2.docx", "报告-20261113-1200-3.docx"), names)
        val dir = File(ws, "产物")
        assertEquals("三份都在", 3, dir.listFiles().orEmpty().count { it.isFile })
        assertEquals("内容各写各的（没互相覆盖）", byteArrayOf(1).toList(), File(dir, names[0]).readBytes().toList())
        assertEquals(byteArrayOf(3).toList(), File(dir, names[2]).readBytes().toList())
    }

    @Test
    fun `不同时间戳各落一份_互不干扰`() {
        val ws = tmp.newFolder("ws2")
        OfficeSink.write(ws, "报告", "docx", byteArrayOf(1), "20261113-1200")
        OfficeSink.write(ws, "报告", "docx", byteArrayOf(2), "20261113-1201")
        val names = File(ws, "产物").listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf("报告-20261113-1200.docx", "报告-20261113-1201.docx"), names)
    }
}
