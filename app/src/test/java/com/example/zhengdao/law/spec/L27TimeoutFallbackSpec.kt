// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// ⚠️ 本文件是**规范（spec）**，不是门禁：生产未接线（#271-L27），它**不会红**。
//    它钉的是「判据长什么样」；接线那天把判据搬进生产、用例名不变，它才升格为门禁。
//
// 第二百七十一批 · 法典规范（spec，非门禁） **L27 超时保底**（法典卷九 L27）。
//
// ## 判据（可执行）
// 御案等待超过窗口（默认 24h）⇒ **视为不批**、**维持原状**；可回退者按原状回退；
// **必须落一条账**（含"未批"结论）。**同一事项不得因超时反复上奏**（最多一次）——
// 二次上奏 ⇒ 记违规。
//
// 为什么超时必须算"不批"而不是"默认同意"：默认同意等于"人不看就自动过"，
// 那 L20 的御批就白设了；默认不批，最坏结果是这件事没做，而不是做错。
//
// ## 生产状态（诚实标注）——**本文件是规范，不是门禁；生产未接线（#271-L27），它不会红**
// 御案超时保底还没落进生产 ⇒ 判据形状规范，缺陷 #271-L27 在账。
//
// ## 负例（造违规即红——红的是**判据**，不是生产：生产里还没有它）
// ① 超时却不落账 ⇒ 红；② 同一事项二次上奏 ⇒ 红；③ 超时被当成"同意" ⇒ 红。
package com.example.zhengdao.law.spec


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L27TimeoutFallbackSpec {

    internal data class Settlement(
        val approved: Boolean,
        val keptOriginal: Boolean,
        val needsLedger: Boolean,
        val violation: String?,
    )

    internal object Timeout {

        /** 等待窗口：24 小时（写死可配）。 */
        const val WINDOW_MS = 24L * 3600 * 1000

        /** 同一事项允许的上奏次数（超时后最多再一次）。 */
        const val MAX_SUBMITS = 1

        /**
         * @param raisedAtMs 这次上奏的时刻
         * @param nowMs 现在
         * @param ledgerHasRow 208 里已经有这次"未批"的账了吗
         * @param submitsSoFar 这个事项**已经**上奏过几次（含这一次）
         */
        fun settle(
            raisedAtMs: Long,
            nowMs: Long,
            ledgerHasRow: Boolean,
            submitsSoFar: Int = 1,
        ): Settlement {
            if (submitsSoFar > MAX_SUBMITS) {
                return Settlement(
                    approved = false,
                    keptOriginal = true,
                    needsLedger = true,
                    violation = "同一事项二次上奏（第 $submitsSoFar 次）：超时不许反复上奏，记一次违规",
                )
            }
            val expired = nowMs - raisedAtMs > WINDOW_MS
            if (!expired) {
                return Settlement(approved = false, keptOriginal = false, needsLedger = false, violation = null)
            }
            return Settlement(
                approved = false,
                keptOriginal = true,
                needsLedger = true,
                violation = if (ledgerHasRow) null else "超时未落账：必须记一条「待御裁（未批）」，不许静默作废",
            )
        }
    }

    private val t0 = 1_800_000_000_000L
    private val hour = 3600_000L

    // ── 正例：窗口内继续等；超时视为不批且落账 ─────────────────────────────

    @Test
    fun `L27_正例_窗口内等_超时视为不批并落账维持原状`() {
        val waiting = Timeout.settle(raisedAtMs = t0, nowMs = t0 + hour, ledgerHasRow = false)
        assertFalse("窗口内不许当成超时", waiting.keptOriginal)
        assertEquals("窗口内没有违规", null, waiting.violation)

        val expired = Timeout.settle(raisedAtMs = t0, nowMs = t0 + 24 * hour + 1, ledgerHasRow = true)
        assertFalse("超时视为不批（不是默认同意）", expired.approved)
        assertTrue("超时必须维持原状", expired.keptOriginal)
        assertTrue("超时必须落账", expired.needsLedger)
        assertEquals("落了账就没有违规", null, expired.violation)
    }

    // ── 负例①：超时不落账 ─────────────────────────────────────────────────

    @Test
    fun `L27_负例1_超时不落账必须记违规`() {
        val silent = Timeout.settle(raisedAtMs = t0, nowMs = t0 + 30 * hour, ledgerHasRow = false)
        assertTrue("超时没落账 ⇒ 静默作废，必须记违规", silent.violation != null)
        assertTrue("理由要点明落账：${silent.violation}", silent.violation!!.contains("落账"))
        assertFalse("超时永远不许当成同意", silent.approved)
    }

    // ── 负例②：同一事项二次上奏 ───────────────────────────────────────────

    @Test
    fun `L27_负例2_同一事项二次上奏必须记违规`() {
        val second = Timeout.settle(raisedAtMs = t0, nowMs = t0 + 48 * hour, ledgerHasRow = true, submitsSoFar = 2)
        assertTrue("二次上奏必须记违规", second.violation != null)
        assertTrue("理由要点明反复上奏：${second.violation}", second.violation!!.contains("上奏"))
        assertFalse("二次上奏也不许放行", second.approved)
        // 第一次不算违规（防「一次就报」的假门禁）
        assertEquals(
            "第一次上奏不该被判违规",
            null,
            Timeout.settle(raisedAtMs = t0, nowMs = t0 + hour, ledgerHasRow = false, submitsSoFar = 1).violation,
        )
    }
}
