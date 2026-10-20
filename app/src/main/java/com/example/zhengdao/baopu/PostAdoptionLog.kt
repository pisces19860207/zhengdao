// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批（2026-10-21）：采纳事件的落点——**追加式 JSONL**（`抱朴记忆/采纳效果.jsonl`）。
//
// 为什么不塞进 `决策审计.jsonl`：决策审计一行必带 `input`＋`decision` 两坨，采纳事件没有
// `decision`；混写会让每行一半字段是 null，读的人还得先看 type 再决定哪半边可信。
// 为什么不建 DB 表：`skill_event` 已经是技能事件的唯一表，加表就要升 `BaopuDb.VERSION`
// （红线）。⇒ 一条独立的**记录流**，不是第二套审计。
//
// 纪律照 `EvolutionAuditLog`：只追加、不覆盖、`@Synchronized`、写失败向上抛
// （由调用方决定「这次不记账」，而不是静默丢账）。正文一个字都不进来。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条采纳事件 ⇒ JSONL 的一行（键名固定，测试逐个钉）。 */
internal fun PostAdoptionEvent.toJson(): JSONObject = JSONObject()
    .put("type", TYPE_ADOPT)
    .put("event_id", eventId)
    .put("channel", channel.name)
    .put("candidate_id", candidateId ?: JSONObject.NULL)
    .put("skill_name", skillName)
    .put("skill_version", skillVersion)
    .put("applied_at_ms", appliedAtMs)
    .put("baseline_source", baselineSource.name)
    .put(
        "baseline",
        JSONObject()
            .put("success", baselineOf(SkillOutcome.SUCCESS))
            .put("failure", baselineOf(SkillOutcome.FAILURE))
            .put("corrected", baselineOf(SkillOutcome.USER_CORRECTED))
            .put("retried", baselineOf(SkillOutcome.RETRIED)),
    )
    .put("rollback_available", rollbackAvailable)
    .put("backup_ids", JSONArray(backupIds))
    .put("window_uses", windowUses)
    .put("window_days", windowDays)

/** 一条结案记录 ⇒ JSONL 的一行。 */
internal fun PostAdoptionSettlement.toJson(): JSONObject = JSONObject()
    .put("type", TYPE_SETTLE)
    .put("event_id", eventId)
    .put("verdict", verdict?.name ?: JSONObject.NULL)
    .put("action", action.name)
    .put("at_ms", atMs)
    .put("reason", reason?.name ?: JSONObject.NULL)
    .put("delta", delta?.let(::effectJson) ?: JSONObject.NULL)

internal const val TYPE_ADOPT = "adopt"
internal const val TYPE_SETTLE = "settle"

private fun effectJson(e: EffectSnapshot): JSONObject = JSONObject()
    .put("successes", e.successes)
    .put("failures", e.failures)
    .put("corrections", e.corrections)
    .put("retries", e.retries)

private fun effectOf(json: JSONObject?): EffectSnapshot? = if (json == null) {
    null
} else {
    EffectSnapshot(
        successes = json.optInt("successes", 0),
        failures = json.optInt("failures", 0),
        corrections = json.optInt("corrections", 0),
        retries = json.optInt("retries", 0),
    )
}

/**
 * 采纳效果账本（追加式 JSONL）。
 *
 * 读的一侧**宽进严出**：认不出的行（脏 JSON、未知 type、缺主键）跳过并 log，
 * 绝不让一行坏账把整份账本读成空——但也不猜它的内容。
 */
internal class PostAdoptionLog(private val file: File) {

    fun file(): File = file

    @Synchronized
    fun append(event: PostAdoptionEvent) {
        file.parentFile?.mkdirs()
        file.appendText(event.toJson().toString() + "\n", Charsets.UTF_8)
    }

    @Synchronized
    fun append(settlement: PostAdoptionSettlement) {
        file.parentFile?.mkdirs()
        file.appendText(settlement.toJson().toString() + "\n", Charsets.UTF_8)
    }

    /**
     * 读整份账本。
     *
     * @return null ＝ **文件读不出来**（不是「没有账」）；文件不存在算空账本
     */
    fun load(): PostAdoptionLedger? {
        val text = runCatching {
            if (file.isFile) file.readText(Charsets.UTF_8) else ""
        }.getOrElse {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴：采纳效果账本读失败 ${it.javaClass.simpleName}: ${it.message}",
            )
            return null
        }
        val events = ArrayList<PostAdoptionEvent>()
        val settlements = ArrayList<PostAdoptionSettlement>()
        var dropped = 0
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val obj = runCatching { JSONObject(t) }.getOrNull()
            if (obj == null) {
                dropped++
                continue
            }
            when (obj.optString("type")) {
                TYPE_ADOPT -> eventOf(obj)?.let { events += it } ?: run { dropped++ }
                TYPE_SETTLE -> settlementOf(obj)?.let { settlements += it } ?: run { dropped++ }
                else -> dropped++
            }
        }
        if (dropped > 0) {
            com.example.zhengdao.rootfs.RunLog.log("抱朴：采纳效果账本有 $dropped 行认不出（已跳过）")
        }
        return PostAdoptionLedger(events, settlements)
    }

    private fun eventOf(o: JSONObject): PostAdoptionEvent? {
        val id = o.optString("event_id").takeIf { it.isNotBlank() } ?: return null
        val name = o.optString("skill_name").takeIf { it.isNotBlank() } ?: return null
        val channel = PostAdoptionChannel.entries.firstOrNull { it.name == o.optString("channel") } ?: return null
        val source = PostAdoptionBaseline.entries.firstOrNull { it.name == o.optString("baseline_source") }
            ?: return null
        val baseline = o.optJSONObject("baseline")
        val map = if (baseline == null) {
            emptyMap()
        } else {
            mapOf(
                SkillOutcome.SUCCESS to baseline.optInt("success", 0),
                SkillOutcome.FAILURE to baseline.optInt("failure", 0),
                SkillOutcome.USER_CORRECTED to baseline.optInt("corrected", 0),
                SkillOutcome.RETRIED to baseline.optInt("retried", 0),
            )
        }
        val ids = o.optJSONArray("backup_ids")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        return PostAdoptionEvent(
            eventId = id,
            channel = channel,
            candidateId = o.optString("candidate_id").takeIf { it.isNotBlank() && it != "null" },
            skillName = name,
            skillVersion = o.optString("skill_version").ifBlank { "-" },
            appliedAtMs = o.optLong("applied_at_ms", 0L),
            baseline = map,
            baselineSource = source,
            rollbackAvailable = o.optBoolean("rollback_available", false),
            backupIds = ids,
            windowUses = o.optInt("window_uses", PostAdoptionWindow.USES),
            windowDays = o.optInt("window_days", PostAdoptionWindow.DAYS),
        )
    }

    private fun settlementOf(o: JSONObject): PostAdoptionSettlement? {
        val id = o.optString("event_id").takeIf { it.isNotBlank() } ?: return null
        val action = PostAdoptionAction.entries.firstOrNull { it.name == o.optString("action") } ?: return null
        val verdict = PostAdoptionVerdict.entries.firstOrNull { it.name == o.optString("verdict") }
        val reason = PostAdoptionReason.entries.firstOrNull { it.name == o.optString("reason") }
        return PostAdoptionSettlement(
            eventId = id,
            verdict = verdict,
            action = action,
            atMs = o.optLong("at_ms", 0L),
            reason = reason,
            delta = effectOf(o.optJSONObject("delta")),
        )
    }

    companion object {
        /** 账本文件名；与决策审计同一棵目录（用户可看可备份）。 */
        const val FILE_NAME = "采纳效果.jsonl"

        fun under(globalRoot: File): PostAdoptionLog = PostAdoptionLog(File(globalRoot, FILE_NAME))
    }
}
