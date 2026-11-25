// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.hygiene

import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.Circumcision
import com.example.zhengdao.baopu.state.prepuce.RetractState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HygieneTypesTest {

    @Test
    fun `判据一_包皮垢四档与色档齐`() {
        assertEquals(
            listOf(0 to "无", 1 to "少量", 2 to "明显", 3 to "堆积"),
            SmegmaTier.entries.map { it.level to it.label },
        )
        assertEquals(listOf("白", "微黄", "黄"), SmegmaColor.entries.map { it.label })
        assertEquals(4, SmegmaTier.COUNT)
        assertThrows(IllegalArgumentException::class.java) { SmegmaTier.of(-1) }
        assertThrows(IllegalArgumentException::class.java) { SmegmaTier.of(4) }
    }

    @Test
    fun `判据一_清洁两轴各自齐全且不互相覆盖`() {
        assertEquals(4, HygieneHabit.COUNT)
        assertEquals(listOf("无意愿", "弱意愿", "中意愿", "强意愿"), HygieneHabit.entries.map { it.label })
        assertEquals(3, WashCondition.COUNT)
        assertEquals(listOf("随时可洗", "受限", "极受限"), WashCondition.entries.map { it.label })
        for (habit in HygieneHabit.entries) {
            for (condition in WashCondition.entries) {
                val state = HygieneFixtures.state(hygieneHabit = habit, washCondition = condition)
                assertEquals(habit, state.hygieneHabit)
                assertEquals(condition, state.washCondition)
            }
        }
    }

    @Test
    fun `口径_可回缩与环切直接复用804类型`() {
        assertEquals(listOf("可完全回缩", "部分回缩", "不可回缩"), RetractState.entries.map { it.label })
        assertEquals(listOf("未环切", "已环切"), Circumcision.entries.map { it.label })
        assertEquals(6, AreaLevel.entries.size)
    }

    @Test
    fun `判据四_全身洗澡洗脚下体三笔互不代替`() {
        val state = HygieneFixtures.state(
            bathFreq = WashFrequency.DAILY,
            foot = WashFrequency.OVER_WEEK,
            genitals = WashFrequency.EVERY_OTHER_DAY,
        )
        assertEquals(WashFrequency.DAILY, state.bathFreq)
        assertEquals(WashFrequency.OVER_WEEK, state.localWash.foot)
        assertEquals(WashFrequency.EVERY_OTHER_DAY, state.localWash.genitals)
        val line = HygieneRenderer.render(state, HygieneDeriver.derive(state, HygieneFixtures.context()))
        assertTrue(line.contains("全身洗澡=每日"))
        assertTrue(line.contains("洗脚=一周以上"))
        assertTrue(line.contains("下体=隔日"))
    }

    @Test
    fun `判据四_推动者四档齐且他人代做只记中性动作`() {
        assertEquals(
            listOf("自主", "被提醒", "被明确要求", "他人代做（备水／放好衣物）"),
            HygieneDriver.entries.map { it.label },
        )
        val state = HygieneFixtures.state(hygieneDriver = HygieneDriver.OTHER_DONE)
        HygieneGuard.requireNarrative(state.hygieneDriver.label)
        assertThrows(IllegalArgumentException::class.java) {
            HygieneGuard.requireNarrative("他人代做是一种施舍")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HygieneGuard.requireNarrative("备水是为了控制对方")
        }
    }

    @Test
    fun `判据五_不可算三项永远只有absent理由`() {
        val result = HygieneDeriver.derive(HygieneFixtures.state(), HygieneFixtures.context())
        assertEquals("味型不可算", result.smellType.reason)
        assertEquals("菌群不可算", result.flora.reason)
        assertEquals("不做病理性判断", result.pathologicalOdor.reason)
        assertTrue(result.unavailableLine().contains("味型=○"))
        assertTrue(result.unavailableLine().contains("菌群=○"))
        assertTrue(result.unavailableLine().contains("病理性气味=○"))
        assertFalse(result.unavailableLine().contains("value"))
    }

    @Test
    fun `判据三_观察档不会被表推档覆盖`() {
        val state = HygieneFixtures.state(
            smegmaTier = SmegmaTier.NONE,
            cleaningFreq = WashFrequency.OVER_WEEK,
            retractability = RetractState.NONE,
        )
        val context = HygieneFixtures.context(
            coverage = AreaLevel.FULLY_EXPOSED,
            warmHumid = WarmHumidTier.HOT_HUMID,
        )
        val result = HygieneDeriver.derive(state, context)
        assertEquals(SmegmaTier.NONE, state.smegmaTier)
        assertEquals(SmegmaTier.BUILDUP, result.smegmaTier.tier)
        assertNotEquals(state.smegmaTier, result.smegmaTier.tier)
        val line = HygieneRenderer.render(state, result)
        assertTrue(line.contains("包皮垢=无"))
        assertTrue(line.contains("表推包皮垢="))
        assertFalse("观察档未到明显，不许因表推而写气味", line.contains("味"))
    }

    @Test
    fun `确定性_同输入两次派生与渲染逐字一致`() {
        val state = HygieneFixtures.state(smegmaTier = SmegmaTier.OBVIOUS)
        val context = HygieneFixtures.context()
        val first = HygieneDeriver.derive(state, context)
        val second = HygieneDeriver.derive(state, context)
        assertEquals(first, second)
        assertEquals(HygieneRenderer.render(state, first), HygieneRenderer.render(state, second))
    }
}
