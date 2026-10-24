// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 定时任务的纯判据层（第一百六十七批 §3）——时间数学、去重、降级路由都在这一个 object 里。
 *
 * 为什么全部做成纯函数（只吃毫秒与字符串，不吃 Context）：
 * 时区 / 夏令时 / 跨天 / 错过窗口这些账，在 JVM 上就能算、也必须算准；
 * 一旦掺进 Android API 就测不动了，而本批的验收判据恰恰全在这几条上（派工单 §3 判据、§5 ①③）。
 */
object ScheduleRules {

    /** 晚于锚点这么多还算「准点」（WorkManager 的弹性延迟、JobScheduler 的最小粒度都在这儿兜住）。 */
    const val GRACE_MS = 15 * 60 * 1000L

    /**
     * 错过之后还愿意补跑的最长时间窗。
     * 超出就只标「已错过」不再发（派工单 §1：下次打开补跑一次或标记已错过，不刷屏 ——
     * 关机一周再开机，不该一次性往会话里灌七条）。
     */
    const val BACKFILL_WINDOW_MS = 6 * 60 * 60 * 1000L

    /** 同一个槽位最多试几次（全局单活跃门被别的会话长期占着时的封顶，防无限重试）。 */
    const val MAX_ATTEMPTS = 4

    /** WorkManager 唯一名 / 闹钟 data 的前缀（同一个任务改一次时间就换一个名，不会自我取消）。 */
    const val WORK_NAME_PREFIX = "zd-sched-"

    /** 一次性任务算不出未来时刻时的哨兵值（列表显示「没有下次」）。 */
    const val NO_RUN = 0L

    private val SLOT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    /** 一次 pass 对某个任务的处理结论。 */
    enum class Reason {
        /** 关着的，不看。 */
        DISABLED,

        /** 没有锚点（一次性任务已经跑完）。 */
        NO_ANCHOR,

        /** 还没到点。 */
        NOT_DUE,

        /** 到点，发。 */
        FIRE,

        /** 到点晚了超过 [GRACE_MS]，仍发，但记成补跑。 */
        FIRE_LATE,

        /** 这个槽位已经发过了（精确闹钟与 WorkManager 兜底谁先到谁算，另一趟走这条）。 */
        ALREADY_FIRED,

        /** 超过 [BACKFILL_WINDOW_MS]，只标错过。 */
        MISSED_EXPIRED,

        /** 同一个槽位重试到 [MAX_ATTEMPTS] 还没进去，记失败并翻篇。 */
        RETRY_LIMIT,
    }

    /** 触发走哪条路（派工单 §5 ①）。 */
    enum class Route {
        /** 有精确闹钟权限：闹钟准点打，WorkManager 只当兜底（闹钟被系统丢掉时补）。 */
        EXACT_ALARM_PLUS_WORK,

        /** 没给精确闹钟：只走 WorkManager，页面明示「可能不准点」。 */
        WORK_ONLY,
    }

    /**
     * 权限决定路由 —— 就这一句，但它是「默认非精确、给了权限才准点」这条口径的唯一出处，
     * 页面文案与调度器必须都读它，不许两处各判一次（本仓 E2「消双判」的同款教训）。
     */
    fun routeOf(canScheduleExactAlarms: Boolean): Route =
        if (canScheduleExactAlarms) Route.EXACT_ALARM_PLUS_WORK else Route.WORK_ONLY

    /**
     * 严格晚于 [fromMs] 的下一次触发时刻（epoch ms），按任务自己的时区算；没有下次 ⇒ [NO_RUN]。
     *
     * 夏令时口径（java.time 的既定语义，不自己发明）：
     * - 春季跳表（本地那个时刻不存在）⇒ `atZone` 把它顺延到跳表之后的真实时刻（02:30 → 03:30），
     *   宁可晚也不丢这一天；
     * - 秋季回拨（本地那个时刻出现两次）⇒ `atZone` 取**第一个**（较早的那个偏移）。
     */
    fun nextRunAfter(
        freq: ScheduleFreq,
        minuteOfDay: Int,
        dayOfWeek: Int,
        onceDate: String,
        zone: String,
        fromMs: Long,
    ): Long {
        if (minuteOfDay !in 0..1439) return NO_RUN
        val z = runCatching { ZoneId.of(zone) }.getOrNull() ?: return NO_RUN
        val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        if (freq == ScheduleFreq.ONCE) {
            val date = runCatching { LocalDate.parse(onceDate) }.getOrNull() ?: return NO_RUN
            val at = LocalDateTime.of(date, time).atZone(z).toInstant().toEpochMilli()
            return if (at > fromMs) at else NO_RUN
        }
        // 每天最多往后找 1 天，每周最多找 7 天；找不到就是数据本身不成立（返回哨兵）。
        val horizon = if (freq == ScheduleFreq.DAILY) 1 else 7
        val startDate = Instant.ofEpochMilli(fromMs).atZone(z).toLocalDate()
        for (i in 0..horizon) {
            val date = startDate.plusDays(i.toLong())
            if (freq == ScheduleFreq.WEEKLY && date.dayOfWeek.value != dayOfWeek) continue
            val at = LocalDateTime.of(date, time).atZone(z).toInstant().toEpochMilli()
            if (at > fromMs) return at
        }
        return NO_RUN
    }

    /**
     * 槽位标签 = 锚点时刻在该任务时区里的本地读法。
     *
     * 它就是去重键（派工单 §5 ③「同一次只补一次」靠这一个字符串）：
     * 两条触发路径、重试、重启后的补跑，全部拿它跟 [ScheduleTask.firedSlot] 对。
     */
    fun slotOf(anchorMs: Long, zone: String): String {
        if (anchorMs <= NO_RUN) return ""
        val z = runCatching { ZoneId.of(zone) }.getOrNull() ?: return ""
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(anchorMs), z).format(SLOT_FORMAT)
    }

    /**
     * 调度对象的名字：任务 id + 槽位，同一个槽位只有一个名字（重排不取消正在跑的那一趟）。
     *
     * 冒号换成连字符：这个名字既当 WorkManager 的唯一名，又当闹钟 `PendingIntent` 的
     * **data URI** 里那一段（`zd-sched://<name>`）。`Uri` 的 authority 段里冒号是端口分隔符，
     * 留着它就把一个可读的 URI 变成解析结果随实现而变的东西——这里不需要那个风险。
     * 去重键仍是 [slotOf] 原样（带冒号），两处不共用一个字符串。
     */
    fun slotName(taskId: String, anchorMs: Long, zone: String): String =
        (WORK_NAME_PREFIX + taskId + "-" + slotOf(anchorMs, zone)).replace(':', '-')

    /** 到没到点、该怎么处置（纯函数，输入是「现在」与任务本身）。 */
    fun reasonOf(nowMs: Long, task: ScheduleTask): Reason {
        if (!task.enabled) return Reason.DISABLED
        if (task.nextRunAtMs <= NO_RUN) return Reason.NO_ANCHOR
        if (nowMs < task.nextRunAtMs) return Reason.NOT_DUE
        val due = task.nextRunAtMs
        if (task.firedSlot.isNotEmpty() && task.firedSlot == slotOf(due, task.zone)) {
            return Reason.ALREADY_FIRED
        }
        if (nowMs - due > BACKFILL_WINDOW_MS) return Reason.MISSED_EXPIRED
        if (task.attempts >= MAX_ATTEMPTS) return Reason.RETRY_LIMIT
        return if (nowMs - due > GRACE_MS) Reason.FIRE_LATE else Reason.FIRE
    }

    /** 触发之后：锚点推到下一次（一次性任务推到 [NO_RUN] ⇒ 由调用方置 enabled=false）。 */
    fun nextAnchor(task: ScheduleTask, afterMs: Long): Long = nextRunAfter(
        freq = task.freq,
        minuteOfDay = task.minuteOfDay,
        dayOfWeek = task.dayOfWeek,
        onceDate = task.onceDate,
        zone = task.zone,
        fromMs = afterMs,
    )

    /** 这一趟要不要再排一次（一次性任务跑完就不排了）。 */
    fun shouldReschedule(task: ScheduleTask): Boolean = task.freq != ScheduleFreq.ONCE

    // ── 状态迁移（也全是纯函数：列表里「上次结果」怎么变、什么时候翻篇，都在这里定） ──────

    /**
     * 发出去之后（或判定这一趟「不再试」之后）的账：
     * 记槽位、推进锚点、一次性任务就此关掉。
     *
     * `firedSlot` 一定要写——它就是「同一次只补一次」的那道锁；
     * 锚点一定要推进——否则下一趟读到同一个过去时刻会当成又一次该发。
     */
    fun afterHandled(
        task: ScheduleTask,
        nowMs: Long,
        outcome: ScheduleOutcome,
        detail: String,
        sessionId: Long,
        ran: Boolean,
    ): ScheduleTask {
        val slot = slotOf(task.nextRunAtMs, task.zone)
        val next = nextAnchor(task, maxOf(nowMs, task.nextRunAtMs))
        return task.copy(
            firedSlot = slot,
            lastRunAtMs = nowMs,
            lastOutcome = outcome,
            lastDetail = detail,
            sessionId = if (sessionId > NO_RUN) sessionId else task.sessionId,
            runCount = if (ran) task.runCount + 1 else task.runCount,
            attempts = 0,
            nextRunAtMs = next,
            // 一次性任务没有「下一次」，处理完就停（列表里灰掉，不会到点又冒出来）。
            enabled = task.freq != ScheduleFreq.ONCE,
        )
    }

    /** 这个槽位没发出去、下一趟还要再试一次（重试次数封顶在 [reasonOf] 里判）。 */
    fun afterRetry(task: ScheduleTask, detail: String): ScheduleTask =
        task.copy(attempts = task.attempts + 1, lastDetail = detail)

    /**
     * 关掉又打开：补上锚点再回表，历史账（上次结果、绑定会话）不清。
     * 打开时锚点必须重算，否则一个关了很久的事务一开就照着旧时刻「立刻」补发。
     */
    fun afterEnable(task: ScheduleTask, nowMs: Long): ScheduleTask = task.copy(
        enabled = true,
        nextRunAtMs = nextAnchor(task, nowMs),
        attempts = 0,
    )

    /**
     * 暂停：锚点留着（列表要显示「下次」），但这一槽的重试计数清零、不再排系统对象。
     * 为什么不把 `nextRunAtMs` 清成 [NO_RUN]：清了就等于把用户填的时间意图丢掉，
     * 重新打开时 [afterEnable] 没得算，只能让他重填一遍。
     */
    fun afterDisable(task: ScheduleTask): ScheduleTask = task.copy(
        enabled = false,
        attempts = 0,
    )

    /** 锚点落在「今天 / 明天 / 别的日期」（界面按这个取文案，纯判据层不产字）。 */
    enum class DayOffset { NONE, TODAY, TOMORROW, OTHER }

    fun dayOffsetOf(anchorMs: Long, zone: String, nowMs: Long): DayOffset {
        if (anchorMs <= NO_RUN) return DayOffset.NONE
        val z = runCatching { ZoneId.of(zone) }.getOrNull() ?: return DayOffset.NONE
        val target = Instant.ofEpochMilli(anchorMs).atZone(z).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(z).toLocalDate()
        return when (target) {
            today -> DayOffset.TODAY
            today.plusDays(1) -> DayOffset.TOMORROW
            else -> DayOffset.OTHER
        }
    }

    /** 锚点在该任务时区里的本地读法（列表显示与「今天/明天」都从这儿来）。 */
    fun localOf(anchorMs: Long, zone: String): LocalDateTime? {
        if (anchorMs <= NO_RUN) return null
        val z = runCatching { ZoneId.of(zone) }.getOrNull() ?: return null
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(anchorMs), z)
    }

    /** 任务时区是否就是手机当前时区（不是的话列表要显式带上时区，免得用户按手机点读错）。 */
    fun isDeviceZone(zone: String): Boolean =
        runCatching { ZoneId.of(zone) }.getOrNull() == ZoneId.systemDefault()

    /** 分钟数 → 本地时间（界面自己格式化，或自己拆时分）。 */
    fun localTimeOf(minuteOfDay: Int): LocalTime =
        LocalTime.of(minuteOfDay.coerceIn(0, 1439) / 60, minuteOfDay.coerceIn(0, 1439) % 60)
}
