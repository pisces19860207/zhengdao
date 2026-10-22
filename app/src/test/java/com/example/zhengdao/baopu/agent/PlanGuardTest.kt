// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第一百四十批 §测试（3 步偏离弹卡 / 计划变更过卡 / 负例）。
//
// 本文件只测**纯判据**（`PlanGuard.kt` 零 Android / 零 IO / 零时钟）：
// - 结构变更 vs 进度更新的分类（负例：永远 PROGRESS ⇒ 悄悄改计划不再被拦）；
// - 偏离卡三选一的映射与文案（负例：超时/未点被当成「更新计划」⇒ 偷偷放行改动）；
// - 卡的选项 id 是稳定契约（UI 回传 id，引擎只认 id）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlanGuardTest {

    private fun item(text: String, status: String = PlanRules.STATUS_TODO) = PlanItem(text, status)

    // ── 变更分类：只有文字增删才过卡 ──────────────────────────────────────

    @Test
    fun `初始计划_不算变更_不过卡`() {
        val change = PlanChangeRules.classify(emptyList(), listOf(item("读文件")))
        assertEquals(PlanChangeKind.NEW, change.kind)
        assertFalse(change.needsCard)
    }

    @Test
    fun `只改status_是进度更新_不过卡`() {
        val before = listOf(item("一"), item("二"))
        val after = listOf(item("一", PlanRules.STATUS_DONE), item("二", PlanRules.STATUS_DOING))
        val change = PlanChangeRules.classify(before, after)
        assertEquals(PlanChangeKind.PROGRESS, change.kind)
        assertFalse(change.needsCard)
        assertTrue(change.added.isEmpty() && change.removed.isEmpty())
    }

    @Test
    fun `只换顺序_不算结构变更_不过卡`() {
        val before = listOf(item("一"), item("二"), item("三"))
        val after = listOf(item("三"), item("一"), item("二"))
        val change = PlanChangeRules.classify(before, after)
        assertEquals(PlanChangeKind.PROGRESS, change.kind)
        assertFalse("重排一下不该逼用户点卡", change.needsCard)
    }

    @Test
    fun `文字增删_结构变更_必须过卡`() {
        val before = listOf(item("一"), item("二"), item("三"))
        val after = listOf(item("一"), item("新加一条"), item("四"))
        val change = PlanChangeRules.classify(before, after)
        assertEquals(PlanChangeKind.STRUCTURE, change.kind)
        assertTrue(change.needsCard)
        assertEquals(listOf("新加一条", "四"), change.added)
        assertEquals(listOf("二", "三"), change.removed)
    }

    @Test
    fun `文字被换掉一个_也算结构变更`() {
        // 负例靶心：用「条数相同」判没变 ⇒ 本用例红（换掉内容也能绕卡）
        val change = PlanChangeRules.classify(
            listOf(item("读文件"), item("写文件")),
            listOf(item("读文件"), item("随便看看")),
        )
        assertEquals(PlanChangeKind.STRUCTURE, change.kind)
        assertEquals(listOf("随便看看"), change.added)
        assertEquals(listOf("写文件"), change.removed)
    }

    // ── 偏离阈值与三选一 ──────────────────────────────────────────────────

    @Test
    fun `连续3步才提示_第1第2步不提示`() {
        assertFalse(PlanGuardRules.shouldPromptDeviation(1))
        assertFalse(PlanGuardRules.shouldPromptDeviation(2))
        assertTrue("第 3 步必须提示", PlanGuardRules.shouldPromptDeviation(PLAN_STALE_STEPS))
    }

    @Test
    fun `偏离卡三选一_id映射稳定`() {
        assertEquals(PlanDeviationAction.BACK_TO_PLAN, PlanGuardRules.deviationAction(PlanGuardRules.OPT_BACK))
        assertEquals(PlanDeviationAction.UPDATE_PLAN, PlanGuardRules.deviationAction(PlanGuardRules.OPT_UPDATE))
        assertEquals(PlanDeviationAction.CONTINUE, PlanGuardRules.deviationAction(PlanGuardRules.OPT_CONTINUE))
        assertEquals("超时/没点 ⇒ 继续，绝不偷偷批准改动", PlanDeviationAction.CONTINUE, PlanGuardRules.deviationAction(null))
    }

    @Test
    fun `计划变更卡_只有明确点接受才算同意`() {
        assertTrue(PlanGuardRules.changeAccepted(PlanGuardRules.OPT_CHANGE_ACCEPT))
        assertFalse(PlanGuardRules.changeAccepted(PlanGuardRules.OPT_CHANGE_REJECT))
        assertFalse("超时 = 不同意", PlanGuardRules.changeAccepted(null))
        assertFalse("偏离卡的 id 不能被当成变更接受", PlanGuardRules.changeAccepted(PlanGuardRules.OPT_UPDATE))
    }

    @Test
    fun `偏离卡_三个选项_回计划排第一并推荐`() {
        val req = PlanGuardCards.deviationRequest(
            listOf(item("一", PlanRules.STATUS_DONE), item("二", PlanRules.STATUS_DOING), item("三")),
        )
        assertEquals(3, req.options.size)
        assertEquals(PlanGuardRules.OPT_BACK, req.options[0].id)
        assertTrue(req.options[0].recommended)
        assertEquals(PlanGuardRules.OPT_UPDATE, req.options[1].id)
        assertEquals(PlanGuardRules.OPT_CONTINUE, req.options[2].id)
        assertTrue("问题要说清几步没推进", req.question.contains("连续 3 步"))
        assertTrue("问题要报剩余条数", req.question.contains("还有 2 条没勾"))
        assertTrue("要点名未完成项", req.question.contains("二"))
    }

    @Test
    fun `变更卡_两个选项_接受排第一`() {
        val req = PlanGuardCards.changeRequest(
            PlanChange(
                kind = PlanChangeKind.STRUCTURE,
                added = listOf("新一", "新二"),
                removed = listOf("旧一"),
            ),
        )
        assertEquals(2, req.options.size)
        assertEquals(PlanGuardRules.OPT_CHANGE_ACCEPT, req.options[0].id)
        assertEquals(PlanGuardRules.OPT_CHANGE_REJECT, req.options[1].id)
        assertTrue(req.question.contains("新增"))
        assertTrue(req.question.contains("删去"))
    }

    @Test
    fun `回计划回灌_说清下一步做什么`() {
        val feed = PlanGuardRules.deviationFeed(
            PlanDeviationAction.BACK_TO_PLAN,
            listOf(item("一", PlanRules.STATUS_DONE), item("二", PlanRules.STATUS_DOING), item("三")),
        )
        assertNotNull(feed)
        assertTrue(feed!!.contains("回到计划"))
        assertTrue("要指到第一条未完成", feed.contains("二"))
        assertTrue("要指到 update_plan", feed.contains("update_plan"))
        assertTrue("要说清不绑一步一勾", feed.contains("不要求一步一勾"))
    }

    @Test
    fun `更新计划回灌_明确下一版直接生效`() {
        val feed = PlanGuardRules.deviationFeed(PlanDeviationAction.UPDATE_PLAN, listOf(item("一")))
        assertNotNull(feed)
        assertTrue(feed!!.contains("更新计划"))
        assertTrue("模型必须知道这次改动会直接生效", feed.contains("直接生效"))
        assertTrue(feed.contains("update_plan"))
    }

    @Test
    fun `继续_不回灌任何东西`() {
        assertNull(
            "选继续 = 不阻断；往历史塞话术等于另一种打扰",
            PlanGuardRules.deviationFeed(PlanDeviationAction.CONTINUE, listOf(item("一"))),
        )
    }

    @Test
    fun `悄悄改被拒的回灌_说清没生效与下一步`() {
        val text = PlanGuardRules.changeBlocked(
            PlanChange(PlanChangeKind.STRUCTURE, added = listOf("新"), removed = listOf("旧")),
        )
        assertTrue("要明说没生效", text.contains("没有生效"))
        assertTrue("要说明原计划不变", text.contains("原计划保持不变"))
        assertTrue("要堵住换写法绕卡", text.contains("不要换一种写法"))
    }

    // ── 源码级：引擎接线（JVM 跑不起整条循环，判据压在文本上） ──────────

    private fun source(rel: String): String {
        val roots = listOf(
            File("app/src/main/java/com/example/zhengdao"),
            File("src/main/java/com/example/zhengdao"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 main 源码根（cwd=${File("").absolutePath}）")
        val f = File(root, rel)
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    @Test
    fun `引擎接线_变更卡在真执行前_偏离卡不阻断`() {
        val src = source("baopu/agent/RunEngine.kt")
        val gateIdx = src.indexOf("planChangeGate(call)?.let")
        val execIdx = src.indexOf("val result = pipeline.execute(call)")
        assertTrue(
            "变更闸必须在真执行之前（先问人再落库；索引失效会让本判据静默通过）",
            gateIdx > 0 && execIdx > gateIdx,
        )
        assertTrue(
            "变更卡走 askUser（与视觉授权卡同一条路）",
            src.contains("askUser(PlanGuardCards.changeRequest(change))"),
        )
        assertTrue(
            "偏离卡走 askUser",
            src.contains("askUser(PlanGuardCards.deviationRequest(items))"),
        )
        assertTrue(
            "每步执行后要记账（没有它，连续 3 步永远凑不满）",
            src.contains("planController.noteToolStep()"),
        )
        assertTrue(
            "成功的 update_plan 要清零",
            src.contains("planController.notePlanUpdated()"),
        )
        val devStart = src.indexOf("private fun promptPlanDeviation(")
        assertTrue("找不到 promptPlanDeviation", devStart > 0)
        val nextFun = src.indexOf("private fun ", devStart + 10)
        val devBody = src.substring(devStart, if (nextFun > 0) nextFun else src.length)
        assertFalse("偏离卡绝不能停轮（选继续 = 不阻断）", devBody.contains("STOP_RUN"))
        assertFalse("偏离卡不能改执行态", devBody.contains("settle("))
        assertFalse("偏离卡不落库（只进内存历史）", devBody.contains("persistAssistantMessage"))
        assertTrue(
            "「继续」要真的不塞提示（feed 为 null 时什么都不做）",
            devBody.contains("deviationFeed(action, items)?.let"),
        )
    }

}
