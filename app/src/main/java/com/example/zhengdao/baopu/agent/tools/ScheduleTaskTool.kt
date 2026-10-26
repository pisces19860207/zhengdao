// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十八批 ③ `schedule_task`：建/改/停定时任务（R2——会产生「无人值守的自己」）。
//
// 判据（派工单 §1③）：
//   1. **必过确认卡**：permission=CONFIRM——卡上就是 args（几点、做什么、跑几次），
//      现有机制自动走，本工具没有任何「跳过确认直接写库」的支路；
//   2. **复用 167 的调度层**：写走 `ScheduleStore`，排程走 `ScheduleScheduler`（经
//      [ScheduleToolSink] 注入，工具层不碰 Context）；**不新建调度器**；
//   3. `when` 支持「每天 21:00／每周三 09:30／2026-11-01 21:00／21:00」→ 167 的
//      DAILY/WEEKLY/ONCE＋minuteOfDay。⚠️ 偏差如实记：**不支持 cron**——167 的任务
//      形状没有 cron 字段（只有 频率+分钟），v1 不为它发明新调度语义，cron 挂账；
//   4. **连败 3 次自动暂停**：在 167 层落（`ScheduleTask.failStreak`＋
//      `ScheduleRules.afterFailure`——成功清零、≥3 关 enabled 并通知），本工具只负责 list 时可见；
//   5. prompt 含扣费/外发类动作词 ⇒ 结果里**必须提示**（负例：含 R3 动作却不提示）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.core.RunLog
import com.example.zhengdao.schedule.ScheduleFreq
import com.example.zhengdao.schedule.ScheduleRules
import com.example.zhengdao.schedule.ScheduleStore
import com.example.zhengdao.schedule.ScheduleTask
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** R3 动作词（prompt 命中 ⇒ 结果提示；不阻断——建任务本身已过确认卡）。 */
internal val SENSITIVE_ACTION_WORDS = listOf("扣费", "付费", "购买", "转账", "发送", "发短信", "外发", "群发", "删除全部")

internal object ScheduleWhenParser {

    /**
     * 「每天 21:00／每周三 09:30／2026-11-01 21:00／21:00」⇒ 167 的任务形状。
     * 认不出 ⇒ null（**不许猜**——负例「cron 非法也写库」在这堵死：非法 when 一律拒绝）。
     */
    fun parse(raw: String): Triple<ScheduleFreq, Int, Pair<Int, String>>? {
        val s = raw.trim()
        val time = Regex("""(\d{1,2})[:：点](\d{2})?""").find(s)?.let { m ->
            val h = m.groupValues[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return@let null
            val min = m.groupValues[2].toIntOrNull()?.takeIf { it in 0..59 } ?: 0
            LocalTime.of(h, min)
        } ?: return null
        val minute = time.hour * 60 + time.minute
        val date = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(s)?.let { m ->
            runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        if (date != null) return Triple(ScheduleFreq.ONCE, minute, 0 to date.toString())
        val week = Regex("""周[一二三四五六日天]|周\s*([1-7])""").find(s)?.let { m ->
            when {
                m.value.contains("一") -> 1
                m.value.contains("二") -> 2
                m.value.contains("三") -> 3
                m.value.contains("四") -> 4
                m.value.contains("五") -> 5
                m.value.contains("六") -> 6
                m.value.contains("日") || m.value.contains("天") -> 7
                else -> m.groupValues.lastOrNull()?.toIntOrNull()
            }
        }
        if (week != null) return Triple(ScheduleFreq.WEEKLY, minute, week to "")
        if (s.contains("每天") || s.contains("每日") || Regex("""^\d{1,2}[:：]\d{2}$""").matches(s)) {
            return Triple(ScheduleFreq.DAILY, minute, 0 to "")
        }
        return null
    }
}

/**
 * 排程的注入口（工具层不碰 Context——房型照 `CapabilitiesTool.sink`）：
 * 生产由 `BaopuRuntime` 注入「写完库重排一遍系统闹钟」；测试注入记录器；null ⇒ 只写库。
 */
internal interface ScheduleToolSink {
    /** 库已写完，重排系统闹钟（`ScheduleScheduler.rescheduleAll`）。 */
    fun reschedule()
}

internal object ScheduleTaskTool {

    const val ARG_ACTION = "action"
    const val ARG_ID = "id"
    const val ARG_WHEN = "when"
    const val ARG_PROMPT = "prompt"
    const val ARG_TITLE = "title"

    /** 工具侧的宿主（库与排程；workspaceRoot 用不到，但 spec 工厂口径一致）。 */
    internal var sink: ScheduleToolSink? = null
    internal var storeProvider: (() -> ScheduleStore?)? = null

    fun spec(): ToolSpec = ToolSpec(
        name = "schedule_task",
        description = "建/改/停定时任务（create/update/pause/resume/delete/list）。" +
            "when 支持「每天 21:00」「每周三 09:30」「2026-11-01 21:00」「21:00」；" +
            "到点把 prompt 发进会话照着做。建与改都要你确认（这是无人值守的事，看清楚再批）。" +
            "同一任务连败 3 次会自动暂停并通知。" +
            "（这个工具在 SCHEDULE 组：看不到就先 capabilities 解锁）",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_ACTION to jsonObjectOf("type" to "string", "description" to "create / update / pause / resume / delete / list"),
                ARG_ID to jsonObjectOf("type" to "string", "description" to "update/pause/resume/delete 必填：任务 id（list 里看）"),
                ARG_WHEN to jsonObjectOf("type" to "string", "description" to "create 必填：什么时候（每天 21:00／每周三 09:30／2026-11-01 21:00／21:00）"),
                ARG_PROMPT to jsonObjectOf("type" to "string", "description" to "create/update 可改：到点要它做什么"),
                ARG_TITLE to jsonObjectOf("type" to "string", "description" to "可选：任务名（列表里显示）"),
            ),
            "required" to jsonArrayOf(ARG_ACTION),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM, // R2：必过确认卡
        riskTier = "R2",
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, System.currentTimeMillis()) }

    fun run(args: JSONObject, nowMs: Long): ToolOutcome {
        val store = storeProvider?.invoke()
            ?: return ToolOutcome.Failed("调度层没接上（宿主没注入）——如实说，不假装修好了")
        val action = args.optString(ARG_ACTION)
        return when (action) {
            "list" -> list(store)
            "create" -> create(args, store, nowMs)
            "update" -> update(args, store, nowMs)
            "pause" -> setEnabled(store, args, false, nowMs)
            "resume" -> setEnabled(store, args, true, nowMs)
            "delete" -> delete(store, args, nowMs)
            else -> ToolOutcome.Failed("action 认不出：$action（允许：create/update/pause/resume/delete/list）")
        }
    }

    private fun list(store: ScheduleStore): ToolOutcome {
        val tasks = store.load().tasks
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("when", describe(t))
                    .put("enabled", t.enabled)
                    .put("runCount", t.runCount)
                    .put("lastOutcome", t.lastOutcome.wire)
                    .put("failStreak", t.failStreak)
                    .put("pausedByFailStreak", !t.enabled && t.failStreak >= ScheduleRules.AUTO_PAUSE_STREAK),
            )
        }
        return ToolOutcome.Ok(JSONObject().put("tasks", arr).toString(2))
    }

    private fun create(args: JSONObject, store: ScheduleStore, nowMs: Long): ToolOutcome {
        val whenText = args.optString(ARG_WHEN).ifBlank {
            return ToolOutcome.Failed("缺 when（什么时候跑）——支持：每天 21:00／每周三 09:30／2026-11-01 21:00／21:00")
        }
        val prompt = args.optString(ARG_PROMPT).ifBlank { return ToolOutcome.Failed("缺 prompt（到点要它做什么）") }
        val parsed = ScheduleWhenParser.parse(whenText)
            ?: return ToolOutcome.Failed(
                "when「$whenText」认不出——不许猜。支持：每天 21:00／每周三 09:30／2026-11-01 21:00／21:00（cron 尚不支持）",
            )
        val (freq, minute, dayAndDate) = parsed
        val title = args.optString(ARG_TITLE).ifBlank { "定时任务 ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}" }
        val zone = ZoneId.systemDefault().id
        val task = ScheduleTask(
            id = newId(nowMs),
            title = title,
            prompt = prompt,
            freq = freq,
            minuteOfDay = minute,
            dayOfWeek = dayAndDate.first,
            onceDate = dayAndDate.second,
            zone = zone,
            enabled = true,
            nextRunAtMs = nextAnchorOf(freq, minute, dayAndDate, zone, nowMs),
            firedSlot = "",
            lastRunAtMs = 0L,
            lastOutcome = com.example.zhengdao.schedule.ScheduleOutcome.NONE,
            lastDetail = "",
            sessionId = -1L,
            runCount = 0,
            attempts = 0,
            createdAtMs = nowMs,
        )
        val current = store.load().tasks
        if (!store.save(current + task)) {
            return ToolOutcome.Failed("写库失败（任务没建成）——不许以为保存成功")
        }
        reschedule()
        RunLog.log("schedule_task: created id=${task.id} freq=${freq.wire} minute=$minute title=$title")
        return ToolOutcome.Ok(resultText(task, "已创建（过了确认卡才落库）") + sensitiveHint(prompt))
    }

    private fun update(args: JSONObject, store: ScheduleStore, nowMs: Long): ToolOutcome {
        val id = args.optString(ARG_ID).ifBlank { return ToolOutcome.Failed("缺 id（list 里看）") }
        val current = store.load().tasks
        val task = current.firstOrNull { it.id == id }
            ?: return ToolOutcome.Failed("没有这个任务：$id")
        var next = task
        args.optString(ARG_WHEN).takeIf { it.isNotBlank() }?.let { whenText ->
            val parsed = ScheduleWhenParser.parse(whenText)
                ?: return ToolOutcome.Failed("when「$whenText」认不出——不许猜（同 create）")
            next = next.copy(
                freq = parsed.first, minuteOfDay = parsed.second,
                dayOfWeek = parsed.third.first, onceDate = parsed.third.second,
                nextRunAtMs = nextAnchorOf(parsed.first, parsed.second, parsed.third, task.zone, nowMs),
                firedSlot = "",
            )
        }
        args.optString(ARG_PROMPT).takeIf { it.isNotBlank() }?.let { next = next.copy(prompt = it) }
        args.optString(ARG_TITLE).takeIf { it.isNotBlank() }?.let { next = next.copy(title = it) }
        if (!store.update(next)) return ToolOutcome.Failed("写库失败（没改上）")
        reschedule()
        return ToolOutcome.Ok(resultText(next, "已更新（过了确认卡才落库）") + sensitiveHint(next.prompt))
    }

    private fun setEnabled(store: ScheduleStore, args: JSONObject, enabled: Boolean, nowMs: Long): ToolOutcome {
        val id = args.optString(ARG_ID).ifBlank { return ToolOutcome.Failed("缺 id（list 里看）") }
        val current = store.load().tasks
        val task = current.firstOrNull { it.id == id }
            ?: return ToolOutcome.Failed("没有这个任务：$id")
        val next = if (enabled) ScheduleRules.afterEnable(task, nowMs) else task.copy(enabled = false)
        if (!store.update(next)) return ToolOutcome.Failed("写库失败（没改上）")
        reschedule()
        val what = if (enabled) "已恢复（锚点重算，不会把停用期间的漏跑一次补完）" else "已暂停（不删，随时可恢复）"
        return ToolOutcome.Ok(resultText(next, what))
    }

    private fun delete(store: ScheduleStore, args: JSONObject, nowMs: Long): ToolOutcome {
        @Suppress("UNUSED_PARAMETER") val unused = nowMs
        val id = args.optString(ARG_ID).ifBlank { return ToolOutcome.Failed("缺 id（list 里看）") }
        val current = store.load().tasks
        val task = current.firstOrNull { it.id == id }
            ?: return ToolOutcome.Failed("没有这个任务：$id")
        if (!store.save(current - task)) return ToolOutcome.Failed("写库失败（没删成）")
        reschedule()
        // 删除要说一声（负例：删任务不通知）——结果与日志都留话
        RunLog.log("schedule_task: deleted id=$id title=${task.title}")
        return ToolOutcome.Ok("已删除「${task.title}」（$id，跑了 ${task.runCount} 次）；下次不再触发。")
    }

    private fun reschedule() {
        runCatching { sink?.reschedule() }
            .onFailure { RunLog.log("schedule_task: 重排闹钟失败 ${it.javaClass.simpleName}:${it.message}") }
    }

    private fun resultText(task: ScheduleTask, what: String): String =
        "「${task.title}」${task.id.take(8)}｜${describe(task)}｜${what}"

    private fun describe(t: ScheduleTask): String {
        val time = "%02d:%02d".format(t.minuteOfDay / 60, t.minuteOfDay % 60)
        return when (t.freq) {
            ScheduleFreq.DAILY -> "每天 $time"
            ScheduleFreq.WEEKLY -> "每周${"一二三四五六日".getOrElse(t.dayOfWeek - 1) { '?' }} $time"
            ScheduleFreq.ONCE -> "${t.onceDate} $time（一次）"
        }
    }

    private fun nextAnchorOf(
        freq: ScheduleFreq,
        minute: Int,
        dayAndDate: Pair<Int, String>,
        zone: String,
        nowMs: Long,
    ): Long = runCatching {
        val z = ZoneId.of(zone)
        if (freq == ScheduleFreq.ONCE) {
            LocalDate.parse(dayAndDate.second).atTime(minute / 60, minute % 60).atZone(z).toInstant().toEpochMilli()
        } else {
            var anchor = nowMs + 60_000L
            repeat(8) {
                val next = ScheduleRules.nextRunAfter(freq, minute, dayAndDate.first, dayAndDate.second, zone, anchor)
                if (next > nowMs) return next
                anchor += 24L * 60 * 60 * 1000
            }
            nowMs
        }
    }.getOrDefault(nowMs)

    private fun sensitiveHint(prompt: String): String =
        SENSITIVE_ACTION_WORDS.firstOrNull { prompt.contains(it) }
            ?.let { "\n⚠️ 任务内容含「$it」这类有真实世界后果的动作词——确认时看清楚，出问题的是无人值守的那一次。" }
            ?: ""

    private fun newId(nowMs: Long): String = "t$nowMs-${(100..999).random()}"
}
