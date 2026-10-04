// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 轻量应用状态：环境安装状态与 Agent 卡片数据（第一批为静态卡片 + 文件探测，
 * M3 起由服务器 manifest 驱动）。
 */
object AppState {

    /** Agent 卡片定义与实时安装状态。 */
    data class AgentInfo(
        val id: String,
        val name: String,
        val desc: String,
        val launchCmd: String,
        val installCmd: String?,          // null = 安装命令待定（按钮禁用）
        val installed: Boolean,
    )

    fun rootfsInstalled(ctx: Context): Boolean =
        File(ctx.filesDir, "rootfs/.zhengdao-rootfs-ok").isFile

    private fun firstExisting(ctx: Context, relative: List<String>): Boolean =
        relative.any { File(ctx.filesDir, it).isFile }

    /** Agent 卡片列表（安装状态按真实文件探测：home 绑定 = /root，rootfs 内路径在 host 侧可见）。 */
    fun agents(ctx: Context): List<AgentInfo> {
        val rootfsInstalled = rootfsInstalled(ctx)
        return listOf(
            AgentInfo(
                id = "claude-code",
                name = "Claude Code",
                desc = "Anthropic 官方 AI 编程助手",
                launchCmd = "claude",
                installCmd = "curl -fsSL https://claude.ai/install.sh | bash",
                installed = rootfsInstalled && firstExisting(
                    ctx, listOf("home/.local/bin/claude", "rootfs/usr/local/bin/claude")
                ),
            ),
            AgentInfo(
                id = "hermes",
                name = "Hermes Agent",
                desc = "多技能 AI 助手（Nous Research）",
                launchCmd = "hermes",
                installCmd = "curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash",
                installed = rootfsInstalled && firstExisting(
                    ctx, listOf("home/.local/bin/hermes", "rootfs/usr/local/bin/hermes")
                ),
            ),
            AgentInfo(
                id = "opencode",
                name = "OpenCode",
                desc = "开源编程 Agent（多模型，官方 npm 含 linux-arm64 预编译二进制）",
                launchCmd = "opencode",
                installCmd = "npm install -g opencode-ai",
                installed = rootfsInstalled && firstExisting(
                    ctx, listOf(
                        "home/.local/bin/opencode", "rootfs/usr/local/bin/opencode",
                        "rootfs/usr/lib/node_modules/opencode-ai",
                    )
                ),
            ),
            AgentInfo(
                id = "gemini-cli",
                name = "Gemini CLI",
                desc = "Google 官方 CLI（免费层级可用，Node 20+ 已预装）",
                launchCmd = "gemini",
                installCmd = "npm install -g @google/gemini-cli",
                installed = rootfsInstalled && firstExisting(
                    ctx, listOf(
                        "home/.local/bin/gemini", "rootfs/usr/local/bin/gemini",
                        "rootfs/usr/lib/node_modules/@google/gemini-cli",
                    )
                ),
            ),
            AgentInfo(
                id = "zcode",
                name = "Zcode",
                desc = "安装命令整理中，将经 manifest 免发版下发",
                launchCmd = "zcode",
                installCmd = null,
                installed = false,
            ),
        )
    }

    /** 状态卡折叠态的一行摘要。 */
    fun summaryLine(ctx: Context): String {
        val installedCount = agents(ctx).count { it.installed }
        val env = if (rootfsInstalled(ctx)) "Debian 13.7 已安装" else "环境未安装"
        return "Android ${Build.VERSION.RELEASE} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"} · $env · $installedCount 个 Agent"
    }
}
