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
 *
 * 2026-10-08 修复：WakeLock 有 6 小时上限（防漏释放），而 30 秒定时器原先**只刷通知**，
 * 于是会话跑满 6 小时后锁静默失效。现在定时器每轮补取（[renewWakeLockIfLost]），
 * 并在通知里显性化「保活已关」。
 *
 * ⚠️ **已知的长期问题（本次没动，需要产品决策）**：本服务是"**会话活着就一直持锁**"，
 * 而"会话活着"不等于"有活在跑"——tmux 常驻 ⇒ 只要进过一次终端，锁就一直被握着。
 * Google Play 2026-03 起把「屏幕关闭时平均持有非豁免 PARTIAL_WAKE_LOCK ≥ 2 小时、
 * 且覆盖 >5% 会话」定为**过度持锁**，超阈值会影响商店展示（见
 * android-developers.googleblog.com/2026/03/battery-technical-quality-enforcement.html）。
 * 证道不在 Play 上架，这条对"能不能上架"不构成硬约束；但它指出的问题是真的：
 * **锁应该跟着"有无活动任务"走，而不是跟着"会话是否存在"走**。
 * 拆分方式（前台服务负责"不被回收"、锁只负责"CPU 别睡"）与取舍已写在
 * `docs/milestones/证道-故障排查手册.md` 与今日报告里，改动面比本次大，故留作独立议题。
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
    /** E-054：只在**首次**成功持锁时记一行，避免把"补取成功"也刷成噪声。 */
    private var loggedWakeLockHeld = false
    /** E-054：丢锁累计次数（首次与每 10 次各记一条，见 [renewWakeLockIfLost]）。 */
    private var lostWakeLockCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private var lastRssMb = 0L

    private val monitorTick = object : Runnable {
        override fun run() {
            renewWakeLockIfLost()
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
            // E-054（2026-10-09）：这里原先是 `runCatching { acquire() }` ——**吞掉了异常**。
            // 而 AndroidManifest 里一直没声明 WAKE_LOCK ⇒ 每次 acquire() 都抛
            // SecurityException、每次都被吞，保活锁**从未真正持有**，且零日志证据
            //（唯一痕迹是下面那条每 30 秒的"补取"）。现在：失败必须落盘一句原因。
            runCatching { wakeLock.acquire(6 * 60 * 60 * 1000L) } // 上限 6 小时，防漏释放
                .onSuccess {
                    if (!loggedWakeLockHeld) {
                        loggedWakeLockHeld = true
                        RunLog.log("保活锁已获取（PARTIAL_WAKE_LOCK，上限 6 小时）")
                    }
                }
                .onFailure {
                    RunLog.log(
                        "保活锁获取失败：${it.javaClass.simpleName}: ${it.message}" +
                            "（保活退化为仅前台服务 + 通知）"
                    )
                }
        }
    }

    /**
     * **补取**保活锁——这是上面那个「6 小时上限」的必要配对（缺陷修复，2026-10-08 读码发现）。
     *
     * 原实现只在本服务创建、以及通知里的「保活开关」被点时才取锁，而 30 秒定时器**只刷新通知**。
     * 后果：会话连续跑满 6 小时后锁到期自动释放，**既没有日志也没有任何提示**，
     * 而 App 与用户都以为还在保活（表现是"看着在跑，其实手机早就允许睡眠了"）。
     * 这类"静默降级"正是本项目 E-020 一类的错误形态——不崩、不报错，只是悄悄不干活。
     *
     * 现在每轮定时器检查一次「该持有却没持有」：是则补取，**并在 RunLog 里留一条**，
     * 让"曾丢过锁"这件事可查，而不是继续静默。
     * 锁不 `setReferenceCounted`（见 [wakeLock]），重复补取不会累积。
     *
     * ⚠️ E-054 的教训：真机上这条日志曾**每 30 秒一条、连刷 12 分钟**——因为权限缺失导致
     * 补取永远失败。同一件事反复刷屏 = 用户只会当噪声看，所以现在**首次与每 10 次各记一条**
     * （第 10 次 = 5 分钟），中间的重复只累加不落盘。
     */
    private fun renewWakeLockIfLost() {
        if (!keepAlive || !SessionManager.isAlive()) return
        if (runCatching { wakeLock.isHeld }.getOrDefault(true)) return
        lostWakeLockCount++
        if (lostWakeLockCount == 1 || lostWakeLockCount % 10 == 0) {
            RunLog.log(
                "保活锁已失效，重新获取（会话仍在运行；这是第 $lostWakeLockCount 次，中间重复不再逐条记录）"
            )
        }
        acquireWakeLockIfActive()
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
        // 「保活已关」必须显性：锁没在手上时 CPU 可以睡、长任务可能被拖慢/中断，
        // 用户只有看到这一句才知道自己点掉过保活（此前这个状态在通知里完全不可见）。
        val keepText = if (keepAlive) "" else " · 保活已关"
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
            .setContentText("已运行 $runtime · 内存 $memText$warn$keepText")
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
