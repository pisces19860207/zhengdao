// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.schedule.SchedulePermissions
import com.example.zhengdao.schedule.ScheduleRules
import java.util.concurrent.TimeUnit

/**
 * 把锚点交给系统（第三百九十九批）——房型照 167 的 `ScheduleScheduler`：两条路一条账。
 *
 * - 没给精确闹钟（Android 14+ 新装机默认）：只排 WorkManager 一次性作业，到点可能推迟；
 * - 给了精确闹钟：`setExactAndAllowWhileIdle` 准点打一下，WorkManager 推迟一个宽限期兜底
 *   （闹钟会被重启/Doze 丢，兜底那条负责补）。
 *
 * 为什么只排**最早的锚点**、且作业名里带锚点值：一趟 pass 会把到点的一起判掉（一趟一条地发），
 * 发完 [TriggerRunner.refreshAnchors] 会把下一次重排回来——逐规则排 N 个闹钟没有收益。
 * 名字必须带锚点（167「槽位不同 ⇒ 名字不同」的同一条教训）：pass 跑完在本趟**正在执行的作业里**
 * 重排下一次，固定名字的 `REPLACE` 会把正在跑的这趟取消掉；名字不同 ⇒ 互不相碰，旧锚点的
 * 作业到点空扫一趟（幂等，读到没到点的东西就收工）自然结束。
 *
 * 闹钟响起的补扫用另一个独立名（`APPEND_OR_REPLACE`）：它不知道锚点值，也不该跟兜底作业
 * 互相顶掉——两趟真撞在一起也无妨，[TriggerRunner] 整趟串行、去重键保证同一事件只发一遍。
 */
object TriggerScheduler {

    /** 诊断用标签。 */
    internal const val TAG = "zd-trigger"

    /** 闹钟叫醒之后那趟补扫的唯一名。 */
    private const val ALARM_SWEEP_NAME = "zd-trig-alarm-sweep"

    /** 上一次排出去的锚点（同值不重排——设置页开关反复拨不攒一堆待跑作业）。 */
    private var lastScheduledWakeAtMs = 0L

    /** 全量重排（判据只有一处：触发完/建删规则/开关拨动都走这里）。 */
    fun rescheduleAll(context: Context, store: TriggerStore, nowMs: Long) {
        val earliest = store.load()
            .filter { it.enabled && it.nextWakeAtMs > TriggerRules.NO_WAKE }
            .minOfOrNull { it.nextWakeAtMs } ?: return
        if (earliest == lastScheduledWakeAtMs) return
        lastScheduledWakeAtMs = earliest
        val route = SchedulePermissions.route(context)
        val delay = (earliest - nowMs).coerceAtLeast(0L) +
            if (route == ScheduleRules.Route.EXACT_ALARM_PLUS_WORK) TriggerRules.GRACE_MS else 0L
        val name = wakeName(earliest)
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                name,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TriggerWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .addTag(TAG)
                    .build(),
            )
        }.onFailure { RunLog.log("事件触发：兜底作业没排上 ${it.javaClass.simpleName}: ${it.message}") }
        if (route == ScheduleRules.Route.EXACT_ALARM_PLUS_WORK) setExactAlarm(context, earliest)
    }

    private fun setExactAlarm(context: Context, wakeAtMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        // 缺权限时 setExactAndAllowWhileIdle 抛 SecurityException（不静默降级），必须 catch 回 WorkManager。
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeAtMs, sweepPendingIntent(context, wakeAtMs)) }
            .onFailure { RunLog.log("事件触发：精确闹钟没打响（权限或 ROM），本次只走 WorkManager 兜底：${it.javaClass.simpleName}") }
    }

    private fun sweepPendingIntent(context: Context, wakeAtMs: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_SWEEP_ALARM,
            // data 带锚点：PendingIntent 等价性只看 filterEquals（含 data），不同锚点的闹钟互不顶掉。
            Intent(context, TriggerAlarmReceiver::class.java).setData("zd-trig://wake/$wakeAtMs".toUri()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** 闹钟响了：把进程叫醒，活儿交给同一趟 pass（不阻塞广播接收器——167 同款）。 */
    fun onAlarmFired(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ALARM_SWEEP_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<TriggerWorker>().addTag(TAG).build(),
            )
        }.onFailure { RunLog.log("事件触发：闹钟后的补扫没排上 ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun wakeName(wakeAtMs: Long): String = "zd-trig-wake-$wakeAtMs"

    /** 请求码（与 167 的 REQ_SLOT_ALARM 段、430 的检查段都错开）。 */
    private const val REQ_SWEEP_ALARM = 4301
}
