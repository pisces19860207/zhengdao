// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零六批 · `budgetFor`（耐心阈值，让步）的造场景测试。
//
// 判据口径（派工单 406）：**造场景 ⇒ 行为改变**——同一画像、同一张问卡账，
// 换一个档位，阈值必须动；用户松口（复位/回落 CALM）必须让预算回满（让步，不记仇）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediatorBudgetTest {

    @Test
    fun `同一画像_档位升起来阈值收下去`() {
        val calm = MediatorDecide.budgetFor(PatienceProfile(), HarmonyMediator.State(), 0)
        val strained = MediatorDecide.budgetFor(
            PatienceProfile(),
            HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED),
            0,
        )
        val loop = MediatorDecide.budgetFor(
            PatienceProfile(),
            HarmonyMediator.State(mood = HarmonyMediator.Mood.LOOP),
            0,
        )
        assertEquals(2, calm.tolerance)
        assertEquals(1, strained.tolerance)
        assertEquals(0, loop.tolerance)
    }

    @Test
    fun `CAREFUL不收档_回灌只说回答更短_预算不动`() {
        val careful = MediatorDecide.budgetFor(
            PatienceProfile(),
            HarmonyMediator.State(mood = HarmonyMediator.Mood.CAREFUL),
            0,
        )
        assertEquals(2, careful.tolerance)
    }

    @Test
    fun `收紧不越零_STRAINED碰上阈值本来就是1的画像`() {
        val tight = PatienceProfile(askTolerance = PatienceProfile.MIN_ASK_TOLERANCE)
        val strained = MediatorDecide.budgetFor(
            tight,
            HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED),
            0,
        )
        assertEquals(0, strained.tolerance)
    }

    @Test
    fun `已问的张数吃预算_负数钳回零`() {
        val budget = MediatorDecide.budgetFor(
            PatienceProfile(),
            HarmonyMediator.State(),
            askedSoFar = 5,
        )
        assertEquals(0, budget.remaining)
        assertTrue(budget.exhausted)
    }

    @Test
    fun `造让步场景_用户松口档位回CALM_预算自动回满`() {
        val strained = HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED)
        val relaxed = HarmonyMediator.State() // 复位/两次成功后的新态（observe 的 isReset 分支产物）
        // 同样问过 1 张：紧张档阈值收成 1 ⇒ 已用尽；松口后阈值回 2 ⇒ 还有余量。
        val before = MediatorDecide.budgetFor(PatienceProfile(), strained, askedSoFar = 1)
        val after = MediatorDecide.budgetFor(PatienceProfile(), relaxed, askedSoFar = 1)
        assertTrue(before.exhausted)
        assertFalse("让步：让的不是特判，是档位回原位预算跟着回", after.exhausted)
        assertEquals(PatienceProfile.DEFAULT_ASK_TOLERANCE, after.tolerance)
    }

    @Test
    fun `画像的保守闸与关机口径原样继承_不抄第二遍`() {
        // 关机（enabled=false）⇒ 一律默认阈值，手工值不生效（238 的口径）。
        val off = MediatorDecide.budgetFor(
            PatienceProfile(askTolerance = 3, enabled = false),
            HarmonyMediator.State(),
            0,
        )
        assertEquals(PatienceProfile.DEFAULT_ASK_TOLERANCE, off.tolerance)
        // 手工值过保守闸后生效（MIN..MAX 之内）。
        val hand = MediatorDecide.budgetFor(
            PatienceProfile(askTolerance = 3),
            HarmonyMediator.State(),
            0,
        )
        assertEquals(3, hand.tolerance)
    }

    @Test
    fun `依据一句带上三个数_可查`() {
        val budget = MediatorDecide.budgetFor(
            PatienceProfile(),
            HarmonyMediator.State(mood = HarmonyMediator.Mood.STRAINED),
            askedSoFar = 1,
        )
        assertTrue(basisContains(budget.basis, "1", "2", "1"))
    }

    /** 依据句里三个数（阈值/画像基值/已问）都在（§判据：每笔可查）。 */
    private fun basisContains(basis: String, vararg cells: String): Boolean =
        cells.all { basis.contains(it) } && basis.contains("耐心阈值")
}
