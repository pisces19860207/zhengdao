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
 * 自动清理的**静默**通知（Issue #3，2026-10-10）。
 *
 * 清理是后台自己做的决定，提示必须"看得见但不吵"：渠道用既有的
 * [NotificationChannels.INSTALL]（IMPORTANCE_LOW = 无声、无振动）。2026-10-06 定稿的就是
 * 四个渠道，不为这件事新开第五个——它本来就叫"环境与安装"，缓存维护正属于这一类
 * （[InstallNotifier] 也是这么复用的）。
 *
 * 只报结果、不报过程：跳过（有人在装东西）不打扰用户，日志里有。
 * 通知可划掉、点它回主界面（缓存入口在设置页，不在主界面）。
 */
object CacheNotifier {

    /** 与保活通知（`SessionService.NOTIF_ID = 42`）、安装通知（20261008）区分开，互不覆盖。 */
    private const val NOTIF_ID = 20261010

    private fun contentIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 清完了：报释放量。 */
    fun cleaned(ctx: Context, freedMb: Long, totalMb: Long) {
        runCatching {
            NotificationChannels.ensureAll(ctx)
            val text = "释放 ${freedMb}MB（清理前 ${totalMb}MB）· 下次安装会重新下载"
            val n = Notification.Builder(ctx, NotificationChannels.INSTALL)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle("证道 · 已自动清理缓存")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(
                    "已自动清理可再下载的缓存（npm / uv / pip 缓存、Agent 包缓存、旧版安装包）。\n" +
                        "释放 ${freedMb}MB（清理前 ${totalMb}MB）。\n" +
                        "不会动 Agent 工具链、rootfs 系统层与你的数据；需要时可到「设置 → 缓存清理」手动再清。"
                ))
                .setContentIntent(contentIntent(ctx))
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
        }
    }

    /** 撤下（用户已经看到、或本次没清）。 */
    fun cancel(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
    }
}
