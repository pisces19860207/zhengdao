package com.example.zhengdao.baopu.state.geometry

import kotlin.math.PI
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 655 判据：可达性＝闭式＋采样三态（只判能不能到，不是 IK）。 */
class ReachabilityTest {

    private fun chain(lengths: List<Double>, limits: List<JointLimit>): JointChain =
        JointChain(lengths.zip(limits) { l, lim -> JointSpec("j", l, Vec3.UNIT_Z, lim) })

    private val full = JointLimit(-PI, PI)

    @Test
    fun twoLinkReachableInsideAnnulus() {
        val c = chain(listOf(1.0, 1.0), listOf(full, full))
        val r = Reachability.ofChain(c, 1.5)
        assertEquals(Reachability.State.REACHABLE, r.state)
        assertEquals(0, r.samplesTried)
    }

    @Test
    fun twoLinkTooFarUnreachable() {
        val c = chain(listOf(1.0, 1.0), listOf(full, full))
        assertEquals(Reachability.State.UNREACHABLE, Reachability.ofChain(c, 2.5).state)
    }

    @Test
    fun twoLinkTooCloseUnreachable() {
        val c = chain(listOf(2.0, 1.0), listOf(full, full))
        assertEquals(Reachability.State.UNREACHABLE, Reachability.ofChain(c, 0.2).state)
    }

    @Test
    fun twoLinkLimitBlocksOtherwiseReachableDistance() {
        val c = chain(listOf(1.0, 1.0), listOf(full, JointLimit(0.0, PI / 2.0)))
        assertEquals(Reachability.State.UNREACHABLE, Reachability.ofChain(c, 0.8).state)
        assertEquals(Reachability.State.REACHABLE, Reachability.ofChain(c, 1.8).state)
        assertEquals(Reachability.State.REACHABLE, Reachability.ofChain(c, sqrt(2.0)).state)
    }

    @Test
    fun twoLinkStraightOnlyLimit() {
        val c = chain(listOf(1.0, 1.0), listOf(full, JointLimit(0.0, 0.0)))
        assertEquals(Reachability.State.REACHABLE, Reachability.ofChain(c, 2.0).state)
        assertEquals(Reachability.State.UNREACHABLE, Reachability.ofChain(c, 1.5).state)
    }

    @Test
    fun oneLinkExactDistance() {
        val c = chain(listOf(2.0), listOf(full))
        assertEquals(Reachability.State.REACHABLE, Reachability.ofChain(c, 2.0).state)
        assertEquals(Reachability.State.UNREACHABLE, Reachability.ofChain(c, 1.0).state)
    }

    @Test
    fun threeLinkSamplingFindsReachable() {
        val c = chain(listOf(1.0, 1.0, 1.0), listOf(full, full, full))
        val r = Reachability.ofChain(c, 2.5)
        assertEquals(Reachability.State.REACHABLE, r.state)
        assertTrue(r.samplesTried >= 1)
        assertTrue(r.bestErrorCm <= 0.5)
    }

    @Test
    fun threeLinkBeyondTotalClosedUnreachable() {
        val c = chain(listOf(1.0, 1.0, 1.0), listOf(full, full, full))
        val r = Reachability.ofChain(c, 3.5)
        assertEquals(Reachability.State.UNREACHABLE, r.state)
        assertEquals(0, r.samplesTried)
    }

    @Test
    fun samplingMissIsUnknownNotUnreachable() {
        val zero = JointLimit(0.0, 0.0)
        val c = chain(listOf(1.0, 1.0, 1.0), listOf(zero, zero, zero))
        val r = Reachability.ofChain(c, 2.0)
        assertEquals(Reachability.State.UNKNOWN, r.state)
        assertEquals(1024, r.samplesTried)
        assertEquals(1.0, r.bestErrorCm, 1e-9)
    }

    @Test
    fun toleranceWidensReachableBand() {
        val c = chain(listOf(1.0, 1.0), listOf(full, full))
        assertEquals(Reachability.State.UNREACHABLE, Reachability.ofChain(c, 2.2, tolCm = 0.1).state)
        assertEquals(Reachability.State.REACHABLE, Reachability.ofChain(c, 2.2, tolCm = 0.3).state)
    }

    @Test
    fun sameInputTwiceIdentical() {
        val c = chain(listOf(1.0, 1.0, 1.0), listOf(full, full, full))
        assertEquals(Reachability.ofChain(c, 2.5), Reachability.ofChain(c, 2.5))
    }

    @Test
    fun resultCarriesAssumptionAndNote() {
        val c = chain(listOf(1.0, 1.0), listOf(full, full))
        val r = Reachability.ofChain(c, 1.5)
        assertTrue(r.assumption.isNotBlank())
        assertEquals("模拟非测量", r.note)
    }
}