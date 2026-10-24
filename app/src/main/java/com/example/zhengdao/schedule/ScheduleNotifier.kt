// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.zhengdao.AppForeground
import com.example.zhengdao.MainActivity
import com.example.zhengdao.R
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.NotificationChannels
import com.example.zhengdao.ui.baopu.BaopuIntents

/**
 * 定时任务的通知（第一百六十七批 §0.5 约束 2）。
 *
 * **复用第一百六十九批那套，不建第二套**，具体复用四样：
 * 1. 渠道：既有 [NotificationChannels.AGENT]（2026-10-06 定稿的四个渠道，不新增第五个）；
 * 2. 前台口径：`App 在前台就不弹`——与 `BaopuCompletionRules.shouldPost` 同一个判断，
 *    用户正看着 App 时不再敲一下；
 * 3. 权限口径：`POST_NOTIFICATIONS` 缺失 ⇒ 只写日志、不弹、不崩，也不影响任务本身；
 * 4. 点按回会话：`MainActivity` + `BaopuIntents.EXTRA_SESSION`，与 169 那条通知同一挂单口。
 *
 * 会不会跟 169 的完成通知打架：不会。两条通知**身份不同**——`NOTIF_ID` 各一个
 * （169 是 20261101，本批 20261102）、`PendingIntent` 请求码各一个（169 是 4106，本批 4107），
 * 触发点也不同：本批在**到点那一刻**说一声「已经发进会话了」，169 在**整轮结束**时说「跑完了」。
 * 一次定时触发最多各响一条，且都在同一渠道、同一前台抑制口径下。
 */
internal object ScheduleNotifier {

    /** 与 169 的 20261101、保活 42、安装 20261008、缓存 20261010 都区分开。 */
    private const val NOTIF_ID = 20261102

    /**
     * 点击通知的 PendingIntent 请求码。
     *
     * 必须与 4101/4102/4103/4104/4105/4106 都不同（`NotificationRequestCodeGateTest` 按源码钉这张账）。
     * 本通知只用一个固定 NOTIF_ID，复用同一个 PendingIntent 是有意的：`FLAG_UPDATE_CURRENT`
     * 让最新那个会话 id 生效，同时天然「后一条顶前一条」＝不刷屏。
     */
    internal const val REQ_SCHEDULE = 4107

    /** 排闹钟用的 PendingIntent 请求码（与上面那张通知账不同用途，也要不撞号）。 */
    internal const val REQ_SLOT_ALARM = 4201

    fun postFired(context: Context, task: ScheduleTask, sessionId: Long, late: Boolean) {
        post(
            context = context,
            titleRes = if (late) R.string.sched_notif_late_title else R.string.sched_notif_fired_title,
            titleArg = task.title,
            textRes = R.string.sched_notif_fired_text,
            sessionId = sessionId,
        )
    }

    /**
     * 没跑成的那一声（派工单 §1：失败要可读原因，不静默）。
     * 错过（[ScheduleOutcome.MISSED]）不走这里——它只标在列表里，不额外敲用户（§1「不刷屏」）。
     */
    fun postFailed(context: Context, task: ScheduleTask, outcome: ScheduleOutcome, detail: String) {
        val textRes = when (outcome) {
            ScheduleOutcome.REFUSED_BUSY -> R.string.sched_notif_reason_busy
            else -> R.string.sched_notif_reason_failed
        }
        post(
            context = context,
            titleRes = R.string.sched_notif_failed_title,
            titleArg = task.title,
            textRes = textRes,
            sessionId = -1L,
        )
        // detail 只进日志，不进通知：它是异常原文，可能带路径；通知是会被旁人看见的。
        RunLog.log("定时任务：触发失败 ${outcome.wire} $detail")
    }

    private fun post(context: Context, titleRes: Int, titleArg: String, textRes: Int, sessionId: Long) {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // 权限缺失：会话照常更新，这里只留一行日志（权限页那三行已把后果写明）。
            RunLog.log("定时任务：通知权限没给，只落会话不弹通知")
            return
        }
        if (AppForeground.isForeground()) return
        runCatching {
            NotificationChannels.ensureAll(context)
            val n = Notification.Builder(context, NotificationChannels.AGENT)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(context.getString(titleRes, titleArg))
                .setContentText(context.getString(textRes))
                .setContentIntent(contentIntent(context, sessionId))
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .build()
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
        }.onFailure {
            RunLog.log("定时任务：通知没发出去 ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun contentIntent(context: Context, sessionId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (sessionId > 0L) intent.putExtra(BaopuIntents.EXTRA_SESSION, sessionId)
        return PendingIntent.getActivity(
            context, REQ_SCHEDULE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
