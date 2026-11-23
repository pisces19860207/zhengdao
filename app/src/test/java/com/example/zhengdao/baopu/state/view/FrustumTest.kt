// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · 判据② 视锥：FOV 三档 × 三态的逐格表。
//
// 这张表是**手写**的（不从 FovTier 的字段反推），因为它是「镜头多宽」这一档的口径本身：
// 窄镜头左右各一格就不在画面里，宽镜头到第三格才贴着边。改实现不改这张表，说明行为变了。
package com.example.zhengdao.baopu.state.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrustumTest {

    /** 偏离中心的格数 → 三态（0..4 格；4 格＝正后方）。 */
    private fun table(fov: FovTier): List<Visibility> =
        (0..4).map { Frustum(fov).bandOf(it) }

    @Test
    fun `三档逐格表_窄只看正对中看一格宽看两格`() {
        assertEquals(
            "窄：0 格内、1 格边缘、2 格以外全出画",
            listOf(
                Visibility.INSIDE, Visibility.EDGE, Visibility.OUTSIDE,
                Visibility.OUTSIDE, Visibility.OUTSIDE,
            ),
            table(FovTier.NARROW),
        )
        assertEquals(
            "中：0..1 格内、2 格边缘、3 格起出画",
            listOf(
                Visibility.INSIDE, Visibility.INSIDE, Visibility.EDGE,
                Visibility.OUTSIDE, Visibility.OUTSIDE,
            ),
            table(FovTier.MEDIUM),
        )
        assertEquals(
            "宽：0..2 格内、3 格边缘、正后方出画",
            listOf(
                Visibility.INSIDE, Visibility.INSIDE, Visibility.INSIDE,
                Visibility.EDGE, Visibility.OUTSIDE,
            ),
            table(FovTier.WIDE),
        )
    }

    @Test
    fun `负格与正格同松紧_左右不分家`() {
        FovTier.entries.forEach { fov ->
            val frustum = Frustum(fov)
            (0..4).forEach { steps ->
                assertEquals(
                    "${fov.label}档：$steps 格与 -$steps 格必须同态",
                    frustum.bandOf(steps), frustum.bandOf(-steps),
                )
            }
        }
    }

    @Test
    fun `正后方一定是视野外_三档皆然`() {
        FovTier.entries.forEach { fov ->
            assertEquals(
                "${fov.label}档：正后方（4 格）不许算在画面里",
                Visibility.OUTSIDE, Frustum(fov).bandOf(Octant.BEHIND_STEPS),
            )
        }
    }

    @Test
    fun `两轴取最严_横向出画纵向正对也救不回来`() {
        val medium = Frustum(FovTier.MEDIUM)
        assertEquals("横 0 纵 0 ⇒ 内", Visibility.INSIDE, medium.visibility(0, 0))
        assertEquals("纵 2 格 ⇒ 边缘", Visibility.EDGE, medium.visibility(0, 2))
        assertEquals("纵 3 格 ⇒ 外（横向再正对也没用）", Visibility.OUTSIDE, medium.visibility(0, 3))
        assertEquals("横 3 格 ⇒ 外（纵向正对也没用）", Visibility.OUTSIDE, medium.visibility(3, 0))
        assertEquals("横 2 纵 2 都贴边 ⇒ 边缘", Visibility.EDGE, medium.visibility(2, 2))
    }

    @Test
    fun `在不在画面里_内外二分与三态一致`() {
        val wide = Frustum(FovTier.WIDE)
        assertTrue("3 格仍在画面里（边缘）", wide.inFrame(3, 0))
        assertFalse("4 格出画", wide.inFrame(4, 0))
        assertFalse("纵向 4 格出画", wide.inFrame(0, 4))
    }

    @Test
    fun `闸有牙_同一方向换档必须换结论`() {
        val step = 2
        assertNotEquals(
            "2 格在窄档出画、在宽档在画面内 —— 三档不是同一张表",
            Frustum(FovTier.NARROW).bandOf(step), Frustum(FovTier.WIDE).bandOf(step),
        )
    }

    @Test
    fun `计数锁_三档三态且边缘恒为内带加一格`() {
        assertEquals("FOV 三档", 3, FovTier.entries.size)
        assertEquals("可见三态", 3, Visibility.entries.size)
        FovTier.entries.forEach { fov ->
            assertEquals("${fov.label}：边缘带必须比内带宽一格", fov.inSteps + 1, fov.edgeSteps)
        }
        assertEquals("内带随档递增（窄<中<宽）", listOf(0, 1, 2), FovTier.entries.map { it.inSteps })
        assertEquals("severity：内 < 边缘 < 外", listOf(0, 1, 2), Visibility.entries.map { it.severity })
    }
}
