// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批：勘验层的两把小工具——一行话的钳形，与"抽检而非全检"的抽样器。
// 零 Android、零 IO、零时钟。
package com.example.zhengdao.baopu.inspect

/** 一行话的形制（§2.4 ≤40 字、§3 三行）。 */
internal object InspectText {

    /**
     * 单行＋限长（227/237 的 `fit` 同口径：先把所有空白压成一个空格，再截）。
     *
     * 为什么在**构造期**就钳而不是显示期截：巡按台账是 JSONL，一行一条。
     * 留着换行写进盘里，下一次读回来就是两条坏行。
     */
    fun fit(raw: String, max: Int): String {
        val one = raw.replace(Regex("""\s+"""), " ").trim()
        return if (one.length <= max) one else one.take(max - 1) + "…"
    }

    /** 勘验那一句（永远走 [fit]，所以 [InspectFinding.fitsLine] 恒真）。 */
    fun line(raw: String): String = fit(raw, InspectLimits.MAX_LINE_CHARS)

    /** 百分比读数（一位小数；与 236 `DelegationMetrics.pctText` 同格式，两处话术不能一个样两制）。 */
    fun pct(rate: Double): String =
        (Math.round(rate * 10) / 10.0).toString().trimEnd('0').trimEnd('.')
}

/**
 * 抽样器（§2.2「抽检而非全检」＋负例④「每次扫全库/全部回答」）。
 *
 * **等距**抽样而不是随机：
 * - 可复现——同一份池子每次巡同一段，不用带 RNG 状态进台账；
 * - 覆盖面——池子按新→旧排好，等距取就把整段窗口都摊到；取"最新 k 条"只盯着昨天。
 *
 * 本函数只从**已经取到内存的**池子里挑（读多少条由 SQL 侧的 `LIMIT` 决定）。
 * 真正的"不全检"靠 `PatrolSources` 的每一条 SQL 都带 `LIMIT`，这里再加一道源码门禁反钉。
 */
internal object InspectSample {

    /** 从 pool（调用方已按新→旧排好）等距取 [k] 条；不足 k 条就全取。 */
    fun <T> spread(pool: List<T>, k: Int): List<T> {
        if (k <= 0 || pool.isEmpty()) return emptyList()
        if (pool.size <= k) return pool
        val out = ArrayList<T>(k)
        // 步长取整后可能少取一条，用 ceil 保证覆盖到最后一个位置。
        val step = pool.size.toDouble() / k
        var i = 0
        while (i < k) {
            out += pool[(i * step).toInt().coerceIn(0, pool.size - 1)]
            i++
        }
        return out
    }

    /** 命中几条才报（派工单里 I1/I2/I3 都是「命中 1 条即报」）。 */
    fun reportable(hits: Int, min: Int): Boolean = hits >= min
}
