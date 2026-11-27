package com.example.zhengdao.baopu.state.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KernelScaleMatrixTest {

    private data class SphereRow(
        val scale: Double,
        val ratio: Double,
        val mode: String,
        val gjkStatus: KernelStatus,
        val hit: Boolean,
        val distanceBits: String,
        val epaStatus: String,
        val depthBits: String,
        val tetra: String,
        val threw: Boolean,
    )

    private data class FlatRow(
        val scale: Double,
        val thickness: Double,
        val gjkStatus: KernelStatus,
        val hit: Boolean,
        val epaStatus: String,
        val depthBits: String,
        val tetra: String,
        val threw: Boolean,
    )

    @Test
    fun 跨尺度球矩阵_记录真实分支误判与异常() {
        val rows = mutableListOf<SphereRow>()
        var throws = 0
        for (scale in listOf(1e-3, 1e-1, 1.0, 10.0, 1e3)) {
            for (ratio in listOf(1.0, 10.0, 1e-6)) {
                for (mode in listOf("overlap", "contact", "contact-band", "entry-band", "outside")) {
                    val rA = scale
                    val rB = scale * ratio
                    val gap = when (mode) {
                        "contact" -> 0.0
                        "contact-band" -> 5e-9
                        "entry-band" -> 5e-8
                        "outside" -> 2e-7
                        else -> -0.5 * minOf(rA, rB)
                    }
                    val a = Sphere(Vec3.ZERO, rA)
                    val b = Sphere(Vec3(rA + rB + gap, 0.0, 0.0), rB)
                    try {
                        val gjk = Gjk.distance(a, b)
                        val epa = Epa.penetration(a, b)
                        val trace = Epa.initialTetraTrace(a, b)
                        rows += SphereRow(
                            scale, ratio, mode, gjk.status, gjk.intersecting, bits(gjk.distanceCm),
                            epa?.status?.name ?: "null", epa?.let { bits(it.depthCm) } ?: "null",
                            trace.source.name, false,
                        )
                    } catch (_: Exception) {
                        throws++
                        rows += SphereRow(
                            scale, ratio, mode, KernelStatus.Unsupported, false, "throw",
                            "throw", "throw", "throw", true,
                        )
                    }
                }
            }
        }
        var mismatches = 0
        for (row in rows) {
            if (row.threw || sphereMismatch(row)) mismatches++
        }
        println("SPHERE_MATRIX_915 rows=${rows.size} throws=$throws mismatches=$mismatches")
        println("scale|ratio|mode|gjk|hit|distanceBits|epa|depthBits|tetra|throw")
        for (row in rows) {
            println(
                "${row.scale}|${row.ratio}|${row.mode}|${row.gjkStatus}|${row.hit}|${row.distanceBits}|" +
                    "${row.epaStatus}|${row.depthBits}|${row.tetra}|${row.threw}",
            )
        }
        assertEquals(75, rows.size)
        assertEquals(0, throws)
        assertEquals(0, mismatches)
        assertEquals(25, rows.count { it.ratio == 1e-6 && it.gjkStatus == KernelStatus.Unsupported })
        assertTrue(rows.all { !it.threw })
    }

    @Test
    fun 极扁矩阵_记录绝对体积阈值下的分支与深度偏差() {
        val rows = mutableListOf<FlatRow>()
        var throws = 0
        for (scale in listOf(1e-3, 1.0, 1e3)) {
            for (thickness in listOf(1e-3, 1e-6, 1e-9, 1e-12, 1e-15)) {
                val a = Box(Vec3.ZERO, Vec3(scale, scale, thickness / 2.0))
                val b = Box(Vec3(0.0, 0.0, thickness / 2.0), Vec3(scale, scale, thickness / 2.0))
                try {
                    val gjk = Gjk.distance(a, b)
                    val epa = Epa.penetration(a, b)
                    val trace = Epa.initialTetraTrace(a, b)
                    rows += FlatRow(
                        scale, thickness, gjk.status, gjk.intersecting,
                        epa?.status?.name ?: "null", epa?.let { bits(it.depthCm) } ?: "null",
                        trace.source.name, false,
                    )
                } catch (_: Exception) {
                    throws++
                    rows += FlatRow(
                        scale, thickness, KernelStatus.Unsupported, false,
                        "throw", "throw", "throw", true,
                    )
                }
            }
        }
        var mismatches = 0
        for (row in rows) {
            val depth = row.depthBits.toLongOrNull(16)?.let { Double.fromBits(it) }
            val expected = row.thickness / 2.0
            val tolerance = maxOf(1e-18, expected * 1e-9)
            if (row.threw || !row.hit || row.epaStatus == "null" ||
                row.epaStatus == KernelStatus.Unsupported.name || depth == null || abs(depth - expected) > tolerance
            ) {
                mismatches++
            }
        }
        assertEquals(15, rows.size)
        assertEquals(0, throws)
        println("FLAT_MATRIX_915 rows=${rows.size} throws=$throws mismatches=$mismatches")
        assertEquals("绝对体积阈值的已知尺度敏感格必须保持可观测", 6, mismatches)
        println("scale|thickness|gjk|hit|epa|depthBits|tetra|throw")
        for (row in rows) {
            println(
                "${row.scale}|${row.thickness}|${row.gjkStatus}|${row.hit}|${row.epaStatus}|" +
                    "${row.depthBits}|${row.tetra}|${row.threw}",
            )
        }
        assertTrue(rows.all { !it.threw })
    }

    private fun sphereMismatch(row: SphereRow): Boolean {
        if (row.ratio == 1e-6) return row.gjkStatus != KernelStatus.Unsupported
        if (row.mode == "outside") return row.hit || row.epaStatus != "null"
        if (row.epaStatus == "null" || row.epaStatus == KernelStatus.Unsupported.name) return true
        val depth = row.depthBits.toLongOrNull(16)?.let { Double.fromBits(it) } ?: return true
        return when (row.mode) {
            "overlap" -> !row.hit || row.epaStatus != KernelStatus.Success.name ||
                abs(depth - 0.5 * row.scale * minOf(1.0, row.ratio)) > maxOf(1e-12, depth * 1e-6)
            "contact", "contact-band" -> !row.hit || abs(depth) > 1e-12
            "entry-band" -> row.hit || abs(depth) > 0.0
            else -> true
        }
    }

    private fun bits(value: Double): String = value.toRawBits().toString(16).padStart(16, '0')
}
