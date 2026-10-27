// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百三十九批 §2.1：怎么跑——**低峰巡检：每日一次（夜间），或用户手动点"巡一遍"**。
//
// 本文件只有"这趟该不该跑"的判据（纯函数，零时钟、零 Android）。
// **为什么没有 WorkManager/闹钟接线**：本批占用范围只有 `baopu/inspect/` 与
// `DecisionCenterScreen.kt`，调度属于装配层（167 批那套定时任务基建在 `schedule/`，
// 不在本批文件里）。所以：低峰的**判据**在这里，低峰的**触发**是交付报告里的挂接点；
// 而手动那颗「巡一遍」在界面里是真接通的——真机三天的读数就从它取。
//
// 「每日一次」落成"距上次巡按 ≥ [MIN_GAP_MS] 且此刻在夜间窗口"：
// 20 小时而不是 24 小时，是为了让每天那一趟不会因一次唤醒漂移就顺延成隔天没巡——
// 夜间窗口本身只有三小时，宁可早一小时巡，也不要"这周一次都没巡"。
package com.example.zhengdao.baopu.inspect

/** 巡按的发起判据。 */
internal object PatrolScheduler {

    /** 夜间低峰窗口（含头不含尾：02:00–05:00）。 */
    const val NIGHT_START_HOUR = 2
    const val NIGHT_END_HOUR = 5

    /** 两次自动巡按的最小间隔（毫秒）。 */
    const val MIN_GAP_MS = 20L * 3_600_000L

    /** 该不该跑这一趟（[why] 永远有字——"没跑"也要能一眼看出为什么没跑）。 */
    data class Gate(val run: Boolean, val why: String)

    /** 钟点（0–23）落在夜间窗口里吗。 */
    fun inNightWindow(hour: Int): Boolean = hour in NIGHT_START_HOUR until NIGHT_END_HOUR

    /**
     * 自动那一趟的闸门。
     *
     * @param lastPatrolAtMs 上一次巡按的时刻（null = 从没巡过 ⇒ 今夜第一趟就跑）
     * @param hourOfDay 此刻钟点（调用方给；本层不读时钟，用例才钉得住）
     * @param running 有会话正在跑吗（巡检不许跟执行抢手机——§2 那句"不许变成第二个负担"）
     */
    fun autoGate(
        lastPatrolAtMs: Long?,
        hourOfDay: Int,
        nowMs: Long,
        running: Boolean = false,
    ): Gate = when {
        running -> Gate(false, "有任务在跑，今晚不巡")
        !inNightWindow(hourOfDay) -> Gate(false, "不在夜间低峰窗口（$NIGHT_START_HOUR–$NIGHT_END_HOUR 点）")
        lastPatrolAtMs != null && nowMs - lastPatrolAtMs < MIN_GAP_MS ->
            Gate(false, "距上次巡按不足 ${MIN_GAP_MS / 3_600_000L} 小时")
        else -> Gate(true, "夜间低峰，该巡")
    }

    /** 手动那一趟只有一条限制：别跟正在跑的会话抢手机。 */
    fun manualGate(running: Boolean = false): Gate =
        if (running) Gate(false, "有任务在跑，稍后再巡") else Gate(true, "手动巡一遍")

    /** 上次巡按的时刻（台账新→旧排好，取第一条 ⇒ 最新一次）。 */
    fun lastPatrolAtMs(entries: List<PatrolEntry>): Long? = entries.maxOfOrNull { it.atMs }
}
