// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：docs/格式层规格-三套卡与酒馆PNG-2026-10-19.md §14.3（统一内部模型）
//   ＋ §14.4（F19 血统与 raw／F20 只读视图）＋ §15.1（血统枚举 WEB）
//   ＋ §15.6（Tavo 是酒馆的改名版，字段对应表）＋ §16.2（视图只读一致性）
//
// 三条纪律：
// 1. `raw` 原样保留，视图**只读**；本批不提供任何写回入口（§16.2）。
// 2. `dialect` 只记来源血统，不改变字段；跨血统转换是未来批次的事。
// 3. 网络平台走 [WebWork]，不许硬塞进 [Card]（它没有卡）。
//
// 第二百二十五批追加（人设／正则／预设三类「只认脸」的补全）：
// 4. **不编字段**：源里没有就是 null／空表，绝不补默认值、绝不猜（用户口径「不撒谎」）。
// 5. **不崩**：单条读不出来标 [UnparsedAsset] 并**保留原文**，整份解析照常返回。
// 6. 人设不塞进 [Card]：它是独立的 [Persona]，卡只是它的来源之一。
package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject

/** 「解析」= 认脸 ＋ 统一视图（同一份字节、同一个结论，不读第二遍判定）。 */
internal object CardAssetParser {

    /**
     * 一次解析的全部产物。
     *
     * [asset] 是这份文件的**主体**；[persona]／[regex]／[preset] 是「这份文件里能读出的人设／
     * 正则／预设」，三种来源共用同一组槽位：① 文件本身就是（Tavo 正则／预设）；
     * ② 从卡里派生（卡内 `data` 的人设段、`data.extensions.regex_scripts`）。
     * 这样展示层只有一条路径，不必按格式分支。
     *
     * [unparsed] 是「读不出来的那几条」——**不丢**，原文在 [UnparsedAsset.rawText]。
     */
    data class Parsed(
        val verdict: FormatVerdict,
        val asset: CardAsset,
        val persona: Persona? = null,
        val regex: RegexScript? = null,
        val preset: Preset? = null,
        val unparsed: List<UnparsedAsset> = emptyList(),
    )

    /** 解析失败（判不出 / 不是对象）⇒ null；调用方拿 [CardFormat.recognize] 的话术报错。 */
    fun parse(bytes: ByteArray): Parsed? {
        val verdict = CardFormat.recognize(bytes)
        if (!verdict.isKnown) return null
        val text = verdict.jsonText ?: verdict.embeddedJson ?: return null
        val raw = CardJson.parseObject(text) ?: return null
        val bad = ArrayList<UnparsedAsset>()
        val asset = when (verdict.format) {
            FormatKind.WEB_BOOK -> web(raw)
            FormatKind.TAVO_BOOK -> worldbook(raw, WorldbookDialect.TAVO, "tavo")
            FormatKind.ST_WORLD -> worldbook(raw, WorldbookDialect.ST_NATIVE, "sillytavern")
            FormatKind.CCV3_LOREBOOK -> worldbook(raw, WorldbookDialect.CCV3, "ccv3")
            FormatKind.TAVO_CHAR -> Card(raw, CardJson.objectOf(raw, "data") ?: JSONObject(), "tavo", raw)
            FormatKind.ST_CARD_PNG -> {
                val data = CardJson.objectOf(raw, "data")
                val spec = CardJson.stringOf(raw, "spec")
                val vendor = if (spec == "chara_card_v3") "ccv3" else "sillytavern"
                Card(raw, data ?: raw, vendor, raw)
            }
            FormatKind.TAVO_REGEX -> regexScriptOf(raw, "tavo", bad)
            FormatKind.TAVO_PRESET -> presetOf(raw, "tavo", bad)
            FormatKind.UNKNOWN -> return null
        }
        val card = asset as? Card
        return Parsed(
            verdict = verdict,
            asset = asset,
            persona = card?.let { personaOf(it) },
            regex = when {
                asset is RegexScript -> asset
                card != null -> regexOf(card, bad)
                else -> null
            },
            preset = asset as? Preset,
            unparsed = bad.toList(),
        )
    }

    // ── 世界书视图 ───────────────────────────────────────────────────────

    private fun web(raw: JSONObject): WebWork {
        val book = CardJson.arrayOf(raw, "world_book") ?: JSONArray()
        val wb = Worldbook(
            raw = raw,
            dialect = WorldbookDialect.WEB,
            entries = (0 until book.length()).mapNotNull { i ->
                book.optJSONObject(i)?.let { viewOf(it, WorldbookDialect.WEB, i) }
            },
            vendor = "web",
        )
        // 平台把「详细介绍／提示词／前置词／后置词」放在作品层；本批只认脸 ⇒ 留空串，
        // 不猜字段名（规格没给这三个键的名字，猜了就违反「不许猜」）。
        return WebWork(
            detailHtml = "",
            prompt = "",
            prePrompt = "",
            postPrompt = "",
            builtInCss = "",
            worldbook = wb,
            raw = raw,
        )
    }

    private fun worldbook(raw: JSONObject, dialect: WorldbookDialect, vendor: String): Worldbook {
        val arr = CardJson.arrayOf(raw, "entries")
        val entries = if (arr != null) {
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { viewOf(it, dialect, i) }
            }
        } else {
            val dict = CardJson.objectOf(raw, "entries") ?: JSONObject()
            // 字典：按整数 uid 升序编号（EntryRef.index 的口径，§4.1）
            dict.keys().asSequence()
                .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                .mapIndexedNotNull { i, k -> dict.optJSONObject(k)?.let { viewOf(it, dialect, i) } }
                .toList()
        }
        return Worldbook(raw = raw, dialect = dialect, entries = entries, vendor = vendor)
    }

    /** 单条统一视图（**只读**；`enabled` 的正写只在视图层）。 */
    fun viewOf(e: JSONObject, dialect: WorldbookDialect, index: Int): WorldbookEntryView =
        when (dialect) {
            WorldbookDialect.WEB -> WorldbookEntryView(
                index = index,
                dialect = WorldbookDialect.WEB,
                keys = webTokens(CardJson.stringOf(e, "key") ?: ""),
                content = CardJson.stringOf(e, "value") ?: "",
                enabled = CardJson.boolOf(e, "enable") ?: false,
                order = CardJson.numberAsInt(e, "sort"),
                raw = e,
            )
            WorldbookDialect.TAVO -> WorldbookEntryView(
                index = index,
                dialect = WorldbookDialect.TAVO,
                keys = stringList(CardJson.arrayOf(e, "keywords")),
                content = CardJson.stringOf(e, "content") ?: "",
                enabled = CardJson.boolOf(e, "enabled") ?: true,
                order = null,                       // Tavo 没有 order（§15.6「不可表达」）
                raw = e,
            )
            WorldbookDialect.ST_NATIVE -> WorldbookEntryView(
                index = index,
                dialect = WorldbookDialect.ST_NATIVE,
                keys = stringList(CardJson.arrayOf(e, "key")),
                content = CardJson.stringOf(e, "content") ?: "",
                // ⚠️ 反义：`disable=true` 才是关。反义**只在视图层**，raw 一个字节不改。
                enabled = !(CardJson.boolOf(e, "disable") ?: false),
                order = CardJson.numberAsInt(e, "order"),
                raw = e,
            )
            WorldbookDialect.CCV3 -> WorldbookEntryView(
                index = index,
                dialect = WorldbookDialect.CCV3,
                keys = stringList(CardJson.arrayOf(e, "keys")),
                content = CardJson.stringOf(e, "content") ?: "",
                enabled = CardJson.boolOf(e, "enabled") ?: true,
                order = CardJson.numberAsInt(e, "insertion_order"),
                raw = e,
            )
        }

    /**
     * 视图一致性复核（F20-i，测试与体检都能调）：
     * 把视图里的每个字段**按 dialect 规则从 raw 重算一遍**，不一致就报出来。
     *
     * 为什么不直接信任视图：视图是 `enabled`/`order` 这类「换名＋反义」的产物，
     * 一旦有人在视图层顺手改了语义（把 `disable` 当真值），这里立刻能看见。
     */
    fun recheck(view: WorldbookEntryView): List<String> {
        val fresh = viewOf(view.raw, view.dialect, view.index)
        val bad = ArrayList<String>()
        if (fresh.keys != view.keys) bad += "keys 与 raw 不一致"
        if (fresh.content != view.content) bad += "content 与 raw 不一致"
        if (fresh.enabled != view.enabled) bad += "enabled 与 raw 不一致"
        if (fresh.order != view.order) bad += "order 与 raw 不一致"
        return bad
    }

    /** `WEB` 关键词切分（前缀只剥一次；`@wb@` 是字面量不是正则；token 去空白去空）。 */
    fun webTokens(key: String): List<String> {
        val body = when {
            key.startsWith("_or_") -> key.substring(4)
            key.startsWith("_and_") -> key.substring(5)
            else -> key
        }
        return body.split("@wb@").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ── 人设（第二百二十五批）──────────────────────────────────────────────

    /**
     * 从卡里取人设段。**不塞进 [Card]**：人设是独立资产，卡只是它的来源。
     *
     * 五个语义全都没有（含全是空白）⇒ 返回 null ⇒ 展示层整节不显示，
     * 而不是给一张空壳人设（那会让「没有人设」看起来像「人设是空的」）。
     *
     * [Persona.raw] 是卡的数据对象本身（`data`；老 v2 卡没有 `data` 时就是顶层），
     * **同一个实例、不做复制**——原文在哪就指哪。
     */
    fun personaOf(card: Card): Persona? {
        val lineage = AssetLineage.ofVendor(card.vendor) ?: return null
        val node = card.ccv3
        val name = textOf(node, lineage, AssetKind.PERSONA, "名")
        val description = textOf(node, lineage, AssetKind.PERSONA, "描述")
        val traits = stringsOf(node, lineage, AssetKind.PERSONA, "性格/标签")
        val scenario = textOf(node, lineage, AssetKind.PERSONA, "场景")
        val examples = examplesOf(node, lineage, AssetKind.PERSONA, "对话样例")
        val present = !name.isNullOrBlank() || !description.isNullOrBlank() ||
            !scenario.isNullOrBlank() || traits.isNotEmpty() || examples.isNotEmpty()
        if (!present) return null
        return Persona(
            raw = node,
            name = name,
            description = description,
            traits = traits,
            scenario = scenario,
            examples = examples,
            vendor = card.vendor,
        )
    }

    // ── 正则（第二百二十五批）──────────────────────────────────────────────

    /** 卡内嵌正则（`data.extensions.regex_scripts`）。没有这一段 ⇒ null（不显示整节）。 */
    fun regexOf(card: Card, bad: MutableList<UnparsedAsset>): RegexScript? {
        val lineage = AssetLineage.ofVendor(card.vendor) ?: return null
        val ext = CardJson.objectOf(card.ccv3, "extensions") ?: return null
        if (!ext.has("regex_scripts")) return null
        val arr = CardJson.arrayOf(ext, "regex_scripts")
        if (arr == null) {
            bad += UnparsedAsset(
                AssetKind.REGEX, -1, "extensions.regex_scripts 不是数组",
                CardJson.objectOf(ext, "regex_scripts"), rawTextOf(ext.opt("regex_scripts")),
            )
            return null
        }
        return RegexScript(
            raw = ext,   // 原文所在父节点，不新拼壳
            entries = regexEntriesOf(arr, lineage, bad),
            vendor = card.vendor,
        )
    }

    /** 独立的 Tavo 正则文件。 */
    fun regexScriptOf(raw: JSONObject, vendor: String, bad: MutableList<UnparsedAsset>): RegexScript {
        val lineage = AssetLineage.ofVendor(vendor)
        val arr = CardJson.arrayOf(raw, "entries")
        if (arr == null && raw.has("entries")) {
            bad += UnparsedAsset(
                AssetKind.REGEX, -1, "entries 不是数组",
                CardJson.objectOf(raw, "entries"), rawTextOf(raw.opt("entries")),
            )
        }
        return RegexScript(
            raw = raw,
            entries = if (arr == null || lineage == null) emptyList() else regexEntriesOf(arr, lineage, bad),
            vendor = vendor,
        )
    }

    private fun regexEntriesOf(
        arr: JSONArray,
        lineage: AssetLineage,
        bad: MutableList<UnparsedAsset>,
    ): List<RegexEntryView> {
        val out = ArrayList<RegexEntryView>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i)
            if (e == null) {
                bad += UnparsedAsset(
                    AssetKind.REGEX, i, "第 ${i + 1} 条不是 JSON 对象",
                    null, rawTextOf(arr.opt(i)),
                )
                continue
            }
            val view = runCatching { regexViewOf(e, lineage, i) }.getOrElse {
                bad += UnparsedAsset(AssetKind.REGEX, i, "第 ${i + 1} 条读字段时出错", e, rawTextOf(e))
                null
            }
            if (view != null) out += view
        }
        return out
    }

    private fun regexViewOf(e: JSONObject, lineage: AssetLineage, index: Int): RegexEntryView {
        val find = textOf(e, lineage, AssetKind.REGEX, "匹配")
        val declared = stringsOf(e, lineage, AssetKind.REGEX, "标志")
        return RegexEntryView(
            index = index,
            find = find,
            replace = textOf(e, lineage, AssetKind.REGEX, "替换"),
            // 独立 flags 字段优先；没有就从 `/pattern/flags` 尾部取（Tavo／酒馆惯例）。
            flags = if (declared.isNotEmpty()) declared else inlineFlags(find),
            scope = stringsOf(e, lineage, AssetKind.REGEX, "范围"),
            enabled = flagOf(e, lineage, AssetKind.REGEX, "启用"),
            raw = e,
        )
    }

    // ── 预设（第二百二十五批）──────────────────────────────────────────────

    /** 独立的 Tavo 预设文件。 */
    fun presetOf(raw: JSONObject, vendor: String, bad: MutableList<UnparsedAsset>): Preset {
        val lineage = AssetLineage.ofVendor(vendor)
        val arr = CardJson.arrayOf(raw, "entries")
        if (arr == null && raw.has("entries")) {
            bad += UnparsedAsset(
                AssetKind.PRESET, -1, "entries 不是数组",
                CardJson.objectOf(raw, "entries"), rawTextOf(raw.opt("entries")),
            )
        }
        val entries = if (arr == null || lineage == null) emptyList() else presetEntriesOf(arr, lineage, bad)
        return Preset(
            raw = raw,
            entries = entries,
            basicPrompts = CardJson.objectOf(raw, "basicPrompts"),
            vendor = vendor,
        )
    }

    private fun presetEntriesOf(
        arr: JSONArray,
        lineage: AssetLineage,
        bad: MutableList<UnparsedAsset>,
    ): List<PresetEntryView> {
        val out = ArrayList<PresetEntryView>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i)
            if (e == null) {
                bad += UnparsedAsset(
                    AssetKind.PRESET, i, "第 ${i + 1} 条不是 JSON 对象",
                    null, rawTextOf(arr.opt(i)),
                )
                continue
            }
            val view = runCatching {
                PresetEntryView(
                    index = i,
                    name = textOf(e, lineage, AssetKind.PRESET, "名"),
                    prompt = textOf(e, lineage, AssetKind.PRESET, "提示词"),
                    enabled = flagOf(e, lineage, AssetKind.PRESET, "启用"),
                    raw = e,
                )
            }.getOrElse {
                bad += UnparsedAsset(AssetKind.PRESET, i, "第 ${i + 1} 条读字段时出错", e, rawTextOf(e))
                null
            }
            if (view != null) out += view
        }
        return out
    }

    // ── 取值（一律走 [CardDialectMap] 的资产表；表里没名字 ⇒ null，不猜）──────

    /** 原样取字符串（**不过滤空串**：`""` 是「有、但空」，`null` 才是「没有」）。 */
    private fun textOf(o: JSONObject, lineage: AssetLineage, kind: AssetKind, semantic: String): String? =
        CardDialectMap.assetFieldOf(lineage, kind, semantic)?.let { CardJson.stringOf(o, it) }

    /** 取布尔并**按列**处理反义；源里没写 ⇒ null（不补 true／false）。 */
    private fun flagOf(o: JSONObject, lineage: AssetLineage, kind: AssetKind, semantic: String): Boolean? {
        val key = CardDialectMap.assetFieldOf(lineage, kind, semantic) ?: return null
        val v = CardJson.boolOf(o, key) ?: return null
        return if (CardDialectMap.assetNegated(lineage, kind, semantic)) !v else v
    }

    /** 取字符串列表（源里是数组就照收；是单串就按标点／换行切）。 */
    private fun stringsOf(o: JSONObject, lineage: AssetLineage, kind: AssetKind, semantic: String): List<String> {
        val key = CardDialectMap.assetFieldOf(lineage, kind, semantic) ?: return emptyList()
        return when (val v = o.opt(key)) {
            is JSONArray -> assetStrings(v)
            is String -> splitTags(v)
            else -> emptyList()
        }
    }

    /** 对话样例：数组照收；单串按 `<START>` 切段。 */
    private fun examplesOf(o: JSONObject, lineage: AssetLineage, kind: AssetKind, semantic: String): List<String> {
        val key = CardDialectMap.assetFieldOf(lineage, kind, semantic) ?: return emptyList()
        return when (val v = o.opt(key)) {
            is JSONArray -> assetStrings(v)
            is String -> v.split("<START>").map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    /** 单串标签 → 列表（**值的规范化**：按标点／换行切，去空白去空；不是造字段）。 */
    private fun splitTags(s: String): List<String> =
        s.split(',', '，', '、', ';', '；', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }

    /** `/pattern/flags` 尾部的标志位；不是这个形状 ⇒ 空表（**不猜**）。 */
    fun inlineFlags(find: String?): List<String> {
        if (find == null || find.length < 3 || find[0] != '/') return emptyList()
        var i = find.length - 1
        while (i > 0 && !(find[i] == '/' && !isEscaped(find, i))) i--
        if (i <= 0) return emptyList()
        val tail = find.substring(i + 1)
        if (tail.isEmpty() || !tail.all { it in REGEX_FLAG_CHARS }) return emptyList()
        return tail.map { it.toString() }
    }

    /** JS 正则的标志位字母（`g` 全局／`i` 忽略大小写／`m` 多行／`s` 点匹配换行／`u` `v` Unicode／`d` 索引／`y` 粘滞）。 */
    private const val REGEX_FLAG_CHARS = "dgimsuvy"

    private fun isEscaped(s: String, at: Int): Boolean {
        var n = 0
        var j = at - 1
        while (j >= 0 && s[j] == '\\') { n++; j-- }
        return n % 2 == 1
    }

    /** 原文的一个节点长什么样（对象就 `toString()`；拿不到就给空串，**绝不抛**）。 */
    private fun rawTextOf(v: Any?): String = runCatching { v?.toString() ?: "" }.getOrDefault("")

    // ── 小工具 ───────────────────────────────────────────────────────────

    private fun stringList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val v = a.opt(i)
            if (v is String) out += v
        }
        return out
    }

    /** 资产字段的数组取值：只收字符串，去空白、去空项。 */
    private fun assetStrings(a: JSONArray): List<String> {
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val v = a.opt(i)
            if (v is String) v.trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }
}
