// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零六批 · `shouldAsk`（左相弹卡前的「少问」判据）的造场景测试。
//
// 判据口径（派工单 406）：**造场景 ⇒ 行为改变**——同一张问卡，换一个调解器状态，
// 弹/压必须翻面；且每一条都带反例（照常场景不得误压）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediatorShouldAskTest {

    private val profile = PatienceProfile()

    /** 造场景：松弛（CALM、没暂停、没问过）——接线前的世界。 */
    private val calm = HarmonyMediator.State()

    /** 造场景：用户说了「不想弄了」。 */
    private fun paused(): HarmonyMediator.State = HarmonyMediator.State(paused = true)

    /** 造场景：熔断（LOOP）。 */
    private fun loop(): HarmonyMediator.State = HarmonyMediator.State(mood = HarmonyMediator.Mood.LOOP)

    @Test
    fun `松弛场景_照常弹_调解器没参与`() {
        val advice = MediatorDecide.shouldAsk(calm, profile, askedSoFar = 0)
        assertTrue(advice.allowed)
        assertFalse(advice.effective)
        assertEquals("", advice.reason)
        assertEquals("", advice.line())
    }

    @Test
    fun `造暂停场景_同一张卡从弹翻成压`() {
        val before = MediatorDecide.shouldAsk(calm, profile, 0)
        val after = MediatorDecide.shouldAsk(paused(), profile, 0)
        assertTrue(before.allowed)
        assertFalse(after.allowed)
        assertTrue(after.effective)
        assertTrue(after.line().contains("暂停"))
    }

    @Test
    fun `造熔断场景_同一张卡从弹翻成压`() {
        val after = MediatorDecide.shouldAsk(loop(), profile, 0)
        assertFalse(after.allowed)
        assertTrue(after.effective)
        assertTrue(after.reason.contains("熔断"))
    }

    @Test
    fun `造追问到耐心阈值的场景_预算用尽从弹翻成压`() {
        // 默认画像 askTolerance=2：本回合问到第 2 张还在界内，第 3 张压下。
        val second = MediatorDecide.shouldAsk(calm, profile, askedSoFar = 1)
        val third = MediatorDecide.shouldAsk(calm, profile, askedSoFar = 2)
        assertTrue(second.allowed)
        assertFalse(third.allowed)
        assertTrue(third.effective)
        assertTrue(third.reason.contains("耐心阈值"))
    }

    @Test
    fun `负例_必问档在熔断加暂停加预算耗尽下也必须放行_调解器无权过问`() {
        val worst = HarmonyMediator.State(
            mood = HarmonyMediator.Mood.LOOP,
            paused = true,
        )
        val advice = MediatorDecide.shouldAsk(worst, profile, askedSoFar = 99, mustAsk = true)
        assertTrue("必问档不许被压（铁律：只少问，不压必问）", advice.allowed)
        assertFalse(advice.effective)
        assertTrue(advice.reason.contains("无权"))
    }

    @Test
    fun `负例_必问档不占耐心预算`() {
        // 必问放行不计入 askedSoFar 的账（计数归接线层，判据只管给定账目下的结论）；
        // 这里钉的是：同一账目下，必问与普通问的结局分得开。
        val busy = MediatorDecide.shouldAsk(calm, profile, askedSoFar = 2)
        val must = MediatorDecide.shouldAsk(calm, profile, askedSoFar = 2, mustAsk = true)
        assertFalse(busy.allowed)
        assertTrue(must.allowed)
    }

    @Test
    fun `预算读数随建议一起给_对拍口径一致`() {
        val advice = MediatorDecide.shouldAsk(calm, profile, askedSoFar = 1)
        assertEquals(2, advice.budget.tolerance)
        assertEquals(1, advice.budget.remaining)
    }
}
