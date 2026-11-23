// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百四十八批 · 测试夹具：形状与角色都从这里造，避免每个用例手写八维漂移。
package com.example.zhengdao.baopu.state.bodyparts

internal object TorsoFixtures {

    /** 八维形状；默认值＝久坐档案的典型形状（倾向全中）。 */
    fun shape(
        blocks: RectusBlocks = RectusBlocks.TWO,
        linea: LineaAlba = LineaAlba.SHALLOW,
        align: BlockAlignment = BlockAlignment.EVEN,
        pecLower: PecLowerTrend = PecLowerTrend.ROUND_BLUNT,
        gap: PecGapWidth = PecGapWidth.MID,
        trap: TrapSlope = TrapSlope.GENTLE,
        lat: LatFlare = LatFlare.STRAIGHT_DOWN,
        serratus: Serratus = Serratus.NONE,
    ): TorsoShape = TorsoShape(blocks, linea, align, pecLower, gap, trap, lat, serratus)

    /** 久坐档案的典型形状：块少、白线浅、下缘圆钝、斜方平缓、背阔直下。 */
    fun sedentaryShape(): TorsoShape = shape()

    /** 健身型档案的典型形状：块多、白线深、下缘外高内低、斜方陡。 */
    fun athleticShape(): TorsoShape = shape(
        blocks = RectusBlocks.SIX,
        linea = LineaAlba.DEEP,
        pecLower = PecLowerTrend.OUTER_HIGH,
        trap = TrapSlope.STEEP,
        lat = LatFlare.V_TAPER,
        serratus = Serratus.FAINT,
    )

    /** 体积签；默认中偏下一档，与形状判据无关。 */
    fun volume(
        pec: PecVolume = PecVolume.SLIGHT,
        abs: AbsBlock = AbsBlock.UPPER,
        arm: Girth = Girth.SLIM,
    ): TorsoVolume = TorsoVolume(pec, abs, arm)

    fun person(
        roleId: String,
        shape: TorsoShape = sedentaryShape(),
        profile: TorsoProfile = TorsoProfile.SEDENTARY,
        volume: TorsoVolume = volume(),
        visible: Boolean = true,
    ): TorsoPerson = TorsoPerson(roleId, profile, shape, volume, visible)
}
