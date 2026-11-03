// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十八单 · 离场值守的**节拍与降级尺**（在飞 388：每小时汇总；
// 388 派工单判据：B 级汇总 2h 没出 ⇒ 降级停手）。纯函数、零时钟零 IO。
//
// 两条纪律：
// 1. **锚点只有一个**：汇总节拍与降级尺都从"上次出汇总的时刻"（没有 ⇒ 开值守那一刻）起算。
//    两把尺两处各算各的锚，迟早一个说该出、一个说该停——同一件事只许一把尺。
// 2. **降级是单行道**：降级之后本文件不提供"自动解除"的口——复工要由人收值守再开
//    （与免打扰"到期不续"同一条法：机器不许自己把自己的熔断掰回来）。
package com.example.zhengdao.unattended

/** 节拍与降级的阈值（改数＝改制度，只许在这里改）。 */
internal object UnattendedCadence {

    /** B 级汇总的出账周期（在飞 388 原话：每小时汇总）。 */
    const val SUMMARY_PERIOD_MS: Long = 60L * 60_000L

    /** 距上次 B 级汇总超过这个时长 ⇒ 降级停手（388 判据：2h 没出）。 */
    const val DEGRADE_AFTER_MS: Long = 2L * 60L * 60_000L

    /** 看门狗（WorkManager 兜底）的巡查周期（分钟）。 */
    const val WATCHDOG_PERIOD_MIN: Long = 15L

    /** 服务心跳的打点周期（毫秒）。心跳落 SP，是"服务还活着"的唯一凭据。 */
    const val HEARTBEAT_MS: Long = 60_000L

    /** 心跳多久没跳就认定"服务已中断"（＝3 拍没跳，容忍一两拍的网络/调度抖动）。 */
    const val BEAT_STALE_MS: Long = 3L * HEARTBEAT_MS

    /** 下一次该出汇总的时刻（锚点＝上次汇总，没有 ⇒ 开值守）。 */
    fun nextSummaryAtMs(armedAtMs: Long, lastSummaryAtMs: Long?): Long =
        maxOf(armedAtMs, lastSummaryAtMs ?: 0L) + SUMMARY_PERIOD_MS

    /** 这一拍该不该出 B 级汇总（到点即出，含恰好到点）。 */
    fun summaryDue(armedAtMs: Long, lastSummaryAtMs: Long?, nowMs: Long): Boolean =
        nowMs >= nextSummaryAtMs(armedAtMs, lastSummaryAtMs)

    /** 这一拍该不该降级（距锚点满 2h 仍没出汇总 ⇒ 停手）。 */
    fun degradeDue(armedAtMs: Long, lastSummaryAtMs: Long?, nowMs: Long): Boolean =
        nowMs - maxOf(armedAtMs, lastSummaryAtMs ?: 0L) >= DEGRADE_AFTER_MS
}
