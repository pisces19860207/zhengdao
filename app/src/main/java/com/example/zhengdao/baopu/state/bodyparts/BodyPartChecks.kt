// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零五批 · 判据：纯函数，只回答「这张部位单自相矛盾不矛盾」，不下生理结论、不进闸、不落库。
// 供单测正负例与变异自检使用；生产侧不自动调用（装配单要判的时候再调）。
//
// 四条判据：
// ② 体脂一致性（共同约束）：脂低却软塌／不显 ⇒ 红；脂高却分块分明 ⇒ 红；
// ⑥ 乳晕：档差矩阵（见 AreolaMatrix）＋ 不得越过胸肌下缘；
// ⑩ 左右对称：左右档差两档以上，却写着「基本对称」⇒ 红；
// ⑨ 喉结：明显起须挂驱动，高凸须两条依据。
//
// 每条结论都带来源与日期（Finding 构造即要求），why 里不许出现长度单位与判定词（Guard 当场拦）。
package com.example.zhengdao.baopu.state.bodyparts

/**
 * 一条结论。[source] 留空按「无来源」记。
 * 构造即守：结论必须有代码、有理由，且理由里没有长度单位、没有诊断词。
 */
data class Finding(
    val verdict: Verdict,
    val code: String,
    val part: BodyPart?,
    val dim: Dim?,
    val why: String,
    val source: String = BODYPART_SOURCE,
    val asOf: String = BODYPART_AS_OF,
) {
    init {
        require(code.isNotBlank()) { "结论缺代码：$NO_SOURCE" }
        require(why.isNotBlank()) { "结论缺理由：$NO_SOURCE" }
        BodyPartGuard.rejectMeasures(why)
        BodyPartGuard.rejectDiagnosis(why)
    }

    val attributedSource: String get() = source.ifBlank { NO_SOURCE }

    /** 一行结论：判定＋代码＋理由（来源 日期）。 */
    fun line(): String =
        "[" + verdict.label + "] " + code + "：" + why + "（" + attributedSource + " " + asOf + "）"

    companion object {
        const val SYMMETRY_STEP: Int = 2
        const val STRICT_DRIVER_COUNT: Int = 2
    }
}

/** 判据集合。全部是纯函数：同一个单子跑两遍，结论逐字一致。 */
object BodyPartChecks {

    /**
     * 判据②：体脂一致性是八部位的共同约束。
     * 脂低（很瘦／偏瘦）：收紧后仍不见分块、胸肌既不高又不分 ⇒ 红。
     * 脂高（偏高）：分块分明、胸肌深沟、肱二头血管毕现 ⇒ 红。
     */
    fun fatConsistency(body: BodyParts, fat: FatBand): List<Finding> {
        val out = mutableListOf<Finding>()
        if (fat.lean) {
            leanAbs(body, fat)?.let { out += it }
            leanPec(body, fat)?.let { out += it }
        }
        if (fat.high) {
            highAbs(body, fat)?.let { out += it }
            highPec(body, fat)?.let { out += it }
            highVessel(body, fat)?.let { out += it }
        }
        return out
    }

    /** 判据⑥：乳晕直径档（倍数）× 两乳头间距档 → 矩阵；另加「越过胸肌下缘」即红。 */
    fun areolaRule(body: BodyParts, spacing: SpacingBand): List<Finding> {
        val out = mutableListOf<Finding>()
        for (side in BOTH_SIDES) {
            val ratio = AreolaRatio.of(body.reading(Dim.AREOLA_RATIO, side, Posture.RELAXED).level)
            val verdict = AreolaMatrix.verdict(spacing, ratio)
            if (verdict != Verdict.PASS) {
                out += Finding(
                    verdict, "AREOLA-RATIO-" + verdict.name, BodyPart.AREOLA, Dim.AREOLA_RATIO,
                    side.label + "侧 " + AreolaMatrix.reason(spacing, ratio),
                )
            }
            val reach = body.reading(Dim.AREOLA_REACH, side, Posture.RELAXED).level
            if (reach >= AreolaReach.CROSS_EDGE.level) {
                out += Finding(
                    Verdict.DENY, "AREOLA-CROSS-EDGE", BodyPart.AREOLA, Dim.AREOLA_REACH,
                    side.label + "侧乳晕越过胸肌下缘：下缘以内是上限",
                )
            }
        }
        return out
    }

    /** 判据⑩：左右档差两档以上时，对称档还写「基本对称」以上 ⇒ 红；无对称档的部位降为须核。 */
    fun symmetryRule(body: BodyParts): List<Finding> {
        val out = mutableListOf<Finding>()
        for (spec in BodyPartCatalog.specs) {
            if (!spec.bilateral) continue
            for (p in spec.postures) {
                val left = body.reading(spec.dim, Side.LEFT, p).level
                val right = body.reading(spec.dim, Side.RIGHT, p).level
                if (kotlin.math.abs(left - right) < Finding.SYMMETRY_STEP) continue
                symmetryFinding(body, spec, p, left, right)?.let { out += it }
            }
        }
        return out
    }

    /** 判据⑨：喉结 3 档起须挂驱动，4 档（高凸）须两条。 */
    fun larynxRule(body: BodyParts, drivers: Set<LarynxDriver>): List<Finding> {
        val level = body.reading(Dim.LARYNX, Side.MIDLINE, Posture.RELAXED).level
        val out = mutableListOf<Finding>()
        if (level >= LarynxGrade.DRIVER_FROM && drivers.isEmpty()) {
            out += Finding(
                Verdict.DENY, "LARYNX-NO-DRIVER", BodyPart.LARYNX, Dim.LARYNX,
                "喉结" + LarynxGrade.of(level).label + "：六类驱动一项都没挂",
            )
        }
        if (level >= LarynxGrade.STRICT_BASIS && drivers.size < Finding.STRICT_DRIVER_COUNT) {
            out += Finding(
                Verdict.DENY, "LARYNX-THIN-BASIS", BodyPart.LARYNX, Dim.LARYNX,
                "喉结高凸须两条依据，现只有 " + drivers.size + " 条",
            )
        }
        return out
    }

    /** 全判据汇总。空列表＝这张单子自洽。 */
    fun all(
        body: BodyParts,
        fat: FatBand,
        spacing: SpacingBand,
        drivers: Set<LarynxDriver> = emptySet(),
    ): List<Finding> =
        fatConsistency(body, fat) + areolaRule(body, spacing) +
            symmetryRule(body) + larynxRule(body, drivers)

    /** 一行汇总，给报告用：过／须核／红 各几条。 */
    fun summary(findings: List<Finding>): String {
        val pass = findings.count { it.verdict == Verdict.PASS }
        val check = findings.count { it.verdict == Verdict.NEED_CHECK }
        val deny = findings.count { it.verdict == Verdict.DENY }
        return "过 " + pass + "／须核 " + check + "／红 " + deny
    }

    /** 两态对照（腹肌放松 vs 收紧、肱二头峰度放松 vs 屈曲），给报告与人工核对用。 */
    fun twoStates(body: BodyParts, dim: Dim): String {
        val spec = BodyPartCatalog.spec(dim)
        return spec.postures.joinToString("／") { p ->
            p.label + "=" + body.levels(dim, p).joinToString(",")
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private fun maxLevel(body: BodyParts, dim: Dim): Int {
        val spec = BodyPartCatalog.spec(dim)
        return spec.postures.maxOf { p -> body.levels(dim, p).max() }
    }

    private fun leanAbs(body: BodyParts, fat: FatBand): Finding? {
        if (body.levels(Dim.ABS_BLOCK, Posture.TIGHTENED).any { it > AbsBlock.NONE.level }) return null
        return Finding(
            Verdict.DENY, "FAT-LEAN-ABS-FLAT", BodyPart.ABS, Dim.ABS_BLOCK,
            "体脂" + fat.label + "，收紧后两侧仍不见分块：脂低才显线条，这条对不上",
        )
    }

    private fun leanPec(body: BodyParts, fat: FatBand): Finding? {
        val volume = maxLevel(body, Dim.PEC_VOLUME)
        val separation = maxLevel(body, Dim.PEC_SEPARATION)
        if (volume > PecVolume.SLIGHT.level || separation > PecSeparation.NONE.level) return null
        return Finding(
            Verdict.DENY, "FAT-LEAN-PEC-SOFT", BodyPart.PEC, Dim.PEC_VOLUME,
            "体脂" + fat.label + "，胸肌既不高又不分：脂低却软塌，这条对不上",
        )
    }

    private fun highAbs(body: BodyParts, fat: FatBand): Finding? {
        if (maxLevel(body, Dim.ABS_BLOCK) < AbsBlock.DISTINCT.level) return null
        return Finding(
            Verdict.DENY, "FAT-HIGH-ABS-CUT", BodyPart.ABS, Dim.ABS_BLOCK,
            "体脂" + fat.label + "，分块却分明：脂高先盖住线条，这条对不上",
        )
    }

    private fun highPec(body: BodyParts, fat: FatBand): Finding? {
        if (maxLevel(body, Dim.PEC_SEPARATION) < PecSeparation.DEEP.level) return null
        return Finding(
            Verdict.DENY, "FAT-HIGH-PEC-DEEP", BodyPart.PEC, Dim.PEC_SEPARATION,
            "体脂" + fat.label + "，胸肌却是深沟：脂高先盖住分界，这条对不上",
        )
    }

    private fun highVessel(body: BodyParts, fat: FatBand): Finding? {
        if (maxLevel(body, Dim.BICEPS_VESSEL) < Vessel.VISIBLE.level) return null
        return Finding(
            Verdict.DENY, "FAT-HIGH-VESSEL", BodyPart.BICEPS, Dim.BICEPS_VESSEL,
            "体脂" + fat.label + "，肱二头血管却可见：同一份体脂不能两头都占",
        )
    }

    private fun symmetryFinding(
        body: BodyParts,
        spec: DimSpec,
        posture: Posture,
        left: Int,
        right: Int,
    ): Finding? {
        val gap = kotlin.math.abs(left - right)
        val written = symmetricClaim(body, spec.part)
        val why = spec.dim.label + "·" + posture.label + " 左右差 " + gap + " 档"
        if (written != null) {
            // 有对称档：写着「基本对称」以上才算自相矛盾；写着不对称就是自洽，不报。
            if (written < Symmetry.MOSTLY.level) return null
            return Finding(
                Verdict.DENY, "SYMMETRY-CONTRADICT", spec.part, Dim.PEC_SYMMETRY,
                why + "，却写着「" + Symmetry.of(written).label + "」：两句不能同时成立",
            )
        }
        return Finding(
            Verdict.NEED_CHECK, "SYMMETRY-GAP", spec.part, spec.dim,
            why + "，该部位没有对称档兜底：须核",
        )
    }

    /** 只有胸肌登记了对称档；其它部位返回 null。 */
    private fun symmetricClaim(body: BodyParts, part: BodyPart): Int? {
        if (part != BodyPart.PEC) return null
        return body.reading(Dim.PEC_SYMMETRY, Side.MIDLINE, Posture.RELAXED).level
    }

    private val BOTH_SIDES: List<Side> = listOf(Side.LEFT, Side.RIGHT)
}
