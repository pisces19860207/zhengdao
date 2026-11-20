// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.adaptive

/**
 * 746 大屏与折叠适配 · 尺寸类（WindowSizeClass）判定。
 *
 * 为什么不用 `material3-window-size-class` 依赖：该 artifact（1.4.0）本机 Gradle 缓存
 * 缺失、需联网；而判据只要求三档宽度分界，Material 3 官方阈值 600dp / 840dp 是公开的
 * 定值。用纯函数实现 ⇒ 零新增依赖、JVM 可测、阈值单一真相源。
 *
 * 阈值口径（M3 官方）：
 *   - compact  : widthDp < 600   ⇒ 单栏
 *   - medium   : 600 ≤ widthDp < 840 ⇒ 双栏
 *   - expanded : widthDp ≥ 840   ⇒ 双栏 + 更宽编辑器
 */
enum class WidthSizeClass { COMPACT, MEDIUM, EXPANDED }

object WindowSizeClasses {
    const val COMPACT_MAX_DP = 600f
    const val MEDIUM_MAX_DP = 840f

    /** 单栏 / 双栏的栏数。compact 单栏，其余双栏。 */
    const val PANES_COMPACT = 1
    const val PANES_WIDE = 2

    /** 编辑器最大宽度（dp）：expanded 给更宽的一栏。 */
    const val EDITOR_MAX_COMPACT_DP = 600
    const val EDITOR_MAX_MEDIUM_DP = 360
    const val EDITOR_MAX_EXPANDED_DP = 640

    fun of(widthDp: Float): WidthSizeClass = when {
        widthDp < COMPACT_MAX_DP -> WidthSizeClass.COMPACT
        widthDp < MEDIUM_MAX_DP -> WidthSizeClass.MEDIUM
        else -> WidthSizeClass.EXPANDED
    }

    fun paneCount(c: WidthSizeClass): Int = when (c) {
        WidthSizeClass.COMPACT -> PANES_COMPACT
        else -> PANES_WIDE
    }

    fun editorMaxWidthDp(c: WidthSizeClass): Int = when (c) {
        WidthSizeClass.COMPACT -> EDITOR_MAX_COMPACT_DP
        WidthSizeClass.MEDIUM -> EDITOR_MAX_MEDIUM_DP
        WidthSizeClass.EXPANDED -> EDITOR_MAX_EXPANDED_DP
    }
}
