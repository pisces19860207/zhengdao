// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第五百一十一批 · **后台自动维护**的唯一开关与机会读数（暗卫与巡按共用一套）。
//
// 为什么这个文件不在 `baopu/inspect/`：勘验层被 `InspectReadOnlyGateTest` 整层钉成
// "只查不改"（写口一律住在装配层）。开关要有**写口**（设置页那一行是唯一写点），
// 所以属主住这里；判据（什么时候该跑）仍然只在 `inspect/Opportunistic.kt`，
// 本文件一句"该不该跑"都不判，只把机器读数交出去。
//
// 键名口径（派工单 479 §一①）：SP `zhengdao-inspect` ／ `auto_maintenance`，**缺省开**。
// 432 的旧键 `nightwatch_auto` 只做**只读回退**：那一版从来没有写口（432 §二④ 明说写侧未做），
// 所以真机上几乎不可能存在；存在时照它读，缺省仍是开——不迁移、不双写。
package com.example.zhengdao.baopu.maintenance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.edit

/** 一次「机会读数」——判据只看这五个数，进程里读不到的一律不冒充（见 [AutoMaintenance.readings]）。 */
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
)

/**
 * 后台自动维护的总开关＋机器读数。
 *
 * 三条纪律：
 * 1. **开关只有一个**：设置页只留「后台自动维护」一行，关 ⇒ 暗卫与巡按都不启动
 *    （派工单 511 §三：不再暴露"每日／每周／夜间"这类开关）。
 * 2. **缺省开**：读不到（SP 没建过／坏了）也按开——后台维护是默认行为，不是需要人去开的彩蛋。
 * 3. **读数 fail-closed**：电量读不出来就当不够（宁可少跑一趟，不许在低电时抽检/巡检）。
 */
internal object AutoMaintenance {

    /** SP 文件名（与体检那套同域：一个用途一个文件）。 */
    const val SP: String = "zhengdao-inspect"

    /** 总开关的键（511 §三唯一出口）。 */
    const val KEY: String = "auto_maintenance"

    /** 432 的旧键：只读回退，不写。 */
    const val LEGACY_KEY: String = "nightwatch_auto"

    /** 缺省＝开（派工单 §三）。 */
    const val DEFAULT_ON: Boolean = true

    /** 现在开着吗（新键 > 旧键 > 缺省开）。 */
    fun on(ctx: Context): Boolean = runCatching {
        val p = prefs(ctx)
        when {
            p.contains(KEY) -> p.getBoolean(KEY, DEFAULT_ON)
            p.contains(LEGACY_KEY) -> p.getBoolean(LEGACY_KEY, DEFAULT_ON)
            else -> DEFAULT_ON
        }
    }.getOrDefault(DEFAULT_ON)

    /**
     * 唯一的写口（设置页那一行调它）。
     *
     * 写失败按没写处理：读侧下次读到的还是旧值，屏上状态跟着回正，不会出现
     * "界面说关了、其实还开着"的假象。
     */
    fun set(ctx: Context, on: Boolean) {
        runCatching { prefs(ctx).edit { putBoolean(KEY, on) } }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 现在在不在充电（与体检同一只探针：读不到电池服务 ⇒ 当没充电）。 */
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

    /** L2 值守中吗（问口是 388 的状态仓，本层不摸 SP／钥匙串）。 */
    fun onDuty(): Boolean = com.example.zhengdao.unattended.UnattendedStatus.armed()

    /**
     * 攒一次机会读数。
     *
     * [lastAutoActionAtMs] 由调用方从三本账（暗卫／巡按／体检）里取最新那条——
     * 本层不读文件，读盘的事仍归装配侧。
     *
     * **"没在跑重活"只认得出进程内的互斥锁**（[com.example.zhengdao.baopu.inspect.HeavyWork]）：
     * 引擎有没有会话在跑，进程里没有可读的问口（432 §四⑥ 同一句诚实），读不到就不冒充读过——
     * 抽检/巡检本身是只读取数＋小账追加，与会话并发不冲突。
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
    )
}
