// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.adaptive

/**
 * 746 折叠感知的纯逻辑层。
 *
 * 真机的 `androidx.window.layout.FoldingFeature` → [FoldState] 由 [FoldStateAdapter] 映射；
 * 判据（③铰链避让、②折叠夹具）只吃本文件的纯值类型，JVM 可测、无需真机。
 *
 * 铰链避让口径：半折（HALF_OPENED）且铰链是 separating + full-overlap 时，铰链区是不可放
 * 内容的物理遮挡带；内容区必须收缩到铰链带之外。水平铰链（ORIENTATION_HORIZONTAL，折痕
 * 横贯左右）把屏分成「上 / 下」两栏 —— 半折时这两栏正是用户看到的两个物理面。
 */
enum class FoldPosture { FLAT, HALF_OPENED, CLOSED }

/** 铰链方向：HORIZONTAL=折痕横贯（上下分屏），VERTICAL=折痕纵贯（左右分屏）。 */
enum class HingeOrientation { HORIZONTAL, VERTICAL }

/** 整数矩形，坐标口径与 android.graphics.Rect 一致（left/top/right/bottom，右/下开区间）。 */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isValid: Boolean get() = width > 0 && height > 0

    /** 与 [other] 是否有正面积相交（仅贴边不算相交 ⇒ 内容贴着铰链边缘是允许的）。 */
    fun overlaps(other: IntRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

/**
 * 折叠状态快照（平台无关的纯值类型）。
 * @param posture 姿态；[hinge] 仅在 HALF_OPENED 时有意义
 * @param hinge 铰链带在窗口坐标系中的矩形
 * @param isSeparating 铰链是否把窗分成两个逻辑区（false ⇒ 不避让）
 * @param isOccluding 铰链是否遮挡内容（false ⇒ 内容可跨越折痕，不避让）
 */
data class FoldState(
    val posture: FoldPosture = FoldPosture.FLAT,
    val hinge: IntRect? = null,
    val orientation: HingeOrientation = HingeOrientation.HORIZONTAL,
    val isSeparating: Boolean = false,
    val isOccluding: Boolean = false,
) {
    companion object {
        /** 无折叠能力 / 平展 / 合上：一律不避让，返回单块全窗内容区。 */
        val FLAT = FoldState()
    }
}

object FoldingFeaturePolicy {
    /** 是否需要为铰链避让：半折 + 分隔 + 遮挡 + 有效铰链带，四条同时成立。 */
    fun shouldAvoid(fold: FoldState): Boolean =
        fold.posture == FoldPosture.HALF_OPENED &&
            fold.isSeparating && fold.isOccluding &&
            fold.hinge?.isValid == true

    /**
     * 给定窗口边界，返回避让铰链后的内容区列表。
     * 不需要避让 ⇒ 单块全窗；需要避让 ⇒ 按铰链方向切成两块，各自不含铰链带。
     */
    fun contentBounds(window: IntRect, fold: FoldState): List<IntRect> {
        if (!shouldAvoid(fold)) return listOf(window)
        val hinge = fold.hinge ?: return listOf(window)
        val h = hinge.coerceInside(window) ?: return listOf(window)
        return when (fold.orientation) {
            HingeOrientation.HORIZONTAL -> listOf(
                IntRect(window.left, window.top, window.right, h.top),
                IntRect(window.left, h.bottom, window.right, window.bottom),
            )
            HingeOrientation.VERTICAL -> listOf(
                IntRect(window.left, window.top, h.left, window.bottom),
                IntRect(h.right, window.top, window.right, window.bottom),
            )
        }.filter { it.isValid }
    }

    /**
     * 判据③的核心：内容矩形是否落入铰链区。
     * 不避让（平展/合上）或铰链无效 ⇒ 恒 false（无铰链可落）。
     */
    fun contentInHinge(content: IntRect, fold: FoldState): Boolean {
        if (!shouldAvoid(fold)) return false
        val hinge = fold.hinge ?: return false
        return content.overlaps(hinge)
    }

    /** 把铰链带裁到窗口内；完全在窗外返回 null（此时不避让）。 */
    private fun IntRect.coerceInside(window: IntRect): IntRect? {
        val l = left.coerceAtLeast(window.left).coerceAtMost(window.right)
        val r = right.coerceAtLeast(window.left).coerceAtMost(window.right)
        val t = top.coerceAtLeast(window.top).coerceAtMost(window.bottom)
        val b = bottom.coerceAtLeast(window.top).coerceAtMost(window.bottom)
        val clipped = IntRect(l, t, r, b)
        return clipped.takeIf { it.isValid }
    }
}
