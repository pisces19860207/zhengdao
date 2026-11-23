// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 判据③（parentEventId 链完整：断链 ⇒ 红）与各项边界反例。
// 每条反例只坏**一处**，这样红起来能指到具体判据，不会被别的毛病顶替。
package com.example.zhengdao.baopu.state.causal

import com.example.zhengdao.baopu.state.bodyparts.BodyPart
import com.example.zhengdao.baopu.state.physiology.Fatigue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalLinkTest {

    private fun codes(log: CausalLog): List<String> = CausalChecks.audit(log).map { it.code }

    private fun gait(parent: String?, at: Int = 1, id: String = "g"): CausalEvent = CausalEvent(
        eventId = id,
        parentEventId = parent,
        at = at,
        kind = CausalKind.GAIT,
        site = SiteRef.Part(BodyPart.THIGH),
        intensity = 1,
        immediate = listOf(GradeWrite.to(CausalDim.POSE_JOINT, 4)),
    )

    @Test
    fun `断链_非根类型没有父事件判红`() {
        assertTrue("步态缺父必须红", CausalChecks.BROKEN_PARENT in codes(CausalLog.of(gait(null))))
    }

    @Test
    fun `断链_父指向不存在判红`() {
        assertTrue("父不存在必须红", CausalChecks.UNKNOWN_PARENT in codes(CausalLog.of(gait("nope"))))
    }

    @Test
    fun `断链_父比子更晚判红`() {
        val strike = InjuryChain.strike(
            "s", at = 10, site = SiteRef.Part(BodyPart.THIGH), mechanism = InjuryMechanism.BLUNT,
            intensity = 1, injury = com.example.zhengdao.baopu.state.injury.InjuryGrade.COLOR,
            pain = com.example.zhengdao.baopu.state.injury.PainGrade.TOUCH, healSpan = Span825.OVERNIGHT,
        )
        assertTrue("时间倒序必须红", CausalChecks.BACKWARD_TIME in codes(CausalLog.of(strike, gait("s", at = 5))))
    }

    @Test
    fun `断链_父类型不在允许集内判红`() {
        val strike = InjuryChain.strike(
            "s", at = 0, site = SiteRef.Part(BodyPart.THIGH), mechanism = InjuryMechanism.BLUNT,
            intensity = 1, injury = com.example.zhengdao.baopu.state.injury.InjuryGrade.COLOR,
            pain = com.example.zhengdao.baopu.state.injury.PainGrade.TOUCH, healSpan = Span825.OVERNIGHT,
        )
        val restrictOfStrike = CausalEvent(
            eventId = "r", parentEventId = "s", at = 1, kind = CausalKind.RESTRICT,
            site = SiteRef.Part(BodyPart.THIGH), intensity = 1,
            immediate = listOf(GradeWrite.to(CausalDim.SITE_INJURY, 3)),
        )
        assertTrue("受限的父只能是步态", CausalChecks.ILLEGAL_PARENT in codes(CausalLog.of(strike, restrictOfStrike)))
    }

    @Test
    fun `断链_三根链的合法夹具零红`() {
        val pairs = listOf(
            CausalScenarios.injuryChain() to CausalChains.INJURY,
            CausalScenarios.environmentChain() to CausalChains.ENVIRONMENT,
            CausalScenarios.fatigueChain() to CausalChains.FATIGUE,
        )
        pairs.forEach { (log, spec) ->
            assertTrue("${spec.label} 不该有静态问题：${CausalChecks.audit(log)}", CausalChecks.audit(log).isEmpty())
            assertTrue("${spec.label} 不该有断链：${CausalChecks.chainIssues(log, spec)}", CausalChecks.chainIssues(log, spec).isEmpty())
        }
    }

    @Test
    fun `断链_别的链的节点凑不齐本链`() {
        val injuryLog = CausalScenarios.injuryChain()
        assertTrue("受伤链不该冒充环境链", CausalChecks.chainIssues(injuryLog, CausalChains.ENVIRONMENT).isNotEmpty())
        assertTrue("受伤链不该冒充疲劳链", CausalChecks.chainIssues(injuryLog, CausalChains.FATIGUE).isNotEmpty())
        assertTrue("三链一起查时要报出缺的那两根", CausalChecks.allChainIssues(injuryLog).size >= 2)
    }

    @Test
    fun `链不完整_少一站就红且只报那一站`() {
        val log = CausalScenarios.injuryChain()
        val headOnly = CausalLog(log.events.filter { it.kind != CausalKind.RESTRICT })
        val issues = CausalChecks.chainIssues(headOnly, CausalChains.INJURY)
        assertEquals("只缺步态→受限这一段", 1, issues.size)
        assertTrue("要指出缺哪一段", issues.single().message.contains("步态改变→后续受限"))
    }

    // ── 字段层面的反例 ───────────────────────────────────────────────────────

    @Test
    fun `反例_写了自己不该写的维度判红`() {
        val bad = CausalEvent(
            eventId = "s", parentEventId = null, at = 0, kind = CausalKind.STRIKE,
            site = SiteRef.Part(BodyPart.THIGH), intensity = 1, mechanism = InjuryMechanism.BLUNT,
            immediate = listOf(GradeWrite.to(CausalDim.POSE_JOINT, 4)),
        )
        assertTrue("受伤不许写姿态", CausalChecks.DIM_NOT_ALLOWED in codes(CausalLog.of(bad)))
    }

    @Test
    fun `反例_绝对档越界判红`() {
        val bad = CausalEvent(
            eventId = "s", parentEventId = null, at = 0, kind = CausalKind.STRIKE,
            site = SiteRef.Part(BodyPart.THIGH), intensity = 1, mechanism = InjuryMechanism.BLUNT,
            immediate = listOf(GradeWrite.to(CausalDim.SITE_INJURY, 9)),
        )
        assertTrue("728 只有 0–3", CausalChecks.LEVEL_OUT_OF_RANGE in codes(CausalLog.of(bad)))
    }

    @Test
    fun `反例_强度越界与部位未点名都判红`() {
        val bad = CausalEvent(
            eventId = "s", parentEventId = null, at = 0, kind = CausalKind.STRIKE,
            site = SiteRef.Unnamed, intensity = 5, mechanism = InjuryMechanism.BLUNT,
            immediate = listOf(GradeWrite.to(CausalDim.SITE_INJURY, 1)),
        )
        val c = codes(CausalLog.of(bad))
        assertTrue("强度越界", CausalChecks.INTENSITY_RANGE in c)
        assertTrue("部位未点名", CausalChecks.SITE_UNNAMED in c)
    }

    @Test
    fun `反例_机制该有就有不该有就没有`() {
        val noMech = CausalEvent(
            eventId = "s", parentEventId = null, at = 0, kind = CausalKind.STRIKE,
            site = SiteRef.Part(BodyPart.THIGH), intensity = 1,
            immediate = listOf(GradeWrite.to(CausalDim.SITE_INJURY, 1)),
        )
        assertTrue("受伤必须给机制", CausalChecks.MECHANISM_REQUIRED in codes(CausalLog.of(noMech)))

        val foreign = CausalEvent(
            eventId = "a", parentEventId = null, at = 0, kind = CausalKind.ACT,
            site = SiteRef.Part(BodyPart.THIGH), intensity = 1, mechanism = InjuryMechanism.BLUNT,
        )
        assertTrue("动作不该带受伤机制", CausalChecks.MECHANISM_FOREIGN in codes(CausalLog.of(foreign)))
    }

    @Test
    fun `反例_重复事件id判红`() {
        val a = CausalEvent("x", null, 0, CausalKind.ACT, SiteRef.Part(BodyPart.THIGH), 1)
        val b = CausalEvent("x", null, 1, CausalKind.ACT, SiteRef.Part(BodyPart.THIGH), 1)
        assertTrue("同名两条", CausalChecks.DUPLICATE_ID in codes(CausalLog.of(a, b)))
    }

    @Test
    fun `反例_增量写法没有基线判红且不默认零`() {
        val act = FatigueChain.act("f", at = 0, site = SiteRef.Part(BodyPart.THIGH),
            action = com.example.zhengdao.baopu.state.motion.MotionAction.WALK, intensity = 1)
        val jump = CausalEvent(
            eventId = "f.+", parentEventId = "f", at = 1, kind = CausalKind.FATIGUE,
            site = SiteRef.Part(BodyPart.THIGH), intensity = 1,
            immediate = listOf(GradeWrite.by(CausalDim.SENSE_FATIGUE, 1)),
        )
        val state = CausalReplay.of(CausalLog.of(act, jump)).at(Span825.MINUTES)
        assertNull("不许拿 0 顶上", state.level(CausalDim.SENSE_FATIGUE))
        assertTrue("要红", state.issues.any { it.code == CausalChecks.DELTA_WITHOUT_BASE })
    }

    @Test
    fun `反例_伤情为零档时不推受限_这是合法断点`() {
        val strike = InjuryChain.strike(
            "s", at = 0, site = SiteRef.Part(BodyPart.THIGH), mechanism = InjuryMechanism.FRICTION,
            intensity = 0, injury = com.example.zhengdao.baopu.state.injury.InjuryGrade.NONE,
            pain = com.example.zhengdao.baopu.state.injury.PainGrade.NONE, healSpan = Span825.OVERNIGHT,
        )
        assertNull("没伤就不该受限", InjuryChain.restrictAfter(InjuryChain.gaitAfter(strike, at = 1), at = 2, healSpan = Span825.OVERNIGHT))
    }

    @Test
    fun `基线_疲劳累加能累加`() {
        val state = CausalReplay.of(CausalScenarios.fatigueChain()).at(Span825.MINUTES)
        assertEquals("两步累积到力竭", Fatigue.EXHAUSTED.level, state.level(CausalDim.SENSE_FATIGUE))
        assertTrue("没有增量基线问题", state.issues.isEmpty())
    }

    @Test
    fun `溯源_每一维都指得回最后写它的那条事件`() {
        val state = CausalReplay.of(CausalScenarios.injuryChain()).at(Span825.MINUTES)
        assertEquals("伤情最后被受限那条写下", "i1.gait.restrict", CausalRenderer.originOf(state, CausalDim.SITE_INJURY))
        assertEquals("姿态最后被步态那条写下", "i1.gait", CausalRenderer.originOf(state, CausalDim.POSE_JOINT))
        assertNotNull("伤情有出处", state.origin[CausalDim.SITE_INJURY])
        assertFalse("光照没写过，不该有出处", state.origin.containsKey(CausalDim.LIGHT))
    }
}
