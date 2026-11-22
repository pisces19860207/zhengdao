// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零五批 · 部位目录表：八部位、二十三维、必记的侧别与姿态。
//
// 这份表是**唯一真相**：档数从档位枚举取（[BodyPartCatalog.words]），不在这里另抄一份档名，
// 免得枚举改了表没改。表只管四件事——这个维度属于哪个部位、要记哪几种姿态、要不要分左右、
// 从哪一档起必须给依据。
//
// 判据⑥的「乳晕 ≤ 两乳头间距的三分之一」落成本文件的 [AreolaMatrix]：**档间矩阵，不存长度**。
// 理由见该对象的注释——只给档是本单的边界②，任何把档换成毫米再比较的写法都会把边界破掉。
package com.example.zhengdao.baopu.state.bodyparts

/** 八个部位。派工单 §①–§⑧ 的顺序在这里就是声明序。 */
enum class BodyPart(val label: String) {
    PEC("胸肌"),
    NIPPLE("乳头"),
    AREOLA("乳晕"),
    LARYNX("喉结"),
    ABS("腹肌"),
    BICEPS("肱二头"),
    THIGH("大腿"),
    CALF("小腿"),
}

/** 维度。标签只描述「看什么」，不含任何数量词。 */
enum class Dim(val label: String) {
    PEC_VOLUME("体积"),
    PEC_SEPARATION("分离度"),
    PEC_LOWER_EDGE("下缘清晰度"),
    PEC_SYMMETRY("左右对称"),

    NIPPLE_DIAMETER("直径"),
    NIPPLE_HEIGHT("高度"),
    NIPPLE_COLOR("颜色（相对肤色）"),

    AREOLA_RATIO("直径（乳头倍数）"),
    AREOLA_EDGE("边缘清晰度"),
    AREOLA_SURFACE("表面"),
    AREOLA_REACH("覆盖范围"),

    LARYNX("喉结档"),

    ABS_BLOCK("分块可见度"),
    ABS_WAIST("腰腹比"),

    BICEPS_GIRTH("围度"),
    BICEPS_PEAK("峰度"),
    BICEPS_VESSEL("血管可见度"),

    THIGH_GIRTH("围度"),
    THIGH_SEPARATION("内外侧分离度"),
    THIGH_HIP_JUNCTION("根部与臀衔接"),

    CALF_GIRTH("围度"),
    CALF_CONTOUR("腓肠肌轮廓"),
    CALF_ANKLE("踝部细分"),
}

/** 三态判定。全包只有这三个词，不另立同义词。 */
enum class Verdict(val label: String) {
    PASS("过"),
    NEED_CHECK("须核"),
    DENY("红"),
}

/** 空档：这一维不需要依据。 */
const val NO_BASIS: Int = Int.MAX_VALUE

/**
 * 一维的记法约束。
 * · [postures]：必记的姿态集合。腹肌、肱二头等必须两态分记的部位在这里列两个。
 * · [bilateral]：true＝左右各记一笔（side 只能是左/右）；false＝中轴，side 只能是 [Side.MIDLINE]。
 * · [basisFrom]：从这一档起必须写依据（null 语义用 [NO_BASIS] 表达）。
 */
data class DimSpec(
    val dim: Dim,
    val part: BodyPart,
    val postures: List<Posture>,
    val bilateral: Boolean,
    val basisFrom: Int = NO_BASIS,
) {
    init {
        require(postures.isNotEmpty()) { "${dim.label}：必记姿态不能为空" }
        require(postures.toSet().size == postures.size) { "${dim.label}：姿态重复" }
        require(basisFrom == NO_BASIS || basisFrom >= 0) { "${dim.label}：依据起点不得为负" }
    }

    /** 档名表（唯一真相在档位枚举里）。 */
    val words: List<String> get() = BodyPartCatalog.words(dim)

    /** 最高档序。 */
    val orderMax: Int get() = words.lastIndex

    /** 这一维一共要几条读数＝（左/右 或 中）× 姿态数。 */
    val readingCount: Int get() = BodyPartCatalog.sidesOf(this).size * postures.size

    /** 该档是否需要依据。 */
    fun needsBasis(level: Int): Boolean = level >= basisFrom
}

/**
 * 目录表。构造时把「漏一维、重复一维、漏一个部位」全部拦下——表坏了不该等到判定才发现。
 */
object BodyPartCatalog {

    /** 维度 → 档名。所有条目都从档位枚举生成，不手抄。 */
    private val WORDS: Map<Dim, List<String>> = mapOf(
        Dim.PEC_VOLUME to PecVolume.entries.map { it.label },
        Dim.PEC_SEPARATION to PecSeparation.entries.map { it.label },
        Dim.PEC_LOWER_EDGE to PecLowerEdge.entries.map { it.label },
        Dim.PEC_SYMMETRY to Symmetry.entries.map { it.label },
        Dim.NIPPLE_DIAMETER to NippleDiameter.entries.map { it.label },
        Dim.NIPPLE_HEIGHT to NippleHeight.entries.map { it.label },
        Dim.NIPPLE_COLOR to ColorRef.entries.map { it.label },
        Dim.AREOLA_RATIO to AreolaRatio.entries.map { it.label },
        Dim.AREOLA_EDGE to AreolaEdge.entries.map { it.label },
        Dim.AREOLA_SURFACE to AreolaSurface.entries.map { it.label },
        Dim.AREOLA_REACH to AreolaReach.entries.map { it.label },
        Dim.LARYNX to LarynxGrade.entries.map { it.label },
        Dim.ABS_BLOCK to AbsBlock.entries.map { it.label },
        Dim.ABS_WAIST to WaistRatio.entries.map { it.label },
        Dim.BICEPS_GIRTH to Girth.entries.map { it.label },
        Dim.BICEPS_PEAK to Peak.entries.map { it.label },
        Dim.BICEPS_VESSEL to Vessel.entries.map { it.label },
        Dim.THIGH_GIRTH to Girth.entries.map { it.label },
        Dim.THIGH_SEPARATION to ThighSeparation.entries.map { it.label },
        Dim.THIGH_HIP_JUNCTION to HipJunction.entries.map { it.label },
        Dim.CALF_GIRTH to Girth.entries.map { it.label },
        Dim.CALF_CONTOUR to CalfContour.entries.map { it.label },
        Dim.CALF_ANKLE to AnkleDetail.entries.map { it.label },
    )

    private val ONE_POSTURE: List<Posture> = listOf(Posture.RELAXED)
    private val TWO_POSTURES: List<Posture> = listOf(Posture.RELAXED, Posture.FLEXED)
    private val ABS_POSTURES: List<Posture> = listOf(Posture.RELAXED, Posture.TIGHTENED)

    /**
     * 八部位 × 各自维度。姿态：胸肌两态（放松/屈曲用力）；腹肌两态（放松/收紧）；
     * 肱二头、大腿、小腿两态（放松/屈曲用力）；乳头、乳晕、喉结只记放松态（形态不随姿态改）。
     */
    val specs: List<DimSpec> = listOf(
        DimSpec(Dim.PEC_VOLUME, BodyPart.PEC, TWO_POSTURES, bilateral = true),
        DimSpec(Dim.PEC_SEPARATION, BodyPart.PEC, TWO_POSTURES, bilateral = true),
        DimSpec(Dim.PEC_LOWER_EDGE, BodyPart.PEC, TWO_POSTURES, bilateral = true),
        DimSpec(Dim.PEC_SYMMETRY, BodyPart.PEC, ONE_POSTURE, bilateral = false),

        DimSpec(Dim.NIPPLE_DIAMETER, BodyPart.NIPPLE, ONE_POSTURE, bilateral = true),
        DimSpec(Dim.NIPPLE_HEIGHT, BodyPart.NIPPLE, ONE_POSTURE, bilateral = true,
            basisFrom = NippleHeight.LAST),
        DimSpec(Dim.NIPPLE_COLOR, BodyPart.NIPPLE, ONE_POSTURE, bilateral = true),

        DimSpec(Dim.AREOLA_RATIO, BodyPart.AREOLA, ONE_POSTURE, bilateral = true),
        DimSpec(Dim.AREOLA_EDGE, BodyPart.AREOLA, ONE_POSTURE, bilateral = true),
        DimSpec(Dim.AREOLA_SURFACE, BodyPart.AREOLA, ONE_POSTURE, bilateral = true),
        DimSpec(Dim.AREOLA_REACH, BodyPart.AREOLA, ONE_POSTURE, bilateral = true),

        DimSpec(Dim.LARYNX, BodyPart.LARYNX, ONE_POSTURE, bilateral = false,
            basisFrom = LarynxGrade.DRIVER_FROM),

        DimSpec(Dim.ABS_BLOCK, BodyPart.ABS, ABS_POSTURES, bilateral = true),
        DimSpec(Dim.ABS_WAIST, BodyPart.ABS, ABS_POSTURES, bilateral = true),

        DimSpec(Dim.BICEPS_GIRTH, BodyPart.BICEPS, TWO_POSTURES, bilateral = true),
        DimSpec(Dim.BICEPS_PEAK, BodyPart.BICEPS, TWO_POSTURES, bilateral = true),
        DimSpec(Dim.BICEPS_VESSEL, BodyPart.BICEPS, TWO_POSTURES, bilateral = true),

        DimSpec(Dim.THIGH_GIRTH, BodyPart.THIGH, TWO_POSTURES, bilateral = true),
        DimSpec(Dim.THIGH_SEPARATION, BodyPart.THIGH, TWO_POSTURES, bilateral = true),
        DimSpec(Dim.THIGH_HIP_JUNCTION, BodyPart.THIGH, TWO_POSTURES, bilateral = true),

        DimSpec(Dim.CALF_GIRTH, BodyPart.CALF, TWO_POSTURES, bilateral = true),
        DimSpec(Dim.CALF_CONTOUR, BodyPart.CALF, TWO_POSTURES, bilateral = true),
        DimSpec(Dim.CALF_ANKLE, BodyPart.CALF, TWO_POSTURES, bilateral = true),
    )

    private val BY_DIM: Map<Dim, DimSpec> = specs.associateBy { it.dim }

    init {
        require(BY_DIM.size == specs.size) { "目录表同一维度登记了两次" }
        require(BY_DIM.size == Dim.entries.size) {
            val missing = Dim.entries.filterNot { BY_DIM.containsKey(it) }
            "目录表漏了维度：" + missing.joinToString("、") { it.label }
        }
        require(WORDS.keys.containsAll(BY_DIM.keys)) { "档名表漏了已登记的维度" }
        require(specs.map { it.part }.toSet().size == BodyPart.entries.size) {
            val missing = BodyPart.entries.filterNot { p -> specs.any { it.part == p } }
            "目录表漏了部位：" + missing.joinToString("、") { it.label }
        }
        specs.forEach { spec ->
            require(spec.basisFrom == NO_BASIS || spec.basisFrom <= spec.orderMax) {
                "${spec.dim.label}：依据起点超出档域"
            }
        }
    }

    fun spec(dim: Dim): DimSpec = BY_DIM.getValue(dim)

    fun words(dim: Dim): List<String> = WORDS.getValue(dim)

    fun orderMax(dim: Dim): Int = words(dim).lastIndex

    fun dimsOf(part: BodyPart): List<DimSpec> = specs.filter { it.part == part }

    /** 该维要记哪几个 side。 */
    fun sidesOf(spec: DimSpec): List<Side> =
        if (spec.bilateral) listOf(Side.LEFT, Side.RIGHT) else listOf(Side.MIDLINE)

    /** 全表读数条数（表驱动，不手数）。 */
    val totalReadings: Int get() = specs.sumOf { it.readingCount }
}

/** 两乳头间距，只记相对宽窄 0–3（判据⑥要用它，但用的仍是档，不是长度）。 */
enum class SpacingBand(val level: Int, val label: String) {
    NARROW(0, "窄"),
    MID(1, "中"),
    WIDE(2, "宽"),
    VERY_WIDE(3, "很宽"),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): SpacingBand = entries[level.coerceIn(0, COUNT - 1)]
    }
}

/**
 * 判据⑥「乳晕直径 ≤ 两乳头间距的三分之一」的机器落点。
 *
 * 为什么是矩阵而不是除法：本包边界②不许把档换成毫米。四档间距 × 四档乳晕倍数＝16 格，
 * 判法只用**档差**（乳晕档 − 间距档）：
 * · 差 ≥ 3 ⇒ 红：最窄的胸配最大的晕，按三分之一规则必超；
 * · 差 ＝ 2 ⇒ 须核：正好压在档界上，两档都还在区间里，判不了死；
 * · 其余 ⇒ 过。
 * 档差为什么要按这个方向：间距档序是窄→很宽，乳晕档序是小→很大，两者同向；乳晕比间距跑得快，
 * 就是「胸窄而晕大」这一个矛盾在档上的样子。
 */
object AreolaMatrix {

    const val STEP_DENY = 3
    const val STEP_CHECK = 2

    fun verdict(spacing: SpacingBand, ratio: AreolaRatio): Verdict {
        val step = ratio.level - spacing.level
        return when {
            step >= STEP_DENY -> Verdict.DENY
            step == STEP_CHECK -> Verdict.NEED_CHECK
            else -> Verdict.PASS
        }
    }

    /** 一句话依据，直接进结论行；不含长度、不含诊断词。 */
    fun reason(spacing: SpacingBand, ratio: AreolaRatio): String {
        val step = ratio.level - spacing.level
        return "间距" + spacing.label + "＋乳晕" + ratio.label + "（档差 " + step + "）"
    }
}

/** 喉结的六类驱动（派工单 §④ 括号里那六项，不多不少）。 */
enum class LarynxDriver(val label: String) {
    NECK_FAT("颈／颏下脂肪"),
    BODY_FAT("体脂"),
    NECK_LENGTH("颈长"),
    ANGLE("角度"),
    AGE("年龄"),
    VOICE_USE("用声"),
    ;

    companion object {
        const val COUNT = 6
    }
}
