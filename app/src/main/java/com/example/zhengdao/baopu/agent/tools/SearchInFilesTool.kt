// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴线刀 2 追加包（关键词搜索工具）。四条硬纪律：
// 1. 权限 READ（FREE）——只读不改；
// 2. 只搜工作区内（目录参数名用 "path" ⇒ GuardPipeline 第 ② 段免费守卫；
//    纯函数层再自检 PathGuard.isInside，双保险）；
// 3. 只回「相对路径:行号:该行内容±1 行」，单次最多 40 条命中；
// 4. 跳过 >8 MB 与二进制文件，并写明「已扫描 N 个文件 / 跳过 M 个」。
// 结果回灌走 GuardPipeline 第 ⑤ 段的 DataFence.wrap（与 read_file 同一个围栏出口，
// 本文件不自造围栏）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.rootfs.PathGuard
import java.io.File
import java.nio.file.Files

/**
 * `search_in_files`：在工作区某个目录里搜一个词（刀 2 追加包）。
 *
 * 为什么 ±1 行而不是只回命中行：单行命中常常看不出上下文（尤其是小说里
 * 「他」「那道」这类词），±1 行让模型能判断这行在讲什么，减少瞎猜重搜。
 *
 * 为什么不递归无上限：深搜 + 无上限会把整个工作区读进上下文；深度默认 3，
 * 命中 40 条，触顶都明说（与 list_dir 同款原则：静默截断 = 骗模型）。
 */
internal object SearchInFilesTool {

    const val ARG_PATH = "path"
    const val ARG_QUERY = "query"
    const val ARG_CASE_SENSITIVE = "case_sensitive"

    /** 单文件大小上限（超过直接跳过，不硬读）。 */
    const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** 单次命中上限（不含 ±1 行的上下文本行；含上下文的输出行数会更多，仍受 spill 兜底）。 */
    const val MAX_HITS = 40

    /** 默认递归深度（目录自身算第 1 层）。 */
    const val DEFAULT_DEPTH = 3

    /** 编码嗅探字节数（与 FileInfoTool 同口径；二进制判定只看这一段）。 */
    const val SNIFF_BYTES = 8 * 1024

    /** 构造注册用 Spec（workspaceRoot 注入：越界自检 + 相对路径渲染都要它）。 */
    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "search_in_files",
        description = "在工作区某个目录里搜一个词，回「相对路径:行号:命中行±1 行」。" +
            "单次最多 $MAX_HITS 条命中；大于 8 MB 与二进制文件会跳过并计数。" +
            "默认大小写不敏感。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "相对工作区根的目录路径（如 资料库/原始）",
                ),
                ARG_QUERY to jsonObjectOf(
                    "type" to "string",
                    "description" to "要搜的词（大小写不敏感，除非 case_sensitive=true）",
                ),
                ARG_CASE_SENSITIVE to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "是否区分大小写（默认 false）",
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH, ARG_QUERY),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true, // 纯文件读：无共享可变状态，可与别的读并行
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }

    /** 扫描统计（「已扫描 N / 跳过 M」明示——静默跳过等于骗模型「就这些了」）。 */
    data class ScanStats(
        val scanned: Int = 0,
        val skippedBig: Int = 0,
        val skippedBinary: Int = 0,
        val skippedError: Int = 0,
    ) {
        val skippedTotal: Int get() = skippedBig + skippedBinary + skippedError
    }

    /**
     * 搜索（**纯函数**：root/dir/query 已定、无 Android 依赖，JVM 直测）。
     *
     * @param root 工作区根（越界自检 + 相对路径渲染的基准）
     * @param dir  要搜的目录（绝对路径；调用方保证已在工作区内）
     * @param query 关键词（非空）
     * @param caseSensitive 是否区分大小写
     * @return [ToolOutcome.Ok] = 命中清单 + 扫描统计；目录不存在/不是目录/越界分别报错
     */
    fun search(
        root: File,
        dir: File,
        query: String,
        caseSensitive: Boolean = false,
    ): ToolOutcome {
        val q = query.trim()
        if (q.isEmpty()) return ToolOutcome.Failed("参数「$ARG_QUERY」为空（要一个非空关键词）")
        if (!dir.exists()) {
            return ToolOutcome.Failed(
                "不存在：${dir.name}（工作区里没有这个目录；先用 list_dir 看结构）",
            )
        }
        if (!dir.isDirectory) {
            return ToolOutcome.Failed("不是一个目录：${dir.name}（search_in_files 只搜目录；读文件用 read_file）")
        }
        if (!PathGuard.isInside(root, dir)) {
            return ToolOutcome.Rejected(
                "路径越界被拒：${dir.name} 不在工作区（${root.absolutePath}）内。",
            )
        }
        val needle = if (caseSensitive) q else q.lowercase()
        var scanned = 0
        var skippedBig = 0
        var skippedBinary = 0
        var skippedError = 0
        val hits = mutableListOf<String>()
        var hitCount = 0
        var truncated = false
        // 栈式 DFS；同层「目录在前、名称序」——输出稳定（与 list_dir 同口径）
        data class Item(val file: File, val level: Int)
        val stack = ArrayDeque<Item>()
        stack.addLast(Item(dir, 1))
        while (stack.isNotEmpty() && hitCount < MAX_HITS) {
            val (current, level) = stack.removeLast()
            val children = runCatching { current.listFiles() }.getOrNull()
            if (children == null) {
                skippedError++
                continue
            }
            val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            for (f in sorted) {
                if (hitCount >= MAX_HITS) {
                    truncated = true
                    break
                }
                if (isSymlink(f)) continue // 软链不跟随（跟出去就是工作区外的内容）
                if (f.name.startsWith(".")) continue // 内部目录/文件不搜（.baopu-bak/.baopu-spill 是私有噪声）
                if (f.isDirectory) {
                    if (level < DEFAULT_DEPTH) stack.addLast(Item(f, level + 1))
                    continue
                }
                if (f.length() > MAX_FILE_BYTES) {
                    skippedBig++
                    continue
                }
                val head = runCatching { readHead(f, SNIFF_BYTES) }.getOrNull()
                if (head == null) {
                    skippedError++
                    continue
                }
                if (looksBinary(head)) {
                    skippedBinary++
                    continue
                }
                // 解码统一走 [TextDecode]（2026-10-17）：以前这里硬读 UTF-8，GBK 老书里的
                // 关键词**静默搜不到**（不报错、只是假装没有），比读失败更坏。
                val lines = runCatching {
                    val bytes = f.readBytes()
                    TextDecode.decode(f, bytes).text?.let { TextDecode.splitLines(it) }
                }.getOrNull()
                if (lines == null) {
                    skippedError++
                    continue
                }
                scanned++
                val rel = relPath(root, f)
                for (i in lines.indices) {
                    if (hitCount >= MAX_HITS) {
                        truncated = true
                        break
                    }
                    val line = lines[i]
                    val hay = if (caseSensitive) line else line.lowercase()
                    if (!hay.contains(needle)) continue
                    hitCount++
                    // 命中块：上一行 + 命中行 + 下一行（行号从 1 起显示）
                    if (i > 0) hits += "$rel:${i}:${lines[i - 1]}"
                    hits += "$rel:${i + 1}:$line"
                    if (i < lines.size - 1) hits += "$rel:${i + 2}:${lines[i + 1]}"
                }
            }
            if (truncated) break
        }
        val stats = ScanStats(scanned, skippedBig, skippedBinary, skippedError)
        val body = buildString {
            if (hitCount == 0) {
                append("（没有命中「$q」）")
            } else {
                append(hits.joinToString("\n"))
            }
            append("\n（命中 $hitCount 条；已扫描 ${stats.scanned} 个文件 / 跳过 ${stats.skippedTotal} 个")
            if (stats.skippedTotal > 0) {
                append("（过大 ${stats.skippedBig} / 二进制 ${stats.skippedBinary}")
                if (stats.skippedError > 0) append(" / 读失败 ${stats.skippedError}")
                append("）")
            }
            append("；深度上限 $DEFAULT_DEPTH 层，软链与点开头文件不搜）")
            if (truncated) {
                append("；已触达命中上限 $MAX_HITS 条，把 path 指到更深的目录或换个更具体的词")
            }
            append("）")
        }
        return ToolOutcome.Ok(body.toString())
    }

    /** executor：arguments 里的 path 已是守卫过的绝对路径。 */
    private fun run(args: org.json.JSONObject, root: File): ToolOutcome {
        val path = args.optString(ARG_PATH).trim()
        if (path.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        val query = args.optString(ARG_QUERY)
        return search(
            root = root,
            dir = File(path),
            query = query,
            caseSensitive = args.optBoolean(ARG_CASE_SENSITIVE, false),
        )
    }

    /** 读文件头 [n] 字节（嗅探用；不整文件读入——追加包判据④）。 */
    private fun readHead(f: File, n: Int): ByteArray = f.inputStream().use { input ->
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        if (read == n) buf else buf.copyOf(read)
    }

    /** 二进制判定：头段里出现 NUL 即判二进制（与 file(1) 同口径）。 */
    internal fun looksBinary(head: ByteArray): Boolean = head.any { it == 0.toByte() }

    /** 相对路径渲染（统一 `/` 分隔：Windows 上 relativize 给的是 `\`，输出要跨平台稳定）。 */
    private fun relPath(root: File, f: File): String =
        runCatching { root.toPath().relativize(f.toPath()).toString().replace('\\', '/') }
            .getOrElse { f.name }

    /** 软链判定（拿不到时保守判 false）。 */
    private fun isSymlink(f: File): Boolean =
        runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
}
