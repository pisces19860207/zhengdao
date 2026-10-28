// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-24-第一百八十二批 §0.5/§1（索引落 证道/知识库/索引/，
// 可整棵删除后重建；只处理 mtime/size 变了的文件；删除靠上轮快照 diff）。
package com.example.zhengdao.baopu.knowledge

import com.example.zhengdao.baopu.kb.KbDoc
import com.example.zhengdao.baopu.kb.KbIndex
import com.example.zhengdao.baopu.kb.KbIndexCodec
import com.example.zhengdao.baopu.kb.KbIndexer
import com.example.zhengdao.baopu.kb.KbStats
import com.example.zhengdao.baopu.library.LibraryPaths
import java.io.File
import java.security.MessageDigest

/**
 * 知识库索引的**读写壳**（第一百八十二批）：格式复用 128 批的 [KbIndexCodec]/[KbIndexer]，
 * 落点改到 `证道/知识库/索引/知识库索引.json`（用户可见、可删、删了就重建）。
 *
 * ## 为什么索引覆盖「原始＋整理＋外部」
 * - `原始/` 是用户投递/迁移的原件（只读）；
 * - `整理/` 是抱朴整理后的副本（md/摘要）；
 * - `外部/` 是只索引不搬的来源（投递、App 私有原件区、`外部库.txt` 点名的目录）。
 *
 * `索引/` 自己被 [KnowledgeLayout.excludedFromIndex] 排除——否则索引文件会把自己索引进去。
 */
internal object KnowledgeIndexStore {

    data class BuildOutcome(
        val index: KbIndex?,
        val report: String,
        val snapshot: KnowledgeSnapshot.Snapshot,
        val diff: KnowledgeSnapshot.Diff,
        val cancelled: Boolean,
        val filesIndexed: Int,
    )

    // ── 读 ──────────────────────────────────────────────────────────────────

    /**
     * 读侧三态（**259-08 / A-P2-18**）：「没有这个文件」与「文件在但读坏了」必须分开。
     *
     * 改前：`runCatching { f.readText() }.getOrDefault("")` → `decode("")` → null，于是**损坏的
     * 索引**和**从没建过索引**在调用方眼里一模一样：界面显示「还没有索引」、刷新按全量重建
     * 走（上一轮的增量复用静默没了），用户既不知道自己的索引文件被写坏过，也不知道这次重建
     * 本来是可以省掉的。派工单的「不许当没有索引」就落在这三态上。
     *
     * [Corrupt] 只带**可对账、不可逆**的线索（字节数＋sha256 前 8 位）＋环节分类，
     * 不带正文原文：索引里是用户的文档片段，原文进日志＝把内容搬到另一个落点。
     */
    internal sealed class FileRead<out T> {
        /** 读数（`Missing`/`Corrupt` ⇒ null）：调用方还能当「没有」用，但**已经知道**是哪种没有。 */
        abstract fun valueOrNull(): T?

        /** 文件不存在 ⇒ 真的「没有」。 */
        object Missing : FileRead<Nothing>() {
            override fun valueOrNull(): Nothing? = null
        }

        /** 文件在，但读不出／解不开 ⇒ 「坏了」。 */
        data class Corrupt(val why: String, val bytes: Int, val digest8: String) : FileRead<Nothing>() {
            override fun valueOrNull(): Nothing? = null
        }

        data class Value<T>(val value: T) : FileRead<T>() {
            override fun valueOrNull(): T = value
        }

        fun isCorrupt(): Boolean = this is Corrupt
    }

    fun load(workspace: File): KbIndex? = loadRead(workspace).valueOrNull()

    /** 索引文件的三态读数（259-08）；[load] 保持「拿不到就 null」的旧签名。 */
    fun loadRead(workspace: File): FileRead<KbIndex> =
        readFile(KnowledgeLayout.indexFile(workspace)) { KbIndexCodec.decode(it) }

    fun loadSnapshot(workspace: File): KnowledgeSnapshot.Snapshot? = loadSnapshotRead(workspace).valueOrNull()

    /** 快照文件的三态读数（A-P2-18 点的是 :44 与 :49 两处，同一口径）。 */
    fun loadSnapshotRead(workspace: File): FileRead<KnowledgeSnapshot.Snapshot> =
        readFile(KnowledgeLayout.snapshotFile(workspace)) { KnowledgeSnapshot.decode(it) }

    /**
     * 「文件在不在？读不读得出？解不解得开？」三问分开答（索引与快照共用这一份，不各写一套）。
     */
    internal fun <T> readFile(f: File, decode: (String) -> T?): FileRead<T> {
        if (!f.isFile) return FileRead.Missing
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
            ?: return FileRead.Corrupt("读不出（IO 故障）", 0, "")
        val value = decode(text)
        if (value != null) return FileRead.Value(value)
        return FileRead.Corrupt("解不开（格式不符或 JSON 坏）", text.toByteArray(Charsets.UTF_8).size, digest8(text))
    }

    /** sha256 前 8 位：同一份坏文件两次读数一致、改一个字就变；不可逆，也不含正文。 */
    internal fun digest8(text: String): String = runCatching {
        // 逐字节掩码（与 McpText.shortHash 同口径）：负 Byte 交给 "%02x" 的表述随版本
        // 不一，而对账指纹必须每次读数都一致。
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }.getOrDefault("00000000")

    // ── 写（原子写；失败如实报，不静默当成功） ───────────────────────────────

    fun save(workspace: File, index: KbIndex, snapshot: KnowledgeSnapshot.Snapshot): Boolean {
        val indexOk = LibraryPaths.atomicWrite(
            KnowledgeLayout.indexFile(workspace),
            KbIndexCodec.encode(index),
        )
        if (!indexOk) return false
        // 索引落盘成功才写快照：快照是下一轮 diff 的基准，索引没写成时别把基准推走。
        return LibraryPaths.atomicWrite(
            KnowledgeLayout.snapshotFile(workspace),
            KnowledgeSnapshot.encode(snapshot),
        )
    }

    // ── 建（增量） ───────────────────────────────────────────────────────────

    /**
     * 建一份合并索引：主根 `知识库/`（排除 `索引/`）＋ 每个外部根（前缀 `外部/<名字>/`）。
     *
     * 取消：任何一环取消 ⇒ 返回 `index = null` 且 `cancelled = true`，调用方**不许保存**。
     * 增量：指纹（relPath＋size＋mtime）没变的文件由 [KbIndexer] 整体留用，一个字节都不重读。
     */
    fun build(
        workspace: File,
        filesDir: File,
        previousIndex: KbIndex?,
        previousSnapshot: KnowledgeSnapshot.Snapshot?,
        control: RefreshControl? = null,
        now: Long = System.currentTimeMillis(),
    ): BuildOutcome {
        val snapshot = KnowledgeSnapshot.capture(workspace, filesDir)
        val diff = KnowledgeSnapshot.diff(previousSnapshot, snapshot)
        val totalHint = snapshot.entries.size.coerceAtLeast(1)
        val mainRoot = KnowledgeLayout.knowledgeDir(workspace)
        val mainPrev = previousIndex?.let { mainOnly(it) }
        val main = KbIndexer.build(
            root = mainRoot,
            label = "知识库",
            previous = mainPrev,
            rebuild = mainPrev == null,
            now = now,
            excludeRelPrefixes = setOf(KnowledgeLayout.SUB_INDEX),
            cancelled = { control?.isCancelled() == true },
            onProgress = { done, _ -> control?.progress(done, totalHint) },
        )
        if (main.cancelled) {
            return BuildOutcome(null, main.report, snapshot, diff, true, main.index.stats.filesIndexed)
        }

        val docs = ArrayList<KbDoc>(main.index.docs)
        var stats = main.index.stats
        var files = main.index.stats.filesIndexed
        var offset = main.index.stats.filesIndexed + main.index.stats.filesSkipped
        for (root in KnowledgeLayout.externalRoots(workspace, filesDir)) {
            if (control?.isCancelled() == true) {
                return BuildOutcome(null, "已取消（外部根 ${root.name} 未处理）", snapshot, diff, true, files)
            }
            val prefix = "外部/${root.name}/"
            val prev = externalPrevious(previousIndex, root, prefix, now)
            val r = KbIndexer.build(
                root = root.dir,
                label = root.name,
                previous = prev,
                rebuild = prev == null,
                now = now,
                cancelled = { control?.isCancelled() == true },
                onProgress = { done, _ ->
                    control?.progress((offset + done).coerceAtMost(totalHint), totalHint)
                },
            )
            if (r.cancelled) {
                return BuildOutcome(null, "已取消（外部根 ${root.name} 未处理完）", snapshot, diff, true, files)
            }
            docs += r.index.docs.map { d -> prefixDoc(d, prefix) }
            stats = plus(stats, r.index.stats)
            files += r.index.stats.filesIndexed
            offset += r.index.stats.filesIndexed + r.index.stats.filesSkipped
        }

        val index = KbIndex(
            root = mainRoot.absolutePath,
            rootLabel = "知识库",
            updatedAt = now,
            docs = docs.sortedBy { it.relPath },
            stats = stats,
        )
        return BuildOutcome(index, report(index, diff, snapshot), snapshot, diff, false, files)
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 旧合并索引里的主根部分（去掉外部前缀的文档）。 */
    private fun mainOnly(index: KbIndex): KbIndex =
        index.copy(docs = index.docs.filterNot { it.relPath.startsWith("外部/") })

    /** 旧索引里某外部根的部分：去掉前缀还原成该根自己的 relPath，供 KbIndexer 增量复用。 */
    private fun externalPrevious(
        previous: KbIndex?,
        root: KnowledgeLayout.ExternalRoot,
        prefix: String,
        now: Long,
    ): KbIndex? {
        if (previous == null) return null
        val docs = previous.docs.filter { it.relPath.startsWith(prefix) }
        if (docs.isEmpty()) return null
        return KbIndex(
            root = root.dir.absolutePath,
            rootLabel = root.name,
            updatedAt = now,
            docs = docs.map { d ->
                val rel = d.relPath.removePrefix(prefix)
                d.copy(relPath = rel, chunks = d.chunks.map { c -> c.copy(relPath = rel) })
            },
        )
    }

    private fun prefixDoc(d: KbDoc, prefix: String): KbDoc = d.copy(
        relPath = prefix + d.relPath,
        chunks = d.chunks.map { c -> c.copy(relPath = prefix + c.relPath) },
    )

    private fun plus(a: KbStats, b: KbStats): KbStats = KbStats(
        filesIndexed = a.filesIndexed + b.filesIndexed,
        filesSkipped = a.filesSkipped + b.filesSkipped,
        chunksTotal = a.chunksTotal + b.chunksTotal,
        truncated = a.truncated || b.truncated,
        skippedBig = a.skippedBig + b.skippedBig,
        skippedBinary = a.skippedBinary + b.skippedBinary,
        skippedUnclaimed = a.skippedUnclaimed + b.skippedUnclaimed,
        skippedExcluded = a.skippedExcluded + b.skippedExcluded,
        reused = a.reused + b.reused,
    )

    private fun report(index: KbIndex, diff: KnowledgeSnapshot.Diff, snapshot: KnowledgeSnapshot.Snapshot): String =
        buildString {
            append("知识库索引：${index.stats.filesIndexed} 个文件、${index.stats.chunksTotal} 块")
            append("（本轮新增 ${diff.added.size}、改动 ${diff.modified.size}、删除 ${diff.removed.size}）")
            if (index.stats.reused > 0) append("；复用 ${index.stats.reused} 个未变文件")
            if (index.stats.filesSkipped > 0) {
                append("；跳过 ${index.stats.filesSkipped} 个（过大 ${index.stats.skippedBig}、" +
                    "读不出 ${index.stats.skippedBinary}、不认领 ${index.stats.skippedUnclaimed}")
                if (index.stats.skippedExcluded > 0) append("、目录排除 ${index.stats.skippedExcluded}")
                append("）")
            }
            if (snapshot.truncated) append("；注意：文件数触顶，未列全")
        }
}
