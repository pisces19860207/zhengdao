// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十五批 · 形轨.几何判定：凸体形状与支撑函数。
// 四类＋球：凸包 / 胶囊 / 椭球 / 盒（球是椭球退化，单列便于解析对拍）。
// 支撑函数 support(dir) = 形状上沿 dir 方向最远的点，是 GJK/EPA 的唯一入口。
package com.example.zhengdao.baopu.state.geometry

import kotlin.math.sqrt

/** 凸体：只暴露支撑函数。GJK/EPA 不关心形状内部表示。 */
interface ConvexShape {
    fun support(dir: Vec3): Vec3
}

/** 球：center + r·unit(dir)。 */
data class Sphere(val center: Vec3, val radius: Double) : ConvexShape {
    init {
        require(radius >= 0.0) { "球半径非负" }
    }

    override fun support(dir: Vec3) = center + dir.normalized() * radius
}

/** 凸包：顶点集（调用方保证是凸的；支撑函数对任意点集都返回凸包上的点）。 */
data class ConvexHull(val vertices: List<Vec3>) : ConvexShape {
    init {
        require(vertices.isNotEmpty()) { "凸包至少一个顶点" }
    }

    override fun support(dir: Vec3): Vec3 {
        var best = vertices[0]
        var bestDot = best.dot(dir)
        for (v in vertices.drop(1)) {
            val d = v.dot(dir)
            if (d > bestDot) {
                best = v
                bestDot = d
            }
        }
        return best
    }
}

/** 胶囊：线段 a–b 外扩半径 r（圆柱＋两端半球）。 */
data class Capsule(val a: Vec3, val b: Vec3, val radius: Double) : ConvexShape {
    init {
        require(radius >= 0.0) { "胶囊半径非负" }
    }

    override fun support(dir: Vec3): Vec3 {
        val end = if (a.dot(dir) >= b.dot(dir)) a else b
        return end + dir.normalized() * radius
    }
}

/** 椭球：中心＋三半轴长＋朝向（默认轴对齐）。支撑点用缩放球面映射闭式。 */
data class Ellipsoid(
    val center: Vec3,
    val radii: Vec3,
    val rot: Rot3 = Rot3.IDENTITY,
) : ConvexShape {
    init {
        require(radii.x >= 0.0 && radii.y >= 0.0 && radii.z >= 0.0) { "椭球半轴非负" }
    }

    override fun support(dir: Vec3): Vec3 {
        val l = rot.transposeTimes(dir)
        val sx = radii.x * radii.x * l.x
        val sy = radii.y * radii.y * l.y
        val sz = radii.z * radii.z * l.z
        // 分母是 ‖r·l‖（Σ ri²li² 开方），不是 ‖r²·l‖——否则半径被约掉。
        val n = sqrt(l.x * sx + l.y * sy + l.z * sz)
        if (n == 0.0) return center
        return center + rot.times(Vec3(sx / n, sy / n, sz / n))
    }
}

/** 盒：中心＋三半宽＋朝向（默认轴对齐）。支撑点逐轴取符号。 */
data class Box(
    val center: Vec3,
    val half: Vec3,
    val rot: Rot3 = Rot3.IDENTITY,
) : ConvexShape {
    init {
        require(half.x >= 0.0 && half.y >= 0.0 && half.z >= 0.0) { "盒半宽非负" }
    }

    override fun support(dir: Vec3): Vec3 {
        val l = rot.transposeTimes(dir)
        val local = Vec3(
            if (l.x >= 0.0) half.x else -half.x,
            if (l.y >= 0.0) half.y else -half.y,
            if (l.z >= 0.0) half.z else -half.z,
        )
        return center + rot.times(local)
    }
}

/** 形状最低点（沿 -Y 支撑），姿态合法性判穿地穿沙用。 */
fun ConvexShape.lowestY(): Double = support(-Vec3.UNIT_Y).y