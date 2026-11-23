// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十八批 · 形状目录表＋档案倾向表。
//
// 目录表是**唯一真相**：档名从档位枚举取（`TorsoCatalog.grades`），这里只登记「哪一维有哪几档」，
// 不另抄一份档名，免得枚举改了表没改。构造时把「漏一维、重复一维、一维挂两处」全部拦下。
//
// 倾向表回答判据④：某种档案的人，形状「不该」落在哪些档。**按反方向写，不按典型值写**——
// 只把「相反的尾巴」判成矛盾，中档／过渡档两边都放行。为什么：白线「中」是大多数人的常态，
// 把常态判成矛盾，档案判据就会天天报提示，最后没人看。判据④ 的原例（久坐却白线深＋斜方陡）
// 两条都落在相反的那一端，照样判得出来。
package com.example.zhengdao.baopu.state.bodyparts

/** 一条档案倾向：[dim] 这一维，形状应当落在 [allowed] 里；落在外面即矛盾。 */
data class Tendency(
    val dim: TorsoDimension,
    val allowed: Set<TorsoGrade>,
    val why: String,
) {
    init {
        require(allowed.isNotEmpty()) { dim.label + "：允许档不能为空" }
        require(allowed.all { it.dim == dim }) { dim.label + "：允许档里有别维的档" }
    }

    /** 落在允许集外＝与档案相反。 */
    fun violatedBy(grade: TorsoGrade): Boolean = grade !in allowed

    /** 允许档的档名，人话列举。 */
    fun allowedWords(): String = TorsoCatalog.grades(dim).filter { it in allowed }
        .joinToString("／") { it.label }
}

/**
 * 档案 → 倾向集合。
 *
 * 三条口径（写在表里，不靠记）：
 * · 只列派工单 §档案倾向 明写的项；派工单没说的维度不硬凑（胸沟宽窄、左右列两项全程不参与）；
 * · 两档相邻处放行（「中」在多张档案里都合法），避免把常态判成矛盾；
 * · 老年只有一条可判约束（块界）：派工单另写「下缘下滑」，下缘位置属形体位置（660 域），
 *   本包不新造第九维——所以老年单靠一条不会判红，这是**有意的降级**，不是漏判。
 */
object TorsoTendencies {

    private val ATHLETIC: List<Tendency> = listOf(
        Tendency(TorsoDimension.RECTUS_BLOCKS,
            setOf(RectusBlocks.FOUR, RectusBlocks.SIX, RectusBlocks.EIGHT), "健身型：块多"),
        Tendency(TorsoDimension.LINEA_ALBA,
            setOf(LineaAlba.MEDIUM, LineaAlba.DEEP), "健身型：白线深"),
        Tendency(TorsoDimension.PEC_LOWER_TREND,
            setOf(PecLowerTrend.OUTER_HIGH), "健身型：下缘外高内低"),
        Tendency(TorsoDimension.TRAP_SLOPE,
            setOf(TrapSlope.STEEP), "健身型：斜方陡"),
    )

    private val LABOR: List<Tendency> = listOf(
        Tendency(TorsoDimension.SERRATUS,
            setOf(Serratus.FAINT, Serratus.OBVIOUS), "体力劳动：前锯肌显"),
        Tendency(TorsoDimension.PEC_LOWER_TREND,
            setOf(PecLowerTrend.FLAT, PecLowerTrend.ROUND_BLUNT), "体力劳动：下缘平"),
        Tendency(TorsoDimension.LAT_FLARE,
            setOf(LatFlare.LOWER_FLARE, LatFlare.STRAIGHT_DOWN), "体力劳动：背阔不呈倒三角"),
    )

    private val SEDENTARY: List<Tendency> = listOf(
        Tendency(TorsoDimension.RECTUS_BLOCKS,
            setOf(RectusBlocks.INDISTINCT, RectusBlocks.TWO, RectusBlocks.FOUR), "久坐：块少"),
        Tendency(TorsoDimension.LINEA_ALBA,
            setOf(LineaAlba.COVERED, LineaAlba.SHALLOW, LineaAlba.MEDIUM), "久坐：白线浅"),
        Tendency(TorsoDimension.PEC_LOWER_TREND,
            setOf(PecLowerTrend.ROUND_BLUNT), "久坐：下缘圆钝"),
        Tendency(TorsoDimension.LAT_FLARE,
            setOf(LatFlare.STRAIGHT_DOWN), "久坐：背阔不扩"),
        Tendency(TorsoDimension.TRAP_SLOPE,
            setOf(TrapSlope.GENTLE, TrapSlope.MID), "久坐：斜方平缓"),
    )

    private val BELLY: List<Tendency> = listOf(
        Tendency(TorsoDimension.LINEA_ALBA,
            setOf(LineaAlba.COVERED), "大腹：白线被脂覆盖"),
        Tendency(TorsoDimension.PEC_LOWER_TREND,
            setOf(PecLowerTrend.FLAT, PecLowerTrend.ROUND_BLUNT), "大腹：下缘被推移变平"),
    )

    private val ELDER: List<Tendency> = listOf(
        Tendency(TorsoDimension.RECTUS_BLOCKS,
            setOf(RectusBlocks.INDISTINCT, RectusBlocks.TWO, RectusBlocks.FOUR), "老年：块界模糊"),
    )

    private val BY_PROFILE: Map<TorsoProfile, List<Tendency>> = mapOf(
        TorsoProfile.ATHLETIC to ATHLETIC,
        TorsoProfile.LABOR to LABOR,
        TorsoProfile.SEDENTARY to SEDENTARY,
        TorsoProfile.BELLY to BELLY,
        TorsoProfile.ELDER to ELDER,
    )

    init {
        require(BY_PROFILE.size == TorsoProfile.entries.size) {
            "倾向表漏了档案：" + TorsoProfile.entries.filterNot { BY_PROFILE.containsKey(it) }
                .joinToString("、") { it.label }
        }
        BY_PROFILE.forEach { (profile, list) ->
            require(list.isNotEmpty()) { profile.label + "：倾向表不能为空" }
            require(list.map { it.dim }.toSet().size == list.size) {
                profile.label + "：同一维登记了两条倾向"
            }
        }
    }

    fun of(profile: TorsoProfile): List<Tendency> = BY_PROFILE.getValue(profile)

    /** 这张档案表没管到的维（本包如实列出来，不假装全覆盖）。 */
    fun unconstrained(profile: TorsoProfile): List<TorsoDimension> {
        val used = of(profile).map { it.dim }.toSet()
        return TorsoDimension.entries.filterNot { it in used }
    }

    /** 全部档案用到的维度（给报告与门禁：这两维从不参与档案判据）。 */
    val NEVER_CONSTRAINED: List<TorsoDimension> =
        TorsoDimension.entries.filter { dim -> BY_PROFILE.values.none { list -> list.any { it.dim == dim } } }
}

/** 形状目录表。 */
object TorsoCatalog {

    /** 维 → 档位枚举（唯一真相）。顺序＝派工单书写序。 */
    val GRADES: Map<TorsoDimension, List<TorsoGrade>> = mapOf(
        TorsoDimension.RECTUS_BLOCKS to RectusBlocks.entries,
        TorsoDimension.LINEA_ALBA to LineaAlba.entries,
        TorsoDimension.BLOCK_ALIGNMENT to BlockAlignment.entries,
        TorsoDimension.PEC_LOWER_TREND to PecLowerTrend.entries,
        TorsoDimension.PEC_GAP to PecGapWidth.entries,
        TorsoDimension.TRAP_SLOPE to TrapSlope.entries,
        TorsoDimension.LAT_FLARE to LatFlare.entries,
        TorsoDimension.SERRATUS to Serratus.entries,
    )

    init {
        require(GRADES.size == TorsoDimension.entries.size) {
            "目录表漏了维度：" + TorsoDimension.entries.filterNot { GRADES.containsKey(it) }
                .joinToString("、") { it.label }
        }
        GRADES.forEach { (dim, list) ->
            require(list.isNotEmpty()) { dim.label + "：档位不能为空" }
            require(list.all { it.dim == dim }) { dim.label + "：档位表里的档声明了别的维" }
            require(list.map { it.level } == list.indices.toList()) {
                dim.label + "：档序必须 0..n 连续，否则按档序取档会取错"
            }
        }
        val covered = TorsoItem.entries.flatMap { it.dimensions }
        require(covered.toSet().size == covered.size) { "有维度挂在两个形状项下" }
        require(covered.toSet() == TorsoDimension.entries.toSet()) {
            "形状项没覆盖全部维度，漏：" + TorsoDimension.entries.filterNot { it in covered }
                .joinToString("、") { it.label }
        }
    }

    fun grades(dim: TorsoDimension): List<TorsoGrade> = GRADES.getValue(dim)

    /** 最高档序。 */
    fun orderMax(dim: TorsoDimension): Int = grades(dim).lastIndex

    /** 某维的档名序列（对外快照用，不手抄）。 */
    fun words(dim: TorsoDimension): List<String> = grades(dim).map { it.label }

    /** 一个形状在某维上的档序。 */
    fun level(shape: TorsoShape, dim: TorsoDimension): Int = shape.of(dim).level

    /** 一个形状在某维上的档名。 */
    fun wordOf(shape: TorsoShape, dim: TorsoDimension): String = shape.of(dim).label

    /** 两人形状不同的维，顺序＝维度声明序（结论稳定可回放）。 */
    fun diff(a: TorsoShape, b: TorsoShape): List<TorsoDimension> =
        TorsoDimension.entries.filter { level(a, it) != level(b, it) }

    /** 差异的逐维说明：维=甲档→乙档。 */
    fun describeDiff(a: TorsoShape, b: TorsoShape, dim: TorsoDimension): String =
        dim.label + "=" + wordOf(a, dim) + "→" + wordOf(b, dim)

    /** 全表档位数（表驱动，不手数）。 */
    val totalGrades: Int get() = GRADES.values.sumOf { it.size }
}
