// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴线刀 2 追加包（文件信息工具）。三条硬纪律：
// 1. 权限 READ（FREE）——只读不改；
// 2. 返回大小、行数（仅文本）、编码猜测（UTF-8/UTF-16/GBK）、是否二进制；
// 3. **判编码只读前 64 KB**，禁止整文件读进内存（行数用流式数，不驻留；
//    超过 8 MB 的文件不数字数——5 秒超时里数不完，且模型也不需要那么准）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.rootfs.PathGuard
import java.io.File

/**
 * `file_info`：看一个文件的大小/行数/编码/是否二进制（刀 2 追加包）。
 *
 * 为什么模型需要它：读文件前先问一句「这文件多大、什么编码」——
 * GBK 文件直接按 UTF-8 读会得到乱码回灌，模型还以为文件内容就是乱的；
 * 二进制文件硬读会把不可见字符灌进上下文。这个工具让它在动手前先看牌。
 *
 * 编码判据是**猜的**（只读前 64 KB，且没有万能的编码鉴定器），所以措辞是
 * 「猜测」——猜错时代码侧有 read_file 的宽松解码兜底，不至于把路堵死。
 */
internal object FileInfoTool {

    const val ARG_PATH = "path"

    /** 编码嗅探字节数（追加包原话：只读前 64 KB）。 */
    const val SNIFF_BYTES = 64 * 1024

    /** 行数统计的大小上限（超过不数，明说）。 */
    const val LINE_COUNT_MAX_BYTES = 8L * 1024 * 1024

    /** 构造注册用 Spec（workspaceRoot 注入：纯函数层越界自检要用）。 */
    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "file_info",
        description = "看工作区内一个文件的大小、行数（仅文本）、编码猜测" +
            "（UTF-8/UTF-16/GBK）与是否二进制。判编码只读前 64 KB，" +
            "不会把整个文件读进内存。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "相对工作区根的文件路径",
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true, // 纯文件读：无共享可变状态，可与别的读并行
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }

    /** 编码猜测的取值（人话，直接回灌给模型）。 */
    object Encodings {
        const val UTF8 = "UTF-8"
        const val UTF8_BOM = "UTF-8（带 BOM）"
        const val UTF16_LE = "UTF-16LE"
        const val UTF16_BE = "UTF-16BE"
        const val GBK = "GBK"
        const val UNKNOWN = "未知（非 UTF-8/GBK 文本）"
        const val BINARY = "二进制（不猜编码）"
    }

    /**
     * 文件信息（**纯函数**：file 已定、无 Android 依赖，JVM 直测）。
     *
     * @param root 工作区根（越界自检；渲染不需要它，但口径与另两个工具一致）
     */
    fun info(root: File, file: File): ToolOutcome {
        if (!file.exists()) {
            return ToolOutcome.Failed(
                "不存在：${file.name}（工作区里没有这个文件；先用 list_dir 看结构）",
            )
        }
        if (!file.isFile) {
            return ToolOutcome.Failed("不是一个文件：${file.name}（file_info 只看文件；列目录用 list_dir）")
        }
        if (!PathGuard.isInside(root, file)) {
            return ToolOutcome.Rejected(
                "路径越界被拒：${file.name} 不在工作区（${root.absolutePath}）内。",
            )
        }
        val size = file.length()
        val head = runCatching { readHead(file, SNIFF_BYTES) }.getOrNull()
            ?: return ToolOutcome.Failed("读不了这个文件（权限或 IO 错误）：${file.name}")
        val encoding = guessEncoding(head, looksBinary(head))
        // 「是不是二进制」以判定的编码为准（BOM 已经压过 NUL，见 guessEncoding 注释）：
        // 否则同一份 UTF-16 文件会「编码：UTF-16LE」+「类型：二进制」自相矛盾。
        val binary = encoding == Encodings.BINARY
        val lines = if (!binary && size <= LINE_COUNT_MAX_BYTES) countLines(file) else null
        val body = buildString {
            append("文件：${file.name}\n")
            append("大小：$size B（${humanSize(size)}）\n")
            append(
                if (lines != null) "行数：$lines 行\n"
                else if (binary) "行数：不适用（二进制文件）\n"
                else "行数：未数（超过 ${LINE_COUNT_MAX_BYTES / 1024 / 1024} MB 的文件不数字数）\n",
            )
            append("编码（猜测）：$encoding\n")
            append("类型：${if (binary) "二进制" else "文本"}")
        }
        return ToolOutcome.Ok(body.toString())
    }

    /** executor：arguments 里的 path 已是守卫过的绝对路径。 */
    private fun run(args: org.json.JSONObject, root: File): ToolOutcome {
        val path = args.optString(ARG_PATH).trim()
        if (path.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        return info(root, File(path))
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

    /**
     * 编码猜测（顺序即优先级：BOM ⇒ 二进制 ⇒ UTF-8 严格 ⇒ GBK ⇒ 未知）。
     *
     * UTF-8 严格判定是手写的（不引库、不依赖 CharsetDecoder 的 Android/JDK 差异）：
     * 按 RFC 3629 的合法序列扫一遍，非法即不是 UTF-8。
     */
    internal fun guessEncoding(head: ByteArray, binary: Boolean): String {
        // BOM 先于二进制（2026-10-17 修正，进化门禁 UTF-16 用例逼出来的）：
        // UTF-16 的换行与码点低位在字节层面天然带 0x00（'\n' ⇒ 0A 00，U+4E00 ⇒ 00 4E），
        // 按「见 NUL 即二进制」的先序，**每一个多行 UTF-16 文本**都会被判成二进制
        // ⇒ file_info 报「二进制」、read_file 直接拒读。BOM（FF FE / FE FF / EF BB BF）
        // 是文本的强证据，让它压过 NUL；没有 BOM 的 NUL 文件照旧判二进制。
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) {
            return Encodings.UTF8_BOM
        }
        if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Encodings.UTF16_LE
        if (head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Encodings.UTF16_BE
        if (binary) return Encodings.BINARY
        if (looksLikeUtf8(head)) return Encodings.UTF8
        if (looksLikeGbk(head)) return Encodings.GBK
        return Encodings.UNKNOWN
    }

    /**
     * 前 [head] 段是否合法 UTF-8（RFC 3629）。
     *
     * 截断在多字节序列中间（64 KB 边界正好切一半）时**宽松判过**——那不足以
     * 证明不是 UTF-8，判「不是」会让 GBK 兜底误报。
     */
    internal fun looksLikeUtf8(head: ByteArray): Boolean {
        var i = 0
        while (i < head.size) {
            val c = head[i].toInt() and 0xFF
            val n = when (c) {
                in 0x00..0x7F -> 1
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> return false // 0x80–0xC1 / 0xF5–0xFF：UTF-8 里没有合法用途
            }
            if (i + n > head.size) return true // 截断：宽松
            for (k in 1 until n) {
                if (head[i + k].toInt() and 0xC0 != 0x80) return false
            }
            i += n
        }
        return true
    }

    /**
     * 前 [head] 段是否像 GBK（双字节序列合法 + **至少出现一个双字节**）。
     *
     * 「至少一个双字节」是必须的：纯 ASCII 也满足 GBK 字节配对，但纯 ASCII
     * 在 [looksLikeUtf8] 就已经判成 UTF-8 了；走到这里还要求见过汉字，
     * 免得把拉丁语文本猜成 GBK。
     */
    internal fun looksLikeGbk(head: ByteArray): Boolean {
        var i = 0
        var sawDouble = false
        while (i < head.size) {
            val c = head[i].toInt() and 0xFF
            if (c < 0x80) {
                i++
                continue
            }
            if (c in 0x81..0xFE && i + 1 < head.size) {
                val t = head[i + 1].toInt() and 0xFF
                if (t in 0x40..0xFE && t != 0x7F) {
                    sawDouble = true
                    i += 2
                    continue
                }
            }
            return false
        }
        return sawDouble
    }

    /** 流式数行（一行一行读、不驻留；大文件已在调用方拦掉）。 */
    private fun countLines(f: File): Long = f.bufferedReader(Charsets.UTF_8).use { r ->
        var n = 0L
        while (r.readLine() != null) n++
        n
    }

    /** 人类可读体积（B / KB / MB；固定 US 小数点，不随系统语言变逗号）。 */
    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
