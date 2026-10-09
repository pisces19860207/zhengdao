// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：Linux procfs（/proc/<pid>/cmdline）、Android 官方文档（Process.myPid）。
package com.example.zhengdao.terminal

import java.io.File

/**
 * 「现在有安装/构建在跑吗」（Issue #3，2026-10-10）。
 *
 * 自动清理第一档删的都是**能再下载回来**的东西（npm / uv / pip 缓存、Agent 包缓存、
 * 旧版安装包），删掉不会坏功能；但**正在装依赖时删掉它**会让那次安装白下、变慢甚至失败。
 * Issue #3 因此写明跳过条件：「检测到 uv/npm/apt 进程在跑」。
 *
 * 探测手段：扫 `/proc/<pid>/cmdline`。App 只读得到**自己 UID** 的进程（proot 里的 guest
 * 进程就是 App 的子进程、同为该 UID）⇒ 不需要 root，也不会越界看到别人的进程。
 *
 * 判定口径**宁可漏清、不可误清**：token 取 basename 后与守卫表做**词边界**比较，分隔符必须
 * 是行尾或非字母（`.`、`-`、数字…）。于是 `uv.real`（hermes 内嵌 uv 的真身）、`apt-get`、
 * `python3.14` 命中，而 `uvicorn` 这类只是前缀相同的名字**不**命中。
 */
object AgentProcesses {

    /** 守卫表：包管理器 / 构建链 / agent 本体。见到任一就当"有人正在干活"。 */
    internal val GUARD_TOKENS: List<String> = listOf(
        // 包管理器（Issue #3 点名的三个都在里面）
        "uv", "npm", "npx", "yarn", "pnpm", "pip", "pip3", "apt", "apt-get", "dpkg",
        // 构建链
        "git", "cargo", "make", "gcc", "clang", "cc1",
        // agent 本体（它们在跑 = 任务在跑，通常会连带装依赖）
        "hermes", "opencode", "python", "python3", "node",
    )

    /** 词边界比较：`uv.real` / `apt-get` / `python3.14` 命中，`uvicorn` 不命中。 */
    internal fun boundaryMatch(token: String, guard: String): Boolean {
        if (!token.startsWith(guard)) return false
        if (token.length == guard.length) return true
        return !token[guard.length].isLetter()
    }

    /** 这个 token 命中哪个守卫词（没命中返回 null）。 */
    internal fun matchGuard(token: String): String? =
        GUARD_TOKENS.firstOrNull { boundaryMatch(token, it) }

    /** cmdline（NUL 分隔）→ basename token；`uv.real` 归一成 `uv`（去掉包装层的后缀）。 */
    internal fun tokensOf(cmdline: String): List<String> =
        cmdline.split('\u0000', ' ', '\t', '\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringAfterLast('/') }
            .map { if (it.endsWith(".real")) it.removeSuffix(".real") else it }

    /** 命中集合（去重、保持守卫表顺序）。 */
    internal fun hits(tokens: List<String>): List<String> =
        tokens.mapNotNull { matchGuard(it) }.distinct()

    /**
     * 扫 [procRoot]，返回命中的进程描述（`pid 首个 token（命中词）`）。
     * [procRoot] 与 [selfPid] 可注入，单测喂假目录即可；真机是 `/proc` + [android.os.Process.myPid]。
     */
    internal fun scan(procRoot: File = File("/proc"), selfPid: Int = android.os.Process.myPid()): List<String> {
        val found = ArrayList<String>()
        val dirs = procRoot.listFiles() ?: return found
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            // 自己这个 App 进程不参与：它的 cmdline 是包名/zygote，若名字里恰好含守卫词会永远"忙"
            if (pid == selfPid) continue
            val raw = runCatching { File(d, "cmdline").readBytes() }.getOrNull() ?: continue
            if (raw.isEmpty()) continue
            val tokens = tokensOf(String(raw, Charsets.UTF_8))
            val hit = hits(tokens)
            if (hit.isNotEmpty()) {
                found += "$pid ${tokens.firstOrNull().orEmpty()}（${hit.joinToString(",")}）"
            }
        }
        return found
    }

    /** 有人干活 → 一句人话（直接进日志/界面）；没人干活 → null。 */
    fun busyReason(): String? {
        val found = runCatching { scan() }.getOrDefault(emptyList())
        if (found.isEmpty()) return null
        val head = found.take(3).joinToString("；")
        return "有安装/构建在跑：$head" + if (found.size > 3) " 等 ${found.size} 个进程" else ""
    }
}
