// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十五批（2026-11-11）：酒馆格式兼容 · 最小 DTO（过渡件）。
//
// ★ 定位：这是「格式兼容层」自己的数据形状，**不是**做卡模型本身。
//   579 的 NovelObject 对象模型尚未收敛（全仓 0 命中，派工单状态「待派」），
//   581 人物卡同样待派 ⇒ 本批只立一层最小 DTO 过渡，导入/导出都对着它，
//   等 579 收敛后把这一层换成 NovelObject 的映射即可，格式层接口不用动。
//   详见交付报告「待 579 收敛」一节。
//
// 纯 Kotlin：只依赖 Kotlin 标准库；org.json 只在 Codec/Schema 里用，本文件零依赖。
package com.example.zhengdao.baopu.card

/**
 * 角色卡（对应 `chara_card_v3`）。
 *
 * [description] 恒为空串（铁律②）：酒馆会把 `data.description` 当人设直接注入，
 * 我们的人设/设定一律走世界书条目。导入时若源卡的 description 非空，明确报一条
 * 「会丢」并置空，不把内容偷偷塞回来（同时保证往返保真严格成立）。
 *
 * [customFields] 是「我们的自定义字段」的唯一入口：导出时它们全部无处可去，
 * 必须逐个报「会丢」（判据③），不许静默丢弃。
 */
internal data class TavernCardDto(
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "zhengdao-tavern-compat",
    val characterVersion: String = "1.0",
    val customFields: Map<String, String> = emptyMap(),
)

/** 一个键的写法：普通文本，或 `/pattern/flags` 正则键（判据④）。 */
internal enum class TavernKeyKind { TEXT, REGEX }

/**
 * 键 token。[raw] 是**导出到 JSON 里的原样字符串**，导入时原样读回 ⇒ 往返不改写。
 * [kind] 只由 `TavernKeys.tokenOf()` 从 raw 判别，不另存第二份事实。
 */
internal data class TavernKeyToken(val raw: String, val kind: TavernKeyKind)

/** 键的三种形态（判据④）：单文本 / 多值 / 正则键。 */
internal enum class TavernKeyForm { PLAIN, MULTI, REGEX }

internal object TavernKeys {
    /**
     * `/re/`、`/re/i` 这类写法判为正则键；其余一律文本。
     * 用**最后一个** `/` 收尾，允许 pattern 里本身带 `/`（如 `/a\/b/`）。
     */
    fun tokenOf(raw: String): TavernKeyToken {
        val isRegex = raw.length >= 3 && raw[0] == '/' &&
            raw.lastIndexOf('/') > 0
        return TavernKeyToken(raw, if (isRegex) TavernKeyKind.REGEX else TavernKeyKind.TEXT)
    }

    fun tokensOf(raws: List<String>): List<TavernKeyToken> = raws.map(::tokenOf)

    /** 一个条目里出现了哪几种键形态（供判据④对拍，不是第二份事实）。 */
    fun formsOf(tokens: List<TavernKeyToken>): Set<TavernKeyForm> {
        val out = LinkedHashSet<TavernKeyForm>()
        if (tokens.size > 1) out += TavernKeyForm.MULTI
        if (tokens.any { it.kind == TavernKeyKind.REGEX }) out += TavernKeyForm.REGEX
        if (tokens.any { it.kind == TavernKeyKind.TEXT }) out += TavernKeyForm.PLAIN
        if (tokens.size == 1 && tokens[0].kind == TavernKeyKind.REGEX) out -= TavernKeyForm.PLAIN
        return out
    }
}

/**
 * 世界书条目。字段清单＝用户派工单逐字给定的 11 项；
 * [position]／[useProbability] 是派工单另列的两项可选兼容字段（有才写）。
 *
 * [customFields] 同 [TavernCardDto.customFields]：导出时逐个报「会丢」。
 */
internal data class TavernEntryDto(
    val keys: List<TavernKeyToken>,
    val content: String,
    val keysecondary: List<TavernKeyToken> = emptyList(),
    val constant: Boolean = false,
    val selective: Boolean = false,
    val order: Int = 100,
    val depth: Int = 4,
    val probability: Int = 100,
    val disable: Boolean = false,
    val addMemo: Boolean = true,
    val group: String = "",
    val position: Int? = null,
    val useProbability: Boolean? = null,
    val customFields: Map<String, String> = emptyMap(),
)

internal data class TavernWorldDto(
    val name: String = "",
    val entries: List<TavernEntryDto> = emptyList(),
)

/** 一次导入/导出的完整载荷（卡 + 设定集）。 */
internal data class TavernBundleDto(val card: TavernCardDto, val world: TavernWorldDto)

/**
 * 「会丢」清单（判据③④）。**不许静默**：导出/导入都要把它交回调用方，
 * 并落一份 `_会丢字段.md` 到产物目录。
 */
internal data class TavernLoss(
    val side: String,
    val field: String,
    val reason: String,
) {
    fun line(): String = "- [$side] $field：$reason"
}

internal data class TavernLossReport(val losses: List<TavernLoss> = emptyList()) {
    val isEmpty: Boolean get() = losses.isEmpty()

    /** 人话话术；判据③要求「报会丢、不静默」，这句就是凭据。 */
    fun message(): String = if (losses.isEmpty()) {
        "本次导出/导入没有不支持的字段，无字段会丢。"
    } else {
        "以下 ${losses.size} 项字段酒馆侧不支持或我方不解析，**会丢**（已如实列出，不静默）：\n" +
            losses.joinToString("\n") { it.line() }
    }

    fun markdown(): String = "# 585 酒馆格式兼容 · 不支持字段清单\n\n" +
        message() + "\n\n> 导出只做格式兼容；以上字段没有被偷偷改写或塞进别的字段。\n"
}
