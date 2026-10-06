// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.example.zhengdao.MainActivity
import com.example.zhengdao.R
import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * M2 前台服务 = 保活锚点（骨架 §1，单会话简化）。
 * 职责：会话存活期间持有前台通知 + WakeLock（可暂停），30 秒一次软监控 guest RSS
 * （骨架 §6：超限只警告不杀——硬上限已否决，见 ProotLauncher 注释）。
 * 通知必须「有用」：运行时长 + 内存读数 + 三个动作，降低被用户关闭的概率。
 */
class SessionService : Service() {

    companion object {
        // P6 渠道注册表在 NotificationChannels；此 ID 是历史发布值（换 ID 会重置用户通知设置）
        private const val CHANNEL_ID = NotificationChannels.SESSION
        private const val NOTIF_ID = 42
        const val ACTION_TOGGLE_KEEPALIVE = "zhengdao.toggle_keepalive"
        const val ACTION_STOP_SESSION = "zhengdao.stop_session"
        /** RSS 警告阈值（MB）：超过则在通知栏明示（骨架 §6 软监控，不主动杀） */
        private const val RSS_WARN_MB = 3072L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionService::class.java))
        }
    }

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zhengdao:session")
            .apply { setReferenceCounted(false) }
    }

    private var keepAlive = true
    private val handler = Handler(Looper.getMainLooper())
    private var lastRssMb = 0L

    private val monitorTick = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // targetSdk 28 ≤ 32：Android 13+ 上 POST_NOTIFICATIONS 对旧 target 应用默认授予，
        // 无需运行时请求；用户手动关闭通知时 FGS 仍存活但通知不可见（降级体验，可接受）
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLockIfActive()
        handler.postDelayed(monitorTick, 30_000L)
        RunLog.log("前台服务已启动（保活锚点）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_KEEPALIVE -> {
                keepAlive = !keepAlive
                if (keepAlive) acquireWakeLockIfActive() else releaseWakeLock()
                updateNotification()
            }
            ACTION_STOP_SESSION -> {
                SessionManager.kill(this)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(monitorTick)
        releaseWakeLock()
        RunLog.log("前台服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLockIfActive() {
        if (keepAlive && SessionManager.isAlive()) {
            runCatching { wakeLock.acquire(6 * 60 * 60 * 1000L) } // 上限 6 小时，防漏释放
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
    }

    private fun createNotificationChannel() {
        // P6：4 渠道集中注册（本服务用 SESSION 渠道；其余渠道供安装/Agent/更新使用）
        NotificationChannels.ensureAll(this)
    }

    /** 读 /proc/<pid>/status 的 VmRSS（软监控，骨架 §6；读不到返回 0，静默）。 */
    private fun readRssMb(pid: Int): Long = try {
        File("/proc/$pid/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.div(1024) ?: 0L
    } catch (_: Throwable) {
        0L
    }

    private fun elapsedText(): String {
        val started = SessionManager.startedAtMs
        if (started <= 0) return ""
        val min = (System.currentTimeMillis() - started) / 60000
        return if (min < 60) "${min} 分钟" else "${min / 60} 小时 ${min % 60} 分"
    }

    private fun buildNotification(): Notification {
        val s = SessionManager.session
        val rss = s?.let { readRssMb(it.pid) } ?: 0L
        lastRssMb = rss
        val runtime = elapsedText()
        val memText = if (rss > 0) "$rss MB" else "统计中"
        val warn = if (rss > RSS_WARN_MB) " · ⚠️ 内存偏高，建议回到终端清理" else ""
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra("open_terminal", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP_SESSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val keepIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SessionService::class.java).setAction(ACTION_TOGGLE_KEEPALIVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("证道 · 会话运行中")
            .setContentText("已运行 $runtime · 内存 $memText$warn")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "停止会话", stopIntent)
            .addAction(0, if (keepAlive) "暂停保活" else "保持运行", keepIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }
}
