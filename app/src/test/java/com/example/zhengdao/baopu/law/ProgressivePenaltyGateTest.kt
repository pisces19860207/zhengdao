// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十五批 · 法典卷七 **L24 累进罚则**的门禁测试。
//
// 这一条是**门禁**，不是规范：它调的是生产码 [ProgressivePenalty]（`app/src/main/.../baopu/law/`），
// 生产判错它就红。与 271 批（分支 `feat/law-gates`，未合并）那份自带 `Penalty` 对象的
// 测试侧 spec 的区别正在这里：本文件不定义任何判据，只消费 `baopu/law/` 里的生产判据。
//
// 覆盖判据（法典 L24 原文逐条）：一记二问三拦四熔断／主动报账减一档／疑罪从无只记不罚且不计累进／
// 30 天窗口外归零／同 kind 才累进／窗口是参数（改窗口 ⇒ 判定变，不许写死在实现里当玄学）。
package com.example.zhengdao.baopu.law

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressivePenaltyGateTest {

    private val t0 = 1_800_000_000_000L
    private val day = 24L * 3600 * 1000
    private val week = 7L * 24 * 3600 * 1000

    private fun v(kind: String, daysAgo: Long, self: Boolean = false, evidence: Boolean = true) =
        Violation(kind = kind, atMs = t0 - daysAgo * day, selfReported = self, evidenceSufficient = evidence)

    // ── 正例：四档 + 三条修正，全走生产判定 ────────────────────────────────

    @Test
    fun `L24_正例_一记二问三拦四熔断_自首减档_疑罪从无_窗口归零`() {
        val kind = "改原件区"

        val first = ProgressivePenalty.decide(v(kind, 0), emptyList(), t0)
        assertEquals("第 1 次只记", PenaltyTier.RECORD, first.tier)
        assertEquals("第 1 次有效次数是 1", 1, first.effectiveCount)
        assertFalse("「记」不许打扰用户（法典：落 208，不打扰）", first.disturb)

        val second = ProgressivePenalty.decide(v(kind, 0), listOf(v(kind, 10)), t0)
        assertEquals("第 2 次问", PenaltyTier.ASK, second.tier)
        assertTrue("「问」要出声", second.disturb)
        assertTrue("问的卡要给两个选项：${second.reason}", second.reason.contains("两个选项"))

        val third = ProgressivePenalty.decide(v(kind, 0), listOf(v(kind, 10), v(kind, 5)), t0)
        assertEquals("第 3 次拦", PenaltyTier.BLOCK, third.tier)
        assertTrue("拦必须写清须御批才解：${third.howToLift}", third.howToLift.contains("御批"))
        assertFalse("第 3 次只熔断到拦，不该熔会话", third.haltSession)

        val fourth = ProgressivePenalty.decide(v(kind, 0), listOf(v(kind, 10), v(kind, 5), v(kind, 1)), t0)
        assertEquals("绕道第 4 次熔断", PenaltyTier.FUSE, fourth.tier)
        assertTrue("熔断要熔", fourth.haltSession)
        assertTrue("熔断要保留现场供御批：${fourth.reason}", fourth.reason.contains("御批"))

        // 30 天无同类 ⇒ 计数归零（旧账不回算）
        val zeroed = ProgressivePenalty.decide(v(kind, 0), listOf(v(kind, 31), v(kind, 40), v(kind, 50)), t0)
        assertEquals("窗口外的旧账不该继续累进", PenaltyTier.RECORD, zeroed.tier)
        assertEquals("归零的是计数：窗口外三条一条都不算", 1, zeroed.effectiveCount)

        // 不同 kind 各算各的（同 kind 才累进）
        val others = listOf(v("另一个 kind", 1), v("另一个 kind", 2), v("另一个 kind", 3))
        val isolated = ProgressivePenalty.decide(v(kind, 0), others, t0)
        assertEquals("不同 kind 不许串味", PenaltyTier.RECORD, isolated.tier)

        // 主动报账减一档：第 3 次自首 ⇒ 降到「问」（不是直接放过）
        val selfThird = ProgressivePenalty.decide(
            v(kind, 0, self = true),
            listOf(v(kind, 9), v(kind, 3)),
            t0,
        )
        assertEquals("第 3 次自首减一档到问", PenaltyTier.ASK, selfThird.tier)
        assertTrue("理由要写明减档：${selfThird.reason}", selfThird.reason.contains("主动报账"))

        // 疑罪从无：证据不足只记、不罚、且不给下一次攒次数
        val noEvidence = ProgressivePenalty.decide(
            v(kind, 0, evidence = false),
            listOf(v(kind, 10), v(kind, 5), v(kind, 1)),
            t0,
        )
        assertEquals("证据不足不罚只记", PenaltyTier.RECORD, noEvidence.tier)
        assertFalse("证据不足这次不计入累进", noEvidence.counted)
        assertEquals("有效次数仍是历史里那 3 条有据的", 3, noEvidence.effectiveCount)
        assertTrue("理由要写明疑罪从无：${noEvidence.reason}", noEvidence.reason.contains("疑罪从无"))

        // 无证据那两次不许给下一次攒次数：1 条有据 + 2 条无据 ⇒ 本次仍是第 2 次（问）
        val mixed = listOf(v(kind, 9), v(kind, 5, evidence = false), v(kind, 4, evidence = false))
        val next = ProgressivePenalty.decide(v(kind, 0), mixed, t0)
        assertEquals("无证据的条次若被算进累进，这里会提前变成拦", 2, next.effectiveCount)
        assertEquals("第 2 次只能问", PenaltyTier.ASK, next.tier)
    }

    // ── 负例：过三／熔而不断／减过头／窗口写死，四种错法都要红 ──────────────

    @Test
    fun `L24_负例_第三次不许放行_第四次不许只拦_自首减到底不许没这一条_窗口是参数`() {
        val kind = "外发敏感载荷"

        // ① 第三次若只是"问"（＝放行了）⇒ 红
        val third = ProgressivePenalty.decide(v(kind, 0), listOf(v(kind, 9), v(kind, 3)), t0)
        assertEquals("第 3 次只问到不拦＝「过三」了：${third.reason}", PenaltyTier.BLOCK, third.tier)
        assertTrue("拦就是不许执行：${third.tier}", third.requiresImperialApproval)

        // ② 第四次若只是"拦"（没熔断）⇒ 红
        val fourth = ProgressivePenalty.decide(v(kind, 0), listOf(v(kind, 9), v(kind, 3), v(kind, 1)), t0)
        assertEquals("绕道第 4 次只是拦、没熔断 ⇒ 红", PenaltyTier.FUSE, fourth.tier)
        assertTrue("第 4 次的须御批不许丢", fourth.requiresImperialApproval)

        // ③ 自首只减一档，不许减成"没这一条"：第 2 次自首 ⇒ 降到记，但档位仍存在
        val selfSecond = ProgressivePenalty.decide(v(kind, 0, self = true), listOf(v(kind, 9)), t0)
        assertEquals("第 2 次自首减到记", PenaltyTier.RECORD, selfSecond.tier)
        assertTrue("减到底也还是一条记录（次数照算）：${selfSecond.effectiveCount}", selfSecond.effectiveCount == 2)
        assertTrue("有效次数不许被自首抹掉", selfSecond.counted)

        // ④ 窗口是参数：把窗口换成 7 天，10 天前那条就不该再作数（第 2 次退回第 1 次）
        val shortWindow = ProgressivePenalty.decide(v(kind, 0), listOf(v(kind, 10)), t0, windowMs = week)
        assertEquals("窗口改短 ⇒ 判定必须跟着变（窗口不是写在实现里的玄学）", PenaltyTier.RECORD, shortWindow.tier)
        val defaultWindow = ProgressivePenalty.decide(v(kind, 0), listOf(v(kind, 10)), t0)
        assertEquals("同一条历史在默认 30 天窗口内 ⇒ 仍是第 2 次（问）", PenaltyTier.ASK, defaultWindow.tier)

        // ⑤ 未来的账不参与窗口（时钟回拨／填错时刻不许提前把累进顶满）
        val futureLedger = listOf(v(kind, 0).copy(atMs = t0 + 10 * day), v(kind, 0).copy(atMs = t0 + 5 * day))
        val notYet = ProgressivePenalty.decide(v(kind, 0), futureLedger, t0)
        assertEquals("未来的两条不许提前顶满累进", PenaltyTier.RECORD, notYet.tier)
    }
}
