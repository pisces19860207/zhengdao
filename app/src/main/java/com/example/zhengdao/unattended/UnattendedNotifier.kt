// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十八单 · **四类通知**（388 派工单：高优先/普通两档）。
// 渠道归属按用户 2026-11-03 拍板（原话四行）：
//   · 红线停手 → [AppNotifications.AGENT]（388 起升 HIGH：横幅/响铃）；
//   · 圈外入队 → [AppNotifications.AGENT]（HIGH，可稍后）；
//   · 降级停手 → [AppNotifications.SESSION]（LOW，不打扰）；
//   · 离场报告 → [AppNotifications.SESSION]（LOW）。
//   环境暂停（派工单明说"普通通知"）与降级同档 → SESSION（LOW）。
// **不新增第五个渠道**；AGENT 从 DEFAULT 升 HIGH 的升级路径与代价见 AppNotifications 文件头。
//
// 复用 167/169 批通知的四样现成口径，不建第二套：权限缺失只落日志不崩；
// 前台不弹（App 正看着就不敲一下）；NOTIF_ID／请求码各一个（RequestCode 账不撞号，
// 与 4101-4107、4201 都错开）；点按走 `zhengdao://unattended` 深链直达值守页。
package com.example.zhengdao.unattended

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.zhengdao.AppForeground
import com.example.zhengdao.MainActivity
import com.example.zhengdao.R
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.notify.AppNotifications

internal object UnattendedNotifier {

    /** 通知 id 六连（与 169 的 20261101、定时 20261102/03、保活 42、安装 20261008、缓存 20261010 都错开）。 */
    internal const val NOTIF_FGS = 20261104
    internal const val NOTIF_STOP = 20261105
    internal const val NOTIF_QUEUE = 20261106
    internal const val NOTIF_PAUSE = 20261107
    internal const val NOTIF_REPORT = 20261108
    internal const val NOTIF_DEGRADE = 20261109

    /** PendingIntent 请求码六连（与 4101-4107、4201 都不同——RequestCode 账按源码钉）。 */
    internal const val REQ_FGS = 4108
    internal const val REQ_STOP = 4109
    internal const val REQ_QUEUE = 4110
    internal const val REQ_PAUSE = 4111
    internal const val REQ_REPORT = 4112
    internal const val REQ_DEGRADE = 4113

    /** 深链（通知点击直达值守页；路由在 IncomingIntentRouter，落点 UnattendedActivity）。 */
    internal const val DEEP_LINK = "zhengdao://unattended"

    /** 红线停手（高优先，AGENT/HIGH）。 */
    fun stopFired(ctx: Context, headline: String) = post(
        ctx, NOTIF_STOP, REQ_STOP, AppNotifications.AGENT,
        ctx.getString(R.string.unattended_notif_stop_title),
        ctx.getString(R.string.unattended_notif_stop_text, headline.take(30)),
    )

    /** 圈外入队（高优先，AGENT/HIGH；同一 id 就地更新，不刷屏）。 */
    fun queued(ctx: Context, latestHeadline: String) = post(
        ctx, NOTIF_QUEUE, REQ_QUEUE, AppNotifications.AGENT,
        ctx.getString(R.string.unattended_notif_queue_title),
        ctx.getString(R.string.unattended_notif_queue_text, latestHeadline.take(30)),
    )

    /** 环境暂停（普通，SESSION/LOW）。 */
    fun envPaused(ctx: Context, reasonsCn: String) = post(
        ctx, NOTIF_PAUSE, REQ_PAUSE, AppNotifications.SESSION,
        ctx.getString(R.string.unattended_notif_pause_title),
        ctx.getString(R.string.unattended_notif_pause_text, reasonsCn),
    )

    /** 环境复工（普通，SESSION/LOW；把暂停那条顶掉，同一 id）。 */
    fun envResumed(ctx: Context) = post(
        ctx, NOTIF_PAUSE, REQ_PAUSE, AppNotifications.SESSION,
        ctx.getString(R.string.unattended_notif_resume_title),
        ctx.getString(R.string.unattended_notif_resume_text),
    )

    /** 降级停手（普通，SESSION/LOW；B级汇总 2h 没出的那声，与红线停手同题不同账）。 */
    fun degraded(ctx: Context) = post(
        ctx, NOTIF_DEGRADE, REQ_DEGRADE, AppNotifications.SESSION,
        ctx.getString(R.string.unattended_notif_degrade_title),
        ctx.getString(R.string.unattended_notif_degrade_text),
    )

    /** 离场报告就绪（普通，SESSION/LOW；点开直达报告）。 */
    fun reportReady(ctx: Context) = post(
        ctx, NOTIF_REPORT, REQ_REPORT, AppNotifications.SESSION,
        ctx.getString(R.string.unattended_notif_report_title),
        ctx.getString(R.string.unattended_notif_report_text),
    )

    /** 前台服务常驻那条（SESSION/LOW，ongoing——划掉=值守失守，同保活口径）。 */
    internal fun ongoing(ctx: Context, text: String): Notification {
        AppNotifications.ensureAll(ctx)
        return Notification.Builder(ctx, AppNotifications.SESSION)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(ctx.getString(R.string.unattended_notif_fgs_title))
            .setContentText(text)
            .setContentIntent(deepLink(ctx, REQ_FGS))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * 发送的唯一口：权限缺失只落日志（167 批口径，不崩不静默丢——账上可查）；
     * App 在前台就不弹（同 ScheduleNotifier／BaopuCompletionRules 的前台抑制口径）。
     */
    private fun post(ctx: Context, notifId: Int, req: Int, channel: String, title: String, text: String) {
        val granted = ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            RunLog.log("离场值守：通知权限没给，只落账不弹（$title）")
            return
        }
        if (AppForeground.isForeground()) return
        runCatching {
            AppNotifications.ensureAll(ctx)
            val n = Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(deepLink(ctx, req))
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(notifId, n)
        }.onFailure {
            RunLog.log("离场值守：通知没发出去 ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 点按直达：VIEW + 显式组件（过同一套路由判据；token 按 Intent 对象身份，连点不吞）。 */
    private fun deepLink(ctx: Context, req: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, DEEP_LINK.toUri(), ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx, req, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
