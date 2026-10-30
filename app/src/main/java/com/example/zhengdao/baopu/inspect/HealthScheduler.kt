// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十一批（第二刀）· 御医体检的**装配位**：一个月一趟，月位判据挂在夜巡调度器那一层
// （`PatrolScheduler.monthlyGate`）。本文件只做三件装配事：排作业、判充电、跑一趟。
//
// 为什么本文件不再自己写月键算法：用户 2026-10-30 的原话是「挂到现成的 PatrolScheduler
// （夜巡那个调度器）一个月任务位」。判据只许有一份实现——两份"本月"迟早会算出两种结果，
// 那时账就对不上了。门禁 `HealthWiringGateTest.月位判据只许来自夜巡调度器` 钉着这一条。
//
// 装配侧仍是四条前提里的两条硬约束（另两条由 `HealthRun` 与 `InspectReadOnlyGateTest` 管）：
//   1. **只在充电时跑**：WorkManager `setRequiresCharging(true)`（排队时的约束）＋
//      [runDue] 里再读一次电池（排到了但已经拔线的情况）；
//   2. **非精确**：走周期作业，不设 `setExactAndAllowWhileIdle`——体检晚几天没关系，
//      月位由自然月判，推迟不会变成两趟。
//
// 为什么不是"独立页面／独立闹钟"：体检是**后台维护**，不是用户要看的东西；给它一个页面
// 等于又开一个"要记得去看"的地方。第一版只给设置页一行手动入口（[runNow]）。
package com.example.zhengdao.baopu.inspect

import android.content.Context
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.zhengdao.core.RunLog
import java.util.concurrent.TimeUnit

/**
 * 月位的装配口（判据在 [PatrolScheduler]，本文件不再持有任何月键/充电判据的算法）。
 */
internal object HealthScheduler {

    /** 周期（天）：与月位判据同一个数，不另立一个（30 天只是"大致这么频繁"）。 */
    const val PERIOD_DAYS: Long = PatrolScheduler.MONTHLY_PERIOD_DAYS

    /** 周期作业的唯一名（`KEEP` 策略靠它幂等：每次开 App 重排不会把周期清零）。 */
    const val WORK_NAME: String = "zd-health-monthly"

    /** 诊断标签（`WorkManager.getWorkInfosByTag` 能一把捞出来）。 */
    const val TAG: String = "zd-health"

    /** 现在在不在充电（读不到电池服务 ⇒ 当没充电，fail-closed）。 */
    fun charging(ctx: Context): Boolean = runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        bm.isCharging
    }.getOrDefault(false)

    /**
     * 排上那个月位（开 App 时调一次就够；`KEEP` 保证不会把已在排的周期清零）。
     *
     * 约束 `setRequiresCharging(true)` 是"只在充电时跑"的系统侧那一半：
     * 作业在队列里等到充电才被放行，不靠我们自己轮询。
     */
    fun ensureScheduled(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            val request = PeriodicWorkRequestBuilder<HealthWorker>(PERIOD_DAYS, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiresCharging(true).build(),
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(app)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            RunLog.log("御医体检：月位已排（$WORK_NAME，每 $PERIOD_DAYS 天，仅充电）")
        }.onFailure {
            // 排不上不是事故（用户还能手动跑），但要留一行——否则"体检从不跑"只能靠猜。
            RunLog.log("御医体检：月位没排上——${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 自动档那一趟（[HealthWorker] 调它）：判月位与充电，判过就跑。
     *
     * 判据一行不落在本文件：[PatrolScheduler.monthlyGate] 说什么就是什么。
     *
     * @return null ＝ 这趟不该跑（已记日志）；非 null ＝ 真跑了一趟（含失败原因）
     */
    fun runDue(ctx: Context, nowMs: Long = System.currentTimeMillis()): HealthResult? {
        val root = HealthRun.memoryRoot()
        if (root == null) {
            RunLog.log("御医体检：这趟不跑（208 没装配落点）")
            return null
        }
        val g = PatrolScheduler.monthlyGate(HealthLedger.lastAt(root), nowMs, charging(ctx))
        if (!g.run) {
            RunLog.log("御医体检：这趟不跑（${g.why}）")
            return null
        }
        return HealthRun.run(ctx, manual = false, nowMs = nowMs)
    }

    /**
     * 手动那一趟（设置页那一行调它）：**不看月位、不看充电**，但同样只读、同样落账。
     *
     * 为什么不看月位：验收要能随时跑；月位管的是"别天天自动跑"，不是"人不许看"。
     */
    fun runNow(ctx: Context, nowMs: Long = System.currentTimeMillis()): HealthResult =
        HealthRun.run(ctx, manual = true, nowMs = nowMs)
}

/**
 * 月位的执行体：WorkManager 到点（且插上电）时跑这一趟。
 *
 * 为什么 `Result.retry()` 只在"库打不开／账落不上"这种可恢复失败上用：
 * 体检是幂等的（同月只跑一次由月位兜着），重试不会把账写重。
 */
class HealthWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val result = runCatching { HealthScheduler.runDue(applicationContext) }.getOrElse {
            RunLog.log("御医体检：作业趟抛出 ${it.javaClass.simpleName}: ${it.message}")
            return Result.retry()
        }
        return if (result?.failReason != null) Result.retry() else Result.success()
    }
}
