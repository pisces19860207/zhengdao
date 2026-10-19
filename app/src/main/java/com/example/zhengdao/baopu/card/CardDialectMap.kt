// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：docs/格式层规格-三套卡与酒馆PNG-2026-10-19.md
//   §15.2（四套激活策略完全不同，别互相套）→ F22：只在该 dialect 存在时才检查
//   §15.6（Tavo 是酒馆的改名版：字段对应表）→ F23：不许默认成 false/0
//   §15.6.1（注入顺序：relative vs before_char）→ F24：injectionDepth 只映射 depth
//   §16.3-3（三层并存：FormatKind.WEB_BOOK／WorldbookDialect.WEB／WEB_* 码表）
//
// 本文件是**纯表**：没有任何 I/O、没有状态。体检拿它判断「这条码属不属于这个方言」，
// 测试拿它当「一套规则套四家」的靶子。
package com.example.zhengdao.baopu.card

/** 方言字段对应表的一行（`null` = 该方言没有这个字段 ⇒ 不可表达）。 */
internal data class DialectFieldRow(
    val semantic: String,
    val web: String?,
    val st: String?,
    val tavo: String?,
    val ccv3: String?,
    val note: String = "",
)

internal object CardDialectMap {

    /** §15.6 的字段对应表（＋ §15.2 的 WEB 列）。**映射只许走这张表**。 */
    val TABLE: List<DialectFieldRow> = listOf(
        DialectFieldRow("关键词", "key", "key", "keywords", "keys"),
        DialectFieldRow("次级键", null, "keysecondary", "secondaryKeywords", "secondary_keys"),
        DialectFieldRow("次级逻辑", null, "selectiveLogic", "secondaryKeywordStrategy", "selective"),
        DialectFieldRow("开关", "enable", "disable", "enabled", "enabled", "唯一反义字段：ST 的 disable=true 才是关"),
        DialectFieldRow("插入位置", "value_region", "position", "injectionPosition", "position"),
        DialectFieldRow("深度", "depth", "depth", "injectionDepth", null),
        DialectFieldRow("角色", null, "role", "injectionRole", null),
        DialectFieldRow("常驻", null, "constant", "strategy", "constant", "Tavo 把常驻/向量化压进 strategy"),
        DialectFieldRow("概率", "probability", "probability", "probability", null),
        DialectFieldRow("概率开关", "value_type", "useProbability", null, null),
        DialectFieldRow("扫描深度", "depth", "scanDepth", "scanDepth", "scan_depth"),
        DialectFieldRow("整词匹配", null, "matchWholeWords", "matchWholeWord", null, "单复数不同名"),
        DialectFieldRow("大小写", null, "caseSensitive", "caseSensitive", "case_sensitive"),
        DialectFieldRow("排除递归", null, "excludeRecursion", "excludeRecursion", null),
        DialectFieldRow("阻止递归", null, "preventRecursion", "preventRecursion", null),
        DialectFieldRow("递归延迟", null, "delayUntilRecursion", "delayUntilRecursion", null),
        DialectFieldRow("粘滞", null, "sticky", "sticky", null),
        DialectFieldRow("冷却", null, "cooldown", "cooldown", null),
        DialectFieldRow("延迟", null, "delay", "delay", null),
        DialectFieldRow("分组", "group", "group", "groupName", null, "仅 group→groupName"),
        DialectFieldRow("组评分", null, "useGroupScoring", "useGroupScoring", null),
        DialectFieldRow("正文", "value", "content", "content", "content"),
        DialectFieldRow("顺序", "sort", "order", null, "insertion_order", "Tavo 没有 order ⇒ 不可表达"),
        DialectFieldRow("扫描人设", null, "matchPersonaDescription", null, null),
        DialectFieldRow("扫描角色描述", null, "matchCharacterDescription", null, null),
        DialectFieldRow("扫描角色性格", null, "matchCharacterPersonality", null, null),
        DialectFieldRow("扫描 depth prompt", null, "matchCharacterDepthPrompt", null, null),
        DialectFieldRow("扫描场景", null, "matchScenario", null, null),
        DialectFieldRow("扫描作者注", null, "matchCreatorNotes", null, null),
    )

    fun fieldOf(dialect: WorldbookDialect, row: DialectFieldRow): String? = when (dialect) {
        WorldbookDialect.WEB -> row.web
        WorldbookDialect.ST_NATIVE -> row.st
        WorldbookDialect.TAVO -> row.tavo
        WorldbookDialect.CCV3 -> row.ccv3
    }

    /**
     * [from] 有、[to] 没有的字段 ⇒ **不可表达**（F23）。
     *
     * 关键：这里返回的是「没有对应物」，调用方**不许**把它们补成 `false`/`0`——
     * 补 false 会把「本来会扫」静默变成「不扫」。
     */
    fun unexpressible(from: WorldbookDialect, to: WorldbookDialect): List<String> =
        TABLE.mapNotNull { row ->
            val f = fieldOf(from, row)
            if (f != null && fieldOf(to, row) == null) "${row.semantic}:$f" else null
        }

    /** 只拿字段名（不带语义前缀）。 */
    fun unexpressibleFields(from: WorldbookDialect, to: WorldbookDialect): List<String> =
        unexpressible(from, to).map { it.substringAfter(":") }

    /** 某一侧字段对应到另一侧的字段名；没有对应物 ⇒ null。 */
    fun mappingOf(dialect: WorldbookDialect, field: String): String? =
        TABLE.firstOrNull { fieldOf(dialect, it) == field }?.let { row ->
            val other = when (dialect) {
                WorldbookDialect.ST_NATIVE -> row.tavo
                WorldbookDialect.TAVO -> row.st
                else -> null
            }
            other
        }

    /** 有损清单（F24：`relative` 与 `before_char` 的语义差必须写进去）。 */
    fun lossyNotes(from: WorldbookDialect, to: WorldbookDialect): List<String> {
        val out = ArrayList<String>()
        if (from == WorldbookDialect.ST_NATIVE && to == WorldbookDialect.TAVO) {
            if ("order" in unexpressibleFields(from, to)) {
                out += "ST 的 order 在 Tavo 没有对应物 ⇒ 不可表达（Tavo 的 relative 顺序跟着预设条目顺序走）"
            }
            out += "ST 的 before_char / after_char 是两个固定锚点；Tavo 的 relative 是「跟着预设走」⇒ 语义不完全相同"
            val matchFields = unexpressibleFields(from, to).filter { it.startsWith("match") }
            if (matchFields.isNotEmpty()) {
                out += "ST 的六个 match* 在 Tavo 没有开关 ⇒ 不可表达（不许默认成 false：那会把「本来会扫」变成「不扫」）"
            }
        }
        if (from == WorldbookDialect.TAVO && to == WorldbookDialect.ST_NATIVE) {
            out += "Tavo 的 strategy（含常驻档）要拆成 ST 的 constant/vectorized ⇒ 有损"
            out += "Tavo 的 relative 顺序无法还原成 ST 的 order（数值不可推断）"
        }
        return out
    }

    /** 注入深度的唯一去处（F24：只许映射 ST 的 depth，不许当 order 用）。 */
    const val INJECTION_DEPTH_TARGET = "depth"
}

/**
 * F22：每个方言「只跑自己那张表」。
 *
 * 两个作用：① 体检用来兜底断言（出了不属于本方言的码就是实现错）；
 * ② 测试用来钉「拿 A 家的字段判 B 家」的反面。
 */
internal object CardDialectChecks {

    /** 三套字段级码（§4.2.1／§4.2.2／§4.2.3）。 */
    val WEB_FIELD_CODES: Set<String> = setOf(
        "WEB_MISSING_FIELD", "WEB_TYPE_MISMATCH", "WEB_PROBABILITY_RANGE", "WEB_DEPTH_RANGE",
        "WEB_KEY_REGION_RANGE", "WEB_VALUE_REGION_RANGE", "WEB_MATCH_TYPE_RANGE",
        "WEB_VALUE_TYPE_RANGE", "WEB_VALUE_CONFIGS_CONFLICT", "WEB_EMPTY_KEY",
        "WEB_SORT_DUPLICATE", "WEB_GROUP_EMPTY",
    )
    val TAVO_FIELD_CODES: Set<String> = setOf(
        "TV_MISSING_FIELD", "TV_TYPE_MISMATCH", "TV_PROBABILITY_RANGE", "TV_KEYWORDS_EMPTY",
        "TV_SCAN_DEPTH_ODD", "TV_INJECTION_DEPTH_ODD", "TV_POSITION_UNKNOWN",
        "TV_STRATEGY_UNKNOWN", "TV_SECONDARY_STRATEGY_UNKNOWN", "TV_CONTENT_EMPTY",
        "TV_CASE_MATCH_ODD",
    )
    val ST_FIELD_CODES: Set<String> = setOf(
        "ST_ENTRY_KEY_NOT_INT", "ST_KEY_NOT_ARRAY", "ST_KEY_ELEM_NOT_STRING",
        "ST_KEYsecondary_NOT_ARRAY", "ST_CONTENT_TYPE", "ST_DISABLE_TYPE",
        "ST_PROBABILITY_RANGE", "ST_DEPTH_RANGE", "ST_NULLED_FIELD",
        "ST_DELAYRECUR_DUAL_TYPE", "ST_CONTENT_EMPTY", "ST_UNKNOWN_FIELD_PRESERVED",
    )
    val CCV3_FIELD_CODES: Set<String> = emptySet()

    /** 方言专属的语义码（区域／分隔符／不适用的说法各不一样）。 */
    val WEB_SEMANTIC_CODES: Set<String> = setOf(
        "WEB_KEY_TOO_BROAD", "WEB_KEY_TOO_BROAD_AND", "WEB_KEY_TOO_BROAD_IMPLICIT",
        "WEB_NOT_APPLICABLE_REGION", "KEYSEP_BROKEN", "REGION_KEY_NO_SELF_TRIGGER", "REGION_VALUE_MULTI",
    )
    val TAVO_SEMANTIC_CODES: Set<String> = setOf(
        "TV_NOT_APPLICABLE_CONSTANT", "TV_NOT_APPLICABLE_DISABLED",
    )
    val ST_SEMANTIC_CODES: Set<String> = setOf(
        "ST_NOT_APPLICABLE_CONSTANT", "ST_NOT_APPLICABLE_DISABLED",
    )

    /** 与方言无关的码（三套都会出）。 */
    val SHARED_CODES: Set<String> = setOf(
        "KEY_IS_HTML_TOKEN", "KEY_COLLISION", "KEY_COLLISION_DISJOINT_REGION",
        "KEY_ORDER_DIFF", "TRIGGER_CLASS_C", "TRIGGER_UNJUDGED_REGEX",
        "CARD_EXTERNAL_WORLDBOOK_UNJUDGED", "CARD_EMBEDDED_REGEX_UNJUDGED",
        "CARD_EMBEDDED_THEME_UNJUDGED", "CARD_EMBEDDED_BOOK_UNJUDGED",
    )

    /** 该方言允许出现的全部码。 */
    fun codesFor(dialect: WorldbookDialect): Set<String> = when (dialect) {
        WorldbookDialect.WEB -> WEB_FIELD_CODES + WEB_SEMANTIC_CODES + SHARED_CODES
        WorldbookDialect.TAVO -> TAVO_FIELD_CODES + TAVO_SEMANTIC_CODES + SHARED_CODES
        WorldbookDialect.ST_NATIVE -> ST_FIELD_CODES + ST_SEMANTIC_CODES + SHARED_CODES
        WorldbookDialect.CCV3 -> CCV3_FIELD_CODES + SHARED_CODES
    }

    /** 该方言**不许**出现的码前缀（测试与自检用）。 */
    fun foreignPrefixes(dialect: WorldbookDialect): List<String> = when (dialect) {
        WorldbookDialect.WEB -> listOf("TV_", "ST_")
        WorldbookDialect.TAVO -> listOf("WEB_", "ST_")
        WorldbookDialect.ST_NATIVE -> listOf("WEB_", "TV_")
        WorldbookDialect.CCV3 -> listOf("WEB_", "TV_", "ST_")
    }
}
