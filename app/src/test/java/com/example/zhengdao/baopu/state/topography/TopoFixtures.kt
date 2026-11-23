// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百六十六批 · 测试夹具：地形单与角色都从这里造，避免每个用例手写十五维漂移。
package com.example.zhengdao.baopu.state.topography

import com.example.zhengdao.baopu.state.bodyparts.FatBand

internal object TopoFixtures {

    /** 凸起读数；默认给「左隐含右清楚」的一档差（惯用侧常态）。 */
    fun relief(
        dim: ReliefDim = ReliefDim.SCAPULA,
        left: ReliefGrade = ReliefGrade.FAINT,
        right: ReliefGrade = left,
        cause: AsymCause? = null,
    ): ReliefReading = ReliefReading(dim, left, right, cause)

    /** 凹陷读数。 */
    fun hollow(
        dim: HollowDim = HollowDim.SUPRACLAVICULAR,
        left: HollowGrade = HollowGrade.MID,
        right: HollowGrade = left,
        cause: AsymCause? = null,
    ): HollowReading = HollowReading(dim, left, right, cause)

    /** 厚度读数。 */
    fun depth(
        dim: DepthDim = DepthDim.CHEST,
        left: DepthGrade = DepthGrade.MID,
        right: DepthGrade = left,
        cause: AsymCause? = null,
        note: String = "",
    ): DepthReading = DepthReading(dim, left, right, cause, note)

    /** 十五条读数；默认＝偏瘦人的常态地形（肩胛左右差一档，其余对称）。 */
    fun readings(): List<TopoReading> = listOf(
        relief(ReliefDim.SCAPULA, ReliefGrade.FAINT, ReliefGrade.CLEAR),
        relief(ReliefDim.RIBS, ReliefGrade.FAINT, ReliefGrade.FAINT),
        relief(ReliefDim.RECTUS_GROOVE, ReliefGrade.CLEAR, ReliefGrade.CLEAR),
        relief(ReliefDim.TRAP_SLOPE, ReliefGrade.FAINT, ReliefGrade.FAINT),
        relief(ReliefDim.ILIAC_CREST, ReliefGrade.CLEAR, ReliefGrade.CLEAR),
        hollow(HollowDim.SUPRACLAVICULAR, HollowGrade.MID, HollowGrade.MID),
        hollow(HollowDim.AXILLA, HollowGrade.DEEP, HollowGrade.DEEP),
        hollow(HollowDim.INGUINAL, HollowGrade.SHALLOW, HollowGrade.SHALLOW),
        hollow(HollowDim.NECK_SIDE, HollowGrade.SHALLOW, HollowGrade.SHALLOW),
        hollow(HollowDim.WAIST_SIDE, HollowGrade.SHALLOW, HollowGrade.SHALLOW),
        depth(DepthDim.CHEST, DepthGrade.MID, DepthGrade.MID),
        depth(DepthDim.ABDOMEN, DepthGrade.THIN, DepthGrade.THIN),
        depth(DepthDim.GLUTEAL, DepthGrade.MID, DepthGrade.MID),
        depth(DepthDim.THIGH_FRONT, DepthGrade.MID, DepthGrade.MID),
        depth(DepthDim.CALF_BACK, DepthGrade.MID, DepthGrade.MID),
    )

    /** 换掉某几维后重造一张单（其余按默认）。 */
    fun topo(vararg overrides: TopoReading): Topography = topoOn(Topography(readings()), *overrides)

    /** 在给定单上换掉某几维（拿大腹常态做底时用这个）。 */
    fun topoOn(base: Topography, vararg overrides: TopoReading): Topography {
        val replaced = overrides.associateBy { it.dim }
        return Topography(base.all().map { replaced[it.dim] ?: it })
    }

    /** 大腹便便的常态：骨性隆起被盖住、凹陷填浅，腹直肌沟反而更深。 */
    fun heavyTopo(): Topography = topo(
        relief(ReliefDim.SCAPULA, ReliefGrade.NONE, ReliefGrade.NONE),
        relief(ReliefDim.RIBS, ReliefGrade.NONE, ReliefGrade.FAINT),
        relief(ReliefDim.RECTUS_GROOVE, ReliefGrade.FAINT, ReliefGrade.FAINT),
        relief(ReliefDim.TRAP_SLOPE, ReliefGrade.FAINT, ReliefGrade.FAINT),
        relief(ReliefDim.ILIAC_CREST, ReliefGrade.NONE, ReliefGrade.NONE),
        hollow(HollowDim.SUPRACLAVICULAR, HollowGrade.NONE, HollowGrade.NONE),
        hollow(HollowDim.AXILLA, HollowGrade.SHALLOW, HollowGrade.SHALLOW),
        hollow(HollowDim.INGUINAL, HollowGrade.NONE, HollowGrade.SHALLOW),
        hollow(HollowDim.NECK_SIDE, HollowGrade.SHALLOW, HollowGrade.SHALLOW),
        hollow(HollowDim.WAIST_SIDE, HollowGrade.NONE, HollowGrade.NONE),
        depth(DepthDim.ABDOMEN, DepthGrade.THICK, DepthGrade.THICK),
        depth(DepthDim.CHEST, DepthGrade.THICK, DepthGrade.THICK),
    )

    fun person(
        roleId: String,
        fat: FatBand = FatBand.LEAN,
        topo: Topography = topo(),
    ): TopoPerson = TopoPerson(roleId, fat, topo)
}
