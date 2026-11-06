// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 504：起居注行的编解码（判据①的行格式；三连转义纪律）。 */
class DiaryCodecTest {

    @Test
    fun `一行一事_编解码往返`() {
        val e = DiaryEvent(DiaryKind.ACTION, 1699000000000L, "值守·r1@s2", "write_file → 1 个目标（a.txt）", "成功已写入")
        val line = DiaryCodec.encode(e)
        assertEquals("action\t1699000000000\t值守·r1@s2\twrite_file → 1 个目标（a.txt）\t成功已写入", line)
        assertEquals(e, DiaryCodec.parse(line))
    }

    @Test
    fun `字段里的制表符换行反斜杠都转义_不撕行`() {
        val e = DiaryEvent(DiaryKind.STOP, 1L, "谁停的：人\ta", "当时：会话\\id：1", "硬停\n第二行")
        val parsed = DiaryCodec.parse(DiaryCodec.encode(e))!!
        assertEquals("谁停的：人\ta", parsed.why)
        assertEquals("当时：会话\\id：1", parsed.what)
        assertEquals("硬停\n第二行", parsed.result)
        // 落盘的那行永远是 5 列
        assertEquals(5, DiaryCodec.encode(e).split('\t').size)
    }

    @Test
    fun `坏行不硬读`() {
        assertNull(DiaryCodec.parse(""))
        assertNull(DiaryCodec.parse("action\t1\t只有四列\t×"))
        assertNull(DiaryCodec.parse("nope\t1\twhy\twhat\tresult")) // 格认不出
        assertNull(DiaryCodec.parse("action\tnot-a-number\twhy\twhat\tresult")) // 坏时刻
    }

    @Test
    fun `种类闭集_line是人话`() {
        assertTrue(DiaryKind.ofWire("action") == DiaryKind.ACTION)
        assertNull(DiaryKind.ofWire("mystery"))
        assertEquals("动作", DiaryKind.ACTION.cn)
        val e = DiaryEvent(DiaryKind.ROLLBACK, 1L, "w", "what", "r")
        assertTrue(e.line.startsWith("回滚｜"))
    }
}
