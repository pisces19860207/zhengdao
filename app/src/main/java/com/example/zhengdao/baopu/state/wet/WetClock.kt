// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百九十七批 · 固定拍钟：与 730 同一拍口径（固定步长、步上限、整数毫档）。
// 1/3/6 是 726 派工单的**计划口径（未合）**，溯源见 889；不可把它写成已实现的口径。
// 726 落地后须回读核对；本包不引用、不修改 726/730 源码，也不把计划口径写成已实现的口径。
package com.example.zhengdao.baopu.state.wet

/**
 * 拍与毫档的常量集中处，源码里不散落 MagicNumber。
 * 拍是无量纲刻度，不给秒；毫档是整档的千分刻度，不给长度/体积。
 */
internal object WetClock {
    /** 固定步长：1 拍。 */
    const val DT = 1

    /** 一次推进上限 6 拍，与 730 的最慢恢复预算同口径。 */
    const val MAX_STEPS = 6

    /** 判据②的三拍时间夹具。 */
    const val DRY_CHECK_BEATS = 3

    /** 每整档的毫档刻度。 */
    const val PER_GRADE = 1000

    /** 湿身上限：三档。 */
    const val WET_MAX = 3 * PER_GRADE

    /** 偏温上限：两档。 */
    const val WARM_MAX = 2 * PER_GRADE

    /** 最慢的衰减也保底每拍半个档：三拍后必降一整档以上。 */
    const val MIN_DECAY = PER_GRADE / 2

    /** 726 派工单计划口径（未合，见 889）：快一拍 / 中三拍 / 慢六拍。 */
    private val RECOVERY_BEATS = intArrayOf(1, 3, 6)

    fun recoveryBeats(level: Int): Int = RECOVERY_BEATS[level.coerceIn(0, RECOVERY_BEATS.lastIndex)]
}
