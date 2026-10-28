// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.kernel.BaopuKernel
import java.io.File
import com.example.zhengdao.core.RunLog

/**
 * 情景记忆（日记）的**文件存取壳**（147 阶段①）——`MemoryStore` 同哲学：
 * **文件是真相**（`<工作区根>/记忆/日记/<yyyy-MM-dd>.md`，一日一文件、条目内追加），
 * `baopu.db` 的 `diary_index` 只是可重建的加速索引。
 *
 * 与 [MemoryStore] 同款的边界：本类不碰 `Context`、不建线程（写由调用方线程做），
 * 全部逻辑可用临时目录 JVM 直测。脱敏闸在写路径上（判据 21）：**摘要先过
 * [DiaryRules.containsSensitive]，命中 ⇒ 整条不写**——「脱敏失败宁可整条不写」。
 */
internal class DiaryStore(
    /**
     * 记忆根（`<工作区>/记忆/`）——157 起收**根**而不是直接收日记目录：
     * 归档要从 `日记/归档/` 上移到 `记忆/归档/`（用户「记忆目录约定」），
     * 收根才能一处推导日记与归档两处（[MemoryLayout] 是路径真源）。
     */
    private val memoryRoot: File,
    /** 索引库（真删/重建时同步 `diary_index`；null = 不碰索引，纯文件半边）。 */
    private val db: BaopuDb? = null,
) {
    /** 每日日记目录（`<记忆根>/日记/`）。 */
    private val diaryRoot: File = File(memoryRoot, BaopuKernel.Data.DIR_DIARY)

    data class WriteResult(val ok: Boolean, val file: File?, val reason: String? = null)

    /** 全量快照：条目 ＋ 解析失败的文件数（重建报告用，不静默——坏文件要有数可查）。 */
    data class Snapshot(val entries: List<DiaryEntry>, val skippedFiles: Int)

    fun dayFile(date: String): File = File(diaryRoot, "$date.md")

    /** 摘要条目落 `digest-<月份>.md`，普通条目落 `<日期>.md`（[loadAll] 两种都扫）。 */
    private fun fileFor(entry: DiaryEntry): File =
        if (DiaryRecall.isDigest(entry.id)) digestFile(entry.date) else dayFile(entry.date)

    private fun digestFile(month: String): File =
        File(diaryRoot, DiaryRecall.DIGEST_PREFIX + month + ".md")

    /** 归档子目录（软删落点；157 起在 `记忆/归档/`——原 `日记/归档/` 上移一级；
     *  [loadAll] 不递归扫它 ⇒ 归档件不参与回灌）。 */
    private fun archiveDir(): File = File(memoryRoot, BaopuKernel.Data.DIR_ARCHIVE)

    /** 维护报告（收尾顺手跑；失败不阻塞，调用方记日志）。 */
    data class MaintenanceReport(val digested: Int, val archivedFiles: Int, val cleanedFiles: Int)

    /**
     * 浓缩/归档/清理（147 阶段②，判据 14/20）。判据全在 [DiaryRecall]（纯函数），
     * 本方法只做编排与落盘：**只碰 `diaryRoot` 内的日记文件**——产物、用户文件、
     * 资料库原件一概不碰（红线 4 重申）。
     */
    fun maintain(
        nowMs: Long,
        retainDays: Int = DiaryRecall.RETAIN_DAYS,
        archiveDays: Int = DiaryRecall.ARCHIVE_DAYS,
    ): MaintenanceReport {
        if (!diaryRoot.isDirectory) return MaintenanceReport(0, 0, 0)
        val entries = loadAll().entries
        val candidates = DiaryRecall.archiveCandidates(entries, nowMs, retainDays)
        var digested = 0
        var archivedFiles = 0
        val composedDigests = mutableListOf<DiaryEntry>()
        if (candidates.isNotEmpty()) {
            // ① 按月浓缩（判据 14：产物引用一条不丢——composeDigest 里并集去重）
            for ((month, group) in candidates.groupBy { it.date.take(7) }) {
                val digest = DiaryRecall.composeDigest(month, group, nowMs)
                val f = digestFile(month)
                val prev = if (f.isFile) splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse) else emptyList()
                val merged = prev.filter { it.id != digest.id } + digest
                f.parentFile?.mkdirs()
                f.writeText(merged.joinToString("\n") { DiaryCodec.render(it) })
                composedDigests += digest
                digested += group.size
            }
            // ② 原文条目转归档：整日文件的条目全在候选里 ⇒ 整文件挪 归档/（部分候选 ⇒ 重写留非候选）
            val candIds = candidates.map { it.id }.toSet()
            archiveDir().apply { mkdirs() }
            for ((date, group) in entries.groupBy { it.date }) {
                if (group.isEmpty()) continue
                val keep = group.filter { it.id !in candIds }
                val f = dayFile(date)
                if (!f.isFile) continue
                if (keep.isEmpty()) {
                    val target = File(archiveDir(), f.name)
                    if (f.renameTo(target)) {
                        // 归档时间戳：清理按「归档满 archiveDays 天」把关（不是条目年龄）——
                        // renameTo 保留的是写入时间，盖一下才是归档时间。
                        runCatching { target.setLastModified(nowMs) }
                        archivedFiles++
                    }
                } else {
                    f.writeText(keep.joinToString("\n") { DiaryCodec.render(it) })
                }
            }
        }
        // ③ 归档清理（判据 20）：归档满窗口（文件时间戳）＋产物引用被同月摘要完整承载
        //    ⇒ 删归档文件（只删日记；产物与用户文件不碰）。
        var cleanedFiles = 0
        val ad = archiveDir()
        if (ad.isDirectory) {
            val digests = entries.filter { DiaryRecall.isDigest(it.id) } + composedDigests
            val archiveWindowMs = archiveDays * 86_400_000L
            ad.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach { f ->
                if (nowMs - f.lastModified() <= archiveWindowMs) return@forEach // 还没住满 180 天
                val parsed = splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse)
                if (parsed.isEmpty()) return@forEach
                val doomedIds = DiaryRecall.cleanupCandidates(parsed, digests).map { it.id }.toSet()
                if (doomedIds.isEmpty()) return@forEach
                val keep = parsed.filter { it.id !in doomedIds }
                if (keep.isEmpty()) {
                    if (f.delete()) cleanedFiles++
                } else {
                    f.writeText(keep.joinToString("\n") { DiaryCodec.render(it) })
                }
            }
        }
        // 第二百零八批 §2.7 `diary_maintain`：归档/浓缩/清理这类**自动**动作要进决策中心
        // （用户看不见它动过什么，就得能查、能判错）。三档可逆性在这里如实降级：
        // 只归档 ⇒ 可回（归档件还在）；真清掉了归档件 ⇒ 不可回（降级）。
        if (digested > 0 || archivedFiles > 0 || cleanedFiles > 0) {
            DecisionLedger.record(
                DecisionDraft(
                    kind = DecisionKind.DIARY_MAINTAIN,
                    actor = DecisionActor.AUTO,
                    reason = "日记维护：浓缩 $digested 条／归档 $archivedFiles 个／清理 $cleanedFiles 条",
                    evidence = "diary:maintain:$nowMs",
                    before = "日记 $digested 条在日记目录",
                    after = "归档 $archivedFiles 个文件，清理 $cleanedFiles 条",
                    atMs = nowMs,
                    rollbackRef = archiveDir().absolutePath,
                    reversible = cleanedFiles == 0,
                ),
            )
        }
        return MaintenanceReport(digested, archivedFiles, cleanedFiles)
    }

    /**
     * upsert（判据 2/3/11/21）：同 id 替换、其余保留，按 `createdAt` 稳定排序落盘；
     * 脱敏闸命中 ⇒ 拒写（返回 [WriteResult.ok] = false 与原因，调用方记日志）。
     */
    fun upsert(entry: DiaryEntry): WriteResult {
        if (DiaryRules.containsSensitive(entry.summary, entry.artifacts)) {
            return WriteResult(false, null, "脱敏闸命中：条目含敏感样式，整条不写（判据 21）")
        }
        val f = fileFor(entry)
        val existing = if (f.isFile) splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse) else emptyList()
        val merged = existing.filter { it.id != entry.id } + entry
        val sorted = merged.sortedBy { it.createdAt }
        f.parentFile?.mkdirs()
        f.writeText(sorted.joinToString("\n") { DiaryCodec.render(it) })
        return WriteResult(true, f, null)
    }

    /**
     * 建空目录（157 初始化约定：进工作区自动建 `记忆/日记/`，**不弹卡、不写内容**）。
     * 归档目录按需由 [maintain] 建；这里只保证日记目录在（首启与迁移两条路都走它）。
     */
    fun ensureDirs() {
        diaryRoot.mkdirs()
    }

    /**
     * **修正已有条目**（157 补 147 缺口「只有追加/浓缩/归档，没有修正」）。
     *
     * 与 [upsert] 的分工：upsert 是同 id 覆盖（收尾每轮累加的追加语义）；
     * amend 是**显式修正**——provenance 六字段不可改（[DiaryRules.amendGuard]）、
     * 条目不存在即拒（「修正必须改在已有条目上」，不许借修正之名新插一条）、
     * 脱敏闸同样管修正。放行后走 upsert 的同 id 替换落盘（文件是真相）。
     *
     * @return [WriteResult]；ok = false 时 [WriteResult.reason] 说清为什么拒
     */
    fun amend(entry: DiaryEntry): WriteResult {
        val existing = loadAll().entries.firstOrNull { it.id == entry.id }
            ?: return WriteResult(
                false, null,
                "条目不存在（${entry.id}）：修正必须改在已有条目上，不许借修正新插（157）",
            )
        DiaryRules.amendGuard(existing, entry)?.let { reason ->
            return WriteResult(false, null, reason)
        }
        return upsert(entry)
    }

    /**
     * 重建索引（157 收尾流程最后一步）：`diary_index` 只是**可重建的加速层**——
     * 清空重扫（[DiaryIndexStore.rebuild]，文件是真相）。db 为 null ⇒ 0
     * （纯文件半边没有索引可重建）。
     * @return 重建的条目数
     */
    fun rebuildIndex(): Int {
        val d = db ?: return 0
        val entries = loadAll().entries
        DiaryIndexStore(d).rebuild(entries)
        return entries.size
    }

    /** 全量读取（`loadAll` 就是 reindex 的地基——把 `diary_index` 删掉，扫一遍目录全回来）。 */
    fun loadAll(): Snapshot {
        if (!diaryRoot.isDirectory) return Snapshot(emptyList(), 0)
        val entries = mutableListOf<DiaryEntry>()
        var skipped = 0
        diaryRoot.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                val parsed = splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse)
                if (parsed.isEmpty()) skipped++ else entries += parsed
            }
        return Snapshot(entries, skipped)
    }

    /** 按日读取。 */
    fun loadDay(date: String): List<DiaryEntry> {
        val f = dayFile(date)
        if (!f.isFile) return emptyList()
        return splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse)
    }

    /** 归档目录里的条目（真删的可见范围：老日子搬进 归档/ 后仍可按日期删）。 */
    fun loadArchive(): List<DiaryEntry> {
        val ad = archiveDir()
        if (!ad.isDirectory) return emptyList()
        return ad.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?.flatMap { f -> splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse) }
            ?: emptyList()
    }

    /**
     * 按 id 删除（阶段③「真删」的文件半边；**只删日记**——本函数只摸 `diaryRoot`，
     * 调用方红线：不得把任何产物/用户文件路径传进本类的任何方法）。
     * @return 删掉的条数；0 = 没找到（包括已在月度摘要里被浓缩的情形，由调用方另行重算摘要）。
     */
    fun deleteById(id: String): Int {
        if (!diaryRoot.isDirectory) return 0
        var deleted = 0
        diaryRoot.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                val all = splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse)
                val kept = all.filter { it.id != id }
                if (kept.size != all.size) {
                    deleted += all.size - kept.size
                    if (kept.isEmpty()) f.delete() else f.writeText(kept.joinToString("\n") { DiaryCodec.render(it) })
                }
            }
        return deleted
    }

    /**
     * 真删（阶段③，判据 18：四处全消）。[DiaryDeletionRules.plan] 给计划、
     * 本方法执行——**只删日记**：日文件、归档残影、月度摘要重算、索引行；
     * 产物与用户文件一个字节不碰（边界①，调用方红线）。
     *
     * 摘要重算口径：只有「从归档里删掉了条目」才重算受影响月份的摘要
     * （归档条目必然进过摘要；日文件里的新条目没进过，删它不碰摘要）。
     * 重算后该月归档为空 ⇒ 摘要连同文件一起消失（删了就忘）。
     */
    fun realDelete(plan: DiaryDeletionPlan): DiaryDeletionReport {
        if (plan.isEmpty || !diaryRoot.isDirectory) return DiaryDeletionReport(0, 0, 0, 0, 0)
        val doomed = plan.targetIds.toSet()
        var deletedEntries = 0
        var deletedFiles = 0
        var removedArchiveFiles = 0
        val monthsTouchedFromArchive = mutableSetOf<String>()

        fun prune(f: File): Pair<Int, Boolean> {
            val all = splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse)
            val kept = all.filter { it.id !in doomed }
            if (kept.size == all.size) return 0 to false
            if (kept.isEmpty()) {
                return if (f.delete()) (all.size to true) else (0 to false)
            }
            f.writeText(kept.joinToString("\n") { DiaryCodec.render(it) })
            return (all.size - kept.size) to false
        }

        // ① 顶层（日文件＋月度摘要文件）
        diaryRoot.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach { f ->
            val (n, del) = prune(f)
            deletedEntries += n
            if (del) deletedFiles++
        }
        // ② 归档残影
        val ad = archiveDir()
        if (ad.isDirectory) {
            ad.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach { f ->
                val before = splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse)
                val (n, del) = prune(f)
                if (n > 0) {
                    deletedEntries += n
                    before.filter { it.id in doomed }.forEach { monthsTouchedFromArchive += it.date.take(7) }
                }
                if (del) removedArchiveFiles++
            }
        }
        // ③ 受影响月份的摘要重算（只动「确有归档条目被删」的月份）
        var rebuiltDigests = 0
        for (month in monthsTouchedFromArchive) {
            val f = digestFile(month)
            val remaining = loadArchive().filter { it.date.take(7) == month }
            if (remaining.isEmpty()) {
                if (f.isFile && f.delete()) rebuiltDigests++
            } else {
                val digest = DiaryRecall.composeDigest(month, remaining, System.currentTimeMillis())
                val prev = if (f.isFile) splitBlocks(f.readText()).mapNotNull(DiaryCodec::parse) else emptyList()
                val merged = prev.filter { it.id != digest.id } + digest
                f.parentFile?.mkdirs()
                f.writeText(merged.joinToString("\n") { DiaryCodec.render(it) })
                rebuiltDigests++
            }
        }
        // ④ 索引行（db 为 null ⇒ 只做文件半边，调用方自行同步）
        var deletedIndexRows = 0
        db?.let { d ->
            val idx = DiaryIndexStore(d)
            doomed.forEach { deletedIndexRows += idx.deleteById(it) }
            // #9：改前「删失败」和「本来就没有」都是 0 行，一句都不说。文件已经删了，
            // 索引残留必须留话（重建索引能清掉，但那是下一次的事）。
            val residue = idx.deleteFailures()
            if (residue.isNotEmpty()) {
                RunLog.log(
                    "日记真删：索引有 ${residue.size} 行没删动（共请求 ${doomed.size} 行；" +
                        "文件侧已删，索引残留会在下次 rebuildIndex 时清掉）",
                )
            }
        }
        // 第二百零八批 §2.7 `diary_purge`：**真删必留账、必抽检**（147 边界③：删了就没了，
        // 所以 reversible 恒 false——kind 的默认就不可回滚，这里连降级参数都不给）。
        if (deletedEntries > 0 || deletedFiles > 0) {
            DecisionLedger.record(
                DecisionDraft(
                    kind = DecisionKind.DIARY_PURGE,
                    actor = DecisionActor.USER,
                    reason = "日记真删：${plan.targetIds.size} 条（按${if (plan.scope == DiaryDeletionScope.DAY) "日" else "条"}）",
                    evidence = "diary:${plan.targetIds.sorted().joinToString(",").take(200)}",
                    before = "${plan.targetIds.size} 条日记",
                    after = "0 条（删了 ${deletedEntries} 条／${deletedFiles} 个文件）",
                    atMs = System.currentTimeMillis(),
                    rollbackRef = "",
                    reversible = false,
                ),
            ) ?: com.example.zhengdao.core.RunLog.log(
                "日记真删：决策记录没写成（${DecisionLedger.lastError}）",
            )
        }
        return DiaryDeletionReport(deletedEntries, deletedFiles, rebuiltDigests, removedArchiveFiles, deletedIndexRows)
    }

    /**
     * 日文件拆条目：`---` 行开块，到下一个 `---` 行（下一块的字段头）为止。
     * 正文里结构化拼装内容不会产生裸 `---` 行（摘要固定「工具成功 …」开头、
     * 产物行固定 `- ` 前缀），块边界因此无歧义。
     */
    private fun splitBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i] != "---") { i++; continue }
            val sb = StringBuilder("---\n")
            i++
            while (i < lines.size && lines[i] != "---") { sb.append(lines[i]).append('\n'); i++ }
            if (i < lines.size) { sb.append("---\n"); i++ }
            while (i < lines.size && lines[i] != "---") { sb.append(lines[i]).append('\n'); i++ }
            blocks.add(sb.toString())
        }
        return blocks
    }
}
