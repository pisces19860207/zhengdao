// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 734 合并 736 §四①（原 736-A）：
//   频率档 0–3 · 深度档 0–3 · 回撤档 0–2；
//   不许无限加速（连续 ≥CAP_BEATS 拍后频率停在最高档）；停 ⇒ 相关事件归零。
//
// 纯函数：输入「已经连续的拍数」，输出档位；对象内不持有可变累积量（无积分器）。
package com.example.zhengdao.baopu.state.stimulus

/** 节律读数：三档一组，只描述「自身刺激」的当下形态。 */
internal data class RhythmReading(
    val frequency: Int,
    val depth: Int,
    val withdraw: Int,
) {
    companion object {
        val STOPPED = RhythmReading(0, 0, 0)
    }
}

internal object Rhythm {
    /** 每 BEATS_PER_STEP 拍升一档；到顶后无论多少拍都不再升。 */
    const val BEATS_PER_STEP = 2
    /** 连续达到最高档需要的拍数（N）：判据「连续 ≥N 拍后停最高档」的 N。 */
    const val CAP_BEATS = FrequencyGrade.MAX * BEATS_PER_STEP

    /**
     * 连续拍数 ⇒ 频率档；单调不减，且封顶在 [FrequencyGrade.MAX]。
     * 「不许无限加速」断言：任意超大拍数都等于最高档，不再往上走。
     */
    fun frequencyAt(consecutiveBeats: Int): Int {
        val beats = consecutiveBeats.coerceAtLeast(0)
        return minOf(FrequencyGrade.MAX, beats / BEATS_PER_STEP)
    }

    /** 深度、回撤由调用方给档位，这里只做钳位，不自行推演。 */
    fun of(frequencyBeats: Int, depthGrade: Int, withdrawGrade: Int): RhythmReading =
        RhythmReading(
            frequency = frequencyAt(frequencyBeats),
            depth = depthGrade.coerceIn(0, DepthGrade.MAX),
            withdraw = withdrawGrade.coerceIn(0, WithdrawGrade.MAX),
        )

    /** 停 ⇒ 相关事件归零（判据④：停止后节律事件随之为 0）。 */
    fun afterStop(): RhythmReading = RhythmReading.STOPPED
}
