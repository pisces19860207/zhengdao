// 独立开发声明：本文件为本项目原创，不含对第三方源码拷贝。
//
// 第四百七十九批 · **单件暂停**（用户 2026-11-08：某个器出问题 ⇒ 停它一个，其余照跑）。
//
// 三条纪律：
// 1. **SP 复用既有文件、不新表**（派工单 ④）：还用 `zhengdao-inspect` 那一本——511 的总开关
//    原来就住在这里，键随总开关一起删了；本批只是把"一个总开关"换成"四把单件锁"。
//    一个用途一个文件的老规矩不破，也不为四行状态另起一本。
// 2. **只做状态，不做判定**：这里是"停到几点"的读写；"该不该跑"归 `inspect/MaintBrake.kt`
//    （纯函数，假时钟钉得住）。装配侧只把 [untilMs] 递过去。
// 3. **默认 24 小时、可续、到期自动恢复**：不需要谁去点"恢复"——停一天是"先让它闭嘴"，
//    不是"永久关掉"。续期是在**剩余时间上再加**一段（[extend]），不是覆盖成"从现在起 24 小时"，
//    免得连点两次反倒缩短。
//
// 写口的活口（为什么不是死代码）：`repair/MaintPauseConfig` 是配置路（御医自修）对这本 SP 的
// 适配器——它按"暂停多少小时"写、写 0 就是恢复。判据表 511 起是空的，所以今天没人自动写它；
// 但路是通的，将来表里加一行就生效（与 447 §三 的分层一致：写盘只住 repair/）。
package com.example.zhengdao.baopu.maintenance

import android.content.Context
import androidx.core.content.edit

/** 单件暂停（四器各一把锁；键在既有 SP 文件里）。 */
internal object MaintPause {

    /** 复用的既有 SP 文件名（511 那本；不新开文件＝不新表）。 */
    const val SP: String = "zhengdao-inspect"

    /** 键前缀：`pause_until_<wire>`，值是"停到哪个时刻"（epoch ms；0／缺键＝没停）。 */
    const val PREFIX: String = "pause_until_"

    /** 一次暂停多长（小时）。派工单 ④：默认 24h。 */
    const val DEFAULT_HOURS: Long = 24L

    private const val HOUR_MS: Long = 3_600_000L

    /** 某一只的键（**唯一**一份拼法；适配器与读侧都走它，不各写一遍）。 */
    fun keyOf(device: MaintDevice): String = PREFIX + device.wire

    /** 键认回是哪一只（不是暂停键／认不出 ⇒ null）。 */
    fun deviceOfKey(key: String): MaintDevice? =
        if (!key.startsWith(PREFIX)) null else MaintDevice.ofWire(key.removePrefix(PREFIX))

    /** 停到几点（0 ＝ 没停；读不动也按没停——不冤枉它）。 */
    fun untilMs(ctx: Context, device: MaintDevice): Long = runCatching {
        prefs(ctx).getLong(keyOf(device), 0L)
    }.getOrDefault(0L)

    /** 直接写"停到几点"（0 ＝ 恢复）。写失败回 false——调用方据此发现"没停成"。 */
    fun setUntil(ctx: Context, device: MaintDevice, until: Long): Boolean = runCatching {
        prefs(ctx).edit { putLong(keyOf(device), until.coerceAtLeast(0L)) }
        true
    }.getOrDefault(false)

    /**
     * 续 [hours] 小时（还停着 ⇒ 在剩余时间上再加；已经恢复 ⇒ 从现在起算）。
     *
     * @return 停到几点（写失败 ⇒ 回旧值，调用方比一比就知道没写成）
     */
    fun pauseFor(
        ctx: Context,
        device: MaintDevice,
        hours: Long = DEFAULT_HOURS,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val until = extend(untilMs(ctx, device), nowMs, hours)
        return if (setUntil(ctx, device, until)) until else untilMs(ctx, device)
    }

    /** 续期算式（**纯**：假时钟一条就把"可续"钉住，不用碰 SP）。 */
    fun extend(currentUntilMs: Long, nowMs: Long, hours: Long = DEFAULT_HOURS): Long =
        currentUntilMs.coerceAtLeast(nowMs) + hours * HOUR_MS

    /** 还停着吗（**纯**：假时钟一条就把 24h 自动恢复钉住）。 */
    fun stillPaused(untilMs: Long, nowMs: Long): Boolean = untilMs > nowMs

    /** 「还剩 X 小时 Y 分」／「X 分钟」（落账与屏上同一种话）。 */
    fun leftLabel(leftMs: Long): String {
        val total = leftMs.coerceAtLeast(0L) / 60_000L
        val h = total / 60
        val m = total % 60
        return if (h > 0) "$h 小时 $m 分" else "$m 分钟"
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
}
