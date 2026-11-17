package com.example.zhengdao.baopu.state.geometry

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 655 判据：每关节 min/max 截断＋链式传播（静态映射，无时间积分）。 */
class JointChainTest {

    private fun chain2(l1: Double, l2: Double, limit1: JointLimit): JointChain =
        JointChain(
            listOf(
                JointSpec("j1", l1, Vec3.UNIT_Z, limit1),
                JointSpec("j2", l2, Vec3.UNIT_Z, JointLimit(-PI, PI)),
            ),
        )

    @Test
    fun zeroAnglesStraightChain() {
        val chain = JointChain(
            listOf(
                JointSpec("a", 1.0, Vec3.UNIT_Z, JointLimit(-PI, PI)),
                JointSpec("b", 1.0, Vec3.UNIT_Z, JointLimit(-PI, PI)),
                JointSpec("c", 1.0, Vec3.UNIT_Z, JointLimit(-PI, PI)),
            ),
        )
        val pose = chain.pose(listOf(0.0, 0.0, 0.0))
        assertEquals(Vec3(3.0, 0.0, 0.0), pose.endEffectorCm)
        assertTrue(pose.joints.all { it.withinLimit })
    }

    @Test
    fun rightAngleFirstJoint() {
        val pose = chain2(1.0, 1.0, JointLimit(-PI, PI)).pose(listOf(PI / 2.0, 0.0))
        assertEquals(0.0, pose.joints[0].worldPosCm.x, 1e-9)
        assertEquals(1.0, pose.joints[0].worldPosCm.y, 1e-9)
        assertEquals(0.0, pose.endEffectorCm.x, 1e-9)
        assertEquals(2.0, pose.endEffectorCm.y, 1e-9)
    }

    @Test
    fun clampToMaxAndMarkViolation() {
        val pose = chain2(1.0, 1.0, JointLimit(0.0, PI / 4.0)).pose(listOf(PI / 2.0, 0.0))
        assertEquals(PI / 4.0, pose.joints[0].appliedRad, 1e-12)
        assertFalse(pose.joints[0].withinLimit)
        assertTrue(pose.joints[1].withinLimit)
        assertEquals(sqrt(2.0), pose.endEffectorCm.x, 1e-9)
        assertEquals(sqrt(2.0), pose.endEffectorCm.y, 1e-9)
    }

    @Test
    fun clampToMin() {
        val pose = chain2(1.0, 1.0, JointLimit(-0.3, 0.3)).pose(listOf(-2.0, 0.0))
        assertEquals(-0.3, pose.joints[0].appliedRad, 1e-12)
        assertFalse(pose.joints[0].withinLimit)
    }

    @Test
    fun clampingPropagatesDownstream() {
        val angles = listOf(PI / 2.0, PI / 3.0)
        val free = chain2(1.0, 1.0, JointLimit(-PI, PI)).pose(angles)
        val clamped = chain2(1.0, 1.0, JointLimit(0.0, PI / 4.0)).pose(angles)
        assertNotEquals(free.joints[1].worldPosCm, clamped.joints[1].worldPosCm)
        assertNotEquals(free.endEffectorCm, clamped.endEffectorCm)
    }

    @Test
    fun arbitraryAxisRotation() {
        val chain = JointChain(listOf(JointSpec("j", 1.0, Vec3.UNIT_Y, JointLimit(-PI, PI))))
        val pose = chain.pose(listOf(PI / 2.0))
        assertEquals(0.0, pose.endEffectorCm.x, 1e-9)
        assertEquals(-1.0, pose.endEffectorCm.z, 1e-9)
    }

    @Test
    fun rootPoseOffsetsWholeChain() {
        val chain = JointChain(
            listOf(JointSpec("j", 1.0, Vec3.UNIT_Z, JointLimit(-PI, PI))),
            rootPosCm = Vec3(10.0, 20.0, 30.0),
        )
        val pose = chain.pose(listOf(0.0))
        assertEquals(Vec3(11.0, 20.0, 30.0), pose.endEffectorCm)
    }

    @Test
    fun fkMatchesHandComputedTwoLink() {
        val t1 = 0.7
        val t2 = -0.4
        val pose = chain2(1.3, 0.9, JointLimit(-PI, PI)).pose(listOf(t1, t2))
        val ex = 1.3 * cos(t1) + 0.9 * cos(t1 + t2)
        val ey = 1.3 * sin(t1) + 0.9 * sin(t1 + t2)
        assertEquals(ex, pose.endEffectorCm.x, 1e-9)
        assertEquals(ey, pose.endEffectorCm.y, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun angleCountMismatchRejected() {
        chain2(1.0, 1.0, JointLimit(-PI, PI)).pose(listOf(0.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invertedLimitRejected() {
        JointLimit(1.0, -1.0)
    }

    @Test
    fun sameInputTwiceIdentical() {
        val chain = chain2(1.0, 1.0, JointLimit(-PI, PI))
        assertEquals(chain.pose(listOf(0.3, -0.8)), chain.pose(listOf(0.3, -0.8)))
    }

    @Test
    fun jointNamesClosedSet() {
        assertEquals(17, JointNames.ALL.size)
        assertEquals(JointNames.ALL.size, JointNames.ALL.toSet().size)
    }

    @Test
    fun resultCarriesAssumptionAndNote() {
        val pose = chain2(1.0, 1.0, JointLimit(-PI, PI)).pose(listOf(0.0, 0.0))
        assertTrue(pose.assumption.isNotBlank())
        assertEquals("模拟非测量", pose.note)
    }
}