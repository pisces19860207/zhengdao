// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 因果链骨架：滞后后果的「时间偏移档」。
//
// 这个文件只做一件事：把 825 派工单 §二 的**四种跨度**原样搬成一个闭集，供滞后后果引用。
// 它**不是**第二个时间系统：本包没有毫秒、没有秒、没有 `Duration`、没有墙钟，
// 回放时只比较「到期档序」——`MINUTES` 到期的滞后项在 `HOURS` 也算到期，反过来不算。
// 于是「时间不够却已恢复」在类型上就成立不了（825 §三②）。
//
// ⚠️ 825 生产类型未落地（仓内无 `state/carryover/`）。825 落地后：
//    删掉本文件 → 把 `import ...state.carryover.Span825` 加进来 → 其余代码一个字不用改
//    （档名、档序、边界文案逐字相同，见 [Span825Source.QUOTE]）。
//    这条迁移路径由 `CausalSourceAssertTest` 钉住：本文件若被改动，那条断言会红。
package com.example.zhengdao.baopu.state.causal

/**
 * 825 §二 的四种时间跨度。**档序即跨度大小**，只可比较，不可换算。
 *
 * [bound] 是派工单原文的边界文案，仅作溯源用，**不参与任何计算**（本包不做时间算术）。
 */
internal enum class Span825(val rank: Int, val label: String, val bound: String) {
    MINUTES(0, "分钟级", "≤10 min"),
    HOURS(1, "小时级", "1–4 h"),
    OVERNIGHT(2, "过夜", "6–10 h"),
    CROSS_DAY(3, "跨日", "跨日/跨周"),
    ;

    /** 是否已经过去这么久：跨度档序不小于到期档序即算到期。 */
    fun reachedBy(elapsed: Span825): Boolean = elapsed.rank >= rank

    companion object {
        const val COUNT = 4

        /** 最长的跨度；「全部滞后都已落地」＝回放到这一档。 */
        val LONGEST: Span825 get() = CROSS_DAY

        fun of(rank: Int): Span825 = values().first { it.rank == rank.coerceIn(0, COUNT - 1) }

        /**
         * 再往后一档（已是最长则原地不动）——「力竭再加一档」用。
         *
         * 刻意写成逐个 when 而不是 `rank + 1`：跨度**不许做算术**，只许在四个档之间跳。
         * 源码门禁里有一条断言钉着「本包不出现 `rank +` / `rank -`」，就是为了防有人
         * 顺手把这里改成算小时数。
         */
        fun later(span: Span825): Span825 = when (span) {
            MINUTES -> HOURS
            HOURS -> OVERNIGHT
            OVERNIGHT -> CROSS_DAY
            CROSS_DAY -> CROSS_DAY
        }
    }
}

/** 825 四档的溯源与迁移说明；唯一真相在这一个对象里，不许在别处再写一份。 */
internal object Span825Source {
    const val ORDER = "825"
    const val SOURCE = "派工单-第八百二十五批-跨场结转 §二（时间跳跃的四种跨度）"
    const val PENDING = "825 生产类型未落地；本包按派工单四档逐字镜像，825 落地后删本文件改 import"

    /** 派工单原文的边界文案（逐字），供测试反查「镜像没走样」。 */
    val QUOTE: Map<Span825, String> = mapOf(
        Span825.MINUTES to "≤10 min",
        Span825.HOURS to "1–4 h",
        Span825.OVERNIGHT to "6–10 h",
        Span825.CROSS_DAY to "跨日/跨周",
    )
}
