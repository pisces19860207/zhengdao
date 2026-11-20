// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 734 §一① 分泌双相：
//   刺激累积档 0–5 ⇒ 分泌档先升（0→3）后趋缓（3→2），有上限。
//   判据①：双相（非单调上升）且封顶；负例「单调无限上升」⇒ 红。
//
// 表驱动、确定性、无积分器：输入累积档，查固定表，不持有状态。
package com.example.zhengdao.baopu.state.stimulus

internal object SecretionCurve {
    /** 分泌档上限（0–3）。表内所有值都 ≤ 它，天然封顶。 */
    const val CAP = 3
    const val MAX_ACCUMULATION = 5

    /** 0→1→2→3 升，3→2 回落，2→2 趋缓：先升后缓且封顶。 */
    private val TABLE = intArrayOf(0, 1, 2, 3, 2, 2)

    /** 累积档 ⇒ 分泌档。越界钳到表区间，绝不因输入增大而突破上限。 */
    fun at(accumulationGrade: Int): Int = TABLE[accumulationGrade.coerceIn(0, MAX_ACCUMULATION)]

    /** 判据用：整表的最大值须等于封顶且处处不越顶。 */
    fun peak(): Int = TABLE.maxOrNull() ?: 0
}
