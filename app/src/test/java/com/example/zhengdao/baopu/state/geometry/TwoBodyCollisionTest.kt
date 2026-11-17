package com.example.zhengdao.baopu.state.geometry

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 655 判据：两体正碰动量/能量 ±5%（闭式解，动量守恒应精确到浮点误差）。 */
class TwoBodyCollisionTest {

    private val nx = Vec3.UNIT_X

    private fun cmKe(r: CollisionResult, m1: Double, m2: Double): Pair<Double, Double> {
        val vcm = r.momentumBefore / (m1 + m2)
        val before = r.keBefore - 0.5 * (m1 + m2) * vcm.normSq()
        val pAfter = r.v1After * m1 + r.v2After * m2
        val vcmAfter = pAfter / (m1 + m2)
        val keAfter = 0.5 * m1 * r.v1After.normSq() + 0.5 * m2 * r.v2After.normSq()
        return before to (keAfter - 0.5 * (m1 + m2) * vcmAfter.normSq())
    }

    @Test
    fun equalMassElasticSwapsVelocities() {
        val r = TwoBodyCollision.resolve(1.0, 1.0, Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0), nx, 1.0)
        assertTrue(r.collided)
        assertEquals(-1.0, r.v1After.x, 1e-9)
        assertEquals(1.0, r.v2After.x, 1e-9)
        assertEquals(r.keBefore, r.keAfter, 1e-9)
    }

    @Test
    fun equalMassPerfectlyInelasticSticks() {
        val r = TwoBodyCollision.resolve(1.0, 1.0, Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0), nx, 0.0)
        assertEquals(0.0, r.v1After.x, 1e-9)
        assertEquals(0.0, r.v2After.x, 1e-9)
        assertEquals(0.0, r.keAfter, 1e-9)
    }

    @Test
    fun unequalMassMatchesClosedForm() {
        val r = TwoBodyCollision.resolve(2.0, 1.0, Vec3(3.0, 0.0, 0.0), Vec3.ZERO, nx, 0.5)
        assertEquals(1.5, r.v1After.x, 1e-9)
        assertEquals(3.0, r.v2After.x, 1e-9)
        assertEquals(9.0, r.keBefore, 1e-9)
        assertEquals(6.75, r.keAfter, 1e-9)
    }

    @Test
    fun momentumConservedAcrossConfigs() {
        val configs = listOf(
            Triple(1.0, 1.0, 1.0),
            Triple(2.0, 5.0, 0.3),
            Triple(0.5, 3.0, 0.0),
            Triple(7.0, 0.2, 0.9),
        )
        for ((m1, m2, e) in configs) {
            val r = TwoBodyCollision.resolve(m1, m2, Vec3(4.0, 1.0, -2.0), Vec3(-1.0, 0.5, 3.0), nx, e)
            val dp = (r.momentumAfter - r.momentumBefore).norm()
            assertTrue("动量应守恒（±5% 判据，实际应≈0）", dp <= 0.05 * r.momentumBefore.norm() + 1e-9)
            assertTrue(dp <= 1e-9)
        }
    }

    @Test
    fun cmEnergyRatioEqualsRestitutionSquared() {
        for (e in listOf(0.0, 0.25, 0.5, 0.9, 1.0)) {
            val r = TwoBodyCollision.resolve(2.0, 1.0, Vec3(3.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0), nx, e)
            val (before, after) = cmKe(r, 2.0, 1.0)
            val ratio = after / before
            assertTrue("e=$e 时质心系动能比应=e²（±5%）", abs(ratio - e * e) <= 0.05 * e * e + 1e-9)
        }
    }

    @Test
    fun totalEnergyNeverIncreases() {
        for (e in listOf(0.0, 0.4, 1.0)) {
            val r = TwoBodyCollision.resolve(3.0, 1.0, Vec3(2.0, 0.0, 0.0), Vec3(-2.0, 0.0, 0.0), nx, e)
            assertTrue(r.keAfter <= r.keBefore + 1e-9)
        }
    }

    @Test
    fun separatingBodiesNoCollision() {
        val r = TwoBodyCollision.resolve(1.0, 1.0, Vec3(-1.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), nx, 1.0)
        assertFalse(r.collided)
        assertEquals(Vec3(-1.0, 0.0, 0.0), r.v1After)
        assertEquals(Vec3(1.0, 0.0, 0.0), r.v2After)
    }

    @Test
    fun tangentialComponentsPreserved() {
        val r = TwoBodyCollision.resolve(1.0, 1.0, Vec3(1.0, 2.0, 0.0), Vec3(-1.0, 3.0, 0.0), nx, 1.0)
        assertEquals(2.0, r.v1After.y, 1e-9)
        assertEquals(3.0, r.v2After.y, 1e-9)
        assertEquals(-1.0, r.v1After.x, 1e-9)
        assertEquals(1.0, r.v2After.x, 1e-9)
    }

    @Test
    fun obliqueNormalWorks() {
        val n = Vec3(1.0, 1.0, 0.0)
        val r = TwoBodyCollision.resolve(1.0, 1.0, Vec3(2.0, 0.0, 0.0), Vec3.ZERO, n, 1.0)
        val u1 = Vec3(2.0, 0.0, 0.0).dot(n.normalized())
        assertEquals(u1, r.v2After.dot(n.normalized()), 1e-9)
        assertEquals(0.0, r.v1After.dot(n.normalized()), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun restitutionAboveOneRejected() {
        TwoBodyCollision.resolve(1.0, 1.0, Vec3.UNIT_X, Vec3.ZERO, nx, 1.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroMassRejected() {
        TwoBodyCollision.resolve(0.0, 1.0, Vec3.UNIT_X, Vec3.ZERO, nx, 0.5)
    }

    @Test
    fun sameInputTwiceIdentical() {
        val args = { TwoBodyCollision.resolve(2.0, 1.0, Vec3(3.0, 1.0, 0.0), Vec3(-1.0, 0.0, 2.0), nx, 0.6) }
        assertEquals(args(), args())
    }

    @Test
    fun resultCarriesAssumptionAndNote() {
        val r = TwoBodyCollision.resolve(1.0, 1.0, Vec3.UNIT_X, Vec3.ZERO, nx, 0.5)
        assertTrue(r.assumption.isNotBlank())
        assertEquals("模拟非测量", r.note)
    }
}