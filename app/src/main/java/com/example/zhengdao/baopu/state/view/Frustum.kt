// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · 视锥：FOV 档 ＋ 可见范围三态。
//
// 本文件回答且只回答一个问题：「这个方向的东西，在这个镜头里算看得见吗？」
// 三态不是「可见/不可见」两态——边缘那一档是 3D→2D 投影最先崩的地方（侧影挤在画框边上、
// 一半身子出画），把它单独留一档，写的人才知道该不该写「半边入画」。
//
// 不做的事：不算遮挡（归 861）、不算景深、不算透视变形。视锥只说「在不在画面里」。
package com.example.zhengdao.baopu.state.view

import kotlin.math.abs

/**
 * 视锥：只由 [fov] 一档决定。
 *
 * 判可见要两轴一起看——横向看方位差，纵向看目标在画面里的高低。
 * 两轴各得一个三态，取最严的那个（外 > 边缘 > 内）：横向出画就是出画，纵向正对也救不回来。
 */
data class Frustum(val fov: FovTier) {

    /**
     * 单轴判定：给「偏离中心的格数」（已取绝对值）。
     *
     * 内带以内=视野内，内带外到边缘带=边缘，再外=视野外。
     * 正后方（方位差 4 格）一定落在视野外：任何一档的边缘带都只到 3 格。
     */
    fun bandOf(steps: Int): Visibility {
        val span = abs(steps)
        return when {
            span <= fov.inSteps -> Visibility.INSIDE
            span <= fov.edgeSteps -> Visibility.EDGE
            else -> Visibility.OUTSIDE
        }
    }

    /**
     * 两轴合成：横向 [bearingSteps]（有符号，符号只影响左右不影响松紧）与纵向 [verticalSteps]
     * （目标在画面里的格数，正值偏上）。
     */
    fun visibility(bearingSteps: Int, verticalSteps: Int): Visibility =
        worse(bandOf(bearingSteps), bandOf(verticalSteps))

    /** 是否在画面里（视野内或边缘都算在画面里）。 */
    fun inFrame(bearingSteps: Int, verticalSteps: Int): Boolean =
        visibility(bearingSteps, verticalSteps) != Visibility.OUTSIDE

    private fun worse(a: Visibility, b: Visibility): Visibility =
        if (a.severity >= b.severity) a else b

    /** 稳定写法：只写档名，不写格数（格数是推出来的，写进来会诱使别人拿它当准绳）。 */
    fun line(): String = "视锥${fov.label}"
}
