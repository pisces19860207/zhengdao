// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-19-第七百三十四批-反复刺激的适应与饱和（含黏液变化）
//      §一、§二、追加·长期暴露，及其合并的第七百三十六批抽送节律与进入感受。
//
// 全包只做「档位与方向」：离散档、纯函数、确定性。
// 不做剂量-效应数值、不做时间量化（秒/天）、不写行为指导。
// 四段（节律／适应饱和／分泌／感受）各自独立，入口不互读。
package com.example.zhengdao.baopu.state.stimulus

/** 档位依据口径：本单派工单＋工程假设，不引用任何诊疗资料原文。 */
internal const val STIMULUS_SOURCE =
    "派工单-2026-11-19-第七百三十四批（含并 736）§一/§二 ＋ 本项目工程假设"
internal const val STIMULUS_AS_OF = "2026-11-19"
internal const val STIMULUS_NOTE = "档位与方向模型，非剂量-效应测量"

/** 频率档 4：慢／中／快／急。 */
internal enum class FrequencyGrade(val level: Int, val label: String) {
    SLOW(0, "慢"), MID(1, "中"), FAST(2, "快"), URGENT(3, "急");

    companion object {
        const val MAX = 3
        fun of(level: Int): FrequencyGrade = values()[level.coerceIn(0, MAX)]
    }
}

/** 深度档 4：浅／中／深／满。 */
internal enum class DepthGrade(val level: Int, val label: String) {
    SHALLOW(0, "浅"), MID(1, "中"), DEEP(2, "深"), FULL(3, "满");

    companion object {
        const val MAX = 3
        fun of(level: Int): DepthGrade = values()[level.coerceIn(0, MAX)]
    }
}

/** 回撤档 3：小／中／大。 */
internal enum class WithdrawGrade(val level: Int, val label: String) {
    SMALL(0, "小"), MID(1, "中"), LARGE(2, "大");

    companion object {
        const val MAX = 2
        fun of(level: Int): WithdrawGrade = values()[level.coerceIn(0, MAX)]
    }
}

/** 敏感档 4：迟钝／偏低／常态／敏锐。恢复与适应只动这一档。 */
internal enum class SensitivityGrade(val level: Int, val label: String) {
    DULL(0, "迟钝"), LOW(1, "偏低"), NORMAL(2, "常态"), KEEN(3, "敏锐");

    companion object {
        const val MAX = 3
        fun of(level: Int): SensitivityGrade = values()[level.coerceIn(0, MAX)]
    }
}

/** 饱和不适档 3：无／轻微／明显。只在饱和区出现。 */
internal enum class DiscomfortGrade(val level: Int, val label: String) {
    NONE(0, "无"), MILD(1, "轻微"), CLEAR(2, "明显");

    companion object {
        const val MAX = 2
        fun of(level: Int): DiscomfortGrade = values()[level.coerceIn(0, MAX)]
    }
}

/** 感受六维键：阻力／顺滑／温度／包覆／压迫／钝痛，各 0–3。 */
internal enum class SensationDimension(val key: String, val label: String) {
    RESISTANCE("resistance", "阻力"),
    SMOOTHNESS("smoothness", "顺滑"),
    TEMPERATURE("temperature", "温度"),
    ENVELOPMENT("envelopment", "包覆"),
    PRESSURE("pressure", "压迫"),
    DULL_PAIN("dull_pain", "钝痛");

    companion object {
        const val MAX = 3
        const val COUNT = 6
    }
}
