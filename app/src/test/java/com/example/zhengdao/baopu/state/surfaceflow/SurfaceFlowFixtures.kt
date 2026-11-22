// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零六批 · 测试夹具：一条全过的正例 + 两个小工具，JUnit 与独立 harness 共用。
package com.example.zhengdao.baopu.state.surfaceflow

/** 红码是否命中，逐码核对，不拿「有没有红」糊弄。 */
internal fun SurfaceVerdict<*>.hasCode(code: String): Boolean = reds.any { it.code == code }

/** 四要素全过的正例：凸分流、凹积水带出口、尖挂滴标最低点、毛束沾水、汗分两区。 */
internal object SurfaceFixtures {

    fun good(): SurfaceFlowClaim = SurfaceFlowClaim(
        source = WaterSource.SWEAT,
        posture = FlowPosture.STANDING,
        paths = listOf(
            FlowPath(PathKind.MIDLINE, Site.LARYNX, FlowBehavior.SPLIT, Site.PECTORAL),
            FlowPath(PathKind.ALONG_GROOVE, Site.STERNAL_GROOVE, FlowBehavior.POOL),
        ),
        pools = listOf(PoolRecord(Site.STERNAL_GROOVE, PoolLevel.OBVIOUS, Site.NAVEL)),
        drips = listOf(DripRecord(Site.GLANS_TIP, true)),
        hairWicks = listOf(HairWick(Site.AXILLA, true)),
        sweat = SweatProfile(
            listOf(
                SweatZoneRead(SweatZone.CHEST, FlowVolume.MODERATE, SeepStage.RUNNING, EvapTier.MIDDLE, true),
                SweatZoneRead(SweatZone.BELLY, FlowVolume.TRACE, SeepStage.BEADING, EvapTier.SLIGHT, true),
            ),
        ),
    )

    /** 全过正例渲染后的稳定四行。 */
    fun goodLine(): String = SurfaceFlowRenderer.render(
        requireNotNull(SurfaceFlowRule.judge(good()).value)
    ).line()
}
