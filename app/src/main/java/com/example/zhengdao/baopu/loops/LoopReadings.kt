// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百五十三批 · 八环读数的**行格式与字段规范**（派工单 753 ①②③；指标定义照 708 §三）。
//
// 八环＝708 定稿的文字线闭环：①检索②计划③突显④中继⑤RPE⑥体感⑦建议⑧验收。
// 本件只管「一行长什么样、哪些字段合法」；落盘／滚动在 [LoopReadingsLedger]（同包）。
//
// 三条硬规矩（派工单判据逐字落码）：
//   ① 八环是闭集：第九个名字在这里产不出来（枚举＋[Loop.of] 拒判——判据①）；
//   ② 每行必须带 ref：缺 ref 在 [validate] 就判废，账本一行都不落（判据②）；
//   ③ unwired 行必须带 needTicket（前置单号），非 unwired 行不许带（判据③的机器化）。
// 633 红线：读数字段严禁正文与音频——键是白名单闭集（READING_SPEC）、串值有长度上限、
// 换行/控制符/"data:"／"base64," 进不了任何串字段（[checkString]）：正文与音频没有结构通道。
package com.example.zhengdao.baopu.loops

import org.json.JSONArray
import org.json.JSONObject

/** 八环闭集（708 §三 的①–⑧；派工单 753 ②「不许自造」）。wire 是线上格式，逐字即派工单原文。 */
internal enum class Loop(val wire: String, val label: String) {
    RETRIEVAL("retrieval", "①检索"),
    PLAN("plan", "②计划"),
    SALIENCE("salience", "③突显"),
    RELAY("relay", "④中继"),
    RPE("rpe", "⑤RPE"),
    INSULA("insula", "⑥体感"),
    SUGGEST("suggest", "⑦建议"),
    ACCEPT("accept", "⑧验收"),
    ;

    companion object {
        /** 线名→环：不在八环里的名字一律 null（判据①的拒判口；大小写敏感，wire 是线格式）。 */
        fun of(wire: String): Loop? = entries.firstOrNull { it.wire == wire }
    }
}

/** 行状态三态（派工单 753 ①：ok|unwired|error；unwired＝结构上取不到读数，不是「没去取」）。 */
internal enum class Status(val wire: String) {
    OK("ok"),
    UNWIRED("unwired"),
    ERROR("error"),
    ;

    companion object {
        fun of(wire: String): Status? = entries.firstOrNull { it.wire == wire }
    }
}

/** 读数来源四态（派工单 753 ①：bio|tool|ui|manual）。 */
internal enum class SourceKind(val wire: String) {
    BIO("bio"),
    TOOL("tool"),
    UI("ui"),
    MANUAL("manual"),
    ;

    companion object {
        fun of(wire: String): SourceKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一行环读数（内存形状）。[reading] 只许装该环白名单字段（[validate] 是唯一裁判）；
 * 值只有 Int/Long/String/List&lt;String&gt;/null 五种——第九种类型在编码口就装不进去。
 */
internal data class LoopReadingRow(
    val ts: Long,
    val sceneId: String,
    val loop: Loop,
    val status: Status,
    val reading: Map<String, Any?>,
    val source: SourceKind,
    val ref: String,
    val needTicket: String? = null,
)

/** 白名单字段的形状（值域按 708 §三 逐环钉死；一个字段一个形状，键位即闸）。 */
internal sealed interface ReadingField {
    val key: String

    /** 必填整数，值域 [min,max]（档位/计数/耗时）。 */
    data class Num(override val key: String, val min: Long, val max: Long) : ReadingField

    /** 可空整数：键必须在场，值可为 null（plan.stuckAt 的「判不了就不猜」）。 */
    data class NumOrNull(override val key: String, val min: Long, val max: Long) : ReadingField

    /** 必填串：≤[maxLen] 字；[allowed] 非空时值必须落该闭集。 */
    data class Text(override val key: String, val maxLen: Int, val allowed: Set<String>? = null) : ReadingField

    /** 必填 id 数组：≤[maxItems] 条，每条 1..[itemMaxLen] 字（检索命中来源段 id）。 */
    data class Ids(override val key: String, val maxItems: Int, val itemMaxLen: Int) : ReadingField
}

/** 验收三态闭集（708 §四；accept 环 route 同款闭集口径）。 */
internal val VERDICTS: Set<String> = setOf("PASS", "FAIL", "UNVERIFIABLE")

/** 串值长度上限（note/reason 是六体的模板句，远够；正文——再短也要走 cap 这道墙）。 */
private const val TEXT_MAX = 200

/** 每环白名单（708 §三「读什么」逐环落码；判据①的姊妹闸：键位也是闭集，多余字段判废）。 */
internal val READING_SPEC: Map<Loop, List<ReadingField>> = mapOf(
    Loop.RETRIEVAL to listOf(
        ReadingField.Num("hits", 0, Int.MAX_VALUE.toLong()),
        ReadingField.Ids("sources", 64, 64),
        ReadingField.Num("ms", 0, Int.MAX_VALUE.toLong()),
    ),
    Loop.PLAN to listOf(
        ReadingField.Num("stepsLeft", 0, Int.MAX_VALUE.toLong()),
        ReadingField.NumOrNull("stuckAt", 0, Int.MAX_VALUE.toLong()),
        ReadingField.Text("reason", TEXT_MAX),
    ),
    Loop.SALIENCE to listOf(
        ReadingField.Num("threat", 0, 4),
        ReadingField.Num("novelty", 0, 2),
        ReadingField.Text("note", TEXT_MAX),
    ),
    Loop.RELAY to listOf(
        ReadingField.Text("route", 8, setOf("门禁", "工具", "记忆", "直答")),
        ReadingField.Num("novelty", 0, 2),
        ReadingField.Text("note", TEXT_MAX),
    ),
    Loop.RPE to listOf(
        ReadingField.Num("rpe", -1, 1),
        ReadingField.Text("note", TEXT_MAX),
    ),
    Loop.INSULA to listOf(
        ReadingField.Text("battery", 120),
        ReadingField.Text("storage", 120),
        ReadingField.Text("thermal", 120),
        ReadingField.Text("build", 120),
        ReadingField.Text("gates", TEXT_MAX),
        ReadingField.Text("formTrack", TEXT_MAX),
        ReadingField.Text("note", TEXT_MAX),
    ),
    Loop.SUGGEST to listOf(
        ReadingField.Num("echo", 0, Int.MAX_VALUE.toLong()),
        ReadingField.Num("inspiration", 0, Int.MAX_VALUE.toLong()),
        ReadingField.Num("replay", 0, Int.MAX_VALUE.toLong()),
        ReadingField.Num("adopted", 0, Int.MAX_VALUE.toLong()),
        ReadingField.Num("revoked", 0, Int.MAX_VALUE.toLong()),
    ),
    Loop.ACCEPT to listOf(
        ReadingField.Text("person", 12, VERDICTS),
        ReadingField.Text("foreshadow", 12, VERDICTS),
        ReadingField.Text("detail", 12, VERDICTS),
    ),
)

private const val SCENE_MAX = 64
private const val REF_MAX = 200
private const val TICKET_MAX = 32

/**
 * 633 红线的串级检查：返 null＝过；返短句＝问题。对 sceneId/ref/needTicket/串值一视同仁——
 * 严禁换行（JSONL 一行一行）、严禁控制符、严禁 data: 前缀与 base64 载荷（音频没有键位也没有通道）。
 */
internal fun checkString(where: String, s: String): String? = when {
    s.isBlank() -> "$where 不得为空（空白也是沉默）"
    s.any { it.code < 0x20 } -> "$where 含换行/控制符（JSONL 断行即坏账）"
    s.startsWith("data:") -> "$where 以 data: 开头（633：读数字段严禁正文与音频）"
    s.contains("base64,") -> "$where 含 base64,（633：音频/二进制没有通道）"
    else -> null
}

/**
 * 行校验（唯一裁判）：返空表＝合法；返问题清单＝该行判废，账本拒收。
 * 判据②③①在这里落死：缺 ref、unwired 缺 needTicket、键位白名单外、值域外——一条都不放行。
 */
internal fun validate(row: LoopReadingRow): List<String> {
    val problems = mutableListOf<String>()
    if (row.ts <= 0) problems += "ts 必须是正的 epoch 毫秒"
    checkIdPart(problems, "sceneId", row.sceneId, SCENE_MAX)
    checkIdPart(problems, "ref", row.ref, REF_MAX)
    when (row.status) {
        Status.UNWIRED ->
            if (row.needTicket.isNullOrBlank()) {
                problems += "unwired 行缺 needTicket（前置单号）——沉默不算读数"
            } else {
                checkIdPart(problems, "needTicket", row.needTicket, TICKET_MAX)
            }
        Status.OK, Status.ERROR ->
            if (row.needTicket != null) problems += "needTicket 只属于 unwired 行（ok/error 的证据走 ref）"
    }
    validateReading(problems, row)
    return problems
}

/** sceneId/ref/needTicket 这类「指针」字段：短、可定位、不塞内容。 */
private fun checkIdPart(problems: MutableList<String>, where: String, value: String, maxLen: Int) {
    if (value.length > maxLen) problems += "$where 超 $maxLen 字（指针不是正文）"
    checkString(where, value)?.let { problems += it }
}

/** reading 校验：ok 行＝白名单键位一 key 不缺一 key 不多、值落域；非 ok 行＝reading 必须空。 */
private fun validateReading(problems: MutableList<String>, row: LoopReadingRow) {
    val spec = READING_SPEC.getValue(row.loop)
    if (row.status != Status.OK) {
        if (row.reading.isNotEmpty()) problems += "${row.status.wire} 行 reading 必须为空（读数只在 ok 行）"
        return
    }
    if (row.reading.isEmpty()) {
        problems += "ok 行 reading 为空——沉默不算读数"
        return
    }
    spec.forEach { field -> validateField(problems, field, row.reading) }
    val extra = row.reading.keys - spec.map(ReadingField::key).toSet()
    extra.forEach { problems += "多余字段 $it（白名单外：正文/音频没有键位）" }
}

private fun validateField(problems: MutableList<String>, field: ReadingField, reading: Map<String, Any?>) {
    if (!reading.containsKey(field.key)) {
        problems += "缺字段 ${field.key}（708 §三 该环必读）"
        return
    }
    val problem = when (field) {
        is ReadingField.Num -> numProblem(field.min, field.max, reading[field.key])
        is ReadingField.NumOrNull -> reading[field.key]?.let { numProblem(field.min, field.max, it) }
        is ReadingField.Text -> textProblem(field, reading[field.key])
        is ReadingField.Ids -> idsProblem(field, reading[field.key])
    }
    problem?.let { problems += "${field.key} $it" }
}

private fun numProblem(min: Long, max: Long, v: Any?): String? {
    val n = (v as? Int)?.toLong() ?: (v as? Long) ?: return "必须是整数（现在是 ${typeName(v)}）"
    return if (n < min || n > max) "超域 [$min,$max]（值 $n）" else null
}

private fun textProblem(field: ReadingField.Text, v: Any?): String? {
    val s = v as? String ?: return "必须是串（现在是 ${typeName(v)}）"
    val shape = when {
        s.length > field.maxLen -> "超 ${field.maxLen} 字上限（模板句够用，正文进不来）"
        field.allowed != null && s !in field.allowed -> "不在闭集 ${field.allowed}"
        else -> null
    }
    return shape ?: checkString(field.key, s)
}

private fun idsProblem(field: ReadingField.Ids, v: Any?): String? {
    val list = v as? List<*> ?: return "必须是 id 数组（现在是 ${typeName(v)}）"
    if (list.size > field.maxItems) return "超 ${field.maxItems} 条上限"
    val bad = list.firstOrNull { it !is String || it.isEmpty() || it.length > field.itemMaxLen }
    if (bad != null) return "每条须为 1..${field.itemMaxLen} 字的来源 id"
    return list.firstNotNullOfOrNull { checkString(field.key, it as String) }
}

private fun typeName(v: Any?): String = when (v) {
    null -> "null"
    is String -> "串"
    is Int, is Long -> "整数"
    is List<*> -> "数组"
    else -> v::class.simpleName ?: "未知"
}

/** 编码成一行 JSONL（不含换行符；键序按 schema，reading 按白名单序；null 用 JSONObject.NULL 保键位）。 */
internal fun encode(row: LoopReadingRow): String {
    val json = JSONObject()
        .put("ts", row.ts)
        .put("sceneId", row.sceneId)
        .put("loop", row.loop.wire)
        .put("status", row.status.wire)
        .put("reading", encodeReading(row))
        .put("source", row.source.wire)
        .put("ref", row.ref)
    row.needTicket?.let { json.put("needTicket", it) }
    return json.toString()
}

private fun encodeReading(row: LoopReadingRow): JSONObject {
    val reading = JSONObject()
    val spec = READING_SPEC.getValue(row.loop)
    for (field in spec) {
        if (!row.reading.containsKey(field.key)) continue
        when (val v = row.reading[field.key]) {
            null -> reading.put(field.key, JSONObject.NULL)
            is Int, is Long, is String -> reading.put(field.key, v)
            is List<*> -> reading.put(field.key, JSONArray(v))
        }
    }
    return reading
}

/** 一行→行对象；坏行返 null（坏 JSON／九环名／缺 ts 都算坏——[LoopReadingsLedger.audit] 会点名）。 */
internal fun parseLine(line: String): LoopReadingRow? {
    val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
    val loop = Loop.of(json.optString("loop")) ?: return null
    val status = Status.of(json.optString("status")) ?: return null
    val source = SourceKind.of(json.optString("source")) ?: return null
    val ts = json.optLong("ts", -1L)
    if (ts <= 0) return null
    return LoopReadingRow(
        ts = ts,
        sceneId = json.optString("sceneId"),
        loop = loop,
        status = status,
        reading = parseReading(json.optJSONObject("reading")),
        source = source,
        ref = json.optString("ref"),
        needTicket = if (json.has("needTicket")) json.optString("needTicket") else null,
    )
}

private fun parseReading(reading: JSONObject?): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    val json = reading ?: JSONObject()
    json.keys().asSequence().forEach { key ->
        out[key] = when (val v = json.get(key)) {
            JSONObject.NULL -> null
            is JSONArray -> List(v.length()) { i -> v.opt(i) }
            else -> v
        }
    }
    return out
}
