// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第一百六十七批 · 定时任务 v1》§0.5 约束 1（v1 存独立 `files/schedule_tasks.json`，
// 原子写＋坏了回退空表）与 §5 ④（存储原子写怎么写）。
//
// 这里用**真临时目录**跑读写与损坏回退，不 mock：mock 出来的"原子性"是自证，
// 真文件才能把 rename、`.tmp` 残留、`.bad` 证据这些路径跑出来。
package com.example.zhengdao.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScheduleStoreTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private fun store(): ScheduleStore = ScheduleStore(File(tmp.root, ScheduleStore.FILE_NAME))

    private fun task(id: String = "t1", prompt: String = "提醒我喝水", next: Long = 1_700_000_000_000L) =
        ScheduleTask(
            id = id,
            title = "早间一句",
            prompt = prompt,
            freq = ScheduleFreq.DAILY,
            minuteOfDay = 480,
            dayOfWeek = 0,
            onceDate = "",
            zone = "Asia/Shanghai",
            enabled = true,
            nextRunAtMs = next,
            firedSlot = "2026-10-23T08:00",
            lastRunAtMs = next - 86_400_000L,
            lastOutcome = ScheduleOutcome.FIRED,
            lastDetail = "started",
            sessionId = 42L,
            runCount = 3,
            attempts = 0,
            createdAtMs = next - 900_000_000L,
        )

    @Test
    fun `文件不存在_是空的而不是坏的`() {
        val r = store().load()
        assertEquals(LoadStatus.EMPTY_MISSING, r.status)
        assertTrue(r.tasks.isEmpty())
        assertEquals("没坏就不许有 detail（界面上「损坏」与「还没建」是两句话）", "", r.detail)
    }

    @Test
    fun `存进去的与读出来的一条不差`() {
        val s = store()
        val rows = listOf(task("t1"), task("t2", "晚上复盘", 1_700_003_600_000L))
        assertTrue(s.save(rows))
        val back = s.load()
        assertEquals(LoadStatus.OK, back.status)
        assertEquals(rows, back.tasks)
        // 逐字段点一遍：账目字段丢一个都会让「下次/上次」显示错，而整表相等已经覆盖，
        // 这里再钉一次「结局与槽位」这两个最容易被序列化漏掉的枚举/字符串字段。
        assertEquals(ScheduleOutcome.FIRED, back.tasks[0].lastOutcome)
        assertEquals("2026-10-23T08:00", back.tasks[0].firedSlot)
    }

    @Test
    fun `单条序列化是幂等的`() {
        val t = task()
        val once = ScheduleTask.fromJson(t.toJson())
        assertNotNull(once)
        assertEquals(t, once)
    }

    @Test
    fun `空表落盘后读回来仍是空但状态是好的`() {
        val s = store()
        assertTrue(s.save(emptyList()))
        val r = s.load()
        assertEquals(LoadStatus.OK, r.status)
        assertTrue(r.tasks.isEmpty())
    }

    @Test
    fun `坏文件_整表回退空表并把原件留成点名的坏件`() {
        val file = File(tmp.root, ScheduleStore.FILE_NAME)
        val garbage = "这不是 JSON，半截就断了"
        file.writeText(garbage, Charsets.UTF_8)
        val r = store().load()
        assertEquals(LoadStatus.CORRUPT_RECOVERED, r.status)
        assertTrue("坏了只能给空表，不许猜内容", r.tasks.isEmpty())
        assertEquals(ScheduleStore.corruptFileName(), "schedule_tasks.json.bad")
        val bad = File(tmp.root, ScheduleStore.corruptFileName())
        assertTrue(".bad 证据文件必须留下：否则用户没法知道丢了什么", bad.isFile)
        assertEquals(garbage, bad.readText(Charsets.UTF_8))
        assertFalse("原件已挪走，原地不留一个坏文件继续被读", file.exists())
    }

    @Test
    fun `版本号不认识_同样整表按损坏处理`() {
        File(tmp.root, ScheduleStore.FILE_NAME).writeText(
            """{"version":99,"tasks":[]}""",
            Charsets.UTF_8,
        )
        assertEquals(LoadStatus.CORRUPT_RECOVERED, store().load().status)
    }

    @Test
    fun `半条坏数据不许救半条`() {
        val good = task().toJson()
        val text = """{"version":1,"tasks":[$good,{}]}"""
        File(tmp.root, ScheduleStore.FILE_NAME).writeText(text, Charsets.UTF_8)
        val r = store().load()
        assertEquals(
            "补成半条任务比整表空掉更危险：用户看不见它，它却照样到点发消息",
            LoadStatus.CORRUPT_RECOVERED,
            r.status,
        )
        assertTrue(r.tasks.isEmpty())
    }

    @Test
    fun `写盘不留临时件`() {
        val s = store()
        assertTrue(s.save(listOf(task())))
        val leftovers = tmp.root.listFiles { f: File -> f.name.endsWith(".tmp") }.orEmpty()
        assertTrue("原子写之后 .tmp 不该留在盘上：${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    @Test
    fun `写不进就说写不进_不许静默当保存成功`() {
        val s = store()
        assertTrue(s.save(listOf(task())))
        val before = File(tmp.root, ScheduleStore.FILE_NAME).readText(Charsets.UTF_8)
        // 把 .tmp 那个名字先占成一个**目录**：FileOutputStream 打不开目录 ⇒ 原子写这条路上所有
        // 出口都失败 ⇒ save 必须返回 false，并且原文件一个字都不动。
        val tmpDir = File(tmp.root, ScheduleStore.FILE_NAME + ".tmp")
        assertTrue(tmpDir.mkdir())
        assertFalse(s.save(listOf(task(), task("t2"))))
        assertEquals(before, File(tmp.root, ScheduleStore.FILE_NAME).readText(Charsets.UTF_8))
        assertEquals(LoadStatus.OK, s.load().status)
    }

    @Test
    fun `改单条找不到就返回失败不新增`() {
        val s = store()
        assertTrue(s.save(listOf(task("t1"))))
        assertFalse("表里没有这个 id ⇒ update 是失败，不是顺手插一条", s.update(task("t9")))
        assertEquals(listOf("t1"), s.load().tasks.map { it.id })
    }

    @Test
    fun `整表解析在纯函数层就已经拦住半条`() {
        assertNull(ScheduleTask.listFromJson("{"))
        assertNull(ScheduleTask.listFromJson("""{"version":1}"""))
        assertEquals(0, ScheduleTask.listFromJson("""{"version":1,"tasks":[]}""")?.size)
    }
}
