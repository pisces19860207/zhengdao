// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十八批（2026-10-30）：复制/批量/整理的**共享内核**——四件工具
// （copy_path / bulk_move / bulk_copy / organize / organize_preview）共用的纯机制。
//
// 为什么单独成文件：判据只有一份才保证「预览与实际一致」——organize_preview 与
// organize 执行体跑的是**同一个** [planBulk] / [planOrganize] / [executePlan]，
// 预览列出来的就必然是执行要发生的（判据②的结构性保证，不靠两处代码自觉对齐）。
//
// 五条硬纪律（与 move_path / delete_path 同源，一字不改口径）：
// 1. **绝不覆盖**：撞名不同内容 ⇒ 加 sha 后缀让路；内容相同 ⇒ 幂等跳过；
// 2. **流式复制**：64 KB 缓冲逐块拷，1 GB 的文件也不整块进内存；
// 3. **保留 mtime**：复制完 setLastModified 对齐源文件（rename 天然保留）；
// 4. move 语义 = 优先 rename（原子）；跨分区只能复制 ⇒ **不自动删源**，
//    源路径记进 `产物/_待删除清单.md`（与 move_path 同一份清单，不另起第二张）；
// 5. 整理/批量移动执行成功后写**回执**（`产物/_整理回执/<id>.md`，一行一条
//    `源 -> 目标`），organize 的 undo_receipt 按回执**逐条搬回**——可撤销是判据③。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.core.PathGuard
import com.example.zhengdao.core.TimeFormats
import java.io.File
import java.security.MessageDigest

/** 共享机制（四件工具与单测共用；JVM 直测，零 Android 依赖）。 */
internal object PathCopyCore {

    /** 流式复制的缓冲（64 KB：1 GB 文件也只占这点内存）。 */
    private const val BUFFER_SIZE = 64 * 1024

    /** sha 后缀取前 8 位（与 move_path 同口径）。 */
    private const val SHA_SUFFIX_LEN = 8

    // ── 基础件 ────────────────────────────────────────────────────────────

    /** 文件 sha256（小写 hex，流式）；IO 失败返回 null。 */
    fun sha256(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * 流式复制单文件：64 KB 逐块拷（判据①「复制 1GB 不崩」的结构性保证——
     * 任何时刻内存里最多一个缓冲块），完成后对齐 mtime。失败返回 false（调用方收尾）。
     */
    fun copyFile(src: File, dst: File): Boolean {
        val ok = runCatching {
            src.inputStream().use { input ->
                dst.outputStream().use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }.isSuccess
        if (!ok) return false
        // mtime 对齐：失败不影响「内容已就位」这个事实（个别 FS 不支持 setLastModified）。
        runCatching { dst.setLastModified(src.lastModified()) }
        return true
    }

    /** 撞名让路：`名.ext` ⇒ `名.<sha8>.ext`；再撞就 `-2`、`-3`……（与 move_path 同款）。 */
    fun bypassTarget(dst: File, sha: String): File {
        val base = dst.name.substringBeforeLast('.', dst.name)
        val ext = dst.name.substringAfterLast('.', "")
        val tail = if (ext.isEmpty()) "" else ".$ext"
        var candidate = File(dst.parentFile, "$base.${sha.take(SHA_SUFFIX_LEN)}$tail")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dst.parentFile, "$base.${sha.take(SHA_SUFFIX_LEN)}-$n$tail")
            n++
        }
        return candidate
    }

    /** 相对/绝对路径统一解析（与 move_path 同一条口径）。 */
    fun resolve(root: File, path: String): File =
        if (File(path).isAbsolute) File(path) else File(root, path)

    /** 给模型看的路径：工作区内 ⇒ 相对；工作区外 ⇒ 绝对（287 的显示口径）。 */
    fun relOf(root: File, f: File): String =
        runCatching { f.relativeTo(root).path }.getOrDefault(f.absolutePath)

    // ── 规则筛选 ──────────────────────────────────────────────────────────

    /** 整理的大类（mode=type 的子目录名；扩展名映射唯一真源）。 */
    enum class Category(val dirName: String, val exts: Set<String>) {
        IMAGE("图片", setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")),
        VIDEO("视频", setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "3gp", "ts")),
        AUDIO("音频", setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "amr", "opus")),
        DOC(
            "文档",
            setOf(
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv",
                "epub", "mobi", "json", "html", "htm", "xml",
            ),
        ),
        ARCHIVE("压缩包", setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "apk")),
        OTHER("其他", emptySet()),
        ;

        companion object {
            /** 按扩展名归类（大小写不敏感；无扩展名 ⇒ OTHER）。 */
            fun of(file: File): Category {
                val ext = file.name.substringAfterLast('.', "").lowercase()
                if (ext.isEmpty()) return OTHER
                return entries.firstOrNull { it !== OTHER && ext in it.exts } ?: OTHER
            }

            fun fromKey(raw: String): Category? =
                entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }

    /**
     * 选择规则（批量与整理共用的筛选面；各条件 **AND** 叠加，全空 ＝ 全选）。
     *
     * 日期按 mtime（复制/下载场景里它就是「文件是什么时候来的」）；
     * 大小按字节，参数给 KB。
     */
    data class Rule(
        val exts: Set<String> = emptySet(),
        val category: Category? = null,
        val olderThanDays: Int? = null,
        val newerThanDays: Int? = null,
        val minKb: Long? = null,
        val maxKb: Long? = null,
    ) {
        /** 纯函数：这个文件命中规则吗（[nowMs] 注入以便 JVM 直测确定性）。 */
        fun matches(f: File, nowMs: Long): Boolean =
            !f.isDirectory &&
                extMatches(f) &&
                categoryMatches(f) &&
                dateMatches(f, nowMs) &&
                sizeMatches(f)

        /** 扩展名筛（规则没配 exts ＝ 不筛）。 */
        private fun extMatches(f: File): Boolean {
            if (exts.isEmpty()) return true
            val ext = f.name.substringAfterLast('.', "").lowercase()
            return ext in exts
        }

        /** 类型筛（OTHER 不作为筛选口径——无扩展名的文件只受 exts 管）。 */
        private fun categoryMatches(f: File): Boolean {
            if (category == null || category === Category.OTHER) return true
            return Category.of(f) === category
        }

        /** 日期筛（mtime 读不出＝时间未知 ⇒ 一律不过日期筛，宁可少选不误选）。 */
        private fun dateMatches(f: File, nowMs: Long): Boolean {
            if (olderThanDays == null && newerThanDays == null) return true
            val mtime = runCatching { f.lastModified() }.getOrDefault(0L)
            if (mtime <= 0L) return false
            olderThanDays?.let { days -> if (nowMs - mtime < days * 86_400_000L) return false }
            newerThanDays?.let { days -> if (nowMs - mtime > days * 86_400_000L) return false }
            return true
        }

        /** 大小筛（KB 口径；读不出长度按 0 算）。 */
        private fun sizeMatches(f: File): Boolean {
            if (minKb == null && maxKb == null) return true
            val len = runCatching { f.length() }.getOrDefault(0L)
            minKb?.let { if (len < it * 1024) return false }
            maxKb?.let { if (len > it * 1024) return false }
            return true
        }

        /** 人话一句话（预览与回执里说清「按什么选的」）。 */
        fun describe(): String = buildString {
            val parts = mutableListOf<String>()
            if (exts.isNotEmpty()) parts += "扩展名 " + exts.sorted().joinToString("/")
            category?.let { parts += "类型 ${it.dirName}" }
            olderThanDays?.let { parts += "早于 $it 天" }
            newerThanDays?.let { parts += "近 $it 天内" }
            minKb?.let { parts += "≥${it}KB" }
            maxKb?.let { parts += "≤${it}KB" }
            append(if (parts.isEmpty()) "全部文件" else parts.joinToString(" 且 "))
        }

        companion object {
            /** 从工具参数合成（org.json 入参 → 规则；无名堂的值忽略）。 */
            fun fromArgs(args: org.json.JSONObject): Rule {
                val extSet = args.optJSONArray("ext")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).trim().trim('.', '*').lowercase().takeIf { it.isNotEmpty() }
                    }.toSet()
                }.orEmpty()
                val cat = Category.fromKey(args.optString("type"))
                fun intOf(key: String): Int? = args.opt(key)?.let { v ->
                    when (v) {
                        is Number -> v.toInt()
                        is String -> v.trim().toIntOrNull()
                        else -> null
                    }
                }
                fun longOf(key: String): Long? = intOf(key)?.toLong()
                return Rule(
                    exts = extSet,
                    category = cat,
                    olderThanDays = intOf("older_than_days"),
                    newerThanDays = intOf("newer_than_days"),
                    minKb = longOf("min_kb"),
                    maxKb = longOf("max_kb"),
                )
            }
        }
    }

    // ── 计划 ─────────────────────────────────────────────────────────────

    /** 一份计划：每条 = 源 → 目标（都是绝对路径；预览与执行共用同一份结构）。 */
    data class Plan(
        val entries: List<Pair<File, File>>,
        /** 自身已在目标位（整理时已在分类子目录里 / 复制时源目标同位）——跳过不算数。 */
        val skippedSelf: Int,
        val totalBytes: Long,
    )

    /**
     * 递归收集候选文件（跳过禁区：备份区/回收站/记忆/粘出区/整理回执与整理技能——
     * 那些是 App 自己的地盘，整理不许把它们当原料）。
     */
    fun collect(dir: File, recursive: Boolean): List<File> {
        val out = mutableListOf<File>()
        fun walk(d: File) {
            val kids = runCatching { d.listFiles()?.sortedBy { it.absolutePath } }.getOrNull() ?: return
            for (k in kids) {
                if (k.isFile) {
                    out += k
                } else if (recursive && k.isDirectory && !isProtectedDir(k)) {
                    walk(k)
                }
            }
        }
        walk(dir)
        return out
    }

    /** App 自管目录（散落任何层级都不进整理/批量范围）。 */
    fun isProtectedDir(d: File): Boolean {
        val n = d.name
        return n == "备份" || n == "回收站" || n == ".回收站" || n == "记忆" ||
            n == ".baopu-spill" || n == "_整理回执" || n == "_整理技能" || n == "原始"
    }

    /**
     * 批量计划（bulk_move / bulk_copy 共用）：dir 下命中规则的文件 → dest。
     * recursive=true 时保留 dir 下的相对层级；false 只搬顶层。
     */
    fun planBulk(dir: File, dest: File, rule: Rule, recursive: Boolean, nowMs: Long): Plan {
        val entries = mutableListOf<Pair<File, File>>()
        var skippedSelf = 0
        var total = 0L
        val base = dir.absoluteFile
        for (f in collect(base, recursive)) {
            if (!rule.matches(f, nowMs)) continue
            val rel = runCatching { f.relativeTo(base).path }.getOrDefault(f.name)
            val target = File(dest, rel)
            if (f.absolutePath == target.absolutePath) {
                skippedSelf++
                continue
            }
            entries += f to target
            total += runCatching { f.length() }.getOrDefault(0L)
        }
        return Plan(entries, skippedSelf, total)
    }

    /**
     * 整理计划（organize）：dir 下命中规则的文件按 [mode] 归入子目录。
     * mode=type ⇒ 图片/视频/音频/文档/压缩包/其他；mode=date ⇒ YYYY-MM（按 mtime）。
     * [dest] 缺省 ＝ 就在 dir 里归（in-place）；已经在目标子目录里的文件跳过（幂等）。
     */
    fun planOrganize(dir: File, dest: File, mode: String, rule: Rule, recursive: Boolean, nowMs: Long): Plan {
        val byDate = mode.equals("date", ignoreCase = true)
        val entries = mutableListOf<Pair<File, File>>()
        var skippedSelf = 0
        var total = 0L
        for (f in collect(dir.absoluteFile, recursive)) {
            if (!rule.matches(f, nowMs)) continue
            val sub = if (byDate) {
                val mtime = runCatching { f.lastModified() }.getOrDefault(nowMs)
                TimeFormats.fileStamp(mtime).take(6).let { ym ->
                    // fileStamp 形如 20261030-183000：取前 6 位年月，排成 2026-10
                    if (ym.length >= 6) "${ym.take(4)}-${ym.drop(4)}" else ym
                }
            } else {
                Category.of(f).dirName
            }
            val target = File(dest, "$sub/${f.name}")
            if (f.absolutePath == target.absolutePath) {
                skippedSelf++
                continue
            }
            entries += f to target
            total += runCatching { f.length() }.getOrDefault(0L)
        }
        return Plan(entries, skippedSelf, total)
    }

    // ── 执行 ─────────────────────────────────────────────────────────────

    /** 一次执行的对账（回执与人话回执共用）。 */
    data class ExecReport(
        val done: Int,
        val skippedSame: Int,
        val bypassed: Int,
        val failed: List<String>,
        val bytes: Long,
        /** 跨分区复制没能 rename 的源（move 语义才有；已记进待删除清单）。 */
        val pendingDelete: List<String>,
    ) {
        fun line(verb: String): String = buildString {
            append("已$verb ${done} 个文件")
            if (bytes > 0) append("（${bytes / 1024} KB）")
            if (skippedSame > 0) append("；${skippedSame} 个目标已有相同内容，视为完成")
            if (bypassed > 0) append("；${bypassed} 个撞名不同内容，改名让路（没覆盖任何文件）")
            if (pendingDelete.isNotEmpty()) append(
                "；另有 ${pendingDelete.size} 个源跨分区复制后保留未删，已记进 产物/_待删除清单.md",
            )
            if (failed.isNotEmpty()) {
                append("；${failed.size} 个失败：")
                append(failed.take(3).joinToString("、"))
            }
        }
    }

    /**
     * 执行一份计划（预览与执行同源判据的执行半边）。
     *
     * @param move true＝move 语义（源要消失）：先 rename，跨分区复制后源保留并记待删除清单；
     *             false＝copy 语义（源原地不动）。
     * @param preferCopy 测试钩子：true 时跳过 rename 直接走复制（单测里没有真实的跨分区，
     *                   与 MovePathTool.move 的同名钩子同款，用于确定性验「复制后源保留」）。
     * @param onDone 每成功一条回调（写回执用），参数 = (源, 实际落点)。
     */
    fun executePlan(
        plan: Plan,
        move: Boolean,
        root: File,
        preferCopy: Boolean = false,
        onDone: (File, File) -> Unit,
    ): ExecReport {
        var done = 0
        var skippedSame = 0
        var bypassed = 0
        var bytes = 0L
        val failed = mutableListOf<String>()
        val pendingDelete = mutableListOf<String>()
        for ((src, dst0) in plan.entries) {
            val r = execStep(src, dst0, move, preferCopy, onDone)
            when (r.kind) {
                StepKind.FAILED -> failed += relOf(root, src)
                StepKind.SAME -> {
                    skippedSame++
                    // 幂等跳过时 move 语义下源还在 ⇒ 同样是「该删而没删」的候选（与 move_path 同口径）
                    if (move && src.exists()) pendingDelete += relOf(root, src)
                }
                StepKind.DONE -> {
                    done++
                    bytes += r.bytes
                    if (r.bypassed) bypassed++
                    if (r.keepSource) pendingDelete += relOf(root, src)
                }
            }
        }
        return ExecReport(done, skippedSame, bypassed, failed, bytes, pendingDelete)
    }

    /** 一条计划项的结局（execStep 的回执；对账字段全在这里，executePlan 只管数数）。 */
    private enum class StepKind { DONE, SAME, FAILED }

    private data class StepResult(
        val kind: StepKind,
        val bypassed: Boolean = false,
        val bytes: Long = 0L,
        val keepSource: Boolean = false,
    )

    /**
     * 执行**一条**计划项：建目录 → 撞名处置（同内容幂等/让路）→ rename 优先、
     * 复制兜底（move 语义复制后源保留＝keepSource）→ 失败回删半截目标。
     */
    private fun execStep(
        src: File,
        dst0: File,
        move: Boolean,
        preferCopy: Boolean,
        onDone: (File, File) -> Unit,
    ): StepResult {
        if (!ensureParent(dst0)) return StepResult(StepKind.FAILED)
        val sha = sha256(src) ?: return StepResult(StepKind.FAILED)
        var target = dst0
        var bypassed = false
        if (dst0.exists()) {
            if (sha256(dst0) == sha) {
                // 内容相同 ⇒ 幂等；回执也要记（撤销时把现位的那份搬回原位）
                if (move && src.exists()) onDone(src, dst0)
                return StepResult(StepKind.SAME)
            }
            target = bypassTarget(dst0, sha)
            bypassed = true
        }
        val len = runCatching { src.length() }.getOrDefault(0L)
        if (move && !src.isDirectory && !preferCopy) {
            if (runCatching { src.renameTo(target) }.getOrDefault(false)) {
                onDone(src, target)
                return StepResult(StepKind.DONE, bypassed, len)
            }
            // 跨分区（或 rename 失败）：复制，源保留——**不自动删**（刀 2 铁律）
            return finishCopy(src, target, bypassed, len, keepSource = true, onDone = onDone)
        }
        return finishCopy(src, target, bypassed, len, keepSource = move, onDone = onDone)
    }

    private fun finishCopy(
        src: File,
        target: File,
        bypassed: Boolean,
        len: Long,
        keepSource: Boolean,
        onDone: (File, File) -> Unit,
    ): StepResult {
        if (!copyFile(src, target)) {
            runCatching { if (target.exists()) target.delete() }
            return StepResult(StepKind.FAILED)
        }
        onDone(src, target)
        return StepResult(StepKind.DONE, bypassed, len, keepSource)
    }

    /** 目标父目录就位（已存在或建成功；mkdirs 对已存在的目录返回 false，不算失败）。 */
    private fun ensureParent(dst: File): Boolean = dst.parentFile?.let { p ->
        p.isDirectory || runCatching { p.mkdirs() }.getOrDefault(false)
    } == true

    // ── 回执与撤销 ───────────────────────────────────────────────────────

    /** 回执目录（工作区相对；`产物/` 下带下划线前缀＝App 管理面）。 */
    const val RECEIPTS_DIR_REL = "产物/_整理回执"

    /**
     * 追加一条回执（一行 `- 源 -> 目标`；undo 就是按这些行逐条搬回）。
     * @return undo_id（回执文件名，不带扩展名）；写失败返回 null（动作已发生，
     * 回执缺一行如实由调用方在回执话术里说明）。
     */
    fun appendReceipt(root: File, kind: String, src: File, dst: File, stamp: String): String? {
        val dir = File(root, RECEIPTS_DIR_REL)
        return runCatching {
            dir.mkdirs()
            val id = "$kind-$stamp"
            val f = File(dir, "$id.md")
            if (!f.exists()) {
                f.writeText(
                    "# $kind（$stamp）\n\n" +
                        "抱朴批量动文件的回执：一行一条「源 -> 目标」。" +
                        "要退回就让抱朴按它逐条搬回原位（重名不覆盖，动了哪些退哪些）。\n\n",
                    Charsets.UTF_8,
                )
            }
            f.appendText("- ${src.absolutePath} -> ${dst.absolutePath}\n", Charsets.UTF_8)
            id
        }.getOrNull()
    }

    /**
     * 按回执撤销：把「目标」搬回「源」位置。重名不覆盖（撞名 ⇒ 源位加后缀让路）；
     * 目标已不在（被用户自己动了）的跳过并如实报数。
     *
     * @param receiptRef 回执 id 或路径（相对工作区/绝对均可）
     * @return 三元组 (还原数, 跳过数, 失败数)；回执读不到返回 null
     */
    fun undoReceipt(
        root: File,
        receiptRef: String,
        onEntry: (File, File) -> Unit,
    ): Triple<Int, Int, Int>? {
        val f = resolve(root, receiptRef)
        val receipt = when {
            f.isFile -> f
            f.absolutePath.endsWith(".md") -> f
            else -> File(File(root, RECEIPTS_DIR_REL), "$receiptRef.md")
        }
        val lines = runCatching { receipt.readText(Charsets.UTF_8).lines() }.getOrNull()
            ?: return null
        var restored = 0
        var skipped = 0
        var failed = 0
        for (raw in lines) {
            val line = raw.trim()
            if (!line.startsWith("- ")) continue
            val parts = line.removePrefix("- ").split(" -> ")
            if (parts.size != 2) continue
            val original = File(parts[0].trim()) // 原来在哪（要退回的地方）
            val current = File(parts[1].trim()) // 现在在哪（回执里「目标」端）
            when (undoOne(original, current)) {
                UndoKind.RESTORED -> {
                    restored++
                    onEntry(current, original)
                }
                UndoKind.SKIPPED -> skipped++
                UndoKind.FAILED -> failed++
            }
        }
        return Triple(restored, skipped, failed)
    }

    private enum class UndoKind { RESTORED, SKIPPED, FAILED }

    /**
     * 退回**一条**回实行：现位不在（用户自己动过）＝跳过；原位已有同名（用户后来放的
     * 新文件）＝让路绝不覆盖；搬回失败（复制兜底也不成）＝失败。
     */
    private fun undoOne(original: File, current: File): UndoKind {
        if (!current.exists()) return UndoKind.SKIPPED
        val target = if (original.exists()) {
            // 原位已有同名 ⇒ 让路，绝不覆盖
            val sha = sha256(current) ?: sha256(original) ?: ""
            bypassTarget(original, sha)
        } else {
            runCatching { original.parentFile?.mkdirs() }
            original
        }
        val ok = runCatching { current.renameTo(target) }.getOrDefault(false) ||
            (copyFile(current, target) && runCatching { current.delete() }.getOrDefault(false))
        return if (ok) UndoKind.RESTORED else UndoKind.FAILED
    }

    /** 备份区禁区判定（与 move_path / delete_path 同一处常量）。 */
    fun insideBackup(root: File, f: File): Boolean =
        PathGuard.isInside(File(root, "备份"), f)
}
