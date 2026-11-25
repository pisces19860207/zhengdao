// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十四批 · 534「默认开（可关）」开关（与 625/677 同房型，独立 prefs）。
// 判据⑥：关 ⇒ 不浮、不 offer、信箱恒空 ⇒ 引擎拼出来的提示词与接线前逐字节一致。
// 记录（capture）不受开关管——那是用户主动的「记一下」，关掉只停「唤」的那一半。
package com.example.zhengdao.baopu.insight

import android.content.Context

/** 捕获与关联唤醒开关（独立 prefs；读失败按默认开——不让一次 IO 故障悄悄关掉）。 */
internal object InsightSwitch {
    const val PREFS_NAME = "baopu_insight"
    const val KEY_ENABLED = "insight_enabled"

    fun get(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    }.getOrDefault(true)

    fun set(ctx: Context, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, on)
                .apply()
        }
    }

    /** 纯逻辑重载（JVM 直测；null＝没存过 ⇒ 默认开）。 */
    fun resolve(stored: Boolean?): Boolean = stored ?: true
}
