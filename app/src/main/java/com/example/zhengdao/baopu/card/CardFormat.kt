// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：docs/格式层规格-三套卡与酒馆PNG-2026-10-19.md §2（认脸）：
//   §2.0 门禁只看扩展名，解析器只看字节（本文件签名只有 ByteArray）
//   §2.2 判定算法**按顺序命中即停**（顺序就是优先级，不许重排）
//   §2.3 PNG 分支／§2.4 风月省略默认值就地补／§2.5 冲突裁决／§2.6 置信度／§2.7 逐字话术
//   §12.1–§12.3 CCv3 Lorebook 与 CHARX（F17／F18）
//
// 三条纪律：不许猜（判不出就 UNKNOWN＋keysSeen）；不许写盘；不许按文件名判。
package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 认脸（纯函数：字节进、结论出；**不碰文件系统**）。 */
internal object CardFormat {

    /** PNG 签名。 */
    private val PNG_SIG = CardPng.SIGNATURE

    /** zip 魔数（`.charx` 是 zip，§12.3 本批不解析）。 */
    private val ZIP_SIGS = listOf(
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),
        byteArrayOf(0x50, 0x4B, 0x05, 0x06),
        byteArrayOf(0x50, 0x4B, 0x07, 0x08),
    )

    fun recognize(bytes: ByteArray): FormatVerdict {
        // 第 0 步：PNG 先判（PNG 字节当文本解必失败）
        if (isPng(bytes)) return judgePng(bytes)
        if (ZIP_SIGS.any { startsWith(bytes, it) }) {
            return unknown("NOT_IN_SCOPE", message = "这不是 JSON 也不是 PNG（看起来是压缩包 / .charx），不在本批范围。")
        }

        // 第 1 步：当文本读
        val text = CardJson.decodeUtf8Strict(bytes)
            ?: return unknown("NON_UTF8", message = CardText.NOT_JSON)
        val value = try {
            JSONTokener(CardJson.withoutBom(text)).nextValue()
        } catch (e: Exception) {
            return unknown("NOT_JSON", message = CardText.NOT_JSON)
        }
        if (value !is JSONObject) {
            return unknown("NOT_OBJECT", message = CardText.NOT_OBJECT)
        }
        val j = value
        val keys = CardJson.topLevelKeys(text)

        // 第 2 步：P1 风月 —— 最高优先级（见了 world_book 就不再往下看）
        val worldBook = CardJson.arrayOf(j, "world_book")
        if (worldBook != null) return fengyue(j, worldBook, keys, text)

        // 第 2.5 步：P2 CCv3 显式标记（比字段猜更可靠）
        val spec = CardJson.stringOf(j, "spec")
        if (spec == "lorebook_v3") {
            val data = CardJson.objectOf(j, "data")
                ?: return unknown("CCV3_SPEC_NO_DATA", keys, text, CardText.CCV3_SPEC_BUT_NO_ENTRIES)
            val entries = CardJson.arrayOf(data, "entries")
                ?: return unknown("CCV3_SPEC_BUT_NO_ENTRIES", keys, text, CardText.CCV3_SPEC_BUT_NO_ENTRIES)
            return FormatVerdict(
                format = FormatKind.CCV3_LOREBOOK,
                confidence = Confidence.HIGH,
                evidence = listOf("spec=lorebook_v3 且 data.entries 是数组（${entries.length()} 条）"),
                entryCount = entries.length(),
                jsonText = text,
            )
        }
        if (spec == "chara_card_v2" || spec == "chara_card_v3") {
            return unknown("CCV3_CARD_NOT_IN_SCOPE", keys, text, CardText.CCV3_CARD_NOT_IN_SCOPE)
        }

        // 第 3 步：P3/P4 Tavo 资产 —— 四件套比「entries 的形态」更专有
        val fourPieces = j.has("kind") && j.has("id") && j.has("name") && j.has("updatedAt")
        if (fourPieces) {
            val data = CardJson.objectOf(j, "data")
            if (data != null) {
                return FormatVerdict(
                    format = FormatKind.TAVO_CHAR,
                    confidence = Confidence.HIGH,
                    evidence = listOf("Tavo 四件套 kind/id/name/updatedAt ＋ data 是对象（${data.length()} 个键）"),
                    jsonText = text,
                )
            }
            val entries = CardJson.arrayOf(j, "entries")
            if (entries != null) {
                val first = entries.optJSONObject(0)
                if (entries.length() > 0 && first != null && first.has("findRegex")) {
                    return FormatVerdict(
                        format = FormatKind.TAVO_REGEX,
                        confidence = Confidence.HIGH,
                        evidence = listOf("Tavo 四件套 ＋ entries 元素含 findRegex（${entries.length()} 条）"),
                        entryCount = entries.length(),
                        jsonText = text,
                    )
                }
                val presetish = j.has("basicPrompts") || j.has("active") ||
                    (first != null && first.has("content") && first.has("role"))
                // 元素明显是世界书（keywords/injectionPosition）⇒ 子类是 null（F1 对 J3 的期望）；
                // 只有「四件套齐、但子类真判不出」才打 TAVO_ASSET_UNKNOWN 哨兵。
                val tavolike = first != null && (first.has("keywords") || first.has("injectionPosition"))
                if (tavolike && !presetish) {
                    return FormatVerdict(
                        format = FormatKind.TAVO_BOOK,
                        subtype = null,
                        confidence = Confidence.HIGH,
                        evidence = listOf("Tavo 四件套 ＋ entries 是数组，元素含 keywords/injectionPosition（${entries.length()} 条）"),
                        entryCount = entries.length(),
                        jsonText = text,
                    )
                }
                if (presetish) {
                    return FormatVerdict(
                        format = FormatKind.TAVO_PRESET,
                        confidence = Confidence.HIGH,
                        evidence = listOf(
                            if (j.has("basicPrompts")) "Tavo 四件套 ＋ 顶层含 basicPrompts"
                            else "Tavo 四件套 ＋ 顶层含 active / 元素含 content+role",
                        ),
                        entryCount = entries.length(),
                        jsonText = text,
                    )
                }
                return FormatVerdict(
                    format = FormatKind.TAVO_BOOK,
                    subtype = SUBTYPE_TAVO_ASSET_UNKNOWN,
                    confidence = Confidence.LOW,
                    evidence = listOf("Tavo 四件套 ＋ entries 是数组，但子类判不出（无 findRegex / 无 basicPrompts）"),
                    entryCount = entries.length(),
                    jsonText = text,
                )
            }
            if (j.has("entries")) {
                // C3：四件套 ＋ entries 是字典 ⇒ 仍是 Tavo 资产，但形态异常
                return FormatVerdict(
                    format = FormatKind.TAVO_BOOK,
                    subtype = SUBTYPE_TAVO_ASSET_UNKNOWN,
                    confidence = Confidence.LOW,
                    evidence = listOf("Tavo 四件套 ＋ entries 是字典（形态异常）"),
                    conflicts = listOf("Tavo 四件套与字典形态 entries 并存，形态异常"),
                    jsonText = text,
                )
            }
            return unknown("TAVO_SHAPE_INCOMPLETE", keys, text, CardText.unknownKeys(keys))
        }

        // 第 4 步：只有 entries —— 数组要按元素字段再分一次（Tavo vs CCv3）
        if (j.has("entries")) {
            val arr = CardJson.arrayOf(j, "entries")
            if (arr != null) {
                if (arr.length() == 0) {
                    return FormatVerdict(
                        format = FormatKind.TAVO_BOOK,
                        subtype = SUBTYPE_EMPTY_AMBIGUOUS,
                        confidence = Confidence.LOW,
                        evidence = listOf("entries 是空数组，无元素可交叉验证"),
                        conflicts = listOf("entries 是空数组，Tavo 与 CCv3 无法区分，按 Tavo 处理"),
                        entryCount = 0,
                        jsonText = text,
                    )
                }
                val first = arr.optJSONObject(0)
                val ccv3ish = first != null && (first.has("keys") || first.has("insertion_order") || first.has("use_regex"))
                if (ccv3ish) {
                    return FormatVerdict(
                        format = FormatKind.CCV3_LOREBOOK,
                        confidence = Confidence.HIGH,
                        evidence = listOf("entries 是数组，元素含 keys/insertion_order/use_regex"),
                        entryCount = arr.length(),
                        jsonText = text,
                    )
                }
                val tavolike = first != null && (first.has("keywords") || first.has("injectionPosition"))
                if (tavolike) {
                    return FormatVerdict(
                        format = FormatKind.TAVO_BOOK,
                        confidence = Confidence.HIGH,
                        evidence = listOf("entries 是数组，元素含 keywords/injectionPosition（${arr.length()} 条）"),
                        entryCount = arr.length(),
                        jsonText = text,
                    )
                }
                val conflicts = if (first != null && first.has("findRegex")) {
                    listOf("像正则但缺 Tavo 四件套，按 Tavo 世界书处理")
                } else {
                    listOf("entries 是数组但元素字段认不出，按 Tavo 世界书处理")
                }
                return FormatVerdict(
                    format = FormatKind.TAVO_BOOK,
                    subtype = SUBTYPE_ELEMENT_SHAPE_UNKNOWN,
                    confidence = Confidence.LOW,
                    evidence = listOf("entries 是数组，元素既非 keywords 也非 keys"),
                    conflicts = conflicts,
                    entryCount = arr.length(),
                    jsonText = text,
                )
            }
            val dict = CardJson.objectOf(j, "entries")
            if (dict != null) {
                val empty = dict.length() == 0
                val first = dict.keys().asSequence().firstOrNull()?.let { dict.optJSONObject(it) }
                // C5：只有 Tavo 专有的 keywords 才算「元素像 Tavo」；content 两边都有，不能当证据
                val conflicts = if (first != null && first.has("keywords")) {
                    listOf("元素字段像 Tavo，但 entries 是字典 ⇒ 键类型说了算")
                } else emptyList()
                return FormatVerdict(
                    format = FormatKind.ST_WORLD,
                    confidence = if (empty) Confidence.LOW else Confidence.HIGH,
                    evidence = listOf(
                        if (empty) "entries 是空字典，无元素可交叉验证" else "entries 是字典（键为整数 uid，${dict.length()} 条）",
                    ),
                    conflicts = conflicts,
                    entryCount = dict.length(),
                    jsonText = text,
                )
            }
            return unknown("ENTRIES_NOT_COLLECTION", keys, text, CardText.ENTRIES_NOT_COLLECTION)
        }

        // 第 5 步：什么都不是
        val conflicts = if (looksLikeIdMap(j)) listOf("疑似 id 映射表，不是卡") else emptyList()
        return unknown("NO_SIGNATURE_KEY", keys, text, CardText.unknownKeys(keys)).copy(conflicts = conflicts)
    }

    // ── 风月（P1） ───────────────────────────────────────────────────────

    private fun fengyue(j: JSONObject, book: JSONArray, keys: List<String>, text: String): FormatVerdict {
        val defaults = LinkedHashSet<String>()
        for (i in 0 until book.length()) {
            val e = book.optJSONObject(i) ?: continue
            if (!e.has("value_type")) defaults += "value_type"
            if (!e.has("value_configs")) defaults += "value_configs"
        }

        val conflicts = ArrayList<String>()
        for (k in listOf("entries", "kind", "id", "updatedAt")) {
            if (j.has(k)) conflicts += "world_book 与 $k 并存，按风月世界书处理"
        }
        val comment = j.opt("_comment")
        val hasComment = j.has("_comment")
        val commentIsArray = comment is JSONArray
        if (hasComment && !commentIsArray) conflicts += "_comment 不是数组"

        return FormatVerdict(
            format = FormatKind.FENGYUE,
            subtype = if (commentIsArray) SUBTYPE_FENGYUE_TEMPLATE else SUBTYPE_FENGYUE_PLAIN,
            confidence = Confidence.HIGH,
            evidence = buildList {
                add("顶层键 world_book 是数组（${book.length()} 条）")
                if (commentIsArray) add("顶层另有 _comment（字符串数组）⇒ 平台模板")
            },
            conflicts = conflicts,
            defaultsApplied = defaults.toList(),
            entryCount = book.length(),
            jsonText = text,
        )
    }

    // ── PNG（第 0 步） ───────────────────────────────────────────────────

    private fun judgePng(bytes: ByteArray): FormatVerdict {
        val chunks = when (val s = CardPng.scan(bytes)) {
            is PngScan.Broken -> return unknown(
                "PNG_CHUNK_BROKEN",
                message = CardText.pngBroken(s.index, s.offset),
            )
            is PngScan.Ok -> s.chunks
        }
        val textBlocks = LinkedHashMap<String, String>()
        val textKeys = ArrayList<String>()
        for (c in chunks) {
            when (c.type) {
                "tEXt" -> {
                    val zero = c.data.indexOf(0.toByte())
                    if (zero <= 0) continue
                    val kw = String(c.data, 0, zero, Charsets.ISO_8859_1)
                    textBlocks[kw] = String(c.data, zero + 1, c.data.size - zero - 1, Charsets.ISO_8859_1)
                    textKeys += kw
                }
                "iTXt", "zTXt" -> {
                    val zero = c.data.indexOf(0.toByte())
                    val kw = if (zero > 0) String(c.data, 0, zero, Charsets.ISO_8859_1) else "?"
                    textKeys += "$kw（${c.type}，未使用）"
                }
            }
        }

        val hasCcv3 = textBlocks.containsKey(CardPng.KEY_CCV3)
        val hasChara = textBlocks.containsKey(CardPng.KEY_CHARA)
        if (!hasCcv3 && !hasChara) {
            return unknown("PNG_NO_CARD_BLOCK", message = CardText.PNG_NO_CARD_BLOCK).copy(pngTextKeys = textKeys)
        }
        val kw = if (hasCcv3) CardPng.KEY_CCV3 else CardPng.KEY_CHARA
        val raw = try {
            java.util.Base64.getDecoder().decode(textBlocks.getValue(kw))
        } catch (e: IllegalArgumentException) {
            return unknown("BASE64_BAD", message = CardText.pngPayload("base64 解码失败"))
                .copy(pngTextKeys = textKeys)
        }
        val s = CardJson.decodeUtf8Strict(raw)
            ?: return unknown("PAYLOAD_NOT_UTF8", message = CardText.pngPayload("内容不是 UTF-8"))
                .copy(pngTextKeys = textKeys)
        val obj = CardJson.parseObject(s)
        if (obj == null) {
            val t = CardJson.withoutBom(s).trimStart()
            val isJson = t.startsWith("{") || t.startsWith("[")
            return unknown(
                if (isJson) "PAYLOAD_NOT_OBJECT" else "PAYLOAD_NOT_JSON",
                message = CardText.pngPayload(if (isJson) "JSON 最外层不是对象" else "内容不是 JSON"),
            ).copy(pngTextKeys = textKeys)
        }

        val spec = CardJson.stringOf(obj, "spec")
        val subtype = when {
            hasCcv3 -> SUBTYPE_PNG_V3
            spec == "chara_card_v3" -> SUBTYPE_PNG_V3
            else -> SUBTYPE_PNG_V2
        }
        val conflicts = if (hasCcv3 && hasChara && textBlocks[CardPng.KEY_CHARA] != textBlocks[CardPng.KEY_CCV3]) {
            listOf("chara 与 ccv3 内容不一致，已取 ccv3")
        } else emptyList()

        return FormatVerdict(
            format = FormatKind.ST_CARD_PNG,
            subtype = subtype,
            confidence = Confidence.HIGH,
            evidence = listOf("PNG 签名 ＋ 文本块 ${if (hasCcv3) "ccv3" else "chara"}（base64 → JSON 对象）"),
            conflicts = conflicts,
            embeddedJson = s,
            pngTextKeys = textKeys,
        )
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    private fun unknown(
        reason: String,
        keys: List<String> = emptyList(),
        text: String? = null,
        message: String = "",
    ): FormatVerdict = FormatVerdict(
        format = FormatKind.UNKNOWN,
        reason = reason,
        message = message,
        keysSeen = keys,
        jsonText = text,
    )

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && (0 until 8).all { bytes[it] == PNG_SIG[it] }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    /** C6：顶层是纯 `{整数键: 整数}` 字典（映射表）。 */
    private fun looksLikeIdMap(j: JSONObject): Boolean {
        if (j.length() == 0) return false
        return j.keys().asSequence().all { k ->
            k.toIntOrNull() != null && CardJson.numberAsInt(j, k) != null
        }
    }

    // 子类型字面量（§2.2 哨兵值；§2.1 表视为过时，见 §16.3-2）
    const val SUBTYPE_FENGYUE_TEMPLATE = "TEMPLATE"
    const val SUBTYPE_FENGYUE_PLAIN = "PLAIN"
    const val SUBTYPE_PNG_V3 = "V3"
    const val SUBTYPE_PNG_V2 = "V2"
    const val SUBTYPE_TAVO_ASSET_UNKNOWN = "TAVO_ASSET_UNKNOWN"
    const val SUBTYPE_EMPTY_AMBIGUOUS = "EMPTY_AMBIGUOUS"
    const val SUBTYPE_ELEMENT_SHAPE_UNKNOWN = "ELEMENT_SHAPE_UNKNOWN"
}
