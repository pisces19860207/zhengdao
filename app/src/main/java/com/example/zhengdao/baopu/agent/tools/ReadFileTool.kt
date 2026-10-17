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
            "自动识别 UTF-8（含 BOM）/UTF-16/GBK 并解码，GBK 老书不会读成乱码；" +
            "二进制文件直接拒绝。大文件被 spill 后，用这个工具带 offset/limit 把需要的那几段读回来。",
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
        // 「不存在」与「不是文件」要分开说：真机实测把不存在的路径报成「不是一个文件」，
        // 模型会以为路径对、只是类型不对，于是原地反复重试（2026-10-17）。
        if (!file.exists()) {
            return ToolOutcome.Failed(
                "不存在：${file.name}（工作区里没有这个文件；先用 list_dir 看结构再决定读哪个）"
            )
        }
        if (!file.isFile) {
            return ToolOutcome.Failed("不是一个文件：${file.name}（read_file 只读文件；列目录用 list_dir）")
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolOutcome.Failed(
                "文件太大不整读：${file.name} 有 ${file.length()} B，超过 ${MAX_FILE_BYTES} B 上限。" +
                    "这类文件应该先 list_dir 看结构，再分批 offset/limit 读。"
            )
        }
        val bytes = runCatching { file.readBytes() }
            .getOrElse { return ToolOutcome.Failed("读不了这个文件（权限或 IO 错误）：${file.name}") }
        if (bytes.isEmpty()) {
            return ToolOutcome.Ok("（空文件）")
        }
        val decoded = decode(file, bytes)
        val text = decoded.text ?: return ToolOutcome.Failed(decoded.error ?: "读不了：${file.name}")
        val lines = splitLines(text)
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
            if (decoded.encoding != FileInfoTool.Encodings.UTF8) {
                append("（文件编码判为 ${decoded.encoding}，读时已自动解码）\n")
            }
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

    // ── 读侧解码（刀 2 追加包欠的账）─────────────────────────────────────────

    /**
     * 解码结果：[text] 非 null 即成功；否则 [error] 是回给模型的拒绝理由。
     *
     * @param encoding 判定出的编码（人话，直接进回灌文本）
     */
    private class Decoded(val text: String?, val encoding: String, val error: String? = null)

    /**
     * GBK 解码器（Android 与 JDK 都自带；拿不到就退 GB18030，再拿不到就明说读不了）。
     *
     * 为什么不用 CharsetDecoder 的 REPORT：读侧要**宽松**——老书里偶尔一个坏字节
     * 不该让整本读不出来；坏字节显示成 U+FFFD，比把整本判成「读不了」有用。
     */
    private val gbk: java.nio.charset.Charset? by lazy {
        runCatching { java.nio.charset.Charset.forName("GBK") }.getOrNull()
            ?: runCatching { java.nio.charset.Charset.forName("GB18030") }.getOrNull()
    }

    /**
     * 读侧宽松解码。
     *
     * 依据：`FileInfoTool` 的类注释早就写了「猜错时代码侧有 read_file 的宽松解码兜底」——
     * 但本文件一直硬读 UTF-8，等于那句承诺没兑现（进化门禁 gbk-book 用例因此红：
     * 模型读到乱码后如实说「我读了但读不出内容」）。编码判定复用
     * [FileInfoTool.guessEncoding]（BOM/UTF-16/UTF-8 严格/GBK 同一套判据，不另起一套）。
     */
    private fun decode(file: File, bytes: ByteArray): Decoded {
        val head = if (bytes.size <= FileInfoTool.SNIFF_BYTES) bytes else bytes.copyOf(FileInfoTool.SNIFF_BYTES)
        val guess = FileInfoTool.guessEncoding(head, FileInfoTool.looksBinary(head))
        if (guess == FileInfoTool.Encodings.BINARY) {
            return Decoded(
                null,
                "",
                "二进制文件不读：${file.name}（read_file 只读文本；先用 file_info 看类型，别硬读）",
            )
        }
        val charset: java.nio.charset.Charset = when (guess) {
            FileInfoTool.Encodings.UTF8, FileInfoTool.Encodings.UTF8_BOM -> Charsets.UTF_8
            FileInfoTool.Encodings.UTF16_LE -> java.nio.charset.Charset.forName("UTF-16LE")
            FileInfoTool.Encodings.UTF16_BE -> java.nio.charset.Charset.forName("UTF-16BE")
            FileInfoTool.Encodings.GBK -> gbk ?: return Decoded(
                null,
                guess,
                "读不了：这台机器解不开 GBK（${file.name}）。用 file_info 确认后请用户转成 UTF-8。",
            )
            else -> return Decoded(
                null,
                guess,
                "读不了：${file.name} 的编码判不出来（既不是 UTF-8 也不是 GBK/UTF-16），" +
                    "可能不是文本。先 file_info 确认，别猜内容。",
            )
        }
        val text = runCatching { String(bytes, charset) }.getOrElse {
            return Decoded(null, guess, "读不了这个文件（按 $guess 解不开）：${file.name}")
        }
        // BOM 解出来是 U+FEFF（UTF-16 常见）；不裁掉的话第一行会被它污染
        return Decoded(text.trimStart('\uFEFF'), guess)
    }

    /**
     * 按行切（**复刻 BufferedReader.readLine 的语义**：`\r\n`／`\n`／`\r` 都算终结符，
     * 末尾终结符不产生空行）。
     *
     * 为什么手写而不是 `String.bufferedReader().readLines()`：一是行尾口径要与旧版
     * 逐字一致（老书 CRLF 极常见，`\r` 不许留在行内容里）；二是纯字符串
     * 处理更容易在 JVM 直测里钉死。
     */
    internal fun splitLines(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                out.add(text.substring(start, i))
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                i++
                start = i
            } else {
                i++
            }
        }
        if (start < text.length) out.add(text.substring(start))
        return out
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
