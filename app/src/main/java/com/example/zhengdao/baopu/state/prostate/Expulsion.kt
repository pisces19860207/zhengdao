// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-20-第七百三十五批（前列腺通道）②。
//
// 排出事件两型：流出（无节律、量少档、延迟）vs 射出（有节律、搏动由强到弱）。
// 注意「无节律」这三个字本身就带节律一词，所以流出的文案用「缓慢延迟外渗」表达
// 非节律性——判据「流出里不出现节律词」由 [ProstateChannel] 出口处硬扫兜底。
// 前列腺相关**默认归流出**；要写射出，调用方必须显式给触发条件（在出口处校验）。
package com.example.zhengdao.baopu.state.prostate

/** 排出事件两型。射出侧的文案才允许出现节律词。 */
internal enum class ExpulsionKind(
    val id: String,
    val label: String,
    val trait: String,
) {
    OUTFLOW("outflow", "流出", "缓慢延迟外渗"),
    EMISSION("emission", "射出", "有节律，搏动由强到弱");

    val source: String get() = PROSTATE_SOURCE
    val asOf: String get() = PROSTATE_AS_OF

    companion object {
        const val COUNT = 2

        /** 前列腺相关的默认型：流出。没有「默认射出」这条路。 */
        val DEFAULT: ExpulsionKind = OUTFLOW
    }
}

/** 流出量只开量少档两档——量大根本不该写成流出，类型上就不给这个口子。 */
internal enum class OutflowVolume(
    val level: Int,
    val label: String,
) {
    TRACE(0, "微量"),
    SMALL(1, "少量");

    companion object {
        const val COUNT = 2

        /** 缺省取最低一档（fail-closed），不悄悄当成「少量」以上。 */
        fun of(level: Int?): OutflowVolume =
            values().first { it.level == (level ?: TRACE.level).coerceIn(0, COUNT - 1) }
    }
}
