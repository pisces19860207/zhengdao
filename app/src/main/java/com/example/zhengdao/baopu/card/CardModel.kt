// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：docs/格式层规格-三套卡与酒馆PNG-2026-10-19.md
//   §2.1（FormatVerdict 字段名照抄）／§2.2（九个 FormatKind）／§4.1（CheckReport）
//   §14.3（统一内部模型）／§15.1（血统枚举写 WorldbookDialect.WEB）
//   §16.3（三层并存：FormatKind.FENGYUE／WorldbookDialect.WEB／FY_* 码表）
//
// 「正文/数据只有一个真相」：认脸结果、体检输入、导出的 JSON 全走这里的数据类，
// 不许为读/写各拼一份。
package com.example.zhengdao.baopu.card

import org.json.JSONObject

/** 认脸结论（规格 §2.2：**只有这 9 个值**，不许自创）。 */
internal enum class FormatKind {
    /** 网络平台世界书（`world_book` 数组）。判据文本固定叫 FENGYUE，见 §16.3。 */
    FENGYUE,
    TAVO_BOOK,
    TAVO_CHAR,
    TAVO_REGEX,
    TAVO_PRESET,
    ST_WORLD,
    CCV3_LOREBOOK,
    ST_CARD_PNG,
    UNKNOWN,
}

/** 置信度（§2.6）。`UNKNOWN` 不适用 ⇒ 用 null。 */
internal enum class Confidence { HIGH, LOW }

/**
 * 认脸结果（§2.1 字段名照抄；末尾三个是本批追加，见报告「规格追加」一节）。
 *
 * 追加理由：
 * - [reason]／[message]：§2.7 要求「判不出」给逐字话术、F18 要求给理由码，
 *   原字段表里没有承载位置；
 * - [jsonText]：JSON 分支解码后的原文，供体检与解析复用（PNG 分支用 [embeddedJson]），
 *   避免下游再解一遍字节而出现「两处解码口径」。
 */
internal data class FormatVerdict(
    val format: FormatKind,
    val subtype: String? = null,
    val confidence: Confidence? = null,
    val evidence: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val defaultsApplied: List<String> = emptyList(),
    val entryCount: Int? = null,
    val keysSeen: List<String> = emptyList(),
    val embeddedJson: String? = null,
    val pngTextKeys: List<String> = emptyList(),
    val reason: String? = null,
    val message: String? = null,
    val jsonText: String? = null,
) {
    val isKnown: Boolean get() = format != FormatKind.UNKNOWN
    val isPng: Boolean get() = format == FormatKind.ST_CARD_PNG
}

/** 世界书血统（§15.1 硬规定：代码里写 `WEB`，不写平台名）。 */
internal enum class WorldbookDialect {
    /** 网络平台（把世界书渲染进服务端的那类聚合平台）。 */
    WEB,
    TAVO,
    ST_NATIVE,
    CCV3,
}

/**
 * 统一内部模型（§14.3）。**每一层都带 raw**：原样保留原始 JSON，
 * 统一视图只读；写回一律回原字段名（本批不做写回，见 §16.2）。
 */
internal sealed interface CardAsset {
    val raw: JSONObject

    /** 来源平台族：`web` / `tavo` / `sillytavern` / `ccv3`。 */
    val vendor: String

    /**
     * 来源血统（F19 要求「携带 vendor/dialect」）。只有世界书有血统；
     * 卡/正则/预设/人设没有方言概念 ⇒ null（不硬造一个）。
     */
    val dialect: WorldbookDialect? get() = null
}

/**
 * 角色卡（CCv2/CCv3 血统）。
 *
 * [legacyTopLevel] 是**整个原始顶层对象**（老 v2 顶层字段与 `data` 并存的那一层，F15）：
 * 读的一律取 [ccv3]，写回时它的键集合要原样保留。
 */
internal data class Card(
    override val raw: JSONObject,
    val ccv3: JSONObject,
    override val vendor: String,
    val legacyTopLevel: JSONObject?,
) : CardAsset

/** 世界书（三条血统 ＋ CCv3 交换格式）。 */
internal data class Worldbook(
    override val raw: JSONObject,
    override val dialect: WorldbookDialect,
    val entries: List<WorldbookEntryView>,
    override val vendor: String,
) : CardAsset

/**
 * 世界书条目的**统一只读视图**。
 *
 * [enabled] 是视图层的正写口径：酒馆的 `disable`（反义）在这里取反，
 * 但**只存在于视图层**——`raw` 里的 `disable` 一个字节不改（§16.2-i）。
 */
internal data class WorldbookEntryView(
    val index: Int,
    val dialect: WorldbookDialect,
    val keys: List<String>,
    val content: String,
    val enabled: Boolean,
    val order: Int?,
    val raw: JSONObject,
)

/** 正则脚本（与酒馆同源；本批只认脸、只做卡内提取）。 */
internal data class RegexScript(
    override val raw: JSONObject,
    val entries: List<JSONObject>,
    override val vendor: String,
) : CardAsset

/** 预设（近似但不同源）。 */
internal data class Preset(
    override val raw: JSONObject,
    val entries: List<JSONObject>,
    val basicPrompts: JSONObject?,
    override val vendor: String,
) : CardAsset

/** 人设（本批只认脸，不解析细则）。 */
internal data class Persona(
    override val raw: JSONObject,
    override val vendor: String,
) : CardAsset

/**
 * 网络平台的「作品」：**它没有角色卡**，对等物是「详细介绍」等四段文本 ＋ 世界书。
 * （§14.3 第三条纪律：风月内容不许塞进 [Card]。）
 */
internal data class FengyueWork(
    val detailHtml: String,
    val prompt: String,
    val prePrompt: String,
    val postPrompt: String,
    val builtInCss: String,
    val worldbook: Worldbook?,
    override val raw: JSONObject,
    override val vendor: String = "web",
) : CardAsset

/** 认脸与体检共用的逐字话术（§2.7／§5.8／§4.5）。**改话术只改这里**。 */
internal object CardText {
    const val NOT_JSON = "认不出这个文件：既不是 PNG 图片，也不是合法的 JSON 文本。"
    const val NOT_OBJECT = "认不出这个文件：JSON 最外层不是对象（是数组或单值），三套卡都不是这种形状。"
    const val ENTRIES_NOT_COLLECTION = "认不出这个文件：entries 既不是数组也不是对象。"
    const val PNG_NO_CARD_BLOCK = "这不是酒馆角色卡：PNG 里找不到 chara 或 ccv3 文本块。"
    const val CCV3_CARD_NOT_IN_SCOPE = "这是角色卡（CCv3），不是世界书。本批只处理世界书。"
    const val CCV3_SPEC_BUT_NO_ENTRIES = "这是 CCv3 世界书，但里面没有 entries，读不出条目。"

    fun unknownKeys(keys: List<String>): String =
        "认不出这个文件是什么格式。看过的顶层键：${keys.joinToString(", ")}。"

    fun pngBroken(index: Int, offset: Int): String =
        "PNG 文件结构损坏：第 $index 个数据块（偏移 $offset）读不下去。"

    fun pngPayload(reason: String): String = "这是 PNG，但卡数据解不开：$reason。"
}
