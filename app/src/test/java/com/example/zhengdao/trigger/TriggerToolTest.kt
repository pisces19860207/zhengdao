// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `event_trigger` 工具（399 创建口）：必过确认卡（permission=CONFIRM）、
 * 参数不全不许猜、建了但源没开要如实说、list 报源开关现状。
 * 宿主（store/sink/env）全注入，不碰 Context。
 */
class TriggerToolTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    private lateinit var store: TriggerStore
    private var rescheduled = false

    @Before
    fun setUp() {
        store = TriggerStore(File(tmp.root, "trigger_rules.json"))
        TriggerTool.storeProvider = { store }
        rescheduled = false
        TriggerTool.sink = object : TriggerToolSink {
            override fun reschedule() {
                rescheduled = true
            }
        }
        TriggerTool.envProvider = { TriggerToolEnv(schedOn = true, calendarOn = false, calendarGranted = false) }
    }

    @After
    fun tearDown() {
        TriggerTool.storeProvider = null
        TriggerTool.sink = null
        TriggerTool.envProvider = null
    }

    private fun create(source: String, extra: JSONObject.() -> Unit = {}): ToolOutcome =
        TriggerTool.run(
            JSONObject().put("action", "create").put("source", source).put("prompt", "汇总材料").apply(extra),
            nowMs = 1_000L,
        )

    @Test
    fun `必过确认卡_permission是CONFIRM`() {
        assertEquals("event_trigger 必须是 CONFIRM（无人值守的事）", ToolPermission.CONFIRM, TriggerTool.spec().permission)
    }

    @Test
    fun `建定时规则_写库_重排_锚点在未来`() {
        val out = create("sched") { put("when", "每天 08:00") }
        assertTrue(out is ToolOutcome.Ok)
        assertEquals(1, store.load().size)
        val rule = store.load().single()
        assertEquals(TriggerSource.SCHED, rule.source)
        assertTrue("锚点要算出来（下一次 08:00）", rule.nextWakeAtMs > 0L)
        assertTrue("写完库要重排", rescheduled)
    }

    @Test
    fun `建日历规则_lead越界拒绝_合法入库`() {
        assertTrue(create("calendar") { put("lead_minutes", 0) } is ToolOutcome.Failed)
        assertTrue("lead 上限 720", create("calendar") { put("lead_minutes", 721) } is ToolOutcome.Failed)
        assertTrue(create("calendar") { put("lead_minutes", 5) } is ToolOutcome.Ok)
        assertEquals(1, store.load().size)
    }

    @Test
    fun `when认不出_拒绝_不许猜`() {
        val out = create("sched") { put("when", "0 8 * * *") }
        assertTrue("cron 尚不支持，认不出就拒绝（218 同口径）", out is ToolOutcome.Failed)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `源没开_结果里必须说_不许假装建成就会跑`() {
        val sched = create("sched") { put("when", "每天 08:00") }
        assertTrue("定时源开着 ⇒ 不提示", !(sched as ToolOutcome.Ok).text.contains("源开关还没开"))
        val cal = create("calendar") { put("lead_minutes", 5) } as ToolOutcome.Ok
        assertTrue("日程源没开 ⇒ 要说", cal.text.contains("日程源开关还没开"))
        assertTrue("日历权限没给 ⇒ 要说", cal.text.contains("日历权限没给"))
    }

    @Test
    fun `list_带规则与源开关现状`() {
        create("sched") { put("when", "每天 08:00") }
        val out = TriggerTool.run(JSONObject().put("action", "list"), nowMs = 2_000L) as ToolOutcome.Ok
        val json = JSONObject(out.text)
        assertEquals(1, json.getJSONArray("rules").length())
        assertFalse(json.getJSONObject("switches").getBoolean("calendarOn"))
    }

    @Test
    fun `暂停与恢复_删除_写完都重排`() {
        create("sched") { put("when", "每天 08:00") }
        val id = store.load().single().id
        assertTrue(TriggerTool.run(JSONObject().put("action", "pause").put("id", id), nowMs = 2_000L) is ToolOutcome.Ok)
        assertFalse(store.load().single().enabled)
        assertTrue(TriggerTool.run(JSONObject().put("action", "resume").put("id", id), nowMs = 3_000L) is ToolOutcome.Ok)
        assertTrue(store.load().single().enabled)
        assertTrue(TriggerTool.run(JSONObject().put("action", "delete").put("id", id), nowMs = 4_000L) is ToolOutcome.Ok)
        assertTrue(store.load().isEmpty())
        assertTrue(rescheduled)
    }

    @Test
    fun `没接宿主_如实说没接上_不假装修好`() {
        TriggerTool.storeProvider = null
        val out = TriggerTool.run(JSONObject().put("action", "list"), nowMs = 1_000L)
        assertTrue(out is ToolOutcome.Failed)
    }
}
