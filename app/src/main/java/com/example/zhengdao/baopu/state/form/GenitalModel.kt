// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：13 区生殖器参数（尺寸一律经 FactSource，禁止身高推尺寸）。
// 区名/可观察特征/常见误写取 669 的 anatomy-zones.json，本文件不复制第二份。
package com.example.zhengdao.baopu.state.form

import com.example.zhengdao.baopu.body.AnatomyZone
import com.example.zhengdao.baopu.state.geometry.GeometryNotes
import com.example.zhengdao.baopu.state.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 误写报账：指名区与正确说法（只报不改写，同 669 口径）。 */
data class VocabError(
    val ruleId: String,
    val zoneId: String,
    val zoneName: String,
    val correction: String,
    val source: String,
)

/** 直径剖面采样点（工单 §二：5–8 点）。 */
data class ProfilePoint(val axialFrac: Double, val diameterMm: FactRange)

/** 阴囊当前态：松紧随温度/充血/体脂持续变化，不是常量。 */
data class ScrotumState(
    val volumeGrade: Int,
    val sagGradeInput: Int,
    val sagGradeEffective: Double,
    val hangSide: String,
    val asymmetryMm: Double,
    val hangMm: Double,
    val radiusMm: FactRange,
    val tempC: Double,
)

/** 接触参数当前态（供 661；655 两体碰撞取 current 作 restitution）。 */
data class ContactState(val restitution: FactRange, val stiffnessGrade: Int, val current: Double)

/**
 * 一个区实例的当前读数。左右各一的区（睾丸/精索）出两条：zoneId 相同、sideLabel 不同。
 * 半径是区间——充血增量本身是区间，不给假点值。
 */
data class GenitalZone(
    val zoneId: String,
    val name: String,
    val sideLabel: String,
    val radiusMm: FactRange,
    val axialFromMm: Double,
    val axialToMm: Double,
    val centerCm: Vec3,
    val boxHalfCm: Vec3?,
    val side: String,
    val form: String?,
    val surfaceRule: String?,
    val surfaceGrade: String,
    /** 光滑秩：可超出名表（龟头恒比柱身高一档），名字钳到表内最后一档但秩保留差别。 */
    val surfaceRank: Int,
    val colorHex: String,
    val colorSource: String,
    val colorAsOf: String,
    val colorDeepenStep: Int?,
    val turgorGain: FactRange,
    val proxyKind: String,
    val anchorJoint: String,
    val layers: List<String>,
    val retractable: Boolean,
    val sourceRef: String,
    val lengthRef: String,
) {
    /** 当前半径中值（cm）：代理体用点值，区间仍随 radiusMm 一起交付。 */
    val radiusCm: Double get() = radiusMm.mid() / MM_PER_CM

    val axialSpanCm: Double get() = abs(axialToMm - axialFromMm) / MM_PER_CM
}

/** 生殖器模型读数。ok=false 表示有 vocab_error：只报不改写。 */
data class GenitalResult(
    val zones: List<GenitalZone>,
    val hardnessGrade: Int,
    val lengthErectMm: Sourced,
    val lengthFlaccidMm: Sourced,
    val lengthRangeMm: FactRange,
    val diameterErectMm: Sourced,
    val diameterRangeMm: FactRange,
    val diameterProfile: List<ProfilePoint>,
    val curvature: Double,
    val bendDirection: String,
    val shape: String,
    val surfaceGrade: String,
    val vesselVisibility: String,
    val scrotum: ScrotumState,
    val bulgeCm: Double,
    val contact: ContactState,
    val advisories: List<String>,
    val vocabErrors: List<VocabError>,
    val ok: Boolean,
    val assumption: String = GENITAL_ASSUMPTION,
    val note: String = GeometryNotes.NOT_MEASUREMENT,
)

private const val GENITAL_ASSUMPTION =
    "13 区参数：尺寸经 FactSource 分位表取中位作代表值并随附区间；充血增量与形态分数是工程假设；" +
        "规范系原点在根部、轴沿 +Z、腹侧 −Y；姿态角归 655/699，本模型只给规范形"

private const val MM_PER_CM = 10.0

/** 表面粗糙度序：明显皱 → 细皱 → 光滑。龟头比柱身光滑一档由此序推。 */
private val SURFACE_ORDER = listOf("pronounced", "fine_wrinkle", "smooth")

/**
 * 生殖器参数推导。**没有身高入口**：尺寸与身高只允许弱相关（FactSource.statureCorrelationR），
 * 故不读身高推长度/周长；身高只用于耻骨垫厚度与根部挂点这类几何位置。
 */
class GenitalModel(
    private val input: GenitalInput,
    private val spec: HumanBodySpec,
    private val frame: BodyFrame,
    private val facts: FactSource,
    private val table: ZoneTable,
    anatomy: List<AnatomyZone>,
) {
    private val names: Map<String, String> = anatomy.associate { it.zoneId to it.name }

    init {
        val zoneIds = table.zones.map { it.zoneId }.toSet()
        require(zoneIds == names.keys) {
            "分区表须与 669 词表 13 区一一对应，差集＝${(zoneIds - names.keys) + (names.keys - zoneIds)}"
        }
    }

    val hardnessGrade: Int = resolveGrade()
    val fill: Double = hardnessGrade.toDouble() / (facts.hardnessGradeCount - 1).toDouble()
    val lengthErectMm: Sourced = resolveLength()
    val lengthRangeMm: FactRange = facts.lengthRangePct.range(
        facts.lengthRangePct.lowPct, facts.lengthRangePct.highPct,
    )
    val girthErectMm: Sourced = resolveGirth()
    val diameterErectMm: Sourced = Sourced(
        facts.equivalentDiameterMm(girthErectMm.value), "mm", girthErectMm.origin,
        girthErectMm.source, girthErectMm.asOf,
    )
    val diameterRangeMm: FactRange = facts.diameterRangePct(
        facts.girthRangePct.lowPct, facts.girthRangePct.highPct,
    )
    val refRadiusMm: Double = diameterErectMm.value / 2.0
    val lengthFlaccidMm: Sourced = resolveFlaccid()
    val curvature: Double = resolveCurvature()
    val bendDirection: String = resolveEnum(input.bendDirection, "bend_direction")
    val shape: String = resolveShape()
    val surfaceGrade: String = resolveSurface()
    val vesselVisibility: String =
        resolveEnum(input.vesselVisibility ?: table.vesselDefault, "vessel_visibility")
    val tempC: Double = input.skinTempC ?: table.domain("scrotum_ref_temp_c").default
    val constricted: Boolean = input.constricted
    val scrotum: ScrotumState = resolveScrotum()
    val zones: List<GenitalZone> = table.zones.flatMap { emit(it) }
    val diameterProfile: List<ProfilePoint> = resolveProfile()
    val bulgeCm: Double = resolveBulge()
    val contact: ContactState = resolveContact()
    val advisories: List<String> = resolveAdvisories()
    val vocabErrors: List<VocabError> = ZoneGuard.check(this, table, names)
    val ok: Boolean = vocabErrors.isEmpty()

    private fun resolveGrade(): Int {
        val g = input.turgorGrade
        require(g in 0 until facts.hardnessGradeCount) {
            "硬度档 $g 超域 0..${facts.hardnessGradeCount - 1}（EHS 0＝无反应）"
        }
        return g
    }

    /** 存在域上界取 FactSource.rareLength（md §四：18cm 可存在但极少见），不取素材的常见观察域上界。 */
    private fun resolveLength(): Sourced {
        val t = facts.lengthRangePct
        val ov = input.lengthMm
        if (ov == null) return Sourced(t.at(t.lowPct), "mm", Origin.DERIVED, t.source, t.asOf)
        val dom = table.domain("erect_length_mm")
        require(ov.value >= dom.low) { "长度 ${ov.value}mm 低于可存在域下界 ${dom.low}" }
        require(ov.value <= facts.rareLength.valueMm) {
            "长度 ${ov.value}mm 超存在域上界 ${facts.rareLength.valueMm}（${facts.rareLength.rarity}）"
        }
        return Sourced(ov.value, "mm", Origin.MEASURED, ov.source, ov.asOf)
    }

    private fun resolveGirth(): Sourced {
        val t = facts.girthRangePct
        val ov = input.girthMm
        if (ov == null) return Sourced(t.at(t.lowPct), "mm", Origin.DERIVED, t.source, t.asOf)
        val dom = table.domain("erect_diameter_mm")
        val d = facts.equivalentDiameterMm(ov.value)
        require(dom.contains(d)) { "等效直径 ${d}mm 超可存在域 ${dom.low}..${dom.high}" }
        return Sourced(ov.value, "mm", Origin.MEASURED, ov.source, ov.asOf)
    }

    private fun resolveFlaccid(): Sourced {
        val d = table.domain("flaccid_length_ratio")
        val r = input.flaccidRatio ?: d.default
        require(r in d.low..d.high) { "疲软比 $r 超域 ${d.low}..${d.high}" }
        return Sourced(lengthErectMm.value * r, "mm", Origin.DERIVED, d.source, d.asOf)
    }

    private fun resolveCurvature(): Double {
        val c = input.curvature ?: table.domain("curvature_default").default
        require(c in 0.0..1.0) { "curvature 须在 0..1，得 $c" }
        return c
    }

    private fun resolveEnum(v: String, key: String): String {
        val allowed = table.enums.getValue(key)
        require(v in allowed) { "$key 取值 $v 不在 $allowed" }
        return v
    }

    private fun resolveShape(): String =
        resolveEnum(input.shape ?: if (curvature > 0.0) "curved" else "capsule", "shape")

    /** 褶皱随充血消失：取 ≤ 当前档的最近关键档（0 明显／2 细皱／4 光滑）。 */
    private fun resolveSurface(): String {
        val key = table.surfaceByTurgor.keys.filter { it <= hardnessGrade }.maxOrNull() ?: 0
        return table.surfaceByTurgor.getValue(key)
    }

    private fun gradeOf(v: Int?, id: String): Int {
        val d = table.domain(id)
        val g = v ?: d.default.toInt()
        require(g.toDouble() in d.low..d.high) { "$id 档 $g 超域 ${d.low}..${d.high}" }
        return g
    }

    private fun gradeT(v: Int, id: String): Double {
        val d = table.domain(id)
        return (v - d.low) / (d.high - d.low)
    }

    private fun resolveScrotum(): ScrotumState {
        val vol = gradeOf(input.scrotumVolumeGrade, "scrotum_volume_grade")
        val sag = gradeOf(input.scrotumSagGrade, "scrotum_sag_grade")
        val d = table.domain("scrotum_sag_grade")
        val eff = sag +
            table.domain("scrotum_temp_step").default * (tempC - table.domain("scrotum_ref_temp_c").default) -
            table.domain("scrotum_turgor_step").default * fill -
            table.domain("scrotum_fat_step").default * (spec.bodyFatPct - table.domain("fat_ref_pct").default)
        val clamped = eff.coerceIn(d.low, d.high)
        val vr = table.domain("scrotum_volume_radius_factor")
        val side = resolveEnum(input.hangSide ?: "left", "hang_side")
        val asym = input.testisAsymmetryMm ?: table.domain("testis_asymmetry_mm").default
        require(asym >= 0.0) { "单侧差异须非负" }
        return ScrotumState(
            volumeGrade = vol, sagGradeInput = sag, sagGradeEffective = clamped,
            hangSide = side, asymmetryMm = asym,
            hangMm = lengthErectMm.value *
                table.domain("scrotum_hang_frac").at((clamped - d.low) / (d.high - d.low)),
            radiusMm = scaled(vr.at(gradeT(vol, "scrotum_volume_grade")), vr, "scrotum.radius"),
            tempC = tempC,
        )
    }

    private fun scaled(factor: Double, s: NumericSpec, id: String): FactRange =
        FactRange(id, refRadiusMm * factor, refRadiusMm * factor, "mm", s.source, s.asOf, s.evidence)

    private fun interval(s: NumericSpec, id: String): FactRange =
        FactRange(id, refRadiusMm * s.low, refRadiusMm * s.high, "mm", s.source, s.asOf, s.evidence)

    private fun shift(r: FactRange, d: Double): FactRange {
        require(r.low + d >= 0.0) { "${r.id} 平移后半径为负" }
        return r.copy(low = r.low + d, high = r.high + d)
    }

    private fun radiusFor(z: ZoneSpec): FactRange = when (z.zoneId) {
        "scrotum" -> scrotum.radiusMm
        "testis" -> interval(table.domain("testis_radius_factor"), "testis.radius")
        "cord" -> interval(table.domain("cord_radius_factor"), "cord.radius")
        "mons" -> monsRadius()
        else -> gainRadius(z)
    }

    /** 区内半径采样可被实测覆盖；VERIFIED 在此指调用方自证（source/asOf 必填）。 */
    private fun zoneRadius(z: ZoneSpec): FactRange {
        val ov = input.zoneRadiusMm[z.zoneId] ?: return radiusFor(z)
        return FactRange("${z.zoneId}.radius", ov.value, ov.value, "mm", ov.source, ov.asOf, Evidence.VERIFIED)
    }

    private fun gainRadius(z: ZoneSpec): FactRange {
        val f = z.radiusFactor.default
        val g = z.gain
        return FactRange(
            "${z.zoneId}.radius",
            refRadiusMm * f * (1.0 + g.low * fill),
            refRadiusMm * f * (1.0 + g.high * fill),
            "mm", g.source, g.asOf, g.evidence,
        )
    }

    /** 耻骨垫：厚度按体脂在可存在域内插值，宽度取骨盆截面宽的一个分数。 */
    private fun monsRadius(): FactRange {
        val d = table.domain("mons_pad_thickness_frac")
        val fat = frame.props.constant("body_fat_range_pct")
        val t = ((spec.bodyFatPct - fat.low) / (fat.high - fat.low)).coerceIn(0.0, 1.0)
        val half = spec.heightCm * d.at(t) / 2.0
        return FactRange("mons.radius", half * MM_PER_CM, half * MM_PER_CM, "mm", d.source, d.asOf, d.evidence)
    }

    private fun shaftRadiusMm(): Double {
        val r = gainRadius(table.zone("shaft"))
        return r.mid()
    }

    private fun bendVector(): Vec3 = when (bendDirection) {
        "up" -> Vec3.UNIT_Y
        "down" -> -Vec3.UNIT_Y
        "left" -> -Vec3.UNIT_X
        "right" -> Vec3.UNIT_X
        else -> throw IllegalArgumentException("未知弯曲方向 $bendDirection")
    }

    /** 轴线：等曲率圆弧，总弯角＝curvature×表上限。curvature=0 退化为直线。 */
    fun axisPoint(sMm: Double): Vec3 {
        val theta = curvature * table.domain("curvature_deg_max").default * PI / 180.0
        val len = lengthErectMm.value
        if (theta <= 0.0 || len <= 0.0) return Vec3(0.0, 0.0, sMm / MM_PER_CM)
        val rc = len / theta
        val ang = theta * sMm / len
        val off = rc * (1.0 - cos(ang)) / MM_PER_CM
        val d = bendVector()
        return Vec3(d.x * off, d.y * off, rc * sin(ang) / MM_PER_CM)
    }

    private fun sideOffset(z: ZoneSpec): Vec3 {
        val d = shaftRadiusMm() * z.lateralFactor / MM_PER_CM
        return when (z.side) {
            "ventral" -> Vec3(0.0, -d, 0.0)
            "dorsal" -> Vec3(0.0, d, 0.0)
            else -> Vec3.ZERO
        }
    }

    private fun centerOf(z: ZoneSpec, sMid: Double): Vec3 = when {
        z.zoneId == "mons" -> Vec3(0.0, zoneRadius(z).mid() / MM_PER_CM, 0.0)
        z.axial.low < 0.0 -> Vec3(
            zoneRadius(table.zone("scrotum")).low * z.lateralFactor / MM_PER_CM,
            -abs(sMid) / MM_PER_CM,
            0.0,
        )
        else -> axisPoint(sMid) + sideOffset(z)
    }

    private fun emit(z: ZoneSpec): List<GenitalZone> {
        val sMid = z.axial.let { (it.low + it.high) / 2.0 } * lengthErectMm.value
        val base = zoneRadius(z)
        val color = spec.skin.zoneColors[z.zoneId]
            ?: throw IllegalArgumentException("${z.zoneId} 缺色值（每区颜色必带 source，缺即拒）")
        val sides = if (z.side == "bilateral") listOf("left", "right") else listOf("mid")
        return sides.map { one(z, it, sMid, base, color) }
    }

    private fun one(z: ZoneSpec, side: String, sMid: Double, base: FactRange, color: ZoneColor): GenitalZone {
        val r = if (z.zoneId == "testis") testisRadius(base, side) else base
        val c = centerOf(z, sMid)
        val center = if (z.side == "bilateral") bilateral(z, c, side) else c
        return GenitalZone(
            zoneId = z.zoneId, name = names.getValue(z.zoneId), sideLabel = side,
            radiusMm = r,
            axialFromMm = z.axial.low * lengthErectMm.value,
            axialToMm = z.axial.high * lengthErectMm.value,
            centerCm = center, boxHalfCm = boxHalf(z, r),
            side = z.side, form = z.form, surfaceRule = z.surface,
            surfaceGrade = surfaceName(surfaceRankOf(z)), surfaceRank = surfaceRankOf(z),
            colorHex = color.hex, colorSource = color.source, colorAsOf = color.asOf,
            colorDeepenStep = z.colorDeepenStep,
            turgorGain = FactRange(
                "${z.zoneId}.gain", z.gain.low, z.gain.high, "ratio",
                z.gain.source, z.gain.asOf, z.gain.evidence,
            ),
            proxyKind = z.proxy, anchorJoint = z.anchor,
            layers = z.layers, retractable = z.retractable,
            sourceRef = z.sourceRef, lengthRef = z.lengthRef,
        )
    }

    /** 龟头质地更光滑：秩恒比柱身高一档（表里 smoother_than_shaft 是口径，不是档位名）。 */
    private fun surfaceRankOf(z: ZoneSpec): Int {
        val base = SURFACE_ORDER.indexOf(surfaceGrade)
        return if (z.surface == "smoother_than_shaft") base + 1 else base
    }

    private fun surfaceName(rank: Int): String =
        SURFACE_ORDER[rank.coerceIn(0, SURFACE_ORDER.lastIndex)]

    private fun boxHalf(z: ZoneSpec, r: FactRange): Vec3? {
        if (z.proxy != "box") return null
        val rc = r.mid() / MM_PER_CM
        val halfZ = if (z.zoneId == "meatus") {
            table.domain("meatus_slit_length_mm").default / 2.0 / MM_PER_CM
        } else {
            abs(z.axial.high - z.axial.low) * lengthErectMm.value / 2.0 / MM_PER_CM
        }
        if (z.zoneId == "mons") {
            val w = frame.sections.first { it.id == "pelvis" }.widthCm.value *
                table.domain("mons_width_frac_of_pelvis").default / 2.0
            return Vec3(w, rc, halfZ)
        }
        return Vec3(rc, rc, halfZ)
    }

    private fun testisRadius(base: FactRange, side: String): FactRange {
        val d = scrotum.asymmetryMm / 2.0
        return if (side == "left") shift(base, d) else shift(base, -d)
    }

    private fun bilateral(z: ZoneSpec, c: Vec3, side: String): Vec3 {
        val sign = if (side == "left") -1.0 else 1.0
        val lower = if (z.zoneId == "testis" && z.leftLower && side == "left") {
            table.domain("testis_left_lower_mm").default / MM_PER_CM
        } else {
            0.0
        }
        return Vec3(sign * abs(c.x), c.y - lower, c.z)
    }

    private fun resolveProfile(): List<ProfilePoint> {
        val n = table.domain("diameter_profile_points").default.toInt()
        require(n in 5..8) { "直径剖面点数须在 5..8，得 $n" }
        return (0 until n).map { i ->
            val f = i.toDouble() / (n - 1).toDouble()
            val r = radiusAtFrac(f)
            ProfilePoint(f, FactRange("profile.$i", r.low * 2.0, r.high * 2.0, "mm", r.source, r.asOf, r.evidence))
        }
    }

    /** 剖面只取柱身类区（盒状的缝/带/口是表面特征，不参与直径剖面）。 */
    private fun radiusAtFrac(f: Double): FactRange {
        val axis = table.zones.filter { it.proxy != "box" && it.axial.low >= 0.0 }
        val z = axis.filter { f >= it.axial.low && f <= it.axial.high }
            .minByOrNull { it.axial.high - it.axial.low } ?: table.zone("shaft")
        val r = gainRadius(z)
        val taper = if (shape == "tapered") 1.0 - table.domain("taper_frac").default * f else 1.0
        return FactRange(r.id, r.low * taper, r.high * taper, "mm", r.source, r.asOf, r.evidence)
    }

    private fun resolveBulge(): Double {
        val dCm = shaftRadiusMm() * 2.0 / MM_PER_CM
        return table.bulge.diameterFactor * dCm * table.bulge.coefficient(fill)
    }

    private fun resolveContact(): ContactState {
        val r = table.contact.restitution
        val range = FactRange("contact.restitution", r.low, r.high, "ratio", r.source, r.asOf, r.evidence)
        return ContactState(range, table.contact.stiffness.default.toInt(), range.low + range.width * fill)
    }

    private fun resolveAdvisories(): List<String> {
        val out = mutableListOf<String>()
        out.addAll(facts.advisoriesFor(diameterErectMm.value))
        if (diameterErectMm.value > facts.defaultCapMm) {
            out.add("超默认抽样上限 ${facts.defaultCapMm}mm：可存在，须附极罕见标记")
        }
        val rare = facts.rareLength
        if (lengthErectMm.value >= rare.valueMm) {
            out.add("长度达 ${rare.valueMm}mm：极少见（${rare.rarity}），不得作默认")
        }
        val lenDom = table.domain("erect_length_mm")
        if (lengthErectMm.value > lenDom.high) {
            out.add("长度 ${lengthErectMm.value}mm 超常见观察域上界 ${lenDom.high}mm：少见，须附说明")
        }
        val dom = table.domain("flaccid_length_mm")
        if (!dom.contains(lengthFlaccidMm.value)) {
            out.add("疲软长度 ${lengthFlaccidMm.value}mm 落在观察域 ${dom.low}..${dom.high} 之外")
        }
        return out.toList()
    }

    /** 根部在身体坐标系（cm，y＝离地高）里的挂点：髋线下方、骨盆截面之前。 */
    fun rootCm(): Vec3 {
        val pelvis = frame.sections.first { it.id == "pelvis" }
        return Vec3(
            0.0,
            frame.layout.hipY - table.rootY.default * spec.heightCm,
            pelvis.thicknessCm.value / 2.0,
        )
    }

    /** 供高度场/代理体取当前柱身半径区间。 */
    fun shaftRadius(): FactRange = gainRadius(table.zone("shaft"))

    /** 胶囊区在规范系里的两个轴端点：悬垂区沿 −Y，柱身区沿弯曲轴（含侧偏）。 */
    fun zoneEnds(z: GenitalZone): Pair<Vec3, Vec3> {
        if (z.axialFromMm < 0.0) {
            val x = z.centerCm.x
            return Vec3(x, -abs(z.axialFromMm) / MM_PER_CM, 0.0) to
                Vec3(x, -abs(z.axialToMm) / MM_PER_CM, 0.0)
        }
        val off = z.centerCm - axisPoint((z.axialFromMm + z.axialToMm) / 2.0)
        return axisPoint(z.axialFromMm) + off to axisPoint(z.axialToMm) + off
    }
}