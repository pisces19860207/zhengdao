// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十五批（2026-11-11）：酒馆格式兼容 · 导入/导出 Codec。
//
// 三条铁律：
//  ① 最终 JSON 一律由 JSONObject/JSONArray **程序生成**，本文件不出现任何手写 JSON 模板；
//  ② 角色卡 description 恒空（人设进世界书），导出/导入都不改写这条；
//  ③ 落盘出口只有 TavernExport（产物/），Codec 自己零 IO、零网络、零子进程。
//
// ★ 2026-11-11 形状修正（用户回源 ST v1.14.0）：两种文件、两种 entries，绝不混用。
//   cardBookJson() = data.character_book 的形状：entries **数组** + 关键词 `keys`
//   worldJson()    = 独立 *.worldbook.json 的形状：entries **字典(uid)** + 关键词 `key`
//   两份内容同源（同一个 TavernWorldDto），只是目标形状不同；换名只在
//   TavernFields.fieldNameOf 一处，本文件不写第二份字段表。
//
// 只做格式兼容：不实现插入深度算法、不做 token 预算裁剪（酒馆前端的事）。
// 纯 Kotlin ＋ org.json，零 Android 依赖。
package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject

/** 一次导出的结果：两份文本 ＋ 合并后的「会丢」清单。 */
internal data class TavernEncoded(
    val cardJson: String,
    val worldJson: String,
    val losses: TavernLossReport,
)

internal data class TavernDecoded(
    val bundle: TavernBundleDto,
    val losses: TavernLossReport,
)

internal object TavernCodec {

    // ── 导出 ─────────────────────────────────────────────────────────────

    fun encode(bundle: TavernBundleDto): TavernEncoded = TavernEncoded(
        cardJson = cardJson(bundle.card, bundle.world),
        worldJson = worldJson(bundle.world),
        losses = TavernLossReport(exportLosses(bundle)),
    )

    /**
     * `chara_card_v3`：V3 校验极宽松（只验 data 是对象）⇒ 这里**自己补全 14 个标准字段**。
     * `data.character_book` 是卡内嵌世界书，形状＝数组 + `keys`（判据⑫）。
     */
    fun cardJson(card: TavernCardDto, world: TavernWorldDto = TavernWorldDto()): String {
        val data = JSONObject()
        val values = cardValues(card, world)
        for (r in TavernFields.CARD_ROWS) {
            data.put(r.tavern, checkNotNull(values[r.ours]) { "字段表里出现未接线的卡字段：${r.ours}" })
        }
        // 铁律②：无论 DTO 里 description 传了什么，导出一律空串。
        data.put("description", "")
        data.put(TavernFields.EXTENSIONS, extensionsJson(card))
        return JSONObject()
            .put("spec", TavernFields.CARD_SPEC)
            .put("spec_version", TavernFields.CARD_SPEC_VERSION)
            .put("data", data)
            .toString(2) + "\n"
    }

    /** 卡内嵌世界书：`entries` 是**数组**，主关键词字段 `keys`。 */
    fun cardBookJson(world: TavernWorldDto): String = JSONObject()
        .put(TavernFields.WORLD_NAME, world.name)
        .put(TavernFields.WORLD_ENTRIES, entriesArray(world))
        .toString(2) + "\n"

    /** 独立世界书文件：`entries` 是**字典（键 = 整数 uid 字符串）**，主关键词字段 `key`。 */
    fun worldJson(world: TavernWorldDto): String = JSONObject()
        .put(TavernFields.WORLD_NAME, world.name)
        .put(TavernFields.WORLD_ENTRIES, entriesDict(world))
        .toString(2) + "\n"

    private fun entriesArray(world: TavernWorldDto): JSONArray {
        val arr = JSONArray()
        for (e in world.entries) arr.put(entryJson(e, TavernShape.CARD_BOOK))
        return arr
    }

    private fun entriesDict(world: TavernWorldDto): JSONObject {
        val dict = JSONObject()
        world.entries.forEachIndexed { uid, e -> dict.put(uid.toString(), entryJson(e, TavernShape.STANDALONE)) }
        return dict
    }

    private fun entryJson(e: TavernEntryDto, shape: TavernShape): JSONObject {
        val o = JSONObject()
        val values = entryValues(e)
        for (r in TavernFields.ENTRY_ROWS) {
            val v = values[r.ours] ?: continue // 可选字段没给就不写，不补默认值
            o.put(TavernFields.fieldNameOf(shape, r), v)
        }
        val ext = entryExtensions(e)
        if (ext != null) o.put(TavernFields.EXTENSIONS, ext)
        return o
    }

    /**
     * position 双轨（判据⑭）：顶层 `position` 只写 before_char/after_char；
     * 真值 8 档写在 `extensions.position`；atDepth(4) 再配 `extensions.depth` 与 `role`。
     */
    private fun entryExtensions(e: TavernEntryDto): JSONObject? {
        val p = e.stPosition ?: return null
        val ext = JSONObject().put(TavernFields.EXT_POSITION, p)
        if (p == TavernFields.ST_AT_DEPTH) {
            if (e.stDepth != null) ext.put(TavernFields.EXT_DEPTH, e.stDepth)
            if (e.stRole != null) ext.put(TavernFields.EXT_ROLE, e.stRole)
        }
        return ext
    }

    /** 卡 `data.extensions`：正则脚本的 findRegex 必须是完整形式（判据⑮）。 */
    private fun extensionsJson(card: TavernCardDto): JSONObject {
        val scripts = JSONArray()
        for (s in card.regexScripts) {
            if (!TavernRegex.isComplete(s.findRegex)) continue // 不完整的在 exportLosses 里报「会丢」
            scripts.put(
                JSONObject()
                    .put("findRegex", s.findRegex)
                    .put("replaceString", s.replaceString)
                    .put("markdownOnly", s.markdownOnly)
                    .put("promptOnly", s.promptOnly)
                    .put("placement", JSONArray(s.placement))
                    .put("disabled", s.disabled),
            )
        }
        return JSONObject().put("regex_scripts", scripts)
    }

    /** 值表（ours → value）：与 [TavernFields.CARD_ROWS] 一一对应，14 个标准字段一格不漏。 */
    private fun cardValues(c: TavernCardDto, w: TavernWorldDto): Map<String, Any> = linkedMapOf(
        "name" to c.name,
        "description" to c.description,
        "personality" to c.personality,
        "scenario" to c.scenario,
        "firstMes" to c.firstMes,
        "mesExample" to c.mesExample,
        "creatorNotes" to c.creatorNotes,
        "systemPrompt" to c.systemPrompt,
        "postHistoryInstructions" to c.postHistoryInstructions,
        "alternateGreetings" to JSONArray(c.alternateGreetings),
        "characterBook" to JSONObject(cardBookJson(w)),
        "tags" to JSONArray(c.tags),
        "creator" to c.creator,
        "characterVersion" to c.characterVersion,
    )

    private fun entryValues(e: TavernEntryDto): Map<String, Any?> = linkedMapOf(
        "keys" to JSONArray(e.keys.map { it.raw }),
        "keysecondary" to JSONArray(e.keysecondary.map { it.raw }),
        "content" to e.content,
        "constant" to e.constant,
        "selective" to e.selective,
        "order" to e.order,
        "depth" to e.depth,
        "probability" to e.probability,
        "disable" to e.disable,
        "addMemo" to e.addMemo,
        "group" to e.group,
        "position" to e.position,
        "useProbability" to e.useProbability,
    )

    /** 导出方向：自定义字段、不完整正则都无处可去 ⇒ 逐个列「会丢」（判据③⑮）。 */
    private fun exportLosses(b: TavernBundleDto): List<TavernLoss> {
        val out = ArrayList<TavernLoss>()
        b.card.customFields.keys.sorted().forEach {
            out += TavernLoss("导出/卡", it, "酒馆 chara_card_v3 没有对应字段，会丢")
        }
        b.card.regexScripts.forEachIndexed { i, s ->
            if (!TavernRegex.isComplete(s.findRegex)) {
                out += TavernLoss("导出/正则[$i]", "findRegex", "裸 pattern 会被 ST 拒（缺 /flags/），会丢且不导出")
            }
        }
        b.world.entries.forEachIndexed { i, e ->
            e.customFields.keys.sorted().forEach {
                out += TavernLoss("导出/世界书[$i]", it, "酒馆世界书条目没有对应字段，会丢")
            }
        }
        return out
    }

    // ── 导入 ─────────────────────────────────────────────────────────────

    /**
     * 反向解析成最小 DTO（**待 579 收敛**：此处不依赖 NovelObject）。
     * v2/v3 都吃；独立世界书两种 entries 形状都吃；没有独立文件时从卡内
     * `data.character_book` 读回（卡内形状＝数组 + keys）。
     */
    fun decode(cardText: String, worldText: String): TavernDecoded {
        val losses = ArrayList<TavernLoss>()
        val card = readCard(cardText, losses)
        val world = if (worldText.isBlank()) {
            readCardBook(cardText, losses)
        } else {
            readWorld(worldText, losses)
        }
        return TavernDecoded(TavernBundleDto(card, world), TavernLossReport(losses))
    }

    /** v3 = data 包裹；v2 = 顶层就是 data（或 spec=chara_card_v2 但字段平铺）。 */
    private fun readCard(text: String, losses: MutableList<TavernLoss>): TavernCardDto {
        val root = JSONObject(CardJson.withoutBom(text))
        val data = root.optJSONObject("data") ?: root // v2 平铺
        unknownKeys(data, TavernFields::isKnownCardKey, "导入/卡", losses)
        val sourceDescription = data.optString("description")
        if (sourceDescription.isNotEmpty()) {
            losses += TavernLoss("导入/卡", "description", "源卡描述非空；铁律②要求留空，内容会丢（人设请走世界书）")
        }
        return TavernCardDto(
            name = data.optString("name"),
            description = "",
            personality = data.optString("personality"),
            scenario = data.optString("scenario"),
            firstMes = data.optString("first_mes"),
            mesExample = data.optString("mes_example"),
            creatorNotes = data.optString("creator_notes"),
            systemPrompt = data.optString("system_prompt"),
            postHistoryInstructions = data.optString("post_history_instructions"),
            alternateGreetings = stringArray(data.opt("alternate_greetings")),
            tags = stringArray(data.opt("tags")),
            creator = data.optString("creator"),
            characterVersion = data.optString("character_version"),
            regexScripts = readRegexScripts(data),
        )
    }

    private fun readRegexScripts(data: JSONObject): List<TavernRegexScript> {
        val ext = data.optJSONObject(TavernFields.EXTENSIONS) ?: return emptyList()
        val arr = ext.optJSONArray("regex_scripts") ?: return emptyList()
        val out = ArrayList<TavernRegexScript>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += TavernRegexScript(
                findRegex = o.optString("findRegex"),
                replaceString = o.optString("replaceString"),
                markdownOnly = o.optBoolean("markdownOnly", false),
                promptOnly = o.optBoolean("promptOnly", false),
                placement = intArray(o.opt("placement")),
                disabled = o.optBoolean("disabled", false),
            )
        }
        return out
    }

    /** 卡内 character_book.entries（数组 + keys）读回。 */
    private fun readCardBook(text: String, losses: MutableList<TavernLoss>): TavernWorldDto {
        val root = JSONObject(CardJson.withoutBom(text))
        val data = root.optJSONObject("data") ?: root
        val book = data.optJSONObject(TavernFields.CARD_BOOK_KEY) ?: return TavernWorldDto()
        val holder = JSONObject()
            .put(TavernFields.WORLD_NAME, book.optString(TavernFields.WORLD_NAME))
            .put(TavernFields.WORLD_ENTRIES, book.opt(TavernFields.WORLD_ENTRIES) ?: JSONArray())
        return readWorld(holder.toString(), losses)
    }

    /** 独立世界书：字典(uid) 与数组两种形状都吃；字典按 uid 数值升序还原。 */
    private fun readWorld(text: String, losses: MutableList<TavernLoss>): TavernWorldDto {
        val root = JSONObject(CardJson.withoutBom(text))
        unknownKeys(root, TavernFields::isKnownWorldKey, "导入/世界书", losses)
        val entries = ArrayList<TavernEntryDto>()
        when (val raw = root.opt(TavernFields.WORLD_ENTRIES)) {
            is JSONArray -> for (i in 0 until raw.length()) {
                raw.optJSONObject(i)?.let { entries += readEntry(it, "[$i]", losses) }
            }
            is JSONObject -> {
                val keys = raw.keys().asSequence().toList()
                    .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                for (k in keys) raw.optJSONObject(k)?.let { entries += readEntry(it, ".$k", losses) }
            }
            else -> Unit
        }
        return TavernWorldDto(name = root.optString(TavernFields.WORLD_NAME), entries = entries)
    }

    private fun readEntry(o: JSONObject, ref: String, losses: MutableList<TavernLoss>): TavernEntryDto {
        unknownKeys(o, TavernFields::isKnownEntryKey, "导入/世界书$ref", losses)
        val ext = o.optJSONObject(TavernFields.EXTENSIONS)
        return TavernEntryDto(
            keys = keysOf(o, "keys") ?: keysOf(o, "key") ?: emptyList(),
            content = o.optString("content"),
            keysecondary = keysOf(o, "keysecondary") ?: emptyList(),
            constant = o.optBoolean("constant", false),
            selective = o.optBoolean("selective", false),
            order = o.optInt("order", 100),
            depth = o.optInt("depth", 4),
            probability = o.optInt("probability", 100),
            disable = o.optBoolean("disable", false),
            addMemo = o.optBoolean("addMemo", true),
            group = o.optString("group"),
            position = if (o.has("position")) o.optString("position") else null,
            stPosition = ext?.opt(TavernFields.EXT_POSITION) as? Int,
            stDepth = ext?.opt(TavernFields.EXT_DEPTH) as? Int,
            stRole = ext?.opt(TavernFields.EXT_ROLE) as? Int,
            useProbability = if (o.has("useProbability")) o.optBoolean("useProbability") else null,
        )
    }

    /** keys 允许「单个字符串（纯文本）」或「字符串数组（多值/正则键）」两种源写法。 */
    private fun keysOf(o: JSONObject, key: String): List<TavernKeyToken>? = when (val v = o.opt(key)) {
        is String -> listOf(TavernKeys.tokenOf(v))
        is JSONArray -> TavernKeys.tokensOf(
            (0 until v.length()).mapNotNull { i -> v.opt(i) as? String },
        )
        else -> null
    }

    private fun stringArray(v: Any?): List<String> = if (v is JSONArray) {
        (0 until v.length()).mapNotNull { i -> v.opt(i) as? String }
    } else {
        emptyList()
    }

    private fun intArray(v: Any?): List<Int> = if (v is JSONArray) {
        (0 until v.length()).mapNotNull { i -> v.opt(i) as? Int }
    } else {
        listOf(2)
    }

    private fun unknownKeys(
        o: JSONObject,
        known: (String) -> Boolean,
        side: String,
        losses: MutableList<TavernLoss>,
    ) {
        for (k in o.keys()) {
            if (!known(k)) losses += TavernLoss(side, k, "酒馆侧字段我方不解析，会丢")
        }
    }
}
