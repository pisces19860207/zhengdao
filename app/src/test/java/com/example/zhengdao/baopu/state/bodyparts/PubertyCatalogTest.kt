// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百三十九批 · 闭集与档位：五维、四档、五里程碑，条数与顺序都钉死。
package com.example.zhengdao.baopu.state.bodyparts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PubertyCatalogTest {

    @Test
    fun `五维闭集逐字`() {
        assertEquals("五维条数", 5, PubertyDim.entries.size)
        assertEquals(PubertyDim.COUNT, PubertyDim.entries.size)
        assertEquals(
            listOf("生长突增", "变声", "体毛", "体型转变", "社会侧"),
            PubertyDim.entries.map { it.label },
        )
    }

    @Test
    fun `四档档序连续且档名无数字`() {
        assertEquals("四档", 4, PubertyTier.entries.size)
        assertEquals(PubertyTier.COUNT, PubertyTier.entries.size)
        assertEquals("档序从 0 连续", listOf(0, 1, 2, 3), PubertyTier.entries.map { it.order })
        val digits = "\\d".toRegex()
        PubertyTier.entries.forEach { tier ->
            assertFalse("档名不许带数字：" + tier.label, digits.containsMatchIn(tier.label))
        }
        assertTrue(PubertyTier.SETTLED.atLeast(PubertyTier.EARLY))
        assertFalse(PubertyTier.NOT_STARTED.atLeast(PubertyTier.EARLY))
        val reading = PubertyTier.entries.joinToString("/") { it.order.toString() + ":" + it.label }
        println("CATALOG-READING tiers=" + reading)
    }

    @Test
    fun `里程碑顺序表逐字`() {
        assertEquals("五条", 5, PubertyMilestone.entries.size)
        assertEquals(PubertyMilestone.COUNT, PubertyMilestone.entries.size)
        assertEquals(
            "常见先后：生长突增 → 体毛腋毛 → 变声喉结 → 肌肉量与肩宽 → 体味痤疮",
            listOf("生长突增", "体毛腋毛", "变声喉结", "肌肉量与肩宽", "体味痤疮"),
            MilestoneOrder.SEQUENCE.map { it.label },
        )
        assertEquals("可换文案", "个体差异大、先后可换", MilestoneOrder.SWAPPABLE)
        assertEquals(listOf(0, 1, 2, 3, 4), MilestoneOrder.SEQUENCE.map { it.order })
        println(MilestoneOrder.lines().joinToString("\n") + "\n" + MilestoneOrder.note())
    }

    @Test
    fun `换序只标注不判红`() {
        assertTrue(MilestoneOrder.isCommonOrder(MilestoneOrder.SEQUENCE))
        assertFalse(MilestoneOrder.swapped(MilestoneOrder.SEQUENCE))
        val swapped = PubertyFixtures.swappedOrder().milestoneOrder
        assertFalse("体毛先行不是常见顺序", MilestoneOrder.isCommonOrder(swapped))
        assertTrue("但它是允许的个体差异", MilestoneOrder.swapped(swapped))
        assertTrue("换序的单子照常构造", PubertyFixtures.swappedOrder().swapped())
        val text = PubertySheet.render(PubertyFixtures.swappedOrder(), PubertyFixtures.nonSexualMinor)
        assertTrue("换序要标注", text.contains("本次：换过顺序"))
        assertTrue("换序不该变红", text.contains("汇总：过 1／须核 0／红 0"))
        println(text)
    }

    @Test
    fun `缺一维构造即抛`() {
        val partial = PubertyFixtures.spreading - PubertyDim.SOCIAL_SIDE
        val thrown = runCatching {
            PubertyHistory(tiers = partial)
        }.exceptionOrNull()
        assertTrue("缺维必须抛 IllegalArgumentException，实际 " + thrown, thrown is IllegalArgumentException)
        println("CATALOG-READING missing-dim rejected: " + (thrown?.message ?: "未抛"))
    }

    @Test
    fun `里程碑缺一条构造即抛`() {
        val short = MilestoneOrder.SEQUENCE.dropLast(1)
        val thrown = runCatching {
            PubertyHistory(tiers = PubertyFixtures.spreading, milestoneOrder = short)
        }.exceptionOrNull()
        assertTrue("里程碑缺条必须抛", thrown is IllegalArgumentException)
    }

    @Test
    fun `五维与里程碑是两张表不共用行`() {
        val dims = PubertyDim.entries.map { it.label }.toSet()
        val milestones = MilestoneOrder.SEQUENCE.map { it.label }.toSet()
        assertFalse("两张表不许是同一张", dims == milestones)
        assertEquals("五维表只五条", 5, dims.size)
        assertEquals("里程碑表只五条", 5, milestones.size)
        // 肌肉量与肩宽、体味痤疮只在里程碑里；体型转变、社会侧只在五维里。
        assertTrue(milestones.contains("肌肉量与肩宽"))
        assertFalse(dims.contains("肌肉量与肩宽"))
        assertTrue(dims.contains("社会侧"))
        assertFalse(milestones.contains("社会侧"))
    }

    @Test
    fun `档位表五行_里程碑表六行`() {
        val history = PubertyFixtures.boy14()
        val tiers = PubertySheet.tierTable(history).lines().filter { it.isNotBlank() }
        assertEquals("五维五行", 5, tiers.size)
        assertEquals("生长突增：渐显", tiers[0])
        val milestones = PubertySheet.milestoneTable(history).lines().filter { it.isNotBlank() }
        assertEquals("五条 + 一行注", 6, milestones.size)
        assertTrue(milestones.last().startsWith(MilestoneOrder.SWAPPABLE))
        println(PubertySheet.tierTable(history) + "\n" + PubertySheet.milestoneTable(history))
    }
}
