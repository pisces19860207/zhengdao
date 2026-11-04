// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import com.example.zhengdao.core.RunLog
import java.io.File

/**
 * 事件触发账（第三百九十九批 §二④「必须留痕」＋ §三判据⑤「可回放」）。
 *
 * JSONL 一行一条，落在 `filesDir/trigger/事件触发.jsonl`（App 私有目录，自建父目录）。
 * **只追加不修改**：哪条事件、什么时候、触发了什么、结果如何，事后按时间窗能一把列出来
 * ——「给定时间窗 ⇒ 列出所有触发」就是 [rowsInWindow]。
 *
 * 记账口径（与判据对齐）：
 * - **真叫醒过**（fired/firedLate/queued）与**被拒**（refusedBusy/failed/rateLimited）都记——
 *   后者不记的话「限频丢了活」就成了静默事故；
 * - **去重跳过不记**：与 167 的 ALREADY_FIRED 同款——它是「这件事已经办过了」的沉默重访，
 *   记下来只会把账本刷成同一条事件的流水账；去重这件事本身由 [TriggerRule.firedKey] 落在规则表里。
 *
 * 文件形态照 363 的偏差账（一处追加口＋一处自建父目录；追加失败不抛，返 false 让调用方
 * 说出来——账没落成不许当没事发生）。
 */
class TriggerLedger(private val file: File) {

    /** 一条账。`action` 取 [ACTIONS] 里的字面值，闭集。 */
    data class Row(
        val tsMs: Long,
        val source: String,
        val ruleId: String,
        /** CALENDAR 事件的 id + 开始时刻（SCHED 为空串——定时没有「事件」可指）。 */
        val eventId: String,
        val action: String,
        val sessionId: Long,
        val detail: String,
    ) {
        fun toJson(): String = org.json.JSONObject()
            .put("ts", tsMs)
            .put("source", source)
            .put("ruleId", ruleId)
            .put("eventId", eventId)
            .put("action", action)
            .put("sessionId", sessionId)
            .put("detail", detail)
            .toString()

        companion object {
            fun fromJson(line: String): Row? = runCatching {
                val o = org.json.JSONObject(line)
                Row(
                    tsMs = o.optLong("ts", 0L),
                    source = o.optString("source"),
                    ruleId = o.optString("ruleId"),
                    eventId = o.optString("eventId"),
                    action = o.optString("action"),
                    sessionId = o.optLong("sessionId", -1L),
                    detail = o.optString("detail"),
                )
            }.getOrNull()?.takeIf { it.tsMs > 0 && it.action in ACTIONS }
        }
    }

    /** 追加一条；false = 没落成（调用方要落日志，不许静默）。 */
    fun append(row: Row): Boolean = synchronized(LOCK) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(row.toJson() + "\n", Charsets.UTF_8)
        }.onFailure { log("追加失败 ${it.javaClass.simpleName}: ${it.message}") }.isSuccess
    }

    /** 全量读（解析不过的行跳过——账本容错不挡回放；行数上限防一个意外膨胀的文件拖死调用方）。 */
    fun rows(): List<Row> = synchronized(LOCK) {
        if (!file.isFile) return@synchronized emptyList()
        runCatching { file.readLines(Charsets.UTF_8) }
            .onFailure { log("读失败 ${it.javaClass.simpleName}: ${it.message}") }
            .getOrDefault(emptyList())
            .takeLast(MAX_ROWS)
            .mapNotNull { Row.fromJson(it) }
    }

    /** 派工单 §三判据⑤：时间窗回放。 */
    fun rowsInWindow(fromMs: Long, toMs: Long): List<Row> =
        rows().filter { it.tsMs in fromMs..toMs }

    /** 限频用：某源在 (sinceMs, now] 里真叫醒过的条数（queued 也占额度——它也是一次叫醒）。 */
    fun fireCountSince(source: TriggerSource, sinceMs: Long): Int =
        rows().count { it.source == source.wire && it.tsMs > sinceMs && it.action in FIRE_ACTIONS }

    companion object {
        const val FILE_NAME = "事件触发.jsonl"

        /** 账的动作闭集（真叫醒 / 被拒 / 被限频；去重跳过不记账，见类 KDoc）。 */
        val ACTIONS = setOf("fired", "firedLate", "queued", "refusedBusy", "failed", "missed", "rateLimited")

        /** 占限频额度的动作 = 叫醒成功与入队。 */
        val FIRE_ACTIONS = setOf("fired", "firedLate", "queued")

        /** 读侧行数上限（一次 pass 读它两遍；一个意外膨胀的文件不许把叫醒链拖死）。 */
        const val MAX_ROWS = 20_000

        private val LOCK = TriggerLedger::class.java

        private fun log(line: String) = RunLog.log("事件触发：$line")
    }
}
