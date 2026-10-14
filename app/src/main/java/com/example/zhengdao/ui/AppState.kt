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
        /**
         * 能不能进「恢复全部」的候选（用户两次拍板：2026-10-08 AGY、2026-10-10 Hermes）。
         *
         * `false` = **这条路现在走不通**。把走不通的条目当成"装过、可以一键恢复"，只会递给用户
         * 一个点了必错的按钮。两条判据，任一条成立就关：
         *
         * 1. **官方安装器在受限网络下必然失败**（AGY）。判据不是猜的，是真机网络探针
         *    （2026-10-08 17:19）：环境里只有 `registry.npmmirror.com -> 200`，`github.com` /
         *    `raw.githubusercontent.com` / `antigravity.google` / `registry.npmjs.org` /
         *    `www.google.com` 全 `000`。用户原话：「清掉 AGY 的恢复功能吧，谷歌对地域限制太严了，
         *    除非把终端的 IP、地址什么的都改成国外才行」。
         * 2. **依赖链重、恢复出来多半是半截环境**（Hermes）。它的安装脚本只落一个入口，真要跑起来
         *    还得 Python/uv 现建 venv，实测反复死在依赖环境这一环（E-025 搬家包恢复后必崩、
         *    E-056 uv 的 wheel 缓存挂共享存储建不了软链）。用户原话（2026-10-10）：「恢复上次安装
         *    的 agent 这个功能可以不加 hermes agent 了，应该只有像 claude、AGY 这些才行，
         *    依赖少的那种？」
         *
         * 两种情形都**只关"恢复入口"**：安装卡片、文件探测、卸载能力全部保留（网络好时用户仍可
         * 自己点装，装好了会被文件探测认出来，账本照样记账）。见 `docs/ERRATA.md` E-040 / E-081。
         */
        val restorable: Boolean = true,
    )

    fun rootfsInstalled(ctx: Context): Boolean =
        File(ctx.filesDir, "rootfs/.zhengdao-rootfs-ok").isFile

    private fun firstExisting(ctx: Context, relative: List<String>): Boolean =
        relative.any { existsInHost(ctx, it) }

    /**
     * 宿主视角下"这条路径是不是一个可执行文件" —— **逐段解析软链**（§B 顺序 26，2026-10-14）。
     *
     * ## 为什么不能只解开一跳
     * Agent 安装脚本**在 guest 里**创建命令软链，目标是 guest 的绝对路径，例如
     *     /root/.local/bin/claude -> /root/.local/share/claude/versions/2.1.292
     * 而 guest 的 /root 是 App 端 `files/home` bind 进去的（设计文档 §8）。App 在**宿主**侧
     * 用 File.isFile() 读它是悬空的 ⇒ 明明装好了却显示「安装」。更要紧的是**两跳**链（真机实测，
     * Codex CLI 正是这条）：
     *     ~/.local/bin/codex -> ~/.codex/packages/standalone/current/bin/codex
     *                        -> releases/<ver>/bin/codex
     * 旧实现只把 /root/ 映射一跳 ⇒ 第二跳仍然悬空 ⇒ 判定失败。这也解释了为什么旧实现下
     * agy（真身文件）能认出、claude（一跳链）能认出、**codex（两跳链）认不出**。
     *
     * ## 算法：按**路径分量**逐段走（不是"循环 readSymbolicLink N 次"）
     * 从 filesDir 起、按相对路径的分量一段段前进；遇到**在宿主上到不了**的软链，就把它的目标里
     * 的 /root/ 前缀重写成 home/（guest 的 /root == App 的 files/home），再与**剩余分量**重拼，
     * 从起点重走一遍。跳数封顶 [MAX_LINK_HOPS]（防自指/环），超限即判「没装」。
     *
     * ⚠️ 上一版试过"循环 readSymbolicLink 直到不抛异常"——真机已回退：那样每次只解开**整条
     * 路径的最后一段**，中间分量若是链就永远走不过去。逐段走才对。
     *
     * ⚠️ 跨平台：判 /root 前缀时两种分隔符都认 —— Windows 上 `readSymbolicLink().toString()`
     * 给的是 `\root\...`（反斜杠），只判 `/root/` 会漏（AgentCatalogTest 那条软链用例在"能建
     * 软链的 Windows 机器"上真红就是这个原因，本刀一并修掉）。
     */
    private fun existsInHost(ctx: Context, relative: String): Boolean {
        val base = ctx.filesDir
        var rest = relative.replace('\\', '/').trim('/')
        repeat(MAX_LINK_HOPS + 1) { hop ->
            if (File(base, rest).isFile) return true
            if (hop == MAX_LINK_HOPS) return false
            rest = resolveFirstDanglingLink(base, rest) ?: return false
        }
        return false
    }

    /** 宿主侧软链最多解几跳（防自指/环）。Codex 那条链是两跳，留一倍余量。 */
    private const val MAX_LINK_HOPS = 4

    /**
     * 从 [base] 起逐分量走 [rest]，把**第一条宿主上到不了的软链**换成宿主可解的相对路径并返回；
     * 走完全程都没遇到这种链则返回 null（= 这条路径在宿主上到不了 ⇒ 判「没装」）。
     *
     * 只解"到不了的"链：宿主上本来就能正常解析的链（比如将来某条链指向宿主真路径）保持原样，
     * 不必也不该改写。
     */
    private fun resolveFirstDanglingLink(base: File, rest: String): String? {
        val parts = rest.split('/').filter { it.isNotEmpty() }
        var acc = ""
        for (i in parts.indices) {
            acc = if (acc.isEmpty()) parts[i] else "$acc/${parts[i]}"
            val cur = File(base, acc)
            if (cur.exists()) continue // 真实存在（含能正常解析的链）⇒ 继续往下走
            if (!isSymbolicLink(cur)) return null // 真实分量就缺了 ⇒ 这条路径到此为止
            val target = readLinkOrNull(cur) ?: return null
            val mapped = mapGuestRootToHome(target) ?: return null
            val remainder = parts.drop(i + 1).joinToString("/")
            return if (remainder.isEmpty()) mapped else "$mapped/$remainder"
        }
        return null
    }

    private fun isSymbolicLink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    private fun readLinkOrNull(f: File): String? =
        runCatching { java.nio.file.Files.readSymbolicLink(f.toPath()).toString() }.getOrNull()

    /**
     * guest 视角的绝对路径 → 宿主相对路径（相对 filesDir）。
     *
     * guest 的 `/root` 就是 App 的 `files/home`（设计文档 §8）⇒ `/root/x` → `home/x`。
     * 两种分隔符都认（Windows 的 readSymbolicLink 会给 `\root\...`）。
     * 其它绝对路径（如 guest 的 `/usr/...`，对应宿主 `files/rootfs/usr/...`）**不映射** ——
     * 与改动前口径一致（判「没装」），不猜根。
     */
    private fun mapGuestRootToHome(target: String): String? {
        val t = target.replace('\\', '/')
        return when {
            t == "/root" -> "home"
            t.startsWith("/root/") -> "home/" + t.removePrefix("/root/")
            else -> null
        }
    }

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
                    "codex" to "@openai/codex",
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
                // 不参与「恢复全部」：装脚本只落一个入口，跑起来还要 Python/uv 现建 venv，
                // 恢复出来多半是半截环境（E-025 / E-056；用户 2026-10-10 拍板，见 restorable 注释）。
                restorable = false,
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
                // 不参与「恢复全部」：官方安装器要从 antigravity.google 拉 manifest，
                // 国内网络到不了（见 AgentInfo.restorable 的注释与网络探针记录）。
                restorable = false,
            ),
            AgentInfo(
                id = "codex",
                name = "Codex CLI",
                desc = "OpenAI 官方编程 Agent（可用自己的 API Key 换基底）",
                // `--no-daemon` 不是装饰：非 root 的 Android 上 bubblewrap 起不来
                // （/proc/sys/kernel/overflowuid 读不到）⇒ Codex 的 pid-managed app-server
                // 子进程秒退。它是**顶层**标志，写在 exec 后面会报 unexpected argument。
                launchCmd = "codex --no-daemon",
                // npm 全局安装（与 opencode 同类）：不下载脚本、不预置任何配置，装完走官方登录；
                // 想换基底（DeepSeek 等）由用户自己在 guest 里配 —— 卡不做这件事。
                installCmd = "npm i -g @openai/codex",
                installed = rootfsInstalled && firstExisting(
                    ctx, listOf(
                        "rootfs/usr/bin/codex", "rootfs/usr/local/bin/codex", "home/.local/bin/codex",
                    )
                ),
                // 卸载只删程序，配置 ~/.codex 保留（与 claude-code / hermes 同一口径）
                uninstallCmd = "npm uninstall -g @openai/codex 2>/dev/null; " +
                    "rm -rf /root/.local/bin/codex /usr/lib/node_modules/@openai/codex 2>/dev/null; " +
                    "echo '[卸载] Codex CLI 已删除（配置 ~/.codex 保留）'",
            ),
        )

    /** 状态卡折叠态的一行摘要。 */
    fun summaryLine(ctx: Context): String {
        val installedCount = agents(ctx).count { it.installed }
        val env = if (rootfsInstalled(ctx)) "Debian 13.7 已安装" else "环境未安装"
        return "Android ${Build.VERSION.RELEASE} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"} · $env · $installedCount 个 Agent"
    }
}
