// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第一百七十七批施工图 §4（S2 坐标注入）。纯算术直测。
package com.example.zhengdao.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TapGeometry] 的判据：**CSS 视口坐标 → 视图像素**。
 *
 * 为什么这条最该测：模型看截图报的是 CSS px，手指落在视图上是物理像素；
 * 换算错一次，就是「点了，但点到了别的地方」——比不点更糟（可能误触按钮）。
 */
class TapGeometryTest {

    @Test
    fun 同尺寸_一比一() {
        assertEquals(120, TapGeometry.scale(120, 360, 360))
    }

    @Test
    fun 三倍屏_乘三() {
        // 360 CSS px 的视口画在 1080 px 宽的视图上
        assertEquals(360, TapGeometry.scale(120, 360, 1080))
    }

    @Test
    fun 缩放兼容_取整四舍五入() {
        assertEquals("4*1000/360=11.11 → 11", 11, TapGeometry.scale(4, 360, 1000))
        assertEquals("9*500/360=12.5 → 13（Math.round 半上取）", 13, TapGeometry.scale(9, 360, 500))
    }

    @Test
    fun 视口宽为0_不炸_回0() {
        assertEquals(0, TapGeometry.scale(10, 0, 100))
    }

    @Test
    fun map_正常换算() {
        val p = TapGeometry.map(100, 200, 400, 800, 1080, 2160)
        assertEquals(TapGeometry.ViewPoint(270, 540), p)
    }

    @Test
    fun map_越界坐标夹进视图内_不产生负数() {
        val p = TapGeometry.map(9999, 9999, 400, 800, 1080, 2160)!!
        assertTrue(p.x in 0..1079)
        assertTrue(p.y in 0..2159)
    }

    @Test
    fun map_几何非法_回null() {
        assertNull("视口宽为 0（页面还没绘制）", TapGeometry.map(1, 1, 0, 800, 1080, 2160))
        assertNull("视图宽为 0（未布局）", TapGeometry.map(1, 1, 400, 800, 0, 2160))
    }

    @Test
    fun insideViewport_右下边界是开区间() {
        assertTrue(TapGeometry.insideViewport(0, 0, 400, 800))
        assertTrue(TapGeometry.insideViewport(399, 799, 400, 800))
        assertFalse("x 等于视口宽 = 越界（0-based）", TapGeometry.insideViewport(400, 10, 400, 800))
        assertFalse(TapGeometry.insideViewport(10, 800, 400, 800))
        assertFalse(TapGeometry.insideViewport(-1, 10, 400, 800))
    }

    @Test
    fun path_含首尾_中间帧数正确() {
        val p = TapGeometry.path(0, 0, 100, 200, 4)
        assertEquals(6, p.size)
        assertEquals(0 to 0, p.first())
        assertEquals(100 to 200, p.last())
        assertEquals(20 to 40, p[1])
    }

    @Test
    fun path_零帧退化首尾两点() {
        val p = TapGeometry.path(5, 5, 9, 9, 0)
        assertEquals(listOf(5 to 5, 9 to 9), p)
    }

    @Test
    fun path_帧数上限24() {
        val p = TapGeometry.path(0, 0, 1000, 1000, 999)
        assertEquals(26, p.size)
    }
}
