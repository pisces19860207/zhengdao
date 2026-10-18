// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：工具补强第二刀（章节目录）。五条硬纪律：
// 1. 权限 READ（FREE）——只读不改；
// 2. 切章**复用** [ChapterSplitter]（同一套判定；两套判定必然让「目录」与「抽取」对不上）；
// 3. 行号口径与 read_file 的 offset 一致（0 起、含端点）⇒ 模型拿 startLine 直接当 offset，
//    不用换算；
// 4. 只回目录（章号/标题/起止行/字数/首句），**不回正文**——正文要用 read_file 按章读，
//    否则这个工具就变成「整本塞进上下文」；
// 5. 触顶（max_chapters）与超大文件都明说，不静默截断。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.library.ChapterSplitter
import com.example.zhengdao.baopu.library.SplitOptions
import com.example.zhengdao.rootfs.PathGuard
import java.io.File

/**
 * `book_outline`：先看一本书的章节目录，再决定读哪几章（工具补强第二刀）。
 *
 * 为什么需要它：两千本书里挑一本读，直接 read_file 只能从头读；
 * 模型要么瞎猜一章、要么把整本读进来。目录是「省 token 的入口」——
 * 一看章名就知道该读哪几章，offset 直接照抄。
 */
internal object BookOutlineTool {

    const val ARG_PATH = "path"
    const val ARG_MAX_CHAPTERS = "max_chapters"

    /** 默认回多少章；触顶会明说「还有 N 章没列」。 */
    const val DEFAULT_MAX_CHAPTERS = 200
    const val MAX_CHAPTERS = 800

    /** 单文件大小上限（目录只是文本，超过 8 MB 的书先让用户拆）。 */
    const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** 首句预览长度（够模型判断这章讲什么，又不至于把目录撑成正文）。 */
    const val PREVIEW_CHARS = 32

    /** 构造注册用 Spec（workspaceRoot 注入：越界自检 + 相对路径渲染都要它）。 */
    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "book_outline",
        description = "看一本书（txt/md）的章节目录：每章的标题、起止行、字数与首句。" +
            "只回目录不回正文；拿到起始行后，用 read_file 的 offset 按章读。" +
            "默认最多 $DEFAULT_MAX_CHAPTERS 章。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "相对工作区根的文件路径（如 资料库/原始/斗罗大陆.txt）",
                ),
                ARG_MAX_CHAPTERS to jsonObjectOf(
                    "type" to "integer",
                    "description" to "最多列几章（默认 $DEFAULT_MAX_CHAPTERS，最大 $MAX_CHAPTERS）",
                    "minimum" to 1,
                    "maximum" to MAX_CHAPTERS,
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }

    /**
     * 目录（**纯函数**：root/file 已定、无 Android 依赖，JVM 直测）。
     *
     * @param root 工作区根（越界自检 + 相对路径渲染的基准）
     * @param file 目标文件（绝对路径；调用方保证已在工作区内）
     */
    fun outline(
        root: File,
        file: File,
        maxChapters: Int = DEFAULT_MAX_CHAPTERS,
        splitOptions: SplitOptions = SplitOptions(),
    ): ToolOutcome {
        if (!file.exists()) {
            return ToolOutcome.Failed("不存在：${file.name}（工作区里没有这个文件；先用 find_files 或 list_dir 找）")
        }
        if (!file.isFile) {
            return ToolOutcome.Failed("不是一个文件：${file.name}（book_outline 只看文件；列目录用 list_dir）")
        }
        if (!PathGuard.isInside(root, file)) {
            return ToolOutcome.Rejected("路径越界被拒：${file.name} 不在工作区（${root.absolutePath}）内。")
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolOutcome.Failed(
                "文件太大不列目录：${file.name} 有 ${file.length()} B，超过 $MAX_FILE_BYTES B 上限。" +
                    "先用 convert_encoding / 切章把它拆小，或直接用 read_file 带 offset 分段读。",
            )
        }
        // 编码只留一份实现（2026-10-17 行 180 的口径）：复用 TextDecode，不另起一套猜法。
        val bytes = runCatching { file.readBytes() }
            .getOrElse { return ToolOutcome.Failed("读不了这个文件（权限或 IO 错误）：${file.name}") }
        val decoded = TextDecode.decode(file, bytes)
        val text = decoded.text
            ?: return ToolOutcome.Failed(decoded.error ?: "读不了：${file.name}")
        val lines = TextDecode.splitLines(text)
        if (lines.isEmpty() || text.isBlank()) {
            return ToolOutcome.Ok("（空文件，没有可列的章节）：${file.name}")
        }
        // 用「已按 TextDecode 口径拆好的行」重新拼文本再切章：这样 split 内部 text.lines()
        // 得到的行号与 read_file 的 offset 严格同口径（末尾换行不会多出一行错位）。
        val chapters = ChapterSplitter.split(lines.joinToString("\n"), splitOptions)
        if (chapters.isEmpty()) {
            return ToolOutcome.Ok("（这本书切不出任何章节）：${file.name}")
        }
        val cap = maxChapters.coerceIn(1, MAX_CHAPTERS)
        val shown = chapters.take(cap)
        val body = buildString {
            append("文件：${relPath(root, file)}\n")
            append("共 ${chapters.size} 章，${lines.size} 行，${text.length} 字符")
            if (decoded.encoding != FileInfoTool.Encodings.UTF8) append("（按 ${decoded.encoding} 解码）")
            append("\n\n")
            append("序号\t标题\t起止行\t字数\t首句\n")
            shown.forEach { ch ->
                val text = ch.textOf(lines).trim()
                append(ch.index).append('\t')
                append(ch.title).append('\t')
                append(ch.startLine).append('–').append(ch.endLine).append('\t')
                append(text.length).append('\t')
                append(previewOf(text)).append('\n')
            }
            if (shown.size < chapters.size) {
                append("\n（只列了前 $cap 章，还有 ${chapters.size - shown.size} 章没列；把 max_chapters 调大）")
            }
            append("\n（读某一章：read_file path=\"${relPath(root, file)}\" offset=起始行 limit=该章行数）")
        }
        return ToolOutcome.Ok(body.toString().trimEnd())
    }

    /** executor：arguments 里的 path 已是守卫过的绝对路径。 */
    private fun run(args: org.json.JSONObject, root: File): ToolOutcome {
        val path = args.optString(ARG_PATH).trim()
        if (path.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        return outline(
            root = root,
            file = File(path),
            maxChapters = args.optInt(ARG_MAX_CHAPTERS, DEFAULT_MAX_CHAPTERS),
        )
    }

    /**
     * 首句预览：第一段非空行，压掉多余空白后截断。
     *
     * 为什么不取「第一个句号前的句子」：小说首行常常是标题/卷名，按句号切反而常常空表；
     * 直接取首个非空行最稳，且模型一眼能判断这章在讲什么。
     */
    private fun previewOf(text: String): String {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val squeezed = firstLine.replace(Regex("\\s+"), " ")
        return if (squeezed.length <= PREVIEW_CHARS) squeezed else squeezed.take(PREVIEW_CHARS - 1) + "…"
    }

    /** 相对路径渲染（统一 `/`）。 */
    private fun relPath(root: File, f: File): String = runCatching {
        f.absoluteFile.toRelativeString(root.absoluteFile).replace('\\', '/')
    }.getOrDefault(f.name)
}
