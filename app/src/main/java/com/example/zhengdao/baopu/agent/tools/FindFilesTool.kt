// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：工具补强第二刀（找文件）。四条硬纪律：
// 1. 权限 READ（FREE）——只读不改；
// 2. 按**文件名子串**找（不是按内容：内容搜有 search_in_files），大小写不敏感；
// 3. 默认深度 6、命中 50、扫描 20000 个文件，任何一项触顶都**明说**（静默截断 = 骗模型）；
// 4. 跳过 `.` 开头的内部目录与文件（`.baopu-bak` / `.baopu-spill` 是私有噪声）。
// 结果回灌走 GuardPipeline 第 ⑤ 段的 DataFence.wrap（与 read_file 同一出口）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.rootfs.PathGuard
import java.io.File

/**
 * `find_files`：在工作区某个目录里按**文件名**找文件（工具补强第二刀）。
 *
 * 为什么有它：手机资料库里两千本书、2 GB，`list_dir` 一屏放不下；
 * 「名字里有『斗罗』的那几本在哪」是最高频的一次查询，靠列目录再翻是把上下文烧掉。
 *
 * 为什么不做通配符/正则：模型用子串最不容易写错，而子串已经覆盖「按书名找」这个真实需求；
 * 复杂匹配一旦写错，它会把「没找到」误当成「手机里没有这本书」。
 */
internal object FindFilesTool {

    const val ARG_PATTERN = "pattern"
    const val ARG_PATH = "path"
    const val ARG_LIMIT = "limit"
    const val ARG_MAX_DEPTH = "max_depth"

    /** 默认命中上限；触顶会明说「还有更多」。 */
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200

    /** 默认递归深度（传入目录自身算第 1 层）与硬上限。 */
    const val DEFAULT_DEPTH = 6
    const val MAX_DEPTH = 12

    /** 扫描文件数上限（防深搜把一次工具调用拖死在 5 秒超时上）。 */
    const val MAX_SCANNED = 20_000

    /** 构造注册用 Spec（workspaceRoot 注入：越界自检 + 相对路径渲染都要它）。 */
    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "find_files",
        description = "在工作区某个目录里按文件名找文件（子串匹配，大小写不敏感），" +
            "回「相对路径 + 大小」。默认深度 $DEFAULT_DEPTH 层、最多 $DEFAULT_LIMIT 个命中，" +
            "触顶会明说。找文件内容用 search_in_files。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATTERN to jsonObjectOf(
                    "type" to "string",
                    "description" to "文件名里要包含的片段（如 斗罗；大小写不敏感）",
                ),
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "相对工作区根的目录（省略 = 工作区根）",
                ),
                ARG_LIMIT to jsonObjectOf(
                    "type" to "integer",
                    "description" to "最多回几个命中（默认 $DEFAULT_LIMIT，最大 $MAX_LIMIT）",
                    "minimum" to 1,
                    "maximum" to MAX_LIMIT,
                ),
                ARG_MAX_DEPTH to jsonObjectOf(
                    "type" to "integer",
                    "description" to "往下找几层（默认 $DEFAULT_DEPTH，最大 $MAX_DEPTH）",
                    "minimum" to 1,
                    "maximum" to MAX_DEPTH,
                ),
            ),
            "required" to jsonArrayOf(ARG_PATTERN),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true, // 纯文件读：无共享可变状态，可与别的读并行
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }

    /**
     * 找文件（**纯函数**：root/dir/pattern 已定、无 Android 依赖，JVM 直测）。
     *
     * @param root 工作区根（越界自检的基准）
     * @param dir 从哪个目录开始找（调用方保证已在工作区内）
     * @param pattern 文件名子串（非空）
     */
    fun find(
        root: File,
        dir: File,
        pattern: String,
        limit: Int = DEFAULT_LIMIT,
        maxDepth: Int = DEFAULT_DEPTH,
    ): ToolOutcome {
        val needle = pattern.trim()
        if (needle.isEmpty()) return ToolOutcome.Failed("参数「$ARG_PATTERN」为空（要给一个文件名片段）")
        if (!dir.exists()) {
            return ToolOutcome.Failed("不存在：${dir.name}（工作区里没有这个目录；先用 list_dir 看结构）")
        }
        if (!dir.isDirectory) {
            return ToolOutcome.Failed("不是一个目录：${dir.name}（find_files 从目录开始找；读单个文件用 read_file）")
        }
        if (!PathGuard.isInside(root, dir)) {
            return ToolOutcome.Rejected("路径越界被拒：${dir.name} 不在工作区（${root.absolutePath}）内。")
        }
        val cap = limit.coerceIn(1, MAX_LIMIT)
        val depthCap = maxDepth.coerceIn(1, MAX_DEPTH)
        val lowered = needle.lowercase()
        val hits = mutableListOf<File>()
        var scanned = 0
        var truncatedByLimit = false
        var truncatedByScan = false
        data class Item(val file: File, val level: Int)
        val stack = ArrayDeque<Item>()
        stack.addLast(Item(dir, 1))
        while (stack.isNotEmpty()) {
            val (current, level) = stack.removeLast()
            val children = runCatching { current.listFiles() }.getOrNull() ?: continue
            // 同层「目录在前、名称序」：输出稳定，模型两次调用看到同样的顺序
            children.sortedWith(compareBy({ !it.isDirectory }, { it.name })).forEach { child ->
                if (child.name.startsWith(".")) return@forEach // 内部目录/隐藏文件不找
                if (child.isDirectory) {
                    if (level < depthCap) stack.addLast(Item(child, level + 1))
                    return@forEach
                }
                scanned++
                if (scanned > MAX_SCANNED) {
                    truncatedByScan = true
                    return@forEach
                }
                if (child.name.lowercase().contains(lowered)) {
                    hits += child
                    if (hits.size >= cap) truncatedByLimit = true
                }
            }
            if (truncatedByLimit || truncatedByScan) break
        }
        if (hits.isEmpty()) {
            return ToolOutcome.Ok(
                "没找到文件名含「$needle」的文件（扫了 $scanned 个文件，深度上限 $depthCap 层）。" +
                    "换一个更短的片段再试，或用 list_dir 看目录结构。",
            )
        }
        val sortedHits = hits.sortedBy { relPath(root, it) }
        val body = buildString {
            append("在 ")
            append(if (dir == root) "工作区" else relPath(root, dir))
            append(" 里找到 ${sortedHits.size} 个文件名含「$needle」的文件（扫了 $scanned 个，深度上限 $depthCap 层）：\n")
            sortedHits.forEach { f ->
                append(relPath(root, f)).append('\t').append(f.length()).append(" B\n")
            }
            if (truncatedByLimit) append("（已到 $cap 个上限，还有更多没列；把 limit 调大或换更具体的片段）")
            if (truncatedByScan) append("（扫到 $MAX_SCANNED 个文件就停了；把 path 指到更窄的目录再找）")
        }
        return ToolOutcome.Ok(body.toString().trimEnd())
    }

    /** executor：path 可省略（省略 = 工作区根）。 */
    private fun run(args: org.json.JSONObject, root: File): ToolOutcome {
        val raw = args.optString(ARG_PATH).trim()
        val dir = if (raw.isEmpty()) root else File(raw)
        return find(
            root = root,
            dir = dir,
            pattern = args.optString(ARG_PATTERN),
            limit = args.optInt(ARG_LIMIT, DEFAULT_LIMIT),
            maxDepth = args.optInt(ARG_MAX_DEPTH, DEFAULT_DEPTH),
        )
    }

    /** 相对路径渲染（统一 `/`，与 read_file 的 path 口径一致）。 */
    private fun relPath(root: File, f: File): String = runCatching {
        f.absoluteFile.toRelativeString(root.absoluteFile).replace('\\', '/')
    }.getOrDefault(f.name)
}
