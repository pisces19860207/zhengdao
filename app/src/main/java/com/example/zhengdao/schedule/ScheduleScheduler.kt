// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.zhengdao.core.RunLog
import java.util.concurrent.TimeUnit

/**
 * 把表里的下一次触发时刻交给系统（第一百六十七批 §3「调度：WorkManager＋精确闹钟」）。
 *
 * 两条路，一条账：
 * - **[ScheduleRules.Route.WORK_ONLY]**（没给精确闹钟，也是 Android 14+ 新装机的默认）：
 *   只排 WorkManager 的一次性作业，到点由系统按约束与省电策略挑时机 ⇒ **可能不准点**，页面明说；
 * - **[ScheduleRules.Route.EXACT_ALARM_PLUS_WORK]**（用户到系统设置单独允许了精确闹钟）：
 *   `setExactAndAllowWhileIdle` 准点打一下，WorkManager 那条仍然排、只是推迟到宽限期之后再兜底
 *   （闹钟会被设备重启、Doze 节流丢掉，兜底那条负责补）。
 *
 * 为什么兜底作业用「任务 id + 槽位」当唯一名，而不是「任务 id」：
 * 触发完成后要在**作业自己正在跑**的这一趟里排下一次；同名 `REPLACE` 会把自己取消掉。
 * 槽位不同 ⇒ 名字不同 ⇒ 排下一次不会动正在跑的这一次；同一个槽位重复排 ⇒ `REPLACE` 幂等。
 *
 * 为什么闹钟不直接发消息，而是再排一趟兜底作业：广播接收器只有约十秒窗口，
 * 而这一趟要建库、装配引擎；把活儿交给 WorkManager 的执行器，接收器只负责「把进程叫醒」。
 */
object ScheduleScheduler {

    /** 诊断用标签（`WorkManager.getWorkInfosByTag` 能一把捞出来）。 */
    internal const val TAG = "zd-schedule"

    /** 闹钟叫醒之后那趟补扫的唯一名。 */
    private const val SWEEP_NAME = "zd-sched-sweep"

    /**
     * 走精确闹钟时，兜底作业推迟多久再跑。
     * 取 [ScheduleRules.GRACE_MS]：闹钟正常打响时这一趟读到「已发过」只推进锚点；
     * 闹钟丢了时这一趟最晚在宽限期后把消息补出去。
     */
    private const val FALLBACK_AFTER_EXACT_MS = ScheduleRules.GRACE_MS

    /** 全量重排：UI 改完、触发完、页面打开都走它（判据只有一处）。 */
    fun rescheduleAll(context: Context, store: ScheduleStore, nowMs: Long) {
        val app = context.applicationContext
        val tasks = store.load().tasks
        val route = SchedulePermissions.route(app)
        for (task in tasks.filter { it.enabled && it.nextRunAtMs > ScheduleRules.NO_RUN }) {
            scheduleOne(app, task, route, nowMs)
        }
    }

    /** 排一个任务的这一次槽位（两条路都在这里汇成同一个 [ScheduleWorker]）。 */
    fun scheduleOne(context: Context, task: ScheduleTask, route: ScheduleRules.Route, nowMs: Long) {
        val name = ScheduleRules.slotName(task.id, task.nextRunAtMs, task.zone)
        val delay = (task.nextRunAtMs - nowMs).coerceAtLeast(0L) +
            if (route == ScheduleRules.Route.EXACT_ALARM_PLUS_WORK) FALLBACK_AFTER_EXACT_MS else 0L
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                name,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ScheduleWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .addTag(TAG)
                    .build(),
            )
        }.onFailure { log("兜底作业没排上 ${it.javaClass.simpleName}: ${it.message}") }

        if (route == ScheduleRules.Route.EXACT_ALARM_PLUS_WORK) {
            setExactAlarm(context, name, task)
        }
    }

    /** 撤掉一个任务当前这一槽的登记（删除/关闭时用；过去的槽位不用清，读到没这条任务就什么都不做）。 */
    fun cancelSlot(context: Context, task: ScheduleTask) {
        val name = ScheduleRules.slotName(task.id, task.nextRunAtMs, task.zone)
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(name) }
            .onFailure { log("取消作业没成 ${it.javaClass.simpleName}: ${it.message}") }
        runCatching {
            context.getSystemService(AlarmManager::class.java)
                ?.cancel(slotPendingIntent(context, name))
        }
    }

    private fun setExactAlarm(context: Context, name: String, task: ScheduleTask) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        // 权限判定在这儿过第二遍：路由是取时刻时算的，中间用户可能刚在设置里撤掉。
        // 缺权限时 setExactAndAllowWhileIdle 是**抛 SecurityException**（不是静默降级，2026-10-24 核到的），
        // 所以必须 catch 住并回落到「只靠已排上的 WorkManager 兜底」，同时把这句话说出来。
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.nextRunAtMs, slotPendingIntent(context, name)) }
            .onFailure { log("精确闹钟没打响（权限或 ROM），本次只走 WorkManager 兜底：${it.javaClass.simpleName}") }
    }

    private fun slotPendingIntent(context: Context, name: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ScheduleNotifier.REQ_SLOT_ALARM,
            // data 里带槽位名：PendingIntent 的等价性只看 filterEquals（含 data，不看 extras 的值），
            // 不同槽位必须不同 data，否则后一条会把前一条顶掉——那才是真的「只响最后一次」。
            Intent(context, ScheduleAlarmReceiver::class.java).setData("zd-sched://$name".toUri()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** 闹钟响了：把进程叫醒，活儿交给同一趟 pass（不阻塞广播接收器）。 */
    fun onAlarmFired(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                SWEEP_NAME,
                // APPEND_OR_REPLACE：正在跑就排到后面（多扫一遍是幂等的），已结束过就换新的一条。
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ScheduleWorker>().addTag(TAG).build(),
            )
        }.onFailure { log("闹钟后的补扫没排上 ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun log(line: String) = RunLog.log("定时任务：$line")
}
