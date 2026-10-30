// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零四批 · 触发账本（JSONL，**只增不改**；一行＝一次触发尝试的完整快照）。
//
// 这本账同时是三样东西：
//   1. **限频状态**：[EvalTriggerBudget] 只读它（读不到＝从没触发过 ⇒ 首次放行）；
//   2. **证据落账**：验收①「触发一次且证据落账」的"账"就是这里的一行
//      （评测读数 run/passed/passRate/失败场景 全在行内，不许"评了但没数"）；
//   3. **对账依据**：被抑制的尝试也记行（triggered=false＋原因）——验收②
//      「同日再 FAILED ⇒ 不触发」要能从账上看出"为什么没触发"。
//
// 落点：App 私有 `filesDir/evolution/trigger-ledger.jsonl`（机器遥测，不进工作区、
// 不进任何面向用户的账本——那是路由层的事，见 [EvalTriggerEngine]）。
package com.example.zhengdao.baopu.evolution.trigger

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 账本一行（读侧解析失败整行丢弃；坏行不许让整本账读不出来）。 */
internal data class TriggerRow(
    val atMs: Long,
    val type: TriggerType,
    /** true＝真触发了（评测已跑）；false＝被限频挡下（[suppressReason] 必填）。 */
    val triggered: Boolean,
    /** 证据引用：`task:<run_id>` / `skill_event:<id>` / `manual`。 */
    val ref: String,
    /** 人话（触发原因／想做的事；可空）。 */
    val intent: String,
    // ── 评测读数（triggered=true 时有效；跑不出来时 [evalError] 说明为什么）──
    val evalRan: Int = 0,
    val evalPassed: Int = 0,
    val evalTotal: Int = 0,
    /** 0–1；null ＝ 没跑出读数（不许写成 0.0 冒充"全挂"）。 */
    val evalPassRate: Double? = null,
    val evalFailedCases: List<String> = emptyList(),
    val evalError: String = "",
    // ── 路由与抑制 ──
    /** `""` / `unfinished:<key>` / `proposal:<id>`（走没走、走到哪，一行看全）。 */
    val route: String = "",
    val suppressReason: String = "",
) {
    init {
        require(!triggered || suppressReason.isBlank()) { "triggered 行不许带抑制原因" }
        require(triggered || suppressReason.isNotBlank()) { "被抑制的行必须写明原因" }
    }
}

/** 行的编码与解析（**纯函数**；store 只做追加与读尾）。 */
internal object TriggerRowCodec {

    fun encode(row: TriggerRow): JSONObject = JSONObject().apply {
        put("at", row.atMs)
        put("type", row.type.wire)
        put("triggered", row.triggered)
        put("ref", row.ref)
        put("intent", row.intent)
        put("evalRan", row.evalRan)
        put("evalPassed", row.evalPassed)
        put("evalTotal", row.evalTotal)
        row.evalPassRate?.let { put("evalPassRate", it) }
        put("evalFailedCases", JSONArray(row.evalFailedCases))
        if (row.evalError.isNotBlank()) put("evalError", row.evalError)
        if (row.route.isNotBlank()) put("route", row.route)
        if (row.suppressReason.isNotBlank()) put("suppressReason", row.suppressReason)
    }

    fun parse(line: String): TriggerRow? = runCatching {
        val o = JSONObject(line)
        TriggerRow(
            atMs = o.getLong("at"),
            type = TriggerType.ofWire(o.getString("type")) ?: return null,
            triggered = o.getBoolean("triggered"),
            ref = o.optString("ref"),
            intent = o.optString("intent"),
            evalRan = o.optInt("evalRan", 0),
            evalPassed = o.optInt("evalPassed", 0),
            evalTotal = o.optInt("evalTotal", 0),
            evalPassRate = if (o.has("evalPassRate")) o.getDouble("evalPassRate") else null,
            evalFailedCases = o.optJSONArray("evalFailedCases")
                ?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: emptyList(),
            evalError = o.optString("evalError"),
            route = o.optString("route"),
            suppressReason = o.optString("suppressReason"),
        )
    }.getOrNull()
}

/** 账本的落盘壳（单文件、只追加；写失败返回 false——记账失败不许挡住主流程）。 */
internal class TriggerLedgerStore(private val file: File) {

    /** 读全部（坏行丢弃；文件不在＝空账＝从没触发过）。 */
    fun read(): List<TriggerRow> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                if (line.isBlank()) null else TriggerRowCodec.parse(line)
            }
        }.getOrDefault(emptyList())
    }

    /** 追加一行（@Synchronized：收尾触发与手动触发可能并发）。 */
    @Synchronized
    fun append(row: TriggerRow): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.appendText(TriggerRowCodec.encode(row).toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)
}
