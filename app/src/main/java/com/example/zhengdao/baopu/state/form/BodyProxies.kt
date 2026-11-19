// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：导出代理体给 655（消费既有 SegmentProxy/凸体，不另造）。
// 17 段与 JointNames.ALL 一一对应；13 区出 15 个实例（睾丸/精索左右各一），全锚 ROOT。
package com.example.zhengdao.baopu.state.form

import com.example.zhengdao.baopu.state.geometry.Box
import com.example.zhengdao.baopu.state.geometry.Capsule
import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.Ellipsoid
import com.example.zhengdao.baopu.state.geometry.JointNames
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.Vec3

/** 代理体清单＋每个代理体的关节锚点＋接触参数（供 655/661）。 */
data class BodyProxies(
    val segments: List<SegmentProxy>,
    val genital: List<SegmentProxy>,
    val anchors: Map<String, String>,
    val contact: ContactState,
) {
    init {
        val segNames = segments.map { it.name }.toSet()
        require(segNames == JointNames.ALL.toSet()) {
            "段代理体须与 17 关节一一对应，差集＝${(segNames - JointNames.ALL) + (JointNames.ALL - segNames)}"
        }
        require(genital.isNotEmpty()) { "区代理体为空" }
        val names = (segments + genital).map { it.name }
        require(names.distinct().size == names.size) { "代理体名重复" }
        require(anchors.keys == names.toSet()) { "锚点不齐：缺 ${(names.toSet() - anchors.keys)}" }
        require(anchors.values.all { it in JointNames.ALL }) { "锚点指向未知关节" }
    }

    val all: List<SegmentProxy> get() = segments + genital
}

/** 形体 → 凸体代理。纯几何换算，零随机、零时钟、零联网。 */
object ProxyExport {

    fun of(frame: BodyFrame, props: Proportions, genital: GenitalModel): BodyProxies {
        val segs = frame.segments.flatMap { seg ->
            if (seg.side == "bilateral") listOf(-1.0, 1.0).map { limb(frame, props, seg, it) }
            else axial(frame, seg)
        }
        val zones = genitalProxies(genital)
        val anchors = LinkedHashMap<String, String>()
        for (s in segs) anchors[s.name] = s.name
        for (z in genital.zones) anchors["${z.zoneId}.${z.sideLabel}"] = z.anchorJoint
        return BodyProxies(segs, zones, anchors, genital.contact)
    }

    private fun axial(frame: BodyFrame, seg: SegmentGeometry): List<SegmentProxy> = when (seg.id) {
        "torso" -> listOf(
            trunk(frame, JointNames.CHEST, frame.layout.neckBaseY, frame.layout.chestLen, "chest_upper"),
            trunk(frame, JointNames.SPINE, frame.layout.pelvisTopY + frame.layout.abdomenLen,
                frame.layout.abdomenLen, "waist"),
        )
        "pelvis" -> listOf(trunk(frame, seg.joint, frame.layout.pelvisTopY, frame.cm("pelvis"), "pelvis"))
        else -> listOf(SegmentProxy(seg.joint, axialShape(frame, seg)))
    }

    private fun trunk(frame: BodyFrame, joint: String, yTop: Double, len: Double, secId: String): SegmentProxy {
        val s = frame.sections.first { it.id == secId }
        val center = Vec3(0.0, yTop - len / 2.0, 0.0)
        val radii = Vec3(s.widthCm.value / 2.0, len / 2.0, s.thicknessCm.value / 2.0)
        return SegmentProxy(joint, Ellipsoid(center, radii))
    }

    private fun axialShape(frame: BodyFrame, seg: SegmentGeometry): ConvexShape {
        val r = frame.radii.getValue(seg.id).value
        return if (seg.shape == "capsule") {
            Capsule(Vec3(0.0, seg.yTopCm, 0.0), Vec3(0.0, seg.yBottomCm, 0.0), r)
        } else {
            Ellipsoid(
                Vec3(0.0, (seg.yTopCm + seg.yBottomCm) / 2.0, 0.0),
                Vec3(r, seg.lengthCm.value / 2.0, r),
            )
        }
    }

    private fun limb(frame: BodyFrame, props: Proportions, seg: SegmentGeometry, sign: Double): SegmentProxy {
        val name = seg.joint + if (sign < 0.0) "_l" else "_r"
        val x = sign * seg.lateralCm
        val r = frame.radii.getValue(seg.id).value
        val shape = when (seg.shape) {
            "capsule" -> Capsule(Vec3(x, seg.yTopCm, 0.0), Vec3(x, seg.yBottomCm, 0.0), r)
            "box" -> foot(props, seg, x, r)
            else -> Ellipsoid(
                Vec3(x, (seg.yTopCm + seg.yBottomCm) / 2.0, 0.0),
                Vec3(r, seg.lengthCm.value / 2.0, r),
            )
        }
        return SegmentProxy(name, shape)
    }

    private fun foot(props: Proportions, seg: SegmentGeometry, x: Double, r: Double): Box {
        val halfY = seg.lengthCm.value / 2.0
        val halfZ = r * props.constant("foot_length_factor").default
        val heel = props.constant("foot_heel_frac").default
        return Box(Vec3(x, halfY, halfZ * (1.0 - 2.0 * heel)), Vec3(r, halfY, halfZ))
    }

    private fun genitalProxies(genital: GenitalModel): List<SegmentProxy> {
        val root = genital.rootCm()
        return genital.zones.map { z ->
            SegmentProxy("${z.zoneId}.${z.sideLabel}", shifted(zoneShape(genital, z), root))
        }
    }

    private fun zoneShape(genital: GenitalModel, z: GenitalZone): ConvexShape = when (z.proxyKind) {
        "capsule" -> genital.zoneEnds(z).let { Capsule(it.first, it.second, z.radiusCm) }
        "sphere" -> Sphere(z.centerCm, z.radiusCm)
        "box" -> Box(z.centerCm, requireNotNull(z.boxHalfCm) { "${z.zoneId} 缺盒半宽" })
        else -> Ellipsoid(z.centerCm, Vec3(z.radiusCm, z.axialSpanCm / 2.0, z.radiusCm))
    }

    private fun shifted(s: ConvexShape, d: Vec3): ConvexShape = when (s) {
        is Capsule -> Capsule(s.a + d, s.b + d, s.radius)
        is Ellipsoid -> Ellipsoid(s.center + d, s.radii, s.rot)
        is Sphere -> Sphere(s.center + d, s.radius)
        is Box -> Box(s.center + d, s.half, s.rot)
        else -> throw IllegalArgumentException("未知凸体，无法平移：${s::class.simpleName}")
    }
}