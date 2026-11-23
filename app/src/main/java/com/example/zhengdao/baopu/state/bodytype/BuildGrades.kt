// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十五批 · 体型分布（母约束）：五个坐标的闭集档。
//
// 为什么要有这一批：805/807/848 都是「部位级」判据，单独看都能过，但它们不知道自己属于谁。
// 没有这层，每个部位都按「最理想值」写，拼起来就是同一个低脂高肌男。这一单只回答
// 「这个人是哪种体型」，所有部位档都得服从它。
//
// 四条边界（用户 2026-11-24 当面划定，机器可判）：
// ① 字段名即接口——skeletonTier/bodyFatTier/muscleTier/ageTier/bodyStateTier 逐字固定，不自造；
// ② 禁数值——五档闭集，level 只是档序不是任何物理量；不写体脂率／BMI／围度／长度；
// ③ 零装配零改 660——不产工具、不进闸、不写库、不读也不改 660 的数值文件；
// ④ 不做身材评价——好/差/理想这类词不写，只描述（BuildGuard 拦）。
//
// 与邻单分工：体脂档可与 805 的 FatBand 档对档映射；截面只通过只读的 [SectionSpec]
// 由装配层从 660 档化后传入，本包一个 660 数值都不读。
package com.example.zhengdao.baopu.state.bodytype

/** 本单唯一来源口径。对外每条结论都引用它。 */
const val BUILD_SOURCE: String =
    "派工单-2026-11-24-第八百一十五批（体型分布与默认值纠偏）＋本项目工程假设"

/** 口径成形日。 */
const val BUILD_AS_OF: String = "2026-11-24"

/** 骨架档：肩宽与骨盆宽的相对档（窄／中／宽）。不是绝对尺寸。 */
enum class SkeletonTier(val level: Int, val label: String) {
    NARROW(0, "窄"),
    MID(1, "中"),
    WIDE(2, "宽"),
    ;
    companion object {
        const val COUNT = 3
        fun of(level: Int): SkeletonTier = entries[level.coerceIn(0, COUNT - 1)]
    }
}

/**
 * 体脂档：低／中／高／很高四档闭集。低＝腹肌难显，很高＝明显堆积。
 * 只给档不给率；[mapToFatBand] 提供对 805 的档对档映射，供 866 等下游使用。
 */
enum class BodyFatTier(val level: Int, val label: String) {
    LOW(0, "低"),
    MID(1, "中"),
    HIGH(2, "高"),
    VERY_HIGH(3, "很高"),
    ;
    companion object {
        const val COUNT = 4

        /** 很高在 805 的四档里封顶到 HIGHER，不新造第五档。 */
        fun of(level: Int): BodyFatTier = entries[level.coerceIn(0, COUNT - 1)]
    }
}

/** 肌量档：少／中／多三档，只看现状，与「是否练过」无关。 */
enum class MuscleTier(val level: Int, val label: String) {
    SCANT(0, "少"),
    MID(1, "中"),
    AMPLE(2, "多"),
    ;
    companion object {
        const val COUNT = 3
        fun of(level: Int): MuscleTier = entries[level.coerceIn(0, COUNT - 1)]
    }
}

/** 年龄档：青年／中年／老年。只作倾向坐标，不直接改三轴取值。 */
enum class AgeTier(val level: Int, val label: String) {
    YOUNG(0, "青年"),
    MID(1, "中年"),
    ELDER(2, "老年"),
    ;
    companion object {
        const val COUNT = 3
        fun of(level: Int): AgeTier = entries[level.coerceIn(0, COUNT - 1)]
    }
}

/** 状态档：训练期／久坐期／病后恢复。健康状态只作状态，不打分、不下诊断。 */
enum class BodyStateTier(val level: Int, val label: String) {
    TRAINING(0, "训练期"),
    SEDENTARY(1, "久坐期"),
    RECOVERING(2, "病后恢复"),
    ;
    companion object {
        const val COUNT = 3
        fun of(level: Int): BodyStateTier = entries[level.coerceIn(0, COUNT - 1)]
    }
}

/** 常见程度：分布描述，不是身材评价。三档且不许全同。 */
enum class Commonness(val label: String, val rank: Int) {
    COMMON("常见", 0),
    USUAL("一般", 1),
    RARE("少见", 2),
    ;
    companion object {
        const val COUNT = 3
    }
}

/** 依据闭集：低脂高肌这类少见组合必须给出其中一类，NONE 不算。 */
enum class Basis(val label: String) {
    OCCUPATION("职业"),
    TRAINING_HISTORY("训练史"),
    DESCRIPTION("照片级描述"),
    NONE("无依据"),
    ;
    companion object {
        const val COUNT = 4

        /** 真实依据：除 NONE 外都算。 */
        fun isReal(basis: Basis): Boolean = basis != NONE
    }
}

/**
 * 依据如何影响取值。职业只能是 [TENDENCY]（倾向），写成 [DECISIVE]（决定）即红——
 * 「工人一定低脂」正是把职业当决定因素，判据⑥ 的机器落点。
 */
enum class InfluenceEffect(val label: String) {
    TENDENCY("倾向"),
    DECISIVE("决定"),
    ;
    companion object {
        const val COUNT = 2
    }
}

/**
 * 660 截面的离散档（只读输入）。装配层把 660 的躯干截面档化后传入，
 * 本包只档对档比对，不读 660 的数值文件、不改 660 一个字。
 * 三档（窄/中/宽）分别用于 width（骨架）、thickness（体脂）、girth（肌量）。
 */
enum class SectionGrade(val level: Int, val label: String) {
    NARROW(0, "窄"),
    MID(1, "中"),
    WIDE(2, "宽"),
    ;
    companion object {
        const val COUNT = 3
        fun of(level: Int): SectionGrade = entries[level.coerceIn(0, COUNT - 1)]
    }
}

/** 截面规格：三个离散档，零数值字段（门禁钉）。 */
data class SectionSpec(
    val width: SectionGrade,
    val thickness: SectionGrade,
    val girth: SectionGrade,
)
