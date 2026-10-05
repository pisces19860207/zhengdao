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
            // TMPDIR 指向 home（uv 缓存与目标同侧，避免跨挂载点；WorkBuddy 建议）。
            // TMPDIR 不带 UV_ 前缀，hermes pm 的 UV_* 剥离不影响它（未实测·推断）
            append("mkdir -p /root/tmp; export TMPDIR=/root/tmp; ")
            if (agent.id == "hermes") {
                // hermes 专属（2026-10-05 官方 install.sh 源码核实）：ensure_uv 注释明写
                // "never a uv already on PATH"——它把 pinned uv 0.12.3 自装到
                // ~/.hermes/tools/uv-0.12.3-<target>/uv，系统 PATH 上的 uv（包括此前
                // 的包装尝试）根本不会被调用；且脚本全局 UV_NO_CONFIG=1 吞掉一切
                // uv 配置文件、pm 剥离 UV_* 环境变量。唯一可靠的注入点是它自己的
                // 二进制：真实 uv 挪为 uv.real，原路径放包装器（exec 前补回
                // UV_LINK_MODE=copy，pm 剥环境变量剥不到二进制内部）。
                // ensure_uv 见路径已有可执行文件就跳过下载 → 预置包装器即接管；
                // 包装器首跑自带 pinned 工件下载 + SHA256 校验（URL/哈希与 install.sh
                // 同源）。hermes 将来升 pin 版本号时：预置路径失效、新版本目录由
                // ensure_uv 正常下载裸奔一次（该轮安装可能仍报硬链接），重装一轮
                // 即被下面的 swap 循环接管——已知降级，不做动态解析。
                append("for d in /root/.hermes/tools/uv-*; do " +
                    "if [ -f \"${'$'}d/uv\" ] && [ ! -f \"${'$'}d/uv.real\" ]; then " +
                    "mv \"${'$'}d/uv\" \"${'$'}d/uv.real\"; fi; done 2>/dev/null; ")
                // 无条件重写 wrapper（自愈：历史坏版本/未来逻辑更新都直接覆盖）
                append("mkdir -p /root/.hermes/tools/uv-0.12.3-linux-arm64; " +
                    "echo $HERMES_UV_WRAPPER_B64 | base64 -d > /root/.hermes/tools/uv-0.12.3-linux-arm64/uv; " +
                    "chmod +x /root/.hermes/tools/uv-*/uv 2>/dev/null; ")
            }
            append(installCmd)
            append(" && echo \"[证道] 安装完成，正在启动 $launch（首次启动需初始化，请稍候）…\" && $launch")
        }
    }

    /**
     * hermes 专属 uv 包装器（base64 免转义注入）。逻辑：
     * uv.real 缺失 → 从 GitHub pinned 地址下载 uv 0.12.3 arm64（失败换 hermes 官方
     * 镜像），SHA256 校验后落位；exec 真身前强制 UV_LINK_MODE=copy + TMPDIR 兜底。
     * 真身获取失败退回系统 uv（rootfs 自带）。URL/SHA256 与 hermes install.sh 同源。
     */
    private const val HERMES_UV_WRAPPER_B64 =
        "IyEvYmluL2Jhc2gKIyB6aGVuZ2RhbyBpbmplY3Rpb24gbGF5ZXI6IGhlcm1lcyBwbSBzdHJpcHMgVVZfKiBlbnYgdmFycyBhbmQgaWdub3JlcyB1diBjb25maWcKIyBmaWxlcyAoVVZfTk9fQ09ORklHPTEpIC0tIHdyYXBwaW5nIGl0cyBvd24gcGlubmVkIHV2IGJpbmFyeSBpcyB0aGUgb25seQojIHJlbGlhYmxlIGluamVjdGlvbiBwb2ludC4gVGhlIHJlYWwgYmluYXJ5IGxpdmVzIG5leHQgdG8gdGhpcyBhcyB1di5yZWFsLgpEPSIkKGNkICIkKGRpcm5hbWUgIiQwIikiICYmIHB3ZCkiClI9IiREL3V2LnJlYWwiCmlmIFsgISAteCAiJFIiIF07IHRoZW4KICBUPSIkKG1rdGVtcCAtZCAyPi9kZXYvbnVsbCB8fCBlY2hvIC90bXAvLnpkdXYuJCQpIgogIG1rZGlyIC1wICIkVCIKICBmb3IgVSBpbiBcCiAgICBodHRwczovL2dpdGh1Yi5jb20vYXN0cmFsLXNoL3V2L3JlbGVhc2VzL2Rvd25sb2FkLzAuMTIuMy91di1hYXJjaDY0LXVua25vd24tbGludXgtZ251LnRhci5neiBcCiAgICBodHRwczovL2hlcm1lcy1hc3NldHMubm91c3Jlc2VhcmNoLmNvbS91cHN0cmVhbS9zaGEyNTYvYmI2NmNiNTJlN2IxODIzYWVkMTE4MzYzMGQ4ZDhlNWM5NTg4NDBkNTg0YTRjNTVlYzEwYTRjZmMxNjhkY2NhMiA7IGRvCiAgICBjdXJsIC1Mc1NmICIkVSIgLW8gIiRUL3V2LnRneiIgJiYgYnJlYWsKICBkb25lCiAgaWYgWyAtZiAiJFQvdXYudGd6IiBdICYmIFsgIiQoc2hhMjU2c3VtICIkVC91di50Z3oiIDI+L2Rldi9udWxsIHwgY3V0IC1kJyAnIC1mMSkiID0gImJiNjZjYjUyZTdiMTgyM2FlZDExODM2MzBkOGQ4ZTVjOTU4ODQwZDU4NGE0YzU1ZWMxMGE0Y2ZjMTY4ZGNjYTIiIF07IHRoZW4KICAgIHRhciAteHpmICIkVC91di50Z3oiIC1DICIkVCIgMj4vZGV2L251bGwKICAgIEY9IiQoZmluZCAiJFQiIC1uYW1lIHV2IC10eXBlIGYgMj4vZGV2L251bGwgfCBoZWFkIC1uMSkiCiAgICBbIC1uICIkRiIgXSAmJiBtdiAiJEYiICIkUiIgJiYgY2htb2QgMDc1NSAiJFIiCiAgZmkKICBybSAtcmYgIiRUIgpmaQppZiBbICEgLXggIiRSIiBdOyB0aGVuCiAgZWNobyAiW3poZW5nZGFvXSB1diB3cmFwcGVyOiBwaW5uZWQgdXYgdW5hdmFpbGFibGUsIGZhbGxpbmcgYmFjayB0byBzeXN0ZW0gdXYiID4mMgogIFsgLXggL3Vzci9sb2NhbC9iaW4vdXYgXSAmJiBleGVjIC91c3IvbG9jYWwvYmluL3V2ICIkQCIKICBleGl0IDEyNwpmaQpleHBvcnQgVVZfTElOS19NT0RFPWNvcHkKZXhwb3J0IFRNUERJUj0iJHtUTVBESVI6LS9yb290L3RtcH0iCmV4ZWMgIiRSIiAiJEAiCg=="
}
