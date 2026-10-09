// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.keepalive

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Process
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.SessionService
import java.io.File

/**
 * 保活心跳 + 崩溃留档（#5，2026-10-09）。
 *
 * [KeepaliveArchive] 负责"事后取证"（读系统的死亡证明、导出），这里负责**活着的时候留痕**：
 *
 * - **生命周期**：Activity 起停、前后台切换各记一行 —— 死后再看，最后几行就是"死之前用户在哪"；
 * - **内存压力**：`onTrimMemory` / `onLowMemory` 是系统**提前打招呼**（RUNNING_LOW →
 *   RUNNING_CRITICAL 之后往往就是 LMK），这是唯一能在被杀**之前**拿到"内存水位"的机会；
 * - **巡检**：每 [PATROL_MINUTES] 分钟一行心跳 + 一份 logcat 片段 —— 心跳的**间隔变化**本身
 *   就是证据：正常是 10 分钟一条，若最后一条停在 21:30 而下一轮启动在 23:00，
 *   说明它在那之间被清掉了（而不是"一直活着"）；
 * - **崩溃**：`Thread.setDefaultUncaughtExceptionHandler` 把异常 + 堆栈 + 当时状态写进
 *   `crash-<时间>.log`（Java 崩溃走不到 `ApplicationExitInfo` 的 trace，这条是我们自己的原文），
 *   然后**照旧交给系统默认处理器**——不吞异常，用户看到的还是系统那个"已停止运行"。
 *
 * 全部是"记一行"级别的开销：写的是 [KeepaliveArchive.HEARTBEAT] 这个有上限的文件。
 */
object KeepaliveWatcher {

    /** 巡检间隔（分钟）。10 分钟一条：既够画出存活曲线，又不会撑爆心跳文件。 */
    internal const val PATROL_MINUTES = 10L

    internal const val CRASH_PREFIX = "crash-"

    /** 心跳里最多带多少字符的"最近心跳"（崩溃留档用，别把整份心跳塞进崩溃文件）。 */
    internal const val CRASH_HEARTBEAT_CHARS = 4000

    private var installed = false

    /** 最近一次活动的界面（心跳/崩溃时一起记，回答"死的时候用户在哪一页"）。 */
    @Volatile
    private var lastActivity = "（还没进过界面）"

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(callbacks)
        app.registerComponentCallbacks(trim)
        installCrashHandler(app)
        startPatrol(app)
    }

    // ── 心跳 ──

    /**
     * 一行心跳的文本（纯函数，单测直接调）。
     *
     * 格式固定成"`[时间] 事件 | 服务=… | 内存=… | 线程=… | 界面=…`"，
     * 这样"grep 服务=运行中"就能筛出"被杀时前台服务在不在"。
     */
    internal fun heartbeatLine(
        now: Long,
        event: String,
        serviceRunning: Boolean,
        memory: String,
        threads: Int,
        activity: String,
    ): String = "[${KeepaliveArchive.timeText(now)}] $event | 服务=${if (serviceRunning) "运行中" else "未运行"}" +
        " | $memory | 线程=$threads | 界面=$activity\n"

    /** 写一行心跳（任何线程可调）。 */
    fun heartbeat(ctx: Context, event: String, now: Long = System.currentTimeMillis()) {
        runCatching {
            val line = heartbeatLine(
                now = now,
                event = event,
                serviceRunning = sessionServiceRunning(),
                memory = memoryText(ctx),
                threads = Thread.activeCount(),
                activity = lastActivity,
            )
            KeepaliveArchive.appendCapped(
                File(KeepaliveArchive.dir(ctx), KeepaliveArchive.HEARTBEAT),
                line,
                KeepaliveArchive.HEARTBEAT_MAX_BYTES,
            )
        }
    }

    /**
     * 内存水位一句话。
     *
     * 三路数各有用途：**Java 堆**看我们自己涨没涨，**系统可用内存 / lowMemory 标志**
     * 看整机压力（LMK 是按整机算的），**RSS** 与会话里那个 RSS 警告阈值口径一致。
     */
    fun memoryText(ctx: Context): String {
        val heap = Runtime.getRuntime()
        val usedMb = (heap.totalMemory() - heap.freeMemory()) / 1024 / 1024
        val maxMb = heap.maxMemory() / 1024 / 1024
        val mi = ActivityManager.MemoryInfo()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        runCatching { am?.getMemoryInfo(mi) }
        val rss = rssText()
        return "内存=堆 ${usedMb}/${maxMb}MB · 系统可用 ${mi.availMem / 1024 / 1024}MB" +
            "（阈值 ${mi.threshold / 1024 / 1024}MB，lowMemory=${mi.lowMemory}）$rss"
    }

    /**
     * 本进程 RSS（读 `/proc/self/statm`；读不到就留空，不编数）。
     *
     * ⚠️ 必须取**第 2 列**（resident，常驻页数）：第 1 列是 total program size（**虚拟**地址空间），
     * 真机上量出来是 16 GB 这种荒谬数字——第一版就是这么错的，好在日志里一眼能看出不对。
     */
    internal fun rssText(): String = runCatching {
        val fields = File("/proc/self/statm").readText().trim().split(" ")
        val residentPages = fields.getOrNull(1)?.toLong() ?: return ""
        " · RSS=${KeepaliveArchive.rssMb(residentPages, 4096)}MB"
    }.getOrDefault("")

    /** 前台保活服务在不在（[SessionService] 自己报的状态，比 getRunningServices 可靠）。 */
    internal fun sessionServiceRunning(): Boolean = runCatching { SessionService.running }.getOrDefault(false)

    // ── 生命周期 / 内存回调 ──

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            lastActivity = activity.javaClass.simpleName
            heartbeat(activity, "界面进入 ${activity.javaClass.simpleName}")
        }

        override fun onActivityStopped(activity: Activity) {
            // Activity 之间的切换也会走 stopped，两次都记但**文案分开**：排查时我们要看的正是
            // 「最后一步是真的退到后台，还是只是换了个页面」（配置变更/重建另算，别混进"离开"）。
            val rebuilding = runCatching { activity.isChangingConfigurations }.getOrDefault(false)
            heartbeat(activity, if (rebuilding) "界面重建 ${activity.javaClass.simpleName}"
            else "界面离开 ${activity.javaClass.simpleName}")
        }

        override fun onActivityDestroyed(activity: Activity) {
            heartbeat(activity, "界面销毁 ${activity.javaClass.simpleName}")
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    private val trim = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            // 系统"提前打招呼"：这几档之后往往就是被杀。别用 heartbeat 的默认 6 参数重复写逻辑，
            // 直接调同一入口即可（lastActivity / 服务状态 / 内存都会一起记）。
            runCatching {
                val ctx = appContext
                if (ctx != null) heartbeat(ctx, "内存压力 ${KeepaliveArchive.trimText(level)}")
            }
        }

        override fun onLowMemory() {
            runCatching {
                val ctx = appContext
                if (ctx != null) heartbeat(ctx, "系统低内存回调 onLowMemory")
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    private var appContext: Context? = null

    // ── 巡检 ──

    /**
     * 每 [PATROL_MINUTES] 分钟：一行心跳 + 一份 logcat 片段。
     *
     * 为什么不能只在启动时抓 logcat：logcat 环形缓冲会被冲掉。10 分钟一份（只留最近
     * [KeepaliveArchive.SNIPPET_KEEP] 份）意味着"最多丢最近 10 分钟的死前现场"。
     */
    private fun startPatrol(app: Application) {
        appContext = app.applicationContext
        runCatching {
            val t = Thread {
                while (true) {
                    runCatching { Thread.sleep(PATROL_MINUTES * 60 * 1000L) }
                    runCatching {
                        heartbeat(app, "保活巡检（进程仍存活）")
                        KeepaliveArchive.captureLogcat(
                            KeepaliveArchive.dir(app),
                            "patrol",
                            400,
                            System.currentTimeMillis(),
                        )
                    }
                }
            }
            t.isDaemon = true
            t.name = "zhengdao-keepalive-patrol"
            t.start()
        }
    }

    // ── 崩溃留档 ──

    /**
     * 未捕获异常留档。
     *
     * 只做一件事：把"我们这边能说清楚的"写下来（异常、堆栈、当时状态、最近心跳），
     * 然后**照旧抛给默认处理器**——不吞、不覆盖系统行为（用户看到的仍是系统的崩溃提示）。
     * 系统侧那份（`ApplicationExitInfo` 的 trace）由下一轮启动时 [KeepaliveArchive.onStartup] 收。
     */
    private fun installCrashHandler(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val now = System.currentTimeMillis()
                val d = KeepaliveArchive.dir(app)
                val body = buildString {
                    append("时间：${KeepaliveArchive.timeText(now)}（$now）\n")
                    append("pid：${pid()}\n")
                    append("线程：${thread.name}\n")
                    append("异常：${error.javaClass.name}: ${error.message}\n")
                    append("服务=${if (sessionServiceRunning()) "运行中" else "未运行"}\n")
                    append("${memoryText(app)}\n")
                    append("界面=$lastActivity\n")
                    append("── 堆栈 ──\n")
                    append(error.stackTraceToString())
                    append("\n── 最近心跳 ──\n")
                    append(
                        KeepaliveArchive.truncate(
                            runCatching {
                                File(d, KeepaliveArchive.HEARTBEAT).readText()
                            }.getOrDefault("（还没写过心跳）"),
                            CRASH_HEARTBEAT_CHARS,
                        )
                    )
                }
                File(d, "$CRASH_PREFIX${KeepaliveArchive.stamp(now)}.log").writeText(body)
                RunLog.log(
                    "崩溃已留档：${CRASH_PREFIX}${KeepaliveArchive.stamp(now)}.log" +
                        "（${error.javaClass.simpleName}: ${error.message}）"
                )
            }
            // 不吞：交给系统默认处理器（它才会走系统的"应用已停止"与 kill 流程）
            previous?.uncaughtException(thread, error)
        }
    }

    /** 单测用：本进程 pid（也写进崩溃文件，便于和 [KeepaliveArchive] 的记录对齐）。 */
    internal fun pid(): Int = Process.myPid()
}
