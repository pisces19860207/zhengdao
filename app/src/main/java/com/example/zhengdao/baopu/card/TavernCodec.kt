// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十五批（2026-11-11）：酒馆格式兼容 · 导入/导出 Codec。
//
// 三条铁律：
//  ① 最终 JSON 一律由 JSONObject/JSONArray **程序生成**，本文件不出现任何手写 JSON 模板；
//  ② 角色卡 description 恒空（人设进世界书），导出/导入都不改写这条；
//  ③ 落盘出口只有 TavernExport（产物/），Codec 自己零 IO、零网络、零子进程。
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

/** 一次导入的结果：最小 DTO ＋ 「会丢」清单。 */
internal data class TavernDecoded(
    val bundle: TavernBundleDto,
    val losses: TavernLossReport,
)

internal object TavernCodec {

    // ── 导出 ─────────────────────────────────────────────────────────────

    fun encode(bundle: TavernBundleDto): TavernEncoded = TavernEncoded(
        cardJson = cardJson(bundle.card),
        worldJson = worldJson(bundle.world),
        losses = TavernLossReport(exportLosses(bundle)),
    )

    /** `chara_card_v3`：spec/spec_version/data 三层，data 内逐字段 put。 */
    fun cardJson(card: TavernCardDto): String {
        val data = JSONObject()
        val values = cardValues(card)
        for (r in TavernFields.CARD_ROWS) {
            data.put(r.tavern, checkNotNull(values[r.ours]) { "字段表里出现未接线的卡字段：${r.ours}" })
        }
        // 铁律②在这里钉死：无论 DTO 里 description 传了什么，导出一律空串。
        data.put("description", "")
        return JSONObject()
            .put("spec", TavernFields.CARD_SPEC)
            .put("spec_version", TavernFields.CARD_SPEC_VERSION)
            .put("data", data)
            .toString(2) + "\n"
    }

    /**
     * 世界书：顶层 `name` + `entries[]`（数组，用户派工单明确形状）。
     * 每个条目按 [TavernFields.ENTRY_ROWS] 逐行 put；keys 三种写法原样输出为字符串数组。
     */
    fun worldJson(world: TavernWorldDto): String {
        val entries = JSONArray()
        for (e in world.entries) entries.put(entryJson(e))
        return JSONObject()
            .put(TavernFields.WORLD_NAME, world.name)
            .put(TavernFields.WORLD_ENTRIES, entries)
            .toString(2) + "\n"
    }

    private fun entryJson(e: TavernEntryDto): JSONObject {
        val o = JSONObject()
        val values = entryValues(e)
        for (r in TavernFields.ENTRY_ROWS) {
            val v = values[r.ours] ?: continue // 可选字段没给就不写，不补默认值
            o.put(r.tavern, v)
        }
        return o
    }

    /**
     * 值表（ours → value）：与 [TavernFields.CARD_ROWS] 一一对应。
     * 用 Map 而非 when 分支，既避免复杂度闸，也保证「表里加一行、这里必须跟着加一格」
     * ⇒ 字段表是唯一真相源，不会出现漏接线的字段。
     */
    private fun cardValues(c: TavernCardDto): Map<String, Any> = linkedMapOf(
        "name" to c.name,
        "description" to c.description,
        "personality" to c.personality,
        "scenario" to c.scenario,
        "firstMes" to c.firstMes,
        "mesExample" to c.mesExample,
        "creatorNotes" to c.creatorNotes,
        "tags" to JSONArray(c.tags),
        "creator" to c.creator,
        "characterVersion" to c.characterVersion,
    )

    /** 条目值表；可选字段给 null ⇒ 不写（不补默认值）。 */
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

    /** 导出方向：我们的自定义字段无处可去 ⇒ 逐个列「会丢」（判据③）。 */
    private fun exportLosses(b: TavernBundleDto): List<TavernLoss> {
        val out = ArrayList<TavernLoss>()
        b.card.customFields.keys.sorted().forEach {
            out += TavernLoss("导出/卡", it, "酒馆 chara_card_v3 没有对应字段，会丢")
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
     * 表外字段一律进「会丢」清单，不静默吞掉。
     */
    fun decode(cardText: String, worldText: String): TavernDecoded {
        val losses = ArrayList<TavernLoss>()
        val card = readCard(cardText, losses)
        val world = readWorld(worldText, losses)
        return TavernDecoded(TavernBundleDto(card, world), TavernLossReport(losses))
    }

    private fun readCard(text: String, losses: MutableList<TavernLoss>): TavernCardDto {
        val root = JSONObject(CardJson.withoutBom(text))
        val data = root.optJSONObject("data") ?: JSONObject()
        unknownKeys(data, TavernFields::isKnownCardKey, "导入/卡", losses)
        // 铁律②：卡描述恒空。源卡若非空，内容不做「偷偷塞回人设」的猜测，
        // 而是明确报一条「会丢」，同时保证 导入→导出→再导入 的核心字段严格相等。
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
            tags = stringArray(data.opt("tags")),
            creator = data.optString("creator"),
            characterVersion = data.optString("character_version"),
        )
    }

    private fun readWorld(text: String, losses: MutableList<TavernLoss>): TavernWorldDto {
        val root = JSONObject(CardJson.withoutBom(text))
        unknownKeys(root, TavernFields::isKnownWorldKey, "导入/世界书", losses)
        val entries = ArrayList<TavernEntryDto>()
        when (val raw = root.opt(TavernFields.WORLD_ENTRIES)) {
            is JSONArray -> for (i in 0 until raw.length()) {
                raw.optJSONObject(i)?.let { entries += readEntry(it, "[$i]", losses) }
            }
            is JSONObject -> {
                // 酒馆原生世界书是「整数 uid → 条目」字典；按 uid 数值升序还原顺序。
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
        // 数组形状用 keys；酒馆原生字典形状用 key（单数）。两者都收，不改原字段名以外的语义。
        val keys = keysOf(o, "keys") ?: keysOf(o, "key") ?: emptyList()
        return TavernEntryDto(
            keys = keys,
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
            position = if (o.has("position")) o.optInt("position") else null,
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
