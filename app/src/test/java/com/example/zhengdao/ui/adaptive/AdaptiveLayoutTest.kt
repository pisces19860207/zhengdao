// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判据①：三尺寸档判定与布局决策（10 条断言）。 */
class AdaptiveLayoutTest {

    @Test
    fun `三档宽度分界_600与840`() {
        assertEquals(WidthSizeClass.COMPACT, WindowSizeClasses.of(0f))
        assertEquals(WidthSizeClass.COMPACT, WindowSizeClasses.of(599.9f))
        assertEquals(WidthSizeClass.MEDIUM, WindowSizeClasses.of(600f))
        assertEquals(WidthSizeClass.MEDIUM, WindowSizeClasses.of(839.9f))
        assertEquals(WidthSizeClass.EXPANDED, WindowSizeClasses.of(840f))
        assertEquals(WidthSizeClass.EXPANDED, WindowSizeClasses.of(2400f))
    }

    @Test
    fun `compact单栏底栏_expanded双栏侧栏且编辑器更宽`() {
        val window = IntRect(0, 0, 1080, 2400)
        val c = AdaptiveLayout.decide(411f, FoldState.FLAT, window)
        val m = AdaptiveLayout.decide(700f, FoldState.FLAT, window)
        val e = AdaptiveLayout.decide(1024f, FoldState.FLAT, window)
        assertEquals(1, c.paneCount)
        assertEquals(NavigationStyle.BOTTOM, c.navigation)
        assertEquals(2, m.paneCount)
        assertEquals(2, e.paneCount)
        assertEquals(NavigationStyle.RAIL, e.navigation)
        assertTrue("expanded 编辑器必须比 medium 宽", e.editorMaxWidthDp > m.editorMaxWidthDp)
        assertEquals(0, c.hingeTopPaddingDp)
    }

    @Test
    fun `半折水平铰链_上下各留避让padding`() {
        val window = IntRect(0, 0, 1080, 2400)
        val fold = FoldState(
            posture = FoldPosture.HALF_OPENED,
            hinge = IntRect(0, 1168, 1080, 1232),
            orientation = HingeOrientation.HORIZONTAL,
            isSeparating = true,
            isOccluding = true,
        )
        val cfg = AdaptiveLayout.decide(411f, fold, window)
        assertTrue("半折必须触发上下分屏", cfg.isSplitByHinge)
        assertTrue("上避让 > 0", cfg.hingeTopPaddingDp > 0)
        assertTrue("下避让 > 0", cfg.hingeBottomPaddingDp > 0)
    }

    @Test
    fun `平展不避让_哪怕铰链矩形存在`() {
        val window = IntRect(0, 0, 1080, 2400)
        val fold = FoldState(
            posture = FoldPosture.FLAT,
            hinge = IntRect(0, 1168, 1080, 1232),
            isSeparating = true,
            isOccluding = true,
        )
        val cfg = AdaptiveLayout.decide(411f, fold, window)
        assertFalse("平展不该分屏", cfg.isSplitByHinge)
    }
}
