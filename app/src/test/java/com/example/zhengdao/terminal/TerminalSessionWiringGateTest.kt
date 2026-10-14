package com.example.zhengdao.terminal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 终端会话结束通道接线的**文本门禁**（台账 §B-补-49 🟢4 / 🟢6，2026-10-14）。
 *
 * 为什么是文本断言：涉及 `TerminalActivity`（要 Context/View）与 `SessionManager`（要
 * pty 会话）的 Android 链路，JVM 里造不出这套依赖；真机「会话结束看到 Toast / 光标是
 * 细竖线」两条读数由蓝总管采。这里只断言**接线与取值还在源码里**，防被悄悄删回去。
 */
class TerminalSessionWiringGateTest {

    /** Gradle 单测的工作目录是模块目录 `app/`（同 TerminalGestureGateTest 的约定）。 */
    private fun read(rel: String): String =
        File(rel).let { if (it.exists()) it else File("app/$rel") }.readText()

    @Test
    fun `onSessionDied 必须有赋值点（死钩子不得复活）`() {
        val activity = read("src/main/java/com/example/zhengdao/TerminalActivity.kt")
        assertTrue(
            "TerminalActivity 必须给 SessionManager.onSessionDied 赋值——否则会话结束通道" +
                "（重置行缓冲 + 结束提示）永远不触发（§B-补-49 🟢4/🟢6 的死钩子）",
            activity.contains("SessionManager.onSessionDied = ")
        )
    }

    @Test
    fun `引擎侧光标样式必须是 BAR`() {
        val sessionManager = read("src/main/java/com/example/zhengdao/terminal/SessionManager.kt")
        assertTrue(
            "SessionManager 匿名 client 的 getTerminalCursorStyle 必须传 2（BAR）——" +
                "用户明确要求光标细一点；传 0 是方块，且 Activity 的 override 从未注册（§B-补-49 🟢4）",
            sessionManager.contains("getTerminalCursorStyle(): Int = 2")
        )
    }
}
