// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 频率（派工单 §1：一次 / 每天 / 每周）。
 *
 * `weekly` 在 v1 只支持一个星期几（与界面选择器一致）；要多个日子等真需要时另单。
 */
enum class ScheduleFreq(val wire: String) {
    ONCE("once"),
    DAILY("daily"),
    WEEKLY("weekly"),
    ;

    companion object {
        fun fromWire(v: String?): ScheduleFreq? = entries.firstOrNull { it.wire == v }
    }
}

/** 一次触发的结局（列表里「上次结果」那一列 + 失败通知的理由来源）。 */
enum class ScheduleOutcome(val wire: String) {
    /** 从没跑过（新建任务）。 */
    NONE(""),

    /** 准点触发并被引擎接走。 */
    FIRED("fired"),

    /** 补跑触发（过了宽限期才发出去，仍是同一次）。 */
    FIRED_LATE("firedLate"),

    /** 到点了但本会话已有轮次在跑，进了 FIFO 队列。 */
    QUEUED("queued"),

    /** 错过窗口，只标记不补跑。 */
    MISSED("missed"),

    /** 全局单活跃门被别的会话占着，重试到上限仍没进去。 */
    REFUSED_BUSY("refusedBusy"),

    /** 发送抛异常（装配失败等），详情写在 lastDetail。 */
    FAILED("failed"),
    ;

    /** 要不要给用户说一声：失败与错过都不许静默（派工单 §1）。 */
    val needsNotice: Boolean
        get() = this == REFUSED_BUSY || this == FAILED

    companion object {
        fun fromWire(v: String?): ScheduleOutcome =
            entries.firstOrNull { it.wire == v } ?: NONE
    }
}

/**
 * 定时任务（第一百六十七批 §1）。
 *
 * 来历：v1 的存储是独立文件 `files/schedule_tasks.json`，不是 `baopu/BaopuDb.kt` 的表
 * ——172-L4 正在那个文件上做 V19 迁移，本批不许抬库版本（派工单 §0.5 约束 1），并表另单。
 *
 * 时间怎么存：存的是**本地意图**（`minuteOfDay` / `dayOfWeek` / `onceDate` ＋ `zone`），
 * 不是绝对毫秒；`nextRunAtMs` 是「下一次该发生的那个绝对时刻」，由 [ScheduleRules] 算出来后
 * 存下来当**待办锚点**用。为什么必须存：设备关掉三天后，「严格晚于现在的那次」会把这三天
 * 该跑的东西直接变没得，错过检测就失效了。
 *
 * `firedSlot` 是去重键（见 [ScheduleRules.slotOf]）：精确闹钟与 WorkManager 兜底那一趟
 * 谁先到谁算，另一趟读到同一个槽位就什么都不做。
 */
data class ScheduleTask(
    val id: String,
    val title: String,
    val prompt: String,
    val freq: ScheduleFreq,
    /** 每天/每周的那个本地时刻：0..1439 分钟。 */
    val minuteOfDay: Int,
    /** WEEKLY 用：ISO 星期几，1=周一 … 7=周日；其它频率为 0。 */
    val dayOfWeek: Int,
    /** ONCE 用：ISO 日期 yyyy-MM-dd；其它频率为空串。 */
    val onceDate: String,
    /** 时区 id（Asia/Shanghai 之类）；错过跨时区时按它算，不跟手机当前时区漂移。 */
    val zone: String,
    val enabled: Boolean,
    /** 下一次该发生的时刻（epoch ms）；0 = 没有待办（一次性任务跑完或被关掉）。 */
    val nextRunAtMs: Long,
    /** 已触发过的那个槽位标签；空串 = 从没触发。 */
    val firedSlot: String,
    val lastRunAtMs: Long,
    val lastOutcome: ScheduleOutcome,
    /** 失败/错过的可读短因（进通知与列表，不含模型正文）。 */
    val lastDetail: String,
    /** 这个任务绑定的抱朴会话 id；-1 = 还没建过。 */
    val sessionId: Long,
    val runCount: Int,
    /** 本槽位已尝试的次数（派工单 §1「不刷屏」靠它封顶）。 */
    val attempts: Int,
    val createdAtMs: Long,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(K_ID, id)
        put(K_TITLE, title)
        put(K_PROMPT, prompt)
        put(K_FREQ, freq.wire)
        put(K_MINUTE, minuteOfDay)
        put(K_DAY_OF_WEEK, dayOfWeek)
        put(K_ONCE_DATE, onceDate)
        put(K_ZONE, zone)
        put(K_ENABLED, enabled)
        put(K_NEXT, nextRunAtMs)
        put(K_FIRED_SLOT, firedSlot)
        put(K_LAST_RUN, lastRunAtMs)
        put(K_LAST_OUTCOME, lastOutcome.wire)
        put(K_LAST_DETAIL, lastDetail)
        put(K_SESSION, sessionId)
        put(K_RUN_COUNT, runCount)
        put(K_ATTEMPTS, attempts)
        put(K_CREATED, createdAtMs)
    }

    companion object {
        const val K_ID = "id"
        const val K_TITLE = "title"
        const val K_PROMPT = "prompt"
        const val K_FREQ = "freq"
        const val K_MINUTE = "minuteOfDay"
        const val K_DAY_OF_WEEK = "dayOfWeek"
        const val K_ONCE_DATE = "onceDate"
        const val K_ZONE = "zone"
        const val K_ENABLED = "enabled"
        const val K_NEXT = "nextRunAtMs"
        const val K_FIRED_SLOT = "firedSlot"
        const val K_LAST_RUN = "lastRunAtMs"
        const val K_LAST_OUTCOME = "lastOutcome"
        const val K_LAST_DETAIL = "lastDetail"
        const val K_SESSION = "sessionId"
        const val K_RUN_COUNT = "runCount"
        const val K_ATTEMPTS = "attempts"
        const val K_CREATED = "createdAtMs"

        /**
         * 解一条；任何一处不合要求就返回 null（**不做「能救一半就救一半」**——
         * 半条任务比整表回退空表更危险，用户会以为它还在跑）。
         */
        fun fromJson(o: JSONObject?): ScheduleTask? {
            if (o == null) return null
            val id = o.optString(K_ID)
            val title = o.optString(K_TITLE)
            val prompt = o.optString(K_PROMPT)
            if (id.isBlank() || prompt.isBlank()) return null
            val freq = ScheduleFreq.fromWire(o.optString(K_FREQ)) ?: return null
            val minute = if (o.has(K_MINUTE)) o.getInt(K_MINUTE) else return null
            if (minute !in 0..1439) return null
            val dayOfWeek = o.optInt(K_DAY_OF_WEEK, 0)
            if (freq == ScheduleFreq.WEEKLY && dayOfWeek !in 1..7) return null
            val onceDate = o.optString(K_ONCE_DATE)
            if (freq == ScheduleFreq.ONCE && !isIsoDate(onceDate)) return null
            val zone = o.optString(K_ZONE)
            if (!isZone(zone)) return null
            val slot = o.optString(K_FIRED_SLOT)
            if (slot.length > 32) return null
            return ScheduleTask(
                id = id,
                title = title,
                prompt = prompt,
                freq = freq,
                minuteOfDay = minute,
                dayOfWeek = dayOfWeek,
                onceDate = onceDate,
                zone = zone,
                enabled = o.optBoolean(K_ENABLED),
                nextRunAtMs = if (o.has(K_NEXT)) o.getLong(K_NEXT) else return null,
                firedSlot = slot,
                lastRunAtMs = o.optLong(K_LAST_RUN, 0L),
                lastOutcome = ScheduleOutcome.fromWire(o.optString(K_LAST_OUTCOME)),
                lastDetail = o.optString(K_LAST_DETAIL),
                sessionId = o.optLong(K_SESSION, -1L),
                runCount = o.optInt(K_RUN_COUNT, 0),
                attempts = o.optInt(K_ATTEMPTS, 0),
                createdAtMs = o.optLong(K_CREATED, 0L),
            )
        }

        /** 整份文件：`{"version":1,"tasks":[…]}`；版本不认识或结构不对 ⇒ null（整表按损坏处理）。 */
        fun listFromJson(text: String): List<ScheduleTask>? {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (root.optInt(ScheduleStore.KEY_VERSION, -1) != ScheduleStore.VERSION) return null
            val arr = root.optJSONArray(ScheduleStore.KEY_TASKS) ?: return null
            val out = ArrayList<ScheduleTask>(arr.length())
            for (i in 0 until arr.length()) {
                val t = fromJson(arr.optJSONObject(i)) ?: return null
                out += t
            }
            return out
        }

        fun listToJson(tasks: List<ScheduleTask>): String {
            val arr = JSONArray()
            tasks.forEach { arr.put(it.toJson()) }
            return JSONObject().apply {
                put(ScheduleStore.KEY_VERSION, ScheduleStore.VERSION)
                put(ScheduleStore.KEY_TASKS, arr)
            }.toString()
        }

        private fun isIsoDate(v: String): Boolean = runCatching {
            LocalDate.parse(v) != null
        }.getOrDefault(false) && v.length <= 10

        private fun isZone(v: String): Boolean = v.isNotBlank() && runCatching {
            ZoneId.of(v) != null
        }.getOrDefault(false) && v.length <= 64

        /** 新建时的空壳（下次时刻由调用方按 [ScheduleRules.nextRunAfter] 算）。 */
        fun draft(
            id: String,
            title: String,
            prompt: String,
            freq: ScheduleFreq,
            minuteOfDay: Int,
            dayOfWeek: Int,
            onceDate: String,
            zone: String,
            nextRunAtMs: Long,
            nowMs: Long,
        ): ScheduleTask = ScheduleTask(
            id = id,
            title = title,
            prompt = prompt,
            freq = freq,
            minuteOfDay = minuteOfDay,
            dayOfWeek = dayOfWeek,
            onceDate = onceDate,
            zone = zone,
            enabled = true,
            nextRunAtMs = nextRunAtMs,
            firedSlot = "",
            lastRunAtMs = 0L,
            lastOutcome = ScheduleOutcome.NONE,
            lastDetail = "",
            sessionId = -1L,
            runCount = 0,
            attempts = 0,
            createdAtMs = nowMs,
        )

        /** 编辑表单里用户能改的那几项（其余字段沿用旧值：账不能因为改一次文案就丢）。 */
        fun withEdit(
            old: ScheduleTask,
            title: String,
            prompt: String,
            freq: ScheduleFreq,
            minuteOfDay: Int,
            dayOfWeek: Int,
            onceDate: String,
            nextRunAtMs: Long,
        ): ScheduleTask = old.copy(
            title = title,
            prompt = prompt,
            freq = freq,
            minuteOfDay = minuteOfDay,
            dayOfWeek = dayOfWeek,
            onceDate = onceDate,
            nextRunAtMs = nextRunAtMs,
            // 改了时间就是换了槽位：旧的触发记录留着（lastRun*），但去重键要作废重算，
            // 否则「今天 08:00 已经跑过」会把新设的 08:00 判成已触发。
            firedSlot = "",
            attempts = 0,
        )

        /** 解析一次时间意图所需的最低校验（UI 保存前也用它，判据只有一处）。 */
        fun validate(freq: ScheduleFreq, minuteOfDay: Int, dayOfWeek: Int, onceDate: String, zone: String): String? = when {
            minuteOfDay !in 0..1439 -> "minute"
            freq == ScheduleFreq.WEEKLY && dayOfWeek !in 1..7 -> "dayOfWeek"
            freq == ScheduleFreq.ONCE && !isIsoDate(onceDate) -> "onceDate"
            !isZone(zone) -> "zone"
            else -> null
        }
    }
}
