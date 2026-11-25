// GZAG · 抱朴 · :policy · 第881批（2026-11-24）· 原创测试。
package com.example.zhengdao.baopu.state.garment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GarmentPinchTest {

    @Test
    fun `三条件同时成立才判可夹`() {
        val state = GarmentFixture.state(withUnderwear = false)
        val assessment = GarmentChecks.assessPinch(state, GarmentFixture.scene())
        assertTrue(assessment.conditions.met)
        assertTrue(assessment.possible)
        assertTrue(assessment.issues.isEmpty())
    }

    @Test
    fun `缺暴露条件判红`() {
        val state = GarmentFixture.state(withUnderwear = false)
        val assessment = GarmentChecks.assessPinch(state, GarmentFixture.scene(exposure = ExposurePath.COVERED))
        assertFalse(assessment.conditions.exposed)
        assertFalse(assessment.conditions.met)
        assertFalse(assessment.possible)
        assertTrue(GarmentChecks.PINCH_REQUIRES_EXPOSURE in GarmentFixture.codes(assessment.issues))
    }

    @Test
    fun `拉开时判红且内裤防护不构成卷入`() {
        val state = GarmentFixture.state(withUnderwear = true, frontOpening = true)
        val assessment = GarmentChecks.assessPinch(
            state,
            GarmentFixture.scene(phase = ZipPhase.OPENING, exposure = ExposurePath.FRONT_OPENING),
        )
        assertFalse(assessment.conditions.closing)
        assertFalse(assessment.conditions.met)
        assertFalse(assessment.possible)
        assertTrue(GarmentChecks.PINCH_REQUIRES_CLOSING in GarmentFixture.codes(assessment.issues))
    }

    @Test
    fun `顺滑无锁判红自锁或卡齿才留皮`() {
        val smooth = GarmentFixture.state(withUnderwear = false, zipBehavior = ZipBehavior.SMOOTH)
        val smoothResult = GarmentChecks.assessPinch(smooth, GarmentFixture.scene())
        assertFalse(smoothResult.conditions.retained)
        assertFalse(smoothResult.conditions.met)
        assertTrue(GarmentChecks.PINCH_REQUIRES_RETENTION in GarmentFixture.codes(smoothResult.issues))

        val grip = GarmentFixture.state(withUnderwear = false, zipBehavior = ZipBehavior.TEETH_GRIP)
        assertTrue(GarmentChecks.assessPinch(grip, GarmentFixture.scene()).conditions.retained)
        val softGrip = grip.copy(fabricStiffness = FabricStiffness.SOFT)
        assertTrue(GarmentChecks.assessPinch(softGrip, GarmentFixture.scene()).conditions.retained)
        val stiff = grip.copy(fabricStiffness = FabricStiffness.STIFF)
        assertFalse(GarmentChecks.assessPinch(stiff, GarmentFixture.scene()).conditions.retained)
    }

    @Test
    fun `非拉链门襟没有齿列路径`() {
        val state = GarmentFixture.state(flyType = FlyType.BUTTON)
        val result = GarmentChecks.assessPinch(state, GarmentFixture.scene())
        assertFalse(result.possible)
        assertTrue(GarmentChecks.PINCH_REQUIRES_ZIPPER in GarmentFixture.codes(result.issues))
    }

    @Test
    fun `内裤前开口与局部破洞是暴露路径但非自动夹到`() {
        val opening = GarmentFixture.state(withUnderwear = true, frontOpening = true)
        assertTrue(GarmentChecks.assessPinch(
            opening,
            GarmentFixture.scene(exposure = ExposurePath.FRONT_OPENING),
        ).conditions.exposed)
        val closedOpening = GarmentFixture.state(withUnderwear = true, frontOpening = false)
        assertFalse(GarmentChecks.assessPinch(
            closedOpening,
            GarmentFixture.scene(exposure = ExposurePath.FRONT_OPENING),
        ).conditions.exposed)

        val holed = GarmentFixture.state(
            withUnderwear = true,
            wear = InnerWear.Present(Wornness.HOLED, HoleSite.CROTCH, setOf(WearDriver.HABIT)),
        )
        assertTrue(GarmentChecks.assessPinch(
            holed,
            GarmentFixture.scene(exposure = ExposurePath.LOCAL_HOLE),
        ).conditions.exposed)
        assertFalse(GarmentChecks.assessPinch(
            holed,
            GarmentFixture.scene(phase = ZipPhase.IDLE, exposure = ExposurePath.LOCAL_HOLE),
        ).possible)

        val sideHole = holed.copy(innerLayer = holed.innerLayer.copy(
            wear = InnerWear.Present(Wornness.HOLED, HoleSite.SIDE_SEAM, setOf(WearDriver.HABIT)),
        ))
        assertFalse(GarmentChecks.assessPinch(
            sideHole,
            GarmentFixture.scene(exposure = ExposurePath.LOCAL_HOLE),
        ).conditions.exposed)
    }

    @Test
    fun `穿着完整内裤时无内裤暴露路径不成立`() {
        val state = GarmentFixture.state(withUnderwear = true)
        assertFalse(GarmentChecks.assessPinch(
            state,
            GarmentFixture.scene(exposure = ExposurePath.NO_UNDERWEAR),
        ).conditions.exposed)
        assertTrue(GarmentChecks.assessPinch(
            state,
            GarmentFixture.scene(exposure = ExposurePath.FORESKIN_EXPOSED),
        ).conditions.exposed)
        assertTrue(GarmentChecks.assessPinch(
            state,
            GarmentFixture.scene(exposure = ExposurePath.ERECTION_SHIFT),
        ).conditions.exposed)
    }

    @Test
    fun `松垮内裤前缘是第二路径但仍需暴露与闭合`() {
        val sagging = GarmentFixture.state(
            withUnderwear = true,
            frontOpening = true,
            wear = InnerWear.Present(Wornness.SAGGING, drivers = setOf(WearDriver.HABIT)),
        )
        val edgeScene = GarmentFixture.scene(
            exposure = ExposurePath.FRONT_OPENING,
            entanglement = EntanglementPath.UNDERWEAR_EDGE,
        )
        assertTrue(GarmentChecks.assessPinch(sagging, edgeScene).conditions.met)
        assertFalse(GarmentChecks.assessPinch(
            sagging,
            edgeScene.copy(exposure = ExposurePath.COVERED),
        ).conditions.met)
        assertFalse(GarmentChecks.assessPinch(
            sagging,
            edgeScene.copy(phase = ZipPhase.OPENING),
        ).conditions.met)

        val notSagging = GarmentFixture.state(withUnderwear = true, frontOpening = true)
        val blocked = GarmentChecks.assessPinch(notSagging, edgeScene)
        assertFalse(blocked.conditions.edgeAvailable)
        assertTrue(GarmentChecks.PINCH_EDGE_REQUIRES_SAGGING in GarmentFixture.codes(blocked.issues))
    }

    @Test
    fun `解除只许回拉滑块`() {
        val state = GarmentFixture.state(withUnderwear = false)
        assertTrue(GarmentChecks.assessPinch(
            state,
            GarmentFixture.scene(release = ReleaseMethod.BACK_SLIDER),
        ).issues.none { it.code == GarmentChecks.RELEASE_METHOD })
        ReleaseMethod.entries.filter { it != ReleaseMethod.BACK_SLIDER }.forEach { release ->
            val result = GarmentChecks.assessPinch(state, GarmentFixture.scene(release = release))
            assertTrue("$release 必须红", GarmentChecks.RELEASE_METHOD in GarmentFixture.codes(result.issues))
        }
    }

    @Test
    fun `后果上限rare`() {
        val allowed = setOf(
            Consequence.NONE,
            Consequence.REDNESS,
            Consequence.BRIEF_PAIN,
            Consequence.MARK,
        )
        val forbidden = setOf(
            Consequence.TEARING,
            Consequence.BLEEDING,
            Consequence.INFECTION,
            Consequence.MEDICAL_CARE,
        )
        assertEquals(Consequence.entries.toSet(), allowed + forbidden)
        assertEquals(allowed, Consequence.entries.filter { it.withinRare }.toSet())
        val state = GarmentFixture.state(withUnderwear = false)
        allowed.forEach { consequence ->
            assertTrue(
                "$consequence 不应超上限",
                GarmentChecks.assessPinch(
                    state,
                    GarmentFixture.scene(consequence = consequence),
                ).issues.none { it.code == GarmentChecks.CONSEQUENCE_CEILING },
            )
        }
        forbidden.forEach { consequence ->
            val result = GarmentChecks.assessPinch(state, GarmentFixture.scene(consequence = consequence))
            assertTrue("$consequence 必须红", GarmentChecks.CONSEQUENCE_CEILING in GarmentFixture.codes(result.issues))
        }
    }

    @Test
    fun `routine默认无伤且非常规必须给触发`() {
        val state = GarmentFixture.state(withUnderwear = false)
        assertTrue(GarmentChecks.auditAction(state, GarmentAction()).isEmpty())
        val routinePinch = GarmentChecks.auditAction(state, GarmentAction(pinch = GarmentFixture.scene()))
        assertTrue(GarmentChecks.ROUTINE_PINCH in GarmentFixture.codes(routinePinch))

        val noTrigger = GarmentChecks.auditAction(
            state,
            GarmentAction(mode = ActionMode.NON_ROUTINE, pinch = GarmentFixture.scene()),
        )
        assertTrue(GarmentChecks.TRIGGER_REQUIRED in GarmentFixture.codes(noTrigger))
        val triggered = GarmentChecks.auditAction(
            state,
            GarmentAction(
                mode = ActionMode.NON_ROUTINE,
                trigger = NonRoutineTrigger.RUSHED,
                pinch = GarmentFixture.scene(),
            ),
        )
        assertTrue(triggered.isEmpty())
    }

    @Test
    fun `文本声称夹到不能绕过结构化三条件`() {
        val state = GarmentFixture.state(withUnderwear = false)
        val issues = GarmentChecks.auditAction(
            state,
            GarmentAction(mode = ActionMode.NON_ROUTINE, trigger = NonRoutineTrigger.RUSHED, text = "夹到包皮"),
        )
        assertTrue(GarmentChecks.PINCH_SCENE_REQUIRED in GarmentFixture.codes(issues))
        assertTrue(GarmentChecks.RELEASE_TEXT_REQUIRED in GarmentFixture.codes(issues))
        val injuryClaim = GarmentChecks.auditAction(
            state,
            GarmentAction(mode = ActionMode.NON_ROUTINE, trigger = NonRoutineTrigger.RUSHED, text = "上拉夹伤包皮"),
        )
        assertTrue(GarmentChecks.PINCH_SCENE_REQUIRED in GarmentFixture.codes(injuryClaim))
        assertTrue(GarmentChecks.RELEASE_TEXT_REQUIRED in GarmentFixture.codes(injuryClaim))
        val bodyPinch = GarmentChecks.auditAction(
            state,
            GarmentAction(mode = ActionMode.NON_ROUTINE, trigger = NonRoutineTrigger.RUSHED, text = "上拉闭合时夹了包皮"),
        )
        assertTrue(GarmentChecks.PINCH_SCENE_REQUIRED in GarmentFixture.codes(bodyPinch))
        assertTrue(GarmentChecks.RELEASE_TEXT_REQUIRED in GarmentFixture.codes(bodyPinch))
        val completedPinch = GarmentChecks.auditAction(
            state,
            GarmentAction(mode = ActionMode.NON_ROUTINE, trigger = NonRoutineTrigger.RUSHED, text = "夹住了包皮"),
        )
        assertTrue(GarmentChecks.PINCH_SCENE_REQUIRED in GarmentFixture.codes(completedPinch))
        assertTrue(GarmentChecks.RELEASE_TEXT_REQUIRED in GarmentFixture.codes(completedPinch))
        listOf(
            "上拉闭合时夹住他的包皮",
            "上拉闭合时咬住了皮肤",
            "上拉闭合时把他的包皮夹住了",
            "上拉闭合时卡住包皮",
        ).forEach { text ->
            val codes = GarmentFixture.codes(GarmentChecks.auditAction(
                state,
                GarmentAction(mode = ActionMode.NON_ROUTINE, text = text),
            ))
            assertTrue("$text 必须要求触发", GarmentChecks.TRIGGER_REQUIRED in codes)
            assertTrue("$text 必须要求 PinchScene", GarmentChecks.PINCH_SCENE_REQUIRED in codes)
            assertTrue("$text 必须要求回拉滑块", GarmentChecks.RELEASE_TEXT_REQUIRED in codes)
        }
        val routineInjury = GarmentChecks.auditAction(state, GarmentAction(text = "上拉夹伤包皮"))
        assertTrue(GarmentTextChecks.ROUTINE_INJURY in GarmentFixture.codes(routineInjury))
        val bareOpeningPinch = GarmentChecks.auditAction(
            state,
            GarmentAction(
                mode = ActionMode.NON_ROUTINE,
                trigger = NonRoutineTrigger.RUSHED,
                text = "拉开时夹住，回拉滑块",
            ),
        )
        assertTrue(GarmentTextChecks.WRONG_PHASE in GarmentFixture.codes(bareOpeningPinch))
        assertTrue(GarmentChecks.PINCH_SCENE_REQUIRED in GarmentFixture.codes(bareOpeningPinch))
        val doubleNegatedPinch = GarmentChecks.auditAction(
            state,
            GarmentAction(
                mode = ActionMode.NON_ROUTINE,
                trigger = NonRoutineTrigger.RUSHED,
                text = "并非没有夹到包皮",
            ),
        )
        assertTrue(GarmentChecks.PINCH_SCENE_REQUIRED in GarmentFixture.codes(doubleNegatedPinch))
        assertTrue(GarmentChecks.RELEASE_TEXT_REQUIRED in GarmentFixture.codes(doubleNegatedPinch))
    }

    @Test
    fun `文本描写夹到必须正面写回拉滑块`() {
        val state = GarmentFixture.state(withUnderwear = false)
        val action = GarmentAction(
            mode = ActionMode.NON_ROUTINE,
            trigger = NonRoutineTrigger.RUSHED,
            pinch = GarmentFixture.scene(),
            text = "上拉闭合时夹到，回拉滑块后松开",
        )
        assertTrue(GarmentChecks.auditAction(state, action).isEmpty())
        val bad = action.copy(text = "上拉闭合时夹到，把滑块拔出来")
        val codes = GarmentFixture.codes(GarmentChecks.auditAction(state, bad))
        assertTrue(GarmentTextChecks.FORBIDDEN_RELEASE in codes)
        assertTrue(GarmentChecks.RELEASE_TEXT_REQUIRED in codes)
        val negatedRelease = action.copy(text = "上拉闭合时夹到，千万别回拉滑块")
        assertTrue(GarmentChecks.RELEASE_TEXT_REQUIRED in GarmentFixture.codes(
            GarmentChecks.auditAction(state, negatedRelease),
        ))
        val politeNegation = action.copy(text = "上拉闭合时夹到，请千万别回拉滑块")
        assertTrue(GarmentChecks.RELEASE_TEXT_REQUIRED in GarmentFixture.codes(
            GarmentChecks.auditAction(state, politeNegation),
        ))
        listOf("不许", "无法", "禁止", "不允许", "不应", "不用", "不").forEach { prohibition ->
            val text = "上拉闭合时夹到，${prohibition}回拉滑块"
            assertTrue("$text 必须红", GarmentChecks.RELEASE_TEXT_REQUIRED in GarmentFixture.codes(
                GarmentChecks.auditAction(state, action.copy(text = text)),
            ))
        }
    }

    @Test
    fun `动作审计接上文本审计`() {
        val issues = GarmentChecks.auditAction(GarmentDefaults.default(), GarmentAction(text = "硬拽拉链"))
        assertEquals(listOf(GarmentTextChecks.FORBIDDEN_RELEASE), GarmentFixture.codes(issues))
    }

    @Test
    fun `把裤型当风险源判红`() {
        val state = GarmentDefaults.default()
        val issues = GarmentChecks.auditAction(state, GarmentAction(), RiskSource.GARMENT_FORM)
        assertEquals(listOf(GarmentChecks.GARMENT_RISK_SOURCE), GarmentFixture.codes(issues))
    }
}
