// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百七十五批 · 龟头体与柱身静态形态（子区层）：三个闭集档位——质地、色档、形态轴。
//
// 三条口径：
// · 全部是**档**，没有量。质地只有三档、色档只有四档、形态轴各 2–3 档，一个字都不许换成尺寸；
// · 质地的秩与 660 的 `SURFACE_ORDER`（明显皱 → 细皱 → 光滑）同向：秩大＝更光滑。
//   这样「龟头比柱身光滑一档」在两张表里是同一个方向，不会一处说高一档、一处说低一档；
// · 色档是**静态相对档**（与柱身比），不是 731 的 `colorShift` 动态色变档。两层分开：
//   本单元说「这块地方常态比柱身深一档」，731 说「现在比常态又变了多少」。
package com.example.zhengdao.baopu.state.subzone

/** 质地三档。秩大＝更光滑（与 660 的表面粗糙度序同向）。 */
enum class Texture(val rank: Int, val label: String) {
    ROUGH(0, "粗糙"),
    PLAIN(1, "一般"),
    SMOOTH(2, "光滑"),
    ;

    companion object {
        /** 一档之差＝秩差 1。「龟头更光滑」判的就是这个 1。 */
        const val STEP: Int = 1

        fun ofRank(rank: Int): Texture = entries.firstOrNull { it.rank == rank }
            ?: throw IllegalArgumentException("质地只有 0–2 三档：收到秩 $rank")
    }
}

/**
 * 颜色相对档（相对柱身，闭集四档）。
 *
 * step 是**档差**：浅＝−1、同＝0、深一档＝1、深两档＝2。判「龟头深一档」只算这个差，
 * 不比色号——绝对颜色归呈现侧（805 的 `BodyPartGuard.rejectAbsoluteColor` 同一口径）。
 */
enum class ColorShift(val step: Int, val label: String) {
    LIGHTER(-1, "浅"),
    SAME(0, "同"),
    DEEPER_1(1, "深一档"),
    DEEPER_2(2, "深两档"),
    ;

    companion object {
        /** 柱身是参照物：与柱身比，柱身自己记「同」。 */
        val BASELINE: ColorShift = SAME

        fun ofStep(step: Int): ColorShift = entries.firstOrNull { it.step == step }
            ?: throw IllegalArgumentException("色档只有 −1／0／+1／+2 四档：收到档差 $step")
    }
}

/**
 * 形态轴：子区「长什么样」的闭集维度表。每条轴自带档名表（2–3 档），档序 0..n 连续。
 *
 * 为什么用共享轴而不是每个子区一套枚举：柱身中段的中线与龟头体部的中线是同一个问题，
 * 分成两个枚举就迟早各写各的档名。共享轴的代价是「某轴某档对某个子区不适用」，
 * 这一层由 [SubzoneTable] 的行声明管——每行只列自己用得上的 1–3 条轴。
 *
 * 不做的事：不含截面廓形（814）、不含尺寸／弯曲（660 的 curvature 自有字段）、
 * 不含松紧度以外的医学口径（包皮口只记口缘形状，不记「能不能翻开」——那是 804 的覆盖位）。
 */
enum class FormAxis(val label: String, private val tierNames: List<String>) {
    TIP("顶端形状", listOf("圆钝", "锥", "偏斜")),
    TRANSITION("与体部过渡", listOf("连续", "略分", "分明")),
    EDGE("边缘厚薄", listOf("薄", "中", "厚")),
    PLUMP("饱满度", listOf("欠", "适中", "满")),
    MIDLINE("中线", listOf("无", "浅", "深")),
    GROOVE("沟深浅", listOf("浅", "中", "深")),
    RING("环完整", listOf("整环", "腹侧断开")),
    BAND("带长短", listOf("短", "中", "长")),
    TIGHT("带紧度", listOf("松", "中", "紧")),
    TAPER("段形走向", listOf("收", "平", "胀")),
    VISIBLE("体表可见", listOf("不可见", "微凸", "可见")),
    RIM("口缘厚薄", listOf("薄", "中", "厚")),
    SLIT("裂隙方向", listOf("纵裂偏腹侧", "纵裂居中", "圆孔")),
    FOLD("褶", listOf("平", "细褶", "叠褶")),
    ;

    /** 档名表（唯一真相在这里，别处不手抄）。 */
    val tiers: List<String> get() = tierNames

    val maxTier: Int get() = tierNames.lastIndex

    /** 档名。越界即抛——档序是闭集，不静默夹取。 */
    fun tierName(tier: Int): String =
        tierNames.getOrNull(tier) ?: throw IllegalArgumentException("$label 只有 0–$maxTier 档：收到 $tier")

    /** 档序；不在册返回 −1（调用方自己决定报红还是当未定）。 */
    fun tierOf(name: String): Int = tierNames.indexOf(name)

    companion object {
        fun ofLabel(label: String): FormAxis = entries.firstOrNull { it.label == label }
            ?: throw IllegalArgumentException("形态轴只收 ${entries.size} 条登记轴的 label：收到「$label」")
    }
}

/** 一条形态读数：哪条轴、第几档。构造即验档序在册。 */
data class FormValue(val axis: FormAxis, val tier: Int) {

    init {
        require(tier in 0..axis.maxTier) {
            "形态档越界：${axis.label} 只有 0–${axis.maxTier} 档，收到 $tier"
        }
    }

    val label: String get() = axis.tierName(tier)

    fun line(): String = axis.label + "=" + label
}
