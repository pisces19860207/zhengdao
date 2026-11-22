// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 段 A（2026-11-22）· 教材条目 schema。
// 十字段闭集，缺一拒收；本包按派工单段 A 明确不接注入、不接判据，
// 零外部调用是判据⑥而不是缺口。全部 internal：编译期即挡住"顺手接一下"。
package com.example.zhengdao.baopu.textbook

import org.json.JSONObject

/** 来源类型四档闭集。非法值拒收，不做模糊匹配。 */
internal enum class SourceType { 文献, 标准规范, 经验总结, 用户口述 }

/** 域四档闭集。非法值拒收，不做模糊匹配。 */
internal enum class Domain { 事实域, 设定域, 工程域, 规范域 }

/** 十字段闭集；顺序即报错顺序，也是测试逐字段造负例的顺序。 */
internal val TEXTBOOK_FIELDS: List<String> = listOf(
    "id", "sourceType", "source", "asOf", "domain",
    "content", "strength", "scope", "revocable", "supersedes",
)

/** 非空字段（supersedes 允许空串＝无前序；revocable 用 false 表可撤销关）。 */
private val NONBLANK_FIELDS: List<String> = listOf(
    "id", "sourceType", "source", "asOf", "domain", "content", "strength", "scope",
)

/**
 * 一条教材。字段一字不许少：`supersedes` 为空表示新条，非空表示它取代的那条 id。
 * `asOf` 保留 ISO 日期文本而不是 LocalDate——录入路径要先能拒"日期写错"，再谈解析。
 */
internal data class TextbookEntry(
    val id: String,
    val sourceType: SourceType,
    val source: String,
    val asOf: String,
    val domain: Domain,
    val content: String,
    val strength: String,
    val scope: String,
    val revocable: Boolean,
    val supersedes: String,
)

/** 解析结果：Ok 带条目，Err 带缺字段与非法值，绝不静默补默认值。 */
internal sealed interface EntryParse {
    data class Ok(val entry: TextbookEntry) : EntryParse
    data class Err(val missing: List<String>, val illegal: List<String>) : EntryParse
}

internal object TextbookSchema {
    val SOURCE_TYPES: Set<String> = SourceType.entries.map { it.name }.toSet()
    val DOMAINS: Set<String> = Domain.entries.map { it.name }.toSet()

    fun parse(raw: JSONObject): EntryParse {
        val missing = TEXTBOOK_FIELDS.filter { !raw.has(it) || raw.isNull(it) }.toMutableList()
        NONBLANK_FIELDS.filter { it !in missing && raw.optString(it).isBlank() }.forEach { missing += it }

        val illegal = mutableListOf<String>()
        val st = raw.optString("sourceType")
        val dm = raw.optString("domain")
        if (st.isNotBlank() && st !in SOURCE_TYPES) illegal += "sourceType=$st"
        if (dm.isNotBlank() && dm !in DOMAINS) illegal += "domain=$dm"
        if (raw.has("revocable") && raw.get("revocable") !is Boolean) illegal += "revocable 非布尔"

        if (missing.isNotEmpty() || illegal.isNotEmpty()) return EntryParse.Err(missing, illegal)

        return EntryParse.Ok(
            TextbookEntry(
                id = raw.getString("id"),
                sourceType = SourceType.valueOf(st),
                source = raw.getString("source"),
                asOf = raw.getString("asOf"),
                domain = Domain.valueOf(dm),
                content = raw.getString("content"),
                strength = raw.getString("strength"),
                scope = raw.getString("scope"),
                revocable = raw.getBoolean("revocable"),
                supersedes = raw.optString("supersedes"),
            ),
        )
    }

    fun toJson(entry: TextbookEntry): JSONObject = JSONObject()
        .put("id", entry.id)
        .put("sourceType", entry.sourceType.name)
        .put("source", entry.source)
        .put("asOf", entry.asOf)
        .put("domain", entry.domain.name)
        .put("content", entry.content)
        .put("strength", entry.strength)
        .put("scope", entry.scope)
        .put("revocable", entry.revocable)
        .put("supersedes", entry.supersedes)
}
