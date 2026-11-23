// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 判据①：三条链各一个**真跑通**用例（只有结构没有跑通 ⇒ 红）。
//
// 「真跑通」在本文件里的意思：从上游包（657 求解器 / 658 求解器 / 728 档 / 725 档）出发，
// 经本包的链构造器生成事件 → 回放 → 投影，断言**下游档确实变了**，且时间不够时**确实不变**。
package com.example.zhengdao.baopu.state.causal

import com.example.zhengdao.baopu.state.injury.InjuryGrade
import com.example.zhengdao.baopu.state.injury.PainGrade
import com.example.zhengdao.baopu.state.physiology.Fatigue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalChainRunTest {

    // ── 链① 受伤 → 步态改变 → 后续受限 ────────────────────────────────────────

    @Test
    fun `链一_受伤推出步态与受限且断链检查为零`() {
        val log = CausalScenarios.injuryChain()
        assertEquals("链①三站", 3, log.events.size)
        assertTrue("链①不该有断链", CausalChecks.chainIssues(log, CausalChains.INJURY).isEmpty())

        val state = CausalReplay.of(log).at(Span825.MINUTES)
        assertEquals("伤情落到活动受限档（728 level 3）", InjuryGrade.LIMITED.level, state.level(CausalDim.SITE_INJURY))
        assertEquals("疼痛档取自 728", PainGrade.STEADY.level, state.level(CausalDim.SENSE_PAIN))
        assertEquals("患侧关节角被推到第 5 档", 5, state.level(CausalDim.POSE_JOINT))
        assertEquals("重心掉到第 1 档", 1, state.level(CausalDim.POSE_COM))
    }

    @Test
    fun `链一_轻伤也照样推受限_门槛是常态加一档`() {
        val strike = InjuryChain.strike(
            eventId = "m1", at = 0, site = SiteRef.Part(com.example.zhengdao.baopu.state.bodyparts.BodyPart.THIGH),
            mechanism = InjuryMechanism.FRICTION, intensity = 1,
            injury = InjuryGrade.COLOR, pain = PainGrade.TOUCH, healSpan = Span825.OVERNIGHT,
        )
        val gait = InjuryChain.gaitAfter(strike, at = 1)
        assertEquals("轻伤把关节角推到第 4 档", 4, levelOf(gait.immediate, CausalDim.POSE_JOINT))
        assertEquals("重心跟着掉一档", 2, levelOf(gait.immediate, CausalDim.POSE_COM))
        assertNotNull("关节角到 4 档就该受限（门槛＝常态 L3 ＋ 1）", InjuryChain.restrictAfter(gait, at = 2, healSpan = Span825.OVERNIGHT))
    }

    @Test
    fun `链一_跨日之后伤情与疼痛都回到无`() {
        val state = CausalReplay.of(CausalScenarios.injuryChain()).at(Span825.CROSS_DAY)
        assertEquals("伤情回零档", InjuryGrade.NONE.level, state.level(CausalDim.SITE_INJURY))
        assertEquals("疼痛回零档", PainGrade.NONE.level, state.level(CausalDim.SENSE_PAIN))
    }

    @Test
    fun `链一_时间不够不许宣称已恢复`() {
        val replay = CausalReplay.of(CausalScenarios.injuryChain())
        val early = replay.at(Span825.MINUTES)
        assertNotNull("分钟级还有三条滞后项没到期", early.recoveryClaimIssue(CausalDim.SITE_INJURY))
        assertTrue("滞后项至少三条", early.pending.size >= 3)
        assertNull("跨日后才算结算完", replay.at(Span825.CROSS_DAY).recoveryClaimIssue(CausalDim.SITE_INJURY))
    }

    @Test
    fun `链一_每一步都能回放到那一条事件`() {
        val replay = CausalReplay.of(CausalScenarios.injuryChain())
        assertEquals("回放到受伤只有伤情与疼痛", null, replay.toEvent("i1").level(CausalDim.POSE_JOINT))
        assertEquals("回放到步态才有姿态档", 5, replay.toEvent("i1.gait").level(CausalDim.POSE_JOINT))
        assertEquals(
            "回放到受限时伤情已是受限档",
            InjuryGrade.LIMITED.level,
            replay.toEvent("i1.gait.restrict").level(CausalDim.SITE_INJURY),
        )
    }

    // ── 链② 环境变化 → 皮肤湿/温 → 体感与光影 ─────────────────────────────────

    @Test
    fun `链二_657真实解出的湿温体感光影四档都落到投影里`() {
        val log = CausalScenarios.environmentChain()
        assertTrue("链②不该有断链", CausalChecks.chainIssues(log, CausalChains.ENVIRONMENT).isEmpty())

        val state = CausalReplay.of(log).at(Span825.MINUTES)
        assertEquals("657 产汗档 4 压到 806 的上封 3", 3, state.level(CausalDim.SKIN_WET))
        assertEquals("皮肤温取 658 的体温偏移档", CausalScenarios.PHYSIO.bodyTempShift.grade.level, state.level(CausalDim.SKIN_TEMP))
        assertEquals("体感取 657 的 felt_temp 档", 3, state.level(CausalDim.SENSE_FEEL))
        assertEquals("日照取 657 的 intensity 档", 3, state.level(CausalDim.LIGHT))
        assertTrue("投影里要看得见 657 的档名", CausalRenderer.render(state).contains("烫（657）"))
    }

    @Test
    fun `链二_小时级之后汗退一档_分钟级不退`() {
        val replay = CausalReplay.of(CausalScenarios.environmentChain())
        assertEquals("分钟级汗还在", 3, replay.at(Span825.MINUTES).level(CausalDim.SKIN_WET))
        assertEquals("小时级汗退一档", 2, replay.at(Span825.HOURS).level(CausalDim.SKIN_WET))
    }

    @Test
    fun `链二_光影行显式写824未落地`() {
        val text = CausalRenderer.render(CausalReplay.of(CausalScenarios.environmentChain()).at(Span825.MINUTES))
        assertTrue("必须写出 824 缺省，不许凭空给五维值", text.contains("光影缺省") && text.contains("824"))
    }

    // ── 链③ 动作 → 疲劳累积 → 恢复时限 ───────────────────────────────────────

    @Test
    fun `链三_两次累积到力竭_跨日才回`() {
        val log = CausalScenarios.fatigueChain()
        assertTrue("链③不该有断链", CausalChecks.chainIssues(log, CausalChains.FATIGUE).isEmpty())

        val replay = CausalReplay.of(log)
        assertEquals("累积到力竭档", Fatigue.EXHAUSTED.level, replay.at(Span825.MINUTES).level(CausalDim.SENSE_FATIGUE))
        assertEquals("过夜还不够（734 预算 6 拍＋力竭退一档 ⇒ 跨日）", Fatigue.EXHAUSTED.level, replay.at(Span825.OVERNIGHT).level(CausalDim.SENSE_FATIGUE))
        assertEquals("跨日回满", Fatigue.AWAKE.level, replay.at(Span825.CROSS_DAY).level(CausalDim.SENSE_FATIGUE))
    }

    @Test
    fun `链三_734的恢复预算换成825四档是单调的`() {
        assertEquals("1 拍 ⇒ 分钟级", Span825.MINUTES, CausalRules.recoverySpan(Fatigue.LIGHT, 0))
        assertEquals("3 拍 ⇒ 小时级", Span825.HOURS, CausalRules.recoverySpan(Fatigue.LIGHT, 1))
        assertEquals("6 拍 ⇒ 过夜", Span825.OVERNIGHT, CausalRules.recoverySpan(Fatigue.MID, 2))
        assertEquals("力竭再退一档 ⇒ 跨日", Span825.CROSS_DAY, CausalRules.recoverySpan(Fatigue.EXHAUSTED, 2))
    }

    @Test
    fun `链三_越累恢复越慢不许出现倒挂`() {
        val spans = listOf(
            CausalRules.recoverySpan(Fatigue.LIGHT, 0),
            CausalRules.recoverySpan(Fatigue.LIGHT, 1),
            CausalRules.recoverySpan(Fatigue.LIGHT, 2),
            CausalRules.recoverySpan(Fatigue.EXHAUSTED, 2),
        )
        assertEquals("档序必须单调不降", spans.map { it.rank }, spans.map { it.rank }.sorted())
    }
}
