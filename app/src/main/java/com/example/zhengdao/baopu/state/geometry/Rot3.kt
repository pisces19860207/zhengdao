// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十五批 · 形轨.几何判定：旋转矩阵（三个单位正交列向量）。
// 只用于形状朝向与关节链正向运动学；不含任何时间积分。
package com.example.zhengdao.baopu.state.geometry

import kotlin.math.cos
import kotlin.math.sin

/** 旋转矩阵，按列存（ax/ay/az 是局部坐标轴在世界系下的方向）。 */
data class Rot3(val ax: Vec3, val ay: Vec3, val az: Vec3) {

    /** 世界向量 = 矩阵 × 局部向量。 */
    fun times(v: Vec3) = ax * v.x + ay * v.y + az * v.z

    /** 局部向量 = 转置 × 世界向量（正交矩阵的逆＝转置）。 */
    fun transposeTimes(v: Vec3) = Vec3(v.dot(ax), v.dot(ay), v.dot(az))

    operator fun times(o: Rot3) = Rot3(times(o.ax), times(o.ay), times(o.az))

    fun determinant(): Double = ax.dot(ay.cross(az))

    fun isOrthonormal(tolerance: Double = 1e-9): Boolean =
        tolerance.isFinite() && tolerance >= 0.0 &&
            vec3IsFinite(ax) && vec3IsFinite(ay) && vec3IsFinite(az) &&
            kotlin.math.abs(ax.normSq() - 1.0) <= tolerance &&
            kotlin.math.abs(ay.normSq() - 1.0) <= tolerance &&
            kotlin.math.abs(az.normSq() - 1.0) <= tolerance &&
            kotlin.math.abs(ax.dot(ay)) <= tolerance &&
            kotlin.math.abs(ay.dot(az)) <= tolerance &&
            kotlin.math.abs(az.dot(ax)) <= tolerance &&
            kotlin.math.abs(determinant() - 1.0) <= tolerance

    companion object {
        val IDENTITY = Rot3(Vec3.UNIT_X, Vec3.UNIT_Y, Vec3.UNIT_Z)

        /** 绕单位轴 axis 转 rad（Rodrigues 公式，闭式）；无效轴或角度返回 null。 */
        fun fromAxisAngle(axis: Vec3, rad: Double): Rot3? {
            if (!vec3IsFinite(axis) || !rad.isFinite()) return null
            val norm = axis.norm()
            if (!norm.isFinite() || norm <= 1e-12) return null
            val a = axis.normalized()
            val c = cos(rad)
            val s = sin(rad)
            val t = 1.0 - c
            return Rot3(
                Vec3(c + a.x * a.x * t, a.x * a.y * t + a.z * s, a.x * a.z * t - a.y * s),
                Vec3(a.x * a.y * t - a.z * s, c + a.y * a.y * t, a.y * a.z * t + a.x * s),
                Vec3(a.x * a.z * t + a.y * s, a.y * a.z * t - a.x * s, c + a.z * a.z * t),
            )
        }
    }
}