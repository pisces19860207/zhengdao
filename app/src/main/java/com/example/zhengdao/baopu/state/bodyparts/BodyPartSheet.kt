// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零五批 · 部位单：一条读数怎么写，一张单子怎么算齐。
//
// 这里落两条硬规矩：
// ① 每条读数必须带侧别（左／右／中）与姿态（放松／收紧／屈曲用力），空着进不来；
//    分左右的部位缺一侧就不成单——「左右」不是一个可选字段，是硬要求。
// ② 须给依据的档（乳头高凸、喉结明显起）缺依据就不成单。
//
// 读数自带来源与日期：构造时给了就用，没给就写「无来源」，不留空。
package com.example.zhengdao.baopu.state.bodyparts

/**
 * 一条部位读数。
 *
 * @param dim 维度（决定它属于哪个部位、有几档、要记哪几态）
 * @param side 左／右／中（中轴维度只收「中」）
 * @param posture 放松／收紧／屈曲用力
 * @param level 档序（只表先后，不是量）
 * @param basis 依据。须给依据的档（见目录表 basisFrom）留空即拒；不需依据的档可留空
 * @param source 来源。留空即按「无来源」记，不抛——给不出来源是事实，不掩盖
 * @param asOf 口径日期
 */
data class Reading(
    val dim: Dim,
    val side: Side,
    val posture: Posture,
    val level: Int,
    val basis: String? = null,
    val source: String = BODYPART_SOURCE,
    val asOf: String = BODYPART_AS_OF,
) {
    init {
        val spec = BodyPartCatalog.spec(dim)
        require(posture in spec.postures) { postureRule(spec) }
        require(if (spec.bilateral) side != Side.MIDLINE else side == Side.MIDLINE) { sideRule(spec) }
        require(level in 0..spec.orderMax) { rangeRule(spec, level) }
        require(!spec.needsBasis(level) || !basis.isNullOrBlank()) { basisRule(spec, level) }
        val b = basis.orEmpty()
        BodyPartGuard.rejectMeasures(b)
        BodyPartGuard.rejectAbsoluteColor(b)
        BodyPartGuard.rejectDiagnosis(b)
    }

    val part: BodyPart get() = BodyPartCatalog.spec(dim).part

    /** 档名。 */
    val label: String get() = BodyPartCatalog.words(dim)[level]

    /** 给不出来源就写「无来源」。 */
    val attributedSource: String get() = source.ifBlank { NO_SOURCE }

    /** 一行机读快照：部位·维度 侧／姿态=档名。 */
    fun line(): String = part.label + "·" + dim.label + " " + side.label + "／" + posture.label + "=" + label
}

/** 单子的键：一维在某一侧某一姿态下只许有一条读数。 */
private data class ReadingKey(val dim: Dim, val side: Side, val posture: Posture)

/**
 * 一张部位单。构造即校验，不完整的单子造不出来：
 * · 八部位、二十三维、每维的（侧 × 姿态）组合必须齐；
 * · 同一组合不许记两次。
 * 缺什么当场点名（哪部位、哪维、哪侧、哪态），不给「大概齐了」的余地。
 */
class BodyParts(readings: List<Reading>) {

    private val items: List<Reading> = readings.toList()
    private val byKey: Map<ReadingKey, Reading> =
        items.associateBy { ReadingKey(it.dim, it.side, it.posture) }

    init {
        require(items.isNotEmpty()) { EMPTY_REJECT }
        require(byKey.size == items.size) { duplicateRule(items) }
        val missing = missingCombos()
        require(missing.isEmpty()) { missingRule(missing) }
    }

    val size: Int get() = items.size

    fun all(): List<Reading> = items

    fun byPart(part: BodyPart): List<Reading> = items.filter { it.part == part }

    fun reading(dim: Dim, side: Side, posture: Posture): Reading =
        byKey.getValue(ReadingKey(dim, side, posture))

    /** 某维在某态下各侧的读数（左／右，或只有「中」）。 */
    fun sideReadings(dim: Dim, posture: Posture): Map<Side, Reading> =
        BodyPartCatalog.sidesOf(BodyPartCatalog.spec(dim))
            .associateWith { reading(dim, it, posture) }

    /** 某维在某态下各侧的档序，顺序＝侧别声明序。 */
    fun levels(dim: Dim, posture: Posture): List<Int> =
        sideReadings(dim, posture).values.map { it.level }

    /**
     * 一行对照快照。这是**唯一出口**：出口前过两道闸（长度／面积／百分比、诊断词），
     * 出口里出现单位或判定词就当场抛——边界②③不靠自觉。
     */
    fun line(): String {
        val text = BodyPart.entries.joinToString(SEP_PART) { part -> partLine(part) }
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
        return text
    }

    private fun partLine(part: BodyPart): String =
        part.label + PART_MARK + BodyPartCatalog.dimsOf(part).joinToString(SEP_DIM) { spec ->
            BodyPartCatalog.sidesOf(spec).joinToString(SEP_DIM) { side ->
                spec.postures.joinToString(SEP_DIM) { p ->
                    val r = reading(spec.dim, side, p)
                    spec.dim.label + "·" + side.label + "／" + p.label + "=" + r.label
                }
            }
        }

    private fun missingCombos(): List<String> = BodyPartCatalog.specs.flatMap { spec ->
        BodyPartCatalog.sidesOf(spec).flatMap { side ->
            spec.postures.filter { p -> !byKey.containsKey(ReadingKey(spec.dim, side, p)) }
                .map { p -> spec.part.label + "·" + spec.dim.label + " 缺" + side.label + "／" + p.label }
        }
    }

    companion object {
        const val EMPTY_REJECT: String = "部位单不能是空的：八部位各要给档"
        const val DUPLICATE_REJECT: String = "同一维同一侧同一姿态记了两次：两个真相"
        const val MISSING_PREFIX: String = "部位单不齐："
        const val SEP_PART: String = "；"
        const val SEP_DIM: String = "，"
        const val PART_MARK: String = "｜"

        fun of(vararg readings: Reading): BodyParts = BodyParts(readings.toList())

        private fun duplicateRule(items: List<Reading>): String {
            val seen = mutableSetOf<ReadingKey>()
            val dup = items.firstOrNull { !seen.add(ReadingKey(it.dim, it.side, it.posture)) }
            val where = dup?.let { it.part.label + "·" + it.dim.label + " " + it.side.label + "／" + it.posture.label }
            return DUPLICATE_REJECT + "（" + (where ?: "") + "）"
        }

        private fun missingRule(missing: List<String>): String =
            MISSING_PREFIX + missing.size + " 条。" + missing.joinToString("；")
    }
}

// ── 拒收消息：一句话说清「这一维该怎么记」────────────────────────────

private fun sideRule(spec: DimSpec): String =
    if (spec.bilateral) {
        spec.part.label + "·" + spec.dim.label + "：分左右两侧，须记左或右（中轴只给喉结与对称档）"
    } else {
        spec.part.label + "·" + spec.dim.label + "：中轴维度，侧别只记「中」"
    }

private fun postureRule(spec: DimSpec): String =
    spec.part.label + "·" + spec.dim.label + "：不记这一态。应记 " +
        spec.postures.joinToString("／") { it.label }

private fun rangeRule(spec: DimSpec, level: Int): String =
    spec.part.label + "·" + spec.dim.label + "：档序 " + level + " 越界（0–" + spec.orderMax + "）"

private fun basisRule(spec: DimSpec, level: Int): String =
    spec.part.label + "·" + spec.dim.label + "：" + spec.words[level] + " 档须给依据"
