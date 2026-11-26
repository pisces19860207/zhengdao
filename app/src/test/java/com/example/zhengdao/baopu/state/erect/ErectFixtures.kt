// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百一十批 · 测试夹具：一张合法的五行两态单，各测试在此基础上做变异。
package com.example.zhengdao.baopu.state.erect

internal object ErectFixtures {

    fun size(type: GrowthType = GrowthType.INCREASE, tier: GrowthTier = GrowthTier.CLEAR) =
        SizeState(type, tier)

    fun angle(f: ErectAngle = ErectAngle.FLAT, e: ErectAngle = ErectAngle.SLIGHT_UP) =
        AnglePair(f, e)

    fun hardness(tier: HardnessTier = HardnessTier.EHS3,
                 flaccidness: FullnessTier? = FullnessTier.FLACCID) =
        HardnessState(tier, flaccidness)

    /** 906 夹具 A：疲软是已知状态，给明确档，不是 absent。 */
    fun flaccid(tier: HardnessTier = HardnessTier.EHS3) =
        HardnessState(tier, FullnessTier.FLACCID)

    /** 906 夹具 B：真的不知道，才用 null 渲染 absent。 */
    fun unknownHardness(tier: HardnessTier = HardnessTier.EHS3) =
        HardnessState(tier, null)

    fun prepuce(f: String = "完全覆盖", e: String = "完全露出") = PrepuceState(f, e)

    fun corona(profile: ErectProfile = ErectProfile.CORONA) = CoronaState(profile)

    fun sheet() = ErectSheet.of(size(), angle(), hardness(), prepuce(), corona())

    fun person(role: String = "测试角色") = ErectPerson(role, sheet())
}
