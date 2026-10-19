// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十七批 §3（J1–J4 全压 PlanController 这个 Context-free 内核；
// 引擎壳要真 SQLite + 网络，本仓无 Robolectric ⇒ 整环归真机，见交付报告挂账清单）。
// 房型照 CompressionRunnerTest：假 store 当「磁盘」，判据全部在 JVM 跑绿。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanControllerTest {

    /** 内存假件：insert 的流水全记着（落库的账）；latest 由测试摆（回种的账）。 */
    private class FakeStore : PlanStore {
        val inserts = mutableListOf<Pair<Long, String>>()
        var latestPayload: String? = null

        override fun insert(sessionId: Long, payload: String) {
            inserts += sessionId to payload
        }

        override fun latest(sessionId: Long): String? = latestPayload

        /** 最近一次落库的 payload 解析成的计划（没有落过 ⇒ null）。 */
        fun lastPlan(): List<PlanRules.PlanItem>? =
            inserts.lastOrNull()?.second?.let { PlanRules.fromJson(it) }
    }

    private class Rig(
        val controller: PlanController,
        val store: FakeStore,
        val logs: MutableList<String>,
    ) {
        /** 经过 GuardPipeline 真跑一次的结局（Done 才有 wrapped）。 */
        var lastWrapped: String? = null
    }

    private fun rig(sessionId: Long = 7L): Rig {
        val store = FakeStore()
        val logs = mutableListOf<String>()
        val controller = PlanController(sessionId = { sessionId }, store = store, log = { logs += it })
        return Rig(controller, store, logs)
    }

    private fun planArgs(vararg items: Pair<String, String>): JSONObject =
        JSONObject().put(
            "items",
            JSONArray().also { arr ->
                items.forEach { (text, status) ->
                    arr.put(JSONObject().put("text", text).put("status", status))
                }
            },
        )

    // ── J1：参数校验（执行器层；schema 层见 PlanToolTest） ──────────────

    @Test
    fun `空items_拒绝_且不落库不动内存`() {
        val r = rig()
        val out = r.controller.submit(JSONObject().put("items", JSONArray()))
        assertTrue("空 items 必须 Failed：$out", out is ToolOutcome.Failed)
        assertTrue("错误原文回灌（模型能自己改）", (out as ToolOutcome.Failed).error.contains("空"))
        assertTrue("拒绝不许落库", r.store.inserts.isEmpty())
    }

    @Test
    fun `坏条目_拒绝并不落库`() {
        val r = rig()
        val out = r.controller.submit(planArgs("读文件" to "finish"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("status"))
        assertTrue(r.store.inserts.isEmpty())
    }

    @Test
    fun `超12条_截断到12_落库的就是12条`() {
        val r = rig()
        // 2026-10-19：上限 8 → 12
        val thirteen = (1..13).map { "条目$it" to "todo" }.toTypedArray()
        val out = r.controller.submit(planArgs(*thirteen))
        assertTrue("截断不是拒绝：$out", out is ToolOutcome.Ok)
        val stored = r.store.lastPlan()
        assertNotNull(stored)
        assertEquals(12, stored!!.size)
        assertTrue("回灌要说明截断", (out as ToolOutcome.Ok).text.contains("12"))
    }

    @Test
    fun `text超60字_截断到60`() {
        val r = rig()
        val out = r.controller.submit(planArgs("甲".repeat(80) to "todo"))
        assertTrue(out is ToolOutcome.Ok)
        assertEquals(60, r.store.lastPlan()!![0].text.length)
        assertTrue((out as ToolOutcome.Ok).text.contains("60"))
    }

    // ── J2：全量覆盖 ───────────────────────────────────────────────────

    @Test
    fun `先5条再3条_库里留两条_最后一条是3条版`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo", "二" to "todo", "三" to "todo", "四" to "todo", "五" to "todo"))
        val out = r.controller.submit(planArgs("一" to "done", "二" to "doing", "三" to "todo"))
        assertTrue(out is ToolOutcome.Ok)
        assertEquals("每次调用一条，全部保留（不删旧行）", 2, r.store.inserts.size)
        val last = r.store.lastPlan()!!
        assertEquals("计划表是 3 条，不是 8 条合并", 3, last.size)
        assertEquals(listOf("一", "二", "三"), last.map { it.text })
        assertTrue("回灌报的是当前进度", (out as ToolOutcome.Ok).text.contains("1/3"))
    }

    @Test
    fun `落库带上引擎喂的sessionId`() {
        val r = rig(sessionId = 42L)
        r.controller.submit(planArgs("读文件" to "todo"))
        assertEquals(42L, r.store.inserts.single().first)
    }

    // ── J3：勾选只增不减 ───────────────────────────────────────────────

    @Test
    fun `done回退doing_保持done_并写进回灌`() {
        val r = rig()
        r.controller.submit(planArgs("读文件" to "done"))
        val out = r.controller.submit(planArgs("读文件" to "doing"))
        assertEquals(PlanRules.STATUS_DONE, r.store.lastPlan()!![0].status)
        assertTrue(
            "sticky 要告知模型（否则它下一版还打回 doing）",
            (out as ToolOutcome.Ok).text.contains("读文件") && out.text.contains("done"),
        )
    }

    @Test
    fun `todo进阶done_放行`() {
        val r = rig()
        r.controller.submit(planArgs("读文件" to "todo"))
        r.controller.submit(planArgs("读文件" to "done"))
        assertEquals(PlanRules.STATUS_DONE, r.store.lastPlan()!![0].status)
    }

    // ── 轮次事件（引擎接线的两个口子） ─────────────────────────────────

    @Test
    fun `新一轮_清空旧勾选`() {
        val r = rig()
        r.controller.submit(planArgs("读文件" to "done"))
        r.controller.newRound()
        val out = r.controller.submit(planArgs("读文件" to "doing"))
        val last = r.store.lastPlan()!!
        assertEquals("新任务不继承旧勾", PlanRules.STATUS_DOING, last[0].status)
        assertFalse("不该有 sticky notice", (out as ToolOutcome.Ok).text.contains("已完成"))
    }

    @Test
    fun `续跑回种_done不许被进程重启打回doing`() {
        val r = rig()
        // 模拟：进程被杀前最后落库的计划（第三条已完成）
        r.store.latestPayload = PlanRules.toJson(
            listOf(
                PlanRules.PlanItem("一", PlanRules.STATUS_DONE),
                PlanRules.PlanItem("二", PlanRules.STATUS_DONE),
                PlanRules.PlanItem("三", PlanRules.STATUS_DONE),
            ),
        )
        r.controller.seedFromStore(7L)
        r.controller.submit(planArgs("一" to "doing", "二" to "doing", "三" to "doing"))
        val last = r.store.lastPlan()!!
        assertTrue("回种后 done 全部保持", last.all { it.status == PlanRules.STATUS_DONE })
    }

    @Test
    fun `续跑回种_库里没有计划_从空表起`() {
        val r = rig()
        r.controller.seedFromStore(7L) // latestPayload = null
        r.controller.submit(planArgs("读文件" to "todo"))
        assertEquals(PlanRules.STATUS_TODO, r.store.lastPlan()!![0].status)
    }

    // ── J4：落库可回读 ─────────────────────────────────────────────────

    @Test
    fun `写入的内容_按sessionId原样读回`() {
        val r = rig()
        r.controller.submit(planArgs("读文件" to "done", longText(60) to "doing"))
        val payload = r.store.inserts.single().second
        // 「读回」= latest 取到的 payload 能解析回同一份计划（逐字一致的强形式）
        r.store.latestPayload = payload
        val back = PlanRules.fromJson(r.store.latest(7L)!!)
        assertEquals(2, back.size)
        assertEquals("读文件", back[0].text)
        assertEquals(60, back[1].text.length)
        assertEquals("payload 再序列化逐字一致", payload, PlanRules.toJson(back))
    }

    private fun longText(n: Int) = "乙".repeat(n)

    // ── 2026-10-19：计划提醒（用户真机「打完一步不打勾」；库里 done 出现 0 次）────────

    @Test
    fun reminder_lists_remaining_and_disappears_when_all_done() {
        val r = rig()
        assertNull("没计划时不该有提醒", r.controller.reminderLine())

        r.controller.submit(planArgs("读文件" to "doing", "写文件" to "todo", "核对" to "todo"))
        val line = r.controller.reminderLine()
        assertNotNull("有计划时应给提醒", line)
        assertTrue("要报剩余条数：$line", line!!.contains("还有 3 条没勾"))
        assertTrue("要指到 update_plan：$line", line.contains("update_plan"))
        assertTrue("要把后果说清：$line", line.contains("不标就等于没做"))
        assertTrue("要讲清「开始先标 doing」：$line", line.contains("先标 doing"))
        assertTrue("要讲清「不许批量补」：$line", line.contains("攒到最后批量补"))

        r.controller.submit(planArgs("读文件" to "done", "写文件" to "done", "核对" to "done"))
        assertNull("全勾完就不该再提醒", r.controller.reminderLine())
    }
}
