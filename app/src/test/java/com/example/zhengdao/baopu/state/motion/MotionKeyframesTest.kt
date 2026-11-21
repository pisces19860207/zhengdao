package com.example.zhengdao.baopu.state.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 725 主判据：每帧四项合法、方向断言、左右交替、退档、逐字节一致。 */
class MotionKeyframesTest {

    private val all = MotionKeyframes.all()

    @Test
    fun fifteenKeyframesAcrossFourActions() {
        assertEquals(15, all.size)
        assertEquals(4, all.map { it.action }.toSet().size)
        assertEquals(5, MotionKeyframes.of(MotionAction.WALK).size)
        assertEquals(3, MotionKeyframes.of(MotionAction.CROUCH).size)
        assertEquals(4, MotionKeyframes.of(MotionAction.STEP_OVER).size)
        assertEquals(3, MotionKeyframes.of(MotionAction.BEND).size)
    }

    // ── 判据①：每帧四项合法 ──────────────────────────────────────────
    @Test
    fun everyFramePassesAllFourChecks() {
        assertTrue("存在四项未全过的帧", all.all { it.checks.allOk })
        assertTrue(all.all { it.checks.render() == "L1R1C1G1" })
    }

    // ── 判据②：方向断言（≥8 条，逐条独立）────────────────────────────
    @Test
    fun dir01_crouchDeeperLowerCoM() {
        val stand = MotionKeyframes.get(MotionAction.CROUCH, 0)
        val full = MotionKeyframes.get(MotionAction.CROUCH, 2)
        assertTrue("蹲比站重心低", full.comHeight.level < stand.comHeight.level)
    }

    @Test
    fun dir02_crouchMonotonicCoMAndJoint() {
        val s = MotionKeyframes.get(MotionAction.CROUCH, 0)
        val h = MotionKeyframes.get(MotionAction.CROUCH, 1)
        val f = MotionKeyframes.get(MotionAction.CROUCH, 2)
        assertTrue("重心档 站>半>全", s.comHeight.level > h.comHeight.level && h.comHeight.level > f.comHeight.level)
        assertTrue("膝档 全>半>站", f.jointLeft.level > h.jointLeft.level && h.jointLeft.level > s.jointLeft.level)
    }

    @Test
    fun dir03_bendDeeperHigherSpine() {
        val straight = MotionKeyframes.get(MotionAction.BEND, 0)
        val half = MotionKeyframes.get(MotionAction.BEND, 1)
        val deep = MotionKeyframes.get(MotionAction.BEND, 2)
        assertTrue("弯腰越深脊柱档越高", straight.spine.level < half.spine.level && half.spine.level < deep.spine.level)
    }

    @Test
    fun dir04_bendDeeperLowerCoM() {
        val straight = MotionKeyframes.get(MotionAction.BEND, 0)
        val deep = MotionKeyframes.get(MotionAction.BEND, 2)
        assertTrue("深弯腰比直立重心低", deep.comHeight.level < straight.comHeight.level)
    }

    @Test
    fun dir05_stepLiftHigherThanPrep() {
        val prep = MotionKeyframes.get(MotionAction.STEP_OVER, 0)
        val lift = MotionKeyframes.get(MotionAction.STEP_OVER, 1)
        assertTrue("抬腿摆动腿档高于准备", lift.jointRight.level > prep.jointRight.level)
    }

    @Test
    fun dir06_walkSwingHigherThanSupport() {
        assertTrue("摆动腿档须高于支撑腿档", Gait.swingHigherThanSupport(MotionKeyframes.of(MotionAction.WALK)))
    }

    @Test
    fun dir07_walkHasAllThreePhases() {
        assertTrue("走须含支撑/摆动/双支撑三态", Gait.allThreePhasesPresent(MotionKeyframes.of(MotionAction.WALK)))
    }

    @Test
    fun dir08_standIsHighestCoMAndLowestJoint() {
        val stand = MotionKeyframes.get(MotionAction.CROUCH, 0)
        assertEquals(5, stand.comHeight.level)
        assertEquals(1, stand.jointLeft.level)
    }

    @Test
    fun dir09_stepCrossHigherThanLand() {
        val cross = MotionKeyframes.get(MotionAction.STEP_OVER, 2)
        val land = MotionKeyframes.get(MotionAction.STEP_OVER, 3)
        assertTrue("跨过高档不低于落地", cross.jointRight.level >= land.jointRight.level)
    }

    // ── 判据④：左右交替 ──────────────────────────────────────────────
    @Test
    fun supportAlternatesLeftRight() {
        assertTrue("不许同脚连续支撑", Gait.supportAlternates(MotionKeyframes.of(MotionAction.WALK)))
    }

    // ── 退档机制有牙齿 ───────────────────────────────────────────────
    @Test
    fun demotionHasTeeth() {
        val full = MotionKeyframes.get(MotionAction.CROUCH, 2)
        val deepBend = MotionKeyframes.get(MotionAction.BEND, 2)
        val cross = MotionKeyframes.get(MotionAction.STEP_OVER, 2)
        listOf(full, deepBend, cross).forEach {
            assertEquals("目标档 5 应退到 4", 4, it.comHeight.level.let { c -> 6 - c })
            assertNotNull("应留退档痕迹", it.demotedFrom)
            assertEquals(5, it.demotedFrom)
        }
    }

    @Test
    fun demoteStopsAtFirstPassingGrade() {
        assertEquals(4, MotionKeyframes.demote(5, MotionAction.CROUCH))
        assertEquals(3, MotionKeyframes.demote(3, MotionAction.CROUCH))
        assertEquals(1, MotionKeyframes.demote(1, MotionAction.CROUCH))
    }

    // ── 判据⑤：同输入两次逐字节一致 ──────────────────────────────────
    @Test
    fun byteIdenticalAcrossTwoRuns() {
        val a = MotionKeyframes.all().joinToString("\n") { it.render() }
        val b = MotionKeyframes.all().joinToString("\n") { it.render() }
        assertEquals(a, b)
        assertEquals(15, a.lines().size)
    }

    @Test
    fun outputCarriesNoRadianOrAngleNumbers() {
        all.forEach { pose ->
            val r = pose.render()
            assertTrue("输出不得含小数（弧度/角度数值）：$r", !Regex("\\d+\\.\\d+").containsMatchIn(r))
            assertTrue("输出不得含 rad/度 字样：$r", !r.contains("rad", true) && !r.contains("度"))
        }
    }
}
