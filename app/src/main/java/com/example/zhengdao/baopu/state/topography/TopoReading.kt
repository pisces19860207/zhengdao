// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十六批 · 地形单：一条读数怎么写，一张单子怎么算齐。
//
// 「四件齐」在这里是**结构性**的，不靠自觉：
// · 凸 5 维、凹 5 维、厚 5 维，缺一维 [Topography] 根本构造不出来；
// · 第四件「常态不对称」不是另记一节——分左右的 11 维每条都**必须**左右各记一笔，
//   记成同一档就是「完全对称」（判据③ 会提示），所以「不对称」不可能漏记。
//
// 中轴四维（腹直肌沟、胸厚、腹厚、臀厚）只记一笔，两侧填同一档，给原因即拒——
// 中轴没有左右，硬给「惯用侧」是把不对称说到了不存在的地方。
package com.example.zhengdao.baopu.state.topography

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import kotlin.math.abs

private const val SIDE_LEFT = "左"
private const val SIDE_RIGHT = "右"
private const val SIDE_MARK = "="
private const val ITEM_SEP = "；"

/**
 * 一条地形读数。三个子类各管一族，档位类型跟着族走，跨族混记在编译期就进不来。
 */
sealed interface TopoReading {

    val dim: TopoDim

    /** 左侧（中轴维两侧同值）。 */
    val left: TopoGrade

    /** 右侧（中轴维两侧同值）。 */
    val right: TopoGrade

    /** 左右差超过常态时给的原因；中轴维必须为空。 */
    val cause: AsymCause?

    /** 左右相差几档。0＝完全对称。 */
    val gap: Int get() = abs(left.level - right.level)

    val even: Boolean get() = gap == 0

    /** 一行读数：分左右的写两侧，中轴只写一笔。 */
    fun shapeLine(): String = if (dim.lateral) {
        dim.label + " " + SIDE_LEFT + SIDE_MARK + left.label + " " + SIDE_RIGHT + SIDE_MARK + right.label
    } else {
        dim.label + SIDE_MARK + left.label
    }
}

/** 凸起读数。 */
data class ReliefReading(
    override val dim: ReliefDim,
    override val left: ReliefGrade,
    override val right: ReliefGrade,
    override val cause: AsymCause? = null,
) : TopoReading {
    init {
        requireSided(dim, left, right, cause)
    }
}

/** 凹陷读数。 */
data class HollowReading(
    override val dim: HollowDim,
    override val left: HollowGrade,
    override val right: HollowGrade,
    override val cause: AsymCause? = null,
) : TopoReading {
    init {
        requireSided(dim, left, right, cause)
    }
}

/**
 * 厚度读数。
 *
 * @param note 说明（可空）。这道说明单独过形状词闸：厚度最容易被顺手写成形状
 *   （「胸厚得像倒三角」），一写就替 848 说了话（判据④）。
 */
data class DepthReading(
    override val dim: DepthDim,
    override val left: DepthGrade,
    override val right: DepthGrade,
    override val cause: AsymCause? = null,
    val note: String = "",
) : TopoReading {
    init {
        requireSided(dim, left, right, cause)
        BodyPartGuard.rejectMeasures(note)
        BodyPartGuard.rejectDiagnosis(note)
        TopoGuard.rejectShapeWords(note)
    }
}

/** 中轴记两笔同档＝自欺；给原因＝把不对称说到不存在的地方。两件都当场拒。 */
private fun requireSided(dim: TopoDim, left: TopoGrade, right: TopoGrade, cause: AsymCause?) {
    if (!dim.lateral) {
        require(left == right) {
            dim.label + "：" + "中轴维只记一笔，两侧必须同档（写成了 " + left.label + "／" + right.label + "）"
        }
        require(cause == null) { dim.label + "：" + "中轴维没有左右，不给不对称原因" }
    }
}

/**
 * 一张地形单。构造即校验，不齐的单子造不出来：
 * · 凸 5／凹 5／厚 5，共 15 维，一维不许少、一维不许记两次；
 * · 每条读数自带左右两笔（中轴同档），所以「左右差多少」这件事不可能缺。
 * 缺什么当场点名，不给「大概齐了」的余地。
 */
class Topography(readings: List<TopoReading>) {

    private val items: List<TopoReading> = readings.toList()
    private val byDim: Map<TopoDim, TopoReading> = items.associateBy { it.dim }
    private val reliefs: Map<ReliefDim, ReliefReading> = items.filterIsInstance<ReliefReading>()
        .associateBy { it.dim }
    private val hollows: Map<HollowDim, HollowReading> = items.filterIsInstance<HollowReading>()
        .associateBy { it.dim }
    private val depths: Map<DepthDim, DepthReading> = items.filterIsInstance<DepthReading>()
        .associateBy { it.dim }

    init {
        require(items.isNotEmpty()) { EMPTY_REJECT }
        require(byDim.size == items.size) { duplicateRule(items) }
        val missing = TopoCatalog.allDims().filterNot { byDim.containsKey(it) }
        require(missing.isEmpty()) { missingRule(missing) }
    }

    val size: Int get() = items.size

    /** 全部读数，顺序＝目录表书写序（凸／凹／厚），逐字可回放。 */
    fun all(): List<TopoReading> = TopoCatalog.RELIEF_DIMS.map { reliefs.getValue(it) } +
        TopoCatalog.HOLLOW_DIMS.map { hollows.getValue(it) } +
        TopoCatalog.DEPTH_DIMS.map { depths.getValue(it) }

    fun reading(dim: TopoDim): TopoReading = byDim.getValue(dim)

    fun relief(dim: ReliefDim): ReliefReading = reliefs.getValue(dim)

    fun hollow(dim: HollowDim): HollowReading = hollows.getValue(dim)

    fun depth(dim: DepthDim): DepthReading = depths.getValue(dim)

    /** 分左右的读数（11 条）。判据③④ 只看这批。 */
    fun lateral(): List<TopoReading> = all().filter { it.dim.lateral }

    /** 中轴读数（4 条）。 */
    fun midline(): List<TopoReading> = all().filterNot { it.dim.lateral }

    fun gapOf(dim: TopoDim): Int = reading(dim).gap

    /** 最大的左右差（档）。 */
    fun maxGap(): Int = lateral().maxOf { it.gap }

    /**
     * 一行地形快照：凸／凹／厚 各一行。
     * 这是**唯一出口**，出门前过 805 的两道闸（不出长度／百分比、不出诊断词）。
     */
    fun line(): String {
        val text = TopoFamily.entries.joinToString("\n") { family -> familyLine(family) }
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
        return text
    }

    private fun familyLine(family: TopoFamily): String =
        family.label + "｜" + all().filter { it.dim.family == family }
            .joinToString(ITEM_SEP) { it.shapeLine() }

    companion object {
        const val EMPTY_REJECT: String = "地形单不能是空的：凸／凹／厚各五维都要给档"
        const val DUPLICATE_REJECT: String = "同一维记了两次：两个真相"
        const val MISSING_PREFIX: String = "地形单不齐："

        /** 三族各五维。表驱动取，不手数。 */
        val EXPECTED_SIZE: Int get() = TopoCatalog.allDims().size

        fun of(vararg readings: TopoReading): Topography = Topography(readings.toList())

        private fun duplicateRule(items: List<TopoReading>): String {
            val seen = mutableSetOf<TopoDim>()
            val dup = items.firstOrNull { !seen.add(it.dim) }
            return DUPLICATE_REJECT + "（" + (dup?.dim?.label ?: "") + "）"
        }

        private fun missingRule(missing: List<TopoDim>): String =
            MISSING_PREFIX + missing.size + " 维缺。" + missing.joinToString("、") { it.label }
    }
}
