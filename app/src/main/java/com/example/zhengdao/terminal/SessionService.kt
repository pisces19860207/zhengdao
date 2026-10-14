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
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import com.example.zhengdao.MainActivity
import com.example.zhengdao.R
import com.example.zhengdao.core.IssueCenter
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
 * 2026-10-13（用户拍板）上面的长期问题落地——**锁改为跟「终端有无活动」走**：
 * - 30 秒一轮汇总本 App UID 全部进程的 CPU jiffies（[GuestCpuCounter]）：agent 在跑时
 *   jiffies 持续增长，tmux 空转时不再增长；
 * - 连续 [WakeLockIdlePolicy.DEFAULT_IDLE_TICKS] 轮（10 分钟）无增长 ⇒ 释放 WakeLock
 *   （[WakeLockIdlePolicy]）。前台服务、通知、tmux 会话一律不动——只是允许系统睡眠；
 * - 检测到 CPU 回升立即补取（[IdleDecision.ACQUIRE]），通知文案随之在
 *   「会话运行中」⇄「空闲休眠」之间切换；
 * - 释放期间暂停调用 [renewWakeLockIfLost]：否则刚释放的锁会被下一轮「补取」原样取回
 *   （该函数的 `!SessionManager.isAlive()` 守卫一行未动，只是由调用方决定要不要调它）。
 *
 * **判据刻意偏保守（用户原话「宁可多持一会儿锁，也不要误判有任务在跑为空闲」）**：
 * 读不到 /proc、首轮无基线、计数倒退，一律按"有活动"处理；微量增长（低于单轮阈值）
 * 还有窗口累计预算兜底——慢速下载这类 I/O 型任务每轮只涨一点，累计超预算同样算活动。
 *
 * Google Play 2026-03 起把「屏幕关闭时平均持有非豁免 PARTIAL_WAKE_LOCK ≥ 2 小时、
 * 且覆盖 >5% 会话」定为**过度持锁**，超阈值会影响商店展示（见
 * android-developers.googleblog.com/2026/03/battery-technical-quality-enforcement.html）。
 * 证道不在 Play 上架，这条对"能不能上架"不构成硬约束；但它指出的问题是真的：
 * **锁应该跟着"有无活动任务"走，而不是跟着"会话是否存在"走**——本次改动即为此落地。
 */
class SessionService : Service() {

    companion object {
        // P6 渠道注册表在 NotificationChannels；此 ID 是历史发布值（换 ID 会重置用户通知设置）
        private const val CHANNEL_ID = NotificationChannels.SESSION
        private const val NOTIF_ID = 42
        const val ACTION_TOGGLE_KEEPALIVE = "zhengdao.toggle_keepalive"
        const val ACTION_STOP_SESSION = "zhengdao.stop_session"
        /**
         * 通知栏口径的 RSS 警告阈值（MB）：超过则在通知里明示（骨架 §6 软监控，不主动杀）。
         * 只量**会话 shell 这一个进程**，而它下面挂着 agent（node/python/tmux），正常就能上 GB
         * ⇒ 比体检卡那个阈值高得多。两处阈值**故意不同**，集中登记在 [RssThresholds]。
         */
        private const val NOTIFY_RSS_WARN_MB = RssThresholds.NOTIFY_MB

        /**
         * 前台服务是否在跑（#5 留档，2026-10-09）。
         *
         * 为什么不用 `ActivityManager.getRunningServices()`：那个 API 对第三方应用早已"只看得见自己"，
         * 而且要拼一堆服务名判定；这里服务自己就是权威。心跳/崩溃留档把它记进去，
         * 「被杀时保活锚点还在不在」这条就变成可查的事实，而不是事后猜。
         */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * 拉起前台服务（保活锚点）。**#10 起不外抛**：`startForegroundService` 会抛
         * （后台启动限制、服务被禁用/被系统拒），而这时会话已经建好、只差"点火"——
         * 静默失败的表现就是"进了终端却什么都没发生，日志里也查不到"。失败必须同时
         * 落日志与「最近问题」。
         */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, SessionService::class.java)) }
                .onFailure {
                    fail(it, "后台保活没起来", "前台服务启动失败（startForegroundService 抛异常）")
                }
        }

        /** 停前台服务（#10 起不外抛；失败同样留痕）。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SessionService::class.java)) }
                .onFailure {
                    fail(it, "前台服务没停干净", "前台服务停止失败（stopService 抛异常）")
                }
        }

        /**
         * 失败留痕（#10）：日志 + 「最近问题」。
         * 这两条路以前是全静默的——服务没起来，用户侧只表现为"保活失效"。
         */
        private fun fail(t: Throwable, title: String, what: String) {
            val why = t.javaClass.simpleName + (t.message?.let { "：$it" } ?: "")
            RunLog.log("$what：$why")
            IssueCenter.report(
                id = "session-service",
                title = title,
                detail = "$what（$why）。会话本身仍可用，但切到后台更容易被系统回收。",
                actionLabel = "看日志",
                actionId = IssueCenter.ACTION_VIEW_LOGS,
            )
        }
    }

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zhengdao:session")
            .apply { setReferenceCounted(false) }
    }

    /** 保活开关：主线程写（设置里拨动）、巡检线程读（D-3 挪线程后跨线程）。 */
    @Volatile
    private var keepAlive = true
    /** E-054：只在**首次**成功持锁时记一行，避免把"补取成功"也刷成噪声。 */
    private var loggedWakeLockHeld = false
    /** E-054：丢锁累计次数（首次与每 10 次各记一条，见 [renewWakeLockIfLost]）。 */
    private var lostWakeLockCount = 0
    // D-3（第⑪批 B-1）：30 秒巡检挪到专用 HandlerThread。
    // 为什么：monitorTick 每轮经 applyIdlePolicy → GuestCpuCounter.read 逐 pid 读
    // /proc/<pid>/stat 与 status（几十项小读），原先跑在主线程 Handler 上——
    // 30 秒一次的固定卡顿源。挪到后台线程后：WakeLock 的 acquire/release/isHeld
    // 与 NotificationManager.notify 都是线程安全的；keepAlive 与 idlePolicy 的
    // 跨线程访问见各字段上的注记。**30 秒节奏（TICK_MS）与判定逻辑一行未动**。
    private val monitorThread = HandlerThread("zd-session-monitor").apply { isDaemon = true; start() }
    private val handler = Handler(monitorThread.looper)
    private var lastRssMb = 0L

    /** 空闲检测策略（纯逻辑，JVM 可测；判据与阈值见 [WakeLockIdlePolicy] 的 KDoc）。 */
    private val idlePolicy = WakeLockIdlePolicy()

    private val monitorTick = object : Runnable {
        override fun run() {
            val decision = applyIdlePolicy()
            // 补取只该发生在"维持现状"这一轮（①：ACQUIRE 分支已经补过，再来一遍只是让
            // isHeld 守卫空转；RELEASE/刻意空闲时补取更会把刚放的锁原样取回）。
            // （renewWakeLockIfLost 的 !keepAlive/!isAlive 守卫原样保留、一行未动；
            //   这里只是调用方决定"要不要调它"。6h 上限到期+CPU 活动的丢锁场景由 NONE
            //   轮的补取覆盖，与旧行为等价。）
            if (decision == IdleDecision.NONE && !idlePolicy.isDeliberatelyIdle) renewWakeLockIfLost()
            updateNotification()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createNotificationChannel()
        // targetSdk 28 ≤ 32：Android 13+ 上 POST_NOTIFICATIONS 对旧 target 应用默认授予，
        // 无需运行时请求；用户手动关闭通知时 FGS 仍存活但通知不可见（降级体验，可接受）
        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLockIfActive()
        handler.postDelayed(monitorTick, 30_000L)
        RunLog.log("前台服务已启动（保活锚点）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 🟠-1（Qoder 2026-10-13，采用方案 B：识别"系统重建"这条回调，语义最精确）
        //
        // START_STICKY 的意义是：本服务进程被系统回收后，服务被重建、保活继续 —— 这是刻意设计，
        // 不能改成全局 START_NOT_STICKY。但重建这一次的 intent 是 **null**，而"有没有会话"是
        // **进程内**状态（SessionManager.session）：新进程里必然没有会话，onCreate 那句无条件的
        // startForeground 却照样组装出「证道 · 会话运行中」，30 秒一次的 monitorTick 只会原样
        // 重发同一张，**没有任何一轮会自愈** ⇒ 通知栏挂着一条"任务还在跑"的假消息（纯显示谎言：
        // acquireWakeLockIfActive 有 isAlive 守卫，不会误持锁，见 Qoder 报告的缓和因素）。
        //
        // 这里只掐断"重建且确实没有会话"这一条路：自愈式 stopSelf 后返回 START_NOT_STICKY，
        // 不再让它被再次重建；其余所有路径（正常首次启动 intent 非 null、ACTION_TOGGLE_KEEPALIVE、
        // ACTION_STOP_SESSION）一律保持 START_STICKY，既有的保活设计不动。
        //
        // 刻意不把守卫挪进 onCreate 去"干脆不装前台"（报告里的方案 A）：前台服务被
        // startForegroundService 拉起后必须在 5 秒内调用 startForeground，
        // 在 onCreate 里条件跳过会把这条契约变成"看时机"，而方案 B 里 onCreate 照常调用、
        // 只是随后 stopSelf —— 假通知的存活窗口是毫秒级，契约也始终满足。
        if (intent == null && !SessionManager.isAlive()) {
            RunLog.log("本服务被系统重建，但进程内已无会话：按僵尸通知处理，停止服务（Qoder 🟠-1）")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE_KEEPALIVE -> {
                keepAlive = !keepAlive
                // 空闲策略重新基线（2026-10-13）：重新打开保活后第一轮按"活动"处理（保守），
                // 避免上一轮残留的空闲计数把用户刚点开的锁又立刻放掉
                idlePolicy.reset()
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
        monitorThread.quitSafely()
        releaseWakeLock()
        running = false
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
     *
     * 2026-10-13 起本函数**只在该持锁时被调用**（monitorTick 里被 `isDeliberatelyIdle` 门控）：
     * 空闲释放期间调用它会把刚释放的锁原样补回，等于白放。函数本体一行未动。
     */
    private fun renewWakeLockIfLost() {
        if (!keepAlive || !SessionManager.isAlive()) return
        // P2-16（issue #17）：读不到持有状态时**保守地当"没持有"**（兜底值取 false）。
        // 原写法兜底取 true 是乐观默认——isHeld 抛异常就假装持锁、直接 return，
        // 结果是锁真丢了也不再补（保活静默失效）。补取本身幂等（锁不 setReferenceCounted，
        // 见 [wakeLock]），重复 acquire 不会累积，所以"多补一次"远比"漏补"安全。
        if (runCatching { wakeLock.isHeld }.getOrDefault(false)) return
        lostWakeLockCount++
        if (lostWakeLockCount == 1 || lostWakeLockCount % 10 == 0) {
            RunLog.log(
                "保活锁已失效，重新获取（会话仍在运行；这是第 $lostWakeLockCount 次，中间重复不再逐条记录）"
            )
        }
        acquireWakeLockIfActive()
    }

    /**
     * 空闲检测（2026-10-13）：每轮 tick 把「本 App 全部进程的 CPU jiffies 读数」喂给策略，
     * 按决策补取/释放锁。会话不在或保活被关时策略退出判定（回到既有守卫行为）。
     * @return 本轮的决策（monitorTick 据此决定是否还需要走一次补取）
     */
    private fun applyIdlePolicy(): IdleDecision {
        if (!keepAlive || !SessionManager.isAlive()) {
            idlePolicy.reset()
            return IdleDecision.NONE
        }
        val cpu = GuestCpuCounter.read(
            myUid = android.os.Process.myUid(),
            myPid = android.os.Process.myPid(),
        )
        // P2-16（issue #17）：同 renewWakeLockIfLost——isHeld 读不到时保守当"没持有"，
        // 让策略走 ACQUIRE 补取（幂等），而不是兜底 true 让补取门控永远跳过。
        val held = runCatching { wakeLock.isHeld }.getOrDefault(false)
        return when (val decision = idlePolicy.onTick(cpu, held)) {
            IdleDecision.ACQUIRE -> {
                RunLog.log("检测到终端活动（CPU 回升），重新获取保活锁")
                acquireWakeLockIfActive()
                decision
            }
            IdleDecision.RELEASE -> {
                val minutes = WakeLockIdlePolicy.DEFAULT_IDLE_TICKS * WakeLockIdlePolicy.TICK_MS / 60_000
                RunLog.log(
                    "终端已连续 $minutes 分钟无活动（CPU 无增长），释放保活锁；" +
                        "会话保持，检测到活动将自动恢复"
                )
                releaseWakeLock()
                decision
            }
            IdleDecision.NONE -> decision
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

    /** 通知标题区分持锁状态（用户要求：持锁=「会话运行中」，空闲释放=「空闲休眠」）。 */
    private fun notifTitle(): String =
        if (keepAlive && idlePolicy.isDeliberatelyIdle) "证道 · 空闲休眠" else "证道 · 会话运行中"

    private fun buildNotification(): Notification {
        val s = SessionManager.session
        val rss = s?.let { readRssMb(it.pid) } ?: 0L
        lastRssMb = rss
        val runtime = elapsedText()
        val memText = if (rss > 0) "$rss MB" else "统计中"
        val warn = if (rss > NOTIFY_RSS_WARN_MB) " · ⚠️ 内存偏高，建议回到终端清理" else ""
        // 「保活已关」必须显性：锁没在手上时 CPU 可以睡、长任务可能被拖慢/中断，
        // 用户只有看到这一句才知道自己点掉过保活（此前这个状态在通知里完全不可见）。
        val keepText = if (keepAlive) "" else " · 保活已关"
        // 空闲休眠是 App 自己的省电行为（区别于用户手动关保活）：告诉用户"有活动会自动回来"，
        // 避免他看到"空闲休眠"以为任务死了
        val idleText = if (keepAlive && idlePolicy.isDeliberatelyIdle) " · 有活动自动唤醒" else ""
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
            .setContentTitle(notifTitle())
            .setContentText("已运行 $runtime · 内存 $memText$warn$idleText$keepText")
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

// ═══════════════════════════════════════════════════════════════════════════
// 空闲检测的三块可单测内核（2026-10-13）：不 import 任何 android.*，
// JVM 单测直接跑（SessionServiceIdleTest）。
// ═══════════════════════════════════════════════════════════════════════════

/** 一轮 tick 后对锁的决策。 */
internal enum class IdleDecision {
    /** 当前没持锁且判为有活动 ⇒ 补取。 */
    ACQUIRE,
    /** 连续空闲达到阈值 ⇒ 释放。 */
    RELEASE,
    /** 维持现状。 */
    NONE,
}

/**
 * WakeLock 空闲策略（纯逻辑，无 Android 依赖）。
 *
 * 输入是每轮 tick 的「本 App 全部进程 CPU jiffies 总读数」（[GuestCpuCounter]），
 * 输出对锁的 [IdleDecision]。设计取向**刻意偏保守**（用户原话：宁可多持一会儿锁，
 * 也不要把"有任务在跑"误判成空闲）：
 *
 * 1. **读不到 / 无基线 / 计数倒退 ⇒ 一律按"有活动"**：jiffies 读数是推断证据里最弱的一类，
 *    任何"读不到所以当空闲"都会变成悄悄放锁；
 * 2. **单轮阈值 [DEFAULT_ACTIVE_JIFFIES]（2 jiffy ≈ 20ms CPU / 30s ≈ 0.07% 单核）**：
 *    低于它视为"这轮没干活"。tmux 空转的噪声（状态栏默认 15s 重绘一次）通常 <1 jiffy/30s；
 * 3. **窗口累计预算 [DEFAULT_STREAK_BUDGET]**：慢速 I/O 型任务（trickle 下载）单轮可能
 *    只有 1 jiffy，但 20 轮累计不低——累计超预算同样判为活动、清零空闲计数，
 *    宁可永不释放也不能把下载中的任务放睡着（睡了网络就断，任务假死比费电更糟）；
 * 4. **连续 [DEFAULT_IDLE_TICKS] 轮（10 分钟）全部"没干活"且累计未超预算 ⇒ 释放**。
 *    计数达标即置位"刻意空闲"标记（不依赖锁状态）：锁被外部释放、isHeld 又读不到时
 *    也当轮收敛，补取门控立即生效，不必多等一轮。
 *
 * `held` 每轮由调用方传**真实锁状态**：策略因此能自愈（外部把锁取走了/补回来了都能对齐）。
 * 阈值是读码+推断值（证据等级 ③），待真机校准——偏保守方向只会"多持锁"，不会误放。
 */
internal class WakeLockIdlePolicy(
    private val idleTicksRequired: Int = WakeLockIdlePolicy.DEFAULT_IDLE_TICKS,
    private val activeCpuJiffies: Long = WakeLockIdlePolicy.DEFAULT_ACTIVE_JIFFIES,
    private val streakCpuBudget: Long = WakeLockIdlePolicy.DEFAULT_STREAK_BUDGET,
) {
    companion object {
        /** monitorTick 的周期（SessionService 里 postDelayed 用的同一个值）。 */
        const val TICK_MS = 30_000L

        /** 连续多少轮"没干活"才放锁：20 轮 × 30s = 10 分钟。 */
        const val DEFAULT_IDLE_TICKS = 20

        /** 单轮判"有活动"的 jiffy 增量下限（Android USER_HZ=100 ⇒ 1 jiffy = 10ms CPU）。 */
        const val DEFAULT_ACTIVE_JIFFIES = 2L

        /** 空闲连续期内累计 CPU 增量预算：超过即视为活动（防 trickle 型任务被误放）。 */
        const val DEFAULT_STREAK_BUDGET = 15L
    }

    private var lastCpu = -1L
    private var idleTicks = 0
    private var streakGrowth = 0L
    private var idle = false

    /** 当前是否处于"刻意释放"状态（monitorTick 据此暂停补取、通知据此换文案）。 */
    // D-3：monitorTick 挪到巡检线程后，本状态被主线程（通知/开关拨动）与巡检线程
    // 双方读写 ⇒ 三个入口全部同步（逻辑不变，只是跨线程可见性）。
    @get:Synchronized
    val isDeliberatelyIdle: Boolean get() = idle

    /** 回到初始判定态（保活开关被用户拨动、会话不存在时调用）。 */
    @Synchronized
    fun reset() {
        lastCpu = -1L
        idleTicks = 0
        streakGrowth = 0L
        idle = false
    }

    /**
     * 喂一轮 CPU 读数。
     * @param totalCpu 本 App 全部进程的 jiffies 总读数；null = 读不到（按活动处理）
     * @param held 当前锁的真实持有状态
     */
    @Synchronized
    fun onTick(totalCpu: Long?, held: Boolean): IdleDecision {
        val active = when {
            totalCpu == null -> true               // 读不到 ⇒ 保守
            lastCpu < 0L -> true                   // 首轮无基线 ⇒ 保守
            totalCpu < lastCpu -> true             // 计数倒退（进程树重建等）⇒ 保守
            totalCpu - lastCpu >= activeCpuJiffies -> true
            else -> false
        }
        if (totalCpu != null) {
            if (lastCpu >= 0L && totalCpu > lastCpu) streakGrowth += totalCpu - lastCpu
            lastCpu = totalCpu
        }
        if (active || streakGrowth > streakCpuBudget) {
            idleTicks = 0
            streakGrowth = 0L
            idle = false
            return if (!held) IdleDecision.ACQUIRE else IdleDecision.NONE
        }
        idleTicks++
        if (idleTicks >= idleTicksRequired) {
            // 计数达标即置位「刻意空闲」（不依赖锁状态）：若锁已被外部释放、isHeld 又读不到
            // 而默认 true，旧写法要再等一轮才收敛；现在标记当轮就位，补取门控立即生效。
            idle = true
            return if (held) IdleDecision.RELEASE else IdleDecision.NONE
        }
        return IdleDecision.NONE
    }
}

/**
 * 「本 App 全部进程 CPU jiffies」读取器。
 *
 * 为什么量 **UID 全体**而不是只量会话 shell 进程：
 * - 终端里的真实负载（agent、编译、下载）跑在 tmux server 之下，而 tmux server 是
 *   **daemonize 出去的**——它的真实 ppid 是 init，**不在**会话进程的子树里
 *   （与「双 fork 守护进程会跑出 proot」是同一件事的进程树侧写）；
 *   按父子树从会话 pid 往下爬会漏掉全部 agent ⇒ 必须按 UID 圈定。
 * - hidepid=2（Android 9+ 默认）下 `/proc` 只列得出自己 UID 的进程 ⇒ 遍历成本是
 *   几十项的小读，主线程可承受；再按 status 的 Uid 行过滤一次兜底（防个别机型
 *   hidepid 配置不同导致读到别家进程）。
 * - App 自己的进程（[myPid]）**排除**：否则每次扫描自身消耗的 CPU 会喂进判据，
 *   形成"扫描活动 ⇒ 判活动 ⇒ 永不放锁"的自激。
 * - cutime/cstime 一并计入：agent 进程退出瞬间，它一生消耗的 CPU 会折叠进收尸父进程
 *   的 cutime——只看 utime/stime 会把"刚跑完的任务"读成零。
 */
internal object GuestCpuCounter {

    /**
     * 解析 /proc/<pid>/stat 的 (utime, stime, cutime, cstime)。
     *
     * ⚠️ 这四个字段在 **stat** 里而不是 status 里（status 只有 VmRSS 等内存行）；
     * stat 的第 2 字段 comm 是**括号包裹、可含空格与括号**的进程名
     * （如 `tmux: server`）⇒ 以**最后一个 `)`** 为界，其后才是空格分隔的数字字段：
     * `state ppid pgrp session tty_nr tpgid flags minflt cminflt majflt cmajflt utime stime cutime cstime …`
     * （state 是去头后的第 0 项，utime 起 = 第 11~14 项）。
     *
     * @return 长度 4 的数组 [utime, stime, cutime, cstime]；解析不出返回 null
     */
    internal fun parseCpuJiffies(stat: String): LongArray? {
        val rparen = stat.lastIndexOf(')')
        if (rparen < 0) return null
        val fields = stat.substring(rparen + 1).trim().split(Regex("\\s+"))
        if (fields.size < 15) return null
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        val cutime = fields[13].toLongOrNull() ?: return null
        val cstime = fields[14].toLongOrNull() ?: return null
        return longArrayOf(utime, stime, cutime, cstime)
    }

    /** 解析 status 里的 `Uid:` 行首字段（真实 uid）；不是 Uid 行 / 解析不出 ⇒ null。 */
    internal fun parseUid(statusLine: String): Int? =
        if (!statusLine.startsWith("Uid:")) null
        else statusLine.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()

    /**
     * 汇总本 UID 全部进程的 CPU jiffies 总读数。
     *
     * @param myUid 当前 App 的 uid（`Process.myUid()`）
     * @param myPid 当前 App 进程号（`Process.myPid()`，**排除自身**，防扫描自激）
     * @return jiffies 总和；一个进程都读不到（/proc 不可用等）⇒ null（调用方按活动处理）
     */
    fun read(myUid: Int, myPid: Int): Long? {
        val names = File("/proc").list() ?: return null
        var total = 0L
        var seen = false
        for (name in names) {
            val pid = name.toIntOrNull() ?: continue
            if (pid == myPid) continue
            val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: continue
            val cpu = parseCpuJiffies(stat) ?: continue
            val uidLine = runCatching {
                File("/proc/$pid/status").readLines().firstOrNull { it.startsWith("Uid:") }
            }.getOrNull() ?: continue
            if (parseUid(uidLine) != myUid) continue
            total += cpu[0] + cpu[1] + cpu[2] + cpu[3]
            seen = true
        }
        return if (seen) total else null
    }
}
