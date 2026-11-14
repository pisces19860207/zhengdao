// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：抱朴-Agent设计-程枢版 §5 补录「spill 必须配套范围读」：spill 后模型要能只读
// 需要的段 ⇒ read_file 必须支持 offset/limit（刀 0 定 schema 时就留好参数）；
// §10 竖切片①（read_file 是切片①的两个读工具之一）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.ContentRead
import com.example.zhengdao.baopu.ContentUri
import com.example.zhengdao.baopu.UserPath
import com.example.zhengdao.core.doc.DocRead
import com.example.zhengdao.core.doc.DocTextExtractor
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
        description = "读一个文件的指定行范围（offset 从 0 起 = 第一行）。" +
            "**全盘可读**：相对路径相对工作区，绝对路径（如 /storage/emulated/0/小说/…）直接读；" +
            "自动识别 UTF-8（含 BOM）/UTF-16/GBK 并解码，GBK 老书不会读成乱码；" +
            "Word 老格式 .doc 也能读（自动抽正文；抽不出会明确说只支持 .docx）；" +
            "其它二进制文件直接拒绝。大文件被 spill 后，用这个工具带 offset/limit 把需要的那几段读回来。" +
            "回显行号从 0 起；要拿它去 edit_file 改行要 +1（那边行号从 1 起）。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "文件路径：相对工作区根（如 资料库/原始/设定.md），或全盘绝对路径",
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
        readOnly = true,
        parallelSafe = true, // 纯文件读：无共享可变状态，可与别的读并行
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args) }

    /**
     * 读文件的指定行范围（**纯函数**：file 已定、无 Android 依赖，JVM 直测）。
     *
     * @param file 目标文件（绝对路径；调用方保证已在工作区内）
     */
    fun read(file: File, offset: Int, limit: Int): ToolOutcome {
        // 「不存在」与「不是文件」要分开说：真机实测把不存在的路径报成「不是一个文件」，
        // 模型会以为路径对、只是类型不对，于是原地反复重试（2026-10-17）。
        if (!file.exists()) {
            return ToolOutcome.Failed(
                "不存在：${file.absolutePath}（这个位置没有这个文件；先用 list_dir 看结构再决定读哪个）"
            )
        }
        if (!file.isFile) {
            return ToolOutcome.Failed("不是一个文件：${file.absolutePath}（read_file 只读文件；列目录用 list_dir）")
        }
        if (file.length() > maxBytesOf(file.name)) {
            return ToolOutcome.Failed(
                "文件太大不整读：${file.absolutePath} 有 ${file.length()} B，超过 ${maxBytesOf(file.name)} B 上限。" +
                    "这类文件应该先 list_dir 看结构，再分批 offset/limit 读。"
            )
        }
        val bytes = runCatching { file.readBytes() }
            .getOrElse { return ToolOutcome.Failed("读不了这个文件（权限或 IO 错误）：${file.absolutePath}") }
        // 628：解码话术仍按**文件名**（613 未改这层文案，保持零回归）；content:// 走下面的 readContent。
        return render(file.name, bytes, offset, limit)
    }

    /**
     * 628：`content://` 读取入口 —— **不碰 File**（不拼路径、不落产物）。
     * 三条结局各说人话：成功走 [render] 同一份行号渲染；无权限/失效绝不报「不存在」；
     * 超限整条失败、不截断。
     */
    fun readContent(uri: String, offset: Int, limit: Int): ToolOutcome =
        when (val result = ContentUri.read(uri, MAX_FILE_BYTES)) {
            is ContentRead.Ok -> render(
                ContentUri.parseName(result.displayName, result.bytes),
                result.bytes,
                offset,
                limit,
            )
            ContentRead.Unavailable -> ToolOutcome.Failed(ContentUri.unavailableMessage(uri))
            is ContentRead.TooBig -> ToolOutcome.Failed(
                ContentUri.tooBigMessage(result.bytes, MAX_FILE_BYTES, uri)
            )
        }

    /** 从字节到带行号的输出：File 与 content:// 两条入口**只有这一份**。 */
    private fun render(name: String, bytes: ByteArray, offset: Int, limit: Int): ToolOutcome {
        if (bytes.isEmpty()) {
            return ToolOutcome.Ok("（空文件）")
        }
        // 642：`.doc`（Word 97–2003）是**复合文档**，按文本硬读只会出乱码 ⇒ 先抽正文。
        if (DocTextExtractor.canHandle(name)) return renderDoc(bytes, offset, limit)
        val decoded = TextDecode.decodeNamed(name, bytes)
        val text = decoded.text ?: return ToolOutcome.Failed(decoded.error ?: "读不了：$name")
        val note = if (decoded.encoding != FileInfoTool.Encodings.UTF8) {
            "（文件编码判为 ${decoded.encoding}，读时已自动解码）\n"
        } else {
            ""
        }
        return renderLines(text, offset, limit, note)
    }

    /**
     * 642：`.doc` 走 [DocTextExtractor]（只读抽取，不写 .doc）。
     *
     * 抽不出来时**必须明确说"只支持 .docx"**（派工单判据②的负例：报"不存在"就红）——
     * 模型看到"不存在"会以为路径错了、原地重试；看到"格式不支持"才会换路子
     * （让用户另存为 .docx，或改用别的文件）。
     */
    private fun renderDoc(bytes: ByteArray, offset: Int, limit: Int): ToolOutcome =
        when (val read = DocTextExtractor.extract(bytes)) {
            is DocRead.Ok -> renderLines(
                read.text,
                offset,
                limit,
                "（这是 .doc 老格式：已抽出正文 ${read.chars} 字；表格按制表符分隔）\n",
            )
            is DocRead.Unsupported -> ToolOutcome.Failed(
                "只支持 .docx：这份 .doc ${read.reason}。" +
                    "请在 Word/WPS 里「另存为」.docx 再让我读（.doc 我不写，只读这一种老格式）",
            )
        }

    /** 行号渲染（纯文本与 `.doc` 抽取两条路共用一份，行号口径不许分叉）。 */
    private fun renderLines(text: String, offset: Int, limit: Int, note: String): ToolOutcome {
        val lines = TextDecode.splitLines(text)
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
            append(note)
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

    /** 读侧解码已统一到 [TextDecode]（2026-10-17）：判定与解码只许有一份实现——
     *  否则又会回到「read_file 读得出、edit_file 改出乱码」那种各写一套的老路。
     */

    /**
     * 642：`.doc` 走抽取器，它有自己更宽的上限（40MB）。
     *
     * 为什么不一起用 2MB：`.doc` 是二进制容器，正文之外塞着格式信息，2MB 的老合同很常见；
     * 而 `.doc` 又不能"分批 offset 读"（正文位置由分片表决定，不是行）⇒ 卡 2MB 等于
     * 让一半旧资料读不了。抽取器内部另有 20 万字截断兜底，不会把上下文撑爆。
     */
    private fun maxBytesOf(name: String): Long =
        if (DocTextExtractor.canHandle(name)) DocTextExtractor.MAX_DOC_BYTES else MAX_FILE_BYTES

    /**
     * executor：arguments 里的 path 已是守卫过的绝对路径（613 起 `file://` 与前导空白也在这里归一）；
     * 628 起 `content://` 原样透传，在**任何本地路径解析之前**转走。
     */
    private fun run(args: org.json.JSONObject): ToolOutcome {
        val path = args.optString(ARG_PATH).trim()
        if (path.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        val offset = args.optInt(ARG_OFFSET, DEFAULT_OFFSET)
        val limit = args.optInt(ARG_LIMIT, DEFAULT_LIMIT)
        // 628：content:// 不是本地路径 —— **在 613 的外域 scheme 拒与 File(path) 之前**就转走。
        // 顺序是判据③源码断言钉死的：它绝不进「拼路径／判存在」那一支。
        if (ContentUri.isContent(path)) return readContent(path, offset, limit)
        UserPath.foreignScheme(path)?.let { scheme ->
            return ToolOutcome.Failed(
                "「$scheme://」这种地址抱朴还不认（另立单）；只认文件路径，或 file:// 前缀的同一个路径",
            )
        }
        return read(
            // 这里**没有**工作区根可拼（spec 不带根）：相对路径按既有语义交给进程 cwd，
            // 绝对路径本就原样——613 只补 `file://` 归一，不改老语义。
            File(UserPath.normalize(path)),
            offset,
            limit,
        )
    }
}
