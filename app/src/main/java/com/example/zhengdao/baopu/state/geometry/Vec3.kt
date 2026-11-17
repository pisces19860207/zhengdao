// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十五批 · 形轨.几何判定：三维向量（不可变）。
// 只做静态几何所需的向量算术；不含任何时间积分。
package com.example.zhengdao.baopu.state.geometry

import kotlin.math.sqrt

/** 不可变三维向量。所有运算返回新实例，同输入必得同输出（确定性的一半）。 */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)

    operator fun div(k: Double) = Vec3(x / k, y / k, z / k)

    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun normSq() = dot(this)

    fun norm() = sqrt(normSq())

    /** 零向量归一化仍为零向量（调用方负责不拿零方向做支撑查询）。 */
    fun normalized(): Vec3 {
        val n = norm()
        return if (n == 0.0) ZERO else this / n
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UNIT_X = Vec3(1.0, 0.0, 0.0)
        val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        val UNIT_Z = Vec3(0.0, 0.0, 1.0)
    }
}