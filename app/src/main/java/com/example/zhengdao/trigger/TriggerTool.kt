// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import com.example.zhengdao.baopu.agent.SENSITIVE_ACTION_WORDS
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.ToolTimeout
import com.example.zhengdao.baopu.agent.jsonArrayOf
import com.example.zhengdao.baopu.agent.jsonObjectOf
import com.example.zhengdao.baopu.agent.ScheduleWhenParser
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.schedule.ScheduleFreq
import com.example.zhengdao.schedule.ScheduleOutcome
import com.example.zhengdao.schedule.ScheduleRules
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** 工具侧的宿主（库与排程；不碰 Context——房型照 ScheduleToolSink）。 */
internal interface TriggerToolSink {
    /** 库已写完，重算锚点并重排系统闹钟（`TriggerRunner.rescheduleAll`）。 */
    fun reschedule()
}

/** 工具结果里要如实说出口的环境状态（源开关、日历权限）——装配层注入，工具层不摸 Context。 */
data class TriggerToolEnv(val schedOn: Boolean, val calendarOn: Boolean, val calendarGranted: Boolean)

/**
 * `event_trigger`：建/改/停事件触发规则（第三百九十九批「手机侧自己接活」的创建口）。
 *
 * 判据（派工单 §三 + 218 批 `schedule_task` 同款房型）：
 * 1. **必过确认卡**：permission=CONFIRM——卡上就是 args（哪个源、几点/提前几分钟、做什么），
 *    现有机制自动走，本工具没有任何「跳过确认直接写库」的支路；
 * 2. **复用既有判据**：定时侧的 when 解析复用 218 的 [ScheduleWhenParser]（不写第二套），
 *    时刻数学走 [TriggerRules]→`ScheduleRules`；源级开关/限频/去重/落账全在 [TriggerPass]；
 * 3. **源没开要说出来**：建规则 ≠ 源已开启——结果里必须提示开关与权限的现状，不许让
 *    「建了却永远不触发」装作建成了。
 */
internal object TriggerTool {

    const val ARG_ACTION = "action"
    const val ARG_SOURCE = "source"
    const val ARG_ID = "id"
    const val ARG_WHEN = "when"
    const val ARG_LEAD = "lead_minutes"
    const val ARG_PROMPT = "prompt"
    const val ARG_TITLE = "title"

    internal var sink: TriggerToolSink? = null
    internal var storeProvider: (() -> TriggerStore?)? = null
    internal var envProvider: (() -> TriggerToolEnv?)? = null

    fun spec() = ToolSpec(
        name = "event_trigger",
        description = "建/改/停事件触发规则（create/update/pause/resume/delete/list）。" +
            "source=sched 时 when 支持「每天 08:00」「每周三 09:30」「2026-11-01 21:00」；" +
            "source=calendar 时到指定日历事件开始前 lead_minutes 分钟触发。" +
            "触发 = 把 prompt 发进会话照着做。建与改都要你确认。" +
            "两个源在设置里默认全关（事件触发卡），没开就建了也不触发。" +
            "（这个工具在 SCHEDULE 组：看不到就先 capabilities 解锁）",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_ACTION to jsonObject("create / update / pause / resume / delete / list"),
                ARG_SOURCE to jsonObject("create 必填：sched（定时到点）/ calendar（日程开始前）"),
                ARG_ID to jsonObject("update/pause/resume/delete 必填：规则 id（list 里看）"),
                ARG_WHEN to jsonObject("source=sched 必填：什么时候（每天 08:00／每周三 09:30／2026-11-01 21:00）"),
                ARG_LEAD to jsonObject("source=calendar 必填：开始前多少分钟（1..720）"),
                ARG_PROMPT to jsonObject("create/update 可改：触发时要它做什么"),
                ARG_TITLE to jsonObject("可选：规则名（列表里显示）"),
            ),
            "required" to jsonArrayOf(ARG_ACTION),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM, // 必过确认卡
        riskTier = "R2",
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, System.currentTimeMillis()) }

    fun run(args: JSONObject, nowMs: Long): ToolOutcome {
        val store = storeProvider?.invoke()
            ?: return failed("触发层没接上（宿主没注入）——如实说，不假装修好了")
        return when (args.optString(ARG_ACTION)) {
            "list" -> list(store)
            "create" -> create(args, store, nowMs)
            "update" -> update(args, store, nowMs)
            "pause" -> setEnabled(store, args, false)
            "resume" -> setEnabled(store, args, true)
            "delete" -> delete(store, args)
            else -> failed("action 认不出：${args.optString(ARG_ACTION)}（允许：create/update/pause/resume/delete/list）")
        }
    }

    private fun list(store: TriggerStore): ToolOutcome {
        val env = envProvider?.invoke()
        val arr = JSONArray()
        store.load().forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("source", r.source.wire)
                    .put("title", r.title)
                    .put("when", describe(r))
                    .put("enabled", r.enabled)
                    .put("runCount", r.runCount)
                    .put("lastOutcome", r.lastOutcome.wire)
                    .put("lastDetail", r.lastDetail),
            )
        }
        val switches = env?.let {
            JSONObject().put("schedOn", it.schedOn).put("calendarOn", it.calendarOn).put("calendarGranted", it.calendarGranted)
        } ?: JSONObject()
        return ok(JSONObject().put("rules", arr).put("switches", switches).put("note", "源开关在 设置 → 事件触发；默认全关").toString(2))
    }

    private fun create(args: JSONObject, store: TriggerStore, nowMs: Long): ToolOutcome {
        val prompt = args.optString(ARG_PROMPT).ifBlank { return failed("缺 prompt（触发时要它做什么）") }
        val source = TriggerSource.fromWire(args.optString(ARG_SOURCE))
            ?: return failed("缺 source 或认不出（允许：sched / calendar）")
        val base = when (source) {
            TriggerSource.SCHED -> schedBase(args, nowMs) ?: return failed(
                "when 认不出——不许猜。支持：每天 08:00／每周三 09:30／2026-11-01 21:00（cron 尚不支持）",
            )
            TriggerSource.CALENDAR -> calBase(args)
                ?: return failed("缺 lead_minutes 或越界（开始前多少分钟，1..720）")
        }
        val title = args.optString(ARG_TITLE).ifBlank { "事件触发 ${source.wire}" }
        val rule = base.copy(id = newId(nowMs), title = title, prompt = prompt, createdAtMs = nowMs)
        val current = store.load()
        if (!store.save(current + rule)) return failed("写库失败（规则没建成）——不许以为保存成功")
        reschedule()
        RunLog.log("event_trigger: created id=${rule.id} source=${source.wire} title=$title")
        return ok(resultText(rule, "已创建（过了确认卡才落库）") + envHint(source) + sensitiveHint(prompt))
    }

    private fun update(args: JSONObject, store: TriggerStore, nowMs: Long): ToolOutcome {
        val id = args.optString(ARG_ID).ifBlank { return failed("缺 id（list 里看）") }
        val current = store.load()
        val rule = current.firstOrNull { it.id == id } ?: return failed("没有这条规则：$id")
        var next = rule
        args.optString(ARG_WHEN).takeIf { it.isNotBlank() }?.let { whenText ->
            if (rule.source != TriggerSource.SCHED) return failed("这条是日历源规则，没有 when；改 lead_minutes")
            val parsed = ScheduleWhenParser.parse(whenText)
                ?: return failed("when「$whenText」认不出——不许猜（同 create）")
            next = next.copy(freq = parsed.first, minuteOfDay = parsed.second, dayOfWeek = parsed.third.first, onceDate = parsed.third.second)
        }
        args.optInt(ARG_LEAD, -1).takeIf { it > 0 }?.let { lead ->
            if (rule.source != TriggerSource.CALENDAR) return failed("这条是定时源规则，没有 lead_minutes；改 when")
            next = next.copy(leadMinutes = lead)
        }
        args.optString(ARG_PROMPT).takeIf { it.isNotBlank() }?.let { next = next.copy(prompt = it) }
        args.optString(ARG_TITLE).takeIf { it.isNotBlank() }?.let { next = next.copy(title = it) }
        if (next.source == TriggerSource.SCHED) {
            next = next.copy(nextWakeAtMs = anchorOf(next, nowMs), firedKey = "", attempts = 0)
        }
        if (!store.update(next)) return failed("写库失败（没改上）")
        reschedule()
        return ok(resultText(next, "已更新（过了确认卡才落库）") + envHint(next.source) + sensitiveHint(next.prompt))
    }

    private fun setEnabled(store: TriggerStore, args: JSONObject, enabled: Boolean): ToolOutcome {
        val id = args.optString(ARG_ID).ifBlank { return failed("缺 id（list 里看）") }
        val current = store.load()
        val rule = current.firstOrNull { it.id == id } ?: return failed("没有这条规则：$id")
        val next = if (enabled) {
            rule.copy(enabled = true, attempts = 0, lastOutcome = ScheduleOutcome.NONE, lastDetail = "")
        } else {
            rule.copy(enabled = false)
        }
        if (!store.update(next)) return failed("写库失败（没改上）")
        reschedule()
        val what = if (enabled) "已恢复（锚点重排，停用期间的漏跑不补）" else "已暂停（不删，随时可恢复）"
        return ok(resultText(next, what))
    }

    private fun delete(store: TriggerStore, args: JSONObject): ToolOutcome {
        val id = args.optString(ARG_ID).ifBlank { return failed("缺 id（list 里看）") }
        val current = store.load()
        val rule = current.firstOrNull { it.id == id } ?: return failed("没有这条规则：$id")
        if (!store.save(current - rule)) return failed("写库失败（没删成）")
        reschedule()
        RunLog.log("event_trigger: deleted id=$id title=${rule.title}")
        return ok("已删除「${rule.title}」（$id，跑过 ${rule.runCount} 次）；下次不再触发。")
    }

    // ── 小件 ─────────────────────────────────────────────────────────────

    private fun schedBase(args: JSONObject, nowMs: Long): TriggerRule? {
        val parsed = ScheduleWhenParser.parse(args.optString(ARG_WHEN)) ?: return null
        val zone = ZoneId.systemDefault().id
        val base = TriggerRule(
            id = "", source = TriggerSource.SCHED, title = "", prompt = "", enabled = true,
            freq = parsed.first, minuteOfDay = parsed.second,
            dayOfWeek = parsed.third.first, onceDate = parsed.third.second, zone = zone,
            leadMinutes = 0, nextWakeAtMs = 0L, firedKey = "", lastRunAtMs = 0L,
            lastOutcome = ScheduleOutcome.NONE, lastDetail = "", sessionId = -1L,
            runCount = 0, attempts = 0, createdAtMs = 0L,
        )
        return base.copy(nextWakeAtMs = anchorOf(base, nowMs))
    }

    private fun calBase(args: JSONObject): TriggerRule? {
        val lead = args.optInt(ARG_LEAD, -1)
        if (lead !in TriggerRules.LEAD_MIN..TriggerRules.LEAD_MAX) return null
        return TriggerRule(
            id = "", source = TriggerSource.CALENDAR, title = "", prompt = "", enabled = true,
            freq = ScheduleFreq.ONCE, minuteOfDay = 0, dayOfWeek = 0, onceDate = "",
            zone = ZoneId.systemDefault().id, leadMinutes = lead,
            nextWakeAtMs = TriggerRules.NO_WAKE, firedKey = "", lastRunAtMs = 0L,
            lastOutcome = ScheduleOutcome.NONE, lastDetail = "", sessionId = -1L,
            runCount = 0, attempts = 0, createdAtMs = 0L,
        )
    }

    private fun anchorOf(rule: TriggerRule, nowMs: Long): Long =
        if (rule.freq == ScheduleFreq.ONCE) {
            ScheduleRules.nextRunAfter(rule.freq, rule.minuteOfDay, rule.dayOfWeek, rule.onceDate, rule.zone, nowMs)
        } else {
            TriggerRules.nextSchedAnchor(rule, nowMs)
        }

    private fun describe(r: TriggerRule): String = when (r.source) {
        TriggerSource.SCHED -> {
            val time = "%02d:%02d".format(r.minuteOfDay / 60, r.minuteOfDay % 60)
            when (r.freq) {
                ScheduleFreq.DAILY -> "每天 $time"
                ScheduleFreq.WEEKLY -> "每周${"一二三四五六日".getOrElse(r.dayOfWeek - 1) { '?' }} $time"
                ScheduleFreq.ONCE -> "${r.onceDate} $time（一次）"
            }
        }
        TriggerSource.CALENDAR -> "日程开始前 ${r.leadMinutes} 分钟"
    }

    /** 建了 ≠ 会触发：源开关与权限的现状必须如实说（不许「建了却永远不触发」装作建成）。 */
    private fun envHint(source: TriggerSource): String {
        val env = envProvider?.invoke() ?: return ""
        return when (source) {
            TriggerSource.SCHED -> if (!env.schedOn) "\n⚠️ 定时源开关还没开（设置 → 事件触发）：开了才会触发。" else ""
            TriggerSource.CALENDAR -> buildString {
                if (!env.calendarGranted) append("\n⚠️ 日历权限没给（设置 → 申请权限）：读不到日程。")
                if (!env.calendarOn) append("\n⚠️ 日程源开关还没开（设置 → 事件触发）：开了才会触发。")
            }
        }
    }

    private fun sensitiveHint(prompt: String): String =
        SENSITIVE_ACTION_WORDS.firstOrNull { prompt.contains(it) }
            ?.let { "\n⚠️ 触发内容含「$it」这类有真实世界后果的动作词——确认时看清楚，出问题的是无人值守的那一次。" }
            ?: ""

    private fun resultText(rule: TriggerRule, what: String): String =
        "「${rule.title}」${rule.id.take(8)}｜${describe(rule)}｜$what"

    private fun reschedule() {
        runCatching { sink?.reschedule() }
            .onFailure { RunLog.log("event_trigger: 重排闹钟失败 ${it.javaClass.simpleName}:${it.message}") }
    }

    private fun newId(nowMs: Long): String = "et$nowMs-${(100..999).random()}"

    private fun ok(text: String) = ToolOutcome.Ok(text)

    private fun failed(text: String) = ToolOutcome.Failed(text)

    private fun jsonObject(desc: String): JSONObject = JSONObject().put("type", "string").put("description", desc)
}
