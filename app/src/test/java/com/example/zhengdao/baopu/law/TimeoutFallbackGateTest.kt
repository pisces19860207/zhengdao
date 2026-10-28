// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十五批 · 法典卷九 **L27 超时保底**的门禁测试。
//
// 这一条是**门禁**，不是规范：它调的是生产码 [TimeoutFallback]（`app/src/main/.../baopu/law/`），
// 生产判错它就红。271 批（分支 `feat/law-gates`，未合并）那份测试侧 spec 自带 `Timeout` 对象，
// 生产怎么改都不会红；本文件不定义判据，只消费 `baopu/law/` 里的生产判据，并钉上 spec 漏掉的
// 那半句法典——
// **「可回退者按原状回退」**（[TimeoutVerdict.rollbackRequested]）。
//
// 覆盖判据（法典 L27 原文逐条）：超窗口⇒判不批／维持原状／可回退者回退／必落「待御裁」／
// 同一事项二次上奏必被拒并记违规／窗口与次数是参数（改了判定必须跟着变）。
package com.example.zhengdao.baopu.law

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeoutFallbackGateTest {

    private val t0 = 1_800_000_000_000L
    private val hour = 3600_000L

    private fun r(
        raisedAgoHours: Long,
        reversible: Boolean,
        submissions: Int = 1,
        recorded: Boolean = false,
    ) = PendingRuling(
        caseId = "case-1",
        raisedAtMs = t0 - raisedAgoHours * hour,
        reversible = reversible,
        submissions = submissions,
        settlementRecorded = recorded,
    )

    // ── 正例：窗口内只等；超时按不批结算，可回退者回退，账已落则无违规 ──────

    @Test
    fun `L27_正例_窗口内继续等_超时判不批维持原状可回退者回退并落待御裁`() {
        val waiting = TimeoutFallback.settle(r(1, reversible = true), t0)
        assertEquals("窗口内还在等", TimeoutOutcome.WAITING, waiting.outcome)
        assertFalse("窗口内也没有批（没批就是不许执行）", waiting.approved)
        assertFalse("窗口内不结算，谈不上维持原状", waiting.keepOriginal)
        assertFalse("窗口内不落账", waiting.mustRecord)
        assertNull("窗口内没有违规", waiting.violation)

        // 边界写死：等满 24h 整不算超时，超出 1ms 才算（口径只能有一个）
        val exact = TimeoutFallback.settle(
            PendingRuling("case-2", raisedAtMs = t0, reversible = true),
            nowMs = t0 + TimeoutFallback.WINDOW_MS,
        )
        assertEquals("等满窗口整仍是等待（超出才算超时）", TimeoutOutcome.WAITING, exact.outcome)

        val rolled = TimeoutFallback.settle(r(TimeoutFallback.WINDOW_MS / hour + 1, reversible = true, recorded = true), t0)
        assertEquals("超窗口判超时不批", TimeoutOutcome.TIMEOUT_REJECTED, rolled.outcome)
        assertFalse("超时永远不等于同意", rolled.approved)
        assertTrue("超时必须维持原状", rolled.keepOriginal)
        assertTrue("可回退者必须回退", rolled.rollbackRequested)
        assertTrue("超时必须有那条账", rolled.mustRecord)
        assertTrue("抬头要写「待御裁」：${rolled.recordNote}", rolled.recordNote.contains("待御裁"))
        assertEquals("账已落 ⇒ 这次结算没有违规", null, rolled.violation)

        // 不可回退者：维持原状，但本层不许喊回退（回退不了的东西不许假装回退）
        val stuck = TimeoutFallback.settle(r(30, reversible = false, recorded = true), t0)
        assertEquals("不可回退者也判超时不批", TimeoutOutcome.TIMEOUT_REJECTED, stuck.outcome)
        assertTrue("不可回退者也维持原状", stuck.keepOriginal)
        assertFalse("不可回退者不许喊回退", stuck.rollbackRequested)
        assertFalse("账注不许写「已回退」：${stuck.recordNote}", stuck.recordNote.contains("已按原状回退"))

        // 窗口是参数：换成 1 小时后，等了 2 小时的事项就该结算
        val oneHourWindow = TimeoutFallback.settle(r(2, reversible = true), t0, windowMs = hour)
        assertEquals("改窗口 ⇒ 判定必须跟着变", TimeoutOutcome.TIMEOUT_REJECTED, oneHourWindow.outcome)
    }

    // ── 负例：超时当同意／不落账／二次上奏放过，三种错法都要红 ──────────────

    @Test
    fun `L27_负例_超时不许当同意_不落账必须记违规_同一事项二次上奏必被拒`() {
        // ① 超时后被当成"同意" ⇒ 红（法典：默认同意等于 L20 的御批白设）
        val expired = TimeoutFallback.settle(r(30, reversible = true, recorded = true), t0)
        assertFalse("超时永远不许产出 approved=true", expired.approved)
        assertTrue("超时不许还挂在等待里", expired.outcome != TimeoutOutcome.WAITING)

        // ② 超时却不落账 ⇒ 红（不许静默作废）
        val silent = TimeoutFallback.settle(r(30, reversible = true, recorded = false), t0)
        assertTrue("超时没落账必须记违规", silent.violation != null)
        assertTrue("违规原文要点明落账：${silent.violation}", silent.violation!!.contains("落账"))
        assertTrue("账没落也要给出该落的抬头：${silent.recordNote}", silent.recordNote.contains("待御裁"))

        // ③ 同一事项二次上奏 ⇒ 必被拒 + 记违规（最多一次的"一次"是上限，不是许可）
        val second = TimeoutFallback.settle(r(48, reversible = true, submissions = 2), t0)
        assertEquals("二次上奏必判被拒", TimeoutOutcome.REFILING_REJECTED, second.outcome)
        assertFalse("二次上奏也不许放行", second.approved)
        assertTrue("二次上奏必须记违规", second.violation != null)
        assertTrue("违规原文要点明反复上奏：${second.violation}", second.violation!!.contains("上奏"))
        assertFalse("不许二次回退（上一轮已按原状回退过）", second.rollbackRequested)

        // ④ 第一次不许被判违规（防"一次就报"的假门禁）
        val first = TimeoutFallback.settle(r(1, reversible = true, submissions = 1), t0)
        assertEquals("第一次上奏不该被判违规", null, first.violation)

        // ⑤ 时钟回拨（now 早于上奏时刻）不许当超时，也不许当"等到了"就批
        val skewed = TimeoutFallback.settle(
            PendingRuling("case-3", raisedAtMs = t0 + 10 * hour, reversible = true),
            nowMs = t0,
        )
        assertEquals("时钟回拨只当还没到期", TimeoutOutcome.WAITING, skewed.outcome)
        assertFalse("到期与否都不许产出 approved=true", skewed.approved)

        // ⑥ 参数口径钉住：窗口与上奏上限都不是随手数
        assertEquals("窗口默认 24 小时", 24L * hour, TimeoutFallback.WINDOW_MS)
        assertEquals("同一事项上奏上限 1 次", 1, TimeoutFallback.MAX_SUBMISSIONS)
    }
}
