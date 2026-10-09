// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.zhengdao.rootfs.RootfsDownloader
import com.example.zhengdao.terminal.Store
import com.example.zhengdao.terminal.Workspace
import java.io.File

/**
 * AgentInstaller（M3 骨架 §3 简化版）：安装脚本本地化 + 安装前清障。
 *
 * - **脚本本地化**：安装脚本先由 App 侧下载到 `Download/证道/agents/scripts/`
 *   （guest 内可直接读，路径 `/opt/zhengdao/agents/scripts/`——公共区被单独 bind 到
 *   `/opt/zhengdao`，**不再挂在工作区下面**，因为工作区可能是用户自己的内容目录），
 *   重试时脚本已存在则跳过下载——网络断续不再卡安装。2026-10-08 从 `.zhengdao/scripts`
 *   搬来（老脚本会被自动迁移，重试免下载的收益不作废）；
 * - **安装前清障**（坑 #12 固化）：git 残锁（进程被杀留 .git/index.lock → 之后全部
 *   exit 128，表现酷似网络失败）安装前自动清除；UV_LINK_MODE=copy 前置 export；
 * - **一键到底**：安装成功自动启动 Agent，不逼用户回主页；
 * - **账本**：派发安装时往 `Download/证道/agents/installed.json` 记一笔，重装 App 后
 *   主页据此给出「恢复全部」（见 [AgentLedger]）。
 *
 * ⚠️ 为什么 Agent 的**可执行文件**（`~/.local/bin`，agy 实测 201 MB）不搬公共区：
 *    `/sdcard` 是 **noexec** 挂载，放过去就再也执行不了（这正是"安装脚本能放、二进制
 *    不能放"的分界）。公共区只放**不需要执行权限**的东西：脚本、npm/uv/pip 包缓存。
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
        Thread {
            AgentLedger.markStarted(ctx, agent)
            val cmd = installCommand(ctx, agent)
            mainHandler.post { onReady(cmd) }
        }.start()
    }

    /**
     * 「恢复全部」（2026-10-08）：把账本里记着、但当前探测不到的 Agent 串成**一条命令**，
     * 在同一个终端会话里顺序装完（每一步都写自己的 rc 文件，失败不影响后面的）。
     *
     * 为什么串成一条而不是逐个派发：终端是全局单会话模型，逐个派发等于反复重建会话，
     * 用户会在"复制/解压/重建"之间反复跳；一条命令跑完，进度和输出都留在同一屏里。
     */
    fun prepareRestoreAll(ctx: Context, agents: List<AppState.AgentInfo>, onReady: (String) -> Unit) {
        Thread {
            val list = agents.filter { it.installCmd != null || SCRIPT_URLS.containsKey(it.id) }
            if (list.isEmpty()) {
                mainHandler.post { onReady("echo '[证道] 没有需要恢复的 Agent'") }
                return@Thread
            }
            val parts = mutableListOf<String>()
            list.forEachIndexed { i, a ->
                AgentLedger.markStarted(ctx, a)
                parts += "echo \"[证道] 恢复 ${i + 1}/${list.size}：${a.name}（有缓存走缓存，没缓存才下载）…\""
                parts += installCommand(ctx, a)
            }
            parts += "echo \"[证道] 恢复流程结束：${list.joinToString("、") { it.name }}——每个 Agent 的结局见上面各段输出\""
            val cmd = parts.joinToString("; ")
            mainHandler.post { onReady(cmd) }
        }.start()
    }

    /**
     * 组装某个 Agent 的安装命令（**可能阻塞**：需要下载安装脚本，故只在后台线程调用）。
     */
    private fun installCommand(ctx: Context, agent: AppState.AgentInfo): String {
        val scriptUrl = SCRIPT_URLS[agent.id]
        if (scriptUrl == null) {
            // npm 类：清锁 + 原组合命令（镜像已在 guest 配置）
            return buildCommand(ctx, agent, agent.installCmd ?: "")
        }
        val dir = Store.agentScriptsDir(ctx)
        // 老落点搬家（一次性、幂等、两处都收）：脚本原先放在**工作区**下的
        // `.zhengdao/scripts`（工作区可能是用户设的自定义内容目录，所以这里走
        // Workspace.hostDir 而不是 Store.root），2026-10-08 换到公共区后
        // 公共区自己也短暂有过一份。搬完源目录空了会被删掉。
        runCatching { Store.adoptDir(dir, File(Workspace.hostDir(ctx), ".zhengdao/scripts")) }
        runCatching { Store.adoptDir(dir, File(Store.root(ctx), ".zhengdao/scripts")) }
        var ok = dir.isDirectory || runCatching { dir.mkdirs() }.getOrDefault(false)
        val script = File(dir, "${agent.id}-install.sh")
        // E-059：缓存的脚本**看起来不像脚本**就先删掉重下。这份缓存在共享存储里，
        // 卸载 App 也清不掉——一份坏文件（代理 HTML 拦截页 / 半截正文 / 被改坏）会被
        // 每一次重试复用，用户在丹房看到的是永远同一个失败，且没有任何自救手段。
        if (ok && script.isFile && !AgentInstallPrep.fileLooksUsable(script)) {
            com.example.zhengdao.rootfs.RunLog.log(
                "AgentInstaller: ${agent.id} 缓存的安装脚本不可用（${script.length()} 字符，" +
                    "疑似错误页/半截文件），删掉重下"
            )
            runCatching { script.delete() }
        }
        // 重试免下载：脚本已在且非空直接复用（用户指定）
        if (ok && (!script.isFile || script.length() < 64)) {
            val tmp = File(dir, "${agent.id}-install.sh.part")
            var why: String? = null
            ok = runCatching {
                val text = RootfsDownloader.fetchText(scriptUrl, trimEnds = false)
                    ?: throw IllegalStateException("脚本下载失败")
                // 下到了、但内容不像脚本（代理拦截页 / 门户登录页 / 半截正文）：
                // 宁可判定失败退回 curl|bash，也不要把一份坏文件写进共享存储再复用。
                if (!AgentInstallPrep.looksUsableScript(text)) {
                    throw IllegalStateException("下载到的内容不像安装脚本（${text.length} 字符，疑似代理拦截页）")
                }
                tmp.writeText(text)
                if (!tmp.renameTo(script)) {
                    tmp.copyTo(script, overwrite = true)
                    tmp.delete()
                }
            }.onFailure { why = it.message }.isSuccess
            if (!ok) com.example.zhengdao.rootfs.RunLog.log(
                "AgentInstaller: ${agent.id} 安装脚本下载失败（${why ?: "未知原因"}），退回 curl|bash 通道"
            )
        }
        // E-057：上游脚本的最后一步（gateway 服务）在 proot 里注定失败并把 rc 顶成 1，
        // 丹房于是永远显示「安装失败（退出码 1）」，而 hermes 本体早就装好了。执行前打一行
        // 本地补丁，让它只告警（锚点不在就原样放过，绝不乱改别人的脚本）。
        if (ok && script.isFile && agent.id == "hermes") {
            val outcome = runCatching { HermesInstallScript.ensurePatched(script) }
                .getOrElse { e ->
                    com.example.zhengdao.rootfs.RunLog.log(
                        "AgentInstaller: hermes 安装脚本补丁失败：${e.message ?: e::class.java.simpleName}"
                    )
                    null
                }
            when (outcome) {
                HermesInstallScript.Outcome.PATCHED ->
                    com.example.zhengdao.rootfs.RunLog.log("AgentInstaller: hermes 安装脚本已打 gateway 补丁（只告警不失败）")
                HermesInstallScript.Outcome.ALREADY -> Unit
                HermesInstallScript.Outcome.ANCHOR_MISSING ->
                    com.example.zhengdao.rootfs.RunLog.log(
                        "AgentInstaller: hermes 安装脚本里找不到 gateway 那一句（上游改版？）——未打补丁，" +
                            "安装若仍以退出码 1 收尾，原因多半是它"
                    )
                HermesInstallScript.Outcome.NO_FILE, null -> Unit
            }
        }
        val effectiveCmd = if (ok && script.isFile) {
            // guest 内 /workspace 即手机侧工作区，脚本以本地文件执行（不再走网络）
            "bash ${Store.GUEST_SCRIPTS_DIR}/${agent.id}-install.sh"
        } else {
            agent.installCmd ?: "" // 本地化失败：退回原始 curl|bash
        }
        return buildCommand(ctx, agent, effectiveCmd)
    }

    /**
     * 组合命令：清上一轮残锁（坑 #12，E-059 扩面）→ copy 模式 → 安装 → 自动启动。
     *
     * 清障只在**新一轮的第一条命令**里跑，此刻旧会话已被 kill（路由：kill 旧会话 → 起新的），
     * 所以不存在"删掉别人正持有的锁"这种并发风险；清的是被打断那轮留下的死锁。
     */
    private fun buildCommand(ctx: Context, agent: AppState.AgentInfo, installCmd: String): String {
        val launch = agent.launchCmd.substringBefore(' ')
        // hermes：包装器在**注入命令之前**由宿主侧落盘（不在命令里拼 base64，见下）
        if (agent.id == "hermes") prepareHermesUvWrapperOnHost(ctx)
        return buildString {
            append(AgentInstallPrep.staleLockCleanup())
            append("export UV_LINK_MODE=copy; ")
            // TMPDIR 指向 home（uv 缓存与目标同侧，避免跨挂载点；WorkBuddy 建议）。
            // TMPDIR 不带 UV_ 前缀，hermes pm 的 UV_* 剥离不影响它（未实测·推断）
            append("mkdir -p /root/tmp; export TMPDIR=/root/tmp; ")
            // ⚠️ hermes 的 uv 包装器（2026-10-05 官方 install.sh 源码核实）：
            // 它把 pinned uv 0.12.3 自装到 ~/.hermes/tools/uv-0.12.3-<target>/uv，
            // 且 ensure_uv 注释明写 "never a uv already on PATH"——系统 PATH 上的 uv
            // （包括此前的包装尝试）根本不会被调用；脚本还全局 UV_NO_CONFIG=1、pm 剥离
            // UV_* 环境变量。唯一可靠的注入点是它自己的二进制：真实 uv 挪为 uv.real，
            // 原路径放包装器（exec 前补回 UV_LINK_MODE=copy，pm 剥环境变量剥不到二进制内部）。
            // ensure_uv 见路径已有可执行文件就跳过下载 → 预置包装器即接管；
            // 包装器首跑自带 pinned 工件下载 + SHA256 校验（URL/哈希与 install.sh 同源）。
            // hermes 将来升 pin 版本号时：预置路径失效、新版本目录由 ensure_uv 正常下载
            // 裸奔一次（该轮安装可能仍报硬链接），重装一轮即被巡检的 swap 循环接管——已知降级。
            //
            // 🔧 2026-10-07 改：这一整段（挪真身 + 写包装器 + chmod）从**命令里**搬到
            //    **宿主侧落盘**（见 prepareHermesUvWrapperOnHost）。原因：包装器是 1.5KB
            //    的 base64，`echo … | base64 -d` 会被 bash 整行回显——真机实测点「安装」后
            //    整整一屏全是 base64 乱码，用户完全看不到安装进度。宿主侧写的是同一个文件
            //    （filesDir/home ⇄ guest /root 是同一个 bind），结果一样，终端里只剩可读输出。
            append(installCmd)
            // 安装结局**必须落在文件上**，否则 App 只能靠固定 TTL 猜 —— 而 hermes 官方
            // 安装器要 clone 1.1GB 仓库再建 Python 环境，真机实测远超 15 分钟：安装还在
            // 正常跑，卡片已经翻成「上次安装未完成」，用户看到一条假警报（2026-10-07 报）。
            // 这里把退出码写进 home 层的 ~/.zhengdao/install-<id>.rc
            //（宿主侧 = filesDir/home/.zhengdao/），成功失败都留痕；
            // 进程被 Ctrl-C / 关页打断则文件不更新，由 AgentRepository 用「有没有进程」补判。
            append("; __zd_rc=\$?; mkdir -p /root/.zhengdao; echo \$__zd_rc > /root/.zhengdao/install-${agent.id}.rc; ")
            // E-057 顺带（真机：装完敲 `hermes` 报 command not found）：hermes 把命令发布在
            // ~/.local/bin，而**当时那个 shell 的 PATH 是启动时的快照**（Debian 的 /etc/profile
            // 还会显式重置 PATH，root 分支里没有 ~/.local/bin）。往 /usr/local/bin——系统层、
            // 天然在所有 shell 的 PATH 里——补一条软链，装完当场可敲，不必重启 App。
            // （新开 login shell 的兜底见 ProotLauncher 的 profile.d 那一段。）
            if (agent.id == "hermes") {
                append("[ -f /root/.local/bin/hermes ] && ln -sf /root/.local/bin/hermes /usr/local/bin/hermes 2>/dev/null; ")
            }
            append("if [ \$__zd_rc -eq 0 ]; then ")
            append("echo \"[证道] 安装完成，正在启动 $launch（首次启动需初始化，请稍候）…\"; $launch; ")
            append("else echo \"[证道] 安装失败（退出码 \$__zd_rc）。原因就在上面几行；修好后回丹房点「安装」重试。\"; fi")
        }
    }

    /**
     * hermes 的 uv 包装器：**宿主侧直接落盘**，不走终端。
     *
     * 为什么（2026-10-07，真机截图驱动）：包装器本身是 1.5KB 的 base64，原先在命令里
     * `echo <base64> | base64 -d > …/uv` 写入 —— bash 会把这一整行**回显**出来，
     * 于是点「安装」的第一屏就是满屏 base64 乱码，用户看不到任何进度，
     * 正是他报的"hermes 安装有问题"里最直观的那一条。
     *
     * 宿主侧写的是同一个文件：proot 用 `-b $D/home:/root` 把 filesDir/home 绑成 guest
     * 的 /root，两边就是同一份存储（这条 bind 也是 rc 文件、启动脚本能互通的原因）。
     * 逻辑复用 [EnvSelfHeal.ensureHermesUvWrappers]（每次启动的巡检走的是同一函数），
     * ensurePinnedDir=true 额外保证 pinned 目录已存在——install.sh 的 ensure_uv
     * 见路径上有可执行文件就跳过下载，预置包装器即接管。
     */
    private fun prepareHermesUvWrapperOnHost(ctx: Context) {
        // 返回值是「本次有没有改动」（幂等巡检：已最新就是 false），**不是成功与否**。
        // 2026-10-09（E-056）：原先打成 `ok=false`，昨晚日志里连出三条让人以为预置失败，
        // 白查了一轮；改成说得清的中文。
        val outcome = runCatching {
            com.example.zhengdao.terminal.EnvSelfHeal
                .ensureHermesUvWrappers(File(ctx.filesDir, "home"), ensurePinnedDir = true)
        }
        val text = outcome.fold(
            onSuccess = { changed -> if (changed) "已写入/更新" else "已是最新（本次无需改动）" },
            onFailure = { e -> "预置失败：${e.message ?: e::class.java.simpleName}" },
        )
        com.example.zhengdao.rootfs.RunLog.log("hermes uv 包装器宿主侧预置：$text")
    }
}
