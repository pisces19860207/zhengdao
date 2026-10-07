// 独立开发声明：本文件为本项目从零编写。可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「全局单会话」路由决策表 —— 逐格钉住用户 2026-10-07 定稿的规则。
 *
 * 覆盖的就是用户给的验收清单里那几条（真机验收之前，先在这里把它们变成可执行的断言）：
 *   · 点 claude → 起新的
 *   · 再点 claude → 不新增、attach 回原来那个
 *   · 点 agy → claude 那条被 kill，换成 agy
 *   · 点安装 → 换掉
 *   · 只"打开终端"（不带命令）→ 什么都不动
 */
class SessionRouterTest {

    private fun decide(
        hasCommand: Boolean = true,
        agent: String? = "claude-code",
        isLaunch: Boolean = true,
        current: String? = null,
        hasSession: Boolean = true,
    ) = SessionRouter.decide(hasCommand, agent, isLaunch, current, hasSession)

    // ── 1. 不带命令：什么都不动 ────────────────────────────────────────────────

    @Test
    fun `不带命令时一律保留会话 - 哪怕当前正跑着 claude`() {
        assertEquals(
            SessionRouter.Action.KEEP_SESSION,
            decide(hasCommand = false, current = "claude-code"),
        )
    }

    @Test
    fun `不带命令时也保留会话 - 会话已经死了也一样`() {
        // 死了就死了：这里不该顺手替用户起一个他不知道的东西（要"接回来"是
        // restoreLastAgentIfAny 的职责，且只在没有待执行命令时发生）。
        assertEquals(
            SessionRouter.Action.KEEP_SESSION,
            decide(hasCommand = false, current = "agy", hasSession = false),
        )
    }

    // ── 2. 同一个 Agent：attach 回去 ──────────────────────────────────────────

    @Test
    fun `再点一次同一个 Agent - attach 回原来那个`() {
        assertEquals(
            SessionRouter.Action.ATTACH_CURRENT,
            decide(agent = "claude-code", isLaunch = true, current = "claude-code", hasSession = true),
        )
    }

    @Test
    fun `同一个 Agent 但会话已经死了 - 不能 attach 幽灵会话`() {
        assertEquals(
            SessionRouter.Action.REPLACE_SESSION,
            decide(agent = "claude-code", isLaunch = true, current = "claude-code", hasSession = false),
        )
    }

    @Test
    fun `名字撞上但这次是安装命令 - 仍然要换掉重装`() {
        // 安装 = 覆盖/修复，必须真的跑一遍，不能因为"同名"被当成 attach 吞掉。
        assertEquals(
            SessionRouter.Action.REPLACE_SESSION,
            decide(agent = "hermes", isLaunch = false, current = "hermes", hasSession = true),
        )
    }

    // ── 3. 换 Agent：整条换掉 ────────────────────────────────────────────────

    @Test
    fun `点 agy 时当前是 claude - 换掉`() {
        assertEquals(
            SessionRouter.Action.REPLACE_SESSION,
            decide(agent = "antigravity", isLaunch = true, current = "claude-code", hasSession = true),
        )
    }

    @Test
    fun `当前没有 Agent（裸 bash）- 直接起新的`() {
        assertEquals(
            SessionRouter.Action.REPLACE_SESSION,
            decide(agent = "claude-code", isLaunch = true, current = null, hasSession = true),
        )
    }

    @Test
    fun `会话与 Agent 记录都没有 - 起新的`() {
        assertEquals(
            SessionRouter.Action.REPLACE_SESSION,
            decide(agent = "claude-code", isLaunch = true, current = null, hasSession = false),
        )
    }

    // ── 4. 无归属的命令（清理缓存 / 卸载）：换掉，绝不打进正在跑的 TUI ──────────

    @Test
    fun `清理缓存这类无归属命令 - 换掉而不是注入到 claude 界面里`() {
        assertEquals(
            SessionRouter.Action.REPLACE_SESSION,
            decide(agent = null, isLaunch = false, current = "claude-code", hasSession = true),
        )
    }

    @Test
    fun `卸载命令同理 - 换掉`() {
        assertEquals(
            SessionRouter.Action.REPLACE_SESSION,
            decide(agent = "hermes", isLaunch = false, current = "claude-code", hasSession = true),
        )
    }

    // ── 5. 边界：空字符串的 agent id 不该被当成"同一个" ────────────────────────

    @Test
    fun `空字符串的 requestAgentId 不算同一个 Agent`() {
        assertEquals(
            SessionRouter.Action.REPLACE_SESSION,
            decide(agent = "", isLaunch = true, current = "", hasSession = true),
        )
    }
}
