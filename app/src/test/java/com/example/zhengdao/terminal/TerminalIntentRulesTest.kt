// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [deriveTerminalRequest] 的验收（第七十四批 · 结构拆分第三阶段）。
 *
 * 钉的是 2026-10-07 起 `readRequestFromIntent` 长出来的两条业务口径，不是实现：
 * 1. 空白命令 = 没有请求（不然等于替用户敲回车）；
 * 2. `agent_id` 只在**带命令**时才算数——安装中按钮会单独传 agent_id，
 *    把它当请求会把用户带到错的 agent（第二次点「安装」时最明显）。
 */
class TerminalIntentRulesTest {

    // ── 纯函数行为 ────────────────────────────────────────────────────────

    @Test
    fun `两个 extra 都空就不算一次请求`() {
        val r = deriveTerminalRequest(null, null)
        assertNull(r.cmd)
        assertNull(r.agentId)

        assertNull("空白命令（空格/换行）同样不算请求", deriveTerminalRequest("   ", null).cmd)
        assertNull(deriveTerminalRequest("\n\t", null).cmd)
    }

    @Test
    fun `只有命令没有点名 agent`() {
        val r = deriveTerminalRequest("ls -al", null)
        assertEquals("ls -al", r.cmd)
        assertNull(r.agentId)
    }

    @Test
    fun `命令和 agent 都在就都收下`() {
        val r = deriveTerminalRequest("opencode --agent claude", "claude")
        assertEquals("opencode --agent claude", r.cmd)
        assertEquals("claude", r.agentId)
    }

    @Test
    fun `只有 agent_id 不算请求（安装中按钮那条路）`() {
        // HomeScreen 的「安装中」按钮：带 agent_id 不带命令 = 只是"带我去看看"。
        val r = deriveTerminalRequest(null, "claude")
        assertNull("没有命令时 agent 必须被丢弃，否则会触发一次错误的会话切换", r.cmd)
        assertNull(r.agentId)

        val blankCmd = deriveTerminalRequest("  ", "claude")
        assertNull(blankCmd.cmd)
        assertNull("空白命令同样不能把 agent 带进来", blankCmd.agentId)
    }

    @Test
    fun `命令两侧的空白原样保留不 trim`() {
        // 2026-10-07 起的既有行为：只判空白，不改写命令内容（用户可能故意带前导空格）。
        val r = deriveTerminalRequest("  echo hi  ", "  claude  ")
        assertEquals("  echo hi  ", r.cmd)
        assertEquals("  claude  ", r.agentId)
    }

    @Test
    fun `agent_id 是空白就当作没有点名`() {
        val r = deriveTerminalRequest("echo hi", "   ")
        assertEquals("echo hi", r.cmd)
        assertNull(r.agentId)
    }

    // ── 源码门禁：Activity 只留调用，不得留两份实现 ────────────────────────

    private fun activitySource(): String =
        File("src/main/java/com/example/zhengdao/TerminalActivity.kt")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/zhengdao/TerminalActivity.kt") }
            .readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    @Test
    fun `readRequestFromIntent 只调纯函数不含旧的手写分支`() {
        val src = activitySource()
        val start = src.indexOf("private fun readRequestFromIntent")
        assertTrue("找不到 readRequestFromIntent（改名了？门禁跟着改）", start >= 0)
        val body = src.substring(start, src.indexOf("\n    }", start))

        assertTrue("必须调用 deriveTerminalRequest —— 删掉调用等于把规则又写回 Activity", body.contains("deriveTerminalRequest("))
        assertFalse(
            "旧的「按 cmd 是否为空手写 agent_id 分支」不得残留（两份实现必然漂移）",
            body.contains("pendingAgentId = if (cmd == null)"),
        )
        assertFalse("不得再直接读 autocmd extra（取值只该发生在调用参数里）", body.contains("getStringExtra(\"autocmd\")?.takeIf"))
    }
}
