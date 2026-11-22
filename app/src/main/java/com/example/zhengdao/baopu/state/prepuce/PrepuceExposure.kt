// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零四批 · 包皮专批②：完全露出四型（带 retractState）＋疲软露出规则表。
//
// 口径四条：
// · 写「完全露出」必须给 retractState：可完全回缩／部分回缩／不可回缩（判据③）。不给即红。
// · 环切后＝没有可来回推的包皮 ⇒ retractState 只能是「不可回缩」；写「可来回推」即红（判据③）。
// · 状态性全露（仅勃起）⇒ 疲软时必然回盖 ⇒ 皮须「可完全回缩」（延伸口径 R3，见交付报告）。
// · 疲软露出＝四轴查表（皮量 × 长短 × 可否回缩 × 环切）＋两条调制（冷／紧张升一档、热／松弛降一档），
//   每条结论都带口径行号与理由；表外组合（皮量不足＋不可回缩、已环切＋可回缩）当场红，不给依据不成立。
//
// 表只产「预期档」，绝不产位图：位图只能逐区观察记录（PrepuceCoverage 里禁反推）。
package com.example.zhengdao.baopu.state.prepuce

/** 可否回缩。三档，缺一不可（判据③）。 */
enum class RetractState(val label: String) {
    FULL("可完全回缩"),
    PARTIAL("部分回缩"),
    NONE("不可回缩"),
}

/** 完全露出四型：为什么露。与「露多少」是两轴，不复用面积档。 */
enum class ExposeType(val label: String) {
    POST_CIRCUMCISION("环切后"),
    NATURAL("自然全露"),
    ERECTION_ONLY("状态性全露（仅勃起）"),
    MANUAL_UNRESTORED("手动未复位"),
}

/** 一条「完全露出」断言。构造即守：retractState 必给、环切后不可回推、结论不带量。 */
data class ExposeClaim private constructor(
    val type: ExposeType,
    val retract: RetractState,
    val note: String,
) {
    init {
        val required = when (type) {
            ExposeType.POST_CIRCUMCISION -> RetractState.NONE
            ExposeType.ERECTION_ONLY -> RetractState.FULL
            ExposeType.NATURAL, ExposeType.MANUAL_UNRESTORED -> retract
        }
        val message = when (type) {
            ExposeType.POST_CIRCUMCISION -> CIRCUMCISED_RETRACT
            ExposeType.ERECTION_ONLY -> ERECTION_RETRACT
            else -> RETRACT_REQUIRED
        }
        require(retract == required) { message }
        PrepuceGuard.rejectMeasures(note)
    }

    fun line(): String = "${type.label}|${retract.label}|$note"

    companion object {
        const val RETRACT_REQUIRED: String =
            "写「完全露出」必须给 retractState（可完全回缩／部分回缩／不可回缩）——不给即红"
        const val CIRCUMCISED_RETRACT: String =
            "环切后没有可来回推的包皮：retractState 只能是「不可回缩」，写「可来回推」即红"
        const val ERECTION_RETRACT: String =
            "状态性全露只在勃起时成立 ⇒ 疲软必回盖 ⇒ 皮须「可完全回缩」"

        /** 唯一入口：retractState 为 null 时按判据③当场红。 */
        fun of(type: ExposeType, retract: RetractState?, note: String = ""): ExposeClaim {
            require(retract != null) { RETRACT_REQUIRED }
            return ExposeClaim(type, retract, note)
        }
    }
}

/** 环切与否。来源是 660 的 `g.prepuce` 档「已割／其余三档」（[PrepuceGrade] 只做字面映射）。 */
enum class Circumcision(val label: String) {
    KEPT("未环切"),
    DONE("已环切"),
}

/** 皮量（疲软时包皮的实际多少，不看长短）。 */
enum class SkinAmount(val label: String) {
    SPARSE("皮量不足"),
    MODERATE("皮量适中"),
    AMPLE("皮量富余"),
}

/** 疲软长短：同一个皮量，阴茎偏长则相对盖得少（降一档），偏短则相对盖得多（升一档）。 */
enum class FlaccidSize(val label: String, val shift: Int) {
    SHORT("疲软偏短", -1),
    MEDIUM("疲软中等", 0),
    LONG("疲软偏长", 1),
}

/** 调制：冷／紧张 ⇒ 覆盖倾向升一档；热／松弛 ⇒ 降一档。 */
enum class Modulation(val label: String, val shift: Int) {
    COLD_TENSE("冷／紧张", -1),
    NONE("无调制", 0),
    WARM_RELAXED("热／松弛", 1),
}

/** 查表输入四轴＋调制。四轴缺一不可（Kotlin 非空类型即强制）。 */
data class FlaccidInput(
    val skin: SkinAmount,
    val size: FlaccidSize,
    val retract: RetractState,
    val circumcision: Circumcision,
    val modulation: Modulation = Modulation.NONE,
)

/** 查表结论：落在哪一档、命中哪一行、理由是什么。三者缺一即红（判据④）。 */
data class FlaccidPrediction(val level: AreaLevel, val rowId: String, val basis: String) {
    init {
        require(rowId.isNotBlank() && basis.isNotBlank()) { BASIS_REQUIRED }
        PrepuceGuard.rejectMeasures(basis)
    }

    fun line(): String = "$rowId|${level.label}|$basis"

    companion object {
        const val BASIS_REQUIRED: String =
            "疲软露出必须给依据（口径行号＋两条修正）：落表外或不给依据即红"
    }
}

/** 疲软露出规则表：一行一条口径，[id] 可回查本文件与交付报告 §三。 */
object FlaccidTable {

    /** 表内一行。 */
    private data class Row(
        val id: String,
        val skin: SkinAmount,
        val retract: RetractState,
        val level: AreaLevel,
        val why: String,
    )

    const val CIRCUMCISED_RETRACT: String =
        "已环切却给了可回缩状态：环切后没有可推的皮，retractState 只能是「不可回缩」"
    const val SKIN_RETRACT_CONFLICT: String =
        "皮量不足却说不可回缩：皮不够就包不住，也谈不上翻不开——口径矛盾，落表外不收"

    private const val CIRCUMCISED_ROW = "F0"

    /**
     * 疲软表最大序数（完全露出）＝4。
     * 831 补了「退至冠后」这一档（覆盖序 4），但它由位图专判、不进疲软表；
     * 这里取 flaccidOrder 的最大值而非覆盖序最大值，804 的 162 组合读数逐项不变。
     */
    private val LAST_ORDER = AreaLevel.entries.maxOf { it.flaccidOrder }

    /** 环切独占一行：皮量／长短／回缩都不再决定露多少。 */
    private val CIRCUMCISED = Row(
        CIRCUMCISED_ROW, SkinAmount.SPARSE, RetractState.NONE, AreaLevel.FULLY_EXPOSED,
        "已环切：无包皮可盖，龟头常年全露",
    )

    /** 未环切九格（皮量三 × 回缩三，皮量不足＋不可回缩这一格是矛盾，不在表内）。 */
    private val ROWS = listOf(
        Row("F1", SkinAmount.AMPLE, RetractState.NONE, AreaLevel.FULL_COVER,
            "皮量富余＋不可回缩：整圈盖住且翻不开"),
        Row("F2", SkinAmount.MODERATE, RetractState.NONE, AreaLevel.ALMOST_ALL,
            "皮量适中＋不可回缩：盖住大部分，只露一点"),
        Row("F3", SkinAmount.AMPLE, RetractState.PARTIAL, AreaLevel.ALMOST_ALL,
            "皮量富余＋部分回缩：只能翻开一部分"),
        Row("F4", SkinAmount.MODERATE, RetractState.PARTIAL, AreaLevel.OVER_HALF,
            "皮量适中＋部分回缩：翻开约一半"),
        Row("F5", SkinAmount.SPARSE, RetractState.PARTIAL, AreaLevel.CORONA_ONLY,
            "皮量不足＋部分回缩：翻不全，只剩冠与沟那圈盖着"),
        Row("F6", SkinAmount.AMPLE, RetractState.FULL, AreaLevel.FULL_COVER,
            "皮量富余＋可完全回缩：疲软常态仍整圈盖住"),
        Row("F7", SkinAmount.MODERATE, RetractState.FULL, AreaLevel.ALMOST_ALL,
            "皮量适中＋可完全回缩：平时盖住大部分"),
        Row("F8", SkinAmount.SPARSE, RetractState.FULL, AreaLevel.FULLY_EXPOSED,
            "皮量不足＋可完全回缩：翻起后不回盖，龟头常年全露"),
    )

    /**
     * 查表。先拦两个落表外的矛盾组合，再取行，最后叠两条修正并钳在五档内。
     * @throws IllegalArgumentException 已环切＋可回缩、皮量不足＋不可回缩（判据④）。
     */
    fun predict(input: FlaccidInput): FlaccidPrediction {
        guard(input)
        val row = if (input.circumcision == Circumcision.DONE) CIRCUMCISED else rowOf(input)
        return FlaccidPrediction(levelOf(row, input), row.id, basis(row, input))
    }

    private fun guard(input: FlaccidInput) {
        require(input.circumcision != Circumcision.DONE || input.retract == RetractState.NONE) {
            CIRCUMCISED_RETRACT
        }
        // 只对未环切查这条：环切后本来就是「皮量不足＋不可回缩」，那是常态不是矛盾。
        require(input.circumcision == Circumcision.DONE ||
            input.skin != SkinAmount.SPARSE || input.retract != RetractState.NONE) {
            SKIN_RETRACT_CONFLICT
        }
    }

    private fun rowOf(input: FlaccidInput): Row = ROWS.first { row ->
        row.skin == input.skin && row.retract == input.retract
    }

    /**
     * 行基准档 ＋ 长短修正 ＋ 调制修正，钳在五档内。
     * 环切那行不叠修正：没有包皮就没有「盖多盖少」，长短冷热动不了它。
     */
    private fun levelOf(row: Row, input: FlaccidInput): AreaLevel {
        if (row.id == CIRCUMCISED_ROW) return row.level
        val shifted = (row.level.flaccidOrder + input.size.shift + input.modulation.shift)
            .coerceIn(0, LAST_ORDER)
        return AreaLevel.ofFlaccidOrder(shifted)
    }

    private fun basis(row: Row, input: FlaccidInput): String {
        val level = levelOf(row, input)
        if (row.id == CIRCUMCISED_ROW) {
            return "${row.id}（${row.why}）＋环切后不叠长短与冷热修正＝${level.label}"
        }
        return buildString {
            append(row.id).append("（").append(row.why).append("）")
            append("＋长短").append(input.size.label).append(shiftText(input.size.shift))
            append("＋").append(input.modulation.label).append(shiftText(input.modulation.shift))
            append("＝").append(level.label)
        }
    }

    private fun shiftText(shift: Int): String = when {
        shift < 0 -> "升一档"
        shift > 0 -> "降一档"
        else -> "不动"
    }
}

/**
 * 660 `g.prepuce` 的四档 → 环切与否。只做字面映射，**档表不在本包重立**（真源＝`AnchorSpecTable`）。
 */
object PrepuceGrade {

    const val UNKNOWN_REJECT: String =
        "660 的 g.prepuce 只有四档（未割半包／未割全包能翻／过长／已割），表外词不收"

    fun circumcisionOf(prepuceGrade: String): Circumcision = when (prepuceGrade.trim()) {
        "已割" -> Circumcision.DONE
        "未割半包", "未割全包能翻", "过长" -> Circumcision.KEPT
        else -> throw IllegalArgumentException("$UNKNOWN_REJECT（收到「$prepuceGrade」）")
    }
}
