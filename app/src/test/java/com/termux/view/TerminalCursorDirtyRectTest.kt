package com.termux.view

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `TerminalCursorBlinker.dirtyLeftForCursor` 脏区边界的单测（台账 §B-补-49 🟡3）。
 *
 * 缺陷链路：光标停在宽字符**第二格**时 TerminalRenderer 从 startColumn = cursorCol - 1
 * 起画反色块（Renderer:226），而脏区左边界旧式从 cursorCol 起算 ⇒ 关光标那一帧
 * 左半格不在脏区、留残影。纯几何函数，JVM 可跑。
 */
class TerminalCursorDirtyRectTest {

    @Test
    fun `窄字符光标 脏区从左一格起`() {
        // cursorCol=5、fontWidth=10 ⇒ startColumn=4 ⇒ floor(40)-1 = 39
        assertEquals(39, TerminalCursorBlinker.dirtyLeftForCursor(5, 10f))
    }

    @Test
    fun `第一列光标 不退到负列`() {
        // cursorCol=0 ⇒ 钳到 0 ⇒ floor(0)-1 = -1（与旧式 0*10-1 相同，行为不变）
        assertEquals(-1, TerminalCursorBlinker.dirtyLeftForCursor(0, 10f))
    }

    @Test
    fun `宽字符第二格 脏区覆盖前一格`() {
        // cursorCol=6（宽字符第二格）⇒ startColumn=5 ⇒ 49；旧式 6*10-1=59 会把 [49,59) 漏在脏区外
        assertEquals(49, TerminalCursorBlinker.dirtyLeftForCursor(6, 10f))
    }
}
