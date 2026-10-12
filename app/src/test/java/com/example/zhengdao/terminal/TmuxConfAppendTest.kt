// 独立开发声明：本文件为本项目从零编写。
// 被测对象有两个：① 第一方 `ProotLauncher.tmuxConfAppend()`（纯函数，决定往 guest
// ~/.tmux.conf 追加什么）；② 第一方 `TerminalActivity` 的黄点按钮处理器 ——
// 后者用**文本门禁**方式钉住「不再盲发按键」（理由见下）。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §B 顺序 22 ②「黄点盲发 `y`」的验收（2026-10-12）。
 *
 * ## 这条缺陷是什么
 * 黄点 = 关掉当前分屏。tmux 默认把 `C-b x` 绑到 `confirm-before -p "kill-pane #P? (y/n)" kill-pane`
 * ⇒ 按完要先答一次 `y`。客户端原先的做法是「发完 `C-b x`，延迟 250 ms 再盲发一个 `y`」：
 * 那条按键**没有任何依据**能保证此刻屏幕上正等着确认（未启用 tmux、pane 数判断漂移、
 * 确认框还没起来），于是 `y` 会直接落进 pane 里前台程序的 stdin —— 在跑 agent TUI 时
 * 就是乱打字（外部审计报告的 A4）。
 *
 * 修法是把确认整段去掉：往 `~/.tmux.conf` 写一条 `bind-key x kill-pane` 覆盖默认绑定，
 * 客户端只发 `C-b x`。于是这里有两半要验：
 * 1. **配置侧**（可行为断言）：[ProotLauncher.tmuxConfAppend] 会补上这条、且幂等、且不覆盖用户自有配置；
 * 2. **客户端侧**（只能文本门禁）：黄点处理器里只剩**一次** `SessionManager.write`，
 *    没有延迟、没有第二个按键。
 *
 * 第 2 半说明白：文本门禁证明的是「没人把盲发按键加回来」，不是「点了就一定关得掉」——
 * 后者取决于 guest 里 tmux 是否真读到了这条绑定（主会话每次启动都 `kill-server`，
 * 见 `ProotLauncher.buildLaunchPlan`），真机走查口径写在 §B 顺序 22 与台账 §A 里。
 */
class TmuxConfAppendTest {

    /** Gradle 单测的工作目录是模块目录 `app/`（与 R8KeepRuleGuardTest 同一约定）。 */
    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    // ── 第一半：配置侧 ────────────────────────────────────────────────────

    @Test
    fun `空配置时三条都补上_且包含黄点那条 kill-pane`() {
        val add = ProotLauncher.tmuxConfAppend("")
        assertTrue("缺 set -g mouse on：\n$add", add.contains("set -g mouse on\n"))
        assertTrue("缺 set -g set-clipboard on：\n$add", add.contains("set -g set-clipboard on\n"))
        assertTrue("缺 bind-key x kill-pane（黄点不再补发 y 就靠它）：\n$add", add.contains("bind-key x kill-pane\n"))
    }

    @Test
    fun `幂等_追加过一遍之后再算一次返回空串`() {
        val once = ProotLauncher.tmuxConfAppend("")
        val merged = once // 原文为空 ⇒ 追加结果就是这几行
        assertEquals("已经写过的配置不该再被追加一次", "", ProotLauncher.tmuxConfAppend(merged))
        // 混入用户已有的其它设置后仍然为空
        val withUser = merged + "set -g history-limit 50000\n"
        assertEquals("", ProotLauncher.tmuxConfAppend(withUser))
    }

    @Test
    fun `用户自己写过的设置不会被覆盖_缺的那条才补`() {
        val cur = """
            # 我自己写的
            set -g mouse on
            bind x kill-pane
        """.trimIndent() + "\n"
        val add = ProotLauncher.tmuxConfAppend(cur)
        assertFalse("用户已写 mouse，不该重复追加：\n$add", add.contains("set -g mouse"))
        assertFalse("用户已绑定 x（`bind x` 写法也算），不该重复追加：\n$add", add.contains("kill-pane"))
        assertTrue("只缺 set-clipboard，应补上：\n$add", add.contains("set -g set-clipboard on\n"))
    }

    // ── 第二半：客户端侧（文本门禁） ──────────────────────────────────────

    private fun yellowDotBlock(): String {
        val f = appFile("src/main/java/com/example/zhengdao/TerminalActivity.kt")
        assertTrue("找不到 TerminalActivity.kt：${f.path}", f.isFile)
        val src = f.readText()
        val start = src.indexOf("R.id.btn_yellow).setOnClickListener")
        assertTrue("源码里找不到黄点按钮的处理器（锚点变了就更新本测试）", start >= 0)
        val end = src.indexOf("for (id in intArrayOf(", start)
        assertTrue("找不到黄点处理器之后的长按循环（锚点变了就更新本测试）", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `黄点处理器只发一次按键_没有延迟也没有补发的 y`() {
        val block = yellowDotBlock()
        val writes = Regex("SessionManager\\.write").findAll(block).count()
        assertEquals("黄点处理器应当只发一次 `C-b x`，实际 $writes 次：\n$block", 1, writes)
        assertFalse("不该再有延迟发送（盲发的载体）：\n$block", block.contains("postDelayed"))
        assertFalse("不该再补发 y：\n$block", block.contains("\"y\""))
    }
}
