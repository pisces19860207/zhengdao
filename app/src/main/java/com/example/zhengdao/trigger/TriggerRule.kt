// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import com.example.zhengdao.schedule.ScheduleFreq
import com.example.zhengdao.schedule.ScheduleOutcome
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条事件触发规则（第三百九十九批）。
 *
 * 形状刻意与 167 的 `ScheduleTask` 同族但不共型：定时那半边（频率/时刻/时区）的
 * **时间数学**复用 `ScheduleRules.nextRunAfter` / `slotOf`，不写第二套；但规则里多出
 * 日历源的字段（`leadMinutes`），且整套账（源级开关、限频、事件账）归本包——
 * 派工单 §二「四条硬规矩」只挂在这层，不往 167 里渗。
 *
 * 时间怎么存（同 167 口径）：存**本地意图**（minuteOfDay/dayOfWeek/onceDate + zone），
 * `nextWakeAtMs` 是「下一次该叫醒的绝对时刻」，由 [TriggerRules] 算出来当待办锚点。
 *
 * `firedKey` 是去重锚（派工单 §二③「重复事件合并成一条」）：
 * - SCHED = `ScheduleRules.slotOf(锚点, zone)`（与 167 同款：闹钟与兜底谁先到谁算）；
 * - CALENDAR = `cal:<eventId>@<beginMs>`（同一个日历事件只叫一次，改了提醒时间算新事件）。
 */
data class TriggerRule(
    val id: String,
    val source: TriggerSource,
    val title: String,
    /** 到点发给引擎的那句话——与用户在输入框敲的字走同一个入口，不做第二套执行体。 */
    val prompt: String,
    val enabled: Boolean,
    /** SCHED 用：频率形状（复用 167 的三格）；CALENDAR 忽略此字段（存 ONCE 占位）。 */
    val freq: ScheduleFreq,
    /** SCHED 用：0..1439 本地分钟。 */
    val minuteOfDay: Int,
    /** SCHED WEEKLY 用：ISO 星期几 1..7；其余 0。 */
    val dayOfWeek: Int,
    /** SCHED ONCE 用：ISO 日期 yyyy-MM-dd；其余空串。 */
    val onceDate: String,
    val zone: String,
    /** CALENDAR 用：开始前多少分钟叫醒（[TriggerRules.LEAD_MIN]..[TriggerRules.LEAD_MAX]）。 */
    val leadMinutes: Int,
    /** 下一次该叫醒的时刻（epoch ms）；0 = 没有待办（一次性跑完或被关掉）。 */
    val nextWakeAtMs: Long,
    /** 已触发过的去重锚；空串 = 从没触发。 */
    val firedKey: String,
    val lastRunAtMs: Long,
    val lastOutcome: ScheduleOutcome,
    /** 失败/错过的可读短因（进工具 list 与账，不含模型正文）。 */
    val lastDetail: String,
    /** 这个规则最近一次叫醒绑定的抱朴会话 id；-1 = 还没建过。 */
    val sessionId: Long,
    val runCount: Int,
    /** 本槽位已尝试的次数（派工单 §二③「限频」之外的第二道不刷屏闸，同 167 口径）。 */
    val attempts: Int,
    val createdAtMs: Long,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(K_ID, id)
        put(K_SOURCE, source.wire)
        put(K_TITLE, title)
        put(K_PROMPT, prompt)
        put(K_ENABLED, enabled)
        put(K_FREQ, freq.wire)
        put(K_MINUTE, minuteOfDay)
        put(K_DAY_OF_WEEK, dayOfWeek)
        put(K_ONCE_DATE, onceDate)
        put(K_ZONE, zone)
        put(K_LEAD, leadMinutes)
        put(K_NEXT, nextWakeAtMs)
        put(K_FIRED_KEY, firedKey)
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
        const val K_SOURCE = "source"
        const val K_TITLE = "title"
        const val K_PROMPT = "prompt"
        const val K_ENABLED = "enabled"
        const val K_FREQ = "freq"
        const val K_MINUTE = "minuteOfDay"
        const val K_DAY_OF_WEEK = "dayOfWeek"
        const val K_ONCE_DATE = "onceDate"
        const val K_ZONE = "zone"
        const val K_LEAD = "leadMinutes"
        const val K_NEXT = "nextWakeAtMs"
        const val K_FIRED_KEY = "firedKey"
        const val K_LAST_RUN = "lastRunAtMs"
        const val K_LAST_OUTCOME = "lastOutcome"
        const val K_LAST_DETAIL = "lastDetail"
        const val K_SESSION = "sessionId"
        const val K_RUN_COUNT = "runCount"
        const val K_ATTEMPTS = "attempts"
        const val K_CREATED = "createdAtMs"

        /** 解一条；任何一处不合要求就返回 null（不救半条——半条规则会到点发话却没人看得见）。 */
        fun fromJson(o: JSONObject?): TriggerRule? {
            if (o == null) return null
            val id = o.optString(K_ID)
            val prompt = o.optString(K_PROMPT)
            if (id.isBlank() || prompt.isBlank()) return null
            val source = TriggerSource.fromWire(o.optString(K_SOURCE)) ?: return null
            val title = o.optString(K_TITLE).ifBlank { id }
            val freq = ScheduleFreq.fromWire(o.optString(K_FREQ)) ?: ScheduleFreq.ONCE
            val minute = o.optInt(K_MINUTE, 0)
            val zone = o.optString(K_ZONE)
            if (source == TriggerSource.SCHED && !validSched(freq, minute, o.optString(K_ONCE_DATE), zone)) {
                return null
            }
            val lead = o.optInt(K_LEAD, 0)
            if (source == TriggerSource.CALENDAR && lead !in TriggerRules.LEAD_MIN..TriggerRules.LEAD_MAX) {
                return null
            }
            val key = o.optString(K_FIRED_KEY)
            if (key.length > 128) return null
            return TriggerRule(
                id = id,
                source = source,
                title = title,
                prompt = prompt,
                enabled = o.optBoolean(K_ENABLED, false),
                freq = freq,
                minuteOfDay = minute,
                dayOfWeek = o.optInt(K_DAY_OF_WEEK, 0),
                onceDate = o.optString(K_ONCE_DATE),
                zone = zone,
                leadMinutes = lead,
                nextWakeAtMs = o.optLong(K_NEXT, 0L),
                firedKey = key,
                lastRunAtMs = o.optLong(K_LAST_RUN, 0L),
                lastOutcome = ScheduleOutcome.fromWire(o.optString(K_LAST_OUTCOME)),
                lastDetail = o.optString(K_LAST_DETAIL),
                sessionId = o.optLong(K_SESSION, -1L),
                runCount = o.optInt(K_RUN_COUNT, 0),
                attempts = o.optInt(K_ATTEMPTS, 0),
                createdAtMs = o.optLong(K_CREATED, 0L),
            )
        }

        fun listFromJson(text: String): List<TriggerRule>? = runCatching {
            val root = JSONObject(text)
            if (root.optInt("version") != TriggerStore.VERSION) return@runCatching null
            val arr = root.optJSONArray("rules") ?: return@runCatching null
            val out = ArrayList<TriggerRule>(arr.length())
            for (i in 0 until arr.length()) {
                out += fromJson(arr.optJSONObject(i)) ?: return@runCatching null
            }
            out
        }.getOrNull()

        fun listToJson(rules: List<TriggerRule>): String = JSONObject()
            .put("version", TriggerStore.VERSION)
            .put("rules", JSONArray().apply { rules.forEach { put(it.toJson()) } })
            .toString()

        /** SCHED 的完整性：时刻在界内、一次性给得出合法日期、时区认得出。 */
        private fun validSched(freq: ScheduleFreq, minute: Int, onceDate: String, zone: String): Boolean {
            if (minute !in 0..1439) return false
            if (freq == ScheduleFreq.WEEKLY && onceDate.isNotBlank()) return false
            if (freq == ScheduleFreq.ONCE && !Regex("""\d{4}-\d{2}-\d{2}""").matches(onceDate)) return false
            return runCatching { java.time.ZoneId.of(zone) }.isSuccess
        }
    }
}
