// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十八单 · 离场值守的**前台服务**（388 派工单：前台服务/WorkManager 兜底，
// 不许被 Doze 静默杀掉）。主仓现在的第一个前台服务（SessionService 随终端搬走了），
// 选型照 M2 保活服务的老账：specialUse——dataSync 在 Android 15+ 每天 6 小时 onTimeout，
// 值守恰恰要跑几小时（manifest 注释同源）。
//
// 职责三样（每拍一次，UnattendedCadence.HEARTBEAT_MS）：
// 1. **心跳**：落 SP 的 last_beat——"服务还活着"的唯一凭据；看门狗凭它认中断（断档 ⇒ 补报）；
// 2. **环境监护**：电量/温度/网络一拍一采 ⇒ [nextPauseReasons] 迟滞转移 ⇒ 暂停/复工各记一笔；
// 3. **节拍**：每小时 B 级汇总；2h 没出 ⇒ 降级停手（单行道，复工＝收值守再开）。
//
// 杀不死的口径说实话：Doze 不杀前台服务，但低内存/OEM 清后台会杀**进程**；那时——
// START_STICKY 让系统尽量拉起、WorkManager 看门狗 15 分钟一查、MainActivity 回前台补起；
// 三层都没接住的那段断档，看门狗记 [UnattendedEventKind.INTERRUPT]，离场报告里补报
// （388 判据原话"被中断要补报"）——**不许静默丢**，这是本单对"Doze"的最终回答。
package com.example.zhengdao.unattended

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.baopu.BaopuRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UnattendedDutyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (loop?.isActive != true) {
            // 单拍异常不许杀循环：心跳/监护停一晚比一次采样失败严重得多（兜底归看门狗，
            // 但看门狗是分钟级，这里的自愈是秒级——两层都留）。
            loop = scope.launch {
                while (isActive) {
                    runCatching { tick() }.onFailure {
                        RunLog.log("离场值守：这一拍没走完 ${it.javaClass.simpleName}: ${it.message}")
                    }
                    delay(UnattendedCadence.HEARTBEAT_MS)
                }
            }
        }
        // START_STICKY：进程被杀后系统尽量重建（重建即 onStartCommand(null)——goForeground 照走）。
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        scope.cancel()
        super.onDestroy()
    }

    /** 起前台（minSdk 36 ⇒ specialUse 类型恒可用；不 startForeground 就是被系统杀，别怪 Doze）。 */
    private fun goForeground() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                UnattendedNotifier.NOTIF_FGS,
                UnattendedNotifier.ongoing(this, statusLine()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure {
            RunLog.log("离场值守：前台起不来 ${it.javaClass.simpleName}: ${it.message}")
            stopSelf()
        }
    }

    /** 一拍：开没开、到期、心跳、环境、汇总、降级——顺序不许调（先停后干，先活着再干活）。 */
    private fun tick() {
        val now = System.currentTimeMillis()
        if (!UnattendedStatus.armed()) {
            stopSelf()
            return
        }
        if (autoDisarmPass(now)) return // 到点自动收了 ⇒ 收尾（服务随即自停，别再走下面的拍）
        refreshForeground()
        UnattendedStatus.beat(now)
        envPass(now)
        cadencePass(now)
    }

    /**
     * 441 加法：一句话值守带时长 ⇒ 到点自动收值守并出报告。
     * 留痕口径与隐式出场同格（DISARM 账＋报告头理由）；心跳早于这一拍的最后几秒差在
     * 一拍（≤1 分钟）之内——派工单写的是"≈ now+时长"，不承诺秒级。
     */
    private fun autoDisarmPass(nowMs: Long): Boolean {
        val dueAt = UnattendedStatus.autoDisarmAtMs() ?: return false
        if (nowMs < dueAt) return false
        val armedAt = UnattendedStatus.armedAtMs() ?: return false
        val reason = "预计时长到了，自动收值守，值守了 ${UnattendedReport.formatSpan(nowMs - armedAt)}"
        val queue = BaopuRuntime.engineIfBuilt()?.awayDeferredHeadlines().orEmpty()
        UnattendedRecorder.disarmAndReport(this, nowMs, queue, closeReasonCn = reason)
        RunLog.log("离场值守：$reason")
        stopSelf()
        return true
    }

    /** 常驻条随状态刷新（暂停中要说"暂停中"，不然锁屏上看不出值守已经停手）。 */
    private fun refreshForeground() {
        runCatching {
            UnattendedNotifier.ongoing(this, statusLine()).let {
                ServiceCompat.startForeground(this, UnattendedNotifier.NOTIF_FGS, it, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        }
    }

    private fun statusLine(): String {
        val paused = UnattendedStatus.pausedReasons()
        val base = if (UnattendedStatus.degraded()) "已降级停手" else "值守中"
        return if (paused.isEmpty()) base else "$base（${paused.joinToString("、") { it.cn }}）"
    }

    /** 环境一拍：采一拍 ⇒ 纯函数转移 ⇒ 落笔（进/退格与通知都归 [UnattendedRecorder]）。 */
    private fun envPass(nowMs: Long) {
        val prev = UnattendedStatus.pausedReasons()
        val next = nextPauseReasons(UnattendedEnvSampler.sample(this), prev)
        UnattendedRecorder.pauseTransition(this, prev, next, nowMs)
    }

    /** 节拍一拍：到点出 B 级汇总；2h 没出且未降级 ⇒ 降级停手。 */
    private fun cadencePass(nowMs: Long) {
        val armedAt = UnattendedStatus.armedAtMs() ?: return
        val last = UnattendedStatus.lastSummaryAtMs()
        if (UnattendedCadence.summaryDue(armedAt, last, nowMs)) {
            UnattendedRecorder.summary(this, armedAt, last, nowMs)
        }
        val latest = UnattendedStatus.lastSummaryAtMs()
        if (!UnattendedStatus.degraded() && UnattendedCadence.degradeDue(armedAt, latest, nowMs)) {
            UnattendedRecorder.degrade(this, nowMs)
        }
    }

    companion object {

        /** "服务活着"的进程内凭据（看门狗判中断用；进程死了它自然不在）。 */
        @Volatile
        internal var alive: Boolean = false
            private set

        /** 起服务（开值守 / 看门狗补起 / 回前台补起共用；后台起 FGS 受限时落账不崩）。 */
        internal fun start(ctx: Context) {
            runCatching {
                ctx.startForegroundService(Intent(ctx, UnattendedDutyService::class.java))
            }.onFailure {
                RunLog.log("离场值守：此刻起不了前台服务（后台限制）${it.javaClass.simpleName}——回前台会自动补起")
            }
        }

        internal fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, UnattendedDutyService::class.java)) }
        }
    }
}

/**
 * 环境采样（装配层里唯一摸 BatteryManager/ConnectivityManager 的地方；
 * 服务每拍采、值守页顺手显示——两处共用一个口，不许各长一套探针）。
 */
internal object UnattendedEnvSampler {

    fun sample(ctx: Context): UnattendedEnvSample {
        val battery = runCatching {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val pct = battery?.let(::batteryPct)
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        return UnattendedEnvSample(batteryPct = pct, batteryTempTenths = temp, networkOk = networkOk(ctx))
    }

    /** level/scale 换百分数；读不到负值 ⇒ null（不假装健康，也不假装没电）。 */
    private fun batteryPct(b: Intent): Int? {
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    /** 网络能不能用（照斥候探针的口径：默认网络＋INTERNET＋VALIDATED；读不到 ⇒ false，fail-closed）。 */
    private fun networkOk(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
