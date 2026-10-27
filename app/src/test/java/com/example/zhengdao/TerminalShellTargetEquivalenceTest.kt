// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao

import com.example.zhengdao.terminal.TerminalShellTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 第二百四十一批（终端解耦第二刀）：**通知落点等价性门禁**。
 *
 * 搬终端代码进 `:terminal-core` 时撞上一个硬约束：三条通知的 `PendingIntent` 原本写的是
 * `Intent(ctx, MainActivity::class.java)`，而 `MainActivity` 属于 `:app`——库模块看不见它，
 * 反向依赖又被判据④的门禁钉死。处置是把"类引用"降级成"组件名字符串"
 * （`terminal/TerminalShellTarget.LAUNCH_ACTIVITY`），运行时造出的 ComponentName 完全一样。
 *
 * 但字符串是可以写错的，而且**错了不报错**——点通知只是没反应。所以这里把它钉住：
 * 本测试在 `:app` 里，同时看得见 `MainActivity` 与 `:terminal-core` 的常量，
 * 两者不等 ⇒ 立刻红。
 *
 * 第三刀（`:app-term`）换落点时应把期望值改成新壳的主界面——**改这里就说明你在改落点**，
 * 这是有意的摩擦。
 */
class TerminalShellTargetEquivalenceTest {

    @Test
    fun `通知落点与搬前的 MainActivity 是同一个组件`() {
        assertEquals(
            "TerminalShellTarget.LAUNCH_ACTIVITY 必须等于 MainActivity 的全限定类名" +
                "（搬前 Intent(ctx, MainActivity::class.java) 的落点）",
            MainActivity::class.java.name,
            TerminalShellTarget.LAUNCH_ACTIVITY,
        )
    }
}
