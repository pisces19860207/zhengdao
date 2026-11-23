// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · 视点：一个挂在场景图节点上的「看」。
//
// 五个要素各管一件事，互不替代：
//   · [anchor] ＋ [at]   —— 从哪儿看（挂哪个节点、在该区域的第几格）；
//   · [facing]           —— 朝哪边看（八方位之一）；
//   · [pitch]            —— 镜头平／俯／仰（决定画面纵向偏移）；
//   · [frustum]          —— 镜头多宽（决定视野内／边缘／外）；
//   · [focusDistance]    —— 这一镜打算拍多远的那个主体（声明式意图，与算出来的距离档对账）。
//
// 为什么不给裸坐标：视点一旦可以漂浮，「遮挡／侧影／近大远小」就没有共同的参照系。
// 所以构造时 [anchor] 必填，且不许挂在场景根上（根没有位置）。
package com.example.zhengdao.baopu.state.view

/**
 * 一个视点。
 *
 * [at] 是**相对区域中心**的格位，必须与锚点在场景图里的格位一致——这是**装订**（binding）：
 * 视点写的格位与它挂的那件物件的格位对不上，就是「挂在这件上、站在那件的位置」，照样算漂浮，
 * [ViewChecks] 判红。两处都写不是冗余：只写一处时，错的那一处永远没人对账。
 *
 * [focusDistance] 不是算出来的那个距离档，而是「这一镜想拍多远的那个主体」；算出来的距离档
 * 由 [Projection.distanceTier] 逐个目标给。两者对账见 [ViewChecks]——差一档提提示，差两档判红。
 */
data class Viewpoint(
    val anchor: NodeRef,
    val region: RegionGrade,
    val at: Cell,
    val facing: Octant,
    val pitch: PitchTier,
    val frustum: Frustum,
    val focusDistance: DistanceTier,
) {
    init {
        require(anchor.layer != SceneLayer.SCENE) {
            "视点不许挂在场景根（${anchor.nodeId}）：根没有位置，挂上去就是漂浮坐标（判据②）"
        }
    }

    /** 稳定写法：逐项档名，供确定性双跑逐字比对。 */
    fun line(): String = buildString {
        append("视点@").append(anchor.line())
        append("|区域=").append(region.label)
        append("|格=").append(at.label)
        append("|朝=").append(facing.label)
        append("|").append(pitch.label).append("拍")
        append("|").append(frustum.line())
        append("|取景=").append(focusDistance.label)
    }
}
