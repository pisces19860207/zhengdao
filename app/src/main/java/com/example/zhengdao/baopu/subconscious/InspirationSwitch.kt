// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十七批 · 534 灵感闪回「默认开（可关）」（统一口径①，2026-11-12）。
// 房型照 ImpactPrefs／AskHabitSwitch：独立 prefs 文件、读失败按默认开处理
// （宁可多浮一行，也不让一次 IO 故障把功能悄悄关掉）、纯逻辑重载 JVM 直测。
package com.example.zhengdao.baopu.subconscious

import android.content.Context
import androidx.core.content.edit

/** 灵感闪回的开关（关掉 ⇒ 只停「浮出」这一半；记录灵感不受它管——那是用户主动的行为）。 */
internal object InspirationSwitch {

    /** 开关所在的 prefs 文件（与其它抱朴设置同房型，独立文件避免互踩）。 */
    const val PREFS_NAME = "baopu_inspiration"

    /** 键名（只此一处，设置页与唤醒口共用）。 */
    const val KEY_ENABLED = "inspiration_enabled"

    /** 读（失败按默认开）。 */
    fun get(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    }.getOrDefault(true)

    /** 写入口（设置页唯一调用点）。写失败按没写处理：下次读到的还是旧值。 */
    fun set(ctx: Context, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_ENABLED, on) }
        }
    }

    /** 纯逻辑重载（JVM 测试用：不碰 Android；null＝没存过 ⇒ 默认开）。 */
    fun resolve(stored: Boolean?): Boolean = stored ?: true
}
