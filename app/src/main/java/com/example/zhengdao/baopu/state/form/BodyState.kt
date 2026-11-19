// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：BodyState（裁定③：形体归 660，组合归装配，通电归 699）。
// 本单零装配：不碰 ZhengdaoApp、不建状态机、不写挂接行；只产出"场次里的身体这部分"。
package com.example.zhengdao.baopu.state.form

import com.example.zhengdao.baopu.body.AnatomyZone
import com.example.zhengdao.baopu.body.AnatomyZones
import com.example.zhengdao.baopu.state.geometry.GeometryNotes
import java.io.File

/** 定标账：哪些读数是实测、哪些是推算，以及偏离留痕（工单 §一⑤）。 */
data class CalibrationReport(
    val measured: List<String>,
    val derived: List<String>,
    val deviations: List<Deviation>,
) {
    val measuredCount: Int get() = measured.size
    val derivedCount: Int get() = derived.size
}

/**
 * 定标入口：填 3–5 个真实尺寸（必须带来源），其余按比例推并标"推算"。
 * 键：height_cm / weight_kg / body_fat_pct / shoulder_biacromial / arm_span / head_body_ratio /
 * 分段 id（长度）/ 截面 id.width / 截面 id.thickness。悬空键即拒。
 */
object Calibration {

    fun apply(readings: Map<String, Override>, base: HumanBodySpec, props: Proportions): HumanBodySpec {
        val lo = props.constant("calibration_min_readings").default.toInt()
        val hi = props.constant("calibration_max_readings").default.toInt()
        require(readings.size in lo..hi) { "定标须填 $lo–$hi 个实测尺寸，得 ${readings.size}" }
        var s = base
        for ((k, v) in readings.toSortedMap()) s = put(k, v, s, props)
        return s
    }

    private fun put(key: String, ov: Override, b: HumanBodySpec, props: Proportions): HumanBodySpec =
        scalar(key, ov, b) ?: geometry(key, ov, b, props)

    private fun scalar(key: String, ov: Override, b: HumanBodySpec): HumanBodySpec? = when (key) {
        "height_cm" -> b.copy(heightCm = ov.value)
        "weight_kg" -> b.copy(weightKg = ov.value)
        "body_fat_pct" -> b.copy(bodyFatPct = ov.value)
        "shoulder_biacromial" -> b.copy(shoulderRatio = ov)
        "arm_span" -> b.copy(armSpanRatio = ov)
        "head_body_ratio" -> b.copy(headBodyRatio = ov)
        else -> null
    }

    private fun geometry(key: String, ov: Override, b: HumanBodySpec, props: Proportions): HumanBodySpec {
        val secIds = props.sections.map { it.id }
        val segIds = props.segments.map { it.id }
        val w = key.removeSuffix(".width")
        val t = key.removeSuffix(".thickness")
        return when {
            key.endsWith(".width") && w in secIds -> b.copy(sections = b.sections + (w to section(b, w).copy(widthCm = ov)))
            key.endsWith(".thickness") && t in secIds ->
                b.copy(sections = b.sections + (t to section(b, t).copy(thicknessCm = ov)))
            key in segIds -> b.copy(segments = b.segments + (key to segment(b, key).copy(lengthCm = ov)))
            else -> throw IllegalArgumentException("定标键悬空：$key")
        }
    }

    private fun section(b: HumanBodySpec, id: String) = b.sections[id] ?: SectionOverride()

    private fun segment(b: HumanBodySpec, id: String) = b.segments[id] ?: SegmentOverride()
}

/** 皮肤读数：弹性档/体毛档/色值位数＋分区体毛梯度（梯度是方向性口径，不是数值区间）。 */
data class SkinReading(
    val elasticity: Sourced,
    val hair: Sourced,
    val hexDigits: Int,
    val hairByZone: Map<String, Double>,
    val gradientSource: String,
    val gradientAsOf: String,
)

/**
 * 身体这部分的全部产出（五件）：输入＋推算骨架＋13 区生殖器＋体表高度场＋代理体。
 * 不叫 SceneState：场次由装配层组合 660/657/658/699 的产出，本单不自造。
 */
data class BodyState(
    val spec: HumanBodySpec,
    val frame: BodyFrame,
    val genital: GenitalModel,
    val heightField: BodyHeightField,
    val proxies: BodyProxies,
    val skin: SkinReading,
    val flex: FlexReading,
    val jointLimits: List<JointFlexLimit>,
    val calibration: CalibrationReport,
    val vocabErrors: List<VocabError>,
    val advisories: List<String>,
    val assumption: String = BODY_ASSUMPTION,
    val note: String = GeometryNotes.NOT_MEASUREMENT,
) {
    /** 硬误写命中即不通过；偏离留痕只报不改写，不拉低本值。 */
    val ok: Boolean get() = vocabErrors.isEmpty()

    /** 判据①：三条比例落人群区间（不叠 ±10%）。 */
    val ratiosInPopulationRange: Boolean get() = frame.ratios.allInPopulationRange()

    val deviations: List<Deviation> get() = frame.deviations
}

private const val BODY_ASSUMPTION =
    "比例推算＋口径表区间：肩峰宽/臂展/头身比取人群比例区间，分段长度按身高比例、躯干与上臂取残差保总高自洽；" +
        "生殖器尺寸经 FactSource 分位表，不由身高推；体表高度场是椭球叠加隆起，非组织力学解"

/** 形体产出入口。of 是纯函数（换底演练只换 facts 一个参数）；load 只多一步读盘。 */
object BodyModel {

    fun of(
        spec: HumanBodySpec,
        props: Proportions,
        zones: ZoneTable,
        anatomy: List<AnatomyZone>,
        facts: FactSource,
    ): BodyState {
        SpecGuard.check(spec, props, zones)
        val frame = BodyFrame(spec, props)
        val genital = GenitalModel(spec.genital, spec, frame, facts, zones, anatomy)
        val field = BodyHeightField(frame, props, spec.parts, genital)
        val flex = Flexibility.reading(spec, props)
        return BodyState(
            spec = spec, frame = frame, genital = genital, heightField = field,
            proxies = ProxyExport.of(frame, props, genital),
            skin = skinReading(spec, props, zones),
            flex = flex,
            jointLimits = Flexibility.limits(flex.grade, props),
            calibration = audit(frame, genital),
            vocabErrors = genital.vocabErrors, advisories = genital.advisories,
        )
    }

    private fun skinReading(spec: HumanBodySpec, props: Proportions, zones: ZoneTable): SkinReading {
        val s = props.skin
        return SkinReading(
            elasticity = grade(spec.skin.elasticityGrade, s.elasticity),
            hair = grade(spec.skin.hairGrade, s.hair),
            hexDigits = s.hexDigits.default.toInt(),
            hairByZone = zones.zones.associate { it.zoneId to s.factor(it.zoneId) },
            gradientSource = s.gradientSource, gradientAsOf = s.gradientAsOf,
        )
    }

    private fun grade(v: Int?, s: NumericSpec) =
        Sourced((v ?: s.default.toInt()).toDouble(), "grade", Origin.DERIVED, s.source, s.asOf)

    fun load(spec: HumanBodySpec, repoRoot: File, facts: FactSource = FactsFromMd): BodyState = of(
        spec, FormTables.loadProportions(repoRoot), FormTables.loadZones(repoRoot),
        AnatomyZones.load(repoRoot), facts,
    )

    private fun audit(frame: BodyFrame, genital: GenitalModel): CalibrationReport {
        val readings = mutableListOf<Pair<String, Sourced>>()
        readings.add("shoulder_biacromial" to frame.ratios.shoulder)
        readings.add("arm_span" to frame.ratios.armSpan)
        readings.add("head_body_ratio" to frame.ratios.headBody)
        for ((id, s) in frame.lengths) readings.add(id to s)
        for ((id, s) in frame.radii) readings.add("$id.radius" to s)
        for (s in frame.sections) {
            readings.add("${s.id}.width" to s.widthCm)
            readings.add("${s.id}.thickness" to s.thicknessCm)
        }
        readings.add("genital.length" to genital.lengthErectMm)
        readings.add("genital.girth" to genital.girthErectMm)
        readings.add("genital.flaccid" to genital.lengthFlaccidMm)
        val (m, d) = readings.partition { it.second.origin == Origin.MEASURED }
        return CalibrationReport(m.map { it.first }, d.map { it.first }, frame.deviations)
    }
}