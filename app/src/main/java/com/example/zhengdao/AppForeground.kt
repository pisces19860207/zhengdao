// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 进程级「App 是否在前台」判定（第一百六十九批 §3）。
 *
 * 为什么不用别的：本仓没有 lifecycle-process 依赖，也不想为一条通知引一个新库；
 * `ActivityLifecycleCallbacks` 是系统自带、覆盖所有 Activity（主页/终端/设置）。
 *
 * 口径：**任一** Activity 处于 started（可见）即算前台——包括终端页与设置页；
 * 只有全部 stopped（按 Home / 切到别的 App / 锁屏）才叫后台。
 * 用 started 而不是 resumed：转场瞬间 resumed 会短暂为 0，会让「用户明明在看」的通知漏发。
 */
internal object AppForeground : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var foreground = false

    /** 计数器只在主线程（Activity 回调）改；读侧是 volatile 布尔，供引擎线程判断。 */
    private var started = 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    fun isForeground(): Boolean = foreground

    override fun onActivityStarted(activity: Activity) {
        started++
        foreground = started > 0
    }

    override fun onActivityStopped(activity: Activity) {
        if (started > 0) started--
        foreground = started > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
