// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百九十五批 · 复合代理装配：关闭态零副作用、三夹具差异、候选 API。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.calibration.CalibrationWiring
import com.example.zhengdao.baopu.state.geometry.Capsule
import com.example.zhengdao.baopu.state.geometry.CompoundDecompose
import com.example.zhengdao.baopu.state.geometry.CompoundPairs
import com.example.zhengdao.baopu.state.geometry.CompoundProxy
import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.Epa
import com.example.zhengdao.baopu.state.geometry.Gjk
import com.example.zhengdao.baopu.state.geometry.LocalTransform
import com.example.zhengdao.baopu.state.geometry.PartSpec
import com.example.zhengdao.baopu.state.geometry.ProxyChunk
import com.example.zhengdao.baopu.state.geometry.Rot3
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.TwoBodyCollision
import com.example.zhengdao.baopu.state.geometry.Vec3
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompoundWiringTest {

    private data class Fixture(val name: String, val u: Vec3, val v: Vec3, val scaleCm: Double)
    private data class Case(
        val name: String,
        val single: ConvexShape,
        val compound: CompoundProxy,
        val probe: Sphere,
        val reason: String,
    )

    private val fixtures = listOf(
        Fixture("腋下", Vec3.UNIT_Y, Vec3.UNIT_X, 30.0),
        Fixture("胯", Vec3.UNIT_Y, -Vec3.UNIT_X, 36.0),
        Fixture("颈侧", Vec3.UNIT_Y, Vec3.UNIT_Z, 24.0),
    )
    private val env = Environment(-1000.0, -1000.0)
    private val chain = CavityFixtures.twoLinkChain()
    private val pose = chain.pose(listOf(0.0, 0.0))

    @Before
    fun setUp() = CompoundWiring.detach()

    @After
    fun tearDown() {
        CompoundWiring.detach()
        CalibrationWiring.resetForTest()
    }

    @Test
    fun 关闭态_完整夹具逐字节一致且注册源零读取零日志() {
        val baseline = compoundBaselineSnapshot()
        val case = caseOf(fixtures.first())
        var reads = 0
        val logs = mutableListOf<String>()
        CompoundWiring.install(
            registrations = { reads++; listOf(CompoundRegistration(case.name, case.compound)) },
            enabled = false,
            trace = { logs += it },
        )

        assertEquals(baseline, compoundBaselineSnapshot())
        assertTrue(CompoundWiring.compoundsFor(setOf(case.name)).isEmpty())
        assertEquals("关闭态连注册源都不许读", 0, reads)
        assertTrue("关闭态零日志零轨迹", logs.isEmpty())
    }

    @Test
    fun 开启态_三夹具逐例不同且日志只在实际复合查询出现() {
        val cases = fixtures.map(::caseOf)
        val singleReadings = cases.associate { it.name to selfState(legality(it), it.name) }
        val registrations = cases.map { CompoundRegistration(it.name, it.compound) }
        val logs = mutableListOf<String>()
        var reads = 0
        CompoundWiring.install(
            registrations = { reads++; registrations },
            enabled = true,
            trace = { logs += it },
        )

        val readings = mutableListOf<String>()
        val differences = cases.map { case ->
            val single = singleReadings.getValue(case.name)
            val compound = selfState(legality(case), case.name)
            val singleCm = Gjk.distance(case.single, case.probe).distanceCm
            val pair = requireNotNull(
                CompoundPairs.read(
                    SegmentProxy(case.name, case.single),
                    SegmentProxy("probe", case.probe),
                    mapOf(case.name to case.compound),
                ),
            )
            val measuredReason = "单凸体=$singleCm/$single；复合=${pair.distanceCm}/${pair.intersecting}；" +
                "最近块=${pair.nearestChunkId}；迭代=${pair.iterations}"
            println("${case.name}: $measuredReason | ${case.reason}")
            assertFalse("${case.name} 单凸体应假命中", single == LegalityState.LEGAL)
            assertEquals("${case.name} 复合应放行凹口探针", LegalityState.LEGAL, compound)
            assertFalse("${case.name} 复合查询应分离", pair.intersecting)
            assertTrue("${case.name} 复合距离必须为正", pair.distanceCm > 0.0)
            readings += "$single→$compound;$singleCm→${pair.distanceCm}"
            case.name to measuredReason
        }

        assertEquals("三例读数须逐例不同", 3, readings.distinct().size)
        assertEquals("三例实测原因须逐例不同", 3, differences.map { it.second }.distinct().size)
        assertEquals(3, logs.size)
        assertTrue(logs.all { it.startsWith("[compound]") })
        logs.forEach(::println)
        assertEquals("三例校验各读一次来源", 3, reads)

        val first = cases.first()
        val repeat = selfState(legality(first), first.name)
        assertEquals(LegalityState.LEGAL, repeat)
        assertEquals(logs.first(), logs.last())
        assertEquals("三次校验加一次复跑各读一次来源", 4, reads)
        verifyProductionCompoundSides(first, logs)
    }

    @Test
    fun 开启态_相邻名单跳过时不写复合日志() {
        val case = caseOf(fixtures.first())
        val logs = mutableListOf<String>()
        var reads = 0
        CompoundWiring.install(
            registrations = { reads++; listOf(CompoundRegistration(case.name, case.compound)) },
            enabled = true,
            trace = { logs += it },
        )

        assertEquals(
            LegalityState.LEGAL,
            selfState(legality(case, adjacent = setOf(case.name to "probe")), case.name),
        )
        assertEquals(1, reads)
        assertTrue("没有实际查询就不许写轨迹", logs.isEmpty())
    }

    @Test
    fun 开启态_缺失注册返回absent不造代理() {
        val case = caseOf(fixtures.first())
        val logs = mutableListOf<String>()
        CompoundWiring.install(
            registrations = { listOf(CompoundRegistration(case.name, case.compound)) },
            enabled = true,
            trace = { logs += it },
        )
        assertTrue(CompoundWiring.compoundsFor(setOf("absent")).isEmpty())
        assertTrue(logs.isEmpty())
    }

    @Test
    fun 校准provider缝_关闭时零读取_开启后只读真实注册() {
        val case = caseOf(fixtures.first())
        var reads = 0
        CalibrationWiring.compoundProvider = { reads++; listOf(CompoundRegistration(case.name, case.compound)) }
        CompoundWiring.install(
            registrations = { CalibrationWiring.compoundProvider?.invoke().orEmpty() },
            enabled = false,
        )
        assertTrue(CompoundWiring.compoundsFor(setOf(case.name)).isEmpty())
        assertEquals(0, reads)

        CompoundWiring.install(
            registrations = { CalibrationWiring.compoundProvider?.invoke().orEmpty() },
            enabled = true,
        )
        assertEquals(case.compound, CompoundWiring.compoundsFor(setOf(case.name))[case.name])
        assertEquals(1, reads)
    }

    @Test
    fun 开启态_凹深零到二档只按既有注册块数接收() {
        for (tier in 0..2) {
            val id = "tier-$tier"
            val spec = PartSpec(id, Vec3.ZERO, Vec3.UNIT_Y, Vec3.UNIT_X, 30.0, tier, 0, 0, 0)
            val compound = CompoundDecompose.decompose(spec)
            CompoundWiring.install(
                registrations = { listOf(CompoundRegistration(id, compound)) },
                enabled = true,
            )
            val read = requireNotNull(
                CompoundPairs.read(
                    SegmentProxy(id, compound.chunks.first().shape),
                    SegmentProxy("probe", Sphere(Vec3(100.0, 0.0, 0.0), 1.0)),
                    CompoundWiring.compoundsFor(setOf(id)),
                ),
            )
            assertEquals("档位 $tier 只接收 862 已生成的块数", tier + 2, read.chunkCount)
            CompoundWiring.detach()
        }
    }

    @Test
    fun TwoBodyCollision候选_复合不命中挡碰撞_命中仍走闭式解() {
        val case = caseOf(fixtures.first())
        val logs = mutableListOf<String>()
        CompoundWiring.install(
            registrations = { listOf(CompoundRegistration(case.name, case.compound)) },
            enabled = true,
            trace = { logs += it },
        )
        val miss = resolve { CompoundWiring.candidate(case.name, "probe", case.single, case.probe) }
        assertFalse(miss.collided)

        val overlap = Sphere(Vec3.ZERO, 20.0)
        val hit = resolve { CompoundWiring.candidate(case.name, "probe", case.single, overlap) }
        val expected = TwoBodyCollision.resolve(1.0, 1.0, Vec3.UNIT_X, -Vec3.UNIT_X, Vec3.UNIT_X, 1.0)
        assertEquals(expected, hit)
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.startsWith("[compound]") })
    }

    private fun verifyProductionCompoundSides(case: Case, logs: MutableList<String>) {
        val probe = CompoundProxy(
            "probe",
            listOf(
                ProxyChunk("probe-near", case.probe, LocalTransform.IDENTITY),
                ProxyChunk(
                    "probe-far",
                    Sphere(case.probe.center + Vec3(100.0, 0.0, 0.0), case.probe.radius),
                    LocalTransform.IDENTITY,
                ),
            ),
        )
        val body = CompoundRegistration(case.name, case.compound)
        val other = CompoundRegistration("probe", probe)
        val before = logs.size
        CompoundWiring.install(
            registrations = { listOf(body, other) },
            enabled = true,
            trace = { logs += it },
        )
        assertEquals("双侧真实注册应放行", LegalityState.LEGAL, selfState(legality(case), case.name))
        assertEquals("双侧实际查询两条轨迹", 2, logs.size - before)

        verifyRightCompound(case, logs)
    }

    private fun verifyRightCompound(case: Case, logs: MutableList<String>) {
        val probe = rightProbe(case)
        CompoundWiring.install(
            registrations = { listOf(CompoundRegistration("probe", probe)) },
            enabled = true,
            trace = { logs += it },
        )
        val before = logs.size
        val compounds = CompoundWiring.compoundsFor(setOf(case.name, "probe"))
        val read = requireNotNull(
            CompoundPairs.read(
                SegmentProxy(case.name, case.single),
                SegmentProxy("probe", case.probe),
                compounds,
            ),
        )
        val depths = probe.chunks.map { chunk ->
            val sphere = chunk.shape as Sphere
            val world = Sphere(chunk.transform.worldPoint(sphere.center), sphere.radius)
            Epa.penetration(case.single, world)?.depthCm ?: 0.0
        }
        val positiveDepths = depths.filter { it > 0.0 }
        assertEquals("右侧两块须正穿透且深度不同", 2, positiveDepths.distinct().size)
        assertTrue(read.intersecting)
        assertEquals(0.0, read.distanceCm, 0.0)
        assertEquals(depths.max(), read.depthCm, 1e-3)
        assertEquals(2, read.chunkCount)
        assertEquals("右侧真实注册应判碰撞", LegalityState.ILLEGAL, selfState(legality(case), case.name))
        assertEquals("直接查询与 legality 各一条轨迹", 2, logs.size - before)
        println("RIGHT_SINGLE distance=${read.distanceCm} depth=${read.depthCm} depths=$depths chunks=${read.chunkCount}")
    }

    private fun rightProbe(case: Case): CompoundProxy {
        val capsule = case.single as Capsule
        val axis = (capsule.b - capsule.a).normalized()
        val radial = axis.cross(Vec3.UNIT_X).normalized()
        val nearCenter = capsule.a + axis * 4.0 + radial * 6.0
        val farCenter = capsule.a + axis * 7.0 + radial * 5.5
        val nearLocal = Vec3(1.0, 2.0, 0.0)
        val farLocal = Vec3(-2.0, 1.0, 0.0)
        val nearRotation = checkNotNull(Rot3.fromAxisAngle(Vec3.UNIT_Z, Math.PI / 2.0))
        val farRotation = checkNotNull(Rot3.fromAxisAngle(Vec3.UNIT_X, Math.PI / 3.0))
        return CompoundProxy(
            "probe",
            listOf(
                ProxyChunk(
                    "right-near",
                    Sphere(Vec3.ZERO, 1.0),
                    LocalTransform(nearCenter - nearRotation.times(nearLocal), nearRotation),
                ),
                ProxyChunk(
                    "right-far",
                    Sphere(Vec3.ZERO, 1.5),
                    LocalTransform(farCenter - farRotation.times(farLocal), farRotation),
                ),
            ),
        )
    }

    private fun resolve(candidate: () -> Boolean) = TwoBodyCollision.resolve(
        1.0, 1.0, Vec3.UNIT_X, -Vec3.UNIT_X, Vec3.UNIT_X, 1.0, candidate = candidate,
    )

    private fun caseOf(f: Fixture): Case {
        val bis = (f.u.normalized() + f.v.normalized()).normalized()
        val side = f.u.cross(f.v).normalized()
        val single = Capsule(bis * -1.5, bis * 10.5, 6.6)
        val probe = Sphere(bis + side * 5.0, 0.6)
        val spec = PartSpec(f.name, Vec3.ZERO, f.u, f.v, f.scaleCm, 2, 3, 2, 1)
        return Case(
            f.name, single, CompoundDecompose.decompose(spec), probe,
            "标尺=${f.scaleCm}cm；探针沿 ${f.u}×${f.v} 的侧向凹口偏离，单凸体填平凹口",
        )
    }

    private fun legality(
        case: Case,
        adjacent: Set<Pair<String, String>> = emptySet(),
    ): List<LegalityReading> {
        val proxies = listOf(
            OrganProxy(case.name, ProxyKind.CAPSULE, case.single),
            CavityFixtures.sphere("probe", case.probe.center, case.probe.radius),
        )
        return ProxyLegality.check(
            proxies, env, chain, pose,
            mapOf(case.name to "root", "probe" to "root"), adjacent,
        )
    }

    private fun selfState(readings: List<LegalityReading>, id: String): LegalityState =
        readings.single { it.proxyId == id && it.item == LegalityItem.SELF_COLLISION }.state
}
