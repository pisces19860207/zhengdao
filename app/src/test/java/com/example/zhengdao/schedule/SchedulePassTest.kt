// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第一百六十七批 · 定时任务 v1》§1（到点发预设 prompt、错过补跑一次或标记已错过、
// 不刷屏、失败不静默）与 §3 的负例清单（重复触发 / 删除仍跑 / 无权限静默失败）。
//
// 这一套用真 [ScheduleStore]（临时目录里的真文件）＋假发送出口，把一趟 pass 的账整个跑出来：
// 派工单的验收判据全在"发几条、什么时候不再发、账写没写对"上，这些必须能反复跑，
// 而不是靠真机中等到点。引擎那边（BaopuRuntime / RunEngine）由 TaskSender 隔在外面。
package com.example.zhengdao.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class SchedulePassTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun store(): ScheduleStore = ScheduleStore(File(tmp.root, ScheduleStore.FILE_NAME))

    private fun task(
        id: String = "t1",
        freq: ScheduleFreq = ScheduleFreq.DAILY,
        onceDate: String = "",
        enabled: Boolean = true,
        next: Long = 0L,
        firedSlot: String = "",
        attempts: Int = 0,
        outcome: ScheduleOutcome = ScheduleOutcome.NONE,
        sessionId: Long = -1L,
        runCount: Int = 0,
    ): ScheduleTask = ScheduleTask(
        id = id,
        title = "早间一句",
        prompt = "提醒我喝水",
        freq = freq,
        minuteOfDay = 480,
        dayOfWeek = 0,
        onceDate = onceDate,
        zone = ZONE,
        enabled = enabled,
        nextRunAtMs = next,
        firedSlot = firedSlot,
        lastRunAtMs = 0L,
        lastOutcome = outcome,
        lastDetail = "",
        sessionId = sessionId,
        runCount = runCount,
        attempts = attempts,
        createdAtMs = 0L,
    )

    /** 只记不弹的出口：通知那条路要有，但这一层只管「该不该说」。 */
    private class Recording : PassNotifier {
        val firedNotes = mutableListOf<Triple<String, Long, Boolean>>()
        val failedNotes = mutableListOf<Pair<String, ScheduleOutcome>>()
        /** 第二百二十九批：事件顺序账——starting / sent 谁先谁后必须可断言。 */
        val events = mutableListOf<String>()

        override fun starting(task: ScheduleTask) {
            events += "starting:${task.id}"
        }

        override fun fired(task: ScheduleTask, sessionId: Long, late: Boolean) {
            firedNotes += Triple(task.id, sessionId, late)
            events += "fired:${task.id}"
        }

        override fun failed(task: ScheduleTask, outcome: ScheduleOutcome, detail: String) {
            failedNotes += task.id to outcome
            events += "failed:${task.id}"
        }
    }

    private val due = ms("2026-10-24T08:00:00Z")

    @Test
    fun `到点发一条_账一次写全`() {
        val s = store()
        s.save(listOf(task(next = due)))
        val calls = mutableListOf<String>()
        val rec = Recording()
        val result = SchedulePass(
            s,
            TaskSender { t, _ -> calls += t.id; SendResult(ScheduleOutcome.FIRED, 7L, "started") },
            rec,
        ).run(due + 1_000L)

        assertTrue(result.sent)
        assertEquals(1, result.handled)
        assertFalse("没有剩下的了，不该再让 WorkManager 重试", result.needsRetry)
        assertEquals(listOf("t1"), calls)

        val row = s.load().tasks.single()
        assertEquals(ScheduleOutcome.FIRED, row.lastOutcome)
        assertEquals(7L, row.sessionId)
        assertEquals(1, row.runCount)
        assertEquals(ScheduleRules.slotOf(due, ZONE), row.firedSlot)
        assertTrue("锚点必须推进，否则下一趟读到同一个过去时刻会再发一遍", row.nextRunAtMs > due)
        assertEquals(1, rec.firedNotes.size)
        assertFalse("准点那条不算补跑", rec.firedNotes.single().third)
    }

    @Test
    fun `负例_通知与执行不许同时_先说要开始了再发`() {
        // 第二百二十九批 §2：到点**先**响「要开始了」，**再**把 prompt 发进会话——
        // 顺序不许反、也不许并作一步（响不响由装配层的开关/权限判，pass 只保证顺序）。
        val s = store()
        s.save(listOf(task(next = due)))
        val rec = Recording()
        var sentAt = -1
        SchedulePass(
            s,
            TaskSender { _, _ -> sentAt = rec.events.size; SendResult(ScheduleOutcome.FIRED, 7L, "started") },
            rec,
        ).run(due + 1_000L)
        assertEquals(
            "starting 必须先于 send（send 时刻 events 里只有 starting，还没有 fired）",
            listOf("starting:t1"),
            rec.events.subList(0, sentAt),
        )
        assertEquals("fired 在 send 之后才记", listOf("starting:t1", "fired:t1"), rec.events)
    }

    @Test
    fun `失败路径也先响一声_顺序不变`() {
        // REFUSED_BUSY 重试趟同样先 starting 再 send：顺序是 pass 的形状，与结局无关。
        val s = store()
        s.save(listOf(task(next = due)))
        val rec = Recording()
        var sentAt = -1
        SchedulePass(
            s,
            TaskSender { _, _ -> sentAt = rec.events.size; SendResult(ScheduleOutcome.REFUSED_BUSY, -1L, "other_session_busy") },
            rec,
        ).run(due + 1_000L)
        assertEquals(listOf("starting:t1"), rec.events.subList(0, sentAt))
    }

    @Test
    fun `两条同时到点_这一趟只发一条`() {
        val s = store()
        s.save(listOf(task("t1", next = due), task("t2", next = due + 1_000L)))
        val calls = mutableListOf<String>()
        val result = SchedulePass(
            s,
            TaskSender { t, _ -> calls += t.id; SendResult(ScheduleOutcome.FIRED, 1L, "started") },
        ).run(due + 2_000L)

        assertTrue(result.sent)
        assertEquals("抱朴是全局单活跃，一趟灌两条必然被拒 ⇒ 只发一条", listOf("t1"), calls)
        assertTrue("剩下那条要说「还有」，交给退避重试", result.needsRetry)
        val t2 = s.load().tasks.first { it.id == "t2" }
        assertEquals("没发的那条一个字段都不许动", due + 1_000L, t2.nextRunAtMs)
        assertEquals("", t2.firedSlot)
    }

    @Test
    fun `闹钟与兜底撞同一槽位_后到的只推进锚点`() {
        val slot = ScheduleRules.slotOf(due, ZONE)
        val s = store()
        s.save(listOf(task(next = due, firedSlot = slot, outcome = ScheduleOutcome.FIRED, sessionId = 7L, runCount = 1)))
        val calls = mutableListOf<String>()
        val result = SchedulePass(
            s,
            TaskSender { t, _ -> calls += t.id; SendResult(ScheduleOutcome.FIRED, 7L, "started") },
        ).run(due + 60_000L)

        assertFalse(result.sent)
        assertEquals("一条都不许再发（派工单 §3 负例「重复触发」）", emptyList<String>(), calls)
        assertEquals(1, result.handled)
        val row = s.load().tasks.single()
        assertEquals("runCount 只在真发出去时加", 1, row.runCount)
        assertEquals("后到的那条不改写已记的结局（它没做过任何事）", ScheduleOutcome.FIRED, row.lastOutcome)
        assertTrue(row.nextRunAtMs > due)
    }

    @Test
    fun `被别的会话挡住_试到上限才翻篇并说一声`() {
        val s = store()
        s.save(listOf(task(next = due)))
        val rec = Recording()
        val pass = SchedulePass(
            s,
            TaskSender { _, _ -> SendResult(ScheduleOutcome.REFUSED_BUSY, -1L, "other_session_busy") },
            rec,
        )
        for (i in 1..3) {
            val r = pass.run(due + i * 1_000L)
            val row = s.load().tasks.single()
            assertEquals(i, row.attempts)
            assertTrue("还没到上限，下一趟还得再试", r.needsRetry)
            assertEquals("没到上限就不敲用户", emptyList<ScheduleOutcome>(), rec.failedNotes.map { it.second })
        }
        val r = pass.run(due + 4_000L)
        val row = s.load().tasks.single()
        assertEquals(ScheduleRules.slotOf(due, ZONE), row.firedSlot)
        assertEquals(ScheduleOutcome.REFUSED_BUSY, row.lastOutcome)
        assertEquals(0, row.attempts)
        assertTrue("锚点已推走", row.nextRunAtMs > due)
        assertEquals("到上限要说一声，不静默（§1）", listOf(ScheduleOutcome.REFUSED_BUSY), rec.failedNotes.map { it.second })
        assertFalse(r.needsRetry)
    }

    @Test
    fun `超过补跑窗_只标错过_不发消息也不敲通知`() {
        val s = store()
        s.save(listOf(task(next = due)))
        val calls = mutableListOf<String>()
        val rec = Recording()
        val result = SchedulePass(
            s,
            TaskSender { t, _ -> calls += t.id; SendResult(ScheduleOutcome.FIRED, 1L, "started") },
            rec,
        ).run(due + ScheduleRules.BACKFILL_WINDOW_MS + 60_000L)

        assertFalse(result.sent)
        assertEquals(emptyList<String>(), calls)
        assertEquals("关机一周再开不该灌七条：错过只标不敲（§1「不刷屏」）", emptyList<ScheduleOutcome>(), rec.failedNotes)
        assertTrue(rec.firedNotes.isEmpty())
        val row = s.load().tasks.single()
        assertEquals(ScheduleOutcome.MISSED, row.lastOutcome)
        assertEquals(ScheduleRules.slotOf(due, ZONE), row.firedSlot)
        assertTrue(row.nextRunAtMs > due)
        assertFalse(result.needsRetry)
    }

    @Test
    fun `发送抛异常不当成没事发生`() {
        val s = store()
        s.save(listOf(task(next = due)))
        val result = SchedulePass(
            s,
            TaskSender { _, _ -> throw IllegalStateException("装配失败") },
        ).run(due + 1_000L)
        assertFalse(result.sent)
        assertTrue("异常没发出去 ⇒ 还要再试", result.needsRetry)
        val row = s.load().tasks.single()
        assertEquals(1, row.attempts)
        // 还在重试途中：结局列保持「从没跑过」，界面上不会提前显示一条失败（翻了篇才定结局）。
        assertEquals(ScheduleOutcome.NONE, row.lastOutcome)
        assertEquals("IllegalStateException", row.lastDetail)
    }

    @Test
    fun `过了宽限期发出去的记成补跑`() {
        val s = store()
        s.save(listOf(task(next = due)))
        val rec = Recording()
        SchedulePass(
            s,
            TaskSender { _, _ -> SendResult(ScheduleOutcome.FIRED, 5L, "started") },
            rec,
        ).run(due + 20L * 60L * 1_000L)
        val row = s.load().tasks.single()
        assertEquals("补跑是这一趟判出来的，引擎只说发没发", ScheduleOutcome.FIRED_LATE, row.lastOutcome)
        assertTrue(rec.firedNotes.single().third)
    }

    @Test
    fun `引擎说排队就原样记排队`() {
        val s = store()
        s.save(listOf(task(next = due)))
        SchedulePass(
            s,
            TaskSender { _, _ -> SendResult(ScheduleOutcome.QUEUED, 9L, "queued") },
        ).run(due + 1_000L)
        assertEquals(ScheduleOutcome.QUEUED, s.load().tasks.single().lastOutcome)
    }

    @Test
    fun `一次性任务发完就此关掉`() {
        val s = store()
        s.save(listOf(task(freq = ScheduleFreq.ONCE, onceDate = "2026-10-24", next = due)))
        val calls = mutableListOf<String>()
        val pass = SchedulePass(
            s,
            TaskSender { t, _ -> calls += t.id; SendResult(ScheduleOutcome.FIRED, 2L, "started") },
        )
        pass.run(due + 1_000L)
        val row = s.load().tasks.single()
        assertFalse(row.enabled)
        assertEquals(ScheduleRules.NO_RUN, row.nextRunAtMs)
        // 再跑一趟：没有锚点 ⇒ 一条都不发（列表里它已经灰掉，不会到点又冒出来）。
        pass.run(due + 3_600_000L)
        assertEquals(listOf("t1"), calls)
    }

    @Test
    fun `没到点与关着的都不动`() {
        val s = store()
        val before = listOf(task("t1", next = due + 3_600_000L), task("t2", next = due - 1_000L, enabled = false))
        s.save(before)
        var calls = 0
        val result = SchedulePass(s, TaskSender { _, _ -> calls++; SendResult(ScheduleOutcome.FIRED, 1L, "x") }).run(due)
        assertFalse(result.sent)
        assertEquals(0, calls)
        assertEquals(0, result.handled)
        assertEquals(before, s.load().tasks)
    }

    @Test
    fun `账没落成要说还要再来一趟`() {
        val s = store()
        s.save(listOf(task(next = due)))
        val before = File(tmp.root, ScheduleStore.FILE_NAME).readText(Charsets.UTF_8)
        // 把原子写的 .tmp 名字先占成目录 ⇒ 这趟发得出去、落不了盘。
        assertTrue(File(tmp.root, ScheduleStore.FILE_NAME + ".tmp").mkdir())
        val result = SchedulePass(s, TaskSender { _, _ -> SendResult(ScheduleOutcome.FIRED, 7L, "started") }).run(due + 1_000L)
        assertTrue(result.sent)
        assertTrue("发出去了但账没写：下一趟可能重复 ⇒ 必须让它重试并说出来，不许当没事", result.needsRetry)
        assertEquals(before, File(tmp.root, ScheduleStore.FILE_NAME).readText(Charsets.UTF_8))
    }

    @Test
    fun `空表不跑发送`() {
        val s = store()
        var calls = 0
        val result = SchedulePass(s, TaskSender { _, _ -> calls++; SendResult(ScheduleOutcome.FIRED, 1L, "x") }).run(due)
        assertFalse(result.sent)
        assertEquals(0, calls)
        assertFalse(result.needsRetry)
    }

    private companion object {
        const val ZONE = "UTC"
    }
}
