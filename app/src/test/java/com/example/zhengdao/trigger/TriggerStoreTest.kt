// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import com.example.zhengdao.schedule.ScheduleFreq
import com.example.zhengdao.schedule.ScheduleOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 规则表落盘：往返一致、坏文件整份隔离回退空表（167 ScheduleStore 同款判据）。 */
class TriggerStoreTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private fun store(): TriggerStore = TriggerStore(File(tmp.root, TriggerStore.FILE_NAME))

    private fun rule(id: String = "r1", source: TriggerSource = TriggerSource.SCHED): TriggerRule = TriggerRule(
        id = id, source = source, title = "早报", prompt = "今天日程+待办", enabled = true,
        freq = ScheduleFreq.DAILY, minuteOfDay = 480, dayOfWeek = 0, onceDate = "",
        zone = "Asia/Shanghai", leadMinutes = if (source == TriggerSource.CALENDAR) 10 else 0,
        nextWakeAtMs = 123L, firedKey = "", lastRunAtMs = 0L, lastOutcome = ScheduleOutcome.NONE,
        lastDetail = "", sessionId = -1L, runCount = 0, attempts = 0, createdAtMs = 9L,
    )

    @Test
    fun `存读往返_两个源的字段都齐`() {
        val s = store()
        assertTrue(s.save(listOf(rule("a"), rule("b", TriggerSource.CALENDAR))))
        val loaded = s.load()
        assertEquals(2, loaded.size)
        val cal = loaded.first { it.id == "b" }
        assertEquals(TriggerSource.CALENDAR, cal.source)
        assertEquals(10, cal.leadMinutes)
    }

    @Test
    fun `坏文件_整份挪bad_回退空表_不救半条`() {
        val f = File(tmp.root, TriggerStore.FILE_NAME)
        f.writeText("{\"version\":1,\"rules\":[{\"id\":\"x\"", Charsets.UTF_8)
        val s = store()
        assertTrue(s.load().isEmpty())
        assertTrue("原件要留证据（.bad 隔离，不原地硬删）", !f.exists() || File(tmp.root, "${TriggerStore.FILE_NAME}.bad").isFile)
    }

    @Test
    fun `version不符_按空表_旧文件不误用`() {
        val f = File(tmp.root, TriggerStore.FILE_NAME)
        f.writeText("""{"version":99,"rules":[]}""", Charsets.UTF_8)
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `update只改指定id_找不到返回false`() {
        val s = store()
        s.save(listOf(rule("a")))
        assertTrue(s.update(rule("a").copy(prompt = "改了")))
        assertEquals("改了", s.load().first { it.id == "a" }.prompt)
        assertFalse(s.update(rule("zzz")))
    }

    @Test
    fun `缺source的行_整条拒收_不救半条`() {
        val f = File(tmp.root, TriggerStore.FILE_NAME)
        f.writeText(
            """{"version":1,"rules":[{"id":"a","prompt":"x","enabled":true}]}""",
            Charsets.UTF_8,
        )
        assertTrue("缺 source 的规则比整表空掉更危险（会到点发话没人看得见）", store().load().isEmpty())
    }
}
