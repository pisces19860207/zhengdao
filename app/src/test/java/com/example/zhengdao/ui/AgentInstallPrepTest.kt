// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E-059：安装前准备的两件事——脚本可信度、残锁清理命令。
 *
 * 针对的场景（用户 2026-10-09 点出的）：**用户不会卸载 App，只会一直点重试**。
 * 所以"坏缓存必须能被自动发现并重下"、"上一轮的死锁必须每次都被清掉"，
 * 这两条都不能靠用户做任何额外动作。
 */
class AgentInstallPrepTest {

    /** 一份最小但"像脚本"的正文：shebang + 足够长。 */
    private fun script(
        shebang: String = "#!/usr/bin/env bash",
        tail: String = "x".repeat(600),
    ): String = "$shebang\nset -e\necho hi\n$tail\n"

    // ── ① 脚本可信度 ────────────────────────────────────────────────────────

    @Test
    fun `正常安装脚本判为可用`() {
        assertTrue(AgentInstallPrep.looksUsableScript(script()))
    }

    @Test
    fun `前导空行不影响判断`() {
        assertTrue(AgentInstallPrep.looksUsableScript("\n\n" + script()))
    }

    @Test
    fun `shebang 前有注释也算脚本`() {
        // 有些安装器会在 shebang 之前放版权注释。判负的代价（hermes 丢掉 gateway 补丁）
        // 比误判更贵，所以只要求"第一行非空是注释"，不要求它必须是 #!
        assertTrue(
            AgentInstallPrep.looksUsableScript("# (c) 2026 someone\n#!/bin/sh\necho ok\n" + "y".repeat(600))
        )
    }

    @Test
    fun `HTML 错误页判为不可用`() {
        val html = "<!DOCTYPE html>\n<html><body>502 Bad Gateway</body></html>\n" + "x".repeat(600)
        assertFalse(AgentInstallPrep.looksUsableScript(html))
    }

    @Test
    fun `HTML 判定不区分大小写`() {
        assertFalse(AgentInstallPrep.looksUsableScript("<!doctype HTML>\n<HTML>" + "x".repeat(600)))
    }

    @Test
    fun `门户登录页即使没有 doctype 也判为不可用`() {
        assertFalse(AgentInstallPrep.looksUsableScript("<html><head><title>Portal</title>" + "x".repeat(600)))
    }

    @Test
    fun `不像脚本的正文判为不可用`() {
        // 命令式正文（少了 shebang 与注释头）
        assertFalse(AgentInstallPrep.looksUsableScript("apt-get install -y curl\n" + "x".repeat(600)))
        // JSON 形式的错误页（HTTP 200 + {"error": …}），bash 跑它只会报语法错
        assertFalse(
            AgentInstallPrep.looksUsableScript(
                "{\"error\":\"rate limited, try again later\"}\n" + "x".repeat(600)
            )
        )
    }

    @Test
    fun `过短的文件判为不可用`() {
        assertFalse(AgentInstallPrep.looksUsableScript("#!/bin/sh\necho hi\n"))
    }

    @Test
    fun `空文件与 null 判为不可用`() {
        assertFalse(AgentInstallPrep.looksUsableScript(""))
        assertFalse(AgentInstallPrep.looksUsableScript(null))
    }

    // ── ② 残锁清理 ─────────────────────────────────────────────────────────

    @Test
    fun `清障命令删的是所有 lock 而不只是 index_lock`() {
        val cmd = AgentInstallPrep.staleLockCleanup()
        assertTrue("必须按通配删锁", cmd.contains("-name '*.lock'"))
        assertTrue("必须覆盖 index.lock 所在的那棵树", cmd.contains("/root/.hermes"))
        assertTrue(cmd.contains("/root/.claude"))
        assertTrue(cmd.contains("/root/.config"))
        assertTrue("必须以 ; 结尾才能和后面的命令串起来", cmd.trimEnd().endsWith(";"))
    }

    @Test
    fun `清障命令不碰用户数据与已装好的二进制`() {
        val cmd = AgentInstallPrep.staleLockCleanup()
        assertFalse("共享存储里是用户内容，不能扫", cmd.contains("/sdcard"))
        assertFalse("工作区是用户内容，不能扫", cmd.contains("/workspace"))
        assertFalse("已装好的可执行文件目录不能扫", cmd.contains("/root/.local"))
        assertFalse("整盘扫会拖慢安装", cmd.contains("find / "))
    }

    @Test
    fun `清障命令的失败不会中断安装`() {
        // find 在目录不存在时报错，2>/dev/null 必须留在命令里，否则 bash 会把 stderr 回显给用户
        assertTrue(AgentInstallPrep.staleLockCleanup().contains("2>/dev/null"))
    }
}
