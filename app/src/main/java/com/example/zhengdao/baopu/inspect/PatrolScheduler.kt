// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百三十九批 §2.1：怎么跑——**低峰巡检：每日一次（夜间），或用户手动点"巡一遍"**。
//
// **§10③ 裁定（2026-10-27）把夜间那一趟默认关掉**：默认**不自动夜跑**；改为
// **手动「巡一遍」＋每 7 天提醒一次**（提醒不弹窗，形态 = 巡按栏里那一句）；真要自动，
// 必须 `WorkManager` 约束三件（**充电中＋设备空闲＋电量>30%**），且**一次唤醒最多 1 轮**。
// 裁定原文的理由：夜里自动跑＝耗电＋可能被系统杀＋打扰；勘验的价值在"能问"，不在"高频"。
//
// 本文件只有"这趟该不该跑/该不该提醒"的判据（纯函数，零时钟、零 Android）。
// WorkManager 的注册属装配层（167 批那套定时任务基建在 `schedule/`，不在本批占用范围）。
// 三约束在判据里**再验一遍**是纵深防御：WorkManager 的约束是"到了才唤醒"，
// 这里的参数是"唤醒了也要再查"——两道闸谁也不能单独放行。手动那颗「巡一遍」
// 在界面里是真接通的，真机读数从它取。
package com.example.zhengdao.baopu.inspect

/** 巡按的发起判据。 */
internal object PatrolScheduler {

    /** 夜间低峰窗口（含头不含尾：02:00–05:00）。只在自动夜跑被显式打开时才用得上。 */
    const val NIGHT_START_HOUR = 2
    const val NIGHT_END_HOUR = 5

    /** 两次自动巡按的最小间隔（毫秒）。 */
    const val MIN_GAP_MS = 20L * 3_600_000L

    /** §10③：自动夜跑**默认关**。装配层真要开，必须显式传参，判据不替谁记这个开关。 */
    const val AUTO_ENABLED = false

    /** §10③ 三约束之一：电量下限（**>**30% 才巡，等于线也拦）。 */
    const val MIN_BATTERY_PCT = 30

    /** §10③：一次唤醒最多跑几轮（判据层钉死为 1；装配层不许在唤醒里循环补跑）。 */
    const val MAX_ROUNDS_PER_PATROL = 1

    /** §10③：提醒间隔——距上次巡按 ≥7 天，巡按栏出一句「距上次巡按已 N 天」。 */
    const val REMIND_AFTER_DAYS = 7

    /** 该不该跑这一趟（[why] 永远有字——"没跑"也要能一眼看出为什么没跑）。 */
    data class Gate(val run: Boolean, val why: String)

    /** 钟点（0–23）落在夜间窗口里吗。 */
    fun inNightWindow(hour: Int): Boolean = hour in NIGHT_START_HOUR until NIGHT_END_HOUR

    /**
     * 自动那一趟的闸门（§10③ 后的形状：**默认永远不跑**）。
     *
     * [enabled] 缺省读 [AUTO_ENABLED]＝false ⇒ 一律拒，`why` 告诉人该点哪里。
     * 装配层真要自动夜跑，必须显式传 `enabled = true`，并把 WorkManager 的
     * `requiresCharging()`／`setRequiresDeviceIdle(true)` 与电量读数带进来——
     * 判据在这里再验一遍，两道闸谁也不能单独放行。
     *
     * @param lastPatrolAtMs 上一次巡按的时刻（null = 从没巡过 ⇒ 间隔不拦）
     * @param hourOfDay 此刻钟点（调用方给；本层不读时钟，用例才钉得住）
     * @param running 有会话正在跑吗（巡检不许跟执行抢手机——§2 那句"不许变成第二个负担"）
     * @param charging 充电中（§10③ 三约束之一）
     * @param deviceIdle 设备空闲（§10③ 三约束之一）
     * @param batteryPct 当前电量百分比（须 >[MIN_BATTERY_PCT]）
     * @param roundsThisWake 这次唤醒已经跑过几轮（≥[MAX_ROUNDS_PER_PATROL] 就不再跑）
     * @param enabled 自动夜跑开关（缺省 = [AUTO_ENABLED]，默认关是 §10③ 的本体）
     */
    fun autoGate(
        lastPatrolAtMs: Long?,
        hourOfDay: Int,
        nowMs: Long,
        running: Boolean = false,
        charging: Boolean = false,
        deviceIdle: Boolean = false,
        batteryPct: Int = MIN_BATTERY_PCT,
        roundsThisWake: Int = 0,
        enabled: Boolean = AUTO_ENABLED,
    ): Gate = when {
        !enabled -> Gate(false, "自动夜跑默认关（§10③）：要巡就点「巡一遍」，7 天没巡会提醒")
        running -> Gate(false, "有任务在跑，今晚不巡")
        !inNightWindow(hourOfDay) -> Gate(false, "不在夜间低峰窗口（$NIGHT_START_HOUR–$NIGHT_END_HOUR 点）")
        lastPatrolAtMs != null && nowMs - lastPatrolAtMs < MIN_GAP_MS ->
            Gate(false, "距上次巡按不足 ${MIN_GAP_MS / 3_600_000L} 小时")
        roundsThisWake >= MAX_ROUNDS_PER_PATROL ->
            Gate(false, "这次唤醒已巡过 $roundsThisWake 轮（上限 $MAX_ROUNDS_PER_PATROL）")
        !charging -> Gate(false, "只在充电中自动巡（§10③ 三约束之一）")
        !deviceIdle -> Gate(false, "只在设备空闲时自动巡（§10③ 三约束之一）")
        batteryPct <= MIN_BATTERY_PCT ->
            Gate(false, "电量 ${batteryPct}% 不足 ${MIN_BATTERY_PCT}%，不自动巡")
        else -> Gate(true, "夜间低峰＋充电＋空闲＋电量够，该巡")
    }

    /** 手动那一趟只有一条限制：别跟正在跑的会话抢手机。 */
    fun manualGate(running: Boolean = false): Gate =
        if (running) Gate(false, "有任务在跑，稍后再巡") else Gate(true, "手动巡一遍")

    /**
     * §10③：7 天提醒（**不弹窗**——她已裁定，形态就是巡按栏那一句）。
     *
     * 从没巡过（null）不算「该提醒」：那是首趟引导，巡按栏的空态有自己的话；
     * 这条只管「巡过一次之后开始懈怠」的情形。
     */
    fun remindGate(lastPatrolAtMs: Long?, nowMs: Long): Gate {
        if (lastPatrolAtMs == null) return Gate(false, "还没巡过第一趟：先点一次「巡一遍」")
        return if (remindDays(lastPatrolAtMs, nowMs) >= REMIND_AFTER_DAYS) {
            Gate(true, "距上次巡按已 ${remindDays(lastPatrolAtMs, nowMs)} 天")
        } else {
            Gate(false, "距上次巡按不足 $REMIND_AFTER_DAYS 天")
        }
    }

    /** 距上次巡按过了多少个整天（提醒行的数字；[remindGate] 与它同一个算式）。 */
    fun remindDays(lastPatrolAtMs: Long, nowMs: Long): Long =
        (nowMs - lastPatrolAtMs) / (24L * 3_600_000L)

    /** 上次巡按的时刻（台账新→旧排好，取第一条 ⇒ 最新一次）。 */
    fun lastPatrolAtMs(entries: List<PatrolEntry>): Long? = entries.maxOfOrNull { it.atMs }
}
