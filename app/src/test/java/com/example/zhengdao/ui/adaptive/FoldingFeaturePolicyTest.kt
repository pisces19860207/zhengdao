// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判据③：铰链避让的边界断言（含"贴边不算相交"负例）。 */
class FoldingFeaturePolicyTest {

    private val halfOpen = FoldState(
        posture = FoldPosture.HALF_OPENED,
        hinge = IntRect(0, 1168, 1080, 1232),
        orientation = HingeOrientation.HORIZONTAL,
        isSeparating = true,
        isOccluding = true,
    )

    @Test
    fun `shouldAvoid_四条件缺一不可`() {
        assertTrue(FoldingFeaturePolicy.shouldAvoid(halfOpen))
        assertFalse("非半折不避让", FoldingFeaturePolicy.shouldAvoid(halfOpen.copy(posture = FoldPosture.FLAT)))
        assertFalse("非分隔不避让", FoldingFeaturePolicy.shouldAvoid(halfOpen.copy(isSeparating = false)))
        assertFalse("非遮挡不避让", FoldingFeaturePolicy.shouldAvoid(halfOpen.copy(isOccluding = false)))
        assertFalse("无铰链不避让", FoldingFeaturePolicy.shouldAvoid(halfOpen.copy(hinge = null)))
        assertFalse("零面积铰链不避让", FoldingFeaturePolicy.shouldAvoid(halfOpen.copy(hinge = IntRect(0, 0, 0, 0))))
    }

    @Test
    fun `内容分上下两块_都不含铰链带`() {
        val window = IntRect(0, 0, 1080, 2400)
        val slots = FoldingFeaturePolicy.contentBounds(window, halfOpen)
        assertEquals(2, slots.size)
        slots.forEach { s ->
            assertFalse("每块内容都不得落入铰链区：$s", FoldingFeaturePolicy.contentInHinge(s, halfOpen))
        }
        assertEquals(0, slots[0].top)
        assertEquals(2400, slots[1].bottom)
    }

    @Test
    fun `铰链带内的内容被判红_贴边的不判红`() {
        val inside = IntRect(100, 1180, 200, 1220)
        val touchingTop = IntRect(100, 1100, 200, 1168)
        val touchingBottom = IntRect(100, 1232, 200, 1300)
        assertTrue("铰链带内必须判红", FoldingFeaturePolicy.contentInHinge(inside, halfOpen))
        assertFalse("贴铰链上沿不算落入", FoldingFeaturePolicy.contentInHinge(touchingTop, halfOpen))
        assertFalse("贴铰链下沿不算落入", FoldingFeaturePolicy.contentInHinge(touchingBottom, halfOpen))
    }

    @Test
    fun `垂直铰链分左右两块`() {
        val window = IntRect(0, 0, 2400, 1080)
        val vertical = halfOpen.copy(
            hinge = IntRect(1188, 0, 1212, 1080),
            orientation = HingeOrientation.VERTICAL,
        )
        val slots = FoldingFeaturePolicy.contentBounds(window, vertical)
        assertEquals(2, slots.size)
        assertEquals(0, slots[0].left)
        assertEquals(2400, slots[1].right)
        slots.forEach { assertFalse(FoldingFeaturePolicy.contentInHinge(it, vertical)) }
    }

    @Test
    fun `铰链夹到窗外返回null_不避让`() {
        val window = IntRect(0, 0, 1080, 2400)
        val outside = halfOpen.copy(hinge = IntRect(2000, 2500, 2100, 2600))
        assertEquals(listOf(window), FoldingFeaturePolicy.contentBounds(window, outside))
    }

    @Test
    fun `overlaps贴边与角落负例`() {
        val a = IntRect(0, 0, 10, 10)
        assertFalse(a.overlaps(IntRect(10, 0, 20, 10)))   // 右边贴边
        assertFalse(a.overlaps(IntRect(0, 10, 10, 20)))   // 下边贴边
        assertFalse(a.overlaps(IntRect(10, 10, 20, 20)))  // 角落
        assertTrue(a.overlaps(IntRect(9, 9, 20, 20)))     // 真相交 1x1
    }
}
