// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.zhengdao.core.RunLog

/**
 * 精确闹钟的落点（第三百九十九批）——167 的 ScheduleAlarmReceiver 同款户型：
 * 这里**不发消息**（广播接收器只有约十秒窗口），只把进程叫醒、排一趟补扫
 * （[TriggerScheduler.onAlarmFired]），与「没给精确闹钟」那条路收敛到同一个
 * [TriggerWorker]、同一趟账。
 *
 * 必须是 manifest 里的静态组件：闹钟是跨进程重启登记的 PendingIntent，
 * 动态注册的接收器在进程重启后找不到组件，闹钟会被直接丢弃。`exported="false"`。
 */
class TriggerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        runCatching { TriggerScheduler.onAlarmFired(app) }
            .onFailure { RunLog.log("事件触发：闹钟叫醒没接住 ${it.javaClass.simpleName}: ${it.message}") }
    }
}
