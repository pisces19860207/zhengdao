// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十五批 · 骨点目录表：哪十四个点、每点三项怎么取。
//
// 这张表是**唯一真相**：点位、浅深、显露方位与推导表只写一次。
// 补丁二：体脂档直接接收 815 的 BodyFatTier，本包零自造枚举；
// 方位直接复用 860 的 Octant 八向闭集，不自造第九向。
// 补丁一/七：骨段与关节归 900，本表没有骨段、没有关节、没有骨骼几何。
// 补丁五/六：每点标 zoneId/zoneName，zoneId 与 subzoneId 分字段，子区只认 775 的 14 code。
package com.example.zhengdao.baopu.state.frame

import com.example.zhengdao.baopu.state.bodytype.BodyFatTier
import com.example.zhengdao.baopu.state.subzone.SubzoneId
import com.example.zhengdao.baopu.state.view.Octant

/**
 * 一处骨点。这些字段就是对外接口，逐字冻结：
 * id（枚举名）/ label / region / relative / exposure / sided / zoneId / zoneName / subzoneId。
 *
 * [relative] 取 860 的八向闭集，含义是「从哪一面最容易显露或触及」，
 * 以身体正前为基准；左右成对结构用 [sided] 标，不把侧别混进方位档。
 *
 * 补丁五：[zoneId] 只取 13 区真名，躯干四肢不塞进那张 genital 词表 ⇒ `none` + [zoneName]；
 * 补丁六：[subzoneId] 与 [zoneId] **分两个字段**，非空时必须能被 775 的 [SubzoneId] 解析；
 * 本单不指认具体子区，故当前全为 null（空表＝「本单没有子区账」，不是「它没有子区」）。
 */
enum class FramePoint(
    val label: String,
    val region: FrameRegion,
    val relative: Octant,
    val exposure: FrameExposure,
    val sided: Boolean,
    val zoneId: String,
    val zoneName: String,
    val subzoneId: String?,
) {
    CLAVICLE("锁骨", FrameRegion.UPPER, Octant.FRONT, FrameExposure.SUPERFICIAL, true,
        FRAME_ZONE_NONE, ZONE_TRUNK, null),
    STERNAL_ANGLE("胸骨角", FrameRegion.UPPER, Octant.FRONT, FrameExposure.SUPERFICIAL, false,
        FRAME_ZONE_NONE, ZONE_TRUNK, null),
    ACROMION("肩峰", FrameRegion.UPPER, Octant.FRONT, FrameExposure.SUPERFICIAL, true,
        FRAME_ZONE_NONE, ZONE_TRUNK, null),
    ILIAC_CREST("髂嵴(左右)", FrameRegion.UPPER, Octant.FRONT, FrameExposure.MID, true,
        FRAME_ZONE_NONE, ZONE_TRUNK, null),
    SACRUM("骶骨", FrameRegion.UPPER, Octant.BACK, FrameExposure.MID, false,
        FRAME_ZONE_NONE, ZONE_TRUNK, null),
    COSTAL_MARGIN("肋弓下缘", FrameRegion.UPPER, Octant.FRONT, FrameExposure.MID, true,
        FRAME_ZONE_NONE, ZONE_TRUNK, null),

    GREATER_TROCHANTER("大转子", FrameRegion.LOWER, Octant.FRONT, FrameExposure.MID, true,
        FRAME_ZONE_NONE, ZONE_LEG_FOOT, null),
    PATELLA("髌骨", FrameRegion.LOWER, Octant.FRONT, FrameExposure.SUPERFICIAL, false,
        FRAME_ZONE_NONE, ZONE_LEG_FOOT, null),
    FIBULAR_HEAD("腓骨头", FrameRegion.LOWER, Octant.FRONT, FrameExposure.MID, true,
        FRAME_ZONE_NONE, ZONE_LEG_FOOT, null),
    ANKLE_MALLEOLI("内踝外踝", FrameRegion.LOWER, Octant.FRONT, FrameExposure.SUPERFICIAL, true,
        FRAME_ZONE_NONE, ZONE_LEG_FOOT, null),
    CALCANEUS("跟骨", FrameRegion.LOWER, Octant.BACK, FrameExposure.SUPERFICIAL, false,
        FRAME_ZONE_NONE, ZONE_LEG_FOOT, null),

    MANDIBULAR_ANGLE("下颌角", FrameRegion.HEAD_NECK, Octant.FRONT, FrameExposure.MID, true,
        FRAME_ZONE_NONE, ZONE_HEAD, null),
    MASTOID("乳突", FrameRegion.HEAD_NECK, Octant.BACK, FrameExposure.MID, true,
        FRAME_ZONE_NONE, ZONE_HEAD, null),
    SUPRAORBITAL_RIDGE("眉弓", FrameRegion.HEAD_NECK, Octant.FRONT, FrameExposure.SUPERFICIAL, true,
        FRAME_ZONE_NONE, ZONE_HEAD, null),
    ;

    companion object {
        const val COUNT = 14
    }
}

/**
 * 一条参照锚示例：「某点｜距另一点 一档／两档（方向）」。
 * [step] 是**相差几档**，不是尺寸；下游照此写「肩峰距锁骨 N 档」。
 */
data class AnchorExample(
    val target: FramePoint,
    val reference: FramePoint,
    val step: AnchorStep,
    val direction: String,
) {
    init {
        require(target != reference) { "锚示例不能自己距自己" }
        require(direction.isNotBlank()) { "锚示例缺方向：下游照抄会写反" }
    }

    fun line(): String =
        target.label + "｜距" + reference.label + " " + step.label + "（" + direction + "）"
}

/** 骨点目录表。唯一真相，构造期自检覆盖与档序。 */
object FrameCatalog {

    const val MIN_POINTS: Int = 10
    const val MAX_POINTS: Int = 14

    val POINTS: List<FramePoint> = FramePoint.entries

    /**
     * 补丁八：报告里的下游引用示例同步落表，避免文档与代码各写一套。
     * 每条都点名已冻结点与具体下游；「两档」是档数不是尺寸。
     */
    val DOWNSTREAM_EXAMPLES: List<String> = listOf(
        "肩峰｜距锁骨 两档（上方）→ 869 外观卡 bone.acromion",
        "髂嵴｜对 815 体脂敏感：高体脂→隐约 → 866 体表地形／869 外观卡",
        "髌骨｜距大转子 两档（下方）→ 814 下体形状／869 外观卡",
        "乳突｜相对锁骨居后 → 860 视点取景／869 外观卡",
    )

    /** 报告与判据③共用的引用示例；每一条点名两个已冻结点。 */
    val ANCHOR_EXAMPLES: List<AnchorExample> = listOf(
        AnchorExample(FramePoint.ACROMION, FramePoint.CLAVICLE, AnchorStep.ONE, "上方"),
        AnchorExample(FramePoint.STERNAL_ANGLE, FramePoint.CLAVICLE, AnchorStep.ONE, "下方"),
        AnchorExample(FramePoint.COSTAL_MARGIN, FramePoint.STERNAL_ANGLE, AnchorStep.TWO, "下方"),
        AnchorExample(FramePoint.PATELLA, FramePoint.GREATER_TROCHANTER, AnchorStep.TWO, "下方"),
        AnchorExample(FramePoint.ANKLE_MALLEOLI, FramePoint.PATELLA, AnchorStep.TWO, "下方"),
        AnchorExample(FramePoint.MANDIBULAR_ANGLE, FramePoint.CLAVICLE, AnchorStep.TWO, "上方"),
    )

    init {
        require(POINTS.size in MIN_POINTS..MAX_POINTS) {
            "点数须在 $MIN_POINTS..$MAX_POINTS，实为 " + POINTS.size
        }
        require(POINTS.size == FramePoint.COUNT) { "点数与冻结表不一致" }
        require(FrameRegion.entries.all { region -> POINTS.any { it.region == region } }) {
            "三群各要有骨点，不能整群缺失"
        }
        require(POINTS.all { it.label.none { c -> c.isDigit() } }) {
            "点名不得带数字：档数归档，不把尺寸塞进名字"
        }
        require(POINTS.all { it.zoneId.isNotBlank() && it.zoneName.isNotBlank() }) {
            "补丁五：每点都要标 zoneId 与人读名，缺一即不许出门"
        }
        require(POINTS.all { point ->
            point.subzoneId == null || runCatching { SubzoneId.ofCode(point.subzoneId!!) }.isSuccess
        }) {
            "补丁六：subzoneId 非空时必须能被 775 的十四个 code 解析，不许改名或新增"
        }
        require(Octant.SIZE == 8) { "八向闭集被改了：方位档不再是八向" }
        require(ANCHOR_EXAMPLES.size >= 3) { "锚示例不足三条：下游不会照着用" }
        require(DOWNSTREAM_EXAMPLES.size >= 3 && DOWNSTREAM_EXAMPLES.all { it.contains("→") }) {
            "补丁八：下游引用示例不足三条或没点名消费方"
        }
        require(ANCHOR_EXAMPLES.map { it.line() }.toSet().size == ANCHOR_EXAMPLES.size) {
            "锚示例重复"
        }
        require(VisibleGrade.entries.map { it.level } == VisibleGrade.entries.indices.toList()) {
            "可见档序必须 0..3 连续"
        }
        require(PalpableGrade.entries.map { it.level } == PalpableGrade.entries.indices.toList()) {
            "触及档序必须 0..3 连续"
        }
    }

    fun pointsOf(region: FrameRegion): List<FramePoint> = POINTS.filter { it.region == region }

    /** 775 冻结的十四个 code，表驱动取，不另抄一份（888 的冻结断言在测试侧核字面）。 */
    fun subzoneCodes(): List<String> = SubzoneId.entries.map { it.code }

    /**
     * 从 815 真枚举推可见档。表覆盖「点位浅深 × 体脂四档」，构造期自检全覆盖。
     *
     * 骨架档、截面宽档**不在本表**——宽骨架不等于骨点更明显（判据⑤）。
     */
    private val VISIBLE_TABLE: Map<Pair<FrameExposure, BodyFatTier>, VisibleGrade> = mapOf(
        (FrameExposure.SUPERFICIAL to BodyFatTier.LOW) to VisibleGrade.PRONOUNCED,
        (FrameExposure.SUPERFICIAL to BodyFatTier.MID) to VisibleGrade.PRONOUNCED,
        (FrameExposure.SUPERFICIAL to BodyFatTier.HIGH) to VisibleGrade.CLEAR,
        (FrameExposure.SUPERFICIAL to BodyFatTier.VERY_HIGH) to VisibleGrade.FAINT,
        (FrameExposure.MID to BodyFatTier.LOW) to VisibleGrade.PRONOUNCED,
        (FrameExposure.MID to BodyFatTier.MID) to VisibleGrade.CLEAR,
        (FrameExposure.MID to BodyFatTier.HIGH) to VisibleGrade.FAINT,
        (FrameExposure.MID to BodyFatTier.VERY_HIGH) to VisibleGrade.HIDDEN,
        (FrameExposure.DEEP to BodyFatTier.LOW) to VisibleGrade.CLEAR,
        (FrameExposure.DEEP to BodyFatTier.MID) to VisibleGrade.FAINT,
        (FrameExposure.DEEP to BodyFatTier.HIGH) to VisibleGrade.HIDDEN,
        (FrameExposure.DEEP to BodyFatTier.VERY_HIGH) to VisibleGrade.HIDDEN,
    )

    /** 从 815 真枚举推可触及档。脂肪对触及的影响比对可见小。 */
    private val PALPABLE_TABLE: Map<Pair<FrameExposure, BodyFatTier>, PalpableGrade> = mapOf(
        (FrameExposure.SUPERFICIAL to BodyFatTier.LOW) to PalpableGrade.EASY,
        (FrameExposure.SUPERFICIAL to BodyFatTier.MID) to PalpableGrade.EASY,
        (FrameExposure.SUPERFICIAL to BodyFatTier.HIGH) to PalpableGrade.EASY,
        (FrameExposure.SUPERFICIAL to BodyFatTier.VERY_HIGH) to PalpableGrade.CLEAR,
        (FrameExposure.MID to BodyFatTier.LOW) to PalpableGrade.CLEAR,
        (FrameExposure.MID to BodyFatTier.MID) to PalpableGrade.CLEAR,
        (FrameExposure.MID to BodyFatTier.HIGH) to PalpableGrade.FAINT,
        (FrameExposure.MID to BodyFatTier.VERY_HIGH) to PalpableGrade.FAINT,
        (FrameExposure.DEEP to BodyFatTier.LOW) to PalpableGrade.CLEAR,
        (FrameExposure.DEEP to BodyFatTier.MID) to PalpableGrade.FAINT,
        (FrameExposure.DEEP to BodyFatTier.HIGH) to PalpableGrade.FAINT,
        (FrameExposure.DEEP to BodyFatTier.VERY_HIGH) to PalpableGrade.HARD,
    )

    init {
        val allPairs = FrameExposure.entries.flatMap { exposure ->
            BodyFatTier.entries.map { exposure to it }
        }
        require(allPairs.all { it in VISIBLE_TABLE }) { "可见表漏格：骨架档与截面档都不许补位" }
        require(allPairs.all { it in PALPABLE_TABLE }) { "可触表漏格：不许用默认值兜底" }
    }

    fun visibleOf(point: FramePoint, fat: BodyFatTier): VisibleGrade =
        VISIBLE_TABLE.getValue(point.exposure to fat)

    fun palpableOf(point: FramePoint, fat: BodyFatTier): PalpableGrade =
        PALPABLE_TABLE.getValue(point.exposure to fat)

    /** 每点三项的可选组合数（表驱动，不手数）。 */
    val totalChoices: Int get() = POINTS.size * (VisibleGrade.COUNT + PalpableGrade.COUNT + 1)
}
