// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 第八百一十五批 · 档位与目录表测试：闭集、36 组合、少见表。
package com.example.zhengdao.baopu.state.bodytype

import com.example.zhengdao.baopu.state.bodyparts.FatBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildCatalogTest {

    @Test
    fun `五维闭集_档数固定`() {
        assertEquals(3, SkeletonTier.COUNT)
        assertEquals(4, BodyFatTier.COUNT)
        assertEquals(3, MuscleTier.COUNT)
        assertEquals(3, AgeTier.COUNT)
        assertEquals(3, BodyStateTier.COUNT)
        assertEquals(3, Commonness.COUNT)
        assertEquals(4, Basis.COUNT)
        assertEquals(3, SectionGrade.COUNT)
    }

    @Test
    fun `档序必须从零连续`() {
        val levelLists: List<List<Int>> = listOf(
            SkeletonTier.entries.map { it.level }, BodyFatTier.entries.map { it.level },
            MuscleTier.entries.map { it.level }, AgeTier.entries.map { it.level },
            BodyStateTier.entries.map { it.level }, SectionGrade.entries.map { it.level },
        )
        levelLists.forEach { levels ->
            assertEquals(levels, levels.indices.toList())
        }
    }

    @Test
    fun `档名零数字`() {
        val all = SkeletonTier.entries.map { it.label } + BodyFatTier.entries.map { it.label } +
            MuscleTier.entries.map { it.label } + AgeTier.entries.map { it.label } +
            BodyStateTier.entries.map { it.label }
        all.forEach { label -> assertTrue("$label 不该有数字", label.none { it.isDigit() }) }
    }

    @Test
    fun `of函数越界钳到边界不抛`() {
        assertEquals(SkeletonTier.NARROW, SkeletonTier.of(-3))
        assertEquals(SkeletonTier.WIDE, SkeletonTier.of(99))
        assertEquals(BodyFatTier.VERY_HIGH, BodyFatTier.of(99))
    }

    @Test
    fun `组合表36个且互异`() {
        assertEquals(36, BuildCatalog.COMBOS.size)
        assertEquals(36, BuildCatalog.COMBOS.toSet().size)
        assertEquals(36, BuildCatalog.ENTRIES.size)
    }

    @Test
    fun `常见程度三档齐全不许全同`() {
        val degrees = BuildCatalog.ENTRIES.values.map { it.commonness }.toSet()
        assertEquals(3, degrees.size)
        assertTrue(degrees.containsAll(Commonness.entries))
    }

    @Test
    fun `少见至少三例且各给一句为什么`() {
        assertTrue("少见 ≥3", BuildCatalog.RARE_ENTRIES.size >= 3)
        BuildCatalog.RARE_ENTRIES.forEach { e ->
            assertTrue(e.key.label + " 要有原因", e.why.isNotBlank())
            assertFalse("原因里不许评价词", BuildGuard.valueHits(e.why).isNotEmpty())
        }
    }

    @Test
    fun `低脂高肌必标少见_不当默认`() {
        val lfh = BuildCatalog.ENTRIES.values.first { it.key.lowFatHighMuscle }
        assertEquals(Commonness.RARE, lfh.commonness)
        assertEquals(Commonness.COMMON, BuildCatalog.ENTRIES.getValue(BuildCatalog.DEFAULT_COMBO).commonness)
    }

    @Test
    fun `默认组合就是中中中`() {
        assertEquals(SkeletonTier.MID, BuildCatalog.DEFAULT_COMBO.skeleton)
        assertEquals(BodyFatTier.MID, BuildCatalog.DEFAULT_COMBO.fat)
        assertEquals(MuscleTier.MID, BuildCatalog.DEFAULT_COMBO.muscle)
    }

    @Test
    fun `三轴映射单调_体脂封顶不高造档`() {
        assertTrue(BuildCatalog.widthOf(SkeletonTier.NARROW).level < BuildCatalog.widthOf(SkeletonTier.WIDE).level)
        assertTrue(BuildCatalog.girthOf(MuscleTier.SCANT).level < BuildCatalog.girthOf(MuscleTier.AMPLE).level)
        assertEquals(BuildCatalog.thicknessOf(BodyFatTier.HIGH), BuildCatalog.thicknessOf(BodyFatTier.VERY_HIGH))
        assertEquals(FatBand.HIGHER, BuildCatalog.mapToFatBand(BodyFatTier.VERY_HIGH))
        assertEquals(4, FatBand.COUNT)
    }

    @Test
    fun `体脂四档映射805不降档`() {
        val levels = BodyFatTier.entries.map { BuildCatalog.mapToFatBand(it).level }
        assertEquals(levels, levels.sorted())
    }
}
