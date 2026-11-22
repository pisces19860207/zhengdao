// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零四批 · 包皮专批①：覆盖位图（coverageMask）与覆盖面积（coverageArea）。
//
// 口径三条（真源＝派工单 §②）：
// · 位图是唯一真相：逐子区记三态；面积只能**由位图单向聚合**（mask ⇒ area），
//   反向（area ⇒ mask）在 `fromArea` 里当场红——五档不保真，同一个面积档可由多张不同位图得到。
// · 冠（corona）与沟（sulcus）是两个独立子区，永不合并；合名由 `PrepuceGuard` 拦。
// · 子区表：龟头拆背／腹／左右侧缘／颈缘，柱身拆背侧／腹侧（阴茎缝）／侧缘，系带、尿道口各独立；
//   `specField` 只是 660（`AnchorSpecTable`）字段 id 的**字符串引用**，本包不 import 660 任何类型。
//
// 纯数据、零 IO、零随机、零时钟：同输入必得同结论（自检见单测）。
package com.example.zhengdao.baopu.state.prepuce

/** 子区所属部位。冠与沟各自单独一组：这是判据⑤的机器落点，不许有「冠沟」组。 */
enum class RegionGroup(val label: String) {
    GLANS("龟头"),
    CORONA("冠"),
    SULCUS("沟"),
    FRENULUM("系带"),
    MEATUS("尿道口"),
    SHAFT("柱身"),
}

/**
 * 覆盖位图的子区（12 项）。
 *
 * 龟头五区：派工单正文写「龟头四（背/腹/左右侧缘/颈缘）」，括注里是五个名字。
 * 按名字逐项落——背、腹、左右侧缘是四个面，颈缘是龟头与冠接壤的一圈，单列不并入；
 * 宁可多记一区，不许把两个可分别观察的面合成一格（判据⑤同理）。
 *
 * 柱身三区：背侧／腹侧（阴茎缝）／侧缘。依据不是自己想的：660 把「阴茎缝」在可指区域里单列
 * （`CalibrationTrigger` 的 zoneNames），所以柱身按背／腹（缝）／侧拆。
 */
enum class SubRegion(val label: String, val group: RegionGroup, val specField: String) {
    GLANS_BACK("龟头·背侧", RegionGroup.GLANS, "g.glansTip"),
    GLANS_FRONT("龟头·腹侧", RegionGroup.GLANS, "g.glansTip"),
    GLANS_LEFT("龟头·左侧缘", RegionGroup.GLANS, "g.glansTip"),
    GLANS_RIGHT("龟头·右侧缘", RegionGroup.GLANS, "g.glansTip"),
    GLANS_NECK("龟头·颈缘", RegionGroup.GLANS, "g.glansTip"),
    CORONA("冠", RegionGroup.CORONA, "g.corona"),
    SULCUS("沟", RegionGroup.SULCUS, "g.sulcus"),
    FRENULUM("系带", RegionGroup.FRENULUM, "g.frenulum"),
    MEATUS("尿道口", RegionGroup.MEATUS, "g.meatusForm"),
    SHAFT_BACK("柱身·背侧", RegionGroup.SHAFT, "g.shaft"),
    SHAFT_FRONT("柱身·腹侧（阴茎缝）", RegionGroup.SHAFT, "g.shaft"),
    SHAFT_SIDE("柱身·侧缘", RegionGroup.SHAFT, "g.shaft"),
}

/** 三态。code 是机读名（covered/partial/exposed），label 是入文用词。 */
enum class CoverState(val code: String, val label: String) {
    COVERED("covered", "覆盖"),
    PARTIAL("partial", "半露"),
    EXPOSED("exposed", "露出"),
}

/**
 * 覆盖六档。序数即「覆盖从多到少」，0（完全覆盖）→ 5（完全露出）。
 *
 * 831 补「退至冠后」：龟头五区全露＋冠露出＋沟仍覆，即 804 判为未定的 X2 转正。
 * [flaccidOrder] 是疲软表专用的 0–4 序：退至冠后由位图专判、疲软表预测不出来，记 -1；
 * 804 的 162 组合读数因此逐项不变（证据见 PrepuceExposureTest）。
 */
enum class AreaLevel(val label: String, val order: Int, internal val flaccidOrder: Int) {
    FULL_COVER("完全覆盖", 0, 0),
    ALMOST_ALL("几乎全部", 1, 1),
    OVER_HALF("过半", 2, 2),
    CORONA_ONLY("仅冠", 3, 3),
    RETRACTED_BEHIND_CORONA("退至冠后", 4, -1),
    FULLY_EXPOSED("完全露出", 5, 4),
    ;

    companion object {
        /** 按覆盖序数取档，调用方负责先钳到 0..5（越界即抛，不静默夹取）。 */
        fun ofOrder(order: Int): AreaLevel =
            entries.firstOrNull { it.order == order } ?: throw IllegalArgumentException(OUT_OF_RANGE)

        /** 疲软表专用：0–4，跳过退至冠后（它没有疲软表中的序）。 */
        internal fun ofFlaccidOrder(order: Int): AreaLevel =
            entries.firstOrNull { it.flaccidOrder == order } ?: throw IllegalArgumentException(OUT_OF_RANGE)

        const val OUT_OF_RANGE: String = "档序越界：覆盖只有 0（完全覆盖）到 5（完全露出）六档"
    }
}

/** 未定项的建议动作，三选一。裸未定不许出门（831）。 */
enum class UnknownAction(val label: String) {
    HUMAN_FILL("人补档"),
    SNAP_NEAREST("落最近档"),
    ESCALATE("转人工"),
}

/**
 * 面积结论。[level] 为 null ＝ **档外（未定）**：位图落在六档之外，不许硬塞进相邻档。
 * [rowId] 是命中的口径行号（A1–A6／X1–X2），[reason] 是给人看的一句依据。
 *
 * 831：未定必须同时带 [unknownReason]（为什么判不了）与 [suggestedAction]（人下一步做什么），
 * 缺一即红；已定档反过来不许带这两个字段，免得结论自相矛盾。
 */
data class CoverageArea(
    val level: AreaLevel?,
    val rowId: String,
    val reason: String,
    val unknownReason: String? = null,
    val suggestedAction: UnknownAction? = null,
) {
    init {
        require(rowId.isNotBlank()) { NO_ROW }
        require(reason.isNotBlank()) { NO_ROW }
        PrepuceGuard.rejectMeasures(reason)
        val why = unknownReason
        if (level == null) {
            require(!why.isNullOrBlank() && suggestedAction != null) { BARE_UNKNOWN }
            PrepuceGuard.rejectMeasures(why)
        } else {
            require(why == null && suggestedAction == null) { RESOLVED_WITH_UNKNOWN }
        }
    }

    val resolved: Boolean get() = level != null

    /** 一行机读快照：未定时档位写「未定」，并附未定因与建议动作。 */
    fun line(): String = buildString {
        append(level?.label ?: UNRESOLVED_LABEL).append('|').append(rowId).append('|').append(reason)
        if (unknownReason != null) {
            append("|未定因=").append(unknownReason)
            append("|建议=").append(suggestedAction?.label)
        }
    }

    companion object {
        const val NO_ROW: String = "面积结论必须带口径行号与理由：不给依据的档位不算数"
        const val UNRESOLVED_LABEL: String = "未定"
        const val BARE_UNKNOWN: String = "裸未定不许出门：未定项必须同时带 unknownReason 与建议动作"
        const val RESOLVED_WITH_UNKNOWN: String = "已定档不许带未定因与建议动作：结论自相矛盾"
    }
}

/** 龟头五区，聚合只数这五区；顺序＝[SubRegion] 声明序，保证两跑逐字一致。 */
internal val GLANS_REGIONS: List<SubRegion> =
    SubRegion.entries.filter { it.group == RegionGroup.GLANS }

/**
 * 覆盖位图。构造即守两条硬规则：corona 与 sulcus 都必须逐项在册（判据①后半）。
 * 位图可以只记一部分子区（[isDense] 为 false），但**算面积**要求龟头五区＋冠＋沟齐备。
 */
class CoverageMask private constructor(private val states: Map<SubRegion, CoverState>) {

    init {
        require(states.containsKey(SubRegion.CORONA)) { MISSING_CORONA }
        require(states.containsKey(SubRegion.SULCUS)) { MISSING_SULCUS }
    }

    fun stateOf(region: SubRegion): CoverState? = states[region]

    val size: Int get() = states.size

    fun isDense(): Boolean = states.size == SubRegion.entries.size

    /** 面积只能从这里出：单向聚合。缺龟头五区或冠沟任一 ⇒ 红（算不出就别算）。 */
    fun area(): CoverageArea {
        val missing = AREA_NEEDS.filterNot { states.containsKey(it) }
        require(missing.isEmpty()) { areaNeeds(missing) }
        return when (val units = glansHalfUnits()) {
            FULL_UNITS -> CoverageArea(AreaLevel.FULL_COVER, "A1", "龟头五区全 covered")
            in HALF_STEP..FULL_UNITS - 1 -> CoverageArea(AreaLevel.ALMOST_ALL, "A2", ALMOST_WHY)
            in HALF_UNITS - 1..HALF_UNITS -> CoverageArea(AreaLevel.OVER_HALF, "A3", HALF_WHY)
            0 -> exposedArea()
            else -> CoverageArea(
                level = null,
                rowId = "X1",
                reason = "龟头只零星覆盖（半区计 $units，六档外），不硬塞档",
                unknownReason = "龟头半区计 $units，落在 1–3：覆盖太零星，六档里没有这一格",
                suggestedAction = UnknownAction.HUMAN_FILL,
            )
        }
    }

    /**
     * 龟头全 exposed 之后才看冠与沟：冠沟都还盖着＝仅冠；都露＝完全露出；
     * 冠露沟仍覆＝退至冠后（831 把 804 的 X2 转正）；冠未露而沟露＝仍未定，须带治理字段。
     * 804 的方向性原样保留：冠露绝不直接推完全露出，沟必须自己也露。
     */
    private fun exposedArea(): CoverageArea {
        val corona = states.getValue(SubRegion.CORONA)
        val sulcus = states.getValue(SubRegion.SULCUS)
        val coronaExposed = corona == CoverState.EXPOSED
        val sulcusExposed = sulcus == CoverState.EXPOSED
        return when {
            !coronaExposed && !sulcusExposed ->
                CoverageArea(AreaLevel.CORONA_ONLY, "A4", "龟头全露出，只剩冠与沟还盖着")
            coronaExposed && sulcusExposed ->
                CoverageArea(AreaLevel.FULLY_EXPOSED, "A5", "龟头五区＋冠＋沟全 exposed")
            PrepuceGuard.isRetractedBehindCorona(corona, sulcus) ->
                CoverageArea(AreaLevel.RETRACTED_BEHIND_CORONA, "A6", RETRACTED_WHY)
            else -> CoverageArea(
                level = null,
                rowId = "X2",
                reason = "沟已露、冠仍覆：不是六档里的任何一档",
                unknownReason = "冠未露而沟已露，与六档的方向相反（退至冠后是冠露沟覆）",
                suggestedAction = UnknownAction.ESCALATE,
            )
        }
    }

    /** 龟头覆盖折成「半区」单位：covered＝2、partial＝1、exposed＝0，满值 10。 */
    private fun glansHalfUnits(): Int = GLANS_REGIONS.sumOf { region ->
        when (states[region]) {
            CoverState.COVERED -> UNIT_COVERED
            CoverState.PARTIAL -> UNIT_PARTIAL
            else -> 0
        }
    }

    /** 一行机读快照，只列在册子区，按声明序。 */
    fun line(): String = SubRegion.entries
        .mapNotNull { region -> states[region]?.let { "$region=${it.code}" } }
        .joinToString(",")

    companion object {
        const val MISSING_CORONA: String =
            "位图缺「冠」（corona）：冠与沟必须逐项记，缺一项即红"
        const val MISSING_SULCUS: String =
            "位图缺「沟」（sulcus）：冠与沟必须逐项记，缺一项即红"
        const val AREA_TO_MASK_REJECT: String =
            "不许由面积反推位图：五档是多对一的聚合，反推出来的子区状态是编的；位图只能逐区记"
        const val DUPLICATE_REGION: String = "同一子区只能记一次三态，重复写等于两个真相"
        const val UNKNOWN_AREA: String =
            "区名对不上任何子区：只收 12 个子区的名字，不猜近义词"
        const val AREA_NEEDS_PREFIX: String = "算面积要求龟头五区＋冠＋沟齐备，缺："

        private const val UNIT_COVERED = 2
        private const val UNIT_PARTIAL = 1
        private const val FULL_UNITS = 10
        private const val HALF_UNITS = 5
        private const val HALF_STEP = 6
        private const val ALMOST_WHY = "龟头覆盖过半（半区计满 6 起）"
        private const val HALF_WHY = "龟头覆盖半数左右（半区计 4–5）"
        private const val RETRACTED_WHY = "龟头五区全露出、冠露出、沟仍被盖：退至冠后（804 的 X2 转正）"

        private val AREA_NEEDS: List<SubRegion> = GLANS_REGIONS +
            listOf(SubRegion.CORONA, SubRegion.SULCUS)

        private fun areaNeeds(missing: List<SubRegion>): String =
            AREA_NEEDS_PREFIX + missing.joinToString("、") { it.label }

        fun of(vararg pairs: Pair<SubRegion, CoverState>): CoverageMask = of(pairs.toList())

        /**
         * 文字区名 → 子区。判据⑤在这里落到生产侧：**合名先红**（「冠沟」进不来），再按 label 精确对。
         * 叙事层写「冠」就记 corona、写「沟」就记 sulcus，两区各一笔，不给合并的余地。
         */
        fun regionOf(areaName: String): SubRegion {
            PrepuceGuard.requireSeparateAreaName(areaName)
            val key = areaName.filterNot { it.isWhitespace() }
            ALIASES[key]?.let { return it }
            return SubRegion.entries.firstOrNull { it.label.filterNot { c -> c.isWhitespace() } == key }
                ?: throw IllegalArgumentException("$UNKNOWN_AREA（收到「$areaName」）")
        }

        /** 唯一别名：「冠状沟」是解剖固定术语，只指沟（660 的 g.sulcus 行也用这个词）。 */
        private val ALIASES: Map<String, SubRegion> = mapOf("冠状沟" to SubRegion.SULCUS)

        /** 由「子区→三态」建位图；重复子区即红（不许两个真相）。 */
        fun of(pairs: List<Pair<SubRegion, CoverState>>): CoverageMask {
            val map = pairs.toMap()
            require(map.size == pairs.size) { DUPLICATE_REGION }
            return CoverageMask(map)
        }

        /** 判据①：由面积反推位图 ⇒ 红。签名用 Nothing：这条路在类型上就走不通。 */
        fun fromArea(level: AreaLevel): Nothing = throw IllegalArgumentException(
            "$AREA_TO_MASK_REJECT（收到「${level.label}」）"
        )
    }
}
