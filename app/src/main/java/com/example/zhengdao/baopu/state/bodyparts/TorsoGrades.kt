// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十八批 · 躯干形状库：八维形状档（离散枚举，禁数值、禁评价）。
//
// 为什么要这一批：805 记的是**体积**（多厚、多显），一个「饱满」落到十个人身上还是同一个人。
// 体积只回答「多厚」，形状才回答「是谁」。所以这里补的是形状：块的排布、线的走向、外扩的方向。
//
// 四条边界（与 805 同口径，机器可判）：
// ① 不读真人影像——本包零 IO（门禁见 TorsoSourceAssertTest）；
// ② 不反推尺寸——没有任何长度／围度／体脂字段，也不接受百分比；只有档序与块数计数；
// ③ 不做诊断、不做评价——档名只描述形态，不写「异常」「完美」「运动型」这类词；
// ④ 不改 805 的既有档——体积维直接复用 805 的 PecVolume／AbsBlock／Girth，本包一个新造体积档都没有。
//
// 与 805 的分工（三条最容易混的）：
// · 805 `PecLowerEdge` 记「下缘清不清楚（锐利度）」，本包 `PecLowerTrend` 记「下缘往哪走（走向）」；
// · 805 `PecSeparation` 记「有没有沟、沟多深」，本包 `PecGapWidth` 记「沟有多宽」；
// · 805 `AbsBlock` 记「分块显出多少（多显）」，本包 `RectusBlocks` 记「几块、界清不清」。
package com.example.zhengdao.baopu.state.bodyparts

/** 本单唯一来源口径。对外每条结论都引用它。 */
const val TORSO_SOURCE: String =
    "派工单-2026-11-23-第八百四十八批（躯干形状库）＋本项目工程假设"

/** 口径成形日。 */
const val TORSO_AS_OF: String = "2026-11-23"

/**
 * 形状一律记在**放松态**下。理由：收紧态下块更显、下缘更抬，两人一紧一松就没法比；
 * 姿态与投影的变化归 830（未落地），本包不另立第二态。
 * 这里直接引用 805 的姿态枚举（不抄一个字符串），口径只有一个来源。
 */
val TORSO_SHAPE_POSTURE: Posture = Posture.RELAXED

/** 五个形状项（派工单 §①–§⑤）。项是「人话分组」，算差异按维算（见 TorsoItem.dimensions）。 */
enum class TorsoItem(val label: String, val dimensions: List<TorsoDimension>) {
    RECTUS("腹直肌", listOf(TorsoDimension.RECTUS_BLOCKS, TorsoDimension.LINEA_ALBA,
        TorsoDimension.BLOCK_ALIGNMENT)),
    PEC("胸肌", listOf(TorsoDimension.PEC_LOWER_TREND, TorsoDimension.PEC_GAP)),
    TRAP("斜方肌", listOf(TorsoDimension.TRAP_SLOPE)),
    LAT("背阔", listOf(TorsoDimension.LAT_FLARE)),
    SERRATUS("前锯肌", listOf(TorsoDimension.SERRATUS)),
    ;

    companion object {
        const val COUNT = 5
    }
}

/** 八个形状维。顺序＝派工单 §①–§⑤ 的书写顺序，也是结论里逐条列举的顺序。 */
enum class TorsoDimension(val label: String) {
    RECTUS_BLOCKS("腹直肌块数"),
    LINEA_ALBA("白线深浅"),
    BLOCK_ALIGNMENT("腹直肌左右列"),
    PEC_LOWER_TREND("胸肌下缘走向"),
    PEC_GAP("胸沟宽窄"),
    TRAP_SLOPE("斜方肌坡度"),
    LAT_FLARE("背阔外扩"),
    SERRATUS("前锯肌"),
    ;
}

/**
 * 档位共契约。
 * · [dim]：这一档属于哪一维（表驱动取档用，测试逐条核对）；
 * · [level]：档序，只表先后，**不是任何物理量**；
 * · [zero]：这一档是不是「没有该特征」的零档（不写「正常」，免得带进诊断语气）。
 */
interface TorsoGrade {
    val dim: TorsoDimension
    val level: Int
    val label: String
    val zero: Boolean get() = false
}

// ── ① 腹直肌（3 维）──────────────────────────────────────────────────

/**
 * 腹直肌块数。派工单给 2/4/6/8 四档，本包在最前面补一个零档「界不清」。
 * 为什么补：2 块是「看得见两块」，界不清是「一块也数不出」——这两件事相反，混一档会把
 * 老年松垮的人记成有腹肌的人。零档不猜块数（[blocks] = 0），要记块数就得真看见。
 * 块数是**计数**不是**量度**：数得出来几块就写几块，没有围度、没有大小。
 */
enum class RectusBlocks(
    override val level: Int,
    override val label: String,
    /** 块数；零档（界不清）记 0。 */
    val blocks: Int,
) : TorsoGrade {
    INDISTINCT(0, "界不清", 0),
    TWO(1, "两块", 2),
    FOUR(2, "四块", 4),
    SIX(3, "六块", 6),
    EIGHT(4, "八块", 8),
    ;

    override val dim: TorsoDimension get() = TorsoDimension.RECTUS_BLOCKS

    override val zero: Boolean get() = level == INDISTINCT.level

    companion object {
        const val LAST = 4

        fun of(level: Int): RectusBlocks = entries[level.coerceIn(0, LAST)]
    }
}

/**
 * 白线深浅。派工单给浅／中／深三档，本包在最前面补零档「被脂覆盖」。
 * 为什么补：大腹的白线不是「浅」，是整条被脂理掉、看不见——记成「浅」等于说还看得见一点。
 * 覆盖不是深浅的一档，是「看不到」这一种情形，所以占零档。
 */
enum class LineaAlba(override val level: Int, override val label: String) : TorsoGrade {
    COVERED(0, "被脂覆盖"),
    SHALLOW(1, "浅"),
    MEDIUM(2, "中"),
    DEEP(3, "深"),
    ;

    override val dim: TorsoDimension get() = TorsoDimension.LINEA_ALBA

    override val zero: Boolean get() = level == COVERED.level

    companion object {
        const val LAST = 3

        fun of(level: Int): LineaAlba = entries[level.coerceIn(0, LAST)]
    }
}

/**
 * 腹直肌左右两列是否等高（派工单 §① 末项）。
 * 与 805 `Symmetry`（左右两片胸肌整体对称）不是一回事：这里只说腹直肌左右两列的高低错位。
 */
enum class BlockAlignment(override val level: Int, override val label: String) : TorsoGrade {
    EVEN(0, "等高"),
    SLIGHT_OFFSET(1, "略错"),
    CLEAR_OFFSET(2, "明显错"),
    ;

    override val dim: TorsoDimension get() = TorsoDimension.BLOCK_ALIGNMENT

    override val zero: Boolean get() = level == EVEN.level

    companion object {
        const val LAST = 2

        fun of(level: Int): BlockAlignment = entries[level.coerceIn(0, LAST)]
    }
}

// ── ② 胸肌（2 维）────────────────────────────────────────────────────

/**
 * 胸肌下缘**走向**（派工单 §② 首项）：平／外高内低／外低内高／圆钝。
 * 与 805 `PecLowerEdge`（清晰度）正交：一条下缘可以又清楚又外高内低。
 * 四档按派工单原序，不自作聪明重排。
 */
enum class PecLowerTrend(override val level: Int, override val label: String) : TorsoGrade {
    FLAT(0, "平"),
    OUTER_HIGH(1, "外高内低"),
    OUTER_LOW(2, "外低内高"),
    ROUND_BLUNT(3, "圆钝"),
    ;

    override val dim: TorsoDimension get() = TorsoDimension.PEC_LOWER_TREND

    override val zero: Boolean get() = level == FLAT.level

    companion object {
        const val LAST = 3

        fun of(level: Int): PecLowerTrend = entries[level.coerceIn(0, LAST)]
    }
}

/** 胸沟**宽窄**（派工单 §② 次项）：窄／中／宽。与 805 `PecSeparation`（深浅）正交。 */
enum class PecGapWidth(override val level: Int, override val label: String) : TorsoGrade {
    NARROW(0, "窄"),
    MID(1, "中"),
    WIDE(2, "宽"),
    ;

    override val dim: TorsoDimension get() = TorsoDimension.PEC_GAP

    override val zero: Boolean get() = level == NARROW.level

    companion object {
        const val LAST = 2

        fun of(level: Int): PecGapWidth = entries[level.coerceIn(0, LAST)]
    }
}

// ── ③ 斜方肌（1 维）──────────────────────────────────────────────────

/** 斜方肌坡度（派工单 §③）：平缓／中／陡。陡＝颈肩折角小、颈显短。 */
enum class TrapSlope(override val level: Int, override val label: String) : TorsoGrade {
    GENTLE(0, "平缓"),
    MID(1, "中"),
    STEEP(2, "陡"),
    ;

    override val dim: TorsoDimension get() = TorsoDimension.TRAP_SLOPE

    override val zero: Boolean get() = level == GENTLE.level

    companion object {
        const val LAST = 2

        fun of(level: Int): TrapSlope = entries[level.coerceIn(0, LAST)]
    }
}

// ── ④ 背阔（1 维）────────────────────────────────────────────────────

/** 背阔外扩（派工单 §④）：倒三角／下缘外翻／直下。这是轮廓方向，不是宽度读数。 */
enum class LatFlare(override val level: Int, override val label: String) : TorsoGrade {
    V_TAPER(0, "倒三角"),
    LOWER_FLARE(1, "下缘外翻"),
    STRAIGHT_DOWN(2, "直下"),
    ;

    override val dim: TorsoDimension get() = TorsoDimension.LAT_FLARE

    override val zero: Boolean get() = level == STRAIGHT_DOWN.level

    companion object {
        const val LAST = 2

        fun of(level: Int): LatFlare = entries[level.coerceIn(0, LAST)]
    }
}

// ── ⑤ 前锯肌（1 维）──────────────────────────────────────────────────

/** 前锯肌（派工单 §⑤）：无／隐约／明显。体力劳动的指纹就在这一条上。 */
enum class Serratus(override val level: Int, override val label: String) : TorsoGrade {
    NONE(0, "无"),
    FAINT(1, "隐约"),
    OBVIOUS(2, "明显"),
    ;

    override val dim: TorsoDimension get() = TorsoDimension.SERRATUS

    override val zero: Boolean get() = level == NONE.level

    companion object {
        const val LAST = 2

        fun of(level: Int): Serratus = entries[level.coerceIn(0, LAST)]
    }
}

// ── 档案（判据④用）───────────────────────────────────────────────────

/**
 * 档案。这一层**不是** 848 的本行：档案由 815（体型分布，未落地）定，本包只拿它当
 * 「这个人的形状该往哪边偏」的校验依据，不定义档案、不给常见程度、不外传。
 *
 * [archive815] 是 815 派工单十三型档案里的原名，逐字对齐，等 815 落地后由装配层一一映射，
 * 本包不再造第二套名字。测试 `档案名逐字对齐815` 钉住这五个名字。
 */
enum class TorsoProfile(val label: String, val archive815: String) {
    ATHLETIC("健身型", "健身型"),
    LABOR("体力劳动", "体力劳动"),
    SEDENTARY("久坐", "脑力劳动久坐"),
    BELLY("大腹", "大腹便便"),
    ELDER("老年", "老年松垮"),
    ;

    companion object {
        const val COUNT = 5
    }
}
