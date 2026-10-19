// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第四十批：输入栏上方那一行「继续说完 / 重试 / 编辑上一条」的显示判据。
// 为什么单独测这个纯函数：什么时候出现，是这批最容易漂的地方
// （跑着的时候还显示 ⇒ 用户能在半路改历史；没会话就显示编辑 ⇒ 点了没反应）。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Test

class TailActionsTest {

    @Test
    fun `跑着的时候一个动作都不给`() {
        assertEquals(
            emptySet<TailAction>(),
            tailActions(busy = true, hasSession = true, truncated = true, aborted = true),
        )
    }

    @Test
    fun `平常只显示编辑上一条`() {
        // 口径：只要有一轮对话在，就允许改「最近一条」；继续/重试只在出事时出现。
        assertEquals(
            setOf(TailAction.EDIT),
            tailActions(busy = false, hasSession = true, truncated = false, aborted = false),
        )
    }

    @Test
    fun `被截断就有继续说完_编辑一直在`() {
        assertEquals(
            setOf(TailAction.CONTINUE, TailAction.EDIT),
            tailActions(busy = false, hasSession = true, truncated = true, aborted = false),
        )
    }

    @Test
    fun `跑挂了就有重试`() {
        assertEquals(
            setOf(TailAction.RETRY, TailAction.EDIT),
            tailActions(busy = false, hasSession = true, truncated = false, aborted = true),
        )
    }

    @Test
    fun `截断与失败同时成立_两个都给`() {
        assertEquals(
            setOf(TailAction.CONTINUE, TailAction.RETRY, TailAction.EDIT),
            tailActions(busy = false, hasSession = true, truncated = true, aborted = true),
        )
    }

    @Test
    fun `没会话就没有编辑上一条`() {
        assertEquals(
            setOf(TailAction.CONTINUE),
            tailActions(busy = false, hasSession = false, truncated = true, aborted = false),
        )
    }
}
