// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十五批（2026-11-11）：字段映射表 + 酒馆 schema 校验器。
//
// ★ 唯一真相源：导出、导入、schema 校验、双向映射文档、"会丢"清单
//   全部从下面两张表派生。加一个字段只改这里一处，不许在别处抄第二份事实
//   （项目方法纪律：文档里的计数/清单必须从表驱动唯一真相复现）。
//
// 纯 Kotlin ＋ org.json；零 Android 依赖、零 IO（落盘在 TavernExport）。
package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject

/** 字段类型（判据②：按酒馆 schema 校验字段类型/必填）。 */
internal enum class TavernFieldType {
    STRING, BOOL, INT, STRING_ARRAY, OBJECT;

    /** org.json 取出来的 Java 对象是否合本字段的类型。 */
    fun accepts(v: Any?): Boolean = when (this) {
        STRING -> v is String
        BOOL -> v is Boolean
        INT -> v is Int || v is Long
        STRING_ARRAY -> v is JSONArray && (0 until v.length()).all { v.opt(it) is String }
        OBJECT -> v is JSONObject
    }

    val label: String get() = when (this) {
        STRING -> "字符串"
        BOOL -> "布尔"
        INT -> "整数"
        STRING_ARRAY -> "字符串数组"
        OBJECT -> "对象"
    }
}

/** 一行映射：我们的字段名 ↔ 酒馆字段名 ↔ 类型/必填/处置。 */
internal data class TavernFieldRow(
    val ours: String,
    val tavern: String,
    val type: TavernFieldType,
    val required: Boolean,
    val note: String = "",
) {
    /** 双向映射表的一行（导出列＋导入列各一列）。 */
    val mappingLine: String get() =
        "| `$ours` | `$tavern` | `$tavern` | ${type.label} | ${if (required) "必填" else "可选"} | ${note.ifEmpty { "—" }} |"
}

internal object TavernFields {

    const val CARD_SPEC = "chara_card_v3"
    const val CARD_SPEC_VERSION = "3.0"
    const val WORLD_ENTRIES = "entries"
    const val WORLD_NAME = "name"

    /** 角色卡 `data` 内的字段（用户派工单逐字清单）。 */
    val CARD_ROWS: List<TavernFieldRow> = listOf(
        TavernFieldRow("name", "name", TavernFieldType.STRING, true),
        TavernFieldRow("description", "description", TavernFieldType.STRING, true, "铁律②：恒空串；人设不进这里"),
        TavernFieldRow("personality", "personality", TavernFieldType.STRING, true),
        TavernFieldRow("scenario", "scenario", TavernFieldType.STRING, true),
        TavernFieldRow("firstMes", "first_mes", TavernFieldType.STRING, true),
        TavernFieldRow("mesExample", "mes_example", TavernFieldType.STRING, true),
        TavernFieldRow("creatorNotes", "creator_notes", TavernFieldType.STRING, true),
        TavernFieldRow("tags", "tags", TavernFieldType.STRING_ARRAY, true),
        TavernFieldRow("creator", "creator", TavernFieldType.STRING, false),
        TavernFieldRow("characterVersion", "character_version", TavernFieldType.STRING, false),
    )

    /** 世界书条目字段：用户明确给定的 11 项 ＋ 派工单另列的 2 项可选兼容字段。 */
    val ENTRY_ROWS: List<TavernFieldRow> = listOf(
        TavernFieldRow("keys", "keys", TavernFieldType.STRING_ARRAY, true, "三种写法：纯文本／多值／`/re/` 正则键"),
        TavernFieldRow("keysecondary", "keysecondary", TavernFieldType.STRING_ARRAY, true, "次级键"),
        TavernFieldRow("content", "content", TavernFieldType.STRING, true),
        TavernFieldRow("constant", "constant", TavernFieldType.BOOL, true),
        TavernFieldRow("selective", "selective", TavernFieldType.BOOL, true),
        TavernFieldRow("order", "order", TavernFieldType.INT, true),
        TavernFieldRow("depth", "depth", TavernFieldType.INT, true, "只搬运数值，不做插入深度算法（边界）"),
        TavernFieldRow("probability", "probability", TavernFieldType.INT, true),
        TavernFieldRow("disable", "disable", TavernFieldType.BOOL, true, "反义字段：true＝关闭"),
        TavernFieldRow("addMemo", "addMemo", TavernFieldType.BOOL, true),
        TavernFieldRow("group", "group", TavernFieldType.STRING, true),
        TavernFieldRow("position", "position", TavernFieldType.INT, false, "派工单另列；可选兼容"),
        TavernFieldRow("useProbability", "useProbability", TavernFieldType.BOOL, false, "派工单另列；可选兼容"),
    )

    /** 顶层已知键（其余一律算「我方不解析 ⇒ 会丢」）。 */
    val CARD_TOP_KEYS: Set<String> = setOf("spec", "spec_version", "data")
    val WORLD_TOP_KEYS: Set<String> = setOf(WORLD_ENTRIES, WORLD_NAME)

    private val CARD_TAVERN: Set<String> = CARD_ROWS.map { it.tavern }.toSet()
    private val ENTRY_TAVERN: Set<String> = ENTRY_ROWS.map { it.tavern }.toSet()

    /** 导入时某键是否是我们认识的卡字段。 */
    fun isKnownCardKey(k: String): Boolean = k in CARD_TOP_KEYS || k in CARD_TAVERN

    /** 导入时某键是否是我们认识的世界书字段。 */
    fun isKnownWorldKey(k: String): Boolean = k in WORLD_TOP_KEYS

    /** 导入时某键是否是我们认识的条目字段；`key` 是酒馆原生字典形状的主键别名。 */
    fun isKnownEntryKey(k: String): Boolean = k in ENTRY_TAVERN || k == "key"

    /** 双向映射文档（两列映射各一列），从表生成，不手写第二份。 */
    fun renderMappingMarkdown(): String = buildString {
        appendLine("| 我们的字段 | 导出→酒馆 | 酒馆→导入 | 类型 | 必填 | 不支持时处置 |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        CARD_ROWS.forEach { appendLine(it.mappingLine) }
        ENTRY_ROWS.forEach { appendLine(it.mappingLine) }
    }

    // ── schema 校验（判据②）──────────────────────────────────────────────
    //
    // 刻意做成「输入一段 JSON 文本、返回违规清单」的纯函数：测试里从**磁盘读回**
    // 导出产物再喂进来，不拿本次刚 render 的内存字符串自证（454 教训）。

    data class Violation(val path: String, val reason: String) {
        val line: String get() = "$path：$reason"
    }

    /** 卡：`spec` / `spec_version` / `data` 必填，data 内逐行查类型与必填。 */
    fun validateCard(text: String): List<Violation> {
        val o = parse(text) ?: return listOf(Violation("根", "不是合法 JSON 对象"))
        val out = ArrayList<Violation>()
        checkScalar(o, "spec", TavernFieldType.STRING, true, "根", out)
        if (o.optString("spec") != CARD_SPEC) {
            out += Violation("根.spec", "必须是 '$CARD_SPEC'")
        }
        checkScalar(o, "spec_version", TavernFieldType.STRING, true, "根", out)
        val data = o.opt("data")
        if (data !is JSONObject) {
            out += Violation("根.data", "缺失或不是对象")
            return out
        }
        checkRows(data, CARD_ROWS, "根.data", out)
        if (data.has("description") && data.opt("description") != "") {
            out += Violation("根.data.description", "铁律②：必须留空串（人设进世界书）")
        }
        return out
    }

    /**
     * 世界书：顶层 `entries` 必填；形状为数组（本批导出形状）或整数 uid 字典
     * （酒馆原生形状，导入兼容）。逐条按 [ENTRY_ROWS] 查类型与必填。
     */
    fun validateWorld(text: String): List<Violation> {
        val o = parse(text) ?: return listOf(Violation("根", "不是合法 JSON 对象"))
        val out = ArrayList<Violation>()
        for (k in o.keys()) {
            if (!isKnownWorldKey(k)) out += Violation("根.$k", "表外顶层键（导入会丢）")
        }
        val entries = o.opt(WORLD_ENTRIES)
        if (entries == null) return out + Violation("根.entries", "缺失（必填）")
        when (entries) {
            is JSONArray -> for (i in 0 until entries.length()) {
                val e = entries.opt(i)
                if (e !is JSONObject) out += Violation("entries[$i]", "不是对象")
                else checkRows(e, ENTRY_ROWS, "entries[$i]", out)
            }
            is JSONObject -> for (k in entries.keys()) {
                val e = entries.opt(k)
                if (e !is JSONObject) out += Violation("entries.$k", "不是对象")
                else checkRows(withKeyAlias(e), ENTRY_ROWS, "entries.$k", out)
            }
            else -> out += Violation("根.entries", "既不是数组也不是字典")
        }
        return out
    }

    /**
     * 酒馆原生字典形状把主关键词叫 `key`（单数），本批数组形状叫 `keys`。
     * 校验前做一次**只读别名补齐**（复制对象，不改原 JSON），这样两种形状
     * 都按同一张 [ENTRY_ROWS] 判，不是第二份字段表。
     */
    private fun withKeyAlias(src: JSONObject): JSONObject {
        val copy = JSONObject(src.toString())
        if (!copy.has("keys") && copy.has("key")) copy.put("keys", copy.opt("key"))
        return copy
    }

    private fun checkRows(
        o: JSONObject,
        rows: List<TavernFieldRow>,
        base: String,
        out: MutableList<Violation>,
    ) {
        for (r in rows) checkScalar(o, r.tavern, r.type, r.required, base, out)
    }

    private fun checkScalar(
        o: JSONObject,
        key: String,
        type: TavernFieldType,
        required: Boolean,
        base: String,
        out: MutableList<Violation>,
    ) {
        val path = "$base.$key"
        if (!o.has(key)) {
            if (required) out += Violation(path, "缺失（必填）")
            return
        }
        val v = o.opt(key)
        if (v === JSONObject.NULL) {
            if (required) out += Violation(path, "是 null（必填）")
            return
        }
        if (!type.accepts(v)) out += Violation(path, "类型不是${type.label}")
    }

    private fun parse(text: String): JSONObject? = try {
        JSONObject(CardJson.withoutBom(text))
    } catch (e: Exception) {
        null
    }
}
