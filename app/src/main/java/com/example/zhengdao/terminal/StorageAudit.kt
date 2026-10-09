package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.rootfs.RootfsCache
import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * 存储明细与「可选工具」清理（Issue #8 的 B / C / D）。
 *
 * 背景：`~/.hermes/tools` 里躺着一整套 hermes 自带的工具链（chromium / ffmpeg / python /
 * node / uv / cua-driver / agent-browser …）。真机实测里 **可选工具那一组 ≈ 1.0G**
 * （chromium 602M + ffmpeg 329M + cua-driver 81M + agent-browser 10M），而它们都是
 * 「浏览器 / 媒体 / 桌面自动化」类能力：不用的时候没有理由占着，**删掉之后 hermes 在
 * 需要时会自己重新下载**（用户 2026-10-08 对 rootfs 预装清单的原话就是这个理由：
 * 「ffmpeg 是 hermes agent 要用的，不然 hermes 会自己下载的」）。
 *
 * 所以本文件的边界是：**只碰 [OPTIONAL_TOOL_NAMES] 这一组**——
 * python / node / uv / npm / ripgrep 这些核心运行时**绝不动**（删了 hermes 直接瘸），
 * 未归类的目录也不动（宁可少清，不可清错）。
 *
 * 另一个边界来自 #6 的结论：`~/.hermes/hermes-agent/.git`（638M）**不能剥离**
 * （`hermes update` 走 git blobless partial clone + `git fetch` / `merge --ff-only`），
 * 所以它只出现在明细里，没有删除入口。
 */
object StorageAudit {

    /** 可选工具（浏览器 / 媒体 / 桌面自动化）：不用时可以清掉，需要时 hermes 会重新下载。 */
    internal val OPTIONAL_TOOL_NAMES = listOf("chromium", "ffmpeg", "cua-driver", "agent-browser")

    /** 核心运行时：清掉等于把 hermes 弄瘸，任何清理入口都不许碰。 */
    internal val CORE_TOOL_NAMES = listOf("python", "node", "uv", "npm", "ripgrep", "rg", "busybox", "git", "tmux", "hermes")

    /** 明细的一行。`removable = true` 的才会被一键清理波及。 */
    data class Item(
        val group: String,
        val label: String,
        val mb: Long,
        val removable: Boolean,
        val note: String = "",
    )

    internal enum class ToolKind { OPTIONAL, CORE, OTHER }

    /**
     * 公共区 rootfs 安装包留档数：**当前 + 上一个**（用户 2026-10-08 定案）。
     * 与 `RootfsCache.pruneKeep(ctx, keep = 2)`、`CacheCleaner.prunableRootfsBytes` 同口径。
     */
    internal const val KEEP_ROOTFS_ARCHIVES = 2

    /** `home/.hermes/tools`（hermes 自带工具的落地处）。 */
    /**
     * `tools/` 目录：注意 [HermesEnv.hermesHome] **已经就是** `files/home/.hermes`，
     * 所以这里只能拼 `tools`（第一版写成 `.hermes/tools` ⇒ 变成 `…/.hermes/.hermes/tools`，
     * 真机上表现为「面板说可清理 0MB」，见 ERRATA E-075）。
     */
    internal fun toolsDir(hermesHome: File): File = File(hermesHome, "tools")

    /**
     * 目录名 → 工具类别。按**词边界**匹配（目录名等于工具名，或后面紧跟 `-`），
     * 免得 `chromium` 之类的名字被前缀误伤成别的东西（`uvicorn` 式的教训，见 AgentProcesses）。
     */
    internal fun toolKind(dirName: String): ToolKind {
        fun hit(names: List<String>) = names.any { n ->
            dirName.equals(n, ignoreCase = true) || dirName.startsWith("$n-", ignoreCase = true)
        }
        return when {
            hit(OPTIONAL_TOOL_NAMES) -> ToolKind.OPTIONAL
            hit(CORE_TOOL_NAMES) -> ToolKind.CORE
            else -> ToolKind.OTHER
        }
    }

    private fun note(kind: ToolKind): String = when (kind) {
        ToolKind.OPTIONAL -> "可选（浏览器/媒体/自动化）：删了 hermes 用到时会自己重新下载"
        ToolKind.CORE -> "核心运行时：不动"
        ToolKind.OTHER -> "未归类：不动"
    }

    /** hermes 工具逐项的占用（只列真的占地方的），按体积降序。 */
    internal fun toolItemsIn(tools: File): List<Item> {
        val dirs = runCatching { tools.listFiles { f -> f.isDirectory } }.getOrNull() ?: return emptyList()
        return dirs.map { d ->
            val kind = toolKind(d.name)
            Item(
                group = "hermes 工具",
                label = d.name,
                mb = CacheCleaner.bytesToMb(CacheCleaner.fileLengths(d)),
                removable = kind == ToolKind.OPTIONAL,
                note = note(kind),
            )
        }.filter { it.mb > 0 }.sortedByDescending { it.mb }
    }

    fun toolItems(ctx: Context): List<Item> =
        runCatching { toolItemsIn(toolsDir(HermesEnv.hermesHome(ctx))) }.getOrDefault(emptyList())

    /** 可选工具合计（MB）：这就是 #8-B 里「能省多少」的真机数字来源。 */
    fun optionalToolsMb(ctx: Context): Long =
        toolItems(ctx).filter { it.removable }.sumOf { it.mb }

    /**
     * 清理可选工具：只删 [OPTIONAL_TOOL_NAMES] 命中的目录，返回 `删除项数 to 释放字节数`。
     *
     * 双保险与 [CacheCleaner.cleanAgentCaches] 同款：目标必须真的在 `tools/` 之下
     * （符号链接指向别处就跳过），删不掉的部分静默跳过 —— 宁可少报，也不抛异常。
     */
    fun cleanOptionalTools(ctx: Context): Pair<Int, Long> {
        val tools = runCatching { toolsDir(HermesEnv.hermesHome(ctx)) }.getOrNull() ?: return 0 to 0L
        val res = cleanOptionalToolDirs(tools)
        if (res.first > 0) {
            RunLog.log(
                "清理可选工具: 删除 ${res.first} 项（浏览器/媒体/自动化），释放 " +
                    "${CacheCleaner.bytesToMb(res.second)}MB（需要时 hermes 会重新下载）"
            )
        }
        return res
    }

    /**
     * 真正的删除动作（不吃 Context，便于单测锁死"只删白名单"）。
     *
     * 双保险与 [CacheCleaner.cleanAgentCaches] 同款：目标必须真的在 `tools/` 之下
     * （符号链接指向别处就跳过），删不掉的部分静默跳过 —— 宁可少报，也不抛异常。
     */
    internal fun cleanOptionalToolDirs(tools: File): Pair<Int, Long> {
        val root = runCatching { tools.canonicalPath }.getOrNull() ?: return 0 to 0L
        val dirs = runCatching { tools.listFiles { f -> f.isDirectory } }.getOrNull() ?: return 0 to 0L
        var count = 0
        var freed = 0L
        dirs.forEach { d ->
            if (toolKind(d.name) != ToolKind.OPTIONAL) return@forEach
            val ap = runCatching { d.canonicalPath }.getOrNull() ?: return@forEach
            if (!ap.startsWith(root + File.separator)) return@forEach
            val before = CacheCleaner.fileLengths(d)
            if (CacheCleaner.deleteTree(d)) {
                freed += before
                count++
            }
        }
        return count to freed
    }

    /**
     * 全景明细：能清的标 `removable`，不能清的把**为什么不能清**写出来。
     *
     * 数字口径与 [CacheCleaner.cleanableMb] 同源（都走 `fileLengths` / `bytesToMb`），
     * 免得再出现 E-073 那种「面板说没有、其实堆了一堆」的两套口径。
     */
    fun items(ctx: Context): List<Item> {
        val out = mutableListOf<Item>()

        // A. Agent 包缓存（npm/uv/pip/opencode 包缓存/hermes 缓存/太极缓存）
        runCatching { CacheCleaner.agentCacheMeasure(ctx) }.getOrDefault(emptyList())
            .forEach { (name, mb) ->
                out += Item("Agent 包缓存", name, mb, removable = true, note = "删了下次安装会重下")
            }

        // A. hermes 旧依赖代（facts.json 只指向一代，其余是历次 repair/update 的残留）
        runCatching { HermesEnv.deadGenerationsMb(HermesEnv.hermesHome(ctx)) }
            .getOrDefault(0L)
            .takeIf { it > 0 }
            ?.let {
                out += Item(
                    "hermes 依赖代", "旧依赖代（可回收）", it, removable = true,
                    note = "保留当前代与最新一代，删完 hermes 照跑",
                )
            }

        // B. hermes 工具（可选工具可清，核心运行时不动）
        out += toolItems(ctx)

        // hermes 本体：只展示，不给删除入口（.git 不可剥离，#6 结论）
        runCatching { File(HermesEnv.hermesHome(ctx), "hermes-agent") }
            .getOrNull()
            ?.takeIf { it.isDirectory }
            ?.let {
                val mb = CacheCleaner.bytesToMb(CacheCleaner.fileLengths(it))
                if (mb > 0) out += Item("hermes 本体", "hermes-agent（含 .git）", mb, removable = false, note = ".git 不可剥离（#6）")
            }

        // C. 公共区：安装包留档（rootfs 三件套；装完留着能省重下，超过两个的旧包可回收）
        runCatching { RootfsCache.listArchives(ctx) }.getOrDefault(emptyList())
            .let { archives ->
                archives.drop(KEEP_ROOTFS_ARCHIVES).forEach { f ->
                    out += Item(
                        "公共区安装包", "旧 rootfs 安装包 ${f.name}",
                        CacheCleaner.bytesToMb(f.length()), removable = true,
                        note = "只保留当前 + 上一个（自动清理会收）",
                    )
                }
            }

        // C. 公共区工作区数据：只展示，永不清
        runCatching { Store.root(ctx) }.getOrNull()?.let { root ->
            listOf("agents" to "Agent 安装脚本与账本", "logs" to "运行日志", "资料库" to "资料库").forEach { (n, label) ->
                val d = File(root, n)
                val mb = CacheCleaner.bytesToMb(CacheCleaner.fileLengths(d))
                if (mb > 0) out += Item("公共区工作区", label, mb, removable = false, note = "用户数据：不清")
            }
        }

        return out
    }

    /** 可清理合计（MB）：体检面板那一行显示的就是它。 */
    fun totalCleanableMb(ctx: Context): Long =
        runCatching { items(ctx).filter { it.removable }.sumOf { it.mb } }.getOrDefault(0L)
}
