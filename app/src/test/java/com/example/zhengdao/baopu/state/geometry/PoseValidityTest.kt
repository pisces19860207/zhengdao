package com.example.zhengdao.baopu.state.geometry

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 655 判据：姿态合法性＝自碰撞＋穿地穿沙＋限位越界。 */
class PoseValidityTest {

    private val env = Environment(groundYCm = 0.0, sandYCm = 0.0)
    private val sandy = Environment(groundYCm = 0.0, sandYCm = 0.3)

    private fun seg(name: String, center: Vec3, r: Double = 1.0) = SegmentProxy(name, Sphere(center, r))

    @Test
    fun cleanPoseValid() {
        val verdict = PoseValidity.check(listOf(seg("a", Vec3(0.0, 5.0, 0.0)), seg("b", Vec3(5.0, 5.0, 0.0))), env)
        assertTrue(verdict.valid)
        assertTrue(verdict.violations.isEmpty())
    }

    @Test
    fun selfCollisionDetectedWithDepth() {
        val verdict = PoseValidity.check(listOf(seg("a", Vec3(0.0, 5.0, 0.0)), seg("b", Vec3(1.0, 5.0, 0.0))), env)
        assertFalse(verdict.valid)
        val v = verdict.violations.single()
        assertEquals(ViolationKind.SELF_COLLISION, v.kind)
        assertTrue("穿透深度≈1", abs(v.depthCm - 1.0) <= 0.05)
    }

    @Test
    fun adjacentPairSkipped() {
        val verdict = PoseValidity.check(
            listOf(seg("thigh", Vec3(0.0, 5.0, 0.0)), seg("calf", Vec3(1.0, 5.0, 0.0))),
            env,
            adjacent = setOf("thigh" to "calf"),
        )
        assertTrue(verdict.valid)
    }

    @Test
    fun adjacentIsUnordered() {
        val verdict = PoseValidity.check(
            listOf(seg("a", Vec3(0.0, 5.0, 0.0)), seg("b", Vec3(1.0, 5.0, 0.0))),
            env,
            adjacent = setOf("b" to "a"),
        )
        assertTrue(verdict.valid)
    }

    @Test
    fun groundPenetrationDepth() {
        val verdict = PoseValidity.check(listOf(seg("foot", Vec3(0.0, 0.5, 0.0))), env)
        val v = verdict.violations.single()
        assertEquals(ViolationKind.GROUND_PENETRATION, v.kind)
        assertEquals(0.5, v.depthCm, 1e-9)
    }

    @Test
    fun sandPenetrationAboveGround() {
        val verdict = PoseValidity.check(listOf(seg("foot", Vec3(0.0, 1.1, 0.0))), sandy)
        val v = verdict.violations.single()
        assertEquals(ViolationKind.SAND_PENETRATION, v.kind)
        assertEquals(0.2, v.depthCm, 1e-9)
    }

    @Test
    fun belowGroundIsGroundNotSand() {
        val verdict = PoseValidity.check(listOf(seg("foot", Vec3(0.0, 0.5, 0.0))), sandy)
        assertEquals(ViolationKind.GROUND_PENETRATION, verdict.violations.single().kind)
    }

    @Test
    fun restingOnSandValid() {
        val verdict = PoseValidity.check(listOf(seg("foot", Vec3(0.0, 1.3, 0.0))), sandy)
        assertTrue(verdict.valid)
    }

    @Test
    fun limitExceededFromChainPose() {
        val chain = JointChain(listOf(JointSpec("knee", 1.0, Vec3.UNIT_Z, JointLimit(0.0, 0.5))))
        val pose = chain.pose(listOf(1.0))
        val verdict = PoseValidity.check(listOf(seg("a", Vec3(0.0, 5.0, 0.0))), env, chain = pose)
        val v = verdict.violations.single()
        assertEquals(ViolationKind.LIMIT_EXCEEDED, v.kind)
        assertEquals("knee", v.segment)
        assertEquals(0.5, v.depthCm, 1e-9)
    }

    @Test
    fun multipleViolationsAllReported() {
        val chain = JointChain(listOf(JointSpec("knee", 1.0, Vec3.UNIT_Z, JointLimit(0.0, 0.5))))
        val verdict = PoseValidity.check(
            listOf(seg("a", Vec3(0.0, 0.5, 0.0)), seg("b", Vec3(1.0, 0.5, 0.0))),
            env,
            chain = chain.pose(listOf(1.0)),
        )
        assertEquals(4, verdict.violations.size)
        assertFalse(verdict.valid)
    }

    @Test
    fun contactToleranceAllowsTouching() {
        val touching = listOf(seg("a", Vec3(0.0, 5.0, 0.0)), seg("b", Vec3(2.0, 5.0, 0.0)))
        assertTrue(PoseValidity.check(touching, env, contactTolCm = 0.01).valid)
    }

    @Test
    fun sameInputTwiceIdentical() {
        val segs = listOf(seg("a", Vec3(0.0, 5.0, 0.0)), seg("b", Vec3(1.0, 5.0, 0.0)))
        assertEquals(PoseValidity.check(segs, env), PoseValidity.check(segs, env))
    }

    @Test
    fun resultCarriesAssumptionAndNote() {
        val verdict = PoseValidity.check(listOf(seg("a", Vec3(0.0, 5.0, 0.0))), env)
        assertTrue(verdict.assumption.isNotBlank())
        assertEquals("模拟非测量", verdict.note)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sandBelowGroundRejected() {
        Environment(groundYCm = 1.0, sandYCm = 0.0)
    }

    @Test
    fun boxSegmentLowestPointUsed() {
        val box = SegmentProxy("torso", Box(Vec3(0.0, 0.9, 0.0), Vec3(1.0, 1.0, 1.0)))
        val verdict = PoseValidity.check(listOf(box), env)
        assertEquals(ViolationKind.GROUND_PENETRATION, verdict.violations.single().kind)
        assertEquals(0.1, verdict.violations.single().depthCm, 1e-9)
    }

    @Test
    fun unusedFullCircleLimitKeepsChainLegal() {
        val chain = JointChain(listOf(JointSpec("j", 1.0, Vec3.UNIT_Z, JointLimit(-PI, PI))))
        val verdict = PoseValidity.check(emptyList(), env, chain = chain.pose(listOf(3.0)))
        assertTrue(verdict.valid)
    }
}