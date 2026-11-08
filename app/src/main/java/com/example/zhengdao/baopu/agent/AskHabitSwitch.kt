// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十九批 · 「按习惯免问」开关（派工单 §一⑤：一个开关，默认开）。
//
// 写口只有设置页那一行开关；读口被装配层注入 ProfileIntakeWiring（每次弹卡前现读，
// 关掉即回 null 行为——不是「不问」，是连画像读数都不查）。SP 读失败按默认开处理。
package com.example.zhengdao.baopu.agent

import android.content.Context
import androidx.core.content.edit

/** 「按习惯免问」的 SP 开关（房型照 ProdEvalSettings：读写各一口，无旁路）。 */
internal object AskHabitSwitch {

    private const val PREFS_NAME = "baopu_ask_habit"
    private const val KEY = "habit_no_ask_on"

    /** 现读（默认开；读失败也按开——与「没拨过」同一待遇）。 */
    fun on(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }.getOrDefault(true)

    /** 拨动（设置页唯一写口；写失败按没写处理，下次读到的还是旧值）。 */
    fun set(ctx: Context, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY, on) }
        }
    }
}