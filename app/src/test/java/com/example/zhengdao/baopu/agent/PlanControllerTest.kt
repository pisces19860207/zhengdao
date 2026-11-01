// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第十七批 §3（J1–J4 全压 PlanController 这个 Context-free 内核；
// 引擎壳要真 SQLite + 网络，本仓无 Robolectric ⇒ 整环归真机，见交付报告挂账清单）。
// 房型照 CompressionRunnerTest：假 store 当「磁盘」，判据全部在 JVM 跑绿。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanStore
import java.io.File
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

    private fun source(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }
            .readText()
            .replace("\r\n", "\n")

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
        // 2026-10-19：上限 8 → 12。347 军令状：13 条是长程任务 ⇒ 带 pledge（截断后 12 条
        // 对 13 条 acceptance，多出来的按位置丢——判定层口径，不影响本条要验的截断行为）
        val thirteen = (1..13).map { "条目$it" to "todo" }.toTypedArray()
        val accepts = (1..13).map { "验收$it" }
        val out = r.controller.submit(
            planArgs(*thirteen).put("pledge", JSONObject().put("acceptance", JSONArray(accepts))),
        )
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
        // 347 军令状：5 条是长程任务，开工要带 pledge（验收方式与条目按位对齐）；
        // 直调路径没有开工卡 ⇒ 按无状处理落库（不许自动立状），后续行为与今天一致。
        r.controller.submit(
            planArgs("一" to "todo", "二" to "todo", "三" to "todo", "四" to "todo", "五" to "todo")
                .put(
                    "pledge",
                    JSONObject().put(
                        "acceptance",
                        JSONArray(listOf("甲", "乙", "丙", "丁", "戊")),
                    ),
                ),
        )
        // 140 软档：5 条缩成 3 条是结构变更，必须过用户的卡（这里模拟用户点了「更新计划」）
        r.controller.authorizeNextChange()
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

    // ── 140 软档：计划有变必须过卡 ──────────────────────────────────────

    @Test
    fun `结构变更_未过卡_拒绝且不落库不改变内存`() {
        val r = rig()
        r.controller.submit(planArgs("读文件" to "todo", "写文件" to "todo"))
        val out = r.controller.submit(planArgs("读文件" to "todo", "随便看看" to "todo"))
        assertTrue("未经确认的结构变更必须拒绝：$out", out is ToolOutcome.Failed)
        assertTrue("要说清没生效", (out as ToolOutcome.Failed).error.contains("没有生效"))
        assertEquals("拒绝不许落库", 1, r.store.inserts.size)
        assertEquals("内存计划保持不变", listOf("读文件", "写文件"), r.controller.currentPlan().map { it.text })
        assertTrue(
            "预览能看出这版是结构变更",
            r.controller.previewChange(planArgs("读文件" to "todo", "随便看看" to "todo"))!!.needsCard,
        )
    }

    @Test
    fun `结构变更_过卡后生效_一次批准只放行一次`() {
        val r = rig()
        r.controller.submit(planArgs("读文件" to "todo"))
        r.controller.authorizeNextChange()
        val accepted = r.controller.submit(planArgs("读文件" to "doing", "写文件" to "todo"))
        assertTrue("用户点过更新计划 ⇒ 这一次必须生效：$accepted", accepted is ToolOutcome.Ok)
        assertEquals(listOf("读文件", "写文件"), r.controller.currentPlan().map { it.text })
        assertFalse("授权已消费", r.controller.isChangeAuthorized())
        val second = r.controller.submit(planArgs("读文件" to "doing", "核对" to "todo"))
        assertTrue("一次批准只放行一次：$second", second is ToolOutcome.Failed)
        assertEquals(2, r.store.inserts.size)
        assertEquals(listOf("读文件", "写文件"), r.controller.currentPlan().map { it.text })
    }

    @Test
    fun `只改status_是进度_不过卡`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo", "二" to "todo"))
        val out = r.controller.submit(planArgs("一" to "done", "二" to "doing"))
        assertTrue("进度更新不打扰用户：$out", out is ToolOutcome.Ok)
        assertEquals(2, r.store.inserts.size)
    }

    @Test
    fun `只换顺序_不过卡`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo", "二" to "todo"))
        val out = r.controller.submit(planArgs("二" to "doing", "一" to "todo"))
        assertTrue("重排不该逼用户点卡：$out", out is ToolOutcome.Ok)
    }

    @Test
    fun `初始计划_不过卡`() {
        val r = rig()
        assertEquals(PlanChangeKind.NEW, r.controller.previewChange(planArgs("一" to "todo"))!!.kind)
        val out = r.controller.submit(planArgs("一" to "todo"))
        assertTrue(out is ToolOutcome.Ok)
        assertFalse("初始计划不该产生授权状态", r.controller.isChangeAuthorized())
    }

    // ── 140 软档：连续 3 步偏离 ────────────────────────────────────────

    @Test
    fun `连续3步未推进_第3步提示_第4步不重复`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo", "二" to "todo"))
        assertFalse("第 1 步不弹", r.controller.noteToolStep())
        assertFalse("第 2 步不弹", r.controller.noteToolStep())
        assertTrue("第 3 步必须提示", r.controller.noteToolStep())
        assertFalse("同一段偏离不连环弹", r.controller.noteToolStep())
    }

    @Test
    fun `偏离卡选更新计划_放行新计划_后续按新计划提醒`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo"))
        repeat(3) { r.controller.noteToolStep() }
        r.controller.noteDeviationAnswered(PlanDeviationAction.UPDATE_PLAN)
        assertTrue("偏离卡选更新 ⇒ 下一次结构变更放行", r.controller.isChangeAuthorized())
        val out = r.controller.submit(planArgs("一" to "doing", "二" to "todo"))
        assertTrue("新计划必须生效：$out", out is ToolOutcome.Ok)
        assertEquals(listOf("一", "二"), r.controller.currentPlan().map { it.text })
        assertFalse("授权已消费", r.controller.isChangeAuthorized())
        assertTrue("回灌按新计划提醒（二还没勾）", r.controller.reminderLine()!!.contains("二"))
    }

    @Test
    fun `偏离卡选继续_不阻断也不再弹_计划更新后恢复`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo"))
        repeat(3) { r.controller.noteToolStep() }
        r.controller.noteDeviationAnswered(PlanDeviationAction.CONTINUE)
        assertFalse("继续不等于授权改计划", r.controller.isChangeAuthorized())
        repeat(6) { assertFalse("选继续后本段不再弹", r.controller.noteToolStep()) }
        val out = r.controller.submit(planArgs("一" to "doing"))
        assertTrue("继续不阻断计划更新：$out", out is ToolOutcome.Ok)
        assertFalse(r.controller.noteToolStep())
        assertFalse(r.controller.noteToolStep())
        assertTrue("计划更新后重新计数，满 3 步再提示", r.controller.noteToolStep())
    }

    @Test
    fun `偏离卡选回计划_三步后可再提示`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo"))
        repeat(3) { r.controller.noteToolStep() }
        r.controller.noteDeviationAnswered(PlanDeviationAction.BACK_TO_PLAN)
        assertFalse("回计划不等于授权改计划", r.controller.isChangeAuthorized())
        assertFalse(r.controller.noteToolStep())
        assertFalse(r.controller.noteToolStep())
        assertTrue("模型不听劝 ⇒ 满 3 步还得提醒", r.controller.noteToolStep())
    }

    @Test
    fun `没有计划_不提示偏离`() {
        val r = rig()
        repeat(5) { assertFalse(r.controller.noteToolStep()) }
    }

    @Test
    fun `计划全完成_不提示偏离`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "done", "二" to "done"))
        repeat(5) { assertFalse("全勾完不该催", r.controller.noteToolStep()) }
    }

    @Test
    fun `一次勾完多条_接受_不是一步一勾`() {
        // 负例靶心：把实现改成「一步只许勾一条」⇒ 本用例红
        val r = rig()
        r.controller.submit(planArgs("一" to "todo", "二" to "todo", "三" to "todo"))
        val out = r.controller.submit(planArgs("一" to "done", "二" to "done", "三" to "done"))
        assertTrue("不绑一步一勾：一次勾完多条必须接受：$out", out is ToolOutcome.Ok)
        assertEquals(3, PlanRules.doneCount(r.controller.currentPlan()))
    }

    @Test
    fun `新轮重置偏离与授权`() {
        val r = rig()
        r.controller.submit(planArgs("旧任务" to "todo"))
        repeat(2) { r.controller.noteToolStep() }
        r.controller.noteDeviationAnswered(PlanDeviationAction.UPDATE_PLAN)
        r.controller.newRound()
        assertFalse("新轮不许继承旧授权", r.controller.isChangeAuthorized())
        assertFalse("新轮没有计划，不提示", r.controller.noteToolStep())
        val out = r.controller.submit(planArgs("新任务" to "todo"))
        assertTrue("新轮第一份计划是 NEW，不过卡：$out", out is ToolOutcome.Ok)
    }

    // ── 161 降噪：同一版被明确拒过的改动不重复要授权 ────────────────────

    @Test
    fun `已拒过的同一版_重发不再要授权_仍不落库`() {
        val r = rig()
        r.controller.submit(planArgs("读文件" to "todo", "写文件" to "todo"))
        val v = planArgs("读文件" to "todo", "随便看看" to "todo")
        val first = r.controller.submit(v)
        assertTrue("没点过卡 ⇒ 照旧拦：$first", first is ToolOutcome.Failed)
        // 模拟确认卡：她点了「不改，按原计划」
        r.controller.noteDecision(r.controller.previewChange(v)!!, PlanChangeDecision.REJECTED)
        val again = r.controller.submit(v)
        assertTrue("拒过的同一版重发仍然不许落库：$again", again is ToolOutcome.Failed)
        assertTrue("回灌要讲清她已经答过：$again", (again as ToolOutcome.Failed).error.contains("已经明确拒过"))
        assertTrue("要堵住原样重发", again.error.contains("不会有第二次确认机会"))
        assertEquals("两次都不落库", 1, r.store.inserts.size)
        assertEquals("内存计划保持不变", listOf("读文件", "写文件"), r.controller.currentPlan().map { it.text })
    }

    @Test
    fun `拒过一版不影响别的改动_照常要授权`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo", "二" to "todo"))
        val rejected = planArgs("一" to "todo", "随便看看" to "todo")
        r.controller.noteDecision(r.controller.previewChange(rejected)!!, PlanChangeDecision.REJECTED)
        assertFalse("登记表不产生任何放行", r.controller.isChangeAuthorized())
        val other = planArgs("一" to "todo", "三" to "todo")
        val out = r.controller.submit(other)
        assertTrue("换了内容就是另一版，照旧拦：$out", out is ToolOutcome.Failed)
        assertFalse("不许误用「已经拒过」文案", (out as ToolOutcome.Failed).error.contains("已经明确拒过"))
        assertTrue("仍走未经确认的文案", out.error.contains("没有生效"))
    }

    @Test
    fun `拒过的同一版_她改主意批一次就生效_且不连放`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo", "二" to "todo"))
        val v = planArgs("一" to "todo", "随便看看" to "todo")
        r.controller.noteDecision(r.controller.previewChange(v)!!, PlanChangeDecision.REJECTED)
        assertTrue("拒过之后原样重发仍拦得住", r.controller.submit(v) is ToolOutcome.Failed)
        r.controller.authorizeNextChange()
        val ok = r.controller.submit(v)
        assertTrue("她改主意批一次就必须生效：$ok", ok is ToolOutcome.Ok)
        assertEquals(listOf("一", "随便看看"), r.controller.currentPlan().map { it.text })
        assertFalse("放行照旧一次一张", r.controller.isChangeAuthorized())
        val next = r.controller.submit(planArgs("一" to "todo", "换一个" to "todo"))
        assertTrue("批准过一版不给下一版开绿灯：$next", next is ToolOutcome.Failed)
    }

    @Test
    fun `生效即把同版旧拒翻成批准`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo", "二" to "todo"))
        val v = planArgs("一" to "todo", "随便看看" to "todo")
        val ch = r.controller.previewChange(v)!!
        r.controller.noteDecision(ch, PlanChangeDecision.REJECTED)
        assertEquals(PlanChangeDecision.REJECTED, r.controller.decisionFor(ch))
        r.controller.authorizeNextChange()
        assertTrue(r.controller.submit(v) is ToolOutcome.Ok)
        assertEquals("旧拒不该继续挡后面", PlanChangeDecision.APPROVED, r.controller.decisionFor(ch))
    }

    @Test
    fun `新轮开始_旧裁决作废_同一版重新要授权`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo", "二" to "todo"))
        val v = planArgs("一" to "todo", "随便看看" to "todo")
        r.controller.noteDecision(r.controller.previewChange(v)!!, PlanChangeDecision.REJECTED)
        r.controller.newRound()
        r.controller.submit(planArgs("一" to "todo", "二" to "todo"))
        val out = r.controller.submit(v)
        assertTrue("换一轮她重答：$out", out is ToolOutcome.Failed)
        assertFalse("用的是未经确认文案", (out as ToolOutcome.Failed).error.contains("已经明确拒过"))
    }

    @Test
    fun `续跑回种_旧裁决不跟过来`() {
        val r = rig()
        r.controller.submit(planArgs("一" to "todo", "二" to "todo"))
        val v = planArgs("一" to "todo", "随便看看" to "todo")
        val ch = r.controller.previewChange(v)!!
        r.controller.noteDecision(ch, PlanChangeDecision.REJECTED)
        assertEquals(PlanChangeDecision.REJECTED, r.controller.decisionFor(ch))
        r.store.latestPayload = PlanRules.toJson(
            listOf(
                PlanRules.PlanItem("一", PlanRules.STATUS_TODO),
                PlanRules.PlanItem("二", PlanRules.STATUS_TODO),
            ),
        )
        r.controller.seedFromStore(7L)
        assertNull("进程重启回种 ⇒ 裁决表清空", r.controller.decisionFor(r.controller.previewChange(v)!!))
        val out = r.controller.submit(v)
        assertTrue("回种后她重新答：$out", out is ToolOutcome.Failed)
        assertFalse((out as ToolOutcome.Failed).error.contains("已经明确拒过"))
    }

    // ── 140 红线：不新开存储 ────────────────────────────────────────────

    @Test
    fun `不新开存储_仍只走PlanStore`() {
        val controller = source("src/main/java/com/example/zhengdao/baopu/agent/PlanController.kt")
        val guard = source("src/main/java/com/example/zhengdao/baopu/agent/PlanGuard.kt")
        assertTrue("计划内核仍只认 PlanStore 接口", controller.contains("private val store: PlanStore"))
        assertFalse("计划内核不许自己建表", controller.contains("CREATE TABLE"))
        assertFalse("计划内核不许碰 SQLite", controller.contains("SQLite"))
        assertFalse("软档判据零存储", guard.contains("CREATE TABLE") || guard.contains("SQLite"))
        assertFalse("软档判据不许拿数据库", guard.contains("PlanStore("))
    }
}
