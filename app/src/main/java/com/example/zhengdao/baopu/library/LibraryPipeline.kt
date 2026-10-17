// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开标准：SHA-256（FIPS 180-4）；JSON（RFC 8259）；
// java.io.File 拷贝语义（JDK 标准库）。
package com.example.zhengdao.baopu.library

import com.example.zhengdao.util.TimeFormats
import java.io.File

/**
 * 五步流水线（刀 2 B 段）：认脸 → 去重 → 归一 → 副本进 `整理/` → 写索引。
 *
 * ## 每步都可单独被单测打到（派工单 §1 B 段原话）
 * - 认脸/去重/索引读写：[FaceIndex]（纯函数）；
 * - 归一：[Normalizer]（纯函数）；
 * - 本类只做**串起来 + IO**，每一步的产出都进 [PipelineReport]（不许 silently 吞异常）。
 *
 * ## 与 A 段（[LibraryImport]）的分工
 * A 段管「投递 → 私有 `原始/`」（含提问卡与待删除清单）；本类管「私有 `原始/` → `整理/`」。
 * 私有里有什么本类不知道来历，所以 `isolated`（这批有没有真隔离）由壳层按 A 段的结果
 * 经 [run] 的 `isolatedBySha` 喂进来——**默认 true**：躺在 App 私有目录里的件，
 * 天生就是隔离的（除非 A 段说它是「复制」进来的）。
 *
 * ## 索引是真相，库是镜子
 * `整理/_认脸索引.json` 由本类写（字节稳定，见 [FaceIndex]）；`library_item` 表由壳层
 * 经 [run] 的 `onItem` 回调落（UI 秒查用）。两份字段一一对应，不许双真相。
 */
internal class LibraryPipeline(
    private val rawPrivateDir: File,
    private val doneDir: File,
) {

    companion object {
        /** 导出目录名前缀（`整理/原件导出-<yyyyMMdd-HHmmss>/`）。 */
        const val DIR_EXPORT_PREFIX = "原件导出-"

        /** 单次导出件数上限（防极端情况拖住设备；达到必须告知）。 */
        const val MAX_EXPORT = 2000
    }

    /** 一件的流水线结果。[product] = `整理/` 里的产出相对路径。 */
    data class PipelineItem(
        val relPath: String,
        val sha256: String,
        val product: String,
    )

    /** 一次流水线运行的报告（失败逐条列，不吞）。 */
    data class PipelineReport(
        val scanned: Int,
        val processed: List<PipelineItem>,
        /** 去重打掉的（同内容已认过脸；保留来源路径与时间的是索引里那条）。 */
        val duplicates: List<String>,
        /** 拒绝的（非 UTF-8 / 读失败）——壳层要把它们并进 `_待外部转换.json`。 */
        val rejected: List<RejectedFile>,
        val indexFile: File,
        val indexBytes: Int,
    ) {
        val ok: Boolean get() = rejected.isEmpty()
    }

    /**
     * 跑一遍五步流水线（**幂等**：同一批文件跑两遍，第二遍全记 duplicate，
     * 索引字节与第一遍结束时相同——判据 §5.6）。
     *
     * @param source 来源码（`deliver`/`import`，见 [LibrarySource]）
     * @param isolatedBySha sha → 是否真隔离（A 段「复制」档为 false）
     * @param onItem 每件新认脸的回调（壳层落 `library_item`；不传就纯文件侧）
     */
    fun run(
        source: String = LibrarySource.DELIVER,
        isolatedBySha: Map<String, Boolean> = emptyMap(),
        onItem: (FaceIndex.Item) -> Unit = {},
    ): PipelineReport {
        val scan = LibraryPaths.scanTree(rawPrivateDir)
        val rejected = mutableListOf<RejectedFile>()

        // ① 认脸：流式 sha256（大文件不整读进内存）
        val candidates = mutableListOf<FaceIndex.Item>()
        for (f in scan.files) {
            val file = File(rawPrivateDir, f.relPath)
            val sha = runCatching { FaceIndex.sha256(file) }.getOrElse { e ->
                rejected += RejectedFile(f.relPath, "读取失败：${e.message ?: e.javaClass.simpleName}")
                null
            } ?: continue
            candidates += FaceIndex.Item(
                sha256 = sha,
                name = f.relPath,
                size = f.size,
                mtime = f.mtime,
                source = source,
            )
        }

        // ② 去重：索引里已有同 sha ⇒ duplicate（保留来源路径与时间的是索引里那条）
        val existing = readDoc()
        val dedup = FaceIndex.dedup(candidates, existing.items)

        // ③④ 归一 ＋ 副本进整理/
        val processed = mutableListOf<PipelineItem>()
        val newItems = mutableListOf<FaceIndex.Item>()
        for (c in dedup.kept) {
            val file = File(rawPrivateDir, c.name)
            val bytes = runCatching { file.readBytes() }.getOrElse { e ->
                rejected += RejectedFile(c.name, "读取失败：${e.message ?: e.javaClass.simpleName}")
                null
            } ?: continue
            val norm = Normalizer.normalize(bytes)
            if (!norm.ok) {
                rejected += RejectedFile(c.name, norm.reason)
                continue
            }
            val text = norm.text.orEmpty()
            val base = File(c.name).name
            val content = if (Normalizer.needsSourceHeader(base)) {
                Normalizer.sourceHeaderLine(c.name) + "\n" + text
            } else {
                text
            }
            val product = writeProduct(base, content)
            processed += PipelineItem(relPath = c.name, sha256 = c.sha256, product = product)
            newItems += c.copy(
                isolated = isolatedBySha[c.sha256] ?: true,
                kind = LibraryKind.TEXT,
            )
        }

        // ⑤ 写索引（合并旧条目；render 显式排序 ⇒ 字节稳定）
        val merged = FaceIndex.Doc(items = existing.items + newItems)
        val indexFile = File(doneDir, FaceIndex.FILE_NAME)
        val rendered = FaceIndex.render(merged)
        val wrote = LibraryPaths.atomicWrite(indexFile, rendered)

        newItems.forEach { onItem(it) }

        return PipelineReport(
            scanned = scan.files.size,
            processed = processed,
            duplicates = dedup.duplicates.map { it.first.name },
            rejected = rejected,
            indexFile = indexFile,
            indexBytes = if (wrote) rendered.toByteArray(Charsets.UTF_8).size else -1,
        )
    }

    /** 读当前索引（坏文件 ⇒ 空文档：按「没有索引」重写，不猜半份）。 */
    fun readDoc(): FaceIndex.Doc {
        val f = File(doneDir, FaceIndex.FILE_NAME)
        if (!f.isFile) return FaceIndex.Doc()
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return FaceIndex.Doc()
        return FaceIndex.parse(text) ?: FaceIndex.Doc()
    }

    /**
     * 导出全部原件到 `整理/原件导出-<时间戳>/`（复制一份，**临时用**——导出即主动失去隔离，
     * §E.12 原话）。导出后回写索引 `exported_at` 与两个汇总字段。
     *
     * @param onExported 每件导出成功的回调（壳层同步 `library_item.exported_at`）
     */
    fun exportAll(now: Long, onExported: (String, Long) -> Unit = { _, _ -> }): ExportReport {
        val doc = readDoc()
        if (doc.items.isEmpty()) {
            return ExportReport(exported = emptyList(), dir = null, notes = listOf("还没有认脸索引，没有可导出的原件"))
        }
        return exportItems(doc.items, now, onExported)
    }

    /** 导出某一类（`text`/`unclaimed`，见 [LibraryKind]）。 */
    fun exportKind(kind: String, now: Long, onExported: (String, Long) -> Unit = { _, _ -> }): ExportReport {
        val doc = readDoc()
        val items = doc.items.filter { it.kind == kind }
        if (items.isEmpty()) {
            return ExportReport(exported = emptyList(), dir = null, notes = listOf("这一类（$kind）没有可导出的原件"))
        }
        return exportItems(items, now, onExported)
    }

    private fun exportItems(
        items: List<FaceIndex.Item>,
        now: Long,
        onExported: (String, Long) -> Unit,
    ): ExportReport {
        val dir = File(doneDir, DIR_EXPORT_PREFIX + TimeFormats.fileStamp(now))
        val exported = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val done = mutableSetOf<String>()
        for (it in items) {
            if (exported.size >= MAX_EXPORT) {
                notes += "已达单次导出上限 $MAX_EXPORT 件，其余未导出（不静默截断）"
                break
            }
            val src = File(rawPrivateDir, it.name)
            if (!src.isFile) {
                notes += "私有原件不在，跳过：${it.name}"
                continue
            }
            val dest = collisionSafe(File(dir, File(it.name).name), it.sha256)
            if (dest == null) {
                notes += "导出目标撞名且内容不同，跳过（不覆盖）：${it.name}"
                continue
            }
            if (!dest.exists()) {
                val ok = runCatching {
                    dest.parentFile?.mkdirs()
                    src.copyTo(dest, overwrite = false)
                }.isSuccess
                if (!ok) {
                    notes += "复制失败，跳过：${it.name}"
                    continue
                }
            }
            exported += it.name
            done += it.sha256
        }
        // 回写索引（exported_at 只在这里动）
        val doc = readDoc()
        val updated = doc.items.map { if (it.sha256 in done) it.copy(exportedAt = now) else it }
        LibraryPaths.atomicWrite(File(doneDir, FaceIndex.FILE_NAME), FaceIndex.render(FaceIndex.Doc(items = updated)))
        done.forEach { sha -> onExported(sha, now) }
        return ExportReport(exported = exported, dir = dir.takeIf { exported.isNotEmpty() }, notes = notes)
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    /**
     * 写 `整理/<名>.md`：撞名且**不同内容** ⇒ 加 sha 前 8 位后缀让路（两个不同的
     * `a.md` 都得活）；同内容 ⇒ 直接覆写（幂等，内容一样写不写都一样）。
     *
     * @return 产出文件相对 `整理/` 的路径
     */
    private fun writeProduct(baseName: String, content: String): String {
        var target = File(doneDir, Normalizer.outputName(baseName))
        val contentSha = FaceIndex.sha256Bytes(content.toByteArray(Charsets.UTF_8))
        if (target.exists()) {
            val existingSha = runCatching {
                FaceIndex.sha256Bytes(target.readBytes())
            }.getOrNull()
            if (existingSha != contentSha) {
                val name = target.name
                val dot = name.lastIndexOf('.')
                val prefixed = if (dot > 0) {
                    name.substring(0, dot) + "." + contentSha.take(8) + name.substring(dot)
                } else {
                    name + "." + contentSha.take(8)
                }
                target = File(doneDir, prefixed)
            }
        }
        LibraryPaths.atomicWrite(target, content)
        return target.name
    }

    /** 导出目标撞名裁决：同内容 ⇒ 用它（已在）；不同内容 ⇒ sha 后缀；还撞 ⇒ null。 */
    private fun collisionSafe(dest: File, sha: String): File? {
        if (!dest.exists()) return dest
        val existing = runCatching { FaceIndex.sha256(dest) }.getOrNull()
        if (existing == sha) return dest
        val parent = dest.parentFile ?: return null
        val name = dest.name
        val dot = name.lastIndexOf('.')
        val suffixed = if (dot > 0) {
            File(parent, name.substring(0, dot) + "." + sha.take(8) + name.substring(dot))
        } else {
            File(parent, name + "." + sha.take(8))
        }
        return if (suffixed.exists()) null else suffixed
    }
}

/** 一次导出的报告。 */
data class ExportReport(
    val exported: List<String>,
    val dir: File?,
    val notes: List<String>,
)

/**
 * 防丢顶条的**纯判据**（刀 2 D 段第 3 条；判据 §5.9：N 可配）。
 *
 * 「原件超过 N 天没导出就提醒」——`lastExportAt = 0`（从没导出过）且库里有件 ⇒ 立刻算超期
 * （私有目录卸载会丢，从没备份过才是最该提醒的形态）。库是空的 ⇒ 没什么可丢，不提醒。
 */
object BackupRules {

    /** 默认超期天数（UI 可传别的值——判据要求「N 可配」，所以它是参数不是常量）。 */
    const val DEFAULT_STALE_DAYS = 30

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun isStale(lastExportAt: Long, now: Long, totalCount: Int, staleDays: Int = DEFAULT_STALE_DAYS): Boolean {
        if (totalCount <= 0) return false
        if (lastExportAt <= 0L) return true
        return now - lastExportAt > staleDays * DAY_MS
    }

    /** 距上次导出多少天（从没导出过 ⇒ null，UI 要区分「从没导出」与「0 天前」）。 */
    fun daysSinceExport(lastExportAt: Long, now: Long): Long? =
        if (lastExportAt <= 0L) null else (now - lastExportAt) / DAY_MS
}
