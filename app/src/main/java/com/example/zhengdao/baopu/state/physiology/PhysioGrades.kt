// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-21-第六百五十八批（轻生理状态轨迹：4 状态 ＋ 三液分道）。
//
// 本包只产出「整体生理态」的档位读数：四状态 + 三液分道，全部是档位、不给点值、
// 不判定、不写事实、不进任何闸。状态行 722 留位由后续装配单来接，本单不碰。
// 与邻单分工：735 前列腺排出/感觉、732 肛管湿润、657 环境出汗、747 锚定体温/出汗槽
// 各有自己的落账口；本单只给「整体层」的四档与三液分道，重复落账一律判红。
package com.example.zhengdao.baopu.state.physiology

/** 统一依据口径：本单派工单＋工程假设，不引用任何诊疗资料原文。 */
internal const val PHYSIO_SOURCE =
    "派工单-2026-11-21-第六百五十八批（轻生理状态轨迹）＋本项目工程假设"
internal const val PHYSIO_AS_OF = "2026-11-21"

/** 本单只产状态、不下结论的统一附言；渲染唯一出口是 [PhysioRenderer]。 */
internal const val PHYSIO_DISCLAIMER = "非医疗判断·仅档位"

/**
 * 档位共契约：档序只表先后，不是任何物理量测值；[zero] 标记「无偏移/静息/无/清醒」这类零档。
 * 全包只有档位，没有摄氏度、每分心跳数、体积数这类点值字段。
 */
internal interface PhysioGrade {
    val level: Int
    val label: String
    val zero: Boolean
}

/** 五类驱动，全部档位化；每条非零档至少挂一类（判据③）。 */
internal enum class PhysioDriver(val label: String) {
    ENV_TEMP_HUMIDITY("环境温湿"),
    ACTIVITY_INTENSITY("活动强度"),
    DURATION("时长"),
    EMOTION("情绪"),
    INDIVIDUAL_DIFFERENCE("个体差异"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/** ①-1 体温偏移（dT）四档：偏低/正常/偏高/发热档。无温度测值。 */
internal enum class BodyTempShift(
    override val level: Int,
    override val label: String,
    override val zero: Boolean,
) : PhysioGrade {
    LOW(0, "偏低", false),
    NORMAL(1, "正常", true),
    HIGH(2, "偏高", false),
    FEVER(3, "发热档", false),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): BodyTempShift =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** ①-2 心率档四档：静息/轻动/中动/剧烈。无每分次数。 */
internal enum class HeartRateBand(
    override val level: Int,
    override val label: String,
    override val zero: Boolean,
) : PhysioGrade {
    REST(0, "静息", true),
    LIGHT(1, "轻动", false),
    MODERATE(2, "中动", false),
    INTENSE(3, "剧烈", false),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): HeartRateBand =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** ①-3 出汗档四档：无/微/明显/湿透。这是「整体出汗表现档」，不是三液里的汗液分道。 */
internal enum class SweatLevel(
    override val level: Int,
    override val label: String,
    override val zero: Boolean,
) : PhysioGrade {
    NONE(0, "无", true),
    SLIGHT(1, "微", false),
    OBVIOUS(2, "明显", false),
    SOAKED(3, "湿透", false),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): SweatLevel =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** ①-4 疲劳档四档：清醒/轻疲/中疲/力竭。 */
internal enum class Fatigue(
    override val level: Int,
    override val label: String,
    override val zero: Boolean,
) : PhysioGrade {
    AWAKE(0, "清醒", true),
    LIGHT(1, "轻疲", false),
    MID(2, "中疲", false),
    EXHAUSTED(3, "力竭", false),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): Fatigue =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 三液分道共用的量档：无/微/中/多。只表量级，无体积数值。 */
internal enum class FluidAmount(
    override val level: Int,
    override val label: String,
    override val zero: Boolean,
) : PhysioGrade {
    NONE(0, "无", true),
    TRACE(1, "微", false),
    MODERATE(2, "中", false),
    COPIOUS(3, "多", false),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): FluidAmount =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}
