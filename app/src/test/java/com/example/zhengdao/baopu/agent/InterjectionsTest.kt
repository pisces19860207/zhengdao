// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十九批 §1.4.2 判据的直测：插话 FIFO「不丢不重」「空白不收」「取空必尽」。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterjectionsTest {

    @Test
    fun `连续插3条_FIFO注入3条_不丢不重`() {
        val q = InterjectionQueue()
        assertTrue(q.offer("先看看第一段"))
        assertTrue(q.offer("用简体中文"))
        assertTrue(q.offer("把文件名改成小写"))
        assertEquals(3, q.pending())
        assertEquals(
            "按入队顺序注入（FIFO）",
            listOf("先看看第一段", "用简体中文", "把文件名改成小写"),
            q.drainAll(),
        )
        assertEquals("取空必尽（再取必空——重与漏都靠这条挡住）", emptyList<String>(), q.drainAll())
        assertEquals(0, q.pending())
    }

    @Test
    fun `空白不收_不让空行占安全点`() {
        val q = InterjectionQueue()
        assertFalse("空串拒绝", q.offer(""))
        assertFalse("纯空白拒绝", q.offer("   \n  "))
        assertEquals(0, q.pending())
    }

    @Test
    fun `边插边取_各安全点只拿自己那份`() {
        val q = InterjectionQueue()
        q.offer("第一条")
        assertEquals(listOf("第一条"), q.drainAll())
        q.offer("第二条")
        q.offer("第三条")
        assertEquals(listOf("第二条", "第三条"), q.drainAll())
    }
}
