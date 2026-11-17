// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十五批 · 形轨.几何判定：两体解析碰撞（闭式）。
// 沿碰撞法向的一维正碰公式：动量守恒＋恢复系数 e∈[0,1]；切向分量不变
// （无摩擦假设）。不做顺序冲量、不做接触流形、不做时间积分——一次代入，
// 直接出碰后速度，不是求解器迭代。
package com.example.zhengdao.baopu.state.geometry

/** 两体碰撞读数：碰后速度＋碰前/碰后动量与动能（核验守恒用）。 */
data class CollisionResult(
    val v1After: Vec3,
    val v2After: Vec3,
    val momentumBefore: Vec3,
    val momentumAfter: Vec3,
    val keBefore: Double,
    val keAfter: Double,
    val collided: Boolean,
    val assumption: String = "两体闭式正碰：沿法向动量守恒＋恢复系数，切向无摩擦不变；未接近则无碰撞",
    val note: String = GeometryNotes.NOT_MEASUREMENT,
)

/** 两体解析碰撞（纯函数）。normal 无需单位化（内部归一）。 */
object TwoBodyCollision {

    fun resolve(
        mass1: Double,
        mass2: Double,
        v1: Vec3,
        v2: Vec3,
        normal: Vec3,
        restitution: Double,
    ): CollisionResult {
        require(mass1 > 0.0 && mass2 > 0.0) { "质量为正" }
        require(restitution in 0.0..1.0) { "恢复系数在 [0,1]" }
        require(normal.norm() > 0.0) { "法向非零" }
        val n = normal.normalized()
        val u1 = v1.dot(n)
        val u2 = v2.dot(n)
        val pBefore = v1 * mass1 + v2 * mass2
        val keBefore = 0.5 * mass1 * v1.normSq() + 0.5 * mass2 * v2.normSq()
        if (u1 - u2 <= 0.0) {
            return CollisionResult(v1, v2, pBefore, pBefore, keBefore, keBefore, false)
        }
        val (u1After, u2After) = normalSpeeds(mass1, mass2, u1, u2, restitution)
        val v1After = v1 + n * (u1After - u1)
        val v2After = v2 + n * (u2After - u2)
        val pAfter = v1After * mass1 + v2After * mass2
        val keAfter = 0.5 * mass1 * v1After.normSq() + 0.5 * mass2 * v2After.normSq()
        return CollisionResult(v1After, v2After, pBefore, pAfter, keBefore, keAfter, true)
    }

    /** 一维正碰闭式解（动量守恒＋恢复系数定义联立）。 */
    private fun normalSpeeds(m1: Double, m2: Double, u1: Double, u2: Double, e: Double): Pair<Double, Double> {
        val p = m1 * u1 + m2 * u2
        val total = m1 + m2
        val a = (p + m2 * e * (u2 - u1)) / total
        val b = (p + m1 * e * (u1 - u2)) / total
        return a to b
    }
}