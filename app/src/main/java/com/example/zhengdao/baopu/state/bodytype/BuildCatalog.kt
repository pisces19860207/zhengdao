// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十五批 · 体型目录表：三轴组合的唯一真相，五档到 660 截面的唯一映射。
//
// 这张表是**唯一真相**：组合从三个枚举叉乘生成，不手抄 36 行；常见程度与少见原因
// 按规则落档，构造时把「组合缺/重、常见程度全同、少见不足三例、低脂高肌不标少见、
// 映射不单调」全部拦下。枚举改了表跟着改，不出现第二份。
//
// 两个数要盯住（改档就破）：组合 3×4×3=36；少见原因至少 3 条且各非空。
package com.example.zhengdao.baopu.state.bodytype

import com.example.zhengdao.baopu.state.bodyparts.FatBand

/** 一个三轴组合（骨架 × 体脂 × 肌量）。修饰维不进组合——状态不打分。 */
data class ComboKey(
    val skeleton: SkeletonTier,
    val fat: BodyFatTier,
    val muscle: MuscleTier,
) {
    val label: String get() = skeleton.label + "·" + fat.label + "·" + muscle.label

    /** 是不是「低脂高肌」（体脂低档且肌量高档）。默认纠偏盯的就是它。 */
    val lowFatHighMuscle: Boolean
        get() = fat == BodyFatTier.LOW && muscle == MuscleTier.AMPLE
}

/** 一条组合记录：常见程度＋原因（少见必给一句为什么少见）。 */
data class ComboEntry(
    val key: ComboKey,
    val commonness: Commonness,
    val why: String,
) {
    init {
        require(why.isNotBlank()) { key.label + "：常见程度不能没有理由" }
        BodyPartGuardBridge.rejectValue(why)
    }
}

/** 体型目录表。 */
object BuildCatalog {

    /** 36 个组合，表驱动叉乘生成（顺序固定：骨架→体脂→肌量）。 */
    val COMBOS: List<ComboKey> = buildList {
        SkeletonTier.entries.forEach { s ->
            BodyFatTier.entries.forEach { f ->
                MuscleTier.entries.forEach { m -> add(ComboKey(s, f, m)) }
            }
        }
    }

    /** 少见原因：按规则落，不逐条手抄。低脂高肌必标少见并给依据要求。 */
    private fun reasonOf(key: ComboKey): String = when {
        key.lowFatHighMuscle ->
            "体脂压到低档还维持肌量高档，人群里少见；要职业／训练史／照片级描述之一才立得住"
        key.fat == BodyFatTier.VERY_HIGH && key.muscle == MuscleTier.SCANT ->
            "体脂很高又肌量少，多见于活动受限或热量长期盈余，不是人群多数"
        key.skeleton == SkeletonTier.WIDE && key.fat == BodyFatTier.LOW ->
            "宽骨架却体脂低，肩背骨性标志本就被脂肪覆盖得少，组合不算常见"
        else -> "介于常见两端之间，属于一般分布"
    }

    /** 常见程度：三档都要出现，且不许把低脂高肌当常见。 */
    private fun commonnessOf(key: ComboKey): Commonness = when {
        key.lowFatHighMuscle -> Commonness.RARE
        key.fat == BodyFatTier.MID && key.muscle == MuscleTier.MID -> Commonness.COMMON
        key.skeleton == SkeletonTier.MID && key.fat == BodyFatTier.MID -> Commonness.COMMON
        key.fat == BodyFatTier.VERY_HIGH && key.muscle == MuscleTier.SCANT -> Commonness.RARE
        key.fat == BodyFatTier.LOW && key.muscle == MuscleTier.SCANT -> Commonness.USUAL
        else -> Commonness.USUAL
    }

    /** 组合 → 记录（唯一真相）。 */
    val ENTRIES: Map<ComboKey, ComboEntry> = COMBOS.associateWith { key ->
        ComboEntry(key, commonnessOf(key), reasonOf(key))
    }

    /** 少见组合，供判据⑤点名。 */
    val RARE_ENTRIES: List<ComboEntry> = ENTRIES.values.filter { it.commonness == Commonness.RARE }

    /** 默认组合：骨架中／体脂中／肌量中。纠偏的锚。 */
    val DEFAULT_COMBO: ComboKey = ComboKey(SkeletonTier.MID, BodyFatTier.MID, MuscleTier.MID)

    // ── 三轴 → 660 截面的档对档映射（单调，不读数值）────────────────────

    /** 骨架档 → 截面宽档：窄/中/宽一一对应，不改档数。 */
    fun widthOf(skeleton: SkeletonTier): SectionGrade = when (skeleton) {
        SkeletonTier.NARROW -> SectionGrade.NARROW
        SkeletonTier.MID -> SectionGrade.MID
        SkeletonTier.WIDE -> SectionGrade.WIDE
    }

    /** 体脂档 → 截面厚档：四档并到三档，高与很高同落「宽（厚）」——封顶，不新造档。 */
    fun thicknessOf(fat: BodyFatTier): SectionGrade = when (fat) {
        BodyFatTier.LOW -> SectionGrade.NARROW
        BodyFatTier.MID -> SectionGrade.MID
        BodyFatTier.HIGH, BodyFatTier.VERY_HIGH -> SectionGrade.WIDE
    }

    /** 肌量档 → 截面围度档：少/中/多一一对应。 */
    fun girthOf(muscle: MuscleTier): SectionGrade = when (muscle) {
        MuscleTier.SCANT -> SectionGrade.NARROW
        MuscleTier.MID -> SectionGrade.MID
        MuscleTier.AMPLE -> SectionGrade.WIDE
    }

    /** 体脂档 → 805 的 FatBand（档对档映射，供下游复用；不新造体脂档）。 */
    fun mapToFatBand(fat: BodyFatTier): FatBand = when (fat) {
        BodyFatTier.LOW -> FatBand.LEAN
        BodyFatTier.MID -> FatBand.MODERATE
        BodyFatTier.HIGH -> FatBand.HIGHER
        BodyFatTier.VERY_HIGH -> FatBand.HIGHER
    }

    init {
        require(COMBOS.size == EXPECTED_COMBOS) { "组合应为 $EXPECTED_COMBOS 个，实为 ${COMBOS.size}" }
        require(COMBOS.toSet().size == COMBOS.size) { "组合重复：两个真相" }
        require(ENTRIES.size == COMBOS.size) { "组合表漏项" }
        val degrees = ENTRIES.values.map { it.commonness }.toSet()
        require(degrees.size == Commonness.COUNT) {
            "常见程度必须三档齐全，实为 " + degrees.joinToString("／") { it.label }
        }
        require(RARE_ENTRIES.size >= MIN_RARE) {
            "少见组合至少 $MIN_RARE 例，实为 ${RARE_ENTRIES.size}"
        }
        require(RARE_ENTRIES.all { it.why.isNotBlank() }) { "少见组合必须各给一句为什么少见" }
        val lfh = ENTRIES.getValue(defaultLowFatHighMuscleKey())
        require(lfh.commonness == Commonness.RARE) { "低脂高肌必须标少见，不能当常见" }
        require(ENTRIES.getValue(DEFAULT_COMBO).commonness == Commonness.COMMON) {
            "默认中中中必须是常见档，纠偏才成立"
        }
        // 映射单调：三档输入升档，输出档不许降。
        require(widthOf(SkeletonTier.NARROW).level < widthOf(SkeletonTier.MID).level &&
            widthOf(SkeletonTier.MID).level < widthOf(SkeletonTier.WIDE).level) {
            "骨架→截面宽映射必须单调"
        }
        require(girthOf(MuscleTier.SCANT).level < girthOf(MuscleTier.MID).level &&
            girthOf(MuscleTier.MID).level < girthOf(MuscleTier.AMPLE).level) {
            "肌量→围度映射必须单调"
        }
        require(thicknessOf(BodyFatTier.LOW).level <= thicknessOf(BodyFatTier.MID).level &&
            thicknessOf(BodyFatTier.MID).level <= thicknessOf(BodyFatTier.VERY_HIGH).level) {
            "体脂→截面厚映射必须单调不降"
        }
        require(mapToFatBand(BodyFatTier.LOW).level <= mapToFatBand(BodyFatTier.HIGH).level) {
            "体脂→805 FatBand 映射必须单调不降"
        }
    }

    const val EXPECTED_COMBOS: Int = 36
    const val MIN_RARE: Int = 3

    private fun defaultLowFatHighMuscleKey(): ComboKey = ComboKey(
        SkeletonTier.MID, BodyFatTier.LOW, MuscleTier.AMPLE,
    )

    fun entryOf(skeleton: SkeletonTier, fat: BodyFatTier, muscle: MuscleTier): ComboEntry =
        ENTRIES.getValue(ComboKey(skeleton, fat, muscle))

    /** 每个常见程度各举一例的快照（给人核对「不许全同」用）。 */
    fun degreeSamples(): String = Commonness.entries.joinToString("；") { degree ->
        val one = ENTRIES.values.first { it.commonness == degree }
        degree.label + "＝" + one.key.label
    }
}

/**
 * 评价词闸的薄封装：目录层要在构造期挡住少见原因里的评价词，
 * 但真正的闸在 [BuildGuard]——这里只做一次转调，闸只有一套口径。
 */
private object BodyPartGuardBridge {
    fun rejectValue(text: String) = BuildGuard.rejectValueWords(text)
}
