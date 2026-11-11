// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十五批（2026-11-11）：字段映射表 + 酒馆 schema 校验器。
//
// ★ 唯一真相源：导出、导入、schema 校验、双向映射文档、"会丢"清单
//   全部从下面两张表派生。加一个字段只改这里一处，不许在别处抄第二份事实。
//
// ★ 2026-11-11 形状修正（用户回源 ST v1.14.0）：**两种文件、两种 entries，不许说成一样**。
//   [TavernShape.CARD_BOOK]      卡内 data.character_book.entries：数组 + 关键词 keys
//   [TavernShape.STANDALONE]     独立 *.worldbook.json 的 entries：字典(uid) + 关键词 key
//   两种形状共用同一张 [ENTRY_ROWS]，只有「主关键词字段名」按形状换名，
//   差异集中在本文件一处 [fieldNameOf]，不在 Codec 里各写一份。
//
// 纯 Kotlin ＋ org.json；零 Android 依赖、零 IO（落盘在 TavernExport）。
package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject

/** 两种目标形状（判据⑫⑬）：卡内嵌世界书 vs 独立世界书文件。 */
internal enum class TavernShape {
    /** `data.character_book.entries` 是数组，关键词字段 `keys`。 */
    CARD_BOOK,

    /** 独立 `*.worldbook.json` 的 `entries` 是字典（键=uid），关键词字段 `key`。 */
    STANDALONE,
}

/** 字段类型（判据②：按酒馆 schema 校验字段类型/必填）。 */
internal enum class TavernFieldType {
    STRING, BOOL, INT, STRING_ARRAY, OBJECT;

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
    fun line(): String =
        "| `$ours` | `$tavern` | `$tavern` | ${type.label} | ${if (required) "必填" else "可选"} | ${note.ifEmpty { "—" }} |"
}

internal object TavernFields {

    const val CARD_SPEC = "chara_card_v3"
    const val CARD_SPEC_VERSION = "3.0"
    const val WORLD_ENTRIES = "entries"
    const val WORLD_NAME = "name"
    const val CARD_BOOK_KEY = "character_book"

    /** V2 顶层 data 没有 spec 包裹（导入吃 v2/v3）。 */
    const val CARD_SPEC_V2 = "chara_card_v2"

    /** position 双轨（判据⑭）：顶层只这两个字符串，真值在 extensions.position。 */
    val TOP_POSITIONS: Set<String> = setOf("before_char", "after_char")
    const val EXT_POSITION = "position"
    const val EXT_DEPTH = "depth"
    const val EXT_ROLE = "role"
    val ST_POSITION_RANGE: IntRange = 0..7
    const val ST_AT_DEPTH = 4
    val ST_ROLE_RANGE: IntRange = 0..2
    const val EXTENSIONS = "extensions"

    /**
     * V3 校验极宽松（只验 `data` 是对象）⇒ 导出自己补全这 **14 个标准字段**，
     * 一个不少。名字从这张表出，不手写第二份。
     */
    val V3_STANDARD_FIELDS: List<String> = listOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example",
        "creator_notes", "system_prompt", "post_history_instructions", "alternate_greetings",
        "character_book", "tags", "creator", "character_version",
    )

    /** 角色卡 `data` 内字段（＝14 个标准字段，逐行带类型，导出时一格不漏）。 */
    val CARD_ROWS: List<TavernFieldRow> = listOf(
        TavernFieldRow("name", "name", TavernFieldType.STRING, true),
        TavernFieldRow("description", "description", TavernFieldType.STRING, true, "铁律②：恒空串；人设不进这里"),
        TavernFieldRow("personality", "personality", TavernFieldType.STRING, true),
        TavernFieldRow("scenario", "scenario", TavernFieldType.STRING, true),
        TavernFieldRow("firstMes", "first_mes", TavernFieldType.STRING, true),
        TavernFieldRow("mesExample", "mes_example", TavernFieldType.STRING, true),
        TavernFieldRow("creatorNotes", "creator_notes", TavernFieldType.STRING, true),
        TavernFieldRow("systemPrompt", "system_prompt", TavernFieldType.STRING, true, "V3 宽松，导出自己补"),
        TavernFieldRow("postHistoryInstructions", "post_history_instructions", TavernFieldType.STRING, true, "V3 宽松，导出自己补"),
        TavernFieldRow("alternateGreetings", "alternate_greetings", TavernFieldType.STRING_ARRAY, true, "V3 宽松，导出自己补"),
        TavernFieldRow("characterBook", "character_book", TavernFieldType.OBJECT, true, "卡内嵌世界书：entries 数组 + keys"),
        TavernFieldRow("tags", "tags", TavernFieldType.STRING_ARRAY, true),
        TavernFieldRow("creator", "creator", TavernFieldType.STRING, false),
        TavernFieldRow("characterVersion", "character_version", TavernFieldType.STRING, false),
    )

    /** 世界书条目字段：用户明确给定的 11 项 ＋ 派工单另列的可选兼容字段。 */
    val ENTRY_ROWS: List<TavernFieldRow> = listOf(
        TavernFieldRow("keys", "keys", TavernFieldType.STRING_ARRAY, true, "三种写法：纯文本／多值／`/re/` 正则键；卡内叫 keys，独立文件叫 key"),
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
        TavernFieldRow("position", "position", TavernFieldType.STRING, false, "顶层只 before_char／after_char；真值在 extensions.position"),
        TavernFieldRow("useProbability", "useProbability", TavernFieldType.BOOL, false, "派工单另列；可选兼容"),
    )

    /** 顶层已知键（其余一律算「我方不解析 ⇒ 会丢」）。 */
    val CARD_TOP_KEYS: Set<String> = setOf("spec", "spec_version", "data")
    val WORLD_TOP_KEYS: Set<String> = setOf(WORLD_ENTRIES, WORLD_NAME)

    /** `data` 内除 14 标准字段外，我方还认识（并会接进 DTO）的容器字段。 */
    val CARD_DATA_KNOWN_EXTRA: Set<String> = setOf(EXTENSIONS)

    private val CARD_TAVERN: Set<String> = CARD_ROWS.map { it.tavern }.toSet()
    private val ENTRY_TAVERN: Set<String> = ENTRY_ROWS.map { it.tavern }.toSet()

    /**
     * **唯一一处形状换名**：只有主关键词那一行随形状换名，其余字段名两边相同。
     * 卡内数组用 `keys`，独立字典用 `key`。不许把两者说成一样。
     */
    fun fieldNameOf(shape: TavernShape, row: TavernFieldRow): String = when {
        row.ours == "keys" && shape == TavernShape.STANDALONE -> "key"
        else -> row.tavern
    }

    fun isKnownCardKey(k: String): Boolean =
        k in CARD_TOP_KEYS || k in CARD_TAVERN || k in CARD_DATA_KNOWN_EXTRA
    fun isKnownWorldKey(k: String): Boolean = k in WORLD_TOP_KEYS

    /** 导入两种形状都吃：`keys`（卡内）与 `key`（独立文件）都算已知。 */
    fun isKnownEntryKey(k: String): Boolean = k in ENTRY_TAVERN || k == "key" || k == EXTENSIONS

    /** 双向映射文档（两列映射各一列），从表生成，不手写第二份。 */
    fun renderMappingMarkdown(): String = buildString {
        appendLine("| 我们的字段 | 导出→酒馆 | 酒馆→导入 | 类型 | 必填 | 不支持时处置 |")
        appendLine("| --- | --- | --- | --- | --- | --- |")
        CARD_ROWS.forEach { appendLine(it.line()) }
        ENTRY_ROWS.forEach { appendLine(it.line()) }
    }

    // ── schema 校验（判据②⑧⑫⑬）──────────────────────────────────────────
    //
    // 刻意做成「输入一段 JSON 文本、返回违规清单」的纯函数：测试里从**磁盘读回**
    // 导出产物再喂进来，不拿本次刚 render 的内存字符串自证（454 教训）。

    data class Violation(val path: String, val reason: String) {
        fun line(): String = "$path：$reason"
    }

    fun validateCard(text: String): List<Violation> {
        val o = parse(text) ?: return listOf(Violation("根", "不是合法 JSON 对象"))
        val out = ArrayList<Violation>()
        checkScalar(o, "spec", TavernFieldType.STRING, true, "根", out)
        if (o.optString("spec") != CARD_SPEC) out += Violation("根.spec", "必须是 '$CARD_SPEC'")
        checkScalar(o, "spec_version", TavernFieldType.STRING, true, "根", out)
        val data = o.opt("data")
        if (data !is JSONObject) {
            out += Violation("根.data", "缺失或不是对象")
            return out
        }
        // V3 校验宽松 ⇒ 我方自检反过来要严：14 个标准字段一个不少。
        for (f in V3_STANDARD_FIELDS) {
            if (!data.has(f)) out += Violation("根.data.$f", "V3 标准字段缺失（导出必须自己补全）")
        }
        for (r in CARD_ROWS) checkScalar(data, r.tavern, r.type, r.required, "根.data", out)
        if (data.has("description") && data.opt("description") != "") {
            out += Violation("根.data.description", "铁律②：必须留空串（人设进世界书）")
        }
        return out
    }

    /** 卡内 character_book：判据⑫ —— entries 必须是**数组**、关键词字段必须是 `keys`。 */
    fun validateCardBook(text: String): List<Violation> = validateEntries(text, TavernShape.CARD_BOOK)

    /** 独立世界书：判据⑬ —— entries 必须是**字典**（键=整数 uid）、关键词字段 `key`。 */
    fun validateStandaloneWorld(text: String): List<Violation> = validateEntries(text, TavernShape.STANDALONE)

    /** 兼容旧调用点／导入侧：按形状委派，不再"两种形状都算过"。 */
    fun validateWorld(text: String): List<Violation> = validateStandaloneWorld(text)

    private fun validateEntries(text: String, shape: TavernShape): List<Violation> {
        val o = parse(text) ?: return listOf(Violation("根", "不是合法 JSON 对象"))
        val out = ArrayList<Violation>()
        for (k in o.keys()) {
            if (!isKnownWorldKey(k)) out += Violation("根.$k", "表外顶层键（导入会丢）")
        }
        val entries = o.opt(WORLD_ENTRIES)
            ?: return out + Violation("根.entries", "缺失（必填）")
        when (shape) {
            TavernShape.CARD_BOOK -> checkArray(entries, shape, out)
            TavernShape.STANDALONE -> checkDict(entries, shape, out)
        }
        return out
    }

    private fun checkArray(entries: Any, shape: TavernShape, out: MutableList<Violation>) {
        if (entries !is JSONArray) {
            out += Violation("根.entries", "卡内 character_book.entries 必须是数组（判据⑫）")
            return
        }
        for (i in 0 until entries.length()) {
            val e = entries.opt(i)
            if (e !is JSONObject) out += Violation("entries[$i]", "不是对象")
            else checkEntryRows(e, shape, "entries[$i]", out)
        }
    }

    private fun checkDict(entries: Any, shape: TavernShape, out: MutableList<Violation>) {
        if (entries !is JSONObject) {
            out += Violation("根.entries", "独立世界书 entries 必须是字典（判据⑬）")
            return
        }
        for (k in entries.keys()) {
            if (k.toIntOrNull() == null) out += Violation("entries.$k", "uid 键不是整数（酒馆按整数 uid 排条目）")
            val e = entries.opt(k)
            if (e !is JSONObject) out += Violation("entries.$k", "不是对象")
            else checkEntryRows(e, shape, "entries.$k", out)
        }
    }

    private fun checkEntryRows(
        e: JSONObject,
        shape: TavernShape,
        base: String,
        out: MutableList<Violation>,
    ) {
        for (r in ENTRY_ROWS) {
            val name = fieldNameOf(shape, r)
            checkScalar(e, name, r.type, r.required, base, out)
        }
        // 判据⑭：position 双轨不许混。
        if (e.has(EXTENSIONS)) {
            val ext = e.optJSONObject(EXTENSIONS)
            if (ext == null) out += Violation("$base.extensions", "不是对象")
            else checkPositionDualTrack(e, ext, base, out)
        }
    }

    /** 顶层只 before/after；真值 8 档在 extensions.position；atDepth 还要 depth + role。 */
    private fun checkPositionDualTrack(
        e: JSONObject,
        ext: JSONObject,
        base: String,
        out: MutableList<Violation>,
    ) {
        if (e.has("position") && e.optString("position") !in TOP_POSITIONS) {
            out += Violation("$base.position", "顶层只允许 before_char／after_char（判据⑭）")
        }
        if (ext.has(EXT_POSITION)) {
            val p = ext.opt(EXT_POSITION)
            if (p !is Int || p !in ST_POSITION_RANGE) {
                out += Violation("$base.extensions.position", "必须是 0..7 的整数（判据⑭）")
                return
            }
            if (p == ST_AT_DEPTH) {
                val d = ext.opt(EXT_DEPTH)
                val r = ext.opt(EXT_ROLE)
                if (d !is Int) out += Violation("$base.extensions.depth", "atDepth 必须配整数 depth")
                if (r !is Int || r !in ST_ROLE_RANGE) {
                    out += Violation("$base.extensions.role", "atDepth 必须配 0..2 的 role")
                }
            }
        }
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
