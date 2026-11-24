// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
package com.example.zhengdao.baopu.state.presence

import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceGuardTest {

    @Test
    fun `中性照护文字放行`() {
        PresenceGuard.requireNeutral("给孩子洗澡换衣是日常照护")
        PresenceGuard.requireNeutral("母亲给男婴喂奶")
        PresenceGuard.requireNeutral("澡堂里排队泡池搓澡")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `分性别设限被拦`() {
        PresenceGuard.rejectStigmatizingCare("父亲不能给女儿洗澡")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `污名化措辞被拦`() {
        PresenceGuard.rejectStigmatizingCare("帮孩子洗澡是有问题的")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `群体判断被拦`() {
        PresenceGuard.rejectGroupJudgment("南方人都不在乎")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `行为指导被拦`() {
        PresenceGuard.rejectBehaviorAdvice("必须穿整齐才能出去")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `无外人时穿戴整齐被拦`() {
        PresenceGuard.requireUnderwearEnough(CanBeNude.FULLY_DRESSED, false)
    }

    @Test
    fun `有外人时穿戴整齐放行_穿内裤始终放行`() {
        PresenceGuard.requireUnderwearEnough(CanBeNude.FULLY_DRESSED, true)
        PresenceGuard.requireUnderwearEnough(CanBeNude.UNDERWEAR, false)
        PresenceGuard.requireUnderwearEnough(CanBeNude.NATURAL, false)
        PresenceGuard.requireUnderwearEnough(CanBeNude.WRAPPED, false)
        assertTrue(CanBeNude.UNDERWEAR.level == CanBeNude.DEFAULT_LEVEL)
    }
}
