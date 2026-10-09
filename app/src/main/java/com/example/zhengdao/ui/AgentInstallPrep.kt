// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import java.io.File

/**
 * 安装前的两道准备（都是纯函数，JVM 可测）：
 * ① **脚本可信度校验**、② **上一轮残留的清理命令**。
 *
 * ## 为什么单独拿出来（E-059）
 *
 * 用户 2026-10-09 拍板要修的是「**用户不会卸载 App，只会一直点重试**」这个场景。
 * 顺着这条线查，重试路径上有两处"越重试越坏"的地方：
 *
 * 1. 安装脚本**缓存在共享存储**（`Download/证道/agents/scripts/`），而 [AgentInstaller]
 *    原本的复用判据只有「文件存在且 ≥ 64 B」。共享存储**卸载 App 也删不掉**——
 *    于是"卸载重装"这个万能解在这里失效：一份代理拦截页（HTTP 200 的 HTML）、一份
 *    上游 CDN 半截吐出的正文、或一份被手改坏的文件，会被**每一次重试**当成好脚本复用，
 *    用户在丹房看到的永远是同一个失败，且他没有任何手段自救。
 * 2. 上一轮被打断（关页面 / App 被杀 / OOM）留下的锁文件。原先只清 `index.lock`
 *    一种（坑 #12 的 git 残锁），而同类残留还有 git 的 `shallow.lock`、
 *    `packed-refs.lock`、`HEAD.lock`，以及 uv / pm 自己的 `*.lock`。
 */
internal object AgentInstallPrep {

    /** 小于这个体积的一定不是安装脚本（真机实测三个官方脚本 40 KB ~ 100 KB）。 */
    const val MIN_CHARS = 512

    /** 头部这么多字符里出现 HTML 特征，就判定是「错误页」而不是脚本。 */
    private const val HEAD_CHARS = 2048

    /**
     * 这份文本像不像一个能交给 `bash` 跑的安装脚本。
     *
     * 刻意**只做粗判、宁放行不误杀**：判负只说明"肯定不是脚本"，判正则继续交给 `bash`
     * （真有语法问题它会自己报错，用户仍能在终端里看到原因）。为什么不敢严：
     * 误判的代价不只是"白下一次"，对 hermes 还意味着**丢掉 gateway 补丁**（见
     * [HermesInstallScript]）——那会让装好的 hermes 又以退出码 1 收尾，正是要修的那个病。
     * 所以这里只要求"第一行非空的内容是注释/shebang"，而不是"必须以 #! 开头"：
     * 官方脚本第一行就是 `#!/usr/bin/env bash`（真机拉下来的 44,739 B 版本核实过），
     * 但上游哪天在前面加一行版权注释也不该因此被拒。
     */
    fun looksUsableScript(text: String?): Boolean {
        if (text == null) return false
        if (text.length < MIN_CHARS) return false
        val head = text.take(HEAD_CHARS).lowercase()
        if (head.contains("<!doctype") || head.contains("<html")) return false
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return false
        return firstLine.startsWith("#")
    }

    /** 同上，按文件读；读不动 / 不是文件 = 不可用。 */
    fun fileLooksUsable(f: File): Boolean =
        runCatching { f.isFile && looksUsableScript(f.readText()) }.getOrDefault(false)

    /**
     * 安装前的清障命令。
     *
     * 安全性来自调用时机：它是**新一轮安装会话的第一条命令**，而派发安装时 App 已经
     * kill 掉旧会话（真机日志「路由：kill 旧会话 → 起新的」）——所以此刻这些目录里
     * 不存在"别人正持有"的锁，删掉只剩好处。锁定在 agent 自己的三个目录里，
     * 不碰 `/workspace`、`/sdcard`（用户数据）和 `/root/.local`（已装好的二进制）。
     */
    fun staleLockCleanup(): String =
        "find /root/.hermes /root/.claude /root/.config -name '*.lock' -delete 2>/dev/null; "
}
