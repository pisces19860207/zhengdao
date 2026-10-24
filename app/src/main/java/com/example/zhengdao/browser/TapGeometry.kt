// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第一百七十七批施工图 §4（S2 坐标注入）。纯函数，零 Android，JVM 直测。
package com.example.zhengdao.browser

/**
 * 坐标换算：**CSS 像素（页面视口坐标）→ 视图像素（MotionEvent 坐标）**。
 *
 * ## 为什么必须换算
 * 模型看截图说「点在 (120, 860)」时，它说的是**页面视口坐标**（截图是按 CSS px 标注的）。
 * 屏幕上的缩放系数（系统字号/desktop 模式/pinch）会让同一位置在 `WebView` 视图里落在
 * 别的像素上。判据只用两样东西：
 * - `clientWidth`（页面自己报的视口宽，CSS px，`viewportBoundsJsScript` 已经给了）；
 * - `webView.width`（视图宽，物理像素）。
 * ⇒ `scale = webView.width / clientWidth`，`viewX = cssX * scale`。
 *
 * ## 为什么不做「整页坐标」
 * 本批只支持**视口坐标**（与截图同一坐标系）。整页坐标（含滚动量）会让「滚了之后
 * 点哪」变得隐晦，且 172 的 L4 快照口径也未定 ⇒ 不在本批引入。用户想点视口外的
 * 位置，先 `browser_scroll` 再点。
 *
 * ## 边界
 * - 换算结果夹到 `[0, viewSize-1]`：越界坐标不该变成「点到了视图外面」（那会静默无效），
 *   但它**也不是合法点击**——调用方在换算前就该判 `cssX/cssY` 是否落在视口内，
 *   本函数只负责「不越界」这一层防护。
 * - 几何非法（视口宽高 ≤0：页面还没绘制）⇒ **null**，调用方明说原因，不瞎点。
 */
internal object TapGeometry {

    /** 一个视图坐标点（像素，整数）。 */
    data class ViewPoint(val x: Int, val y: Int)

    /**
     * 把 CSS 视口坐标换成视图像素坐标。
     *
     * @param cssX/cssY 模型给的坐标（CSS px，视口坐标）
     * @param cssW/cssH 页面视口尺寸（CSS px，来自 `documentElement.clientWidth/clientHeight`）
     * @param viewW/viewH WebView 视图尺寸（像素，来自 `webView.width/height`）
     * @return null = 几何非法（页面还没绘制 / 尺寸为 0）
     */
    fun map(
        cssX: Int,
        cssY: Int,
        cssW: Int,
        cssH: Int,
        viewW: Int,
        viewH: Int,
    ): ViewPoint? {
        if (cssW <= 0 || cssH <= 0 || viewW <= 0 || viewH <= 0) return null
        return ViewPoint(
            x = scale(cssX, cssW, viewW).coerceIn(0, viewW - 1),
            y = scale(cssY, cssH, viewH).coerceIn(0, viewH - 1),
        )
    }

    /** 单轴换算（纯算术，单独暴露给单测）。 */
    fun scale(css: Int, cssSize: Int, viewSize: Int): Int {
        if (cssSize <= 0) return 0
        val v = css.toDouble() * viewSize.toDouble() / cssSize.toDouble()
        return Math.round(v).toInt()
    }

    /** 坐标是否落在视口内（模型给 0-based，右/下边界是开区间）。 */
    fun insideViewport(cssX: Int, cssY: Int, cssW: Int, cssH: Int): Boolean =
        cssX >= 0 && cssY >= 0 && cssX < cssW && cssY < cssH

    /**
     * 滑动的中间点序列（含起点与终点）。
     *
     * @param steps 中间帧数（不含首尾）；≤0 时退化成「只有首尾」
     */
    fun path(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        steps: Int,
    ): List<Pair<Int, Int>> {
        val n = steps.coerceIn(0, 24)
        if (n == 0) return listOf(x1 to y1, x2 to y2)
        val out = ArrayList<Pair<Int, Int>>(n + 2)
        out += x1 to y1
        for (i in 1..n) {
            val t = i.toDouble() / (n + 1).toDouble()
            out += Math.round(x1 + (x2 - x1) * t).toInt() to
                Math.round(y1 + (y2 - y1) * t).toInt()
        }
        out += x2 to y2
        return out
    }
}
