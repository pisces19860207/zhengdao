package com.example.zhengdao.baopu.state.geometry

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 655 判据：GJK 距离对解析解误差 ≤1e-3（球/盒两例为硬判据，其余形状加测）。 */
class GjkTest {

    private val tol = 1e-3

    @Test
    fun sphereSphereMatchesAnalytic() {
        val a = Sphere(Vec3.ZERO, 1.0)
        val cases = listOf(
            Vec3(5.0, 0.0, 0.0) to 2.0,
            Vec3(3.0, 4.0, 0.0) to 2.0,
            Vec3(3.5, 0.0, 0.0) to 0.5,
            Vec3(10.0, 0.0, 0.0) to 7.0,
            Vec3(2.0, 2.0, 2.0) to 2.0 * sqrt(3.0) - 3.0,
        )
        for ((center, exact) in cases) {
            val r = Gjk.distance(a, Sphere(center, 2.0))
            assertTrue("中心 $center 期望 $exact 实得 ${r.distanceCm}", kotlin.math.abs(r.distanceCm - exact) <= tol)
        }
    }

    @Test
    fun boxBoxMatchesAnalytic() {
        val a = Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0))
        val cases = listOf(
            Vec3(4.0, 0.0, 0.0) to 2.0,
            Vec3(4.0, 5.0, 0.0) to sqrt(13.0),
            Vec3(3.0, 3.0, 3.0) to sqrt(3.0),
            Vec3(0.0, 6.0, 0.0) to 4.0,
        )
        for ((center, exact) in cases) {
            val r = Gjk.distance(a, Box(center, Vec3(1.0, 1.0, 1.0)))
            assertTrue("中心 $center 期望 $exact 实得 ${r.distanceCm}", kotlin.math.abs(r.distanceCm - exact) <= tol)
        }
    }

    @Test
    fun sphereBoxMatchesAnalytic() {
        val box = Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0))
        val r1 = Gjk.distance(Sphere(Vec3(4.0, 0.0, 0.0), 1.0), box)
        assertTrue(kotlin.math.abs(r1.distanceCm - 2.0) <= tol)
        val r2 = Gjk.distance(Sphere(Vec3(3.0, 4.0, 0.0), 1.0), box)
        assertTrue(kotlin.math.abs(r2.distanceCm - (sqrt(13.0) - 1.0)) <= tol)
    }

    @Test
    fun capsuleCapsuleMatchesAnalytic() {
        val a = Capsule(Vec3.ZERO, Vec3(2.0, 0.0, 0.0), 0.5)
        val b = Capsule(Vec3(0.0, 3.0, 0.0), Vec3(2.0, 3.0, 0.0), 0.5)
        val r = Gjk.distance(a, b)
        assertTrue(kotlin.math.abs(r.distanceCm - 2.0) <= tol)
    }

    @Test
    fun hullHullMatchesAnalytic() {
        val tetra = ConvexHull(listOf(Vec3.ZERO, Vec3.UNIT_X, Vec3.UNIT_Y, Vec3.UNIT_Z))
        val point = ConvexHull(listOf(Vec3(3.0, 0.0, 0.0)))
        val r = Gjk.distance(tetra, point)
        assertTrue(kotlin.math.abs(r.distanceCm - 2.0) <= tol)
    }

    @Test
    fun rotatedBoxMatchesAnalytic() {
        val rot = Rot3.fromAxisAngle(Vec3.UNIT_Z, Math.PI / 4.0)
        val box = Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0), rot)
        val r = Gjk.distance(box, Sphere(Vec3(5.0, 0.0, 0.0), 1.0))
        val exact = 5.0 - sqrt(2.0) - 1.0
        assertTrue(kotlin.math.abs(r.distanceCm - exact) <= tol)
    }

    @Test
    fun ellipsoidSphereMatchesAnalytic() {
        val e = Ellipsoid(Vec3.ZERO, Vec3(2.0, 1.0, 1.0))
        val r = Gjk.distance(e, Sphere(Vec3(6.0, 0.0, 0.0), 1.0))
        assertTrue(kotlin.math.abs(r.distanceCm - 3.0) <= tol)
    }

    @Test
    fun overlappingSpheresReportZero() {
        val r = Gjk.distance(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(2.0, 0.0, 0.0), 2.0))
        assertTrue(r.intersecting)
        assertEquals(0.0, r.distanceCm, 0.0)
    }

    @Test
    fun touchingSpheresNearZero() {
        val r = Gjk.distance(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(3.0, 0.0, 0.0), 2.0))
        assertTrue(r.distanceCm <= tol)
    }

    @Test
    fun witnessesSpanTheGap() {
        val a = Sphere(Vec3.ZERO, 1.0)
        val b = Sphere(Vec3(5.0, 0.0, 0.0), 2.0)
        val r = Gjk.distance(a, b)
        assertTrue(kotlin.math.abs((r.witnessA - r.witnessB).norm() - r.distanceCm) <= tol)
        assertTrue(kotlin.math.abs(r.witnessA.norm() - 1.0) <= tol)
        assertTrue(kotlin.math.abs((r.witnessB - Vec3(5.0, 0.0, 0.0)).norm() - 2.0) <= tol)
    }

    @Test
    fun distanceIsSymmetric() {
        val a = Box(Vec3.ZERO, Vec3(1.0, 2.0, 0.5))
        val b = Capsule(Vec3(4.0, 1.0, 0.0), Vec3(5.0, 2.0, 1.0), 0.7)
        val ab = Gjk.distance(a, b).distanceCm
        val ba = Gjk.distance(b, a).distanceCm
        assertEquals(ab, ba, 1e-9)
    }

    @Test
    fun sameInputTwiceIdentical() {
        val a = Sphere(Vec3(0.3, -0.7, 1.2), 1.1)
        val b = Box(Vec3(4.2, 0.0, -1.0), Vec3(1.0, 1.0, 1.0))
        assertEquals(Gjk.distance(a, b), Gjk.distance(a, b))
    }

    @Test
    fun resultCarriesAssumptionAndNote() {
        val r = Gjk.distance(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(5.0, 0.0, 0.0), 1.0))
        assertTrue(r.assumption.isNotBlank())
        assertEquals("模拟非测量", r.note)
    }
}