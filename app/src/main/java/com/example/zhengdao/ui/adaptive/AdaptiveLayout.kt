// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.adaptive

/**
 * 746 布局决策（纯函数，JVM 可测）。
 *
 * 三档口径：compact 单栏（底栏） / medium 双栏（侧边导航＋内容） / expanded 双栏＋更宽编辑器。
 * 本文件只产出"决策数据"，不画 UI —— 画 UI 在 [AdaptiveShell]，判据全打在这个纯函数上。
 */
enum class NavigationStyle { BOTTOM, RAIL }

data class LayoutConfig(
    val widthClass: WidthSizeClass,
    val paneCount: Int,
    val navigation: NavigationStyle,
    val editorMaxWidthDp: Int,
    /** 半折时上下分屏：内容区上下各留出的避让高度（dp），0 表示无铰链。 */
    val hingeTopPaddingDp: Int,
    val hingeBottomPaddingDp: Int,
) {
    val isSplitByHinge: Boolean get() = hingeTopPaddingDp > 0 || hingeBottomPaddingDp > 0
}

object AdaptiveLayout {
    /** 铰链带厚度默认 32dp：真机从 FoldingFeature.bounds 换算，夹具用此定值。 */
    const val DEFAULT_HINGE_THICKNESS_DP = 32

    /**
     * 决策主体。
     * @param window 窗口尺寸（dp，整数像素口径），用于把铰链 px 带换算成 dp padding
     * @param fold 折叠状态；仅 HALF_OPENED+separating+occluding 时避让
     */
    fun decide(widthDp: Float, fold: FoldState, window: IntRect): LayoutConfig {
        val cls = WindowSizeClasses.of(widthDp)
        val avoid = FoldingFeaturePolicy.shouldAvoid(fold)
        val hingePx = fold.hinge
        val topPx = if (avoid && hingePx != null && fold.orientation == HingeOrientation.HORIZONTAL) {
            (hingePx.top - window.top).coerceAtLeast(0)
        } else 0
        val bottomPx = if (avoid && hingePx != null && fold.orientation == HingeOrientation.HORIZONTAL) {
            (window.bottom - hingePx.bottom).coerceAtLeast(0)
        } else 0
        // px → dp 换算比例按窗口宽推导（density = window.width / widthDp），避免依赖 Android 资源。
        val density = if (widthDp > 0f) window.width / widthDp else 1f
        fun toDp(px: Int) = if (density > 0f) (px / density).toInt() else 0
        return LayoutConfig(
            widthClass = cls,
            paneCount = WindowSizeClasses.paneCount(cls),
            navigation = if (cls == WidthSizeClass.COMPACT) NavigationStyle.BOTTOM else NavigationStyle.RAIL,
            editorMaxWidthDp = WindowSizeClasses.editorMaxWidthDp(cls),
            hingeTopPaddingDp = toDp(topPx),
            hingeBottomPaddingDp = toDp(bottomPx),
        )
    }
}
