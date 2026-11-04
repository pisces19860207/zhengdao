// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 事件触发账（399 §二④「必须留痕」＋§三判据⑤「可回放」）：追加、时间窗回放、
 * 限频计数口径（只有真叫醒占额度）、坏行容错。
 */
class TriggerLedgerTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private fun ledger(): TriggerLedger = TriggerLedger(File(tmp.root, "trigger/事件触发.jsonl"))

    private fun row(ts: Long, source: String = "sched", action: String = "fired", sessionId: Long = 7L): TriggerLedger.Row =
        TriggerLedger.Row(tsMs = ts, source = source, ruleId = "r1", eventId = "", action = action, sessionId = sessionId, detail = "d")

    @Test
    fun `追加_读回_时间窗回放`() {
        val l = ledger()
        assertTrue(l.append(row(1_000L)))
        assertTrue(l.append(row(2_000L, source = "calendar")))
        assertTrue(l.append(row(3_000L)))
        assertEquals(3, l.rows().size)
        assertEquals("给定时间窗 ⇒ 列出所有触发（判据⑤）", 2, l.rowsInWindow(1_500L, 3_000L).size)
        assertEquals(1, l.rowsInWindow(1_500L, 3_000L).count { it.source == "calendar" })
    }

    @Test
    fun `限频计数_只有真叫醒占额度_被拒与限频本身不占`() {
        val l = ledger()
        l.append(row(1_000L, action = "fired"))
        l.append(row(2_000L, action = "queued"))
        l.append(row(3_000L, action = "refusedBusy"))
        l.append(row(4_000L, action = "rateLimited"))
        l.append(row(5_000L, action = "missed"))
        assertEquals(2, l.fireCountSince(TriggerSource.SCHED, 0L))
        assertEquals("窗口外的不数", 1, l.fireCountSince(TriggerSource.SCHED, 1_000L))
        assertEquals("分源计数（日历的 fired 不占定时的额度）", 0, l.fireCountSince(TriggerSource.CALENDAR, 0L))
    }

    @Test
    fun `坏行跳过_不挡回放_不抛`() {
        val f = File(tmp.root, "trigger/事件触发.jsonl")
        f.parentFile.mkdirs()
        f.writeText("{\"ts\":1000,\"source\":\"sched\",\"ruleId\":\"r1\",\"eventId\":\"\",\"action\":\"fired\",\"sessionId\":1,\"detail\":\"d\"}\n不是json\n", Charsets.UTF_8)
        val rows = ledger().rows()
        assertEquals(1, rows.size)
        assertEquals(1_000L, rows[0].tsMs)
    }

    @Test
    fun `文件不存在_空表_追加会自建父目录`() {
        val dir = File(tmp.root, "deep/nested")
        val l = TriggerLedger(File(dir, TriggerLedger.FILE_NAME))
        assertFalse(dir.exists())
        assertTrue(l.append(row(1L)))
        assertTrue("一处自建父目录（账本房型）", File(dir, TriggerLedger.FILE_NAME).isFile)
    }
}
