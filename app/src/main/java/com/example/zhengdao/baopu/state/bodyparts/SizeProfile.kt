// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十批 · 一条尺寸记录：六件，彼此独立。
//
// 为什么把「看起来埋没」和「尺寸」放在同一个类里却互不读：外观与本体是两个事实，
// 放在一起是为了让报告一行读得完，不是为了互相推。类里没有任何一行代码读另一个字段来算自己。
//
// 默认中性（判据①）：什么都不给的时候，每个字段都落自己的中性档——
// 长短「中」、粗细「中等」、龟头相对「相对中」、埋没「无」、时相「常」、外观「无可见标记」、
// 色变「无变化」、色档「与肤色相同」。中性档的意思是「不额外声称任何偏离」，不是「正常值」。
package com.example.zhengdao.baopu.state.bodyparts

/**
 * 一条尺寸记录。构造即校验：来源只有三种，默认中性必须全中性，观测／档案必须给依据。
 *
 * @param length 长短档
 * @param girth 粗细档（复用 805 的 Girth）
 * @param glans 龟头相对大小档（相对柱身）
 * @param buriedLook 看起来埋没多少（外观，与上面三个尺寸档无推导边）
 * @param timing 发育时相（背景，无推导边）
 * @param look 发育相关外观（与年龄体重都不联动）
 * @param colorShift 色变档（动态·血流）
 * @param colorTier 色档（静态·黑色素＋摩擦，复用 805 的 ColorRef）
 * @param source 来源三选一
 * @param basis 依据。观测／档案必填；默认中性不填
 */
data class SizeProfile(
    val length: LengthTier,
    val girth: Girth,
    val glans: GlansTier,
    val buriedLook: BuriedLook,
    val timing: DevelopmentTiming,
    val look: DevelopmentLook,
    val colorShift: FlowShift,
    val colorTier: ColorRef,
    val source: SizeSource,
    val basis: String? = null,
) {
    init {
        require(source != SizeSource.DEFAULT_NEUTRAL || neutral) { NEUTRAL_REJECT }
        require(source == SizeSource.DEFAULT_NEUTRAL || !basis.isNullOrBlank()) { BASIS_REJECT }
        val b = basis.orEmpty()
        BodyPartGuard.rejectMeasures(b)
        BodyPartGuard.rejectAbsoluteColor(b)
        BodyPartGuard.rejectDiagnosis(b)
        SizeGuard.rejectDerivedBasis(b)
        SizeGuard.rejectCausation(b)
    }

    /** 八件是不是全落在中性档。默认中性只认这一种形状。 */
    val neutral: Boolean
        get() = length.level == LengthTier.NEUTRAL &&
            girth.level == Girth.MEDIUM.level &&
            glans.level == GlansTier.NEUTRAL &&
            buriedLook.level == BuriedLook.NEUTRAL &&
            timing.level == DevelopmentTiming.NEUTRAL &&
            look.level == DevelopmentLook.NEUTRAL &&
            colorShift.level == FlowShift.NEUTRAL &&
            colorTier.level == ColorRef.SAME.level

    /** 一行机读快照。颜色两层分两段写（判据⑥）；出口过三道文字闸。 */
    fun line(): String {
        val text = "尺寸=" + length.label + "／" + girth.label + "／" + glans.label +
            "；埋没=" + buriedLook.label +
            "；时相=" + timing.label +
            "；发育外观=" + look.label +
            "；色变档=" + colorShift.label +
            "；色档=" + colorTier.label +
            "（" + source.label + "）"
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
        SizeGuard.rejectCausation(text)
        SizeGuard.rejectMergedColor(text)
        return text
    }

    companion object {
        const val NEUTRAL_REJECT: String =
            "默认中性必须八件全落中性档：想说哪一档不同，就得写观测或档案并给依据"
        const val BASIS_REJECT: String = "观测与档案必须给依据：给不出依据的尺寸只能记默认中性"

        /** 默认中性记录。这是「什么都没给」时唯一合法的形状。 */
        fun neutral(): SizeProfile = SizeProfile(
            length = LengthTier.of(LengthTier.NEUTRAL),
            girth = Girth.of(Girth.MEDIUM.level),
            glans = GlansTier.of(GlansTier.NEUTRAL),
            buriedLook = BuriedLook.of(BuriedLook.NEUTRAL),
            timing = DevelopmentTiming.of(DevelopmentTiming.NEUTRAL),
            look = DevelopmentLook.of(DevelopmentLook.NEUTRAL),
            colorShift = FlowShift.of(FlowShift.NEUTRAL),
            colorTier = ColorRef.of(ColorRef.SAME.level),
            source = SizeSource.DEFAULT_NEUTRAL,
        )
    }
}
