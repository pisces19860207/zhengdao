// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百九十五批 · 关闭态基线：686 既有夹具完整输出摘要。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.PoseValidity
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.TwoBodyCollision
import com.example.zhengdao.baopu.state.geometry.Vec3
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class CompoundBaselineSnapshotTest {

    @Test
    fun 既有夹具关闭态摘要固定() {
        CompoundWiring.install()
        try {
            val text = compoundBaselineSnapshot()
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            println("BASELINE_BYTES=${text.toByteArray(Charsets.UTF_8).size}")
            println("BASELINE_SHA256=$digest")
            // 2026-11-27 重钉（emit #340 / ci #2189）：915 把内核边界显式化后，既有夹具的输出摘要
            // 有两处**有意的**变化——① 同心等球不再造伪深度（degenerate，depth 0）；
            // ② PoseValidity 对 compound 读数走 status 分支（Unsupported 另有 detail）。
            // 旧 79f6d858… 是 915 之前的口径；新 71c0a9cc… 与 CI 实测逐字相同（两台机器同值，非漂移）。
            assertEquals("71c0a9ccca62fa9ea1f1f3628dc0f3c6d0234764187ccb72fc2d79a2b749ddab", digest)
        } finally {
            CompoundWiring.detach()
        }
    }
}

internal fun compoundBaselineSnapshot(): String {
    val body = CavityFixtures.fromBody()
    val (chain, pose) = CavityFixtures.bodyChain()
    val adjacent = CavityFixtures.bodyAdjacent(body, CavityFixtures.anatomyZones())
    val anchors = body.associate { it.id to "root" }
    val env = CavityFixtures.groundEnv()
    val sandy = Environment(0.0, 0.3)
    val beyond = CavityFixtures.beyondLimitPose(chain)
    val pair = CavityFixtures.pairOfSpheres
    val sections = linkedMapOf(
        "body-proxies" to body.toString(),
        "body-simplify" to ProxySimplify.fromBody(CavityFixtures.bodyProxies()).toString(),
        "body-pose" to PoseValidity.check(body.map { SegmentProxy(it.id, it.shape) }, env, pose, adjacent).toString(),
        "body-legality" to ProxyLegality.check(body, env, chain, pose, anchors, adjacent).toString(),
        "body-cavity" to CavityMap.of(body, CavityFixtures.anatomyZones()).toString(),
        "body-broadphase" to Broadphase.fine(body).toString(),
        "body-contact" to ContactProbe.readAll(body).toString(),
        "body-gravity" to GravityContact.settle(body, env).toString(),
        "body-rest" to GravityContact.restProxies(body, env).toString(),
        "sandy-pose" to PoseValidity.check(body.map { SegmentProxy(it.id, it.shape) }, sandy, pose, adjacent).toString(),
        "limit-pose" to PoseValidity.check(body.map { SegmentProxy(it.id, it.shape) }, env, beyond, adjacent).toString(),
        "limit-legality" to ProxyLegality.check(body, env, chain, beyond, anchors, adjacent).toString(),
        "pair-pose" to PoseValidity.check(listOf(pair.first, pair.second).map { SegmentProxy(it.id, it.shape) }, env).toString(),
        "pair-gap" to CavityFixtures.signedGapCm(pair.first, pair.second).toString(),
        "two-body" to TwoBodyCollision.resolve(
            1.0, 1.0, Vec3.UNIT_X, -Vec3.UNIT_X, Vec3.UNIT_X, 1.0,
        ).toString(),
        "analytic-ground" to PoseValidity.check(
            listOf(SegmentProxy("foot", CavityFixtures.sphere("foot", Vec3(0.0, 0.5, 0.0)).shape)),
            env,
        ).toString(),
    )
    return sections.entries.joinToString("\n") { (name, value) -> "$name\n$value" }
}
