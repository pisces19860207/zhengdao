// 独立开发声明：本文件为本项目从零编写。被测类是 Termux 官方 terminal-emulator
// （Apache-2.0，v0.119.0-beta.3，聚合分发见 PROVENANCE.md）经本项目打过补丁的
// `TerminalSession`；本测试钉的正是 2026-10-12 那处补丁（🔴2 未 spawn 就被 kill）。
package com.termux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `TerminalSession` 存活判据的单测。
 *
 * `mShellPid` 的哨兵值（上游字段注释里写着、构造器也照此留默认 0）：
 * **0 = 还没启动**、-1 = 已经结束。所以 [TerminalSession.isRunning] 只在 `> 0` 时为真。
 * 旧写法 `!= -1` 把"还没 spawn"当成"在跑"，于是 `finishIfRunning()` 会执行
 * `Os.kill(0, SIGKILL)`；POSIX 里 pid 0 是**调用者自己的进程组**，等于 App 自杀。
 *
 * 钉住它不需要 Robolectric、也不需要真机：构造器不碰 Android API，
 * spawn 发生在 `updateSize` 之后 —— 一个新构造的会话天然就是"还没启动"那个状态。
 */
class TerminalSessionTest {

    private fun freshSession() = TerminalSession(
        "/bin/sh", "/", emptyArray<String>(), emptyArray<String>(), 1000, null
    )

    @Test
    fun `还没 spawn 的会话 PID 是 0 且不算在跑`() {
        val session = freshSession()
        assertEquals(0, session.pid)
        assertFalse(session.isRunning)
    }

    @Test
    fun `还没 spawn 时 finishIfRunning 不得发信号`() {
        val session = freshSession()
        // 旧代码：isRunning() 为 true ⇒ 走 Os.kill(0, SIGKILL)。单测里 android.jar 的
        // Os.kill 是桩（抛 "not mocked"/"Stub!"），所以这条用例在旧代码上必然失败；
        // 修好后它只是一次"什么都不做"的早返回。真机上的表现是整页 FATAL。
        session.finishIfRunning()
        assertEquals(0, session.pid)
        assertFalse(session.isRunning)
    }
}
