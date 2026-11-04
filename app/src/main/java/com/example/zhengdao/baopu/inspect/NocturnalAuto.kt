// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百三十二批 · 暗卫**自动档**的装配与执行：409 交付时暗卫的运行器没接（"格式补齐等
// 运行器那批来填"的就是本批）；2026-11-04 用户对派工单 432 拍板 A「开」——每周 1 次、
// 仅充电＋WiFi＋非值守（L2 期间不跑），结果只进体检单＋账本，不弹卡。
//
// 形状照 [HealthScheduler]（月位）那套：这里只做装配（排作业、读探针）＋把判据层的
// 结论落账；**判据一行不落在本文件**——周位闸在 [PatrolScheduler.autoWeeklyGate]，
// 配额与充电闸在 [NocturnalPatrol.gate]（409 钉过它"一字未动"，本批首次给它生产调用点）。
//
// 四条纪律（派工单 §二③/边界）：
// 1. **只进体检单＋账本**：一轮的运行记录只有 `暗卫记录.jsonl` 一行（零命中也落）；
//    不递意见（不碰 `OpinionWiring`）、不弹卡、不起通知——体检的「抽检读数」块自己会来读。
// 2. **零模型调用、不联网**：抽检走的是 279 的纯判据（I1／I6 投回抽中的那几条），
//    WiFi 只是"该不该跑"的机器条件（§二②），不是这条链路的依赖。
// 3. **抽检不是全检**：一轮最多 [PatrolScheduler.WEEKLY_MIN_SAMPLE] 条（单次闸 [NocturnalLimits.NIGHTLY_MAX]），
//    随机起点不重复（[NocturnalPatrol.pick]）；抽不到 5 条 ⇒ 运行记录照落，
//    体检那行显「样本不足」——**不许**显"假 PASS 0，正常"（拍板补充②，判据在
//    `HealthCheck.samplingLine`）。
// 4. **可关（只做读开关）**：开关的真源在 SP（[SWITCH_FILE]），本批只做**读**口；
//    设置页的开关 UI 是 UI 批的活（§二④）。
//
// 首跑时机（拍板补充①）：开启后**不立即跑**——周期作业首跑在排上之后一个周期（≥7 天）
// 且要等约束放行，到了窗口还要过判据两道闸。"等第一个满足条件的窗口"由这个形状保证。
package com.example.zhengdao.baopu.inspect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.core.RunLog
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit

/** 暗卫自动档的装配口（周位判据在 [PatrolScheduler]，配额闸在 [NocturnalPatrol]，本文件不持算法）。 */
internal object NocturnalAuto {

    /** 周期（天）：与周位判据同一个数，不另立一个。 */
    const val PERIOD_DAYS: Long = PatrolScheduler.WEEKLY_PERIOD_DAYS

    /** 周期作业的唯一名（`KEEP` 策略靠它幂等：重复开 App 不会把周期清零）。 */
    const val WORK_NAME: String = "zd-nightwatch-weekly"

    /** 诊断标签（`WorkManager.getWorkInfosByTag` 能一把捞出来）。 */
    const val TAG: String = "zd-nightwatch"

    /** 开关的 SP（项目惯例：`zhengdao-*` 一个用途一个文件；写侧留给 UI 批，本批只读）。 */
    const val SWITCH_FILE: String = "zhengdao-inspect"
    const val SWITCH_KEY: String = "nightwatch_auto"

    /**
     * 读开关（432 §二④：本批只做读）。读不到（SP 没建/异常）⇒ 缺省值，
     * 缺省值就是 [PatrolScheduler.WEEKLY_ENABLED]（拍板 A＝开）——判据只收参数，不替谁记开关。
     */
    fun switchOn(ctx: Context): Boolean = runCatching {
        ctx.applicationContext
            .getSharedPreferences(SWITCH_FILE, Context.MODE_PRIVATE)
            .getBoolean(SWITCH_KEY, PatrolScheduler.WEEKLY_ENABLED)
    }.getOrDefault(PatrolScheduler.WEEKLY_ENABLED)

    /** 现在在不在充电（读不到电池服务 ⇒ 当没充电，fail-closed；与月位同一只探针）。 */
    fun charging(ctx: Context): Boolean = HealthScheduler.charging(ctx)

    /**
     * 现在走的是不是不按流量计费的网络（§二② "WiFi" 的机器判法，与 WorkManager 的
     * `NetworkType.UNMETERED` 同一口径；读不到 ⇒ 当没有，fail-closed）。
     */
    fun wifiNow(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    /** L2 值守中吗（§二②：值守期间不跑，避免抢资源；问口是 388 的状态仓，本层不摸 SP/钥匙串）。 */
    fun onDuty(): Boolean = com.example.zhengdao.unattended.UnattendedStatus.armed()

    /**
     * 自动档那一趟（[NocturnalAutoWorker] 调它）：周位闸 → 配额闸 → 抽一轮、落账。
     *
     * @return null ＝ 这趟不该跑（已记日志，首跑等的就是这种窗口）；非 null ＝ 真抽了一轮
     */
    fun runDue(ctx: Context, nowMs: Long = System.currentTimeMillis()): NightRound? {
        val root = PatrolSources.memoryRoot()
        if (root == null) {
            RunLog.log("暗卫自动档：这趟不跑（208 没装配落点，抽检没处落账）")
            return null
        }
        val rounds = NocturnalLedger.read(root)
        val gate = PatrolScheduler.autoWeeklyGate(
            lastRoundAtMs = PatrolScheduler.lastAutoRoundAtMs(rounds),
            nowMs = nowMs,
            wifi = wifiNow(ctx),
            onDuty = onDuty(),
            enabled = switchOn(ctx),
        )
        if (!gate.run) {
            RunLog.log("暗卫自动档：这趟不跑（${gate.why}）")
            return null
        }
        val bucket = PatrolScheduler.dayBucket(nowMs)
        val quota = NocturnalPatrol.gate(
            nowMs = nowMs,
            bucket = bucket,
            rounds = rounds,
            charging = charging(ctx),
            // 一键停/在跑/灾异三条是夜间与手动档的闸（调用方给得出才给）：自动档按 432 §二②
            // 的条件执行，进程里没有可读的"引擎在跑"问口——读不到就不冒充读过。
            stopped = false,
            running = false,
            emergency = false,
        )
        if (!quota.run) {
            RunLog.log("暗卫自动档：这趟不跑（${quota.why}）")
            return null
        }
        return runRound(ctx, root, nowMs, bucket, quota.allowance)
    }

    /**
     * 抽一轮（279 的机制原样跑一遍）：池子建在判据读的同一份输入上（裁定②）→ 随机抽
     * → 投回 I1／I6 的输入侧 → 运行记录落自己的账。
     */
    private fun runRound(
        ctx: Context,
        root: File,
        nowMs: Long,
        bucket: Long,
        allowance: Int,
    ): NightRound? {
        val db = runCatching { BaopuRuntime.db(ctx) }.getOrNull()
        if (db == null) {
            RunLog.log("暗卫自动档：这趟没跑成（库打不开，取不到痕迹）")
            return null
        }
        val t0 = System.currentTimeMillis()
        val from = PatrolRunner.windowFrom(nowMs)
        val ledger = PatrolSources.ledger(root)
        val acts = PatrolSources.writeTraces(db, from)
        val pool = NocturnalPatrol.poolOf(ledger, acts)
        val picked = NocturnalPatrol.pick(pool, allowance, Random(nowMs))
        val scoped = NocturnalPatrol.scopeTo(picked, ledger, acts)
        // I6 的痕迹侧同样缩到抽中的那几条（裁定②）；影子台账是"对手"，照旧给全量。
        val pickedKeys = scoped.acts.map { it.sessionId to it.step }.toSet()
        val scopedShadows = PatrolSources.shadowTraces(db, from)
            .filter { (it.sessionId to it.step) in pickedKeys }
        val findings = listOf(
            AuditGapCheck.check(scoped.acts, ledger),
            ShadowCheck.check(scopedShadows, PatrolSources.shadows(root)),
        )
        val round = NocturnalPatrol.roundOf(nowMs, bucket, picked.map { it.record }, findings)
            .copy(elapsedMs = (System.currentTimeMillis() - t0).coerceAtLeast(0L))
        val saved = NocturnalLedger.append(root, round)
        RunLog.log(
            "暗卫自动档：抽了 ${round.inspected} 条，假 PASS ${round.hits} 条" +
                (if (round.inspected < PatrolScheduler.WEEKLY_MIN_SAMPLE) {
                    "（样本不足 n<${PatrolScheduler.WEEKLY_MIN_SAMPLE}）"
                } else {
                    ""
                }) +
                (if (saved) "" else "，账没落上"),
        )
        return round
    }

    /**
     * 排上周位（开 App 时调一次就够；`KEEP` 保证不会把已在排的周期清零）。
     *
     * 约束是 §二② 的系统侧那一半：作业在队列里等到"充电＋不按流量计费"才被放行，
     * 不靠我们自己轮询；放行后 [NocturnalAutoWorker] 里还有判据在等（两道闸谁也不能单独放行）。
     */
    fun ensureScheduled(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            val request = PeriodicWorkRequestBuilder<NocturnalAutoWorker>(PERIOD_DAYS, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(app)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            RunLog.log("暗卫自动档：周位已排（$WORK_NAME，每 $PERIOD_DAYS 天，仅充电＋WiFi）")
        }.onFailure {
            // 排不上不是事故（还有手动档与月位体检），但要留一行——否则"从不抽检"只能靠猜。
            RunLog.log("暗卫自动档：周位没排上——${it.javaClass.simpleName}: ${it.message}")
        }
    }
}

/**
 * 周位的执行体：WorkManager 到点（且插着电、走着不按流量计费的网络）时跑这一趟。
 *
 * `Result.retry()` 只接"抛了异常"的可恢复失败；判据说"这趟不跑"（null）不是失败——
 * 重试改变不了判据的结论，下一周窗自然再来（同 [HealthWorker] 的口径）。
 */
class NocturnalAutoWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        runCatching { NocturnalAuto.runDue(applicationContext) }.getOrElse {
            RunLog.log("暗卫自动档：作业趟抛出 ${it.javaClass.simpleName}: ${it.message}")
            return Result.retry()
        }
        return Result.success()
    }
}
