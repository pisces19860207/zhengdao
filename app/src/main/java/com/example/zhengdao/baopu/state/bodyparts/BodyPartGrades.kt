// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零五批 · 体型部位外观档：八部位 × 各自维度的档位。
//
// 三条边界（用户 2026-11-22 当面划定，本包全部落成机器可判，不靠自觉）：
// ① 不读真人影像——本包零 IO：不读文件、不开相机、不解码图片（源码门禁见 BodyPartSourceAssertTest）；
// ② 不反推尺寸——本包**没有任何长度字段、也没有长度常数**，只给档序与相对措辞。
//    乳晕一律记「乳头直径的倍数档」，绝不以绝对大小记：换算出来的数一旦落库就过期，且不可复核。
// ③ 不做诊断——档名只描述形态，不含「异常／需就医／病理性」这类判定词（BodyPartGuard 拦）。
//
// 每条结论都带来源与日期：见 [BODYPART_SOURCE]／[BODYPART_AS_OF]；给不出来源一律写 [NO_SOURCE]。
//
// 与邻单分工：乳头敏感归 781；穿衣效果与体表可见度归 687／666；绝对围度与体脂率读数归 660。
// 本包只做「形态与可见度的档」，不重复落账。
package com.example.zhengdao.baopu.state.bodyparts

/** 本单唯一来源口径。对外每条结论都引用它，不许出处不明。 */
const val BODYPART_SOURCE: String =
    "派工单-2026-11-22-第八百零五批（体型部位外观档）＋本项目工程假设"

/** 口径成形日：事实层读数与档位定义都以这一天为准。 */
const val BODYPART_AS_OF: String = "2026-11-22"

/** 给不出来源时的唯一写法：留空、留「—」都不算，必须显式写这一条。 */
const val NO_SOURCE: String = "无来源"

/** 侧别。分左右两侧的部位必须两侧各记一笔；中轴部位（喉结、对称性档）只记 [MIDLINE]。 */
enum class Side(val label: String) {
    LEFT("左"),
    RIGHT("右"),
    MIDLINE("中"),
}

/** 姿态。硬规矩①：每条读数都必须落在某个姿态上，不许留空。 */
enum class Posture(val label: String) {
    RELAXED("放松"),
    TIGHTENED("收紧"),
    FLEXED("屈曲用力"),
}

/**
 * 体脂档（0–3）。**不是本包产出的读数**：它由装配层从 660 的体重/体脂率档化后传入，
 * 本包不读 660、不自己换算。脂低才显线条这一步判定（判据②）要它当共同约束。
 */
enum class FatBand(val level: Int, val label: String) {
    VERY_LEAN(0, "很瘦"),
    LEAN(1, "偏瘦"),
    MODERATE(2, "中等"),
    HIGHER(3, "偏高"),
    ;

    /** 脂低＝0–1 档；脂高＝3 档。 */
    val lean: Boolean get() = level <= LEAN.level

    val high: Boolean get() = level >= HIGHER.level

    companion object {
        const val COUNT = 4

        fun of(level: Int): FatBand = entries[level.coerceIn(0, COUNT - 1)]
    }
}

/**
 * 颜色一律**相对肤色**记（判据④）。档序只表深浅先后，不承载任何色值。
 * 给绝对色号（形如井号加六位十六进制）即红：色号是可变的显示参数，不是形态事实。
 */
enum class ColorRef(val level: Int, val label: String) {
    MUCH_LIGHTER(0, "明显浅于肤色"),
    LIGHTER(1, "略浅于肤色"),
    SAME(2, "与肤色相同"),
    DARKER(3, "略深于肤色"),
    MUCH_DARKER(4, "明显深于肤色"),
    ;

    companion object {
        const val COUNT = 5

        fun of(level: Int): ColorRef = entries[level.coerceIn(0, COUNT - 1)]
    }
}

/**
 * 档位共契约。
 * · [level]：档序，只表先后与强弱，**不是任何物理量**；
 * · [zero]：这一档是不是「没有该特征」的零档（不是「正常」，避免把诊断语气带进来）。
 */
interface BodyGrade {
    val level: Int
    val label: String
    val zero: Boolean get() = false
}

// ── ① 胸肌（4 维）────────────────────────────────────────────────────

/** 胸肌体积 0–3。 */
enum class PecVolume(override val level: Int, override val label: String) : BodyGrade {
    FLAT(0, "平伏"),
    SLIGHT(1, "微隆"),
    FULL(2, "饱满"),
    THICK(3, "厚实"),
    ;

    override val zero: Boolean get() = level == FLAT.level

    companion object {
        const val LAST = 3

        fun of(level: Int): PecVolume = entries[level.coerceIn(0, LAST)]
    }
}

/** 胸肌分离度（左右两片之间的分界）0–3。 */
enum class PecSeparation(override val level: Int, override val label: String) : BodyGrade {
    NONE(0, "无分界"),
    FAINT(1, "隐约"),
    VISIBLE(2, "见沟"),
    DEEP(3, "深沟"),
    ;

    override val zero: Boolean get() = level == NONE.level

    companion object {
        const val LAST = 3

        fun of(level: Int): PecSeparation = entries[level.coerceIn(0, LAST)]
    }
}

/** 胸肌下缘清晰度 0–3。 */
enum class PecLowerEdge(override val level: Int, override val label: String) : BodyGrade {
    NONE(0, "无边界"),
    BLURRED(1, "模糊"),
    CLEAR(2, "清楚"),
    SHARP(3, "锐利"),
    ;

    override val zero: Boolean get() = level == NONE.level

    companion object {
        const val LAST = 3

        fun of(level: Int): PecLowerEdge = entries[level.coerceIn(0, LAST)]
    }
}

/** 左右对称 0–3。这是部位级属性，不按左右各记一笔（side 固定 MIDLINE）。 */
enum class Symmetry(override val level: Int, override val label: String) : BodyGrade {
    CLEARLY_ASYMMETRIC(0, "明显不对称"),
    SLIGHTLY_ASYMMETRIC(1, "轻微不对称"),
    MOSTLY(2, "基本对称"),
    FULL(3, "完全对称"),
    ;

    companion object {
        const val LAST = 3

        fun of(level: Int): Symmetry = entries[level.coerceIn(0, LAST)]
    }
}

// ── ② 乳头（3 维）────────────────────────────────────────────────────

/**
 * 乳头直径 0–3（小／中／大／很大）。
 * 事实层均值约 7 毫米（来源：派工单 §②）——**只作文档口径记录，本包不存该数、不参与任何运算**。
 */
enum class NippleDiameter(override val level: Int, override val label: String) : BodyGrade {
    SMALL(0, "小"),
    MEDIUM(1, "中"),
    LARGE(2, "大"),
    VERY_LARGE(3, "很大"),
    ;

    companion object {
        const val LAST = 3

        fun of(level: Int): NippleDiameter = entries[level.coerceIn(0, LAST)]
    }
}

/**
 * 乳头高度 0–3（低平／微凸／明显／高凸）。
 * 高凸（3 档）须给依据：这一档少见，写之前要能说出凭什么（判据⑧，由目录表 basisFrom 强制）。
 */
enum class NippleHeight(override val level: Int, override val label: String) : BodyGrade {
    FLAT(0, "低平"),
    SLIGHT(1, "微凸"),
    OBVIOUS(2, "明显"),
    HIGH(3, "高凸"),
    ;

    override val zero: Boolean get() = level == FLAT.level

    companion object {
        const val LAST = 3

        fun of(level: Int): NippleHeight = entries[level.coerceIn(0, LAST)]
    }
}

// ── ③ 乳晕（4 维）────────────────────────────────────────────────────

/**
 * 乳晕直径，**以乳头直径的倍数**记（判据⑤）。
 * 最小档就是两倍起：乳头不会超过乳晕的一半，这条判据在本口径下结构性成立，
 * 不需要算长度也不需要查间距。想记绝对大小的人只能改口径——Guard 会拦。
 */
enum class AreolaRatio(override val level: Int, override val label: String) : BodyGrade {
    TWO_TO_THREE(0, "乳头两到三倍"),
    THREE_TO_FOUR(1, "乳头三到四倍"),
    FOUR_TO_FIVE(2, "乳头四到五倍"),
    OVER_FIVE(3, "乳头五倍以上"),
    ;

    companion object {
        const val LAST = 3

        /** 全包唯一的下限：倍数档不得低于两倍（判据⑤的机器落点）。 */
        const val MIN_FACTOR = 2

        fun of(level: Int): AreolaRatio = entries[level.coerceIn(0, LAST)]
    }
}

/** 乳晕边缘清晰度 0–3。 */
enum class AreolaEdge(override val level: Int, override val label: String) : BodyGrade {
    BLENDED(0, "完全融合"),
    BLURRED(1, "模糊"),
    CLEAR(2, "清楚"),
    SHARP(3, "锐利"),
    ;

    override val zero: Boolean get() = level == BLENDED.level

    companion object {
        const val LAST = 3

        fun of(level: Int): AreolaEdge = entries[level.coerceIn(0, LAST)]
    }
}

/** 乳晕表面 0–1。 */
enum class AreolaSurface(override val level: Int, override val label: String) : BodyGrade {
    SMOOTH(0, "平滑"),
    GLANDULAR(1, "腺体突显"),
    ;

    companion object {
        const val LAST = 1

        fun of(level: Int): AreolaSurface = entries[level.coerceIn(0, LAST)]
    }
}

/**
 * 乳晕覆盖范围 0–2（判据⑥后半：乳晕不得铺到胸肌下缘）。
 * 这不是尺寸，是「与胸肌下缘的相对位置」，只需要看边界有没有越过去。
 */
enum class AreolaReach(override val level: Int, override val label: String) : BodyGrade {
    WITHIN_PEC(0, "限于胸肌区内"),
    NEAR_EDGE(1, "接近下缘"),
    CROSS_EDGE(2, "越过下缘"),
    ;

    override val zero: Boolean get() = level == WITHIN_PEC.level

    companion object {
        const val LAST = 2

        fun of(level: Int): AreolaReach = entries[level.coerceIn(0, LAST)]
    }
}

// ── ④ 喉结（1 维，0–4）──────────────────────────────────────────────

/**
 * 喉结 0–4。**0 档（几乎看不见）是常见档**，不是缺陷档：颈脂、体脂、角度都会把它藏住。
 * 3 档起须给驱动、4 档（高凸）须给两条依据——由目录表的 basisFrom 与判据⑨强制。
 */
enum class LarynxGrade(override val level: Int, override val label: String) : BodyGrade {
    HIDDEN(0, "几乎看不见"),
    FAINT(1, "隐约"),
    VISIBLE(2, "可见"),
    OBVIOUS(3, "明显"),
    PROMINENT(4, "高凸"),
    ;

    override val zero: Boolean get() = level == HIDDEN.level

    companion object {
        const val LAST = 4

        /** 须给驱动的起点（3 档）。 */
        const val DRIVER_FROM = 3

        /** 须给两条依据的档（4 档）。 */
        const val STRICT_BASIS = 4

        fun of(level: Int): LarynxGrade = entries[level.coerceIn(0, LAST)]
    }
}

// ── ⑤ 腹肌（2 维 + 两态）─────────────────────────────────────────────

/** 腹肌分块可见度 0–3。必须放松态与收紧态**分开记**（判据⑦）。 */
enum class AbsBlock(override val level: Int, override val label: String) : BodyGrade {
    NONE(0, "不见分块"),
    UPPER(1, "上腹可见"),
    UPPER_MID(2, "上中腹可见"),
    DISTINCT(3, "分块分明"),
    ;

    override val zero: Boolean get() = level == NONE.level

    companion object {
        const val LAST = 3

        fun of(level: Int): AbsBlock = entries[level.coerceIn(0, LAST)]
    }
}

/** 腰腹比 0–3（定性档，不给比值）。 */
enum class WaistRatio(override val level: Int, override val label: String) : BodyGrade {
    NARROWER(0, "腰窄于胯"),
    EVEN(1, "腰约等胯"),
    WIDER(2, "腰略宽于胯"),
    MUCH_WIDER(3, "腰明显宽于胯"),
    ;

    companion object {
        const val LAST = 3

        fun of(level: Int): WaistRatio = entries[level.coerceIn(0, LAST)]
    }
}

// ── ⑥ 肱二头（3 维）──────────────────────────────────────────────────

/** 围度 0–3（肱二头／大腿／小腿共用同一套相对档）。绝对围度归 660，本包不收。 */
enum class Girth(override val level: Int, override val label: String) : BodyGrade {
    THIN(0, "细"),
    SLIM(1, "偏细"),
    MEDIUM(2, "中等"),
    THICK(3, "粗"),
    ;

    companion object {
        const val LAST = 3

        fun of(level: Int): Girth = entries[level.coerceIn(0, LAST)]
    }
}

/** 峰度 0–3。放松态与屈肘（[Posture.FLEXED]）两态必须分记。 */
enum class Peak(override val level: Int, override val label: String) : BodyGrade {
    NONE(0, "无隆峰"),
    SLIGHT(1, "略隆"),
    OBVIOUS(2, "明显"),
    PEAKED(3, "峰起"),
    ;

    override val zero: Boolean get() = level == NONE.level

    companion object {
        const val LAST = 3

        fun of(level: Int): Peak = entries[level.coerceIn(0, LAST)]
    }
}

/** 血管可见度 0–3。只记「看得见多少」，血管走行与粗细归 687，本包不描述。 */
enum class Vessel(override val level: Int, override val label: String) : BodyGrade {
    NONE(0, "不见"),
    FAINT(1, "隐约"),
    VISIBLE(2, "可见"),
    PROMINENT(3, "毕现"),
    ;

    override val zero: Boolean get() = level == NONE.level

    companion object {
        const val LAST = 3

        fun of(level: Int): Vessel = entries[level.coerceIn(0, LAST)]
    }
}

// ── ⑦ 大腿（3 维）────────────────────────────────────────────────────

/** 大腿内外侧分离度 0–3。 */
enum class ThighSeparation(override val level: Int, override val label: String) : BodyGrade {
    NONE(0, "无分界"),
    FAINT(1, "隐约"),
    LINE(2, "见线"),
    DEEP(3, "深线"),
    ;

    override val zero: Boolean get() = level == NONE.level

    companion object {
        const val LAST = 3

        fun of(level: Int): ThighSeparation = entries[level.coerceIn(0, LAST)]
    }
}

/** 大腿根部与臀的衔接 0–3（只描述接缝形态，臀本身归 660 的部件参数）。 */
enum class HipJunction(override val level: Int, override val label: String) : BodyGrade {
    SMOOTH(0, "连贯无褶"),
    SHALLOW(1, "有浅褶"),
    CLEAR(2, "明显褶"),
    DEEP(3, "折痕深"),
    ;

    override val zero: Boolean get() = level == SMOOTH.level

    companion object {
        const val LAST = 3

        fun of(level: Int): HipJunction = entries[level.coerceIn(0, LAST)]
    }
}

// ── ⑧ 小腿（3 维）────────────────────────────────────────────────────

/** 腓肠肌轮廓 0–3。 */
enum class CalfContour(override val level: Int, override val label: String) : BodyGrade {
    NONE(0, "不见轮廓"),
    FAINT(1, "隐约"),
    VISIBLE(2, "可见"),
    DISTINCT(3, "分明"),
    ;

    override val zero: Boolean get() = level == NONE.level

    companion object {
        const val LAST = 3

        fun of(level: Int): CalfContour = entries[level.coerceIn(0, LAST)]
    }
}

/** 踝部细分 0–3（内踝、外踝、跟腱两侧的可见程度）。 */
enum class AnkleDetail(override val level: Int, override val label: String) : BodyGrade {
    NONE(0, "不见踝骨"),
    FAINT(1, "隐约"),
    VISIBLE(2, "可见"),
    DISTINCT(3, "分明"),
    ;

    override val zero: Boolean get() = level == NONE.level

    companion object {
        const val LAST = 3

        fun of(level: Int): AnkleDetail = entries[level.coerceIn(0, LAST)]
    }
}
