// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §10 竖切片①（list_dir/read_file 是切片①的两个读工具）
// + §5（路径守卫在 GuardPipeline 第②段，本文件只管"列目录"本身）。
// 软链不跟随的纪律与 Guard.kt 的 checkMaterial / MemoryStore 扫描一致（仓库既有口径）。
package com.example.zhengdao.baopu.agent

import java.io.File
import java.nio.file.Files

/**
 * `list_dir`：列工作区内某个目录的内容（程枢版 §10 切片①）。
 *
 * 三条硬纪律：
 * 1. **软链不跟随**：软链条目本身被跳过、不列出（与资料库扫描口径一致）——
 *    跟随就会把工作区外的内容带进上下文，那是数据外泄面；
 * 2. **深度/条数有上限**（depth 默认 1 上限 3、limit 默认 100 上限 500）：
 *    无上限列目录会把整个工作区灌进上下文，一次吃光预算；
 * 3. **触顶必须明说**：静默截断会让模型以为"就这些了"（Guard.kt MAX_SCAN 同款原则）。
 *
 * **路径不在本文件守卫**：executor 收到的 [ARG_PATH] 已经是 GuardPipeline
 * 解析 + 守卫后的**绝对路径**（相对路径的翻译在第 ② 段做，工具本身只认绝对路径）。
 */
internal object ListDirTool {

    /** 入参数名（与 [spec] 的 Schema 逐字对齐）。 */
    const val ARG_PATH = "path"
    const val ARG_DEPTH = "depth"
    const val ARG_LIMIT = "limit"

    const val DEFAULT_DEPTH = 1
    const val MAX_DEPTH = 3
    const val DEFAULT_LIMIT = 100
    const val MAX_LIMIT = 500

    /** 构造注册用 Spec。 */
    fun spec(): ToolSpec = ToolSpec(
        name = "list_dir",
        description = "列出工作区内某个目录的内容（软链不跟随；深度与条数有上限，触顶会明说）。" +
            "路径相对工作区根，如「资料库/原始」。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "相对工作区根的目录路径（如 资料库/原始）",
                ),
                ARG_DEPTH to jsonObjectOf(
                    "type" to "integer",
                    "description" to "递归深度，默认 1（仅当前层），最大 $MAX_DEPTH",
                    "minimum" to 1,
                    "maximum" to MAX_DEPTH,
                ),
                ARG_LIMIT to jsonObjectOf(
                    "type" to "integer",
                    "description" to "最多列出的条目数，默认 $DEFAULT_LIMIT，最大 $MAX_LIMIT",
                    "minimum" to 1,
                    "maximum" to MAX_LIMIT,
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args) }

    /**
     * 列举逻辑（**纯函数**：target 已定、无 Android 依赖，JVM 直测）。
     *
     * @param target 目标目录（绝对路径；调用方保证已在工作区内）
     */
    fun list(target: File, depth: Int, limit: Int): ToolOutcome {
        if (!target.isDirectory) {
            return ToolOutcome.Failed("不是一个目录：${target.name}（list_dir 只能列目录；读文件用 read_file）")
        }
        val effectiveDepth = depth.coerceIn(1, MAX_DEPTH)
        val effectiveLimit = limit.coerceIn(1, MAX_LIMIT)
        val lines = mutableListOf<String>()
        var truncated = false
        // 栈式 DFS（同层按"目录在前、名称序"排——输出稳定，缓存友好）
        data class Item(val file: File, val level: Int)
        val stack = ArrayDeque<Item>()
        stack.addLast(Item(target, 1))
        while (stack.isNotEmpty()) {
            val (dir, level) = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrNull()
                ?: return ToolOutcome.Failed("读不了这个目录（权限或 IO 错误）：${dir.name}")
            val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            for (f in sorted) {
                if (lines.size >= effectiveLimit) {
                    truncated = true
                    break
                }
                if (isSymlink(f)) continue // 软链不跟随、不列出、不计数
                val indent = "  ".repeat(level - 1)
                lines += if (f.isDirectory) {
                    "$indent${f.name}/"
                } else {
                    "$indent${f.name}（${f.length()} B）"
                }
                if (f.isDirectory && level < effectiveDepth) {
                    stack.addLast(Item(f, level + 1))
                }
            }
            if (truncated) break
        }
        if (lines.isEmpty()) {
            return ToolOutcome.Ok("（空目录）")
        }
        val body = buildString {
            append(lines.joinToString("\n"))
            if (truncated) {
                append("\n")
                append("（已触达条目上限 $effectiveLimit，下面可能还有更多内容——")
                append("把 limit 提到 $MAX_LIMIT、把 path 指到更深的目录、或用 read_file 直接读具体文件）")
            }
        }
        return ToolOutcome.Ok(body)
    }

    /** executor：arguments 里的 path 已是守卫过的绝对路径。 */
    private fun run(args: org.json.JSONObject): ToolOutcome {
        val path = args.optString(ARG_PATH).trim()
        if (path.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        return list(
            File(path),
            args.optInt(ARG_DEPTH, DEFAULT_DEPTH),
            args.optInt(ARG_LIMIT, DEFAULT_LIMIT),
        )
    }

    /** 软链判定（拿不到时保守判 false——调用方还有 isFile/isDirectory 等其他闸门）。 */
    private fun isSymlink(f: File): Boolean =
        runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
}
