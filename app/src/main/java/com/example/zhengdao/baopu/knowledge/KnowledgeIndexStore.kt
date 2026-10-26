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

    fun load(workspace: File): KbIndex? = readIndex(KnowledgeLayout.indexFile(workspace))

    fun loadSnapshot(workspace: File): KnowledgeSnapshot.Snapshot? {
        val f = KnowledgeLayout.snapshotFile(workspace)
        if (!f.isFile) return null
        return KnowledgeSnapshot.decode(runCatching { f.readText(Charsets.UTF_8) }.getOrDefault(""))
    }

    private fun readIndex(f: File): KbIndex? {
        if (!f.isFile) return null
        return KbIndexCodec.decode(runCatching { f.readText(Charsets.UTF_8) }.getOrDefault(""))
    }

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
