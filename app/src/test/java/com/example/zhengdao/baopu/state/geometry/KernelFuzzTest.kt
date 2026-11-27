package com.example.zhengdao.baopu.state.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.random.Random

class KernelFuzzTest {

    @Test
    fun 一千例随机退化参数_零抛且状态显式() {
        val random = Random(915)
        val families = mutableMapOf<String, Int>()
        val statuses = mutableMapOf<String, Int>()
        var throws = 0
        var unlabeled = 0
        var d2WrongReason = 0
        var statusMismatch = 0
        var boundaryExceptions = 0
        var d1FakeDepth = 0
        val expectedGjk = mapOf(
            "D1" to KernelStatus.Degenerate,
            "D2" to KernelStatus.Success,
            "D3" to KernelStatus.Unsupported,
            "D4" to KernelStatus.Degenerate,
            "D5" to KernelStatus.Unsupported,
            "D7" to KernelStatus.Degenerate,
            "D8" to KernelStatus.Degenerate,
        )
        val expectedEpa = mapOf(
            "D1" to KernelStatus.Degenerate,
            "D2" to KernelStatus.Degenerate,
            "D3" to KernelStatus.Unsupported,
            "D4" to KernelStatus.Degenerate,
            "D5" to KernelStatus.Unsupported,
            "D7" to KernelStatus.Degenerate,
            "D8" to KernelStatus.Degenerate,
        )
        repeat(1000) { index ->
            val family = "D${index % 8 + 1}"
            families[family] = (families[family] ?: 0) + 1
            if (family == "D6") {
                val axis = when ((index / 8) % 4) {
                    0 -> Vec3.ZERO
                    1 -> Vec3(1e-13, 0.0, 0.0)
                    2 -> Vec3(Double.NaN, 0.0, 0.0)
                    else -> Vec3.UNIT_Z
                }
                val angle = if ((index / 8) % 4 == 3) Double.POSITIVE_INFINITY else PI / 2.0
                val rotation = Rot3.fromAxisAngle(axis, angle)
                if (rotation != null) unlabeled++
                statuses["D6-null"] = (statuses["D6-null"] ?: 0) + (if (rotation == null) 1 else 0)
                return@repeat
            }
            val pair = degeneratePair(family, random)
            val gjk = try {
                Gjk.distance(pair.first, pair.second)
            } catch (_: Exception) {
                throws++
                null
            }
            val epa = if (gjk == null) {
                null
            } else {
                try {
                    Epa.penetration(pair.first, pair.second)
                } catch (_: Exception) {
                    throws++
                    null
                }
            }
            if (gjk != null) {
                statuses["$family-GJK-${gjk.status}"] = (statuses["$family-GJK-${gjk.status}"] ?: 0) + 1
                if (expectedGjk[family] != gjk.status) statusMismatch++
                if (gjk.reason?.contains("边界异常") == true) boundaryExceptions++
                if (family != "D2" && gjk.status == KernelStatus.Success) unlabeled++
                if (gjk.status != KernelStatus.Success && gjk.reason.isNullOrBlank()) unlabeled++
                if (gjk.status != KernelStatus.Success && epa == null) unlabeled++
            }
            if (epa != null) {
                statuses["$family-EPA-${epa.status}"] = (statuses["$family-EPA-${epa.status}"] ?: 0) + 1
                if (expectedEpa[family] != epa.status) statusMismatch++
                if (epa.reason?.contains("边界异常") == true) boundaryExceptions++
                if (family in setOf("D1", "D2", "D7", "D8") && epa.status == KernelStatus.Success) unlabeled++
                if (epa.status != KernelStatus.Success && epa.reason.isNullOrBlank()) unlabeled++
                if (family == "D1" && (epa.depthCm != 0.0 || epa.normal != Vec3.UNIT_Y)) d1FakeDepth++
            }
            if (family == "D2" && epa?.reason != "分离近贴无穿透深度") d2WrongReason++
        }
        println("FUZZ_915 cases=1000 throws=$throws unlabeled=$unlabeled d2WrongReason=$d2WrongReason statusMismatch=$statusMismatch boundaryExceptions=$boundaryExceptions d1FakeDepth=$d1FakeDepth families=$families statuses=$statuses")
        assertEquals(1000, families.values.sum())
        assertEquals("退化 fuzz 抛异常数", 0, throws)
        assertEquals("退化 fuzz 出现未标记成功态", 0, unlabeled)
        assertEquals("D2 必须走入口带分离预判", 0, d2WrongReason)
        assertEquals("每个退化族必须返回工单指定状态", 0, statusMismatch)
        assertEquals("通用边界异常不得冒充退化验收", 0, boundaryExceptions)
        assertEquals("D1 同心球不得产生伪深度或任意法向", 0, d1FakeDepth)
        assertTrue(statuses.isNotEmpty())
    }

    private fun degeneratePair(family: String, random: Random): Pair<ConvexShape, ConvexShape> = when (family) {
        "D1" -> {
            val center = randomVec(random, 10.0)
            val radius = 10.0.pow(random.nextDouble(-3.0, 3.0))
            Sphere(center, radius) to Sphere(center, radius)
        }
        "D2" -> {
            val radius = 10.0.pow(random.nextDouble(0.0, 2.0))
            val gap = 1.1e-8 + random.nextDouble() * 8.8e-8
            Sphere(Vec3.ZERO, radius) to Sphere(Vec3(2.0 * radius + gap, 0.0, 0.0), radius)
        }
        "D3" -> {
            val large = 10.0.pow(random.nextDouble(0.0, 3.0))
            val small = large * 1e-6
            Sphere(Vec3.ZERO, large) to Sphere(randomVec(random, large), small)
        }
        "D4" -> {
            val base = randomVec(random, 10.0)
            val direction = randomVec(random, 1.0).normalized()
            val line = ConvexHull(listOf(base - direction, base, base + direction))
            line to Sphere(randomVec(random, 10.0), 10.0.pow(random.nextDouble(-2.0, 1.0)))
        }
        "D5" -> {
            Sphere(Vec3.ZERO, 1e3) to Sphere(randomVec(random, 2.0), 1e-3)
        }
        "D7" -> {
            val point = randomVec(random, 10.0)
            Capsule(point, point, 0.0) to Sphere(randomVec(random, 10.0), 0.0)
        }
        "D8" -> {
            val point = randomVec(random, 10.0)
            val circumRadius = 0.0
            val inradius = 0.0
            check(circumRadius == inradius)
            ConvexHull(listOf(point)) to Sphere(point, inradius)
        }
        else -> error("未知退化族 $family")
    }

    private fun randomVec(random: Random, scale: Double): Vec3 = Vec3(
        random.nextDouble(-scale, scale),
        random.nextDouble(-scale, scale),
        random.nextDouble(-scale, scale),
    )
}
