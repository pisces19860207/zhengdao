// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零四单 · 469② 关键动作通知＋471 停机通知的**发与样式**。
//
// 口径（照 UnattendedNotifier 388 的四样现成规矩，不建第二套）：
// - 渠道只用已有的 `agent`（388 起 HIGH：关键动作要横幅）与 `session`（停机告知走 LOW，
//   它是"让你知道"不是警报——通知≠要人处理）；
// - 权限缺失只落日志不崩；前台不弹（App 正看着就不敲一下）；
// - 点按走 `zhengdao://safety` 深链直达安全台；
// - **叫停/硬停两只按钮直接钉在通知上**（471①：通知栏可达——不用进 App 也能停）。
package com.example.zhengdao.baopu.safety

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

internal object SafetyNotifier {

    /** 通知 id（与 169 的 20261101、定时 20261102/03、值守 20261104-110 都错开）。 */
    internal const val NOTIF_KEY = 20261111
    internal const val NOTIF_HALT = 20261112

    /** PendingIntent 请求码（与 4101-4114 都错开）。 */
    internal const val REQ_KEY = 4115
    internal const val REQ_HALT = 4116
    internal const val REQ_SOFT = 4117
    internal const val REQ_HARD = 4118

    /** 通知栏动作按钮的 action（SafetyNotificationReceiver 收）。 */
    internal const val ACTION_SOFT_STOP = "com.example.zhengdao.action.SAFETY_SOFT_STOP"
    internal const val ACTION_HARD_STOP = "com.example.zhengdao.action.SAFETY_HARD_STOP"

    /** 深链（通知点按直达安全台；路由在 IncomingIntentRouter，落点 SafetyActivity）。 */
    internal const val DEEP_LINK = "zhengdao://safety"

    /** 关键动作通知（469②；AGENT/HIGH 横幅；只告知不指令；开关可关、缺省开）。 */
    fun keyAction(ctx: Context?, kind: KeyActionKind, what: String) {
        if (ctx == null) return
        if (!SafetyPrefs.notifyEnabled()) return
        post(
            ctx, NOTIF_KEY, REQ_KEY, AppNotifications.AGENT,
            ctx.getString(R.string.safety_notif_key_title, kind.cn),
            ctx.getString(R.string.safety_notif_key_text, what.take(80)),
            withStopButtons = true,
        )
    }

    /** 停机通知（软停/硬停都发；SESSION/LOW——回执不是警报）。 */
    fun halted(ctx: Context?, level: HaltLevel, by: String) {
        if (ctx == null) return
        post(
            ctx, NOTIF_HALT, REQ_HALT, AppNotifications.SESSION,
            ctx.getString(R.string.safety_notif_halt_title, level.cn),
            ctx.getString(R.string.safety_notif_halt_text, by),
            withStopButtons = false,
        )
    }

    /** 通知上"叫停"按钮的 PendingIntent（SafetyNotificationReceiver 收）。 */
    internal fun stopPendingIntent(ctx: Context, action: String, req: Int): PendingIntent {
        val intent = Intent(ctx, SafetyNotificationReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            ctx, req, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 发送的唯一口：权限缺失只落日志；App 在前台就不弹（同值守通知的前台抑制口径）。
     * [withStopButtons]＝钉上"叫停/硬停"两只按钮（关键动作通知要可就地叫停）。
     */
    private fun post(
        ctx: Context,
        notifId: Int,
        req: Int,
        channel: String,
        title: String,
        text: String,
        withStopButtons: Boolean,
    ) {
        val granted = ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            RunLog.log("安全台：通知权限没给，只落账不弹（$title）")
            return
        }
        if (AppForeground.isForeground()) return
        runCatching {
            AppNotifications.ensureAll(ctx)
            val builder = Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(deepLink(ctx, req))
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
            if (withStopButtons) {
                val icon = android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_terminal)
                builder.addAction(
                    Notification.Action.Builder(
                        icon,
                        ctx.getString(R.string.safety_stop_action),
                        stopPendingIntent(ctx, ACTION_SOFT_STOP, REQ_SOFT),
                    ).build(),
                )
                builder.addAction(
                    Notification.Action.Builder(
                        icon,
                        ctx.getString(R.string.safety_hard_stop_action),
                        stopPendingIntent(ctx, ACTION_HARD_STOP, REQ_HARD),
                    ).build(),
                )
            }
            ctx.getSystemService(NotificationManager::class.java)?.notify(notifId, builder.build())
        }.onFailure {
            RunLog.log("安全台：通知没发出去 ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 点按直达：VIEW + 显式组件（与值守深链同一套路由判据）。 */
    private fun deepLink(ctx: Context, req: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, DEEP_LINK.toUri(), ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx, req, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
