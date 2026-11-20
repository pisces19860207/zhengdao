// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-20-第七百三十五批（前列腺通道）①。
//
// 感觉通道档**与力学通道分离**：本包不读 727 冲击档，没有「冲击→感觉」的映射函数，
// 也不 import injury 包的任何东西——感觉档只由当事人报告直接给档（判据①）。
// 这里只放「他怎么说」的四档，不写结论、不写评分、不写怎么处置。
package com.example.zhengdao.baopu.state.prostate

/** 每档统一依据口径：本单派工单＋工程假设，不引用任何诊疗资料原文。 */
internal const val PROSTATE_SOURCE =
    "派工单-2026-11-20-第七百三十五批（前列腺通道）＋本项目工程假设"
internal const val PROSTATE_AS_OF = "2026-11-20"

/** 每条输出统一附的一句话；渲染只有一个出口（[ProstateChannel]），所以不可能漏。 */
internal const val DISCLAIMER = "非医疗判断"

/** 感觉通道档 4 档：无感 → 深处压感 → 酸胀扩散 → 沿会阴放射。 */
internal enum class ProstateSensation(
    val level: Int,
    val label: String,
    val observable: String,
) {
    NONE(0, "无感", "说不清位置，也没觉得有"),
    DEEP_PRESS(1, "深处压感", "位置在深处，像被顶着压"),
    SPREAD(2, "酸胀扩散", "酸胀从原点向周围扩开"),
    RADIATE(3, "沿会阴放射", "酸胀沿会阴一条带往外放");

    /** 依据与时点统一由表头常量给，业务码里没有第二份。 */
    val source: String get() = PROSTATE_SOURCE
    val asOf: String get() = PROSTATE_AS_OF

    companion object {
        const val COUNT = 4

        /** 越界钳到最重一档（fail-closed），不悄悄当成「无感」。 */
        fun of(level: Int): ProstateSensation =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}
