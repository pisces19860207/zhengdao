// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：docs/格式层规格-三套卡与酒馆PNG-2026-10-19.md §14.3（统一内部模型）
//   ＋ §14.4（F19 血统与 raw／F20 只读视图）＋ §15.1（血统枚举 WEB）
//   ＋ §15.6（Tavo 是酒馆的改名版，字段对应表）＋ §16.2（视图只读一致性）
//
// 三条纪律：
// 1. `raw` 原样保留，视图**只读**；本批不提供任何写回入口（§16.2）。
// 2. `dialect` 只记来源血统，不改变字段；跨血统转换是未来批次的事。
// 3. 网络平台走 [WebWork]，不许硬塞进 [Card]（它没有卡）。
package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject

/** 「解析」= 认脸 ＋ 统一视图（同一份字节、同一个结论，不读第二遍判定）。 */
internal object CardAssetParser {

    data class Parsed(val verdict: FormatVerdict, val asset: CardAsset)

    /** 解析失败（判不出 / 不是对象）⇒ null；调用方拿 [CardFormat.recognize] 的话术报错。 */
    fun parse(bytes: ByteArray): Parsed? {
        val verdict = CardFormat.recognize(bytes)
        if (!verdict.isKnown) return null
        val text = verdict.jsonText ?: verdict.embeddedJson ?: return null
        val raw = CardJson.parseObject(text) ?: return null
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
            FormatKind.TAVO_REGEX -> RegexScript(raw, objectsOf(CardJson.arrayOf(raw, "entries")), "tavo")
            FormatKind.TAVO_PRESET -> Preset(
                raw = raw,
                entries = objectsOf(CardJson.arrayOf(raw, "entries")),
                basicPrompts = CardJson.objectOf(raw, "basicPrompts"),
                vendor = "tavo",
            )
            FormatKind.UNKNOWN -> return null
        }
        return Parsed(verdict, asset)
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

    private fun stringList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val v = a.opt(i)
            if (v is String) out += v
        }
        return out
    }

    private fun objectsOf(a: JSONArray?): List<JSONObject> {
        if (a == null) return emptyList()
        val out = ArrayList<JSONObject>(a.length())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += it }
        return out
    }
}
