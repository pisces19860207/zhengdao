package com.example.zhengdao.baopu.state.geometry

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 655 判据：穿透深度误差 ≤5%（对解析解）；不相交返回 null；深度随重叠单调。 */
class EpaTest {

    private fun assertDepth(exact: Double, actual: Double) {
        assertTrue("期望 $actual ≈ $exact（±5%）", abs(actual - exact) <= 0.05 * exact + 1e-9)
    }

    @Test
    fun sphereSphereDepthMatchesAnalytic() {
        val a = Sphere(Vec3.ZERO, 1.0)
        val cases = listOf(2.9 to 0.1, 2.5 to 0.5, 2.0 to 1.0, 1.0 to 2.0, 0.0 to 3.0)
        for ((dist, exact) in cases) {
            val r = Epa.penetration(a, Sphere(Vec3(dist, 0.0, 0.0), 2.0))
            assertNotNull("中心距 $dist 应相交", r)
            assertDepth(exact, r!!.depthCm)
        }
    }

    @Test
    fun boxBoxDepthMatchesAnalytic() {
        val a = Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0))
        val r1 = Epa.penetration(a, Box(Vec3(1.5, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)))
        assertDepth(0.5, r1!!.depthCm)
        val r2 = Epa.penetration(a, Box(Vec3(1.5, 1.9, 1.95), Vec3(1.0, 1.0, 1.0)))
        assertDepth(0.05, r2!!.depthCm)
    }

    @Test
    fun boxBoxPicksMinOverlapAxis() {
        val a = Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0))
        val r = Epa.penetration(a, Box(Vec3(1.5, 1.9, 1.95), Vec3(1.0, 1.0, 1.0)))
        assertTrue("最浅轴应是 z", abs(r!!.normal.z) > 0.99)
    }

    @Test
    fun sphereBoxDepthMatchesAnalytic() {
        val box = Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0))
        val r = Epa.penetration(Sphere(Vec3(0.5, 0.0, 0.0), 1.0), box)
        assertDepth(1.5, r!!.depthCm)
    }

    @Test
    fun capsuleCapsuleDepthMatchesAnalytic() {
        val a = Capsule(Vec3.ZERO, Vec3(2.0, 0.0, 0.0), 0.5)
        val b = Capsule(Vec3(0.0, 0.6, 0.0), Vec3(2.0, 0.6, 0.0), 0.5)
        val r = Epa.penetration(a, b)
        assertDepth(0.4, r!!.depthCm)
    }

    @Test
    fun separatedReturnsNull() {
        assertNull(Epa.penetration(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(5.0, 0.0, 0.0), 2.0)))
        assertNull(Epa.penetration(Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0)), Box(Vec3(4.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0))))
    }

    @Test
    fun depthMonotoneInOverlap() {
        val a = Sphere(Vec3.ZERO, 1.0)
        var prev = -1.0
        for (dist in listOf(2.9, 2.5, 2.0, 1.0, 0.5)) {
            val depth = Epa.penetration(a, Sphere(Vec3(dist, 0.0, 0.0), 2.0))!!.depthCm
            assertTrue("深度应随重叠递增", depth > prev)
            prev = depth
        }
    }

    @Test
    fun normalPointsFromAToB() {
        val r = Epa.penetration(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(2.0, 0.0, 0.0), 2.0))
        assertTrue("法向应≈+X", r!!.normal.dot(Vec3.UNIT_X) > 0.99)
    }

    @Test
    fun movingAAlongMinusNormalSeparates() {
        val a = Sphere(Vec3.ZERO, 1.0)
        val b = Sphere(Vec3(2.0, 0.0, 0.0), 2.0)
        val r = Epa.penetration(a, b)!!
        val moved = a.copy(center = a.center - r.normal * r.depthCm)
        assertTrue("平移后应恰好接触", Gjk.distance(moved, b).distanceCm <= 1e-3)
    }

    @Test
    fun sameInputTwiceIdentical() {
        val a = Sphere(Vec3.ZERO, 1.0)
        val b = Box(Vec3(1.5, 0.2, -0.1), Vec3(1.0, 1.0, 1.0))
        assertEquals(Epa.penetration(a, b), Epa.penetration(a, b))
    }

    @Test
    fun resultCarriesAssumptionAndNote() {
        val r = Epa.penetration(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(2.0, 0.0, 0.0), 2.0))!!
        assertTrue(r.assumption.isNotBlank())
        assertEquals("模拟非测量", r.note)
    }
}