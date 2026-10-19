// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：docs/格式层规格-三套卡与酒馆PNG-2026-10-19.md §4（体检）
//   §4.1 输入/输出/级别口径（级别不许自行升降）
//   §4.2 字段级三张表（缺字段与越界分开报；空值不等于错）
//   §4.3 语义级（积分刺客／A·B·C 三类／同名冲突／sort 重复）
//   §4.4 空值纪律（没给系统文本 ⇒ cCount=null，不许报 0）
//   §4.5 逐字话术
//   ＋ §15.3 F21（KEYSEP_BROKEN）／§15.7 F25（两套区域分开报）
//   ＋ 派工单 §2.1–§2.7 F12–F16（按 §16.1 裁定：只做卡内提取＋INFO）
package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject

/** 级别（§4.1：B 段不许自行升降）。 */
internal enum class Level { ERROR, WARN, INFO }

/** 检查阶段：字段级与语义级**分开输出**（派工单 §1）。 */
internal enum class Stage { FIELD, SEMANTIC }

/** 定位（§4.1）。 */
internal data class EntryRef(
    val index: Int? = null,
    val sort: Int? = null,
    val uid: String? = null,
    val key: String? = null,
)

/** 一条体检结论。 */
internal data class Finding(
    val level: Level,
    val code: String,
    val stage: Stage,
    val entry: EntryRef? = null,
    val field: String? = null,
    val message: String,
    val evidence: String = "",
)

/**
 * 计数（§4.1）。[cCount] 未判时**必须是 null，不是 0**（F6-c 专盯）。
 * [broadKeyCount] 是 §4.3.1 要求的口径（含单字 token 的**条目数**）。
 */
internal data class CheckStats(
    val entries: Int,
    val byFormat: String,
    val cCount: Int?,
    val unjudgedCount: Int,
    val notApplicableCount: Int,
    val broadKeyCount: Int = 0,
)

internal data class Skipped(val check: String, val reason: String)

internal data class SystemTextGiven(val prompt: Boolean, val pre: Boolean, val post: Boolean) {
    val any: Boolean get() = prompt || pre || post
}

internal data class CheckReport(
    val findings: List<Finding>,
    val stats: CheckStats,
    val skipped: List<Skipped>,
    val systemTextGiven: SystemTextGiven,
) {
    fun of(code: String): List<Finding> = findings.filter { it.code == code }
    fun count(code: String): Int = findings.count { it.code == code }
    val errors: List<Finding> get() = findings.filter { it.level == Level.ERROR }
}

/** 体检（纯逻辑：认脸结果 ＋ 可选系统文本进，报告出）。 */
internal object CardCheck {

    /** 六个「次级文本通道」开关（酒馆）。 */
    val ST_MATCH_SWITCHES = listOf(
        "matchPersonaDescription",
        "matchCharacterDescription",
        "matchCharacterPersonality",
        "matchCharacterDepthPrompt",
        "matchScenario",
        "matchCreatorNotes",
    )

    /** 常见 HTML 符号表（§4.3.2.1 ③ 固定词表，不靠猜）。 */
    val HTML_TOKENS: Set<String> = setOf(
        "div", "span", "p", "br", "b", "i", "u", "em", "strong", "table", "tr", "td", "th",
        "img", "a", "href", "src", "class", "id", "style", "font", "center", "small",
        "details", "summary", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "ul", "ol", "li", "script",
    )

    fun check(
        parsed: CardAssetParser.Parsed,
        prompt: String? = null,
        pre: String? = null,
        post: String? = null,
        detailHtml: String? = null,
    ): CheckReport {
        val verdict = parsed.verdict
        val asset = parsed.asset
        val given = SystemTextGiven(prompt != null, pre != null, post != null)
        val findings = ArrayList<Finding>()
        val skipped = ArrayList<Skipped>()

        val entries = when (asset) {
            is Worldbook -> asset.entries
            is FengyueWork -> asset.worldbook?.entries ?: emptyList()
            else -> emptyList()
        }
        val stats = when (val f = verdict.format) {
            FormatKind.FENGYUE -> checkWeb(entries, prompt, pre, post, detailHtml, given, findings)
            FormatKind.TAVO_BOOK -> checkTavo(entries, prompt, pre, post, detailHtml, given, findings)
            FormatKind.ST_WORLD -> checkSt(asset.raw, entries, prompt, pre, post, detailHtml, given, findings)
            FormatKind.TAVO_CHAR, FormatKind.ST_CARD_PNG -> {
                checkCardInfo(asset.raw, findings)
                skipped += Skipped("世界书字段级体检", "这不是世界书（${f.name}），本批只做卡内提取＋认脸")
                zeroStats(0, f)
            }
            FormatKind.CCV3_LOREBOOK -> {
                skipped += Skipped("CCv3 世界书体检", "本批只做认脸（规格 §3 注：体检细则不在本批）")
                zeroStats(entries.size, f)
            }
            FormatKind.TAVO_REGEX, FormatKind.TAVO_PRESET -> {
                skipped += Skipped("世界书体检", "这是 ${f.name}，不是世界书；硬套世界书校验会满屏假错误")
                zeroStats(0, f)
            }
            FormatKind.UNKNOWN -> {
                skipped += Skipped("全部体检", "认脸失败（${verdict.reason ?: "-"}），不体检")
                zeroStats(0, f)
            }
        }

        return CheckReport(findings, stats, skipped, given)
    }

    /**
     * 汇总话术（§4.5）。**C 类必须逐条显示条号**，计数为 0 时也要显示「0」。
     * 没给系统文本时给「N 条没判：没给系统文本。」——不许写「0 条永不触发」。
     */
    fun render(report: CheckReport): String = buildString {
        val c = report.stats.cCount
        if (c == null) {
            val why = if (!report.systemTextGiven.any) "没给系统文本" else "缺次级文本或条目本身无法用子串近似"
            appendLine("${report.stats.unjudgedCount} 条没判：$why。")
        } else {
            val idx = report.findings.filter { it.code == "TRIGGER_CLASS_C" }.mapNotNull { it.entry?.index }
            if (c > 0) appendLine("有 $c 条永远触发不了：第 ${idx.joinToString("、")} 条。")
            else appendLine("有 0 条永远触发不了。")
        }
        val noRegion = report.findings.count { it.code == "FY_NOT_APPLICABLE_REGION" }
        if (noRegion > 0) appendLine("另有 $noRegion 条不参与判定（触发区域不吃系统文本）。")
    }.trimEnd()

    // ── 风月（WEB） ──────────────────────────────────────────────────────

    private fun checkWeb(
        entries: List<WorldbookEntryView>,
        prompt: String?, pre: String?, post: String?,
        detailHtml: String?, given: SystemTextGiven,
        out: MutableList<Finding>,
    ): CheckStats {
        var broad = 0
        val sortGroups = HashMap<Int, MutableList<Int>>()
        val text = joinedSystemText(prompt, pre, post)

        for (v in entries) {
            val e = v.raw
            val ref = EntryRef(
                index = v.index,
                sort = CardJson.numberAsInt(e, "sort"),
                key = truncate(CardJson.stringOf(e, "key") ?: "", 30),
            )

            // ① 缺必填（value_type / value_configs **不在**此列：平台合法省略，§2.4）
            for (f in listOf("key", "value", "group", "sort", "enable", "key_region", "value_region", "depth", "probability", "match_type")) {
                if (!e.has(f) || CardJson.isJsonNull(e, f)) {
                    out += Finding(Level.ERROR, "FY_MISSING_FIELD", Stage.FIELD, ref, f, "第 ${v.index} 条缺少字段 $f。")
                }
            }

            // ② 类型（先判类型再取；不许用 optInt 把 "3" 吃成 3）
            expectString(e, "key", ref, out)
            expectString(e, "value", ref, out)
            expectString(e, "group", ref, out)
            expectInt(e, "sort", ref, out)
            expectBool(e, "enable", ref, out)
            expectInt(e, "key_region", ref, out)
            expectInt(e, "value_region", ref, out)
            expectInt(e, "depth", ref, out)
            expectInt(e, "probability", ref, out)
            expectInt(e, "match_type", ref, out)
            if (e.has("value_type") && !CardJson.isJsonNull(e, "value_type")) expectInt(e, "value_type", ref, out)
            if (e.has("value_configs") && !CardJson.isJsonNull(e, "value_configs")) {
                if (e.opt("value_configs") !is JSONArray) {
                    out += Finding(Level.ERROR, "FY_TYPE_MISMATCH", Stage.FIELD, ref, "value_configs", "第 ${v.index} 条的 value_configs 类型不对：应为数组。")
                }
            }

            // ③ 取值域
            CardJson.numberAsInt(e, "probability")?.let {
                if (it !in 1..100) out += Finding(Level.ERROR, "FY_PROBABILITY_RANGE", Stage.FIELD, ref, "probability", "第 ${v.index} 条的触发概率是 $it，不在 1–100 之间。", "$it")
            }
            CardJson.numberAsInt(e, "depth")?.let {
                if (it !in 0..20) out += Finding(Level.ERROR, "FY_DEPTH_RANGE", Stage.FIELD, ref, "depth", "第 ${v.index} 条的扫描深度是 $it，不在 0–20 之间。", "$it")
            }
            CardJson.numberAsInt(e, "key_region")?.let {
                if (it !in 1..7) out += Finding(Level.ERROR, "FY_KEY_REGION_RANGE", Stage.FIELD, ref, "key_region", "第 ${v.index} 条的触发区域是 $it，只允许 1–7（1 系统 / 2 玩家 / 4 AI 相加）。", "$it")
            }
            CardJson.numberAsInt(e, "value_region")?.let {
                if (it !in 1..7) out += Finding(Level.ERROR, "FY_VALUE_REGION_RANGE", Stage.FIELD, ref, "value_region", "第 ${v.index} 条的影响范围是 $it，只允许 1–7（1 提示词 / 2 前置词 / 4 后置词 相加）。", "$it")
            }
            CardJson.numberAsInt(e, "match_type")?.let {
                if (it != 2 && it != 3) out += Finding(Level.ERROR, "FY_MATCH_TYPE_RANGE", Stage.FIELD, ref, "match_type", "第 ${v.index} 条的匹配方式是 $it，只允许 2（关键词）或 3（正则）。", "$it")
            }
            CardJson.numberAsInt(e, "value_type")?.let {
                if (it != 0 && it != 1) out += Finding(Level.ERROR, "FY_VALUE_TYPE_RANGE", Stage.FIELD, ref, "value_type", "第 ${v.index} 条的注入模式是 $it，只允许 0（常规）或 1（概率）。", "$it")
            }
            val vt = CardJson.numberAsInt(e, "value_type") ?: 0
            val vc = CardJson.arrayOf(e, "value_configs")
            if (vt == 0 && vc != null && vc.length() > 0) {
                out += Finding(Level.WARN, "FY_VALUE_CONFIGS_CONFLICT", Stage.FIELD, ref, "value_configs", "第 ${v.index} 条是常规模式，却配了概率候选内容，配置矛盾。", "value_type=0 且 value_configs 有 ${vc.length()} 项")
            }

            // ④ 弱提示
            val keyStr = v.keys
            if (CardJson.stringOf(e, "key")?.let { CardAssetParser.webTokens(it).isEmpty() } == true) {
                out += Finding(Level.WARN, "FY_EMPTY_KEY", Stage.FIELD, ref, "key", "第 ${v.index} 条的关键词是空的。", "key=\"${truncate(CardJson.stringOf(e, "key") ?: "", 20)}\"")
            }
            if (CardJson.stringOf(e, "group") == "") {
                out += Finding(Level.INFO, "FY_GROUP_EMPTY", Stage.FIELD, ref, "group", "第 ${v.index} 条没有分组（按默认处理）。", "")
            }
            CardJson.numberAsInt(e, "sort")?.let { sortGroups.getOrPut(it) { ArrayList() } += v.index }

            // ⑤ 积分刺客：单字（§4.3.1，只对 WEB 跑）
            val broadFinding = broadOf(v)
            if (broadFinding != null) {
                broad++
                out += broadFinding
            }
            // ⑥ HTML 标签名（§4.3.2.1 ③；与上一条叠加时降 INFO，避免一个毛病报两遍）
            htmlTokenFinding(v, ref, hasBroad = broadFinding != null)?.let { out += it }

            // ⑦ F21 分隔符（§15.3）
            if (CardJson.stringOf(e, "key") != null) {
                keysepFinding(v, ref)?.let { out += it }
            }

            // ⑧ F25 两套区域分开报（§15.7）
            regionFindings(v, ref)?.let { out += it }
        }

        // sort 重复（§4.3.4）
        for ((sort, idx) in sortGroups) {
            if (idx.size >= 2) {
                out += Finding(
                    Level.WARN, "FY_SORT_DUPLICATE", Stage.FIELD,
                    EntryRef(index = idx.first(), sort = sort),
                    "sort",
                    "有 ${idx.size} 条的插入顺序都排在第 $sort 位，先后不确定。",
                    "sort=$sort 出现 ${idx.size} 次（条目 ${idx.joinToString("、")}）",
                )
            }
        }

        // A/B/C 三类（§4.3.2）
        val cls = classifyWeb(entries, text, detailHtml, given, out)
        // 同名冲突（§4.3.3）
        out += collisions(entries)

        return CheckStats(
            entries = entries.size,
            byFormat = FormatKind.FENGYUE.name,
            cCount = cls.cCount,
            unjudgedCount = cls.unjudged,
            notApplicableCount = cls.notApplicable,
            broadKeyCount = broad,
        )
    }

    private fun broadOf(v: WorldbookEntryView): Finding? {
        val key = CardJson.stringOf(v.raw, "key") ?: return null
        val prefix = when {
            key.startsWith("_or_") -> "OR"
            key.startsWith("_and_") -> "AND"
            else -> "NONE"
        }
        val single = CardAssetParser.webTokens(key).firstOrNull { it.codePointCount() == 1 } ?: return null
        val ref = EntryRef(index = v.index, sort = CardJson.numberAsInt(v.raw, "sort"), key = truncate(key, 30))
        return when (prefix) {
            "OR" -> Finding(
                Level.ERROR, "FY_KEY_TOO_BROAD", Stage.SEMANTIC, ref, "key",
                "第 ${v.index} 条的关键词里有单字「$single」。单字在「或」模式下几乎必中，平台会判为积分刺客风险（下架＋清积分）。",
                "单字 token：$single",
            )
            "AND" -> Finding(
                Level.WARN, "FY_KEY_TOO_BROAD_AND", Stage.SEMANTIC, ref, "key",
                "第 ${v.index} 条的「与」模式里有单字「$single」，会明显抬高触发门槛、多半导致永不触发。",
                "单字 token：$single",
            )
            else -> Finding(
                Level.WARN, "FY_KEY_TOO_BROAD_IMPLICIT", Stage.SEMANTIC, ref, "key",
                "第 ${v.index} 条的关键词里有单字「$single」（没写「或/与」前缀，按保守口径提示）。",
                "单字 token：$single",
            )
        }
    }

    private fun htmlTokenFinding(
        v: WorldbookEntryView,
        ref: EntryRef,
        hasBroad: Boolean,
        level: Level = if (hasBroad) Level.INFO else Level.WARN,
    ): Finding? {
        val hit = v.keys.firstOrNull { it.trim().lowercase() in HTML_TOKENS } ?: return null
        return Finding(
            level, "KEY_IS_HTML_TOKEN", Stage.SEMANTIC, ref, "key",
            "第 ${v.index} 条的关键词是 HTML 符号「$hit」，有 HTML 的卡里几乎必然出现，会被无差别触发。",
            "命中 HTML 词表：$hit",
        )
    }

    private fun keysepFinding(v: WorldbookEntryView, ref: EntryRef): Finding? {
        val key = CardJson.stringOf(v.raw, "key") ?: return null
        val problems = keysepProblems(key)
        if (problems.isEmpty()) return null
        return Finding(
            Level.ERROR, "KEYSEP_BROKEN", Stage.SEMANTIC, ref, "key",
            "第 ${v.index} 条的关键词分隔符没被识别（${problems.joinToString("、")}），整串会当成一个词 ⇒ 这条世界书永不触发。",
            "切出的词：${CardAssetParser.webTokens(key).joinToString(" | ")}",
        )
    }

    private fun regionFindings(v: WorldbookEntryView, ref: EntryRef): List<Finding> {
        val out = ArrayList<Finding>()
        val kr = CardJson.numberAsInt(v.raw, "key_region")
        val vr = CardJson.numberAsInt(v.raw, "value_region")
        if (kr != null && (kr and 4) == 0) {
            out += Finding(
                Level.INFO, "REGION_KEY_NO_SELF_TRIGGER", Stage.SEMANTIC, ref, "key_region",
                "第 ${v.index} 条的触发区域不含 AI ⇒ AI 自己在正文里写到这些词不会触发。",
                "key_region=$kr",
            )
        }
        if (vr != null && Integer.bitCount(vr) > 1) {
            out += Finding(
                Level.INFO, "REGION_VALUE_MULTI", Stage.SEMANTIC, ref, "value_region",
                "第 ${v.index} 条的内容会同时出现在多处（同一段内容被注入到多个区段）。",
                "value_region=$vr",
            )
        }
        return out
    }

    /** F21 分隔符问题（返回空 = 没问题）。**只认半角字面量**。 */
    fun keysepProblems(key: String): List<String> {
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val full = listOf("＿or＿", "＿and＿", "＠wb＠")
        if (full.any { key.contains(it) }) out += "含全角分隔符"
        val prefix = when {
            key.startsWith("_or_") -> "_or_"
            key.startsWith("_and_") -> "_and_"
            else -> ""
        }
        val body = if (prefix.isEmpty()) key else key.substring(prefix.length)
        val rawTokens = body.split("@wb@")
        if (rawTokens.any { it.isNotEmpty() && it != it.trim() }) out += "分隔符旁有空格"
        if (body.startsWith("@wb@") || body.endsWith("@wb@") || body.contains("@wb@@wb@")) {
            out += "有空词或首尾残留分隔符"
        }
        if (body.contains("_or_") || body.contains("_and_")) out += "混用或残留了「或/与」标记"
        if (prefix.isEmpty() && listOf("_or_", "_and_", "@wb@").any { key.contains(it) }) out += "整串含分隔符却未切分"
        return out.distinct()
    }

    // ── Tavo 世界书 ──────────────────────────────────────────────────────

    private fun checkTavo(
        entries: List<WorldbookEntryView>,
        prompt: String?, pre: String?, post: String?,
        detailHtml: String?, given: SystemTextGiven,
        out: MutableList<Finding>,
    ): CheckStats {
        val text = joinedSystemText(prompt, pre, post)
        for (v in entries) {
            val e = v.raw
            val ref = EntryRef(index = v.index, uid = CardJson.stringOf(e, "identifier"), key = truncate(v.keys.firstOrNull() ?: "", 30))

            for (f in listOf("keywords", "content")) {
                if (!e.has(f) || CardJson.isJsonNull(e, f)) {
                    out += Finding(Level.ERROR, "TV_MISSING_FIELD", Stage.FIELD, ref, f, "第 ${v.index} 条缺少字段 $f。")
                }
            }
            if (e.has("keywords") && !CardJson.isJsonNull(e, "keywords") && e.opt("keywords") !is JSONArray) {
                out += Finding(Level.ERROR, "TV_TYPE_MISMATCH", Stage.FIELD, ref, "keywords", "第 ${v.index} 条的 keywords 类型不对：应为数组。")
            }
            if (e.has("content") && !CardJson.isJsonNull(e, "content") && e.opt("content") !is String) {
                out += Finding(Level.ERROR, "TV_TYPE_MISMATCH", Stage.FIELD, ref, "content", "第 ${v.index} 条的 content 类型不对：应为字符串。")
            }
            if (e.has("enabled") && !CardJson.isJsonNull(e, "enabled") && e.opt("enabled") !is Boolean) {
                out += Finding(Level.ERROR, "TV_TYPE_MISMATCH", Stage.FIELD, ref, "enabled", "第 ${v.index} 条的 enabled 类型不对：应为布尔值。")
            }
            CardJson.numberAsInt(e, "probability")?.let {
                if (it !in 1..100) out += Finding(Level.ERROR, "TV_PROBABILITY_RANGE", Stage.FIELD, ref, "probability", "第 ${v.index} 条的触发概率是 $it，不在 1–100 之间。", "$it")
            }
            // §16.3-1 裁定：Tavo scanDepth 只要求整数 ≥0，**不设上限**（>20 不报错）
            val scanDepth = e.opt("scanDepth")
            if (e.has("scanDepth") && !CardJson.isJsonNull(e, "scanDepth")) {
                val n = CardJson.numberAsInt(e, "scanDepth")
                if (n == null || n < 0) {
                    out += Finding(Level.ERROR, "TV_SCAN_DEPTH_ODD", Stage.FIELD, ref, "scanDepth", "第 ${v.index} 条的扫描深度不是非负整数。", "${scanDepth}")
                }
            }
            CardJson.numberAsInt(e, "injectionDepth")?.let {
                if (it !in 0..999) out += Finding(Level.ERROR, "TV_INJECTION_DEPTH_ODD", Stage.FIELD, ref, "injectionDepth", "第 ${v.index} 条的注入深度是 $it，不在 0–999 之间。", "$it")
            }
            CardJson.stringOf(e, "injectionPosition")?.let {
                if (it !in setOf("lorebookBefore", "lorebookAfter", "atDepth")) {
                    out += Finding(Level.WARN, "TV_POSITION_UNKNOWN", Stage.FIELD, ref, "injectionPosition", "第 ${v.index} 条的注入位置「$it」没取证过，只提示不拦。", it)
                }
            }
            CardJson.stringOf(e, "strategy")?.let {
                if (it !in setOf("constant", "keyword")) {
                    out += Finding(Level.WARN, "TV_STRATEGY_UNKNOWN", Stage.FIELD, ref, "strategy", "第 ${v.index} 条的触发策略「$it」没取证过，只提示不拦。", it)
                }
            }
            CardJson.stringOf(e, "secondaryKeywordStrategy")?.let {
                if (it !in setOf("none", "andAny")) {
                    out += Finding(Level.WARN, "TV_SECONDARY_STRATEGY_UNKNOWN", Stage.FIELD, ref, "secondaryKeywordStrategy", "第 ${v.index} 条的次键逻辑「$it」没取证过，只提示不拦。", it)
                }
            }
            if (CardJson.stringOf(e, "content") == "") {
                out += Finding(Level.WARN, "TV_CONTENT_EMPTY", Stage.FIELD, ref, "content", "第 ${v.index} 条的正文是空的。", "")
            }
            for (f in listOf("caseSensitive", "matchWholeWord")) {
                if (e.has(f) && e.opt(f) !is Boolean) {
                    out += Finding(Level.ERROR, "TV_CASE_MATCH_ODD", Stage.FIELD, ref, f, "第 ${v.index} 条的 $f 不是布尔值。")
                }
            }
            // Tavo 不扫角色卡 ⇒ 标签名不会进扫描范围；只有玩家/模型真写了才会命中 ⇒ 只报 INFO
            htmlTokenFinding(v, ref, hasBroad = false, level = Level.INFO)?.let { out += it }
            if (CardJson.stringOf(e, "strategy") == "keyword" && v.keys.isEmpty()) {
                out += Finding(Level.WARN, "TV_KEYWORDS_EMPTY", Stage.SEMANTIC, ref, "keywords", "第 ${v.index} 条按关键词触发，但一个关键词都没有 ⇒ 永不触发。", "")
            }
        }

        val cls = classifyTavo(entries, text, given, out)
        out += collisions(entries)
        return CheckStats(
            entries = entries.size,
            byFormat = FormatKind.TAVO_BOOK.name,
            cCount = cls.cCount,
            unjudgedCount = cls.unjudged,
            notApplicableCount = cls.notApplicable,
            broadKeyCount = 0,
        )
    }

    // ── 酒馆原生世界书 ───────────────────────────────────────────────────

    private fun checkSt(
        raw: JSONObject,
        entries: List<WorldbookEntryView>,
        prompt: String?, pre: String?, post: String?,
        detailHtml: String?, given: SystemTextGiven,
        out: MutableList<Finding>,
    ): CheckStats {
        val text = joinedSystemText(prompt, pre, post)
        val known = ST_ENTRY_FIELDS

        // §4.2.3 第一条：entries 的**键**必须是整数字符串（视图里键已经变成序号，这里查原文）
        CardJson.objectOf(raw, "entries")?.let { dict ->
            for (k in dict.keys()) {
                if (k.toIntOrNull() == null) {
                    out += Finding(
                        Level.ERROR, "ST_ENTRY_KEY_NOT_INT", Stage.FIELD,
                        EntryRef(uid = k), "entries",
                        "有一个条目的编号不是整数（$k），酒馆按整数 uid 排条目。", k,
                    )
                }
            }
        }
        for (v in entries) {
            val e = v.raw
            val uid = e.opt("uid")?.toString()
            val ref = EntryRef(index = v.index, uid = uid, key = truncate(v.keys.firstOrNull() ?: "", 30))

            if (e.opt("key") !is JSONArray) {
                out += Finding(Level.ERROR, "ST_KEY_NOT_ARRAY", Stage.FIELD, ref, "key", "第 ${v.index} 条的 key 不是数组（关键词必须一个词一项）。", e.opt("key")?.let { truncate(it.toString(), 20) } ?: "")
            } else {
                val arr = e.opt("key") as JSONArray
                for (i in 0 until arr.length()) {
                    if (arr.opt(i) !is String) {
                        out += Finding(Level.ERROR, "ST_KEY_ELEM_NOT_STRING", Stage.FIELD, ref, "key", "第 ${v.index} 条的 key 第 ${i + 1} 项不是字符串。")
                    }
                }
            }
            if (e.has("keysecondary") && e.opt("keysecondary") !is JSONArray) {
                out += Finding(Level.ERROR, "ST_KEYsecondary_NOT_ARRAY", Stage.FIELD, ref, "keysecondary", "第 ${v.index} 条的 keysecondary 不是数组。")
            }
            if (e.has("content") && e.opt("content") !is String) {
                out += Finding(Level.ERROR, "ST_CONTENT_TYPE", Stage.FIELD, ref, "content", "第 ${v.index} 条的 content 不是字符串。")
            }
            if (e.has("disable") && e.opt("disable") !is Boolean) {
                out += Finding(Level.ERROR, "ST_DISABLE_TYPE", Stage.FIELD, ref, "disable", "第 ${v.index} 条的 disable 不是布尔值。")
            }
            CardJson.numberAsInt(e, "probability")?.let {
                if (it !in 1..100) out += Finding(Level.ERROR, "ST_PROBABILITY_RANGE", Stage.FIELD, ref, "probability", "第 ${v.index} 条的触发概率是 $it，不在 1–100 之间。", "$it")
            }
            if (e.has("depth") && !CardJson.isJsonNull(e, "depth")) {
                val d = CardJson.numberAsInt(e, "depth")
                if (d == null || d !in 0..20) {
                    out += Finding(Level.ERROR, "ST_DEPTH_RANGE", Stage.FIELD, ref, "depth", "第 ${v.index} 条的注入深度不是 0–20 的整数。", "${e.opt("depth")}")
                }
            }
            for (f in listOf("scanDepth", "caseSensitive", "matchWholeWords", "role", "sticky", "cooldown", "delay", "useGroupScoring")) {
                if (CardJson.isJsonNull(e, f)) {
                    out += Finding(Level.INFO, "ST_NULLED_FIELD", Stage.FIELD, ref, f, "第 ${v.index} 条的 $f 是空值，按酒馆默认处理。", "null")
                }
            }
            if (e.has("delayUntilRecursion")) {
                val v2 = e.opt("delayUntilRecursion")
                if (v2 !is Boolean && CardJson.numberAsInt(e, "delayUntilRecursion") == null) {
                    out += Finding(Level.ERROR, "ST_DELAYRECUR_DUAL_TYPE", Stage.FIELD, ref, "delayUntilRecursion", "第 ${v.index} 条的 delayUntilRecursion 既不是布尔值也不是整数。", "$v2")
                }
            }
            if (CardJson.stringOf(e, "content") == "" && CardJson.boolOf(e, "constant") == false) {
                out += Finding(Level.WARN, "ST_CONTENT_EMPTY", Stage.FIELD, ref, "content", "第 ${v.index} 条不是常驻条目，正文却是空的。", "")
            }
            // 酒馆的六个 match* 扫的是**含标签的原始字符串** ⇒ 标签名过宽按 WARN 报
            htmlTokenFinding(v, ref, hasBroad = false)?.let { out += it }
            for (k in e.keys()) {
                if (maybeKnown(k, known)) continue
                // 表外字段：只记录、必须原样保留（不判错）
                out += Finding(Level.INFO, "ST_UNKNOWN_FIELD_PRESERVED", Stage.FIELD, ref, k, "第 ${v.index} 条有表外字段 $k（原样保留，不判）。", k)
                break // 每条只报一次，避免刷屏
            }
        }

        val cls = classifySt(entries, text, detailHtml, given, out)
        out += collisions(entries)
        return CheckStats(
            entries = entries.size,
            byFormat = FormatKind.ST_WORLD.name,
            cCount = cls.cCount,
            unjudgedCount = cls.unjudged,
            notApplicableCount = cls.notApplicable,
            broadKeyCount = 0,
        )
    }

    /** §3.3 表内字段（核心＋扩展）。表外字段只记 INFO。 */
    private val ST_ENTRY_FIELDS: Set<String> = setOf(
        "key", "keysecondary", "comment", "content", "constant", "vectorized", "selective",
        "selectiveLogic", "addMemo", "order", "position", "disable", "ignoreBudget",
        "excludeRecursion", "preventRecursion",
        "matchPersonaDescription", "matchCharacterDescription", "matchCharacterPersonality",
        "matchCharacterDepthPrompt", "matchScenario", "matchCreatorNotes",
        "delayUntilRecursion", "probability", "useProbability", "depth", "group",
        "groupOverride", "groupWeight", "scanDepth", "caseSensitive", "matchWholeWords",
        "useGroupScoring", "automationId", "role", "sticky", "cooldown", "delay", "uid",
        "displayIndex", "extensions", "triggers", "outletName", "characterFilter",
    )

    /** `extensions` 里允许任意子键（开放对象）；其余按表判。 */
    private fun maybeKnown(k: String, known: Set<String>): Boolean = k in known

    // ── A/B/C 三类 ───────────────────────────────────────────────────────

    private data class Classes(val cCount: Int?, val unjudged: Int, val notApplicable: Int)

    private fun classifyWeb(
        entries: List<WorldbookEntryView>,
        text: String?,
        detailHtml: String?,
        given: SystemTextGiven,
        out: MutableList<Finding>,
    ): Classes {
        var c = 0
        var unjudged = 0
        var na = 0
        for (v in entries) {
            val ref = EntryRef(index = v.index, sort = CardJson.numberAsInt(v.raw, "sort"), key = truncate(CardJson.stringOf(v.raw, "key") ?: "", 30))
            val kr = CardJson.numberAsInt(v.raw, "key_region")
            val mt = CardJson.numberAsInt(v.raw, "match_type")
            if (mt == 3) {
                unjudged++
                out += Finding(Level.INFO, "TRIGGER_UNJUDGED_REGEX", Stage.SEMANTIC, ref, "match_type", "第 ${v.index} 条是正则条目，子串无法近似，不参与判定。", "match_type=3")
                continue
            }
            if (kr == null || (kr and 1) == 0) {
                na++
                out += Finding(
                    Level.INFO, "FY_NOT_APPLICABLE_REGION", Stage.SEMANTIC, ref, "key_region",
                    "第 ${v.index} 条不参与触发判定：${regionPhrase(kr)}。",
                    "key_region=${kr ?: "?"}",
                )
                continue
            }
            val tokens = v.keys
            if (tokens.isEmpty()) {
                if (given.any) { c++; out += classC(v.index, ref, 0) } else unjudged++
                continue
            }
            // 分隔符写坏 ⇒ 整串被当成一个词，结构性永不触发（F21 要求计入 C 类）
            val keyStr = CardJson.stringOf(v.raw, "key")
            if (keyStr != null && keysepProblems(keyStr).isNotEmpty()) {
                if (given.any) { c++; out += classC(v.index, ref, tokens.size) } else unjudged++
                continue
            }
            if (!given.any) {
                unjudged++
                continue
            }
            if (tokens.any { hit(it, text!!) }) continue      // A
            if (detailHtml != null) {
                if (tokens.any { hit(it, detailHtml) }) continue // B
                c++
                out += classC(v.index, ref, tokens.size)
            } else {
                unjudged++                                        // WEB 恒有次级通道
            }
        }
        return Classes(if (given.any) c else null, unjudged, na)
    }

    private fun classifyTavo(
        entries: List<WorldbookEntryView>,
        text: String?,
        given: SystemTextGiven,
        out: MutableList<Finding>,
    ): Classes {
        var c = 0
        var unjudged = 0
        var na = 0
        for (v in entries) {
            val ref = EntryRef(index = v.index, uid = CardJson.stringOf(v.raw, "identifier"), key = truncate(v.keys.firstOrNull() ?: "", 30))
            val strategy = CardJson.stringOf(v.raw, "strategy")
            val enabled = CardJson.boolOf(v.raw, "enabled")
            if (strategy == "constant") {
                na++
                out += Finding(Level.INFO, "TV_NOT_APPLICABLE_CONSTANT", Stage.SEMANTIC, ref, "strategy", "第 ${v.index} 条不参与触发判定：它是常驻条目，每轮都会注入。", "strategy=constant")
                continue
            }
            if (enabled == false) {
                na++
                out += Finding(Level.INFO, "TV_NOT_APPLICABLE_DISABLED", Stage.SEMANTIC, ref, "enabled", "第 ${v.index} 条不参与触发判定：这条是关闭状态。", "enabled=false")
                continue
            }
            val tokens = v.keys + stringList(CardJson.arrayOf(v.raw, "secondaryKeywords"))
            if (tokens.isEmpty()) {
                if (given.any) { c++; out += classC(v.index, ref, 0) } else unjudged++
                continue
            }
            if (!given.any) { unjudged++; continue }
            if (tokens.any { hit(it, text!!) }) continue
            // Tavo 没有次级文本通道 ⇒ 不命中就是 C（不是未判）
            c++
            out += classC(v.index, ref, tokens.size)
        }
        return Classes(if (given.any) c else null, unjudged, na)
    }

    private fun classifySt(
        entries: List<WorldbookEntryView>,
        text: String?,
        detailHtml: String?,
        given: SystemTextGiven,
        out: MutableList<Finding>,
    ): Classes {
        var c = 0
        var unjudged = 0
        var na = 0
        for (v in entries) {
            val ref = EntryRef(index = v.index, uid = v.raw.opt("uid")?.toString(), key = truncate(v.keys.firstOrNull() ?: "", 30))
            if (CardJson.boolOf(v.raw, "constant") == true) {
                na++
                out += Finding(Level.INFO, "ST_NOT_APPLICABLE_CONSTANT", Stage.SEMANTIC, ref, "constant", "第 ${v.index} 条不参与触发判定：它是常驻条目，每轮都会注入。", "constant=true")
                continue
            }
            if (CardJson.boolOf(v.raw, "disable") == true) {
                na++
                out += Finding(Level.INFO, "ST_NOT_APPLICABLE_DISABLED", Stage.SEMANTIC, ref, "disable", "第 ${v.index} 条不参与触发判定：这条是关闭状态。", "disable=true")
                continue
            }
            val tokens = v.keys + stringList(CardJson.arrayOf(v.raw, "keysecondary"))
            if (tokens.isEmpty()) {
                if (given.any) { c++; out += classC(v.index, ref, 0) } else unjudged++
                continue
            }
            if (!given.any) { unjudged++; continue }
            if (tokens.any { hit(it, text!!) }) continue
            val channel = ST_MATCH_SWITCHES.any { CardJson.boolOf(v.raw, it) == true }
            if (detailHtml != null) {
                if (tokens.any { hit(it, detailHtml) }) continue
                c++
                out += classC(v.index, ref, tokens.size)
            } else if (channel) {
                unjudged++          // 通道开着，只是不知道内容
            } else {
                c++
                out += classC(v.index, ref, tokens.size)
            }
        }
        return Classes(if (given.any) c else null, unjudged, na)
    }

    private fun classC(index: Int, ref: EntryRef, tokenCount: Int) = Finding(
        Level.WARN, "TRIGGER_CLASS_C", Stage.SEMANTIC, ref, null,
        "第 $index 条永远触发不了：它的关键词在系统文本和次级文本里都没出现。",
        "所有 $tokenCount 个词都没有出现在系统文本里",
    )

    /** 命中：先原样，再不区分大小写；都是**子串**（§4.3.2）。 */
    private fun hit(token: String, text: String): Boolean =
        text.contains(token) || text.contains(token, ignoreCase = true)

    private fun joinedSystemText(prompt: String?, pre: String?, post: String?): String? {
        val parts = listOfNotNull(prompt, pre, post)
        return if (parts.isEmpty()) null else parts.joinToString("\n")
    }

    private fun regionPhrase(kr: Int?): String {
        if (kr == null) return "触发区域读不出来"
        val sys = (kr and 1) != 0
        val user = (kr and 2) != 0
        val ai = (kr and 4) != 0
        return when {
            !sys && user && !ai -> "这条只扫玩家输入，不看系统文本"
            !sys && !user && ai -> "这条只扫 AI 正文"
            !sys && user && ai -> "这条只扫玩家输入和 AI 正文"
            else -> "这条不吃系统文本"
        }
    }

    // ── 同名冲突（§4.3.3）────────────────────────────────────────────────

    private fun collisions(entries: List<WorldbookEntryView>): List<Finding> {
        val out = ArrayList<Finding>()

        // 顺序不同：token 集合相同、顺序不同 ⇒ 只提示（§4.3.3）
        val bySet = LinkedHashMap<Set<String>, MutableList<WorldbookEntryView>>()
        for (v in entries) {
            if (v.keys.isEmpty()) continue
            bySet.getOrPut(v.keys.toSet()) { ArrayList() } += v
        }
        for ((set, list) in bySet) {
            val orders = list.map { it.keys }.distinct()
            if (orders.size > 1) {
                out += Finding(
                    Level.INFO, "KEY_ORDER_DIFF", Stage.SEMANTIC,
                    EntryRef(index = list.first().index, key = truncate(orders.first().firstOrNull() ?: "", 30)),
                    "key",
                    "有几条关键词相同但顺序不同（第 ${list.joinToString("、") { it.index.toString() }} 条），顺序可能影响匹配优先级。",
                    "共 ${orders.size} 种顺序：${orders.joinToString(" / ") { it.joinToString(",") }}",
                )
            }
            if (set.size != orders.size) { /* 同一集合必然同序，不需要额外处理 */ }
        }
        val groups = LinkedHashMap<List<String>, MutableList<WorldbookEntryView>>()
        for (v in entries) {
            if (v.keys.isEmpty()) continue
            groups.getOrPut(v.keys) { ArrayList() } += v
        }
        for ((keys, list) in groups) {
            if (list.size < 2) continue
            for (a in list.indices) {
                for (b in a + 1 until list.size) {
                    val x = list[a]
                    val y = list[b]
                    val overlap = regionOverlap(x, y)
                    val ref = EntryRef(index = x.index, sort = CardJson.numberAsInt(x.raw, "sort"), key = truncate(keys.firstOrNull() ?: "", 30))
                    if (overlap) {
                        out += Finding(
                            Level.WARN, "KEY_COLLISION", Stage.SEMANTIC, ref, "key",
                            "第 ${x.index} 条与第 ${y.index} 条的关键词完全相同，触发区域又重叠，可能互相压制。",
                            "关键词：${keys.joinToString("/")}",
                        )
                    } else {
                        out += Finding(
                            Level.INFO, "KEY_COLLISION_DISJOINT_REGION", Stage.SEMANTIC, ref, "key",
                            "第 ${x.index} 条与第 ${y.index} 条关键词相同，但触发区域不重叠，不算冲突。",
                            "关键词：${keys.joinToString("/")}",
                        )
                    }
                }
            }
        }
        return out
    }

    /** 重叠判定：按位与 != 0；没有位掩码的格式视为「全区域重叠」（§4.3.3）。 */
    private fun regionOverlap(a: WorldbookEntryView, b: WorldbookEntryView): Boolean {
        val ar = CardJson.numberAsInt(a.raw, "key_region")
        val br = CardJson.numberAsInt(b.raw, "key_region")
        return if (ar == null || br == null) true else (ar and br) != 0
    }

    // ── 卡内提取（F12–F16，按 §16.1 裁定：只报 INFO、文案带「未判」）──────

    private fun checkCardInfo(raw: JSONObject, out: MutableList<Finding>) {
        val data = CardJson.objectOf(raw, "data") ?: raw
        val ext = CardJson.objectOf(data, "extensions")

        val world = CardJson.stringOf(ext, "world")
        if (!world.isNullOrEmpty()) {
            out += Finding(
                Level.INFO, "CARD_EXTERNAL_WORLDBOOK_UNJUDGED", Stage.SEMANTIC, null, "extensions.world",
                "引用了外部世界书『${truncate(world, 30)}』，无册可验（未判）。",
                "extensions.world",
            )
        }

        val scripts = ext?.opt("regex_scripts")
        val scriptCount = when (scripts) {
            is JSONArray -> scripts.length()
            is JSONObject -> scripts.length()
            else -> 0
        }
        if (scriptCount > 0) {
            out += Finding(
                Level.INFO, "CARD_EMBEDDED_REGEX_UNJUDGED", Stage.SEMANTIC, null, "extensions.regex_scripts",
                "内嵌 $scriptCount 条正则脚本（未与外部比对，未判）。",
                "extensions.regex_scripts",
            )
        }

        val theme = CardJson.objectOf(CardJson.objectOf(ext, "tavo"), "theme")
        if (theme != null) {
            val hasSpec = theme.has("spec") && CardJson.stringOf(theme, "spec")?.isNotEmpty() == true
            // spec 缺失/认不出 ⇒ 忽略该子项、不报错（仍报「卡自带外观」这一层）
            out += Finding(
                Level.INFO, "CARD_EMBEDDED_THEME_UNJUDGED", Stage.SEMANTIC, null, "extensions.tavo.theme",
                "卡自带外观（${theme.length()} 键，${if (hasSpec) "含" else "不含"} spec），未与当前主题比对（未判）。",
                "extensions.tavo.theme",
            )
        }

        val book = CardJson.objectOf(data, "character_book")
        val bookName = CardJson.stringOf(book, "name")
        if (!bookName.isNullOrEmpty()) {
            out += Finding(
                Level.INFO, "CARD_EMBEDDED_BOOK_UNJUDGED", Stage.SEMANTIC, null, "character_book.name",
                "内嵌世界书『${truncate(bookName, 30)}』，与外部同名未判。",
                "character_book.name",
            )
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    private fun zeroStats(entries: Int, f: FormatKind) = CheckStats(
        entries = entries, byFormat = f.name, cCount = null, unjudgedCount = 0, notApplicableCount = 0,
    )

    private fun truncate(s: String, n: Int): String =
        if (s.length <= n) s else s.substring(0, n) + "…"

    private fun String.codePointCount(): Int = java.lang.Character.codePointCount(this, 0, length)

    private fun stringList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) (a.opt(i) as? String)?.let { out += it }
        return out
    }

    private fun expectString(e: JSONObject, f: String, ref: EntryRef, out: MutableList<Finding>) {
        if (!e.has(f) || CardJson.isJsonNull(e, f)) return
        if (e.opt(f) !is String) {
            out += Finding(Level.ERROR, "FY_TYPE_MISMATCH", Stage.FIELD, ref, f, "第 ${ref.index} 条的 $f 类型不对：应为字符串。")
        }
    }

    private fun expectInt(e: JSONObject, f: String, ref: EntryRef, out: MutableList<Finding>) {
        if (!e.has(f) || CardJson.isJsonNull(e, f)) return
        if (CardJson.numberAsInt(e, f) == null) {
            out += Finding(Level.ERROR, "FY_TYPE_MISMATCH", Stage.FIELD, ref, f, "第 ${ref.index} 条的 $f 类型不对：应为整数。")
        }
    }

    private fun expectBool(e: JSONObject, f: String, ref: EntryRef, out: MutableList<Finding>) {
        if (!e.has(f) || CardJson.isJsonNull(e, f)) return
        if (e.opt(f) !is Boolean) {
            out += Finding(Level.ERROR, "FY_TYPE_MISMATCH", Stage.FIELD, ref, f, "第 ${ref.index} 条的 $f 类型不对：应为布尔值。")
        }
    }
}
