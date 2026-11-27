package com.example.zhengdao.baopu.state.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class Rot3RedlineTest {

    @Test
    fun 零轴近零轴NaN与Inf轴角_全部显式拒绝() {
        val invalid = listOf(
            Vec3.ZERO to PI / 2.0,
            Vec3(1e-13, 0.0, 0.0) to PI / 2.0,
            Vec3(Double.NaN, 0.0, 0.0) to PI / 2.0,
            Vec3(Double.POSITIVE_INFINITY, 0.0, 0.0) to PI / 2.0,
            Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE) to PI / 2.0,
            Vec3.UNIT_Z to Double.NaN,
            Vec3.UNIT_Z to Double.POSITIVE_INFINITY,
        )
        for ((axis, rad) in invalid) {
            val result = Rot3.fromAxisAngle(axis, rad)
            assertNull("axis=$axis rad=$rad 必须拒绝", result)
            if (result != null) {
                assertFalse(vec3IsFinite(result.ax) || vec3IsFinite(result.ay) || vec3IsFinite(result.az))
            }
        }
    }

    @Test
    fun 反射矩阵不得冒充正交旋转() {
        assertFalse(Rot3(Vec3.UNIT_X, Vec3.UNIT_Y, -Vec3.UNIT_Z).isOrthonormal())
    }

    @Test
    fun 非法容差不得放过反射或非有限矩阵() {
        val reflection = Rot3(Vec3.UNIT_X, Vec3.UNIT_Y, -Vec3.UNIT_Z)
        for (tolerance in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertFalse(reflection.isOrthonormal(tolerance))
            assertFalse(Rot3.IDENTITY.isOrthonormal(tolerance))
        }
    }

    @Test
    fun 合法轴角_仍为正交旋转且不改变既有公式() {
        val rotation = Rot3.fromAxisAngle(Vec3(1.0, 2.0, 3.0), 0.37)
        assertTrue(rotation?.isOrthonormal() == true)
        assertEquals(1.0, rotation!!.determinant(), 1e-15)
    }

    @Test
    fun 连续一千次旋转复合_只测漂移不自动校正() {
        val step = checkNotNull(Rot3.fromAxisAngle(Vec3(1.0, 2.0, 3.0).normalized(), 0.37))
        var rotation = Rot3.IDENTITY
        var maxOrthogonality = 0.0
        var maxNorm = 0.0
        var maxDeterminant = 0.0
        repeat(1000) {
            rotation = rotation * step
            maxOrthogonality = maxOf(
                maxOrthogonality,
                abs(rotation.ax.dot(rotation.ay)),
                abs(rotation.ay.dot(rotation.az)),
                abs(rotation.az.dot(rotation.ax)),
            )
            maxNorm = maxOf(
                maxNorm,
                abs(rotation.ax.norm() - 1.0),
                abs(rotation.ay.norm() - 1.0),
                abs(rotation.az.norm() - 1.0),
            )
            maxDeterminant = maxOf(maxDeterminant, abs(rotation.determinant() - 1.0))
        }
        assertTrue(maxOrthogonality.isFinite())
        assertTrue(maxNorm.isFinite())
        assertTrue(maxDeterminant.isFinite())
        assertTrue("千次复合漂移超出双精度合理范围：$maxOrthogonality", maxOrthogonality <= 1e-10)
        println(
            "ROT_DRIFT_915 steps=1000 maxOrthogonality=$maxOrthogonality " +
                "maxNorm=$maxNorm maxDeterminant=$maxDeterminant",
        )
    }
}
