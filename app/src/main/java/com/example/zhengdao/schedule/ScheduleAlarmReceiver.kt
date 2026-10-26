// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.zhengdao.core.RunLog

/**
 * 精确闹钟的落点（第一百六十七批 §3）。
 *
 * 这里**不发消息**：广播接收器只有约十秒窗口，而这一趟要读表、装配引擎、可能建会话。
 * 只做一件事——把进程叫醒，然后 [ScheduleScheduler.onAlarmFired] 排一趟 WorkManager 补扫，
 * 与「没给精确闹钟」那条路收敛到同一个 [ScheduleWorker]、同一趟账（`SchedulePass`）。
 *
 * 为什么仍要在 manifest 里声明它而不是 `Context.registerReceiver`：闹钟是**跨进程重启**登记的
 * PendingIntent，接收器必须是清单里的静态组件，否则设备重启后打到的闹钟会因为找不到组件而丢弃。
 * `exported="false"`：Intent 显式指向本类，没有外部 App 需要能调它。
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        runCatching { ScheduleScheduler.onAlarmFired(app) }
            .onFailure { RunLog.log("定时任务：闹钟叫醒没接住 ${it.javaClass.simpleName}: ${it.message}") }
    }
}
