// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十八批 · 修剪界面的**设置写口**（757 图纸 §2.1 施工；四写口里的三键，
// 第四写口＝检索页 Switch 的页面 state，在 ui/baopu，不落盘）。
//
// 房型照 168 批 [com.example.zhengdao.baopu.eval.ProdEvalSettings]：
//   · 一个键一个写口（[setEnabled]/[setSchedule]/[setRetireDays]），读走同一出口 [get]；
//   · 只有显式拨动一条路——没有 devMode／adb／环境变量旁路；
//   · SharedPreferences 里只有这三个键（757 §2.1.1 原话），别的键不进这份prefs。
//
// 判据层零改动（757 §五.1）：752 的 PruneRules/PruneStores/PruneWiring 一字不动，
// 三个值在这里被读走，到接收器前置门与装配层频率门（PruneGates.kt）消费。
// 夹回判定是**纯函数**（[enabledOrDefault]/[scheduleOrDefault]/[retireDaysOrDefault]），
// JVM 直测（757 判据①「或 JVM 侧读同一纯函数」口径）——本机单测环境无 Robolectric，
// SharedPreferences 往返的真值取证归真机轮（757 §六 同款记账）。
package com.example.zhengdao.baopu.memoryprune

import android.content.Context
import androidx.core.content.edit

/** 修剪设置的唯一住处（三键；写口各一个，读走同一出口——757 §三映射表的 1/2/3 行）。 */
internal object PruneSettings {

    const val PREFS_NAME = "baopu-prune"

    /** 总开关键（757 §三 行1；唯一写口 [setEnabled]，生效点＝接收器前置门）。 */
    const val KEY_ENABLED = "prune_enabled"

    /** 周期键（行2；存字面量 off/daily/weekly；唯一写口 [setSchedule]，生效点＝装配层频率门）。 */
    const val KEY_SCHEDULE = "prune_schedule"

    /** 退役阈值键（行3；唯一写口 [setRetireDays]，生效点＝runPruneRound 的 retireAfterDays 形参）。 */
    const val KEY_RETIRE_DAYS = "prune_retire_after_days"

    /** 周期值域闭集（757 §2.1：关／每天／每周；第 4 个值不存在）。 */
    const val SCHEDULE_OFF = "off"
    const val SCHEDULE_DAILY = "daily"
    const val SCHEDULE_WEEKLY = "weekly"
    val SCHEDULE_CHOICES: Set<String> = setOf(SCHEDULE_OFF, SCHEDULE_DAILY, SCHEDULE_WEEKLY)

    /** 退役阈值值域闭集（757 §2.1：30/60/90/180 四档；45 这种不在档上的值夹回 90）。 */
    const val RETIRE_DAYS_DEFAULT = 90
    val RETIRE_DAYS_CHOICES: Set<Int> = setOf(30, 60, 90, 180)

    /** 一次读齐三键的快照（UI 初值与门槛读数走同一形状——屏上显示的＝门槛看到的）。 */
    data class Values(
        val enabled: Boolean,
        val schedule: String,
        val retireAfterDays: Int,
    )

    /** 总开关夹回：读不到 ⇒ 按 true（与出厂默认一致，不炸）。 */
    internal fun enabledOrDefault(raw: Boolean?): Boolean = raw ?: true

    /** 周期夹回：值域闭集之外的任何值（含 null/大小写不对）⇒ 写回 weekly。 */
    internal fun scheduleOrDefault(raw: String?): String =
        if (raw != null && raw in SCHEDULE_CHOICES) raw else SCHEDULE_WEEKLY

    /** 阈值夹回：四档之外的任何值（含 null/45）⇒ 90。 */
    internal fun retireDaysOrDefault(raw: Int?): Int =
        if (raw != null && raw in RETIRE_DAYS_CHOICES) raw else RETIRE_DAYS_DEFAULT

    /** 读走同一出口（757 §2.1.1 原话；prefs 读不动 ⇒ 出厂默认三键值，不炸）。 */
    fun get(ctx: Context): Values = runCatching {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Values(
            enabled = enabledOrDefault(prefs.getBoolean(KEY_ENABLED, true)),
            schedule = scheduleOrDefault(prefs.getString(KEY_SCHEDULE, null)),
            retireAfterDays = retireDaysOrDefault(prefs.getInt(KEY_RETIRE_DAYS, RETIRE_DAYS_DEFAULT)),
        )
    }.getOrDefault(Values(enabled = true, schedule = SCHEDULE_WEEKLY, retireAfterDays = RETIRE_DAYS_DEFAULT))

    /** 总开关写口（唯一；写失败按没写处理——下次读到的还是旧值，屏上状态回正）。 */
    fun setEnabled(ctx: Context, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_ENABLED, on) }
        }
    }

    /** 周期写口（唯一；收到的值先过夹回再落盘——非法值存不进 prefs）。 */
    fun setSchedule(ctx: Context, value: String) {
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putString(KEY_SCHEDULE, scheduleOrDefault(value)) }
        }
    }

    /** 阈值写口（唯一；同理先夹回——塞 45 落盘的就是 90）。 */
    fun setRetireDays(ctx: Context, days: Int) {
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putInt(KEY_RETIRE_DAYS, retireDaysOrDefault(days)) }
        }
    }
}
