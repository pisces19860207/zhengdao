// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零六批 · `decide`（只给排序建议）的造场景测试。
//
// 判据口径（派工单 406）：**造场景 ⇒ 行为改变**——同一组问卡（重打字的在前），
// 档位升起来必须建议「省力的在前」；松弛与熔断一律照旧；同重保持原序（最小干预）。
// 「只给建议」也钉在这里：产出是下标排列，不丢卡、不改卡、卡数不变。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediatorDecideOrderTest {

    /** 造场景：三问——要打字的(2)、点一下的(0)、数字(1)。 */
    private val weights = listOf(2, 0, 1)

    @Test
    fun `CALM照旧_模型的序不动`() {
        val advice = MediatorDecide.decide(weights, HarmonyMediator.State())
        assertFalse(advice.changed)
        assertEquals(listOf(0, 1, 2), advice.order)
        assertEquals("", advice.line)
    }

    @Test
    fun `造紧张场景_同一组从模型序翻成省力序`() {
        val calm = MediatorDecide.decide(weights, HarmonyMediator.State())
        val strained = MediatorDecide.decide(
            weights,
            HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED),
        )
        assertFalse(calm.changed)
        assertTrue(strained.changed)
        assertEquals(listOf(1, 2, 0), strained.order)
        // 重排后第一张是点一下的（重量 0），要打字的排最后。
        assertEquals(0, weights[strained.order.first()])
        assertEquals(2, weights[strained.order.last()])
        assertTrue(strained.line.contains("省力"))
    }

    @Test
    fun `CAREFUL也建议_与guidance的回答更短同一方向`() {
        val advice = MediatorDecide.decide(
            weights,
            HarmonyMediator.State(mood = HarmonyMediator.Mood.CAREFUL),
        )
        assertTrue(advice.changed)
        assertEquals(listOf(1, 2, 0), advice.order)
    }

    @Test
    fun `造熔断与暂停场景_排序不参与_压卡归shouldAsk`() {
        val loop = MediatorDecide.decide(
            weights,
            HarmonyMediator.State(mood = HarmonyMediator.Mood.LOOP),
        )
        val paused = MediatorDecide.decide(weights, HarmonyMediator.State(paused = true))
        assertFalse(loop.changed)
        assertFalse(paused.changed)
    }

    @Test
    fun `负例_已经省力在前的序不许翻面_最小干预`() {
        val advice = MediatorDecide.decide(
            listOf(0, 1, 2),
            HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED),
        )
        assertFalse(advice.changed)
    }

    @Test
    fun `同重保持原序_稳定不洗牌`() {
        val advice = MediatorDecide.decide(
            listOf(2, 0, 0, 1),
            HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED),
        )
        assertEquals(listOf(1, 2, 3, 0), advice.order)
    }

    @Test
    fun `只给建议的机械含义_排列不丢卡`() {
        val advice = MediatorDecide.decide(
            weights,
            HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED),
        )
        assertEquals(weights.indices.toList(), advice.order.sorted())
        assertEquals(weights.size, advice.order.size)
    }

    @Test
    fun `单问与空组_没有排序这回事`() {
        assertTrue(!MediatorDecide.decide(listOf(2), HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED)).changed)
        assertTrue(!MediatorDecide.decide(emptyList(), HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED)).changed)
    }
}
