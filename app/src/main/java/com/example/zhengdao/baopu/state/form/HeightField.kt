// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：体表高度场（cm 网格、椭球叠加）＋侧影＋高度等值线。
// 高度场是**浮在躯干椭圆柱面上的隆起**（肌肉/脂肪轮廓），不是躯干本身的粗细——
// 粗细由 BMI 经 softTissue 进截面，故体脂升 ⇒ 隆起降、不确定度升 ⇒ 区间变宽。
package com.example.zhengdao.baopu.state.form

import com.example.zhengdao.baopu.body.HeightFieldSource
import com.example.zhengdao.baopu.body.SilhouettePosition
import com.example.zhengdao.baopu.state.geometry.GeometryNotes
import com.example.zhengdao.baopu.state.geometry.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/** 一个椭球隆起：中心＋三半轴＋峰值区间（cm）。side 分前/后，前网格只叠前侧。 */
data class Bump(
    val id: String,
    val side: String,
    val centerCm: Vec3,
    val radiiCm: Vec3,
    val amplitudeCm: FactRange,
)

/** 网格采样：体表前表面 z ＋该点隆起区间。 */
data class HeightSample(val xCm: Double, val yCm: Double, val surfaceZCm: Double, val reliefCm: FactRange)

/** 等值线段（ marching squares 的输出，渲染侧自行串成环）。 */
data class ContourSegment(val a: Vec3, val b: Vec3)

/** 一条高度等值线：阈值＋该阈值下的全部线段。 */
data class ContourLevel(val levelCm: Double, val segments: List<ContourSegment>)

/** 侧影（矢状面）一行：离地高＋半宽＋前/后表面 z。 */
data class SilhouettePoint(val yCm: Double, val halfWidthCm: Double, val frontZCm: Double, val backZCm: Double)

/** 部件读数（覆盖值或表默认，都带出身）。 */
data class ResolvedParts(
    val pecMass: Sourced,
    val pecInsertion: Sourced,
    val absBlocks: Int,
    val absDepth: Sourced,
    val gluteMass: Sourced,
)

private const val RELIEF_ASSUMPTION =
    "椭球叠加隆起：峰值＝口径表 relief 区间中值×部件档×脂肪遮盖，半宽＝区间半宽×部件档×脂肪不确定度／基准不确定度；" +
        "等值线取隆起中值场的 marching squares，阈值把峰值等分成 n＋1 份取内部分点（不含峰值本身，避免退化零长线段）"

/** 体表高度场。构造即算完，同输入逐字节一致（零随机、零时钟、零联网）。 */
class BodyHeightField(
    val frame: BodyFrame,
    val props: Proportions,
    parts: PartsSpec,
    val genital: GenitalModel,
) : HeightFieldSource {

    val resolved: ResolvedParts = resolveParts(parts)
    val bumps: List<Bump> = buildBumps()
    val xs: List<Double> = axis(-maxHalfWidth(), maxHalfWidth())
    val ys: List<Double> = axis(frame.layout.hipY, frame.layout.neckBaseY)
    val grid: List<List<Double>> = ys.map { y -> xs.map { x -> reliefAt(x, y).mid() } }
    val samples: List<HeightSample> = buildSamples()
    val silhouette: List<SilhouettePoint> = buildSilhouette()
    val contours: List<ContourLevel> = buildContours()
    val assumption: String = RELIEF_ASSUMPTION
    val note: String = GeometryNotes.NOT_MEASUREMENT

    private fun step() = props.constant("height_field_grid_cm").default

    private fun axis(from: Double, to: Double): List<Double> {
        val s = step()
        require(s > 0.0) { "网格步长须为正" }
        val n = ((to - from) / s).toInt()
        return (0..n).map { from + it * s }
    }

    private fun maxHalfWidth() = frame.sections.maxOf { it.widthCm.value } / 2.0

    /** 部件档是表刻度上的选择而非实测读数，故出身恒为推算、来源即刻度出处（实测尺寸走 Override）。 */
    private fun pick(v: Double?, s: NumericSpec): Sourced =
        Sourced(v ?: s.default, "grade", Origin.DERIVED, s.source, s.asOf)

    private fun resolveParts(p: PartsSpec): ResolvedParts {
        val blocks = p.absBlocks ?: props.part("abs_blocks").default.toInt()
        val d = props.part("abs_blocks")
        require(blocks.toDouble() in d.low..d.high) { "abs_blocks $blocks 超域 ${d.low}..${d.high}" }
        return ResolvedParts(
            pecMass = pick(p.pecMass, props.part("pec_mass")),
            pecInsertion = pick(p.pecInsertion, props.part("pec_insertion")),
            absBlocks = blocks,
            absDepth = pick(p.absDepth, props.part("abs_depth")),
            gluteMass = pick(p.gluteMass, props.part("glute_mass")),
        )
    }

    /**
     * 峰值随脂肪遮盖下降，半宽随脂肪不确定度上升——两者分开，故"降且变宽"同时成立。
     * 下界不钳零：这是**不确定度带**不是实测高度，钳零会把变宽的那一半折成抬高中值（体脂越高读数反而越高）。
     */
    private fun amplitude(reliefId: String, mass: Double): FactRange {
        val s = props.part(reliefId)
        val f = frame.factors
        val mid = s.default * mass * f.fatCover
        val half = (s.high - s.low) / 2.0 * mass * f.fatUncertainty /
            props.constant("fat_uncertainty_base").default
        return FactRange(
            reliefId, mid - half, mid + half, "cm",
            FormTables.PROPORTIONS_REL_PATH + "#parts", s.asOf, s.evidence,
        )
    }

    private fun section(id: String) = frame.sections.first { it.id == id }

    private fun buildBumps(): List<Bump> = pecBumps() + absBumps() + gluteBumps()

    private fun pecBumps(): List<Bump> {
        val up = section("chest_upper")
        val lo = section("chest_lower")
        val y = up.yCm - (up.yCm - lo.yCm) * resolved.pecInsertion.value
        val rx = up.widthCm.value / 4.0
        val ry = (up.yCm - lo.yCm) / 2.0
        val amp = amplitude("pec_relief_cm", resolved.pecMass.value)
        return listOf(-1.0, 1.0).map { sign ->
            Bump(
                "pec.${if (sign < 0) "left" else "right"}", "front",
                Vec3(sign * rx, y, up.thicknessCm.value / 2.0),
                Vec3(rx, ry, up.thicknessCm.value / 2.0), amp,
            )
        }
    }

    private fun absBumps(): List<Bump> {
        val waist = section("waist")
        val pelvis = section("pelvis")
        val rows = (resolved.absBlocks / 2).coerceAtLeast(1)
        val span = waist.yCm - pelvis.yCm
        val ry = span / (2.0 * rows)
        val rx = waist.widthCm.value / 4.0
        val amp = amplitude("abs_relief_cm", resolved.absDepth.value)
        val out = mutableListOf<Bump>()
        for (r in 0 until rows) {
            val y = pelvis.yCm + ry + 2.0 * ry * r
            for (sign in listOf(-1.0, 1.0)) {
                out.add(
                    Bump(
                        "abs.$r.${if (sign < 0) "left" else "right"}", "front",
                        Vec3(sign * rx, y, waist.thicknessCm.value / 2.0),
                        Vec3(rx, ry, waist.thicknessCm.value / 2.0), amp,
                    ),
                )
            }
        }
        return out.toList()
    }

    private fun gluteBumps(): List<Bump> {
        val pelvis = section("pelvis")
        val rx = pelvis.widthCm.value / 4.0
        val ry = frame.cm("pelvis") / 2.0
        val amp = amplitude("glute_relief_cm", resolved.gluteMass.value)
        return listOf(-1.0, 1.0).map { sign ->
            Bump(
                "glute.${if (sign < 0) "left" else "right"}", "back",
                Vec3(sign * rx, frame.layout.hipY, -pelvis.thicknessCm.value / 2.0),
                Vec3(rx, ry, pelvis.thicknessCm.value / 2.0), amp,
            )
        }
    }

    private fun falloff(b: Bump, x: Double, y: Double): Double {
        val dx = (x - b.centerCm.x) / b.radiiCm.x
        val dy = (y - b.centerCm.y) / b.radiiCm.y
        val q = 1.0 - dx * dx - dy * dy
        return if (q > 0.0) sqrt(q) else 0.0
    }

    /** 前表面隆起区间：只叠前侧椭球，逐点线性叠加（不做有限元、不解组织应力）。 */
    fun reliefAt(x: Double, y: Double): FactRange {
        var lo = 0.0
        var hi = 0.0
        for (b in bumps) {
            if (b.side != "front") continue
            val f = falloff(b, x, y)
            if (f <= 0.0) continue
            lo += b.amplitudeCm.low * f
            hi += b.amplitudeCm.high * f
        }
        return FactRange(
            "relief", lo, hi, "cm", FormTables.PROPORTIONS_REL_PATH + "#parts",
            props.part("pec_relief_cm").asOf, Evidence.ASSUMPTION,
        )
    }

    /** 躯干椭圆柱面在高度 y 处的半厚/半宽：截面间线性插值，端外钳到最近截面。 */
    fun profileAt(y: Double): SilhouettePoint {
        val s = frame.sections
        if (y <= s.first().yCm) return point(y, s.first())
        if (y >= s.last().yCm) return point(y, s.last())
        val k = s.indexOfFirst { it.yCm >= y }
        val a = s[k - 1]
        val b = s[k]
        val t = (y - a.yCm) / (b.yCm - a.yCm)
        val w = a.widthCm.value + (b.widthCm.value - a.widthCm.value) * t
        val th = a.thicknessCm.value + (b.thicknessCm.value - a.thicknessCm.value) * t
        return SilhouettePoint(y, w / 2.0, th / 2.0, -th / 2.0)
    }

    private fun point(y: Double, s: SectionGeometry) =
        SilhouettePoint(y, s.widthCm.value / 2.0, s.thicknessCm.value / 2.0, -s.thicknessCm.value / 2.0)

    private fun buildSamples(): List<HeightSample> = ys.flatMap { y ->
        val z = profileAt(y).frontZCm
        xs.map { x -> HeightSample(x, y, z, reliefAt(x, y)) }
    }

    private fun buildSilhouette(): List<SilhouettePoint> =
        axis(0.0, frame.layout.topY).map { y -> silhouetteAt(y) }

    private fun silhouetteAt(y: Double): SilhouettePoint {
        val l = frame.layout
        if (y >= l.headBaseY) return limbProfile(y, frame.radii.getValue("head").value, 0.0)
        if (y >= l.neckBaseY) return limbProfile(y, frame.radii.getValue("neck").value, 0.0)
        if (y >= l.hipY) return profileAt(y)
        if (y >= l.hipY - frame.cm("thigh")) return limbProfile(y, frame.radii.getValue("thigh").value, l.hipJointX)
        if (y >= frame.cm("foot")) return limbProfile(y, frame.radii.getValue("calf").value, l.hipJointX)
        return limbProfile(y, frame.radii.getValue("foot").value, l.hipJointX)
    }

    private fun limbProfile(y: Double, r: Double, offset: Double) =
        SilhouettePoint(y, offset + r, r, -r)

    private fun buildContours(): List<ContourLevel> {
        val n = props.constant("contour_levels").default.toInt()
        require(n > 0) { "等值线条数须为正" }
        val peak = grid.maxOf { row -> row.maxOrNull() ?: 0.0 }
        if (peak <= 0.0) return emptyList()
        return (1..n).map { contourAt(peak * it / (n + 1)) }
    }

    private fun contourAt(level: Double): ContourLevel {
        val out = mutableListOf<ContourSegment>()
        for (j in 0 until ys.size - 1) {
            for (i in 0 until xs.size - 1) out.addAll(cell(i, j, level))
        }
        return ContourLevel(level, out.toList())
    }

    private fun cell(i: Int, j: Int, level: Double): List<ContourSegment> {
        val v = listOf(grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i])
        val p = listOf(
            Vec3(xs[i], ys[j], 0.0), Vec3(xs[i + 1], ys[j], 0.0),
            Vec3(xs[i + 1], ys[j + 1], 0.0), Vec3(xs[i], ys[j + 1], 0.0),
        )
        val cross = mutableListOf<Vec3>()
        for (e in 0 until 4) {
            val a = v[e]
            val b = v[(e + 1) % 4]
            if ((a < level) == (b < level)) continue
            val t = (level - a) / (b - a)
            cross.add(p[e] + (p[(e + 1) % 4] - p[e]) * t)
        }
        return pairCrossings(cross, v.sum() / 4.0 >= level).filter { it.a != it.b }
    }

    /** 四交点是鞍点：按格心值定配对，保证同输入同输出（不查表、不随机）。 */
    private fun pairCrossings(c: List<Vec3>, centerHigh: Boolean): List<ContourSegment> = when (c.size) {
        2 -> listOf(ContourSegment(c[0], c[1]))
        4 -> if (centerHigh) {
            listOf(ContourSegment(c[0], c[1]), ContourSegment(c[2], c[3]))
        } else {
            listOf(ContourSegment(c[0], c[3]), ContourSegment(c[1], c[2]))
        }
        else -> emptyList()
    }

    /**
     * 某高度带上最大的隆起：取纵向跨度"够得着"该高度的前侧隆起各自的峰值，再取最大。
     * 不取网格采样——网格步长固定，隆起中心随体脂移动，采样值会被网格对齐带着漂。
     */
    fun reliefNear(y: Double): FactRange {
        val front = bumps.filter { it.side == "front" }
        fun gap(b: Bump) = maxOf(0.0, abs(y - b.centerCm.y) - b.radiiCm.y)
        val best = front.minOfOrNull { gap(it) } ?: return reliefAt(0.0, y)
        val pool = front.filter { gap(it) <= best + 1e-9 }
        return pool.map { reliefAt(it.centerCm.x, it.centerCm.y) }.maxByOrNull { it.mid() }
            ?: reliefAt(0.0, y)
    }

    private fun thighRelief(): FactRange {
        val s = props.constant("limb_relief_frac")
        val r = frame.radii.getValue("thigh").value
        return FactRange("thigh.relief", s.low * r, s.high * r, "cm", s.source, s.asOf, s.evidence)
    }

    /** 666 侧影比值模型的输入：只产 h，不乘清晰度（面料归 666）。 */
    override fun bumpHeightCm(position: SilhouettePosition): Double = when (position) {
        SilhouettePosition.WAIST -> reliefNear(section("waist").yCm).mid()
        SilhouettePosition.CROTCH -> genital.bulgeCm
        SilhouettePosition.THIGH -> thighRelief().mid()
    }
}