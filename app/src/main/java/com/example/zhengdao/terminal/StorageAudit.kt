// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 本文件只按 POSIX 标准接口（execve / wait4 / pipe / lstat 等）与 Android 官方文档实现，未参考任何第三方同类应用（含 termux-app）的源码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.rootfs.RootfsCache
import com.example.zhengdao.rootfs.RunLog
import org.json.JSONObject
import java.io.File

/**
 * 存储明细与「可选工具」清理（Issue #8 的 B / C / D）。
 *
 * 背景：`~/.hermes/tools` 里躺着一整套 hermes 自带的工具链（chromium / ffmpeg / python /
 * node / uv / cua-driver / agent-browser …），真机实测那几件大件合计 ≈1.0G。
 *
 * ⚠️ **2026-10-11 更正（ERRATA E-092）：ffmpeg 不是"可选工具"。** 真机上把它清掉之后，
 * hermes 每次启动都报 `install out of sync (ffmpeg: not installed or outdated)`——
 * `~/.hermes/hermes-agent/pm/install.py` 的 `drift()` 只跳过 `package.optional` 的包，
 * 而 ffmpeg 进了问题列表 ⇒ 它是**必需包**。系统层那份（rootfs 自带的 `/usr/bin/ffmpeg`）
 * hermes 也不认：`pm.activate()` 会把自己的 store 工具目录插到 PATH 最前（docstring 原话
 * "never a user copy that sorts earlier"）。所以 ffmpeg 归 [REQUIRED_TOOL_NAMES]，
 * 任何清理入口都不许碰；并且**删掉的包不会自己回来**——要用户在终端跑 `hermes pm install`。
 *
 * 所以本文件的边界是：**只碰 [OPTIONAL_TOOL_NAMES] 这一组**（桌面自动化 cua-driver）——
 * python / node / uv / npm / ripgrep 这些核心运行时、ffmpeg（必需包）、以及
 * **用户点名要留的浏览器工具**（[PINNED_TOOL_NAMES]：agent-browser + chromium），都**绝不动**，
 * 未归类的目录也不动（宁可少清，不可清错）。
 *
 * 另一个边界来自 #6 的结论：`~/.hermes/hermes-agent/.git`（638M）**不能剥离**
 * （`hermes update` 走 git blobless partial clone + `git fetch` / `merge --ff-only`），
 * 所以它只出现在明细里，没有删除入口。
 */
object StorageAudit {

    /**
     * hermes 的**必需**包：它的启动自检（`pm/install.py` 的 `drift()`）会因为缺它们而报
     * `install out of sync`。清掉 = 每次启动都带着一条红字，**任何清理入口都不许碰**。
     */
    internal val REQUIRED_TOOL_NAMES = listOf("ffmpeg")

    /**
     * **用户点名要留的 hermes 扩展**（2026-10-11）：「hermes 自己下载的那个浏览器工具也是要
     * 保留的，虽然没有也可以用，但是有的话 hermes 是可以用的，我试过了。」
     *
     * `agent-browser`（驱动）+ `chromium-1208`（被驱动的浏览器本体）是**一套**，少了任一半
     * 都不能开网页；它们当初被归进可选，是因为 hermes 的 lockfile 标着 `optional`——那是
     * "装不装都行"（针对别人的机器），不是"可以删"（针对这台机器上真用得起它的用户）。
     * ⇒ 从清理名单里摘出来，明细里显示「保留」，任何清理入口都不许碰。
     */
    internal val PINNED_TOOL_NAMES = listOf("chromium", "agent-browser")

    /**
     * 可选工具（桌面自动化）：hermes 的 lockfile 里标着 `optional`，不用时可以清掉
     * ——**但不会自己回来**，要用时得在终端跑 `hermes pm install`（用户 2026-10-11 实测）。
     */
    internal val OPTIONAL_TOOL_NAMES = listOf("cua-driver")

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

    internal enum class ToolKind { REQUIRED, PINNED, OPTIONAL, CORE, OTHER }

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
            hit(REQUIRED_TOOL_NAMES) -> ToolKind.REQUIRED
            hit(PINNED_TOOL_NAMES) -> ToolKind.PINNED
            hit(OPTIONAL_TOOL_NAMES) -> ToolKind.OPTIONAL
            hit(CORE_TOOL_NAMES) -> ToolKind.CORE
            else -> ToolKind.OTHER
        }
    }

    private fun note(kind: ToolKind): String = when (kind) {
        ToolKind.REQUIRED -> "hermes 必需：缺了它启动自检会报错（系统层那份它不认）：不动"
        ToolKind.PINNED -> "hermes 的浏览器工具（agent-browser + chromium 一套）：用户点名保留，不提供清理"
        ToolKind.OPTIONAL -> "可选（桌面自动化）：删了不会自己回来，要用时在终端跑 hermes pm install"
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

    /**
     * hermes 自检：读它自己的 `tools/facts.json`，把**注册了但目录已经不在**的包列出来。
     *
     * 这正是用户 2026-10-11 撞见的那一幕：清了工具目录，hermes 启动就报
     * `install out of sync (ffmpeg: not installed or outdated)`，而面板什么都不说。
     * 「包名 → 目录名」的对应关系只写在 facts.json 里（`ffmpeg` → `ffmpeg-9.0.1-linux-arm64`），
     * 所以这里照它的 `entry` 判，不猜名字。
     */
    internal fun missingRegisteredPackages(factsJson: File): List<Pair<String, String>> {
        if (!factsJson.isFile) return emptyList()
        val store = factsJson.parentFile ?: return emptyList()
        val pkgs = runCatching {
            JSONObject(factsJson.readText()).optJSONObject("packages")
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (name in pkgs.keys()) {
            val p = pkgs.optJSONObject(name) ?: continue
            if (p.optBoolean("internal", false)) continue
            val entry = p.optString("entry").takeIf { it.isNotEmpty() } ?: continue
            if (!File(store, entry).exists()) out += name to entry
        }
        return out.sortedBy { it.first }
    }

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
                "清理可选工具: 删除 ${res.first} 项（浏览器/桌面自动化），释放 " +
                    "${CacheCleaner.bytesToMb(res.second)}MB（要用时得在终端跑 hermes pm install）"
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
     * 清理公共区那份**安装脚本缓存**（连同宿主侧指纹记录与 guest 私有副本），返回 `项数 to 字节数`。
     *
     * 为什么要有这个入口（用户 2026-10-11）：「重试免下载」让一份脚本被**每次重装原样复用**
     * （用户当初指定，见 AgentInstaller）。可一旦这份缓存本身有问题——上游改了脚本格式、
     * 代理塞了拦截页、写到一半被杀——用户看到的就是**永远同一个失败**，而且没有任何自救手段
     * （公共区 App 卸载也清不掉）。清掉它，下次安装就会重新下载一份干净的。
     *
     * 账本 `installed.json` **不动**：那是"装过什么"（重装 App 后一键恢复的依据），不是缓存。
     */
    fun cleanAgentScriptCache(ctx: Context): Pair<Int, Long> {
        var count = 0
        var freed = 0L
        fun wipeFiles(dir: File?) {
            val files = runCatching { dir?.listFiles { f -> f.isFile } }.getOrNull() ?: return
            files.forEach { f ->
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    count++
                    freed += len
                }
            }
        }
        runCatching { wipeFiles(Store.agentScriptsDir(ctx)) }
        // guest 私有执行副本（/root/.zhengdao/scripts ← filesDir/home/.zhengdao/scripts）
        runCatching { wipeFiles(Store.hostPrivateScriptsDir(ctx)) }
        // 宿主侧指纹记录目录：filesDir/agents/script-sha（路径由 Store 自己算，这里不重复拼）
        runCatching { wipeFiles(Store.scriptRecordFile(ctx, "hermes").parentFile) }
        if (count > 0) {
            RunLog.log(
                "清理安装脚本缓存: 删除 $count 个文件，释放 ${CacheCleaner.bytesToMb(freed)}MB" +
                    "（下次安装会重新下载）"
            )
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

        // B. hermes 工具（可选工具可清，ffmpeg 与核心运行时不动）
        out += toolItems(ctx)

        // B2. hermes 自检缺口：facts.json 里注册了、目录却不在了 ⇒ 它启动就会报
        // `install out of sync`。面板必须把这件事说出来（用户 2026-10-11 的困惑源头）。
        runCatching {
            missingRegisteredPackages(File(toolsDir(HermesEnv.hermesHome(ctx)), "facts.json"))
        }.getOrDefault(emptyList()).forEach { (name, entry) ->
            out += Item(
                group = "hermes 自检",
                // 行里就把补救办法写上：用户看到"目录不在"时最想知道的就是怎么办。
                label = "$name（$entry 目录不在）· 跑 hermes pm install 补",
                mb = 0,
                removable = false,
                note = "在终端跑 hermes pm install 补回来",
            )
        }

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

        // C. 公共区：安装脚本缓存可清（下次安装重下），账本 / 日志 / 资料库只展示、永不清
        runCatching { Store.agentsDir(ctx) }.getOrNull()?.let { agents ->
            val scriptFiles = runCatching {
                Store.agentScriptsDir(ctx).listFiles { f -> f.isFile }
            }.getOrNull() ?: emptyArray()
            if (scriptFiles.isNotEmpty()) {
                out += Item(
                    group = "公共区安装脚本",
                    label = "Agent 安装脚本缓存（${scriptFiles.size} 个）",
                    mb = CacheCleaner.bytesToMb(scriptFiles.sumOf { it.length() }),
                    removable = true,
                    note = "删了下次安装会重新下载（脚本本身出问题时清它）",
                )
            }
            val ledger = File(agents, Store.LEDGER_NAME)
            if (ledger.isFile) {
                out += Item(
                    "公共区工作区", "Agent 账本 ${Store.LEDGER_NAME}",
                    CacheCleaner.bytesToMb(ledger.length()), removable = false,
                    note = "用户数据：不清",
                )
            }
            listOf("logs" to "运行日志", "资料库" to "资料库").forEach { (n, label) ->
                val d = File(Store.root(ctx), n)
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
