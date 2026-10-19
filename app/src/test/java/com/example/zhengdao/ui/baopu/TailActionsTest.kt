// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第四十批：输入栏上方那一行「继续说完 / 重试」的显示判据。
// 为什么单独测这个纯函数：什么时候出现，是这批最容易漂的地方
// （跑着的时候还显示 ⇒ 用户能在半路重发；平常还显示 ⇒ 挡屏幕——用户明确说过不要）。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Test

class TailActionsTest {

    @Test
    fun `跑着的时候一个动作都不给`() {
        assertEquals(
            emptySet<TailAction>(),
            tailActions(busy = true, truncated = true, aborted = true),
        )
    }

    @Test
    fun `平常什么都不显示`() {
        // 用户 2026-10-19：「就不这么显化了，有点挡手机的屏幕」——平常这一行整行不存在。
        assertEquals(
            emptySet<TailAction>(),
            tailActions(busy = false, truncated = false, aborted = false),
        )
    }

    @Test
    fun `被截断就有继续说完`() {
        assertEquals(
            setOf(TailAction.CONTINUE),
            tailActions(busy = false, truncated = true, aborted = false),
        )
    }

    @Test
    fun `跑挂了就有重试`() {
        assertEquals(
            setOf(TailAction.RETRY),
            tailActions(busy = false, truncated = false, aborted = true),
        )
    }

    @Test
    fun `截断与失败同时成立_两个都给`() {
        assertEquals(
            setOf(TailAction.CONTINUE, TailAction.RETRY),
            tailActions(busy = false, truncated = true, aborted = true),
        )
    }
}
