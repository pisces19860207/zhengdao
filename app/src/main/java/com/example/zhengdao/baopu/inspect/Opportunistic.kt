// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第五百一十一批 · **机会式触发的判据层**（暗卫 432 ＋ 巡按 478 合并成一套）。
//
// 用户 2026-11-05：「巡逻部队也可以随时巡逻啊」＋「都改成自动吧」。所以"什么时候跑"
// 不再挂在"夜里某点／每周某天"上，而是三条触发并存：
//   ① 手动（人当场点，随时）；
//   ② 按负载（累计够 N 个任务或 M 次工具调用）；
//   ③ 7 天下限（一直没负载也要跑一次，保底）。
// 三条都要过同一套**机会条件**（非值守 · 电量 ≥20% 或充电中 · 不发烫 · 前台不在会话 ·
// 30 分钟无自动动作 · 没在跑重活），
// 再加各 6h 冷却（手动不受限，两件冷却互相独立）。
//
// 第四百七十九批：**总开关那一层删了**（用户 2026-11-08「别弄太多入口或者开关」）——
// 后台维护恒自动，管的只有两级刹车（`MaintBrake`）与单件暂停；本文件只负责"什么时候该跑"，
// 一句"关不关"都不问了。同批把电量线从 30% 收到 20%，并新增两条机会条件（发烫／前台会话）。
//
// 本文件零 Android、零时钟、零读写：时刻、负载、机会读数全由调用方给，用例才钉得住。
// 装配（排作业、读电池、落账）在 `NocturnalAuto`／`PatrolAuto`，这里只说"该不该跑、为什么"。
//
// 为什么不做常驻轮询（派工单 ④）：负载那一截由**会话收工事件**驱动（`TriggerWiring.onRunSettled`），
// 7 天下限那一截交给现有 WorkManager 周期作业（每 [CHECK_PERIOD_HOURS] 小时醒一次看账，
// 醒来先过判据，不满足就一句话都不做）。定时器一律 ≥ 5 分钟（判据⑥由源码门禁钉）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.maintenance.Opportunity
import java.util.concurrent.atomic.AtomicReference

/** 后台维护的两件（职责各自不动：暗卫＝抽检（轻），巡按＝六项全跑（重））。 */
internal enum class MaintJob(
    val wire: String,
    val cn: String,
    /** 负载阈：累计完成这么多个任务就够一趟。 */
    val loadTasks: Int,
    /** 负载阈：或累计这么多次工具调用。 */
    val loadCalls: Int,
    /** 各自的冷却（两件互相独立；手动不受限）。 */
    val cooldownMs: Long,
) {
    NIGHTWATCH("nightwatch", "暗卫", 20, 200, Opportunistic.COOLDOWN_MS),
    PATROL("patrol", "巡按", 10, 100, Opportunistic.COOLDOWN_MS),
    ;

    companion object {
        fun ofWire(wire: String): MaintJob? = entries.firstOrNull { it.wire == wire }
    }
}

/** 这趟是谁让它跑起来的（落账第一格；旧账没有这格 ⇒ 按 [wire] 空串读，不冒充）。 */
internal enum class TriggerReason(val wire: String, val cn: String) {
    MANUAL("manual", "手动"),
    LOAD("load", "负载"),
    FLOOR("floor", "下限"),
}

/** 谁在问这一趟：人手／会话收工事件／周期作业（决定"到期"算哪一条）。 */
internal enum class AskSource { MANUAL, EVENT, SCHEDULED }

/** 负载读数：自上次这一件跑完之后，累计了多少任务与工具调用。 */
internal data class Load(val tasks: Int, val toolCalls: Int) {
    companion object {
        val NONE = Load(0, 0)
    }
}

/** 判据结论（[why] 永远有字——"不跑"也要能一眼看出为什么不跑）。 */
internal data class AutoGate(val run: Boolean, val why: String, val trigger: TriggerReason? = null)

/** 机会式触发的判据（纯函数；两件共用同一套）。 */
internal object Opportunistic {

    /** 机会条件之一：电量下限（低于它且没充电就不跑；等于线也够）。479 由 30% 收到 20%。 */
    const val MIN_BATTERY_PCT: Int = 20

    /** 机会条件之二：距上一次后台自动动作至少这么久（别刚跑完马上又跑）。 */
    const val QUIET_MS: Long = 30L * 60_000L

    /** 冷却下限（两件各自 6 小时；手动不受限）。 */
    const val COOLDOWN_MS: Long = 6L * 3_600_000L

    /** 7 天下限（保底：一直没负载也要跑一次）。 */
    const val FLOOR_MS: Long = 7L * 24L * 3_600_000L

    /**
     * 周期作业的间隔（小时）。
     *
     * 为什么是"天"而不是"分钟"：这一趟只是**看账**（读两本小账＋一次判据），
     * 命中条件才真跑；判据⑥要求"没有 5 分钟以内的定时"，24 小时是最省的一种兜底。
     */
    const val CHECK_PERIOD_HOURS: Long = 24L

    const val WHY_HEAVY: String = "已有一件后台重活在跑（同一时刻只许一件）"
    const val WHY_ON_DUTY: String = "值守中（非值守才跑）"
    const val WHY_HOT: String = "设备发烫（热档已到限流那一档：先让开，凉了再说）"
    const val WHY_TALKING: String = "前台正在会话（人在跟模型说话：不抢它的资源）"

    /**
     * 该不该跑这一趟。
     *
     * 顺序与派工单一致：**手动的例外 → 重活互斥 → 机会条件 → 冷却 → 到期**。
     * 手动只受互斥约束（人当场点的，别把两件重活叠在一起）；其余一条不满足就不跑。
     *
     * 479 起没有"总开关"这一格：`enabled` 参数与 `WHY_OFF` 一并删了——恒自动，
     * 拦它的是 [MaintBrake]（急停／单件暂停）和下面这几条机会条件。
     */
    fun decide(
        job: MaintJob,
        source: AskSource,
        nowMs: Long,
        lastRunAtMs: Long?,
        load: Load,
        opp: Opportunity,
    ): AutoGate {
        if (source == AskSource.MANUAL) return manualGate(opp)
        blockedBy(opp)?.let { return AutoGate(false, it) }
        cooling(job, lastRunAtMs, nowMs)?.let { return AutoGate(false, it) }
        return due(job, source, nowMs, lastRunAtMs, load)
    }

    /** 手动那一趟：不看开关、不看机会条件、不看冷却——只看别跟另一件重活撞上。 */
    private fun manualGate(opp: Opportunity): AutoGate =
        if (opp.heavyBusy) {
            AutoGate(false, WHY_HEAVY)
        } else {
            AutoGate(true, "手动触发（不受冷却与机会条件限制）", TriggerReason.MANUAL)
        }

    /**
     * 机会条件六条（派工单 §一②，同时满足才跑）。
     *
     * 479 新增两条：**发烫**（热档到限流那一档就让开）与**前台正在会话**（别跟人抢）。
     * 两条都是"延后"而不是"取消"——凉了、人退出会话，下一次机会照跑。
     *
     * @return null ＝ 全齐；非 null ＝ 第一条不满足的人话（只说拦下来的那一条，不列清单）
     */
    fun blockedBy(opp: Opportunity): String? = when {
        opp.heavyBusy -> WHY_HEAVY
        opp.onDuty -> WHY_ON_DUTY
        opp.hot -> WHY_HOT
        opp.talking -> WHY_TALKING
        !powerOk(opp) -> if (opp.batteryPct < 0) {
            "电量读不出来且没在充电（读不到就当不够）"
        } else {
            "电量 ${opp.batteryPct}% 不足 $MIN_BATTERY_PCT% 且没在充电"
        }
        opp.quietForMs < QUIET_MS ->
            "距上次后台自动动作 ${opp.quietForMs / 60_000L} 分钟（不满 ${QUIET_MS / 60_000L} 分钟）"
        else -> null
    }

    /**
     * 电源条件（§一② 那个"或"）：充电中，或电量 ≥ [MIN_BATTERY_PCT]；电量读不出 ⇒ 只认充电。
     *
     * 这个"或"只在这里定义一次：机会条件用它判拦不拦，暗卫交给 [NocturnalPatrol.gate] 的
     * `charging` 那一格也用它换算（409 那格的本意就是"电源条件成立"，那边话术仍是"不在充电"）。
     */
    fun powerOk(opp: Opportunity): Boolean = opp.charging || opp.batteryPct >= MIN_BATTERY_PCT

    /** 冷却（各管各的；从没跑过 ⇒ 不拦）。 */
    fun cooling(job: MaintJob, lastRunAtMs: Long?, nowMs: Long): String? {
        if (lastRunAtMs == null) return null
        val gap = nowMs - lastRunAtMs
        if (gap >= job.cooldownMs || gap < 0) return null
        val hours = job.cooldownMs / 3_600_000L
        return "冷却中（距上次 ${minutesCn(gap)}，${job.cn}下限 $hours 小时）"
    }

    /** 负载到阈了吗（任务或调用任一够数）。 */
    fun loadReached(job: MaintJob, load: Load): Boolean =
        load.tasks >= job.loadTasks || load.toolCalls >= job.loadCalls

    /** 7 天下限：从没跑过也算到期（首趟不欠着）。 */
    fun floorDue(lastRunAtMs: Long?, nowMs: Long): Boolean =
        lastRunAtMs == null || nowMs - lastRunAtMs >= FLOOR_MS

    /** 到期判定：负载优先（谁的证据先说谁），否则看这趟是谁在问。 */
    private fun due(
        job: MaintJob,
        source: AskSource,
        nowMs: Long,
        lastRunAtMs: Long?,
        load: Load,
    ): AutoGate {
        if (loadReached(job, load)) {
            return AutoGate(true, "负载到阈（任务 ${load.tasks}/${job.loadTasks}·调用 ${load.toolCalls}/${job.loadCalls}）", TriggerReason.LOAD)
        }
        val floor = floorDue(lastRunAtMs, nowMs)
        if (floor && source == AskSource.SCHEDULED) {
            return AutoGate(true, "7 天下限（距上次 ${daysSince(lastRunAtMs, nowMs)}）", TriggerReason.FLOOR)
        }
        val loadLine = "负载未到阈（任务 ${load.tasks}/${job.loadTasks}·调用 ${load.toolCalls}/${job.loadCalls}）"
        val tail = if (floor) {
            "；下限已到，等周期作业那趟"
        } else {
            "；未到 7 天下限（还差 ${remainDays(lastRunAtMs, nowMs)}）"
        }
        return AutoGate(false, loadLine + tail)
    }

    /** 机会读数压成一行（落账第二格：谁都能照着复现"当时是什么条件"）。 */
    fun readout(opp: Opportunity): String {
        val duty = if (opp.onDuty) "值守中" else "非值守"
        val power = when {
            opp.batteryPct < 0 -> "电量读不出"
            opp.charging -> "电量 ${opp.batteryPct}%·充电中"
            else -> "电量 ${opp.batteryPct}%·未充电"
        }
        val quiet = if (opp.quietForMs == Long.MAX_VALUE) {
            "从没自动跑过"
        } else {
            "静默 ${minutesCn(opp.quietForMs)}"
        }
        // 479：发烫／前台会话两格也进读数——"这趟为什么没跑"要能照着这一行复现。
        val heat = if (opp.hot) "发烫" else "温度正常"
        val talk = if (opp.talking) "前台会话中" else "无会话"
        return listOf(
            duty,
            power,
            heat,
            talk,
            quiet,
            if (opp.heavyBusy) "有重活在跑" else "无重活",
        ).joinToString("·")
    }

    /** 「N 天」那句（首趟如实说，不编一个 0）。 */
    private fun daysSince(lastRunAtMs: Long?, nowMs: Long): String {
        if (lastRunAtMs == null) return "从没跑过"
        return "${(nowMs - lastRunAtMs) / 86_400_000L} 天"
    }

    private fun remainDays(lastRunAtMs: Long?, nowMs: Long): String {
        if (lastRunAtMs == null) return "0 天"
        val left = FLOOR_MS - (nowMs - lastRunAtMs)
        return "${(left + 86_399_999L) / 86_400_000L} 天"
    }

    /** 毫秒 ⇒ 「X 小时 Y 分」／「Y 分钟」（落账与日志里给人看的那种）。 */
    private fun minutesCn(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 60_000L
        val h = total / 60
        val m = total % 60
        return if (h > 0) "$h 小时 $m 分" else "$m 分钟"
    }
}

/**
 * 后台重活的互斥（判据⑦：同一时刻只允许一件）。
 *
 * 为什么进程内一把锁就够：暗卫／巡按／体检三件都跑在同一个进程（WorkManager 的 Worker
 * 默认同进程），跨进程锁要文件锁——那是另一种复杂度，而这三件本来就是"App 自己的后台"。
 * 持锁者记名字：放锁时只认放锁的人，别让后进来的人把别人的锁放掉。
 */
internal object HeavyWork {

    private val holder = AtomicReference<String?>(null)

    /** 现在被谁持着（null ＝ 自由的）。 */
    fun heldBy(): String? = holder.get()

    /** 抢锁（抢不到就是抢不到，不排队、不重试——重活不该叠起来等）。 */
    fun tryEnter(owner: String): Boolean = holder.compareAndSet(null, owner)

    /** 放锁（只放自己的；别人的锁碰不到）。 */
    fun exit(owner: String) {
        holder.compareAndSet(owner, null)
    }
}
