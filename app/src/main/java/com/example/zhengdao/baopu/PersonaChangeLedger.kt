// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十五批（派工单 §1 · 「入账：谁／何时／原文」）：**人设账**。
//
// 为什么另起一本：`[persona]` 那行日志只说明"应用了哪一层、条款类别是什么"——
// 它答不了用户最想知道的三个问题：**谁加的、什么时候加的、加的原话是什么**。
// 日志会被轮转，账本不会；所以正文抄一份进 `人设账.jsonl`（append-only）。
//
// 与其它账本分家的理由同 277/278：这本记"人设为什么长这样"，
// `意见判定.jsonl` 记"该不该打扰你"，`御批.jsonl` 记"你怎么裁的"，三本各答一个问题。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.RunLog
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/** 这条人设是**谁**加的（用户手写／模型工具／卡片确认／导入）。 */
internal enum class PersonaWho(val wire: String, val cn: String) {
    USER("user", "你自己"),
    MODEL("model", "模型"),
    CARD("card", "卡片确认"),
    IMPORT("import", "导入"),
    ;

    companion object {
        fun ofWire(w: String): PersonaWho? = entries.firstOrNull { it.wire == w }
    }
}

/**
 * 人设账的一行。
 *
 * @param text **原文**（用户敲进去的那一行／补丁渲染出的条款全文）——不是摘要
 * @param result `applied` / `rejected`（没落盘也要记：用户点了没生效，账上得看得见）
 */
internal data class PersonaChange(
    val id: String,
    val atMs: Long,
    val who: PersonaWho,
    val layer: PersonaLayer,
    val text: String,
    val reason: String,
    val result: String,
    val source: String = "",
) {
    val day: String get() = PersonaChangeLedger.dayOf(atMs).toString()

    /** 落盘了吗（false ⇒ 被拒/没同意，账上照样留一行）。 */
    val applied: Boolean get() = result == RESULT_APPLIED

    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("day", day)
        .put("who", who.wire)
        .put("layer", layer.name)
        .put("text", text)
        .put("reason", reason)
        .put("result", result)
        .put("src", source)

    companion object {
        const val VERSION = 1
        const val RESULT_APPLIED = "applied"
        const val RESULT_REJECTED = "rejected"

        fun parse(raw: String): PersonaChange? {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val id = o.optString("id")
            val who = PersonaWho.ofWire(o.optString("who")) ?: return null
            val layer = runCatching { PersonaLayer.valueOf(o.optString("layer")) }.getOrNull() ?: return null
            if (id.isEmpty()) return null
            return PersonaChange(
                id = id,
                atMs = o.optLong("at", 0L),
                who = who,
                layer = layer,
                text = o.optString("text"),
                reason = o.optString("reason"),
                result = o.optString("result", RESULT_REJECTED),
                source = o.optString("src"),
            )
        }
    }
}

/**
 * 人设账（进程内单例；append-only，落 `抱朴记忆/人设账.jsonl`）。
 *
 * 写失败不抛（留痕丢了不算事故），但要有一行日志——照 208/影子账的口径。
 */
internal object PersonaChangeLedger {

    const val FILE_NAME = "人设账.jsonl"

    private const val MEMORY_CAP = 200

    private val memory = ArrayList<PersonaChange>()
    private var seq = 0

    /**
     * 记一条。
     *
     * @param text 原文；空原文也记（"用户点了添加但没写内容"本身就是一条信息），
     *   但会在 [PersonaChange.text] 里留空串——不伪造。
     */
    @Synchronized
    fun record(
        who: PersonaWho,
        layer: PersonaLayer,
        text: String,
        reason: String,
        applied: Boolean,
        source: String = "",
        now: Long = System.currentTimeMillis(),
    ): PersonaChange {
        seq += 1
        val row = PersonaChange(
            id = "pc:$now:$seq",
            atMs = now,
            who = who,
            layer = layer,
            text = text.trim(),
            reason = reason.trim(),
            result = if (applied) PersonaChange.RESULT_APPLIED else PersonaChange.RESULT_REJECTED,
            source = source,
        )
        memory.add(row)
        if (memory.size > MEMORY_CAP) memory.removeAt(0)
        root()?.let { r ->
            runCatching {
                val f = fileIn(r)
                f.parentFile?.mkdirs()
                f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
            }.onFailure {
                RunLog.log("抱朴人设账：没落上（${row.id}）——${it.javaClass.simpleName}")
            }
        }
        RunLog.log(logLine(row))
        return row
    }

    /** 回放（新→旧；不设时间窗——人设是长期事实，不该有过期）。 */
    @Synchronized
    fun replay(limit: Int = 0): List<PersonaChange> {
        val fromFile = root()?.let { r ->
            runCatching {
                val f = fileIn(r)
                if (!f.isFile) emptyList() else f.readLines(Charsets.UTF_8).mapNotNull(PersonaChange::parse)
            }.getOrDefault(emptyList())
        }.orEmpty()
        val all = (fromFile + memory).distinctBy { it.id }.sortedByDescending { it.atMs }
        return if (limit > 0) all.take(limit) else all
    }

    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    internal fun logLine(row: PersonaChange): String =
        "抱朴人设账：${row.who.cn} ${if (row.applied) "加了" else "没加成"}（${row.layer.name}）" +
            "｜${row.text.take(24)}"

    internal fun dayOf(now: Long): LocalDate =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()

    @Synchronized
    fun resetForTest() {
        memory.clear()
        seq = 0
    }

    private fun root(): File? = DecisionLedger.installedRoot()
}
