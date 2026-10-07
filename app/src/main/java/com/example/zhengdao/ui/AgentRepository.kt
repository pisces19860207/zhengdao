// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import android.content.Context
import java.io.File

/**
 * Agent 安装状态仓库（M3 骨架 §2 简化版）。
 *
 * - 「已安装」仍以**真实文件探测**为准（AppState.agents）——安装命令也可能由用户在终端
 *   手动敲，文件是唯一可信事实；
 * - 本仓库负责「安装中 / 未完成」这两个瞬态，供卡片决定按钮形态与状态行文案。
 *
 * ⚠️ 判据于 2026-10-07 重写（用户报「hermes 明明在装，卡片却报上次安装未完成」）：
 *   旧实现只有「文件出现」和固定 15 分钟 TTL 两条出口。而 hermes 官方安装器要
 *   clone 1.1GB 仓库再建 Python 环境，真机实测**远超 15 分钟** ⇒ 安装还在正常跑，
 *   卡片已经翻成失败 —— 一条凭空的假警报。
 *   新判据三条，全部落在可验证的事实上：
 *     1. 安装命令结尾自己把退出码写进 ~/.zhengdao/install-<id>.rc（见 AgentInstaller）；
 *     2. 进程还在 = 还在装（扫 /proc；proot 不做 PID 隔离，宿主侧直接看得到）；
 *     3. 进程没了、也没有退出码 = 被打断（Ctrl-C / 关页面 / App 被杀）。
 */
object AgentRepository {

    private const val PREFS = "zhengdao-agent-state"

    /** 刚点下「安装」到进程真正起来之间的宽限：这期间没进程属正常，不算被打断。 */
    private const val START_GRACE_MS = 90 * 1000L

    enum class State { NotInstalled, Installing, Installed, Failed }

    /**
     * 三段式判据（2026-10-07 重写第二版，第一版在真机上被自己的"重试安装"打脸了）。
     *
     * 第一版把"退出码 != 0 → 失败"提到最前面解决了一个问题，却又漏了另一个：
     * 点「重试安装」时 [markInstalling] 会**先删掉 rc 文件**，于是
     * "正在装、rc 还没落盘"这一路会直接落回 `installed` 分支 —— 卡片显示「启动」，
     * 而安装其实正跑着。真机实测：点完重试安装 30 秒后看卡片，写着「启动」。
     *
     * 所以判据按**这一轮安装的进度**分段，而不是按单个信号：
     *   1. rc 非 0            → 失败（硬证据，优先于"文件在那儿"，见下）
     *   2. 有一轮在跑、尚无 rc → 进程还在 / 未过宽限期 ⇒ 安装中；否则 ⇒ 被打断
     *   3. 没有进行中的轮次    → 以**文件**为准（这也是"用户在终端里手动装好"的兜底）
     */
    fun stateOf(ctx: Context, agent: AppState.AgentInfo): State {
        val rc = readRc(ctx, agent.id)

        // ① 退出码非 0 = 这一轮确实没成。**必须排在 installed 之前**：
        //    安装在最后一步失败时，可执行文件往往已经被安装器建好了 ——
        //    hermes 实测：`~/.local/bin/hermes`（转发脚本）在 22:29 就落位，
        //    而 Python 依赖装不上、脚本退出码 1。旧顺序先判 installed 并顺手删掉 rc，
        //    于是卡片理直气壮显示「启动」——点下去只拿到一句报错，卡片在骗用户，
        //    正好违反「失败必须可见」。
        if (rc != null && rc != 0) {
            clearInstalling(ctx, agent.id)
            return State.Failed
        }

        // ② 这一轮还没收尾（点了安装、退出码尚未落盘）
        val started = prefs(ctx).getLong("installing_${agent.id}", 0L)
        if (started > 0L && rc == null) {
            if (installProcessAlive(agent.id)) return State.Installing
            // 刚点下去、进程还没起来：宽限期内一律算"安装中"（否则会有 90 秒的假失败）
            if (System.currentTimeMillis() - started < START_GRACE_MS) return State.Installing
            // 进程没了、也没有退出码 = 被打断（Ctrl-C / 关页面 / App 被杀）
            clearInstalling(ctx, agent.id)
            return State.Failed
        }

        // ③ 没有进行中的轮次：文件是唯一可信事实
        if (agent.installed) {
            clearInstalling(ctx, agent.id)
            runCatching { rcFile(ctx, agent.id).delete() }
            return State.Installed
        }
        // rc == 0 却探不到可执行文件：脚本说成功了人却在原地，交给 failureReason 说明
        if (rc != null) {
            clearInstalling(ctx, agent.id)
            return State.Failed
        }
        return State.NotInstalled
    }

    /** 点 [安装] 时登记（TerminalActivity 在 autocmd 派发时调用）。 */
    fun markInstalling(ctx: Context, agentId: String) {
        prefs(ctx).edit().putLong("installing_$agentId", System.currentTimeMillis()).apply()
        runCatching { rcFile(ctx, agentId).delete() } // 新一轮开始，先清掉上一轮的结局文件
    }

    fun clearInstalling(ctx: Context, agentId: String) {
        prefs(ctx).edit().remove("installing_$agentId").apply()
    }

    /**
     * 失败的具体原因 —— 卡片状态行直接显示。
     *
     * 用户定的规矩「失败必须可见」：一条笼统的「上次安装未完成」等于没说，
     * 得告诉他**是失败还是被打断**、以及下一步点哪里。
     */
    fun failureReason(ctx: Context, agentId: String): String {
        val rc = readRc(ctx, agentId)
        return when {
            rc == null -> "上次安装没跑完（关页面或 Ctrl-C 会中断它）。点「安装」重试，这次的输出会留在终端里。"
            rc == 0 -> "安装脚本报告成功，但没找到可执行文件 —— 可能装到了别处。点「安装」再跑一次看看。"
            else -> "上次安装失败（退出码 $rc）。终端往上翻就是原因；点「安装」可重试。"
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 安装结局文件：guest 侧 /root/.zhengdao/install-<id>.rc ⇒ 宿主侧 filesDir/home/.zhengdao/ */
    private fun rcFile(ctx: Context, agentId: String) =
        File(File(ctx.filesDir, "home/.zhengdao"), "install-$agentId.rc")

    private fun readRc(ctx: Context, agentId: String): Int? = runCatching {
        val f = rcFile(ctx, agentId)
        if (f.isFile) f.readText().trim().toIntOrNull() else null
    }.getOrNull()

    /**
     * 该 Agent 的安装脚本进程还在不在。
     *
     * 依据：proot 不做 PID 隔离，guest 进程就是宿主进程，扫 /proc/<pid>/cmdline 即可。
     * 脚本被本地化后固定叫 `<id>-install.sh`。
     *
     * ⚠️ 这里的扫 /proc 与终端那条「启动幂等」不是一回事，别一起删：
     *    终端侧扫进程判重已随「全局单会话」模型删除（换 Agent 就是 kill，不存在并存）；
     *    这里问的是**另一个问题**——"安装这个长任务还有没有在跑"，
     *    它没有别的信号可用（安装器在非 TTY 下的输出会被吞掉）。
     */
    private fun installProcessAlive(agentId: String): Boolean {
        val needle = "$agentId-install.sh"
        return runCatching {
            (File("/proc").listFiles() ?: emptyArray()).any { d ->
                d.name.toIntOrNull() != null && runCatching {
                    File(d, "cmdline").readBytes().toString(Charsets.UTF_8).contains(needle)
                }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }
}
