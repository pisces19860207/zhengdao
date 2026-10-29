// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// ⚠️ 本文件是**规范（spec）**，不是门禁：生产未接线（#271-L24），它**不会红**。
//    它钉的是「判据长什么样」；接线那天把判据搬进生产、用例名不变，它才升格为门禁。
//
// 第二百七十一批 · 法典规范（spec，非门禁） **L24 累进罚则：可再一再二，绝不可过三**（法典卷七 L24）。
//
// ## 判据（可执行）
// 同 `kind` 同违规类型、**30 天窗口**内累加：
//   第 1 次 ⇒ **记**（落 208，不打扰）；第 2 次 ⇒ **问**（弹卡给两个选项）；
//   第 3 次 ⇒ **拦**（拒绝执行，须御批才解）；绕道第 4 次 ⇒ **停**（熔断本次会话）。
// 两条减免：**主动报账减一档**（自首）；**证据不足不罚只记**（疑罪从无，且不计入累进）。
// 30 天无同类 ⇒ 计数归零（记录仍永久留档）。
//
// ## 生产状态（诚实标注）——**本文件是规范，不是门禁；生产未接线（#271-L24），它不会红**
// 罚则引擎**还没落进生产码**（`app/src/main` 里没有违规计数与分档）⇒ 本条门禁钉的是
// **判据形状**（policy-as-code），缺陷 #271-L24 在账；接线时把 [Penalty] 原样搬进生产、
// 把 `decide` 的调用点接在 `GuardPipeline` 拒绝分支上，用例名不变。
//
// ## 负例（造违规即红——红的是**判据**，不是生产：生产里还没有它）
// ① 第 3 次若只是"问"（没拦）⇒ 红；② 第 4 次若只是"拦"（没熔断）⇒ 红；
// ③ 自首若没减档 / 证据不足若被罚 ⇒ 红。
package com.example.zhengdao.law.spec


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class L24ProgressivePenaltySpec {

    internal enum class Tier { RECORD, ASK, BLOCK, FUSE }

    internal data class Violation(
        val kind: String,
        val atMs: Long,
        /** 主动报账（自首）——减一档。 */
        val selfReported: Boolean = false,
        /** 证据是否够（不够 ⇒ 只记，不罚、不计入累进）。 */
        val evidenceSufficient: Boolean = true,
    )

    internal data class Decision(val tier: Tier, val effectiveCount: Int, val reason: String)

    internal object Penalty {

        /** 累进窗口：30 天。 */
        const val WINDOW_MS = 30L * 24 * 3600 * 1000

        fun decide(kind: String, history: List<Violation>, nowMs: Long, fresh: Violation): Decision {
            // 疑罪从无：证据不足不罚只记，且**不计入**累进（否则"没证据也能攒次数"）
            if (!fresh.evidenceSufficient) {
                val kept = history.count { it.kind == kind && inWindow(it, nowMs) }
                return Decision(Tier.RECORD, kept, "证据不足：只记一条，不罚、不计入累进（疑罪从无）")
            }
            val prior = history.count { it.kind == kind && inWindow(it, nowMs) && it.evidenceSufficient }
            val n = prior + 1
            val base = when {
                n <= 1 -> Tier.RECORD
                n == 2 -> Tier.ASK
                n == 3 -> Tier.BLOCK
                else -> Tier.FUSE
            }
            // 自首减一档（FUSE→BLOCK，BLOCK→ASK，ASK→RECORD，RECORD 仍是 RECORD）
            val tier = if (fresh.selfReported) down(base) else base
            return Decision(tier, n, reasonOf(tier, n, fresh.selfReported))
        }

        private fun inWindow(v: Violation, nowMs: Long): Boolean = nowMs - v.atMs <= WINDOW_MS

        private fun down(t: Tier): Tier = when (t) {
            Tier.FUSE -> Tier.BLOCK
            Tier.BLOCK -> Tier.ASK
            Tier.ASK -> Tier.RECORD
            Tier.RECORD -> Tier.RECORD
        }

        private fun reasonOf(t: Tier, n: Int, self: Boolean): String {
            val tail = if (self) "（主动报账，减一档）" else ""
            return when (t) {
                Tier.RECORD -> "第 ${n} 次：记一条，不打扰$tail"
                Tier.ASK -> "第 ${n} 次：弹卡问一次，给两个选项$tail"
                Tier.BLOCK -> "第 ${n} 次：拦下不执行，须御批才解$tail"
                Tier.FUSE -> "第 ${n} 次（绕道再犯）：熔断本次会话，保留现场供御批$tail"
            }
        }
    }

    private val t0 = 1_800_000_000_000L
    private val day = 24L * 3600 * 1000

    private fun v(kind: String, daysAgo: Long, self: Boolean = false, evidence: Boolean = true) =
        Violation(kind, t0 - daysAgo * day, self, evidence)

    // ── 正例：一记二问三拦四熔断 ────────────────────────────────────────────

    @Test
    fun `L24_正例_一记二问三拦四熔断_窗口外归零`() {
        val kind = "改原件区"
        assertEquals("第 1 次只记", Tier.RECORD, Penalty.decide(kind, emptyList(), t0, v(kind, 0)).tier)

        val h1 = listOf(v(kind, 10))
        assertEquals("第 2 次问", Tier.ASK, Penalty.decide(kind, h1, t0, v(kind, 0)).tier)

        val h2 = listOf(v(kind, 10), v(kind, 5))
        assertEquals("第 3 次必拦", Tier.BLOCK, Penalty.decide(kind, h2, t0, v(kind, 0)).tier)

        val h3 = listOf(v(kind, 10), v(kind, 5), v(kind, 1))
        val fused = Penalty.decide(kind, h3, t0, v(kind, 0))
        assertEquals("绕道第 4 次必熔断", Tier.FUSE, fused.tier)
        assertTrue("熔断要说清怎么解", fused.reason.contains("御批"))

        // 30 天窗口外 ⇒ 计数归零（旧账不回算）
        val old = listOf(v(kind, 31), v(kind, 40), v(kind, 50))
        assertEquals("窗口外的旧账不该继续累进", Tier.RECORD, Penalty.decide(kind, old, t0, v(kind, 0)).tier)
        // 不同 kind 各算各的（法不责众：同一违规多处出现按一处计，不同类不叠加）
        val other = listOf(v("另一个 kind", 1), v("另一个 kind", 2), v("另一个 kind", 3))
        assertEquals("不同 kind 不许串味", Tier.RECORD, Penalty.decide(kind, other, t0, v(kind, 0)).tier)
    }

    // ── 负例①：第 3 次没拦 / 第 4 次没熔断 ─────────────────────────────────

    @Test
    fun `L24_负例1_第三次必须拦_第四次必须熔断`() {
        val kind = "外发敏感载荷"
        val h2 = listOf(v(kind, 9), v(kind, 3))
        val third = Penalty.decide(kind, h2, t0, v(kind, 0))
        assertEquals("第 3 次只问到不拦＝「过三」了：${third.reason}", Tier.BLOCK, third.tier)
        assertEquals("有效次数必须数到 3", 3, third.effectiveCount)
        assertTrue("拦截要给可读原因", third.reason.contains("第 3 次"))
        assertTrue("拦截要说怎么解", third.reason.contains("御批"))

        val h3 = h2 + v(kind, 1)
        assertEquals(
            "绕道第 4 次只是拦、没熔断 ⇒ 红",
            Tier.FUSE,
            Penalty.decide(kind, h3, t0, v(kind, 0)).tier,
        )
    }

    // ── 负例②：自首没减档 / 证据不足被罚 ───────────────────────────────────

    @Test
    fun `L24_负例2_自首必须减一档_证据不足不许罚`() {
        val kind = "重复写同一文件"
        val h2 = listOf(v(kind, 9), v(kind, 3))
        val selfReported = Penalty.decide(kind, h2, t0, v(kind, 0, self = true))
        assertEquals("第 3 次主动报账 ⇒ 减一档到「问」", Tier.ASK, selfReported.tier)
        assertTrue("理由要写明减档", selfReported.reason.contains("主动报账"))

        val h3 = h2 + v(kind, 1)
        assertEquals(
            "第 4 次自首 ⇒ 减到「拦」（不是直接放过）",
            Tier.BLOCK,
            Penalty.decide(kind, h3, t0, v(kind, 0, self = true)).tier,
        )

        // 疑罪从无：证据不足只记，不升级、也不计入累进
        val noEvidence = Penalty.decide(kind, h3, t0, v(kind, 0, evidence = false))
        assertEquals("证据不足不罚，只记", Tier.RECORD, noEvidence.tier)
        assertEquals("证据不足不计入累进（次数保持 3）", 3, noEvidence.effectiveCount)
        assertTrue("理由要写明疑罪从无", noEvidence.reason.contains("疑罪从无"))

        // 证据不足的那次不许给下一次攒次数：1 条有效 + 2 条无证据 ⇒ 下一次仍是第 2 次（问）
        val mixed = listOf(v(kind, 9), v(kind, 5, evidence = false), v(kind, 4, evidence = false))
        val next = Penalty.decide(kind, mixed, t0, v(kind, 0))
        assertEquals("无证据的两次若被算进累进，这里会提前变成「拦」", 2, next.effectiveCount)
        assertEquals("第 2 次只能问", Tier.ASK, next.tier)
    }
}
