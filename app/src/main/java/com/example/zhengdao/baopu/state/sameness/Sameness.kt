// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu.state.sameness

import com.example.zhengdao.baopu.state.casting.CardGroup
import com.example.zhengdao.baopu.state.casting.CastingCard
import com.example.zhengdao.baopu.state.casting.CastingRefs
import com.example.zhengdao.baopu.state.casting.FieldRef
import com.example.zhengdao.baopu.state.casting.RefSource

enum class DiffKind { DIFFERENT, PARTIAL, ABSENT }

enum class Significance(val rank: Int) { NONE(0), LOW(1), MEDIUM(2), HIGH(3) }

data class DiffItem(
    val fieldId: String,
    val fieldName: String,
    val left: List<String>,
    val right: List<String>,
    val kind: DiffKind,
    val significance: Significance,
    val distinguishable: Boolean,
) {
    fun line(): String =
        "$fieldId|$fieldName|${kind.name}|${significance.name}|${tierText(left)}|${tierText(right)}|$distinguishable"
}

data class SourceNote(
    val fieldId: String,
    val fieldName: String,
    val source: RefSource,
    val left: List<String>,
    val right: List<String>,
    val allowed: List<String>,
) {
    fun line(): String =
        "$fieldId|$fieldName|${source.name}|${tierText(left)}|${tierText(right)}|${tierText(allowed)}"
}

data class UnseenCondition(
    val index: Int,
    val distinguishableCount: Int,
) {
    val belowIndexThreshold: Boolean get() = index < UNSEEN_INDEX_THRESHOLD
    val hasEnoughDifferences: Boolean get() = distinguishableCount >= UNSEEN_MIN_DIFFERENCES
    val holds: Boolean get() = belowIndexThreshold && hasEnoughDifferences

    fun line(): String =
        "847|index=$index|<$UNSEEN_INDEX_THRESHOLD=$belowIndexThreshold|" +
            "可辨差异=$distinguishableCount|≥$UNSEEN_MIN_DIFFERENCES=$hasEnoughDifferences|同时满足=$holds"
}

data class SamenessObservation(
    val index: Int,
    val items: List<DiffItem>,
    val notes: List<SourceNote>,
    val unseen: UnseenCondition,
) {
    fun line(): String = buildString {
        append("index=").append(index).append('\n')
        items.forEach { append(it.line()).append('\n') }
        notes.forEach { append("source|").append(it.line()).append('\n') }
        append(unseen.line())
    }
}

internal enum class CountBand { ZERO, ONE, TWO, THREE, FOUR, FIVE_PLUS }

internal data class ScoreRow(
    val count: CountBand,
    val none: Int,
    val low: Int,
    val medium: Int,
    val high: Int,
)

internal val SAMENESS_SCORE_TABLE: List<ScoreRow> = listOf(
    ScoreRow(CountBand.ZERO, 100, 100, 100, 100),
    ScoreRow(CountBand.ONE, 100, 82, 72, 62),
    ScoreRow(CountBand.TWO, 100, 68, 52, 42),
    ScoreRow(CountBand.THREE, 100, 56, 38, 28),
    ScoreRow(CountBand.FOUR, 100, 44, 28, 18),
    ScoreRow(CountBand.FIVE_PLUS, 100, 32, 20, 12),
)

const val UNSEEN_INDEX_THRESHOLD = 40
const val UNSEEN_MIN_DIFFERENCES = 2

private const val ABSENT_INDEX_CAP = 60
private const val NO_COMPARABLE_INDEX = 50

fun diffItems(a: CastingCard, b: CastingCard): List<DiffItem> =
    CastingRefs.CARD_FIELDS.mapNotNull { ref -> compareField(a, b, ref) }

fun samenessIndex(a: CastingCard, b: CastingCard): Int {
    val comparableCount = CastingRefs.CARD_FIELDS.count { a.has(it.id) && b.has(it.id) }
    if (comparableCount == 0) return NO_COMPARABLE_INDEX
    val items = diffItems(a, b)
    val scored = items.filter { it.kind != DiffKind.ABSENT }
    val significance = scored.maxByOrNull { it.significance.rank }?.significance ?: Significance.NONE
    val raw = tableScore(countBandOf(scored.size), significance)
    val hasAbsentEvidence = items.any {
        it.kind == DiffKind.ABSENT && CastingRefs.field(it.fieldId).source != RefSource.NONE
    }
    return if (hasAbsentEvidence) minOf(raw, ABSENT_INDEX_CAP) else raw
}

fun sourceNote(item: DiffItem): SourceNote {
    val ref = CastingRefs.field(item.fieldId)
    return SourceNote(
        fieldId = ref.id,
        fieldName = ref.cn,
        source = ref.source,
        left = item.left,
        right = item.right,
        allowed = ref.words(),
    )
}

fun unseenCondition(a: CastingCard, b: CastingCard): UnseenCondition {
    val items = diffItems(a, b)
    return UnseenCondition(
        index = samenessIndex(a, b),
        distinguishableCount = items.count { it.distinguishable },
    )
}

fun observe(a: CastingCard, b: CastingCard): SamenessObservation {
    val items = diffItems(a, b)
    return SamenessObservation(
        index = samenessIndex(a, b),
        items = items,
        notes = items.map(::sourceNote),
        unseen = unseenCondition(a, b),
    )
}

private fun compareField(a: CastingCard, b: CastingCard, ref: FieldRef): DiffItem? {
    val left = tiers(a.of(ref.id))
    val right = tiers(b.of(ref.id))
    if (left.isEmpty() || right.isEmpty()) return absent(ref, left, right)
    if (left == right) return null
    val partial = left.any { it in right }
    return DiffItem(
        fieldId = ref.id,
        fieldName = ref.cn,
        left = left,
        right = right,
        kind = if (partial) DiffKind.PARTIAL else DiffKind.DIFFERENT,
        significance = significanceOf(ref),
        distinguishable = !partial,
    )
}

private fun absent(ref: FieldRef, left: List<String>, right: List<String>): DiffItem =
    DiffItem(
        fieldId = ref.id,
        fieldName = ref.cn,
        left = left,
        right = right,
        kind = DiffKind.ABSENT,
        significance = significanceOf(ref),
        distinguishable = false,
    )

private fun significanceOf(ref: FieldRef): Significance = when {
    ref.id in CastingRefs.TRIO.map { it.id } -> Significance.HIGH
    ref.group == CardGroup.FACE || ref.group == CardGroup.BODY ||
        ref.group == CardGroup.HAIR || ref.group == CardGroup.MARK -> Significance.MEDIUM
    else -> Significance.LOW
}

private fun countBandOf(count: Int): CountBand = when (count) {
    0 -> CountBand.ZERO
    1 -> CountBand.ONE
    2 -> CountBand.TWO
    3 -> CountBand.THREE
    4 -> CountBand.FOUR
    else -> CountBand.FIVE_PLUS
}

private fun tableScore(count: CountBand, significance: Significance): Int {
    val row = SAMENESS_SCORE_TABLE.first { it.count == count }
    return when (significance) {
        Significance.NONE -> row.none
        Significance.LOW -> row.low
        Significance.MEDIUM -> row.medium
        Significance.HIGH -> row.high
    }
}

private fun tiers(values: List<String>): List<String> = values.distinct().sorted()

private fun tierText(values: Collection<String>): String =
    if (values.isEmpty()) "absent" else values.sorted().joinToString("+")
