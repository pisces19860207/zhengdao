// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-19-第七百二十八批-伤情与疼痛观察档 §一、§三。
//
// 这里只放「看得见的三套档」：颜色／形状／动作，以及当事人怎么说。
// 不写结论、不写评分、不写怎么处置——那些一个字都没有。
package com.example.zhengdao.baopu.state.injury

/** 可观察特征的依据口径：本单派工单＋工程假设，不引用任何诊疗资料原文。 */
internal const val OBSERVABLE_SOURCE =
    "派工单-2026-11-19-第七百二十八批 §一（可观察特征口径）＋本项目工程假设"
internal const val OBSERVABLE_AS_OF = "2026-11-19"

/** 每条输出统一附的一句话；渲染只有一个出口，所以不可能漏。 */
internal const val DISCLAIMER = "非医疗判断"

/** 伤情观察档 4 档：无 → 局部色变（红／青）→ 肿起 → 活动受限。 */
internal enum class InjuryGrade(
    val level: Int,
    val label: String,
    val observable: String,
) {
    NONE(0, "无", "看不到色变、鼓起或动作变化"),
    COLOR(1, "局部色变", "局部颜色与周围不同：发红或发青"),
    SWELL(2, "肿起", "局部可见鼓起，轮廓比平时高"),
    LIMITED(3, "活动受限", "动作做不了，或做到一半停住");

    /** 每档都带依据与时点；统一由表头常量给，业务码里没有第二份。 */
    val source: String get() = OBSERVABLE_SOURCE
    val asOf: String get() = OBSERVABLE_AS_OF

    companion object {
        const val COUNT = 4

        /** 越界钳到最重一档（fail-closed），不悄悄当成「无」。 */
        fun of(level: Int): InjuryGrade = values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 疼痛观察档 4 档：只写当事人怎么表现／怎么说，没有评分也没有量表。 */
internal enum class PainGrade(
    val level: Int,
    val label: String,
    val observable: String,
) {
    NONE(0, "无感", "看不出不舒服，也没听他说"),
    TOUCH(1, "提示感", "一碰才说有感觉"),
    STEADY(2, "持续感", "不动也说一直有感觉"),
    MOTION(3, "影响动作", "动作因此慢下来，或做到一半停下");

    val source: String get() = OBSERVABLE_SOURCE
    val asOf: String get() = OBSERVABLE_AS_OF

    companion object {
        const val COUNT = 4
        fun of(level: Int): PainGrade = values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/**
 * 恢复观察档 3 档：只给时间档，不给天数。
 *
 * [budgetRounds] 是内部轮次刻度，只用于单调回落与时间夹具，不出现在输出里。
 */
internal enum class RecoveryGrade(
    val level: Int,
    val label: String,
    val observable: String,
    val budgetRounds: Int,
) {
    SAME_TURN(1, "当轮消", "这一轮里就消了", 1),
    LATER(2, "隔一段", "过一段才消", 3),
    LONGER(3, "需更久", "还要更久才消", 6);

    val source: String get() = OBSERVABLE_SOURCE
    val asOf: String get() = OBSERVABLE_AS_OF

    companion object {
        const val COUNT = 3

        /** 最慢档刻度；时间夹具超过它就必须回落。 */
        val LONGEST_ROUNDS: Int get() = LONGER.budgetRounds
    }
}
