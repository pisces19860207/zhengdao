// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 形轨.器官代理与腔隙碰撞：四类简化代理体与特征点。
// 形状原语一律复用第六百五十五批（球／胶囊／椭球／凸包），本单不新增原语；
// 660 的脚掌盒按「降阶」处理成八角凸包（判据①「不新增形状原语」）。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.Capsule
import com.example.zhengdao.baopu.state.geometry.ConvexHull
import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.Ellipsoid
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.Vec3
import com.example.zhengdao.baopu.state.geometry.lowestY

/** 四类简化代理体。判据①：每一类都有 655 的支撑函数实现，故 GJK／EPA 直接可判。 */
enum class ProxyKind { SPHERE, CAPSULE, ELLIPSOID, HULL }

/** 方向档：六主轴带符号。本单只出档，不出连续向量，更不出应力张量。 */
enum class NormalBucket { PLUS_X, MINUS_X, PLUS_Y, MINUS_Y, PLUS_Z, MINUS_Z }

/** 特征点：稳定序号（同类恒同序）＋世界坐标。 */
data class FeaturePoint(val index: Int, val point: Vec3)

/**
 * 特征点集（口径来源：686 澄清 2026-11-20）：
 * 球＝1（球心）、胶囊＝2（两端点）、椭球＝6（三轴极点，随朝向一起转）、凸包＝顶点全集截到上限。
 */
data class FeatureSet(
    val kind: ProxyKind,
    val points: List<FeaturePoint>,
    val truncated: Boolean,
    val droppedCount: Int,
)

/** 一个器官／一段骨架的碰撞代理。 */
data class OrganProxy(val id: String, val kind: ProxyKind, val shape: ConvexShape) {

    init {
        require(id.isNotBlank()) { "代理体缺 id" }
        require(kindOfOrNull(shape) == kind) { "$id：kind＝$kind 与形状 ${shape.javaClass.simpleName} 不符" }
    }

    fun aabb(): Aabb = Aabb.of(shape)

    /** 包围球半径＝半对角线（粗剔与 ε 归一都用得上，保守）。 */
    fun boundingRadiusCm(): Double = aabb().diagonalCm() / 2.0

    fun lowestY(): Double = shape.lowestY()

    fun highestY(): Double = shape.support(Vec3.UNIT_Y).y

    /** 平移：kind 与 id 不变，形状跟着走（重力落定与推开演练用）。 */
    fun shiftedBy(d: Vec3): OrganProxy = OrganProxy(id, kind, shifted(shape, d))

    fun features(): FeatureSet {
        val raw = when (kind) {
            ProxyKind.SPHERE -> listOf((shape as Sphere).center)
            ProxyKind.CAPSULE -> listOf((shape as Capsule).a, shape.b)
            ProxyKind.ELLIPSOID -> (shape as Ellipsoid).axisPoles()
            ProxyKind.HULL -> (shape as ConvexHull).vertices.sortedWith(VECCMP)
        }
        val kept = if (kind == ProxyKind.HULL) raw.take(CavityTolerances.HULL_FEATURE_CAP) else raw
        val dropped = raw.size - kept.size
        return FeatureSet(kind, kept.mapIndexed(::FeaturePoint), dropped > 0, dropped)
    }

    companion object {

        /** 形状 → 四类；盒等其它凸体返回 null（须先经 ProxySimplify 降阶）。 */
        fun kindOfOrNull(shape: ConvexShape): ProxyKind? = when (shape) {
            is Sphere -> ProxyKind.SPHERE
            is Capsule -> ProxyKind.CAPSULE
            is Ellipsoid -> ProxyKind.ELLIPSOID
            is ConvexHull -> ProxyKind.HULL
            else -> null
        }

        /** 特征点用的规范字典序（x→y→z），保证截断结果与输入顺序无关。 */
        private val VECCMP: Comparator<Vec3> = compareBy({ it.x }, { it.y }, { it.z })
    }
}

/** 三轴极点：局部坐标取极点后再由 rot 转到世界（rot 是 local→world，与 655 支撑函数同口径）。 */
private fun Ellipsoid.axisPoles(): List<Vec3> = listOf(
    Vec3(radii.x, 0.0, 0.0), Vec3(-radii.x, 0.0, 0.0),
    Vec3(0.0, radii.y, 0.0), Vec3(0.0, -radii.y, 0.0),
    Vec3(0.0, 0.0, radii.z), Vec3(0.0, 0.0, -radii.z),
).map { center + rot.times(it) }

private fun shifted(shape: ConvexShape, d: Vec3): ConvexShape = when (shape) {
    is Capsule -> Capsule(shape.a + d, shape.b + d, shape.radius)
    is Ellipsoid -> Ellipsoid(shape.center + d, shape.radii, shape.rot)
    is Sphere -> Sphere(shape.center + d, shape.radius)
    is ConvexHull -> ConvexHull(shape.vertices.map { it + d })
    else -> throw IllegalArgumentException("686 只平移四类代理体，收到 ${shape.javaClass.simpleName}")
}
