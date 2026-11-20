// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 形轨.器官代理与腔隙碰撞：简化阶梯（交付①＋交付④的入口）。
// 660 的 BodyProxies 原样收下，盒降阶成八角凸包；反向不承诺精度，只承诺保守包住。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.Box
import com.example.zhengdao.baopu.state.geometry.Capsule
import com.example.zhengdao.baopu.state.geometry.ConvexHull
import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.Ellipsoid
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.Vec3
import com.example.zhengdao.baopu.state.form.BodyProxies

/** 代理体换算：660 → 686，以及任一代理体 → 更粗的一档。 */
object ProxySimplify {

    /** 已是四类则原样收；盒降阶成八角凸包；其它凸体即拒（不新增原语）。 */
    fun of(id: String, shape: ConvexShape): OrganProxy = when (shape) {
        is Box -> OrganProxy(id, ProxyKind.HULL, ConvexHull(boxCorners(shape)))
        else -> OrganProxy(
            id,
            requireNotNull(OrganProxy.kindOfOrNull(shape)) { "686 只认 655 的球/胶囊/椭球/凸包，收到 ${shape.javaClass.simpleName}" },
            shape,
        )
    }

    /** 交付④：把 660 的真形体（17 段＋13 区实例）全部导成代理体，名一一对应。 */
    fun fromBody(proxies: BodyProxies): List<OrganProxy> = fromList(proxies.all)

    fun fromList(segments: List<SegmentProxy>): List<OrganProxy> = segments.map { of(it.name, it.shape) }

    /** 降阶阶梯：任何代理体都能降成球／椭球／胶囊／凸包，四档都由包围盒撑出 ⇒ 只大不小。 */
    fun simplify(src: OrganProxy, id: String, kind: ProxyKind): OrganProxy {
        val box = src.aabb()
        val c = box.center()
        val half = (box.max - box.min) * 0.5
        return when (kind) {
            ProxyKind.SPHERE -> OrganProxy(id, kind, Sphere(c, src.boundingRadiusCm()))
            ProxyKind.ELLIPSOID -> OrganProxy(id, kind, Ellipsoid(c, half))
            ProxyKind.HULL -> OrganProxy(id, kind, ConvexHull(corners(box)))
            ProxyKind.CAPSULE -> OrganProxy(id, kind, capsuleAround(src, half))
        }
    }

    /** 八角（含朝向）——盒降阶后支撑值与盒逐方向同值的根据。 */
    private fun boxCorners(box: Box): List<Vec3> {
        val out = ArrayList<Vec3>(8)
        for (sx in listOf(-1.0, 1.0)) for (sy in listOf(-1.0, 1.0)) for (sz in listOf(-1.0, 1.0)) {
            out += box.center + box.rot.times(Vec3(sx * box.half.x, sy * box.half.y, sz * box.half.z))
        }
        return out
    }

    private fun corners(box: Aabb): List<Vec3> {
        val out = ArrayList<Vec3>(8)
        for (x in listOf(box.min.x, box.max.x)) for (y in listOf(box.min.y, box.max.y)) for (z in listOf(box.min.z, box.max.z)) {
            out += Vec3(x, y, z)
        }
        return out
    }

    /** 胶囊档：沿最长轴取两端支撑点，半径取另两半宽的最大值（平局取序号小的轴）。 */
    private fun capsuleAround(src: OrganProxy, half: Vec3): Capsule {
        val axes = listOf(Vec3.UNIT_X, Vec3.UNIT_Y, Vec3.UNIT_Z)
        val spans = listOf(half.x, half.y, half.z)
        val main = axes.indices.maxBy { spans[it] }
        val u = axes[main]
        return Capsule(src.shape.support(u), src.shape.support(-u), spans.filterIndexed { i, _ -> i != main }.max())
    }
}
