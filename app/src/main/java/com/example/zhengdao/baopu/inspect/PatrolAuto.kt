// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第五百一十一批 · **巡按的自动档**（478 的落点，与暗卫 432 共用一套触发规则）。
//
// 在此之前巡按只有"人手点一下巡一遍"：`PatrolScheduler` 的夜间自动档按 §10③ 默认关着，
// 用户 2026-11-05 一句「巡逻部队也可以随时巡逻啊」⇒ 改成机会式：手动随时 · 负载
// （N=10 任务／M=100 次工具调用）· 7 天下限，三条同过一套机会条件与 6h 冷却。
//
// 三条纪律：
// 1. **只改"何时跑"，不改"巡什么"**：六项判据、三行报告、额度草稿全在 [PatrolRunner] 里，
//    本文件一行判据都不写（触发判据在 [Opportunistic]）。
// 2. **重活互斥**：巡按是两件里重的那件，跑之前先抢 [HeavyWork] 的锁（判据⑦），
//    与暗卫、御医体检同一把锁。
// 3. **落账四格**：谁触发（[TriggerReason]）· 机会读数 · 跑了什么（六项明细在账里）· 结论一行
//    （`PatrolEntry.headline`）——都走既有的 `巡按记录.jsonl`，不另开第二本。
package com.example.zhengdao.baopu.inspect

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.maintenance.AutoMaintenance
import com.example.zhengdao.baopu.maintenance.Opportunity
import com.example.zhengdao.core.RunLog
import java.io.File
import java.util.concurrent.TimeUnit

/** 巡按自动档的装配口（触发判据在 [Opportunistic]；六项判据在 [PatrolRunner]，本文件不持算法）。 */
internal object PatrolAuto {

    /** 周期（小时）：与判据层同一个数（7 天下限的兜底节拍）。 */
    const val PERIOD_HOURS: Long = Opportunistic.CHECK_PERIOD_HOURS

    /** 周期作业的唯一名（`KEEP` 幂等：重复开 App 不会把周期清零）。 */
    const val WORK_NAME: String = "zd-patrol-opportunistic"

    /** 诊断标签。 */
    const val TAG: String = "zd-patrol"

    /** 这一件的身份（互斥锁与落账都用它）。 */
    private val JOB: MaintJob = MaintJob.PATROL

    /**
     * 自动档那一趟：[PatrolAutoWorker]（周期兜底）与 [TriggerWiring]（会话收工事件）都走它。
     *
     * @param source 周期作业只认"到没到 7 天下限"，事件那条看"负载够不够"
     * @return null ＝ 这趟不该跑（已记日志）；非 null ＝ 真巡了一趟
     */
    fun runDue(
        ctx: Context,
        nowMs: Long = System.currentTimeMillis(),
        source: AskSource = AskSource.SCHEDULED,
    ): PatrolResult? {
        val root = PatrolSources.memoryRoot()
        if (root == null) {
            RunLog.log("巡按自动档：这趟不跑（208 没装配落点，巡按没处落账）")
            return null
        }
        val opp = readingsOf(ctx, root, nowMs)
        val gate = Opportunistic.decide(
            job = JOB,
            source = source,
            nowMs = nowMs,
            lastRunAtMs = PatrolLedger.latest(root)?.atMs,
            load = loadOf(ctx, root),
            opp = opp,
            enabled = AutoMaintenance.on(ctx),
        )
        val trigger = gate.trigger
        if (!gate.run || trigger == null) {
            RunLog.log("巡按自动档：这趟不跑（${gate.why}）")
            return null
        }
        if (!HeavyWork.tryEnter(JOB.wire)) {
            RunLog.log("巡按自动档：这趟不跑（${Opportunistic.WHY_HEAVY}）")
            return null
        }
        val result = try {
            PatrolRunner.run(
                ctx = ctx,
                manual = false,
                nowMs = nowMs,
                trigger = trigger.wire,
                opportunity = Opportunistic.readout(opp),
            )
        } finally {
            HeavyWork.exit(JOB.wire)
        }
        RunLog.log("巡按自动档：${result.report.headline}（${trigger.cn}触发）")
        return result
    }

    /** 机器读数（机会条件五格）。 */
    private fun readingsOf(ctx: Context, root: File, nowMs: Long): Opportunity =
        AutoMaintenance.readings(
            ctx = ctx,
            nowMs = nowMs,
            lastAutoActionAtMs = PatrolSources.lastAutoActionAtMs(root),
            heavyBusy = HeavyWork.heldBy() != null,
        )

    /** 负载读数（自上一位巡按起；读不动 ⇒ 0，当没负载）。 */
    private fun loadOf(ctx: Context, root: File): Load {
        val since = PatrolLedger.latest(root)?.atMs ?: return Load.NONE
        return runCatching { PatrolSources.loadSince(BaopuRuntime.db(ctx), since) }.getOrDefault(Load.NONE)
    }

    /**
     * 排上周期位（开 App 时调一次就够；`KEEP` 保证不会把已在排的周期清零）。
     *
     * 与暗卫同款：不带 WorkManager 约束——"电量 ≥30% 或充电中"那个"或"只能当场判。
     */
    fun ensureScheduled(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            val request = PeriodicWorkRequestBuilder<PatrolAutoWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(app)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            RunLog.log("巡按自动档：周期位已排（$WORK_NAME，每 $PERIOD_HOURS 小时看一次账，条件齐才跑）")
        }.onFailure {
            RunLog.log("巡按自动档：周期位没排上——${it.javaClass.simpleName}: ${it.message}")
        }
    }
}

/**
 * 周期位的执行体：每天醒一次看账（负载那一截由 [TriggerWiring] 在收工时问）。
 */
class PatrolAutoWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        runCatching { PatrolAuto.runDue(applicationContext) }.getOrElse {
            RunLog.log("巡按自动档：作业趟抛出 ${it.javaClass.simpleName}: ${it.message}")
            return Result.retry()
        }
        return Result.success()
    }
}
