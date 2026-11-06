// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十八单 · 值守的**看门狗**（WorkManager 兜底，388 派工单：前台服务/
// WorkManager 兜底）。前台服务在，它只是每 15 分钟对一次表；前台服务不在了，它才上班：
//
// 1. **断档补报**：开着值守、服务又不在、心跳也停了 ⇒ 记一笔中断账（断档多久）——
//    这一笔就是 388 判据"被中断要补报"的机器依据；然后试着重起服务
//    （Android 12+ 后台起 FGS 受限 ⇒ 起不了就落日志，回前台时 MainActivity 补起）；
// 2. **降级兜底**：B 级汇总 2h 没出 ⇒ 降级停手（服务活着时它自己会降；
//    服务死了汇总必然停，这条兜底就是给"服务死了但看门狗还活着"的那段留的）。
//
// 周期作业在 Doze 里会被推迟到维护窗——这不是缺陷是如实转告：看门狗迟到的那几分钟里
// 断档照样会被记（时刻取自心跳），账不会因为查得晚就少一笔。
package com.example.zhengdao.unattended

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.example.zhengdao.core.RunLog
import java.util.concurrent.TimeUnit

/** 值守看门狗的排程口（ZhengdaoApp.onCreate 经 UnattendedStatus.install 调一次，KEEP 幂等）。 */
internal object UnattendedWatchdog {

    /** 唯一名（KEEP：重复排不重置周期，照 HealthScheduler 的幂等口径）。 */
    const val WORK_NAME: String = "zd-unattended-watchdog"

    /** 诊断标签。 */
    const val TAG: String = "zd-unattended"

    fun ensureScheduled(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            val request = PeriodicWorkRequestBuilder<UnattendedWatchdogWorker>(
                UnattendedCadence.WATCHDOG_PERIOD_MIN, TimeUnit.MINUTES,
            ).addTag(TAG).build()
            WorkManager.getInstance(app)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            RunLog.log("离场值守：看门狗已排（${UnattendedCadence.WATCHDOG_PERIOD_MIN} 分钟一查）")
        }.onFailure {
            // 排不上不是事故（服务自己也在管节拍），但要留一行——否则"从没兜过底"只能靠猜。
            RunLog.log("离场值守：看门狗没排上 ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}

/** 看门狗本体（plain Worker，照 ScheduleWorker 的样式；失败不 retry——下一拍见）。 */
class UnattendedWatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val app = applicationContext
        // 第五百零四单（471③）：停机期看门狗自己也不补起值守（防自启是连看门狗一起管的）。
        if (com.example.zhengdao.baopu.safety.SafetyWiring.blockAutoStartIfHalted("值守看门狗")) {
            return Result.success()
        }
        if (!UnattendedStatus.armed()) return Result.success()
        val now = System.currentTimeMillis()
        watchdogPass(app, now)
        degradePass(app, now)
        return Result.success()
    }

    /** 断档补报＋补起服务（服务活着/心跳新鲜就什么都不做）。 */
    private fun watchdogPass(app: Context, nowMs: Long) {
        // 心跳从没跳过（开了值守但服务一次都没起来）⇒ 锚点退到开值守时刻——
        // "服务压根没起来"也是中断，不许因为没心跳就不算账。
        val lastBeat = UnattendedStatus.lastBeatMs() ?: UnattendedStatus.armedAtMs() ?: return
        val stale = nowMs - lastBeat > UnattendedCadence.BEAT_STALE_MS
        if (!stale || UnattendedDutyService.alive) return
        UnattendedRecorder.interrupt(app, nowMs, nowMs - lastBeat)
        UnattendedDutyService.start(app)
    }

    /** 降级兜底（与服务的 cadencePass 同一把尺：UnattendedCadence.degradeDue）。 */
    private fun degradePass(app: Context, nowMs: Long) {
        val armedAt = UnattendedStatus.armedAtMs() ?: return
        if (UnattendedStatus.degraded()) return
        if (UnattendedCadence.degradeDue(armedAt, UnattendedStatus.lastSummaryAtMs(), nowMs)) {
            UnattendedRecorder.degrade(app, nowMs)
        }
    }
}
