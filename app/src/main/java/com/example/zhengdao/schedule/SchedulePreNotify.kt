// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import android.content.Context
import androidx.core.content.edit
import com.example.zhengdao.core.AppPrefs
import com.example.zhengdao.core.RunLog

/**
 * 「定时任务开始前提醒」的开关与判据（第二百二十九批 §2）。
 *
 * 到点顺序改成：**先**发一条本地通知（「定时任务要开始了：X」），**再**把 prompt 发进会话——
 * 复用 169/ScheduleNotifier 那条既有通知栈（同一个 `AGENT` 渠道，不新建第五个渠道），
 * 通知与执行不许同时。这个开关只管**前面那声**：关掉 ⇒ 只发进会话，开始前不响
 * （到点后「已发出/失败/暂停」那些通知照旧，它们是 167/218 的既有语义，与这里无关）。
 *
 * **默认开**（派工单 §2「默认开可关」）；没有 devMode／adb／环境变量旁路。
 * 通知权限没给 ⇒ 通知发不出**不许静默**：RunLog 落一行（[ScheduleNotifier.postStarting]），
 * 且定时任务页顶部显示「通知权限未开」（UI 只读系统权限态，不落 prefs）。
 */
internal object SchedulePreNotify {

    /** 键名（进 prefs 文件 `zhengdao-settings`；默认 true——第一次读就是「提醒」）。 */
    const val KEY_PRE_NOTIFY_ON = "sched_pre_notify_on"

    /** 现读（缺省 **开**：开始前提醒是默认体验，关掉是用户显式选的）。 */
    fun enabled(ctx: Context): Boolean =
        runCatching { AppPrefs.prefs(ctx).getBoolean(KEY_PRE_NOTIFY_ON, true) }.getOrDefault(true)

    /** 拨动开关。写失败按没写处理——下次读到的还是旧值。 */
    fun set(ctx: Context, on: Boolean) {
        runCatching {
            AppPrefs.prefs(ctx).edit { putBoolean(KEY_PRE_NOTIFY_ON, on) }
        }
        RunLog.log("定时任务：开始前提醒 = $on")
    }
}

/** 开始前提醒的判据（**纯函数**，JVM 直测）：开关关 ⇒ 不通知，只发进会话。 */
internal object SchedulePreNotifyRules {

    /**
     * 到点那一刻要不要先响一声。
     * @param prefsOn 「定时任务开始前提醒」现值（生产 = [SchedulePreNotify.enabled]，默认 true）
     */
    fun shouldPreNotify(prefsOn: Boolean): Boolean = prefsOn
}
