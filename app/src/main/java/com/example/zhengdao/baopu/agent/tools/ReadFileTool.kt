// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §5 补录「spill 必须配套范围读」：spill 后模型要能只读
// 需要的段 ⇒ read_file 必须支持 offset/limit（刀 0 定 schema 时就留好参数）；
// §10 竖切片①（read_file 是切片①的两个读工具之一）。
package com.example.zhengdao.baopu.agent

import java.io.File

/**
 * `read_file`：读工作区内一个文件的**指定行范围**（程枢版 §10 切片①）。
 *
 * 为什么必须有 offset/limit（而不是"整个文件读回来"）：
 * 大文件 spill 后只回摘要，模型要么凭摘要瞎猜、要么把整个文件再读一遍（那 spill
 * 就白做了）⇒ 范围读是 spill 的配套（程枢版 §5 补录原文）。
 *
 * 三条纪律：
 * 1. **路径不在本文件守卫**（管线第 ② 段做过；executor 收到绝对路径）；
 * 2. **二进制/超限文件不硬读**：超过 [MAX_FILE_BYTES] 直接拒绝并说清大小
 *    （读 50 MB 进上下文等于自杀，且手机上一次读完必卡）；
 * 3. **输出带行号**：不带行号，模型没法把 offset 与"第几行"对上，范围读形同虚设。
 */
internal object ReadFileTool {

    const val ARG_PATH = "path"
    const val ARG_OFFSET = "offset"
    const val ARG_LIMIT = "limit"

    /** offset 从 0 起（与数组下标一致；0 = 第一行——描述里写明，模型不用猜）。 */
    const val DEFAULT_OFFSET = 0

    /** 默认读 200 行；上限 1000（再大就该用 offset 分段，或承认这文件不适合整读）。 */
    const val DEFAULT_LIMIT = 200
    const val MAX_LIMIT = 1000

    /** 单文件大小上限（超过直接拒绝，不硬读）。 */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 构造注册用 Spec。 */
    fun spec(): ToolSpec = ToolSpec(
        name = "read_file",
        description = "读工作区内一个文件的指定行范围（offset 从 0 起 = 第一行）。" +
            "大文件被 spill 后，用这个工具带 offset/limit 把需要的那几段读回来。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "相对工作区根的文件路径（如 资料库/原始/设定.md）",
                ),
                ARG_OFFSET to jsonObjectOf(
                    "type" to "integer",
                    "description" to "起始行号，从 0 起（0 = 第一行），默认 0",
                    "minimum" to 0,
                ),
                ARG_LIMIT to jsonObjectOf(
                    "type" to "integer",
                    "description" to "读多少行，默认 $DEFAULT_LIMIT，最大 $MAX_LIMIT",
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
     * 读文件的指定行范围（**纯函数**：file 已定、无 Android 依赖，JVM 直测）。
     *
     * @param file 目标文件（绝对路径；调用方保证已在工作区内）
     */
    fun read(file: File, offset: Int, limit: Int): ToolOutcome {
        if (!file.isFile) {
            return ToolOutcome.Failed("不是一个文件：${file.name}（read_file 只读文件；列目录用 list_dir）")
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolOutcome.Failed(
                "文件太大不整读：${file.name} 有 ${file.length()} B，超过 ${MAX_FILE_BYTES} B 上限。" +
                    "这类文件应该先 list_dir 看结构，再分批 offset/limit 读。"
            )
        }
        val lines = runCatching { file.readLines(Charsets.UTF_8) }
            .getOrElse { return ToolOutcome.Failed("读不了这个文件（编码或 IO 错误）：${file.name}") }
        if (lines.isEmpty()) {
            return ToolOutcome.Ok("（空文件）")
        }
        val start = offset.coerceAtLeast(0)
        if (start >= lines.size) {
            return ToolOutcome.Failed(
                "offset 越界：从 $start 起读，但这个文件一共只有 ${lines.size} 行" +
                    "（行号从 0 起算，最大合法 offset 是 ${lines.size - 1}）"
            )
        }
        val end = (start + limit.coerceIn(1, MAX_LIMIT)).coerceAtMost(lines.size)
        val body = buildString {
            for (i in start until end) {
                append(i).append('\t').append(lines[i]).append('\n')
            }
            append("（第 $start–${end - 1} 行，共 ${lines.size} 行")
            if (end < lines.size) {
                append("；还剩 ${lines.size - end} 行，把 offset 提到 $end 继续读")
            }
            append("）")
        }
        return ToolOutcome.Ok(body.toString().trimEnd())
    }

    /** executor：arguments 里的 path 已是守卫过的绝对路径。 */
    private fun run(args: org.json.JSONObject): ToolOutcome {
        val path = args.optString(ARG_PATH).trim()
        if (path.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        return read(
            File(path),
            args.optInt(ARG_OFFSET, DEFAULT_OFFSET),
            args.optInt(ARG_LIMIT, DEFAULT_LIMIT),
        )
    }
}
