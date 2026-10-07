// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Context
import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * 「运行环境是否已安装」的**可观察**状态（v1.2 修复：状态卡装完不刷新）。
 *
 * 背景：`AppState.rootfsInstalled(ctx)` 是一次性读文件的普通函数。原先各页面直接
 * 在组合里调用它，等于把一次快照当成常量用——Compose 不会为它建立任何失效关系，
 * 只有"恰好有别的 State 变化引发重组"时才会顺带重算。真机实测（2026-10-07）：
 * 环境装完回到主页，状态卡依旧显示「环境未安装」，「安装运行环境」按钮照常亮着，
 * 必须杀进程重进才变「环境就绪」。
 *
 * 修法：把判定结果装进 State，由两处驱动刷新——
 *   1. 页面回到前台（ON_RESUME）调 [refresh]；
 *   2. 安装器写完成标记后调 [markInstalled]（同进程即时生效，不必等回前台）。
 *
 * 不持有 Context（只作参数传入），故可作进程级单例，无泄漏风险。
 */
object RootfsState {
    private val _installed = mutableStateOf(false)

    /** 供 Composable 以 `by` 读取；只读，避免调用方误写。 */
    val installed: State<Boolean> get() = _installed

    /** 重新读一次磁盘判定（ON_RESUME 用）。 */
    fun refresh(ctx: Context) {
        _installed.value = AppState.rootfsInstalled(ctx)
    }

    /** 安装器写完成标记后调用：立刻置位，不等回前台。 */
    fun markInstalled() {
        _installed.value = true
    }

    /** 环境被卸载/清除后调用（目前由 refresh 兜底，保留给显式卸载路径）。 */
    fun markRemoved() {
        _installed.value = false
    }
}

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
        val npmPackage: String? = null,   // 非空时可探测已装版本 + 查 npm 最新版
        val installedVersion: String? = null, // node_modules package.json 探测；null = 版本未知
        val uninstallCmd: String? = null, // P3：卸载命令（guest 内执行）
    )

    fun rootfsInstalled(ctx: Context): Boolean =
        File(ctx.filesDir, "rootfs/.zhengdao-rootfs-ok").isFile

    private fun firstExisting(ctx: Context, relative: List<String>): Boolean =
        relative.any { File(ctx.filesDir, it).isFile }

    /**
     * Agent 卡片列表（M3 起由验签 manifest 驱动，骨架 §2）：
     * - 内置清单 = 出厂版兜底（APK 同源发布，manifest 双通道全挂时仍可用）；
     * - manifest 已缓存条目按 id 覆盖内置（安装命令免发版更新），未知 id 追加为新卡片；
     * - 安装探测：已知 id 走专用路径；manifest 新增 id 通用探测（~/.local/bin 与
     *   /usr/local/bin 下的启动命令名）。
     */
    fun agents(ctx: Context): List<AgentInfo> {
        val rootfsInstalled = rootfsInstalled(ctx)
        val factory = factoryAgents(ctx, rootfsInstalled)
        val manifest = AgentManifest.cached(ctx)

        val detectFor: (String, String) -> Boolean = { _, launchCmd ->
            val cmd = launchCmd.substringBefore(' ').trim()
            rootfsInstalled && cmd.isNotBlank() && firstExisting(
                ctx, listOf(
                    "home/.local/bin/$cmd", "rootfs/usr/local/bin/$cmd", "rootfs/usr/bin/$cmd",
                )
            )
        }
        val byId = factory.associateBy { it.id }.toMutableMap()
        // npm 包名解析：manifest 声明优先，其次内置映射（npm 安装路径的版本可探测）
        val npmFor: (String) -> String? = { id ->
            manifest?.firstOrNull { it.id == id }?.npmPackage
                ?: mapOf(
                    "claude-code" to "@anthropic-ai/claude-code",
                    "opencode" to "opencode-ai",
                )[id]
        }
        // manifest 卡片合并：按 id 覆盖内置（安装命令免发版更新），未知 id 追加
        manifest?.forEach { m ->
            val known = byId[m.id]
            val pkg = npmFor(m.id)
            byId[m.id] = if (known != null) {
                known.copy(
                    name = m.name.ifBlank { known.name },
                    desc = m.desc.ifBlank { known.desc },
                    launchCmd = m.launchCmd.ifBlank { known.launchCmd },
                    installCmd = m.installCmd.ifBlank { known.installCmd },
                    uninstallCmd = m.uninstallCmd ?: known.uninstallCmd,
                    npmPackage = pkg ?: known.npmPackage,
                    installedVersion = pkg?.let { AgentManifest.installedVersion(ctx, it) },
                )
            } else {
                AgentInfo(
                    id = m.id, name = m.name, desc = m.desc,
                    launchCmd = m.launchCmd, installCmd = m.installCmd,
                    installed = detectFor(m.id, m.launchCmd),
                    npmPackage = pkg,
                    installedVersion = pkg?.let { AgentManifest.installedVersion(ctx, it) },
                    uninstallCmd = m.uninstallCmd,
                )
            }
        }
        // 展示顺序：出厂顺序优先，manifest 新增排后面；全部补版本探测
        val order = factory.map { it.id } + (manifest?.map { it.id } ?: emptyList())
            .filter { it !in factory.map { f -> f.id } }
        return order.mapNotNull { byId[it] }.map { a ->
            if (a.installedVersion == null && a.npmPackage == null) {
                val pkg = npmFor(a.id)
                a.copy(npmPackage = pkg, installedVersion = pkg?.let { AgentManifest.installedVersion(ctx, it) })
            } else a
        }
    }

    /** 出厂版（APK 内置，等于 agents.json 的首发快照）。 */
    private fun factoryAgents(ctx: Context, rootfsInstalled: Boolean): List<AgentInfo> = listOf(
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
                        "rootfs/usr/bin/opencode",
                        "rootfs/usr/lib/node_modules/opencode-ai/package.json",
                    )
                ),
            ),
            AgentInfo(
                id = "antigravity",
                name = "AGY CLI（Antigravity）",
                desc = "Google 新一代 Agent CLI（Gemini/Claude 模型，官方含 linux-arm64 构建；Gemini CLI 已向它合并）",
                launchCmd = "agy",
                installCmd = "curl -fsSL https://antigravity.google/cli/install.sh | bash",
                installed = rootfsInstalled && firstExisting(
                    ctx, listOf(
                        "home/.local/bin/agy", "rootfs/usr/local/bin/agy",
                        "rootfs/usr/local/lib/agy",
                    )
                ),
            ),
        )

    /** 状态卡折叠态的一行摘要。 */
    fun summaryLine(ctx: Context): String {
        val installedCount = agents(ctx).count { it.installed }
        val env = if (rootfsInstalled(ctx)) "Debian 13.7 已安装" else "环境未安装"
        return "Android ${Build.VERSION.RELEASE} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"} · $env · $installedCount 个 Agent"
    }
}
