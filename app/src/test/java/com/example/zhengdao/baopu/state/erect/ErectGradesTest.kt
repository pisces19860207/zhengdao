// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百一十批 · 五套档位枚举：闭集、档序连续、label 逐字、反查。
package com.example.zhengdao.baopu.state.erect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ErectGradesTest {

    @Test
    fun `三型闭集_档序连续_label逐字`() {
        assertEquals(3, GrowthType.COUNT)
        assertEquals(listOf("增长型", "平缓型", "中间型"), GrowthType.entries.map { it.label })
        assertEquals((0..2).toList(), GrowthType.entries.map { it.level })
    }

    @Test
    fun `增长档四档_档序0到3连续`() {
        assertEquals(4, GrowthTier.COUNT)
        assertEquals(listOf("几乎不变", "略增", "明显增", "大幅增"), GrowthTier.entries.map { it.label })
        assertEquals((0..3).toList(), GrowthTier.entries.map { it.level })
    }

    @Test
    fun `角度四档_方向逐字`() {
        assertEquals(4, ErectAngle.COUNT)
        assertEquals(listOf("上翘", "略上", "平", "略下"), ErectAngle.entries.map { it.label })
    }

    @Test
    fun `硬度四级_对齐EHS逐字_无无反应档`() {
        assertEquals(4, HardnessTier.COUNT)
        assertEquals(listOf("稍硬", "可插入", "坚挺", "极硬"), HardnessTier.entries.map { it.label })
        assertEquals((1..4).toList(), HardnessTier.entries.map { it.ehs })
        assertThrows(IllegalArgumentException::class.java) { HardnessTier.of(0) }
    }

    @Test
    fun `廓形四档_逐字`() {
        assertEquals(4, ErectProfile.COUNT)
        assertEquals(listOf("变直", "弯曲更显", "冠更外扩", "不变"), ErectProfile.entries.map { it.label })
    }

    @Test
    fun `各档of反查逐档对_越界即抛`() {
        GrowthType.entries.forEach { assertEquals(it, GrowthType.of(it.level)) }
        GrowthTier.entries.forEach { assertEquals(it, GrowthTier.of(it.level)) }
        ErectAngle.entries.forEach { assertEquals(it, ErectAngle.of(it.level)) }
        HardnessTier.entries.forEach { assertEquals(it, HardnessTier.of(it.ehs)) }
        FullnessTier.entries.forEach { assertEquals(it, FullnessTier.of(it.fullness)) }
        ErectProfile.entries.forEach { assertEquals(it, ErectProfile.of(it.level)) }
        assertThrows(IllegalArgumentException::class.java) { GrowthTier.of(9) }
        assertThrows(IllegalArgumentException::class.java) { ErectAngle.of(-1) }
    }

    @Test
    fun `档名无数值_五套label里不出现数码与敏感词`() {
        val labels = GrowthType.entries.map { it.label } + GrowthTier.entries.map { it.label } +
            ErectAngle.entries.map { it.label } + HardnessTier.entries.map { it.label } +
            FullnessTier.entries.map { it.label } + ErectProfile.entries.map { it.label }
        labels.forEach { label ->
            assertTrue("档名不得含数码：$label", label.none { it.isDigit() })
            ErectGuard.rejectFactor(label)
            ErectGuard.rejectMerged(label)
        }
    }
}
