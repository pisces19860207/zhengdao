// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零四单 · 469② 的开关：关键动作通知——**可关，缺省开**（派工单原话）。
// 存在 `zhengdao-safety` 同一份 SP（与停机闸同一用途一个文件）；[changeTick] 照
// UnattendedStatus 的样板给 Compose 用。
package com.example.zhengdao.baopu.safety

import androidx.core.content.edit

object SafetyPrefs {

    private const val KEY_NOTIFY = "key_action_notify_enabled"

    /** 偏好变更信号（Compose 可观察）。 */
    internal val changeTick = androidx.compose.runtime.mutableIntStateOf(0)

    @Volatile
    private var appCtxRef: java.lang.ref.WeakReference<android.content.Context>? = null

    /** 装配口（ZhengdaoApp onCreate 调一次；与 HaltGate.install 并排）。 */
    fun install(ctx: android.content.Context) {
        appCtxRef = java.lang.ref.WeakReference(ctx.applicationContext)
    }

    private fun prefs() =
        appCtxRef?.get()?.getSharedPreferences("zhengdao-safety", android.content.Context.MODE_PRIVATE)

    /** 关键动作通知开着吗（缺省开——派工单"可关，缺省开"）。 */
    fun notifyEnabled(): Boolean = runCatching {
        prefs()?.getBoolean(KEY_NOTIFY, true)
    }.getOrNull() ?: true

    fun setNotifyEnabled(value: Boolean) {
        runCatching { prefs()?.edit { putBoolean(KEY_NOTIFY, value) } }
        changeTick.intValue++
    }
}
