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

    // ── 176 §2：可撤销 ＋ 快照（UI 半的引擎侧判据）────────────────────────

    @Test
    fun `撤销一条_剩下的按FIFO注入`() {
        val q = InterjectionQueue()
        q.offer("第一段")
        q.offer("用简体中文")
        q.offer("文件名改小写")
        assertTrue("撤到了必须返回 true（界面据此收回 chip）", q.remove("用简体中文"))
        assertEquals(2, q.pending())
        assertEquals(listOf("第一段", "文件名改小写"), q.drainAll())
        assertEquals("取空必尽", 0, q.pending())
    }

    @Test
    fun `撤销没有的那条_返回false_队列不动`() {
        val q = InterjectionQueue()
        q.offer("在的")
        assertFalse("从没插过的撤不到", q.remove("不在的"))
        assertFalse("空串也撤不到", q.remove(""))
        assertFalse("纯空白也撤不到", q.remove("   "))
        assertEquals("队列一条不许少", listOf("在的"), q.drainAll())
    }

    @Test
    fun `两条一样的_撤掉先入队的那条`() {
        val q = InterjectionQueue()
        q.offer("继续")
        q.offer("继续")
        q.offer("别的")
        assertTrue(q.remove("继续"))
        assertEquals("FIFO 顺序对其余条目不变", listOf("继续", "别的"), q.drainAll())
    }

    @Test
    fun `快照按FIFO_且是拿到手那一拍的拷贝`() {
        val q = InterjectionQueue()
        q.offer("甲")
        q.offer("乙")
        val snap = q.snapshot()
        assertEquals(listOf("甲", "乙"), snap)
        q.offer("丙")
        assertEquals("快照不随后续入队变（界面显示的是拿到手那一拍）", listOf("甲", "乙"), snap)
        assertEquals(listOf("甲", "乙", "丙"), q.snapshot())
    }

    @Test
    fun `撤空之后_快照与drain都为空`() {
        val q = InterjectionQueue()
        q.offer("只有一条")
        assertTrue(q.remove("只有一条"))
        assertEquals(0, q.pending())
        assertEquals(emptyList<String>(), q.snapshot())
        assertEquals(emptyList<String>(), q.drainAll())
    }
}
