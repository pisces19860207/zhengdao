// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 473 灰度的判据与负例（派工单 §五 判据③＋§二 四句）。
//
// 判据③拆成四问，一问一条测试：
//   · 缺省是不是关闭／灰度（不是全开）——这一条不许靠人记，`violations()` 会点名；
//   · 指标是不是**从账算出来的**（内存值手改不了它，因为根本没有入口收内存值）；
//   · 达标是不是**自动转全开**（§四：落账即可，不需要人逐个点）；
//   · 未达标是不是**转不上去**（连"把失败率填成 0"一并拒：档位行的读数只认账）。
// 负例：
//   C1 缺省写成全开　C2 拿内存计数当指标　C3 未达标却转全开　C4 模型自己转档
//   C5 机制把「关闭」的能力自己打开　C6 回灰判据不成立也落一条空变动
//   C7 机制自己把能力关掉（回关闭只许人拨）
package com.example.zhengdao.baopu.consequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsequenceCanaryGateTest {

    private val now = 1_760_000_000_000L
    private val day = 86_400_000L
    private val cap = CanaryCapability.ORCHESTRATION

    /**
     * 造样本：前 [runs] 条里前 `bad` 条算失败（`bad = max(fails, losses)`——
     * 一条"跑成功了但永久损失"的账不存在，硬造出来会让指标层看起来允许这种账）。
     */
    private fun samples(runs: Int, fails: Int, asks: Int, losses: Int = 0): List<CanarySample> {
        val bad = maxOf(fails, losses)
        return (0 until runs).map { i ->
            CanarySample(
                atMs = now - i * 1000L,
                ok = i >= bad,
                askedHuman = i < asks,
                permanentLoss = i < losses,
            )
        }
    }

    private fun m(runs: Int, fails: Int, asks: Int, losses: Int = 0): CanaryMetrics =
        CanaryMetrics.of(samples(runs, fails, asks, losses), now)

    private fun change(
        before: CanaryBand,
        to: CanaryBand,
        mm: CanaryMetrics,
        actor: String = "auto",
        trigger: String = CanaryLadder.TRIGGER_AUTO_PROMOTE,
    ) = CanaryChange(cap.wire, before.wire, to.wire, actor, now, mm, trigger)

    // ── ① 三档＋缺省 ──────────────────────────────────────────────────────

    @Test
    fun `判据3a_三档齐且缺省一律不是全开`() {
        assertEquals(3, CanaryBand.entries.size)
        assertTrue("负例C1：登记表里出现了缺省全开的能力 ⇒ ${CanaryRegistry.violations()}", CanaryRegistry.violations().isEmpty())
        CanaryRegistry.all.forEach { c ->
            assertTrue("${c.wire} 的缺省档必须是关闭或灰度", c.defaultBand == CanaryBand.OFF || c.defaultBand == CanaryBand.CANARY)
        }
    }

    @Test
    fun `判据3b_不在登记表里的能力一律不认`() {
        assertFalse(CanaryRegistry.knows("make_me_admin"))
        assertNull(CanaryCapability.ofWire("make_me_admin"))
        assertNull("线值认不出不许静默当某一档", CanaryBand.ofWire("super_on"))
        val r = CanaryLadder.refusalOf(CanaryChange("ghost", CanaryBand.OFF.wire, CanaryBand.CANARY.wire, "auto", now, m(12, 0, 0), "human"))
        assertNotNull(r)
        assertTrue(r!!.contains("没登记在册"))
    }

    // ── ② 指标从账本算 ────────────────────────────────────────────────────

    @Test
    fun `判据3c_指标只从样本算_没账长成没账的样子`() {
        val empty = CanaryMetrics.of(emptyList(), now)
        assertEquals("负例C2：没账必须是负数，不能是 0%", CanaryMetrics.UNKNOWN_PCT, empty.failRatePct, 0.0)
        assertEquals(CanaryMetrics.UNKNOWN_PCT, empty.interventionPct, 0.0)
        assertEquals(0, empty.runs)
        assertFalse("没账不许被读成「一条都没失败」", empty.known)
        assertFalse("没账不算达标", empty.passedForFull)
    }

    @Test
    fun `判据3d_样本不够什么都算不出`() {
        val thin = m(9, 0, 0)
        assertFalse(thin.known)
        assertEquals(CanaryBand.OFF, CanaryLadder.nextBand(CanaryBand.OFF, thin, 5))
        val r = CanaryLadder.refusalOf(change(CanaryBand.CANARY, CanaryBand.FULL, thin))
        assertNotNull("读数不够却要动档 ⇒ 必须拒", r)
        assertTrue(r!!.contains("算不出"))
    }

    @Test
    fun `判据3e_窗口外的账不压新账`() {
        val old = (0 until 20).map { CanarySample(atMs = now - 90 * day, ok = false, askedHuman = false) }
        val mm = CanaryMetrics.of(old, now)
        assertEquals("90 天前的账不许算进 14 天窗口", 0, mm.runs)
    }

    // ── ③ 达标自动转全开／未达标不许转 ─────────────────────────────────────

    @Test
    fun `判据3f_达标两轮自动转全开_不需要人点`() {
        val good = m(20, 0, 2) // 失败 0%／介入 10%
        assertTrue(good.passedForFull)
        assertEquals(CanaryBand.FULL, CanaryLadder.nextBand(CanaryBand.CANARY, good, CanaryLadder.PASS_ROUNDS_FOR_FULL))
        val c = change(CanaryBand.CANARY, CanaryBand.FULL, good, trigger = CanaryLadder.TRIGGER_AUTO_PROMOTE)
        assertNull(CanaryLadder.refusalOf(c))
        val d = ConsequenceLedger.draftOf(c)!!
        assertEquals("full", d.after)
        assertEquals("auto", d.actor.wire)
        assertTrue(d.evidence.startsWith("${ConsequenceEvidence.CANARY}${cap.wire}:full:"))
        assertNotNull(d.rollbackRef)
    }

    @Test
    fun `判据3g_一轮达标不转_连续两轮才算`() {
        val good = m(20, 0, 2)
        assertEquals(
            "一轮就转＝拿运气当依据",
            CanaryBand.CANARY,
            CanaryLadder.nextBand(CanaryBand.CANARY, good, CanaryLadder.PASS_ROUNDS_FOR_FULL - 1),
        )
    }

    @Test
    fun `判据3h_负例C3_未达标转全开被拒`() {
        val bad = m(20, 4, 2) // 失败 20% > 5%
        assertFalse(bad.passedForFull)
        val r = CanaryLadder.refusalOf(change(CanaryBand.CANARY, CanaryBand.FULL, bad))
        assertNotNull(r)
        assertTrue(r!!.contains("未达标不许转全开"))
        assertNull("被拒的档位不落草稿", ConsequenceLedger.draftOf(change(CanaryBand.CANARY, CanaryBand.FULL, bad)))
    }

    @Test
    fun `判据3i_有永久损失就不算达标`() {
        val withLoss = m(20, 0, 2, losses = 1)
        assertFalse(withLoss.passedForFull)
        assertNotNull(CanaryLadder.refusalOf(change(CanaryBand.CANARY, CanaryBand.FULL, withLoss)))
    }

    @Test
    fun `判据3j_负例C4_模型自调档位被拒`() {
        val good = m(20, 0, 0)
        val r = CanaryLadder.refusalOf(change(CanaryBand.CANARY, CanaryBand.FULL, good, actor = IdentityRules.ACTOR_MODEL))
        assertNotNull(r)
        assertTrue(r!!.contains("模型自调"))
    }

    @Test
    fun `判据3k_负例C6_档没动不落空变动`() {
        val good = m(20, 0, 0)
        val r = CanaryLadder.refusalOf(change(CanaryBand.CANARY, CanaryBand.CANARY, good))
        assertNotNull(r)
        assertTrue(r!!.contains("没动"))
    }

    // ── ④ 回灰：自动 ＋ 一键 ──────────────────────────────────────────────

    @Test
    fun `判据3l_坏消息自动回灰_触发条件写死`() {
        val over = m(20, 4, 2)
        assertEquals(CanaryBand.CANARY, CanaryLadder.nextBand(CanaryBand.FULL, over))
        assertEquals(CanaryBand.CANARY, CanaryLadder.revertBand(CanaryBand.FULL, over))
        val loss = m(20, 0, 0, losses = 1)
        assertEquals(CanaryBand.CANARY, CanaryLadder.revertBand(CanaryBand.FULL, loss))
        val r = CanaryLadder.refusalOf(
            change(CanaryBand.FULL, CanaryBand.CANARY, over, trigger = CanaryLadder.TRIGGER_AUTO_REVERT),
        )
        assertNull(r)
    }

    @Test
    fun `判据3m_已在灰度不报假回灰`() {
        val over = m(20, 4, 2)
        assertNull("把「已在灰度」报成「已回灰」＝多一条什么都没变的账", CanaryLadder.revertBand(CanaryBand.CANARY, over))
    }

    @Test
    fun `判据3n_一键回灰到灰度但不许一键到关闭`() {
        assertEquals(CanaryBand.CANARY, CanaryLadder.humanRevert(CanaryBand.FULL))
        assertEquals(CanaryBand.CANARY, CanaryLadder.humanRevert(CanaryBand.CANARY))
        assertNull(CanaryLadder.humanRevert(CanaryBand.OFF))
    }

    @Test
    fun `判据3o_负例C7_机制不许自己关掉能力`() {
        val over = m(20, 8, 2)
        val auto = CanaryLadder.refusalOf(change(CanaryBand.CANARY, CanaryBand.OFF, over, trigger = CanaryLadder.TRIGGER_AUTO_REVERT))
        assertNotNull(auto)
        assertTrue(auto!!.contains("只许人拨"))
        val human = CanaryLadder.refusalOf(
            change(CanaryBand.CANARY, CanaryBand.OFF, over, actor = "user", trigger = CanaryLadder.TRIGGER_HUMAN),
        )
        assertNull("人可以拨回关闭", human)
    }

    @Test
    fun `判据3p_负例C5_机制不许把关闭的能力自己打开`() {
        // 窗口里留着几条老坏账（曾经跑过），能力后来被人拨回关闭——坏消息不是开关。
        val bad = m(20, 8, 2)
        assertEquals(CanaryBand.OFF, CanaryLadder.nextBand(CanaryBand.OFF, bad, 5))
        val good = m(20, 0, 0)
        assertEquals(CanaryBand.OFF, CanaryLadder.nextBand(CanaryBand.OFF, good, 5))
    }

    @Test
    fun `读数_一句话里带次数与两个率`() {
        val s = CanaryLadder.readout(cap, m(20, 2, 4), CanaryBand.CANARY, CanaryBand.FULL)
        assertTrue(s, s.contains("20 条") && s.contains("灰度→全开"))
        assertEquals("无账", CanaryLadder.pct(CanaryMetrics.UNKNOWN_PCT))
    }
}
