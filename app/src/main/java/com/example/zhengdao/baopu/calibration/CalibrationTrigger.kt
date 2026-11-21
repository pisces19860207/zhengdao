// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十八批 · 校准块触发判定（轻量、毫秒级、**不调模型**）。
//
// 只做字符串包含匹配：命中男性身体相关内容（部位名／状态词／身体描述请求）⇒ calibrate=true。
// 词表由上层注入（[CalibrationLexicon]），本文件不读盘、不检索、不调模型；
// 分档 L0/L1/L2 只是"要不要多带几条"的标记，真正的预算裁剪切在 CalibrationInject。
package com.example.zhengdao.baopu.calibration

/** 注入分档：OFF 不注入；L0 仅三行；L1 具体部位/状态；L2 用户显式要"详细"。 */
enum class CalibrationLevel(val rank: Int) {
    OFF(0),
    L0(1),
    L1(2),
    L2(3),
    ;

    companion object {
        fun of(name: String): CalibrationLevel = values().firstOrNull { it.name == name } ?: OFF
    }
}

/** 文本风格信号（三病对照的判定输出，不做内容裁决）。 */
enum class CalibrationStyle {
    NEUTRAL,
    /** 网文腔：命中 739 blacklist 默认词 ⇒ 词档行要带"禁用默认词"提醒。 */
    WEBNOVEL,
    /** 医学腔：命中治疗/预防等 ⇒ 输出带"非医疗判断"提示。 */
    MEDICAL,
}

/** 轻量触发词表：全部由上层从真源装配一次，判定时只在内存里 `contains`。 */
data class CalibrationLexicon(
    val zoneNames: List<String>,
    val stateWords: List<String>,
    val bodyRequestWords: List<String>,
    val detailWords: List<String>,
    val collapseWords: List<String>,
    val webnovelWords: List<String>,
    val medicalWords: List<String>,
) {
    companion object {
        /** 内置兜底表（触发词，不是内容库）：真源装配失败时用，仍能判定命中。 */
        val MINIMAL: CalibrationLexicon = CalibrationLexicon(
            zoneNames = listOf("根部", "柱身", "阴茎缝", "龟头", "龟头冠", "冠状沟", "系带", "尿道口", "包皮", "阴囊", "睾丸", "精索", "耻骨区"),
            stateWords = listOf("勃起", "充血", "硬度", "潮红", "心跳", "出汗", "湿润", "搏动"),
            bodyRequestWords = listOf("身体", "下体", "部位", "描写身体", "写身体", "描述一下身体"),
            detailWords = listOf("详细", "具体", "仔细", "展开", "细写"),
            collapseWords = listOf("描述一下", "说说", "写写", "随便写", "来一段"),
            webnovelWords = listOf("一柱擎天", "粗如手腕", "大汗淋漓", "无法自已", "血脉偾张"),
            medicalWords = listOf("治疗", "预防", "卫生建议", "诊断", "症状"),
        )
    }
}

/** 一次触发判定的结果（纯数据，可单测钉死）。 */
data class TriggerDecision(
    val calibrate: Boolean,
    val level: CalibrationLevel,
    val matchedZones: List<String>,
    val style: CalibrationStyle,
    val wantsDetail: Boolean,
    val collapse: Boolean,
) {
    companion object {
        val OFF = TriggerDecision(
            calibrate = false,
            level = CalibrationLevel.OFF,
            matchedZones = emptyList(),
            style = CalibrationStyle.NEUTRAL,
            wantsDetail = false,
            collapse = false,
        )
    }
}

/** 触发判定本体：纯函数，零 IO、零时钟、零随机、零模型。 */
object CalibrationTrigger {

    fun decide(text: String, lex: CalibrationLexicon = CalibrationLexicon.MINIMAL): TriggerDecision {
        if (text.isBlank()) return TriggerDecision.OFF
        val zones = lex.zoneNames.filter { it.isNotBlank() && it in text }
        val states = containsAny(lex.stateWords, text)
        val request = containsAny(lex.bodyRequestWords, text)
        val detail = containsAny(lex.detailWords, text)
        val collapse = containsAny(lex.collapseWords, text) && zones.isEmpty() && !states
        val style = styleOf(lex, text)
        if (!isHit(zones, states, request, collapse, style)) return TriggerDecision.OFF
        return TriggerDecision(true, levelOf(detail, zones, states, request, collapse), zones, style, detail, collapse)
    }

    /** 轻量包含判定：词表里任一词非空且出现在文本中。抽出来压低 decide 的圈复杂度。 */
    private fun containsAny(words: List<String>, text: String): Boolean =
        words.any { it.isNotBlank() && it in text }

    /** 风格判定：网文腔优先于医学腔，二者皆无即中性。 */
    private fun styleOf(lex: CalibrationLexicon, text: String): CalibrationStyle = when {
        containsAny(lex.webnovelWords, text) -> CalibrationStyle.WEBNOVEL
        containsAny(lex.medicalWords, text) -> CalibrationStyle.MEDICAL
        else -> CalibrationStyle.NEUTRAL
    }

    /** 是否算命中男性身体相关内容。 */
    private fun isHit(
        zones: List<String>, states: Boolean, request: Boolean,
        collapse: Boolean, style: CalibrationStyle,
    ): Boolean = zones.isNotEmpty() || states || request || collapse || style != CalibrationStyle.NEUTRAL

    /** 分档：显式详细 L2；部位/状态/请求/塌缩 L1；仅风格 L0。 */
    private fun levelOf(
        detail: Boolean, zones: List<String>, states: Boolean, request: Boolean, collapse: Boolean,
    ): CalibrationLevel = when {
        detail -> CalibrationLevel.L2
        zones.isNotEmpty() || states || request || collapse -> CalibrationLevel.L1
        else -> CalibrationLevel.L0
    }
}
