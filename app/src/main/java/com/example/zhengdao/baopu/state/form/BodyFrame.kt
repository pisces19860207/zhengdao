// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：比例 → 分段 → 截面 的推算骨架。
// 裁定②：三条比例落人群区间即通过，不再叠 ±10%；±10% 只用于"实测覆盖值 vs 推算值"。
package com.example.zhengdao.baopu.state.form

/** 一条偏离留痕（只报不改写）：超人群区间，或实测与推算偏差超定标容差。 */
data class Deviation(val id: String, val kind: String, val detail: String)

/** 三条比例读数＋它们的口径区间。 */
data class Ratios(
    val shoulder: Sourced,
    val armSpan: Sourced,
    val headBody: Sourced,
    val intervals: Map<String, NumericSpec>,
) {
    /** 头高／身高＝头身比的倒数。 */
    val headHeightFrac: Double get() = 1.0 / headBody.value

    /** 三值是否都落在人群比例区间内（判据①，不叠 ±10%）。 */
    fun allInPopulationRange(): Boolean =
        intervals.getValue("shoulder_biacromial").contains(shoulder.value) &&
            intervals.getValue("arm_span").contains(armSpan.value) &&
            intervals.getValue("head_body_ratio").contains(headBody.value)
}

/** 软组织与脂肪派生系数：体重管半径，体脂管隆起可见度与不确定度。 */
data class TissueFactors(
    val bmi: Double,
    val softTissue: Double,
    val muscleGrade: Int,
    val muscle: Double,
    val fatCover: Double,
    val fatUncertainty: Double,
)

/** 竖直骨架（cm，y＝离地高）与横向关节偏置。 */
data class Layout(
    val hipY: Double,
    val pelvisTopY: Double,
    val neckBaseY: Double,
    val headBaseY: Double,
    val topY: Double,
    val shoulderY: Double,
    val shoulderJointX: Double,
    val hipJointX: Double,
    val trunkLen: Double,
    val abdomenLen: Double,
    val chestLen: Double,
)

/** 躯干柱截面（椭圆，宽×厚，cm）。 */
data class SectionGeometry(
    val id: String,
    val station: Double,
    val yCm: Double,
    val widthCm: Sourced,
    val thicknessCm: Sourced,
)

/** 分段几何：长度/半径都带出身，关节名取 655 的 JointNames。 */
data class SegmentGeometry(
    val id: String,
    val joint: String,
    val extraJoint: String?,
    val side: String,
    val shape: String,
    val lengthCm: Sourced,
    val radiusCm: Sourced?,
    val yTopCm: Double,
    val yBottomCm: Double,
    val lateralCm: Double,
    val splitFrac: Double?,
)

/**
 * 推算骨架。构造即算完，同输入逐字节一致（零随机、零时钟、零联网）。
 * 总高自洽由残差保证：躯干＝身高减其余轴向段；上臂＝臂展半长减其余臂段。
 */
class BodyFrame(val spec: HumanBodySpec, val props: Proportions) {
    private val trail = mutableListOf<Deviation>()

    val ratios: Ratios = resolveRatios()
    val factors: TissueFactors = resolveFactors()
    val lengths: Map<String, Sourced> = resolveLengths()
    val radii: Map<String, Sourced> = resolveRadii()
    val layout: Layout = resolveLayout()
    val sections: List<SectionGeometry> = resolveSections()
    val segments: List<SegmentGeometry> = resolveSegments()

    /** 偏离留痕（构造期追加，构造后只读）。 */
    val deviations: List<Deviation> get() = trail.toList()

    /** 实际臂展（cm）：由肩关节横偏＋三段臂长合成，覆盖后仍自洽可核。 */
    val armSpanCm: Double
        get() = 2.0 * (layout.shoulderJointX + cm("upper_arm") + cm("forearm") + cm("hand"))

    fun cm(id: String) = lengths.getValue(id).value

    private fun derived(v: Double, s: NumericSpec) = Sourced(v, "cm", Origin.DERIVED, s.source, s.asOf)

    private fun measured(o: Override) = Sourced(o.value, "cm", Origin.MEASURED, o.source, o.asOf)

    private fun residual(v: Double, basis: String, asOf: String) =
        Sourced(v, "cm", Origin.DERIVED, "比例推算·残差（$basis）", asOf)

    private fun note(id: String, kind: String, detail: String) {
        trail.add(Deviation(id, kind, detail))
    }

    private fun tolerance() = props.constant("calibration_tolerance").default

    private fun checkCalibration(id: String, actual: Double, expected: Double) {
        if (expected <= 0.0) return
        val rel = kotlin.math.abs(actual - expected) / expected
        if (rel > tolerance()) {
            note(id, "calibration", "实测 $actual 与推算 $expected 偏差 ${rel}，超定标容差 ${tolerance()}")
        }
    }

    private fun pickRatio(id: String, ov: Override?): Sourced {
        val s = props.ratio(id)
        if (ov == null) return Sourced(s.default, "ratio", Origin.DERIVED, s.source, s.asOf)
        if (!s.contains(ov.value)) {
            note(id, "population_range", "覆盖值 ${ov.value} 落在人群区间 ${s.low}..${s.high} 之外")
        }
        checkCalibration(id, ov.value, s.default)
        return Sourced(ov.value, "ratio", Origin.MEASURED, ov.source, ov.asOf)
    }

    private fun resolveRatios(): Ratios = Ratios(
        shoulder = pickRatio("shoulder_biacromial", spec.shoulderRatio),
        armSpan = pickRatio("arm_span", spec.armSpanRatio),
        headBody = pickRatio("head_body_ratio", spec.headBodyRatio),
        intervals = props.ratios,
    )

    private fun grade(v: Int?, s: NumericSpec): Int {
        val g = v ?: s.default.toInt()
        require(g.toDouble() in s.low..s.high) { "${s.id} 档位 $g 超域 ${s.low}..${s.high}" }
        return g
    }

    private fun resolveFactors(): TissueFactors {
        val bmi = spec.weightKg / (spec.heightM * spec.heightM)
        val clamp = props.constant("soft_tissue_clamp")
        val soft = (bmi / props.constant("ref_bmi").default).coerceIn(clamp.low, clamp.high)
        val mr = props.constant("muscle_grade_range")
        val mg = grade(spec.muscleGrade, mr)
        val muscle = 1.0 + props.constant("muscle_step").default * (mg - (mr.low + mr.high) / 2.0)
        val fatRange = props.constant("body_fat_range_pct")
        require(spec.bodyFatPct in fatRange.low..fatRange.high) {
            "体脂率 ${spec.bodyFatPct} 超可行域 ${fatRange.low}..${fatRange.high}"
        }
        return TissueFactors(bmi, soft, mg, muscle, fatCover(), fatUncertainty())
    }

    private fun fatCover(): Double {
        val c = props.constant("fat_cover_clamp")
        val v = 1.0 - props.constant("fat_cover_slope").default *
            (spec.bodyFatPct - props.constant("fat_cover_ref_pct").default)
        return v.coerceIn(c.low, c.high)
    }

    private fun fatUncertainty(): Double =
        props.constant("fat_uncertainty_base").default +
            props.constant("fat_uncertainty_slope").default *
            (spec.bodyFatPct - props.constant("fat_uncertainty_ref_pct").default)

    private fun directLengths(): Map<String, Sourced> {
        val out = LinkedHashMap<String, Sourced>()
        for (s in props.segments) {
            val len = s.length ?: continue
            val expected = len.default * spec.heightCm
            val ov = spec.segments[s.id]?.lengthCm
            if (ov == null) {
                out[s.id] = derived(expected, len)
            } else {
                checkCalibration(s.id, ov.value, expected)
                out[s.id] = measured(ov)
            }
        }
        return out
    }

    private fun resolveLengths(): Map<String, Sourced> {
        val out = LinkedHashMap(directLengths())
        val headSpec = props.segment("head")
        val headOv = spec.segments["head"]?.lengthCm
        val headExpected = spec.heightCm * ratios.headHeightFrac
        out["head"] = if (headOv == null) {
            residual(headExpected, "头身比倒数", props.ratio("head_body_ratio").asOf)
        } else {
            checkCalibration("head", headOv.value, headExpected)
            measured(headOv)
        }
        require(headSpec.lengthFrom == "head_body_ratio") { "头段长度来源口径不符" }
        out["torso"] = torsoLength(out)
        out["upper_arm"] = upperArmLength(out)
        return out
    }

    private fun torsoLength(d: Map<String, Sourced>): Sourced {
        val legs = d.getValue("thigh").value + d.getValue("calf").value + d.getValue("foot").value
        val v = spec.heightCm - d.getValue("head").value - d.getValue("neck").value -
            d.getValue("pelvis").value - legs
        require(v > 0.0) { "躯干残差非正（$v）：覆盖值把身高挤爆了" }
        return residual(v, "身高−头−颈−骨盆−腿总长", props.constant("leg_total_frac").asOf)
    }

    private fun upperArmLength(d: Map<String, Sourced>): Sourced {
        val inset = props.constant("shoulder_joint_inset_frac").default
        val jointFrac = ratios.shoulder.value / 2.0 - inset
        val expected = (ratios.armSpan.value / 2.0 - jointFrac) * spec.heightCm -
            d.getValue("forearm").value - d.getValue("hand").value
        require(expected > 0.0) { "上臂残差非正（$expected）：臂展/肩宽覆盖值不自洽" }
        val ov = spec.segments["upper_arm"]?.lengthCm
        if (ov == null) {
            return residual(expected, "臂展半长−肩关节横偏−前臂−手", props.ratio("arm_span").asOf)
        }
        checkCalibration("upper_arm", ov.value, expected)
        return measured(ov)
    }

    private fun resolveRadii(): Map<String, Sourced> {
        val out = LinkedHashMap<String, Sourced>()
        for (s in props.segments) {
            val r = s.radius ?: continue
            val expected = r.default * spec.heightCm * factors.softTissue * factors.muscle
            val ov = spec.segments[s.id]?.radiusCm
            if (ov == null) {
                out[s.id] = derived(expected, r)
            } else {
                checkCalibration("${s.id}.radius", ov.value, expected)
                out[s.id] = measured(ov)
            }
        }
        return out
    }

    private fun resolveLayout(): Layout {
        val hipY = cm("thigh") + cm("calf") + cm("foot")
        val pelvisTopY = hipY + cm("pelvis")
        val trunkLen = cm("pelvis") + cm("torso")
        val neckBaseY = pelvisTopY + cm("torso")
        val headBaseY = neckBaseY + cm("neck")
        val split = requireNotNull(props.segment("torso").split) { "躯干段缺 split_frac，拒收" }.default
        val chestLen = cm("torso") * split
        return Layout(
            hipY = hipY, pelvisTopY = pelvisTopY, neckBaseY = neckBaseY, headBaseY = headBaseY,
            topY = headBaseY + cm("head"),
            shoulderY = props.constant("shoulder_height_frac").default * spec.heightCm,
            shoulderJointX = (ratios.shoulder.value / 2.0 - props.constant("shoulder_joint_inset_frac").default) * spec.heightCm,
            hipJointX = props.constant("hip_offset_frac").default * spec.heightCm,
            trunkLen = trunkLen, abdomenLen = cm("torso") - chestLen, chestLen = chestLen,
        )
    }

    private fun resolveSections(): List<SectionGeometry> = props.sections.map { s ->
        val ov = spec.sections[s.id]
        val wExp = s.width.default * spec.heightCm * factors.softTissue
        val tExp = s.thickness.default * spec.heightCm * factors.softTissue
        val w = ov?.widthCm
        val t = ov?.thicknessCm
        if (w != null) checkCalibration("${s.id}.width", w.value, wExp)
        if (t != null) checkCalibration("${s.id}.thickness", t.value, tExp)
        SectionGeometry(
            id = s.id,
            station = s.station.default,
            yCm = layout.hipY + s.station.default * layout.trunkLen,
            widthCm = w?.let { measured(it) } ?: derived(wExp, s.width),
            thicknessCm = t?.let { measured(it) } ?: derived(tExp, s.thickness),
        )
    }

    private fun lateralOf(s: SegmentSpec): Double = when (s.lateral) {
        "none" -> 0.0
        "shoulder" -> layout.shoulderJointX
        "hip" -> layout.hipJointX
        else -> throw IllegalArgumentException("未知横向口径 ${s.lateral}")
    }

    private fun resolveSegments(): List<SegmentGeometry> = props.segments.map { s ->
        val len = cm(s.id)
        val top = topOf(s)
        SegmentGeometry(
            id = s.id, joint = s.joint, extraJoint = s.extraJoint, side = s.side, shape = s.shape,
            lengthCm = lengths.getValue(s.id), radiusCm = radii[s.id],
            yTopCm = top, yBottomCm = top - len, lateralCm = lateralOf(s),
            splitFrac = s.split?.default,
        )
    }

    private fun topOf(s: SegmentSpec): Double = when (s.id) {
        "head" -> layout.topY
        "neck" -> layout.headBaseY
        "torso" -> layout.neckBaseY
        "pelvis" -> layout.pelvisTopY
        "upper_arm", "forearm", "hand" -> armTop(s.id)
        "thigh" -> layout.hipY
        "calf" -> layout.hipY - cm("thigh")
        "foot" -> cm("foot")
        else -> throw IllegalArgumentException("未知分段 ${s.id}")
    }

    private fun armTop(id: String): Double = when (id) {
        "upper_arm" -> layout.shoulderY
        "forearm" -> layout.shoulderY - cm("upper_arm")
        else -> layout.shoulderY - cm("upper_arm") - cm("forearm")
    }
}