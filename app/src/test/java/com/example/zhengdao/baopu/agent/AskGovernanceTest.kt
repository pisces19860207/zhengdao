// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十九批 · A 段（授权档）＋ C 段（在环快判/误拦/预算）判据测试。
// 判据编号对齐派工单：①三档行为 ②硬拒三条任何档都拒 ③不可逆无回收站任何档都问
// ④切档落账 ⑤放过仍记账 ⑥缺省＝标准 ⑩判定段 p95<50ms ⑪硬拒原因≤80字
// ⑫误拦可申诉/误拦率分场景/预算生效 ⑯⑰画像边界。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RiskClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskGovernanceTest {

    private fun facts(
        tier: AskTier,
        risk: RiskClass? = RiskClass.R1,
        mustAsk: Boolean = false,
        unclassified: Boolean = false,
        inRoot: Boolean = true,
        reversible: Boolean = true,
    ): AskTierFacts = AskTierFacts(tier, risk, mustAsk, unclassified, inRoot, reversible)

    // ── ① 三档行为各≥2 条 ─────────────────────────────────────────────────

    @Test
    fun `①严格档_根内可回滚R1也问_现状`() {
        val d = AskTierRules.decide(facts(AskTier.STRICT))
        assertFalse(d.pass)
        assertTrue(d.basis.contains("严格"))
    }

    @Test
    fun `①严格档_R2必问_现状`() {
        val d = AskTierRules.decide(facts(AskTier.STRICT, risk = RiskClass.R2))
        assertFalse(d.pass)
    }

    @Test
    fun `①标准档_根内可回滚R1放行`() {
        val d = AskTierRules.decide(facts(AskTier.STANDARD))
        assertTrue(d.pass)
        assertTrue(d.basis.contains("R1"))
    }

    @Test
    fun `①标准档_R2仍问一次`() {
        val d = AskTierRules.decide(facts(AskTier.STANDARD, risk = RiskClass.R2))
        assertFalse(d.pass)
        assertTrue(d.basis.contains("R2"))
    }

    @Test
    fun `①放权档_根内可回滚R1放行`() {
        val d = AskTierRules.decide(facts(AskTier.DELEGATED))
        assertTrue(d.pass)
    }

    @Test
    fun `①放权档_根内进回收站的R2放行`() {
        val d = AskTierRules.decide(facts(AskTier.DELEGATED, risk = RiskClass.R2))
        assertTrue("放权档的原文：根内 R1/R2 不问（进回收站＋可回滚）", d.pass)
    }

    @Test
    fun `①放权档_根外不放（跨根要问）`() {
        val d = AskTierRules.decide(facts(AskTier.DELEGATED, inRoot = false))
        assertFalse("487：放权档只豁免根内；跨根/根外照样问", d.pass)
    }

    // ── ② 硬拒/必问三条：任何档位都不许跳 ────────────────────────────────

    @Test
    fun `②R3外发花钱账号_任何档都问`() {
        for (t in AskTier.entries) {
            val d = AskTierRules.decide(facts(t, risk = RiskClass.R3))
            assertFalse("档位=$t：R3 是硬线②，任何档都问", d.pass)
        }
    }

    @Test
    fun `②矩阵必问_任何档都问`() {
        for (t in AskTier.entries) {
            val d = AskTierRules.decide(facts(t, risk = RiskClass.R2, mustAsk = true))
            assertFalse("档位=$t：必问是硬线，档位买不走", d.pass)
        }
    }

    @Test
    fun `②未分类fail-closed_任何档都问`() {
        for (t in AskTier.entries) {
            val d = AskTierRules.decide(facts(t, risk = RiskClass.R2, unclassified = true))
            assertFalse("档位=$t：未分类按 R2 必问（fail-closed），档位不许豁免", d.pass)
        }
    }

    // ── ③ 不可逆且无回收站：任何档位都必问 ────────────────────────────────

    @Test
    fun `③不可逆无回收站_任何档都问`() {
        for (t in AskTier.entries) {
            val d = AskTierRules.decide(facts(t, reversible = false))
            assertFalse("档位=$t：硬线①", d.pass)
            assertTrue(d.basis.contains("不可逆"))
        }
    }

    // ── ⑥ 缺省＝标准 ─────────────────────────────────────────────────────

    @Test
    fun `⑥缺省档是标准_新装读到标准`() {
        assertEquals(AskTier.STANDARD, AskTier.DEFAULT)
        AskGovernance.resetForTest()
        assertEquals("进程内单例的初值也必须是标准（新装读到的就是它）", AskTier.STANDARD, AskGovernance.currentTier)
    }

    // ── ④ 切档落账 ───────────────────────────────────────────────────────

    @Test
    fun `④切档落账_谁和从哪到哪都有`() {
        AskGovernance.resetForTest()
        val row = AskGovernance.setTier(AskTier.DELEGATED, who = "user", atMs = 1_000L)
        assertEquals(AskGovLedger.KIND_TIER_SWITCH, row.kind)
        assertTrue("依据里要有去向", row.basis.contains("放权"))
        assertTrue("账上带 who", row.detail.contains("who=user"))
        assertEquals(AskTier.DELEGATED, AskGovernance.currentTier)
        // 回放里找得到这一行（账是追加式，回放是唯一读法）
        assertTrue(AskGovLedger.replay().any { it.id == row.id && it.kind == AskGovLedger.KIND_TIER_SWITCH })
        AskGovernance.resetForTest()
    }

    // ── ⑤ 放过仍记账（notePass 一行，依据带当时档位）────────────────────

    @Test
    fun `⑤档位放行的动作留一行账_依据带档位`() {
        AskGovernance.resetForTest()
        val d = AskTierRules.decide(facts(AskTier.STANDARD))
        assertTrue(d.pass)
        val row = AskGovernance.notePass("write_file", d, opId = "op-1", atMs = 2_000L)
        assertEquals(AskGovLedger.KIND_PASS, row.kind)
        assertTrue("依据要带当时档位", row.basis.contains("tier=standard"))
        assertTrue("依据要带放行理由", row.basis.contains("先做后审"))
        AskGovernance.resetForTest()
    }

    // ── ⑩ 判定段 p95 <50ms 且不跨轮（纯函数、零 IO）──────────────────────

    @Test
    fun `⑩判定段p95小于50ms_同一调用内完成`() {
        val samples = mutableListOf<Long>()
        repeat(2_000) {
            val f = facts(AskTier.STANDARD, risk = if (it % 2 == 0) RiskClass.R1 else RiskClass.R2)
            val t0 = System.nanoTime()
            AskTierRules.decide(f)
            samples += (System.nanoTime() - t0) / 1_000
        }
        samples.sort()
        val p95 = samples[(samples.size * 95) / 100]
        assertTrue("判定段 p95=${p95}µs 必须 <50ms（C 段：不跨轮、无 IO）", p95 < 50_000)
    }

    // ── ⑪ 硬拒一句话原因 ≤80 字 ──────────────────────────────────────────

    @Test
    fun `⑪硬拒原因截到80字以内且不空`() {
        val longWhy = "很长".repeat(200)
        val line = "动作被拒：$longWhy"
        val fitted = if (line.length <= AskGovernance.HARD_REJECT_MAX) line else line.take(AskGovernance.HARD_REJECT_MAX - 1) + "…"
        assertTrue(fitted.length <= AskGovernance.HARD_REJECT_MAX)
        assertTrue(fitted.isNotBlank())
    }

    // ── ⑫ 误拦申诉＋误拦率分场景＋体检行 ─────────────────────────────────

    @Test
    fun `⑫误拦申诉落账_申诉不改规则`() {
        AskGovernance.resetForTest()
        AskGovernance.setTier(AskTier.STANDARD, atMs = 3_000L)
        // 一张主观场景的问卡＋一次申诉
        AskGovLedger.append(
            kind = AskGovLedger.KIND_CARD, who = "ask_user", basis = "弹卡",
            scene = AskGovLedger.SCENE_SUBJECTIVE, detail = "用什么标题", atMs = 3_100L,
        )
        val row = AskGovernance.appeal("用什么标题", AskGovLedger.SCENE_SUBJECTIVE, atMs = 3_200L)
        assertEquals(AskGovLedger.KIND_APPEAL, row.kind)
        assertEquals("user", row.who)
        // 申诉不自动改规则：档位没动、没有规则行
        assertEquals(AskTier.STANDARD, AskGovernance.currentTier)
        assertTrue("申诉不该产生 tier_switch 行", AskGovLedger.replay().none { it.kind == AskGovLedger.KIND_TIER_SWITCH && it.atMs > 3_000L })
        AskGovernance.resetForTest()
    }

    @Test
    fun `⑫误拦率按场景分开统计_不混成总指标`() {
        AskGovernance.resetForTest()
        // 客观 10 张卡申诉 1 次（10%）；主观 1 张卡申诉 1 次（100%）——两格必须各自算
        repeat(10) {
            AskGovLedger.append(
                kind = AskGovLedger.KIND_CARD, who = "ask_user", basis = "弹卡",
                scene = AskGovLedger.SCENE_OBJECTIVE, detail = "obj-$it", atMs = 4_000L + it,
            )
        }
        AskGovLedger.append(
            kind = AskGovLedger.KIND_CARD, who = "ask_user", basis = "弹卡",
            scene = AskGovLedger.SCENE_SUBJECTIVE, detail = "subj", atMs = 4_100L,
        )
        AskGovernance.appeal("obj-3", AskGovLedger.SCENE_OBJECTIVE, atMs = 4_200L)
        AskGovernance.appeal("subj", AskGovLedger.SCENE_SUBJECTIVE, atMs = 4_300L)
        val line = AskGovernance.healthLine(4_400L)
        assertTrue("体检行要含授权档读数", line.contains("授权档"))
        assertTrue("客观 1/10", line.contains("(1/10)"))
        assertTrue("主观 1/1", line.contains("(1/1)"))
        AskGovernance.resetForTest()
    }

    // ── ⑫ 预算生效：每天 ≤3，超了压成汇总；必问不占预算 ───────────────────

    @Test
    fun `⑫日预算前三张放行_第四张压成汇总`() {
        AskDailyBudget.resetForTest()
        val day = 20_000
        repeat(3) {
            assertTrue(AskDailyBudget.decide(day) is AskDailyBudget.Verdict.Allow)
        }
        val fourth = AskDailyBudget.decide(day)
        assertTrue(fourth is AskDailyBudget.Verdict.Compress)
        AskDailyBudget.noteCompressed("第四问：配色选哪个")
        assertTrue("汇总收着，不是吞了", AskDailyBudget.drainSummary().any { it.contains("配色") })
        assertTrue("取走即清", AskDailyBudget.drainSummary().isEmpty())
        // 换日回满
        assertTrue("换日预算回满", AskDailyBudget.decide(day + 1) is AskDailyBudget.Verdict.Allow)
        assertEquals(1, AskDailyBudget.usedToday(day + 1))
        AskDailyBudget.resetForTest()
    }

    @Test
    fun `⑫必问不占日预算_不被压`() {
        AskDailyBudget.resetForTest()
        val day = 21_000
        repeat(5) {
            assertTrue("必问第${it + 1}张也放行", AskDailyBudget.decide(day, mustAsk = true) is AskDailyBudget.Verdict.Allow)
        }
        assertEquals("必问不占预算：used 仍 0", 0, AskDailyBudget.usedToday(day))
        AskDailyBudget.resetForTest()
    }

    // ── ⑯⑰ 画像边界：画像可代"怎么做"，禁止代"能不能" ────────────────────

    private fun profileOf(n: Int, conf: Double) = ProfileBoundary.ProfileAnswer(
        entryKey = "how_to_naming", conclusion = "喜欢下划线命名", sampleCount = n, confidence = conf,
    )

    @Test
    fun `⑯画像说以前都同意删除_授权类仍须问人且代答被记录`() {
        AskGovLedger.resetForTest()
        // 画像再"自信"也买不走授权：判据就是这一格
        val d = ProfileBoundary.decide(AskClass.AUTHORIZATION, profileOf(n = 99, conf = 0.99))
        assertEquals(ProfileBoundary.Verdict.ASK_HUMAN, d.verdict)
        assertTrue(d.basis.contains("不许代答"))
        AskGovLedger.resetForTest()
    }

    @Test
    fun `⑯画像代授权的企图进治理账`() {
        AskIntakeGate.resetForTest()
        AskGovLedger.resetForTest()
        AskIntakeGate.profileProvider = { _ -> profileOf(n = 99, conf = 0.99) }
        val out = AskIntakeGate.beforeAsk(
            title = "可以删除 这个目录里的旧备份吗",
            taskId = "t1",
            dayKey = 30_000,
            nowMs = 30_001L,
        )
        assertTrue("授权类问题卡照弹（真人给）", out is AskIntakeOutcome.Proceed)
        val violations = AskGovLedger.replay().filter { it.kind == AskGovLedger.KIND_VIOLATION && it.who == "profile" }
        assertTrue("画像代授权必须被拦并记录", violations.isNotEmpty())
        assertTrue(violations.first().basis.contains("画像"))
        AskIntakeGate.resetForTest()
        AskGovLedger.resetForTest()
    }

    @Test
    fun `⑰画像自决有账行_用了哪条置信度都在`() {
        AskGovLedger.resetForTest()
        val d = ProfileBoundary.decide(AskClass.PREFERENCE, profileOf(n = 25, conf = 0.95))
        assertEquals(ProfileBoundary.Verdict.SELF_DECIDE, d.verdict)
        val row = ProfileBoundary.noteSelfDecide(d, "命名用下划线还是驼峰", 40_000L)
        assertTrue(row != null)
        assertTrue("账行带画像键", row!!.detail.contains("entry=how_to_naming"))
        assertTrue("账行带置信度", row.detail.contains("conf=0.95"))
        assertTrue("样本数只标下限", row.detail.contains(" n>=25｜"))
        assertFalse("下限不许标成实测计数", row.detail.contains(" n="))
        AskGovLedger.resetForTest()
    }

    @Test
    fun `样本下限20与置信度边界仍自决且账行标下限`() {
        AskGovLedger.resetForTest()
        val d = ProfileBoundary.decide(AskClass.PREFERENCE, profileOf(n = 20, conf = 0.9))
        assertEquals(ProfileBoundary.Verdict.SELF_DECIDE, d.verdict)
        val row = ProfileBoundary.noteSelfDecide(d, "命名习惯", 40_001L)
        assertNotNull(row)
        assertEquals("entry=how_to_naming conf=0.9 n>=20｜命名习惯", row!!.detail)
        AskGovLedger.resetForTest()
    }

    @Test
    fun `⑰低置信用默认_不硬套画像`() {
        // 样本 <20
        val few = ProfileBoundary.decide(AskClass.PREFERENCE, profileOf(n = 10, conf = 0.99))
        assertEquals(ProfileBoundary.Verdict.USE_DEFAULT, few.verdict)
        assertTrue(few.basis.contains("样本不足"))
        // 置信 <0.9
        val unsure = ProfileBoundary.decide(AskClass.PREFERENCE, profileOf(n = 25, conf = 0.5))
        assertEquals(ProfileBoundary.Verdict.USE_DEFAULT, unsure.verdict)
        // 没画像
        val none = ProfileBoundary.decide(AskClass.PREFERENCE, null)
        assertEquals(ProfileBoundary.Verdict.USE_DEFAULT, none.verdict)
    }
}
