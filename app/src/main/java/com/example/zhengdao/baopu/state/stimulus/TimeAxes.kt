// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 734 §一②④、追加长期暴露；节拍口径沿用 726（本单自定义离散「拍」）。
//
// 三条时间轴分开记、互不混写：
//   · 短时适应：同强度持续每 ADAPT_BEATS 拍降 1 档（有下限，不降到无感）。
//   · 恢复：停止后随 elapsed 拍回升，超过最慢档 LONGEST_BEATS 仍不回 ⇒ 红。
//   · 长期适应：暴露/摩擦累积 ⇒ 基线最多降 MAX_DROP 档，可逆但慢、不即时回弹。
// 全部纯函数，入参是「已经过了多少拍/多少周档」，对象不持有可变累积量（无积分器）。
package com.example.zhengdao.baopu.state.stimulus

internal object Adaptation {
    /** 同强度持续每 3 拍降 1 档。 */
    const val ADAPT_BEATS = 3
    /** 短时适应下限：1 档，不许降到 0（降到无感 ⇒ 红）。 */
    const val FLOOR = 1

    /** 同强度持续 sameBeats 拍后的敏感档：只降不升，封底 [FLOOR]。 */
    fun after(base: Int, sameBeats: Int): Int {
        val b = base.coerceIn(0, SensitivityGrade.MAX)
        val drop = sameBeats.coerceAtLeast(0) / ADAPT_BEATS
        return maxOf(FLOOR, b - drop)
    }
}

internal object Recovery {
    /** 停止后的恢复档（对齐 728 的拍口径：1/3/6）。 */
    const val LONGEST_BEATS = 6

    /** 恢复档预算：快=1 拍、中=3 拍、慢=6 拍。 */
    private val BUDGET = intArrayOf(1, 3, 6)

    fun budgetAt(recoveryLevel: Int): Int = BUDGET[recoveryLevel.coerceIn(0, 2)]

    /**
     * 停止后从 reduced 回升到 target：超过最慢恢复档必然回到 target。
     * 时间夹具：elapsed >= LONGEST_BEATS ⇒ 必等于 target（判据③）。
     */
    fun at(reduced: Int, target: Int, elapsedBeats: Int, recoveryLevel: Int): Int {
        val elapsed = elapsedBeats.coerceAtLeast(0)
        if (elapsed >= LONGEST_BEATS) return target
        val budget = budgetAt(recoveryLevel).coerceAtMost(LONGEST_BEATS)
        return if (elapsed >= budget) target else maxOf(reduced, target - (budget - elapsed) / budget)
    }
}

internal object LongTermAdaptation {
    /** 暴露/摩擦累积档 0–3（按周计，不给天数）。 */
    const val MAX_EXPOSURE = 3
    /** 基线最多降一档；降到 0（无感）⇒ 红。 */
    const val MAX_DROP = 1
    const val BASE_FLOOR = 1
    /** 慢可逆：停止暴露后要满 SLOW_WEEKS 周档才回升，不即时回弹。 */
    const val SLOW_WEEKS = 2

    /** 暴露累积 ⇒ 基线敏感档；最多降一档且封底，不因暴露更高而一路降。 */
    fun baselineAfter(base: Int, exposureGrade: Int): Int {
        val b = base.coerceIn(BASE_FLOOR, SensitivityGrade.MAX)
        val drop = if (exposureGrade.coerceIn(0, MAX_EXPOSURE) > 0) MAX_DROP else 0
        return maxOf(BASE_FLOOR, b - drop)
    }

    /** 停止暴露 weeksAfter 周档后的基线：未满 SLOW_WEEKS 不回升（不即时回弹）。 */
    fun recoveringBaseline(reduced: Int, originalBase: Int, weeksAfter: Int): Int {
        val target = originalBase.coerceIn(BASE_FLOOR, SensitivityGrade.MAX)
        val r = reduced.coerceIn(BASE_FLOOR, SensitivityGrade.MAX)
        if (target <= r) return r
        return if (weeksAfter.coerceAtLeast(0) < SLOW_WEEKS) r else minOf(target, r + 1)
    }
}
