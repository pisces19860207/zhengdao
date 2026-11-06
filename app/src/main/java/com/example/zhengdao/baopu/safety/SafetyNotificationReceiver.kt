// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零四单 · 通知栏"叫停/硬停"按钮的落点（471①：任何时刻可叫停——通知栏可达）。
// exported=false：只收本 App 自己的 PendingIntent（显式指向本类），外部没有入口。
package com.example.zhengdao.baopu.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.zhengdao.core.RunLog

class SafetyNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SafetyNotifier.ACTION_SOFT_STOP -> {
                RunLog.log("安全台：通知栏点了叫停（软停）")
                SafetyWiring.softStop(by = "人工（通知栏）")
            }
            SafetyNotifier.ACTION_HARD_STOP -> {
                RunLog.log("安全台：通知栏点了硬停")
                SafetyWiring.hardStop(by = "人工（通知栏）")
            }
            else -> Unit
        }
    }
}
