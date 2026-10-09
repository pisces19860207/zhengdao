package com.example.zhengdao.ui

import java.io.File

/**
 * hermes 官方安装脚本的**本地补丁**（ERRATA E-057，2026-10-09 真机）。
 *
 * 背景：安装脚本的最后一步 `stage_gateway()` 会跑
 * ```
 * "$INSTALL_DIR/.hermes/bin/hermes" gateway install --if-missing </dev/tty || fail "gateway installation failed"
 * ```
 * 它要在系统里装一个 **systemd 服务**。proot 里没有 init/systemd，于是 `hermes gateway install`
 * 打印 `Service installation not supported on this platform.` / `Run manually: hermes gateway run`
 * 后**非零退出** ⇒ `fail` ⇒ 整个安装脚本 exit 1。
 *
 * 而 App 侧（[AgentInstaller]）把「脚本退出码」当作「安装成功与否」写进
 * `~/.zhengdao/install-hermes.rc`，丹房据此显示「安装失败（退出码 1）」——**尽管 hermes 本体
 * 已经装好、配置也已完成**（真机截图：`✓ Install complete!` 之后就这一条红叉）。用户因此
 * 反复点「安装」，每次都白等一遍全量安装。
 *
 * 处置：下载脚本后、执行之前，把那一句的失败尾巴换成**只告警**。为什么改脚本而不是改 App 的
 * 判定逻辑：App 端的判定只能靠「hermes 二进制在不在」猜，而它在上一次半成品安装后也在
 * ——那就把后来真正的失败（venv、依赖、git）一并吞掉了。改在**失败的那一处**才精确。
 *
 * 锚点策略：只认上游那一句**逐字**的尾巴。上游改版导致锚点消失时不猜、不改、原样放过
 * （[Outcome.ANCHOR_MISSING]），宁可回到「rc=1」也不要乱改别人的脚本。
 */
internal object HermesInstallScript {

    /** 上游（2026-10-07 版，44,739 B）`stage_gateway()` 里的失败尾巴，全文件唯一。 */
    const val GATEWAY_FAIL_TAIL = "|| fail \"gateway installation failed\""

    /**
     * 换成只告警：`set -e` 下 `cmd || log_warn …` 整体退出 0，脚本继续走 `complete` 阶段。
     * 纯 ASCII，避免在别人的脚本里引入编码差异。
     */
    const val GATEWAY_WARN_TAIL =
        "|| log_warn \"gateway service not installable here (no systemd inside proot); " +
            "start it by hand if you need it: hermes gateway run\""

    enum class Outcome {
        /** 本次真的改了（写回文件）。 */
        PATCHED,

        /** 已经是补丁后的样子，没动。 */
        ALREADY,

        /** 找不到锚点（上游改版？）——原样放过，并在日志里说出来。 */
        ANCHOR_MISSING,

        /** 脚本文件不在。 */
        NO_FILE,
    }

    /**
     * 纯函数：返回打过补丁的脚本正文。
     *  - 锚点在且尚未打过 ⇒ 替换（**只替换这一处**，别处的 `|| fail "…"` 一律不动）；
     *  - 已经是补丁后的内容 ⇒ 原样返回（幂等）；
     *  - 锚点不在 ⇒ 原样返回。
     */
    fun patch(text: String): String = when {
        text.contains(GATEWAY_WARN_TAIL) -> text
        text.contains(GATEWAY_FAIL_TAIL) -> text.replace(GATEWAY_FAIL_TAIL, GATEWAY_WARN_TAIL)
        else -> text
    }

    /** 幂等落盘：内容需要变才写。 */
    fun ensurePatched(script: File): Outcome {
        if (!script.isFile) return Outcome.NO_FILE
        val before = script.readText()
        val after = patch(before)
        return when {
            after == before && before.contains(GATEWAY_WARN_TAIL) -> Outcome.ALREADY
            after == before -> Outcome.ANCHOR_MISSING
            else -> {
                script.writeText(after)
                Outcome.PATCHED
            }
        }
    }
}
