// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 事件账的编解码钉（IO 不测——JVM 没有 Android 盘；一行账怎么落就怎么读回来，钉在这里）。
package com.example.zhengdao.unattended

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnattendedLedgerTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `一行账_落盘长什么样_三段用制表符隔开`() {
        val line = UnattendedEventCodec.encode(UnattendedEvent(UnattendedEventKind.QUEUE, t0, "删哪些旧稿？"))
        assertEquals("queue\t$t0\t删哪些旧稿？", line)
    }

    @Test
    fun `正文里的制表符_换行_反斜杠_都不许把一行撕成三行`() {
        val nasty = "带\\\\反斜杠\\t制表符\\n换行的正文"
        val event = UnattendedEvent(UnattendedEventKind.STOP, t0, nasty)
        val line = UnattendedEventCodec.encode(event)
        assertEquals("转义后整行没有裸制表符之外的分隔问题：分 3 段", 3, line.split('\t').size)
        assertEquals(event, UnattendedEventCodec.parse(line))
    }

    @Test
    fun `原文含真换行_编码后单行_解码回原文`() {
        val event = UnattendedEvent(UnattendedEventKind.SUMMARY, t0, "第一行\n第二行")
        val line = UnattendedEventCodec.encode(event)
        assertTrue("落盘必须单行", !line.contains('\n'))
        assertEquals(event, UnattendedEventCodec.parse(line))
    }

    @Test
    fun `种类闭集_认不出的wire回null_不许静默当别的格`() {
        assertNull(UnattendedEventKind.ofWire("no_such_kind"))
        assertEquals(UnattendedEventKind.STOP, UnattendedEventKind.ofWire("stop"))
    }

    @Test
    fun `坏行一律回null_段数不对_时刻不是数_种类不认识`() {
        assertNull(UnattendedEventCodec.parse("queue"))
        assertNull(UnattendedEventCodec.parse("queue\tnot-a-time\t正文"))
        assertNull(UnattendedEventCodec.parse("mystery\t$t0\t正文"))
        assertNull(UnattendedEventCodec.parse(""))
    }

    @Test
    fun `十种事件_名字与人话都在_一个不自造`() {
        assertEquals(10, UnattendedEventKind.entries.size)
        UnattendedEventKind.entries.forEach { kind ->
            assertTrue("人话不许空：${kind.wire}", kind.cn.isNotBlank())
        }
    }
}
