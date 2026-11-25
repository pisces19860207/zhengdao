// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十五批 · 静默回想开关（默认开、可关；房型照 677 InspirationSwitch）。
// 关掉 ⇒ 连算都不算：refreshSilentRecall 第一行就返回，零 publish、零落账、零附注，
// 会话提示词与没有本功能时逐字节一致。读失败按开——静默一行不算打扰。
package com.example.zhengdao.baopu.subconscious

import android.content.Context
import androidx.core.content.edit

/** 静默回想开关（只管「浮出与放行」，不管用户手动点开的展示）。 */
internal object SilentRecallSwitch {

    /** 独立 prefs 文件（不与他人互踩）。 */
    const val PREFS_NAME = "baopu_silent_recall"

    /** 键名（设置页与唤醒口共用这一处）。 */
    const val KEY_ENABLED = "silent_recall_enabled"

    fun get(ctx: Context): Boolean = runCatching {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    }.getOrDefault(true)

    fun set(ctx: Context, on: Boolean) {
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_ENABLED, on) }
        }
    }

    /** 纯逻辑重载（JVM 直测；null ＝ 没存过 ⇒ 默认开）。 */
    fun resolve(stored: Boolean?): Boolean = stored ?: true
}
