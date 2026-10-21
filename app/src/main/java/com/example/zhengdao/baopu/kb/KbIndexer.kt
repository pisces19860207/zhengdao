// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单-2026-10-21-第一百二十八批-知识库v2 §1（索引：支持格式、切块、存储、
// 增量/重建；大文件/二进制跳过；编码按现有读取规则）。设计页 §1/§3/§4。
package com.example.zhengdao.baopu.kb

import com.example.zhengdao.baopu.agent.TextDecode
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.terminal.DocxTextExtractor
import java.io.File

/**
 * 建索引（第一百二十八批）——**纯函数**：吃目录出 [KbIndex]，零 Android 依赖，JVM 直测。
 *
 * ## 复用什么、不重写什么
 * - 遍历/垃圾名/软链/上限 ⇒ [LibraryPaths.scanTree]（与资料库同一把尺子）；
 * - 解码 ⇒ [TextDecode]（GBK/UTF-8/UTF-16/BOM 唯一真源）；
 * - docx 正文 ⇒ [DocxTextExtractor]（121 批转正件，zip 炸弹上限它自己管）；
 * - 切块 ⇒ [KbChunker]。
 *
 * ## 与资料库分类的一处刻意差异
 * [LibraryPaths.classify] 把 docx 判「不认领（需外部转换）」——那是**整理流水线**的口径
 * （不搬 docx 进私有原件）。本索引器**认领 docx**（有提取器），所以自己按扩展名分：
 * `TEXT_EXTS` → text；`docx` → docx；其余一律不认领（含 pdf/图片/压缩包）。
 */
internal object KbIndexer {

    /** 单文件大小上限（纯文本/docx/html 一视同仁；超过跳过并计数，不硬读）。 */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 单次索引文件数上限（与 [LibraryPaths.MAX_SCAN] 同量级；触顶明示，不静默）。 */
    const val MAX_FILES = LibraryPaths.MAX_SCAN

    /** 建索引结果（索引本体 ＋ 给人看的账）。 */
    data class BuildResult(val index: KbIndex, val report: String)

    /** 文件分类（索引口径）：无扩展名 ⇒ 不猜；docx ⇒ docx；html ⇒ html；文本扩展名 ⇒ text；其余 ⇒ null（不认领）。 */
    private fun kindOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || name == ext) return null
        return when (ext) {
            "docx" -> "docx"
            "html", "htm" -> "html"
            in LibraryPaths.TEXT_EXTS -> "text"
            else -> null
        }
    }

    /**
     * 建/增量更新 [root] 目录的索引。
     *
     * @param root 被索引目录（绝对路径；调用方已保证在工作区内）
     * @param label 相对工作区根的展示名（写进 [KbIndex.rootLabel]）
     * @param previous 旧索引（增量用；null 或 root 不符 ⇒ 全量）
     * @param rebuild true ⇒ 忽略指纹全量重切
     * @param maxFiles 文件数上限（测试可传小值；生产走 [MAX_FILES]）
     * @param now 索引时间戳（注入以便 JVM 直测确定性输出）
     */
    fun build(
        root: File,
        label: String,
        previous: KbIndex? = null,
        rebuild: Boolean = false,
        maxFiles: Int = MAX_FILES,
        now: Long = System.currentTimeMillis(),
    ): BuildResult {
        val scan = LibraryPaths.scanTree(root)
        val reusable = if (rebuild || previous == null || previous.root != root.absolutePath) {
            emptyMap()
        } else {
            previous.docs.associateBy { it.relPath }
        }
        val docs = ArrayList<KbDoc>()
        var skippedBig = 0
        var skippedBinary = 0
        var skippedUnclaimed = 0
        var reused = 0
        var truncated = false

        for (f in scan.files) {
            if (docs.size >= maxFiles) {
                truncated = true
                break
            }
            val kind = kindOf(f.name)
            if (kind == null) {
                skippedUnclaimed++
                continue
            }
            if (f.size > MAX_FILE_BYTES) {
                skippedBig++
                continue
            }
            // 增量：指纹（relPath+size+mtime）没变 ⇒ chunks 整体留用，一个字节都不重读
            val prev = reusable[f.relPath]
            if (prev != null && prev.size == f.size && prev.mtime == f.mtime && prev.kind == kind) {
                docs += prev
                reused++
                continue
            }
            val file = File(root, f.relPath)
            val chunks = when (kind) {
                "docx" -> chunkDocx(file, f.relPath)
                else -> chunkTextFile(file, f.relPath, kind)
            }
            if (chunks == null) {
                skippedBinary++ // 读不出/二进制/抽取失败：明账，不静默
                continue
            }
            docs += KbDoc(relPath = f.relPath, size = f.size, mtime = f.mtime, kind = kind, chunks = chunks)
        }

        val stats = KbStats(
            filesIndexed = docs.size,
            filesSkipped = skippedBig + skippedBinary + skippedUnclaimed,
            chunksTotal = docs.sumOf { it.chunks.size },
            truncated = truncated || scan.truncated,
            skippedBig = skippedBig,
            skippedBinary = skippedBinary,
            skippedUnclaimed = skippedUnclaimed,
            reused = reused,
        )
        val index = KbIndex(
            root = root.absolutePath,
            rootLabel = label,
            updatedAt = now,
            docs = docs,
            stats = stats,
        )
        return BuildResult(index, report(index, stats, truncated || scan.truncated, maxFiles))
    }

    /** 纯文本/html：走 [TextDecode] 解码后切块；读不出 ⇒ null（调用方计数跳过）。 */
    private fun chunkTextFile(file: File, relPath: String, kind: String): List<KbChunk>? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val decoded = TextDecode.decode(file, bytes)
        val text = decoded.text ?: return null
        return when (kind) {
            "html" -> KbChunker.chunkText(
                relPath = relPath,
                text = KbChunker.htmlToText(text),
                style = KbChunker.ChunkStyle.PLAIN,
                source = KbChunk.SOURCE_EXTRACTED,
            )
            else -> {
                val style = if (relPath.substringAfterLast('.').lowercase() in setOf("md", "markdown")) {
                    KbChunker.ChunkStyle.MARKDOWN
                } else {
                    KbChunker.ChunkStyle.PLAIN
                }
                KbChunker.chunkText(relPath, text, style, KbChunk.SOURCE_FILE)
            }
        }
    }

    /** docx：121 批的提取器抽正文后切块；抽不出（空串）⇒ null。 */
    private fun chunkDocx(file: File, relPath: String): List<KbChunk>? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val text = runCatching { DocxTextExtractor.extract(bytes) }.getOrNull() ?: return null
        if (text.isBlank()) return null
        return KbChunker.chunkText(relPath, text, KbChunker.ChunkStyle.PLAIN, KbChunk.SOURCE_EXTRACTED)
    }

    /** 建索引结果的**人话报告**（工具回灌与日志共用；数字必须和 stats 逐项对得上）。 */
    private fun report(index: KbIndex, stats: KbStats, truncated: Boolean, maxFiles: Int): String = buildString {
        append("已索引目录：").append(index.rootLabel).append('\n')
        append("文件：索引 ${stats.filesIndexed} 个（复用 ${stats.reused} 个），跳过 ${stats.filesSkipped} 个")
        if (stats.filesSkipped > 0) {
            append("（过大 ${stats.skippedBig} / 读不出或二进制 ${stats.skippedBinary} / 格式不认领 ${stats.skippedUnclaimed}）")
        }
        append("；切块共 ${stats.chunksTotal} 块\n")
        if (truncated) {
            append("注意：已达文件数上限 $maxFiles，未索引进全部——把目录收窄后重建\n")
        }
        append("索引只存在本机 App 私有目录，不上传、不分享；检索用 kb_search。")
    }
}
