// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：java.io.File 拷贝语义（JDK 标准库）；JSON（RFC 8259）。
package com.example.zhengdao.baopu.library

import com.example.zhengdao.baopu.AskUserCard
import com.example.zhengdao.baopu.AskUserCardRules
import com.example.zhengdao.baopu.AskUserOption
import java.io.File

/** 收料时用户对「怎么处理投递里的件」的四种选择（一次 `ask_user`，选完才动手）。 */
internal enum class MoveChoice {
    /** 搬：**全部认领的文本件**进 App 私有 `原始/`（真隔离），投递那份进**待删除清单**（不自动删）。 */
    MOVE,

    /** 复制：私有留一份，但**共享区留底 ⇒ 隔离作废**（库里 `isolated=0`，UI 必须明示）。 */
    COPY,

    /** 只搬文档类（`.txt`/`.md`）：json/yaml/csv/源码等仍留投递（派工单的「只挑某些格式」）。 */
    MOVE_DOCS_ONLY,

    /** 先不搬：什么都不动（连清单都不写）。 */
    SKIP,
    ;

    /** 这次选择下，[LibraryPaths.FileKind.TEXT] 件要不要进私有目录。 */
    val touchesText: Boolean get() = this != SKIP

    /** 这次选择是不是「搬」（投递原件要进待删除清单、`isolated=1`）。 */
    val isMove: Boolean get() = this == MOVE || this == MOVE_DOCS_ONLY
}

/** 一次收料的扫描报告（给 UI 出清单用：件数/体积/重复件）。 */
internal data class ImportScan(
    val files: List<LibraryPaths.ScannedFile>,
    val truncated: Boolean,
) {
    val textFiles: List<LibraryPaths.ScannedFile> get() = files.filter { it.kind == LibraryPaths.FileKind.TEXT }
    val unclaimedFiles: List<LibraryPaths.ScannedFile> get() = files.filter { it.kind == LibraryPaths.FileKind.UNCLAIMED }
    val textCount: Int get() = textFiles.size
    val unclaimedCount: Int get() = unclaimedFiles.size
    val textBytes: Long get() = textFiles.sumOf { it.size }
}

/** 搬/复制成功的一件（[CopiedFile.privateRelPath] 可能因撞名加过 sha 后缀）。 */
internal data class CopiedFile(
    val relPath: String,
    val privateRelPath: String,
    val sha256: String,
    val size: Long,
    val isolated: Boolean,
    /** true = 私有里已有同内容（没真复制，但内容确实已在私有）。 */
    val alreadyThere: Boolean = false,
)

/** 被拒绝的一件（越界/失败）——**拒绝绝不静默**，调用方要落 [com.example.zhengdao.rootfs.RunLog]。 */
internal data class RejectedFile(val relPath: String, val reason: String)

/** 一次收料执行的结果（数据；DB 落账由壳层接着做）。 */
internal data class ImportOutcome(
    val choice: MoveChoice,
    val copied: List<CopiedFile>,
    /** 没进私有的（不认领格式 / 只搬文本时的非文本 / SKIP 时的全部）。 */
    val leftBehind: List<LibraryPaths.ScannedFile>,
    /** 投递里已搬走原件的相对路径——**只出清单，不许自动删**（派工单硬纪律 8）。 */
    val pendingDelete: List<String>,
    val rejected: List<RejectedFile>,
    /** `整理/_待外部转换.json`（不认领格式只列这里，不解析、不引库）。 */
    val externalListFile: File,
    /** `整理/_待删除清单.json`（搬过之后才有内容）。 */
    val pendingDeleteFile: File,
)

/**
 * 搬家流水线（刀 2 A 段）——扫 `投递/` → 一次 `ask_user` → 执行 → 待删除清单。
 *
 * ## 顺序不许改（派工单 §1 A 段）
 * 1. [scan] 出清单（文件名/大小/mtime/疑似类型）；
 * 2. [card] 一次提问卡问：搬（推荐）/ 复制 / 只挑某些格式 / 先不搬；
 * 3. [execute] 按选择执行；
 * 4. 搬完只出 [ImportOutcome.pendingDelete]，**不删投递原件**。
 *
 * ## 「搬」到底是什么（诚实口径，§E.12）
 * 要真隔离就得搬——复制会在共享区留底、终端 agent 照样读到，隔离作废。所以
 * **搬 = 复制进私有 `原始/` ＋ 对投递原件出待删除清单**；不是 renameTo
 * （原件在清单被用户确认前一个字节都不许少）。
 *
 * ## 纯 IO，无 Android、无 DB
 * 目录全由构造参数喂（JVM 单测用临时目录直跑）；`library_item`/`library_move_log`
 * 的落账在壳层（`BaopuRuntime`）——本类只对文件系统负责。
 */
internal class LibraryImport(
    private val deliverDir: File,
    private val doneDir: File,
    private val rawPrivateDir: File,
) {

    companion object {
        /** 不认领格式清单（只列，不解析）。 */
        const val FILE_EXTERNAL = "_待外部转换.json"

        /** 已搬走原件的待删除清单（用户点头才删；App 永不自动删）。 */
        const val FILE_PENDING_DELETE = "_待删除清单.json"

        /** 「只搬文档」档认的扩展名（派工单「只挑某些格式」的最小可用形态）。 */
        val DOC_EXTS: Set<String> = setOf("txt", "md", "markdown")
    }

    /** 扫 `投递/`（只扫这里——工作区根的 `_索引.md`、`素材库/` 一律不进，判据 §5.3）。 */
    fun scan(): ImportScan {
        val r = LibraryPaths.scanDeliver(deliverDir)
        return ImportScan(files = r.files, truncated = r.truncated)
    }

    /**
     * 一次 `ask_user` 的提问卡（§E.10 ② 房型：推荐第一＋其他＋空白行）。
     * 四档选择正好是派工单 §1 A 段点名的四种：搬／复制／只挑某些格式／先不搬。
     */
    fun card(scan: ImportScan): AskUserCard = AskUserCard(
        question = "投递里有 ${scan.textCount} 个文本件（${humanSize(scan.textBytes)}），怎么处理？",
        options = listOf(
            AskUserOption(id = MoveChoice.MOVE.name.lowercase(), label = "搬进 App 私有（推荐）", recommended = true),
            AskUserOption(id = MoveChoice.COPY.name.lowercase(), label = "复制一份，共享区留底（不作隔离）"),
            AskUserOption(id = MoveChoice.MOVE_DOCS_ONLY.name.lowercase(), label = "只搬文档（.txt/.md），其它仍留投递"),
            AskUserOption(id = MoveChoice.SKIP.name.lowercase(), label = "先不搬"),
        ),
        freeTextPrompt = AskUserCardRules.FREE_TEXT_PROMPT,
    )

    /**
     * 自由输入 → 选择（**纯函数**：关键词命中 ⇒ 对应档；没命中 ⇒ null）。
     *
     * 提问卡永远留一行空白输入（派工单 §1 C 段），用户真在里面打字了就得有个
     *  deterministic 的解释——不猜语义，只认这几个词。没对上 ⇒ null，UI 明说
     * 「没能对上选项，请直接点上面的按钮」（不静默按某个档执行：那是替用户做决定）。
     *
     * 判定顺序即优先级：**先窄后宽**——「只搬文档」比「搬」窄、「先不搬」比「搬」
     * 窄，都含「搬」字，窄的必须先判，否则全被宽的吃掉。
     */
    fun parseChoice(text: String): MoveChoice? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return when {
            t.contains("只搬文档") || t.contains("只搬文本") ||
                (t.contains("只") && (t.contains("文档") || t.contains("文本"))) -> MoveChoice.MOVE_DOCS_ONLY
            t.contains("复制") || t.contains("拷贝") -> MoveChoice.COPY
            t.contains("先不") || t.contains("暂时不") || t.contains("不搬") || t == "不" -> MoveChoice.SKIP
            t.contains("搬") -> MoveChoice.MOVE
            else -> null
        }
    }

    /**
     * 按选择执行（写盘：私有复制 ＋ 两份清单 JSON）。**不删投递里的任何文件。**
     *
     * 每一步的失败都进 [ImportOutcome.rejected]（越界即拒，判据 §5.4），
     * 不让异常把半趟执行吞掉。
     */
    fun execute(choice: MoveChoice, now: Long): ImportOutcome {
        val scan = LibraryPaths.scanDeliver(deliverDir)
        if (choice == MoveChoice.SKIP) {
            return ImportOutcome(
                choice = choice,
                copied = emptyList(),
                leftBehind = scan.files,
                pendingDelete = emptyList(),
                rejected = emptyList(),
                externalListFile = File(doneDir, FILE_EXTERNAL),
                pendingDeleteFile = File(doneDir, FILE_PENDING_DELETE),
            )
        }

        val copied = mutableListOf<CopiedFile>()
        val rejected = mutableListOf<RejectedFile>()
        val pending = mutableListOf<String>()
        val leftBehind = mutableListOf<LibraryPaths.ScannedFile>()

        for (f in scan.files) {
            val isText = f.kind == LibraryPaths.FileKind.TEXT
            // 不认领的格式永远不进私有（只列 _待外部转换.json）；「只搬文档」再收窄到 .txt/.md
            val wanted = when {
                !isText -> false
                choice == MoveChoice.MOVE_DOCS_ONLY -> f.name.substringAfterLast('.', "").lowercase() in DOC_EXTS
                else -> true
            }
            if (!wanted) {
                leftBehind += f
                continue
            }
            val src = File(deliverDir, f.relPath)
            val dest = LibraryPaths.destinationUnder(rawPrivateDir, f.relPath)
            if (dest == null) {
                // 越界即拒：投递里出现 `../`/绝对路径形态的相对路径 ⇒ 拒绝并留痕（调用方落 RunLog）
                rejected += RejectedFile(f.relPath, "目标路径越界（投递相对路径含 ../ 或绝对路径形态），已拒绝")
                continue
            }
            val srcSha = runCatching { FaceIndex.sha256(src) }.getOrElse { e ->
                rejected += RejectedFile(f.relPath, "读取失败：${e.message ?: e.javaClass.simpleName}")
                null
            } ?: continue

            val target = resolveTarget(dest, srcSha)
            if (target == null) {
                rejected += RejectedFile(f.relPath, "私有目录已有同名但不同内容的文件，且加后缀后仍冲突，已拒绝（不覆盖）")
                continue
            }

            if (target.exists()) {
                // 同内容已在私有（sha 相同才可能走到这——resolveTarget 只在这种情况返回已存在路径）
                copied += CopiedFile(
                    relPath = f.relPath,
                    privateRelPath = target.relativeToOrSelf(rawPrivateDir),
                    sha256 = srcSha,
                    size = f.size,
                    isolated = choice.isMove,
                    alreadyThere = true,
                )
            } else {
                val ok = runCatching {
                    target.parentFile?.mkdirs()
                    src.copyTo(target, overwrite = false)
                }.isSuccess
                if (!ok) {
                    rejected += RejectedFile(f.relPath, "复制进私有目录失败（权限/空间？），已拒绝")
                    continue
                }
                copied += CopiedFile(
                    relPath = f.relPath,
                    privateRelPath = target.relativeToOrSelf(rawPrivateDir),
                    sha256 = srcSha,
                    size = f.size,
                    isolated = choice.isMove,
                )
            }

            // 「搬」的诚实代价：投递那份变冗余 ⇒ 只列进待删除清单（用户点头才删）
            if (choice.isMove) {
                pending += f.relPath
            }
        }

        // 目录首建（投递/整理/私有原始 都可能还不存在）——建失败也要让清单写不进去时报出来
        runCatching { doneDir.mkdirs() }
        runCatching { rawPrivateDir.mkdirs() }

        val externalFile = File(doneDir, FILE_EXTERNAL)
        writeExternalList(externalFile, scan.files.filter { it.kind == LibraryPaths.FileKind.UNCLAIMED })

        val pendingDeleteFile = File(doneDir, FILE_PENDING_DELETE)
        if (pending.isNotEmpty()) {
            writePendingDelete(pendingDeleteFile, pending, copied.associate { it.relPath to it.sha256 }, now)
        }

        return ImportOutcome(
            choice = choice,
            copied = copied,
            leftBehind = leftBehind,
            pendingDelete = pending,
            rejected = rejected,
            externalListFile = externalFile,
            pendingDeleteFile = pendingDeleteFile,
        )
    }

    /**
     * 撞名裁决：目标已存在且**同内容** ⇒ 返回它（幂等，不重复复制）；
     * 已存在且**不同内容** ⇒ 加 sha 前 8 位后缀让路（用户两个不同的 `a.txt` 都得活）；
     * 加后缀还撞 ⇒ null（拒绝，不覆盖）。
     */
    private fun resolveTarget(dest: File, srcSha: String): File? {
        if (!dest.exists()) return dest
        val existingSha = runCatching { FaceIndex.sha256(dest) }.getOrNull()
        if (existingSha == srcSha) return dest
        val parent = dest.parentFile ?: return null
        val base = dest.nameWithoutExtension
        val ext = dest.extension
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        var candidate = File(parent, "$base.${srcSha.take(8)}$suffix")
        if (!candidate.exists()) return candidate
        candidate = File(parent, "$base.${srcSha.take(16)}$suffix")
        return if (candidate.exists()) null else candidate
    }

    // ── 两份清单 JSON（原子写：先 .tmp 再 rename，绝不产生半个文件） ──────────

    private fun writeExternalList(file: File, unclaimed: List<LibraryPaths.ScannedFile>) {
        val sorted = unclaimed.sortedBy { it.relPath }
        val sb = StringBuilder()
        sb.append("{\n  \"items\": [\n")
        sorted.forEachIndexed { i, f ->
            sb.append("    {\"name\": \"").append(FaceIndex.jsonEscape(f.name))
            sb.append("\", \"rel_path\": \"").append(FaceIndex.jsonEscape(f.relPath))
            sb.append("\", \"size\": ").append(f.size)
            sb.append(", \"mtime\": ").append(f.mtime)
            sb.append(", \"reason\": \"").append(FaceIndex.jsonEscape(f.reason))
            sb.append("\"}")
            sb.append(if (i == sorted.lastIndex) "\n" else ",\n")
        }
        sb.append("  ]\n}\n")
        atomicWrite(file, sb.toString())
    }

    /**
     * 待删除清单：**与旧清单合并**（只增不减——上一批还没被用户确认，不能因为
     * 又收一批就把提醒抹掉）。按 (rel_path, sha256) 去重、按 rel_path 排序。
     *
     * 同一件再次入列时**刷新 listed_at**（它还在等着用户处理，时间该记「最近一次
     * 被列入」，不是第一次——否则用户看时间戳会以为早就处理过了）。
     */
    private fun writePendingDelete(file: File, relPaths: List<String>, shaByRel: Map<String, String>, now: Long) {
        val existing = runCatching { if (file.isFile) parsePendingDelete(file.readText()) else emptyList() }
            .getOrDefault(emptyList())
        val known = existing.associateBy { it.relPath to it.sha256 }.toMutableMap()
        for (rel in relPaths) {
            val sha = shaByRel[rel] ?: ""
            known[rel to sha] = PendingDeleteEntry(
                relPath = rel,
                name = File(rel).name,
                size = 0,
                mtime = 0,
                sha256 = sha,
                listedAt = now,
            )
        }
        val merged = known.values.sortedBy { it.relPath }
        val sb = StringBuilder()
        sb.append("{\n  \"items\": [\n")
        merged.forEachIndexed { i, e ->
            sb.append("    {\"rel_path\": \"").append(FaceIndex.jsonEscape(e.relPath))
            sb.append("\", \"name\": \"").append(FaceIndex.jsonEscape(e.name))
            sb.append("\", \"size\": ").append(e.size)
            sb.append(", \"mtime\": ").append(e.mtime)
            sb.append(", \"sha256\": \"").append(FaceIndex.jsonEscape(e.sha256))
            sb.append("\", \"listed_at\": ").append(e.listedAt)
            sb.append("\"}")
            sb.append(if (i == merged.lastIndex) "\n" else ",\n")
        }
        sb.append("  ]\n}\n")
        atomicWrite(file, sb.toString())
    }

    private data class PendingDeleteEntry(
        val relPath: String,
        val name: String,
        val size: Long,
        val mtime: Long,
        val sha256: String,
        val listedAt: Long,
    )

    /**
     * 宽容解析（结构坏 ⇒ 空表：旧清单读不了就当没有，绝不因此挡住新清单写入）。
     *
     * ⚠️ **条目在第 2 层花括号**（第 1 层是 `{"items": [...]}` 文档外壳）。
     * 2026-10-17 单测实测出来的坑：按 depth==1 起记的话，条目全在外壳里，
     * 解析器只把**整个文档**当成一条 ⇒ 合并时旧条目被静默吞掉
     * （第三批待删除清单会把第二批冲掉——用户清单里的东西莫名其妙少几条）。
     */
    private fun parsePendingDelete(text: String): List<PendingDeleteEntry> =
        parseRows(text, ::parseEntry)

    /** 按顶层条目切（字符串内的括号不切；条目 = depth 2 的 `{…}`）。 */
    private fun <T> parseRows(text: String, parse: (String) -> T?): List<T> {
        val out = mutableListOf<T>()
        var depth = 0
        var start = -1
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '"' -> { i = skipStr(text, i); continue }
                '{' -> {
                    depth++
                    if (depth == 2) start = i
                }
                '}' -> {
                    depth--
                    if (depth == 1 && start >= 0) {
                        parse(text.substring(start, i + 1))?.let { out += it }
                        start = -1
                    }
                    if (depth < 0) return emptyList() // 结构坏：括号都不配对
                }
            }
            i++
        }
        return out
    }

    private fun parseEntry(obj: String): PendingDeleteEntry? {
        fun str(key: String): String? {
            val k = obj.indexOf("\"$key\"")
            if (k < 0) return null
            val colon = obj.indexOf(':', k)
            if (colon < 0) return null
            val q1 = obj.indexOf('"', colon)
            if (q1 < 0) return null
            val end = skipStr(obj, q1)
            return if (end > q1 + 1) obj.substring(q1 + 1, end - 1) else ""
        }
        fun num(key: String): Long {
            val k = obj.indexOf("\"$key\"")
            if (k < 0) return 0
            val colon = obj.indexOf(':', k)
            if (colon < 0) return 0
            var j = colon + 1
            while (j < obj.length && obj[j].isWhitespace()) j++
            val sb = StringBuilder()
            while (j < obj.length && (obj[j].isDigit() || obj[j] == '-')) { sb.append(obj[j]); j++ }
            return sb.toString().toLongOrNull() ?: 0
        }
        val rel = str("rel_path") ?: return null
        return PendingDeleteEntry(
            relPath = rel,
            name = str("name") ?: File(rel).name,
            size = num("size"),
            mtime = num("mtime"),
            sha256 = str("sha256") ?: "",
            listedAt = num("listed_at"),
        )
    }

    private fun skipStr(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return text.length
    }

    /**
     * 往 `_待外部转换.json` **合并**条目（流水线发现「不是 UTF-8 编码」的件也走这里——
     * 同一个文件只许一个写者，那就是 A 段）。按 rel_path 去重、排序，原子写。
     *
     * **后写覆盖**（不是 distinctBy 保留先到）：同一件先因「是图片」进清单、后被
     * 流水线判出「不是 UTF-8」，新理由必须赢——否则更新被静默吞掉（2026-10-17
     * 单测实测：distinctBy 保留旧理由，用户看到的是过时的原因）。
     */
    fun appendExternalList(entries: List<ExternalEntry>) {
        if (entries.isEmpty()) return
        val file = File(doneDir, FILE_EXTERNAL)
        val existing = runCatching { if (file.isFile) parseExternalList(file.readText()) else emptyList() }
            .getOrDefault(emptyList())
        val byRel = LinkedHashMap<String, ExternalEntry>()
        existing.forEach { byRel[it.relPath] = it }
        entries.forEach { byRel[it.relPath] = it } // 后写赢
        val merged = byRel.values.sortedBy { it.relPath }
        val sb = StringBuilder()
        sb.append("{\n  \"items\": [\n")
        merged.forEachIndexed { i, e ->
            sb.append("    {\"name\": \"").append(FaceIndex.jsonEscape(File(e.relPath).name))
            sb.append("\", \"rel_path\": \"").append(FaceIndex.jsonEscape(e.relPath))
            sb.append("\", \"reason\": \"").append(FaceIndex.jsonEscape(e.reason))
            sb.append("\"}")
            sb.append(if (i == merged.lastIndex) "\n" else ",\n")
        }
        sb.append("  ]\n}\n")
        LibraryPaths.atomicWrite(file, sb.toString())
    }

    /** `_待外部转换.json` 的一条（只列，不解析、不引任何第三方解析库）。 */
    data class ExternalEntry(val relPath: String, val reason: String)

    /**
     * 解析 `_待外部转换.json`（与 [parsePendingDelete] 同一把尺子：条目在第 2 层）。
     *
     * ⚠️ 同样的坑在 2026-10-17 单测里实测过一次（按 depth==1 记会把整个文档
     * 当成一条，合并时吞掉旧条目）——两处共用 [parseRows]，别各写一份。
     */
    private fun parseExternalList(text: String): List<ExternalEntry> =
        parseRows(text, ::parseExternalRow)

    private fun parseExternalRow(obj: String): ExternalEntry? {
        val rel = strField(obj, "rel_path") ?: return null
        return ExternalEntry(rel, strField(obj, "reason") ?: "")
    }

    private fun strField(obj: String, key: String): String? {
        val k = obj.indexOf("\"$key\"")
        if (k < 0) return null
        val colon = obj.indexOf(':', k)
        if (colon < 0) return null
        val q1 = obj.indexOf('"', colon)
        if (q1 < 0) return null
        val end = skipStr(obj, q1)
        return if (end > q1 + 1) obj.substring(q1 + 1, end - 1) else ""
    }

    private fun atomicWrite(target: File, text: String) {
        LibraryPaths.atomicWrite(target, text)
    }

    private fun File.relativeToOrSelf(base: File): String =
        runCatching { relativeTo(base).path }.getOrNull()?.replace('\\', '/') ?: name

    private fun humanSize(n: Long): String = when {
        n >= 1024L * 1024L -> String.format(java.util.Locale.CHINA, "%.1f MB", n / 1024.0 / 1024.0)
        n >= 1024L -> String.format(java.util.Locale.CHINA, "%.1f KB", n / 1024.0)
        else -> "$n B"
    }
}
