// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 第八百六十五批 · 骨点单测试：正式入口三项齐、裸构造器负例可表达。
package com.example.zhengdao.baopu.state.frame

import com.example.zhengdao.baopu.state.bodytype.BodyFatTier
import com.example.zhengdao.baopu.state.bodytype.SkeletonTier
import com.example.zhengdao.baopu.state.view.Octant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameReadingTest {

    @Test
    fun `正式入口十四点全推导三项齐`() {
        val p = FrameFixtures.person(fat = BodyFatTier.MID)
        val frame = p.frame
        assertEquals(FrameCatalog.POINTS.size, frame.size)
        frame.all().forEach { reading ->
            assertTrue(reading.point.label + " 可见档越界", reading.visible.level in 0..VisibleGrade.LAST)
            assertTrue(reading.point.label + " 触及档越界", reading.palpable.level in 0..PalpableGrade.LAST)
            assertTrue(reading.point.label + " 缺八向", reading.relative in Octant.entries)
            assertEquals(
                FrameCatalog.visibleOf(reading.point, BodyFatTier.MID),
                reading.visible,
            )
            assertEquals(
                FrameCatalog.palpableOf(reading.point, BodyFatTier.MID),
                reading.palpable,
            )
        }
    }

    @Test
    fun `三点群都能从目录表取到`() {
        FrameRegion.entries.forEach { region ->
            assertTrue(region.label + " 群空", FrameCatalog.pointsOf(region).isNotEmpty())
        }
        assertEquals(
            FrameCatalog.POINTS.size,
            FrameRegion.entries.sumOf { FrameCatalog.pointsOf(it).size },
        )
    }

    @Test
    fun `出口每行都有区名与显向触三项`() {
        val frame = FrameFixtures.person().frame
        val lines = frame.line().lines()
        assertEquals(FrameCatalog.POINTS.size, lines.size)
        lines.forEach { line ->
            assertTrue(line, line.contains("（") && line.contains("）"))
            assertTrue(line, line.contains("显 "))
            assertTrue(line, line.contains("向 "))
            assertTrue(line, line.contains("触 "))
        }
    }

    @Test
    fun `区字段和子区字段分两行落账`() {
        val reading = FrameFixtures.person().frame.reading(FramePoint.CLAVICLE)
        val zone = reading.zoneLine()
        assertTrue(zone, zone.contains("zoneId=" + FRAME_ZONE_NONE))
        assertTrue(zone, zone.contains("subzoneId=无"))
        assertTrue(zone, zone.contains(ZONE_TRUNK))
    }

    @Test
    fun `负例_裸构造器能造体脂高锁骨分明`() {
        val base = FrameFixtures.person(fat = BodyFatTier.HIGH)
        val bad = FrameFixtures.withVisible(base, FramePoint.CLAVICLE, VisibleGrade.PRONOUNCED)
        assertEquals(VisibleGrade.PRONOUNCED, bad.frame.reading(FramePoint.CLAVICLE).visible)
        assertFalse(bad.frame.reading(FramePoint.CLAVICLE).visible ==
            FrameCatalog.visibleOf(FramePoint.CLAVICLE, BodyFatTier.HIGH))
    }

    @Test
    fun `人话出口带体脂与骨架真枚举档名`() {
        val line = FrameFixtures.person(fat = BodyFatTier.HIGH, skeleton = SkeletonTier.WIDE)
            .line().lines().first()
        assertTrue(line, line.contains("体脂 高"))
        assertTrue(line, line.contains("骨架 宽"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负例_空骨点单即拒`() {
        BoneFrame(emptyList())
    }
}
