// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零四批 · 触发的**限频预算**（派工单 §3：同类事件 24h 最多 1 次；每月总次数 ≤20）。
//
// 两条口径（都在这里一处写死，别处不许再算）：
//   1. **同类**＝同 [TriggerType]：上一条**真触发**的账行在 24h 内 ⇒ 抑制；
//   2. **每月**＝自然月：本月**真触发**的账行已达 [MONTHLY_CAP] ⇒ 抑制。
//
// 只有"真触发"的行参与预算（[TriggerRow.triggered] = true）：被抑制的尝试记进账本
// 是为了对账"为什么没评"（验收②要的就是这条证据），但它们不消耗预算——不然一次
// 抑制就永久堵死下一个窗口，跟 168 批「NotRun 不记节流账」同一个道理。
package com.example.zhengdao.baopu.evolution.trigger

import java.util.Calendar

internal object EvalTriggerBudget {

    /** 同类两次触发之间的最小间隔（毫秒）。 */
    const val SAME_TYPE_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    /** 每个自然月最多触发多少次（四类合计）。 */
    const val MONTHLY_CAP = 20

    /**
     * 该不该抑制（**纯函数**；null ＝ 放行，非 null ＝ 抑制原因）。
     *
     * @param rows 全部账行（触发的与被抑制的都传进来；只有 triggered=true 的参与判定）
     * @param monthOf 月份函数（默认 [calendarMonthOf]；测试可注入固定值钉边界）
     */
    fun suppressReason(
        type: TriggerType,
        rows: List<TriggerRow>,
        nowMs: Long,
        intervalMs: Long = SAME_TYPE_INTERVAL_MS,
        cap: Int = MONTHLY_CAP,
        monthOf: (Long) -> Int = ::calendarMonthOf,
    ): String? {
        val triggered = rows.filter { it.triggered }
        val lastOfType = triggered.lastOrNull { it.type == type }
        if (lastOfType != null && nowMs - lastOfType.atMs < intervalMs) {
            return "同类「${type.cn}」${intervalMs / 3_600_000}h 内已评过（上次 ${lastOfType.atMs}）"
        }
        val month = monthOf(nowMs)
        val inMonth = triggered.count { monthOf(it.atMs) == month }
        if (inMonth >= cap) return "本月已触发 $inMonth 次（上限 $cap）"
        return null
    }

    /**
     * 自然月键（`年*100+月`；本机时区——"每月"按用户看的日历算，不按 UTC 算）。
     *
     * 抽成函数是为了测试能注入固定值钉住"跨月重置"这条边界（真实时钟没法在 JVM 里拨）。
     */
    fun calendarMonthOf(ms: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return (c.get(Calendar.YEAR) * 100) + (c.get(Calendar.MONTH) + 1)
    }
}
