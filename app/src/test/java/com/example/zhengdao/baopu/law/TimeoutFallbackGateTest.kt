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
// 覆盖判据（法典 L27 原文＋2026-10-29 裁定 Q2 的**窄口径**）：超窗口⇒判不批／维持原状／可回退者回退／
// 必落「待御裁」／**已被超时结算过**的事项再奏必拒并记违规；没结算过的最多 2 次提交、第 3 次才拒／
// 窗口与上奏上限都是参数（改了判定必须跟着变）。
// 边界（2026-10-31 御裁 Q3）：elapsed == threshold ⇒ 触发；差 1 毫秒仍等待。
package com.example.zhengdao.baopu.law

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        settled: Boolean = false,
    ) = PendingRuling(
        caseId = "case-1",
        raisedAtMs = t0 - raisedAgoHours * hour,
        reversible = reversible,
        submissions = submissions,
        settlementRecorded = recorded,
        timeoutSettled = settled,
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

        // 边界改由下面那条专门的边界例钉住（2026-10-31 御裁 Q3：到达即触发）

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

        // Q2 窄口径的另一半：**没结算过**的事项允许再提一次，第 2 次还是"继续等"，不记违规
        val again = TimeoutFallback.settle(r(2, reversible = true, submissions = 2), t0)
        assertEquals("没结算过的第 2 次上奏：还在窗口里就该继续等", TimeoutOutcome.WAITING, again.outcome)
        assertNull("再提一次本身不是违规：${again.violation}", again.violation)

        // 窗口是参数：换成 1 小时后，等了 2 小时的事项就该结算
        val oneHourWindow = TimeoutFallback.settle(r(2, reversible = true), t0, windowMs = hour)
        assertEquals("改窗口 ⇒ 判定必须跟着变", TimeoutOutcome.TIMEOUT_REJECTED, oneHourWindow.outcome)
    }

    // ── 边界（2026-10-31 御裁 Q3）：到达即触发 ────────────────────────────────

    @Test
    fun `L27_边界_到达阈值即触发_差一毫秒仍等待`() {
        // 与旧口径 `>`（超过才触发）的差异由本例钉住：Q3 裁定 L27 是"时间到就兜底"，
        // 不是"超过才算"——旧口径写在 TimeoutFallback.kt 的 `waited <= windowMs`，本批已改 `waited < windowMs`。
        val at = TimeoutFallback.settle(
            PendingRuling("edge-at", raisedAtMs = t0, reversible = true),
            nowMs = t0 + TimeoutFallback.WINDOW_MS,
        )
        assertEquals("elapsed == threshold ⇒ 触发（到达即兜底）", TimeoutOutcome.TIMEOUT_REJECTED, at.outcome)
        assertTrue("到达即触发也必须落「待御裁」账", at.mustRecord)

        val before = TimeoutFallback.settle(
            PendingRuling("edge-before", raisedAtMs = t0, reversible = true),
            nowMs = t0 + TimeoutFallback.WINDOW_MS - 1,
        )
        assertEquals("差 1 毫秒仍是等待（触发点只在 >=）", TimeoutOutcome.WAITING, before.outcome)
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

        // ③ 已被超时结算过的事项再奏 ⇒ 必被拒 + 记违规（Q2 窄口径：结了就不再奏）
        val refiling = TimeoutFallback.settle(r(48, reversible = true, submissions = 2, settled = true, recorded = true), t0)
        assertEquals("结过案的第 2 次上奏必判被拒", TimeoutOutcome.REFILING_REJECTED, refiling.outcome)
        assertFalse("再奏被拒也不许放行", refiling.approved)
        assertTrue("再奏必记违规", refiling.violation != null)
        assertTrue("违规原文要点明「已结算」：${refiling.violation}", refiling.violation!!.contains("结算"))
        assertFalse("不许二次回退（上一轮已按原状回退过）", refiling.rollbackRequested)

        // ③b 没结算过、但累计到第 3 次 ⇒ 照样必拒（上限就是 2 次）
        val thirdSubmission = TimeoutFallback.settle(r(2, reversible = true, submissions = 3), t0)
        assertEquals("第 3 次上奏必判被拒", TimeoutOutcome.REFILING_REJECTED, thirdSubmission.outcome)
        assertTrue("违规原文要点明上限：${thirdSubmission.violation}", thirdSubmission.violation!!.contains("上限"))

        // ③c 没结算过的第 2 次、且确实超了窗口 ⇒ 正常按超时结算，不许错判成"重复上奏被拒"
        val secondExpired = TimeoutFallback.settle(r(48, reversible = true, submissions = 2), t0)
        assertEquals("第 2 次上奏超窗口就按超时结算", TimeoutOutcome.TIMEOUT_REJECTED, secondExpired.outcome)
        assertTrue("可回退者照旧按原状回退", secondExpired.rollbackRequested)

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
        assertEquals("同一事项上奏上限 2 次（第 3 次才拒）", 2, TimeoutFallback.MAX_SUBMISSIONS)

        // ⑦ 事实自相矛盾（说"结过案"却报第 1 次）⇒ 判据拒收，不许悄悄当成没结过案放行
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutFallback.settle(
                PendingRuling("case-4", raisedAtMs = t0, reversible = true, submissions = 1, timeoutSettled = true),
                t0,
            )
        }
    }
}
