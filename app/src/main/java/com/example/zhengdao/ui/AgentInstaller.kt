// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.zhengdao.rootfs.RootfsDownloader
import java.io.File

/**
 * AgentInstaller（M3 骨架 §3 简化版）：安装脚本本地化 + 安装前清障。
 *
 * - **脚本本地化**：安装脚本先由 App 侧下载到工作区 /workspace/.zhengdao/scripts/
 *   （guest 内可直接读），重试时脚本已存在则跳过下载——网络断续不再卡安装；
 * - **安装前清障**（坑 #12 固化）：git 残锁（进程被杀留 .git/index.lock → 之后全部
 *   exit 128，表现酷似网络失败）安装前自动清除；UV_LINK_MODE=copy 前置 export；
 * - **一键到底**：安装成功自动启动 Agent，不逼用户回主页。
 *
 * npm 类（opencode）不走脚本模式，沿用组合命令（镜像已在 guest 内全局配置）。
 */
object AgentInstaller {

    /** 各 Agent 的官方安装脚本 URL（npm 类为 null）。 */
    private val SCRIPT_URLS = mapOf(
        "claude-code" to "https://claude.ai/install.sh",
        "hermes" to "https://hermes-agent.nousresearch.com/install.sh",
        "antigravity" to "https://antigravity.google/cli/install.sh",
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 组装一键安装命令（后台下载脚本，完成后回调）。
     * @param onReady 主线程回调，参数为可直接注入终端的 autocmd
     */
    fun prepareInstall(ctx: Context, agent: AppState.AgentInfo, onReady: (String) -> Unit) {
        val scriptUrl = SCRIPT_URLS[agent.id]
        if (scriptUrl == null) {
            // npm 类：清锁 + 原组合命令（镜像已在 guest 配置）
            onReady(buildCommand(ctx, agent, agent.installCmd ?: return))
            return
        }
        Thread {
            val dir = File(ctx.getExternalFilesDir(null), "workspace/.zhengdao/scripts")
            var ok = runCatching { dir.mkdirs() }.isSuccess
            val script = File(dir, "${agent.id}-install.sh")
            // 重试免下载：脚本已在且非空直接复用（用户指定）
            if (ok && (!script.isFile || script.length() < 64)) {
                val tmp = File(dir, "${agent.id}-install.sh.part")
                ok = runCatching {
                    val text = RootfsDownloader.fetchText(scriptUrl, trimEnds = false)
                        ?: throw IllegalStateException("脚本下载失败")
                    tmp.writeText(text)
                    if (!tmp.renameTo(script)) {
                        tmp.copyTo(script, overwrite = true)
                        tmp.delete()
                    }
                }.isSuccess
            }
            val effectiveCmd = if (ok && script.isFile) {
                // guest 内 /workspace 即手机侧工作区，脚本以本地文件执行
                "bash /workspace/.zhengdao/scripts/${agent.id}-install.sh"
            } else {
                agent.installCmd ?: "" // 本地化失败：退回原始 curl|bash
            }
            val cmd = buildCommand(ctx, agent, effectiveCmd)
            mainHandler.post { onReady(cmd) }
        }.start()
    }

    /** 组合命令：清 git 残锁（坑 #12）→ copy 模式 → 安装 → 自动启动。 */
    private fun buildCommand(ctx: Context, agent: AppState.AgentInfo, installCmd: String): String {
        val launch = agent.launchCmd.substringBefore(' ')
        return buildString {
            append("find /root/.hermes /root/.claude /root/.config -name index.lock -delete 2>/dev/null; ")
            append("export UV_LINK_MODE=copy; ")
            append(installCmd)
            append(" && echo \"[证道] 安装完成，正在启动 $launch（首次启动需初始化，请稍候）…\" && $launch")
        }
    }
}
