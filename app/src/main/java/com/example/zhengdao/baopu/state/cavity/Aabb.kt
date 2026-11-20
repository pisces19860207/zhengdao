// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 形轨.器官代理与腔隙碰撞：粗剔用的轴对齐包围盒。
// 只由 655 的支撑函数在六轴上取值算出——对任意凸体这是**精确**包围盒，不是估计。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.Vec3

/** 轴对齐包围盒（厘米）。 */
data class Aabb(val min: Vec3, val max: Vec3) {

    init {
        require(min.x <= max.x && min.y <= max.y && min.z <= max.z) { "包围盒反转：$min / $max" }
    }

    /** 三轴区间两两相交；边界相切算相交——粗剔宁多不漏（判据②假阴性率 0 的根据）。 */
    fun overlaps(o: Aabb): Boolean =
        min.x <= o.max.x && o.min.x <= max.x &&
            min.y <= o.max.y && o.min.y <= max.y &&
            min.z <= o.max.z && o.min.z <= max.z

    /** 只看水平投影（重力落定沿竖直轴求根时用）。 */
    fun overlapsXz(o: Aabb): Boolean =
        min.x <= o.max.x && o.min.x <= max.x && min.z <= o.max.z && o.min.z <= max.z

    fun contains(p: Vec3): Boolean =
        p.x in min.x..max.x && p.y in min.y..max.y && p.z in min.z..max.z

    fun center(): Vec3 = (min + max) * 0.5

    /** 体对角线：ε 的归一单位，也是代理体大小的整数化前度量。 */
    fun diagonalCm(): Double = (max - min).norm()

    companion object {

        /** 沿三轴各取一次支撑点，得该凸体的精确包围盒。 */
        fun of(shape: ConvexShape): Aabb {
            val xLo = shape.support(-Vec3.UNIT_X).x
            val xHi = shape.support(Vec3.UNIT_X).x
            val yLo = shape.support(-Vec3.UNIT_Y).y
            val yHi = shape.support(Vec3.UNIT_Y).y
            val zLo = shape.support(-Vec3.UNIT_Z).z
            val zHi = shape.support(Vec3.UNIT_Z).z
            return Aabb(Vec3(xLo, yLo, zLo), Vec3(xHi, yHi, zHi))
        }
    }
}
