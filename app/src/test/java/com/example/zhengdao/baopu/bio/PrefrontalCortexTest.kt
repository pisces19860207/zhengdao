// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百九十四批 · 前额叶单测：剩余步数＋卡步四条判据＋不改计划（逐字节）＋
// 计划原文不出读数（敏感夹具）＋确定性。"不修改计划"钉法：读数前后
// PlanRules.toJson(同一份 items) 逐字节相等——前额叶收到的是纯步视图，
// 结构上碰不到计划本体（BioSourceAssertTest 另钉禁引擎符号）。
package com.example.zhengdao.baopu.bio

import com.example.zhengdao.baopu.PlanRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefrontalCortexTest {

    private fun step(no: Int, done: Boolean = false) = PrefrontalCortex.PlanStepView(no, done)

    private fun read(
        steps: List<PrefrontalCortex.PlanStepView>,
        failures: List<Int?> = emptyList(),
    ): PrefrontalCortex.PlanReading = PrefrontalCortex.readPlan(
        PrefrontalCortex.PlanView(steps = steps, recentFailures = failures),
    )

    // ── stepsLeft：done 不算剩余；空计划是 0 不是异常 ──

    @Test
    fun stepsLeftCountsOnlyUndoneSteps() {
        val steps = listOf(step(1, done = true), step(2), step(3), step(4, done = true))
        assertEquals(2, read(steps).stepsLeft)
        assertEquals(0, read(emptyList()).stepsLeft)
    }

    // ── stuckAt：四条判据全满足才出步号 ──

    @Test
    fun stuckAtNeedsThreeFailuresOnSameUnfinishedPlannedStep() {
        val steps = listOf(step(1, done = true), step(2), step(3))
        assertEquals(2, read(steps, listOf(2, 2, 2)).stuckAt)
        // 给多了只看最后 3 条：前两条是别的步的旧失败，不搅动读数。
        assertEquals(2, read(steps, listOf(1, 2, 2, 2)).stuckAt)
    }

    @Test
    fun stuckAtIsNullWhenEvidenceIsInsufficientOrScattered() {
        val steps = listOf(step(1), step(2), step(3))
        assertNull(read(steps, listOf(2, 2)).stuckAt) // 不足 3 条：证据不够
        assertNull(read(steps, listOf(1, 2, 3)).stuckAt) // 分散：不成一线
        assertNull(read(steps, listOf(2, 2, null)).stuckAt) // 有缺步号：判不了就不猜
        assertNull(read(steps, listOf(2, 2, 3)).stuckAt) // 不同指一步
    }

    @Test
    fun stuckAtIsNullWhenStuckStepLeftThePlanOrIsDone() {
        // 计划改过：第 9 步不在当前表里 ⇒ 旧证据作废。
        assertNull(read(listOf(step(1), step(2)), listOf(9, 9, 9)).stuckAt)
        // 该步已完：失败史不追着做完的步跑。
        assertNull(read(listOf(step(1, done = true), step(2)), listOf(1, 1, 1)).stuckAt)
    }

    // ── reason 只由计数与步号拼成：计划原文一个字不出（敏感夹具）──

    @Test
    fun reasonCarriesCountsAndStepNumbersNeverPlanText() {
        val secret = "机密项目-阿尔法-7号批量采购价目表"
        val steps = listOf(step(1), step(2), step(3))
        val r = read(steps, listOf(2, 2, 2))
        assertTrue(r.reason.contains("剩 3/3 步未完"))
        assertTrue(r.reason.contains("第 2 步"))
        assertTrue(secret !in r.reason)
        assertTrue(secret !in r.toString())
        assertTrue("读数仅供参考" in r.reason)
    }

    @Test
    fun reasonExplainsWhyThereIsNoStuckReading() {
        val steps = listOf(step(1), step(2), step(3))
        assertTrue(read(steps, listOf(2, 2)).reason.contains("证据不够"))
        assertTrue(read(steps, listOf(2, 2, null)).reason.contains("判不了就不猜"))
        assertTrue(read(steps, listOf(1, 2, 3)).reason.contains("分散"))
        assertTrue(read(listOf(step(1)), listOf(9, 9, 9)).reason.contains("已不在当前表里"))
        assertTrue(read(listOf(step(1, done = true)), listOf(1, 1, 1)).reason.contains("已完"))
    }

    // ── 不修改计划（本批判据）：读数前后 update_plan 的 payload 逐字节一致 ──

    @Test
    fun updatePlanPayloadIsByteIdenticalAroundReadCalls() {
        val items = listOf(
            PlanRules.PlanItem("先把报表导出成 csv", PlanRules.STATUS_DONE),
            PlanRules.PlanItem("把金额列按大小排序", PlanRules.STATUS_DOING),
            PlanRules.PlanItem("写入汇总并发给你过目", PlanRules.STATUS_TODO),
        )
        val before = PlanRules.toJson(items)
        val view = PrefrontalCortex.PlanView(
            steps = items.mapIndexed { i, it ->
                PrefrontalCortex.PlanStepView(i + 1, it.status == PlanRules.STATUS_DONE)
            },
            recentFailures = listOf(2, 2, 2),
        )
        PrefrontalCortex.readPlan(view)
        PrefrontalCortex.readPlan(view)
        val after = PlanRules.toJson(items)
        assertEquals(before, after)
        // 顺带钉死 items 本体没被改（状态、文字原样）。
        assertEquals(PlanRules.STATUS_DONE, items[0].status)
        assertEquals(PlanRules.STATUS_DOING, items[1].status)
        assertEquals(PlanRules.STATUS_TODO, items[2].status)
    }

    // ── 确定性（判据④）：同输入两次读数逐字一致 ──

    @Test
    fun sameInputSameReadingTwice() {
        val view = PrefrontalCortex.PlanView(
            steps = listOf(step(1, done = true), step(2), step(3)),
            recentFailures = listOf(3, 3, 3),
        )
        assertEquals(PrefrontalCortex.readPlan(view), PrefrontalCortex.readPlan(view))
    }

    // ── 523 三件套①：有它＝失败史出卡步读数；没它（无史）＝同一份计划读不出卡步 ──

    @Test
    fun triple1StuckReadingOnlyWithFailureHistory() {
        val steps = listOf(step(1), step(2), step(3))
        assertEquals(2, read(steps, listOf(2, 2, 2)).stuckAt) // 有失败史：卡步读数出现
        assertNull(read(steps).stuckAt) // 无史：同一份计划，null——这个读数没它给不出来
    }
}
