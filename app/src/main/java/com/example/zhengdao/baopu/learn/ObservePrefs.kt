// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 学习板块阶段一（A 观察＋E 提问＋F 未学会清单）· 观察开关。
//
// 派工单判据①：观察**默认开（可关）**；关后 0 记录。房型照 AskHabitSwitch：
// 一个 SP 一个键，读写各一口，无第三态、无旁路；读失败按默认开处理
// （「没拨过」与「读不动」同一待遇，不静默降级成关）。
package com.example.zhengdao.baopu.learn

import android.content.Context
import androidx.core.content.edit

/** 「玄览观察」的 SP 开关（派工单总则③：默认保守——默认开但只记结构，≤3 问，恒一颗）。 */
internal object ObservePrefs {

    private const val PREFS_NAME = "zhengdao-observe"
    private const val KEY = "observe_enabled"

    /** 现读（默认开；读失败也按开）。 */
    fun on(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }.getOrDefault(true)

    /** 拨动（学习板块页唯一写口；写失败按没写处理，下次读到的还是旧值）。 */
    fun set(ctx: Context, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY, on) }
        }
    }
}
