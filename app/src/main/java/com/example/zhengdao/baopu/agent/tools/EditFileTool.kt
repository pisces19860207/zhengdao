// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：抱朴线「工具补强」（2026-10-17，用户口径「先把你能做的做了」）。
// 为什么需要它：`write_file` 只能整篇写——改 5 行也要把 500 行重写一遍，
// token 双倍、截断风险也双倍。`edit_file` 只提交「要改的那一段」。
//
// 四条硬纪律：
// 1. 权限 CONFIRM——改文件必须过用户那一下（与 write_file 同口径）；
// 2. 路径过 fileAccessCheck（287：工作区不是权限边界；只有系统/凭据目录一律拒），
//    纯函数层自检、JVM 直测；备份区等禁区另用 PathGuard 硬拒；
// 3. 写之前先把旧版本备份到 <工作区>/备份/（157 起可见化，原 .baopu-bak；备份失败 ⇒ 放弃本次修改）；
// 4. 单次提交 ≤ 200 行、只改文本、**无删除能力**（不提供 delete 参数，也不删行以外的任何东西）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.core.PathGuard
import com.example.zhengdao.baopu.PersonaGuard
import com.example.zhengdao.baopu.FilePaths
import com.example.zhengdao.baopu.backupSkippedFor
import com.example.zhengdao.baopu.fileAccessCheck
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.core.TimeFormats
import java.io.File

/**
 * `edit_file`：按行区间改一个已有文件（替换 / 插入 / 追加）。
 *
 * 三个 op 对应模型最常用的三种「小改」：
 * - [OP_REPLACE]：替换第 `from_line`–`to_line` 行（1 起、含两端）⇒ 解决「只改一段」；
 * - [OP_INSERT_AFTER]：在第 `from_line` 行**之后**插入（0 = 插到文件最前 ⇒ 加章节头/表头）；
 * - [OP_APPEND]：追加到末尾（唯一允许目标不存在 = 当新建用，等价于 write_file 的追加形态）。
 *
 * 为什么行号从 1 起：`read_file` 的输出是带行号的 1 起编号，模型照着念就行，不用心算。
 *
 * 为什么行号越界要拒绝而不是「尽力而为」：静默改动别的位置，是这类工具最坏的失败方式
 * ——用户以为只改了一行，实际文件被挪了段。
 *
 * 输出统一以换行结尾（若原文没有，会补一个）：文本文件普遍约定如此，也便于 diff。
 */
internal object EditFileTool {

    const val ARG_PATH = "path"
    const val ARG_OP = "op"
    const val ARG_FROM_LINE = "from_line"
    const val ARG_TO_LINE = "to_line"
    const val ARG_CONTENT = "content"

    const val OP_REPLACE = "replace_lines"
    const val OP_INSERT_AFTER = "insert_after"
    const val OP_APPEND = "append"

    /** 允许的 op（顺序即错误提示里的次序）。 */
    val OPS: List<String> = listOf(OP_REPLACE, OP_INSERT_AFTER, OP_APPEND)

    /** 单次提交行数上限（与 write_file 一致：再大就该检讨「是不是整篇重写更合适」）。 */
    const val MAX_EDIT_LINES = 200

    /** 可编辑文件大小上限：8 MB（再大就不该整读进内存改，先分段 read_file 看清楚）。 */
    const val MAX_EDIT_BYTES = 8L * 1024 * 1024

    /** 二进制嗅探只看开头这么多字符。 */
    private const val SNIFF_CHARS = 8 * 1024

    /** 备份目录名（与 write_file 同一个；用户可见、可自己翻）。157 起＝`备份/`。 */
    const val BACKUP_DIR_NAME = BaopuKernel.Data.DIR_BACKUP

    /** 构造注册用 Spec。 */
    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "edit_file",
        description = "按行改一个已有的文本文件：替换第 a–b 行、在某行后插入、或追加到末尾。" +
            "只提交要改的那几行（单次 ≤ $MAX_EDIT_LINES 行），不必整篇重写；" +
            "写前自动把旧版本备份到 $BACKUP_DIR_NAME/（旧文件 >5MB 时不备份，回执会说明）。" +
            "相对路径相对工作区；**绝对路径可以改全盘**（系统目录与凭据目录除外）。" +
            "行号从 1 起（与 read_file 输出一致）。" +
            "确认由 App 弹确认卡完成（用户点头才落盘，不点作废）——直接调用即可，" +
            "不要在回复里先问一句。本工具没有删除能力。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "文件路径：相对工作区根（如 产物/设定.md），或全盘绝对路径",
                ),
                ARG_OP to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf(OP_REPLACE, OP_INSERT_AFTER, OP_APPEND),
                    "description" to "改法：$OP_REPLACE 替换行区间／$OP_INSERT_AFTER 在指定行后插入／$OP_APPEND 追加到末尾",
                ),
                ARG_FROM_LINE to jsonObjectOf(
                    "type" to "integer",
                    "minimum" to 0,
                    "description" to "$OP_REPLACE 的起始行（1 起）；$OP_INSERT_AFTER 的锚点行（0 = 插到最前）",
                ),
                ARG_TO_LINE to jsonObjectOf(
                    "type" to "integer",
                    "minimum" to 1,
                    "description" to "$OP_REPLACE 的结束行（含；省略则等于 from_line，即只改一行）",
                ),
                ARG_CONTENT to jsonObjectOf(
                    "type" to "string",
                    "description" to "要写入的新文本（≤ $MAX_EDIT_LINES 行）",
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH, ARG_OP, ARG_CONTENT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        run(
            args = args,
            root = workspaceRoot(),
            fromLine = if (args.has(ARG_FROM_LINE)) args.optInt(ARG_FROM_LINE) else null,
            toLine = if (args.has(ARG_TO_LINE)) args.optInt(ARG_TO_LINE) else null,
        )
    }

    /**
     * 改文件（**纯函数**：root 已定、无 Android 依赖，JVM 直测）。
     *
     * 拒绝分两类（与 write_file 一致）：
     * - [ToolOutcome.Rejected]：越界（终止本轮）；
     * - [ToolOutcome.Failed]：可重试（参数缺失／行号越界／超行数／二进制／太大／备份失败）。
     */
    fun edit(
        root: File,
        path: String,
        op: String,
        fromLine: Int?,
        toLine: Int?,
        content: String,
        stamp: String = TimeFormats.fileStamp(),
    ): ToolOutcome {
        val rel = path.trim()
        val guarded = guardEditArgs(root, rel, op, content)
        guarded.second?.let { return it }
        val t = guarded.first!!
        // 解码统一走 [TextDecode]（2026-10-17）：以前这里硬读 UTF-8，改 GBK 老书会把
        // 乱码写回去——有备份也仍是数据损失。encoding 记下来，写回时按原编码写。
        val original = readOriginalText(t.target, t.exists)
        original.error?.let { return it }
        if (t.exists && original.text.take(SNIFF_CHARS).contains('\u0000')) {
            return ToolOutcome.Failed("像是二进制文件：${t.target.name}（edit_file 只改文本）")
        }
        // E-168 / D-03（2026-10-19）：写回要**保住原文件的行尾与「有没有尾换行」**。
        // 旧实现一律 LF＋补一个尾换行 ⇒ 一个 CRLF 文件被改一行，整篇行尾都变。
        // 新文件（不存在）沿用旧习惯：LF＋尾换行；已存在的文件按原文件保真（E-168）
        val eolStyle = if (t.exists) detectEol(original.text) else EolStyle("\n", endsWithNewline = true)
        val body = original.text.removeSuffix("\r\n").removeSuffix("\n").removeSuffix("\r")
        val lines = if (body.isEmpty()) mutableListOf() else body.split("\r\n", "\n", "\r").toMutableList()
        val total = lines.size
        val applied = applyEditOp(op, lines, t.contentLines, fromLine, toLine, total)
        applied.error?.let { return it }
        val newText = joinWithEol(lines, eolStyle)
        if (newText == original.text) {
            return ToolOutcome.Ok("内容没有变化（${applied.note}），未写盘：${t.target.name}")
        }
        val backup = backupOldVersion(root, t.target, t.exists, stamp)
        backup.error?.let { return it }
        if (!atomicWriteEncoded(t.target, newText, original.encoding)) {
            return ToolOutcome.Failed("写入失败（IO 错误）：${t.target.name}；请用 read_file 复核后再试")
        }
        val encodingNote = if (original.encoding == FileInfoTool.Encodings.UTF8) "" else "；保持原编码 ${original.encoding}"
        return ToolOutcome.Ok(
            "已改 ${t.target.name}：${applied.note}；总行数 $total → ${lines.size}；" +
                "${newText.length} 字符$encodingNote${backup.note}",
        )
    }

    /** 329 拆出：edit 守卫簇的通过产物。 */
    private class EditGuarded(val target: File, val exists: Boolean, val contentLines: List<String>)

    /**
     * 329 拆出：前置守卫簇（顺序与错误文案与原内联版逐字相同）。
     * @return first=通过时的目标（second 为 null）；second=不通过时的 outcome（first 为 null）。
     */
    private fun guardEditArgs(root: File, rel: String, op: String, content: String): Pair<EditGuarded?, ToolOutcome?> {
        if (rel.isBlank()) return null to ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        // 第三十七批 A 段：同上（edit_file 改的也是那两个文件）
        PersonaGuard.blocked(rel)?.let { return null to ToolOutcome.Rejected(it) }
        if (op !in OPS) {
            return null to ToolOutcome.Failed("参数「$ARG_OP」只能是 ${OPS.joinToString("／")}，收到的是「$op」")
        }
        val target = if (File(rel).isAbsolute) File(rel) else File(root, rel)
        // 第二百八十七批：工作区不是权限边界——绝对路径可改全盘；只有系统/凭据目录一律拒。
        fileAccessCheck(target, FilePaths.display(root, target))?.let { return null to ToolOutcome.Rejected(it) }
        if (PathGuard.isInside(File(root, BACKUP_DIR_NAME), target)) {
            return null to ToolOutcome.Failed("不能改 $BACKUP_DIR_NAME/ 里的文件（那是旧版本备份区，改它等于毁掉退路）")
        }
        contentGuard(op, content)?.let { return null to it }
        val exists = target.exists()
        targetGuard(target, exists, rel, op)?.let { return null to it }
        return EditGuarded(target, exists, content.lines()) to null
    }

    /** 329 拆出：content 三守卫（空／超行数）；错误文案与原内联版逐字相同。 */
    private fun contentGuard(op: String, content: String): ToolOutcome? {
        if (content.isEmpty()) {
            return ToolOutcome.Failed("参数「$ARG_CONTENT」为空（没有要写入的内容；只想删行请用 $OP_REPLACE 把区间换成空字符串占位说明）")
        }
        val contentLines = content.lines()
        if (contentLines.size > MAX_EDIT_LINES) {
            return ToolOutcome.Failed(
                "一次最多提交 $MAX_EDIT_LINES 行，这次有 ${contentLines.size} 行。请分几次改。",
            )
        }
        return null
    }

    /** 329 拆出：目标文件三守卫（目录／不存在／太大）；错误文案与原内联版逐字相同。 */
    private fun targetGuard(target: File, exists: Boolean, rel: String, op: String): ToolOutcome? {
        if (target.exists() && target.isDirectory) {
            return ToolOutcome.Failed("目标是个目录：${target.name}（edit_file 只改文本文件）")
        }
        if (!exists && op != OP_APPEND) {
            return ToolOutcome.Failed(
                "文件不存在：$rel。$OP_REPLACE / $OP_INSERT_AFTER 只改已有文件；" +
                    "新建文件请用 write_file。",
            )
        }
        if (exists && target.length() > MAX_EDIT_BYTES) {
            return ToolOutcome.Failed(
                "文件太大（${target.length()} 字节 > $MAX_EDIT_BYTES）：先 read_file 分段看清楚，" +
                    "再决定是分块改还是整篇重写（write_file 有 200 行上限）。",
            )
        }
        return null
    }

    /** 329 拆出：读原文＋探测编码的结果；error 非 null ⇒ 调用方直接回。 */
    private class OriginalText(val text: String, val encoding: String, val error: ToolOutcome?)

    private fun readOriginalText(target: File, exists: Boolean): OriginalText {
        if (!exists) return OriginalText("", FileInfoTool.Encodings.UTF8, null)
        val bytes = runCatching { target.readBytes() }.getOrElse {
            return OriginalText("", FileInfoTool.Encodings.UTF8, ToolOutcome.Failed("读不出这个文件（权限或 IO 错误）：${target.name}"))
        }
        val decoded = TextDecode.decode(target, bytes)
        val text = decoded.text
            ?: return OriginalText(
                "",
                FileInfoTool.Encodings.UTF8,
                ToolOutcome.Failed(decoded.error ?: "读不出这个文件（编码或 IO 错误）：${target.name}"),
            )
        return OriginalText(text, decoded.encoding, null)
    }

    /** 329 拆出：op 分派的产物；error 非 null ⇒ 调用方直接回（行表未被消费）。 */
    private class OpApplied(val note: String, val error: ToolOutcome?)

    /** 329 拆出：按 op 就地改 [lines] 并产出回执 note（三分支语义与原 when 逐字相同）。 */
    private fun applyEditOp(
        op: String,
        lines: MutableList<String>,
        contentLines: List<String>,
        fromLine: Int?,
        toLine: Int?,
        total: Int,
    ): OpApplied = when (op) {
        OP_REPLACE -> {
            val from = fromLine
                ?: return OpApplied("", ToolOutcome.Failed("$OP_REPLACE 需要参数「$ARG_FROM_LINE」（1 起）"))
            val to = toLine ?: from
            if (from < 1 || to < from) {
                OpApplied("", ToolOutcome.Failed("行号不合法：from=$from to=$to（1 起，且 to ≥ from）"))
            } else if (from > total || to > total) {
                OpApplied(
                    "",
                    ToolOutcome.Failed(
                        "行号越界：这个文件只有 $total 行，你要改第 $from–$to 行。" +
                            "先 read_file 确认行号。",
                    ),
                )
            } else {
                val removed = to - from + 1
                lines.subList(from - 1, to).clear()
                lines.addAll(from - 1, contentLines)
                OpApplied("替换第 $from–$to 行（删 $removed 行 ⇒ 写 ${contentLines.size} 行）", null)
            }
        }
        OP_INSERT_AFTER -> {
            val at = fromLine
                ?: return OpApplied("", ToolOutcome.Failed("$OP_INSERT_AFTER 需要参数「$ARG_FROM_LINE」（0 = 插到最前）"))
            if (at < 0 || at > total) {
                OpApplied(
                    "",
                    ToolOutcome.Failed(
                        "行号越界：$OP_INSERT_AFTER 可填 0–$total（0 = 插到文件最前），收到 $at",
                    ),
                )
            } else {
                lines.addAll(at, contentLines)
                val note = if (at == 0) "插入到文件最前（${contentLines.size} 行）" else "在第 $at 行后插入 ${contentLines.size} 行"
                OpApplied(note, null)
            }
        }
        else -> { // OP_APPEND
            lines.addAll(contentLines)
            OpApplied("追加 ${contentLines.size} 行到末尾", null)
        }
    }

    /** 329 拆出：备份旧版本的结果；error 非 null ⇒ 放弃修改。 */
    private class OldVersionBackup(val note: String, val error: ToolOutcome?)

    /**
     * 329 拆出：备份旧版本（失败 ⇒ 放弃本次修改：宁可改不进去，也不能把旧内容弄丢）。
     * 287：旧文件 >5MB 不备份——但回执里必须说明（不然用户以为还能回滚）。
     */
    private fun backupOldVersion(root: File, target: File, exists: Boolean, stamp: String): OldVersionBackup {
        if (exists && backupSkippedFor(target.length())) {
            return OldVersionBackup(
                "；旧版本 ${target.length() / 1024 / 1024} MB 超过 5MB 上限，**没有备份**（改完不可回滚）",
                null,
            )
        }
        if (!exists) return OldVersionBackup("", null)
        val backupName = FilePaths.backupName(root, target, stamp)
        var cause = ""
        val ok = runCatching {
            val dir = File(root, BACKUP_DIR_NAME)
            dir.mkdirs()
            target.copyTo(File(dir, backupName), overwrite = true)
        }.onFailure { cause = "：${it.javaClass.simpleName}: ${it.message}" }.isSuccess
        if (!ok) {
            return OldVersionBackup(
                "",
                ToolOutcome.Failed("备份旧版本失败（${target.name}）$cause，已放弃本次修改。文件原样未动。"),
            )
        }
        return OldVersionBackup("；旧版本已备份到 $BACKUP_DIR_NAME/$backupName", null)
    }

    /**
     * 329 拆出：原子写（先 .tmp 再 rename，半截文件比不写更坏）。
     * 字节按**原编码**编码（GBK 老书改完还是 GBK；UTF-8 BOM / UTF-16 的 BOM 由
     * TextDecode.encode 补回去）——否则「只改了一行」实际把整本换了编码。
     */
    private fun atomicWriteEncoded(target: File, newText: String, encoding: String): Boolean {
        val tmp = File(target.parentFile, target.name + ".edit-tmp")
        val written = runCatching {
            target.parentFile?.mkdirs()
            tmp.writeBytes(TextDecode.encode(newText, encoding))
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }.isSuccess
        if (!written) {
            runCatching { tmp.delete() }
        }
        return written
    }

    /**
     * 备份文件名：路径里的分隔符与**冒号**都换成下划线。
     *
     * 为什么连冒号也要换（2026-10-17 进化门禁抓到的真 bug）：path 参数在管线第 ② 段
     * 会被改写成**绝对路径**再交给 executor，`C:\…\b.md` 直接拼进文件名就成了
     * `C:_…_b.md.<stamp>.bak`；Windows 上冒号是非法文件名字符 ⇒ `copyTo` 抛异常
     * ⇒ 被兜成「备份失败，已放弃」，而模型只能回一句「我不知道为什么」。
     * Android 的 ext4 允许冒号，真机碰不到，但这类报错本来就该一次说清。
     */
    internal fun backupFileName(rel: String, stamp: String): String =
        rel.replace('/', '_').replace('\\', '_').replace(':', '_') + "." + stamp + ".bak"

    /** executor：参数已过 SchemaCheck；行号缺省由 [edit] 自己判。 */
    private fun run(
        args: org.json.JSONObject,
        root: File,
        fromLine: Int?,
        toLine: Int?,
    ): ToolOutcome {
        val path = args.optString(ARG_PATH).trim()
        if (path.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        return edit(
            root = root,
            path = path,
            op = args.optString(ARG_OP),
            fromLine = fromLine,
            toLine = toLine,
            content = args.optString(ARG_CONTENT),
        )
    }

    // ── E-168 / D-03：行尾保真（纯函数，JVM 直测）──────────────────────────

    /** 原文件的行尾风格与「是否以换行结尾」。 */
    internal data class EolStyle(val eol: String, val endsWithNewline: Boolean)

    /**
     * 判主导行尾：有 `CRLF` 就是 `CRLF`（Windows 文件最常见）；只有 `CR` 才是 `CR`；否则 `LF`。
     * 混合行尾的文件按主导风格统一（这一点在改之前就做不到「逐字节保真」——记在 D-03 报告里）。
     */
    internal fun detectEol(text: String): EolStyle {
        val eol = when {
            text.contains("\r\n") -> "\r\n"
            text.contains("\r") -> "\r"
            else -> "\n"
        }
        return EolStyle(eol, text.endsWith("\n") || text.endsWith("\r"))
    }

    /** 按原行尾风格拼回去；原来没有尾换行就不补。 */
    internal fun joinWithEol(lines: List<String>, style: EolStyle): String {
        if (lines.isEmpty()) return ""
        return lines.joinToString(style.eol) + if (style.endsWithNewline) style.eol else ""
    }
}
