// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
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
     * 某一侧**有名字**的全部字段（第一百四十九批 组4：体检的字段名从这里取）。
     *
     * 为什么必须由这里出：`CardCheck` 原来自己写死一份 43 个名字的表，与这张对应表
     * **没有机械关系**——对应表里改一个字段名（或补一列），体检表不会跟着动，
     * 于是「表外字段」那条 INFO 判据就开始凭第二份事实判（规格 §15.6 的口径只有一个出处）。
     * 现在体检表 = `fieldsOf(本方言)` ∪ 规格表外补充名（那份补充名逐条写得出理由）。
     */
    fun fieldsOf(dialect: WorldbookDialect): Set<String> =
        TABLE.mapNotNull { fieldOf(dialect, it) }.toSet()

    /**
     * 认脸结果 → 血统（§16.3-3 三层并存里「格式 → 方言」这一层的唯一映射）。
     *
     * 只有世界书四类有血统；卡／正则／预设／认脸失败 ⇒ null（不硬造一个方言，
     * 与 [CardAsset] 的 `dialect` 可空口径一致）。体检拿它决定「该跑哪张表」，
     * 拿到 null 又确实是世界书格式时**必须显式报错**，不许默默跳过（F22 的兜底断言）。
     */
    fun dialectOf(format: FormatKind): WorldbookDialect? = when (format) {
        FormatKind.WEB_BOOK -> WorldbookDialect.WEB
        FormatKind.TAVO_BOOK -> WorldbookDialect.TAVO
        FormatKind.ST_WORLD -> WorldbookDialect.ST_NATIVE
        FormatKind.CCV3_LOREBOOK -> WorldbookDialect.CCV3
        FormatKind.TAVO_CHAR, FormatKind.ST_CARD_PNG, FormatKind.TAVO_REGEX,
        FormatKind.TAVO_PRESET, FormatKind.UNKNOWN -> null
    }

    /** 四类世界书格式（`dialectOf` 返回 null 而格式在这一列里 ⇒ 映射漏了，必须报错）。 */
    val WORLDBOOK_FORMATS: Set<FormatKind> = setOf(
        FormatKind.WEB_BOOK, FormatKind.TAVO_BOOK, FormatKind.ST_WORLD, FormatKind.CCV3_LOREBOOK,
    )

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

    // ── F22 兜底断言（第一百四十九批 组4：接到体检链上）─────────────────────
    //
    // 上面两张表（[codesFor]／[foreignPrefixes]）立账以来只有测试在读：`CardCheck.check`
    // 自己 dispatch 完就直接出报告，**没有任何一步问"这次出的码属不属于本方言"**。
    // 于是"拿 A 家的字段表判 B 家的书"这类改动能在测试全绿的前提下溜进生产
    // ——测试钉的是各家各自的样例，钉不住"dispatch 走错门"这一层。
    // 这里把判据收成一个函数，`check()` 在出报告前调它。

    /** 守卫自己会出的码（不参与「外来码」判定，否则自我指控）。 */
    val GUARD_CODES: Set<String> = setOf("DIALECT_UNMAPPED_FORMAT", "DIALECT_FOREIGN_CODE")

    /** 外来码前缀 → 人话是谁家的码（与 [foreignPrefixes] 同一份事实）。 */
    private fun houseOf(prefix: String?): String? = when (prefix) {
        "WEB_" -> "网络平台"
        "TV_" -> "Tavo"
        "ST_" -> "酒馆"
        else -> null
    }

    /**
     * 认脸说是世界书、血统表却给不出方言 ⇒ **必须显式报错**（不许静默当成"没问题"）。
     *
     * 这条分支在表写全的现在不可达（`dialectOf` 穷举了 9 个 FormatKind）；它守的是**以后**：
     * 有人往 FormatKind 加了第五种世界书、或往 [CardDialectMap.WORLDBOOK_FORMATS] 加了成员
     * 却忘了同步 `dialectOf` —— 那时没有它，体检会一声不响地跑零条字段判据并回「0 条问题」。
     */
    fun unmappedDialectFinding(format: FormatKind): Finding? {
        if (CardDialectMap.dialectOf(format) != null) return null
        if (format !in CardDialectMap.WORLDBOOK_FORMATS) return null
        return Finding(
            Level.ERROR, "DIALECT_UNMAPPED_FORMAT", Stage.FIELD, null, null,
            "认脸说这是世界书（$format），但方言表没给出血统 ⇒ 字段级判据一条都没跑，" +
                "这不是「没有毛病」，是「没判」。补 CardDialectMap.dialectOf。",
            format.name,
        )
    }

    /**
     * 本方言不该出的码，一条都不许放过（F22 的机械版）。
     *
     * 允许集 = [codesFor]（该方言字段级＋语义级＋共享码）∪ [GUARD_CODES]。
     * 外来码按 [foreignPrefixes] 定性成"谁家的码串进了这家的体检"，其余（`CARD_*` 之类
     * 与方言无关的卡内码）只说"不属于本方言"。级别一律 ERROR：这是判据走错的信号，
     * 不是卡片的毛病——§4.1 不许自行升降级，但这一条本来就没人给它定过级。
     */
    fun foreignCodeFindings(dialect: WorldbookDialect, findings: List<Finding>): List<Finding> {
        val allowed = codesFor(dialect) + GUARD_CODES
        val prefixes = foreignPrefixes(dialect)
        return findings.filter { it.code !in allowed }.map { f ->
            val house = houseOf(prefixes.firstOrNull { p -> f.code.startsWith(p) })
            Finding(
                Level.ERROR, "DIALECT_FOREIGN_CODE", Stage.FIELD, f.entry, f.field,
                if (house != null)
                    "体检串味：这份是${dialectName(dialect)}的书，却出了${house}家的码 ${f.code}" +
                        "（F22：每家只跑自己那张判据表）。"
                else
                    "体检串味：${dialectName(dialect)}的判据表里没有 ${f.code} 这条码。",
                f.code,
            )
        }
    }

    /** 方言 → 人话名字（话术用；不新增事实，与 [CardDialectMap] 的列名一致）。 */
    private fun dialectName(dialect: WorldbookDialect): String = when (dialect) {
        WorldbookDialect.WEB -> "网络平台"
        WorldbookDialect.TAVO -> "Tavo"
        WorldbookDialect.ST_NATIVE -> "酒馆"
        WorldbookDialect.CCV3 -> "CCv3"
    }
}
