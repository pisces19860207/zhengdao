// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十七批 · 连续性校验：③写后抽描写与卡比对 / ④鲜活判据 / ⑥千人千面。
// 纯判定层：输入是「卡」和「从场景里抽出的字段→档名」，不读文本原文、不碰装配/落库。
// 三态：红（与卡矛盾）／提示（卡未设定可补、像标本）／通过。
package com.example.zhengdao.baopu.state.casting

/** 校验结论三态。[severity] 越大越严，取最严时用——不可依赖枚举声明次序。 */
enum class CheckState(val label: String, val severity: Int) {
    PASS("通过", 0), HINT("提示", 1), RED("红", 2),
}

/** 一条校验结果。稳定快照 [line] 供确定性与报告逐字对齐。 */
data class CheckResult(
    val state: CheckState,
    val code: String,
    val detail: String,
) {
    fun line(): String = "${state.label}|$code|$detail"
}

/**
 * ③ 连续性：把「场景里抽到的描写」逐字段与卡比对。
 * [observed] key 是卡字段 id，value 是描写里出现的档名列表。
 * · 字段问的是卡里没设的 ⇒ HINT「可补卡」（不是红：没设定不构成矛盾）。
 * · 描写给的值与卡内的值不相交 ⇒ RED（与卡矛盾）。
 * · 描写里出现表外字段或表外档 ⇒ RED（不许新造维度/档）。
 * · 否则 PASS。
 */
object ContinuityCheck {

    fun check(card: CastingCard, observed: Map<String, List<String>>): CheckResult {
        val unknownFields = observed.keys.filter { !CastingRefs.known(it) }
        if (unknownFields.isNotEmpty()) {
            return CheckResult(CheckState.RED, "新维度", "描写出现表外字段：${unknownFields.joinToString("、")}")
        }
        val hits = ArrayList<CheckResult>()
        observed.forEach { (fieldId, seen) ->
            hits += checkField(card, fieldId, seen)
        }
        return worst(hits)
    }

    /** 单字段比对；顺序：先查描写档合法性，再查卡是否设定，最后查是否矛盾。 */
    private fun checkField(card: CastingCard, fieldId: String, seen: List<String>): CheckResult {
        val ref = CastingRefs.field(fieldId)
        val illegal = seen.filter { !ref.accepts(it) }
        if (illegal.isNotEmpty()) {
            return CheckResult(CheckState.RED, "新档", "$fieldId 的描写「${illegal.joinToString("、")}」不在既有档内")
        }
        if (!card.has(fieldId)) {
            return CheckResult(CheckState.HINT, "可补卡", "$fieldId（${ref.cn}）卡未设定：描写出现了，可补卡")
        }
        val cardValues = card.of(fieldId)
        return if (seen.any { it in cardValues }) {
            CheckResult(CheckState.PASS, "一致", "$fieldId 描写与卡一致")
        } else {
            CheckResult(CheckState.RED, "矛盾", "$fieldId 描写「${seen.joinToString("、")}」与卡「${cardValues.joinToString("、")}」矛盾")
        }
    }

    /** 取最严的一态：红 > 提示 > 通过；用 severity，不用声明次序。 */
    private fun worst(hits: List<CheckResult>): CheckResult =
        hits.maxByOrNull { it.state.severity } ?: CheckResult(CheckState.PASS, "无描写", "本场未抽到可比对字段")

    /**
     * 同场 ≥3 人须 ≥2 项可辨差异：任意两人之间，卡已设定且取值不同的字段数 ≥2。
     * 只比已设定字段——「两个都没设定」不构成差异（否则缺卡反而算分辨出来了）。
     */
    fun distinguishable(cards: List<CastingCard>): CheckResult {
        if (cards.size < 3) {
            return CheckResult(CheckState.PASS, "不足三人", "同场 ${cards.size} 人，≥3 人才启用可辨差异")
        }
        val pair = cards.indices.flatMap { a -> (a + 1 until cards.size).map { b -> a to b } }
            .firstOrNull { (a, b) -> diffCount(cards[a], cards[b]) < 2 }
        return if (pair == null) {
            CheckResult(CheckState.PASS, "可辨", "同场 ${cards.size} 人，任意两人 ≥2 项可辨差异")
        } else {
            val (a, b) = pair
            CheckResult(
                CheckState.RED, "难辨",
                "同场 ${cards[a].roleId} 与 ${cards[b].roleId} 仅 ${diffCount(cards[a], cards[b])} 项可辨差异（须 ≥2）",
            )
        }
    }

    /** 两人之间「卡已设定且取值不相交」的字段数。 */
    private fun diffCount(a: CastingCard, b: CastingCard): Int =
        CastingRefs.CARD_FIELDS.count { ref ->
            a.has(ref.id) && b.has(ref.id) && a.of(ref.id).none { it in b.of(ref.id) }
        }
}

/**
 * ④ 鲜活判据：每场至少命中一项状态痕迹（出汗/呼吸/疲劳/姿态/皮肤变化）。
 * [traces] 是描写里抽到的痕迹字段 id 集合（须在 TRACE_FIELDS 白名单内）；
 * 一条都没有 ⇒ HINT「像标本」，不是红（这是鲜活度提醒，不是与卡矛盾）。
 */
object VitalityCheck {

    const val LIKE_SPECIMEN = "像标本：本场没有一项状态痕迹（出汗/呼吸/疲劳/姿态/皮肤变化）"

    fun check(traces: Set<String>): CheckResult {
        val unknown = traces.filter { !CastingRefs.known(it) }
        if (unknown.isNotEmpty()) {
            return CheckResult(CheckState.RED, "新维度", "痕迹字段表外：${unknown.joinToString("、")}")
        }
        val alive = traces.filter { it in CastingRefs.TRACE_FIELDS.map { f -> f.id } }
        return if (alive.isEmpty()) {
            CheckResult(CheckState.HINT, "像标本", LIKE_SPECIMEN)
        } else {
            CheckResult(CheckState.PASS, "鲜活", "命中 ${alive.size} 项状态痕迹：${alive.joinToString("、")}")
        }
    }
}

/** 一场戏的校验汇总：三态取最严，逐条结论按固定次序留存，供报告与回放。 */
data class SceneReport(
    val continuity: CheckResult,
    val distinct: CheckResult,
    val vitality: CheckResult,
    val uniqueness: CheckResult,
) {
    val overall: CheckState get() = listOf(continuity.state, distinct.state, vitality.state, uniqueness.state)
        .maxOf { it.severity }.let { s -> CheckState.values().first { it.severity == s } }

    fun line(): String = listOf(continuity, distinct, vitality, uniqueness)
        .joinToString("\n") { it.line() }
}

/**
 * 每场写后的唯一校验出口：把③连续性、④鲜活、⑥千人千面按固定次序跑一遍，
 * 红压过提示、提示压过通过。[observed] 是描写抽出的卡字段→档名；[traces] 是抽出的痕迹字段。
 */
object SceneCheck {

    fun check(
        sceneCards: List<CastingCard>,
        observed: Map<String, List<String>>,
        traces: Set<String>,
    ): SceneReport {
        val primary = sceneCards.firstOrNull()
            ?: return SceneReport(
                ContinuityCheck.check(CastingCard("∅", "∅", false), observed),
                ContinuityCheck.distinguishable(sceneCards),
                VitalityCheck.check(traces),
                UniquenessCheck.check(sceneCards),
            )
        return SceneReport(
            continuity = ContinuityCheck.check(primary, observed),
            distinct = ContinuityCheck.distinguishable(sceneCards),
            vitality = VitalityCheck.check(traces),
            uniqueness = UniquenessCheck.check(sceneCards),
        )
    }
}

/**
 * ⑥ 千人千面：两条硬规则，任一违反判红。
 * ① 同一档案（profileId）的主要角色 ≤2；
 * ② 主要角色的「脸型＋发型＋体型」三连不得全同（三位全同才红；发型未设定也参与比对）。
 */
object UniquenessCheck {

    fun check(cards: List<CastingCard>): CheckResult {
        val mains = cards.filter { it.isMain }
        val byProfile = mains.groupBy { it.profileId }
            .filterValues { it.size > 2 }
        if (byProfile.isNotEmpty()) {
            val worst = byProfile.entries.first()
            return CheckResult(
                CheckState.RED, "同档案过多",
                "档案 ${worst.key} 的主要角色 ${worst.value.size} 人，上限 2",
            )
        }
        val trioPairs = mains.indices.flatMap { a -> (a + 1 until mains.size).map { b -> a to b } }
            .filter { (a, b) -> trio(mains[a]) == trio(mains[b]) }
        return if (trioPairs.isEmpty()) {
            CheckResult(CheckState.PASS, "千人千面", "${mains.size} 名主要角色三连互不相同")
        } else {
            val (a, b) = trioPairs.first()
            CheckResult(
                CheckState.RED, "三连全同",
                "${mains[a].roleId} 与 ${mains[b].roleId} 脸型＋发型＋体型三连全同",
            )
        }
    }

    /** 三连身份键：未设定用「∅」占位——两人都不设定也算同（共同缺席不构成差异）。 */
    private fun trio(card: CastingCard): String =
        CastingRefs.TRIO.joinToString("+") { ref ->
            card.of(ref.id).joinToString("/").ifEmpty { "∅" }
        }
}
