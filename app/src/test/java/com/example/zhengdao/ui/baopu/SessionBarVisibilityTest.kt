// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.agent.RunState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四十九批 · 任务 A 的纯函数用例：`sessionBarVisible(state, interruptedStep, hasSession, notice)`。
 *
 * 这一条判断原来只写在组合函数里（两个各自独立的 if），空态到底占不占地方没法在 JVM 里问出来。
 * 抽成纯函数后，「什么时候渲染」这条判据能被单测钉死（派工单 §1 的六个判点，全在这里）。
 *
 * 判点：① 全空 ⇒ 不渲染；② 有中断 ∧ 非 busy ∧ 有会话 ⇒ 渲染；③ busy 时有中断 ⇒ 不渲染（正在跑就不是"中断"）；
 * ④ 无会话时有中断 ⇒ 不渲染；⑤ notice 非空 ⇒ 渲染（哪怕没中断）；⑥ notice 是空串／全空格 ⇒ 不渲染。
 */
class SessionBarVisibilityTest {

    private fun visible(
        state: RunState = RunState.IDLE,
        interruptedStep: Int = -1,
        hasSession: Boolean = true,
        notice: String? = null,
    ): Boolean = sessionBarVisible(
        state = state,
        interruptedStep = interruptedStep,
        hasSession = hasSession,
        notice = notice,
    )

    @Test
    fun `全空_不渲染`() {
        assertFalse("没中断、没 notice ⇒ 那条 16dp 灰条不该存在", visible())
        assertFalse(visible(state = RunState.DONE, hasSession = false))
        assertFalse(visible(state = RunState.ABORTED, interruptedStep = -1, notice = ""))
    }

    @Test
    fun `有中断且非busy且有会话_渲染`() {
        for (state in listOf(RunState.IDLE, RunState.DONE, RunState.ABORTED)) {
            assertTrue("$state 时中断提示要看得见", visible(state = state, interruptedStep = 3))
        }
    }

    @Test
    fun `busy时中断_不渲染`() {
        for (state in listOf(
            RunState.THINKING, RunState.ACTING, RunState.COMPRESSING, RunState.AWAIT_CONFIRM,
        )) {
            assertFalse("$state 正在跑 —— 这时候不是「中断」，占着首屏反而误导", visible(state = state, interruptedStep = 3))
        }
    }

    @Test
    fun `无会话时中断_不渲染`() {
        assertFalse("会话都没开，interruptedStep 是上个会话留下的脏值，不许据此占位", visible(interruptedStep = 3, hasSession = false))
    }

    @Test
    fun `notice非空_渲染`() {
        assertTrue("引擎给的人话（AskUser／错误号口径）必须显示", visible(notice = "这一步要过目"))
        assertTrue("busy 时只要 notice 非空也照样显示", visible(state = RunState.ACTING, notice = "压缩中"))
        assertTrue(
            "两个条件同时成立也只显示一次（外层是个或）",
            visible(interruptedStep = 7, notice = "接着上面那句"),
        )
    }

    @Test
    fun `notice是空白_不算内容`() {
        for (notice in listOf("", " ", "   ", "\t", " \t ")) {
            assertFalse("notice=「${notice.replace("\t", "\\t")}」按空处理", visible(notice = notice))
        }
    }
}
