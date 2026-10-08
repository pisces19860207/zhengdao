// 独立开发声明：本文件为本项目从零编写。
// 依据的公开接口：Android 官方文档（Notification.Builder / NotificationChannel / PendingIntent）。
package com.example.zhengdao.terminal

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.zhengdao.MainActivity
import com.example.zhengdao.R

/**
 * 安装进度常驻通知（2026-10-08）。
 *
 * 为什么需要它：终端页的横幅只有**这一页在前台**时才看得见。用户当时的实际动线是
 * 「点安装 → 离开终端去设置页 → 在环境检测里反复点下载」——因为他在设置页看不到任何
 * 安装正在进行的迹象。安装是几分钟到几十分钟的事，进度必须能跨页面看见。
 *
 * 渠道用的是 `NotificationChannels.INSTALL`（"环境与安装"，IMPORTANCE_LOW 静默）：
 * 这个渠道 2026-10-06 就注册好了，但**此前没有任何代码用过**——正好拿来干这件事，不新增渠道。
 *
 * 交互：点通知进终端页（与保活通知同一个 `open_terminal` extra）；安装结束即撤下
 * （成功/失败都改发一条可划掉的短通知，让"最后一眼"留在通知栏）。
 *
 * 权限：manifest 未声明 POST_NOTIFICATIONS，targetSdk ≤ 32 ⇒ Android 13+ 默认授予
 * （依据见 SessionService 里同一条注释），因此这里不需要额外申请。
 */
object InstallNotifier {

    /** 与保活通知（NOTIF_ID=1）区分开，互不覆盖。 */
    private const val NOTIF_ID = 20261008

    private fun contentIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java)
            .putExtra("open_terminal", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun builder(ctx: Context): Notification.Builder =
        Notification.Builder(ctx, NotificationChannels.INSTALL)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentIntent(contentIntent(ctx))

    /** 进度中：常驻、不可划掉（`setOngoing(true)`）。 */
    fun update(ctx: Context, text: String, percent: Int = -1) {
        runCatching {
            val b = builder(ctx)
                .setContentTitle("证道 · 正在准备运行环境")
                .setContentText(text)
                .setOngoing(true)
            if (percent in 0..100) {
                b.setProgress(100, percent, false)
                b.setSubText("$percent%")
            } else {
                b.setProgress(0, 0, true) // 不确定进度：转圈
            }
            NotificationChannels.ensureAll(ctx)
            ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, b.build())
        }
    }

    /** 结束：撤下常驻那条，换成可划掉的结论。 */
    fun finish(ctx: Context, text: String, failed: Boolean = false) {
        runCatching {
            NotificationChannels.ensureAll(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(NOTIF_ID)
            val b = builder(ctx)
                .setContentTitle(if (failed) "证道 · 环境安装失败" else "证道 · 环境已就绪")
                .setContentText(text)
                .setAutoCancel(true)
                .setOngoing(false)
            nm.notify(NOTIF_ID, b.build())
        }
    }

    /** 撤下（用户已经在界面上看到结论、或安装被取消）。 */
    fun cancel(ctx: Context) {
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        }
    }
}
