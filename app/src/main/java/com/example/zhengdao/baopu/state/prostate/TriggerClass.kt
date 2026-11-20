// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-20-第七百三十五批（前列腺通道）③。
//
// 触发三分类必须显式：external / self / 无任何刺激（unverified）。
// unverified **默认关**：不产生事件；放行时事件行必须带「未核实」。
// 三分类缺一（trigger 没给）不许出事件——校验在 [ProstateChannel.emit]。
package com.example.zhengdao.baopu.state.prostate

/** unverified 档的默认开关：关。开它必须调用方显式给 true。 */
internal const val DEFAULT_ALLOW_UNVERIFIED = false

/** 放行后事件行必须挂的标记。 */
internal const val UNVERIFIED_TAG = "未核实"

/** 触发三分类。id 用派工单原词，label 给中文说法。 */
internal enum class TriggerClass(
    val id: String,
    val label: String,
) {
    EXTERNAL("external", "外部触发"),
    SELF("self", "自身收缩"),
    UNVERIFIED("unverified", "无任何刺激");

    val source: String get() = PROSTATE_SOURCE
    val asOf: String get() = PROSTATE_AS_OF

    companion object {
        /** 严格三档：多一档少一档都不算这个体系。 */
        const val COUNT = 3
    }
}
