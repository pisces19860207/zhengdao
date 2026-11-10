// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百三十二批开出「暗卫自动档」，第五百一十一批把**触发**换成机会式（与巡按共用一套）：
// 手动 · 按负载（N=20 任务／M=200 次工具调用）· 7 天下限，三条都过同一套机会条件
// （非值守 · 电量≥20% 或充电中 · 不发烫 · 前台不在会话 · 30 分钟无自动动作 · 没在跑重活）与各自 6h 冷却。
// 432 的"每周一次"从此只是**下限**那一格，不再是唯一节奏；"充电＋WiFi"那两条由 511 §一②
// 取代（电量够就不必插着电，也不再要求 WiFi）。
//
// 形状照旧：本文件只做装配（排作业、读机器读数、把判据的结论落账），
// **判据一行不落在本文件**——触发判据在 [Opportunistic]，配额与本轮抽几条在 [NocturnalPatrol]
// （409 钉过它"一字未动"，两条闸谁也不能单独放行）。
//
// 四条纪律（派工单 511 §一③/§二）：
// 1. **只进体检单＋账本**：一轮的运行记录只有 `暗卫记录.jsonl` 一行（零命中也落）；
//    不递意见（不碰 `OpinionWiring`）、不弹卡、不起通知。
// 2. **零模型调用、不联网**：抽检走 279 的纯判据（I1／I6 投回抽中的那几条）。
// 3. **抽检不是全检**：一轮最多 [NocturnalLimits.NIGHTLY_MAX] 条；抽不到 5 条 ⇒ 运行记录照落，
//    体检那行显「样本不足」——**不许**显"假 PASS 0，正常"（判据在 `HealthCheck.samplingLine`）。
// 4. **不做常驻轮询**：负载那一截由会话收工事件驱动（[TriggerWiring]），周期作业只是
//    7 天下限的兜底（每 [PERIOD_HOURS] 小时醒一次看账，条件不齐一句话都不做）。
package com.example.zhengdao.baopu.inspect

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.evolution.lowrisk.EvoCandidate
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskEvo
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskVerdict
import com.example.zhengdao.baopu.maintenance.AutoMaintenance
import com.example.zhengdao.baopu.maintenance.MaintDevice
import com.example.zhengdao.baopu.maintenance.MaintPause
import com.example.zhengdao.baopu.maintenance.Opportunity
import com.example.zhengdao.baopu.safety.HaltGate
import com.example.zhengdao.core.RunLog
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit

/** 暗卫自动档的装配口（触发判据在 [Opportunistic]，配额闸在 [NocturnalPatrol]，本文件不持算法）。 */
internal object NocturnalAuto {

    /** 周期（小时）：与判据层同一个数，不另立一个（下限的兜底节拍）。 */
    const val PERIOD_HOURS: Long = Opportunistic.CHECK_PERIOD_HOURS

    /** 周期作业的唯一名（`KEEP` 策略靠它幂等：重复开 App 不会把周期清零）。 */
    const val WORK_NAME: String = "zd-nightwatch-opportunistic"

    /** 诊断标签（`WorkManager.getWorkInfosByTag` 能一把捞出来）。 */
    const val TAG: String = "zd-nightwatch"

    /**
     * 432 那一版的作业名（`zd-nightwatch-weekly`）。
     *
     * 511 换了形制（周位 → 每天看账的机会式），名字一变，老机器队列里那条旧作业还留着——
     * 它还会按"每周＋充电＋WiFi"自己醒一次。不撤掉就是两条周期位同时存在（判据⑥那关
     * 也会有人问"这两个周期是哪来的"）。所以排新位时顺手撤旧位：一次性迁移，撤完就不再有。
     */
    const val LEGACY_WORK_NAME: String = "zd-nightwatch-weekly"

    /** 这一件的身份（互斥锁与落账都用它）。 */
    private val JOB: MaintJob = MaintJob.NIGHTWATCH

    /** 四器名单里的那一只（479 的暂停键与落账行都按它说话）。 */
    private val DEVICE: MaintDevice = MaintDevice.NIGHTWATCH

    /**
     * 自动档那一趟：[NocturnalAutoWorker]（周期兜底）与 [TriggerWiring]（会话收工事件）都走它。
     *
     * @param source 这趟是谁在问：周期作业只认"到没到 7 天下限"，事件那条看"负载够不够"
     * @return null ＝ 这趟不该跑（已记日志，条件不齐等下一次机会）；非 null ＝ 真抽了一轮
     */
    fun runDue(
        ctx: Context,
        nowMs: Long = System.currentTimeMillis(),
        source: AskSource = AskSource.SCHEDULED,
    ): NightRound? {
        val root = PatrolSources.memoryRoot()
        if (root == null) {
            RunLog.log("暗卫自动档：这趟不跑（208 没装配落点，抽检没处落账）")
            return null
        }
        val opp = readingsOf(ctx, root, nowMs)
        val readout = Opportunistic.readout(opp)
        // 479 两级刹车：急停（471 的软停/硬停）→ 单件暂停 → 才轮到机会条件。
        MaintBrake.blocked(DEVICE, HaltGate.halted(), MaintPause.untilMs(ctx, DEVICE), nowMs)?.let {
            RunLog.log(MaintBrake.deferredLine(DEVICE, source, it, readout))
            return null
        }
        // 第五百六十八批 A：调度降频域接既有灰度链（473）——**自动**档先问登记表那一档
        // （NOCTURNAL_PATROL 既有登记，缺省灰度 ⇒ 既有行为不变；急停与单件暂停的顺序在前，
        // 479 的语义不动）。MANUAL 是人点的，不受档位管（与 MaintBrake 同一口径）；
        // 机会条件/负载/冷却的判定全在 Opportunistic，本批一个字不改。
        if (source != AskSource.MANUAL) {
            val candidate = EvoCandidate(
                domain = CanaryCapability.NOCTURNAL_PATROL.wire,
                target = "nightwatch:" + JOB.wire,
            )
            when (val v = LowRiskEvo.gate(CanaryCapability.NOCTURNAL_PATROL, candidate)) {
                is LowRiskVerdict.Refuse -> {
                    LowRiskEvo.recordRefusal(
                        CanaryCapability.NOCTURNAL_PATROL,
                        v.redLine,
                        v.note,
                        candidate,
                        nowMs,
                    )
                    RunLog.log(MaintBrake.deferredLine(DEVICE, source, "候选踩自进化红线：" + v.note, readout))
                    return null
                }
                is LowRiskVerdict.Hold -> {
                    RunLog.log(MaintBrake.deferredLine(DEVICE, source, "灰度档关闭，本轮不自动跑（人工入口不受限）", readout))
                    return null
                }
                is LowRiskVerdict.Allow -> Unit
            }
        }
        val gate = Opportunistic.decide(
            job = JOB,
            source = source,
            nowMs = nowMs,
            lastRunAtMs = NocturnalLedger.lastAutoAt(root),
            load = loadOf(ctx, root),
            opp = opp,
        )
        val trigger = gate.trigger
        if (!gate.run || trigger == null) {
            RunLog.log(MaintBrake.deferredLine(DEVICE, source, gate.why, readout))
            return null
        }
        if (!HeavyWork.tryEnter(JOB.wire)) {
            RunLog.log(MaintBrake.deferredLine(DEVICE, source, Opportunistic.WHY_HEAVY, readout))
            return null
        }
        // 568-A：灰度观察账（473 §二②）——自动档这一轮的结局一行：跑成＝ok、抛了＝fail
        // （异常照旧上抛给调用方，观察只是留痕，不改变任何行为）。MANUAL 不经此路。
        return try {
            runRound(ctx, root, nowMs, trigger, readout).also {
                LowRiskEvo.observeRun(CanaryCapability.NOCTURNAL_PATROL, ok = true, atMs = nowMs)
            }
        } catch (t: Throwable) {
            LowRiskEvo.observeRun(CanaryCapability.NOCTURNAL_PATROL, ok = false, atMs = nowMs)
            throw t
        } finally {
            HeavyWork.exit(JOB.wire)
        }
    }

    /** 机器读数（机会条件那五格；"有没有重活在跑"只认进程内互斥锁）。 */
    private fun readingsOf(ctx: Context, root: File, nowMs: Long): Opportunity =
        AutoMaintenance.readings(
            ctx = ctx,
            nowMs = nowMs,
            lastAutoActionAtMs = PatrolSources.lastAutoActionAtMs(root),
            heavyBusy = HeavyWork.heldBy() != null,
        )

    /** 负载读数（自上一次自动档那一轮起；读不动 ⇒ 0，当没负载：等着就是）。 */
    private fun loadOf(ctx: Context, root: File): Load {
        val since = NocturnalLedger.lastAutoAt(root) ?: return Load.NONE
        return runCatching { PatrolSources.loadSince(BaopuRuntime.db(ctx), since) }.getOrDefault(Load.NONE)
    }

    /**
     * 抽一轮（279 的机制原样跑一遍）：池子建在判据读的同一份输入上（裁定②）→ 随机抽
     * → 投回 I1／I6 的输入侧 → 运行记录落自己的账。
     */
    private fun runRound(
        ctx: Context,
        root: File,
        nowMs: Long,
        trigger: TriggerReason,
        readout: String,
    ): NightRound? {
        val rounds = NocturnalLedger.read(root)
        val quota = NocturnalPatrol.gate(
            nowMs = nowMs,
            bucket = PatrolScheduler.dayBucket(nowMs),
            rounds = rounds,
            // 511 §一② 把"必须充电"放宽成"电量≥30% 或充电中"：`gate` 这一格问的是
            // **电源条件成立不成立**（它自己的话术还是 409 那句"不在充电：顺延"）。
            // 口径只在这里换算一次，判据本体一字未动（409 的抽几条/额度窗照旧）。
            charging = powerOkNow(ctx, root, nowMs),
            // 一键停/在跑/灾异三条是夜间与手动档的闸（调用方给得出才给）：自动档按 511 §一②
            // 的条件执行，进程里没有可读的"引擎在跑"问口——读不到就不冒充读过。
            stopped = false,
            running = false,
            emergency = false,
        )
        if (!quota.run) {
            RunLog.log("暗卫自动档：这趟不跑（${quota.why}）")
            return null
        }
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
        val picked = NocturnalPatrol.pick(pool, quota.allowance, Random(nowMs))
        val scoped = NocturnalPatrol.scopeTo(picked, ledger, acts)
        val findings = findingsOf(db, root, from, scoped)
        val round = NocturnalPatrol.roundOf(nowMs, PatrolScheduler.dayBucket(nowMs), picked.map { it.record }, findings)
        val stamped = round.copy(
            elapsedMs = (System.currentTimeMillis() - t0).coerceAtLeast(0L),
            trigger = trigger.wire,
            opportunity = readout,
            conclusion = conclusionOf(round.inspected, round.hits),
        )
        val saved = NocturnalLedger.append(root, stamped)
        // 判据⑤：谁触发 · 机会读数 · 结果一行，同一行里三格齐全（账本里另有结构化的那三格）。
        RunLog.log(
            MaintBrake.runningLine(DEVICE, trigger.cn, readout, stamped.conclusion) +
                if (saved) "" else "（账没落上）",
        )
        // 第五百零八批在 main 上接的互查写口（511 从更早基线切出，rebase 时补回）：
        // 暗卫本轮认定 × 金吾卫上次巡按 ⇒ 有冲突才写行。
        mutualConflicts(root, findings, nowMs)
        return stamped
    }

    /** 电源条件（511 §一② 的新口径；与判据层同一把尺，不另写一份）。 */
    private fun powerOkNow(ctx: Context, root: File, nowMs: Long): Boolean =
        Opportunistic.powerOk(readingsOf(ctx, root, nowMs))

    /** I1／I6 两条判据（抽中的那几条投回输入侧；影子台账是"对手"，照旧给全量）。 */
    private fun findingsOf(db: BaopuDb, root: File, from: Long, scoped: PatrolScoped): List<InspectFinding> {
        val pickedKeys = scoped.acts.map { it.sessionId to it.step }.toSet()
        val scopedShadows = PatrolSources.shadowTraces(db, from)
            .filter { (it.sessionId to it.step) in pickedKeys }
        return listOf(
            AuditGapCheck.check(scoped.acts, PatrolSources.ledger(root)),
            ShadowCheck.check(scopedShadows, PatrolSources.shadows(root)),
        )
    }

    /**
     * 互查判定＋写口一起接（279 §3 的生产调用点；503 §3.2 指的「接上」）。
     *
     * 暗卫这轮刚判出的认定（抽样口径，[findings] 就是投回判据的那两条），对上金吾卫最近一次
     * 巡按留在 `巡按记录.jsonl` 的六项明细——同检查项＋共享证据而认定不同 ⇒ 冲突，两行并列
     * 进 `互查记录.jsonl`（各带对侧证据，标待回放）。**没冲突不写行**：空文件不该被造出来。
     * 只读对方账本、只写互查自己的账——勘验层「只查不改」的纪律原样适用。
     */
    private fun mutualConflicts(root: File, findings: List<InspectFinding>, nowMs: Long) {
        val last = PatrolLedger.latest(root) ?: return
        val shadow = findings.map { MutualFinding(MutualSide.SHADOW, it.check, it.verdict, it.evidence) }
        val guard = last.findings.map { MutualFinding(MutualSide.GUARD, it.check, it.verdict, it.evidence) }
        val conflicts = PatrolMutual.conflicts(shadow, guard, nowMs)
        if (conflicts.isEmpty()) return
        val landed = conflicts.count { MutualLedger.append(root, MutualLedger.conflictJson(it)) }
        RunLog.log(
            "互查台账：暗卫本轮 × 金吾卫上次巡按（${last.id}）冲突入账 $landed/${conflicts.size} 行" +
                "（读回共 ${MutualLedger.read(root).size} 行）",
        )
    }

    /** 结论一行（§一⑤第四格）：零命中也要有字，样本不足照写。 */
    private fun conclusionOf(sampled: Int, hits: Int): String {
        val tail = if (sampled < NocturnalLimits.MIN_SAMPLE) {
            "（样本不足 n<${NocturnalLimits.MIN_SAMPLE}）"
        } else {
            ""
        }
        return "抽了 $sampled 条，假 PASS $hits 条$tail"
    }

    /**
     *
     * 为什么不带约束：511 §一② 的机会条件是"电量 ≥30% **或** 充电中"，WorkManager 的
     * `setRequiresCharging` 只能表达"且"——那样没插电但电量 80% 的机器就永远等不到下限那一趟。
     * 所以约束一句话不设，条件全在 [Opportunistic] 里当场验（醒来先看账，不满足就什么都不做）。
     */
    fun ensureScheduled(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            val request = PeriodicWorkRequestBuilder<NocturnalAutoWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(app)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            // 老机器队列里 432 那条旧周期位（每周＋充电＋WiFi）撤掉：否则两条周期位并存。
            WorkManager.getInstance(app).cancelUniqueWork(LEGACY_WORK_NAME)
            RunLog.log("暗卫自动档：周期位已排（$WORK_NAME，每 $PERIOD_HOURS 小时看一次账，条件齐才跑）")
        }.onFailure {
            // 排不上不是事故（还有手动档与月位体检），但要留一行——否则"从不抽检"只能靠猜。
            RunLog.log("暗卫自动档：周期位没排上——${it.javaClass.simpleName}: ${it.message}")
        }
    }
}

/**
 * 周期位的执行体：WorkManager 到点（每天一次）时看账这一趟。
 *
 * `Result.retry()` 只接"抛了异常"的可恢复失败；判据说"这趟不跑"（null）不是失败——
 * 重试改变不了判据的结论，下一个窗口自然再来（同 [HealthWorker] 的口径）。
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
