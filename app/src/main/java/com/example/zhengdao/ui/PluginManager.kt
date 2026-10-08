// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Context
import com.example.zhengdao.oc.OcManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * OpenCode 插件管理（2026-10-07 新增）。
 *
 * **背景**：2026-10-06 预置的第三方记忆插件 `opencode-mem` 从接入起**从未产出过一条记忆**
 * （它要求 opencodeProvider + opencodeModel 同时配置才启用自动捕获），却带来 656 MB 本地
 * 向量模型 + 1.9 GB 依赖。用户 2026-10-07 拍板摘除（代码侧见 `LegacyMemPlugin`）。
 * 真正的教训是**用户看不见也关不掉**——插件被硬编码写进 `opencode.json`，App 里没有任何入口。
 *
 * 本文件把插件做成**显性化 + 可开关**：
 *  - 已启用的插件从 `opencode.json` 的 `plugin` 数组实时读出；
 *  - Bun 从 npm 装下来的插件包可查看体积、可一键清理；
 *  - 推荐目录内置"轻量·免模型"方案，避免再次引入本地向量模型（用户明确不要，会发热）。
 *
 * 只读写 opencode 自身的配置文件与缓存目录，**不碰** PRoot 启动 / SSE / REST 任何既有链路。
 *
 * ⚠️ **作用域（用户 2026-10-07 裁决 ①）**：插件是 OpenCode 的能力，本 App 里只有
 * **太极**跑 OpenCode（宿主 bionic 版，XDG 隔离目录）。
 * （裁决 ② 已于 v1.2 反转：终端里的 npm 版 opencode **已卸载**，不再存在"两份 opencode"。）
 *
 * ⚠️ **v1.2 阶段 2.0 路径修复**：本类此前指向 `files/home/.zhengdao/taiji/config/opencode/`
 * （终端 taiji 脚本的 XDG 目录），与太极 serve 真正读取的 `files/oc/xdg/config/opencode/`
 * **不是同一个文件** ⇒ 插件页的开关一直写在没人读的地方。现统一取 [OcManager.configFile]，
 * 与权限策略、性能调优共用同一个 merge 写入口。
 */
object PluginManager {

    // ── 路径 ────────────────────────────────────────────────────────────────

    /** 太极实例（OpenCode，XDG 四目录隔离）的 opencode.json —— 与 serve 读取的是同一个。 */
    fun taijiConfig(ctx: Context): File = OcManager.configFile(ctx)

    /** 太极实例的插件包缓存（XDG_CACHE_HOME 被隔离到 files/oc/xdg/cache）。 */
    fun taijiPackagesDir(ctx: Context): File =
        File(OcManager.xdgDir(ctx, "cache"), "opencode/packages")

    /** 缓存目录（扫描 / 清理的作用对象）。 */
    fun allPackagesDirs(ctx: Context): List<File> =
        listOf(taijiPackagesDir(ctx))

    // ── 数据模型 ─────────────────────────────────────────────────────────────

    /** 一个插件在 UI 上的呈现。 */
    data class Plugin(
        /** 写进 `plugin` 数组的原始标识，如 `@chncaesar/opencode-plugin-memory`。 */
        val spec: String,
        /** 是否已在配置中启用。 */
        val enabled: Boolean,
        /** 已缓存的版本（读不到则为 null＝尚未下载，首次启动时才装）。 */
        val cachedVersion: String?,
        /** 已缓存占用（MB，未缓存为 0）。 */
        val cachedMb: Long,
    )

    /** 内置推荐目录条目。 */
    data class Recommend(
        val spec: String,
        val title: String,
        val description: String,
    )

    /**
     * 内置推荐（当前只有一项，且刻意选**零依赖、零向量、不跑本地模型**的方案）。
     *
     * `@chncaesar/opencode-plugin-memory`（MIT）：把记忆存为工作区 `.opencode/memory/MEMORY.md`
     * 纯文本，靠 LLM 自己调 4 个工具（add / update / delete / read）读写，
     * 没有后台管线、没有 embedding、不需要任何 API Key 或云端服务——
     * 因此**不给手机带来额外发热**，也不会像 opencode-mem 那样拖进几百 MB 依赖。
     */
    val RECOMMENDED: List<Recommend> = listOf(
        Recommend(
            spec = "@chncaesar/opencode-plugin-memory",
            title = "会话记忆（轻量 · 免模型）",
            description = "让 Agent 跨会话记住偏好与经验。纯 Markdown 存储、零依赖、零向量、" +
                "不跑本地模型，因此不会额外发热；也不需要任何 API Key。记忆文件可随时人工查看和编辑。",
        ),
    )

    fun recommendOf(spec: String): Recommend? = RECOMMENDED.firstOrNull { it.spec == spec }

    /**
     * **首次安装**时默认启用的推荐插件。
     *
     * 只预置一次（由 `ProotLauncher` 用 prefs 标记控制）——用户在插件页关掉后
     * 不会被下次启动又加回来。"看得见 + 关得掉"是这一版的硬要求。
     */
    val DEFAULT_ON: List<String> = listOf("@chncaesar/opencode-plugin-memory")

    // ── 配置读写 ─────────────────────────────────────────────────────────────

    /** 读某个 opencode.json 里 `plugin` 数组的原始标识列表（缺失或损坏返回空表）。 */
    fun readSpecs(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val obj = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("plugin") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            specOf(arr.opt(i))?.let { out.add(it) }
        }
        return out
    }

    /**
     * 从 `plugin` 数组的一项里取出标识。
     * opencode 支持两种写法：`"pkg"` 与 `["pkg", { 选项 }]`——后者取第 0 个元素。
     */
    fun specOf(item: Any?): String? = when (item) {
        is String -> item.trim().ifEmpty { null }
        is JSONArray -> if (item.length() > 0) specOf(item.opt(0)) else null
        else -> null
    }

    /** 是否已启用。 */
    fun isEnabled(file: File, spec: String): Boolean = readSpecs(file).any { samePackage(it, spec) }

    /**
     * 启用 / 停用一个插件（**只作用于太极的 OpenCode 实例**，见类注释的作用域裁决）。
     *
     * 写入时保留数组里其它插件的**原始形态**（字符串或 `[spec, opts]` 皆原样保留），
     * 只在末尾追加新项，或移除匹配项；数组清空时连 `plugin` 键一起删（避免留下空数组）。
     *
     * @return 实际发生变更的文件数（0 表示已是目标状态，或太极配置尚未生成）。
     */
    fun setEnabled(ctx: Context, spec: String, on: Boolean): Int =
        if (applyToFile(taijiConfig(ctx), spec, on)) 1 else 0

    /** 对单个文件执行启用/停用，返回是否真的改了文件。 */
    internal fun applyToFile(file: File, spec: String, on: Boolean): Boolean {
        if (!file.isFile) {
            // 目标实例还不存在（从未启动过）：不凭空空造配置文件，等它自己生成。
            return false
        }
        // 已是目标状态：直接返回，**不重写文件**。
        // 否则"停用一个本来就没启用的插件"也会因为 toString(2) 重新格式化而落盘一次，
        // 把用户的键顺序与写法无谓地改一遍（曾因此被单测抓出）。
        if (isEnabled(file, spec) == on) return false
        val obj = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return false
        val arr = obj.optJSONArray("plugin") ?: JSONArray()
        val kept = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            val s = specOf(item)
            if (s != null && samePackage(s, spec)) continue // 命中：先摘掉，再按需追加
            kept.add(item)
        }
        val result = JSONArray()
        kept.forEach { result.put(it) }
        if (on) result.put(spec)

        if (result.length() == 0) {
            obj.remove("plugin")
        } else {
            obj.put("plugin", result)
        }
        file.parentFile?.mkdirs()
        file.writeText(obj.toString(2) + "\n")
        return true
    }

    // ── 缓存扫描 / 清理 ──────────────────────────────────────────────────────

    /**
     * 扫两处缓存目录，返回 (包的全名, 占用MB)。
     *
     * opencode 的缓存布局有**两种**，实测确认（真机 `@chncaesar/opencode-plugin-memory@latest`）：
     *  - 非 scope 包：`<cache>/opencode-mem@latest/package.json`
     *  - scope 包：  `<cache>/@chncaesar/opencode-plugin-memory@latest/package.json`（多一层）
     * 只扫一层会把 scope 目录（`@chncaesar`，本身不是包）当包报出来，故按"含 package.json 的目录"识别包根。
     */
    fun scanCaches(ctx: Context): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        for (root in allPackagesDirs(ctx)) {
            for (pkg in findPackageRoots(root, MAX_SCAN_DEPTH)) {
                out.add(fullNameOf(pkg) to SystemInfoProvider.dirSizeMb(pkg))
            }
        }
        return out
    }

    /** 该 spec 已缓存的版本（找不到返回 null）。 */
    fun cachedVersion(ctx: Context, spec: String): String? {
        for (root in allPackagesDirs(ctx)) {
            for (pkg in findPackageRoots(root, MAX_SCAN_DEPTH)) {
                if (!samePackage(fullNameOf(pkg), spec)) continue
                versionFrom(pkg, spec)?.let { return it }
            }
        }
        return null
    }

    /**
     * 从某个包根解析该包版本（纯函数，可单测）。
     *
     * ⚠️ 真机实测的坑：opencode 放在安装目录里的 `package.json` **不是包自身的描述**，
     * 而是一份**依赖清单**——没有 `version` 字段，版本写在 dependencies 里：
     * ```
     * { "dependencies": { "@chncaesar/opencode-plugin-memory": "0.1.1" } }
     * ```
     * 最初只认 `version` 字段，于是明明已下载 52 MB 却显示"尚未下载"。
     */
    internal fun versionFrom(pkgRoot: File, spec: String): String? {
        val obj = runCatching { JSONObject(File(pkgRoot, "package.json").readText()) }.getOrNull()
        obj?.optJSONObject("dependencies")?.let { deps ->
            val v = deps.optString(fullNameOf(pkgRoot)).ifEmpty { deps.optString(parseSpec(spec).first) }
            if (v.isNotEmpty() && v != "latest") return v
        }
        // 缓存目录直接就是包根的情形：包自身的 package.json
        obj?.optString("version")?.takeIf { it.isNotEmpty() }?.let { return it }
        // 退路：目录名里的版本段（`pkg@1.2.3` 形态）
        return parseSpec(pkgRoot.name).second?.takeIf { it != "latest" }
    }

    /** 清理两处插件包缓存，返回回收的 MB。 */
    fun clearCaches(ctx: Context): Long {
        var freed = 0L
        for (root in allPackagesDirs(ctx)) {
            for (pkg in findPackageRoots(root, MAX_SCAN_DEPTH)) {
                val mb = SystemInfoProvider.dirSizeMb(pkg)
                if (pkg.deleteRecursively()) freed += mb
            }
            // scope 目录（如 @chncaesar）里的包删光后会剩个空壳，一并收走
            root.listFiles()?.forEach { d ->
                if (d.isDirectory && d.listFiles()?.isEmpty() == true) d.delete()
            }
        }
        return freed
    }

    /** 扫描深度：`<cache>/<pkg>` 与 `<cache>/<@scope>/<pkg>` 两种布局都要够到。 */
    private const val MAX_SCAN_DEPTH = 2

    // ── 纯函数（可单测）──────────────────────────────────────────────────────

    /**
     * 在 root 下（递归深度 ≤ maxDepth）找出所有**包根**：自身含 `package.json` 的目录。
     * 跳过 `node_modules`——那是依赖，不是"插件包"，否则会把一堆传递依赖都列成插件。
     */
    internal fun findPackageRoots(root: File, maxDepth: Int): List<File> {
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth) return
            val kids = dir.listFiles() ?: return
            for (d in kids) {
                if (!d.isDirectory || d.name == "node_modules") continue
                if (File(d, "package.json").isFile) out.add(d) else walk(d, depth + 1)
            }
        }
        walk(root, 1)
        return out
    }

    /**
     * 包根对应的**完整包名**（去掉版本段、补回 scope）。
     *
     * `packages/@chncaesar/opencode-plugin-memory@latest` → `@chncaesar/opencode-plugin-memory`
     * `packages/opencode-mem@latest` → `opencode-mem`
     */
    internal fun fullNameOf(pkgRoot: File): String {
        val base = parseSpec(pkgRoot.name).first
        val parent = pkgRoot.parentFile?.name
        return if (parent != null && parent.startsWith("@")) "$parent/$base" else base
    }

    /**
     * 解析包标识为 (包名, 版本段?)。
     *
     * 兼容 npm 的两类写法，且**不能把 scope 的 `@` 误当版本分隔符**：
     *  - `@scope/pkg`          → ("@scope/pkg", null)
     *  - `@scope/pkg@1.2.3`    → ("@scope/pkg", "1.2.3")
     *  - `pkg`                 → ("pkg", null)
     *  - `pkg@latest`          → ("pkg", "latest")
     */
    fun parseSpec(spec: String): Pair<String, String?> {
        val s = spec.trim()
        if (s.isEmpty()) return "" to null
        val from = if (s.startsWith("@")) 1 else 0
        val at = s.indexOf('@', from)
        return if (at < 0) s to null else s.substring(0, at) to s.substring(at + 1).ifEmpty { null }
    }

    /** 两个标识是否指同一个包（忽略版本段差异：`pkg` 与 `pkg@latest` 视为同一个）。 */
    fun samePackage(a: String, b: String): Boolean =
        parseSpec(a).first == parseSpec(b).first && parseSpec(a).first.isNotEmpty()

    /** 是否已知的历史遗留插件（不推荐、且在概览里显式提示）。 */
    fun isLegacy(spec: String): Boolean =
        com.example.zhengdao.terminal.isLegacyMemPlugin(parseSpec(spec).first)

    /**
     * 校验用户**手输**的插件标识（2026-10-08，配合「添加插件」入口）。
     *
     * 为什么需要：OpenCode 没有插件市场，插件的标识就是 **npm 包名**——用户从插件主页复制
     * 过来即可。既不能不做校验（空串、带空格、粘贴进整段说明文字都会被原样写进
     * `opencode.json`，表现为"启用了一个永远不会生效的插件"），也不能做太严的校验
     * （npm 命名规则比我们能可靠判定的复杂，过严会把合法包名挡在外面）。
     *
     * 因此只拦**明确不可能合法**的形态，其余放行：
     *  - 空（或只有空白）；
     *  - 含任何空白字符（复制粘贴夹带换行/空格是最常见的一种）；
     *  - 以 `.` 或 `/` 开头（前者是相对路径写法，后者是 URL 尾巴）；
     *  - 非 scope 包却含 `/`（`a/b` 不是包名，`@scope/name` 才是）；
     *  - scope 包没有恰好一个 `/`（`@scope`、`@scope/a/b` 都不合法）。
     *
     * @return 规范化后的标识（已 trim）；不合法返回 null。
     */
    fun normalizeSpecInput(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        // ⚠️ 空白检查必须针对 trim **之后**的 s（而不是 raw）：这样复制粘贴夹带的首尾空白
        // （KDoc 里点名的 `"my-plugin\n"` 这类）已被裁掉、照常放行，而 s 里剩下的任何空白
        // 都必然是**内部**空白 —— 那才是"整段说明文字被粘进来"的形态，必须拦掉。
        if (s.any { it.isWhitespace() }) return null
        if (s.startsWith(".") || s.startsWith("/")) return null
        val name = parseSpec(s).first
        if (name.isEmpty()) return null
        val slashes = name.count { it == '/' }
        return when {
            name.startsWith("@") && slashes == 1 -> s
            !name.startsWith("@") && slashes == 0 -> s
            else -> null
        }
    }
}
