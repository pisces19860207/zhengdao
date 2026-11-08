// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百七十九批 · **机器读数**的交出口（暗卫／巡按／御医／金吾卫共用一套）。
//
// 第五百一十一批那个「后台自动维护」总开关在 479 被**删掉**了：用户 2026-11-08 说
// 「能全自动的尽量全自动，别弄太多入口或者开关」⇒ 后台维护改成「默认自动 ＋ 两级刹车 ＋
// 单件可停」。所以本文件从现在起**一个 SP 都不碰**：
//   · 没有文件名、没有键名、没有缺省值、没有读写口——`on()`／`set()` 连同 `KEY`／`SP` 一起删；
//   · 只剩"进程里读得到什么就交什么"：电量、充电、值守、发烫、前台会话、静默时长、重活。
// 判据（什么时候该跑）仍然只在 `inspect/Opportunistic.kt` 与 `inspect/MaintBrake.kt`，
// 本文件一句"该不该跑"都不判，只把机器读数交出去。
//
// 读数三条纪律（511 原样保留）：
// 1. **读不到不冒充**：电量读不出 ⇒ -1（判据层 fail-closed，当不够）；发烫读不出 ⇒ false
//    （不冤枉设备）；会话状态只有"引擎建过"才读得到，没建过 ⇒ 没在会话（不把冷启动算成忙）。
// 2. **只读**：这里的每一格都是查询系统状态，不改设备、不写盘。
// 3. **单件暂停不在这里**：那是有写口的配置，住 `maintenance/MaintPause.kt`。
package com.example.zhengdao.baopu.maintenance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/** 一次「机会读数」——判据只看这七个数，进程里读不到的一律不冒充（见 [AutoMaintenance.readings]）。 */
internal data class Opportunity(
    /** L2 值守中（值守期间不跑，避免抢资源）。 */
    val onDuty: Boolean,
    /** 电量百分比；0–100 是读数，-1 ＝ 读不出来（fail-closed）。 */
    val batteryPct: Int,
    /** 在充电吗。 */
    val charging: Boolean,
    /** 距上一次后台自动动作过了多久（毫秒）；从没跑过 ＝ Long.MAX_VALUE。 */
    val quietForMs: Long,
    /** 同一时刻只允许一件后台重活：别人的重活正在跑吗。 */
    val heavyBusy: Boolean,
    /** 设备发烫吗（系统热档 ≥ [AutoMaintenance.THERMAL_HOT]；读不出 ⇒ false）。 */
    val hot: Boolean = false,
    /** 前台正在会话吗（App 可见 ∧ 引擎这一轮在跑；见 [AutoMaintenance.talking]）。 */
    val talking: Boolean = false,
)

/**
 * 机器读数（**没有开关**——479 起后台维护恒自动，管它的是两级刹车）。
 *
 * 为什么还留着这个文件：读数要有**唯一**一处来源。四个器各自摸一遍电池／热档／引擎状态，
 * 迟早会量出两种"发烫"；判据层只认这里交出去的那一份。
 */
internal object AutoMaintenance {

    /**
     * 发烫的门槛：系统热档 ≥ 这一档就算"发烫"。
     *
     * `THERMAL_STATUS_MODERATE` 是系统**开始限流**的那一档（SEVERE 之后已经是被迫降频）——
     * 后台维护是"可以晚点做"的事，等它到 SEVERE 再让开就已经在跟系统抢了。
     */
    val THERMAL_HOT: Int = PowerManager.THERMAL_STATUS_MODERATE

    /** 现在在不在充电（读不到电池服务 ⇒ 当没充电，fail-closed）。 */
    fun charging(ctx: Context): Boolean = runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        bm.isCharging
    }.getOrDefault(false)

    /**
     * 电量百分比（0–100；读不出来 ⇒ -1）。
     *
     * 先问电池服务（`BATTERY_PROPERTY_CAPACITY`），拿不到再看黏性广播
     * （`ACTION_BATTERY_CHANGED`，取 LEVEL/SCALE 换算）——两条都是只读，不动设备状态。
     */
    fun batteryPct(ctx: Context): Int = runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val direct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (direct in 0..100) direct else stickyPct(ctx)
    }.getOrDefault(-1)

    private fun stickyPct(ctx: Context): Int = runCatching {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) level * 100 / scale else -1
    }.getOrDefault(-1)

    /**
     * 设备发烫吗（系统热档 ≥ [THERMAL_HOT]）。
     *
     * 读不到（没这个服务／取数抛）⇒ **false**：宁可多跑一趟，也不拿"读不出"当"发烫"把
     * 四器永久停摆（与电量那格的 fail-closed 相反方向，理由不对称：低电是危险的，
     * "读不出热档"不是）。
     */
    fun hot(ctx: Context): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        pm.currentThermalStatus >= THERMAL_HOT
    }.getOrDefault(false)

    /**
     * 前台正在会话吗：**App 可见** ∧ **引擎这一轮在跑**。
     *
     * 为什么两半都要：只看"引擎在跑"会把后台续跑算成"前台"；只看"App 可见"会把"人正开着
     * 设置页翻看"算成"正在会话"——那样收工那一刻（App 明明在前台）负载触发就永远被拦。
     * 引擎没建过（[com.example.zhengdao.baopu.BaopuRuntime.engineIfBuilt] 为 null）⇒ 没在会话，
     * **不为一眼读数把整个引擎拉起来**（388 那条纪律）。
     */
    fun talking(): Boolean = runCatching {
        if (!com.example.zhengdao.AppForeground.isForeground()) return false
        val st = com.example.zhengdao.baopu.BaopuRuntime.engineIfBuilt()?.state?.value ?: return false
        st in TALKING_STATES
    }.getOrDefault(false)

    /** 算"在会话"的引擎状态（与 `BaopuChatController.busy` 同一组，不另立口径）。 */
    private val TALKING_STATES = setOf(
        com.example.zhengdao.baopu.agent.RunState.THINKING,
        com.example.zhengdao.baopu.agent.RunState.ACTING,
        com.example.zhengdao.baopu.agent.RunState.COMPRESSING,
        com.example.zhengdao.baopu.agent.RunState.AWAIT_CONFIRM,
    )

    /** L2 值守中吗（问口是 388 的状态仓，本层不摸 SP／钥匙串）。 */
    fun onDuty(): Boolean = com.example.zhengdao.unattended.UnattendedStatus.armed()

    /**
     * 攒一次机会读数。
     *
     * [lastAutoActionAtMs] 由调用方从三本账（暗卫／巡按／体检）里取最新那条——
     * 本层不读文件，读盘的事仍归装配侧。
     *
     * **"没在跑重活"只认得出进程内的互斥锁**（[com.example.zhengdao.baopu.inspect.HeavyWork]）。
     */
    fun readings(
        ctx: Context,
        nowMs: Long,
        lastAutoActionAtMs: Long?,
        heavyBusy: Boolean,
    ): Opportunity = Opportunity(
        onDuty = onDuty(),
        batteryPct = batteryPct(ctx),
        charging = charging(ctx),
        quietForMs = lastAutoActionAtMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: Long.MAX_VALUE,
        heavyBusy = heavyBusy,
        hot = hot(ctx),
        talking = talking(),
    )
}
